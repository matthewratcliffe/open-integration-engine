/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.nodemonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * One engine, at one moment: what the JVM is doing, what the disks look like, what the
 * channels are doing, and how many messages have been through.
 *
 * <p>Everything here is measured by the node about itself. That is the whole architecture:
 * a node writes its own sample to the shared database, and any node's console can render
 * every node from rows it already has. Nothing calls anything, so there is no peer
 * credential to distribute, no timeout to handle, and a node that is gone still shows its
 * last known state with the time it was taken -- which is exactly what you want to look at
 * when a node is gone.
 */
public final class NodeSample {

    /** Sample record fields, in order. Append only: {@link Tsv#split} pads short rows. */
    private static final int FIELDS = 29;

    private final String serverId;
    private final String nodeName;
    private final String role;
    private final String version;
    private final long timestamp;
    private final long uptimeMs;
    private final double cpuProcessPct;
    private final double cpuSystemPct;
    private final double loadAverage;
    private final long heapUsed;
    private final long heapMax;
    private final long heapCommitted;
    private final long nonHeapUsed;
    private final int threads;
    private final int peakThreads;
    private final int channelsDeployed;
    private final int channelsStarted;
    private final int channelsPaused;
    private final int channelsStopped;
    private final int channelsOther;
    private final long queued;
    private final long received;
    private final long filtered;
    private final long sent;
    private final long errored;
    private final String osName;
    private final String osArch;
    private final String jvmVersion;
    private final List<Volume> volumes;

    NodeSample(String serverId, String nodeName, String role, String version, long timestamp,
               long uptimeMs, double cpuProcessPct, double cpuSystemPct, double loadAverage,
               long heapUsed, long heapMax, long heapCommitted, long nonHeapUsed,
               int threads, int peakThreads, int channelsDeployed, int channelsStarted,
               int channelsPaused, int channelsStopped, int channelsOther, long queued,
               long received, long filtered, long sent, long errored,
               String osName, String osArch, String jvmVersion, List<Volume> volumes) {
        this.serverId = serverId;
        this.nodeName = nodeName;
        this.role = role;
        this.version = version;
        this.timestamp = timestamp;
        this.uptimeMs = uptimeMs;
        this.cpuProcessPct = cpuProcessPct;
        this.cpuSystemPct = cpuSystemPct;
        this.loadAverage = loadAverage;
        this.heapUsed = heapUsed;
        this.heapMax = heapMax;
        this.heapCommitted = heapCommitted;
        this.nonHeapUsed = nonHeapUsed;
        this.threads = threads;
        this.peakThreads = peakThreads;
        this.channelsDeployed = channelsDeployed;
        this.channelsStarted = channelsStarted;
        this.channelsPaused = channelsPaused;
        this.channelsStopped = channelsStopped;
        this.channelsOther = channelsOther;
        this.queued = queued;
        this.received = received;
        this.filtered = filtered;
        this.sent = sent;
        this.errored = errored;
        this.osName = osName;
        this.osArch = osArch;
        this.jvmVersion = jvmVersion;
        this.volumes = volumes == null ? List.of() : volumes;
    }

    /**
     * One filesystem the engine writes to.
     *
     * <p>Encoded inside the sample's last field rather than as a record of its own, because
     * the number of them is a per-node setting and the alternative is a second property
     * whose lifetime has to be kept in step with this one. {@code |} and {@code ;} are
     * stripped from the path on the way in, which is what makes that safe.
     */
    public static final class Volume {
        private final String path;
        private final long usable;
        private final long total;

        Volume(String path, long usable, long total) {
            this.path = path;
            this.usable = usable;
            this.total = total;
        }

        public String getPath() {
            return path;
        }

        public long getUsable() {
            return usable;
        }

        public long getTotal() {
            return total;
        }

        public long getUsed() {
            return Math.max(0, total - usable);
        }

        String encode() {
            return sanitise(path) + "|" + usable + "|" + total;
        }

        static Volume decode(String encoded) {
            String[] parts = encoded.split("\\|", -1);
            if (parts.length < 3) {
                return null;
            }
            return new Volume(parts[0], Tsv.parseLong(parts[1], 0), Tsv.parseLong(parts[2], 0));
        }

        private static String sanitise(String value) {
            return Tsv.clean(value).replace('|', '_').replace(';', '_');
        }
    }

