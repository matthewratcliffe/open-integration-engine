/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator.client;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.client.ui.UIConstants;
import com.mirth.connect.client.ui.panels.connectors.ConnectorSettingsPanel;
import com.mirth.connect.client.ui.panels.connectors.ResponseHandler;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.util.ConnectionTestResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.openintegrationengine.connectors.generator.GeneratorConnectorServletInterface;
import org.openintegrationengine.connectors.generator.GeneratorSamples;
import org.openintegrationengine.connectors.generator.MessageType;
import org.openintegrationengine.connectors.generator.PatientRecord;
import org.openintegrationengine.connectors.generator.PatientSelection;
import org.openintegrationengine.connectors.generator.RandomGeneratorProperties;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Administrator's settings panel for the Random Generator.
 *
 * <p>Two conventions are worth knowing before reading the code:
 *
 * <ul>
 *   <li>The template box is never shown empty: a channel with a blank template -- a new
 *       connector, or hand-written XML -- is shown the built-in sample for its type, and
 *       saving stores what is in the box. What you see is what the channel will send, and a
 *       sample corrected in a later version of this extension does not quietly change the
 *       output of a channel that is already running.</li>
 *   <li>Changing the message type swaps the sample in <em>only</em> if the box still holds
 *       an unedited sample. Somebody's own template is never overwritten by a change to a
 *       combo box.</li>
 *   <li>The patients table belongs to sequential mode and is enabled only there, because a
 *       list is a thing to walk in order. Its columns are all optional: a blank one is
 *       filled from the invented patient at that position.</li>
 * </ul>
 */
public class RandomGeneratorPanel extends ConnectorSettingsPanel {

    private static final String[] PROCESSING_IDS = {"T", "D", "P"};
    private static final String[] HL7_VERSIONS = {"2.3", "2.3.1", "2.4", "2.5", "2.5.1", "2.6", "2.7", "2.8"};

    private final JComboBox<MessageType> messageTypeBox;
    private final JButton loadSampleButton;
    private final JTextArea templateArea;
    private final JButton previewButton;

    private final JTextField messagesPerPollField;
    private final JTextField maxMessagesField;

    private final JTextField patientCountField;
    private final JComboBox<PatientSelection> patientSelectionBox;
    private final JTextField seedField;
    private final JTable patientsTable;
    private final DefaultTableModel patientsModel;
    private final JPanel patientsPanel;
    private final JLabel patientsHint;
    private final JButton addPatientButton;
    private final JButton removePatientButton;

    private final JTextField sendingApplicationField;
    private final JTextField sendingFacilityField;
    private final JTextField receivingApplicationField;
    private final JTextField receivingFacilityField;
    private final JComboBox<String> processingIdBox;
    private final JComboBox<String> hl7VersionBox;

