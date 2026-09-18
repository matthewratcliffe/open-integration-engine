/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp;

import org.apache.commons.lang3.builder.EqualsBuilder;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One account the embedded SFTP server accepts.
 *
 * <p>A user may authenticate with a password, with a public key, or with either: an empty
 * password means password authentication is refused for this account, and an empty
 * {@link #getAuthorizedKeys() authorizedKeys} means public key authentication is. Both
 * empty is rejected at deploy time rather than silently creating an account that nothing
 * can log into -- or, worse, one that anything can.
 *
 * <p>The password is held as typed. Channel XML is not an encrypted store, so the useful
 * pattern is a configuration map reference by its bare key -- {@code ${partnerPassword}},
 * not {@code ${configurationMap.partnerPassword}}, which is the JavaScript API's form and
 * resolves to nothing in a template. That keeps the secret in the configuration map, and
 * out of git, and expands it when the channel deploys.
 */
public class SftpServerUser implements Serializable {

    private String username;
    private String password;
    private String authorizedKeys;
    private String homeDirectory;
    private boolean readOnly;

    public SftpServerUser() {
        username = "";
        password = "";
        authorizedKeys = "";
        homeDirectory = "";
        readOnly = false;
    }

    public SftpServerUser(SftpServerUser props) {
        username = props.getUsername();
        password = props.getPassword();
        authorizedKeys = props.getAuthorizedKeys();
        homeDirectory = props.getHomeDirectory();
        readOnly = props.isReadOnly();
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * One or more public keys in {@code authorized_keys} format, one per line -- the exact
     * lines you would paste into {@code ~/.ssh/authorized_keys} on a Unix host.
     */
    public String getAuthorizedKeys() {
        return authorizedKeys;
    }

    public void setAuthorizedKeys(String authorizedKeys) {
        this.authorizedKeys = authorizedKeys;
    }

    /**
     * This account's directory, relative to the server's root directory. Empty means the
     * root itself. The user is confined to it: the SFTP session is rooted there and cannot
     * see, or escape to, anything above it.
     */
    public String getHomeDirectory() {
        return homeDirectory;
    }

    public void setHomeDirectory(String homeDirectory) {
        this.homeDirectory = homeDirectory;
    }

    /** Allow listing and downloading, refuse writes. Useful for a collection account. */
    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        return username == null ? 0 : username.hashCode();
    }

    /**
     * Usage statistics are aggregated across servers, so this reports only the shape of
     * the account -- never the username, the password or a key.
     */
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new LinkedHashMap<String, Object>();
        purged.put("passwordSet", password != null && !password.isEmpty());
        purged.put("authorizedKeysSet", authorizedKeys != null && !authorizedKeys.trim().isEmpty());
        purged.put("scopedHome", homeDirectory != null && !homeDirectory.trim().isEmpty());
        purged.put("readOnly", readOnly);
        return purged;
    }
}
