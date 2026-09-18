/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir.client;

import com.mirth.connect.client.ui.panels.connectors.ConnectorSettingsPanel;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;

import org.openintegrationengine.connectors.fhir.FhirFormat;
import org.openintegrationengine.connectors.fhir.FhirInteraction;
import org.openintegrationengine.connectors.fhir.FhirReceiverProperties;
import org.openintegrationengine.connectors.fhir.FhirVersion;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * The desktop Administrator's settings panel for the FHIR Listener.
 *
 * <p>Every property the web administrator's panel exposes is here too, so a channel edited
 * in one client is fully editable in the other. Where the web panel has a key/value grid
 * for response headers, this has a {@code Name: value} per line -- see {@link FormBuilder}.
 */
public class FhirListener extends ConnectorSettingsPanel {

    private static final long serialVersionUID = 1L;

    private final JTextField host = FormBuilder.text(20);
    private final JTextField port = FormBuilder.text(6);
    private final JCheckBox useSsl =
            FormBuilder.checkBox("Serve HTTPS on this port (TLS terminated by this connector)");
    private final JTextField keyStoreFile = FormBuilder.text(30);
    private final JPasswordField keyStorePassword = FormBuilder.password(20);
    private final JComboBox<String> keyStoreType = new JComboBox<String>(new String[] {"PKCS12", "JKS"});
    private final JTextField keyAlias = FormBuilder.text(20);
    private final JPasswordField keyPassword = FormBuilder.password(20);
    private final JComboBox<String> clientAuth = new JComboBox<String>(new String[] {
            FhirReceiverProperties.CLIENT_AUTH_NONE, FhirReceiverProperties.CLIENT_AUTH_WANT,
            FhirReceiverProperties.CLIENT_AUTH_NEED });
    private final JTextField trustStoreFile = FormBuilder.text(30);
    private final JPasswordField trustStorePassword = FormBuilder.password(20);
    private final JComboBox<String> trustStoreType = new JComboBox<String>(new String[] {"PKCS12", "JKS"});
    private final JTextField protocols = FormBuilder.text(24);
    private final JTextField cipherSuites = FormBuilder.text(40);
    private final JComboBox<String> fhirVersion = new JComboBox<String>(new String[] {
            FhirVersion.R4.getId(), FhirVersion.R5.getId() });
    private final JTextField basePath = FormBuilder.text(20);
    private final JComboBox<String> defaultFormat = new JComboBox<String>(new String[] {
            FhirFormat.JSON.getCode(), FhirFormat.XML.getCode() });
    private final JCheckBox strictVersionNegotiation =
            FormBuilder.checkBox("Refuse a request that asks for another FHIR release");
    private final JComboBox<String> messageContent = new JComboBox<String>(new String[] {
            FhirReceiverProperties.CONTENT_BODY, FhirReceiverProperties.CONTENT_ENVELOPE });
    private final JTextField enabledInteractions = FormBuilder.text(40);
    private final JTextField resourceTypes = FormBuilder.text(40);
    private final JComboBox<String> capabilityMode = new JComboBox<String>(new String[] {
            FhirReceiverProperties.CAPABILITY_AUTO, FhirReceiverProperties.CAPABILITY_CUSTOM,
            FhirReceiverProperties.CAPABILITY_CHANNEL, FhirReceiverProperties.CAPABILITY_DISABLED });
    private final JTextArea capabilityStatement = FormBuilder.textArea(8);
    private final JTextField baseUrlOverride = FormBuilder.text(30);
    private final JTextField timeout = FormBuilder.text(8);
    private final JTextField maxRequestSize = FormBuilder.text(10);
    private final JTextField charset = FormBuilder.text(10);
    private final JTextField responseStatusCode = FormBuilder.text(8);
    private final JCheckBox wrapNonFhirResponses =
            FormBuilder.checkBox("Wrap a non-FHIR response in an OperationOutcome");
    private final JTextArea responseHeaders = FormBuilder.textArea(4);
    private final JCheckBox useResponseHeadersVariable = FormBuilder.checkBox("Use a map variable");
    private final JTextField responseHeadersVariable = FormBuilder.text(20);

