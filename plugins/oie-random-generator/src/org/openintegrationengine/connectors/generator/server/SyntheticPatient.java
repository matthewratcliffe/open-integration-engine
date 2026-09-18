/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator.server;

import org.apache.commons.lang3.StringUtils;
import org.openintegrationengine.connectors.generator.PatientRecord;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Random;

/**
 * One invented person, and the one visit they are having.
 *
 * <p>Everything here is drawn once, in {@link #create(long, int)}, from a {@link Random}
 * seeded with the pool's seed and this patient's index -- and then never changes. That is
 * the whole point of the class: it is what makes patient 7 carry the same MRN, the same
 * date of birth, the same ward and the same attending doctor in the PV1 of every message
 * they appear in, rather than a fresh set of plausible-looking values each time. A feed
 * where a patient's identity changed between the admit and the discharge would exercise
 * nothing a real one does.
 *
 * <p>Because the index is mixed into the seed rather than the patients being drawn in
 * sequence from one generator, patient 7 is the same person whether the pool holds ten
 * patients or ten thousand.
 *
 * <p>The data is Australian-shaped -- suburbs and states, a Medicare number, an {@code 04}
 * mobile -- because that is where this stack is used. None of it is real, and none of it is
 * derived from anything real: the name lists are common surnames and given names, and every
 * identifier is drawn from a random number generator.
 */
final class SyntheticPatient {

    /* @formatter:off */
    private static final String[] FAMILY_NAMES = {
        "SMITH", "JONES", "WILLIAMS", "BROWN", "WILSON", "TAYLOR", "NGUYEN", "MARTIN",
        "WHITE", "ANDERSON", "THOMPSON", "WALKER", "HARRIS", "LEE", "RYAN", "CAMPBELL",
        "KELLY", "O'CONNOR", "SINGH", "PATEL", "CHEN", "KAUR", "MURPHY", "ROBERTS",
        "EVANS", "KING", "HALL", "YOUNG", "GREEN", "BAKER", "MITCHELL", "CLARKE"};

    private static final String[] MALE_NAMES = {
        "JOHN", "MICHAEL", "DAVID", "PETER", "ANDREW", "JAMES", "ROBERT", "DANIEL",
        "MATTHEW", "LIAM", "NOAH", "OLIVER", "HARRY", "JACK", "THOMAS", "LUCAS",
        "ETHAN", "SAMUEL", "BENJAMIN", "HENRY", "GEORGE", "WILLIAM"};

    private static final String[] FEMALE_NAMES = {
        "MARY", "SARAH", "EMMA", "OLIVIA", "CHARLOTTE", "AMELIA", "SOPHIE", "JESSICA",
        "LAUREN", "HANNAH", "GRACE", "CHLOE", "ISABELLA", "MIA", "RUBY", "ELLA",
        "ZOE", "ALICE", "LUCY", "ANNA", "EVELYN", "MAYA"};

    private static final String[] STREET_NAMES = {
        "WATTLE", "BANKSIA", "ACACIA", "JACARANDA", "MELALEUCA", "BOTANY", "PARRAMATTA",
        "GEORGE", "ELIZABETH", "COLLINS", "FLINDERS", "MACQUARIE", "HARGRAVE", "STURT",
        "LACHLAN", "KOOKABURRA", "CURRAWONG", "BOOMERANG"};

    private static final String[] STREET_TYPES = {
        "STREET", "ROAD", "AVENUE", "PARADE", "CRESCENT", "DRIVE", "LANE", "COURT", "PLACE"};

