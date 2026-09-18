/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.cluster;

import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;

import java.util.Properties;

/**
 * Where this node's identity comes from, and where the cluster's behaviour comes from.
 *
 * <p>The split is the point. <b>Identity is the environment</b> -- whether this pod
 * converges at all, what it is called, what role it plays -- because those are properties
 * of the deployment, and a deployment that can be changed from a web page is one that a
 * redeploy silently reverts. <b>Behaviour is the database</b> -- how often to converge, how
 * long before a silent node counts as gone -- because those must be the same answer on
 * every node, and keeping them in the environment would mean a three-pod cluster could
 * disagree with itself.
 *
 * <p>Convergence is <b>off</b> unless {@code OIE_CLUSTER_ENABLED=true}. Installing this
 * extension on the single-engine stack this repository ships changes nothing: the node
 * registers itself, the console shows one node, and no channel is deployed or undeployed
 * by anything but the person at the keyboard.
 */
public final class ClusterSettings {

    public static final String GROUP = "Cluster";

    public static final String ROLE_WORKER = "worker";
    public static final String ROLE_UTILITY = "utility";

    private static final String K_INTERVAL = "convergeIntervalSeconds";
    private static final String K_STALE = "nodeStaleSeconds";
    private static final String K_CONVERGE_STATE = "convergeChannelState";
    static final String K_SEEDED = "intentSeeded";

    private ClusterSettings() {
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
    // Identity: the environment
    // ------------------------------------------------------------------

    /** Whether this node applies cluster intent. Everything else still works when false. */
    public static boolean enabled() {
        return "true".equalsIgnoreCase(env("OIE_CLUSTER_ENABLED", "false"));
    }

    public static String clusterName() {
        return env("OIE_CLUSTER_NAME", "default");
    }

    /**
     * {@code worker} or {@code utility}. Defaults to {@code utility}, which is what makes
     * a lone engine keep running everything: singleton-placed channels go to the utility
     * node, and a single default-configured engine is one.
     */
    public static String role() {
        String role = env("OIE_CLUSTER_ROLE", ROLE_UTILITY).toLowerCase();
        return ROLE_WORKER.equals(role) ? ROLE_WORKER : ROLE_UTILITY;
    }

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

    /**
     * How the console should link to this node directly, for the per-node tools that
     * cannot be aggregated (thread dumps, the server log). Empty when nothing has told us,
     * and the console then offers no link rather than guessing at an address that is
     * reachable from inside the cluster and nowhere else.
     */
    public static String address() {
        return env("OIE_CLUSTER_ADDRESS", "");
    }

    // ------------------------------------------------------------------
    // Behaviour: the database
    // ------------------------------------------------------------------

    /** Floored at 2s. The tick is a handful of indexed reads; the floor is for the log. */
    public static int convergeIntervalSeconds() {
        return Math.max(2, Tsv.parseInt(stored().getProperty(K_INTERVAL, "5"), 5));
    }

    public static void setConvergeIntervalSeconds(int seconds) {
        config().saveProperty(GROUP, K_INTERVAL, Integer.toString(Math.max(2, seconds)));
    }

    /**
     * How long a node may be silent before it stops counting as present.
     *
     * <p>Floored well above the heartbeat interval: this threshold decides who owns the
     * singleton channels, and a value close to the heartbeat would hand ownership back and
     * forth on nothing more than a slow database write.
     */
    public static int nodeStaleSeconds() {
        int floor = Math.max(15, convergeIntervalSeconds() * 4);
        return Math.max(floor, Tsv.parseInt(stored().getProperty(K_STALE, "60"), 60));
    }

    public static void setNodeStaleSeconds(int seconds) {
        config().saveProperty(GROUP, K_STALE, Integer.toString(seconds));
    }

    /**
     * Whether a node applies the cluster's channel state (started / paused / stopped) when
     * that intent changes.
     *
     * <p>Only on an actual change of intent, never continuously -- see
     * {@link ClusterAgent} for why a continuous reconciler would be a bad idea.
     */
    public static boolean convergeChannelState() {
        return !"false".equalsIgnoreCase(stored().getProperty(K_CONVERGE_STATE, "true"));
    }

    public static void setConvergeChannelState(boolean converge) {
        config().saveProperty(GROUP, K_CONVERGE_STATE, Boolean.toString(converge));
    }
}
