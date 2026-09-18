/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.ListenerConnectorProperties;
import com.mirth.connect.donkey.model.channel.ListenerConnectorPropertiesInterface;
import com.mirth.connect.donkey.model.channel.PollConnectorProperties;
import com.mirth.connect.donkey.model.channel.PollConnectorPropertiesInterface;
import com.mirth.connect.donkey.model.channel.SourceConnectorProperties;
import com.mirth.connect.donkey.model.channel.SourceConnectorPropertiesInterface;
import com.mirth.connect.donkey.util.DonkeyElement;

import org.apache.commons.lang3.builder.EqualsBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings for the SFTP Listener, the source half, which takes files in one of two
 * directions depending on {@link #getMode()}:
 *
 * <ul>
 *   <li>{@link SourceMode#PUSH} -- the engine runs an SFTP server on {@link
 *       #getListenerConnectorProperties() host:port} and partners connect to it and
 *       upload. A message is dispatched as each upload completes, so there is no polling
 *       delay and no window in which a half-written file can be read.</li>
 *   <li>{@link SourceMode#PULL} -- the engine is the client, connecting out to a remote
 *       server on the schedule in {@link #getPollConnectorProperties() Polling Settings}
 *       and taking whatever is waiting.</li>
 * </ul>
 *
 * <p>The two modes share everything after the file has been obtained: the filename
 * filter, the binary/charset handling and the after-processing action all mean the same
 * thing either way, which is what makes switching a partner from push to pull (or back) a
 * single field rather than a new channel.
 *
 * <p>Serialised into the channel XML by XStream under this class's fully qualified name,
 * which is also the {@code @class} the web administrator's panel writes. Renaming or
 * moving this class breaks every channel already using it.
 */
public class SftpReceiverProperties extends ConnectorProperties
        implements ListenerConnectorPropertiesInterface, SourceConnectorPropertiesInterface,
        PollConnectorPropertiesInterface {

    /** Sort order for a pull, applied before the files are processed. */
    public static final String SORT_BY_NAME = "name";
    public static final String SORT_BY_SIZE = "size";
    public static final String SORT_BY_DATE = "date";

    private ListenerConnectorProperties listenerConnectorProperties;
    private SourceConnectorProperties sourceConnectorProperties;
    private PollConnectorProperties pollConnectorProperties;

    private SourceMode mode;

    /* ---- push: the embedded server ---- */
    private String rootDirectory;
    private List<SftpServerUser> users;
    private String hostKeyFile;
    private String hostKeyAlgorithm;
    private String hostKeySize;
    private String maxSessions;
    private String authTimeout;
    private String idleTimeout;
    private boolean allowDownloads;
    private boolean dispatchOnRename;
    private boolean processExistingOnStart;

    /* ---- pull: the remote server ---- */
    private SftpConnectionProperties connectionProperties;
    private String remoteDirectory;
    private boolean directoryRecursion;
    private String sortBy;

    /* ---- both ---- */
    private String fileFilter;
    private boolean regex;
    private boolean ignoreDot;
    private boolean binary;
    private String charsetEncoding;
    private FileAction afterProcessingAction;
    private String moveToDirectory;
    private String moveToFileName;
    private FileAction errorReadingAction;
    private FileAction errorResponseAction;
    private String errorMoveToDirectory;
    private String errorMoveToFileName;

    public SftpReceiverProperties() {
        /*
         * 2222 rather than 22: the engine runs unprivileged, and a container that has to
         * be given CAP_NET_BIND_SERVICE to start a channel is a worse default than a port
         * the compose file maps. Publish it as :22 from the host if partners need that.
         */
        listenerConnectorProperties = new ListenerConnectorProperties("2222");
        sourceConnectorProperties = new SourceConnectorProperties(SourceConnectorProperties.RESPONSE_NONE);
        pollConnectorProperties = new PollConnectorProperties();

        mode = SourceMode.PUSH;

        /*
         * Relative, so it lands under the engine's application data directory -- the
         * volume this stack persists. A path under the container filesystem instead would
         * lose every file not yet processed on the next `compose up --force-recreate`.
         */
        rootDirectory = "sftp/${channelId}";
        users = new ArrayList<SftpServerUser>();
        hostKeyFile = "sftp/hostkey.ser";
        hostKeyAlgorithm = "EC";
        hostKeySize = "256";
        maxSessions = "10";
        authTimeout = "30000";
        idleTimeout = "300000";
        allowDownloads = false;
        dispatchOnRename = true;
        processExistingOnStart = false;

        connectionProperties = new SftpConnectionProperties();
        remoteDirectory = "";
        directoryRecursion = false;
        sortBy = SORT_BY_DATE;

        fileFilter = "*";
        regex = false;
        ignoreDot = true;
        binary = false;
        charsetEncoding = "DEFAULT_ENCODING";
        afterProcessingAction = FileAction.NONE;
        moveToDirectory = "";
        moveToFileName = "";
        errorReadingAction = FileAction.NONE;
        errorResponseAction = FileAction.NONE;
        errorMoveToDirectory = "";
        errorMoveToFileName = "";
    }

    @Override
    public String getProtocol() {
        return "sftp";
    }

    /**
     * Must match {@code <name>} in source.xml exactly: the engine finds the class to
     * instantiate by looking this string up in the connector metadata registry.
     */
    @Override
    public String getName() {
        return "SFTP Listener";
    }

    @Override
    public String toFormattedString() {
        if (mode == SourceMode.PULL) {
            return "PULL sftp://" + connectionProperties.toURIString() + normalisedRemoteDirectory();
        }
        return "PUSH sftp://" + listenerConnectorProperties.getHost() + ":"
                + listenerConnectorProperties.getPort() + " -> " + rootDirectory;
    }

    /** The remote directory with a leading slash and no trailing one, or empty for the login directory. */
    public String normalisedRemoteDirectory() {
        String path = remoteDirectory == null ? "" : remoteDirectory.trim();
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    public SourceMode getMode() {
        return mode;
    }

    public void setMode(SourceMode mode) {
        this.mode = mode;
    }

    @Override
    public ListenerConnectorProperties getListenerConnectorProperties() {
        return listenerConnectorProperties;
    }

    @Override
    public SourceConnectorProperties getSourceConnectorProperties() {
        return sourceConnectorProperties;
    }

    @Override
    public PollConnectorProperties getPollConnectorProperties() {
        return pollConnectorProperties;
    }

    /**
     * Batching is honoured in both modes: a file holding many HL7 messages is split by the
     * inbound data type exactly as the File Reader would split it.
     */
    @Override
    public boolean canBatch() {
        return true;
    }

    /**
     * Where uploads land. Everything a client can see lives under here and nothing above
     * it is reachable, because each session's filesystem is rooted at this directory (or
     * at the user's own directory beneath it).
     *
     * <p>A relative path is resolved against the engine's application data directory,
     * which is the one this stack keeps on a volume. {@code ${channelId}} and
     * configuration map values are expanded when the channel deploys.
     */
    public String getRootDirectory() {
        return rootDirectory;
    }

    public void setRootDirectory(String rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    /** The accounts the server accepts. At least one is required to deploy in push mode. */
    public List<SftpServerUser> getUsers() {
        return users;
    }

    public void setUsers(List<SftpServerUser> users) {
        this.users = users;
    }

    /**
     * Where the server's own host key is kept, relative to the application data directory
     * as with the root. It is generated on first start and reused after that -- which is
     * the point of the file: a key that changed on every restart would make every client
     * report the host identity as compromised, and most would refuse to connect at all.
     *
     * <p>The default is shared by every channel on the server, so partners see one
     * identity for the engine however many drop boxes it runs. Give a channel its own path
     * to separate them.
     */
    public String getHostKeyFile() {
        return hostKeyFile;
    }

    public void setHostKeyFile(String hostKeyFile) {
        this.hostKeyFile = hostKeyFile;
    }

    /** Key type to generate if {@link #getHostKeyFile()} does not exist yet: EC or RSA. */
    public String getHostKeyAlgorithm() {
        return hostKeyAlgorithm;
    }

    public void setHostKeyAlgorithm(String hostKeyAlgorithm) {
        this.hostKeyAlgorithm = hostKeyAlgorithm;
    }

    /** Key size for a generated host key: 256/384/521 for EC, 2048 upwards for RSA. */
    public String getHostKeySize() {
        return hostKeySize;
    }

    public void setHostKeySize(String hostKeySize) {
        this.hostKeySize = hostKeySize;
    }

    /** Concurrent SSH sessions allowed. Further connections are refused, not queued. */
    public String getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(String maxSessions) {
        this.maxSessions = maxSessions;
    }

    /** Milliseconds a client has to finish authenticating before it is disconnected. */
    public String getAuthTimeout() {
        return authTimeout;
    }

    public void setAuthTimeout(String authTimeout) {
        this.authTimeout = authTimeout;
    }

    /** Milliseconds an authenticated but silent session is held open. */
    public String getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(String idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    /**
     * Whether clients may list and download as well as upload. Off by default: a drop box
     * that also hands files back is a data exposure nobody asked for, and a partner who
     * needs to confirm a delivery can do it by the absence of the file after processing.
     */
    public boolean isAllowDownloads() {
        return allowDownloads;
    }

    public void setAllowDownloads(boolean allowDownloads) {
        this.allowDownloads = allowDownloads;
    }

    /**
     * Also treat a rename into place as a completed upload.
     *
     * <p>Most clients write to a temporary name and rename when the transfer finishes,
     * precisely so the far end cannot read a partial file. With this on (and the temporary
     * name excluded by {@link #getFileFilter()}), that pattern is handled properly: the
     * temporary file is ignored and the message is dispatched at the rename.
     */
    public boolean isDispatchOnRename() {
        return dispatchOnRename;
    }

    public void setDispatchOnRename(boolean dispatchOnRename) {
        this.dispatchOnRename = dispatchOnRename;
    }

    /**
     * On start, process files already sitting in the root directory.
     *
     * <p>Off by default, because with an after-processing action of None it would
     * re-dispatch everything the directory still holds on every deploy. Turn it on
     * together with Delete or Move, and an upload that arrived while the channel was
     * stopped -- or one received in the instant before the engine died -- is picked up
     * instead of being stranded.
     */
    public boolean isProcessExistingOnStart() {
        return processExistingOnStart;
    }

    public void setProcessExistingOnStart(boolean processExistingOnStart) {
        this.processExistingOnStart = processExistingOnStart;
    }

    public SftpConnectionProperties getConnectionProperties() {
        return connectionProperties;
    }

    public void setConnectionProperties(SftpConnectionProperties connectionProperties) {
        this.connectionProperties = connectionProperties;
    }

    /** The directory to poll on the remote server. Empty means the login directory. */
    public String getRemoteDirectory() {
        return remoteDirectory;
    }

    public void setRemoteDirectory(String remoteDirectory) {
        this.remoteDirectory = remoteDirectory;
    }

    /** Descend into subdirectories of the polled directory. */
    public boolean isDirectoryRecursion() {
        return directoryRecursion;
    }

    public void setDirectoryRecursion(boolean directoryRecursion) {
        this.directoryRecursion = directoryRecursion;
    }

    /** {@link #SORT_BY_NAME}, {@link #SORT_BY_SIZE} or {@link #SORT_BY_DATE}. */
    public String getSortBy() {
        return sortBy;
    }

    public void setSortBy(String sortBy) {
        this.sortBy = sortBy;
    }

    /**
     * Which filenames count. A space or comma separated list of wildcards by default
     * ({@code *.hl7 *.txt}), or one regular expression when {@link #isRegex()}.
     *
     * <p>A leading {@code !} negates the whole filter, so {@code !*.tmp *.filepart} takes
     * everything except part files -- which is the other half of {@link
     * #isDispatchOnRename()}.
     */
    public String getFileFilter() {
        return fileFilter;
    }

    public void setFileFilter(String fileFilter) {
        this.fileFilter = fileFilter;
    }

    public boolean isRegex() {
        return regex;
    }

    public void setRegex(boolean regex) {
        this.regex = regex;
    }

    /** Skip dot files, which are conventionally partial or hidden. */
    public boolean isIgnoreDot() {
        return ignoreDot;
    }

    public void setIgnoreDot(boolean ignoreDot) {
        this.ignoreDot = ignoreDot;
    }

    /**
     * Read the file as bytes rather than text. Binary content is base64 encoded into the
     * message, exactly as the File Reader does it, and batch processing is refused because
     * there is no text to split.
     */
    public boolean isBinary() {
        return binary;
    }

    public void setBinary(boolean binary) {
        this.binary = binary;
    }

    public String getCharsetEncoding() {
        return charsetEncoding;
    }

    public void setCharsetEncoding(String charsetEncoding) {
        this.charsetEncoding = charsetEncoding;
    }

    /** What to do with a file the channel accepted. */
    public FileAction getAfterProcessingAction() {
        return afterProcessingAction;
    }

    public void setAfterProcessingAction(FileAction afterProcessingAction) {
        this.afterProcessingAction = afterProcessingAction;
    }

    public String getMoveToDirectory() {
        return moveToDirectory;
    }

    public void setMoveToDirectory(String moveToDirectory) {
        this.moveToDirectory = moveToDirectory;
    }

    public String getMoveToFileName() {
        return moveToFileName;
    }

    public void setMoveToFileName(String moveToFileName) {
        this.moveToFileName = moveToFileName;
    }

    /** What to do with a file that could not be read or dispatched at all. */
    public FileAction getErrorReadingAction() {
        return errorReadingAction;
    }

    public void setErrorReadingAction(FileAction errorReadingAction) {
        this.errorReadingAction = errorReadingAction;
    }

    /** What to do with a file the channel read but answered with an ERROR response. */
    public FileAction getErrorResponseAction() {
        return errorResponseAction;
    }

    public void setErrorResponseAction(FileAction errorResponseAction) {
        this.errorResponseAction = errorResponseAction;
    }

    public String getErrorMoveToDirectory() {
        return errorMoveToDirectory;
    }

    public void setErrorMoveToDirectory(String errorMoveToDirectory) {
        this.errorMoveToDirectory = errorMoveToDirectory;
    }

    public String getErrorMoveToFileName() {
        return errorMoveToFileName;
    }

    public void setErrorMoveToFileName(String errorMoveToFileName) {
        this.errorMoveToFileName = errorMoveToFileName;
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        int result = mode == null ? 0 : mode.hashCode();
        result = 31 * result + (listenerConnectorProperties == null ? 0
                : String.valueOf(listenerConnectorProperties.getPort()).hashCode());
        result = 31 * result + (connectionProperties == null ? 0 : connectionProperties.hashCode());
        return result;
    }

    /*
     * Migrations. The engine calls the whole ladder on every properties object it reads,
     * one rung per release since 3.0.1, so the interface demands them all -- but this
     * connector has only ever had one shape, so there is nothing for any of them to do.
     * ConnectorProperties implements 3.1.0 and later; the two oldest are left abstract
     * there and have to be declared here.
     */
    @Override
    public void migrate3_0_1(DonkeyElement element) {
    }

    @Override
    public void migrate3_0_2(DonkeyElement element) {
    }

    /**
     * Usage statistics, which are aggregated across servers -- so this reports the shape
     * of the configuration and nothing that identifies the deployment. Host, port,
     * directories, usernames and every credential are deliberately absent.
     */
    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new LinkedHashMap<String, Object>();
        purged.put("mode", mode == null ? null : mode.name());
        purged.put("userCount", users == null ? 0 : users.size());
        purged.put("allowDownloads", allowDownloads);
        purged.put("dispatchOnRename", dispatchOnRename);
        purged.put("processExistingOnStart", processExistingOnStart);
        purged.put("hostKeyAlgorithm", hostKeyAlgorithm);
        purged.put("directoryRecursion", directoryRecursion);
        purged.put("sortBy", sortBy);
        purged.put("regex", regex);
        purged.put("binary", binary);
        purged.put("afterProcessingAction", afterProcessingAction == null ? null : afterProcessingAction.name());
        purged.put("errorReadingAction", errorReadingAction == null ? null : errorReadingAction.name());
        purged.put("errorResponseAction", errorResponseAction == null ? null : errorResponseAction.name());
        purged.put("connectionProperties", connectionProperties == null ? null : connectionProperties.getPurgedProperties());
        purged.put("pollConnectorProperties", pollConnectorProperties == null ? null : pollConnectorProperties.getPurgedProperties());
        purged.put("sourceConnectorProperties", sourceConnectorProperties == null ? null : sourceConnectorProperties.getPurgedProperties());
        return purged;
    }
}
