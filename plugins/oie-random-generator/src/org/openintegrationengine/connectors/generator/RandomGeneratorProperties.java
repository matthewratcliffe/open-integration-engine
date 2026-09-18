/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.PollConnectorProperties;
import com.mirth.connect.donkey.model.channel.PollConnectorPropertiesInterface;
import com.mirth.connect.donkey.model.channel.SourceConnectorProperties;
import com.mirth.connect.donkey.model.channel.SourceConnectorPropertiesInterface;
import com.mirth.connect.donkey.util.DonkeyElement;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.math.NumberUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings for the Random Generator, a source connector that manufactures HL7 v2 messages
 * on the polling schedule instead of receiving them from anywhere.
 *
 * <p>It exists for the times there is no upstream system yet: proving a channel, a mapping
 * or a downstream interface with traffic that looks like a real feed, at a cadence you
 * choose, without asking a hospital for a test extract.
 *
 * <p>Two things make the output usable rather than noise. The template is HL7 you can read
 * and edit -- a built-in sample per message type, or your own pasted in -- with {@code
 * ${...}} placeholders where the varying data goes. And the placeholders resolve against a
 * <em>population</em>, not a dice roll: {@link #getPatientCount()} synthetic patients are
 * built once from {@link #getSeed()}, so patient 7 carries the same MRN, name, date of
 * birth, ward and attending doctor in every message it ever appears in, and the same seed
 * produces the same population on the next deploy and on someone else's engine.
 *
 * <p>When the population has to be a <em>particular</em> set of people -- the patients the
 * downstream system is already loaded with -- {@link PatientSelection#SEQUENTIAL} mode takes
 * them from {@link #getPatients()}, a table the operator fills in, and walks it in order.
 *
 * <p>Serialised into the channel XML by XStream under this class's fully qualified name,
 * which is also the {@code @class} the web administrator's panel writes. Renaming or moving
 * this class breaks every channel already using it.
 */
public class RandomGeneratorProperties extends ConnectorProperties
        implements SourceConnectorPropertiesInterface, PollConnectorPropertiesInterface {

    private SourceConnectorProperties sourceConnectorProperties;
    private PollConnectorProperties pollConnectorProperties;

    /* ---- what to generate ---- */
    private MessageType messageType;
    private String template;

    /* ---- how much, how often ---- */
    private String messagesPerPoll;
    private String maxMessages;

    /* ---- who it is about ---- */
    private String patientCount;
    private PatientSelection patientSelection;
    private String seed;
    private List<PatientRecord> patients;

    /* ---- MSH ---- */
    private String sendingApplication;
    private String sendingFacility;
    private String receivingApplication;
    private String receivingFacility;
    private String processingId;
    private String hl7Version;

    public RandomGeneratorProperties() {
        sourceConnectorProperties = new SourceConnectorProperties(SourceConnectorProperties.RESPONSE_NONE);
        pollConnectorProperties = new PollConnectorProperties();

        messageType = MessageType.ADT_A01;
        /*
         * Blank, not the sample text. The sample is then whatever the installed version of
         * this extension ships, so a channel exported today keeps working when a sample is
         * corrected -- and an operator who has pasted their own HL7 is the only one holding
         * a copy of it. Both panels fill the box in for you; clearing it goes back to the
         * sample.
         */
        template = "";

        messagesPerPoll = "1";
        maxMessages = "0";

        patientCount = "25";
        patientSelection = PatientSelection.RANDOM;
        seed = "";
        patients = new ArrayList<PatientRecord>();

        sendingApplication = "OIE";
        sendingFacility = "GENERATOR";
        receivingApplication = "DOWNSTREAM";
        receivingFacility = "TEST";
        /*
         * T for test. A generator writing P into MSH-11 is a generator whose output is one
         * mis-routed channel away from being treated as a real patient record.
         */
        processingId = "T";
        hl7Version = "2.5.1";
    }

    @Override
    public String getProtocol() {
        return "generator";
    }

    /**
     * Must match {@code <name>} in source.xml exactly: the engine finds the class to
     * instantiate by looking this string up in the connector metadata registry.
     */
    @Override
    public String getName() {
        return "Random Generator";
    }

    @Override
    public String toFormattedString() {
        return (messageType == null ? "?" : messageType.name()) + " x " + messagesPerPoll
                + " per poll, " + effectivePatientCount() + " patients";
    }

    @Override
    public SourceConnectorProperties getSourceConnectorProperties() {
        return sourceConnectorProperties;
    }

    @Override
    public PollConnectorProperties getPollConnectorProperties() {
        return pollConnectorProperties;
    }

    /**
     * Batching is honoured: a template holding several messages is split by the inbound
     * data type exactly as a file of many messages would be.
     */
    @Override
    public boolean canBatch() {
        return true;
    }

    /** Which sample is used when {@link #getTemplate()} is blank, and what MSH-9 says. */
    public MessageType getMessageType() {
        return messageType;
    }

    public void setMessageType(MessageType messageType) {
        this.messageType = messageType;
    }

    /**
     * The HL7 to generate, with {@code ${...}} placeholders where the varying data goes.
     * Blank means the built-in sample for {@link #getMessageType()}.
     *
     * <p>This text is deliberately <em>not</em> passed through the engine's template value
     * replacer, because that uses the same {@code ${...}} syntax and would consume the
     * placeholders before this connector saw them. Configuration map values belong in the
     * MSH fields below, which are replaced normally.
     */
    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    /** The template if one was pasted, otherwise the built-in sample for the type. */
    public String getEffectiveTemplate() {
        return StringUtils.isBlank(template) ? GeneratorSamples.forType(messageType) : template;
    }

    /**
     * Messages generated on each poll. The poll interval and this together are the cadence:
     * 1 every 10 seconds is a trickle to watch, 500 every second is a load test.
     */
    public String getMessagesPerPoll() {
        return messagesPerPoll;
    }

    public void setMessagesPerPoll(String messagesPerPoll) {
        this.messagesPerPoll = messagesPerPoll;
    }

    /**
     * Stop after this many messages in total, or 0 to keep going until the channel is
     * stopped. The count restarts when the channel starts, so redeploying replays the run.
     */
    public String getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(String maxMessages) {
        this.maxMessages = maxMessages;
    }

    /**
     * How many synthetic patients exist. Every message is about one of them, so this is how
     * many distinct MRNs, names and visits the downstream system will ever see -- the knob
     * that decides whether it is testing a busy ward or one patient's whole journey.
     */
    public String getPatientCount() {
        return patientCount;
    }

    public void setPatientCount(String patientCount) {
        this.patientCount = patientCount;
    }

    public PatientSelection getPatientSelection() {
        return patientSelection;
    }

    public void setPatientSelection(PatientSelection patientSelection) {
        this.patientSelection = patientSelection;
    }

    /**
     * The seed the population is built from. The same seed always produces the same
     * patients, so two channels sharing a seed are talking about the same people and a bug
     * report naming patient 7 means something on someone else's engine.
     *
     * <p>Blank derives the seed from the channel id, which is stable across restarts and
     * distinct per channel -- so the default is reproducible without being shared.
     */
    public String getSeed() {
        return seed;
    }

    public void setSeed(String seed) {
        this.seed = seed;
    }

    /**
     * Patients named by the operator, used in {@link PatientSelection#SEQUENTIAL} mode and
     * walked in the order they appear here. Empty means the invented population of {@link
     * #getPatientCount()}, which is also what random mode always uses.
     *
     * <p>Every column of a row is optional: a blank one falls back to the invented patient
     * at that position, so a row naming only an MRN still produces a complete message with
     * a stable address, next of kin and insurer behind it.
     */
    public List<PatientRecord> getPatients() {
        return patients;
    }

    public void setPatients(List<PatientRecord> patients) {
        this.patients = patients;
    }

    /** How many patients a channel with these settings actually cycles through. */
    public int effectivePatientCount() {
        if (patientSelection == PatientSelection.SEQUENTIAL && patients != null && !patients.isEmpty()) {
            return patients.size();
        }
        return NumberUtils.toInt(patientCount, 25);
    }

    /** MSH-3, and {@code ${message.sendingApplication}}. */
    public String getSendingApplication() {
        return sendingApplication;
    }

    public void setSendingApplication(String sendingApplication) {
        this.sendingApplication = sendingApplication;
    }

    /** MSH-4, and {@code ${message.sendingFacility}}. */
    public String getSendingFacility() {
        return sendingFacility;
    }

    public void setSendingFacility(String sendingFacility) {
        this.sendingFacility = sendingFacility;
    }

    /** MSH-5, and {@code ${message.receivingApplication}}. */
    public String getReceivingApplication() {
        return receivingApplication;
    }

    public void setReceivingApplication(String receivingApplication) {
        this.receivingApplication = receivingApplication;
    }

    /** MSH-6, and {@code ${message.receivingFacility}}. */
    public String getReceivingFacility() {
        return receivingFacility;
    }

    public void setReceivingFacility(String receivingFacility) {
        this.receivingFacility = receivingFacility;
    }

    /** MSH-11. {@code T} for test, {@code D} for debug, {@code P} for production. */
    public String getProcessingId() {
        return processingId;
    }

    public void setProcessingId(String processingId) {
        this.processingId = processingId;
    }

    /** MSH-12, and {@code ${message.version}}. */
    public String getHl7Version() {
        return hl7Version;
    }

    public void setHl7Version(String hl7Version) {
        this.hl7Version = hl7Version;
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        int result = messageType == null ? 0 : messageType.hashCode();
        result = 31 * result + (template == null ? 0 : template.hashCode());
        result = 31 * result + (seed == null ? 0 : seed.hashCode());
        return result;
    }

    /*
     * Migrations. The engine calls the whole ladder on every properties object it reads,
     * one rung per release since 3.0.1, so the interface demands them all -- but this
     * connector has only ever had one shape, so there is nothing for any of them to do.
     * ConnectorProperties implements 3.1.0 and later; the two oldest are left abstract
     * there and have to be declared here.
     */
    @Override
    public void migrate3_0_1(DonkeyElement element) {
    }

    @Override
    public void migrate3_0_2(DonkeyElement element) {
    }

    /**
     * Usage statistics, which are aggregated across servers -- so this reports the shape of
     * the configuration and nothing that identifies the deployment. The template is
     * reported only as a length and whether it is the built-in sample, because someone's
     * pasted HL7 is the one field here that could carry real patient data.
     */
    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new LinkedHashMap<String, Object>();
        purged.put("messageType", messageType == null ? null : messageType.name());
        purged.put("customTemplate", StringUtils.isNotBlank(template));
        purged.put("templateLength", template == null ? 0 : template.length());
        purged.put("messagesPerPoll", messagesPerPoll);
        purged.put("maxMessages", maxMessages);
        purged.put("patientCount", patientCount);
        purged.put("patientSelection", patientSelection == null ? null : patientSelection.name());
        purged.put("definedPatients", patients == null ? 0 : patients.size());
        purged.put("seeded", StringUtils.isNotBlank(seed));
        purged.put("processingId", processingId);
        purged.put("hl7Version", hl7Version);
        purged.put("pollConnectorProperties", pollConnectorProperties == null ? null
                : pollConnectorProperties.getPurgedProperties());
        purged.put("sourceConnectorProperties", sourceConnectorProperties == null ? null
                : sourceConnectorProperties.getPurgedProperties());
        return purged;
    }
}
