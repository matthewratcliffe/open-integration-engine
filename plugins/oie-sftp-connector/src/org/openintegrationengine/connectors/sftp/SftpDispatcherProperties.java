/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorPropertiesInterface;
import com.mirth.connect.donkey.util.DonkeyElement;

import org.apache.commons.lang3.builder.EqualsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings for the SFTP Sender, the destination half: an SFTP client that writes each
 * message to a remote server.
 *
 * <p>The connection half lives in {@link SftpConnectionProperties} and is the same object
 * the SFTP Listener uses when it pulls, so credentials, key material and host key policy
 * are configured identically in both directions.
 *
 * <p>Serialised into the channel XML by XStream under this class's fully qualified name,
 * which is also the {@code @class} the web administrator's panel writes. Renaming or
 * moving this class breaks every channel already using it.
 */
public class SftpDispatcherProperties extends ConnectorProperties
        implements DestinationConnectorPropertiesInterface {

    private DestinationConnectorProperties destinationConnectorProperties;
    private SftpConnectionProperties connectionProperties;

    private String remoteDirectory;
    private String outputPattern;
    private String template;
    private boolean binary;
    private String charsetEncoding;
    private boolean outputAppend;
    private boolean errorOnExists;
    private boolean temporary;
    private String temporarySuffix;
    private boolean createDirectories;
    private String filePermissions;
    private boolean keepConnectionOpen;

    public SftpDispatcherProperties() {
        destinationConnectorProperties = new DestinationConnectorProperties(true);
        connectionProperties = new SftpConnectionProperties();

        remoteDirectory = "";
        outputPattern = "";
        template = "${message.encodedData}";
        binary = false;
        charsetEncoding = "DEFAULT_ENCODING";
        outputAppend = false;
        errorOnExists = false;
        /*
         * Write to a temporary name and rename on completion, by default. The rename is
         * atomic on the far side, so a partner polling the directory can never pick up a
         * file this connector is still writing -- the failure this is here to prevent, and
         * the same discipline this connector's own push mode expects of its clients.
         */
        temporary = true;
        temporarySuffix = ".tmp";
        createDirectories = false;
        filePermissions = "";
        keepConnectionOpen = true;
    }

    public SftpDispatcherProperties(SftpDispatcherProperties props) {
        super(props);
        destinationConnectorProperties = new DestinationConnectorProperties(props.getDestinationConnectorProperties());
        connectionProperties = new SftpConnectionProperties(props.getConnectionProperties());

        remoteDirectory = props.getRemoteDirectory();
        outputPattern = props.getOutputPattern();
        template = props.getTemplate();
        binary = props.isBinary();
        charsetEncoding = props.getCharsetEncoding();
        outputAppend = props.isOutputAppend();
        errorOnExists = props.isErrorOnExists();
        temporary = props.isTemporary();
        temporarySuffix = props.getTemporarySuffix();
        createDirectories = props.isCreateDirectories();
        filePermissions = props.getFilePermissions();
        keepConnectionOpen = props.isKeepConnectionOpen();
    }

    @Override
    public String getProtocol() {
        return "sftp";
    }

    /**
     * Must match {@code <name>} in destination.xml exactly: the engine finds the class to
     * instantiate by looking this string up in the connector metadata registry.
     */
    @Override
    public String getName() {
        return "SFTP Sender";
    }

    @Override
    public String toFormattedString() {
        return toURIString();
    }

    public String toURIString() {
        return "sftp://" + connectionProperties.toURIString() + normalisedRemoteDirectory()
                + "/" + (outputPattern == null ? "" : outputPattern);
    }

    /** The remote directory with no trailing slash, or empty for the login directory. */
    public String normalisedRemoteDirectory() {
        String path = remoteDirectory == null ? "" : remoteDirectory.trim();
        while (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    @Override
    public DestinationConnectorProperties getDestinationConnectorProperties() {
        return destinationConnectorProperties;
    }

    /**
     * Nothing comes back from an SFTP write that a response validator could inspect -- the
     * only outcomes are "the bytes were stored" and an exception -- so the queue's response
     * validation option is not offered.
     */
    @Override
    public boolean canValidateResponse() {
        return false;
    }

    @Override
    public ConnectorProperties clone() {
        return new SftpDispatcherProperties(this);
    }

    public SftpConnectionProperties getConnectionProperties() {
        return connectionProperties;
    }

    public void setConnectionProperties(SftpConnectionProperties connectionProperties) {
        this.connectionProperties = connectionProperties;
    }

    /** Where to write, on the remote server. Empty means the login directory. */
    public String getRemoteDirectory() {
        return remoteDirectory;
    }

    public void setRemoteDirectory(String remoteDirectory) {
        this.remoteDirectory = remoteDirectory;
    }

    /** The filename, as a template -- {@code ${message.messageId}.hl7} and the like. */
    public String getOutputPattern() {
        return outputPattern;
    }

    public void setOutputPattern(String outputPattern) {
        this.outputPattern = outputPattern;
    }

    /** The file's contents, as a template. */
    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    /**
     * Treat the template's value as base64 encoded bytes and write the decoded bytes,
     * rather than encoding text in {@link #getCharsetEncoding()}.
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

    /** Append to the file if it already exists, rather than replacing it. */
    public boolean isOutputAppend() {
        return outputAppend;
    }

    public void setOutputAppend(boolean outputAppend) {
        this.outputAppend = outputAppend;
    }

    /** Fail the message if the file already exists, instead of overwriting it. */
    public boolean isErrorOnExists() {
        return errorOnExists;
    }

    public void setErrorOnExists(boolean errorOnExists) {
        this.errorOnExists = errorOnExists;
    }

    /** Write under {@link #getTemporarySuffix()} and rename once the write completes. */
    public boolean isTemporary() {
        return temporary;
    }

    public void setTemporary(boolean temporary) {
        this.temporary = temporary;
    }

    public String getTemporarySuffix() {
        return temporarySuffix;
    }

    public void setTemporarySuffix(String temporarySuffix) {
        this.temporarySuffix = temporarySuffix;
    }

    /** Create the remote directory, and any parents, when it does not exist. */
    public boolean isCreateDirectories() {
        return createDirectories;
    }

    public void setCreateDirectories(boolean createDirectories) {
        this.createDirectories = createDirectories;
    }

    /**
     * Octal permissions to set on the written file, such as {@code 640}. Empty leaves
     * whatever the server's own umask produced, which is usually what you want unless a
     * partner's collection process is fussy about the mode.
     */
    public String getFilePermissions() {
        return filePermissions;
    }

    public void setFilePermissions(String filePermissions) {
        this.filePermissions = filePermissions;
    }

    /**
     * Hold the SSH session open between messages and reuse it.
     *
     * <p>Worth keeping on: an SSH handshake is expensive enough that reconnecting per
     * message dominates the cost of sending a small one. Turn it off for a partner that
     * drops idle sessions in a way the connector cannot detect cleanly.
     */
    public boolean isKeepConnectionOpen() {
        return keepConnectionOpen;
    }

    public void setKeepConnectionOpen(boolean keepConnectionOpen) {
        this.keepConnectionOpen = keepConnectionOpen;
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        int result = connectionProperties == null ? 0 : connectionProperties.hashCode();
        result = 31 * result + (outputPattern == null ? 0 : outputPattern.hashCode());
        return result;
    }

    /*
     * Migrations -- see the note on SftpReceiverProperties. One shape, nothing to do.
     */
    @Override
    public void migrate3_0_1(DonkeyElement element) {
    }

    @Override
    public void migrate3_0_2(DonkeyElement element) {
    }

    /**
     * Usage statistics, which are aggregated across servers -- so this reports the shape
     * of the configuration and nothing that identifies the deployment. Host, directory,
     * filename, template and every credential are deliberately absent.
     */
    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new LinkedHashMap<String, Object>();
        purged.put("binary", binary);
        purged.put("outputAppend", outputAppend);
        purged.put("errorOnExists", errorOnExists);
        purged.put("temporary", temporary);
        purged.put("createDirectories", createDirectories);
        purged.put("filePermissionsSet", filePermissions != null && !filePermissions.trim().isEmpty());
        purged.put("keepConnectionOpen", keepConnectionOpen);
        purged.put("templateLines", template == null ? 0 : template.split("\r\n|\r|\n").length);
        purged.put("connectionProperties", connectionProperties == null ? null : connectionProperties.getPurgedProperties());
        purged.put("destinationConnectorProperties", destinationConnectorProperties == null ? null
                : destinationConnectorProperties.getPurgedProperties());
        return purged;
    }
}
