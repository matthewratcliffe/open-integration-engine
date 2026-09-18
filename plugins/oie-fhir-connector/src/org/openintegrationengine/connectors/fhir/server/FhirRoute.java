/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir.server;

import org.openintegrationengine.connectors.fhir.FhirInteraction;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One parsed FHIR request line: which interaction the URL and method name, and the type,
 * id, version and operation they name.
 *
 * <p>This is the whole of the FHIR "RESTful API" URL grammar (R4/R5 §3.1.0.1), which is
 * small enough to parse exactly rather than approximately:
 *
 * <pre>
 *   [base]                                    search-system / transaction / batch / capabilities
 *   [base]/metadata                           capabilities
 *   [base]/_history                           history-system
 *   [base]/$op                                operation (system)
 *   [base]/[type]                             search-type / create
 *   [base]/[type]/_history                    history-type
 *   [base]/[type]/_search                     search-type (POSTed form)
 *   [base]/[type]/$op                         operation (type)
 *   [base]/[type]/[id]                        read / update / patch / delete
 *   [base]/[type]/[id]/_history               history-instance
 *   [base]/[type]/[id]/_history/[vid]         vread
 *   [base]/[type]/[id]/$op                    operation (instance)
 *   [base]/[type]/[id]/[compartment-type]     compartment search
 * </pre>
 *
 * <p>Parsing precisely is what lets the facade answer a malformed or unsupported request
 * itself, with the status code and OperationOutcome the spec calls for, instead of waking
 * a channel up for {@code GET /favicon.ico}. Each rejection carries the distinction that
 * matters to a client: 404 for a URL that names nothing, 405 for a URL that exists but not
 * with that method.
 */
public final class FhirRoute {

    private final FhirInteraction interaction;
    private final String resourceType;
    private final String resourceId;
    private final String versionId;
    private final String operation;
    private final String compartmentType;
    private final String compartmentId;
    private final int failureStatus;
    private final String failureReason;

    private FhirRoute(FhirInteraction interaction, String resourceType, String resourceId, String versionId,
            String operation, String compartmentType, String compartmentId, int failureStatus, String failureReason) {
        this.interaction = interaction;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.versionId = versionId;
        this.operation = operation;
        this.compartmentType = compartmentType;
        this.compartmentId = compartmentId;
        this.failureStatus = failureStatus;
        this.failureReason = failureReason;
    }

    private static FhirRoute of(FhirInteraction interaction) {
        return new FhirRoute(interaction, null, null, null, null, null, null, 0, null);
    }

    private static FhirRoute of(FhirInteraction interaction, String resourceType) {
        return new FhirRoute(interaction, resourceType, null, null, null, null, null, 0, null);
    }

    private static FhirRoute of(FhirInteraction interaction, String resourceType, String resourceId) {
        return new FhirRoute(interaction, resourceType, resourceId, null, null, null, null, 0, null);
    }

    private static FhirRoute notFound(String reason) {
        return new FhirRoute(null, null, null, null, null, null, null, 404, reason);
    }

    private static FhirRoute methodNotAllowed(String method) {
        return new FhirRoute(null, null, null, null, null, null, null, 405,
                method + " is not supported for this URL");
    }

    /** True when the URL and method named a FHIR interaction. */
    public boolean isRouted() {
        return interaction != null;
    }

