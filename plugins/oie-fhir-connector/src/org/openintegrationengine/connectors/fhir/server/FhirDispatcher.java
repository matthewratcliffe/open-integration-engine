/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir.server;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.DestinationConnector;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.util.TemplateValueReplacer;
import com.mirth.connect.util.ErrorMessageBuilder;

import org.apache.commons.lang3.math.NumberUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openintegrationengine.connectors.fhir.FhirDispatcherProperties;
import org.openintegrationengine.connectors.fhir.FhirFormat;
import org.openintegrationengine.connectors.fhir.FhirVersion;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends one FHIR RESTful interaction to a remote FHIR server per message.
 *
 * <p>Create is the common case -- POST a resource to {@code [base]/[type]} -- but the same
 * connector performs update, patch, read, vread, search, delete, history, transaction and
 * {@code $operation}, because they differ only in method and URL and a channel that can do
 * one should not need a different connector to do the next.
 *
 * <p>What comes back is reported the way an integration engine needs it: the body becomes
 * the destination's response (so a facade in front of the same channel can return it
 * verbatim), and the status line, headers, {@code Location}, {@code ETag} and the id of the
 * resource the server assigned go into the connector map. A 4xx or 5xx is an error, with
 * the server's own OperationOutcome diagnostics lifted into the error text rather than
 * leaving an operator to read a status code and guess.
 */
public class FhirDispatcher extends DestinationConnector {

    private static final String CONNECTOR_TYPE = "FHIR Sender";

    private final Logger logger = LogManager.getLogger(getClass());
    private final TemplateValueReplacer replacer = new TemplateValueReplacer();
    private final EventController eventController = ControllerFactory.getFactory().createEventController();

    private CloseableHttpClient client;
    private PoolingHttpClientConnectionManager connectionManager;

    @Override
    public FhirDispatcherProperties getConnectorProperties() {
        return (FhirDispatcherProperties) super.getConnectorProperties();
    }

