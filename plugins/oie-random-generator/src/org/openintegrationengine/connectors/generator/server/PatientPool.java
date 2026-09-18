/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator.server;

import org.openintegrationengine.connectors.generator.PatientRecord;
import org.openintegrationengine.connectors.generator.PatientSelection;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The population a channel generates messages about: {@code size} invented patients, built
 * once when the channel deploys and then only read.
 *
 * <p>Built once is the design. Drawing a fresh patient per message would produce a feed in
 * which no MRN is ever seen twice, which tests nothing that matters -- no downstream merge,
 * no update of an existing record, no visit that starts and later ends. A fixed population
 * of a chosen size is the dial between "one patient's whole journey" and "a busy hospital".
 *
 * <p>The population is invented unless the channel names its patients, in which case the
 * pool is exactly those rows, in the order they were typed. A row still sits on top of an
 * invented patient rather than replacing one: the columns it leaves blank -- the address,
 * the next of kin, the insurer -- come from the same seeded draw as ever, so a table of
 * three MRNs still produces three complete and stable people.
 *
 * <p>Thread safe: the patients are immutable and the round-robin cursor is atomic, because
 * a channel with several processing threads polls on one thread but may be asked to
 * generate from more than one.
 */
final class PatientPool {

    private final List<SyntheticPatient> patients;
    private final AtomicInteger cursor = new AtomicInteger();
    private final long seed;

    /**
     * @param size    how many to invent, used only when {@code defined} is empty
     * @param defined the rows the operator typed; when there are any, they <em>are</em> the
     *                population and {@code size} is ignored
     */
    PatientPool(long seed, int size, LocalDateTime now, List<PatientRecord> defined) {
        this.seed = seed;
        boolean named = defined != null && !defined.isEmpty();
        int count = named ? defined.size() : Math.max(1, size);
        List<SyntheticPatient> built = new ArrayList<SyntheticPatient>(count);
        for (int index = 1; index <= count; index++) {
            built.add(SyntheticPatient.create(seed, index, now, named ? defined.get(index - 1) : null));
        }
        this.patients = built;
    }

    SyntheticPatient select(PatientSelection selection, Random random) {
        if (selection == PatientSelection.SEQUENTIAL) {
            // Math.floorMod so the cursor wrapping past Integer.MAX_VALUE stays in range
            // rather than indexing with a negative number after a few billion messages.
            return patients.get(Math.floorMod(cursor.getAndIncrement(), patients.size()));
        }
        return patients.get(random.nextInt(patients.size()));
    }

    /** By 1-based index, for a preview that wants a specific patient. */
    SyntheticPatient get(int index) {
        return patients.get(Math.floorMod(index - 1, patients.size()));
    }

    int size() {
        return patients.size();
    }

    long getSeed() {
        return seed;
    }
}