    public RandomGeneratorPanel() {
        setLayout(new GridBagLayout());
        setBackground(UIConstants.BACKGROUND_COLOR);

        messageTypeBox = PanelSupport.comboBox(MessageType.values(),
                "<html>The message type to generate. This fills in MSH-9 and, while the template below is<br>"
                        + "still an unedited sample, swaps in the sample for the type you pick.</html>");
        messageTypeBox.addActionListener(e -> messageTypeChanged());

        loadSampleButton = new JButton("Load Sample");
        loadSampleButton.setToolTipText("Replace the template with the built-in sample for the selected type.");
        loadSampleButton.addActionListener(e -> loadSample());

        templateArea = PanelSupport.textArea(18, 100,
                "<html>The HL7 to generate. <b>${patient.*}</b> and <b>${visit.*}</b> come from the synthetic<br>"
                        + "patient this message is about and are the same every time that patient appears;<br>"
                        + "<b>${message.*}</b> is the envelope; <b>${random.*}</b> is redrawn at every occurrence.<br>"
                        + "An unrecognised placeholder is sent through exactly as written.</html>");
        // Monospaced, because HL7 is read by counting delimiters.
        templateArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        previewButton = new JButton("Preview Message");
        previewButton.setToolTipText("Generate one message on the server with these settings and show it.");
        previewButton.addActionListener(e -> preview());

        messagesPerPollField = PanelSupport.textField(6,
                "<html>Messages generated on each poll. This and the polling interval above are the cadence:<br>"
                        + "one every ten seconds is a trickle to watch, five hundred a second is a load test.</html>");
        maxMessagesField = PanelSupport.textField(8,
                "<html>Stop after this many messages in total. 0 keeps going until the channel is stopped.<br>"
                        + "The count restarts when the channel starts, so a redeploy replays the run.</html>");

        patientCountField = PanelSupport.textField(6,
                "<html>How many synthetic patients exist. Every message is about one of them, so this is how<br>"
                        + "many distinct MRNs, names and visits the downstream system will ever see.</html>");
        patientSelectionBox = PanelSupport.comboBox(PatientSelection.values(),
                "<html><b>Random</b> repeats patients the way a real feed does.<br>"
                        + "<b>Sequential</b> walks the population in order, so every patient appears equally often.</html>");
        seedField = PanelSupport.textField(16,
                "<html>The same seed always produces the same patients, on this engine and on anyone else's.<br>"
                        + "Blank derives it from the channel id: stable across restarts, different per channel.<br>"
                        + "Any text works; a number is used as it stands.</html>");

        /*
         * One row per patient the operator names. Every column is optional -- what is left
         * blank comes from the invented patient at that position, which is the only reason
         * nine columns is enough to describe a patient a message can carry thirty fields of.
         */
        patientsModel = new DefaultTableModel(new Object[] {"MRN", "Family Name", "Given Name",
                "Sex", "Date of Birth", "Visit Number", "Class", "Location", "Attending Doctor"}, 0);
        patientsModel.addTableModelListener(e -> PanelSupport.markDirty());
        patientsTable = new JTable(patientsModel);
        patientsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        patientsTable.setToolTipText("<html>The patients sequential mode walks through, in this order.<br>"
                + "Leave a cell blank and it is filled from the invented patient at that position, so a row<br>"
                + "naming only an MRN still carries a stable address, next of kin and insurer.</html>");
        patientsTable.getColumnModel().getColumn(3).setPreferredWidth(40);
        patientsTable.getColumnModel().getColumn(6).setPreferredWidth(40);

        addPatientButton = new JButton("New");
        addPatientButton.addActionListener(e -> {
            patientsModel.addRow(new Object[] {"", "", "", "", "", "", "", "", ""});
            patientsTable.setRowSelectionInterval(patientsModel.getRowCount() - 1, patientsModel.getRowCount() - 1);
            PanelSupport.markDirty();
            updateEnabledState();
        });
        removePatientButton = new JButton("Delete");
        removePatientButton.addActionListener(e -> {
            int selected = patientsTable.getSelectedRow();
            if (selected >= 0) {
                // Stop the editor first, or the removed row's editor writes into its
                // replacement when it loses focus.
                if (patientsTable.isEditing()) {
                    patientsTable.getCellEditor().stopCellEditing();
                }
                patientsModel.removeRow(selected);
                PanelSupport.markDirty();
                updateEnabledState();
            }
        });

        patientsHint = new JLabel();
        patientsPanel = new JPanel(new BorderLayout());

        sendingApplicationField = PanelSupport.textField(16, "MSH-3, and ${message.sendingApplication}.");
        sendingFacilityField = PanelSupport.textField(16, "MSH-4, and ${message.sendingFacility}.");
        receivingApplicationField = PanelSupport.textField(16, "MSH-5, and ${message.receivingApplication}.");
        receivingFacilityField = PanelSupport.textField(16, "MSH-6, and ${message.receivingFacility}.");
        processingIdBox = PanelSupport.comboBox(PROCESSING_IDS,
                "<html>MSH-11. <b>T</b> for test, <b>D</b> for debug, <b>P</b> for production.<br>"
                        + "Generated traffic marked P is one mis-routed channel away from being taken seriously.</html>");
        hl7VersionBox = PanelSupport.comboBox(HL7_VERSIONS, "MSH-12, and ${message.version}.");

        int row = 0;
        addSection(row++, buildMessagePanel());
        addSection(row++, buildTemplatePanel());
        addSection(row++, buildCadencePanel());
        addSection(row++, buildPopulationPanel());

        patientSelectionBox.addActionListener(e -> updateEnabledState());
        updateEnabledState();
    }

