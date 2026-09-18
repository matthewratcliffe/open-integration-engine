/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.cluster;

import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The cluster's shared state, in the {@code configuration} table.
 *
 * <p>No new tables, and so no DDL, no migration and no second thing to back up. The
 * database is already the one place every engine can see, the {@code configuration} table
 * is already where every other extension in this repository keeps its settings, and it
 * already travels with the PostgreSQL backup that this stack tells you is the one that
 * matters. Three keys carry everything:
 *
 * <pre>
 *   node.&lt;serverId&gt;      one row per engine, rewritten on each heartbeat
 *   intent.&lt;channelId&gt;   what the cluster is meant to be doing with that channel
 *   applied.&lt;serverId&gt;   what that engine has actually done about each of them
 * </pre>
 *
 * <p>One key per node and one per channel, rather than one key holding all of them: a node
 * writes only its own rows, so two engines converging at the same moment cannot overwrite
 * each other's progress with a stale read-modify-write. The same reason the volume monitor
 * stores one property per rule.
 *
 * <p>On PostgreSQL {@code saveProperty} is a single {@code UPDATE} under a JVM-level
 * statement lock -- the {@code vacuumConfigurationTable} path the controller can take does
 * not exist in the PostgreSQL statement set -- so a heartbeat every few seconds from a
 * handful of nodes is not a load worth engineering around.
 */
public final class ClusterStore {

    private static final String NODE_PREFIX = "node.";
    private static final String INTENT_PREFIX = "intent.";
    private static final String APPLIED_PREFIX = "applied.";

    private static final Logger LOG = LogManager.getLogger(ClusterStore.class);

    private ClusterStore() {
    }

    private static ConfigurationController config() {
        return ControllerFactory.getFactory().createConfigurationController();
    }

    /**
     * {@code saveProperty} is an UPDATE, then an INSERT when the UPDATE matched nothing.
     * Two writers creating the same property for the first time can therefore both reach
     * the INSERT and one loses on the unique key. The retry finds the row present and
     * takes the UPDATE path, which is the correct outcome either way.
     */
    private static void save(String key, String value) {
        try {
            config().saveProperty(ClusterSettings.GROUP, key, value);
        } catch (RuntimeException first) {
            try {
                config().saveProperty(ClusterSettings.GROUP, key, value);
            } catch (RuntimeException second) {
                LOG.error("cluster: could not store {}", key, second);
                throw second;
            }
        }
    }

    private static Properties stored() {
        Properties p = config().getPropertiesForGroup(ClusterSettings.GROUP);
        return p == null ? new Properties() : p;
    }

    // ------------------------------------------------------------------
    // Nodes
    // ------------------------------------------------------------------

    /** Every node that has ever registered, ordered by name so the console list is stable. */
    public static List<NodeRecord> nodes() {
        return nodes(stored());
    }

