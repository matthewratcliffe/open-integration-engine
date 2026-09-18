/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.server;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openintegrationengine.connectors.sftp.AuthMethod;
import org.openintegrationengine.connectors.sftp.HostKeyPolicy;
import org.openintegrationengine.connectors.sftp.SftpConnectionProperties;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/**
 * One SSH session with an SFTP channel on it, wrapped in the few operations this
 * connector needs.
 *
 * <p>JSch is used rather than Apache MINA's client because the engine already ships
 * jsch-2.27.7 on its own classpath -- the same library the built-in File connector uses
 * for its SFTP scheme -- so the client half of this connector adds no jars at all and
 * behaves the way a channel migrated from the File Reader/Writer already behaves.
 *
 * <p>Not thread safe: one instance belongs to one thread at a time, which is what {@link
 * SftpClientPool} exists to arrange.
 */
public class SftpClient implements Closeable {

    private static final Logger logger = LogManager.getLogger(SftpClient.class);

    private final String signature;
    private final Session session;
    private final ChannelSftp channel;
    private final String home;

    private SftpClient(String signature, Session session, ChannelSftp channel) throws SftpException {
        this.signature = signature;
        this.session = session;
        this.channel = channel;
        this.home = channel.pwd();
    }

    /**
     * Opens a session. Every failure -- unreachable host, refused credentials, rejected
     * host key -- arrives as an {@link IOException} carrying JSch's own message, because
     * those messages ("Auth fail", "reject HostKey", "UnknownHostKey") are the ones that
     * tell an operator which of the four things is wrong.
     */
    public static SftpClient connect(SftpConnectionProperties props) throws IOException {
        String host = StringUtils.trimToEmpty(props.getHost());
        if (host.isEmpty()) {
            throw new IOException("No host configured");
        }
        int port = NumberUtils.toInt(StringUtils.trimToEmpty(props.getPort()), 22);
        int connectTimeout = NumberUtils.toInt(StringUtils.trimToEmpty(props.getConnectTimeout()), 5000);
        int timeout = NumberUtils.toInt(StringUtils.trimToEmpty(props.getTimeout()), 0);
        AuthMethod authMethod = props.getAuthMethod() == null ? AuthMethod.PASSWORD : props.getAuthMethod();

        Session session = null;
        try {
            JSch jsch = new JSch();

            if (authMethod.usesPublicKey()) {
                addIdentity(jsch, props);
            }
            configureHostKeyPolicy(jsch, props, host, port);

            session = jsch.getSession(StringUtils.trimToEmpty(props.getUsername()), host, port);

            if (authMethod.usesPassword() && props.getPassword() != null) {
                session.setPassword(props.getPassword());
            }
            session.setConfig("PreferredAuthentications", authMethod.getPreferredAuthentications());
            session.setConfig("StrictHostKeyChecking",
                    props.getHostKeyPolicy() == HostKeyPolicy.TRUST_ANY ? "no" : "yes");

            // Last, so an operator can override anything above for an awkward server.
            Map<String, String> settings = props.getConfigurationSettings();
            if (settings != null) {
                for (Map.Entry<String, String> entry : settings.entrySet()) {
                    if (StringUtils.isNotBlank(entry.getKey())) {
                        session.setConfig(entry.getKey(), StringUtils.trimToEmpty(entry.getValue()));
                    }
                }
            }

            if (timeout > 0) {
                session.setTimeout(timeout);
            }
            session.connect(connectTimeout);

            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(connectTimeout);

            return new SftpClient(signature(props), session, channel);
        } catch (Exception e) {
            if (session != null) {
                session.disconnect();
            }
            throw new IOException("Could not connect to sftp://" + props.toURIString() + ": " + rootMessage(e), e);
        }
    }

    /**
     * A key for {@link SftpClientPool}: two connections are interchangeable only if every
     * value that shapes the session is identical. The credentials are part of it because
     * they can be templated per message, and handing a message a session opened as a
     * different user would be a data leak, not a cache hit.
     */
    public static String signature(SftpConnectionProperties props) {
        StringBuilder key = new StringBuilder();
        key.append(props.getHost()).append((char) 0)
                .append(props.getPort()).append((char) 0)
                .append(props.getUsername()).append((char) 0)
                .append(props.getAuthMethod()).append((char) 0)
                .append(props.getPassword()).append((char) 0)
                .append(props.isPrivateKeyInline()).append((char) 0)
                .append(props.getPrivateKeyFile()).append((char) 0)
                .append(props.getPrivateKey() == null ? 0 : props.getPrivateKey().hashCode()).append((char) 0)
                .append(props.getPassphrase() == null ? 0 : props.getPassphrase().hashCode()).append((char) 0)
                .append(props.getHostKeyPolicy()).append((char) 0)
                .append(props.getKnownHostsFile()).append((char) 0)
                .append(props.getHostKey()).append((char) 0)
                .append(props.getConnectTimeout()).append((char) 0)
                .append(props.getTimeout()).append((char) 0)
                .append(props.getConfigurationSettings());
        return key.toString();
    }

