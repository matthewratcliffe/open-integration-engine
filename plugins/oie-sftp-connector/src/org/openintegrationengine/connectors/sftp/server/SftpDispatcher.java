/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.server;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.DestinationConnector;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.donkey.util.ThreadUtils;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.util.TemplateValueReplacer;
import com.mirth.connect.util.CharsetUtils;
import com.mirth.connect.util.ErrorMessageBuilder;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openintegrationengine.connectors.sftp.SftpConnectionProperties;
import org.openintegrationengine.connectors.sftp.SftpDispatcherProperties;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The SFTP Sender: writes each message to a remote SFTP server.
 *
 * <p>Deliberately close to the File Writer in behaviour -- same template handling, same
 * temporary-file discipline, same queueing semantics -- so a destination moved here from
 * {@code file://sftp} does the same thing, with the client authentication and host key
 * verification the File connector has no fields for.
 */
public class SftpDispatcher extends DestinationConnector {

    private final Logger logger = LogManager.getLogger(getClass());
    private final EventController eventController = ControllerFactory.getFactory().createEventController();
    private final TemplateValueReplacer replacer = new TemplateValueReplacer();

    private SftpDispatcherProperties connectorProperties;
    private SftpClientPool pool;
    private String charsetEncoding;

    @Override
    public void onDeploy() throws ConnectorTaskException {
        connectorProperties = (SftpDispatcherProperties) getConnectorProperties();
        charsetEncoding = CharsetUtils.getEncoding(connectorProperties.getCharsetEncoding(),
                System.getProperty("ca.uhn.hl7v2.llp.charset"));
        pool = new SftpClientPool();

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getDestinationName(), ConnectionStatusEventType.IDLE));
    }

    @Override
    public void onUndeploy() throws ConnectorTaskException {
    }

    @Override
    public void onStart() throws ConnectorTaskException {
        pool.reset();
    }

    @Override
    public void onStop() throws ConnectorTaskException {
        pool.close();
    }

    @Override
    public void onHalt() throws ConnectorTaskException {
        pool.close();
    }

    /**
     * Every field a message could reasonably vary is templated, including the credentials:
     * one destination can then serve many partners, keyed off the message, which is the
     * usual reason a channel has a map variable in its host field.
     */
    @Override
    public void replaceConnectorProperties(ConnectorProperties connectorProperties, ConnectorMessage connectorMessage) {
        SftpDispatcherProperties props = (SftpDispatcherProperties) connectorProperties;

        props.setRemoteDirectory(replacer.replaceValues(props.getRemoteDirectory(), connectorMessage));
        props.setOutputPattern(replacer.replaceValues(props.getOutputPattern(), connectorMessage));
        props.setTemplate(replacer.replaceValues(props.getTemplate(), connectorMessage));
        props.setTemporarySuffix(replacer.replaceValues(props.getTemporarySuffix(), connectorMessage));
        props.setFilePermissions(replacer.replaceValues(props.getFilePermissions(), connectorMessage));

        SftpConnectionProperties connection = props.getConnectionProperties();
        connection.setHost(replacer.replaceValues(connection.getHost(), connectorMessage));
        connection.setPort(replacer.replaceValues(connection.getPort(), connectorMessage));
        connection.setUsername(replacer.replaceValues(connection.getUsername(), connectorMessage));
        connection.setPassword(replacer.replaceValues(connection.getPassword(), connectorMessage));
        connection.setPrivateKeyFile(replacer.replaceValues(connection.getPrivateKeyFile(), connectorMessage));
        connection.setPrivateKey(replacer.replaceValues(connection.getPrivateKey(), connectorMessage));
        connection.setPassphrase(replacer.replaceValues(connection.getPassphrase(), connectorMessage));
        connection.setKnownHostsFile(replacer.replaceValues(connection.getKnownHostsFile(), connectorMessage));
        connection.setHostKey(replacer.replaceValues(connection.getHostKey(), connectorMessage));
        connection.setConfigurationSettings(
                replacer.replaceValuesInMap(connection.getConfigurationSettings(), connectorMessage));
    }

    @Override
    public Response send(ConnectorProperties connectorProperties, ConnectorMessage connectorMessage)
            throws InterruptedException {
        SftpDispatcherProperties props = (SftpDispatcherProperties) connectorProperties;
        SftpConnectionProperties connection = props.getConnectionProperties();

        String filename = StringUtils.trimToEmpty(props.getOutputPattern());
        String directory = props.normalisedRemoteDirectory();

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getDestinationName(), ConnectionStatusEventType.WRITING,
                "Writing to " + props.toURIString()));

        String responseStatusMessage;
        String responseError = null;
        /*
         * QUEUED, not ERROR, is the right failure status: the destination queue exists so a
         * partner being unreachable delays delivery rather than losing the message, and the
         * engine only queues when this comes back QUEUED.
         */
        Status responseStatus = Status.QUEUED;

        SftpClient client = null;
        boolean reusable = false;
        InputStream contents = null;

        try {
            if (filename.isEmpty()) {
                throw new IOException("No filename configured");
            }

            byte[] bytes = getAttachmentHandlerProvider().reAttachMessage(props.getTemplate(), connectorMessage,
                    charsetEncoding, props.isBinary(),
                    props.getDestinationConnectorProperties().isReattachAttachments());
            contents = new ByteArrayInputStream(bytes);

            ThreadUtils.checkInterruptedStatus();
            client = pool.borrow(connection);

            if (props.isCreateDirectories() && !directory.isEmpty()) {
                client.makeDirectories(directory);
            }

            if (props.isErrorOnExists() && client.exists(directory, filename)) {
                throw new IOException("Destination file already exists, will not overwrite: "
                        + props.toURIString());
            }

            if (props.isTemporary() && !props.isOutputAppend()) {
                /*
                 * Write to a temporary name and rename. Appending cannot use it -- the
                 * rename would replace the file being appended to with just the new part --
                 * so that combination writes directly, which is what the panel warns about.
                 */
                String temporaryName = filename + StringUtils.defaultIfEmpty(props.getTemporarySuffix(), ".tmp");
                client.write(directory, temporaryName, false, contents);
                if (StringUtils.isNotBlank(props.getFilePermissions())) {
                    client.chmod(directory, temporaryName, props.getFilePermissions());
                }
                client.move(directory, temporaryName, directory, filename);
            } else {
                client.write(directory, filename, props.isOutputAppend(), contents);
                if (StringUtils.isNotBlank(props.getFilePermissions())) {
                    client.chmod(directory, filename, props.getFilePermissions());
                }
            }

            reusable = true;
            responseStatusMessage = "File successfully written: " + props.toURIString();
            responseStatus = Status.SENT;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(),
                    connectorMessage.getMessageId(), ErrorEventType.DESTINATION_CONNECTOR, getDestinationName(),
                    connectorProperties.getName(), "Error writing file", e));
            responseStatusMessage = ErrorMessageBuilder.buildErrorResponse("Error writing file", e);
            responseError = ErrorMessageBuilder.buildErrorMessage(connectorProperties.getName(),
                    "Error writing file", e);
            logger.error("Error writing to sftp://{} on channel {}", connection.toURIString(), getChannelId(), e);
        } finally {
            IOUtils.closeQuietly(contents);
            if (client != null) {
                // A connection that failed mid-operation is closed rather than pooled: its
                // state is unknown, and handing it to the next message turns one failure
                // into a run of them.
                pool.release(client, reusable && props.isKeepConnectionOpen());
            }
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                    getDestinationName(), ConnectionStatusEventType.IDLE));
        }

        return new Response(responseStatus, null, responseStatusMessage, responseError);
    }
}
