/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.cluster;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.DashboardStatus;
import com.mirth.connect.model.DeployedChannelInfo;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.server.channel.ChannelTaskHandler;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The convergence loop: one tick, on every node, every few seconds.
 *
 * <p>Each tick heartbeats this node into the registry, reads the cluster's intent, and
 * makes the local engine match it. Nothing is pushed between nodes -- a node that was down
 * when the deploy happened converges when it comes back, which is the property that a
 * broadcast does not have and the reason the database is the control plane.
 *
 * <h2>Two rules that keep this from being dangerous</h2>
 *
 * <p><b>A channel with no intent row is never touched.</b> Intent is created by a person
 * deploying something. Until that has happened for a given channel, the cluster has no
 * opinion about it, and "no opinion" must mean "leave it alone" rather than "undeploy it".
 * Anything else would make installing this extension an act that stops channels.
 *
 * <p><b>Channel state is converged on a change of intent, never continuously.</b> If the
 * agent reconciled observed state against intent on every tick, a channel that stopped
 * itself on one node -- a listener port taken, a deploy script that threw -- would be
 * restarted in a loop, and a channel someone stopped by hand for a good reason would be
 * restarted behind them. So a state change is applied once, when the intent's sequence
 * changes, and a node that has diverged since is <em>reported</em> rather than corrected.
 */
public final class ClusterAgent {

    private static final Logger LOG = LogManager.getLogger(ClusterAgent.class);

    /**
     * Channels this node is in the middle of converging.
     *
     * <p>The belt to {@link ClusterServicePlugin}'s braces: the agent deploys with the
     * system event context, which the hook already ignores, but a future caller that
     * passes a user context through would otherwise turn one node's convergence into a new
     * instruction for everyone else.
     */
    private final Set<String> converging = ConcurrentHashMap.newKeySet();

    private final long startedAt = System.currentTimeMillis();

    private volatile String lastError = "";
    private volatile long lastTick;
    private volatile boolean seededChecked;

    private final Map<String, ClusterStore.Applied> applied = new ConcurrentHashMap<>();

    private static EngineController engine() {
        return ControllerFactory.getFactory().createEngineController();
    }

    private static ChannelController channels() {
        return ControllerFactory.getFactory().createChannelController();
    }

    boolean isConverging(String channelId) {
        return converging.contains(channelId);
    }

    long getStartedAt() {
        return startedAt;
    }

    long getLastTick() {
        return lastTick;
    }

    String getLastError() {
        return lastError;
    }

    /**
     * Records what this node has just done about a channel, so the rest of the cluster --
     * and the console -- can see it.
     *
     * <p>Called by the hook as well as by the loop: the node where a deploy was performed
     * is converged the moment it happened, and must not redeploy the channel a second time
     * when it next reads its own intent back.
     */
    void markApplied(String channelId, long deploySeq, long stateSeq, String state, String error) {
        applied.put(channelId,
            new ClusterStore.Applied(channelId, deploySeq, stateSeq, state, error));
    }

    void loadApplied(String nodeId) {
        applied.putAll(ClusterStore.applied(nodeId));
    }

    /**
     * One pass. Never throws: an exception escaping a scheduled task cancels every future
     * run, which would leave a node silently out of the cluster while still serving
     * traffic -- the worst of both.
     */
    void tick() {
        try {
            String nodeId = ClusterSettings.nodeId();
            heartbeat(nodeId);
            if (ClusterSettings.enabled()) {
                converge(nodeId);
            }
            lastTick = System.currentTimeMillis();
            lastError = "";
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            LOG.error("cluster: convergence pass failed", t);
        }
    }

    // ------------------------------------------------------------------
    // Registry
    // ------------------------------------------------------------------