    public FhirListener() {
        FormBuilder form = new FormBuilder(this);

        form.section("Listener Settings");
        form.field("Local Address", host, "The address to bind to. 0.0.0.0 accepts on every interface.");
        form.field("Local Port", port, "The TCP port to listen on.");

        form.section("TLS");
        form.field("Serve TLS", useSsl,
                "Off means plain HTTP on this port, with TLS terminated by something in front"
                        + " (this stack's nginx overlay, or an ingress). On means this connector"
                        + " presents the certificate itself.");
        form.field("Key Store File", keyStoreFile,
                "The store holding the server certificate and its private key. A relative path is"
                        + " taken from the engine's application data directory.");
        form.field("Key Store Password", keyStorePassword,
                "Channel XML is not an encrypted store: use ${configurationMapKey} or a Key Store"
                        + " plugin secret and keep this out of git.");
        form.field("Key Store Type", keyStoreType, "PKCS12 is the JDK default and what openssl produces.");
        form.field("Key Alias", keyAlias,
                "Which certificate to present. Required when the store holds more than one private key.");
        form.field("Key Password", keyPassword, "The private key's own password, if it differs from the store's.");
        form.field("Client Certificates", clientAuth,
                "NEED refuses a client without a valid certificate; WANT accepts either and leaves the"
                        + " channel to decide; NONE asks for nothing.");
        form.field("Trust Store File", trustStoreFile,
                "The certificates client certificates are verified against. Required for WANT or NEED:"
                        + " without it the JDK would accept any certificate from a public CA.");
        form.field("Trust Store Password", trustStorePassword, "Password for the trust store.");
        form.field("Trust Store Type", trustStoreType, "PKCS12 or JKS.");
        form.field("Protocols", protocols,
                "Comma separated, such as TLSv1.2,TLSv1.3. Blank leaves the JDK defaults.");
        form.field("Cipher Suites", cipherSuites,
                "Comma separated. Blank uses Jetty's defaults, which already exclude the broken ones.");

        form.section("FHIR Listener Settings");
        form.field("FHIR Version", fhirVersion,
                "The release this endpoint serves. Sets the fhirVersion media type parameter and the "
                        + "CapabilityStatement's version.");
        form.field("Base Path", basePath,
                "The path the FHIR service base sits at, e.g. /fhir. Requests outside it get a 404.");
        form.field("Default Format", defaultFormat,
                "What to return when the client expresses no preference through Accept or _format.");
        form.field("Version Negotiation", strictVersionNegotiation,
                "When set, a request whose Accept or Content-Type names a different FHIR release is "
                        + "answered with 406 rather than served anyway.");
        form.field("Message Content", messageContent,
                "BODY hands the channel the request body and puts the parsed request in the source map. "
                        + "ENVELOPE hands it one JSON object containing both.");
        form.field("Enabled Interactions", enabledInteractions,
                "Comma-separated interaction codes to serve; anything else gets a 405. Blank means all. "
                        + "Codes: " + FhirInteraction.defaultEnabledCodes());
        form.field("Resource Types", resourceTypes,
                "Comma-separated resource types to serve; anything else gets a 404. Blank means any type.");

        form.section("CapabilityStatement");
        form.field("Served From", capabilityMode,
                "AUTO generates one from these settings. CUSTOM serves the text below. CHANNEL passes "
                        + "GET [base]/metadata to the channel like any other request. DISABLED returns 404.");
        form.field("Custom Statement", FormBuilder.scroll(capabilityStatement, 140),
                "Used when Served From is CUSTOM. Supports ${} template values.");
        form.field("Base URL Override", baseUrlOverride,
                "The service base URL to advertise in Location headers and the CapabilityStatement. "
                        + "Blank derives it from the request and any X-Forwarded-* headers.");

        form.section("Response");
        form.field("Response Status Code", responseStatusCode,
                "Overrides the status code this connector would otherwise choose. Supports ${} values "
                        + "resolved against the processed message. Blank uses the FHIR default for the interaction.");
        form.field("Non-FHIR Responses", wrapNonFhirResponses,
                "When set, anything a destination returns that is not a FHIR resource is wrapped in an "
                        + "OperationOutcome so the client always receives valid FHIR.");
        form.field("Response Headers", FormBuilder.scroll(responseHeaders, 90),
                "One Name: value per line. Supports ${} values.");
        form.field("Headers Source", useResponseHeadersVariable,
                "Read the response headers from a channel map variable instead of the list above.");
        form.field("Map Variable", responseHeadersVariable,
                "The name of a map variable holding the response headers.");

        form.section("Advanced");
        form.field("Receive Timeout (ms)", timeout, "Idle timeout for a connection. 0 disables it.");
        form.field("Max Request Size", maxRequestSize,
                "Largest accepted request body in bytes; a larger one gets a 413. 0 means no limit.");
        form.field("Charset Encoding", charset, "Character set used to read requests and write responses.");
    }

