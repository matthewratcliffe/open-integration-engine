/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.nodemonitor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the console asks one engine about every engine.
 *
 * <p>Every answer is assembled from rows in the shared database, so any node can serve the
 * whole view, and a node that is down is reported as down rather than as a timeout. That is
 * the difference between a monitoring view and a health check: the moment you most want to
 * see a node is after it has stopped answering.
 */
public final class NodeMonitorService {

    private static final Logger LOG = LogManager.getLogger(NodeMonitorService.class);

    private final NodeMonitorAgent agent;

    NodeMonitorService(NodeMonitorAgent agent) {
        this.agent = agent;
    }

    private static Map<String, Object> ok() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        return out;
    }

    /**
     * Every node, its latest sample, its rates and its history, in one response.
     *
     * <p>One request rather than one per node, and certainly not one per node per panel:
     * the view refreshes on a timer, and a page that issues N requests every few seconds to
     * render N cards is a page that gets slower as the thing it monitors gets bigger.
     */
    public Map<String, Object> status() {
        List<NodeSample> samples = NodeMonitorStore.samples();
        Map<String, List<String>> histories = NodeMonitorStore.histories();
        long now = System.currentTimeMillis();
        long offlineAfter = NodeMonitorSettings.offlineAfterSeconds() * 1000L;
        String thisNode = NodeMonitorSettings.nodeId();

        List<String> nodeRows = new ArrayList<>();
        List<String> volumeRows = new ArrayList<>();
        List<String> historyRows = new ArrayList<>();

        int online = 0;
        long totalReceived = 0;
        long totalSent = 0;
        long totalErrored = 0;
        long totalQueued = 0;
        double totalReceivedRate = 0;

        for (NodeSample sample : samples) {
            String nodeId = sample.getServerId();
            boolean isOnline = now - sample.getTimestamp() <= offlineAfter;
            if (isOnline) {
                online++;
            }

            List<String> history = histories.getOrDefault(nodeId, List.of());
            double[] rates = rates(history);

            // A node that is not reporting is not processing either, as far as anyone can
            // tell -- so its counters are shown (they are real) but its rates are not
            // carried forward, which would draw traffic that is not happening.
            if (!isOnline) {
                rates = new double[] {0, 0, 0};
            } else {
                totalReceivedRate += rates[0];
            }

            totalReceived += sample.getReceived();
            totalSent += sample.getSent();
            totalErrored += sample.getErrored();
            totalQueued += sample.getQueued();

            nodeRows.add(Tsv.join(nodeId, sample.getNodeName(), sample.getRole(),
                sample.getVersion(), isOnline ? "1" : "0", sample.getTimestamp(),
                sample.getUptimeMs(),
                fmt(sample.getCpuProcessPct()), fmt(sample.getCpuSystemPct()),
                fmt(sample.getLoadAverage()),
                sample.getHeapUsed(), sample.getHeapMax(), fmt(sample.heapUsedPct()),
                sample.getNonHeapUsed(), sample.getThreads(), sample.getPeakThreads(),
                sample.getChannelsDeployed(), sample.getChannelsStarted(),
                sample.getChannelsPaused(), sample.getChannelsStopped(),
                sample.getChannelsOther(), sample.getQueued(),
                sample.getReceived(), sample.getSent(), sample.getErrored(),
                sample.getFiltered(),
                fmt(rates[0]), fmt(rates[1]), fmt(rates[2]),
                sample.getOsName(), sample.getOsArch(), sample.getJvmVersion(),
                nodeId.equals(thisNode) ? "1" : "0"));

            for (NodeSample.Volume volume : sample.getVolumes()) {
                long total = volume.getTotal();
                double usedPct = total <= 0 ? -1 : (volume.getUsed() * 100.0) / total;
                volumeRows.add(Tsv.join(nodeId, volume.getPath(), volume.getUsable(),
                    total, fmt(usedPct)));
            }

            for (String line : history) {
                historyRows.add(nodeId + "\t" + line);
            }
        }

        Map<String, Object> out = ok();
        out.put("nodeId", thisNode);
        out.put("nodeName", NodeMonitorSettings.nodeName());
        out.put("nodes", samples.size());
        out.put("online", online);
        out.put("offline", samples.size() - online);
        out.put("totalReceived", totalReceived);
        out.put("totalSent", totalSent);
        out.put("totalErrored", totalErrored);
        out.put("totalQueued", totalQueued);
        out.put("totalReceivedPerMin", fmt(totalReceivedRate));
        out.put("sampleIntervalSeconds", NodeMonitorSettings.sampleIntervalSeconds());
        out.put("offlineAfterSeconds", NodeMonitorSettings.offlineAfterSeconds());
        out.put("historySamples", NodeMonitorSettings.historySamples());
        out.put("heapWarnPct", NodeMonitorSettings.heapWarnPct());
        out.put("diskWarnPct", NodeMonitorSettings.diskWarnPct());
        out.put("volumePaths", String.join(", ", NodeMonitorSettings.volumePaths()));
        out.put("lastSampleAt", agent == null ? 0L : agent.getLastSampleAt());
        out.put("lastError", agent == null ? "" : agent.getLastError());
        out.put("nodeRows", nodeRows);
        out.put("volumeRows", volumeRows);
        out.put("historyRows", historyRows);
        return out;
    }

    /**
     * Messages per minute, from the two most recent history points.
     *
     * <p>Two points rather than a smoothed average over the window: this number is read to
     * answer "is it moving *now*", and averaging over an hour makes a feed that died ten
     * minutes ago still look busy. The graph beside it carries the longer view.
     *
     * <p>A negative delta means the counters were reset -- which an operator can do from
     * the dashboard -- and is reported as zero rather than as a spike in the wrong
     * direction.
     */
    private static double[] rates(List<String> history) {
        if (history.size() < 2) {
            return new double[] {0, 0, 0};
        }
        String[] last = Tsv.split(history.get(history.size() - 1), 7);
        String[] prev = Tsv.split(history.get(history.size() - 2), 7);
        long t1 = Tsv.parseLong(last[0], 0);
        long t0 = Tsv.parseLong(prev[0], 0);
        double minutes = (t1 - t0) / 60000.0;
        if (minutes <= 0) {
            return new double[] {0, 0, 0};
        }
        return new double[] {
            delta(last[3], prev[3]) / minutes,
            delta(last[4], prev[4]) / minutes,
            delta(last[5], prev[5]) / minutes,
        };
    }

    private static double delta(String latest, String previous) {
        long d = Tsv.parseLong(latest, 0) - Tsv.parseLong(previous, 0);
        return d < 0 ? 0 : d;
    }

    private static String fmt(double value) {
        if (value < 0) {
            return "-1";
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /** Samples this node immediately, so a settings change is visible without waiting. */
    public Map<String, Object> sampleNow() {
        if (agent == null) {
            throw new IllegalStateException("the sampler is not running");
        }
        agent.sample();
        Map<String, Object> out = ok();
        out.put("sampledAt", agent.getLastSampleAt());
        // Worth saying: the button samples the engine that served the page, and only that
        // one. Every other node is on its own timer and nothing here can reach it.
        out.put("note", "Sampled this node. Other nodes report on their own interval.");
        return out;
    }

    /**
     * Removes a node's samples and history.
     *
     * <p>Refused while the node is still reporting: the row would reappear within an
     * interval, which looks like the button not working. And only ever on request -- a node
     * that has gone quiet is the thing this view exists to show.
     */
    public Map<String, Object> forgetNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("a node id is required");
        }
        if (nodeId.equals(NodeMonitorSettings.nodeId())) {
            throw new IllegalArgumentException("a node cannot forget itself");
        }
        long offlineAfter = NodeMonitorSettings.offlineAfterSeconds() * 1000L;
        long now = System.currentTimeMillis();
        for (NodeSample sample : NodeMonitorStore.samples()) {
            if (sample.getServerId().equals(nodeId)
                && now - sample.getTimestamp() <= offlineAfter) {
                throw new IllegalArgumentException(
                    "node " + sample.getNodeName() + " is still reporting; stop it first");
            }
        }
        NodeMonitorStore.forget(nodeId);
        LOG.info("node monitor: forgot node {}", nodeId);
        return ok();
    }

    public Map<String, Object> saveSettings(Map<String, String> settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings are required");
        }
        if (settings.containsKey("sampleIntervalSeconds")) {
            NodeMonitorSettings.setSampleIntervalSeconds(
                Tsv.parseInt(settings.get("sampleIntervalSeconds"), 30));
        }
        if (settings.containsKey("historySamples")) {
            NodeMonitorSettings.setHistorySamples(
                Tsv.parseInt(settings.get("historySamples"), 120));
        }
        if (settings.containsKey("offlineAfterSeconds")) {
            NodeMonitorSettings.setOfflineAfterSeconds(
                Tsv.parseInt(settings.get("offlineAfterSeconds"), 120));
        }
        if (settings.containsKey("heapWarnPct")) {
            NodeMonitorSettings.setHeapWarnPct(Tsv.parseInt(settings.get("heapWarnPct"), 85));
        }
        if (settings.containsKey("diskWarnPct")) {
            NodeMonitorSettings.setDiskWarnPct(Tsv.parseInt(settings.get("diskWarnPct"), 85));
        }
        if (settings.containsKey("volumePaths")) {
            NodeMonitorSettings.setVolumePaths(settings.get("volumePaths"));
        }
        Map<String, Object> out = ok();
        // The scheduler fixes its period when it starts, the same as the volume monitor's
        // and the cluster agent's, so say so rather than letting a saved value quietly not
        // apply.
        out.put("note", "Thresholds and paths apply on each node's next sample. "
            + "A changed interval applies when that node restarts.");
        return out;
    }
}