    private void heartbeat(String nodeId) {
        int deployed = 0;
        try {
            Set<String> ids = engine().getDeployedIds();
            deployed = ids == null ? 0 : ids.size();
        } catch (RuntimeException e) {
            // The engine is still starting. Registering anyway is the point: a node that
            // is up but not yet deployed should be visible as exactly that.
            deployed = -1;
        }

        NodeRecord existing = ClusterStore.node(nodeId);
        long started = existing == null ? startedAt : existing.getStartedAt();
        if (started <= 0) {
            started = startedAt;
        }

        // Two engines sharing a server.id would interleave their queues, their
        // statistics and their recovery -- each would pick up messages the other is
        // mid-way through sending. Nothing in the engine notices, because from its point
        // of view they are one server. The registry does notice: someone else is
        // heartbeating this id under a different name.
        if (existing != null
            && !ClusterSettings.nodeName().equals(existing.getName())
            && existing.isLive(System.currentTimeMillis(),
                ClusterSettings.nodeStaleSeconds() * 1000L)) {
            LOG.error("cluster: server.id {} is also in use by a live node calling itself "
                + "'{}'. Two engines sharing an id interleave their queues and statistics. "
                + "Give each node its own SERVER_ID before going further.",
                nodeId, existing.getName());
        }

        NodeRecord record = new NodeRecord(
            nodeId,
            ClusterSettings.nodeName(),
            ClusterSettings.role(),
            ClusterSettings.address(),
            ControllerFactory.getFactory().createConfigurationController().getServerVersion(),
            started,
            System.currentTimeMillis(),
            deployed,
            ClusterSettings.enabled() ? "" : "convergence disabled");
        ClusterStore.saveNode(record);
    }

    // ------------------------------------------------------------------
    // Convergence
    // ------------------------------------------------------------------

    private void converge(String nodeId) {
        ClusterStore.Snapshot snapshot = ClusterStore.snapshot();
        seedOnce(nodeId, snapshot);

        long stale = ClusterSettings.nodeStaleSeconds() * 1000L;
        long now = System.currentTimeMillis();
        String singletonOwner = singletonOwner(snapshot.nodes, now, stale);

        Set<String> deployedHere = new HashSet<>();
        Set<String> ids = engine().getDeployedIds();
        if (ids != null) {
            deployedHere.addAll(ids);
        }

        Set<String> toDeploy = new LinkedHashSet<>();
        Set<String> toUndeploy = new LinkedHashSet<>();
        Map<String, ChannelIntent> stateChanges = new LinkedHashMap<>();

        for (ChannelIntent intent : snapshot.intents.values()) {
            String channelId = intent.getChannelId();
            if (converging.contains(channelId)) {
                continue;
            }
            ClusterStore.Applied local = applied.get(channelId);
            boolean mine = placementAppliesHere(intent, nodeId, singletonOwner);

            if (!mine || !intent.isDeployed()) {
                if (deployedHere.contains(channelId)) {
                    toUndeploy.add(channelId);
                }
                continue;
            }

            boolean deployedAtIntent = deployedHere.contains(channelId)
                && local != null
                && local.getDeploySeq() == intent.getDeploySeq();

            if (!deployedAtIntent) {
                toDeploy.add(channelId);
                continue;
            }

            if (ClusterSettings.convergeChannelState()
                && !intent.getLifecycle().isBlank()
                && local.getStateSeq() != intent.getStateSeq()) {
                stateChanges.put(channelId, intent);
            }
        }

        if (!toDeploy.isEmpty()) {
            deploy(toDeploy, snapshot.intents);
        }
        if (!toUndeploy.isEmpty()) {
            undeploy(toUndeploy);
        }
        for (Map.Entry<String, ChannelIntent> e : stateChanges.entrySet()) {
            applyState(e.getKey(), e.getValue());
        }

        refreshLocalState(nodeId, snapshot.intents);
        cleanupRemoved(snapshot, now, stale);
    }

    /**
     * Drops the intent row for a channel that has been deleted and that no node is holding
     * any more.
     *
     * <p>Deletion leaves an undeploy instruction behind on purpose -- it is what tells the
     * other nodes to let go of a channel whose definition has already gone from the shared
     * database. Once they have, the row is landfill, and keeping it would mean a channel id
     * reused later inherited a stale instruction. Removed only after twice the staleness
     * threshold, so a node that was merely restarting still sees the undeploy.
     */
    private void cleanupRemoved(ClusterStore.Snapshot snapshot, long now, long stale) {
        Set<String> existing;
        try {
            existing = channels().getChannelIds();
        } catch (RuntimeException e) {
            return;
        }
        if (existing == null) {
            return;
        }
        for (ChannelIntent intent : snapshot.intents.values()) {
            String channelId = intent.getChannelId();
            if (intent.isDeployed() || existing.contains(channelId)) {
                continue;
            }
            if (now - intent.getUpdatedAt() < stale * 2) {
                continue;
            }
            boolean heldSomewhere = false;
            for (Map<String, ClusterStore.Applied> perNode : snapshot.appliedByNode.values()) {
                if (perNode.containsKey(channelId)) {
                    heldSomewhere = true;
                    break;
                }
            }
            if (!heldSomewhere) {
                ClusterStore.removeIntent(channelId);
                LOG.info("cluster: forgetting intent for deleted channel {}", channelId);
            }
        }
    }

