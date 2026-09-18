/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.server;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.Handle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.openintegrationengine.connectors.sftp.FilenameMatcher;
import org.openintegrationengine.connectors.sftp.SftpServerUser;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SFTP server the SFTP Listener runs in {@link
 * org.openintegrationengine.connectors.sftp.SourceMode#PUSH} mode: partners connect to
 * the engine and upload, and each completed upload becomes a message.
 *
 * <p>Apache MINA SSHD does the SSH and SFTP protocol work. Only the SFTP subsystem is
 * enabled -- no shell, no exec, no port forwarding -- so an account here can move files
 * and nothing else, and each session's filesystem is rooted at the account's directory,
 * which is what confines it.
 *
 * <h2>When an upload is "complete"</h2>
 *
 * A file that is still being written must never be dispatched, so completion is taken
 * from two events, never from a timer:
 *
 * <ul>
 *   <li>the client closing a handle it opened for writing, and</li>
 *   <li>a rename into place, which is how well-behaved clients publish a file they wrote
 *       under a temporary name.</li>
 * </ul>
 *
 * <p>A rename only dispatches when the <em>source</em> name was one the filter rejects --
 * the signature of exactly that temporary-file pattern. That single rule is what stops
 * the same content being dispatched twice by clients that both write and rename.
 */
public class EmbeddedSftpServer {

    private static final Logger logger = LogManager.getLogger(EmbeddedSftpServer.class);

    /** Called on the client's own session thread as each upload completes. */
    public interface UploadHandler {
        void onUpload(Path file, String username, String remoteAddress);
    }

    private final Settings settings;
    private final UploadHandler handler;
    private final FilenameMatcher matcher;
    private final Map<String, List<PublicKey>> authorizedKeys = new LinkedHashMap<String, List<PublicKey>>();

    private SshServer server;

    public EmbeddedSftpServer(Settings settings, FilenameMatcher matcher, UploadHandler handler) {
        this.settings = settings;
        this.matcher = matcher;
        this.handler = handler;
    }

    /**
     * Everything the server needs, already resolved: template values expanded, paths made
     * absolute, numbers parsed. Keeping the parsing in the connector rather than in here
     * means a bad value is a deploy error naming the field, not a stack trace from inside
     * MINA on the first connection.
     */
    public static class Settings {
        public String host = "0.0.0.0";
        public int port = 2222;
        public Path rootDirectory;
        public Path hostKeyFile;
        public String hostKeyAlgorithm = "EC";
        public int hostKeySize = 256;
        public int maxSessions = 10;
        public long authTimeoutMillis = 30000;
        public long idleTimeoutMillis = 300000;
        public boolean allowDownloads;
        public boolean dispatchOnRename = true;
        public List<SftpServerUser> users = new ArrayList<SftpServerUser>();
    }

    public void start() throws IOException {
        Files.createDirectories(settings.rootDirectory);

        for (SftpServerUser user : settings.users) {
            authorizedKeys.put(user.getUsername(), parseAuthorizedKeys(user));
            Path home = homeDirectory(user);
            Files.createDirectories(home);
        }

        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost(StringUtils.defaultIfBlank(settings.host, "0.0.0.0"));
        sshd.setPort(settings.port);

        SimpleGeneratorHostKeyProvider hostKeyProvider = new SimpleGeneratorHostKeyProvider(settings.hostKeyFile);
        hostKeyProvider.setAlgorithm(settings.hostKeyAlgorithm);
        hostKeyProvider.setKeySize(settings.hostKeySize);
        // The stored key is the server's identity. Regenerating it over the top would make
        // every client report a changed host key, so a load failure must be loud, not
        // silently repaired.
        hostKeyProvider.setOverwriteAllowed(false);
        sshd.setKeyPairProvider(hostKeyProvider);

        sshd.setPasswordAuthenticator(this::authenticatePassword);
        sshd.setPublickeyAuthenticator(this::authenticatePublicKey);
        // Anything not explicitly configured is refused: no keyboard-interactive, no GSS,
        // no host-based.
        sshd.setKeyboardInteractiveAuthenticator(null);

        VirtualFileSystemFactory fileSystemFactory = new VirtualFileSystemFactory(settings.rootDirectory);
        for (SftpServerUser user : settings.users) {
            fileSystemFactory.setUserHomeDir(user.getUsername(), homeDirectory(user));
        }
        sshd.setFileSystemFactory(fileSystemFactory);

        SftpSubsystemFactory sftpFactory = new SftpSubsystemFactory.Builder().build();
        sftpFactory.addSftpEventListener(new UploadListener());
        sshd.setSubsystemFactories(Collections.<org.apache.sshd.server.subsystem.SubsystemFactory>singletonList(sftpFactory));
        // No shell and no exec: this is a file drop, not an account on a host.
        sshd.setShellFactory(null);
        sshd.setCommandFactory(null);

        CoreModuleProperties.MAX_CONCURRENT_SESSIONS.set(sshd, settings.maxSessions);
        CoreModuleProperties.AUTH_TIMEOUT.set(sshd, Duration.ofMillis(settings.authTimeoutMillis));
        CoreModuleProperties.IDLE_TIMEOUT.set(sshd, Duration.ofMillis(settings.idleTimeoutMillis));

        sshd.start();
        this.server = sshd;

        logger.info("SFTP server listening on {}:{} rooted at {}",
                sshd.getHost(), sshd.getPort(), settings.rootDirectory);
    }

    public void stop() {
        SshServer sshd = server;
        server = null;
        if (sshd == null) {
            return;
        }
        try {
            // Immediate: a stopping channel should not wait on a partner's idle session.
            sshd.stop(true);
        } catch (Exception e) {
            logger.warn("Error stopping SFTP server", e);
        }
    }

    public boolean isRunning() {
        SshServer sshd = server;
        return sshd != null && sshd.isOpen();
    }

    /** The directory an account is confined to, always inside the root. */
    public Path homeDirectory(SftpServerUser user) {
        String home = StringUtils.trimToEmpty(user.getHomeDirectory());
        if (home.isEmpty()) {
            return settings.rootDirectory;
        }
        Path resolved = settings.rootDirectory.resolve(home).normalize();
        if (!resolved.startsWith(settings.rootDirectory)) {
            // A home of "../elsewhere" would hand the account the filesystem. It is
            // rejected at deploy time too; this is the belt to that's braces.
            throw new IllegalArgumentException("User \"" + user.getUsername()
                    + "\" has a home directory outside the server root: " + user.getHomeDirectory());
        }
        return resolved;
    }

    private static List<PublicKey> parseAuthorizedKeys(SftpServerUser user) throws IOException {
        List<PublicKey> keys = new ArrayList<PublicKey>();
        String text = StringUtils.trimToEmpty(user.getAuthorizedKeys());
        if (text.isEmpty()) {
            return keys;
        }
        for (String line : text.split("\\r?\\n")) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            try {
                AuthorizedKeyEntry parsed = AuthorizedKeyEntry.parseAuthorizedKeyEntry(entry);
                keys.add(parsed.resolvePublicKey(null, PublicKeyEntryResolver.FAILING));
            } catch (Exception e) {
                throw new IOException("User \"" + user.getUsername()
                        + "\" has an authorized key that could not be read: " + e.getMessage(), e);
            }
        }
        return keys;
    }

    private SftpServerUser findUser(String username) {
        for (SftpServerUser user : settings.users) {
            if (user.getUsername() != null && user.getUsername().equals(username)) {
                return user;
            }
        }
        return null;
    }

    private boolean authenticatePassword(String username, String password, ServerSession session) {
        SftpServerUser user = findUser(username);
        // An account with no password refuses password authentication outright rather than
        // treating the empty string as its password.
        if (user == null || StringUtils.isEmpty(user.getPassword())) {
            logger.warn("SFTP password authentication refused for \"{}\" from {}", username, remoteAddress(session));
            return false;
        }
        boolean ok = MessageDigest.isEqual(user.getPassword().getBytes(StandardCharsets.UTF_8),
                password == null ? new byte[0] : password.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            logger.warn("SFTP password authentication failed for \"{}\" from {}", username, remoteAddress(session));
        }
        return ok;
    }

    private boolean authenticatePublicKey(String username, PublicKey key, ServerSession session) {
        List<PublicKey> keys = authorizedKeys.get(username);
        if (keys == null || keys.isEmpty()) {
            logger.warn("SFTP public key authentication refused for \"{}\" from {}", username, remoteAddress(session));
            return false;
        }
        for (PublicKey authorized : keys) {
            if (KeyUtils.compareKeys(authorized, key)) {
                return true;
            }
        }
        logger.warn("SFTP public key authentication failed for \"{}\" from {} (key {})",
                username, remoteAddress(session), KeyUtils.getKeyType(key));
        return false;
    }

    private static String remoteAddress(ServerSession session) {
        if (session == null) {
            return "unknown";
        }
        SocketAddress address = session.getClientAddress();
        return address == null ? "unknown" : address.toString();
    }

    private boolean isReadOnly(String username) {
        SftpServerUser user = findUser(username);
        return user != null && user.isReadOnly();
    }

    /**
     * Enforces the two access rules the account model promises, and turns completed
     * uploads into messages.
     *
     * <p>Vetoing in {@code opening} is what makes "no downloads" and "read only" real:
     * MINA turns the IOException into an SFTP permission-denied status, so the client is
     * told no rather than quietly handed an empty result.
     */
    private class UploadListener implements SftpEventListener {

        @Override
        public void opening(ServerSession session, String remoteHandle, Handle localHandle) throws IOException {
            if (!(localHandle instanceof FileHandle)) {
                // A directory handle: listing is part of uploading (clients stat and list
                // before writing), so it is refused only for accounts that may do nothing.
                return;
            }
            Set<StandardOpenOption> options = ((FileHandle) localHandle).getOpenOptions();
            String username = session.getUsername();

            boolean writing = options.contains(StandardOpenOption.WRITE)
                    || options.contains(StandardOpenOption.APPEND)
                    || options.contains(StandardOpenOption.CREATE)
                    || options.contains(StandardOpenOption.CREATE_NEW);

            if (writing && isReadOnly(username)) {
                throw new IOException("Account is read only");
            }
            if (!writing && !settings.allowDownloads) {
                throw new IOException("Downloads are not permitted on this server");
            }
        }

        @Override
        public void closed(ServerSession session, String remoteHandle, Handle localHandle, Throwable thrown) {
            if (thrown != null || !(localHandle instanceof FileHandle)) {
                return;
            }
            Set<StandardOpenOption> options = ((FileHandle) localHandle).getOpenOptions();
            if (!options.contains(StandardOpenOption.WRITE) && !options.contains(StandardOpenOption.APPEND)) {
                return;
            }
            Path file = localHandle.getFile();
            if (file == null || !matcher.accept(file.getFileName().toString())) {
                return;
            }
            dispatch(session, file);
        }

        @Override
        public void moved(ServerSession session, Path source, Path target,
                Collection<CopyOption> options, Throwable thrown) {
            if (thrown != null || !settings.dispatchOnRename || target == null || source == null) {
                return;
            }
            /*
             * Only a rename OUT of a name the filter rejects INTO one it accepts: that is
             * the publish step of a write-to-temporary-then-rename client. A rename between
             * two accepted names was already dispatched when the first one was written, and
             * dispatching again would duplicate the message.
             */
            if (matcher.accept(source.getFileName().toString())) {
                return;
            }
            if (!matcher.accept(target.getFileName().toString())) {
                return;
            }
            dispatch(session, target);
        }

        private void dispatch(ServerSession session, Path file) {
            try {
                if (!Files.isRegularFile(file)) {
                    return;
                }
                handler.onUpload(file, session.getUsername(), remoteAddress(session));
            } catch (Exception e) {
                // The client is told nothing: SFTP has no way to report that the channel
                // behind the drop box failed, and failing the transfer would make the
                // partner resend a file that was received correctly.
                logger.error("Error handling SFTP upload of {}", file, e);
            }
        }
    }
}
