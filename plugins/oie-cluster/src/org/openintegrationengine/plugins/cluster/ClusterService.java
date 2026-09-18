/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.cluster;

import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.channel.Statistics;
import com.mirth.connect.model.Channel;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ControllerFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the console and the deployment pipeline ask this node about the cluster.
 *
 * <p>Every view here is built from the database -- the node registry, the intent rows, each
 * node's applied map and the per-server statistics rows Donkey already keeps. None of it
 * calls another node. That is what makes the cluster view correct while a node is down
 * (the node is reported as down, with what it was last known to be running), and it is why
 * there is no peer credential anywhere in this extension.
 *
 * <p>Everything is returned as a map of scalars plus lists of tab-separated rows, which is
 * the one shape that survives the engine's XStream serialisation into the console intact.
 * {@link Tsv} has the detail.
 */
public final class ClusterService {

    private static final Logger LOG = LogManager.getLogger(ClusterService.class);

    private final ClusterAgent agent;

    ClusterService(ClusterAgent agent) {
        this.agent = agent;
    }

    private static ChannelController channels() {
        return ControllerFactory.getFactory().createChannelController();
    }

    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        return out;
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------

    /**
     * The whole cluster in one response: nodes, intent, and each node's progress.
     *
     * <p>One response rather than three endpoints because they are read together and must
     * agree with each other -- a channel reported as converged on a node the node list
     * shows as dead is not a view, it is a puzzle.
     */
    public Map<String, Object> status() {
        ClusterStore.Snapshot snapshot = ClusterStore.snapshot();
        long now = now();
        long stale = ClusterSettings.nodeStaleSeconds() * 1000L;
        String thisNode = ClusterSettings.nodeId();
        String owner = ClusterAgent.singletonOwner(snapshot.nodes, now, stale);

        Set<String> liveIds = new LinkedHashSet<>();
        List<String> nodeRows = new ArrayList<>();
        for (NodeRecord node : snapshot.nodes) {
            boolean live = node.isLive(now, stale);
            if (live) {
                liveIds.add(node.getNodeId());
            }
            nodeRows.add(Tsv.join(node.getNodeId(), node.getName(), node.getRole(),
                node.getAddress(), node.getVersion(), node.getStartedAt(), node.getLastSeen(),
                live ? "1" : "0", node.getDeployedCount(),
                node.getNodeId().equals(thisNode) ? "1" : "0",
                node.getNodeId().equals(owner) ? "1" : "0", node.getNote()));
        }

        Map<String, String> names = channelNames();
        List<String> channelRows = new ArrayList<>();
        List<String> placementRows = new ArrayList<>();
        int converged = 0;
        int pending = 0;
        int failed = 0;
        int unownedSingletons = 0;

        for (ChannelIntent intent : snapshot.intents.values()) {
            String channelId = intent.getChannelId();
            Set<String> expected = expectedNodes(intent, liveIds, owner);
            int done = 0;
            int errors = 0;
            List<String> states = new ArrayList<>();

            for (String nodeId : liveIds) {
                ClusterStore.Applied applied =
                    snapshot.appliedByNode.getOrDefault(nodeId, Map.of()).get(channelId);
                boolean wanted = expected.contains(nodeId);
                boolean here = applied != null && applied.getDeploySeq() == intent.getDeploySeq();
                String error = applied == null ? "" : applied.getError();
                if (wanted && here) {
                    done++;
                }
                if (error != null && !error.isBlank()) {
                    errors++;
                }
                if (wanted) {
                    states.add(applied == null ? "" : applied.getState());
                }
                placementRows.add(Tsv.join(channelId, nodeId,
                    wanted ? "1" : "0", here ? "1" : "0",
                    applied == null ? "" : applied.getState(),
                    error == null ? "" : error));
            }

            if (intent.isSingleton() && owner == null) {
                unownedSingletons++;
            }
            boolean complete = intent.isDeployed()
                ? done == expected.size() && !expected.isEmpty()
                : done == 0;
            if (errors > 0) {
                failed++;
            } else if (complete) {
                converged++;
            } else {
                pending++;
            }

            channelRows.add(Tsv.join(channelId,
                names.getOrDefault(channelId, intent.getName()),
                intent.getDesiredState(), intent.getPlacement(), intent.getRevision(),
                intent.getDeploySeq(), intent.getLifecycle(), intent.getUpdatedAt(),
                intent.getUpdatedBy(), intent.getOriginNode(),
                done, expected.size(), errors,
                String.join(",", new LinkedHashSet<>(states))));
        }

        Map<String, Object> out = ok();
        out.put("clusterName", ClusterSettings.clusterName());
        out.put("enabled", ClusterSettings.enabled());
        out.put("nodeId", thisNode);
        out.put("nodeName", ClusterSettings.nodeName());
        out.put("role", ClusterSettings.role());
        out.put("singletonOwner", owner == null ? "" : owner);
        out.put("liveNodes", liveIds.size());
        out.put("knownNodes", snapshot.nodes.size());
        out.put("channels", snapshot.intents.size());
        out.put("converged", converged);
        out.put("pending", pending);
        out.put("failed", failed);
        out.put("unownedSingletons", unownedSingletons);
        out.put("convergeIntervalSeconds", ClusterSettings.convergeIntervalSeconds());
        out.put("nodeStaleSeconds", ClusterSettings.nodeStaleSeconds());
        out.put("convergeChannelState", ClusterSettings.convergeChannelState());
        out.put("lastTick", agent.getLastTick());
        out.put("lastError", agent.getLastError());
        out.put("nodeRows", nodeRows);
        out.put("channelRows", channelRows);
        out.put("placementRows", placementRows);
        out.put("unmanagedRows", unmanaged(snapshot.intents.keySet(), names));
        return out;
    }

