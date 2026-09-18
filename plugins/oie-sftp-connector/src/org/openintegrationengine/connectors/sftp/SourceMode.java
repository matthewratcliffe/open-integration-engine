/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp;

/**
 * Which direction the SFTP Listener works in.
 *
 * <p>Serialised into the channel XML by name, so these constants are part of the on-disk
 * format: renaming one invalidates every channel using it.
 */
public enum SourceMode {

    /** The engine runs the SFTP server and partners connect to it and drop files. */
    PUSH,

    /** The engine is the client, polling a remote SFTP server on a schedule. */
    PULL;

    public static SourceMode fromName(String name) {
        for (SourceMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
                return mode;
            }
        }
        return PUSH;
    }
}
