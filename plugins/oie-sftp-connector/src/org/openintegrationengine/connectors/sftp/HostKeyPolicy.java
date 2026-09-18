/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp;

/**
 * What to do with the host key the remote server presents -- the SFTP equivalent of
 * deciding whether to trust a TLS server certificate.
 *
 * <p>SSH has no certificate authorities, so there is no "valid signature" to check: the
 * only meaningful verification is against a key you already hold. {@link #TRUST_ANY}
 * therefore accepts whatever the server offers, which encrypts the transfer but proves
 * nothing about who is on the other end and is defeated by anyone who can answer on that
 * address.
 */
public enum HostKeyPolicy {

    /** Accept any host key. Encrypted, but not authenticated -- see the class comment. */
    TRUST_ANY,

    /** Verify against a known_hosts file on the engine's filesystem. */
    KNOWN_HOSTS,

    /** Verify against one key pinned in this connector's settings. */
    PINNED
}
