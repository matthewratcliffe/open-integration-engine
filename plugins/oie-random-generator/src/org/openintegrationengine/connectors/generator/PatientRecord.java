/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One patient the operator has named, for sequential mode.
 *
 * <p>Sequential mode walks a list in order, and the reason to want that is usually that the
 * list is <em>yours</em>: the three patients the downstream system has already been loaded
 * with, the MRN in the defect report, the visit number someone is watching in a database.
 * So the panel offers a table, and each row here is a row of it.
 *
 * <p><b>Every field is optional.</b> A blank one is filled from the invented patient at the
 * same position in the population, so a row that names only an MRN still produces a
 * complete message -- with an address, a next of kin, an insurer and a referring doctor
 * that are stable for that row, because they come from the same seeded draw as ever. Giving
 * a table of nine columns rather than the thirty-odd fields a message can carry is only
 * reasonable because of that fallback.
 *
 * <p>Serialised into the channel XML by XStream under this class's fully qualified name,
 * which is also the element name the web administrator's panel writes. Renaming or moving
 * it breaks every channel already using it.
 */
public class PatientRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private String mrn;
    private String family;
    private String given;
    private String sex;
    private String dateOfBirth;
    private String visitNumber;
    private String patientClass;
    private String location;
    private String attendingDoctor;

    public PatientRecord() {
        mrn = "";
        family = "";
        given = "";
        sex = "";
        dateOfBirth = "";
        visitNumber = "";
        patientClass = "";
        location = "";
        attendingDoctor = "";
    }

    public PatientRecord(PatientRecord other) {
        this();
        if (other != null) {
            mrn = other.mrn;
            family = other.family;
            given = other.given;
            sex = other.sex;
            dateOfBirth = other.dateOfBirth;
            visitNumber = other.visitNumber;
            patientClass = other.patientClass;
            location = other.location;
            attendingDoctor = other.attendingDoctor;
        }
    }

    /** {@code ${patient.mrn}}, and PID-3 in every sample. */
    public String getMrn() {
        return mrn;
    }

    public void setMrn(String mrn) {
        this.mrn = mrn;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }

    public String getGiven() {
        return given;
    }

    public void setGiven(String given) {
        this.given = given;
    }

    /** {@code M}, {@code F}, {@code O} or {@code U} -- HL7 table 0001, unchecked. */
    public String getSex() {
        return sex;
    }

    public void setSex(String sex) {
        this.sex = sex;
    }

    /**
     * {@code yyyyMMdd} or {@code yyyy-MM-dd}. Anything else fails the deploy naming the
     * row, rather than silently becoming a date nobody chose.
     */
    public String getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(String dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    /** {@code ${visit.number}}, PV1-19. */
    public String getVisitNumber() {
        return visitNumber;
    }

    public void setVisitNumber(String visitNumber) {
        this.visitNumber = visitNumber;
    }

    /** {@code ${visit.class}}, PV1-2: {@code I}, {@code O} or {@code E}. */
    public String getPatientClass() {
        return patientClass;
    }

    public void setPatientClass(String patientClass) {
        this.patientClass = patientClass;
    }

    /**
     * {@code ${visit.location}}, PV1-3, as {@code WARD^ROOM^BED^FACILITY}. Fewer components
     * is fine -- {@code ICU} on its own sets the ward and leaves the room, bed and facility
     * as drawn -- and {@code ${visit.pointOfCare}} and friends follow whatever is given.
     */
    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    /**
     * {@code ${visit.attending.xcn}}, PV1-7. Either a whole XCN ({@code
     * DR12345^SMITH^JOHN}) or just an id, in which case the name stays as drawn.
     */
    public String getAttendingDoctor() {
        return attendingDoctor;
    }

    public void setAttendingDoctor(String attendingDoctor) {
        this.attendingDoctor = attendingDoctor;
    }

    /** True when the row names nothing at all, which is simply an invented patient. */
    public boolean isEmpty() {
        return StringUtils.isAllBlank(mrn, family, given, sex, dateOfBirth, visitNumber,
                patientClass, location, attendingDoctor);
    }

    /** What a log line or a preview calls this row. */
    public String describe() {
        String name = StringUtils.trimToEmpty(family) + "^" + StringUtils.trimToEmpty(given);
        if (StringUtils.isNotBlank(mrn)) {
            return "MRN " + mrn.trim() + (name.length() > 1 ? " (" + name + ")" : "");
        }
        return name.length() > 1 ? name : "(unnamed row)";
    }

    @Override
    public boolean equals(Object obj) {
        return EqualsBuilder.reflectionEquals(this, obj);
    }

    @Override
    public int hashCode() {
        int result = mrn == null ? 0 : mrn.hashCode();
        result = 31 * result + (visitNumber == null ? 0 : visitNumber.hashCode());
        return result;
    }

    /**
     * Usage statistics: which columns are in use, never what is in them. These rows are the
     * one place in this connector where an operator may well have typed a real MRN.
     */
    public Map<String, Object> getPurgedProperties() {
        Map<String, Object> purged = new LinkedHashMap<String, Object>();
        purged.put("mrn", StringUtils.isNotBlank(mrn));
        purged.put("name", StringUtils.isNotBlank(family) || StringUtils.isNotBlank(given));
        purged.put("sex", StringUtils.isNotBlank(sex));
        purged.put("dateOfBirth", StringUtils.isNotBlank(dateOfBirth));
        purged.put("visitNumber", StringUtils.isNotBlank(visitNumber));
        purged.put("patientClass", StringUtils.isNotBlank(patientClass));
        purged.put("location", StringUtils.isNotBlank(location));
        purged.put("attendingDoctor", StringUtils.isNotBlank(attendingDoctor));
        return purged;
    }
}
