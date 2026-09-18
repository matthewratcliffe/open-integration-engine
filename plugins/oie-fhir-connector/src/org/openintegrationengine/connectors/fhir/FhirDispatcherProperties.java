/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorPropertiesInterface;
import com.mirth.connect.donkey.util.DonkeyElement;

import org.apache.commons.lang3.builder.EqualsBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Settings for the FHIR Sender -- the destination half, which performs one FHIR RESTful
 * interaction against a remote server per message.
 *
 * <p>Every field that can usefully vary per message ({@code serverUrl}, {@code resourceType},
 * {@code resourceId}, the credentials, the query parameters and headers) goes through the
 * engine's template replacer first, so a channel can compute them in a transformer and
 * reference them as {@code ${variable}}.
 */
public class FhirDispatcherProperties extends ConnectorProperties
        implements DestinationConnectorPropertiesInterface {

    /** What to do against the server. Stored as a name so channel XML stays readable. */
    public static final String INTERACTION_CREATE = "CREATE";
    public static final String INTERACTION_UPDATE = "UPDATE";
    public static final String INTERACTION_PATCH = "PATCH";
    public static final String INTERACTION_READ = "READ";
    public static final String INTERACTION_VREAD = "VREAD";
    public static final String INTERACTION_SEARCH = "SEARCH";
    public static final String INTERACTION_DELETE = "DELETE";
    public static final String INTERACTION_HISTORY = "HISTORY";
    public static final String INTERACTION_TRANSACTION = "TRANSACTION";
    public static final String INTERACTION_OPERATION = "OPERATION";

    public static final String AUTH_NONE = "NONE";
    public static final String AUTH_BASIC = "BASIC";
    public static final String AUTH_BEARER = "BEARER";

    /** {@code Prefer: return=} values (R4/R5 §3.1.0.1.7). */
    public static final String PREFER_UNSET = "NONE";
    public static final String PREFER_MINIMAL = "minimal";
    public static final String PREFER_REPRESENTATION = "representation";
    public static final String PREFER_OPERATION_OUTCOME = "OperationOutcome";

    private DestinationConnectorProperties destinationConnectorProperties;

    private String fhirVersion;
    private String serverUrl;
    private String interaction;
    private String resourceType;
    private String resourceId;
    private String versionId;
    private String operationName;
    private String content;
    private String contentFormat;
    private String acceptFormat;
    private String charset;
    private Map<String, List<String>> parameters;
    private boolean useParametersVariable;
    private String parametersVariable;
    private Map<String, List<String>> headers;
    private boolean useHeadersVariable;
    private String headersVariable;
    private String authenticationType;
    private String username;
    private String password;
    private String bearerToken;
    private String ifMatch;
    private String ifNoneExist;
    private String ifNoneMatch;
    private String preferReturn;
    private boolean useProxyServer;
    private String proxyAddress;
    private String proxyPort;
    private String socketTimeout;

    public FhirDispatcherProperties() {
        destinationConnectorProperties = new DestinationConnectorProperties(true);

        fhirVersion = FhirVersion.R4.getId();
        serverUrl = "";
        interaction = INTERACTION_CREATE;
        resourceType = "";
        resourceId = "";
        versionId = "";
        operationName = "";
        content = "${message.encodedData}";
        contentFormat = FhirFormat.JSON.getCode();
        acceptFormat = FhirFormat.JSON.getCode();
        charset = "UTF-8";
        parameters = new LinkedHashMap<String, List<String>>();
        useParametersVariable = false;
        parametersVariable = "";
        headers = new LinkedHashMap<String, List<String>>();
        useHeadersVariable = false;
        headersVariable = "";
        authenticationType = AUTH_NONE;
        username = "";
        password = "";
        bearerToken = "";
        ifMatch = "";
        ifNoneExist = "";
        ifNoneMatch = "";
        preferReturn = PREFER_UNSET;
        useProxyServer = false;
        proxyAddress = "";
        proxyPort = "";
        socketTimeout = "30000";
    }

    /**
     * Copy constructor behind {@link #clone()}.
     *
     * <p>Deep for the two maps, because the engine hands each message its own clone and
     * then rewrites the templates in it. Sharing a map between clones would let one
     * message's resolved header values leak into the next one's request.
     */
    public FhirDispatcherProperties(FhirDispatcherProperties props) {
        super(props);

        destinationConnectorProperties =
                new DestinationConnectorProperties(props.getDestinationConnectorProperties());

        fhirVersion = props.getFhirVersion();
        serverUrl = props.getServerUrl();
        interaction = props.getInteraction();
        resourceType = props.getResourceType();
        resourceId = props.getResourceId();
        versionId = props.getVersionId();
        operationName = props.getOperationName();
        content = props.getContent();
        contentFormat = props.getContentFormat();
        acceptFormat = props.getAcceptFormat();
        charset = props.getCharset();
        parameters = copy(props.getParameters());
        useParametersVariable = props.isUseParametersVariable();
        parametersVariable = props.getParametersVariable();
        headers = copy(props.getHeaders());
        useHeadersVariable = props.isUseHeadersVariable();
        headersVariable = props.getHeadersVariable();
        authenticationType = props.getAuthenticationType();
        username = props.getUsername();
        password = props.getPassword();
        bearerToken = props.getBearerToken();
        ifMatch = props.getIfMatch();
        ifNoneExist = props.getIfNoneExist();
        ifNoneMatch = props.getIfNoneMatch();
        preferReturn = props.getPreferReturn();
        useProxyServer = props.isUseProxyServer();
        proxyAddress = props.getProxyAddress();
        proxyPort = props.getProxyPort();
        socketTimeout = props.getSocketTimeout();
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<String, List<String>>();
        if (source != null) {
            for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                copy.put(entry.getKey(),
                        entry.getValue() == null ? null : new ArrayList<String>(entry.getValue()));
            }
        }
        return copy;
    }

    @Override
    public FhirDispatcherProperties clone() {
        return new FhirDispatcherProperties(this);
    }

    @Override
    public String getProtocol() {
        return "FHIR";
    }

    @Override
    public String getName() {
        return "FHIR Sender";
    }

    @Override
    public String toFormattedString() {
        StringBuilder text = new StringBuilder();
        text.append(interaction).append(' ').append(serverUrl);
        if (resourceType != null && !resourceType.isEmpty()) {
            text.append('/').append(resourceType);
            if (resourceId != null && !resourceId.isEmpty()) {
                text.append('/').append(resourceId);
            }
        }
        if (content != null && !content.isEmpty()) {
            text.append(System.lineSeparator()).append(System.lineSeparator()).append(content);
        }
        return text.toString();
    }

    public FhirVersion version() {
        return FhirVersion.fromId(fhirVersion);
    }

    public FhirFormat contentFhirFormat() {
        FhirFormat format = FhirFormat.fromMimeType(contentFormat);
        return format == null ? FhirFormat.JSON : format;
    }

    public FhirFormat acceptFhirFormat() {
        FhirFormat format = FhirFormat.fromMimeType(acceptFormat);
        return format == null ? FhirFormat.JSON : format;
    }

    /** The server base URL with any trailing slash removed, ready to append a path to. */
    public String normalisedServerUrl() {
        String url = serverUrl == null ? "" : serverUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    @Override
    public DestinationConnectorProperties getDestinationConnectorProperties() {
        return destinationConnectorProperties;
    }

    /**
     * No response validation option on this connector.
     *
     * <p>Response validation exists for transports where success is carried inside the
     * payload rather than the protocol -- an HL7 v2 ACK over MLLP being the reason it was
     * built. FHIR states the outcome in the HTTP status line, which this connector already
     * reads, so offering a second opinion from the response data type would only add a way
     * for the two to disagree.
     */
    @Override
    public boolean canValidateResponse() {
        return false;
    }

    public String getFhirVersion() {
        return fhirVersion;
    }

    public void setFhirVersion(String fhirVersion) {
        this.fhirVersion = fhirVersion;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getInteraction() {
        return interaction;
    }

    public void setInteraction(String interaction) {
        this.interaction = interaction;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getOperationName() {
        return operationName;
    }

    public void setOperationName(String operationName) {
        this.operationName = operationName;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentFormat() {
        return contentFormat;
    }

    public void setContentFormat(String contentFormat) {
        this.contentFormat = contentFormat;
    }

    public String getAcceptFormat() {
        return acceptFormat;
    }

    public void setAcceptFormat(String acceptFormat) {
        this.acceptFormat = acceptFormat;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public Map<String, List<String>> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, List<String>> parameters) {
        this.parameters = parameters;
    }

    public boolean isUseParametersVariable() {
        return useParametersVariable;
    }

    public void setUseParametersVariable(boolean useParametersVariable) {
        this.useParametersVariable = useParametersVariable;
    }

    public String getParametersVariable() {
        return parametersVariable;
    }

    public void setParametersVariable(String parametersVariable) {
        this.parametersVariable = parametersVariable;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, List<String>> headers) {
        this.headers = headers;
    }

    public boolean isUseHeadersVariable() {
        return useHeadersVariable;
    }

    public void setUseHeadersVariable(boolean useHeadersVariable) {
        this.useHeadersVariable = useHeadersVariable;
    }

    public String getHeadersVariable() {
        return headersVariable;
    }

    public void setHeadersVariable(String headersVariable) {
        this.headersVariable = headersVariable;
    }

    public String getAuthenticationType() {
        return authenticationType;
    }

    public void setAuthenticationType(String authenticationType) {
        this.authenticationType = authenticationType;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public String getIfMatch() {
        return ifMatch;
    }

    public void setIfMatch(String ifMatch) {
        this.ifMatch = ifMatch;
    }

    public String getIfNoneExist() {
        return ifNoneExist;
    }

    public void setIfNoneExist(String ifNoneExist) {
        this.ifNoneExist = ifNoneExist;
    }

    public String getIfNoneMatch() {
        return ifNoneMatch;
    }

    public void setIfNoneMatch(String ifNoneMatch) {
        this.ifNoneMatch = ifNoneMatch;
    }

    public String getPreferReturn() {
        return preferReturn;
    }

    public void setPreferReturn(String preferReturn) {
        this.preferReturn = preferReturn;
    }

    public boolean isUseProxyServer() {
        return useProxyServer;
    }

    public void setUseProxyServer(boolean useProxyServer) {
        this.useProxyServer = useProxyServer;
    }

    public String getProxyAddress() {
        return proxyAddress;
    }

    public void setProxyAddress(String proxyAddress) {
        this.proxyAddress = proxyAddress;
    }

    public String getProxyPort() {
        return proxyPort;
    }

    public void setProxyPort(String proxyPort) {
        this.proxyPort = proxyPort;
    }

    public String getSocketTimeout() {
        return socketTimeout;
    }

    public void setSocketTimeout(String socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fhirVersion, serverUrl, interaction, resourceType);
    }

    @Override
    public void migrate3_0_1(DonkeyElement element) {
    }

    @Override
    public void migrate3_0_2(DonkeyElement element) {
    }

    /**
     * Usage statistics. The server URL, credentials and any templated content are the whole
     * of the sensitive surface here, so none of them appear -- only which interaction and
     * which authentication style are in use.
     */
    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new LinkedHashMap<String, Object>();
        purged.put("fhirVersion", fhirVersion);
        purged.put("interaction", interaction);
        purged.put("contentFormat", contentFormat);
        purged.put("acceptFormat", acceptFormat);
        purged.put("authenticationType", authenticationType);
        purged.put("preferReturn", preferReturn);
        purged.put("useProxyServer", useProxyServer);
        purged.put("parameterCount", parameters == null ? 0 : parameters.size());
        purged.put("headerCount", headers == null ? 0 : headers.size());
        purged.put("contentLines", content == null ? 0 : content.split("\r\n|\r|\n").length);
        purged.put("destinationConnectorProperties", destinationConnectorProperties.getPurgedProperties());
        return purged;
    }
}
