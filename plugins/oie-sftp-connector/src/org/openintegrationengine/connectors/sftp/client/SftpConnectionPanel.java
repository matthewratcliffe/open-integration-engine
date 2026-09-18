/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.sftp.client;

import com.mirth.connect.client.ui.UIConstants;

import org.apache.commons.lang3.StringUtils;
import org.openintegrationengine.connectors.sftp.AuthMethod;
import org.openintegrationengine.connectors.sftp.HostKeyPolicy;
import org.openintegrationengine.connectors.sftp.SftpConnectionProperties;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The client half of the settings -- where the remote server is and how we prove who we
 * are to it -- shared by the SFTP Sender and by the SFTP Listener in pull mode.
 *
 * <p>Fields that do not apply to the current choices are disabled rather than hidden, so
 * the panel does not jump around as the authentication method changes and a value already
 * typed into the other option stays visible.
 */
class SftpConnectionPanel extends JPanel {

    private final JTextField hostField;
    private final JTextField portField;
    private final JTextField usernameField;
    private final JComboBox<AuthMethod> authMethodBox;
    private final JPasswordField passwordField;
    private final JCheckBox privateKeyInlineBox;
    private final JTextField privateKeyFileField;
    private final JTextArea privateKeyArea;
    private final JScrollPane privateKeyScroll;
    private final JPasswordField passphraseField;
    private final JComboBox<HostKeyPolicy> hostKeyPolicyBox;
    private final JTextField knownHostsFileField;
    private final JTextArea hostKeyArea;
    private final JScrollPane hostKeyScroll;
    private final JTextField connectTimeoutField;
    private final JTextField timeoutField;
    private final JTextArea configurationArea;

    SftpConnectionPanel() {
        setLayout(new java.awt.GridBagLayout());
        setBackground(UIConstants.BACKGROUND_COLOR);
        setBorder(BorderFactory.createTitledBorder("Remote SFTP Server"));

        hostField = PanelSupport.textField(24, "The host name or address of the remote SFTP server.");
        portField = PanelSupport.textField(6, "The port to connect to. 22 unless the partner says otherwise.");
        usernameField = PanelSupport.textField(18, "The username to authenticate with.");
        authMethodBox = PanelSupport.comboBox(AuthMethod.values(),
                "<html>Password, a private key, or both (the key is offered first and the password used if it is refused).</html>");
        passwordField = PanelSupport.passwordField(18,
                "<html>The password. A configuration map value -- ${partnerPassword}, by its bare key --<br>"
                        + "keeps it out of the channel XML and out of git.</html>");

        privateKeyInlineBox = PanelSupport.checkBox("Paste the key instead of reading a file",
                "<html>Hold the private key in the channel rather than reading it from a file on the engine.<br>Useful when the engine is a container with nowhere durable to put one.</html>");
        privateKeyFileField = PanelSupport.textField(24,
                "The path, on the engine, of the private key file.");
        privateKeyArea = PanelSupport.textArea(5, 34,
                "The private key itself, in OpenSSH or PEM form.");
        privateKeyScroll = PanelSupport.scroll(privateKeyArea);
        passphraseField = PanelSupport.passwordField(18,
                "The passphrase protecting the private key, if it has one.");

        hostKeyPolicyBox = PanelSupport.comboBox(HostKeyPolicy.values(),
                "<html>How the server's host key is checked.<br>"
                        + "<b>TRUST_ANY</b> accepts whatever is offered: encrypted, but it proves nothing about who answered.<br>"
                        + "<b>KNOWN_HOSTS</b> verifies against a known_hosts file on the engine.<br>"
                        + "<b>PINNED</b> verifies against the key below.</html>");
        knownHostsFileField = PanelSupport.textField(24,
                "The path, on the engine, of the known_hosts file to verify against.");
        hostKeyArea = PanelSupport.textArea(3, 34,
                "<html>The server's public key, as ssh-keyscan prints it:<br>ssh-ed25519 AAAAC3Nz...</html>");
        hostKeyScroll = PanelSupport.scroll(hostKeyArea);

        connectTimeoutField = PanelSupport.textField(8,
                "Milliseconds to wait for the connection and the SSH handshake.");
        timeoutField = PanelSupport.textField(8,
                "Milliseconds a read or write may block once connected. 0 for no limit.");
        configurationArea = PanelSupport.textArea(4, 34,
                "<html>Extra JSch settings, one <b>name=value</b> per line, applied last so they override anything above.<br>"
                        + "For a server that needs an algorithm outside the defaults: kex, server_host_key, cipher.s2c...</html>");

        authMethodBox.addActionListener(e -> updateEnabledState());
        privateKeyInlineBox.addActionListener(e -> updateEnabledState());
        hostKeyPolicyBox.addActionListener(e -> updateEnabledState());

        int row = 0;
        PanelSupport.addRow(this, row++, "Host:", hostField);
        PanelSupport.addRow(this, row++, "Port:", portField);
        PanelSupport.addRow(this, row++, "Username:", usernameField);
        PanelSupport.addRow(this, row++, "Authentication:", authMethodBox);
        PanelSupport.addRow(this, row++, "Password:", passwordField);
        PanelSupport.addRow(this, row++, "Private Key:", privateKeyInlineBox);
        PanelSupport.addRow(this, row++, "Private Key File:", privateKeyFileField);
        PanelSupport.addRow(this, row++, "Private Key:", privateKeyScroll);
        PanelSupport.addRow(this, row++, "Passphrase:", passphraseField);
        PanelSupport.addRow(this, row++, "Host Key:", hostKeyPolicyBox);
        PanelSupport.addRow(this, row++, "known_hosts File:", knownHostsFileField);
        PanelSupport.addRow(this, row++, "Pinned Host Key:", hostKeyScroll);
        PanelSupport.addRow(this, row++, "Connect Timeout (ms):", connectTimeoutField);
        PanelSupport.addRow(this, row++, "Timeout (ms):", timeoutField);
        PanelSupport.addRow(this, row++, "Advanced Settings:", PanelSupport.scroll(configurationArea));
        PanelSupport.addWideRow(this, row, warningLabel());
    }

