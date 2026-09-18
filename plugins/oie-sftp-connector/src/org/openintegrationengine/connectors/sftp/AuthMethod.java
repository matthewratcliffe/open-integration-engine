/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp;

/**
 * How this connector authenticates itself to a remote SFTP server.
 *
 * <p>{@link #BOTH} offers the key first and falls back to the password, which is what an
 * OpenSSH client does by default and the only setting that works against a server which
 * accepts either depending on the account.
 */
public enum AuthMethod {

    PASSWORD("password"),
    PUBLIC_KEY("publickey"),
    BOTH("publickey,password");

    private final String preferredAuthentications;

    AuthMethod(String preferredAuthentications) {
        this.preferredAuthentications = preferredAuthentications;
    }

    /** The value for JSch's {@code PreferredAuthentications}, in the order to try. */
    public String getPreferredAuthentications() {
        return preferredAuthentications;
    }

    public boolean usesPassword() {
        return this == PASSWORD || this == BOTH;
    }

    public boolean usesPublicKey() {
        return this == PUBLIC_KEY || this == BOTH;
    }
}