    @Override
    public void onDeploy() throws ConnectorTaskException {
        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getDestinationName(),
                ConnectionStatusEventType.IDLE));
    }

    @Override
    public void onUndeploy() throws ConnectorTaskException {
    }

    @Override
    public void onStart() throws ConnectorTaskException {
        /*
         * One pooled client for the destination rather than one per message. A FHIR server
         * behind TLS costs a handshake per connection, and a queued destination sending
         * thousands of messages would otherwise spend most of its time in them. The pool is
         * sized to the destination's own thread count, which is what actually bounds
         * concurrency here.
         */
        connectionManager = new PoolingHttpClientConnectionManager();
        int threads = Math.max(1, getConnectorProperties().getDestinationConnectorProperties().getThreadCount());
        connectionManager.setMaxTotal(threads * 2);
        connectionManager.setDefaultMaxPerRoute(threads * 2);

        client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableCookieManagement()
                .build();
    }

    @Override
    public void onStop() throws ConnectorTaskException {
        closeClient();
    }

    @Override
    public void onHalt() throws ConnectorTaskException {
        closeClient();
    }

    private void closeClient() {
        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                logger.debug("FHIR Sender on channel {} failed to close its HTTP client", getChannelId(), e);
            }
            client = null;
        }
        if (connectionManager != null) {
            connectionManager.close();
            connectionManager = null;
        }
        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getDestinationName(),
                ConnectionStatusEventType.DISCONNECTED));
    }

    /**
     * Resolves every {@code ${}} template against the message before the send.
     *
     * <p>The engine calls this on a copy, so the channel's configured properties are never
     * mutated; what it produces is also what the message browser shows as the destination's
     * "sent" content, which is why the credentials are replaced here too -- a channel that
     * pulls a token out of the channel map has to have it resolved by this point.
     */
    @Override
    public void replaceConnectorProperties(ConnectorProperties connectorProperties, ConnectorMessage message) {
        FhirDispatcherProperties props = (FhirDispatcherProperties) connectorProperties;

        props.setServerUrl(replacer.replaceValues(props.getServerUrl(), message));
        props.setResourceType(replacer.replaceValues(props.getResourceType(), message));
        props.setResourceId(replacer.replaceValues(props.getResourceId(), message));
        props.setVersionId(replacer.replaceValues(props.getVersionId(), message));
        props.setOperationName(replacer.replaceValues(props.getOperationName(), message));
        props.setContent(replacer.replaceValues(props.getContent(), message));
        props.setUsername(replacer.replaceValues(props.getUsername(), message));
        props.setPassword(replacer.replaceValues(props.getPassword(), message));
        props.setBearerToken(replacer.replaceValues(props.getBearerToken(), message));
        props.setIfMatch(replacer.replaceValues(props.getIfMatch(), message));
        props.setIfNoneExist(replacer.replaceValues(props.getIfNoneExist(), message));
        props.setIfNoneMatch(replacer.replaceValues(props.getIfNoneMatch(), message));
        props.setProxyAddress(replacer.replaceValues(props.getProxyAddress(), message));
        props.setProxyPort(replacer.replaceValues(props.getProxyPort(), message));
        props.setSocketTimeout(replacer.replaceValues(props.getSocketTimeout(), message));

        if (!props.isUseHeadersVariable()) {
            props.setHeaders(replacer.replaceKeysAndValuesInMap(props.getHeaders(), message));
        } else {
            props.setHeadersVariable(replacer.replaceValues(props.getHeadersVariable(), message));
        }
        if (!props.isUseParametersVariable()) {
            props.setParameters(replacer.replaceKeysAndValuesInMap(props.getParameters(), message));
        } else {
            props.setParametersVariable(replacer.replaceValues(props.getParametersVariable(), message));
        }
    }

    @Override
    public Response send(ConnectorProperties connectorProperties, ConnectorMessage message) {
        FhirDispatcherProperties props = (FhirDispatcherProperties) connectorProperties;
        FhirVersion version = props.version();
        Charset charset = charset(props);

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getDestinationName(),
                ConnectionStatusEventType.WRITING));

        HttpRequestBase request = null;
        try {
            request = buildRequest(props, message, version, charset);
        } catch (Exception e) {
            String error = ErrorMessageBuilder.buildErrorMessage(CONNECTOR_TYPE,
                    "Could not build the FHIR request: " + e.getMessage(), e);
            eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), message.getMessageId(),
                    ErrorEventType.DESTINATION_CONNECTOR, getDestinationName(), CONNECTOR_TYPE, e.getMessage(), e));
            return new Response(Status.ERROR, null, "Invalid FHIR request configuration", error);
        }

        CloseableHttpResponse httpResponse = null;
        try {
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                    getDestinationName(), ConnectionStatusEventType.SENDING,
                    request.getMethod() + " " + request.getURI()));

            httpResponse = client.execute(request);
            return readResponse(props, message, httpResponse, request, charset);
        } catch (Exception e) {
            String error = ErrorMessageBuilder.buildErrorMessage(CONNECTOR_TYPE, e.getMessage(), e);
            eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), message.getMessageId(),
                    ErrorEventType.DESTINATION_CONNECTOR, getDestinationName(), CONNECTOR_TYPE, e.getMessage(), e));
            // QUEUED rather than ERROR when queueing is on is the engine's decision, not
            // ours: returning ERROR is what tells it the attempt failed and may be retried.
            return new Response(Status.ERROR, null, "Failed to send to " + props.normalisedServerUrl(), error);
        } finally {
            if (httpResponse != null) {
                try {
                    httpResponse.close();
                } catch (IOException e) {
                    logger.debug("FHIR Sender on channel {} failed to close a response", getChannelId(), e);
                }
            }
            if (request != null) {
                request.releaseConnection();
            }
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                    getDestinationName(), ConnectionStatusEventType.IDLE));
        }
    }

    // ------------------------------------------------------------------
    // Request
    // ------------------------------------------------------------------

    private HttpRequestBase buildRequest(FhirDispatcherProperties props, ConnectorMessage message,
            FhirVersion version, Charset charset) throws Exception {

        String base = props.normalisedServerUrl();
        if (base.isEmpty()) {
            throw new IllegalStateException("No FHIR server URL is configured");
        }

        String interaction = props.getInteraction();
        String path = path(props, interaction);
        URIBuilder uri = new URIBuilder(base + path);

        for (Map.Entry<String, List<String>> parameter : parameters(props, message).entrySet()) {
            if (parameter.getValue() == null) {
                continue;
            }
            for (String value : parameter.getValue()) {
                uri.addParameter(parameter.getKey(), value);
            }
        }

        HttpRequestBase request = method(interaction, uri.build());

        FhirFormat accept = props.acceptFhirFormat();
        request.setHeader("Accept", accept.contentType(version, null));

        if (request instanceof HttpPost || request instanceof HttpPut || request instanceof HttpPatch) {
            FhirFormat contentFormat = props.contentFhirFormat();
            String body = props.getContent() == null ? "" : props.getContent();
            // ContentType carries the charset; the fhirVersion parameter is added by hand
            // because Apache's ContentType has no notion of extra media type parameters.
            StringEntity entity = new StringEntity(body, ContentType.create(contentFormat.getMimeType(), charset));
            ((org.apache.http.client.methods.HttpEntityEnclosingRequestBase) request).setEntity(entity);
            request.setHeader("Content-Type", contentFormat.contentType(version, charset.name()));
        }

        // Conditional interactions (R4/R5 §3.1.0.1.4, §3.1.0.1.12).
        setIfPresent(request, "If-Match", props.getIfMatch());
        setIfPresent(request, "If-None-Match", props.getIfNoneMatch());
        setIfPresent(request, "If-None-Exist", props.getIfNoneExist());

        if (!FhirDispatcherProperties.PREFER_UNSET.equals(props.getPreferReturn())
                && props.getPreferReturn() != null && !props.getPreferReturn().isEmpty()) {
            request.setHeader("Prefer", "return=" + props.getPreferReturn());
        }

        applyAuthentication(request, props);

        // Configured headers last, so an explicit header always wins over a derived one --
        // that is the escape hatch for a server that wants a non-standard Content-Type
        // (a JSON Patch body, say) or its own Accept.
        for (Map.Entry<String, List<String>> header : headers(props, message).entrySet()) {
            List<String> values = header.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            request.setHeader(header.getKey(), values.get(0));
            for (int i = 1; i < values.size(); i++) {
                request.addHeader(header.getKey(), values.get(i));
            }
        }

        request.setConfig(requestConfig(props));
        return request;
    }

    /** The path below the service base for each interaction. */
    private String path(FhirDispatcherProperties props, String interaction) {
        String type = trimToEmpty(props.getResourceType());
        String id = trimToEmpty(props.getResourceId());
        String versionId = trimToEmpty(props.getVersionId());
        String operation = trimToEmpty(props.getOperationName());

        if (FhirDispatcherProperties.INTERACTION_TRANSACTION.equals(interaction)) {
            return "";
        }

        if (FhirDispatcherProperties.INTERACTION_OPERATION.equals(interaction)) {
            if (operation.isEmpty()) {
                throw new IllegalStateException("No operation name is configured");
            }
            String name = operation.startsWith("$") ? operation : "$" + operation;
            StringBuilder path = new StringBuilder();
            if (!type.isEmpty()) {
                path.append('/').append(type);
                if (!id.isEmpty()) {
                    path.append('/').append(id);
                }
            }
            return path.append('/').append(name).toString();
        }

        if (type.isEmpty()) {
            throw new IllegalStateException("No resource type is configured for the "
                    + interaction.toLowerCase(java.util.Locale.ROOT) + " interaction");
        }

        if (FhirDispatcherProperties.INTERACTION_CREATE.equals(interaction)
                || FhirDispatcherProperties.INTERACTION_SEARCH.equals(interaction)) {
            return "/" + type;
        }

        if (FhirDispatcherProperties.INTERACTION_HISTORY.equals(interaction)) {
            return id.isEmpty() ? "/" + type + "/_history" : "/" + type + "/" + id + "/_history";
        }

        if (FhirDispatcherProperties.INTERACTION_VREAD.equals(interaction)) {
            if (id.isEmpty() || versionId.isEmpty()) {
                throw new IllegalStateException("A vread needs both a resource id and a version id");
            }
            return "/" + type + "/" + id + "/_history/" + versionId;
        }

        // update, patch and delete accept an empty id: that is the conditional form, which
        // identifies its target with search parameters instead.
        if (id.isEmpty()) {
            if (FhirDispatcherProperties.INTERACTION_READ.equals(interaction)) {
                throw new IllegalStateException("A read needs a resource id");
            }
            return "/" + type;
        }
        return "/" + type + "/" + id;
    }

    private HttpRequestBase method(String interaction, URI uri) {
        if (FhirDispatcherProperties.INTERACTION_CREATE.equals(interaction)
                || FhirDispatcherProperties.INTERACTION_TRANSACTION.equals(interaction)
                || FhirDispatcherProperties.INTERACTION_OPERATION.equals(interaction)) {
            return new HttpPost(uri);
        }
        if (FhirDispatcherProperties.INTERACTION_UPDATE.equals(interaction)) {
            return new HttpPut(uri);
        }
        if (FhirDispatcherProperties.INTERACTION_PATCH.equals(interaction)) {
            return new HttpPatch(uri);
        }
        if (FhirDispatcherProperties.INTERACTION_DELETE.equals(interaction)) {
            return new HttpDelete(uri);
        }
        return new HttpGet(uri);
    }

    /**
     * Authentication headers, set preemptively.
     *
     * <p>Preemptive rather than waiting for a 401 challenge: it halves the round trips, and
     * a good many FHIR servers answer an unauthenticated request with a 403 or a login page
     * rather than the {@code WWW-Authenticate} a challenge-response flow needs.
     */
    private void applyAuthentication(HttpRequestBase request, FhirDispatcherProperties props) {
        String type = props.getAuthenticationType();
        if (FhirDispatcherProperties.AUTH_BASIC.equals(type)) {
            String credentials = trimToEmpty(props.getUsername()) + ":" + (props.getPassword() == null ? "" : props.getPassword());
            request.setHeader("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        } else if (FhirDispatcherProperties.AUTH_BEARER.equals(type)) {
            String token = trimToEmpty(props.getBearerToken());
            if (!token.isEmpty()) {
                // Accepts a bare token or one already carrying its scheme, because a channel
                // that fetched the token from an OAuth2 endpoint usually has the whole
                // "Bearer x" string in hand.
                request.setHeader("Authorization",
                        token.toLowerCase(java.util.Locale.ROOT).startsWith("bearer ") ? token : "Bearer " + token);
            }
        }
    }

    private RequestConfig requestConfig(FhirDispatcherProperties props) {
        int timeout = NumberUtils.toInt(props.getSocketTimeout(), 30000);
        RequestConfig.Builder config = RequestConfig.custom()
                .setSocketTimeout(timeout)
                .setConnectTimeout(timeout)
                .setConnectionRequestTimeout(timeout);

        if (props.isUseProxyServer()) {
            int proxyPort = NumberUtils.toInt(props.getProxyPort(), 0);
            String proxyAddress = trimToEmpty(props.getProxyAddress());
            if (!proxyAddress.isEmpty() && proxyPort > 0) {
                config.setProxy(new HttpHost(proxyAddress, proxyPort));
            }
        }
        return config.build();
    }

    // ------------------------------------------------------------------
    // Response
    // ------------------------------------------------------------------

    private Response readResponse(FhirDispatcherProperties props, ConnectorMessage message,
            CloseableHttpResponse httpResponse, HttpRequestBase request, Charset charset) throws IOException {

        int statusCode = httpResponse.getStatusLine().getStatusCode();
        HttpEntity entity = httpResponse.getEntity();
        String body = entity == null ? "" : EntityUtils.toString(entity, charset);

        Map<String, List<String>> responseHeaders = new LinkedHashMap<String, List<String>>();
        for (Header header : httpResponse.getAllHeaders()) {
            List<String> values = responseHeaders.get(header.getName());
            if (values == null) {
                values = new ArrayList<String>();
                responseHeaders.put(header.getName(), values);
            }
            values.add(header.getValue());
        }

        String location = firstHeader(httpResponse, "Location", "Content-Location");
        String etag = firstHeader(httpResponse, "ETag");

        /*
         * Everything a channel might want to act on next, put where it can reach it. The id
         * is the interesting one: after a create, the id the server assigned is the only way
         * to link the local record to the remote resource, and it arrives in the Location
         * header rather than in the body when Prefer: return=minimal was used.
         */
        Map<String, Object> connectorMap = message.getConnectorMap();
        connectorMap.put("responseStatusLine", httpResponse.getStatusLine().toString());
        connectorMap.put("responseHeaders", responseHeaders);
        connectorMap.put("fhirResponseStatusCode", statusCode);
        if (location != null) {
            connectorMap.put("fhirResponseLocation", location);
        }
        if (etag != null) {
            connectorMap.put("fhirResponseETag", etag);
        }

        FhirPayload.Info info = FhirPayload.inspect(body, null);
        String assignedId = info.getId() != null ? info.getId() : idFromLocation(location);
        if (assignedId != null) {
            connectorMap.put("fhirResourceId", assignedId);
        }
        if (info.getVersionId() != null) {
            connectorMap.put("fhirVersionId", info.getVersionId());
        }

        String statusMessage = request.getMethod() + " " + request.getURI() + " -> "
                + httpResponse.getStatusLine().getStatusCode() + " "
                + httpResponse.getStatusLine().getReasonPhrase();

        if (statusCode >= 200 && statusCode < 300) {
            return new Response(Status.SENT, body, statusMessage, null);
        }

        /*
         * A 4xx/5xx is an error even though the exchange itself succeeded, because the
         * resource was not accepted. The server's OperationOutcome diagnostics go into the
         * error text: "422 Unprocessable Entity" on a dashboard tells an operator nothing,
         * while "Patient.identifier: minimum required = 1" tells them everything.
         */
        String diagnostics = diagnostics(body);
        String error = ErrorMessageBuilder.buildErrorMessage(CONNECTOR_TYPE,
                diagnostics == null ? statusMessage : statusMessage + ": " + diagnostics, null);
        eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), message.getMessageId(),
                ErrorEventType.DESTINATION_CONNECTOR, getDestinationName(), CONNECTOR_TYPE, statusMessage, null));
        return new Response(Status.ERROR, body, statusMessage, error);
    }

    /**
     * Pulls the first issue's diagnostics out of an OperationOutcome, in either
     * serialisation. Returns null when the body is not one, which is common enough --
     * gateways in front of FHIR servers return HTML error pages.
     */
    private String diagnostics(String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        FhirPayload.Info info = FhirPayload.inspect(body, null);
        if (!"OperationOutcome".equals(info.getResourceType())) {
            return null;
        }
        String trimmed = body.trim();
        // The outcome has already been parsed once to learn its type; rather than parse it
        // again into a model this connector does not have, take the first diagnostics value
        // out of the serialisation directly.
        java.util.regex.Matcher json = java.util.regex.Pattern
                .compile("\"diagnostics\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(trimmed);
        if (json.find()) {
            return json.group(1).replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\");
        }
        java.util.regex.Matcher xml = java.util.regex.Pattern
                .compile("<diagnostics[^>]*value=\"([^\"]*)\"").matcher(trimmed);
        if (xml.find()) {
            return xml.group(1);
        }
        return null;
    }

    /** The logical id from a Location header of the form {@code [base]/[type]/[id]/_history/[vid]}. */
    private static String idFromLocation(String location) {
        if (location == null || location.isEmpty()) {
            return null;
        }
        String path = location;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            if (segments[i].equals("_history") && i >= 1) {
                // .../[id]/_history/[vid] -- the id is two before the version.
                return i >= 1 ? segments[i - 1] : null;
            }
        }
        return segments.length == 0 ? null : segments[segments.length - 1];
    }

    private static String firstHeader(CloseableHttpResponse response, String... names) {
        for (String name : names) {
            Header header = response.getFirstHeader(name);
            if (header != null && header.getValue() != null && !header.getValue().isEmpty()) {
                return header.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, List<String>> headers(FhirDispatcherProperties props, ConnectorMessage message) {
        if (!props.isUseHeadersVariable()) {
            return props.getHeaders();
        }
        return mapVariable(message, props.getHeadersVariable());
    }

    private Map<String, List<String>> parameters(FhirDispatcherProperties props, ConnectorMessage message) {
        if (!props.isUseParametersVariable()) {
            return props.getParameters();
        }
        return mapVariable(message, props.getParametersVariable());
    }

    /**
     * Reads a multi-valued map out of a channel variable, accepting the single-valued map a
     * transformer is far more likely to have built.
     */
    private Map<String, List<String>> mapVariable(ConnectorMessage message, String variable) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        if (variable == null || variable.trim().isEmpty()) {
            return result;
        }
        Object value = lookup(message, variable.trim());
        if (!(value instanceof Map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            List<String> values = new ArrayList<String>();
            if (entry.getValue() instanceof List) {
                for (Object item : (List<?>) entry.getValue()) {
                    values.add(String.valueOf(item));
                }
            } else if (entry.getValue() != null) {
                values.add(String.valueOf(entry.getValue()));
            }
            result.put(String.valueOf(entry.getKey()), values);
        }
        return result;
    }

    private Object lookup(ConnectorMessage message, String key) {
        Map<String, Object> connectorMap = message.getConnectorMap();
        if (connectorMap != null && connectorMap.containsKey(key)) {
            return connectorMap.get(key);
        }
        Map<String, Object> channelMap = message.getChannelMap();
        if (channelMap != null && channelMap.containsKey(key)) {
            return channelMap.get(key);
        }
        Map<String, Object> responseMap = message.getResponseMap();
        if (responseMap != null && responseMap.containsKey(key)) {
            return responseMap.get(key);
        }
        Map<String, Object> sourceMap = message.getSourceMap();
        return sourceMap == null ? null : sourceMap.get(key);
    }

    private Charset charset(FhirDispatcherProperties props) {
        try {
            return Charset.forName(props.getCharset());
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static void setIfPresent(HttpRequestBase request, String name, String value) {
        if (value != null && !value.trim().isEmpty()) {
            request.setHeader(name, value.trim());
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
