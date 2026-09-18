/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.server;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.util.TemplateValueReplacer;
import com.mirth.connect.util.ConnectionTestResponse;

import org.apache.commons.lang3.StringUtils;
import org.openintegrationengine.connectors.sftp.SftpConnectionProperties;
import org.openintegrationengine.connectors.sftp.SftpConnectorServletInterface;
import org.openintegrationengine.connectors.sftp.SftpDispatcherProperties;
import org.openintegrationengine.connectors.sftp.SftpReceiverProperties;
import org.openintegrationengine.connectors.sftp.SourceMode;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

import java.util.List;

/**
 * Serves {@link SftpConnectorServletInterface}.
 *
 * <p>The test opens a real session with the settings as given -- same code path as a
 * running channel, template values expanded the same way -- and lists the configured
 * directory, because "connected" without "and could read the directory you named" is the
 * half of the answer that never turns out to be the problem.
 */
public class SftpConnectorServlet extends MirthServlet implements SftpConnectorServletInterface {

    private final TemplateValueReplacer replacer = new TemplateValueReplacer();

    public SftpConnectorServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        super(request, sc, PLUGIN_POINT);
    }

    @Override
    public ConnectionTestResponse testConnection(String channelId, String channelName,
            ConnectorProperties properties) {
        SftpConnectionProperties connection;
        String directory;

        if (properties instanceof SftpDispatcherProperties) {
            SftpDispatcherProperties props = (SftpDispatcherProperties) properties;
            connection = props.getConnectionProperties();
            directory = props.getRemoteDirectory();
        } else if (properties instanceof SftpReceiverProperties) {
            SftpReceiverProperties props = (SftpReceiverProperties) properties;
            if (props.getMode() != SourceMode.PULL) {
                return new ConnectionTestResponse(ConnectionTestResponse.Type.FAILURE,
                        "In push mode this connector is the SFTP server, so there is nothing to connect to."
                                + " Start the channel and connect an SFTP client to it instead.");
            }
            connection = props.getConnectionProperties();
            directory = props.getRemoteDirectory();
        } else {
            return new ConnectionTestResponse(ConnectionTestResponse.Type.FAILURE,
                    "Unexpected connector properties: " + (properties == null ? "none"
                            : properties.getClass().getName()));
        }

        SftpConnectionProperties resolved = new SftpConnectionProperties(connection);
        resolved.setHost(replace(connection.getHost(), channelId, channelName));
        resolved.setPort(replace(connection.getPort(), channelId, channelName));
        resolved.setUsername(replace(connection.getUsername(), channelId, channelName));
        resolved.setPassword(replace(connection.getPassword(), channelId, channelName));
        resolved.setPrivateKeyFile(replace(connection.getPrivateKeyFile(), channelId, channelName));
        resolved.setPrivateKey(replace(connection.getPrivateKey(), channelId, channelName));
        resolved.setPassphrase(replace(connection.getPassphrase(), channelId, channelName));
        resolved.setKnownHostsFile(replace(connection.getKnownHostsFile(), channelId, channelName));
        resolved.setHostKey(replace(connection.getHostKey(), channelId, channelName));

        String resolvedDirectory = replace(directory, channelId, channelName);

        SftpClient client = null;
        try {
            client = SftpClient.connect(resolved);
            List<SftpFileInfo> files = client.list(resolvedDirectory);
            int fileCount = 0;
            for (SftpFileInfo file : files) {
                if (!file.isDirectory()) {
                    fileCount++;
                }
            }
            return new ConnectionTestResponse(ConnectionTestResponse.Type.SUCCESS,
                    "Connected to sftp://" + resolved.toURIString() + " and listed "
                            + (StringUtils.isBlank(resolvedDirectory) ? "the login directory"
                                    : resolvedDirectory)
                            + " (" + fileCount + " file" + (fileCount == 1 ? "" : "s") + ").");
        } catch (Exception e) {
            return new ConnectionTestResponse(ConnectionTestResponse.Type.FAILURE,
                    SftpClient.rootMessage(e));
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private String replace(String template, String channelId, String channelName) {
        if (template == null) {
            return null;
        }
        return replacer.replaceValues(template, StringUtils.trimToEmpty(channelId),
                StringUtils.trimToEmpty(channelName));
    }
}
