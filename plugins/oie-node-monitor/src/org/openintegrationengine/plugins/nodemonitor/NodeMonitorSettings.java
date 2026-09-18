/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.nodemonitor;

import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * What this node calls itself, and how often everyone samples.
 *
 * <p>Identity comes from the environment and behaviour from the database, the same split
 * the cluster extension uses -- and deliberately from the <em>same</em> environment
 * variables, so a node is called the same thing in both views without either extension
 * knowing the other exists. Neither reads the other's storage; they just read the same
 * deployment.
 *
 * <p>This extension works on its own. With one engine it shows one node, which is a
 * perfectly useful thing to have: heap, disk, threads and throughput for the engine you
 * are looking at, with history, without installing anything else.
 */
public final class NodeMonitorSettings {

    public static final String GROUP = "Node Monitor";

    private static final String K_INTERVAL = "sampleIntervalSeconds";
    private static final String K_HISTORY = "historySamples";
    private static final String K_OFFLINE = "offlineAfterSeconds";
    private static final String K_HEAP_WARN = "heapWarnPct";
    private static final String K_DISK_WARN = "diskWarnPct";
    private static final String K_VOLUMES = "volumePaths";

    private NodeMonitorSettings() {
    }

    private static ConfigurationController config() {
        return ControllerFactory.getFactory().createConfigurationController();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static Properties stored() {
        Properties p = config().getPropertiesForGroup(GROUP);
        return p == null ? new Properties() : p;
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    public static String nodeId() {
        return config().getServerId();
    }

    public static String nodeName() {
        String name = env("OIE_CLUSTER_NODE_NAME", null);
        if (name != null) {
            return name;
        }
        String host = env("HOSTNAME", null);
        if (host != null) {
            return host;
        }
        String serverName = config().getServerName();
        return serverName == null || serverName.isBlank() ? nodeId() : serverName;
    }

    /** Blank when this engine is not part of a cluster, which the view renders as such. */
    public static String role() {
        return env("OIE_CLUSTER_ROLE", "");
    }

    // ------------------------------------------------------------------
    // Behaviour
    // ------------------------------------------------------------------

    /**
     * How often each node samples itself. Floored at 10s.
     *
     * <p>A sample is two property writes and a handful of MXBean reads, so the floor is not
     * about cost -- it is that CPU and throughput measured over five seconds are noise, and
     * a graph of noise is worse than no graph.
     */
    public static int sampleIntervalSeconds() {
        return Math.max(10, Tsv.parseInt(stored().getProperty(K_INTERVAL, "30"), 30));
    }

    public static void setSampleIntervalSeconds(int seconds) {
        config().saveProperty(GROUP, K_INTERVAL, Integer.toString(Math.max(10, seconds)));
    }

    /** How many samples each node keeps. 120 at 30s is the last hour. */
    public static int historySamples() {
        int v = Tsv.parseInt(stored().getProperty(K_HISTORY, "120"), 120);
        return Math.min(500, Math.max(10, v));
    }

    public static void setHistorySamples(int samples) {
        config().saveProperty(GROUP, K_HISTORY, Integer.toString(samples));
    }

    /**
     * How stale a node's last sample may be before it is reported offline.
     *
     * <p>Floored at three intervals: one missed sample is a slow database write or a
     * garbage collection, and calling that "offline" teaches people to ignore the word.
     */
    public static int offlineAfterSeconds() {
        int floor = sampleIntervalSeconds() * 3;
        return Math.max(floor, Tsv.parseInt(stored().getProperty(K_OFFLINE, "120"), 120));
    }

    public static void setOfflineAfterSeconds(int seconds) {
        config().saveProperty(GROUP, K_OFFLINE, Integer.toString(seconds));
    }

    /**
     * Where the bars turn amber. Presentation only.
     *
     * <p>This extension reports; it does not alert. Volume Monitor and Sentinel already
     * own alerting in this stack, and a second thing sending email about the same engine is
     * how people end up filtering both.
     */
    public static int heapWarnPct() {
        return clampPct(Tsv.parseInt(stored().getProperty(K_HEAP_WARN, "85"), 85));
    }

    public static void setHeapWarnPct(int pct) {
        config().saveProperty(GROUP, K_HEAP_WARN, Integer.toString(clampPct(pct)));
    }

    public static int diskWarnPct() {
        return clampPct(Tsv.parseInt(stored().getProperty(K_DISK_WARN, "85"), 85));
    }

    public static void setDiskWarnPct(int pct) {
        config().saveProperty(GROUP, K_DISK_WARN, Integer.toString(clampPct(pct)));
    }

    private static int clampPct(int value) {
        return Math.min(99, Math.max(1, value));
    }

    /**
     * Which filesystems to measure.
     *
     * <p>Defaults to the two directories this image keeps on volumes -- {@code appdata},
     * which holds the keystore and the extension cache, and {@code logs} -- plus the
     * install root. In this stack those are separate Docker volumes, so they are separate
     * numbers, and "the disk is full" is usually one of them rather than all of them.
     */
    public static List<String> volumePaths() {
        String configured = stored().getProperty(K_VOLUMES, "");
        List<String> out = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            for (String path : configured.split(",")) {
                if (!path.isBlank()) {
                    out.add(path.trim());
                }
            }
            return out;
        }
        ConfigurationController cc = config();
        String base = cc.getBaseDir();
        out.add(cc.getApplicationDataDir());
        if (base != null && !base.isBlank()) {
            out.add(base + "/logs");
            out.add(base);
        }
        return out;
    }

    public static void setVolumePaths(String commaSeparated) {
        config().saveProperty(GROUP, K_VOLUMES, commaSeparated == null ? "" : commaSeparated);
    }
}