    private JLabel warningLabel() {
        JLabel label = new JLabel("<html><i>Trust Any accepts any host key: use known_hosts or a pinned"
                + " key for anything crossing a network you do not control.</i></html>");
        label.setForeground(java.awt.Color.GRAY);
        return label;
    }

    private void updateEnabledState() {
        AuthMethod method = (AuthMethod) authMethodBox.getSelectedItem();
        boolean usesPassword = method != null && method.usesPassword();
        boolean usesKey = method != null && method.usesPublicKey();
        boolean inline = privateKeyInlineBox.isSelected();

        passwordField.setEnabled(usesPassword);
        privateKeyInlineBox.setEnabled(usesKey);
        privateKeyFileField.setEnabled(usesKey && !inline);
        privateKeyArea.setEnabled(usesKey && inline);
        privateKeyScroll.setEnabled(usesKey && inline);
        passphraseField.setEnabled(usesKey);

        HostKeyPolicy policy = (HostKeyPolicy) hostKeyPolicyBox.getSelectedItem();
        knownHostsFileField.setEnabled(policy == HostKeyPolicy.KNOWN_HOSTS);
        hostKeyArea.setEnabled(policy == HostKeyPolicy.PINNED);
        hostKeyScroll.setEnabled(policy == HostKeyPolicy.PINNED);
    }

    SftpConnectionProperties getProperties() {
        SftpConnectionProperties props = new SftpConnectionProperties();
        props.setHost(PanelSupport.text(hostField));
        props.setPort(PanelSupport.text(portField));
        props.setUsername(PanelSupport.text(usernameField));
        props.setAuthMethod((AuthMethod) authMethodBox.getSelectedItem());
        props.setPassword(PanelSupport.password(passwordField));
        props.setPrivateKeyInline(privateKeyInlineBox.isSelected());
        props.setPrivateKeyFile(PanelSupport.text(privateKeyFileField));
        props.setPrivateKey(PanelSupport.text(privateKeyArea));
        props.setPassphrase(PanelSupport.password(passphraseField));
        props.setHostKeyPolicy((HostKeyPolicy) hostKeyPolicyBox.getSelectedItem());
        props.setKnownHostsFile(PanelSupport.text(knownHostsFileField));
        props.setHostKey(PanelSupport.text(hostKeyArea));
        props.setConnectTimeout(PanelSupport.text(connectTimeoutField));
        props.setTimeout(PanelSupport.text(timeoutField));
        props.setConfigurationSettings(parseSettings(PanelSupport.text(configurationArea)));
        return props;
    }

