/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.client;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.client.ui.UIConstants;
import com.mirth.connect.client.ui.VariableListHandler.TransferMode;
import com.mirth.connect.client.ui.panels.connectors.ConnectorSettingsPanel;
import com.mirth.connect.client.ui.panels.connectors.ResponseHandler;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.util.ConnectionTestResponse;

import org.apache.commons.lang3.StringUtils;
import org.openintegrationengine.connectors.sftp.SftpConnectorServletInterface;
import org.openintegrationengine.connectors.sftp.SftpDispatcherProperties;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * The Administrator's settings panel for the SFTP Sender.
 *
 * <p>The connection half is {@link SftpConnectionPanel}, the same component the listener
 * uses in pull mode, so the two directions are configured identically.
 */
public class SftpSenderPanel extends ConnectorSettingsPanel {

    private final SftpConnectionPanel connectionPanel;
    private final JTextField remoteDirectoryField;
    private final JTextField outputPatternField;
    private final JCheckBox createDirectoriesBox;
    private final JCheckBox outputAppendBox;
    private final JCheckBox errorOnExistsBox;
    private final JCheckBox temporaryBox;
    private final JTextField temporarySuffixField;
    private final JTextField filePermissionsField;
    private final JCheckBox keepConnectionOpenBox;
    private final JCheckBox binaryBox;
    private final JComboBox<String> charsetBox;
    private final JTextArea templateArea;
    private final JButton testConnectionButton;