    public FhirInteraction getInteraction() {
        return interaction;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getVersionId() {
        return versionId;
    }

    public String getOperation() {
        return operation;
    }

    public String getCompartmentType() {
        return compartmentType;
    }

    public String getCompartmentId() {
        return compartmentId;
    }

    /** HTTP status for an unrouted request: 404 or 405. */
    public int getFailureStatus() {
        return failureStatus;
    }

    public String getFailureReason() {
        return failureReason;
    }

    /**
     * Returns a copy of this route with the interaction replaced.
     *
     * <p>Used for exactly one case: {@code POST [base]} is a transaction or a batch
     * depending on {@code Bundle.type}, which cannot be known until the body has been read.
     */
    public FhirRoute withInteraction(FhirInteraction replacement) {
        return new FhirRoute(replacement, resourceType, resourceId, versionId, operation,
                compartmentType, compartmentId, failureStatus, failureReason);
    }

    /**
     * Parses the path below the service base.
     *
     * @param method the HTTP method; HEAD is treated as GET, since FHIR defines it as a
     *               body-less read or search and Jetty drops the body for us
     * @param path   the request path with the base path already removed
     */
    public static FhirRoute parse(String method, String path) {
        String verb = FhirInteraction.normaliseMethod(method);
        if (verb.equals("HEAD")) {
            verb = "GET";
        }
        List<String> segments = split(path);

        switch (segments.size()) {
            case 0:
                return parseSystem(verb);
            case 1:
                return parseOneSegment(verb, segments.get(0));
            case 2:
                return parseTwoSegments(verb, segments.get(0), segments.get(1));
            case 3:
                return parseThreeSegments(verb, segments.get(0), segments.get(1), segments.get(2));
            case 4:
                return parseFourSegments(verb, segments);
            default:
                return notFound("Unrecognised FHIR URL");
        }
    }

    private static FhirRoute parseSystem(String verb) {
        switch (verb) {
            case "GET":
                // [base]?params is system-wide search; with no parameters it is still a
                // search, just an unfiltered one.
                return of(FhirInteraction.SEARCH_SYSTEM);
            case "POST":
                // Refined to BATCH once the Bundle type has been read.
                return of(FhirInteraction.TRANSACTION);
            case "OPTIONS":
                return of(FhirInteraction.CAPABILITIES);
            default:
                return methodNotAllowed(verb);
        }
    }

    private static FhirRoute parseOneSegment(String verb, String first) {
        if (first.equals("metadata")) {
            return verb.equals("GET") ? of(FhirInteraction.CAPABILITIES) : methodNotAllowed(verb);
        }
        if (first.equals("_history")) {
            return verb.equals("GET") ? of(FhirInteraction.HISTORY_SYSTEM) : methodNotAllowed(verb);
        }
        if (first.equals("_search")) {
            return verb.equals("POST") ? of(FhirInteraction.SEARCH_SYSTEM) : methodNotAllowed(verb);
        }
        if (first.startsWith("$")) {
            if (verb.equals("GET") || verb.equals("POST")) {
                return operation(first, null, null);
            }
            return methodNotAllowed(verb);
        }
        if (!FhirInteraction.isResourceTypeName(first)) {
            return notFound("Unknown resource type or URL segment: " + first);
        }
        switch (verb) {
            case "GET":
                return of(FhirInteraction.SEARCH_TYPE, first);
            case "POST":
                return of(FhirInteraction.CREATE, first);
            // PUT, PATCH and DELETE against a type (rather than an instance) are the
            // conditional forms, which select their target with search parameters.
            case "PUT":
                return of(FhirInteraction.UPDATE, first);
            case "PATCH":
                return of(FhirInteraction.PATCH, first);
            case "DELETE":
                return of(FhirInteraction.DELETE, first);
            default:
                return methodNotAllowed(verb);
        }
    }

    private static FhirRoute parseTwoSegments(String verb, String type, String second) {
        if (!FhirInteraction.isResourceTypeName(type)) {
            return notFound("Unknown resource type: " + type);
        }
        if (second.equals("_history")) {
            return verb.equals("GET") ? of(FhirInteraction.HISTORY_TYPE, type) : methodNotAllowed(verb);
        }
        if (second.equals("_search")) {
            return verb.equals("POST") ? of(FhirInteraction.SEARCH_TYPE, type) : methodNotAllowed(verb);
        }
        if (second.startsWith("$")) {
            if (verb.equals("GET") || verb.equals("POST")) {
                return operation(second, type, null);
            }
            return methodNotAllowed(verb);
        }
        if (!FhirInteraction.isLogicalId(second)) {
            return notFound("Malformed resource id: " + second);
        }
        switch (verb) {
            case "GET":
                return of(FhirInteraction.READ, type, second);
            case "PUT":
                return of(FhirInteraction.UPDATE, type, second);
            case "PATCH":
                return of(FhirInteraction.PATCH, type, second);
            case "DELETE":
                return of(FhirInteraction.DELETE, type, second);
            default:
                return methodNotAllowed(verb);
        }
    }

    private static FhirRoute parseThreeSegments(String verb, String type, String id, String third) {
        if (!FhirInteraction.isResourceTypeName(type)) {
            return notFound("Unknown resource type: " + type);
        }
        if (!FhirInteraction.isLogicalId(id)) {
            return notFound("Malformed resource id: " + id);
        }
        if (third.equals("_history")) {
            return verb.equals("GET")
                    ? of(FhirInteraction.HISTORY_INSTANCE, type, id)
                    : methodNotAllowed(verb);
        }
        if (third.startsWith("$")) {
            if (verb.equals("GET") || verb.equals("POST")) {
                return operation(third, type, id);
            }
            return methodNotAllowed(verb);
        }
        // [base]/Patient/123/Observation -- everything in a compartment, optionally "*"
        // for every type in it.
        if (third.equals("*") || FhirInteraction.isResourceTypeName(third)) {
            if (!verb.equals("GET")) {
                return methodNotAllowed(verb);
            }
            return new FhirRoute(FhirInteraction.SEARCH_COMPARTMENT, third, null, null, null, type, id, 0, null);
        }
        return notFound("Unrecognised FHIR URL segment: " + third);
    }

    private static FhirRoute parseFourSegments(String verb, List<String> segments) {
        String type = segments.get(0);
        String id = segments.get(1);
        String history = segments.get(2);
        String versionId = segments.get(3);

        if (!FhirInteraction.isResourceTypeName(type)) {
            return notFound("Unknown resource type: " + type);
        }
        if (!FhirInteraction.isLogicalId(id) || !history.equals("_history")) {
            return notFound("Unrecognised FHIR URL");
        }
        if (!FhirInteraction.isLogicalId(versionId)) {
            return notFound("Malformed version id: " + versionId);
        }
        if (!verb.equals("GET")) {
            return methodNotAllowed(verb);
        }
        return new FhirRoute(FhirInteraction.VREAD, type, id, versionId, null, null, null, 0, null);
    }

    private static FhirRoute operation(String segment, String type, String id) {
        String name = segment.substring(1);
        if (name.isEmpty()) {
            return notFound("Missing operation name");
        }
        return new FhirRoute(FhirInteraction.OPERATION, type, id, null, name, null, null, 0, null);
    }

    /**
     * Splits a path into decoded, non-empty segments.
     *
     * <p>Decoding happens per segment and after splitting, so an id containing an encoded
     * slash cannot smuggle in an extra path segment.
     */
    private static List<String> split(String path) {
        List<String> segments = new ArrayList<String>();
        if (path == null) {
            return segments;
        }
        for (String raw : path.split("/")) {
            if (raw.isEmpty()) {
                continue;
            }
            segments.add(decode(raw));
        }
        return segments;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is guaranteed present; this branch exists only to satisfy the checked
            // exception on the JDK 8 era signature.
            return value;
        } catch (IllegalArgumentException e) {
            // A stray "%" that is not a valid escape. Leave it as written -- the segment
            // will simply fail the resource type or id test and become a 404.
            return value;
        }
    }
}
