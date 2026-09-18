/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.openintegrationengine.connectors.fhir.FhirFormat;
import org.openintegrationengine.connectors.fhir.FhirInteraction;
import org.openintegrationengine.connectors.fhir.FhirVersion;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Set;

/**
 * The two FHIR resources this connector generates on its own: OperationOutcome, for every
 * error it answers itself, and CapabilityStatement, for {@code GET [base]/metadata}.
 *
 * <p>Both are written by hand rather than through a FHIR model library. They are small,
 * fully specified, and -- the reason this works across releases -- structurally identical
 * in R4 and R5 for every element used here. The only release-dependent value is
 * {@code CapabilityStatement.fhirVersion}, which comes from {@link FhirVersion}.
 *
 * <p>The JSON is built with Jackson so that escaping is never this file's problem; the XML
 * is built with a StringBuilder over an explicit escaper, because FHIR XML puts every
 * primitive in a {@code value} attribute and so needs only attribute escaping.
 */
public final class FhirDocuments {

    /** {@code OperationOutcome.issue.severity} codes (R4/R5 {@code IssueSeverity}). */
    public static final String SEVERITY_FATAL = "fatal";
    public static final String SEVERITY_ERROR = "error";
    public static final String SEVERITY_WARNING = "warning";
    public static final String SEVERITY_INFORMATION = "information";

    /*
     * OperationOutcome.issue.code values (IssueType). Only codes present in both R4 and R5
     * are exposed, so one connector can answer for either release without a per-version
     * value set.
     */
    public static final String ISSUE_INVALID = "invalid";
    public static final String ISSUE_STRUCTURE = "structure";
    public static final String ISSUE_SECURITY = "security";
    public static final String ISSUE_FORBIDDEN = "forbidden";
    public static final String ISSUE_PROCESSING = "processing";
    public static final String ISSUE_NOT_SUPPORTED = "not-supported";
    public static final String ISSUE_NOT_FOUND = "not-found";
    public static final String ISSUE_CONFLICT = "conflict";
    public static final String ISSUE_TOO_LONG = "too-long";
    public static final String ISSUE_TIMEOUT = "timeout";
    public static final String ISSUE_TRANSIENT = "transient";
    public static final String ISSUE_EXCEPTION = "exception";
    public static final String ISSUE_INFORMATIONAL = "informational";

    private static final String FHIR_NAMESPACE = "http://hl7.org/fhir";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FhirDocuments() {
    }

    /**
     * Builds an OperationOutcome with a single issue.
     *
     * <p>{@code diagnostics} is free text meant for a human debugging a request. It carries
     * whatever the connector knows about the failure, which is why the receiver is careful
     * about what it passes: a stack trace from a channel is useful on an internal facade
     * and an information leak on an external one.
     */
    public static String operationOutcome(FhirFormat format, String severity, String code, String diagnostics) {
        if (format == FhirFormat.XML) {
            StringBuilder xml = new StringBuilder();
            xml.append("<OperationOutcome xmlns=\"").append(FHIR_NAMESPACE).append("\">");
            xml.append("<issue>");
            xml.append("<severity value=\"").append(escapeXml(severity)).append("\"/>");
            xml.append("<code value=\"").append(escapeXml(code)).append("\"/>");
            if (diagnostics != null && !diagnostics.isEmpty()) {
                xml.append("<diagnostics value=\"").append(escapeXml(diagnostics)).append("\"/>");
            }
            xml.append("</issue>");
            xml.append("</OperationOutcome>");
            return xml.toString();
        }

        ObjectNode outcome = MAPPER.createObjectNode();
        outcome.put("resourceType", "OperationOutcome");
        ObjectNode issue = outcome.putArray("issue").addObject();
        issue.put("severity", severity);
        issue.put("code", code);
        if (diagnostics != null && !diagnostics.isEmpty()) {
            issue.put("diagnostics", diagnostics);
        }
        return write(outcome);
    }

    /**
     * Builds the CapabilityStatement for a facade.
     *
     * <p>What can honestly be advertised is limited by what the connector knows. It knows
     * which interactions are enabled and, if the administrator listed them, which resource
     * types are served -- so those are declared. It cannot know the search parameters or
     * the operations a channel implements, because those live in the channel's transformer,
     * so none are claimed. An empty {@code rest.resource} list is legal, and honest.
     *
     * @param resourceTypes the configured allowlist; when empty the facade accepts any
     *                      type, which no CapabilityStatement can enumerate
     */
    public static String capabilityStatement(FhirFormat format, FhirVersion version, String softwareName,
            String softwareVersion, String description, String baseUrl, Set<FhirInteraction> enabled,
            Collection<String> resourceTypes) {

        String now = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_INSTANT);

        if (format == FhirFormat.XML) {
            return capabilityStatementXml(version, softwareName, softwareVersion, description, baseUrl,
                    enabled, resourceTypes, now);
        }

