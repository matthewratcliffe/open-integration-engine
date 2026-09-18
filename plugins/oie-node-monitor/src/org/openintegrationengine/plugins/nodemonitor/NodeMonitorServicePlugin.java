/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.nodemonitor;

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
 * Lifecycle for the node monitor.
 *
 * <p>Sampling starts with the server and runs whether or not anyone has the page open. That
 * is the point of it: a view that only collects while someone is watching cannot answer
 * "what was this node doing before it stopped", which is the question people actually
 * arrive with.
 *
 * <p>One plugin interface only. A class that satisfies two of them is added to the
 * extension controller's list once per interface and has its whole lifecycle run twice --
 * two schedulers, two samplers, two writers for one node's row. (Learned from the cluster
 * extension, where it cost an afternoon.)
 */
public class NodeMonitorServicePlugin implements ServicePlugin {

    public static final String PLUGIN_POINT_NAME = "Node Monitor";

    private static final Logger LOG = LogManager.getLogger(NodeMonitorServicePlugin.class);

    /**
     * The first sample waits for the engine to finish starting. Sampling at second zero
     * records a node with no channels deployed and a CPU figure the JVM has not had two
     * readings to compute, which is a misleading first point on every graph.
     */
    private static final int STARTUP_DELAY_SECONDS = 45;

    private static volatile NodeMonitorAgent agent;
    private static volatile NodeMonitorService service;

    private ScheduledExecutorService scheduler;

    public static NodeMonitorService service() {
        return service;
    }

    @Override
    public void init(Properties properties) {
        agent = new NodeMonitorAgent();
        service = new NodeMonitorService(agent);
    }

    @Override
    public void start() {
        NodeMonitorAgent current = agent;
        if (current == null || scheduler != null) {
            return;
        }
        int interval = NodeMonitorSettings.sampleIntervalSeconds();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oie-node-monitor");
            // Daemon so a sample in flight cannot hold up engine shutdown.
            t.setDaemon(true);
            return t;
        });
        // Fixed delay rather than fixed rate: if a sample ever takes longer than the
        // interval, queueing another behind it turns one slow pass into a backlog.
        scheduler.scheduleWithFixedDelay(current::sample, STARTUP_DELAY_SECONDS, interval,
            TimeUnit.SECONDS);

        LOG.info("node monitor: {} sampling every {}s, keeping {} samples "
                + "(first sample in {}s)",
            NodeMonitorSettings.nodeName(), interval, NodeMonitorSettings.historySamples(),
            STARTUP_DELAY_SECONDS);
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        service = null;
        agent = null;
    }

    @Override
    public void update(Properties properties) {
        // Thresholds, paths and history length are read on each pass, so there is nothing
        // to reload. The interval is the exception -- the scheduler fixes its period when
        // it starts -- which the settings response says out loud.
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
            new ExtensionPermission(PLUGIN_POINT_NAME, "View node status",
                "Allows viewing the health, resource use and throughput of every engine.",
                new String[] {
                    "nodeMonitorStatus", "nodeMonitorSample", "nodeMonitorForgetNode",
                    "nodeMonitorSetSettings",
                },
                new String[] {})
        };
    }

    @Override
    public Map<String, Object> getObjectsForSwaggerExamples() {
        return Map.of();
    }
}
