/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.core.Operation.ExecuteType;
import com.mirth.connect.client.core.api.BaseServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;
import com.mirth.connect.client.core.api.Param;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.util.ConnectionTestResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

/**
 * The connector's own REST surface, mounted at {@code /api/connectors/sftp} beside the
 * built-in connectors' services.
 *
 * <p>One operation: prove the settings in front of you work, before saving a channel and
 * finding out from a deploy failure at three in the morning. Both editors call it -- the
 * Swing panel's Test Connection button and the web console's -- which is why it takes the
 * whole connector properties object rather than a list of parameters.
 */
@Path("/connectors/sftp")
@Tag(name = "Connector Services")
@Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
public interface SftpConnectorServletInterface extends BaseServletInterface {

    String PLUGIN_POINT = "SFTP Connector Service";

    /**
     * Connects to the remote server with the supplied settings and lists the configured
     * directory.
     *
     * <p>Accepts either the SFTP Sender's properties or the SFTP Listener's in pull mode.
     * A listener in push mode has nothing to connect to -- it is the server -- and is
     * refused with a message saying so rather than a confusing timeout.
     */
    @POST
    @Path("/_testConnection")
    @Operation(summary = "Tests an SFTP client connection with the supplied connector properties.")
    @MirthOperation(name = "testSftpConnection", display = "Test SFTP connection",
            type = ExecuteType.ASYNC, auditable = false)
    ConnectionTestResponse testConnection(// @formatter:off
            @Param("channelId")
            @Parameter(description = "The ID of the channel, used to expand template values.")
            @QueryParam("channelId") String channelId,

            @Param("channelName")
            @Parameter(description = "The name of the channel, used to expand template values.")
            @QueryParam("channelName") String channelName,

            @Param("properties") ConnectorProperties properties) throws ClientException;
    // @formatter:on
}
