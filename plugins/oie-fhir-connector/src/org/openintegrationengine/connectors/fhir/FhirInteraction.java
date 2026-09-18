/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The RESTful interactions of the FHIR HTTP API (R4/R5 §3.1 "RESTful API").
 *
 * <p>The listener uses this for three jobs: telling the channel what was asked of it
 * ({@code fhirInteraction} in the source map), deciding whether a request is allowed at
 * all, and generating the CapabilityStatement. The {@code code} values are the ones from
 * the {@code TypeRestfulInteraction} and {@code SystemRestfulInteraction} value sets, so
 * they can go into a CapabilityStatement verbatim -- with the exception of
 * {@link #SEARCH_COMPARTMENT}, which the spec describes as a URL form rather than naming
 * as an interaction, and which is therefore never advertised.
 *
 * <p>Identical in R4 and R5. R5 changed nothing in this value set.
 */
public enum FhirInteraction {

    READ("read", Level.TYPE),
    VREAD("vread", Level.TYPE),
    UPDATE("update", Level.TYPE),
    PATCH("patch", Level.TYPE),
    DELETE("delete", Level.TYPE),
    CREATE("create", Level.TYPE),
    SEARCH_TYPE("search-type", Level.TYPE),
    HISTORY_INSTANCE("history-instance", Level.TYPE),
    HISTORY_TYPE("history-type", Level.TYPE),

    HISTORY_SYSTEM("history-system", Level.SYSTEM),
    SEARCH_SYSTEM("search-system", Level.SYSTEM),
    TRANSACTION("transaction", Level.SYSTEM),
    BATCH("batch", Level.SYSTEM),

    /** Compartment search ({@code [base]/Patient/123/Observation}). Not advertisable. */
    SEARCH_COMPARTMENT("search-compartment", Level.NONE),

    /** Any {@code $operation}, at system, type or instance level. Advertised as operations. */
    OPERATION("operation", Level.NONE),

    /** {@code GET [base]/metadata}. Always handled, never gated. */
    CAPABILITIES("capabilities", Level.NONE);

    /** Where an interaction is advertised in a CapabilityStatement, if at all. */
    public enum Level {
        /** {@code rest.resource.interaction} -- per resource type. */
        TYPE,
        /** {@code rest.interaction} -- once for the whole server. */
        SYSTEM,
        /** Not part of either value set. */
        NONE
    }

    private final String code;
    private final Level level;

    FhirInteraction(String code, Level level) {
        this.code = code;
        this.level = level;
    }

    /** The interaction code, as used in a CapabilityStatement and the source map. */
    public String getCode() {
        return code;
    }

    public Level getLevel() {
        return level;
    }

    public static FhirInteraction fromCode(String value) {
        if (value != null) {
            String trimmed = value.trim();
            for (FhirInteraction interaction : values()) {
                if (interaction.code.equalsIgnoreCase(trimmed)) {
                    return interaction;
                }
            }
        }
        return null;
    }

    /**
     * Every interaction a facade will serve unless told otherwise, as a comma-separated
     * list -- the default for the listener's "Enabled Interactions" property.
     *
     * <p>Defaulting to "everything" rather than "nothing" is the right way round for a
     * facade: the channel behind it decides what it can actually do, and a connector that
     * silently 405s an interaction the channel handles is a confusing place to start.
     * Narrowing the list is how you turn the facade into a read-only or write-only one.
     */
    public static String defaultEnabledCodes() {
        List<String> codes = new ArrayList<>();
        for (FhirInteraction interaction : values()) {
            if (interaction != CAPABILITIES) {
                codes.add(interaction.code);
            }
        }
        return String.join(",", codes);
    }

    /**
     * Parses an "Enabled Interactions" property value.
     *
     * <p>A blank value means every interaction, not none: an empty field in a UI reads as
     * "unset", and a facade that answers nothing at all is never what someone meant to
     * configure. Unrecognised codes are ignored rather than rejected so that a channel
     * exported from a future version of this connector still deploys here.
     */
    public static Set<FhirInteraction> parseEnabled(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values())));
        }
        Set<FhirInteraction> enabled = new LinkedHashSet<>();
        for (String token : value.split("[,\s]+")) {
            FhirInteraction interaction = fromCode(token);
            if (interaction != null) {
                enabled.add(interaction);
            }
        }
        // Capabilities is not something a facade can meaningfully refuse: a FHIR client
        // that cannot read /metadata cannot discover anything else either.
        enabled.add(CAPABILITIES);
        return Collections.unmodifiableSet(enabled);
    }

    /**
     * Parses a comma-separated resource type allowlist. Blank means "every type", which is
     * the only sensible default for a facade fronting a channel that may route anything.
     */
    public static Set<String> parseResourceTypes(String value) {
        Set<String> types = new LinkedHashSet<>();
        if (value != null) {
            for (String token : value.split("[,\s]+")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    types.add(trimmed);
                }
            }
        }
        return Collections.unmodifiableSet(types);
    }

    /**
     * FHIR resource type names are UpperCamelCase with no punctuation (R4/R5 §2.1). The
     * check is what separates {@code [base]/Patient} from {@code [base]/favicon.ico}, and
     * lets the listener answer the second with a 404 instead of handing the channel a
     * message about a resource type that does not exist.
     */
    public static boolean isResourceTypeName(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return false;
        }
        if (!Character.isUpperCase(value.charAt(0))) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isLetterOrDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Logical ids are {@code [A-Za-z0-9\-\.]{1,64}} (R4/R5 §2.1 "id"). Anything else in an
     * id position is a malformed URL rather than a missing resource.
     */
    public static boolean isLogicalId(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** Lowercases a method name once, so callers can switch on it. */
    public static String normaliseMethod(String method) {
        return method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
    }
}
