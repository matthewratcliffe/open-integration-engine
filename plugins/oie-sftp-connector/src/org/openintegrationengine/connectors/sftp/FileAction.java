/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp;

/**
 * What happens to a file once the channel has taken it. Mirrors the File Reader's action
 * of the same name so the two connectors behave the same way when swapped.
 */
public enum FileAction {

    /** Leave it where it is. */
    NONE,

    /** Move (and optionally rename) it. */
    MOVE,

    /** Delete it. */
    DELETE
}