    @Override
    public String getConnectorName() {
        return new FhirReceiverProperties().getName();
    }

    @Override
    public ConnectorProperties getProperties() {
        FhirReceiverProperties props = new FhirReceiverProperties();

        props.getListenerConnectorProperties().setHost(host.getText());
        props.getListenerConnectorProperties().setPort(port.getText());
        props.setUseSsl(useSsl.isSelected());
        props.setKeyStoreFile(keyStoreFile.getText());
        props.setKeyStorePassword(FormBuilder.read(keyStorePassword));
        props.setKeyStoreType((String) keyStoreType.getSelectedItem());
        props.setKeyAlias(keyAlias.getText());
        props.setKeyPassword(FormBuilder.read(keyPassword));
        props.setClientAuth((String) clientAuth.getSelectedItem());
        props.setTrustStoreFile(trustStoreFile.getText());
        props.setTrustStorePassword(FormBuilder.read(trustStorePassword));
        props.setTrustStoreType((String) trustStoreType.getSelectedItem());
        props.setProtocols(protocols.getText());
        props.setCipherSuites(cipherSuites.getText());
        props.setFhirVersion((String) fhirVersion.getSelectedItem());
        props.setBasePath(basePath.getText());
        props.setDefaultFormat((String) defaultFormat.getSelectedItem());
        props.setStrictVersionNegotiation(strictVersionNegotiation.isSelected());
        props.setMessageContent((String) messageContent.getSelectedItem());
        props.setEnabledInteractions(enabledInteractions.getText());
        props.setResourceTypes(resourceTypes.getText());
        props.setCapabilityMode((String) capabilityMode.getSelectedItem());
        props.setCapabilityStatement(capabilityStatement.getText());
        props.setBaseUrlOverride(baseUrlOverride.getText());
        props.setTimeout(timeout.getText());
        props.setMaxRequestSize(maxRequestSize.getText());
        props.setCharset(charset.getText());
        props.setResponseStatusCode(responseStatusCode.getText());
        props.setWrapNonFhirResponses(wrapNonFhirResponses.isSelected());
        props.setResponseHeaders(FormBuilder.parseMap(responseHeaders.getText()));
        props.setUseResponseHeadersVariable(useResponseHeadersVariable.isSelected());
        props.setResponseHeadersVariable(responseHeadersVariable.getText());

        return props;
    }

