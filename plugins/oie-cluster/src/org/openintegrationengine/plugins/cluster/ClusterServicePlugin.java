/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.cluster;

import com.mirth.connect.model.ExtensionPermission;
import com.mirth.connect.plugins.ServicePlugin;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle for the cluster extension: it owns the agent, and the scheduled pass that
 * makes this engine match what the cluster intends.
 *
 * <p>The hook that records intent is deliberately a separate class -- see
 * {@link ClusterChannelPlugin} for why implementing both interfaces here would run this
 * lifecycle twice.
 */
public class ClusterServicePlugin implements ServicePlugin {

    public static final String PLUGIN_POINT_NAME = "Cluster";

    private static final Logger LOG = LogManager.getLogger(ClusterServicePlugin.class);

    /**
     * The first pass waits for the engine to finish starting. Converging while channels are
     * still deploying would read a half-populated deployed set and undeploy from it.
     */
    private static final int STARTUP_DELAY_SECONDS = 30;

    private static volatile ClusterAgent agent;
    private static volatile ClusterService service;

    /**
     * Set as soon as this engine begins stopping.
     *
     * <p>Belt to the braces in {@link ClusterChannelPlugin#isInstruction}: the engine
     * undeploys every channel on its way down, and if any of those calls ever arrived with
     * a real user id behind it, one pod stopping would undeploy the channel on every other
     * pod. Whether plugins stop before or after the channels do is the engine's business
     * and could change; this makes the answer not matter.
     */
    private static volatile boolean shuttingDown;

    private ScheduledExecutorService scheduler;

    public static ClusterService service() {
        return service;
    }

    static ClusterAgent agent() {
        return agent;
    }

    static boolean isShuttingDown() {
        return shuttingDown;
    }

    @Override
    public void init(Properties properties) {
        shuttingDown = false;
        agent = new ClusterAgent();
        service = new ClusterService(agent);
    }

    @Override
    public void start() {
        ClusterAgent current = agent;
        if (current == null) {
            return;
        }
        // Idempotent on purpose. The extension loader's list is built by testing each
        // loaded class against every plugin interface, so a class that satisfies two of
        // them is started twice -- and two schedulers means two agents racing to write
        // the same registry row. This class implements one interface and should never be
        // started twice; the guard is here because the failure is silent and the cost is
        // one branch.
        if (scheduler != null) {
            return;
        }
        current.loadApplied(ClusterSettings.nodeId());

        int interval = ClusterSettings.convergeIntervalSeconds();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oie-cluster");
            // Daemon: a convergence pass must never hold up engine shutdown.
            t.setDaemon(true);
            return t;
        });
        // Fixed delay rather than fixed rate: a pass that deploys twenty channels can
        // outlast the interval, and queueing another behind it would turn one slow deploy
        // into a growing backlog of identical work.
        scheduler.scheduleWithFixedDelay(current::tick, STARTUP_DELAY_SECONDS, interval,
            TimeUnit.SECONDS);

        if (ClusterSettings.enabled()) {
            LOG.info("cluster '{}': node {} ({}) converging every {}s, first pass in {}s",
                ClusterSettings.clusterName(), ClusterSettings.nodeName(),
                ClusterSettings.role(), interval, STARTUP_DELAY_SECONDS);
        } else {
            LOG.info("cluster: installed but not enabled (OIE_CLUSTER_ENABLED is not true); "
                + "this node registers itself and changes nothing");
        }
    }

    @Override
    public void stop() {
        shuttingDown = true;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        service = null;
        agent = null;
    }

    @Override
    public void update(Properties properties) {
        // Intent and settings are read from the database on each pass, so there is nothing
        // to reload here. The interval is the exception: the scheduler fixes its period
        // when it starts, which the settings response says out loud.
    }

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT_NAME;
    }

    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] {
            new ExtensionPermission(PLUGIN_POINT_NAME, "Manage the cluster",
                "Allows viewing cluster state, changing where channels run, and resolving "
                    + "queues left by a node that is gone.",
                new String[] {
                    "clusterStatus", "clusterDashboard", "clusterOrphans",
                    "clusterSetPlacement", "clusterSetState", "clusterRedeploy",
                    "clusterSeed", "clusterForgetNode", "clusterResolveOrphans",
                    "clusterSetSettings",
                },
                new String[] {})
        };
    }

    @Override
    public Map<String, Object> getObjectsForSwaggerExamples() {
        return Map.of();
    }
}
