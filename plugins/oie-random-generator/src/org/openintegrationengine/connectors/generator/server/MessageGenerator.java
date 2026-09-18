/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator.server;

import org.openintegrationengine.connectors.generator.MessageType;
import org.openintegrationengine.connectors.generator.PatientRecord;
import org.openintegrationengine.connectors.generator.PatientSelection;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Produces one message at a time from a template, a population and a clock.
 *
 * <p>Deliberately separate from the connector: the preview in both administrators runs
 * through exactly this class, so what an operator is shown before saving a channel is
 * produced by the same code that will run in it, rather than by a second implementation
 * that agrees with it today.
 *
 * <p>Thread safe, on the same terms as {@link PatientPool}: the settings are read only, the
 * sequence is atomic, and each call uses its own {@link Random}.
 */
final class MessageGenerator {

    private static final DateTimeFormatter CONTROL_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** Resolved settings: templates already expanded, numbers already parsed. */
    static final class Settings {
        String template = "";
        MessageType type = MessageType.ADT_A01;
        PatientSelection patientSelection = PatientSelection.RANDOM;
        int patientCount = 25;
        /* Named patients, in sequential mode. Empty means invent patientCount of them. */
        List<PatientRecord> patients = Collections.emptyList();
        long seed;
        String sendingApplication = "";
        String sendingFacility = "";
        String receivingApplication = "";
        String receivingFacility = "";
        String processingId = "";
        String version = "";
    }

    /** One generated message and the few facts about it worth putting in the source map. */
    static final class GeneratedMessage {
        final String content;
        final String controlId;
        final String filename;
        final long sequence;
        final SyntheticPatient patient;
        final List<String> unresolved;

        private GeneratedMessage(String content, String controlId, String filename, long sequence,
                SyntheticPatient patient, List<String> unresolved) {
            this.content = content;
            this.controlId = controlId;
            this.filename = filename;
            this.sequence = sequence;
            this.patient = patient;
            this.unresolved = unresolved;
        }
    }

    private final Settings settings;
    private final PatientPool pool;
    private final AtomicLong sequence = new AtomicLong();
    private final Random random = new Random();

    MessageGenerator(Settings settings) {
        this.settings = settings;
        this.pool = new PatientPool(settings.seed, settings.patientCount, LocalDateTime.now(),
                settings.patients);
    }

    PatientPool getPool() {
        return pool;
    }

    long getCount() {
        return sequence.get();
    }

    /**
     * @throws IllegalArgumentException if a placeholder's arguments are unusable; the
     *                                  message says which placeholder and why
     */
    GeneratedMessage next() {
        return generate(null);
    }

    /** The same, about a named patient, which is what a preview wants to be able to repeat. */
    GeneratedMessage generate(Integer patientIndex) {
        MessageContext context = new MessageContext();
        context.now = LocalDateTime.now();
        context.random = random;
        context.type = settings.type;
        context.sendingApplication = settings.sendingApplication;
        context.sendingFacility = settings.sendingFacility;
        context.receivingApplication = settings.receivingApplication;
        context.receivingFacility = settings.receivingFacility;
        context.processingId = settings.processingId;
        context.version = settings.version;

        long number = sequence.incrementAndGet();
        context.sequence = number;
        /*
         * Timestamp plus a counter. MSH-10 has to be unique for a receiver to be able to
         * detect a duplicate, and a timestamp alone is not: at any cadence worth testing,
         * several messages share a second.
         */
        context.controlId = context.now.format(CONTROL_ID_FORMAT) + String.format("%06d", number % 1000000L);

        context.patient = patientIndex == null
                ? pool.select(settings.patientSelection, random)
                : pool.get(patientIndex.intValue());

        List<String> unresolved = new ArrayList<String>();
        String rendered = TemplateRenderer.render(settings.template, context, unresolved);

        String filename = (settings.type == null ? "MSG" : settings.type.name()) + "_" + context.controlId + ".hl7";
        return new GeneratedMessage(toHl7(rendered), context.controlId, filename, number,
                context.patient, unresolved);
    }

    /**
     * HL7 v2 terminates segments with a carriage return, and nothing else will do: a
     * message with {@code \n} between segments is one segment as far as a conformant parser
     * is concerned, including the engine's own HL7 v2 data type. Templates are written and
     * pasted in editors, so they arrive with whatever that editor uses -- which makes this
     * the single most load-bearing four lines in the connector.
     *
     * <p>Blank lines go too. They are invisible in an editor and fatal in a message.
     */
    private static String toHl7(String rendered) {
        String[] lines = rendered.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder(rendered.length());
        for (String line : lines) {
            String segment = line.trim();
            if (!segment.isEmpty()) {
                out.append(segment).append('\r');
            }
        }
        return out.toString();
    }
}