    static List<NodeRecord> nodes(Properties p) {
        List<NodeRecord> out = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(NODE_PREFIX)) {
                continue;
            }
            NodeRecord node = NodeRecord.parse(p.getProperty(key));
            if (node == null) {
                LOG.warn("cluster: ignoring unreadable node record '{}'", key);
                continue;
            }
            out.add(node);
        }
        out.sort(Comparator.comparing(NodeRecord::getName).thenComparing(NodeRecord::getNodeId));
        return out;
    }

    public static NodeRecord node(String nodeId) {
        return NodeRecord.parse(stored().getProperty(NODE_PREFIX + nodeId));
    }

    public static void saveNode(NodeRecord node) {
        save(NODE_PREFIX + node.getNodeId(), node.serialise());
    }

    /**
     * Forgets a node entirely.
     *
     * <p>Only ever called because someone asked. A node that is merely down still owns its
     * queued messages, and removing it from the registry is how those stop being anybody's
     * problem -- which is exactly the way for work to disappear quietly.
     */
    public static void removeNode(String nodeId) {
        config().removeProperty(ClusterSettings.GROUP, NODE_PREFIX + nodeId);
        config().removeProperty(ClusterSettings.GROUP, APPLIED_PREFIX + nodeId);
    }

    // ------------------------------------------------------------------
    // Intent
    // ------------------------------------------------------------------

    public static Map<String, ChannelIntent> intents() {
        return intents(stored());
    }

    static Map<String, ChannelIntent> intents(Properties p) {
        Map<String, ChannelIntent> out = new LinkedHashMap<>();
        Map<String, String> sorted = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(INTENT_PREFIX)) {
                sorted.put(key, p.getProperty(key));
            }
        }
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            ChannelIntent intent = ChannelIntent.parse(e.getValue());
            if (intent == null) {
                LOG.warn("cluster: ignoring unreadable intent record '{}'", e.getKey());
                continue;
            }
            out.put(intent.getChannelId(), intent);
        }
        return out;
    }

    public static ChannelIntent intent(String channelId) {
        return ChannelIntent.parse(stored().getProperty(INTENT_PREFIX + channelId));
    }

    public static void saveIntent(ChannelIntent intent) {
        save(INTENT_PREFIX + intent.getChannelId(), intent.serialise());
    }

    public static void removeIntent(String channelId) {
        config().removeProperty(ClusterSettings.GROUP, INTENT_PREFIX + channelId);
    }

    // ------------------------------------------------------------------
    // Applied state, per node
    // ------------------------------------------------------------------

    /** What one channel looks like on one node. */
    public static final class Applied {

        private static final int FIELDS = 5;

        final String channelId;
        final long deploySeq;
        final long stateSeq;
        final String state;
        final String error;

        public Applied(String channelId, long deploySeq, long stateSeq,
                       String state, String error) {
            this.channelId = channelId;
            this.deploySeq = deploySeq;
            this.stateSeq = stateSeq;
            this.state = state;
            this.error = error;
        }

        static Applied parse(String line) {
            String[] f = Tsv.split(line, FIELDS);
            if (f[0].isBlank()) {
                return null;
            }
            return new Applied(f[0], Tsv.parseLong(f[1], 0L), Tsv.parseLong(f[2], 0L),
                f[3], f[4]);
        }

        String serialise() {
            return Tsv.join(channelId, deploySeq, stateSeq, state, error);
        }

        public String getChannelId() {
            return channelId;
        }

        public long getDeploySeq() {
            return deploySeq;
        }

        public long getStateSeq() {
            return stateSeq;
        }

        public String getState() {
            return state;
        }

        public String getError() {
            return error;
        }
    }

    public static Map<String, Applied> applied(String nodeId) {
        return applied(stored(), nodeId);
    }

    static Map<String, Applied> applied(Properties p, String nodeId) {
        return parseApplied(p.getProperty(APPLIED_PREFIX + nodeId));
    }

    private static Map<String, Applied> parseApplied(String value) {
        Map<String, Applied> out = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String line : value.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            Applied applied = Applied.parse(line);
            if (applied != null) {
                out.put(applied.getChannelId(), applied);
            }
        }
        return out;
    }

    /**
     * Replaces this node's applied map.
     *
     * <p>One property for all of a node's channels, unlike intent. It is only ever written
     * by the node it belongs to, so there is no other writer to race with, and a cluster of
     * 200 channels would otherwise put 200 rows per node into a table the engine reads
     * whole.
     */
    public static void saveApplied(String nodeId, Map<String, Applied> applied) {
        StringBuilder sb = new StringBuilder();
        for (Applied a : applied.values()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(a.serialise());
        }
        save(APPLIED_PREFIX + nodeId, sb.toString());
    }

    /** One read of the group, for callers that need nodes, intent and applied together. */
    public static Snapshot snapshot() {
        Properties p = stored();
        Snapshot s = new Snapshot();
        s.nodes = nodes(p);
        s.intents = intents(p);
        s.appliedByNode = new LinkedHashMap<>();
        for (NodeRecord node : s.nodes) {
            s.appliedByNode.put(node.getNodeId(), applied(p, node.getNodeId()));
        }
        return s;
    }

    /** Everything the console and the agent need, from a single query. */
    public static final class Snapshot {
        public List<NodeRecord> nodes;
        public Map<String, ChannelIntent> intents;
        public Map<String, Map<String, Applied>> appliedByNode;
    }
}
