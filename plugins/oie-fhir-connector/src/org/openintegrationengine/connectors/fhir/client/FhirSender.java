/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir.client;

import com.mirth.connect.client.ui.panels.connectors.ConnectorSettingsPanel;
import com.mirth.connect.donkey.model.channel.ConnectorProperties;

import org.openintegrationengine.connectors.fhir.FhirDispatcherProperties;
import org.openintegrationengine.connectors.fhir.FhirFormat;
import org.openintegrationengine.connectors.fhir.FhirVersion;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/**
 * The desktop Administrator's settings panel for the FHIR Sender.
 *
 * <p>Mirrors the web administrator's panel field for field, so a channel is fully editable
 * from either client.
 */
public class FhirSender extends ConnectorSettingsPanel {

    private static final long serialVersionUID = 1L;

    private final JComboBox<String> fhirVersion = new JComboBox<String>(new String[] {
            FhirVersion.R4.getId(), FhirVersion.R5.getId() });
    private final JTextField serverUrl = FormBuilder.text(35);
    private final JComboBox<String> interaction = new JComboBox<String>(new String[] {
            FhirDispatcherProperties.INTERACTION_CREATE, FhirDispatcherProperties.INTERACTION_UPDATE,
            FhirDispatcherProperties.INTERACTION_PATCH, FhirDispatcherProperties.INTERACTION_READ,
            FhirDispatcherProperties.INTERACTION_VREAD, FhirDispatcherProperties.INTERACTION_SEARCH,
            FhirDispatcherProperties.INTERACTION_DELETE, FhirDispatcherProperties.INTERACTION_HISTORY,
            FhirDispatcherProperties.INTERACTION_TRANSACTION, FhirDispatcherProperties.INTERACTION_OPERATION });
    private final JTextField resourceType = FormBuilder.text(20);
    private final JTextField resourceId = FormBuilder.text(20);
    private final JTextField versionId = FormBuilder.text(10);
    private final JTextField operationName = FormBuilder.text(20);
    private final JTextArea content = FormBuilder.textArea(8);
    private final JComboBox<String> contentFormat = new JComboBox<String>(new String[] {
            FhirFormat.JSON.getCode(), FhirFormat.XML.getCode() });
    private final JComboBox<String> acceptFormat = new JComboBox<String>(new String[] {
            FhirFormat.JSON.getCode(), FhirFormat.XML.getCode() });
    private final JTextField charset = FormBuilder.text(10);
    private final JTextArea parameters = FormBuilder.textArea(4);
    private final JCheckBox useParametersVariable = FormBuilder.checkBox("Use a map variable");
    private final JTextField parametersVariable = FormBuilder.text(20);
    private final JTextArea headers = FormBuilder.textArea(4);
    private final JCheckBox useHeadersVariable = FormBuilder.checkBox("Use a map variable");
    private final JTextField headersVariable = FormBuilder.text(20);
    private final JComboBox<String> authenticationType = new JComboBox<String>(new String[] {
            FhirDispatcherProperties.AUTH_NONE, FhirDispatcherProperties.AUTH_BASIC,
            FhirDispatcherProperties.AUTH_BEARER });
    private final JTextField username = FormBuilder.text(20);
    private final JPasswordField password = new JPasswordField(20);
    private final JTextField bearerToken = FormBuilder.text(30);
    private final JTextField ifMatch = FormBuilder.text(20);
    private final JTextField ifNoneExist = FormBuilder.text(30);
    private final JTextField ifNoneMatch = FormBuilder.text(20);
    private final JComboBox<String> preferReturn = new JComboBox<String>(new String[] {
            FhirDispatcherProperties.PREFER_UNSET, FhirDispatcherProperties.PREFER_MINIMAL,
            FhirDispatcherProperties.PREFER_REPRESENTATION, FhirDispatcherProperties.PREFER_OPERATION_OUTCOME });
    private final JCheckBox useProxyServer = FormBuilder.checkBox("Send through an HTTP proxy");
    private final JTextField proxyAddress = FormBuilder.text(20);
    private final JTextField proxyPort = FormBuilder.text(6);
    private final JTextField socketTimeout = FormBuilder.text(8);