    public SftpSenderPanel() {
        setLayout(new GridBagLayout());
        setBackground(UIConstants.BACKGROUND_COLOR);

        connectionPanel = new SftpConnectionPanel();

        remoteDirectoryField = PanelSupport.textField(24,
                "The directory to write into. Blank for the login directory.");
        outputPatternField = PanelSupport.textField(24,
                "<html>The filename, as a template: <b>${message.messageId}.hl7</b>.</html>");
        createDirectoriesBox = PanelSupport.checkBox("Create the directory if it does not exist",
                "Creates the remote directory, and any missing parents, before writing.");
        outputAppendBox = PanelSupport.checkBox("Append to an existing file",
                "Append rather than replace when the file already exists.");
        errorOnExistsBox = PanelSupport.checkBox("Fail if the file already exists",
                "Fail the message instead of overwriting a file that is already there.");
        temporaryBox = PanelSupport.checkBox("Write to a temporary name and rename",
                "<html>The rename is atomic on the far side, so a partner polling the directory<br>"
                        + "can never pick up a file this connector is still writing.<br>"
                        + "Ignored when appending, which cannot use it.</html>");
        temporarySuffixField = PanelSupport.textField(10,
                "The suffix used while writing, before the rename.");
        filePermissionsField = PanelSupport.textField(8,
                "<html>Octal permissions to set on the written file, such as <b>640</b>.<br>"
                        + "Blank leaves whatever the server's umask produced.</html>");
        keepConnectionOpenBox = PanelSupport.checkBox("Keep the connection open between messages",
                "<html>An SSH handshake costs more than sending a small message, so reusing the session<br>"
                        + "matters for throughput. Turn it off for a partner that drops idle sessions badly.</html>");
        binaryBox = PanelSupport.checkBox("Contents are base64 encoded binary",
                "Decode the template's value as base64 and write the bytes, rather than encoded text.");
        charsetBox = new JComboBox<String>();
        charsetBox.addActionListener(e -> PanelSupport.markDirty());
        if (PlatformUI.MIRTH_FRAME != null) {
            PlatformUI.MIRTH_FRAME.setupCharsetEncodingForConnector(charsetBox);
        }
        templateArea = PanelSupport.textArea(10, 44, "The file's contents, as a template.");

        temporaryBox.addActionListener(e -> updateEnabledState());
        outputAppendBox.addActionListener(e -> updateEnabledState());

        testConnectionButton = new JButton("Test Connection");
        testConnectionButton.addActionListener(e -> testConnection());

        int row = 0;
        addSection(row++, connectionPanel);
        addSection(row++, buildFilePanel());
        addSection(row, buildTemplatePanel());

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

    private JPanel buildFilePanel() {
        JPanel panel = PanelSupport.grid();
        panel.setBorder(BorderFactory.createTitledBorder("File"));

        int row = 0;
        PanelSupport.addRow(panel, row++, "Directory:", remoteDirectoryField);
        PanelSupport.addRow(panel, row++, "File Name:", outputPatternField);
        PanelSupport.addRow(panel, row++, "Directories:", createDirectoriesBox);
        PanelSupport.addRow(panel, row++, "Existing File:", outputAppendBox);
        PanelSupport.addRow(panel, row++, "", errorOnExistsBox);
        PanelSupport.addRow(panel, row++, "Temporary File:", temporaryBox);
        PanelSupport.addRow(panel, row++, "Temporary Suffix:", temporarySuffixField);
        PanelSupport.addRow(panel, row++, "Permissions:", filePermissionsField);
        PanelSupport.addRow(panel, row++, "Connection:", keepConnectionOpenBox);
        PanelSupport.addRow(panel, row++, "Contents:", binaryBox);
        PanelSupport.addRow(panel, row++, "Encoding:", charsetBox);
        PanelSupport.addRow(panel, row, "", testConnectionButton);
        return panel;
    }

    private JPanel buildTemplatePanel() {
        JPanel panel = PanelSupport.grid();
        panel.setBorder(BorderFactory.createTitledBorder("Template"));
        PanelSupport.addWideRow(panel, 0, PanelSupport.scroll(templateArea));
        return panel;
    }

    private void updateEnabledState() {
        boolean appending = outputAppendBox.isSelected();
        temporaryBox.setEnabled(!appending);
        temporarySuffixField.setEnabled(!appending && temporaryBox.isSelected());
    }

    private void testConnection() {
        ResponseHandler handler = new ResponseHandler() {
            @Override
            public void handle(Object response) {
                ConnectionTestResponse result = (ConnectionTestResponse) response;
                if (result == null) {
                    PlatformUI.MIRTH_FRAME.alertError(PlatformUI.MIRTH_FRAME,
                            "Failed to invoke the connector service.");
                } else if (result.getType() == ConnectionTestResponse.Type.SUCCESS) {
                    PlatformUI.MIRTH_FRAME.alertInformation(PlatformUI.MIRTH_FRAME, result.getMessage());
                } else {
                    PlatformUI.MIRTH_FRAME.alertWarning(PlatformUI.MIRTH_FRAME, result.getMessage());
                }
            }
        };
        try {
            getServlet(SftpConnectorServletInterface.class, "Testing connection...",
                    "Error testing connection: ", handler)
                    .testConnection(getChannelId(), getChannelName(), getProperties());
        } catch (ClientException e) {
            // The proxy runs the call in a worker and reports failures through the
            // handler; this only fires if the invocation itself could not be made.
            PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
        }
    }

    /** The template field takes dropped variables, so the variable list uses Velocity syntax. */
    @Override
    public TransferMode getTransferMode() {
        return TransferMode.VELOCITY;
    }

    @Override
    public String getConnectorName() {
        return new SftpDispatcherProperties().getName();
    }

    @Override
    public ConnectorProperties getProperties() {
        SftpDispatcherProperties properties = new SftpDispatcherProperties();
        properties.setConnectionProperties(connectionPanel.getProperties());
        properties.setRemoteDirectory(PanelSupport.text(remoteDirectoryField));
        properties.setOutputPattern(PanelSupport.text(outputPatternField));
        properties.setCreateDirectories(createDirectoriesBox.isSelected());
        properties.setOutputAppend(outputAppendBox.isSelected());
        properties.setErrorOnExists(errorOnExistsBox.isSelected());
        properties.setTemporary(temporaryBox.isSelected());
        properties.setTemporarySuffix(PanelSupport.text(temporarySuffixField));
        properties.setFilePermissions(PanelSupport.text(filePermissionsField));
        properties.setKeepConnectionOpen(keepConnectionOpenBox.isSelected());
        properties.setBinary(binaryBox.isSelected());
        properties.setCharsetEncoding(PlatformUI.MIRTH_FRAME == null ? "DEFAULT_ENCODING"
                : PlatformUI.MIRTH_FRAME.getSelectedEncodingForConnector(charsetBox));
        properties.setTemplate(PanelSupport.text(templateArea));
        return properties;
    }

    @Override
    public void setProperties(ConnectorProperties props) {
        SftpDispatcherProperties properties = (SftpDispatcherProperties) props;
        connectionPanel.setProperties(properties.getConnectionProperties());
        remoteDirectoryField.setText(properties.getRemoteDirectory());
        outputPatternField.setText(properties.getOutputPattern());
        createDirectoriesBox.setSelected(properties.isCreateDirectories());
        outputAppendBox.setSelected(properties.isOutputAppend());
        errorOnExistsBox.setSelected(properties.isErrorOnExists());
        temporaryBox.setSelected(properties.isTemporary());
        temporarySuffixField.setText(properties.getTemporarySuffix());
        filePermissionsField.setText(properties.getFilePermissions());
        keepConnectionOpenBox.setSelected(properties.isKeepConnectionOpen());
        binaryBox.setSelected(properties.isBinary());
        if (PlatformUI.MIRTH_FRAME != null) {
            PlatformUI.MIRTH_FRAME.setPreviousSelectedEncodingForConnector(charsetBox,
                    properties.getCharsetEncoding());
        }
        templateArea.setText(properties.getTemplate());
        updateEnabledState();
    }

    @Override
    public ConnectorProperties getDefaults() {
        return new SftpDispatcherProperties();
    }

    @Override
    public boolean checkProperties(ConnectorProperties props, boolean highlight) {
        SftpDispatcherProperties properties = (SftpDispatcherProperties) props;
        boolean valid = connectionPanel.checkProperties(properties.getConnectionProperties(), highlight);

        if (StringUtils.isBlank(properties.getOutputPattern())) {
            valid = false;
            if (highlight) {
                PanelSupport.setValid(outputPatternField, false);
            }
        }
        if (StringUtils.isNotBlank(properties.getFilePermissions())
                && !properties.getFilePermissions().matches("[0-7]{3,4}")) {
            valid = false;
            if (highlight) {
                PanelSupport.setValid(filePermissionsField, false);
            }
        }
        return valid;
    }

    @Override
    public void resetInvalidProperties() {
        PanelSupport.setValid(outputPatternField, true);
        PanelSupport.setValid(filePermissionsField, true);
        connectionPanel.resetInvalidProperties();
    }
}
