/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.server;

/** One entry from a remote directory listing. */
public class SftpFileInfo {

    private final String directory;
    private final String name;
    private final long size;
    private final long lastModified;
    private final boolean directoryEntry;

    public SftpFileInfo(String directory, String name, long size, long lastModified, boolean directoryEntry) {
        this.directory = directory;
        this.name = name;
        this.size = size;
        this.lastModified = lastModified;
        this.directoryEntry = directoryEntry;
    }

    public String getDirectory() {
        return directory;
    }

    public String getName() {
        return name;
    }

    public long getSize() {
        return size;
    }

    /** Milliseconds since the epoch. SFTP reports seconds; this is already multiplied. */
    public long getLastModified() {
        return lastModified;
    }

    public boolean isDirectory() {
        return directoryEntry;
    }

    public String getAbsolutePath() {
        if (directory == null || directory.isEmpty()) {
            return name;
        }
        // The remote root is "/", so a naive join would report "//file" -- harmless to
        // the server but confusing in a log line that an operator is trying to match
        // against what they see in their own client.
        return directory.endsWith("/") ? directory + name : directory + "/" + name;
    }

    @Override
    public String toString() {
        return getAbsolutePath();
    }
}