    public FhirSender() {
        FormBuilder form = new FormBuilder(this);

        form.section("FHIR Sender Settings");
        form.field("FHIR Version", fhirVersion,
                "The release to declare in the Content-Type and Accept headers.");
        form.field("Server URL", serverUrl,
                "The FHIR service base URL, e.g. https://server.example.org/fhir. Supports ${} values.");
        form.field("Interaction", interaction, "Which FHIR RESTful interaction to perform.");
        form.field("Resource Type", resourceType,
                "e.g. Patient. Supports ${} values. Not used by a transaction.");
        form.field("Resource Id", resourceId,
                "The logical id for read, update, patch or delete. Leave blank on update, patch or delete "
                        + "to use the conditional form, which selects the target with the parameters below.");
        form.field("Version Id", versionId, "The version to fetch for a vread.");
        form.field("Operation Name", operationName,
                "The operation for the OPERATION interaction, with or without its leading $.");

        form.section("Content");
        form.field("Template", FormBuilder.scroll(content, 140),
                "The request body. Supports ${} values; the default sends the message as encoded.");
        form.field("Content Format", contentFormat, "The serialisation the body is in.");
        form.field("Accept Format", acceptFormat, "The serialisation to ask the server for.");
        form.field("Charset Encoding", charset, "Character set for the request body and response.");

        form.section("Parameters and Headers");
        form.field("Query Parameters", FormBuilder.scroll(parameters, 90),
                "One Name: value per line; repeat a name for a repeated parameter. Supports ${} values.");
        form.field("Parameters Source", useParametersVariable,
                "Read the query parameters from a channel map variable instead of the list above.");
        form.field("Parameters Variable", parametersVariable, "The name of that map variable.");
        form.field("Headers", FormBuilder.scroll(headers, 90),
                "One Name: value per line. These are applied last, so they override the headers this "
                        + "connector would otherwise set.");
        form.field("Headers Source", useHeadersVariable,
                "Read the headers from a channel map variable instead of the list above.");
        form.field("Headers Variable", headersVariable, "The name of that map variable.");

        form.section("Authentication");
        form.field("Type", authenticationType, "Credentials are sent preemptively, without waiting for a challenge.");
        form.field("Username", username, "Basic authentication username. Supports ${} values.");
        form.field("Password", password, "Basic authentication password. Supports ${} values.");
        form.field("Bearer Token", bearerToken,
                "Bearer token, with or without the leading \"Bearer \". Supports ${} values, which is how "
                        + "a channel passes a token it fetched from an OAuth2 endpoint.");

        form.section("Conditional Interactions");
        form.field("If-Match", ifMatch, "Version-aware update or delete, e.g. W/\"2\". Supports ${} values.");
        form.field("If-None-Exist", ifNoneExist,
                "Conditional create: search parameters that must match nothing for the create to proceed.");
        form.field("If-None-Match", ifNoneMatch, "Conditional read.");
        form.field("Prefer Return", preferReturn,
                "What to ask the server to return: nothing, the resource, or an OperationOutcome.");

        form.section("Advanced");
        form.field("Proxy", useProxyServer, null);
        form.field("Proxy Address", proxyAddress, "Proxy host name. Supports ${} values.");
        form.field("Proxy Port", proxyPort, "Proxy port. Supports ${} values.");
        form.field("Socket Timeout (ms)", socketTimeout,
                "Connect and read timeout for the request. Supports ${} values.");
    }

    @Override
    public String getConnectorName() {
        return new FhirDispatcherProperties().getName();
    }

    @Override
    public ConnectorProperties getProperties() {
        FhirDispatcherProperties props = new FhirDispatcherProperties();

        props.setFhirVersion((String) fhirVersion.getSelectedItem());
        props.setServerUrl(serverUrl.getText());
        props.setInteraction((String) interaction.getSelectedItem());
        props.setResourceType(resourceType.getText());
        props.setResourceId(resourceId.getText());
        props.setVersionId(versionId.getText());
        props.setOperationName(operationName.getText());
        props.setContent(content.getText());
        props.setContentFormat((String) contentFormat.getSelectedItem());
        props.setAcceptFormat((String) acceptFormat.getSelectedItem());
        props.setCharset(charset.getText());
        props.setParameters(FormBuilder.parseMap(parameters.getText()));
        props.setUseParametersVariable(useParametersVariable.isSelected());
        props.setParametersVariable(parametersVariable.getText());
        props.setHeaders(FormBuilder.parseMap(headers.getText()));
        props.setUseHeadersVariable(useHeadersVariable.isSelected());
        props.setHeadersVariable(headersVariable.getText());
        props.setAuthenticationType((String) authenticationType.getSelectedItem());
        props.setUsername(username.getText());
        props.setPassword(new String(password.getPassword()));
        props.setBearerToken(bearerToken.getText());
        props.setIfMatch(ifMatch.getText());
        props.setIfNoneExist(ifNoneExist.getText());
        props.setIfNoneMatch(ifNoneMatch.getText());
        props.setPreferReturn((String) preferReturn.getSelectedItem());
        props.setUseProxyServer(useProxyServer.isSelected());
        props.setProxyAddress(proxyAddress.getText());
        props.setProxyPort(proxyPort.getText());
        props.setSocketTimeout(socketTimeout.getText());

        return props;
    }

