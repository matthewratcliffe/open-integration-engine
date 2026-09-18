/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator;

/**
 * The HL7 v2 message types this connector ships a sample template for.
 *
 * <p>Each constant carries the three parts of MSH-9 -- the code, the trigger event and the
 * message structure -- because the structure is not always the code and the event stuck
 * together: an A08 is carried by the {@code ADT_A01} structure, and a generator that wrote
 * {@code ADT^A08^ADT_A08} would be producing something no conformant receiver accepts.
 *
 * <p>The name of each constant is also the name of its sample file, {@code
 * samples/<name>.hl7}, which {@link GeneratorSamples} loads from the classpath. Adding a
 * type is a constant here and a file there; nothing else changes.
 *
 * <p>Serialised into the channel XML by name, so renaming a constant breaks every channel
 * already using it.
 */
public enum MessageType {

    ADT_A01("ADT", "A01", "ADT_A01", "Admit / visit notification"),
    ADT_A03("ADT", "A03", "ADT_A03", "Discharge / end visit"),
    ADT_A08("ADT", "A08", "ADT_A01", "Update patient information"),
    ORM_O01("ORM", "O01", "ORM_O01", "Order message"),
    ORU_R01("ORU", "R01", "ORU_R01", "Observation result"),
    SIU_S12("SIU", "S12", "SIU_S12", "Notification of new appointment booking"),
    DFT_P03("DFT", "P03", "DFT_P03", "Post detail financial transaction"),
    MFN_M02("MFN", "M02", "MFN_M02", "Master file notification: staff / practitioner");

    private final String code;
    private final String event;
    private final String structure;
    private final String description;

    MessageType(String code, String event, String structure, String description) {
        this.code = code;
        this.event = event;
        this.structure = structure;
        this.description = description;
    }

    /** MSH-9.1, such as {@code ADT}. */
    public String getCode() {
        return code;
    }

    /** MSH-9.2, such as {@code A01}. */
    public String getEvent() {
        return event;
    }

    /** MSH-9.3, such as {@code ADT_A01} -- which for an A08 is deliberately not {@code ADT_A08}. */
    public String getStructure() {
        return structure;
    }

    public String getDescription() {
        return description;
    }

    /** The classpath name of this type's sample template, relative to {@link GeneratorSamples}. */
    public String getSampleResource() {
        return name() + ".hl7";
    }

    /** What the Administrator's combo box shows. XStream still serialises {@link #name()}. */
    @Override
    public String toString() {
        return name() + " - " + description;
    }
}
