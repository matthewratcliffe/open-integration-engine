/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp;

import org.apache.commons.lang3.builder.EqualsBuilder;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything needed to open a client connection to a remote SFTP server: where it is, who
 * we claim to be, how we prove it, and how we decide the server is who it claims to be.
 *
 * <p>Shared by both halves that act as a client -- the SFTP Listener in {@link
 * SourceMode#PULL} mode and the SFTP Sender -- so a partner configured for one can be
 * copied to the other field for field. It is a nested object in the channel XML
 * ({@code <connectionProperties>}), which is also why the web panel addresses these
 * fields as {@code connectionProperties.host} and so on.
 */
public class SftpConnectionProperties implements Serializable {

    private String host;
    private String port;
    private String username;
    private AuthMethod authMethod;
    private String password;
    private boolean privateKeyInline;
    private String privateKeyFile;
    private String privateKey;
    private String passphrase;
    private HostKeyPolicy hostKeyPolicy;
    private String knownHostsFile;
    private String hostKey;
    private String connectTimeout;
    private String timeout;
    private Map<String, String> configurationSettings;

    public SftpConnectionProperties() {
        host = "";
        port = "22";
        username = "";
        authMethod = AuthMethod.PASSWORD;
        password = "";
        privateKeyInline = false;
        privateKeyFile = "";
        privateKey = "";
        passphrase = "";
        /*
         * TRUST_ANY matches the File Reader/Writer's SFTP behaviour, which is where most
         * of these settings are migrated from, and it is what makes the connector work
         * against a new partner without a preparatory step. It authenticates nothing: see
         * HostKeyPolicy, and pin the key (or point at a known_hosts file) for anything
         * crossing a network you do not control.
         */
        hostKeyPolicy = HostKeyPolicy.TRUST_ANY;
        knownHostsFile = "";
        hostKey = "";
        connectTimeout = "5000";
        timeout = "10000";
        configurationSettings = new LinkedHashMap<String, String>();
    }

    public SftpConnectionProperties(SftpConnectionProperties props) {
        host = props.getHost();
        port = props.getPort();
        username = props.getUsername();
        authMethod = props.getAuthMethod();
        password = props.getPassword();
        privateKeyInline = props.isPrivateKeyInline();
        privateKeyFile = props.getPrivateKeyFile();
        privateKey = props.getPrivateKey();
        passphrase = props.getPassphrase();
        hostKeyPolicy = props.getHostKeyPolicy();
        knownHostsFile = props.getKnownHostsFile();
        hostKey = props.getHostKey();
        connectTimeout = props.getConnectTimeout();
        timeout = props.getTimeout();
        configurationSettings = new LinkedHashMap<String, String>(props.getConfigurationSettings());
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public AuthMethod getAuthMethod() {
        return authMethod;
    }

    public void setAuthMethod(AuthMethod authMethod) {
        this.authMethod = authMethod;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Whether the private key is held in {@link #getPrivateKey()} rather than read from
     * {@link #getPrivateKeyFile()}.
     *
     * <p>A path is the better answer when the engine has a mounted secret to point at; the
     * inline form exists because a containerised engine often has nowhere durable to put a
     * file, and because it lets the key arrive as a configuration map value like every
     * other secret in this stack.
     */
    public boolean isPrivateKeyInline() {
        return privateKeyInline;
    }

    public void setPrivateKeyInline(boolean privateKeyInline) {
        this.privateKeyInline = privateKeyInline;
    }

    public String getPrivateKeyFile() {
        return privateKeyFile;
    }

    public void setPrivateKeyFile(String privateKeyFile) {
        this.privateKeyFile = privateKeyFile;
    }

    /** The private key itself, in OpenSSH or PEM form, when {@link #isPrivateKeyInline()}. */
    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPassphrase() {
        return passphrase;
    }

    public void setPassphrase(String passphrase) {
        this.passphrase = passphrase;
    }

    public HostKeyPolicy getHostKeyPolicy() {
        return hostKeyPolicy;
    }

    public void setHostKeyPolicy(HostKeyPolicy hostKeyPolicy) {
        this.hostKeyPolicy = hostKeyPolicy;
    }

    public String getKnownHostsFile() {
        return knownHostsFile;
    }

    public void setKnownHostsFile(String knownHostsFile) {
        this.knownHostsFile = knownHostsFile;
    }

    /**
     * The server's public key, pinned. One known_hosts/authorized_keys line --
     * {@code ssh-ed25519 AAAAC3Nz...} -- with or without a leading host pattern, which is
     * ignored because the host is already known from {@link #getHost()}.
     *
     * <p>{@code ssh-keyscan -t ed25519 host} prints exactly this.
     */
    public String getHostKey() {
        return hostKey;
    }

    public void setHostKey(String hostKey) {
        this.hostKey = hostKey;
    }

    /** Milliseconds to wait for the TCP connection and the SSH handshake. */
    public String getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(String connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /** Milliseconds a read or write may block once connected. 0 means no limit. */
    public String getTimeout() {
        return timeout;
    }

    public void setTimeout(String timeout) {
        this.timeout = timeout;
    }

    /**
     * Raw JSch configuration, applied to the session after the built-in settings and
     * before connecting -- so it can override any of them.
     *
     * <p>This is the escape hatch for a partner whose server needs an algorithm outside
     * the defaults: {@code kex}, {@code server_host_key}, {@code cipher.s2c},
     * {@code PubkeyAcceptedAlgorithms} and friends. Old servers usually need one of
     * these; nothing else does.
     */
    public Map<String, String> getConfigurationSettings() {
        return configurationSettings;
    }

    public void setConfigurationSettings(Map<String, String> configurationSettings) {
        this.configurationSettings = configurationSettings;
    }

    /** {@code user@host:port}, for log lines and connector status messages. */
    public String toURIString() {
        return (username == null || username.isEmpty() ? "" : username + "@") + host + ":" + port;
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        int result = host == null ? 0 : host.hashCode();
        result = 31 * result + (port == null ? 0 : port.hashCode());
        result = 31 * result + (username == null ? 0 : username.hashCode());
        return result;
    }

    /**
     * Usage statistics are aggregated across servers. Host, username, password, key and
     * passphrase are all deliberately absent; only the choices are reported.
     */
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new LinkedHashMap<String, Object>();
        purged.put("authMethod", authMethod == null ? null : authMethod.name());
        purged.put("privateKeyInline", privateKeyInline);
        purged.put("hostKeyPolicy", hostKeyPolicy == null ? null : hostKeyPolicy.name());
        purged.put("connectTimeout", connectTimeout);
        purged.put("timeout", timeout);
        purged.put("configurationSettingCount",
                configurationSettings == null ? 0 : configurationSettings.size());
        return purged;
    }
}
