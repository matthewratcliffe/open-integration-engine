/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.volumemonitor;

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
 * Lifecycle for the volume monitor.
 *
 * <p>The evaluator runs on a timer from server start, whether or not anyone has the page
 * open. That is the point: a monitor that only notices a dead feed while someone is
 * looking at it is a report, not a monitor.
 */
public class VolumeMonitorServicePlugin implements ServicePlugin {

    public static final String PLUGIN_POINT_NAME = "Volume Monitor";

    private static final Logger LOG = LogManager.getLogger(VolumeMonitorServicePlugin.class);

    /**
     * The first pass is delayed rather than immediate. At server start channels are still
     * deploying, so counting straight away would report a row of stopped channels and a
     * burst of events that say nothing except "the server restarted".
     */
    private static final int STARTUP_DELAY_SECONDS = 120;

    private static volatile VolumeMonitorService service;

    private ScheduledExecutorService scheduler;

    public static VolumeMonitorService service() {
        return service;
    }

    @Override
    public void init(Properties properties) {
        service = new VolumeMonitorService(new VolumeEvaluator());
    }

    @Override
    public void start() {
        int interval = VolumeRuleStore.evaluateIntervalSeconds();

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "volume-monitor");
            // Daemon so an in-flight count cannot hold up engine shutdown.
            t.setDaemon(true);
            return t;
        });

        // Fixed delay, not fixed rate: on a large message store a pass can outlast the
        // interval, and queueing another behind it would turn one slow query into a
        // growing backlog of identical work.
        scheduler.scheduleWithFixedDelay(this::pass, STARTUP_DELAY_SECONDS, interval,
            TimeUnit.SECONDS);

        int rules = VolumeRuleStore.load().size();
        LOG.info("volume monitor started: {} rule(s), evaluating every {}s "
            + "(first pass in {}s)", rules, interval, STARTUP_DELAY_SECONDS);
    }

    /**
     * One evaluation pass.
     *
     * <p>Never allowed to throw. An exception escaping a scheduled task cancels every
     * future run, which would leave the monitor silently dead -- the exact failure it
     * exists to catch, and the worst possible one for it to have itself.
     */
    private void pass() {
        VolumeMonitorService current = service;
        if (current == null) {
            return;
        }
        try {
            current.evaluator().evaluateAll(true);
        } catch (Throwable t) {
            LOG.error("volume monitor evaluation failed", t);
        }
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        service = null;
    }

    @Override
    public void update(Properties properties) {
        // Rules and settings live in the configuration table and are read on each pass, so
        // there is nothing to reload. The interval is the exception: the scheduler fixes
        // its period when it starts, so changing it takes a restart, which the settings
        // response says out loud.
    }

    @Override
    public Properties getDefaultProperties() {
        return new Properties();
    }

    @Override
    public ExtensionPermission[] getExtensionPermissions() {
        return new ExtensionPermission[] {
            new ExtensionPermission(PLUGIN_POINT_NAME, "Manage volume monitoring",
                "Allows viewing and editing message volume rules.",
                new String[] {
                    "volumeMonitorStatus", "volumeMonitorSummary", "volumeMonitorGetRules",
                    "volumeMonitorSaveRule", "volumeMonitorDeleteRule",
                    "volumeMonitorChannels", "volumeMonitorSetSettings"
                },
                new String[] {})
        };
    }

    @Override
    public String getPluginPointName() {
        return PLUGIN_POINT_NAME;
    }

    @Override
    public Map<String, Object> getObjectsForSwaggerExamples() {
        return Map.of();
    }
}
