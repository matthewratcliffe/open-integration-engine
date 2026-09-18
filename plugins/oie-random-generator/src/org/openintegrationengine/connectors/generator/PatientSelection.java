/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator;

/**
 * How each generated message picks a patient out of the pool.
 *
 * <p>Either way the patient itself is fixed: the pool is built once from the seed, so
 * patient 7 has the same MRN, name, date of birth, ward and attending doctor in every
 * message it ever appears in. This only decides who is next.
 */
public enum PatientSelection {

    /** A patient at random. Repeats are expected -- that is what a real feed looks like. */
    RANDOM("Random"),

    /**
     * Round robin through the pool, so every patient appears equally often and in order --
     * and the mode in which the pool can be a table of patients the operator has named
     * rather than invented ones. See
     * {@link RandomGeneratorProperties#getPatients()}.
     */
    SEQUENTIAL("Sequential (round robin)");

    private final String label;

    PatientSelection(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
