/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator.server;

import org.openintegrationengine.connectors.generator.MessageType;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * Everything one message's placeholders resolve against: the patient it is about, the MSH
 * values the channel is configured with, when "now" is, and the generator to draw
 * {@code ${random.*}} from.
 *
 * <p>Built fresh per message by {@link MessageGenerator} and thrown away afterwards, which
 * is what keeps the per-message randomness out of the population's.
 */
final class MessageContext {

    SyntheticPatient patient;
    MessageType type;

    String sendingApplication = "";
    String sendingFacility = "";
    String receivingApplication = "";
    String receivingFacility = "";
    String processingId = "";
    String version = "";

    String controlId = "";
    long sequence;

    LocalDateTime now = LocalDateTime.now();
    Random random = new Random();
}