    private void addSection(int row, JPanel panel) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1.0;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(4, 4, 4, 4);
        add(panel, constraints);
    }

    private JPanel buildMessagePanel() {
        JPanel panel = PanelSupport.grid();
        panel.setBorder(BorderFactory.createTitledBorder("Message"));

        JPanel typeRow = new JPanel();
        typeRow.setBackground(UIConstants.BACKGROUND_COLOR);
        typeRow.add(messageTypeBox);
        typeRow.add(loadSampleButton);

        int row = 0;
        PanelSupport.addRow(panel, row++, "Message Type:", typeRow);
        PanelSupport.addRow(panel, row++, "Sending Application:", sendingApplicationField);
        PanelSupport.addRow(panel, row++, "Sending Facility:", sendingFacilityField);
        PanelSupport.addRow(panel, row++, "Receiving Application:", receivingApplicationField);
        PanelSupport.addRow(panel, row++, "Receiving Facility:", receivingFacilityField);
        PanelSupport.addRow(panel, row++, "Processing ID:", processingIdBox);
        PanelSupport.addRow(panel, row, "HL7 Version:", hl7VersionBox);
        return panel;
    }

    private JPanel buildTemplatePanel() {
        JPanel panel = PanelSupport.grid();
        panel.setBorder(BorderFactory.createTitledBorder("Template"));

        JLabel hint = new JLabel("<html><i>${patient.*} and ${visit.*} are fixed per patient &mdash; the same"
                + " MRN, name and PV1 every time that patient appears. ${random.*} is redrawn at every"
                + " occurrence. Line endings are normalised to HL7 carriage returns on the way out.</i></html>");

        JPanel buttons = new JPanel();
        buttons.setBackground(UIConstants.BACKGROUND_COLOR);
        buttons.add(previewButton);

        int row = 0;
        PanelSupport.addWideRow(panel, row++, hint);
        PanelSupport.addWideRow(panel, row++, PanelSupport.scroll(templateArea));
        PanelSupport.addWideRow(panel, row, buttons);
        return panel;
    }

    private JPanel buildCadencePanel() {
        JPanel panel = PanelSupport.grid();
        panel.setBorder(BorderFactory.createTitledBorder("Cadence"));

        int row = 0;
        PanelSupport.addRow(panel, row++, "Messages Per Poll:", messagesPerPollField);
        PanelSupport.addRow(panel, row, "Maximum Messages:", maxMessagesField);
        return panel;
    }

    private JPanel buildPopulationPanel() {
        JPanel panel = PanelSupport.grid();
        panel.setBorder(BorderFactory.createTitledBorder("Population"));

        JScrollPane patientsScroll = new JScrollPane(patientsTable);
        patientsScroll.setPreferredSize(new Dimension(760, 140));

        JPanel patientsButtons = new JPanel();
        patientsButtons.setBackground(UIConstants.BACKGROUND_COLOR);
        patientsButtons.add(addPatientButton);
        patientsButtons.add(removePatientButton);

        patientsPanel.setBackground(UIConstants.BACKGROUND_COLOR);
        patientsPanel.add(patientsScroll, BorderLayout.CENTER);
        patientsPanel.add(patientsButtons, BorderLayout.SOUTH);

        int row = 0;
        PanelSupport.addRow(panel, row++, "Patients:", patientCountField);
        PanelSupport.addRow(panel, row++, "Patient Selection:", patientSelectionBox);
        PanelSupport.addRow(panel, row++, "Seed:", seedField);
        PanelSupport.addRow(panel, row++, "Defined Patients:", patientsPanel);
        PanelSupport.addWideRow(panel, row, patientsHint);
        return panel;
    }

    /**
     * The table is sequential mode's, and the invented-population count is whatever the table
     * does not cover -- so exactly one of the two is live at a time, and the label under the
     * table says which.
     */
    private void updateEnabledState() {
        boolean sequential = patientSelectionBox.getSelectedItem() == PatientSelection.SEQUENTIAL;
        boolean defined = sequential && patientsModel.getRowCount() > 0;

        patientsTable.setEnabled(sequential);
        patientsTable.setBackground(sequential ? java.awt.Color.WHITE : UIConstants.BACKGROUND_COLOR);
        addPatientButton.setEnabled(sequential);
        removePatientButton.setEnabled(sequential);
        patientCountField.setEnabled(!defined);

        if (!sequential) {
            patientsHint.setText("<html><i>The table is used in sequential mode only: it is a list to walk"
                    + " in order. Random mode invents the population above.<br>Rows are kept, not"
                    + " discarded, if you switch back.</i></html>");
        } else if (defined) {
            patientsHint.setText("<html><i>" + patientsModel.getRowCount() + " patient(s) defined; the"
                    + " Patients count above is ignored. Blank cells are filled from the invented patient"
                    + " at that position.</i></html>");
        } else {
            patientsHint.setText("<html><i>No patients defined, so sequential mode walks the invented"
                    + " population above. Add rows to name them.</i></html>");
        }
    }

    /* ------------------------------------------------------------------ */
    /* samples                                                             */
    /* ------------------------------------------------------------------ */

    /** True if the box holds a sample nobody has edited, whichever type it came from. */
    private boolean holdsUneditedSample() {
        String current = PanelSupport.text(templateArea).trim();
        if (current.isEmpty()) {
            return true;
        }
        for (MessageType type : MessageType.values()) {
            if (current.equals(GeneratorSamples.forType(type).trim())) {
                return true;
            }
        }
        return false;
    }

    private void messageTypeChanged() {
        if (holdsUneditedSample()) {
            templateArea.setText(GeneratorSamples.forType(selectedType()));
            templateArea.setCaretPosition(0);
        }
    }

    private void loadSample() {
        if (!holdsUneditedSample()) {
            int answer = JOptionPane.showConfirmDialog(this,
                    "Replace the template with the sample for " + selectedType() + "?",
                    "Load Sample", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        templateArea.setText(GeneratorSamples.forType(selectedType()));
        templateArea.setCaretPosition(0);
        PanelSupport.markDirty();
    }

    private MessageType selectedType() {
        MessageType selected = (MessageType) messageTypeBox.getSelectedItem();
        return selected == null ? MessageType.ADT_A01 : selected;
    }

    /* ------------------------------------------------------------------ */
    /* preview                                                             */
    /* ------------------------------------------------------------------ */

    private void preview() {
        final RandomGeneratorProperties properties = (RandomGeneratorProperties) getProperties();
        ResponseHandler handler = new ResponseHandler() {
            @Override
            public void handle(Object response) {
                ConnectionTestResponse result = (ConnectionTestResponse) response;
                if (result == null) {
                    PlatformUI.MIRTH_FRAME.alertError(PlatformUI.MIRTH_FRAME,
                            "Failed to invoke the connector service.");
                } else if (result.getType() == ConnectionTestResponse.Type.SUCCESS) {
                    showMessage(result.getMessage());
                } else {
                    PlatformUI.MIRTH_FRAME.alertWarning(PlatformUI.MIRTH_FRAME, result.getMessage());
                }
            }
        };
        try {
            getServlet(GeneratorConnectorServletInterface.class, "Generating a message...",
                    "Error generating a message: ", handler)
                    .preview(getChannelId(), getChannelName(), properties);
        } catch (ClientException e) {
            // The proxy runs the call in a worker and reports failures through the handler;
            // this only fires if the invocation itself could not be made.
            PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
        }
    }

    /** A scrollable monospaced dialog: a generated message is too wide for an alert box. */
    private void showMessage(String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(760, 420));
        JOptionPane.showMessageDialog(this, scroll, "Generated Message", JOptionPane.PLAIN_MESSAGE);
    }

    /* ------------------------------------------------------------------ */
    /* ConnectorSettingsPanel                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public String getConnectorName() {
        return new RandomGeneratorProperties().getName();
    }

    @Override
    public ConnectorProperties getProperties() {
        RandomGeneratorProperties properties = new RandomGeneratorProperties();

        properties.setMessageType(selectedType());
        properties.setTemplate(PanelSupport.text(templateArea));

        properties.setMessagesPerPoll(PanelSupport.text(messagesPerPollField));
        properties.setMaxMessages(PanelSupport.text(maxMessagesField));

        properties.setPatientCount(PanelSupport.text(patientCountField));
        properties.setPatientSelection((PatientSelection) patientSelectionBox.getSelectedItem());
        properties.setSeed(PanelSupport.text(seedField));
        properties.setPatients(getPatients());

        properties.setSendingApplication(PanelSupport.text(sendingApplicationField));
        properties.setSendingFacility(PanelSupport.text(sendingFacilityField));
        properties.setReceivingApplication(PanelSupport.text(receivingApplicationField));
        properties.setReceivingFacility(PanelSupport.text(receivingFacilityField));
        properties.setProcessingId((String) processingIdBox.getSelectedItem());
        properties.setHl7Version((String) hl7VersionBox.getSelectedItem());

        return properties;
    }

    @Override
    public void setProperties(ConnectorProperties props) {
        RandomGeneratorProperties properties = (RandomGeneratorProperties) props;

        MessageType type = properties.getMessageType() == null ? MessageType.ADT_A01 : properties.getMessageType();
        messageTypeBox.setSelectedItem(type);

        // Blank means "the sample for this type", and the box is never shown empty.
        templateArea.setText(StringUtils.isBlank(properties.getTemplate())
                ? GeneratorSamples.forType(type) : properties.getTemplate());
        templateArea.setCaretPosition(0);

        messagesPerPollField.setText(properties.getMessagesPerPoll());
        maxMessagesField.setText(properties.getMaxMessages());

        patientCountField.setText(properties.getPatientCount());
        patientSelectionBox.setSelectedItem(properties.getPatientSelection() == null
                ? PatientSelection.RANDOM : properties.getPatientSelection());
        seedField.setText(properties.getSeed());

        patientsModel.setRowCount(0);
        if (properties.getPatients() != null) {
            for (PatientRecord patient : properties.getPatients()) {
                patientsModel.addRow(new Object[] {patient.getMrn(), patient.getFamily(),
                        patient.getGiven(), patient.getSex(), patient.getDateOfBirth(),
                        patient.getVisitNumber(), patient.getPatientClass(), patient.getLocation(),
                        patient.getAttendingDoctor()});
            }
        }
        updateEnabledState();

        sendingApplicationField.setText(properties.getSendingApplication());
        sendingFacilityField.setText(properties.getSendingFacility());
        receivingApplicationField.setText(properties.getReceivingApplication());
        receivingFacilityField.setText(properties.getReceivingFacility());
        processingIdBox.setSelectedItem(properties.getProcessingId());
        hl7VersionBox.setSelectedItem(properties.getHl7Version());
    }

    @Override
    public ConnectorProperties getDefaults() {
        return new RandomGeneratorProperties();
    }

    @Override
    public boolean checkProperties(ConnectorProperties props, boolean highlight) {
        RandomGeneratorProperties properties = (RandomGeneratorProperties) props;
        boolean valid = true;

        if (StringUtils.isBlank(properties.getEffectiveTemplate())) {
            valid = false;
            if (highlight) {
                PanelSupport.setValid(templateArea, false);
            }
        }
        if (!atLeast(properties.getMessagesPerPoll(), 1)) {
            valid = false;
            if (highlight) {
                PanelSupport.setValid(messagesPerPollField, false);
            }
        }
        if (!atLeast(properties.getMaxMessages(), 0)) {
            valid = false;
            if (highlight) {
                PanelSupport.setValid(maxMessagesField, false);
            }
        }
        if (!atLeast(properties.getPatientCount(), 1)) {
            valid = false;
            if (highlight) {
                PanelSupport.setValid(patientCountField, false);
            }
        }
        /*
         * A date the connector cannot parse would otherwise fail the deploy rather than the
         * save, which is a much worse place to find out about a typo in a table.
         */
        for (PatientRecord patient : properties.getPatients()) {
            if (!isDateOfBirth(patient.getDateOfBirth())) {
                valid = false;
                if (highlight) {
                    PanelSupport.setValid(patientsTable, false);
                }
                break;
            }
        }
        return valid;
    }

    private List<PatientRecord> getPatients() {
        if (patientsTable.isEditing()) {
            // Otherwise the cell being edited right now is not in the model yet and the
            // change is silently dropped on save.
            patientsTable.getCellEditor().stopCellEditing();
        }
        List<PatientRecord> patients = new ArrayList<PatientRecord>();
        for (int row = 0; row < patientsModel.getRowCount(); row++) {
            PatientRecord patient = new PatientRecord();
            patient.setMrn(cell(row, 0));
            patient.setFamily(cell(row, 1));
            patient.setGiven(cell(row, 2));
            patient.setSex(cell(row, 3));
            patient.setDateOfBirth(cell(row, 4));
            patient.setVisitNumber(cell(row, 5));
            patient.setPatientClass(cell(row, 6));
            patient.setLocation(cell(row, 7));
            patient.setAttendingDoctor(cell(row, 8));
            patients.add(patient);
        }
        return patients;
    }

    private String cell(int row, int column) {
        Object value = patientsModel.getValueAt(row, column);
        return value == null ? "" : String.valueOf(value);
    }

    /** {@code yyyyMMdd} or {@code yyyy-MM-dd}, which is what the connector parses. */
    private static boolean isDateOfBirth(String value) {
        String text = StringUtils.trimToEmpty(value);
        if (text.isEmpty() || text.contains("${")) {
            return true;
        }
        return text.matches("\\d{8}") || text.matches("\\d{4}-\\d{2}-\\d{2}");
    }

    /**
     * A number this big or bigger -- or a template, which cannot be checked here because
     * the configuration map it refers to lives on the server. The connector validates those
     * on deploy, where the value is known.
     */
    private static boolean atLeast(String value, long minimum) {
        if (value != null && value.contains("${")) {
            return true;
        }
        return NumberUtils.toLong(StringUtils.trimToEmpty(value), Long.MIN_VALUE) >= minimum;
    }

    @Override
    public void resetInvalidProperties() {
        PanelSupport.setValid(templateArea, true);
        PanelSupport.setValid(messagesPerPollField, true);
        PanelSupport.setValid(maxMessagesField, true);
        PanelSupport.setValid(patientCountField, true);
        PanelSupport.setValid(patientsTable, true);
    }
}
