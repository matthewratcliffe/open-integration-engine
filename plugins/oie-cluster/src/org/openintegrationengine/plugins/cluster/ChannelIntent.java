/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.cluster;

/**
 * What the cluster is meant to be doing with one channel.
 *
 * <p>Intent is recorded from a deploy a person asked for, and every node converges on it.
 * It is not derived from what the engines are observed to be running: a channel that
 * stopped itself on one node because its listener port was taken is a fault to report, and
 * inferring intent from it would turn one node's fault into the cluster's configuration.
 *
 * <p>{@code deploySeq} and {@code stateSeq} are wall-clock milliseconds at the moment the
 * instruction was given, and nodes compare them for <em>inequality</em>, never for order.
 * Two pods whose clocks disagree still converge; ordering would make the cluster's
 * behaviour depend on NTP.
 */
public final class ChannelIntent {

    public static final String DEPLOYED = "DEPLOYED";
    public static final String UNDEPLOYED = "UNDEPLOYED";

    /** Deploy on every node. The default, and right for anything a load balancer feeds. */
    public static final String PLACEMENT_ALL = "ALL";
    /** Deploy on exactly one node. For polling sources, which would otherwise read N times. */
    public static final String PLACEMENT_SINGLETON = "SINGLETON";
    /** Deploy only on the named node: {@code PINNED:<serverId>}. */
    public static final String PLACEMENT_PINNED_PREFIX = "PINNED:";

    private static final int FIELDS = 12;

    private final String channelId;
    private final String name;
    private final String desiredState;
    private final int revision;
    private final long deploySeq;
    private final String placement;
    private final String lifecycle;
    private final long stateSeq;
    private final long updatedAt;
    private final String updatedBy;
    private final String originNode;
    private final String note;

    public ChannelIntent(String channelId, String name, String desiredState, int revision,
                         long deploySeq, String placement, String lifecycle, long stateSeq,
                         long updatedAt, String updatedBy, String originNode, String note) {
        this.channelId = channelId;
        this.name = name;
        this.desiredState = desiredState;
        this.revision = revision;
        this.deploySeq = deploySeq;
        this.placement = placement;
        this.lifecycle = lifecycle;
        this.stateSeq = stateSeq;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
        this.originNode = originNode;
        this.note = note;
    }

    static ChannelIntent parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] f = Tsv.split(line, FIELDS);
        if (f[0].isBlank()) {
            return null;
        }
        return new ChannelIntent(f[0], f[1],
            f[2].isBlank() ? DEPLOYED : f[2],
            Tsv.parseInt(f[3], 0),
            Tsv.parseLong(f[4], 0L),
            f[5].isBlank() ? PLACEMENT_ALL : f[5],
            f[6],
            Tsv.parseLong(f[7], 0L),
            Tsv.parseLong(f[8], 0L),
            f[9], f[10], f[11]);
    }

    String serialise() {
        return Tsv.join(channelId, name, desiredState, revision, deploySeq, placement,
            lifecycle, stateSeq, updatedAt, updatedBy, originNode, note);
    }

    public String getChannelId() {
        return channelId;
    }

    public String getName() {
        return name;
    }

    public String getDesiredState() {
        return desiredState;
    }

    public boolean isDeployed() {
        return DEPLOYED.equals(desiredState);
    }

    public int getRevision() {
        return revision;
    }

    public long getDeploySeq() {
        return deploySeq;
    }

    public String getPlacement() {
        return placement == null || placement.isBlank() ? PLACEMENT_ALL : placement;
    }

    /** The node a {@code PINNED} placement names, or null for any other placement. */
    public String getPinnedNode() {
        String p = getPlacement();
        return p.startsWith(PLACEMENT_PINNED_PREFIX)
            ? p.substring(PLACEMENT_PINNED_PREFIX.length()).trim() : null;
    }

    public boolean isSingleton() {
        return PLACEMENT_SINGLETON.equals(getPlacement());
    }

    /** STARTED, PAUSED, STOPPED, or blank for "whatever the channel's own initial state says". */
    public String getLifecycle() {
        return lifecycle == null ? "" : lifecycle;
    }

    public long getStateSeq() {
        return stateSeq;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public String getOriginNode() {
        return originNode;
    }

    public String getNote() {
        return note;
    }

    ChannelIntent asDeployed(String channelName, int newRevision, long seq,
                             String by, String origin) {
        return new ChannelIntent(channelId, channelName, DEPLOYED, newRevision, seq,
            getPlacement(), lifecycle, stateSeq, seq, by, origin, note);
    }

    ChannelIntent asUndeployed(long seq, String by, String origin) {
        return new ChannelIntent(channelId, name, UNDEPLOYED, revision, seq,
            getPlacement(), lifecycle, stateSeq, seq, by, origin, note);
    }

    ChannelIntent withPlacement(String newPlacement, long now, String by, String origin) {
        return new ChannelIntent(channelId, name, desiredState, revision, deploySeq,
            newPlacement, lifecycle, stateSeq, now, by, origin, note);
    }

    ChannelIntent withLifecycle(String newLifecycle, long seq, String by, String origin) {
        return new ChannelIntent(channelId, name, desiredState, revision, deploySeq,
            getPlacement(), newLifecycle, seq, seq, by, origin, note);
    }

    ChannelIntent withRedeploy(long seq, String by, String origin) {
        return new ChannelIntent(channelId, name, desiredState, revision, seq,
            getPlacement(), lifecycle, stateSeq, seq, by, origin, note);
    }
}
