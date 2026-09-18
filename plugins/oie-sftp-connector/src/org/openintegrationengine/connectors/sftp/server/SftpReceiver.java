/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.server;

import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.model.message.BatchRawMessage;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.DispatchResult;
import com.mirth.connect.donkey.server.channel.PollConnector;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.donkey.server.message.batch.BatchMessageReader;
import com.mirth.connect.donkey.util.ThreadUtils;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.util.TemplateValueReplacer;
import com.mirth.connect.util.CharsetUtils;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openintegrationengine.connectors.sftp.FileAction;
import org.openintegrationengine.connectors.sftp.FilenameMatcher;
import org.openintegrationengine.connectors.sftp.SftpConnectionProperties;
import org.openintegrationengine.connectors.sftp.SftpReceiverProperties;
import org.openintegrationengine.connectors.sftp.SftpServerUser;
import org.openintegrationengine.connectors.sftp.SourceMode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The SFTP Listener.
 *
 * <p>Two shapes behind one connector, chosen by {@link SftpReceiverProperties#getMode()}:
 *
 * <ul>
 *   <li>PUSH -- {@link EmbeddedSftpServer} runs inside the engine and calls back as each
 *       upload completes. Nothing is polled; the quartz schedule this connector inherits
 *       from {@link PollConnector} simply has nothing to do, and {@link #poll()} returns
 *       immediately.</li>
 *   <li>PULL -- {@link #poll()} connects out on the schedule, lists the remote directory
 *       and takes what matches.</li>
 * </ul>
 *
 * <p>Everything after "we have the bytes" is shared, which is the point of doing both in
 * one connector: the filter, the charset handling, batch splitting and the
 * after-processing action behave identically whichever way the file arrived.
 */
public class SftpReceiver extends PollConnector {

    private final Logger logger = LogManager.getLogger(getClass());
    private final EventController eventController = ControllerFactory.getFactory().createEventController();
    private final ConfigurationController configurationController =
            ControllerFactory.getFactory().createConfigurationController();
    private final TemplateValueReplacer replacer = new TemplateValueReplacer();

    private SftpReceiverProperties connectorProperties;
    private String charsetEncoding;
    private FilenameMatcher matcher;

    /* push */
    private EmbeddedSftpServer server;
    private Path rootDirectory;

    /* pull */
    private SftpClientPool pool;

    @Override
    public void onDeploy() throws ConnectorTaskException {
        connectorProperties = (SftpReceiverProperties) getConnectorProperties();

        if (connectorProperties.isBinary() && isProcessBatch()) {
            throw new ConnectorTaskException("Batch processing is not supported for binary data.");
        }

        try {
            matcher = new FilenameMatcher(connectorProperties.getFileFilter(),
                    connectorProperties.isRegex(), connectorProperties.isIgnoreDot());
        } catch (IllegalArgumentException e) {
            throw new ConnectorTaskException(e.getMessage(), e);
        }

        charsetEncoding = CharsetUtils.getEncoding(connectorProperties.getCharsetEncoding(),
                System.getProperty("ca.uhn.hl7v2.llp.charset"));
        try {
            Charset.forName(charsetEncoding);
        } catch (Exception e) {
            throw new ConnectorTaskException("Unsupported character set encoding: " + charsetEncoding, e);
        }

        if (connectorProperties.getMode() == SourceMode.PULL) {
            pool = new SftpClientPool();
            if (StringUtils.isBlank(connectorProperties.getConnectionProperties().getHost())) {
                throw new ConnectorTaskException("No host configured for the remote SFTP server.");
            }
        } else {
            validatePushSettings();
        }

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getSourceName(), ConnectionStatusEventType.IDLE));
    }

    @Override
    public void onUndeploy() throws ConnectorTaskException {
    }

    @Override
    public void onStart() throws ConnectorTaskException {
        if (connectorProperties.getMode() == SourceMode.PULL) {
            pool.reset();
            return;
        }

        EmbeddedSftpServer.Settings settings = pushSettings();
        rootDirectory = settings.rootDirectory;

        server = new EmbeddedSftpServer(settings, matcher, this::handleUpload);
        try {
            server.start();
        } catch (Exception e) {
            server = null;
            throw new ConnectorTaskException("Failed to start the SFTP server on "
                    + settings.host + ":" + settings.port + ": " + e.getMessage(), e);
        }

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getSourceName(), ConnectionStatusEventType.IDLE,
                "Listening on " + settings.host + ":" + settings.port));

        if (connectorProperties.isProcessExistingOnStart()) {
            processExistingFiles(settings);
        }
    }

    @Override
    public void onStop() throws ConnectorTaskException {
        shutdown();
    }

    @Override
    public void onHalt() throws ConnectorTaskException {
        shutdown();
    }

    private void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (pool != null) {
            pool.close();
        }
        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getSourceName(), ConnectionStatusEventType.DISCONNECTED));
    }

    /**
     * The response the channel produced is of no use here: neither an upload nor a poll
     * has anywhere to send it. Recovered responses are finished so the message is marked
     * processed and does not sit in the recovery set forever.
     */
    @Override
    public void handleRecoveredResponse(DispatchResult dispatchResult) {
        finishDispatch(dispatchResult);
    }

    /* ------------------------------------------------------------------ */
    /* push                                                                */
    /* ------------------------------------------------------------------ */

    private void validatePushSettings() throws ConnectorTaskException {
        List<SftpServerUser> users = connectorProperties.getUsers();
        if (users == null || users.isEmpty()) {
            throw new ConnectorTaskException("At least one user must be configured for the SFTP server.");
        }
        Map<String, Boolean> seen = new HashMap<String, Boolean>();
        for (SftpServerUser user : users) {
            String username = StringUtils.trimToEmpty(user.getUsername());
            if (username.isEmpty()) {
                throw new ConnectorTaskException("An SFTP server user has no username.");
            }
            if (seen.put(username, Boolean.TRUE) != null) {
                throw new ConnectorTaskException("Duplicate SFTP server user: " + username);
            }
            /*
             * An account with neither a password nor a key is not an open account -- both
             * authenticators refuse it -- but it is certainly a mistake, and one that looks
             * like a working configuration until a partner tries to connect.
             */
            if (StringUtils.isEmpty(user.getPassword())
                    && StringUtils.isBlank(user.getAuthorizedKeys())) {
                throw new ConnectorTaskException("SFTP server user \"" + username
                        + "\" has neither a password nor an authorized key, so nothing could log in as it.");
            }
        }
    }

    /** The deploy-time configuration, with templates expanded and paths resolved. */
    private EmbeddedSftpServer.Settings pushSettings() throws ConnectorTaskException {
        EmbeddedSftpServer.Settings settings = new EmbeddedSftpServer.Settings();

        settings.host = StringUtils.defaultIfBlank(
                replace(connectorProperties.getListenerConnectorProperties().getHost()), "0.0.0.0");
        settings.port = NumberUtils.toInt(replace(connectorProperties.getListenerConnectorProperties().getPort()), 2222);
        settings.rootDirectory = resolveLocal(connectorProperties.getRootDirectory(), "sftp/" + getChannelId());
        settings.hostKeyFile = resolveLocal(connectorProperties.getHostKeyFile(), "sftp/hostkey.ser");
        settings.hostKeyAlgorithm = StringUtils.defaultIfBlank(
                replace(connectorProperties.getHostKeyAlgorithm()), "EC");
        settings.hostKeySize = NumberUtils.toInt(replace(connectorProperties.getHostKeySize()), 256);
        settings.maxSessions = NumberUtils.toInt(replace(connectorProperties.getMaxSessions()), 10);
        settings.authTimeoutMillis = NumberUtils.toLong(replace(connectorProperties.getAuthTimeout()), 30000L);
        settings.idleTimeoutMillis = NumberUtils.toLong(replace(connectorProperties.getIdleTimeout()), 300000L);
        settings.allowDownloads = connectorProperties.isAllowDownloads();
        settings.dispatchOnRename = connectorProperties.isDispatchOnRename();

        for (SftpServerUser user : connectorProperties.getUsers()) {
            SftpServerUser resolved = new SftpServerUser(user);
            resolved.setUsername(replace(user.getUsername()));
            resolved.setPassword(replace(user.getPassword()));
            resolved.setAuthorizedKeys(replace(user.getAuthorizedKeys()));
            resolved.setHomeDirectory(replace(user.getHomeDirectory()));

            String home = StringUtils.trimToEmpty(resolved.getHomeDirectory());
            if (!home.isEmpty()) {
                Path path = settings.rootDirectory.resolve(home).normalize();
                if (!path.startsWith(settings.rootDirectory)) {
                    throw new ConnectorTaskException("SFTP server user \"" + resolved.getUsername()
                            + "\" has a home directory outside the server root: " + home);
                }
            }
            settings.users.add(resolved);
        }
        return settings;
    }

    /**
     * Called by {@link EmbeddedSftpServer} on the uploading client's own session thread,
     * so a slow channel slows the upload rather than building an unbounded backlog. The
     * client is not told about a channel-side failure -- SFTP has no way to say it, and
     * failing the transfer would have the partner resend a file that arrived intact.
     */
    private void handleUpload(Path file, String username, String remoteAddress) {
        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getSourceName(), ConnectionStatusEventType.RECEIVING,
                "Receiving " + file.getFileName() + " from " + username));
        try {
            Map<String, Object> sourceMap = new HashMap<String, Object>();
            sourceMap.put("originalFilename", file.getFileName().toString());
            sourceMap.put("fileDirectory", file.getParent() == null ? "" : file.getParent().toString());
            sourceMap.put("sftpUser", username);
            sourceMap.put("sftpRemoteAddress", remoteAddress);
            try {
                sourceMap.put("fileSize", Files.size(file));
                sourceMap.put("fileLastModified", Files.getLastModifiedTime(file).toMillis());
            } catch (IOException e) {
                logger.debug("Could not stat uploaded file {}", file, e);
            }

            byte[] contents = Files.readAllBytes(file);
            boolean errorResponse = dispatch(contents, sourceMap);
            applyLocalAction(file, errorResponse ? connectorProperties.getErrorResponseAction()
                    : connectorProperties.getAfterProcessingAction(), errorResponse);
        } catch (Throwable t) {
            eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), null,
                    ErrorEventType.SOURCE_CONNECTOR, getSourceName(), connectorProperties.getName(),
                    "Error processing uploaded file " + file, t));
            logger.error("Error processing uploaded file {} on channel {}", file, getChannelId(), t);
            applyLocalAction(file, connectorProperties.getErrorReadingAction(), true);
        } finally {
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                    getSourceName(), ConnectionStatusEventType.IDLE));
        }
    }

    /** Files already in the root when the channel starts -- see {@code processExistingOnStart}. */
    private void processExistingFiles(EmbeddedSftpServer.Settings settings) {
        List<Path> existing = new ArrayList<Path>();
        collectFiles(settings.rootDirectory, existing);
        if (existing.isEmpty()) {
            return;
        }
        logger.info("Processing {} file(s) already present in {}", existing.size(), settings.rootDirectory);
        for (Path file : existing) {
            if (isTerminated()) {
                return;
            }
            handleUpload(file, "(existing)", "(startup scan)");
        }
    }

    private void collectFiles(Path directory, List<Path> found) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry)) {
                    collectFiles(entry, found);
                } else if (Files.isRegularFile(entry) && matcher.accept(entry.getFileName().toString())) {
                    found.add(entry);
                }
            }
        } catch (IOException e) {
            logger.warn("Could not scan {} for existing files", directory, e);
        }
    }

    private void applyLocalAction(Path file, FileAction action, boolean error) {
        if (action == null || action == FileAction.NONE) {
            return;
        }
        try {
            if (action == FileAction.DELETE) {
                Files.deleteIfExists(file);
                return;
            }
            String directory = error ? connectorProperties.getErrorMoveToDirectory()
                    : connectorProperties.getMoveToDirectory();
            String filename = error ? connectorProperties.getErrorMoveToFileName()
                    : connectorProperties.getMoveToFileName();

            Path targetDirectory = StringUtils.isBlank(directory)
                    ? file.getParent()
                    : resolveAgainst(rootDirectory, replace(directory));
            String targetName = StringUtils.isBlank(filename)
                    ? file.getFileName().toString()
                    : replace(filename);

            Files.createDirectories(targetDirectory);
            Files.move(file, targetDirectory.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.error("Could not apply the {} action to {}", action, file, e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* pull                                                                */
    /* ------------------------------------------------------------------ */

    @Override
    protected void poll() throws InterruptedException {
        if (connectorProperties.getMode() != SourceMode.PULL) {
            // Push mode has nothing to poll; the schedule exists only because one connector
            // serves both directions.
            return;
        }

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getSourceName(), ConnectionStatusEventType.POLLING));

        SftpClient client = null;
        SftpConnectionProperties connection = resolvedConnection();
        try {
            client = pool.borrow(connection);

            String directory = replace(connectorProperties.getRemoteDirectory());
            List<SftpFileInfo> files = listFiles(client, directory, connectorProperties.isDirectoryRecursion());
            sortFiles(files);

            String pollId = UUID.randomUUID().toString();

            for (int i = 0; i < files.size(); i++) {
                ThreadUtils.checkInterruptedStatus();
                if (isTerminated()) {
                    break;
                }
                // pollComplete marks the last file of the poll, which a channel uses to
                // know the batch it has been receiving is finished.
                processRemoteFile(client, files.get(i), pollId, i + 1, i == files.size() - 1);
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Throwable t) {
            eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), null,
                    ErrorEventType.SOURCE_CONNECTOR, getSourceName(), connectorProperties.getName(),
                    "Error polling sftp://" + connection.toURIString(), t));
            logger.error("Error polling sftp://{} on channel {}", connection.toURIString(), getChannelId(), t);
            if (client != null) {
                // A failed poll may have left the session in an unknown state; do not pool it.
                client.close();
                client = null;
            }
        } finally {
            if (client != null) {
                pool.release(client, true);
            }
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                    getSourceName(), ConnectionStatusEventType.IDLE));
        }
    }

    private List<SftpFileInfo> listFiles(SftpClient client, String directory, boolean recursive) throws IOException {
        List<SftpFileInfo> files = new ArrayList<SftpFileInfo>();
        for (SftpFileInfo entry : client.list(directory)) {
            if (entry.isDirectory()) {
                if (recursive) {
                    files.addAll(listFiles(client, entry.getAbsolutePath(), true));
                }
            } else if (matcher.accept(entry.getName())) {
                files.add(entry);
            }
        }
        return files;
    }

    private void sortFiles(List<SftpFileInfo> files) {
        String sortBy = connectorProperties.getSortBy();
        if (SftpReceiverProperties.SORT_BY_DATE.equals(sortBy)) {
            files.sort(Comparator.comparingLong(SftpFileInfo::getLastModified));
        } else if (SftpReceiverProperties.SORT_BY_SIZE.equals(sortBy)) {
            files.sort(Comparator.comparingLong(SftpFileInfo::getSize));
        } else {
            files.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        }
    }

    private void processRemoteFile(SftpClient client, SftpFileInfo file, String pollId, int sequence,
            boolean pollComplete) {
        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getSourceName(), ConnectionStatusEventType.READING, "Reading " + file.getAbsolutePath()));

        boolean error = false;
        boolean errorResponse = false;
        try {
            Map<String, Object> sourceMap = new HashMap<String, Object>();
            sourceMap.put("originalFilename", file.getName());
            sourceMap.put("fileDirectory", file.getDirectory());
            sourceMap.put("fileSize", file.getSize());
            sourceMap.put("fileLastModified", file.getLastModified());
            sourceMap.put("sftpHost", connectorProperties.getConnectionProperties().getHost());
            sourceMap.put("pollId", pollId);
            sourceMap.put("pollSequenceId", sequence);
            if (pollComplete) {
                sourceMap.put("pollComplete", true);
            }

            InputStream in = null;
            byte[] contents;
            try {
                in = client.read(file.getDirectory(), file.getName());
                contents = IOUtils.toByteArray(in);
            } finally {
                IOUtils.closeQuietly(in);
            }

            errorResponse = dispatch(contents, sourceMap);
        } catch (Throwable t) {
            error = true;
            eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), null,
                    ErrorEventType.SOURCE_CONNECTOR, getSourceName(), connectorProperties.getName(),
                    "Error reading " + file.getAbsolutePath(), t));
            logger.error("Error reading {} on channel {}", file.getAbsolutePath(), getChannelId(), t);
        }

        FileAction action = error ? connectorProperties.getErrorReadingAction()
                : errorResponse ? connectorProperties.getErrorResponseAction()
                        : connectorProperties.getAfterProcessingAction();
        applyRemoteAction(client, file, action, error || errorResponse);

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getSourceName(), ConnectionStatusEventType.IDLE));
    }

    private void applyRemoteAction(SftpClient client, SftpFileInfo file, FileAction action, boolean error) {
        if (action == null || action == FileAction.NONE) {
            return;
        }
        try {
            if (action == FileAction.DELETE) {
                client.delete(file.getDirectory(), file.getName());
                return;
            }
            String directory = error ? connectorProperties.getErrorMoveToDirectory()
                    : connectorProperties.getMoveToDirectory();
            String filename = error ? connectorProperties.getErrorMoveToFileName()
                    : connectorProperties.getMoveToFileName();

            String targetDirectory = StringUtils.isBlank(directory) ? file.getDirectory() : replace(directory);
            String targetName = StringUtils.isBlank(filename) ? file.getName() : replace(filename);

            client.makeDirectories(targetDirectory);
            client.move(file.getDirectory(), file.getName(), targetDirectory, targetName);
        } catch (Exception e) {
            logger.error("Could not apply the {} action to {}", action, file.getAbsolutePath(), e);
        }
    }

    /** The remote connection settings with templates expanded. */
    private SftpConnectionProperties resolvedConnection() {
        SftpConnectionProperties props = new SftpConnectionProperties(connectorProperties.getConnectionProperties());
        props.setHost(replace(props.getHost()));
        props.setPort(replace(props.getPort()));
        props.setUsername(replace(props.getUsername()));
        props.setPassword(replace(props.getPassword()));
        props.setPrivateKeyFile(replace(props.getPrivateKeyFile()));
        props.setPrivateKey(replace(props.getPrivateKey()));
        props.setPassphrase(replace(props.getPassphrase()));
        props.setKnownHostsFile(replace(props.getKnownHostsFile()));
        props.setHostKey(replace(props.getHostKey()));
        props.setConfigurationSettings(replacer.replaceValues(props.getConfigurationSettings(),
                getChannelId(), getChannelName()));
        return props;
    }

    /* ------------------------------------------------------------------ */
    /* shared                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Hands the bytes to the channel.
     *
     * @return true if the channel answered with an ERROR response, which selects the
     *         error-response file action rather than the normal one
     */
    private boolean dispatch(byte[] contents, Map<String, Object> sourceMap) throws Exception {
        if (isProcessBatch()) {
            Reader reader = new InputStreamReader(new ByteArrayInputStream(contents), charsetEncoding);
            try {
                BatchRawMessage batchRawMessage = new BatchRawMessage(new BatchMessageReader(reader), sourceMap);
                Boolean messagesExist = dispatchBatchMessage(batchRawMessage, null);
                if (messagesExist != null && !messagesExist) {
                    logger.warn("{} was processed but produced no messages on channel {}",
                            sourceMap.get("originalFilename"), getChannelId());
                }
            } finally {
                IOUtils.closeQuietly(reader);
            }
            return false;
        }

        RawMessage rawMessage = connectorProperties.isBinary()
                ? new RawMessage(contents)
                : new RawMessage(new String(contents, charsetEncoding));
        rawMessage.setSourceMap(sourceMap);

        DispatchResult dispatchResult = null;
        try {
            dispatchResult = dispatchRawMessage(rawMessage);
            Response response = dispatchResult == null ? null : dispatchResult.getSelectedResponse();
            return response != null && response.getStatus() == Status.ERROR;
        } finally {
            finishDispatch(dispatchResult);
        }
    }

    private String replace(String template) {
        if (template == null) {
            return null;
        }
        return replacer.replaceValues(template, getChannelId(), getChannelName());
    }

    private String getChannelName() {
        return getChannel() == null ? null : getChannel().getName();
    }

    /**
     * A configured local path. Relative paths land under the engine's application data
     * directory -- the volume this stack persists -- so the default configuration survives
     * a container being rebuilt.
     */
    private Path resolveLocal(String configured, String fallback) {
        String path = replace(StringUtils.defaultIfBlank(configured, fallback));
        return resolveAgainst(Paths.get(configurationController.getApplicationDataDir()), path);
    }

    private static Path resolveAgainst(Path base, String path) {
        Path candidate = Paths.get(path);
        return (candidate.isAbsolute() ? candidate : base.resolve(candidate)).normalize();
    }
}
