/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.fhir;

/**
 * The FHIR release a connector speaks.
 *
 * <p>Only three things actually differ between releases at the RESTful layer this
 * connector implements, which is why supporting both R4 and R5 costs an enum rather than
 * two sets of model classes:
 *
 * <ul>
 *   <li>the {@code fhirVersion} media type parameter -- {@code application/fhir+json;
 *       fhirVersion=4.0} vs {@code 5.0}. It is the wire marker a client uses to say which
 *       release it wants, and the one a server must echo (FHIR R4 §3.1.0.7, R5 §3.2.0.7);
 *   <li>{@code CapabilityStatement.fhirVersion}, which carries the full release version
 *       ("4.0.1", "5.0.0") rather than the two-digit media type form;
 *   <li>the resource bodies themselves -- and those are the channel's business, not this
 *       connector's. Nothing here parses or validates a payload against a StructureDefinition.
 * </ul>
 *
 * <p>The two artefacts the connector <em>does</em> generate on its own -- OperationOutcome
 * and CapabilityStatement -- are structurally identical in R4 and R5 for the elements used
 * here, so one builder serves both. Adding R4B (4.3.0/"4.3") or a later release is a line
 * in this enum plus a line in each client panel's version list.
 */
public enum FhirVersion {

    /** FHIR Release 4, the version most production servers still speak. */
    R4("R4", "4.0.1", "4.0"),

    /** FHIR Release 5. */
    R5("R5", "5.0.0", "5.0");

    private final String id;
    private final String releaseVersion;
    private final String mimeParameter;

    FhirVersion(String id, String releaseVersion, String mimeParameter) {
        this.id = id;
        this.releaseVersion = releaseVersion;
        this.mimeParameter = mimeParameter;
    }

    /** Short name, as stored in connector properties and shown in the UI ("R4"). */
    public String getId() {
        return id;
    }

    /** Full release version for {@code CapabilityStatement.fhirVersion} ("4.0.1"). */
    public String getReleaseVersion() {
        return releaseVersion;
    }

    /** Value of the {@code fhirVersion} media type parameter ("4.0"). */
    public String getMimeParameter() {
        return mimeParameter;
    }

    /**
     * Resolves a stored property value, defaulting to R4 rather than throwing.
     *
     * <p>A connector whose version string has been hand-edited into something unknown
     * should still start: refusing to deploy over it would take a channel down for a typo
     * in a field that only affects a media type parameter.
     */
    public static FhirVersion fromId(String value) {
        if (value != null) {
            for (FhirVersion version : values()) {
                if (version.id.equalsIgnoreCase(value.trim())) {
                    return version;
                }
            }
        }
        return R4;
    }

    /**
     * Matches a {@code fhirVersion} media type parameter sent by a client.
     *
     * <p>The spec defines the parameter as major.minor ("4.0"), but clients do send the
     * full release version ("4.0.1"), so both are accepted. Returns null for anything that
     * matches no known release -- the caller decides whether that is a 406 or is ignored.
     */
    public static FhirVersion fromMimeParameter(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        for (FhirVersion version : values()) {
            if (version.mimeParameter.equals(trimmed) || version.releaseVersion.equals(trimmed)) {
                return version;
            }
        }
        return null;
    }
}
