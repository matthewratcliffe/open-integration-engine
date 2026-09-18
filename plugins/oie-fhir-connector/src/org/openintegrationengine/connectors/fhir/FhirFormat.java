/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two serialisations this connector can emit, and the content negotiation around them.
 *
 * <p>FHIR defines three (JSON, XML, Turtle). Turtle is deliberately absent: nothing in
 * healthcare integration asks for it, and offering a format the connector cannot actually
 * produce is worse than a clean 406.
 *
 * <p>Negotiation follows FHIR R4/R5 §3.1.0.7 "Content Types and Encodings": the
 * {@code _format} query parameter wins over the {@code Accept} header, and both accept the
 * shorthand codes ("json", "xml") as well as full media types. The DSTU2-era
 * {@code application/json+fhir} spelling is still accepted on input because old clients
 * emit it; it is never sent back.
 */
public enum FhirFormat {

    JSON("json", "application/fhir+json"),
    XML("xml", "application/fhir+xml");

    private final String code;
    private final String mimeType;

    FhirFormat(String code, String mimeType) {
        this.code = code;
        this.mimeType = mimeType;
    }

    /** "json" or "xml" -- what goes in the source map and the {@code _format} parameter. */
    public String getCode() {
        return code;
    }

    /** The canonical media type, without parameters. */
    public String getMimeType() {
        return mimeType;
    }

    /**
     * The full {@code Content-Type} header value for a response: media type, FHIR version
     * and charset.
     *
     * <p>Echoing {@code fhirVersion} is what lets a client that talks to several servers
     * tell which release it just got, and is required of a server that accepts the
     * parameter on the way in.
     */
    public String contentType(FhirVersion version, String charset) {
        StringBuilder value = new StringBuilder(mimeType);
        if (version != null) {
            value.append("; fhirVersion=").append(version.getMimeParameter());
        }
        if (charset != null && !charset.isEmpty()) {
            value.append("; charset=").append(charset.toLowerCase(Locale.ROOT));
        }
        return value.toString();
    }

    /**
     * Maps one media type or shorthand code to a format, or null if it names something
     * this connector cannot produce.
     */
    public static FhirFormat fromMimeType(String value) {
        if (value == null) {
            return null;
        }
        String type = stripParameters(value).toLowerCase(Locale.ROOT);
        switch (type) {
            case "json":
            case "application/fhir+json":
            case "application/json+fhir":
            case "application/json":
            case "text/json":
                return JSON;
            case "xml":
            case "application/fhir+xml":
            case "application/xml+fhir":
            case "application/xml":
            case "text/xml":
                return XML;
            default:
                return null;
        }
    }

    /**
     * Picks the response format for a request.
     *
     * @param formatParameter the {@code _format} query parameter, or null
     * @param acceptHeader    the {@code Accept} header, or null
     * @param fallback        what to use when neither expresses a preference
     * @return the chosen format, or null when the request asked for something specific
     *         that cannot be produced -- the caller answers that with 406
     */
    public static FhirFormat negotiate(String formatParameter, String acceptHeader, FhirFormat fallback) {
        // _format wins outright: it exists precisely for clients (browsers, curl) whose
        // Accept header they do not control.
        if (formatParameter != null && !formatParameter.trim().isEmpty()) {
            return fromMimeType(formatParameter.trim());
        }

        if (acceptHeader == null || acceptHeader.trim().isEmpty()) {
            return fallback;
        }

        boolean sawWildcard = false;
        for (String candidate : splitAcceptHeader(acceptHeader)) {
            String type = stripParameters(candidate).toLowerCase(Locale.ROOT);
            if (type.equals("*/*") || type.equals("application/*")) {
                sawWildcard = true;
                continue;
            }
            FhirFormat format = fromMimeType(type);
            if (format != null) {
                return format;
            }
        }

        // "Accept: */*, application/pdf" still means the client will take our default.
        return sawWildcard ? fallback : null;
    }

    /**
     * Reads the {@code fhirVersion} parameter out of an {@code Accept} or
     * {@code Content-Type} header, or null when the client did not state one.
     *
     * <p>Only the first entry that carries the parameter is considered; a client listing
     * two different FHIR versions in one Accept header is asking a question this facade
     * has no way to answer, and taking the first is as defensible as any other choice.
     */
    public static String versionParameter(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        for (String candidate : splitAcceptHeader(headerValue)) {
            for (String parameter : candidate.split(";")) {
                String trimmed = parameter.trim();
                if (trimmed.toLowerCase(Locale.ROOT).startsWith("fhirversion=")) {
                    return unquote(trimmed.substring("fhirversion=".length()).trim());
                }
            }
        }
        return null;
    }

    /**
     * Guesses the serialisation of a body the channel produced.
     *
     * <p>The facade passes a channel's response through untouched -- it has no FHIR model
     * to convert between JSON and XML with -- so the declared Content-Type has to describe
     * what is actually there rather than what the client asked for. A leading brace or
     * angle bracket is all the evidence available, and all that is needed.
     *
     * @return the detected format, or null when the body is neither
     */
    public static FhirFormat sniff(String body) {
        if (body == null) {
            return null;
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (Character.isWhitespace(c) || c == '﻿') {
                continue;
            }
            if (c == '{' || c == '[') {
                return JSON;
            }
            if (c == '<') {
                return XML;
            }
            return null;
        }
        return null;
    }

    private static List<String> splitAcceptHeader(String header) {
        List<String> entries = new ArrayList<>();
        for (String entry : header.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    private static String stripParameters(String mediaType) {
        int semicolon = mediaType.indexOf(';');
        return (semicolon < 0 ? mediaType : mediaType.substring(0, semicolon)).trim();
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
