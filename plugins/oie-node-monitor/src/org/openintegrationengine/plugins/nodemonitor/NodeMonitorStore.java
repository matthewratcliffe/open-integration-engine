/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.nodemonitor;

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

/**
 * Samples in the {@code configuration} table, which is what makes every node visible from
 * every node.
 *
 * <p>Two keys per engine:
 *
 * <pre>
 *   sample.&lt;serverId&gt;    the latest sample, replaced each pass
 *   history.&lt;serverId&gt;   the last N samples, newest last, for the graphs
 * </pre>
 *
 * <p>A node writes only its own two rows, so there is no write contention between nodes and
 * no read-modify-write that can lose another node's work. No new tables means no DDL and no
 * migration to own, and the samples travel with the database backup -- which for a
 * monitoring view is the right trade: it is small, it is bounded, and nobody wants a second
 * datastore to keep alive in order to find out that the first one is unhealthy.
 *
 * <p>The history is bounded by count rather than by age, so its cost is fixed: at the
 * default 120 samples of seven numbers, a node's history is a few kilobytes and stays that
 * size for ever.
 */
public final class NodeMonitorStore {

    private static final String SAMPLE_PREFIX = "sample.";
    private static final String HISTORY_PREFIX = "history.";

    private static final Logger LOG = LogManager.getLogger(NodeMonitorStore.class);

    private NodeMonitorStore() {
    }

    private static ConfigurationController config() {
        return ControllerFactory.getFactory().createConfigurationController();
    }

    private static Properties stored() {
        Properties p = config().getPropertiesForGroup(NodeMonitorSettings.GROUP);
        return p == null ? new Properties() : p;
    }

    /**
     * {@code saveProperty} updates, then inserts when the update matched nothing, so two
     * writers creating the same property for the first time can both reach the insert and
     * one loses on the unique key. The retry finds the row present and updates it.
     */
    private static void save(String key, String value) {
        try {
            config().saveProperty(NodeMonitorSettings.GROUP, key, value);
        } catch (RuntimeException first) {
            try {
                config().saveProperty(NodeMonitorSettings.GROUP, key, value);
            } catch (RuntimeException second) {
                LOG.error("node monitor: could not store {}", key, second);
            }
        }
    }

    /** The latest sample from every node that has ever reported, newest name order. */
    public static List<NodeSample> samples() {
        Properties p = stored();
        List<NodeSample> out = new ArrayList<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(SAMPLE_PREFIX)) {
                continue;
            }
            NodeSample sample = NodeSample.parse(p.getProperty(key));
            if (sample == null) {
                LOG.warn("node monitor: ignoring unreadable sample '{}'", key);
                continue;
            }
            out.add(sample);
        }
        out.sort(Comparator.comparing(NodeSample::getNodeName)
            .thenComparing(NodeSample::getServerId));
        return out;
    }

    /** Every node's history, keyed by server id, in one read of the group. */
    public static Map<String, List<String>> histories() {
        Properties p = stored();
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith(HISTORY_PREFIX)) {
                continue;
            }
            String nodeId = key.substring(HISTORY_PREFIX.length());
            out.put(nodeId, lines(p.getProperty(key)));
        }
        return out;
    }

    public static List<String> history(String nodeId) {
        return lines(stored().getProperty(HISTORY_PREFIX + nodeId));
    }

    private static List<String> lines(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String line : value.split("\n")) {
            if (!line.isBlank()) {
                out.add(line);
            }
        }
        return out;
    }

    /** Writes this node's latest sample and appends it to its own history. */
    public static void record(NodeSample sample, int keep) {
        String nodeId = sample.getServerId();
        save(SAMPLE_PREFIX + nodeId, sample.serialise());

        List<String> history = history(nodeId);
        history.add(sample.historyLine());
        while (history.size() > keep) {
            history.remove(0);
        }
        save(HISTORY_PREFIX + nodeId, String.join("\n", history));
    }

    /**
     * Forgets a node entirely.
     *
     * <p>Only ever because someone asked. A node that is merely down is the single most
     * useful thing this view shows -- the last sample before it went, and when that was --
     * so nothing here expires a node on its own.
     */
    public static void forget(String nodeId) {
        config().removeProperty(NodeMonitorSettings.GROUP, SAMPLE_PREFIX + nodeId);
        config().removeProperty(NodeMonitorSettings.GROUP, HISTORY_PREFIX + nodeId);
    }
}