    /** Suburb, state and a postcode that actually belongs to that state. */
    private static final String[][] LOCALITIES = {
        {"RANDWICK", "NSW", "2031"}, {"PARRAMATTA", "NSW", "2150"}, {"NEWTOWN", "NSW", "2042"},
        {"MANLY", "NSW", "2095"}, {"CARLTON", "VIC", "3053"}, {"FITZROY", "VIC", "3065"},
        {"GEELONG", "VIC", "3220"}, {"BRUNSWICK", "VIC", "3056"}, {"TOOWONG", "QLD", "4066"},
        {"SOUTHPORT", "QLD", "4215"}, {"CAIRNS", "QLD", "4870"}, {"UNLEY", "SA", "5061"},
        {"GLENELG", "SA", "5045"}, {"SUBIACO", "WA", "6008"}, {"FREMANTLE", "WA", "6160"},
        {"SANDY BAY", "TAS", "7005"}, {"BRADDON", "ACT", "2612"}, {"NIGHTCLIFF", "NT", "0810"}};

    /** HL7 table 0002. */
    private static final String[] MARITAL_STATUS = {"S", "M", "D", "W", "S", "M"};

    /** HL7 table 0063, abbreviated to the ones a next of kin is actually recorded as. */
    private static final String[] NOK_RELATIONSHIPS = {"SPO", "CHD", "PAR", "SIB", "FND", "GRD"};

    /** HL7 table 0004, weighted: most hospital traffic is inpatients and outpatients. */
    private static final String[] PATIENT_CLASSES = {"I", "I", "I", "O", "O", "E"};

    private static final String[] WARDS = {
        "3EAST", "3WEST", "4EAST", "4WEST", "ICU", "EMERG", "MATERNITY", "ONCOLOGY",
        "DAYSURG", "REHAB", "CORONARY", "PAEDS"};

    private static final String[] HOSPITALS = {"RPA", "RMH", "RBWH", "PAH", "SCGH", "RHH", "TCH", "RDH"};

    /** HL7 table 0007. */
    private static final String[] ADMIT_TYPES = {"A", "E", "L", "R", "U", "C"};

    /** HL7 table 0069. */
    private static final String[] HOSPITAL_SERVICES = {"MED", "SUR", "CAR", "ORT", "ONC", "EMR", "OBS", "PAE"};

    /**
     * Payer code and the matching name, as a pair. Drawing them separately is how a
     * generator ends up sending a message whose fund code is NIB and whose fund name is
     * Medibank -- which no real feed does, and which a mapping under test would happily
     * pass.
     */
    private static final String[][] INSURERS = {
        {"MEDICARE", "Medicare Australia"}, {"BUPA", "Bupa Australia"},
        {"MEDIBANK", "Medibank Private"}, {"HCF", "HCF"}, {"NIB", "NIB Health Funds"},
        {"HBF", "HBF Health"}, {"AHM", "Australian Health Management"},
        {"DVA", "Department of Veterans Affairs"}, {"WORKCOVER", "Workers Compensation"},
        {"SELF", "Self funded"}};
    /* @formatter:on */

    private final int index;
    private final String id;
    private final String mrn;
    private final String medicare;
    private final String accountNumber;

    private final String family;
    private final String given;
    private final String middle;
    private final String prefix;
    private final String sex;
    private final LocalDate dateOfBirth;

    private final String street;
    private final String suburb;
    private final String state;
    private final String postcode;
    private final String phone;
    private final String mobile;
    private final String maritalStatus;
    private final String insurerCode;
    private final String insurerName;
    private final String policyNumber;

    private final String nokFamily;
    private final String nokGiven;
    private final String nokRelationship;
    private final String nokPhone;

    private final String visitNumber;
    private final String patientClass;
    private final String pointOfCare;
    private final String room;
    private final String bed;
    private final String facility;
    private final String admitType;
    private final String hospitalService;
    private final String financialClass;
    private final LocalDateTime admitTime;

    private final Doctor attending;
    private final Doctor referring;

    /**
     * @param seed  the pool's seed
     * @param index this patient's 1-based position in the pool
     * @param now   the moment the pool is being built, which the admit time is measured back
     *              from -- so a patient admitted "yesterday" is still yesterday next week
     */
    /**
     * @param overrides a row the operator filled in, whose non-blank columns replace what was
     *                  drawn; null, or a blank column, leaves the invented value
     */
    static SyntheticPatient create(long seed, int index, LocalDateTime now, PatientRecord overrides) {
        /*
         * A generator per patient, seeded from the pool seed and the index. Drawing the
         * whole pool from one generator would make patient 7 depend on how many patients
         * came before it, so changing the pool size would silently change everybody.
         */
        Random random = new Random(seed * 1000003L + index);
        return new SyntheticPatient(random, index, now, overrides);
    }