    @Override
    public void setProperties(ConnectorProperties properties) {
        FhirDispatcherProperties props = (FhirDispatcherProperties) properties;

        fhirVersion.setSelectedItem(props.getFhirVersion());
        serverUrl.setText(props.getServerUrl());
        interaction.setSelectedItem(props.getInteraction());
        resourceType.setText(props.getResourceType());
        resourceId.setText(props.getResourceId());
        versionId.setText(props.getVersionId());
        operationName.setText(props.getOperationName());
        content.setText(props.getContent());
        contentFormat.setSelectedItem(props.getContentFormat());
        acceptFormat.setSelectedItem(props.getAcceptFormat());
        charset.setText(props.getCharset());
        parameters.setText(FormBuilder.renderMap(props.getParameters()));
        useParametersVariable.setSelected(props.isUseParametersVariable());
        parametersVariable.setText(props.getParametersVariable());
        headers.setText(FormBuilder.renderMap(props.getHeaders()));
        useHeadersVariable.setSelected(props.isUseHeadersVariable());
        headersVariable.setText(props.getHeadersVariable());
        authenticationType.setSelectedItem(props.getAuthenticationType());
        username.setText(props.getUsername());
        password.setText(props.getPassword());
        bearerToken.setText(props.getBearerToken());
        ifMatch.setText(props.getIfMatch());
        ifNoneExist.setText(props.getIfNoneExist());
        ifNoneMatch.setText(props.getIfNoneMatch());
        preferReturn.setSelectedItem(props.getPreferReturn());
        useProxyServer.setSelected(props.isUseProxyServer());
        proxyAddress.setText(props.getProxyAddress());
        proxyPort.setText(props.getProxyPort());
        socketTimeout.setText(props.getSocketTimeout());
    }

    @Override
    public ConnectorProperties getDefaults() {
        return new FhirDispatcherProperties();
    }

    @Override
    public boolean checkProperties(ConnectorProperties properties, boolean highlight) {
        FhirDispatcherProperties props = (FhirDispatcherProperties) properties;
        boolean valid = true;

        if (props.getServerUrl() == null || props.getServerUrl().trim().isEmpty()) {
            valid = false;
            if (highlight) {
                FormBuilder.invalid(serverUrl);
            }
        }

        // The type is required for everything except a transaction (which posts to the base)
        // and an operation (which may be system level).
        String interactionValue = props.getInteraction();
        boolean needsType = !FhirDispatcherProperties.INTERACTION_TRANSACTION.equals(interactionValue)
                && !FhirDispatcherProperties.INTERACTION_OPERATION.equals(interactionValue);
        if (needsType && (props.getResourceType() == null || props.getResourceType().trim().isEmpty())) {
            valid = false;
            if (highlight) {
                FormBuilder.invalid(resourceType);
            }
        }

        boolean needsId = FhirDispatcherProperties.INTERACTION_READ.equals(interactionValue)
                || FhirDispatcherProperties.INTERACTION_VREAD.equals(interactionValue);
        if (needsId && (props.getResourceId() == null || props.getResourceId().trim().isEmpty())) {
            valid = false;
            if (highlight) {
                FormBuilder.invalid(resourceId);
            }
        }

        if (FhirDispatcherProperties.INTERACTION_VREAD.equals(interactionValue)
                && (props.getVersionId() == null || props.getVersionId().trim().isEmpty())) {
            valid = false;
            if (highlight) {
                FormBuilder.invalid(versionId);
            }
        }

        if (FhirDispatcherProperties.INTERACTION_OPERATION.equals(interactionValue)
                && (props.getOperationName() == null || props.getOperationName().trim().isEmpty())) {
            valid = false;
            if (highlight) {
                FormBuilder.invalid(operationName);
            }
        }

        return valid;
    }

    @Override
    public void resetInvalidProperties() {
        FormBuilder.valid(serverUrl);
        FormBuilder.valid(resourceType);
        FormBuilder.valid(resourceId);
        FormBuilder.valid(versionId);
        FormBuilder.valid(operationName);
    }
}
