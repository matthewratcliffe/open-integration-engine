/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator.server;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.server.api.MirthServlet;
import com.mirth.connect.server.util.TemplateValueReplacer;
import com.mirth.connect.util.ConnectionTestResponse;

import org.openintegrationengine.connectors.generator.GeneratorConnectorServletInterface;
import org.openintegrationengine.connectors.generator.RandomGeneratorProperties;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

/**
 * Serves {@link GeneratorConnectorServletInterface}.
 *
 * <p>The preview runs the settings in front of you through {@link MessageGenerator}, the
 * same class a deployed channel uses, with the same seed -- so the patient shown here is
 * the patient the channel will send, not a lookalike produced by a second implementation.
 *
 * <p>The message is returned with newline separators rather than HL7's carriage returns,
 * because it is going into a dialog or a panel to be read. A channel's output is terminated
 * properly; this copy is for human eyes.
 */
public class GeneratorConnectorServlet extends MirthServlet implements GeneratorConnectorServletInterface {

    private final TemplateValueReplacer replacer = new TemplateValueReplacer();

    public GeneratorConnectorServlet(@Context HttpServletRequest request, @Context SecurityContext sc) {
        super(request, sc, PLUGIN_POINT);
    }

    @Override
    public ConnectionTestResponse preview(String channelId, String channelName, ConnectorProperties properties) {
        if (!(properties instanceof RandomGeneratorProperties)) {
            return new ConnectionTestResponse(ConnectionTestResponse.Type.FAILURE,
                    "Unexpected connector properties: " + (properties == null ? "none"
                            : properties.getClass().getName()));
        }

        RandomGeneratorProperties props = (RandomGeneratorProperties) properties;
        MessageGenerator.Settings settings =
                RandomGeneratorReceiver.resolve(props, replacer, channelId, channelName);
        if (settings.template == null || settings.template.trim().isEmpty()) {
            return new ConnectionTestResponse(ConnectionTestResponse.Type.FAILURE,
                    "The message template is empty, and no sample is available for " + props.getMessageType() + ".");
        }

        try {
            MessageGenerator generator = new MessageGenerator(settings);
            MessageGenerator.GeneratedMessage message = generator.next();

            StringBuilder out = new StringBuilder();
            out.append(message.patient.describe())
                    .append(", ").append(message.patient.getIndex())
                    .append(" of ").append(generator.getPool().size())
                    .append(", seed ").append(generator.getPool().getSeed())
                    .append('\n');
            if (!message.unresolved.isEmpty()) {
                // Worth saying loudly: these reach the downstream system as literal
                // ${...} text, which is almost always a typo rather than a decision.
                out.append("Unrecognised placeholders, passed through as written: ")
                        .append(String.join(", ", message.unresolved))
                        .append('\n');
            }
            out.append('\n').append(message.content.replace('\r', '\n'));

            return new ConnectionTestResponse(ConnectionTestResponse.Type.SUCCESS, out.toString());
        } catch (IllegalArgumentException e) {
            return new ConnectionTestResponse(ConnectionTestResponse.Type.FAILURE,
                    "The template could not be rendered: " + e.getMessage());
        } catch (Exception e) {
            return new ConnectionTestResponse(ConnectionTestResponse.Type.FAILURE,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