    /**
     * Channels this engine is running that the cluster has no intent for.
     *
     * <p>Reported, never corrected. They are the channels a convergence pass deliberately
     * does not touch, and the only honest thing to do with a list of things being ignored
     * is show it to somebody.
     */
    private List<String> unmanaged(Set<String> known, Map<String, String> names) {
        List<String> out = new ArrayList<>();
        try {
            Set<String> deployed = ControllerFactory.getFactory()
                .createEngineController().getDeployedIds();
            if (deployed == null) {
                return out;
            }
            for (String id : deployed) {
                if (!known.contains(id)) {
                    out.add(Tsv.join(id, names.getOrDefault(id, id)));
                }
            }
        } catch (RuntimeException e) {
            LOG.debug("cluster: could not list unmanaged channels", e);
        }
        return out;
    }

    /**
     * Message counts per channel, summed across every node that has any.
     *
     * <p>Read from {@code D_MS}, which Donkey keys by {@code (METADATA_ID, SERVER_ID)} --
     * so the sum across server ids is the cluster's throughput, and the per-node breakdown
     * comes from the same read. The engine's own dashboard cannot show either: it reports
     * the statistics of whichever node answered the request.
     */
    public Map<String, Object> dashboard() {
        ClusterStore.Snapshot snapshot = ClusterStore.snapshot();
        long now = now();
        long stale = ClusterSettings.nodeStaleSeconds() * 1000L;
        Map<String, String> names = channelNames();

        Map<String, long[]> totals = new LinkedHashMap<>();
        List<String> byNode = new ArrayList<>();

        for (NodeRecord node : snapshot.nodes) {
            Statistics stats;
            try {
                stats = channels().getStatisticsFromStorage(node.getNodeId());
            } catch (RuntimeException e) {
                LOG.debug("cluster: no statistics for node {}", node.getNodeId(), e);
                continue;
            }
            if (stats == null || stats.getStats() == null) {
                continue;
            }
            for (String channelId : stats.getStats().keySet()) {
                long[] counts = channelCounts(stats, channelId);
                if (counts == null) {
                    continue;
                }
                long[] total = totals.computeIfAbsent(channelId, k -> new long[4]);
                for (int i = 0; i < 4; i++) {
                    total[i] += counts[i];
                }
                byNode.add(Tsv.join(channelId, node.getNodeId(), node.getName(),
                    node.isLive(now, stale) ? "1" : "0",
                    counts[0], counts[1], counts[2], counts[3]));
            }
        }

        List<String> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> e : totals.entrySet()) {
            long[] c = e.getValue();
            rows.add(Tsv.join(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()),
                c[0], c[1], c[2], c[3]));
        }

        Map<String, Object> out = ok();
        out.put("statRows", rows);
        out.put("statNodeRows", byNode);
        return out;
    }

    /** received, filtered, sent, error for one channel, from the channel-level row. */
    private static long[] channelCounts(Statistics stats, String channelId) {
        Map<Integer, Map<Status, Long>> perConnector = stats.getChannelStats(channelId);
        if (perConnector == null) {
            return null;
        }
        // Donkey stores the channel total with a null metadata id (D_MS.METADATA_ID IS
        // NULL); metadata 0 is the source connector. Prefer the total, fall back to the
        // source, and never add the two together -- that would double-count every message.
        Map<Status, Long> row = perConnector.get(null);
        if (row == null) {
            row = perConnector.get(0);
        }
        if (row == null) {
            return null;
        }
        return new long[] {
            value(row, Status.RECEIVED), value(row, Status.FILTERED),
            value(row, Status.SENT), value(row, Status.ERROR),
        };
    }

    private static long value(Map<Status, Long> row, Status status) {
        Long v = row.get(status);
        return v == null ? 0L : v;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Changes where a channel runs. Takes effect on the next convergence pass. */
    public Map<String, Object> setPlacement(String channelId, String placement, String by) {
        String normalised = normalisePlacement(placement);
        ChannelIntent intent = requireIntent(channelId);
        ClusterStore.saveIntent(intent.withPlacement(normalised, now(), by,
            ClusterSettings.nodeId()));
        LOG.info("cluster: placement for channel {} set to {} by {}", channelId, normalised, by);
        Map<String, Object> out = ok();
        out.put("placement", normalised);
        return out;
    }

    private static String normalisePlacement(String placement) {
        if (placement == null || placement.isBlank()) {
            throw new IllegalArgumentException("a placement is required");
        }
        String p = placement.trim();
        if (ChannelIntent.PLACEMENT_ALL.equalsIgnoreCase(p)) {
            return ChannelIntent.PLACEMENT_ALL;
        }
        if (ChannelIntent.PLACEMENT_SINGLETON.equalsIgnoreCase(p)) {
            return ChannelIntent.PLACEMENT_SINGLETON;
        }
        if (p.toUpperCase().startsWith(ChannelIntent.PLACEMENT_PINNED_PREFIX)) {
            String nodeId = p.substring(ChannelIntent.PLACEMENT_PINNED_PREFIX.length()).trim();
            if (nodeId.isBlank()) {
                throw new IllegalArgumentException("PINNED needs a node id");
            }
            if (ClusterStore.node(nodeId) == null) {
                throw new IllegalArgumentException("no node has ever registered as " + nodeId);
            }
            return ChannelIntent.PLACEMENT_PINNED_PREFIX + nodeId;
        }
        throw new IllegalArgumentException("placement must be ALL, SINGLETON or PINNED:<nodeId>");
    }

    /**
     * Makes one channel state the cluster's state.
     *
     * <p>The answer to a node having diverged -- someone stopped a channel on one engine
     * and the console is showing two states side by side. Rather than guessing which one
     * was meant, the view offers this, and whoever is looking decides.
     */
    public Map<String, Object> setLifecycle(String channelId, String state, String by) {
        String wanted = state == null ? "" : state.trim().toUpperCase();
        if (!Arrays.asList("STARTED", "PAUSED", "STOPPED").contains(wanted)) {
            throw new IllegalArgumentException("state must be STARTED, PAUSED or STOPPED");
        }
        ChannelIntent intent = requireIntent(channelId);
        ClusterStore.saveIntent(intent.withLifecycle(wanted, now(), by,
            ClusterSettings.nodeId()));
        LOG.info("cluster: channel {} set to {} across the cluster by {}",
            channelId, wanted, by);
        Map<String, Object> out = ok();
        out.put("state", wanted);
        return out;
    }

    /** Asks every node to deploy these channels again, whether or not they have changed. */
    public Map<String, Object> redeploy(Collection<String> channelIds, String by) {
        if (channelIds == null || channelIds.isEmpty()) {
            throw new IllegalArgumentException("at least one channel id is required");
        }
        long seq = now();
        int count = 0;
        for (String channelId : channelIds) {
            ChannelIntent intent = ClusterStore.intent(channelId);
            if (intent == null) {
                continue;
            }
            ClusterStore.saveIntent(intent.withRedeploy(seq, by, ClusterSettings.nodeId()));
            count++;
        }
        LOG.info("cluster: redeploy requested for {} channel(s) by {}", count, by);
        Map<String, Object> out = ok();
        out.put("channels", count);
        return out;
    }

    /** Takes this node's deployed set as the cluster's intent. */
    public Map<String, Object> seed(String by) {
        int count = agent.seedFromLocal(ClusterSettings.nodeId(), by);
        LOG.info("cluster: intent seeded from this node's {} deployed channel(s) by {}",
            count, by);
        Map<String, Object> out = ok();
        out.put("channels", count);
        return out;
    }

    /** Removes a node from the registry. Its queued messages are untouched. */
    public Map<String, Object> forgetNode(String nodeId, String by) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("a node id is required");
        }
        if (nodeId.equals(ClusterSettings.nodeId())) {
            throw new IllegalArgumentException("a node cannot forget itself");
        }
        NodeRecord node = ClusterStore.node(nodeId);
        if (node != null && node.isLive(now(), ClusterSettings.nodeStaleSeconds() * 1000L)) {
            throw new IllegalArgumentException(
                "node " + node.getName() + " is still heartbeating; stop it first");
        }
        ClusterStore.removeNode(nodeId);
        LOG.warn("cluster: node {} removed from the registry by {}", nodeId, by);
        return ok();
    }

    // ------------------------------------------------------------------
    // Orphaned queues
    // ------------------------------------------------------------------

    /**
     * Queued and unfinished work belonging to engines that are not here.
     *
     * <p>Named per channel with its own count, never summarised into one number: "412
     * messages stranded" is not a thing anyone can make a decision about, and the decision
     * -- leave, reassign, neutralise -- is the whole point of the endpoint.
     */
    public Map<String, Object> orphans() {
        long stale = ClusterSettings.nodeStaleSeconds() * 1000L;
        long now = now();
        Set<String> live = new HashSet<>();
        for (NodeRecord node : ClusterStore.nodes()) {
            if (node.isLive(now, stale)) {
                live.add(node.getNodeId());
            }
        }

        List<OrphanStore.Orphan> found = OrphanStore.scan(live, channelNames());
        List<String> rows = new ArrayList<>();
        for (OrphanStore.Orphan orphan : found) {
            NodeRecord node = ClusterStore.node(orphan.getServerId());
            rows.add(Tsv.join(orphan.getServerId(),
                node == null ? "" : node.getName(),
                node == null ? "0" : Long.toString(node.getLastSeen()),
                orphan.getChannelId(), orphan.getChannelName(),
                orphan.getQueued(), orphan.getUnprocessed()));
        }

        List<String> liveRows = new ArrayList<>();
        for (String nodeId : live) {
            NodeRecord node = ClusterStore.node(nodeId);
            liveRows.add(Tsv.join(nodeId, node == null ? nodeId : node.getName()));
        }

        Map<String, Object> out = ok();
        out.put("orphanRows", rows);
        out.put("liveNodeRows", liveRows);
        return out;
    }

    /**
     * Acts on one departed server's work on one channel.
     *
     * <p>One channel and one server per call, deliberately. A single button that adopted
     * everything would be one click between an operator and a decision they cannot reverse,
     * and the counts differ per channel, so a single answer for all of them is rarely the
     * right one.
     */
    public Map<String, Object> resolveOrphans(String action, String serverId, String channelId,
                                              String targetNodeId, String by) {
        if (serverId == null || serverId.isBlank() || channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("a server id and a channel id are required");
        }
        long stale = ClusterSettings.nodeStaleSeconds() * 1000L;
        NodeRecord source = ClusterStore.node(serverId);
        if (source != null && source.isLive(now(), stale)) {
            throw new IllegalArgumentException(
                "node " + source.getName() + " is live; its queue is not orphaned");
        }

        Map<String, Object> out = ok();
        switch (action == null ? "" : action.toLowerCase()) {
            case "adopt" -> {
                if (targetNodeId == null || targetNodeId.isBlank()) {
                    throw new IllegalArgumentException("a target node is required");
                }
                NodeRecord target = ClusterStore.node(targetNodeId);
                if (target == null || !target.isLive(now(), stale)) {
                    throw new IllegalArgumentException("the target node is not live");
                }
                long moved = OrphanStore.adopt(channelId, serverId, targetNodeId);
                LOG.warn("cluster: {} message(s) on channel {} moved from {} to {} by {}",
                    moved, channelId, serverId, targetNodeId, by);
                out.put("messages", moved);
                out.put("redeployRequired", Boolean.TRUE);
            }
            case "discard" -> {
                long affected = OrphanStore.discard(channelId, serverId);
                LOG.warn("cluster: {} queued message(s) on channel {} from {} marked as "
                    + "errored by {}", affected, channelId, serverId, by);
                out.put("messages", affected);
                out.put("redeployRequired", Boolean.FALSE);
            }
            default -> throw new IllegalArgumentException("action must be adopt or discard");
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    public Map<String, Object> saveSettings(Map<String, String> settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings are required");
        }
        if (settings.containsKey("convergeIntervalSeconds")) {
            ClusterSettings.setConvergeIntervalSeconds(
                Tsv.parseInt(settings.get("convergeIntervalSeconds"), 5));
        }
        if (settings.containsKey("nodeStaleSeconds")) {
            ClusterSettings.setNodeStaleSeconds(
                Tsv.parseInt(settings.get("nodeStaleSeconds"), 60));
        }
        if (settings.containsKey("convergeChannelState")) {
            ClusterSettings.setConvergeChannelState(
                !"false".equalsIgnoreCase(settings.get("convergeChannelState")));
        }
        Map<String, Object> out = ok();
        // The scheduler fixes its period when it starts, exactly as the volume monitor's
        // does, so say so rather than letting a saved value quietly not apply.
        out.put("note", "The convergence interval applies on each node's next restart.");
        return out;
    }

    // ------------------------------------------------------------------

    private static ChannelIntent requireIntent(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("a channel id is required");
        }
        ChannelIntent intent = ClusterStore.intent(channelId);
        if (intent == null) {
            throw new IllegalArgumentException(
                "the cluster has no intent for that channel; deploy it once first");
        }
        return intent;
    }

    /** Which live nodes are meant to be running a channel, given its placement. */
    private static Set<String> expectedNodes(ChannelIntent intent, Set<String> liveIds,
                                             String singletonOwner) {
        if (!intent.isDeployed()) {
            return Set.of();
        }
        String pinned = intent.getPinnedNode();
        if (pinned != null) {
            return liveIds.contains(pinned) ? Set.of(pinned) : Set.of();
        }
        if (intent.isSingleton()) {
            return singletonOwner == null ? Set.of() : Set.of(singletonOwner);
        }
        return liveIds;
    }

    private static Map<String, String> channelNames() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (Channel channel : channels().getChannels(null)) {
                out.put(channel.getId(), channel.getName());
            }
        } catch (RuntimeException e) {
            LOG.debug("cluster: could not read channel names", e);
        }
        return out;
    }
}
