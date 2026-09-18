/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator.server;

import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.model.message.BatchRawMessage;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.DispatchResult;
import com.mirth.connect.donkey.server.channel.PollConnector;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.donkey.server.message.batch.BatchMessageReader;
import com.mirth.connect.donkey.util.ThreadUtils;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.util.TemplateValueReplacer;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openintegrationengine.connectors.generator.MessageType;
import org.openintegrationengine.connectors.generator.PatientRecord;
import org.openintegrationengine.connectors.generator.PatientSelection;
import org.openintegrationengine.connectors.generator.RandomGeneratorProperties;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Random Generator.
 *
 * <p>A source connector with nothing upstream of it: on each poll it manufactures
 * {@code messagesPerPoll} HL7 messages from the template and hands them to the channel,
 * which is what makes the polling schedule the cadence of a synthetic feed.
 *
 * <p>It is a {@link PollConnector} rather than a thread of its own precisely so the cadence
 * is configured the way every other scheduled thing in the engine is -- an interval, a time
 * of day, or cron -- instead of inventing a second way to say "every ten seconds".
 */
public class RandomGeneratorReceiver extends PollConnector {

    private final Logger logger = LogManager.getLogger(getClass());
    private final EventController eventController = ControllerFactory.getFactory().createEventController();
    private final TemplateValueReplacer replacer = new TemplateValueReplacer();

    private RandomGeneratorProperties connectorProperties;
    private MessageGenerator generator;
    private int messagesPerPoll;
    private long maxMessages;
    private boolean limitReported;