    private static void addIdentity(JSch jsch, SftpConnectionProperties props) throws JSchException, IOException {
        byte[] passphrase = StringUtils.isEmpty(props.getPassphrase()) ? null
                : props.getPassphrase().getBytes(StandardCharsets.UTF_8);

        if (props.isPrivateKeyInline()) {
            String key = props.getPrivateKey();
            if (StringUtils.isBlank(key)) {
                throw new IOException("Public key authentication is selected but no private key was supplied");
            }
            /*
             * OpenSSH refuses a key file whose final line has no newline, and so does
             * JSch's parser for some formats. A key pasted into a text area usually has
             * none, so add it rather than failing with "invalid privatekey".
             */
            if (!key.endsWith("\n")) {
                key = key + "\n";
            }
            jsch.addIdentity("connector-key", key.getBytes(StandardCharsets.UTF_8), null, passphrase);
        } else {
            String path = StringUtils.trimToEmpty(props.getPrivateKeyFile());
            if (path.isEmpty()) {
                throw new IOException("Public key authentication is selected but no private key file was supplied");
            }
            if (!Files.isReadable(Paths.get(path))) {
                throw new IOException("Private key file is not readable: " + path);
            }
            jsch.addIdentity(path, passphrase == null ? null : new String(passphrase, StandardCharsets.UTF_8));
        }
    }

    private static void configureHostKeyPolicy(JSch jsch, SftpConnectionProperties props, String host, int port)
            throws JSchException, IOException {
        HostKeyPolicy policy = props.getHostKeyPolicy() == null ? HostKeyPolicy.TRUST_ANY : props.getHostKeyPolicy();

        switch (policy) {
            case KNOWN_HOSTS:
                String knownHosts = StringUtils.trimToEmpty(props.getKnownHostsFile());
                if (knownHosts.isEmpty()) {
                    throw new IOException("Host key policy is KNOWN_HOSTS but no known_hosts file was supplied");
                }
                if (!Files.isReadable(Paths.get(knownHosts))) {
                    throw new IOException("known_hosts file is not readable: " + knownHosts);
                }
                jsch.setKnownHosts(knownHosts);
                break;

            case PINNED:
                byte[] knownHostsEntry = pinnedKnownHosts(props.getHostKey(), host, port);
                try {
                    jsch.setKnownHosts(new ByteArrayInputStream(knownHostsEntry));
                } catch (Exception e) {
                    /*
                     * JSch reports a key whose base64 does not decode to a well-formed key
                     * as an index-out-of-bounds from deep inside its parser, which tells an
                     * operator nothing at all about the value they pasted.
                     */
                    throw new IOException("The pinned host key could not be read. Expected one"
                            + " known_hosts line, such as the output of"
                            + " \"ssh-keyscan -t ed25519 " + host + "\".", e);
                }
                break;

            case TRUST_ANY:
            default:
                break;
        }
    }

