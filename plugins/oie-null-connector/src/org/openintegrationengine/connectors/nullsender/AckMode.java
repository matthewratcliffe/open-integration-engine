/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.nullsender;

/**
 * What the Null Sender hands back as the destination's response.
 *
 * <p>The response matters more here than in a connector that actually delivers something:
 * a channel whose source is set to answer with this destination returns exactly this
 * string to the sending system, and that string is the acknowledgement the far end waits
 * for. Discarding the message and acknowledging it are separate decisions, which is why
 * the acknowledgement is configured rather than assumed.
 *
 * <p>Serialised into the channel XML by name, so these constants are part of the on-disk
 * format -- renaming one silently changes the behaviour of every channel using it.
 */
public enum AckMode {

    /**
     * No response content. The message is still recorded as SENT, so the dashboard counts
     * it and the message browser shows it; there is simply nothing to send back.
     *
     * <p>The right choice when the source connector generates its own acknowledgement --
     * an MLLP or HTTP listener set to "Auto-generate" already answers the sender without
     * any help from a destination.
     */
    NONE("None"),

    /**
     * The response template, with {@code ${...}} values resolved against the message.
     *
     * <p>For anything that is not HL7 v2: a fixed 200-style body, a JSON receipt, a value
     * a transformer put in the channel map.
     */
    TEMPLATE("Template"),

    /**
     * An HL7 v2 acknowledgement generated from the inbound message.
     *
     * <p>Built by the engine's own {@code ACKGenerator}, which is the same code path the
     * source connectors' "Auto-generate" response uses -- so the ACK a partner receives is
     * byte for byte the one they would have received from a channel that acknowledged at
     * the source, control id echoed and all.
     */
    HL7_ACK("HL7 ACK");

    private final String label;

    private AckMode(String label) {
        this.label = label;
    }

    /**
     * The text shown in both administrators' drop-downs. {@link #name()} remains what is
     * written to the channel XML.
     */
    @Override
    public String toString() {
        return label;
    }
}
