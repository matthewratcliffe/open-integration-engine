/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.mirth.connect.donkey.model.event.ConnectionStatusEventType;
import com.mirth.connect.donkey.model.event.ErrorEventType;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Response;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.ConnectorTaskException;
import com.mirth.connect.donkey.server.channel.ChannelException;
import com.mirth.connect.donkey.server.channel.DispatchResult;
import com.mirth.connect.donkey.server.channel.SourceConnector;
import com.mirth.connect.donkey.server.event.ConnectionStatusEvent;
import com.mirth.connect.donkey.server.event.ErrorEvent;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.util.TemplateValueReplacer;
import com.mirth.connect.util.ErrorMessageBuilder;

import org.apache.commons.lang3.math.NumberUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.openintegrationengine.connectors.fhir.FhirFormat;
import org.openintegrationengine.connectors.fhir.FhirInteraction;
import org.openintegrationengine.connectors.fhir.FhirReceiverProperties;
import org.openintegrationengine.connectors.fhir.FhirVersion;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A FHIR RESTful facade in front of a channel.
 *
 * <p>The connector owns the HTTP conversation and the channel owns the clinical logic. It
 * accepts a request at a FHIR URL, works out which interaction it names, hands the channel
 * the body plus everything it parsed, and turns whatever the channel's destinations
 * returned back into a FHIR HTTP response -- status code, {@code Location}, {@code ETag},
 * media type with its {@code fhirVersion} parameter, and an OperationOutcome whenever
 * there is nothing else valid to send.
 *
 * <p>That division is the point. A channel author writing a patient lookup should be
 * writing the lookup, not remembering that a create returns 201 with a Location header,
 * that an unknown resource type is 404 rather than 400, or that {@code Accept:
 * application/fhir+json; fhirVersion=5.0} against an R4 endpoint is a 406. This connector
 * knows all of that; the channel returns a resource and it becomes a correct response.
 *
 * <p><strong>No TLS here.</strong> Like every other core listener in this engine, the
 * socket is plain HTTP; the deployment terminates TLS (and client certificates) at the
 * nginx overlay in front of it. {@code X-Forwarded-Proto}, {@code -Host} and {@code -Prefix}
 * are honoured when building the advertised service base URL so that {@code Location}
 * headers and the CapabilityStatement name the address a client can actually reach.
 */
public class FhirReceiver extends SourceConnector {

    private static final String CONNECTOR_TYPE = "FHIR Listener";

    /** The software name reported in a generated CapabilityStatement. */
    private static final String SOFTWARE_NAME = "Open Integration Engine";

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    private final Logger logger = LogManager.getLogger(getClass());
    private final TemplateValueReplacer replacer = new TemplateValueReplacer();
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventController eventController = ControllerFactory.getFactory().createEventController();
    private final ConfigurationController configurationController =
            ControllerFactory.getFactory().createConfigurationController();

    private Server server;

    /* Everything below is resolved once at deploy: the values can contain ${} templates,
     * and re-resolving them per request would mean a configuration map edit could move a
     * listener's port out from under a running channel. */
    private String host;
    private int port;
    private int timeout;
    private long maxRequestSize;
    private Charset charset;
    private String basePath;
    private String baseUrlOverride;
    private FhirVersion version;
    private FhirFormat defaultFormat;
    private Set<FhirInteraction> enabledInteractions;
    private Set<String> allowedResourceTypes;

    /* TLS, resolved at deploy alongside everything else. */
    private boolean useSsl;
    private Path keyStorePath;
    private String keyStorePassword;
    private String keyStoreType;
    private String keyAlias;
    private String keyPassword;
    private String clientAuth;
    private Path trustStorePath;
    private String trustStorePassword;
    private String trustStoreType;
    private String[] protocols;
    private String[] cipherSuites;

    @Override
    public FhirReceiverProperties getConnectorProperties() {
        return (FhirReceiverProperties) super.getConnectorProperties();
    }