    String serialise() {
        StringBuilder vols = new StringBuilder();
        for (Volume v : volumes) {
            if (vols.length() > 0) {
                vols.append(';');
            }
            vols.append(v.encode());
        }
        return Tsv.join(serverId, nodeName, role, version, timestamp, uptimeMs,
            format(cpuProcessPct), format(cpuSystemPct), format(loadAverage),
            heapUsed, heapMax, heapCommitted, nonHeapUsed, threads, peakThreads,
            channelsDeployed, channelsStarted, channelsPaused, channelsStopped, channelsOther,
            queued, received, filtered, sent, errored,
            osName, osArch, jvmVersion, vols);
    }

    private static String format(double value) {
        // One decimal place: a percentage to fifteen digits is noise in a TSV row and
        // noise in a view.
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    static NodeSample parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] f = Tsv.split(line, FIELDS);
        if (f[0].isBlank()) {
            return null;
        }
        List<Volume> vols = new ArrayList<>();
        if (!f[28].isBlank()) {
            for (String part : f[28].split(";")) {
                Volume v = Volume.decode(part);
                if (v != null) {
                    vols.add(v);
                }
            }
        }
        return new NodeSample(f[0], f[1], f[2], f[3],
            Tsv.parseLong(f[4], 0), Tsv.parseLong(f[5], 0),
            Tsv.parseDouble(f[6], -1), Tsv.parseDouble(f[7], -1), Tsv.parseDouble(f[8], -1),
            Tsv.parseLong(f[9], 0), Tsv.parseLong(f[10], 0), Tsv.parseLong(f[11], 0),
            Tsv.parseLong(f[12], 0),
            Tsv.parseInt(f[13], 0), Tsv.parseInt(f[14], 0),
            Tsv.parseInt(f[15], 0), Tsv.parseInt(f[16], 0), Tsv.parseInt(f[17], 0),
            Tsv.parseInt(f[18], 0), Tsv.parseInt(f[19], 0),
            Tsv.parseLong(f[20], 0), Tsv.parseLong(f[21], 0), Tsv.parseLong(f[22], 0),
            Tsv.parseLong(f[23], 0), Tsv.parseLong(f[24], 0),
            f[25], f[26], f[27], vols);
    }

    /**
     * The compact form kept for the history, and drawn as a sparkline.
     *
     * <p>Seven numbers rather than the whole sample: the history is rewritten on every
     * pass, so its size is a cost paid every interval by every node, and nobody plots an OS
     * name.
     */
    String historyLine() {
        return Tsv.join(timestamp, format(cpuProcessPct), format(heapUsedPct()),
            received, sent, errored, queued);
    }

    public double heapUsedPct() {
        return heapMax <= 0 ? -1 : (heapUsed * 100.0) / heapMax;
    }

    public String getServerId() {
        return serverId;
    }

    public String getNodeName() {
        return nodeName == null || nodeName.isBlank() ? serverId : nodeName;
    }

    public String getRole() {
        return role;
    }

    public String getVersion() {
        return version;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getUptimeMs() {
        return uptimeMs;
    }

    public double getCpuProcessPct() {
        return cpuProcessPct;
    }

    public double getCpuSystemPct() {
        return cpuSystemPct;
    }

    public double getLoadAverage() {
        return loadAverage;
    }

    public long getHeapUsed() {
        return heapUsed;
    }

    public long getHeapMax() {
        return heapMax;
    }

    public long getHeapCommitted() {
        return heapCommitted;
    }

    public long getNonHeapUsed() {
        return nonHeapUsed;
    }

    public int getThreads() {
        return threads;
    }

    public int getPeakThreads() {
        return peakThreads;
    }

    public int getChannelsDeployed() {
        return channelsDeployed;
    }

    public int getChannelsStarted() {
        return channelsStarted;
    }

    public int getChannelsPaused() {
        return channelsPaused;
    }

    public int getChannelsStopped() {
        return channelsStopped;
    }

    public int getChannelsOther() {
        return channelsOther;
    }

    public long getQueued() {
        return queued;
    }

    public long getReceived() {
        return received;
    }

    public long getFiltered() {
        return filtered;
    }

    public long getSent() {
        return sent;
    }

    public long getErrored() {
        return errored;
    }

    public String getOsName() {
        return osName;
    }

    public String getOsArch() {
        return osArch;
    }

    public String getJvmVersion() {
        return jvmVersion;
    }

    public List<Volume> getVolumes() {
        return volumes;
    }
}