    /**
     * The pinned key as an in-memory known_hosts file.
     *
     * <p>A pasted key may or may not carry a host pattern in front of it -- {@code
     * ssh-keyscan} emits one, the server's own {@code /etc/ssh/ssh_host_ed25519_key.pub}
     * does not -- so whatever is there is dropped and the configured host substituted.
     * Both the plain and the bracketed-with-port forms are written, because JSch looks up
     * {@code [host]:port} for any port other than 22 and would otherwise find nothing.
     */
    static byte[] pinnedKnownHosts(String hostKeyLine, String host, int port) throws IOException {
        String line = StringUtils.trimToEmpty(hostKeyLine);
        if (line.isEmpty()) {
            throw new IOException("Host key policy is PINNED but no host key was supplied");
        }
        // Strip a comment tail and normalise whitespace.
        String[] fields = line.split("\\s+");
        int typeIndex = -1;
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].startsWith("ssh-") || fields[i].startsWith("ecdsa-") || fields[i].startsWith("sk-")) {
                typeIndex = i;
                break;
            }
        }
        if (typeIndex < 0 || typeIndex + 1 >= fields.length) {
            throw new IOException("Host key is not in known_hosts/authorized_keys form"
                    + " (expected something like \"ssh-ed25519 AAAAC3Nz...\")");
        }
        String type = fields[typeIndex];
        String key = fields[typeIndex + 1];

        StringBuilder out = new StringBuilder();
        out.append(host).append(' ').append(type).append(' ').append(key).append('\n');
        if (port != 22) {
            out.append('[').append(host).append("]:").append(port)
                    .append(' ').append(type).append(' ').append(key).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public String getSignature() {
        return signature;
    }

    public boolean isConnected() {
        return session != null && session.isConnected() && channel != null && channel.isConnected();
    }

    /**
     * Lists a directory, dropping {@code .} and {@code ..}. An empty directory argument
     * means the login directory.
     */
    public List<SftpFileInfo> list(String directory) throws IOException {
        String path = resolve(directory);
        List<SftpFileInfo> files = new ArrayList<SftpFileInfo>();
        try {
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> entries = channel.ls(path);
            for (ChannelSftp.LsEntry entry : entries) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                SftpATTRS attrs = entry.getAttrs();
                files.add(new SftpFileInfo(path, name, attrs.getSize(),
                        attrs.getMTime() * 1000L, attrs.isDir()));
            }
        } catch (SftpException e) {
            throw new IOException("Could not list " + path + ": " + e.getMessage(), e);
        }
        return files;
    }

    /** The whole file, read into memory. Close is the caller's business. */
    public InputStream read(String directory, String filename) throws IOException {
        try {
            return channel.get(join(resolve(directory), filename));
        } catch (SftpException e) {
            throw new IOException("Could not read " + join(directory, filename) + ": " + e.getMessage(), e);
        }
    }

    public void write(String directory, String filename, boolean append, InputStream contents) throws IOException {
        String path = join(resolve(directory), filename);
        try {
            channel.put(contents, path, append ? ChannelSftp.APPEND : ChannelSftp.OVERWRITE);
        } catch (SftpException e) {
            throw new IOException("Could not write " + path + ": " + e.getMessage(), e);
        }
    }

    public boolean exists(String directory, String filename) throws IOException {
        try {
            channel.stat(join(resolve(directory), filename));
            return true;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw new IOException("Could not stat " + join(directory, filename) + ": " + e.getMessage(), e);
        }
    }

    public void move(String fromDirectory, String fromName, String toDirectory, String toName) throws IOException {
        String from = join(resolve(fromDirectory), fromName);
        String to = join(resolve(toDirectory), toName);
        try {
            /*
             * SFTP rename fails if the target exists, and most servers will not tell you
             * that is why. Removing it first makes "move to processed/" work on the second
             * delivery of the same filename, which is the normal case and not an error.
             */
            try {
                channel.rm(to);
            } catch (SftpException ignored) {
                // Nothing there, or no permission to remove it; the rename reports either.
            }
            channel.rename(from, to);
        } catch (SftpException e) {
            throw new IOException("Could not move " + from + " to " + to + ": " + e.getMessage(), e);
        }
    }

    public void delete(String directory, String filename) throws IOException {
        String path = join(resolve(directory), filename);
        try {
            channel.rm(path);
        } catch (SftpException e) {
            throw new IOException("Could not delete " + path + ": " + e.getMessage(), e);
        }
    }

    /** Creates a directory and any missing parents, ignoring ones that already exist. */
    public void makeDirectories(String directory) throws IOException {
        String path = resolve(directory);
        if (path.isEmpty()) {
            return;
        }
        StringBuilder built = new StringBuilder();
        if (path.startsWith("/")) {
            built.append('/');
        }
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            if (built.length() > 0 && built.charAt(built.length() - 1) != '/') {
                built.append('/');
            }
            built.append(segment);

            String current = built.toString();
            try {
                channel.stat(current);
            } catch (SftpException notThere) {
                try {
                    channel.mkdir(current);
                } catch (SftpException e) {
                    // A concurrent sender may have won the race; only a still-missing
                    // directory is a real failure.
                    try {
                        channel.stat(current);
                    } catch (SftpException stillMissing) {
                        throw new IOException("Could not create directory " + current + ": " + e.getMessage(), e);
                    }
                }
            }
        }
    }

    public void chmod(String directory, String filename, String octalPermissions) throws IOException {
        String path = join(resolve(directory), filename);
        int permissions;
        try {
            permissions = Integer.parseInt(StringUtils.trimToEmpty(octalPermissions), 8);
        } catch (NumberFormatException e) {
            throw new IOException("File permissions must be octal, such as 640: " + octalPermissions, e);
        }
        try {
            channel.chmod(permissions, path);
        } catch (SftpException e) {
            throw new IOException("Could not set permissions on " + path + ": " + e.getMessage(), e);
        }
    }

    /** Absolute paths are used as given; a relative one is taken from the login directory. */
    private String resolve(String directory) {
        String path = StringUtils.trimToEmpty(directory);
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return home;
        }
        if (path.startsWith("/")) {
            return path;
        }
        return join(home, path);
    }

    private static String join(String directory, String name) {
        if (StringUtils.isEmpty(directory)) {
            return name;
        }
        if (directory.endsWith("/")) {
            return directory + name;
        }
        return directory + "/" + name;
    }

    @Override
    public void close() {
        try {
            if (channel != null) {
                channel.disconnect();
            }
        } catch (Exception e) {
            logger.debug("Error closing SFTP channel", e);
        }
        try {
            if (session != null) {
                session.disconnect();
            }
        } catch (Exception e) {
            logger.debug("Error closing SSH session", e);
        }
    }

    /**
     * JSch nests the interesting part -- "Auth fail", "UnknownHostKey", "Connection
     * refused" -- inside a generic wrapper often enough that the outer message alone is
     * not worth reporting.
     */
    static String rootMessage(Throwable t) {
        Throwable current = t;
        String message = current.getMessage();
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            if (StringUtils.isNotBlank(current.getMessage())) {
                message = current.getMessage();
            }
        }
        if (StringUtils.isBlank(message)) {
            message = t.getClass().getSimpleName();
        }
        return message;
    }
}
