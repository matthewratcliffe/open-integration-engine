/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator;

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
 * The connector's own REST surface, mounted at {@code /api/connectors/generator} beside the
 * built-in connectors' services.
 *
 * <p>One operation, and it is the one that matters for a generator: show me what this
 * template actually produces, before a channel is saved and deployed and a downstream
 * system is handed something malformed. Both editors call it -- the Swing panel's Preview
 * button and the web console's -- which is why it takes the whole connector properties
 * object rather than a list of parameters.
 */
@Path("/connectors/generator")
@Tag(name = "Connector Services")
@Consumes({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
public interface GeneratorConnectorServletInterface extends BaseServletInterface {

    String PLUGIN_POINT = "Random Generator Service";

    /**
     * Generates one message from the supplied settings and returns it as text, through the
     * same code path a running channel uses.
     *
     * <p>Answers {@link ConnectionTestResponse.Type#FAILURE} if the template cannot be
     * rendered, with the reason; and reports any placeholder it did not recognise, since an
     * unrecognised one is passed through untouched and would otherwise reach the downstream
     * system as literal {@code ${...}} text.
     */
    @POST
    @Path("/_preview")
    @Operation(summary = "Generates one message from the supplied connector properties.")
    @MirthOperation(name = "previewGeneratedMessage", display = "Preview generated message",
            type = ExecuteType.ASYNC, auditable = false)
    ConnectionTestResponse preview(// @formatter:off
            @Param("channelId")
            @Parameter(description = "The ID of the channel, used to expand template values and to seed the population.")
            @QueryParam("channelId") String channelId,

            @Param("channelName")
            @Parameter(description = "The name of the channel, used to expand template values.")
            @QueryParam("channelName") String channelName,

            @Param("properties") ConnectorProperties properties) throws ClientException;
    // @formatter:on
}