    /**
     * Whether this node is one of the ones meant to be running the channel.
     *
     * <p>A singleton with no live owner deploys <b>nowhere</b>. The alternative -- falling
     * back to "everyone" so the channel keeps running -- would turn the loss of one pod
     * into three engines polling the same directory, which is the exact failure the
     * placement exists to prevent. The console reports it instead, and loudly.
     */
    private boolean placementAppliesHere(ChannelIntent intent, String nodeId,
                                         String singletonOwner) {
        String pinned = intent.getPinnedNode();
        if (pinned != null) {
            return pinned.equals(nodeId);
        }
        if (intent.isSingleton()) {
            return nodeId.equals(singletonOwner);
        }
        return true;
    }

    /**
     * Which node runs the singleton channels: the live {@code utility} node, and if there
     * is more than one, the first by id so that every node picks the same one without
     * having to agree on anything but the registry they can all already read.
     */
    static String singletonOwner(List<NodeRecord> nodes, long now, long staleMillis) {
        List<String> candidates = new ArrayList<>();
        for (NodeRecord node : nodes) {
            if (node.isLive(now, staleMillis)
                && ClusterSettings.ROLE_UTILITY.equals(node.getRole())) {
                candidates.add(node.getNodeId());
            }
        }
        Collections.sort(candidates);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private void deploy(Set<String> channelIds, Map<String, ChannelIntent> intents) {
        converging.addAll(channelIds);
        Errors errors = new Errors();
        try {
            LOG.info("cluster: deploying {} channel(s) to match cluster intent", channelIds.size());
            // The system event context is what stops this becoming a new instruction: the
            // hook records intent only for a deploy a user asked for.
            engine().deployChannels(new LinkedHashSet<>(channelIds),
                ServerEventContext.SYSTEM_USER_EVENT_CONTEXT, errors, null);
        } catch (RuntimeException e) {
            LOG.error("cluster: deploy failed", e);
            for (String id : channelIds) {
                errors.record(id, e.getMessage());
            }
        } finally {
            converging.removeAll(channelIds);
        }

        for (String id : channelIds) {
            ChannelIntent intent = intents.get(id);
            if (intent == null) {
                continue;
            }
            String error = errors.get(id);
            markApplied(id, error == null ? intent.getDeploySeq() : 0L,
                intent.getStateSeq(), localState(id), error == null ? "" : error);
        }
    }

    private void undeploy(Set<String> channelIds) {
        converging.addAll(channelIds);
        Errors errors = new Errors();
        try {
            LOG.info("cluster: undeploying {} channel(s) to match cluster intent",
                channelIds.size());
            engine().undeployChannels(new LinkedHashSet<>(channelIds),
                ServerEventContext.SYSTEM_USER_EVENT_CONTEXT, errors);
        } catch (RuntimeException e) {
            LOG.error("cluster: undeploy failed", e);
        } finally {
            converging.removeAll(channelIds);
        }
        for (String id : channelIds) {
            applied.remove(id);
        }
    }

    private void applyState(String channelId, ChannelIntent intent) {
        Set<String> one = new LinkedHashSet<>();
        one.add(channelId);
        converging.add(channelId);
        Errors errors = new Errors();
        try {
            switch (intent.getLifecycle()) {
                case "STARTED" -> engine().startChannels(one, errors);
                case "PAUSED" -> engine().pauseChannels(one, errors);
                case "STOPPED" -> engine().stopChannels(one, errors);
                default -> {
                    return;
                }
            }
            LOG.info("cluster: set channel {} to {}", channelId, intent.getLifecycle());
        } catch (RuntimeException e) {
            errors.record(channelId, e.getMessage());
            LOG.error("cluster: could not set channel {} to {}", channelId,
                intent.getLifecycle(), e);
        } finally {
            converging.remove(channelId);
        }

        ClusterStore.Applied previous = applied.get(channelId);
        long deploySeq = previous == null ? intent.getDeploySeq() : previous.getDeploySeq();
        String error = errors.get(channelId);
        markApplied(channelId, deploySeq, intent.getStateSeq(), localState(channelId),
            error == null ? "" : error);
    }

    /**
     * Refreshes the recorded state of every channel this node has an opinion about, and
     * publishes the map.
     *
     * <p>Written every tick rather than only on change, because the state it carries -- a
     * channel that has since stopped itself -- changes without this node doing anything.
     * It is one property per node, so this is one write, not one per channel.
     */
    private void refreshLocalState(String nodeId, Map<String, ChannelIntent> intents) {
        Map<String, DeployedState> states = localStates();
        for (Map.Entry<String, ClusterStore.Applied> e : applied.entrySet()) {
            ClusterStore.Applied a = e.getValue();
            DeployedState state = states.get(e.getKey());
            String name = state == null ? "" : state.name();
            if (!name.equals(a.getState())) {
                applied.put(e.getKey(), new ClusterStore.Applied(
                    a.getChannelId(), a.getDeploySeq(), a.getStateSeq(), name, a.getError()));
            }
        }
        // Drop channels the cluster no longer has intent for, so a removed channel does
        // not sit in every node's applied map for ever.
        applied.keySet().removeIf(id -> !intents.containsKey(id));
        ClusterStore.saveApplied(nodeId, new LinkedHashMap<>(applied));
    }

    private Map<String, DeployedState> localStates() {
        Map<String, DeployedState> out = new LinkedHashMap<>();
        try {
            List<DashboardStatus> statuses = engine().getChannelStatusList();
            if (statuses != null) {
                for (DashboardStatus status : statuses) {
                    out.put(status.getChannelId(), status.getState());
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("cluster: could not read local channel states", e);
        }
        return out;
    }

    private String localState(String channelId) {
        DeployedState state = localStates().get(channelId);
        return state == null ? "" : state.name();
    }

    // ------------------------------------------------------------------
    // Adoption
    // ------------------------------------------------------------------

    /**
     * Writes the local engine's deployed set into an empty cluster intent, once.
     *
     * <p>The adoption path. Switching this on in front of an engine that is already running
     * channels must not mean the cluster decides nothing should be deployed; the first node
     * to converge on an empty intent takes what it is running as the starting position.
     * Guarded by a marker so it happens once per cluster, and skipped entirely when intent
     * already exists -- a new pod joining an established cluster must never seed anything.
     */
    private void seedOnce(String nodeId, ClusterStore.Snapshot snapshot) {
        if (seededChecked) {
            return;
        }
        seededChecked = true;
        if (!snapshot.intents.isEmpty()) {
            return;
        }
        String marker = ControllerFactory.getFactory().createConfigurationController()
            .getProperty(ClusterSettings.GROUP, ClusterSettings.K_SEEDED);
        if (marker != null && !marker.isBlank()) {
            return;
        }
        int seeded = seedFromLocal(nodeId, "startup");
        ControllerFactory.getFactory().createConfigurationController()
            .saveProperty(ClusterSettings.GROUP, ClusterSettings.K_SEEDED,
                Long.toString(System.currentTimeMillis()));
        if (seeded > 0) {
            LOG.info("cluster: seeded intent from {} channel(s) already deployed here", seeded);
        }
    }

    /** Takes this node's deployed set as the cluster's intent. Returns how many. */
    int seedFromLocal(String nodeId, String by) {
        Set<String> ids = engine().getDeployedIds();
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        Map<String, DeployedState> states = localStates();
        int count = 0;
        for (String id : ids) {
            Channel channel = channels().getChannelById(id);
            if (channel == null) {
                continue;
            }
            DeployedChannelInfo info = channels().getDeployedChannelInfoById(id);
            int revision = info == null ? channel.getRevision() : info.getDeployedRevision();
            DeployedState state = states.get(id);
            ChannelIntent intent = new ChannelIntent(id, channel.getName(),
                ChannelIntent.DEPLOYED, revision, now, ChannelIntent.PLACEMENT_ALL,
                state == null ? "" : state.name(), now, now, by, nodeId, "seeded");
            ClusterStore.saveIntent(intent);
            markApplied(id, now, now, state == null ? "" : state.name(), "");
            count++;
        }
        ClusterStore.saveApplied(nodeId, new LinkedHashMap<>(applied));
        return count;
    }

    // ------------------------------------------------------------------

    /** Collects per-channel task failures so a partial deploy reports which part failed. */
    private static final class Errors extends ChannelTaskHandler {

        private final Map<String, String> errors = new ConcurrentHashMap<>();

        void record(String channelId, String message) {
            errors.put(channelId, message == null ? "failed" : message);
        }

        String get(String channelId) {
            return errors.get(channelId);
        }

        @Override
        public void taskErrored(String channelId, Integer metaDataId, Exception e) {
            record(channelId, e == null ? "failed" : e.getMessage());
        }
    }
}
