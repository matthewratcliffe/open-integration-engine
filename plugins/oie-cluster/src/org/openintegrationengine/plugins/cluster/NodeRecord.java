/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.plugins.cluster;

/**
 * One engine in the cluster, as the registry holds it.
 *
 * <p>The id is the engine's own {@code server.id}, not an identifier of this plugin's
 * invention. That is deliberate: it is the id the message, connector message and
 * statistics rows are already stamped with, so a node in this registry and the owner of a
 * queued message are the same thing named the same way, and no mapping table has to be
 * kept correct between them.
 */
public final class NodeRecord {

    private static final int FIELDS = 9;

    private final String nodeId;
    private final String name;
    private final String role;
    private final String address;
    private final String version;
    private final long startedAt;
    private final long lastSeen;
    private final int deployedCount;
    private final String note;

    public NodeRecord(String nodeId, String name, String role, String address, String version,
                      long startedAt, long lastSeen, int deployedCount, String note) {
        this.nodeId = nodeId;
        this.name = name;
        this.role = role;
        this.address = address;
        this.version = version;
        this.startedAt = startedAt;
        this.lastSeen = lastSeen;
        this.deployedCount = deployedCount;
        this.note = note;
    }

    static NodeRecord parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] f = Tsv.split(line, FIELDS);
        if (f[0].isBlank()) {
            return null;
        }
        return new NodeRecord(f[0], f[1], f[2], f[3], f[4],
            Tsv.parseLong(f[5], 0L), Tsv.parseLong(f[6], 0L), Tsv.parseInt(f[7], 0), f[8]);
    }

    String serialise() {
        return Tsv.join(nodeId, name, role, address, version,
            startedAt, lastSeen, deployedCount, note);
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getName() {
        return name == null || name.isBlank() ? nodeId : name;
    }

    public String getRole() {
        return role == null || role.isBlank() ? ClusterSettings.ROLE_UTILITY : role;
    }

    public String getAddress() {
        return address;
    }

    public String getVersion() {
        return version;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public int getDeployedCount() {
        return deployedCount;
    }

    public String getNote() {
        return note;
    }

    /**
     * Whether the node has heartbeated recently enough to be counted as present.
     *
     * <p>Everything that matters hangs off this: which node owns the singleton channels,
     * whether a deployment counts as complete, and whether queued messages are orphaned.
     * So it is one threshold, read from settings, rather than a different judgement in
     * each of those places.
     */
    public boolean isLive(long now, long staleMillis) {
        return now - lastSeen <= staleMillis;
    }

    public NodeRecord withHeartbeat(long now, int deployed, String newNote) {
        return new NodeRecord(nodeId, name, role, address, version,
            startedAt, now, deployed, newNote);
    }
}
