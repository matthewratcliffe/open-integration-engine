/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.ListenerConnectorProperties;
import com.mirth.connect.donkey.model.channel.ListenerConnectorPropertiesInterface;
import com.mirth.connect.donkey.model.channel.SourceConnectorProperties;
import com.mirth.connect.donkey.model.channel.SourceConnectorPropertiesInterface;
import com.mirth.connect.donkey.util.DonkeyElement;

import org.apache.commons.lang3.builder.EqualsBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Settings for the FHIR Listener -- the source half, which presents a FHIR RESTful facade
 * and answers it from whatever the channel's destinations return.
 *
 * <p>Serialised into the channel XML by XStream under this class's fully qualified name,
 * which is also the {@code @class} the web administrator's panel writes. Renaming or
 * moving this class breaks every channel already using it.
 */
public class FhirReceiverProperties extends ConnectorProperties
        implements ListenerConnectorPropertiesInterface, SourceConnectorPropertiesInterface {

    /** How the request is handed to the channel. */
    public static final String CONTENT_BODY = "BODY";
    public static final String CONTENT_ENVELOPE = "ENVELOPE";

    /**
     * Whether the listener asks the client for a certificate, and what happens when it does
     * not present one. {@code WANT} is the awkward middle: the handshake succeeds either
     * way and the channel has to decide, which is only useful while a partner is migrating.
     */
    public static final String CLIENT_AUTH_NONE = "NONE";
    public static final String CLIENT_AUTH_WANT = "WANT";
    public static final String CLIENT_AUTH_NEED = "NEED";

    /** Where {@code GET [base]/metadata} is answered from. */
    public static final String CAPABILITY_AUTO = "AUTO";
    public static final String CAPABILITY_CUSTOM = "CUSTOM";
    public static final String CAPABILITY_CHANNEL = "CHANNEL";
    public static final String CAPABILITY_DISABLED = "DISABLED";

    private ListenerConnectorProperties listenerConnectorProperties;
    private SourceConnectorProperties sourceConnectorProperties;

    private boolean useSsl;
    private String keyStoreFile;
    private String keyStorePassword;
    private String keyStoreType;
    private String keyAlias;
    private String keyPassword;
    private String clientAuth;
    private String trustStoreFile;
    private String trustStorePassword;
    private String trustStoreType;
    private String protocols;
    private String cipherSuites;

    private String fhirVersion;
    private String basePath;
    private String charset;
    private String timeout;
    private String maxRequestSize;
    private String messageContent;
    private String defaultFormat;
    private boolean strictVersionNegotiation;
    private String enabledInteractions;
    private String resourceTypes;
    private String capabilityMode;
    private String capabilityStatement;
    private String baseUrlOverride;
    private String responseStatusCode;
    private Map<String, List<String>> responseHeaders;
    private boolean useResponseHeadersVariable;
    private String responseHeadersVariable;
    private boolean wrapNonFhirResponses;

    public FhirReceiverProperties() {
        // 8081 is the channel HTTP port this stack publishes by convention (see
        // compose.dev.yaml, and the nginx overlay's :8444 -> engine:8081 mapping), so the
        // default listener is reachable without editing compose files.
        listenerConnectorProperties = new ListenerConnectorProperties("8081");
        /*
         * Wait for the destinations before answering. A facade whose point is to return
         * what a destination produced must not reply before the destinations have run, so
         * the two "before processing" options are wrong here by construction. This default
         * yields a correct, empty success response; returning the destination's resource is
         * one step further -- select that destination by name in Response.
         */
        sourceConnectorProperties =
                new SourceConnectorProperties(SourceConnectorProperties.RESPONSE_DESTINATIONS_COMPLETED);

        /*
         * Off, so an upgrade changes nothing: this stack's nginx overlay has been
         * terminating TLS in front of the listener, and a connector that started answering
         * TLS on its own port after an extension update would break every one of those
         * deployments at once.
         */
        useSsl = false;
        keyStoreFile = "";
        keyStorePassword = "";
        // PKCS12 rather than JKS: it is the JDK's own default since 9, it is what openssl
        // and certbot produce, and JKS is deprecated.
        keyStoreType = "PKCS12";
        keyAlias = "";
        keyPassword = "";
        clientAuth = CLIENT_AUTH_NONE;
        trustStoreFile = "";
        trustStorePassword = "";
        trustStoreType = "PKCS12";
        /*
         * TLS 1.2 and 1.3 only. The JDK still enables TLS 1.0 and 1.1 on some builds, and
         * no FHIR client worth serving needs them -- an explicit list here is easier to
         * audit than a list of exclusions nobody reads.
         */
        protocols = "TLSv1.2,TLSv1.3";
        cipherSuites = "";

        fhirVersion = FhirVersion.R4.getId();
        basePath = "/fhir";
        charset = "UTF-8";
        timeout = "30000";
        maxRequestSize = "0";
        messageContent = CONTENT_BODY;
        defaultFormat = FhirFormat.JSON.getCode();
        strictVersionNegotiation = false;
        enabledInteractions = FhirInteraction.defaultEnabledCodes();
        resourceTypes = "";
        capabilityMode = CAPABILITY_AUTO;
        capabilityStatement = "";
        baseUrlOverride = "";
        responseStatusCode = "";
        responseHeaders = new LinkedHashMap<String, List<String>>();
        useResponseHeadersVariable = false;
        responseHeadersVariable = "";
        wrapNonFhirResponses = true;
    }

    @Override
    public String getProtocol() {
        return "FHIR";
    }

    @Override
    public String getName() {
        return "FHIR Listener";
    }

    @Override
    public String toFormattedString() {
        return "FHIR " + fhirVersion + " facade at " + (useSsl ? "https://" : "http://")
                + listenerConnectorProperties.getHost() + ":" + listenerConnectorProperties.getPort()
                + normalisedBasePath();
    }

    /**
     * The base path with a leading slash and no trailing one, which is the only form the
     * rest of the connector wants: request URIs are matched against it, and it is
     * concatenated onto a scheme and authority to advertise the service base URL.
     */
    public String normalisedBasePath() {
        String path = basePath == null ? "" : basePath.trim();
        if (path.isEmpty() || path.equals("/")) {
            return "";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    public FhirVersion version() {
        return FhirVersion.fromId(fhirVersion);
    }

    public FhirFormat defaultFhirFormat() {
        FhirFormat format = FhirFormat.fromMimeType(defaultFormat);
        return format == null ? FhirFormat.JSON : format;
    }

    @Override
    public ListenerConnectorProperties getListenerConnectorProperties() {
        return listenerConnectorProperties;
    }

    @Override
    public SourceConnectorProperties getSourceConnectorProperties() {
        return sourceConnectorProperties;
    }

    /**
     * Batching is a data-type level feature for stream formats like HL7 v2 or delimited
     * text. A FHIR request carries exactly one resource or one Bundle, and splitting a
     * Bundle is the channel's business -- a transaction Bundle has to be answered with a
     * single matching response Bundle, which a batch-splitting source could never produce.
     */
    @Override
    public boolean canBatch() {
        return false;
    }

    /**
     * Serve HTTPS on this port, rather than plain HTTP behind something that terminates TLS.
     *
     * <p>Both arrangements are legitimate. A proxy in front keeps private keys out of the
     * engine and makes certificate renewal somebody else's reload; terminating here is what
     * you want when there is no proxy to put in front, when a partner insists on client
     * certificates checked by the application, or when the hop between proxy and engine
     * crosses anything you would not shout across.
     */
    public boolean isUseSsl() {
        return useSsl;
    }

    public void setUseSsl(boolean useSsl) {
        this.useSsl = useSsl;
    }

    /**
     * The key store holding the server certificate and its private key. A relative path is
     * resolved against the engine's application data directory -- the volume this stack
     * persists -- so a certificate survives the container being rebuilt.
     */
    public String getKeyStoreFile() {
        return keyStoreFile;
    }

    public void setKeyStoreFile(String keyStoreFile) {
        this.keyStoreFile = keyStoreFile;
    }

    /**
     * The key store's password. Channel XML is not an encrypted store: put this in the
     * configuration map and reference it by its bare key -- {@code ${fhirKeyStorePassword}}
     * -- or use a Key Store plugin secret, and it stays out of git.
     */
    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    /** {@code PKCS12} or {@code JKS}. */
    public String getKeyStoreType() {
        return keyStoreType;
    }

    public void setKeyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType;
    }

    /**
     * Which certificate to present, when the store holds more than one. Blank lets the JDK
     * choose, which is only predictable in a store with a single key entry.
     */
    public String getKeyAlias() {
        return keyAlias;
    }

    public void setKeyAlias(String keyAlias) {
        this.keyAlias = keyAlias;
    }

    /** The private key's own password, when it differs from the store's. */
    public String getKeyPassword() {
        return keyPassword;
    }

    public void setKeyPassword(String keyPassword) {
        this.keyPassword = keyPassword;
    }

    /** {@link #CLIENT_AUTH_NONE}, {@link #CLIENT_AUTH_WANT} or {@link #CLIENT_AUTH_NEED}. */
    public String getClientAuth() {
        return clientAuth;
    }

    public void setClientAuth(String clientAuth) {
        this.clientAuth = clientAuth;
    }

    /**
     * The certificates client certificates are verified against. Required for client
     * authentication: without it the JDK falls back to its bundled CA list, which would
     * accept a certificate from any public CA on earth -- mutual TLS that verifies nothing
     * in particular.
     */
    public String getTrustStoreFile() {
        return trustStoreFile;
    }

    public void setTrustStoreFile(String trustStoreFile) {
        this.trustStoreFile = trustStoreFile;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    /** {@code PKCS12} or {@code JKS}. */
    public String getTrustStoreType() {
        return trustStoreType;
    }

    public void setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
    }

    /** Comma separated, such as {@code TLSv1.2,TLSv1.3}. Blank leaves the JDK's defaults. */
    public String getProtocols() {
        return protocols;
    }

    public void setProtocols(String protocols) {
        this.protocols = protocols;
    }

    /**
     * Comma separated cipher suites. Blank uses Jetty's defaults, which already exclude the
     * suites with known problems -- narrow this only against a partner's requirement.
     */
    public String getCipherSuites() {
        return cipherSuites;
    }

    public void setCipherSuites(String cipherSuites) {
        this.cipherSuites = cipherSuites;
    }

    public String getFhirVersion() {
        return fhirVersion;
    }

    public void setFhirVersion(String fhirVersion) {
        this.fhirVersion = fhirVersion;
    }

    public String getBasePath() {
        return basePath;
    }

    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public String getTimeout() {
        return timeout;
    }

    public void setTimeout(String timeout) {
        this.timeout = timeout;
    }

    public String getMaxRequestSize() {
        return maxRequestSize;
    }

    public void setMaxRequestSize(String maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
    }

    public String getMessageContent() {
        return messageContent;
    }

    public void setMessageContent(String messageContent) {
        this.messageContent = messageContent;
    }

    public String getDefaultFormat() {
        return defaultFormat;
    }

    public void setDefaultFormat(String defaultFormat) {
        this.defaultFormat = defaultFormat;
    }

    public boolean isStrictVersionNegotiation() {
        return strictVersionNegotiation;
    }

    public void setStrictVersionNegotiation(boolean strictVersionNegotiation) {
        this.strictVersionNegotiation = strictVersionNegotiation;
    }

    public String getEnabledInteractions() {
        return enabledInteractions;
    }

    public void setEnabledInteractions(String enabledInteractions) {
        this.enabledInteractions = enabledInteractions;
    }

    public String getResourceTypes() {
        return resourceTypes;
    }

    public void setResourceTypes(String resourceTypes) {
        this.resourceTypes = resourceTypes;
    }

    public String getCapabilityMode() {
        return capabilityMode;
    }

    public void setCapabilityMode(String capabilityMode) {
        this.capabilityMode = capabilityMode;
    }

    public String getCapabilityStatement() {
        return capabilityStatement;
    }

    public void setCapabilityStatement(String capabilityStatement) {
        this.capabilityStatement = capabilityStatement;
    }

    public String getBaseUrlOverride() {
        return baseUrlOverride;
    }

    public void setBaseUrlOverride(String baseUrlOverride) {
        this.baseUrlOverride = baseUrlOverride;
    }

    public String getResponseStatusCode() {
        return responseStatusCode;
    }

    public void setResponseStatusCode(String responseStatusCode) {
        this.responseStatusCode = responseStatusCode;
    }

    public Map<String, List<String>> getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(Map<String, List<String>> responseHeaders) {
        this.responseHeaders = responseHeaders;
    }

    public boolean isUseResponseHeadersVariable() {
        return useResponseHeadersVariable;
    }

    public void setUseResponseHeadersVariable(boolean useResponseHeadersVariable) {
        this.useResponseHeadersVariable = useResponseHeadersVariable;
    }

    public String getResponseHeadersVariable() {
        return responseHeadersVariable;
    }

    public void setResponseHeadersVariable(String responseHeadersVariable) {
        this.responseHeadersVariable = responseHeadersVariable;
    }

    public boolean isWrapNonFhirResponses() {
        return wrapNonFhirResponses;
    }

    public void setWrapNonFhirResponses(boolean wrapNonFhirResponses) {
        this.wrapNonFhirResponses = wrapNonFhirResponses;
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fhirVersion, basePath,
                listenerConnectorProperties == null ? null : listenerConnectorProperties.getPort());
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
     * the configuration and nothing that identifies the deployment. Host, port, base path,
     * base URL override and any custom CapabilityStatement are deliberately absent.
     */
    @Override
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new LinkedHashMap<String, Object>();
        purged.put("fhirVersion", fhirVersion);
        purged.put("messageContent", messageContent);
        purged.put("defaultFormat", defaultFormat);
        purged.put("strictVersionNegotiation", strictVersionNegotiation);
        purged.put("capabilityMode", capabilityMode);
        purged.put("wrapNonFhirResponses", wrapNonFhirResponses);
        purged.put("enabledInteractionCount", FhirInteraction.parseEnabled(enabledInteractions).size());
        purged.put("resourceTypeCount", FhirInteraction.parseResourceTypes(resourceTypes).size());
        purged.put("responseHeaderCount", responseHeaders == null ? 0 : responseHeaders.size());
        // The shape of the TLS configuration, never a path, a password or an alias.
        purged.put("useSsl", useSsl);
        purged.put("clientAuth", clientAuth);
        purged.put("keyStoreType", keyStoreType);
        purged.put("protocols", protocols);
        purged.put("restrictedCipherSuites", cipherSuites != null && !cipherSuites.trim().isEmpty());
        purged.put("sourceConnectorProperties", sourceConnectorProperties.getPurgedProperties());
        return purged;
    }
}
