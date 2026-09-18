/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.client;

import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.ui.PlatformUI;
import com.mirth.connect.client.ui.UIConstants;
import com.mirth.connect.client.ui.panels.connectors.ConnectorSettingsPanel;
import com.mirth.connect.client.ui.panels.connectors.ResponseHandler;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.util.ConnectionTestResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.openintegrationengine.connectors.sftp.FileAction;
import org.openintegrationengine.connectors.sftp.SftpConnectorServletInterface;
import org.openintegrationengine.connectors.sftp.SftpReceiverProperties;
import org.openintegrationengine.connectors.sftp.SftpServerUser;
import org.openintegrationengine.connectors.sftp.SourceMode;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Administrator's settings panel for the SFTP Listener.
 *
 * <p>One panel for both directions: the Mode selector swaps which half is enabled, since
 * the server settings and the remote-server settings are mutually exclusive but everything
 * below them -- the filter, the encoding, what happens to the file afterwards -- applies
 * either way.
 */
public class SftpListenerPanel extends ConnectorSettingsPanel {

    private final JComboBox<SourceMode> modeBox;

    /* push */
    private final JPanel serverPanel;
    private final JTextField listenerAddressField;
    private final JTextField listenerPortField;
    private final JTextField rootDirectoryField;
    private final JTextField hostKeyFileField;
    private final JComboBox<String> hostKeyAlgorithmBox;
    private final JTextField hostKeySizeField;
    private final JTextField maxSessionsField;
    private final JTextField authTimeoutField;
    private final JTextField idleTimeoutField;
    private final JCheckBox allowDownloadsBox;
    private final JCheckBox dispatchOnRenameBox;
    private final JCheckBox processExistingOnStartBox;
    private final JTable usersTable;
    private final DefaultTableModel usersModel;
    private final JTextArea authorizedKeysArea;
    private final List<String> authorizedKeysByRow = new ArrayList<String>();
    private int selectedUserRow = -1;

    /* pull */
    private final SftpConnectionPanel connectionPanel;
    private final JTextField remoteDirectoryField;
    private final JCheckBox directoryRecursionBox;
    private final JComboBox<String> sortByBox;
    private final JButton testConnectionButton;

    /* both */
    private final JTextField fileFilterField;
    private final JCheckBox regexBox;
    private final JCheckBox ignoreDotBox;
    private final JCheckBox binaryBox;
    private final JComboBox<String> charsetBox;
    private final JComboBox<FileAction> afterProcessingActionBox;
    private final JTextField moveToDirectoryField;
    private final JTextField moveToFileNameField;
    private final JComboBox<FileAction> errorReadingActionBox;
    private final JComboBox<FileAction> errorResponseActionBox;
    private final JTextField errorMoveToDirectoryField;
    private final JTextField errorMoveToFileNameField;