    @Override
    public void setProperties(ConnectorProperties properties) {
        FhirReceiverProperties props = (FhirReceiverProperties) properties;

        host.setText(props.getListenerConnectorProperties().getHost());
        port.setText(props.getListenerConnectorProperties().getPort());
        useSsl.setSelected(props.isUseSsl());
        keyStoreFile.setText(props.getKeyStoreFile());
        keyStorePassword.setText(props.getKeyStorePassword());
        keyStoreType.setSelectedItem(props.getKeyStoreType());
        keyAlias.setText(props.getKeyAlias());
        keyPassword.setText(props.getKeyPassword());
        clientAuth.setSelectedItem(props.getClientAuth());
        trustStoreFile.setText(props.getTrustStoreFile());
        trustStorePassword.setText(props.getTrustStorePassword());
        trustStoreType.setSelectedItem(props.getTrustStoreType());
        protocols.setText(props.getProtocols());
        cipherSuites.setText(props.getCipherSuites());
        fhirVersion.setSelectedItem(props.getFhirVersion());
        basePath.setText(props.getBasePath());
        defaultFormat.setSelectedItem(props.getDefaultFormat());
        strictVersionNegotiation.setSelected(props.isStrictVersionNegotiation());
        messageContent.setSelectedItem(props.getMessageContent());
        enabledInteractions.setText(props.getEnabledInteractions());
        resourceTypes.setText(props.getResourceTypes());
        capabilityMode.setSelectedItem(props.getCapabilityMode());
        capabilityStatement.setText(props.getCapabilityStatement());
        baseUrlOverride.setText(props.getBaseUrlOverride());
        timeout.setText(props.getTimeout());
        maxRequestSize.setText(props.getMaxRequestSize());
        charset.setText(props.getCharset());
        responseStatusCode.setText(props.getResponseStatusCode());
        wrapNonFhirResponses.setSelected(props.isWrapNonFhirResponses());
        responseHeaders.setText(FormBuilder.renderMap(props.getResponseHeaders()));
        useResponseHeadersVariable.setSelected(props.isUseResponseHeadersVariable());
        responseHeadersVariable.setText(props.getResponseHeadersVariable());
    }

    @Override
    public ConnectorProperties getDefaults() {
        return new FhirReceiverProperties();
    }

    @Override
    public boolean checkProperties(ConnectorProperties properties, boolean highlight) {
        FhirReceiverProperties props = (FhirReceiverProperties) properties;
        boolean valid = true;

        String portValue = props.getListenerConnectorProperties().getPort();
        // A ${} template is left alone: it is resolved at deploy from the configuration
        // map, so it cannot be checked here and rejecting it would forbid a valid setup.
        if (portValue == null || portValue.trim().isEmpty() || (!portValue.contains("${") && !isPort(portValue))) {
            valid = false;
            if (highlight) {
                FormBuilder.invalid(port);
            }
        }

        if (props.getListenerConnectorProperties().getHost() == null
                || props.getListenerConnectorProperties().getHost().trim().isEmpty()) {
            valid = false;
            if (highlight) {
                FormBuilder.invalid(host);
            }
        }

        /*
         * The same two rules the connector enforces at deploy, checked here so they fail on
         * Save instead of on a channel that will not start: TLS needs a certificate, and
         * client authentication needs something to verify it against.
         */
        if (props.isUseSsl() && isBlank(props.getKeyStoreFile())) {
            valid = false;
            if (highlight) {
                FormBuilder.invalid(keyStoreFile);
            }
        }
        if (props.isUseSsl() && !FhirReceiverProperties.CLIENT_AUTH_NONE.equals(props.getClientAuth())
                && isBlank(props.getTrustStoreFile())) {
            valid = false;
            if (highlight) {
                FormBuilder.invalid(trustStoreFile);
            }
        }

        if (FhirReceiverProperties.CAPABILITY_CUSTOM.equals(props.getCapabilityMode())
                && (props.getCapabilityStatement() == null || props.getCapabilityStatement().trim().isEmpty())) {
            valid = false;
            if (highlight) {
                FormBuilder.invalid(capabilityStatement);
            }
        }

        return valid;
    }

    @Override
    public void resetInvalidProperties() {
        FormBuilder.valid(host);
        FormBuilder.valid(port);
        FormBuilder.valid(capabilityStatement);
        FormBuilder.valid(keyStoreFile);
        FormBuilder.valid(trustStoreFile);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isPort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
