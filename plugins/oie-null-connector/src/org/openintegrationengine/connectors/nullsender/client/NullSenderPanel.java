/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.nullsender.client;

import com.mirth.connect.client.ui.VariableListHandler.TransferMode;
import com.mirth.connect.client.ui.panels.connectors.ConnectorSettingsPanel;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;

import org.openintegrationengine.connectors.nullsender.AckMode;
import org.openintegrationengine.connectors.nullsender.NullDispatcherProperties;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * The desktop Administrator's settings panel for the Null Sender.
 *
 * <p>Mirrors the web administrator's panel field for field, so a channel is fully editable
 * from either client. The whole form is about the acknowledgement, because that is the only
 * decision this connector has to make -- everything else about it is the absence of a
 * decision.
 */
public class NullSenderPanel extends ConnectorSettingsPanel {

    private static final long serialVersionUID = 1L;

    private static final String ACK_CODE_PATTERN = "(?i)A[ARE]|C[ARE]";

    private final JComboBox<AckMode> ackMode = FormBuilder.comboBox(AckMode.values());
    private final JTextArea responseTemplate = FormBuilder.textArea(8);
    private final JScrollPane responseTemplateScroll = FormBuilder.scroll(responseTemplate, 140);
    private final JTextField ackCode = FormBuilder.text(8);
    private final JTextField ackTextMessage = FormBuilder.text(35);
    private final JCheckBox logEachMessage = FormBuilder.checkBox("Log one line per discarded message");

    public NullSenderPanel() {
        FormBuilder form = new FormBuilder(this);

        form.section("Null Sender Settings");
        form.note("<html>Every message is recorded and acknowledged, and nothing is delivered"
                + " anywhere.<br>The payload is read only to build the acknowledgement.</html>");
        form.field("Acknowledgement", ackMode,
                "None returns no content -- the right choice when the source connector generates its own"
                        + " acknowledgement. Template returns the text below. HL7 ACK builds a v2"
                        + " acknowledgement from the inbound message.");
        form.field("Response Template", responseTemplateScroll,
                "The response content. Supports ${} values, so a transformer can decide what comes back.");
        form.field("ACK Code", ackCode,
                "MSA-1: AA, AE or AR, or the enhanced-mode CA, CE or CR. Supports ${} values.");
        form.field("ACK Text Message", ackTextMessage,
                "MSA-3, the human-readable text on the acknowledgement. Supports ${} values; blank is fine.");

        form.section("Logging");
        form.field("Server Log", logEachMessage,
                "Off by default: the message store is already the record of what arrived, and a channel"
                        + " ending here is usually a busy one. Useful while proving an interface end to end.");

        ackMode.addActionListener(e -> updateEnabledState());
        updateEnabledState();
    }

    /** Only the fields the selected acknowledgement actually reads stay enabled. */
    private void updateEnabledState() {
        AckMode mode = (AckMode) ackMode.getSelectedItem();
        boolean template = mode == AckMode.TEMPLATE;
        boolean hl7 = mode == AckMode.HL7_ACK;

        responseTemplate.setEnabled(template);
        responseTemplateScroll.setEnabled(template);
        ackCode.setEnabled(hl7);
        ackTextMessage.setEnabled(hl7);
    }

    /** The response template takes dropped variables, so the variable list uses Velocity syntax. */
    @Override
    public TransferMode getTransferMode() {
        return TransferMode.VELOCITY;
    }

    @Override
    public String getConnectorName() {
        return new NullDispatcherProperties().getName();
    }

    @Override
    public ConnectorProperties getProperties() {
        NullDispatcherProperties props = new NullDispatcherProperties();

        props.setAckMode((AckMode) ackMode.getSelectedItem());
        props.setResponseTemplate(FormBuilder.text(responseTemplate));
        props.setAckCode(FormBuilder.text(ackCode));
        props.setAckTextMessage(FormBuilder.text(ackTextMessage));
        props.setLogEachMessage(logEachMessage.isSelected());

        return props;
    }

    @Override
    public void setProperties(ConnectorProperties properties) {
        NullDispatcherProperties props = (NullDispatcherProperties) properties;

        ackMode.setSelectedItem(props.getAckMode() == null ? AckMode.NONE : props.getAckMode());
        responseTemplate.setText(props.getResponseTemplate());
        ackCode.setText(props.getAckCode());
        ackTextMessage.setText(props.getAckTextMessage());
        logEachMessage.setSelected(props.isLogEachMessage());

        updateEnabledState();
    }

    @Override
    public ConnectorProperties getDefaults() {
        return new NullDispatcherProperties();
    }

    /**
     * Only the ACK code can be wrong, and only in HL7 mode. A blank response template is
     * allowed on purpose: a channel that wants to answer with nothing at all but still be
     * recorded as having answered is a real configuration, not a mistake.
     */
    @Override
    public boolean checkProperties(ConnectorProperties properties, boolean highlight) {
        NullDispatcherProperties props = (NullDispatcherProperties) properties;
        boolean valid = true;

        if (props.getAckMode() == AckMode.HL7_ACK) {
            String code = props.getAckCode() == null ? "" : props.getAckCode().trim();
            // A template is resolved per message, so it can only be checked at runtime.
            boolean templated = code.contains("${");
            if (!templated && !code.matches(ACK_CODE_PATTERN)) {
                valid = false;
                if (highlight) {
                    FormBuilder.invalid(ackCode);
                }
            }
        }

        return valid;
    }

    @Override
    public void resetInvalidProperties() {
        FormBuilder.valid(ackCode);
    }
}