    public SftpListenerPanel() {
        setLayout(new GridBagLayout());
        setBackground(UIConstants.BACKGROUND_COLOR);

        modeBox = PanelSupport.comboBox(SourceMode.values(),
                "<html><b>PUSH</b>: the engine runs an SFTP server and partners upload into it.<br>"
                        + "<b>PULL</b>: the engine polls a remote SFTP server on the schedule above.</html>");
        modeBox.addActionListener(e -> updateEnabledState());

        listenerAddressField = PanelSupport.textField(16,
                "The local address to bind the SFTP server to. 0.0.0.0 for every interface.");
        listenerPortField = PanelSupport.textField(6,
                "<html>The port to listen on. The engine runs unprivileged, so 22 usually needs<br>"
                        + "a published port on the container rather than a change here.</html>");
        rootDirectoryField = PanelSupport.textField(24,
                "<html>Where uploads land. A relative path is taken from the engine's application data directory.<br>"
                        + "Clients cannot see or reach anything above it.</html>");
        hostKeyFileField = PanelSupport.textField(24,
                "<html>The server's own host key, generated on first start and reused after that.<br>"
                        + "A key that changed on every restart would make every client refuse to connect.</html>");
        hostKeyAlgorithmBox = PanelSupport.comboBox(new String[] {"EC", "RSA"},
                "The key type to generate if the host key file does not exist yet.");
        hostKeySizeField = PanelSupport.textField(6,
                "Key size: 256, 384 or 521 for EC; 2048 or more for RSA.");
        maxSessionsField = PanelSupport.textField(6,
                "Concurrent SSH sessions allowed. Further connections are refused, not queued.");
        authTimeoutField = PanelSupport.textField(8,
                "Milliseconds a client has to finish authenticating.");
        idleTimeoutField = PanelSupport.textField(8,
                "Milliseconds an authenticated but silent session is held open.");
        allowDownloadsBox = PanelSupport.checkBox("Allow clients to list and download",
                "<html>Off by default: a drop box that also hands files back is an exposure nobody asked for.</html>");
        dispatchOnRenameBox = PanelSupport.checkBox("Treat a rename into place as a completed upload",
                "<html>Clients that write to a temporary name and rename when finished are handled properly:<br>"
                        + "exclude the temporary name in the filter and the message is dispatched at the rename.</html>");
        processExistingOnStartBox = PanelSupport.checkBox("Process files already in the directory on start",
                "<html>Picks up an upload that arrived while the channel was stopped.<br>"
                        + "Only safe with an after-processing action of Delete or Move -- with None it re-sends everything on every deploy.</html>");

        usersModel = new DefaultTableModel(new Object[] {"Username", "Password", "Home Directory", "Read Only"}, 0) {
            @Override
            public Class<?> getColumnClass(int column) {
                return column == 3 ? Boolean.class : String.class;
            }
        };
        usersModel.addTableModelListener(e -> PanelSupport.markDirty());
        usersTable = new JTable(usersModel);
        usersTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        usersTable.getSelectionModel().addListSelectionListener(e -> syncAuthorizedKeys());
        usersTable.setToolTipText("<html>The accounts the SFTP server accepts. A user with no password refuses"
                + " password authentication;<br>a user with no authorized key refuses public key authentication.</html>");

        authorizedKeysArea = PanelSupport.textArea(4, 40,
                "<html>Authorized keys for the selected user, one per line, exactly as they would appear<br>"
                        + "in ~/.ssh/authorized_keys.</html>");
        authorizedKeysArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                captureAuthorizedKeys();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                captureAuthorizedKeys();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                captureAuthorizedKeys();
            }
        });

        connectionPanel = new SftpConnectionPanel();
        remoteDirectoryField = PanelSupport.textField(24,
                "The directory to poll on the remote server. Blank for the login directory.");
        directoryRecursionBox = PanelSupport.checkBox("Include subdirectories",
                "Descend into subdirectories of the polled directory.");
        sortByBox = PanelSupport.comboBox(new String[] {SftpReceiverProperties.SORT_BY_DATE,
                SftpReceiverProperties.SORT_BY_NAME, SftpReceiverProperties.SORT_BY_SIZE},
                "The order files are processed in within one poll.");
        testConnectionButton = new JButton("Test Connection");
        testConnectionButton.addActionListener(e -> testConnection());

        fileFilterField = PanelSupport.textField(20,
                "<html>Wildcards, space or comma separated: <b>*.hl7 *.txt</b>.<br>"
                        + "A leading <b>!</b> negates the list, so <b>!*.tmp</b> takes everything except part files.</html>");
        regexBox = PanelSupport.checkBox("Filter is a regular expression",
                "Treat the filter as one regular expression matched against the whole filename.");
        ignoreDotBox = PanelSupport.checkBox("Ignore dot files",
                "Skip names beginning with a dot, which are conventionally partial or hidden.");
        binaryBox = PanelSupport.checkBox("Process as binary",
                "<html>Read the file as bytes and base64 encode it into the message,<br>"
                        + "rather than decoding it as text. Batch processing is not available for binary data.</html>");
        charsetBox = new JComboBox<String>();
        charsetBox.addActionListener(e -> PanelSupport.markDirty());
        if (PlatformUI.MIRTH_FRAME != null) {
            PlatformUI.MIRTH_FRAME.setupCharsetEncodingForConnector(charsetBox);
        }

        afterProcessingActionBox = PanelSupport.comboBox(FileAction.values(),
                "What happens to a file the channel accepted.");
        afterProcessingActionBox.addActionListener(e -> updateEnabledState());
        moveToDirectoryField = PanelSupport.textField(20, "Where to move it. Blank leaves it where it is.");
        moveToFileNameField = PanelSupport.textField(20, "What to rename it to. Blank keeps the name.");
        errorReadingActionBox = PanelSupport.comboBox(FileAction.values(),
                "What happens to a file that could not be read or dispatched at all.");
        errorReadingActionBox.addActionListener(e -> updateEnabledState());
        errorResponseActionBox = PanelSupport.comboBox(FileAction.values(),
                "What happens to a file the channel read but answered with an error.");
        errorResponseActionBox.addActionListener(e -> updateEnabledState());
        errorMoveToDirectoryField = PanelSupport.textField(20, "Where to move a file that errored.");
        errorMoveToFileNameField = PanelSupport.textField(20, "What to rename a file that errored to.");

        serverPanel = buildServerPanel();

        int row = 0;
        JPanel modePanel = PanelSupport.grid();
        PanelSupport.addRow(modePanel, 0, "Mode:", modeBox);
        addSection(row++, modePanel);
        addSection(row++, serverPanel);
        addSection(row++, buildPullPanel());
        addSection(row++, buildFilePanel());

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

    private JPanel buildServerPanel() {
        JPanel panel = PanelSupport.grid();
        panel.setBorder(BorderFactory.createTitledBorder("SFTP Server (push)"));

        int row = 0;
        PanelSupport.addRow(panel, row++, "Listener Address:", listenerAddressField);
        PanelSupport.addRow(panel, row++, "Listener Port:", listenerPortField);
        PanelSupport.addRow(panel, row++, "Root Directory:", rootDirectoryField);
        PanelSupport.addRow(panel, row++, "Host Key File:", hostKeyFileField);
        PanelSupport.addRow(panel, row++, "Host Key Type:", hostKeyAlgorithmBox);
        PanelSupport.addRow(panel, row++, "Host Key Size:", hostKeySizeField);
        PanelSupport.addRow(panel, row++, "Max Sessions:", maxSessionsField);
        PanelSupport.addRow(panel, row++, "Auth Timeout (ms):", authTimeoutField);
        PanelSupport.addRow(panel, row++, "Idle Timeout (ms):", idleTimeoutField);
        PanelSupport.addRow(panel, row++, "Downloads:", allowDownloadsBox);
        PanelSupport.addRow(panel, row++, "Renames:", dispatchOnRenameBox);
        PanelSupport.addRow(panel, row++, "On Start:", processExistingOnStartBox);

        JScrollPane usersScroll = new JScrollPane(usersTable);
        usersScroll.setPreferredSize(new Dimension(520, 110));

        JPanel usersButtons = new JPanel();
        usersButtons.setBackground(UIConstants.BACKGROUND_COLOR);
        JButton addUser = new JButton("New");
        addUser.addActionListener(e -> {
            usersModel.addRow(new Object[] {"user" + (usersModel.getRowCount() + 1), "", "", Boolean.FALSE});
            authorizedKeysByRow.add("");
            usersTable.setRowSelectionInterval(usersModel.getRowCount() - 1, usersModel.getRowCount() - 1);
            PanelSupport.markDirty();
        });
        JButton removeUser = new JButton("Delete");
        removeUser.addActionListener(e -> {
            int selected = usersTable.getSelectedRow();
            if (selected >= 0) {
                // Stop the editor first, or the removed row's editor writes into its
                // replacement when it loses focus.
                if (usersTable.isEditing()) {
                    usersTable.getCellEditor().stopCellEditing();
                }
                selectedUserRow = -1;
                usersModel.removeRow(selected);
                authorizedKeysByRow.remove(selected);
                authorizedKeysArea.setText("");
                PanelSupport.markDirty();
            }
        });
        usersButtons.add(addUser);
        usersButtons.add(removeUser);

        JPanel usersPanel = new JPanel(new BorderLayout());
        usersPanel.setBackground(UIConstants.BACKGROUND_COLOR);
        usersPanel.add(usersScroll, BorderLayout.CENTER);
        usersPanel.add(usersButtons, BorderLayout.SOUTH);

        PanelSupport.addRow(panel, row++, "Users:", usersPanel);
        PanelSupport.addRow(panel, row, "Authorized Keys:", PanelSupport.scroll(authorizedKeysArea));
        return panel;
    }

    private JPanel buildPullPanel() {
        JPanel panel = PanelSupport.grid();
        panel.setBorder(BorderFactory.createTitledBorder("Remote Server (pull)"));

        int row = 0;
        PanelSupport.addWideRow(panel, row++, connectionPanel);
        PanelSupport.addRow(panel, row++, "Directory:", remoteDirectoryField);
        PanelSupport.addRow(panel, row++, "Subdirectories:", directoryRecursionBox);
        PanelSupport.addRow(panel, row++, "Sort Files By:", sortByBox);
        PanelSupport.addRow(panel, row, "", testConnectionButton);
        return panel;
    }

    private JPanel buildFilePanel() {
        JPanel panel = PanelSupport.grid();
        panel.setBorder(BorderFactory.createTitledBorder("Files"));

        int row = 0;
        PanelSupport.addRow(panel, row++, "File Filter:", fileFilterField);
        PanelSupport.addRow(panel, row++, "", regexBox);
        PanelSupport.addRow(panel, row++, "", ignoreDotBox);
        PanelSupport.addRow(panel, row++, "", binaryBox);
        PanelSupport.addRow(panel, row++, "Encoding:", charsetBox);
        PanelSupport.addRow(panel, row++, "After Processing:", afterProcessingActionBox);
        PanelSupport.addRow(panel, row++, "Move To Directory:", moveToDirectoryField);
        PanelSupport.addRow(panel, row++, "Move To File Name:", moveToFileNameField);
        PanelSupport.addRow(panel, row++, "Error Reading:", errorReadingActionBox);
        PanelSupport.addRow(panel, row++, "Error In Response:", errorResponseActionBox);
        PanelSupport.addRow(panel, row++, "Error Move To Directory:", errorMoveToDirectoryField);
        PanelSupport.addRow(panel, row, "Error Move To File Name:", errorMoveToFileNameField);
        return panel;
    }

    /** Keeps the keys text area pointed at the selected account. */
    private void syncAuthorizedKeys() {
        int selected = usersTable.getSelectedRow();
        if (selected == selectedUserRow) {
            return;
        }
        selectedUserRow = selected;
        authorizedKeysArea.setText(selected >= 0 && selected < authorizedKeysByRow.size()
                ? authorizedKeysByRow.get(selected) : "");
        authorizedKeysArea.setEnabled(selected >= 0);
    }

    private void captureAuthorizedKeys() {
        if (selectedUserRow >= 0 && selectedUserRow < authorizedKeysByRow.size()) {
            authorizedKeysByRow.set(selectedUserRow, PanelSupport.text(authorizedKeysArea));
        }
    }

    private void updateEnabledState() {
        boolean push = modeBox.getSelectedItem() == SourceMode.PUSH;
        setPanelEnabled(serverPanel, push);
        setPanelEnabled(connectionPanel, !push);
        remoteDirectoryField.setEnabled(!push);
        directoryRecursionBox.setEnabled(!push);
        sortByBox.setEnabled(!push);
        testConnectionButton.setEnabled(!push);

        boolean move = afterProcessingActionBox.getSelectedItem() == FileAction.MOVE;
        moveToDirectoryField.setEnabled(move);
        moveToFileNameField.setEnabled(move);

        boolean errorMove = errorReadingActionBox.getSelectedItem() == FileAction.MOVE
                || errorResponseActionBox.getSelectedItem() == FileAction.MOVE;
        errorMoveToDirectoryField.setEnabled(errorMove);
        errorMoveToFileNameField.setEnabled(errorMove);
    }

    private static void setPanelEnabled(java.awt.Container container, boolean enabled) {
        container.setEnabled(enabled);
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof java.awt.Container) {
                setPanelEnabled((java.awt.Container) component, enabled);
            } else {
                component.setEnabled(enabled);
            }
        }
    }

    private void testConnection() {
        final SftpReceiverProperties properties = (SftpReceiverProperties) getProperties();
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
                    .testConnection(getChannelId(), getChannelName(), properties);
        } catch (ClientException e) {
            // The proxy runs the call in a worker and reports failures through the
            // handler; this only fires if the invocation itself could not be made.
            PlatformUI.MIRTH_FRAME.alertThrowable(PlatformUI.MIRTH_FRAME, e);
        }
    }

    @Override
    public String getConnectorName() {
        return new SftpReceiverProperties().getName();
    }

    @Override
    public ConnectorProperties getProperties() {
        SftpReceiverProperties properties = new SftpReceiverProperties();

        properties.setMode((SourceMode) modeBox.getSelectedItem());

        properties.getListenerConnectorProperties().setHost(PanelSupport.text(listenerAddressField));
        properties.getListenerConnectorProperties().setPort(PanelSupport.text(listenerPortField));
        properties.setRootDirectory(PanelSupport.text(rootDirectoryField));
        properties.setHostKeyFile(PanelSupport.text(hostKeyFileField));
        properties.setHostKeyAlgorithm((String) hostKeyAlgorithmBox.getSelectedItem());
        properties.setHostKeySize(PanelSupport.text(hostKeySizeField));
        properties.setMaxSessions(PanelSupport.text(maxSessionsField));
        properties.setAuthTimeout(PanelSupport.text(authTimeoutField));
        properties.setIdleTimeout(PanelSupport.text(idleTimeoutField));
        properties.setAllowDownloads(allowDownloadsBox.isSelected());
        properties.setDispatchOnRename(dispatchOnRenameBox.isSelected());
        properties.setProcessExistingOnStart(processExistingOnStartBox.isSelected());
        properties.setUsers(getUsers());

        properties.setConnectionProperties(connectionPanel.getProperties());
        properties.setRemoteDirectory(PanelSupport.text(remoteDirectoryField));
        properties.setDirectoryRecursion(directoryRecursionBox.isSelected());
        properties.setSortBy((String) sortByBox.getSelectedItem());

        properties.setFileFilter(PanelSupport.text(fileFilterField));
        properties.setRegex(regexBox.isSelected());
        properties.setIgnoreDot(ignoreDotBox.isSelected());
        properties.setBinary(binaryBox.isSelected());
        properties.setCharsetEncoding(PlatformUI.MIRTH_FRAME == null ? "DEFAULT_ENCODING"
                : PlatformUI.MIRTH_FRAME.getSelectedEncodingForConnector(charsetBox));
        properties.setAfterProcessingAction((FileAction) afterProcessingActionBox.getSelectedItem());
        properties.setMoveToDirectory(PanelSupport.text(moveToDirectoryField));
        properties.setMoveToFileName(PanelSupport.text(moveToFileNameField));
        properties.setErrorReadingAction((FileAction) errorReadingActionBox.getSelectedItem());
        properties.setErrorResponseAction((FileAction) errorResponseActionBox.getSelectedItem());
        properties.setErrorMoveToDirectory(PanelSupport.text(errorMoveToDirectoryField));
        properties.setErrorMoveToFileName(PanelSupport.text(errorMoveToFileNameField));

        return properties;
    }

    private List<SftpServerUser> getUsers() {
        if (usersTable.isEditing()) {
            // Otherwise the cell being edited right now is not in the model yet and the
            // change is silently dropped on save.
            usersTable.getCellEditor().stopCellEditing();
        }
        List<SftpServerUser> users = new ArrayList<SftpServerUser>();
        for (int row = 0; row < usersModel.getRowCount(); row++) {
            SftpServerUser user = new SftpServerUser();
            user.setUsername(StringUtils.defaultString((String) usersModel.getValueAt(row, 0)));
            user.setPassword(StringUtils.defaultString((String) usersModel.getValueAt(row, 1)));
            user.setHomeDirectory(StringUtils.defaultString((String) usersModel.getValueAt(row, 2)));
            user.setReadOnly(Boolean.TRUE.equals(usersModel.getValueAt(row, 3)));
            user.setAuthorizedKeys(row < authorizedKeysByRow.size() ? authorizedKeysByRow.get(row) : "");
            users.add(user);
        }
        return users;
    }

    @Override
    public void setProperties(ConnectorProperties props) {
        SftpReceiverProperties properties = (SftpReceiverProperties) props;

        modeBox.setSelectedItem(properties.getMode() == null ? SourceMode.PUSH : properties.getMode());

        listenerAddressField.setText(properties.getListenerConnectorProperties().getHost());
        listenerPortField.setText(properties.getListenerConnectorProperties().getPort());
        rootDirectoryField.setText(properties.getRootDirectory());
        hostKeyFileField.setText(properties.getHostKeyFile());
        hostKeyAlgorithmBox.setSelectedItem(properties.getHostKeyAlgorithm());
        hostKeySizeField.setText(properties.getHostKeySize());
        maxSessionsField.setText(properties.getMaxSessions());
        authTimeoutField.setText(properties.getAuthTimeout());
        idleTimeoutField.setText(properties.getIdleTimeout());
        allowDownloadsBox.setSelected(properties.isAllowDownloads());
        dispatchOnRenameBox.setSelected(properties.isDispatchOnRename());
        processExistingOnStartBox.setSelected(properties.isProcessExistingOnStart());

        selectedUserRow = -1;
        authorizedKeysByRow.clear();
        usersModel.setRowCount(0);
        if (properties.getUsers() != null) {
            for (SftpServerUser user : properties.getUsers()) {
                usersModel.addRow(new Object[] {user.getUsername(), user.getPassword(),
                        user.getHomeDirectory(), Boolean.valueOf(user.isReadOnly())});
                authorizedKeysByRow.add(StringUtils.defaultString(user.getAuthorizedKeys()));
            }
        }
        authorizedKeysArea.setText("");

        connectionPanel.setProperties(properties.getConnectionProperties());
        remoteDirectoryField.setText(properties.getRemoteDirectory());
        directoryRecursionBox.setSelected(properties.isDirectoryRecursion());
        sortByBox.setSelectedItem(properties.getSortBy());

        fileFilterField.setText(properties.getFileFilter());
        regexBox.setSelected(properties.isRegex());
        ignoreDotBox.setSelected(properties.isIgnoreDot());
        binaryBox.setSelected(properties.isBinary());
        if (PlatformUI.MIRTH_FRAME != null) {
            PlatformUI.MIRTH_FRAME.setPreviousSelectedEncodingForConnector(charsetBox,
                    properties.getCharsetEncoding());
        }
        afterProcessingActionBox.setSelectedItem(properties.getAfterProcessingAction());
        moveToDirectoryField.setText(properties.getMoveToDirectory());
        moveToFileNameField.setText(properties.getMoveToFileName());
        errorReadingActionBox.setSelectedItem(properties.getErrorReadingAction());
        errorResponseActionBox.setSelectedItem(properties.getErrorResponseAction());
        errorMoveToDirectoryField.setText(properties.getErrorMoveToDirectory());
        errorMoveToFileNameField.setText(properties.getErrorMoveToFileName());

        updateEnabledState();
    }

    @Override
    public ConnectorProperties getDefaults() {
        return new SftpReceiverProperties();
    }

    @Override
    public boolean checkProperties(ConnectorProperties props, boolean highlight) {
        SftpReceiverProperties properties = (SftpReceiverProperties) props;
        boolean valid = true;

        if (properties.getMode() == SourceMode.PULL) {
            valid = connectionPanel.checkProperties(properties.getConnectionProperties(), highlight);
        } else {
            if (NumberUtils.toInt(properties.getListenerConnectorProperties().getPort()) <= 0) {
                valid = false;
                if (highlight) {
                    PanelSupport.setValid(listenerPortField, false);
                }
            }
            if (StringUtils.isBlank(properties.getRootDirectory())) {
                valid = false;
                if (highlight) {
                    PanelSupport.setValid(rootDirectoryField, false);
                }
            }
            /*
             * An account with neither a password nor a key cannot be logged into, which is
             * always a mistake and one that otherwise only shows up when a partner tries to
             * connect. The server refuses to deploy on it, so the panel refuses to save it.
             */
            boolean usersValid = !properties.getUsers().isEmpty();
            for (SftpServerUser user : properties.getUsers()) {
                if (StringUtils.isBlank(user.getUsername())
                        || (StringUtils.isEmpty(user.getPassword())
                                && StringUtils.isBlank(user.getAuthorizedKeys()))) {
                    usersValid = false;
                }
            }
            if (!usersValid) {
                valid = false;
                if (highlight) {
                    PanelSupport.setValid(usersTable, false);
                }
            }
        }

        if (StringUtils.isBlank(properties.getFileFilter())) {
            valid = false;
            if (highlight) {
                PanelSupport.setValid(fileFilterField, false);
            }
        }
        return valid;
    }

    @Override
    public void resetInvalidProperties() {
        PanelSupport.setValid(listenerPortField, true);
        PanelSupport.setValid(rootDirectoryField, true);
        PanelSupport.setValid(usersTable, true);
        PanelSupport.setValid(fileFilterField, true);
        connectionPanel.resetInvalidProperties();
    }

}