        ObjectNode statement = MAPPER.createObjectNode();
        statement.put("resourceType", "CapabilityStatement");
        statement.put("status", "active");
        statement.put("date", now);
        // kind=instance is the accurate one: this describes a specific deployed endpoint,
        // not a reusable requirements document. It obliges us to include implementation.
        statement.put("kind", "instance");

        ObjectNode software = statement.putObject("software");
        software.put("name", softwareName);
        if (softwareVersion != null && !softwareVersion.isEmpty()) {
            software.put("version", softwareVersion);
        }

        ObjectNode implementation = statement.putObject("implementation");
        implementation.put("description", description);
        if (baseUrl != null && !baseUrl.isEmpty()) {
            implementation.put("url", baseUrl);
        }

        statement.put("fhirVersion", version.getReleaseVersion());
        ArrayNode formats = statement.putArray("format");
        formats.add(FhirFormat.JSON.getMimeType());
        formats.add(FhirFormat.XML.getMimeType());

        ObjectNode rest = statement.putArray("rest").addObject();
        rest.put("mode", "server");

        ArrayNode resources = MAPPER.createArrayNode();
        for (String type : resourceTypes) {
            ObjectNode resource = resources.addObject();
            resource.put("type", type);
            ArrayNode interactions = resource.putArray("interaction");
            for (FhirInteraction interaction : enabled) {
                if (interaction.getLevel() == FhirInteraction.Level.TYPE) {
                    interactions.addObject().put("code", interaction.getCode());
                }
            }
        }
        if (resources.size() > 0) {
            rest.set("resource", resources);
        }

        ArrayNode systemInteractions = MAPPER.createArrayNode();
        for (FhirInteraction interaction : enabled) {
            if (interaction.getLevel() == FhirInteraction.Level.SYSTEM) {
                systemInteractions.addObject().put("code", interaction.getCode());
            }
        }
        if (systemInteractions.size() > 0) {
            rest.set("interaction", systemInteractions);
        }

        return write(statement);
    }

    private static String capabilityStatementXml(FhirVersion version, String softwareName, String softwareVersion,
            String description, String baseUrl, Set<FhirInteraction> enabled, Collection<String> resourceTypes,
            String now) {

        StringBuilder xml = new StringBuilder();
        xml.append("<CapabilityStatement xmlns=\"").append(FHIR_NAMESPACE).append("\">");
        xml.append("<status value=\"active\"/>");
        xml.append("<date value=\"").append(escapeXml(now)).append("\"/>");
        xml.append("<kind value=\"instance\"/>");

        xml.append("<software>");
        xml.append("<name value=\"").append(escapeXml(softwareName)).append("\"/>");
        if (softwareVersion != null && !softwareVersion.isEmpty()) {
            xml.append("<version value=\"").append(escapeXml(softwareVersion)).append("\"/>");
        }
        xml.append("</software>");

        xml.append("<implementation>");
        xml.append("<description value=\"").append(escapeXml(description)).append("\"/>");
        if (baseUrl != null && !baseUrl.isEmpty()) {
            xml.append("<url value=\"").append(escapeXml(baseUrl)).append("\"/>");
        }
        xml.append("</implementation>");

        xml.append("<fhirVersion value=\"").append(escapeXml(version.getReleaseVersion())).append("\"/>");
        xml.append("<format value=\"").append(FhirFormat.JSON.getMimeType()).append("\"/>");
        xml.append("<format value=\"").append(FhirFormat.XML.getMimeType()).append("\"/>");

        xml.append("<rest>");
        xml.append("<mode value=\"server\"/>");
        for (String type : resourceTypes) {
            xml.append("<resource>");
            xml.append("<type value=\"").append(escapeXml(type)).append("\"/>");
            for (FhirInteraction interaction : enabled) {
                if (interaction.getLevel() == FhirInteraction.Level.TYPE) {
                    xml.append("<interaction><code value=\"").append(interaction.getCode()).append("\"/></interaction>");
                }
            }
            xml.append("</resource>");
        }
        for (FhirInteraction interaction : enabled) {
            if (interaction.getLevel() == FhirInteraction.Level.SYSTEM) {
                xml.append("<interaction><code value=\"").append(interaction.getCode()).append("\"/></interaction>");
            }
        }
        xml.append("</rest>");
        xml.append("</CapabilityStatement>");
        return xml.toString();
    }

    private static String write(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // Jackson cannot fail to serialise a tree it built itself; the fallback exists
            // so that no error path in this connector can end in an exception rather than
            // an HTTP response.
            return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"fatal\","
                    + "\"code\":\"exception\",\"diagnostics\":\"Unable to serialise response\"}]}";
        }
    }

    /**
     * Escapes for an XML attribute value. Ampersand first, or the escapes escape each other.
     */
    static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&apos;");
                    break;
                default:
                    // XML 1.0 forbids most control characters outright, even escaped, so
                    // they are dropped rather than encoded into an unparseable document.
                    if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
