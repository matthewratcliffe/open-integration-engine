/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.nullsender;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorPropertiesInterface;
import com.mirth.connect.donkey.util.DonkeyElement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Settings for the Null Sender: a destination that records the message, answers it, and
 * then throws it away.
 *
 * <p>There is deliberately no endpoint here -- no host, no path, no credentials. The
 * connector exists to be the thing on the end of a channel when there is nothing on the
 * end of the channel yet, or when there never will be: an interface a partner has to be
 * able to send to before the downstream system exists, a feed being drained while a
 * migration runs, a load test that should not write anywhere.
 *
 * <p>The message still lands in the message store exactly as it would for any other
 * destination, so "dropped" means the payload goes nowhere, not that nothing is recorded.
 * What this connector is careful about is not <em>copying</em> the payload anywhere new:
 * the engine stores a destination's Sent content by serialising this object after template
 * replacement, so nothing here is ever replaced against the message and nothing here ever
 * holds message data.
 *
 * <p>Serialised into the channel XML by XStream under this class's fully qualified name,
 * which is also the {@code @class} the web administrator's panel writes. Renaming or
 * moving this class breaks every channel already using it.
 */
public class NullDispatcherProperties extends ConnectorProperties
        implements DestinationConnectorPropertiesInterface {

    private DestinationConnectorProperties destinationConnectorProperties;

    private AckMode ackMode;
    private String responseTemplate;
    private String ackCode;
    private String ackTextMessage;
    private boolean logEachMessage;

    public NullDispatcherProperties() {
        /*
         * False is "do not validate the response", and it is the only honest setting: the
         * response is one this connector made up, so validating it would only ever check
         * this connector against itself. See canValidateResponse().
         */
        destinationConnectorProperties = new DestinationConnectorProperties(false);

        ackMode = AckMode.HL7_ACK;
        responseTemplate = "";
        ackCode = "AA";
        ackTextMessage = "";
        logEachMessage = false;
    }

    public NullDispatcherProperties(NullDispatcherProperties props) {
        super(props);
        destinationConnectorProperties = new DestinationConnectorProperties(props.getDestinationConnectorProperties());

        ackMode = props.getAckMode();
        responseTemplate = props.getResponseTemplate();
        ackCode = props.getAckCode();
        ackTextMessage = props.getAckTextMessage();
        logEachMessage = props.isLogEachMessage();
    }

    @Override
    public String getProtocol() {
        return "NULL";
    }

    /**
     * Must match the name in destination.xml exactly: the engine finds the class to
     * instantiate by looking this string up in the connector metadata registry, and both
     * administrators find the settings panel the same way.
     */
    @Override
    public String getName() {
        return "Null Sender";
    }

    @Override
    public String toFormattedString() {
        StringBuilder text = new StringBuilder("Message discarded");
        if (ackMode == AckMode.HL7_ACK) {
            text.append("; HL7 ACK ").append(ackCode == null ? "" : ackCode).append(" returned");
        } else if (ackMode == AckMode.TEMPLATE) {
            text.append("; template response returned");
        } else {
            text.append("; no response returned");
        }
        return text.toString();
    }

    @Override
    public DestinationConnectorProperties getDestinationConnectorProperties() {
        return destinationConnectorProperties;
    }

    /**
     * There is nothing to validate. The queue's response validation compares what came back
     * from the far end against what was expected, and here there is no far end -- every
     * response is generated locally and is by construction the expected one.
     */
    @Override
    public boolean canValidateResponse() {
        return false;
    }

    @Override
    public ConnectorProperties clone() {
        return new NullDispatcherProperties(this);
    }

    /** Which kind of acknowledgement to return. Never affects whether the message is dropped. */
    public AckMode getAckMode() {
        return ackMode;
    }

    public void setAckMode(AckMode ackMode) {
        this.ackMode = ackMode;
    }

    /**
     * The response content for {@link AckMode#TEMPLATE}, as a template.
     *
     * <p>Resolved while the message is being processed rather than in
     * {@code replaceConnectorProperties}, so a template that pulls the message in does not
     * end up written back into this destination's stored Sent content. Dropping a message
     * and then filing a copy of it under the connector that dropped it would be a strange
     * thing for this connector to do.
     */
    public String getResponseTemplate() {
        return responseTemplate;
    }

    public void setResponseTemplate(String responseTemplate) {
        this.responseTemplate = responseTemplate;
    }

    /**
     * MSA-1 for {@link AckMode#HL7_ACK} -- {@code AA}, {@code AE} or {@code AR}, or their
     * enhanced-mode equivalents {@code CA}, {@code CE} and {@code CR}.
     *
     * <p>Templated, so a channel that wants to reject some messages while still discarding
     * them can decide the code in its transformer and put it in the channel map.
     */
    public String getAckCode() {
        return ackCode;
    }

    public void setAckCode(String ackCode) {
        this.ackCode = ackCode;
    }

    /** MSA-3, the human-readable text on the acknowledgement. Templated; blank is fine. */
    public String getAckTextMessage() {
        return ackTextMessage;
    }

    public void setAckTextMessage(String ackTextMessage) {
        this.ackTextMessage = ackTextMessage;
    }

    /**
     * Write one line to the server log per discarded message.
     *
     * <p>Off by default, because the message store is already the record of what arrived
     * and a channel whose only destination is this one is usually a busy one. Worth turning
     * on while proving an interface end to end, when watching the log is easier than
     * refreshing the message browser.
     */
    public boolean isLogEachMessage() {
        return logEachMessage;
    }

    public void setLogEachMessage(boolean logEachMessage) {
        this.logEachMessage = logEachMessage;
    }

    /*
     * Written out rather than reflected over: this class has five fields and no dependency
     * on commons-lang3, and the shared jar is loaded by the desktop Administrator as well
     * as the engine, so every dependency it takes on is one more jar that has to be present
     * in both places.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof NullDispatcherProperties)) {
            return false;
        }
        NullDispatcherProperties other = (NullDispatcherProperties) obj;
        return ackMode == other.ackMode
                && Objects.equals(responseTemplate, other.responseTemplate)
                && Objects.equals(ackCode, other.ackCode)
                && Objects.equals(ackTextMessage, other.ackTextMessage)
                && logEachMessage == other.logEachMessage
                && Objects.equals(destinationConnectorProperties, other.destinationConnectorProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ackMode, responseTemplate, ackCode, ackTextMessage, logEachMessage);
    }

    /*
     * Migrations. These run when a channel saved by an older engine is read, and this
     * connector has only ever had one shape, so there is nothing to move. They still have
     * to exist: Migratable declares them, and the engine calls every one in sequence.
     */
    @Override
    public void migrate3_0_1(DonkeyElement element) {
    }

    @Override
    public void migrate3_0_2(DonkeyElement element) {
    }

    /**
     * Usage statistics, which are aggregated across servers -- so this reports the shape of
     * the configuration and nothing that identifies the deployment or the messages. The
     * response template is reported as a line count rather than as text, because it is the
     * one field here an integrator is likely to put a partner's identifiers into.
     */
    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new LinkedHashMap<String, Object>();
        purged.put("ackMode", ackMode == null ? null : ackMode.name());
        purged.put("ackCode", ackCode);
        purged.put("ackTextMessageSet", ackTextMessage != null && !ackTextMessage.trim().isEmpty());
        purged.put("logEachMessage", logEachMessage);
        purged.put("responseTemplateLines",
                responseTemplate == null ? 0 : responseTemplate.split("\r\n|\r|\n").length);
        purged.put("destinationConnectorProperties", destinationConnectorProperties == null ? null
                : destinationConnectorProperties.getPurgedProperties());
        return purged;
    }
}