    /*
     * Every value is drawn first and overridden afterwards, never drawn conditionally on
     * whether a column was filled in. Otherwise typing an MRN into one row would shift that
     * patient's random stream and quietly change their address, their insurer and their next
     * of kin -- and the point of the table is to pin down a few fields, not to redraw the
     * person behind them.
     */
    private SyntheticPatient(Random random, int index, LocalDateTime now, PatientRecord overrides) {
        PatientRecord row = overrides == null ? new PatientRecord() : overrides;
        this.index = index;
        this.id = String.format("PAT%05d", index);
        this.mrn = override(row.getMrn(), RandomValues.digits(random, 7));
        this.medicare = RandomValues.digits(random, 10);
        this.accountNumber = "A" + RandomValues.digits(random, 8);

        boolean male = random.nextBoolean();
        this.sex = override(row.getSex(), male ? "M" : "F");
        this.family = override(row.getFamily(), RandomValues.pick(random, FAMILY_NAMES));
        String drawnGiven = RandomValues.pick(random, male ? MALE_NAMES : FEMALE_NAMES);
        String drawnMiddle = RandomValues.pick(random, male ? MALE_NAMES : FEMALE_NAMES);
        /*
         * A row that sets the sex and leaves the name blank would otherwise read SARAH
         * SMITH, sex M. The names move to the matching list by a hash of the drawn one
         * rather than by drawing again: another draw here would shift everything after it
         * in this patient's stream, and typing M into one cell would change their address.
         */
        this.given = override(row.getGiven(), matching(drawnGiven, sex, male));
        this.middle = matching(drawnMiddle, sex, male);
        LocalDate drawnBirth = now.toLocalDate().minusDays(RandomValues.intBetween(random, 365, 95 * 365));
        this.dateOfBirth = birthDate(row, drawnBirth);
        // The drawn title is moved onto the sex the message will carry rather than redrawn,
        // so a row that sets only the sex does not read "MR SARAH SMITH".
        this.prefix = adjustTitle(title(male, dateOfBirth, now.toLocalDate(), random), sex);

        String[] locality = LOCALITIES[random.nextInt(LOCALITIES.length)];
        this.street = RandomValues.intBetween(random, 1, 240) + " "
                + RandomValues.pick(random, STREET_NAMES) + " " + RandomValues.pick(random, STREET_TYPES);
        this.suburb = locality[0];
        this.state = locality[1];
        this.postcode = locality[2];
        // Area code by state, the way a landline actually is.
        this.phone = "0" + areaCode(state) + RandomValues.digits(random, 8);
        this.mobile = "04" + RandomValues.digits(random, 8);
        this.maritalStatus = RandomValues.pick(random, MARITAL_STATUS);

        String[] insurer = INSURERS[random.nextInt(INSURERS.length)];
        this.insurerCode = insurer[0];
        this.insurerName = insurer[1];
        this.policyNumber = RandomValues.digits(random, 8);

        this.nokRelationship = RandomValues.pick(random, NOK_RELATIONSHIPS);
        // A spouse, a child or a sibling usually shares the surname; a friend does not.
        this.nokFamily = "FND".equals(nokRelationship) ? RandomValues.pick(random, FAMILY_NAMES) : family;
        this.nokGiven = RandomValues.pick(random, random.nextBoolean() ? MALE_NAMES : FEMALE_NAMES);
        this.nokPhone = "04" + RandomValues.digits(random, 8);

        this.visitNumber = override(row.getVisitNumber(), "V" + RandomValues.digits(random, 8));
        this.patientClass = override(row.getPatientClass(), RandomValues.pick(random, PATIENT_CLASSES));
        // A row's Location is an HL7 location with as many components as it cares to give,
        // the rest still drawn: "ICU" on its own sets the ward and keeps the bed.
        String[] locationParts = components(row.getLocation());
        this.pointOfCare = override(part(locationParts, 0), RandomValues.pick(random, WARDS));
        this.room = override(part(locationParts, 1), RandomValues.digits(random, 3));
        this.bed = override(part(locationParts, 2), String.valueOf((char) ('A' + random.nextInt(4))));
        this.facility = override(part(locationParts, 3), RandomValues.pick(random, HOSPITALS));
        this.admitType = RandomValues.pick(random, ADMIT_TYPES);
        this.hospitalService = RandomValues.pick(random, HOSPITAL_SERVICES);
        // PV1-20 is who is paying, so it is the payer -- not a second, unrelated draw.
        this.financialClass = insurerCode;
        this.admitTime = now.minusMinutes(RandomValues.intBetween(random, 30, 14 * 24 * 60));

        this.attending = Doctor.create(random).with(row.getAttendingDoctor());
        this.referring = Doctor.create(random);
    }

