/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.nodemonitor;

import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.channel.Statistics;
import com.mirth.connect.model.DashboardStatus;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Takes one measurement of this engine.
 *
 * <p>Everything is read in process. The JVM numbers come from the platform MXBeans, the
 * disk numbers from {@link File}, and the channel and message numbers from the engine's own
 * controllers -- so a sample costs no HTTP call, no database query beyond the statistics
 * the engine already keeps, and nothing that can block for long enough to matter.
 */
final class NodeSampler {

    private static final Logger LOG = LogManager.getLogger(NodeSampler.class);

    private static EngineController engine() {
        return ControllerFactory.getFactory().createEngineController();
    }

    private static ChannelController channels() {
        return ControllerFactory.getFactory().createChannelController();
    }

    private static ConfigurationController config() {
        return ControllerFactory.getFactory().createConfigurationController();
    }

    NodeSample take() {
        String serverId = NodeMonitorSettings.nodeId();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

        double processCpu = -1;
        double systemCpu = -1;
        // com.sun.management's extension of the OS bean is where per-process CPU lives.
        // It is present on every HotSpot-derived JVM, including the Temurin runtime this
        // image ships, but it is not part of the java.lang.management contract -- so it is
        // asked for rather than assumed, and its absence costs two numbers rather than the
        // sample.
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            processCpu = toPct(sunOs.getProcessCpuLoad());
            systemCpu = toPct(sunOs.getCpuLoad());
        }

        long heapUsed = memory.getHeapMemoryUsage().getUsed();
        long heapMax = memory.getHeapMemoryUsage().getMax();
        long heapCommitted = memory.getHeapMemoryUsage().getCommitted();
        long nonHeap = memory.getNonHeapMemoryUsage().getUsed();

        Counts counts = channelCounts();
        long[] messages = messageTotals(serverId);

        return new NodeSample(
            serverId,
            NodeMonitorSettings.nodeName(),
            NodeMonitorSettings.role(),
            config().getServerVersion(),
            System.currentTimeMillis(),
            runtime.getUptime(),
            processCpu,
            systemCpu,
            os.getSystemLoadAverage(),
            heapUsed, heapMax, heapCommitted, nonHeap,
            threads.getThreadCount(), threads.getPeakThreadCount(),
            counts.deployed, counts.started, counts.paused, counts.stopped, counts.other,
            counts.queued,
            messages[0], messages[1], messages[2], messages[3],
            System.getProperty("os.name"), System.getProperty("os.arch"),
            System.getProperty("java.version"),
            volumes());
    }

    private static double toPct(double load) {
        // The JVM returns a negative value until it has two readings to compare.
        return load < 0 ? -1 : Math.min(100.0, load * 100.0);
    }

    private static final class Counts {
        int deployed;
        int started;
        int paused;
        int stopped;
        int other;
        long queued;
    }

    /**
     * What the channels on this node are doing.
     *
     * <p>Per node, and that is the point: the engine's own dashboard shows the states of
     * whichever engine answered the request, so in a cluster nobody can see "two started
     * here, one stopped there" without asking each node in turn.
     */
    private Counts channelCounts() {
        Counts counts = new Counts();
        try {
            List<DashboardStatus> statuses = engine().getChannelStatusList();
            if (statuses == null) {
                return counts;
            }
            for (DashboardStatus status : statuses) {
                counts.deployed++;
                DeployedState state = status.getState();
                if (state == DeployedState.STARTED) {
                    counts.started++;
                } else if (state == DeployedState.PAUSED) {
                    counts.paused++;
                } else if (state == DeployedState.STOPPED) {
                    counts.stopped++;
                } else {
                    counts.other++;
                }
                Long queued = status.getQueued();
                if (queued != null) {
                    counts.queued += queued;
                }
            }
        } catch (RuntimeException e) {
            // The engine may still be starting. A sample with zeroes and a timestamp is
            // more useful than no sample: it is what "this node is up but has deployed
            // nothing yet" looks like.
            LOG.debug("node monitor: could not read channel states", e);
        }
        return counts;
    }

    /**
     * Cumulative received / filtered / sent / errored for this server id.
     *
     * <p>Read from the statistics Donkey already keeps in {@code D_MS}, which is keyed by
     * {@code (METADATA_ID, SERVER_ID)} -- so these are this node's own counts even though
     * every node writes into the same channel tables. Rates are derived from consecutive
     * samples rather than stored, because a rate is a property of two measurements and not
     * of one.
     */
    private long[] messageTotals(String serverId) {
        long[] totals = new long[4];
        try {
            Statistics stats = channels().getStatisticsFromStorage(serverId);
            if (stats == null || stats.getStats() == null) {
                return totals;
            }
            for (String channelId : stats.getStats().keySet()) {
                Map<Integer, Map<Status, Long>> perConnector = stats.getChannelStats(channelId);
                if (perConnector == null) {
                    continue;
                }
                // Donkey stores the channel total with a null metadata id; metadata 0 is
                // the source connector. Prefer the total and fall back to the source --
                // never add them together, which would count every message twice.
                Map<Status, Long> row = perConnector.get(null);
                if (row == null) {
                    row = perConnector.get(0);
                }
                if (row == null) {
                    continue;
                }
                totals[0] += value(row, Status.RECEIVED);
                totals[1] += value(row, Status.FILTERED);
                totals[2] += value(row, Status.SENT);
                totals[3] += value(row, Status.ERROR);
            }
        } catch (RuntimeException e) {
            LOG.debug("node monitor: could not read message statistics", e);
        }
        return totals;
    }

    private static long value(Map<Status, Long> row, Status status) {
        Long v = row.get(status);
        return v == null ? 0L : v;
    }

    /**
     * Free and total space for each watched path.
     *
     * <p>Paths that do not exist are skipped rather than reported as zero, and paths that
     * turn out to be the same filesystem are reported once: in this image {@code appdata}
     * and {@code logs} are separate Docker volumes but on a plain install they are two
     * directories on one disk, and showing the same number twice invites someone to add
     * them up.
     */
    private List<NodeSample.Volume> volumes() {
        List<NodeSample.Volume> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String path : NodeMonitorSettings.volumePaths()) {
            try {
                File dir = new File(path);
                if (!dir.exists()) {
                    continue;
                }
                long total;
                long usable;
                String fingerprint;
                try {
                    // The filesystem's own identity, so two paths on one filesystem
                    // collapse to one row every time. Comparing free space instead looks
                    // right and is not: usable bytes move between two consecutive reads,
                    // so the same pair of paths deduplicated on one node and not on the
                    // next, which reads as a bug in whichever node disagreed.
                    FileStore store = Files.getFileStore(dir.toPath());
                    total = store.getTotalSpace();
                    usable = store.getUsableSpace();
                    fingerprint = store.name() + "|" + store.type() + "|" + total;
                } catch (IOException | RuntimeException e) {
                    total = dir.getTotalSpace();
                    usable = dir.getUsableSpace();
                    // Total size alone: stable, unlike free space, and wrong only for two
                    // filesystems of exactly equal size.
                    fingerprint = "size|" + total;
                }
                if (total <= 0 || !seen.add(fingerprint)) {
                    continue;
                }
                out.add(new NodeSample.Volume(dir.getPath(), usable, total));
            } catch (RuntimeException e) {
                LOG.debug("node monitor: could not measure {}", path, e);
            }
        }
        return out;
    }
}
