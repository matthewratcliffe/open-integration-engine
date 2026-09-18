/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.cluster;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.plugins.ChannelPlugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The hook that turns a deploy into cluster intent.
 *
 * <p>A separate class from {@link ClusterServicePlugin}, and it has to be.
 * {@code DefaultExtensionController} tests each loaded server class against every plugin
 * interface in turn and adds it to its list once per interface it implements, while
 * {@code startPlugins()} iterates that list and calls {@code start()} on each entry. So a
 * single class implementing both {@code ServicePlugin} and {@code ChannelPlugin} has its
 * whole lifecycle run twice -- two schedulers, two agents, two heartbeats racing to insert
 * the same registry row. Found by running it.
 *
 * <h2>The rule that decides whether a deploy becomes cluster intent</h2>
 *
 * <p>{@code DonkeyEngineController$DeployTask} calls {@link #deploy(Channel,
 * ServerEventContext)} for every deploy there is -- the console, the Swing Administrator,
 * the REST API, a CI push, another extension, the engine's own startup deploy, and the
 * undeploy of everything during shutdown. Most of those must not become an instruction to
 * the rest of the cluster, and the last one emphatically must not: an engine stopping
 * would otherwise undeploy every channel on every other engine.
 *
 * <p>So intent is recorded only when the event context carries a <em>real</em> user id.
 * The engine passes {@code ServerEventContext.SYSTEM_USER_EVENT_CONTEXT} for startup
 * deploy and for shutdown, and the convergence loop deliberately uses the same context for
 * its own work -- but that context's user id is {@code 0} rather than null, which is the
 * trap this class exists to avoid falling into twice. See
 * {@link #isInstruction(ServerEventContext, String)}.
 */
public class ClusterChannelPlugin implements ChannelPlugin {

    public static final String PLUGIN_POINT_NAME = "Cluster Deploy Hook";

    private static final Logger LOG = LogManager.getLogger(ClusterChannelPlugin.class);

    @Override
    public void deploy(Channel channel, ServerEventContext context) {
        if (channel == null || !isInstruction(context, channel.getId())) {
            return;
        }
        try {
            long seq = System.currentTimeMillis();
            String by = describe(context);
            String nodeId = ClusterSettings.nodeId();
            ChannelIntent existing = ClusterStore.intent(channel.getId());
            Integer revision = channel.getRevision();
            ChannelIntent intent = existing == null
                ? new ChannelIntent(channel.getId(), channel.getName(), ChannelIntent.DEPLOYED,
                    revision == null ? 0 : revision, seq,
                    ChannelIntent.PLACEMENT_ALL, "", seq, seq, by, nodeId, "")
                : existing.asDeployed(channel.getName(),
                    revision == null ? existing.getRevision() : revision, seq, by, nodeId);
            ClusterStore.saveIntent(intent);

            // This node is converged the moment the deploy it just performed finishes, so
            // record that here rather than letting the next pass see this node's own
            // instruction as outstanding work and deploy the channel a second time.
            ClusterAgent agent = ClusterServicePlugin.agent();
            if (agent != null) {
                agent.markApplied(channel.getId(), seq, intent.getStateSeq(),
                    DeployedState.STARTED.name(), "");
            }
            LOG.info("cluster: {} deployed here by {}; every node will follow",
                channel.getName(), by);
        } catch (RuntimeException e) {
            // A failure to record intent must not fail the deploy that has already
            // happened. The channel is running here; the cluster view will show it as
            // unmanaged, which is visible and recoverable.
            LOG.error("cluster: could not record deploy intent for {}", channel.getId(), e);
        }
    }

    @Override
    public void undeploy(String channelId, ServerEventContext context) {
        if (!isInstruction(context, channelId)) {
            return;
        }
        try {
            ChannelIntent existing = ClusterStore.intent(channelId);
            if (existing == null) {
                return;
            }
            long seq = System.currentTimeMillis();
            String by = describe(context);
            ClusterStore.saveIntent(existing.asUndeployed(seq, by, ClusterSettings.nodeId()));
            ClusterAgent agent = ClusterServicePlugin.agent();
            if (agent != null) {
                agent.markApplied(channelId, seq, seq, DeployedState.UNDEPLOYED.name(), "");
            }
            LOG.info("cluster: channel {} undeployed here by {}; every node will follow",
                channelId, by);
        } catch (RuntimeException e) {
            LOG.error("cluster: could not record undeploy intent for {}", channelId, e);
        }
    }

    @Override
    public void remove(Channel channel, ServerEventContext context) {
        if (channel == null || !ClusterSettings.enabled()
            || ClusterServicePlugin.isShuttingDown()) {
            return;
        }
        try {
            ChannelIntent existing = ClusterStore.intent(channel.getId());
            if (existing == null) {
                return;
            }
            // Deleting a channel is an instruction whoever it came from: the channel row
            // is gone from the shared database, so a node still running it is running
            // something that no longer exists. The intent row is left behind deliberately
            // and cleaned up by the agent once every node has let go of it.
            long seq = System.currentTimeMillis();
            ClusterStore.saveIntent(existing.asUndeployed(seq, describe(context),
                ClusterSettings.nodeId()));
        } catch (RuntimeException e) {
            LOG.error("cluster: could not record removal of {}", channel.getId(), e);
        }
    }

    @Override
    public void save(Channel channel, ServerEventContext context) {
        // A save is not a deploy. Mirth's whole workflow depends on being able to edit a
        // channel without the running one changing, and propagating a save as an
        // instruction would take that away from every cluster.
    }

    @Override
    public void deploy(ServerEventContext context) {
        // The batch-level callbacks carry no channel, so there is nothing to record: the
        // per-channel calls above cover everything.
    }

    @Override
    public void undeploy(ServerEventContext context) {
        // Deliberately empty. This one also fires when the engine is shutting down.
    }

    @Override
    public void start() {
        // No lifecycle of its own: the agent belongs to the service plugin.
    }

    @Override
    public void stop() {
    }

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT_NAME;
    }

    /**
     * Whether a hook call is a person's instruction to the cluster rather than the engine's
     * own housekeeping or this node's own convergence.
     *
     * <p><b>The system context's user id is 0, not null.</b>
     * {@code ServerEventContext.SYSTEM_USER_EVENT_CONTEXT} is built as
     * {@code new ServerEventContext(Integer.valueOf(0))}, so a null check alone lets every
     * system deploy and undeploy through. That is not a subtle bug: the engine undeploys
     * every channel as it shuts down, so one pod being stopped recorded "undeploy this
     * everywhere" and the rest of the cluster obediently did. Found by stopping a node and
     * watching the other two undeploy the channel.
     *
     * <p>Real user ids start at 1 -- the admin account is user 1 -- so an instruction is a
     * context with a positive user id, and nothing else.
     */
    private static boolean isInstruction(ServerEventContext context, String channelId) {
        if (!ClusterSettings.enabled() || ClusterServicePlugin.isShuttingDown()) {
            return false;
        }
        Integer userId = context == null ? null : context.getUserId();
        if (userId == null || userId <= 0) {
            return false;
        }
        ClusterAgent agent = ClusterServicePlugin.agent();
        return agent == null || !agent.isConverging(channelId);
    }

    private static String describe(ServerEventContext context) {
        Integer userId = context == null ? null : context.getUserId();
        return userId == null ? "system" : "user " + userId;
    }
}