    void setProperties(SftpConnectionProperties props) {
        hostField.setText(props.getHost());
        portField.setText(props.getPort());
        usernameField.setText(props.getUsername());
        authMethodBox.setSelectedItem(props.getAuthMethod() == null ? AuthMethod.PASSWORD : props.getAuthMethod());
        passwordField.setText(props.getPassword());
        privateKeyInlineBox.setSelected(props.isPrivateKeyInline());
        privateKeyFileField.setText(props.getPrivateKeyFile());
        privateKeyArea.setText(props.getPrivateKey());
        passphraseField.setText(props.getPassphrase());
        hostKeyPolicyBox.setSelectedItem(props.getHostKeyPolicy() == null
                ? HostKeyPolicy.TRUST_ANY : props.getHostKeyPolicy());
        knownHostsFileField.setText(props.getKnownHostsFile());
        hostKeyArea.setText(props.getHostKey());
        connectTimeoutField.setText(props.getConnectTimeout());
        timeoutField.setText(props.getTimeout());
        configurationArea.setText(formatSettings(props.getConfigurationSettings()));
        updateEnabledState();
    }

    /**
     * @return true if everything needed to open a connection is present
     */
    boolean checkProperties(SftpConnectionProperties props, boolean highlight) {
        boolean valid = true;

        if (StringUtils.isBlank(props.getHost())) {
            valid = false;
            if (highlight) {
                PanelSupport.setValid(hostField, false);
            }
        }
        if (StringUtils.isBlank(props.getUsername())) {
            valid = false;
            if (highlight) {
                PanelSupport.setValid(usernameField, false);
            }
        }
        if (props.getAuthMethod() == AuthMethod.PUBLIC_KEY) {
            if (props.isPrivateKeyInline() && StringUtils.isBlank(props.getPrivateKey())) {
                valid = false;
                if (highlight) {
                    PanelSupport.setValid(privateKeyArea, false);
                }
            } else if (!props.isPrivateKeyInline() && StringUtils.isBlank(props.getPrivateKeyFile())) {
                valid = false;
                if (highlight) {
                    PanelSupport.setValid(privateKeyFileField, false);
                }
            }
        }
        if (props.getHostKeyPolicy() == HostKeyPolicy.KNOWN_HOSTS
                && StringUtils.isBlank(props.getKnownHostsFile())) {
            valid = false;
            if (highlight) {
                PanelSupport.setValid(knownHostsFileField, false);
            }
        }
        if (props.getHostKeyPolicy() == HostKeyPolicy.PINNED && StringUtils.isBlank(props.getHostKey())) {
            valid = false;
            if (highlight) {
                PanelSupport.setValid(hostKeyArea, false);
            }
        }
        return valid;
    }

    void resetInvalidProperties() {
        PanelSupport.setValid(hostField, true);
        PanelSupport.setValid(usernameField, true);
        PanelSupport.setValid(privateKeyFileField, true);
        PanelSupport.setValid(privateKeyArea, true);
        PanelSupport.setValid(knownHostsFileField, true);
        PanelSupport.setValid(hostKeyArea, true);
    }

    /** {@code name=value} per line, which is the form the same settings take in JSch's own config. */
    private static Map<String, String> parseSettings(String text) {
        Map<String, String> settings = new LinkedHashMap<String, String>();
        for (String line : StringUtils.defaultString(text).split("\\r?\\n")) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            int equals = entry.indexOf('=');
            if (equals > 0) {
                settings.put(entry.substring(0, equals).trim(), entry.substring(equals + 1).trim());
            }
        }
        return settings;
    }

    private static String formatSettings(Map<String, String> settings) {
        if (settings == null || settings.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            text.append(entry.getKey()).append('=').append(StringUtils.defaultString(entry.getValue())).append('\n');
        }
        return text.toString();
    }
}
