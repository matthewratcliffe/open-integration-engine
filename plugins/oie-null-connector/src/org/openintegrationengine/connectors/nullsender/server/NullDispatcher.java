/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.nullsender.server;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.MessageContent;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.DestinationConnector;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.userutil.ACKGenerator;
import com.mirth.connect.server.util.TemplateValueReplacer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openintegrationengine.connectors.nullsender.AckMode;
import org.openintegrationengine.connectors.nullsender.NullDispatcherProperties;

/**
 * The Null Sender: records the message, answers it, and discards it.
 *
 * <p>Three things happen for every message, and it is worth being precise about which is
 * which, because the whole point of the connector is that only one of them is missing:
 *
 * <ul>
 *   <li><b>Recorded.</b> Nothing special is needed. The engine writes the connector message
 *       and its status before and after this class runs, so the message appears in the
 *       message browser and counts towards the dashboard's Sent statistic exactly as it
 *       would for a destination that delivered it.
 *   <li><b>Acknowledged.</b> The response this returns is what the source connector sends
 *       back when the channel's response is set to this destination. For HL7 v2 that ACK is
 *       built by the engine's own {@link ACKGenerator}, the same code the source
 *       connectors' "Auto-generate" setting uses.
 *   <li><b>Dropped.</b> There is no delivery step at all. The payload is read only to build
 *       the acknowledgement and to count what was discarded.
 * </ul>
 *
 * <p>The status is always {@link Status#SENT}. A connector that cannot fail should not
 * invent failures: a malformed message that no ACK can be built from is still successfully
 * discarded, and says so in its status message rather than erroring or queueing.
 */
public class NullDispatcher extends DestinationConnector {

    /** What ACKGenerator's own default is, and what the source connectors generate with. */
    private static final String ACK_DATE_FORMAT = "yyyyMMddHHmmss";

    private final Logger logger = LogManager.getLogger(getClass());
    private final EventController eventController = ControllerFactory.getFactory().createEventController();
    private final TemplateValueReplacer replacer = new TemplateValueReplacer();

    /**
     * Whether the "could not build an ACK" warning has already been logged since this
     * connector started. A channel pointed at the wrong data type would otherwise log the
     * same line for every message, and this connector is the one most likely to be sitting
     * in front of a firehose.
     */
    private volatile boolean ackFailureLogged;

    @Override
    public void onDeploy() throws ConnectorTaskException {
        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getDestinationName(), ConnectionStatusEventType.IDLE));
    }

    @Override
    public void onUndeploy() throws ConnectorTaskException {
    }

    @Override
    public void onStart() throws ConnectorTaskException {
        ackFailureLogged = false;
    }

    @Override
    public void onStop() throws ConnectorTaskException {
    }

    @Override
    public void onHalt() throws ConnectorTaskException {
    }

    /**
     * Deliberately empty.
     *
     * <p>This hook exists so a connector can resolve its templates before the message is
     * sent, and the engine serialises the resolved object into the destination's Sent
     * content. Resolving anything here would therefore write a copy of the message into the
     * message store under the connector whose job was to discard it. The response template
     * and the ACK fields are resolved in {@link #send} instead, against a local string that
     * is never stored.
     */
    @Override
    public void replaceConnectorProperties(ConnectorProperties connectorProperties,
            ConnectorMessage connectorMessage) {
    }

    @Override
    public Response send(ConnectorProperties connectorProperties, ConnectorMessage connectorMessage)
            throws InterruptedException {
        NullDispatcherProperties props = (NullDispatcherProperties) connectorProperties;

        String content = contentOf(connectorMessage);
        int discarded = content == null ? 0 : content.length();
        StringBuilder statusMessage = new StringBuilder("Discarded ").append(discarded)
                .append(discarded == 1 ? " character" : " characters");

        String response = null;
        AckMode mode = props.getAckMode() == null ? AckMode.NONE : props.getAckMode();

        switch (mode) {
            case TEMPLATE:
                response = replacer.replaceValues(props.getResponseTemplate(), connectorMessage);
                statusMessage.append("; template response returned");
                break;

            case HL7_ACK:
                String code = trimmed(replacer.replaceValues(props.getAckCode(), connectorMessage));
                String text = replacer.replaceValues(props.getAckTextMessage(), connectorMessage);
                String failure = null;

                if (content == null || content.trim().isEmpty()) {
                    failure = "the message has no content to acknowledge";
                } else {
                    try {
                        /*
                         * Delegates to the HL7V2 data type plugin's auto-responder, which is
                         * where the source connectors' generated ACKs come from too. It
                         * returns null rather than throwing when that plugin is absent --
                         * which it never is on a stock engine, but an extension should not
                         * assume what the server has installed.
                         */
                        response = ACKGenerator.generateAckResponse(content, isXml(content), code, text,
                                ACK_DATE_FORMAT, "");
                        if (response == null) {
                            failure = "the HL7 v2 data type plugin is not installed";
                        }
                    } catch (Exception e) {
                        failure = "the message is not HL7 v2 (" + rootCause(e) + ")";
                    }
                }

                if (failure == null) {
                    statusMessage.append("; ACK ").append(code).append(" returned");
                } else {
                    statusMessage.append("; no ACK generated: ").append(failure);
                    warnOnce(failure);
                }
                break;

            case NONE:
            default:
                break;
        }

        if (props.isLogEachMessage()) {
            logger.info("Null Sender discarded message {} on channel {} ({}): {}",
                    connectorMessage.getMessageId(), getChannelId(), getDestinationName(), statusMessage);
        }

        /*
         * SENT, always. The message was received, recorded and answered; the only thing that
         * did not happen is the thing this connector exists not to do.
         */
        return new Response(Status.SENT, response, statusMessage.toString());
    }

    /**
     * What to acknowledge and what to count as discarded.
     *
     * <p>A destination's raw content is the source's output, so it still carries the MSH the
     * sending system wrote -- which is what an ACK has to echo. The encoded content is the
     * fallback for a channel whose storage settings mean the raw is not there.
     */
    private String contentOf(ConnectorMessage connectorMessage) {
        String raw = contentOf(connectorMessage.getRaw());
        return raw != null ? raw : contentOf(connectorMessage.getEncoded());
    }

    private String contentOf(MessageContent messageContent) {
        return messageContent == null ? null : messageContent.getContent();
    }

    /** HL7 v2 in its XML encoding rather than ER7, which the ACK generator handles differently. */
    private boolean isXml(String content) {
        return content.trim().startsWith("<");
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
    }

    /**
     * The first failure per start is a warning, the rest are debug. The status message on
     * every affected message carries the same text, so nothing is lost by not repeating it
     * in the log.
     */
    private void warnOnce(String failure) {
        if (ackFailureLogged) {
            logger.debug("Null Sender could not generate an ACK on channel {} ({}): {}",
                    getChannelId(), getDestinationName(), failure);
            return;
        }
        ackFailureLogged = true;
        logger.warn("Null Sender could not generate an ACK on channel {} ({}): {}."
                + " Messages are still being discarded and recorded; only the acknowledgement is empty."
                + " Further occurrences are logged at debug level until this channel is restarted.",
                getChannelId(), getDestinationName(), failure);
    }
}