    @Override
    public void onDeploy() throws ConnectorTaskException {
        connectorProperties = (RandomGeneratorProperties) getConnectorProperties();

        messagesPerPoll = NumberUtils.toInt(replace(connectorProperties.getMessagesPerPoll()), 1);
        if (messagesPerPoll < 1) {
            throw new ConnectorTaskException("Messages per poll must be at least 1.");
        }
        maxMessages = NumberUtils.toLong(replace(connectorProperties.getMaxMessages()), 0L);
        if (maxMessages < 0) {
            throw new ConnectorTaskException("Maximum messages cannot be negative; use 0 for no limit.");
        }

        MessageGenerator.Settings settings = settings();
        if (StringUtils.isBlank(settings.template)) {
            throw new ConnectorTaskException("The message template is empty, and no sample is available for "
                    + connectorProperties.getMessageType() + ".");
        }

        /*
         * Render one message now rather than at the first poll. A template whose
         * placeholders cannot be parsed then fails the deploy, with the reason, instead of
         * failing every poll from then on in the error log of a channel that looks started.
         */
        MessageGenerator.GeneratedMessage sample;
        try {
            // Building the generator builds the population, so a row with an unparseable
            // date of birth fails here too, naming the row.
            sample = new MessageGenerator(settings).next();
        } catch (IllegalArgumentException e) {
            throw new ConnectorTaskException(e.getMessage(), e);
        }
        if (!sample.unresolved.isEmpty()) {
            // Not fatal: the text is passed through untouched, which is recoverable and
            // visible in the message. Silently dropping it would not be.
            logger.warn("Channel {} generates messages containing placeholders this connector does not"
                    + " recognise, which are sent through as written: {}",
                    getChannelId(), String.join(", ", sample.unresolved));
        }

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getSourceName(), ConnectionStatusEventType.IDLE));
    }

    @Override
    public void onUndeploy() throws ConnectorTaskException {
    }

    @Override
    public void onStart() throws ConnectorTaskException {
        /*
         * The population is built here, not in onDeploy, so stopping and starting a channel
         * is a clean run: the same patients (the seed has not changed) with the sequence,
         * the message limit and the admit times drawn again from the current time.
         */
        generator = new MessageGenerator(settings());
        limitReported = false;
        logger.info("Channel {} generating {} {} message(s) per poll from {} of {} patient(s) (seed {})",
                getChannelId(), messagesPerPoll, connectorProperties.getMessageType(),
                connectorProperties.getPatientSelection() == PatientSelection.SEQUENTIAL
                        && !connectorProperties.getPatients().isEmpty() ? "a named list" : "a population",
                generator.getPool().size(), generator.getPool().getSeed());

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getSourceName(), ConnectionStatusEventType.IDLE));
    }

    @Override
    public void onStop() throws ConnectorTaskException {
        shutdown();
    }

    @Override
    public void onHalt() throws ConnectorTaskException {
        shutdown();
    }

    private void shutdown() {
        generator = null;
        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getSourceName(), ConnectionStatusEventType.DISCONNECTED));
    }

    /**
     * Nothing is waiting for a response -- there is no sender to answer. Recovered responses
     * are finished so the message is marked processed rather than sitting in the recovery
     * set forever.
     */
    @Override
    public void handleRecoveredResponse(DispatchResult dispatchResult) {
        finishDispatch(dispatchResult);
    }

    @Override
    protected void poll() throws InterruptedException {
        MessageGenerator current = generator;
        if (current == null) {
            return;
        }

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                getSourceName(), ConnectionStatusEventType.POLLING));

        String pollId = UUID.randomUUID().toString();
        try {
            for (int i = 0; i < messagesPerPoll; i++) {
                ThreadUtils.checkInterruptedStatus();
                if (isTerminated()) {
                    return;
                }
                if (maxMessages > 0 && current.getCount() >= maxMessages) {
                    if (!limitReported) {
                        limitReported = true;
                        logger.info("Channel {} has generated its limit of {} message(s); nothing further"
                                + " will be generated until the channel is restarted.", getChannelId(), maxMessages);
                    }
                    return;
                }
                generateOne(current, pollId, i + 1, i == messagesPerPoll - 1);
            }
        } finally {
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                    getSourceName(), ConnectionStatusEventType.IDLE));
        }
    }

    private void generateOne(MessageGenerator current, String pollId, int sequenceInPoll, boolean pollComplete) {
        try {
            MessageGenerator.GeneratedMessage message = current.next();

            Map<String, Object> sourceMap = new HashMap<String, Object>();
            // Named the way the File Reader names it, so a channel moved onto this
            // connector for testing does not have to change its filter or transformer.
            sourceMap.put("originalFilename", message.filename);
            sourceMap.put("generatorMessageType", connectorProperties.getMessageType() == null ? ""
                    : connectorProperties.getMessageType().name());
            sourceMap.put("generatorControlId", message.controlId);
            sourceMap.put("generatorSequence", Long.valueOf(message.sequence));
            sourceMap.put("generatorPatientId", message.patient.getId());
            sourceMap.put("generatorPatientIndex", Integer.valueOf(message.patient.getIndex()));
            sourceMap.put("generatorMrn", message.patient.getMrn());
            sourceMap.put("generatorVisitNumber", message.patient.getVisitNumber());
            sourceMap.put("pollId", pollId);
            sourceMap.put("pollSequenceId", Integer.valueOf(sequenceInPoll));
            if (pollComplete) {
                sourceMap.put("pollComplete", Boolean.TRUE);
            }

            dispatch(message.content, sourceMap);
        } catch (Throwable t) {
            eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), null,
                    ErrorEventType.SOURCE_CONNECTOR, getSourceName(), connectorProperties.getName(),
                    "Error generating a message", t));
            logger.error("Error generating a message on channel {}", getChannelId(), t);
        }
    }

    private void dispatch(String content, Map<String, Object> sourceMap) throws Exception {
        if (isProcessBatch()) {
            // A template holding several messages is split by the inbound data type, the
            // same way a file of many messages would be.
            StringReader reader = new StringReader(content);
            try {
                BatchRawMessage batchRawMessage = new BatchRawMessage(new BatchMessageReader(reader), sourceMap);
                dispatchBatchMessage(batchRawMessage, null);
            } finally {
                reader.close();
            }
            return;
        }

        RawMessage rawMessage = new RawMessage(content);
        rawMessage.setSourceMap(sourceMap);

        DispatchResult dispatchResult = null;
        try {
            dispatchResult = dispatchRawMessage(rawMessage);
        } finally {
            finishDispatch(dispatchResult);
        }
    }

    /** The configured settings with templates expanded and the seed resolved. */
    private MessageGenerator.Settings settings() {
        return resolve(connectorProperties, replacer, getChannelId(), getChannelName());
    }

    /**
     * Shared with {@link GeneratorConnectorServlet}, so a preview is generated from exactly
     * the settings a deploy would use -- including the seed, which is what makes the
     * previewed patient the same patient the channel will send.
     */
    static MessageGenerator.Settings resolve(RandomGeneratorProperties properties,
            TemplateValueReplacer replacer, String channelId, String channelName) {
        MessageGenerator.Settings settings = new MessageGenerator.Settings();

        /*
         * The template itself is never passed through the replacer: it uses the same
         * ${...} syntax, and the replacer would consume every placeholder in it before
         * this connector ever saw one. The MSH fields around it are replaced normally, so
         * a facility code can still come from the configuration map.
         */
        settings.template = properties.getEffectiveTemplate();
        settings.type = properties.getMessageType() == null ? MessageType.ADT_A01 : properties.getMessageType();
        settings.patientSelection = properties.getPatientSelection() == null
                ? PatientSelection.RANDOM : properties.getPatientSelection();
        settings.patientCount = Math.max(1, NumberUtils.toInt(
                replace(replacer, properties.getPatientCount(), channelId, channelName), 25));
        settings.seed = seed(replace(replacer, properties.getSeed(), channelId, channelName), channelId);
        /*
         * Named patients are a sequential-mode idea: they are a list to walk in order, and
         * picking from them at random would be a different feature wearing the same table.
         * Random mode therefore ignores the rows rather than half-using them, and both
         * panels say so where the table would be.
         */
        settings.patients = settings.patientSelection == PatientSelection.SEQUENTIAL
                ? resolvePatients(properties.getPatients(), replacer, channelId, channelName)
                : Collections.<PatientRecord>emptyList();
        settings.sendingApplication = replace(replacer, properties.getSendingApplication(), channelId, channelName);
        settings.sendingFacility = replace(replacer, properties.getSendingFacility(), channelId, channelName);
        settings.receivingApplication = replace(replacer, properties.getReceivingApplication(), channelId, channelName);
        settings.receivingFacility = replace(replacer, properties.getReceivingFacility(), channelId, channelName);
        settings.processingId = replace(replacer, properties.getProcessingId(), channelId, channelName);
        settings.version = replace(replacer, properties.getHl7Version(), channelId, channelName);
        return settings;
    }

    /** The table with configuration map values expanded, so a cell can hold {@code ${testMrn}}. */
    private static List<PatientRecord> resolvePatients(List<PatientRecord> rows, TemplateValueReplacer replacer,
            String channelId, String channelName) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<PatientRecord> resolved = new ArrayList<PatientRecord>(rows.size());
        for (PatientRecord row : rows) {
            PatientRecord copy = new PatientRecord(row);
            copy.setMrn(replace(replacer, row.getMrn(), channelId, channelName));
            copy.setFamily(replace(replacer, row.getFamily(), channelId, channelName));
            copy.setGiven(replace(replacer, row.getGiven(), channelId, channelName));
            copy.setSex(replace(replacer, row.getSex(), channelId, channelName));
            copy.setDateOfBirth(replace(replacer, row.getDateOfBirth(), channelId, channelName));
            copy.setVisitNumber(replace(replacer, row.getVisitNumber(), channelId, channelName));
            copy.setPatientClass(replace(replacer, row.getPatientClass(), channelId, channelName));
            copy.setLocation(replace(replacer, row.getLocation(), channelId, channelName));
            copy.setAttendingDoctor(replace(replacer, row.getAttendingDoctor(), channelId, channelName));
            resolved.add(copy);
        }
        return resolved;
    }

    /**
     * A number is used as it stands; any other text is hashed, so {@code ward-a} is a
     * perfectly good seed. Blank falls back to the channel id, which makes the default
     * population stable across restarts and different for every channel without anyone
     * having to choose a number.
     *
     * <p>{@link String#hashCode()} is specified by the JDK rather than implementation
     * defined, so the same text gives the same population on any engine -- which is the
     * only reason a seed is worth having.
     */
    private static long seed(String configured, String channelId) {
        String text = StringUtils.isBlank(configured) ? StringUtils.trimToEmpty(channelId) : configured.trim();
        if (text.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return text.hashCode();
        }
    }

    private static String replace(TemplateValueReplacer replacer, String template, String channelId,
            String channelName) {
        if (template == null) {
            return "";
        }
        return replacer.replaceValues(template, StringUtils.trimToEmpty(channelId),
                StringUtils.trimToEmpty(channelName));
    }

    private String replace(String template) {
        return replace(replacer, template, getChannelId(), getChannelName());
    }

    private String getChannelName() {
        return getChannel() == null ? null : getChannel().getName();
    }
}