    /**
     * The drawn name, or its counterpart in the other list when a row set a sex the draw did
     * not. Deterministic and stream-free: the same drawn name always maps to the same
     * replacement, so the patient stays as reproducible as everybody else.
     */
    private static String matching(String drawn, String effectiveSex, boolean drawnMale) {
        boolean male = "M".equalsIgnoreCase(effectiveSex);
        boolean female = "F".equalsIgnoreCase(effectiveSex);
        if ((!male && !female) || male == drawnMale) {
            return drawn;
        }
        String[] names = male ? MALE_NAMES : FEMALE_NAMES;
        return names[Math.floorMod(drawn.hashCode(), names.length)];
    }

    /** The row's value where it has one, otherwise what was drawn. */
    private static String override(String configured, String drawn) {
        return StringUtils.isBlank(configured) ? drawn : configured.trim();
    }

    private static String[] components(String value) {
        return StringUtils.isBlank(value) ? new String[0] : value.trim().split("\\^", -1);
    }

    private static String part(String[] parts, int index) {
        return index < parts.length ? parts[index] : null;
    }

    /**
     * A date of birth from the row, in {@code yyyyMMdd} or {@code yyyy-MM-dd}.
     *
     * @throws IllegalArgumentException naming the row, so a typo fails the deploy with
     *                                  something an operator can act on
     */
    private static LocalDate birthDate(PatientRecord row, LocalDate drawn) {
        String configured = StringUtils.trimToEmpty(row.getDateOfBirth());
        if (configured.isEmpty()) {
            return drawn;
        }
        String text = configured.length() == 8 && configured.indexOf('-') < 0
                ? configured.substring(0, 4) + "-" + configured.substring(4, 6) + "-" + configured.substring(6)
                : configured;
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Patient " + row.describe() + " has a date of birth of \""
                    + configured + "\", which is not yyyyMMdd or yyyy-MM-dd.", e);
        }
    }

    /** MR/MS/MRS/MSTR/MISS moved onto the sex the message will carry. */
    private static String adjustTitle(String drawn, String effectiveSex) {
        boolean male = "M".equalsIgnoreCase(effectiveSex);
        boolean female = "F".equalsIgnoreCase(effectiveSex);
        if (!male && !female) {
            // Anything else (O, U, a local code) gets no honorific rather than a guess.
            return "";
        }
        if ("MISS".equals(drawn) || "MSTR".equals(drawn)) {
            return male ? "MSTR" : "MISS";
        }
        if (male) {
            return "MR";
        }
        return "MR".equals(drawn) ? "MS" : drawn;
    }

    private static String title(boolean male, LocalDate birth, LocalDate today, Random random) {
        if (birth.plusYears(18).isAfter(today)) {
            return male ? "MSTR" : "MISS";
        }
        if (male) {
            return "MR";
        }
        return random.nextBoolean() ? "MS" : "MRS";
    }

    private static String areaCode(String state) {
        if ("VIC".equals(state) || "TAS".equals(state)) {
            return "3";
        }
        if ("QLD".equals(state)) {
            return "7";
        }
        if ("SA".equals(state) || "WA".equals(state) || "NT".equals(state)) {
            return "8";
        }
        return "2";
    }

    int getIndex() {
        return index;
    }

    String getId() {
        return id;
    }

    String getMrn() {
        return mrn;
    }

    String getMedicare() {
        return medicare;
    }

    String getAccountNumber() {
        return accountNumber;
    }

    String getFamily() {
        return family;
    }

    String getGiven() {
        return given;
    }

    String getMiddle() {
        return middle;
    }

    String getPrefix() {
        return prefix;
    }

    String getSex() {
        return sex;
    }

    LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    String getStreet() {
        return street;
    }

    String getSuburb() {
        return suburb;
    }

    String getState() {
        return state;
    }

    String getPostcode() {
        return postcode;
    }

    String getPhone() {
        return phone;
    }

    String getMobile() {
        return mobile;
    }

    String getMaritalStatus() {
        return maritalStatus;
    }

    String getInsurerCode() {
        return insurerCode;
    }

    String getInsurerName() {
        return insurerName;
    }

    String getPolicyNumber() {
        return policyNumber;
    }

    String getNokFamily() {
        return nokFamily;
    }

    String getNokGiven() {
        return nokGiven;
    }

    String getNokRelationship() {
        return nokRelationship;
    }

    String getNokPhone() {
        return nokPhone;
    }

    String getVisitNumber() {
        return visitNumber;
    }

    String getPatientClass() {
        return patientClass;
    }

    String getPointOfCare() {
        return pointOfCare;
    }

    String getRoom() {
        return room;
    }

    String getBed() {
        return bed;
    }

    String getFacility() {
        return facility;
    }

    String getAdmitType() {
        return admitType;
    }

    String getHospitalService() {
        return hospitalService;
    }

    String getFinancialClass() {
        return financialClass;
    }

    LocalDateTime getAdmitTime() {
        return admitTime;
    }

    Doctor getAttending() {
        return attending;
    }

    Doctor getReferring() {
        return referring;
    }

    /** PV1-3: point of care, room, bed, facility. */
    String getLocation() {
        return pointOfCare + "^" + room + "^" + bed + "^" + facility;
    }

    /** What a log line or a preview says this patient is. */
    String describe() {
        return id + " " + family + "^" + given + " (MRN " + mrn + ", visit " + visitNumber + ")";
    }

    /** A doctor, as an XCN: id, family name, given name. */
    static final class Doctor {

        private final String id;
        private final String family;
        private final String given;

        private Doctor(String id, String family, String given) {
            this.id = id;
            this.family = family;
            this.given = given;
        }

        static Doctor create(Random random) {
            boolean male = random.nextBoolean();
            return new Doctor("DR" + RandomValues.digits(random, 5),
                    RandomValues.pick(random, FAMILY_NAMES),
                    RandomValues.pick(random, male ? MALE_NAMES : FEMALE_NAMES));
        }

        /**
         * This doctor with a row's value applied: a whole XCN ({@code DR1^SMITH^JOHN})
         * replaces the id and the name, a bare id replaces only the id, and blank changes
         * nothing.
         */
        Doctor with(String configured) {
            if (StringUtils.isBlank(configured)) {
                return this;
            }
            String[] parts = configured.trim().split("\\^", -1);
            return new Doctor(StringUtils.defaultIfBlank(parts[0], id),
                    parts.length > 1 && StringUtils.isNotBlank(parts[1]) ? parts[1] : family,
                    parts.length > 2 && StringUtils.isNotBlank(parts[2]) ? parts[2] : given);
        }

        String getId() {
            return id;
        }

        String getFamily() {
            return family;
        }

        String getGiven() {
            return given;
        }

        /** The whole XCN, which is what PV1-7 and friends want. */
        String getXcn() {
            return id + "^" + family + "^" + given + "^^^DR";
        }
    }
}