    @Override
    public void onDeploy() throws ConnectorTaskException {
        FhirReceiverProperties props = getConnectorProperties();
        String channelName = getChannel() == null ? getChannelId() : getChannel().getName();

        host = replacer.replaceValues(props.getListenerConnectorProperties().getHost(), getChannelId(), channelName);
        port = NumberUtils.toInt(
                replacer.replaceValues(props.getListenerConnectorProperties().getPort(), getChannelId(), channelName));
        timeout = NumberUtils.toInt(replacer.replaceValues(props.getTimeout(), getChannelId(), channelName), 30000);
        maxRequestSize = NumberUtils.toLong(
                replacer.replaceValues(props.getMaxRequestSize(), getChannelId(), channelName), 0L);
        basePath = props.normalisedBasePath();
        baseUrlOverride = replacer.replaceValues(props.getBaseUrlOverride(), getChannelId(), channelName);
        version = props.version();
        defaultFormat = props.defaultFhirFormat();
        enabledInteractions = FhirInteraction.parseEnabled(props.getEnabledInteractions());
        allowedResourceTypes = FhirInteraction.parseResourceTypes(props.getResourceTypes());

        try {
            charset = Charset.forName(props.getCharset());
        } catch (Exception e) {
            // A channel should not fail to deploy over an unknown charset name, but the
            // operator does need to know their setting was ignored.
            logger.warn("FHIR Listener on channel {}: unknown charset '{}', using UTF-8",
                    getChannelId(), props.getCharset());
            charset = StandardCharsets.UTF_8;
        }

        if (port <= 0 || port > 65535) {
            throw new ConnectorTaskException("Invalid listener port: "
                    + props.getListenerConnectorProperties().getPort());
        }

        resolveTls(props, channelName);

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(),
                ConnectionStatusEventType.IDLE));
    }

    @Override
    public void onUndeploy() throws ConnectorTaskException {
    }

    @Override
    public void onStart() throws ConnectorTaskException {
        // A pool of its own, named after the channel: a thread dump on a busy engine should
        // say which facade is saturated, and one channel's traffic must not be able to
        // starve another's by sharing a pool.
        QueuedThreadPool threadPool = new QueuedThreadPool(250, 8);
        threadPool.setName("fhir-listener-" + getChannelId());

        server = new Server(threadPool);
        ServerConnector connector = useSsl ? tlsConnector(server) : new ServerConnector(server);
        connector.setHost(host);
        connector.setPort(port);
        if (timeout > 0) {
            connector.setIdleTimeout(timeout);
        }
        server.addConnector(connector);
        server.setHandler(new FhirHandler());
        // Jetty's own error pages are HTML. A FHIR client that gets one has to guess what
        // happened, so the handler answers everything itself and this stops Jetty adding
        // a fallback for the paths it never sees.
        server.setErrorHandler(new FhirErrorHandler());

        try {
            server.start();
        } catch (Exception e) {
            throw new ConnectorTaskException("Failed to start FHIR Listener on "
                    + (useSsl ? "https://" : "http://") + host + ":" + port, e);
        }

        if (useSsl) {
            logger.info("FHIR Listener on channel {} is serving TLS on {}:{} ({}, client certificates: {})",
                    getChannelId(), host, port,
                    protocols.length == 0 ? "JDK default protocols" : String.join(", ", protocols),
                    clientAuth.toLowerCase(Locale.US));
        }

        eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(),
                ConnectionStatusEventType.IDLE));
    }

    @Override
    public void onStop() throws ConnectorTaskException {
        stopServer();
    }

    @Override
    public void onHalt() throws ConnectorTaskException {
        stopServer();
    }

    private void stopServer() throws ConnectorTaskException {
        if (server == null) {
            return;
        }
        try {
            server.stop();
        } catch (Exception e) {
            throw new ConnectorTaskException("Failed to stop FHIR Listener on " + host + ":" + port, e);
        } finally {
            server = null;
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(),
                    ConnectionStatusEventType.DISCONNECTED));
        }
    }

    /**
     * Called when the engine recovers a message that was mid-flight at shutdown.
     *
     * <p>There is nothing to do: the client that sent it is long gone, so the recovered
     * response has nowhere to go. Recording it would be misleading, and failing would take
     * a channel down at startup for a request nobody is waiting on any more.
     */
    @Override
    public void handleRecoveredResponse(DispatchResult dispatchResult) {
        finishDispatch(dispatchResult);
    }

    // ------------------------------------------------------------------
    // Request handling
    // ------------------------------------------------------------------

    private class FhirHandler extends AbstractHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                HttpServletResponse response) throws IOException, ServletException {
            baseRequest.setHandled(true);
            eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(), getSourceName(),
                    ConnectionStatusEventType.RECEIVING));
            try {
                service(request, response);
            } catch (Throwable t) {
                /*
                 * Nothing above is expected to throw -- every failure path already writes
                 * its own OperationOutcome. This exists so that if one ever does, the
                 * client still gets FHIR rather than a Jetty HTML error page, and the
                 * failure lands in the channel's error log instead of only in stderr.
                 */
                logger.error("FHIR Listener on channel {} failed to handle a request", getChannelId(), t);
                eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), null,
                        ErrorEventType.SOURCE_CONNECTOR, getSourceName(), CONNECTOR_TYPE, t.getMessage(), t));
                safeWriteOutcome(response, 500, defaultFormat, FhirDocuments.SEVERITY_FATAL,
                        FhirDocuments.ISSUE_EXCEPTION, "Unhandled error processing the request");
            } finally {
                eventController.dispatchEvent(new ConnectionStatusEvent(getChannelId(), getMetaDataId(),
                        getSourceName(), ConnectionStatusEventType.IDLE));
            }
        }
    }

    private void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        FhirReceiverProperties props = getConnectorProperties();
        String method = FhirInteraction.normaliseMethod(request.getMethod());
        String uri = request.getRequestURI();

        Map<String, List<String>> parameters = parseQueryString(request.getQueryString());
        Map<String, List<String>> headers = extractHeaders(request);

        // The format the *error* replies use, chosen before anything can fail so that even
        // a 406 about content negotiation is itself in a format the client is likely to read.
        FhirFormat format = FhirFormat.negotiate(first(parameters.get("_format")), request.getHeader("Accept"),
                defaultFormat);
        FhirFormat errorFormat = format == null ? defaultFormat : format;

        if (!withinBase(uri)) {
            writeOutcome(response, 404, errorFormat, FhirDocuments.SEVERITY_ERROR, FhirDocuments.ISSUE_NOT_FOUND,
                    "No FHIR service at " + uri);
            return;
        }

        if (format == null) {
            writeOutcome(response, 406, errorFormat, FhirDocuments.SEVERITY_ERROR, FhirDocuments.ISSUE_NOT_SUPPORTED,
                    "None of the requested formats are supported; this endpoint serves "
                            + FhirFormat.JSON.getMimeType() + " and " + FhirFormat.XML.getMimeType());
            return;
        }

        if (props.isStrictVersionNegotiation()) {
            String mismatch = versionMismatch(request);
            if (mismatch != null) {
                writeOutcome(response, 406, errorFormat, FhirDocuments.SEVERITY_ERROR,
                        FhirDocuments.ISSUE_NOT_SUPPORTED, mismatch);
                return;
            }
        }

        String relativePath = uri.substring(basePath.length());
        FhirRoute route = FhirRoute.parse(method, relativePath);
        if (!route.isRouted()) {
            String issue = route.getFailureStatus() == 405
                    ? FhirDocuments.ISSUE_NOT_SUPPORTED
                    : FhirDocuments.ISSUE_NOT_FOUND;
            writeOutcome(response, route.getFailureStatus(), errorFormat, FhirDocuments.SEVERITY_ERROR, issue,
                    route.getFailureReason());
            return;
        }

        byte[] rawBody;
        try {
            rawBody = readBody(request);
        } catch (RequestTooLargeException e) {
            writeOutcome(response, 413, errorFormat, FhirDocuments.SEVERITY_ERROR, FhirDocuments.ISSUE_TOO_LONG,
                    "Request body exceeds the configured maximum of " + maxRequestSize + " bytes");
            return;
        }

        Charset requestCharset = requestCharset(request);
        String body = rawBody.length == 0 ? "" : new String(rawBody, requestCharset);

        // A POSTed _search carries its parameters in a form-encoded body, so they are
        // merged in here; the channel sees them alongside the query string ones rather
        // than having to know which way round the client chose to send them.
        if (isFormEncoded(request) && !body.isEmpty()) {
            merge(parameters, parseQueryString(body));
        }

        // POST [base] is a transaction or a batch depending on Bundle.type, which is only
        // knowable now that the body has been read.
        if (route.getInteraction() == FhirInteraction.TRANSACTION) {
            FhirPayload.Info info = FhirPayload.inspect(body, FhirFormat.fromMimeType(request.getContentType()));
            if ("batch".equals(info.getBundleType())) {
                route = route.withInteraction(FhirInteraction.BATCH);
            }
        }

        if (!enabledInteractions.contains(route.getInteraction())) {
            writeOutcome(response, 405, errorFormat, FhirDocuments.SEVERITY_ERROR, FhirDocuments.ISSUE_NOT_SUPPORTED,
                    "The " + route.getInteraction().getCode() + " interaction is not enabled on this endpoint");
            return;
        }

        if (!resourceTypeAllowed(route.getResourceType())) {
            writeOutcome(response, 404, errorFormat, FhirDocuments.SEVERITY_ERROR, FhirDocuments.ISSUE_NOT_SUPPORTED,
                    "Resource type " + route.getResourceType() + " is not served by this endpoint");
            return;
        }

        String baseUrl = baseUrl(request);

        if (route.getInteraction() == FhirInteraction.CAPABILITIES
                && !FhirReceiverProperties.CAPABILITY_CHANNEL.equals(props.getCapabilityMode())) {
            writeCapabilityStatement(response, format, baseUrl, props);
            return;
        }

        dispatchToChannel(request, response, route, format, requestCharset, body, parameters, headers, baseUrl);
    }

    /**
     * Hands the request to the channel and turns the channel's answer back into HTTP.
     */
    private void dispatchToChannel(HttpServletRequest request, HttpServletResponse response, FhirRoute route,
            FhirFormat format, Charset requestCharset, String body, Map<String, List<String>> parameters,
            Map<String, List<String>> headers, String baseUrl) throws IOException {

        Map<String, Object> sourceMap = buildSourceMap(request, route, format, parameters, headers, baseUrl);
        String content = FhirReceiverProperties.CONTENT_ENVELOPE.equals(getConnectorProperties().getMessageContent())
                ? envelope(request, route, format, body, parameters, headers, baseUrl)
                : body;

        DispatchResult dispatchResult = null;
        try {
            dispatchResult = dispatchRawMessage(new RawMessage(content, null, sourceMap));
            sendResponse(response, route, format, dispatchResult, baseUrl, request);
        } catch (ChannelException e) {
            /*
             * The channel is stopped, or its source queue is full. 503 with a Retry-After
             * is the honest answer -- the request was well formed and the client should
             * send it again, which is exactly what a 500 would discourage.
             */
            logger.debug("FHIR Listener on channel {} could not dispatch a message", getChannelId(), e);
            response.setHeader("Retry-After", "5");
            writeOutcome(response, 503, format, FhirDocuments.SEVERITY_ERROR, FhirDocuments.ISSUE_TRANSIENT,
                    e.isStopped() ? "The channel serving this endpoint is not started"
                            : "The channel serving this endpoint is not accepting messages");
        } catch (Exception e) {
            logger.error("FHIR Listener on channel {} failed while dispatching", getChannelId(), e);
            eventController.dispatchEvent(new ErrorEvent(getChannelId(), getMetaDataId(), null,
                    ErrorEventType.SOURCE_CONNECTOR, getSourceName(), CONNECTOR_TYPE,
                    ErrorMessageBuilder.buildErrorMessage(CONNECTOR_TYPE, e.getMessage(), e), e));
            writeOutcome(response, 500, format, FhirDocuments.SEVERITY_ERROR, FhirDocuments.ISSUE_EXCEPTION,
                    "The channel serving this endpoint failed to process the request");
        } finally {
            finishDispatch(dispatchResult);
        }
    }

    /**
     * Turns the selected channel response into a FHIR HTTP response.
     *
     * <p>The rules, in the order they are applied:
     *
     * <ol>
     *   <li>the configured status code wins if one is set, after {@code ${}} replacement
     *       against the processed message -- that is the escape hatch for a channel that
     *       needs a code this connector would not have chosen;
     *   <li>otherwise the code comes from the message status and the interaction: an error
     *       is 500, a queued message is 202, a create is 201, a delete with no body is 204,
     *       everything else is 200;
     *   <li>the body is whatever the channel returned, with its media type taken from what
     *       it actually is rather than from what the client asked for, because this
     *       connector cannot convert between JSON and XML;
     *   <li>a body that is not a FHIR resource becomes an OperationOutcome carrying it as
     *       diagnostics, so that the facade never answers a FHIR client with non-FHIR;
     *   <li>{@code Location}, {@code ETag} and {@code Last-Modified} are derived from the
     *       returned resource when it has the elements for them.
     * </ol>
     */
    private void sendResponse(HttpServletResponse response, FhirRoute route, FhirFormat format,
            DispatchResult dispatchResult, String baseUrl, HttpServletRequest request) throws IOException {

        FhirReceiverProperties props = getConnectorProperties();
        Response selected = dispatchResult == null ? null : dispatchResult.getSelectedResponse();
        Status status = selected == null ? null : selected.getStatus();
        String content = selected == null ? null : selected.getMessage();
        boolean errored = status == Status.ERROR;

        FhirFormat bodyFormat = FhirFormat.sniff(content);
        FhirPayload.Info info = bodyFormat == null ? FhirPayload.empty() : FhirPayload.inspect(content, bodyFormat);

        // Anything that is not a FHIR resource is wrapped, so a plain-text error from a
        // destination still reaches the client as something a FHIR client can parse.
        if (props.isWrapNonFhirResponses() && content != null && !content.trim().isEmpty() && !info.isResource()) {
            content = FhirDocuments.operationOutcome(format,
                    errored ? FhirDocuments.SEVERITY_ERROR : FhirDocuments.SEVERITY_INFORMATION,
                    errored ? FhirDocuments.ISSUE_PROCESSING : FhirDocuments.ISSUE_INFORMATIONAL,
                    content.trim());
            bodyFormat = format;
            info = FhirPayload.empty();
        }

        // Nothing came back at all. For an error that has to become an OperationOutcome;
        // for a success, an empty body is legitimate (a 201 or a 204 need none).
        if (content == null || content.trim().isEmpty()) {
            if (errored) {
                String diagnostics = selected != null && selected.getError() != null && !selected.getError().isEmpty()
                        ? selected.getError()
                        : "The channel serving this endpoint reported an error";
                content = FhirDocuments.operationOutcome(format, FhirDocuments.SEVERITY_ERROR,
                        FhirDocuments.ISSUE_PROCESSING, diagnostics);
                bodyFormat = format;
            } else {
                content = null;
            }
        }

        int statusCode = statusCode(route, status, content, dispatchResult);

        // Prefer: return= (R4/R5 §3.1.0.1.7). A client that asked for minimal gets no body;
        // one that asked for an OperationOutcome gets one instead of the resource. Errors
        // are exempt: the outcome is the only useful thing left to send.
        String prefer = preferReturn(request);
        if (!errored && prefer != null) {
            if (prefer.equals("minimal")) {
                content = null;
            } else if (prefer.equals("operationoutcome")) {
                content = FhirDocuments.operationOutcome(format, FhirDocuments.SEVERITY_INFORMATION,
                        FhirDocuments.ISSUE_INFORMATIONAL, "Request processed successfully");
                bodyFormat = format;
            }
        }

        response.setStatus(statusCode);

        String location = location(route, info, baseUrl);
        if (location != null) {
            response.setHeader("Location", location);
        }
        if (info.getVersionId() != null) {
            // Weak, because a FHIR version id identifies the version of the resource, not
            // a byte-for-byte serialisation of it (R4/R5 §3.1.0.1.10).
            response.setHeader("ETag", "W/\"" + info.getVersionId() + "\"");
        }
        String lastModified = httpDate(info.getLastUpdated());
        if (lastModified != null) {
            response.setHeader("Last-Modified", lastModified);
        }

        for (Map.Entry<String, List<String>> header : responseHeaders(dispatchResult).entrySet()) {
            List<String> values = header.getValue();
            if (values == null) {
                continue;
            }
            boolean first = true;
            for (String value : values) {
                if (first) {
                    response.setHeader(header.getKey(), value);
                    first = false;
                } else {
                    response.addHeader(header.getKey(), value);
                }
            }
        }

        if (content == null) {
            // A 200 with no body is not wrong, but it is rarely what was meant; 204 says
            // "there is deliberately nothing here" and is what the spec uses for a delete.
            if (statusCode == 200) {
                response.setStatus(204);
            }
            response.setContentLength(0);
            return;
        }

        writeBody(response, bodyFormat == null ? format : bodyFormat, content);
    }

    private int statusCode(FhirRoute route, Status status, String content, DispatchResult dispatchResult) {
        String configured = getConnectorProperties().getResponseStatusCode();
        if (configured != null && !configured.trim().isEmpty()) {
            String resolved = replaceValues(configured, dispatchResult);
            int code = NumberUtils.toInt(resolved == null ? "" : resolved.trim(), 0);
            if (code >= 100 && code <= 599) {
                return code;
            }
            logger.warn("FHIR Listener on channel {}: response status code '{}' is not a valid HTTP status, "
                    + "falling back to the interaction default", getChannelId(), resolved);
        }

        if (status == Status.ERROR) {
            return 500;
        }
        if (status == Status.QUEUED) {
            // The message is accepted and will be processed, but the destination has not
            // run yet, so there is no resource to return.
            return 202;
        }

        FhirInteraction interaction = route.getInteraction();
        if (interaction == FhirInteraction.CREATE) {
            return 201;
        }
        if (interaction == FhirInteraction.DELETE && (content == null || content.trim().isEmpty())) {
            return 204;
        }
        return 200;
    }

    /**
     * The {@code Location} of a resource a create just made, per R4/R5 §3.1.0.1.1.
     *
     * <p>Only sent when the channel returned a resource that names its own id, which is the
     * only way this connector can know what was created. A create whose channel returns an
     * empty body gets no Location -- an invented one would be worse than none.
     */
    private String location(FhirRoute route, FhirPayload.Info info, String baseUrl) {
        if (route.getInteraction() != FhirInteraction.CREATE && route.getInteraction() != FhirInteraction.UPDATE) {
            return null;
        }
        if (!info.isResource() || info.getId() == null) {
            return null;
        }
        StringBuilder location = new StringBuilder(baseUrl)
                .append('/').append(info.getResourceType())
                .append('/').append(info.getId());
        if (info.getVersionId() != null) {
            location.append("/_history/").append(info.getVersionId());
        }
        return location.toString();
    }

    /**
     * Response headers configured on the connector, or read out of a map variable the
     * channel populated. The variable form is how a channel returns headers it computed.
     */
    private Map<String, List<String>> responseHeaders(DispatchResult dispatchResult) {
        FhirReceiverProperties props = getConnectorProperties();
        if (!props.isUseResponseHeadersVariable()) {
            Map<String, List<String>> resolved = new LinkedHashMap<String, List<String>>();
            for (Map.Entry<String, List<String>> entry : props.getResponseHeaders().entrySet()) {
                List<String> values = new ArrayList<String>();
                if (entry.getValue() != null) {
                    for (String value : entry.getValue()) {
                        values.add(replaceValues(value, dispatchResult));
                    }
                }
                resolved.put(replaceValues(entry.getKey(), dispatchResult), values);
            }
            return resolved;
        }

        String variable = props.getResponseHeadersVariable();
        if (variable == null || variable.trim().isEmpty() || dispatchResult == null
                || dispatchResult.getProcessedMessage() == null) {
            return Collections.emptyMap();
        }

        ConnectorMessage merged = dispatchResult.getProcessedMessage().getMergedConnectorMessage();
        if (merged == null) {
            return Collections.emptyMap();
        }
        Object value = lookup(merged, variable.trim());
        if (!(value instanceof Map)) {
            return Collections.emptyMap();
        }

        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
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
            headers.put(String.valueOf(entry.getKey()), values);
        }
        return headers;
    }

    /**
     * Looks a variable up across the maps a channel can write to, in the order a channel
     * author would expect: the response map first, since a response header is most often
     * set alongside the response itself.
     */
    private Object lookup(ConnectorMessage message, String key) {
        Map<String, Object> responseMap = message.getResponseMap();
        if (responseMap != null && responseMap.containsKey(key)) {
            return responseMap.get(key);
        }
        Map<String, Object> channelMap = message.getChannelMap();
        if (channelMap != null && channelMap.containsKey(key)) {
            return channelMap.get(key);
        }
        Map<String, Object> connectorMap = message.getConnectorMap();
        if (connectorMap != null && connectorMap.containsKey(key)) {
            return connectorMap.get(key);
        }
        Map<String, Object> sourceMap = message.getSourceMap();
        return sourceMap == null ? null : sourceMap.get(key);
    }

    // ------------------------------------------------------------------
    // The message handed to the channel
    // ------------------------------------------------------------------

    /**
     * Everything the connector parsed, put where a transformer can read it.
     *
     * <p>This is what makes "Plain Body" mode workable: the channel gets the resource as
     * its raw message and finds the interaction, resource type, id and search parameters in
     * the source map, rather than having to re-parse a URL it was never given.
     */
    private Map<String, Object> buildSourceMap(HttpServletRequest request, FhirRoute route, FhirFormat format,
            Map<String, List<String>> parameters, Map<String, List<String>> headers, String baseUrl) {

        Map<String, Object> sourceMap = new LinkedHashMap<String, Object>();

        sourceMap.put("fhirVersion", version.getId());
        sourceMap.put("fhirInteraction", route.getInteraction().getCode());
        putIfPresent(sourceMap, "fhirResourceType", route.getResourceType());
        putIfPresent(sourceMap, "fhirResourceId", route.getResourceId());
        putIfPresent(sourceMap, "fhirVersionId", route.getVersionId());
        putIfPresent(sourceMap, "fhirOperation", route.getOperation());
        putIfPresent(sourceMap, "fhirCompartmentType", route.getCompartmentType());
        putIfPresent(sourceMap, "fhirCompartmentId", route.getCompartmentId());
        sourceMap.put("fhirBaseUrl", baseUrl);
        sourceMap.put("fhirFormat", format.getCode());
        sourceMap.put("fhirBasePath", basePath);

        // Conditional-interaction headers, lifted out of the header map because a channel
        // implementing conditional create or optimistic locking needs them by name.
        putIfPresent(sourceMap, "fhirIfMatch", request.getHeader("If-Match"));
        putIfPresent(sourceMap, "fhirIfNoneMatch", request.getHeader("If-None-Match"));
        putIfPresent(sourceMap, "fhirIfNoneExist", request.getHeader("If-None-Exist"));
        putIfPresent(sourceMap, "fhirIfModifiedSince", request.getHeader("If-Modified-Since"));
        putIfPresent(sourceMap, "fhirPrefer", request.getHeader("Prefer"));

        sourceMap.put("method", FhirInteraction.normaliseMethod(request.getMethod()));
        sourceMap.put("url", requestUrl(request));
        sourceMap.put("parameters", parameters);
        sourceMap.put("headers", headers);
        sourceMap.put("contextPath", basePath);
        sourceMap.put("remoteAddress", request.getRemoteAddr());

        /*
         * What the transport can say about who is calling. With client certificates this is
         * the only identity the request carries, and a channel that authorises on it needs
         * the subject by name rather than having to dig through servlet attributes itself.
         */
        sourceMap.put("fhirSecure", Boolean.valueOf(request.isSecure()));
        if (request.isSecure()) {
            putIfPresent(sourceMap, "fhirCipherSuite",
                    (String) request.getAttribute("javax.servlet.request.cipher_suite"));
            Object certificates = request.getAttribute("javax.servlet.request.X509Certificate");
            if (certificates instanceof X509Certificate[] && ((X509Certificate[]) certificates).length > 0) {
                X509Certificate client = ((X509Certificate[]) certificates)[0];
                putIfPresent(sourceMap, "fhirClientCertSubject", client.getSubjectX500Principal().getName());
                putIfPresent(sourceMap, "fhirClientCertIssuer", client.getIssuerX500Principal().getName());
                putIfPresent(sourceMap, "fhirClientCertSerial", client.getSerialNumber().toString(16));
                putIfPresent(sourceMap, "fhirClientCertNotAfter", client.getNotAfter().toInstant().toString());
            }
        }
        sourceMap.put("remotePort", request.getRemotePort());
        sourceMap.put("localAddress", request.getLocalAddr());
        sourceMap.put("localPort", request.getLocalPort());
        sourceMap.put("protocol", request.getProtocol());

        return sourceMap;
    }

    /**
     * The "Request Envelope" form of the message: one JSON object carrying the request line,
     * the parsed FHIR coordinates, the parameters, the headers and the body.
     *
     * <p>Useful when the channel's logic is mostly routing -- a JavaScript transformer can
     * read one object instead of reaching into the source map -- and when a channel needs
     * to store the whole request as it arrived.
     */
    private String envelope(HttpServletRequest request, FhirRoute route, FhirFormat format, String body,
            Map<String, List<String>> parameters, Map<String, List<String>> headers, String baseUrl) {

        ObjectNode root = mapper.createObjectNode();
        root.put("fhirVersion", version.getId());
        root.put("interaction", route.getInteraction().getCode());
        root.put("method", FhirInteraction.normaliseMethod(request.getMethod()));
        root.put("url", requestUrl(request));
        root.put("baseUrl", baseUrl);
        root.put("format", format.getCode());
        putOrNull(root, "resourceType", route.getResourceType());
        putOrNull(root, "id", route.getResourceId());
        putOrNull(root, "versionId", route.getVersionId());
        putOrNull(root, "operation", route.getOperation());
        putOrNull(root, "compartmentType", route.getCompartmentType());
        putOrNull(root, "compartmentId", route.getCompartmentId());

        root.set("parameters", toJson(parameters));
        root.set("headers", toJson(headers));

        // The body stays a string rather than being inlined as JSON. It may be XML, it may
        // be malformed, and either way the channel must see exactly the bytes that arrived
        // -- re-serialising a parsed copy would quietly rewrite it.
        root.put("body", body);

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            logger.error("FHIR Listener on channel {} could not build a request envelope", getChannelId(), e);
            return body;
        }
    }

    private ObjectNode toJson(Map<String, List<String>> values) {
        ObjectNode node = mapper.createObjectNode();
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            ArrayNode array = node.putArray(entry.getKey());
            if (entry.getValue() != null) {
                for (String value : entry.getValue()) {
                    array.add(value);
                }
            }
        }
        return node;
    }

    // ------------------------------------------------------------------
    // CapabilityStatement
    // ------------------------------------------------------------------

    private void writeCapabilityStatement(HttpServletResponse response, FhirFormat format, String baseUrl,
            FhirReceiverProperties props) throws IOException {

        String mode = props.getCapabilityMode();

        if (FhirReceiverProperties.CAPABILITY_DISABLED.equals(mode)) {
            writeOutcome(response, 404, format, FhirDocuments.SEVERITY_ERROR, FhirDocuments.ISSUE_NOT_SUPPORTED,
                    "This endpoint does not publish a CapabilityStatement");
            return;
        }

        if (FhirReceiverProperties.CAPABILITY_CUSTOM.equals(mode)) {
            String custom = props.getCapabilityStatement();
            if (custom == null || custom.trim().isEmpty()) {
                writeOutcome(response, 500, format, FhirDocuments.SEVERITY_ERROR, FhirDocuments.ISSUE_EXCEPTION,
                        "This endpoint is configured to serve a custom CapabilityStatement but none is set");
                return;
            }
            String resolved = replacer.replaceValues(custom, getChannelId(),
                    getChannel() == null ? getChannelId() : getChannel().getName());
            FhirFormat customFormat = FhirFormat.sniff(resolved);
            response.setStatus(200);
            writeBody(response, customFormat == null ? format : customFormat, resolved);
            return;
        }

        String channelName = getChannel() == null ? getChannelId() : getChannel().getName();
        String statement = FhirDocuments.capabilityStatement(format, version, SOFTWARE_NAME,
                configurationController.getServerVersion(), channelName, baseUrl, enabledInteractions,
                allowedResourceTypes);
        response.setStatus(200);
        writeBody(response, format, statement);
    }

    // ------------------------------------------------------------------
    // HTTP plumbing
    // ------------------------------------------------------------------

    /** True when the request URI falls under the configured base path. */
    private boolean withinBase(String uri) {
        if (basePath.isEmpty()) {
            return true;
        }
        if (!uri.startsWith(basePath)) {
            return false;
        }
        // "/fhirsomething" must not match a base path of "/fhir".
        return uri.length() == basePath.length() || uri.charAt(basePath.length()) == '/';
    }

    private boolean resourceTypeAllowed(String resourceType) {
        if (resourceType == null || allowedResourceTypes.isEmpty() || resourceType.equals("*")) {
            return true;
        }
        return allowedResourceTypes.contains(resourceType);
    }

    /**
     * The service base URL to advertise.
     *
     * <p>Behind the TLS proxy this deployment uses, the request Jetty sees is plain HTTP on
     * an internal port, so building the URL from the socket would hand clients an address
     * they cannot reach. The forwarding headers are believed when present -- which is safe
     * here because they only affect an advertised URL, never a routing or access decision
     * -- and the override property exists for deployments that would rather state it
     * outright than trust a header.
     */
    /* ------------------------------------------------------------------ */
    /* TLS                                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * Resolves the TLS settings and proves them, at deploy, before anything is listening.
     *
     * <p>Everything here could be left to Jetty at start-up, and it would fail then -- with
     * a {@code java.io.IOException: keystore password was incorrect} four frames deep in a
     * connector lifecycle exception, on a channel that looks like it merely failed to
     * start. Reading the store here turns each of those into a sentence naming the file,
     * the setting and what was wrong with it.
     */
    private void resolveTls(FhirReceiverProperties props, String channelName) throws ConnectorTaskException {
        useSsl = props.isUseSsl();
        if (!useSsl) {
            return;
        }

        keyStorePassword = replacer.replaceValues(props.getKeyStorePassword(), getChannelId(), channelName);
        keyStoreType = defaultIfBlank(
                replacer.replaceValues(props.getKeyStoreType(), getChannelId(), channelName), "PKCS12");
        keyAlias = trimToEmpty(replacer.replaceValues(props.getKeyAlias(), getChannelId(), channelName));
        keyPassword = replacer.replaceValues(props.getKeyPassword(), getChannelId(), channelName);
        clientAuth = defaultIfBlank(
                replacer.replaceValues(props.getClientAuth(), getChannelId(), channelName),
                FhirReceiverProperties.CLIENT_AUTH_NONE).toUpperCase(Locale.US);
        trustStorePassword = replacer.replaceValues(props.getTrustStorePassword(), getChannelId(), channelName);
        trustStoreType = defaultIfBlank(
                replacer.replaceValues(props.getTrustStoreType(), getChannelId(), channelName), "PKCS12");
        protocols = splitList(replacer.replaceValues(props.getProtocols(), getChannelId(), channelName));
        cipherSuites = splitList(replacer.replaceValues(props.getCipherSuites(), getChannelId(), channelName));

        String keyStoreFile = trimToEmpty(
                replacer.replaceValues(props.getKeyStoreFile(), getChannelId(), channelName));
        if (keyStoreFile.isEmpty()) {
            throw new ConnectorTaskException("TLS is enabled but no key store file is configured, so the"
                    + " listener has no certificate to present.");
        }
        keyStorePath = resolveLocal(keyStoreFile);
        keyAlias = validateKeyStore();

        String trustStoreFile = trimToEmpty(
                replacer.replaceValues(props.getTrustStoreFile(), getChannelId(), channelName));
        trustStorePath = trustStoreFile.isEmpty() ? null : resolveLocal(trustStoreFile);

        boolean wantsClientCertificates = !FhirReceiverProperties.CLIENT_AUTH_NONE.equals(clientAuth);
        if (wantsClientCertificates && trustStorePath == null) {
            /*
             * Without a trust store the JDK falls back to its own CA bundle, so "client
             * certificate required" would be satisfied by a certificate from any public CA
             * on earth. That is not the check anyone thinks they are configuring.
             */
            throw new ConnectorTaskException("Client authentication is set to " + clientAuth
                    + " but no trust store is configured. Without one, any certificate signed by a public"
                    + " CA would be accepted.");
        }
        if (trustStorePath != null) {
            validateStoreReadable(trustStorePath, trustStoreType, trustStorePassword, "Trust store");
        }
    }

    /** @return the alias actually used, which is the configured one or the store's only key */
    private String validateKeyStore() throws ConnectorTaskException {
        KeyStore store = validateStoreReadable(keyStorePath, keyStoreType, keyStorePassword, "Key store");

        try {
            if (!keyAlias.isEmpty()) {
                if (!store.isKeyEntry(keyAlias)) {
                    throw new ConnectorTaskException("Key store " + keyStorePath + " has no private key under"
                            + " alias \"" + keyAlias + "\". It holds: " + describeAliases(store));
                }
            } else {
                String only = null;
                int keys = 0;
                for (Enumeration<String> aliases = store.aliases(); aliases.hasMoreElements();) {
                    String alias = aliases.nextElement();
                    if (store.isKeyEntry(alias)) {
                        keys++;
                        only = alias;
                    }
                }
                if (keys == 0) {
                    throw new ConnectorTaskException("Key store " + keyStorePath + " holds no private key, so"
                            + " the listener has no certificate to present. It holds: " + describeAliases(store));
                }
                if (keys > 1) {
                    // Which one the JDK would pick is not defined; say so rather than
                    // serving whichever certificate happened to sort first.
                    throw new ConnectorTaskException("Key store " + keyStorePath + " holds " + keys
                            + " private keys, so Key Alias must say which certificate to present. It holds: "
                            + describeAliases(store));
                }
                keyAlias = only;
            }

            Certificate certificate = store.getCertificate(keyAlias);
            if (certificate instanceof X509Certificate) {
                describeCertificate((X509Certificate) certificate);
            }
            return keyAlias;
        } catch (ConnectorTaskException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectorTaskException("Could not read key store " + keyStorePath + ": " + e.getMessage(), e);
        }
    }

    private KeyStore validateStoreReadable(Path path, String type, String password, String what)
            throws ConnectorTaskException {
        try {
            KeyStore store = KeyStore.getInstance(type);
            try (InputStream in = Files.newInputStream(path)) {
                store.load(in, password == null ? null : password.toCharArray());
            }
            return store;
        } catch (NoSuchFileException e) {
            throw new ConnectorTaskException(what + " not found: " + path, e);
        } catch (IOException e) {
            // The JDK reports a wrong password as an IOException whose cause is an
            // UnrecoverableKeyException, which is worth translating.
            String reason = e.getCause() != null && e.getCause().getMessage() != null
                    ? e.getCause().getMessage() : e.getMessage();
            throw new ConnectorTaskException(what + " " + path + " could not be read: " + reason
                    + " (check the password and that the file really is a " + type + " store).", e);
        } catch (Exception e) {
            throw new ConnectorTaskException(what + " " + path + " could not be read: " + e.getMessage(), e);
        }
    }

    /** Says what is being served and when it stops being valid, because nobody diaries this. */
    private void describeCertificate(X509Certificate certificate) {
        Instant expiry = certificate.getNotAfter().toInstant();
        long days = ChronoUnit.DAYS.between(Instant.now(), expiry);
        String subject = certificate.getSubjectX500Principal().getName();

        if (days < 0) {
            logger.warn("FHIR Listener on channel {}: the certificate for {} EXPIRED on {}. Clients will"
                    + " refuse the connection.", getChannelId(), subject, expiry);
        } else if (days <= 30) {
            logger.warn("FHIR Listener on channel {}: the certificate for {} expires on {}, in {} day(s).",
                    getChannelId(), subject, expiry, days);
        } else {
            logger.info("FHIR Listener on channel {}: serving {} (issued by {}), valid until {}.",
                    getChannelId(), subject, certificate.getIssuerX500Principal().getName(), expiry);
        }
    }

    private static String describeAliases(KeyStore store) {
        try {
            StringBuilder out = new StringBuilder();
            for (Enumeration<String> aliases = store.aliases(); aliases.hasMoreElements();) {
                String alias = aliases.nextElement();
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(alias).append(store.isKeyEntry(alias) ? " (key)" : " (certificate only)");
            }
            return out.length() == 0 ? "nothing" : out.toString();
        } catch (Exception e) {
            return "(could not be listed: " + e.getMessage() + ")";
        }
    }

    /** The TLS connector, built from settings already proved in {@link #resolveTls}. */
    private ServerConnector tlsConnector(Server jetty) {
        SslContextFactory.Server ssl = new SslContextFactory.Server();
        ssl.setKeyStorePath(keyStorePath.toString());
        ssl.setKeyStorePassword(keyStorePassword);
        ssl.setKeyStoreType(keyStoreType);
        if (keyAlias != null && !keyAlias.isEmpty()) {
            ssl.setCertAlias(keyAlias);
        }
        if (keyPassword != null && !keyPassword.isEmpty()) {
            ssl.setKeyManagerPassword(keyPassword);
        }
        if (trustStorePath != null) {
            ssl.setTrustStorePath(trustStorePath.toString());
            ssl.setTrustStorePassword(trustStorePassword);
            ssl.setTrustStoreType(trustStoreType);
        }
        if (FhirReceiverProperties.CLIENT_AUTH_NEED.equals(clientAuth)) {
            ssl.setNeedClientAuth(true);
        } else if (FhirReceiverProperties.CLIENT_AUTH_WANT.equals(clientAuth)) {
            ssl.setWantClientAuth(true);
        }
        if (protocols.length > 0) {
            ssl.setIncludeProtocols(protocols);
        }
        if (cipherSuites.length > 0) {
            ssl.setIncludeCipherSuites(cipherSuites);
        }
        // Renegotiation has a history of protocol-level problems and no FHIR client needs
        // it; refusing it is one fewer thing to reason about.
        ssl.setRenegotiationAllowed(false);

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        // Without this the request never learns it was secure: getScheme() stays http, the
        // client certificate is not exposed, and every advertised base URL comes out wrong.
        httpConfiguration.addCustomizer(new SecureRequestCustomizer());
        httpConfiguration.setSendServerVersion(false);

        return new ServerConnector(jetty,
                new SslConnectionFactory(ssl, HttpVersion.HTTP_1_1.asString()),
                new HttpConnectionFactory(httpConfiguration));
    }

    /**
     * A configured local path. A relative one lands under the engine's application data
     * directory -- the volume this stack persists -- so a certificate is not lost the next
     * time the container is rebuilt.
     */
    private Path resolveLocal(String configured) {
        Path candidate = Paths.get(configured);
        return (candidate.isAbsolute()
                ? candidate
                : Paths.get(configurationController.getApplicationDataDir()).resolve(candidate)).normalize();
    }

    private static String[] splitList(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = value.split(",");
        List<String> out = new ArrayList<String>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.toArray(new String[0]);
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String baseUrl(HttpServletRequest request) {
        if (baseUrlOverride != null && !baseUrlOverride.trim().isEmpty()) {
            String url = baseUrlOverride.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            return url;
        }

        String scheme = header(request, "X-Forwarded-Proto", request.getScheme());
        String authority = header(request, "X-Forwarded-Host", null);
        if (authority == null) {
            int serverPort = request.getServerPort();
            boolean defaultPort = ("http".equals(scheme) && serverPort == 80)
                    || ("https".equals(scheme) && serverPort == 443);
            authority = defaultPort ? request.getServerName() : request.getServerName() + ":" + serverPort;
        }
        String prefix = header(request, "X-Forwarded-Prefix", "");
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return scheme + "://" + authority + prefix + basePath;
    }

    private static String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        // A forwarding header may list every proxy in the chain; the first is the client's.
        int comma = value.indexOf(',');
        return (comma < 0 ? value : value.substring(0, comma)).trim();
    }

    private String requestUrl(HttpServletRequest request) {
        String query = request.getQueryString();
        StringBuffer url = request.getRequestURL();
        return query == null || query.isEmpty() ? url.toString() : url.append('?').append(query).toString();
    }

    /**
     * Detects a client asking for a FHIR release this endpoint does not serve.
     *
     * @return the diagnostics for a 406, or null when there is no conflict
     */
    private String versionMismatch(HttpServletRequest request) {
        String accepted = FhirFormat.versionParameter(request.getHeader("Accept"));
        if (accepted != null && FhirVersion.fromMimeParameter(accepted) != version) {
            return "This endpoint serves FHIR " + version.getReleaseVersion()
                    + "; the Accept header asked for " + accepted;
        }
        String sent = FhirFormat.versionParameter(request.getContentType());
        if (sent != null && FhirVersion.fromMimeParameter(sent) != version) {
            return "This endpoint serves FHIR " + version.getReleaseVersion()
                    + "; the request body declared " + sent;
        }
        return null;
    }

    private Charset requestCharset(HttpServletRequest request) {
        String encoding = request.getCharacterEncoding();
        if (encoding != null && !encoding.isEmpty()) {
            try {
                return Charset.forName(encoding);
            } catch (Exception e) {
                logger.debug("FHIR Listener on channel {}: unusable request charset '{}'", getChannelId(), encoding);
            }
        }
        return charset;
    }

    private boolean isFormEncoded(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    private byte[] readBody(HttpServletRequest request) throws IOException, RequestTooLargeException {
        InputStream in = request.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (maxRequestSize > 0 && total > maxRequestSize) {
                throw new RequestTooLargeException();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Parses a query string into a multi-valued map.
     *
     * <p>Deliberately not {@code request.getParameterMap()}: for a form-encoded POST that
     * call consumes the request body to merge form fields in, which would leave nothing for
     * the channel to receive. A FHIR {@code _search} POST is exactly that shape.
     */
    private static Map<String, List<String>> parseQueryString(String query) {
        Map<String, List<String>> parameters = new LinkedHashMap<String, List<String>>();
        if (query == null || query.isEmpty()) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = urlDecode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : urlDecode(pair.substring(equals + 1));
            List<String> values = parameters.get(name);
            if (values == null) {
                values = new ArrayList<String>();
                parameters.put(name, values);
            }
            values.add(value);
        }
        return parameters;
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value;
        } catch (IllegalArgumentException e) {
            // Malformed percent-encoding: hand the channel what was actually sent rather
            // than failing a request that may not even use the parameter.
            return value;
        }
    }

    private static void merge(Map<String, List<String>> target, Map<String, List<String>> extra) {
        for (Map.Entry<String, List<String>> entry : extra.entrySet()) {
            List<String> values = target.get(entry.getKey());
            if (values == null) {
                target.put(entry.getKey(), entry.getValue());
            } else {
                values.addAll(entry.getValue());
            }
        }
    }

    private static Map<String, List<String>> extractHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            List<String> values = new ArrayList<String>();
            Enumeration<String> headerValues = request.getHeaders(name);
            while (headerValues != null && headerValues.hasMoreElements()) {
                values.add(headerValues.nextElement());
            }
            headers.put(name, values);
        }
        return headers;
    }

    /** The {@code return=} part of a Prefer header, lowercased, or null. */
    private static String preferReturn(HttpServletRequest request) {
        String prefer = request.getHeader("Prefer");
        if (prefer == null) {
            return null;
        }
        for (String part : prefer.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("return=")) {
                return trimmed.substring("return=".length()).trim();
            }
        }
        return null;
    }

    /**
     * Formats a FHIR {@code instant} as an HTTP date, or returns null if it is not one.
     * {@code Last-Modified} has a fixed grammar (RFC 7231 §7.1.1.1); a value that does not
     * fit it is better omitted than sent malformed.
     */
    private static String httpDate(String fhirInstant) {
        if (fhirInstant == null || fhirInstant.isEmpty()) {
            return null;
        }
        try {
            return HTTP_DATE.format(ZonedDateTime.parse(fhirInstant).toInstant());
        } catch (Exception e) {
            try {
                return HTTP_DATE.format(Instant.parse(fhirInstant));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private String replaceValues(String template, DispatchResult dispatchResult) {
        if (template == null) {
            return null;
        }
        ConnectorMessage merged = null;
        if (dispatchResult != null && dispatchResult.getProcessedMessage() != null) {
            merged = dispatchResult.getProcessedMessage().getMergedConnectorMessage();
        }
        if (merged != null) {
            return replacer.replaceValues(template, merged);
        }
        return replacer.replaceValues(template, getChannelId(),
                getChannel() == null ? getChannelId() : getChannel().getName());
    }

    private void writeOutcome(HttpServletResponse response, int statusCode, FhirFormat format, String severity,
            String issueCode, String diagnostics) throws IOException {
        response.setStatus(statusCode);
        writeBody(response, format, FhirDocuments.operationOutcome(format, severity, issueCode, diagnostics));
    }

    /** As {@link #writeOutcome}, but for the last-ditch handler where throwing is not an option. */
    private void safeWriteOutcome(HttpServletResponse response, int statusCode, FhirFormat format, String severity,
            String issueCode, String diagnostics) {
        try {
            if (response.isCommitted()) {
                return;
            }
            writeOutcome(response, statusCode, format, severity, issueCode, diagnostics);
        } catch (Exception e) {
            logger.debug("FHIR Listener on channel {} could not write an error response", getChannelId(), e);
        }
    }

    private void writeBody(HttpServletResponse response, FhirFormat format, String content) throws IOException {
        // The charset is left to setCharacterEncoding rather than written into the media
        // type here: the servlet container appends it to the Content-Type itself, and
        // supplying it twice produces a header with two charset parameters.
        response.setContentType(format.contentType(version, null));
        response.setCharacterEncoding(charset.name());
        byte[] bytes = content.getBytes(charset);
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    /** The first value of a multi-valued parameter, or null when it was not sent. */
    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }

    private static void putOrNull(ObjectNode node, String key, String value) {
        if (value == null || value.isEmpty()) {
            node.putNull(key);
        } else {
            node.put(key, value);
        }
    }

    /** Signals that a request body went past the configured ceiling. */
    private static final class RequestTooLargeException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    /**
     * Replaces Jetty's HTML error pages with FHIR ones.
     *
     * <p>Reached only for failures Jetty handles before the handler runs -- a malformed
     * request line, a URI Jetty itself rejects. Rare, but an HTML page from a FHIR endpoint
     * is exactly the kind of thing that sends an integrator down the wrong path for an hour.
     */
    private class FhirErrorHandler extends org.eclipse.jetty.server.handler.ErrorHandler {

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request,
                HttpServletResponse response) throws IOException {
            baseRequest.setHandled(true);
            int status = response.getStatus() >= 400 ? response.getStatus() : 500;
            String outcome = FhirDocuments.operationOutcome(defaultFormat, FhirDocuments.SEVERITY_ERROR,
                    status == 404 ? FhirDocuments.ISSUE_NOT_FOUND : FhirDocuments.ISSUE_PROCESSING,
                    "The request could not be processed");
            response.setStatus(status);
            writeBody(response, defaultFormat, outcome);
        }
    }
}
