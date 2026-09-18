/*
 * SPDX-License-Identifier: MPL-2.0
 */
package org.openintegrationengine.connectors.generator.server;

import org.openintegrationengine.connectors.generator.MessageType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.UUID;

/**
 * Turns a template into a message by replacing every {@code ${...}} placeholder.
 *
 * <p>Four families, and the difference between them is the entire point of the connector:
 *
 * <ul>
 *   <li>{@code ${patient.*}} and {@code ${visit.*}} come from the {@link SyntheticPatient}
 *       this message is about, and are therefore <em>stable</em>: the same patient gives the
 *       same MRN, the same date of birth and the same PV1 in every message, for as long as
 *       the seed is unchanged.</li>
 *   <li>{@code ${message.*}} is the envelope -- the control id, the timestamp, the MSH
 *       fields the channel is configured with -- and changes per message.</li>
 *   <li>{@code ${random.*}} is a fresh draw at every occurrence, for the parts of a message
 *       that genuinely are noise: order numbers, observation values, a code from a list.</li>
 *   <li>{@code ${date.*}} is the clock, with an offset and a format.</li>
 * </ul>
 *
 * <p>An unrecognised placeholder is left exactly as it was written and reported to the
 * caller, rather than replaced with an empty string. A typo then shows up as literal
 * {@code ${patinet.mrn}} in the output and in the preview's warning, instead of as a field
 * that silently went missing.
 *
 * <p>There is no nesting and no escaping: the text between {@code ${} and the next
 * {@code }} is the placeholder. That keeps a template something an integration engineer can
 * read at a glance, and HL7 has no use for either.
 */
final class TemplateRenderer {

    private static final String DEFAULT_DATETIME_FORMAT = "yyyyMMddHHmmss";
    private static final String DEFAULT_DATE_FORMAT = "yyyyMMdd";

    private TemplateRenderer() {
    }

    /**
     * @param unresolved collects every placeholder that was not recognised, in the order
     *                   they were met; may be null
     * @throws IllegalArgumentException if a placeholder is recognised but its arguments are
     *                                  not usable, such as {@code ${random.int:lots}}
     */
    static String render(String template, MessageContext context, Collection<String> unresolved) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(template.length() + 64);
        int index = 0;
        while (true) {
            int start = template.indexOf("${", index);
            if (start < 0) {
                out.append(template, index, template.length());
                return out.toString();
            }
            int end = template.indexOf('}', start + 2);
            if (end < 0) {
                // Unterminated: the rest is text, not a placeholder anyone can act on.
                out.append(template, index, template.length());
                return out.toString();
            }
            out.append(template, index, start);

            String token = template.substring(start + 2, end);
            String value = resolve(token, context);
            if (value == null) {
                if (unresolved != null && !unresolved.contains(token)) {
                    unresolved.add(token);
                }
                out.append(template, start, end + 1);
            } else {
                out.append(value);
            }
            index = end + 1;
        }
    }

    /** @return the replacement, or null if the placeholder is not one of ours */
    private static String resolve(String token, MessageContext context) {
        String name = token;
        String args = null;
        int colon = token.indexOf(':');
        if (colon >= 0) {
            name = token.substring(0, colon);
            args = token.substring(colon + 1);
        }

        SyntheticPatient patient = context.patient;
        MessageType type = context.type;

        switch (name) {
            /* ---- the patient, fixed for the life of the population ---- */
            case "patient.index":
                return patient == null ? null : String.valueOf(patient.getIndex());
            case "patient.id":
                return patient == null ? null : patient.getId();
            case "patient.mrn":
                return patient == null ? null : patient.getMrn();
            case "patient.medicare":
                return patient == null ? null : patient.getMedicare();
            case "patient.accountNumber":
                return patient == null ? null : patient.getAccountNumber();
            case "patient.family":
                return patient == null ? null : patient.getFamily();
            case "patient.given":
                return patient == null ? null : patient.getGiven();
            case "patient.middle":
                return patient == null ? null : patient.getMiddle();
            case "patient.prefix":
                return patient == null ? null : patient.getPrefix();
            case "patient.sex":
                return patient == null ? null : patient.getSex();
            case "patient.dob":
                return patient == null ? null : format(patient.getDateOfBirth(), args, DEFAULT_DATE_FORMAT);
            case "patient.age":
                return patient == null ? null
                        : String.valueOf(Period.between(patient.getDateOfBirth(), context.now.toLocalDate()).getYears());
            case "patient.street":
                return patient == null ? null : patient.getStreet();
            case "patient.suburb":
                return patient == null ? null : patient.getSuburb();
            case "patient.state":
                return patient == null ? null : patient.getState();
            case "patient.postcode":
                return patient == null ? null : patient.getPostcode();
            case "patient.phone":
                return patient == null ? null : patient.getPhone();
            case "patient.mobile":
                return patient == null ? null : patient.getMobile();
            case "patient.maritalStatus":
                return patient == null ? null : patient.getMaritalStatus();
            case "patient.insurer":
                return patient == null ? null : patient.getInsurerCode();
            case "patient.insurerName":
                return patient == null ? null : patient.getInsurerName();
            case "patient.policyNumber":
                return patient == null ? null : patient.getPolicyNumber();
            case "patient.nok.family":
                return patient == null ? null : patient.getNokFamily();
            case "patient.nok.given":
                return patient == null ? null : patient.getNokGiven();
            case "patient.nok.relationship":
                return patient == null ? null : patient.getNokRelationship();
            case "patient.nok.phone":
                return patient == null ? null : patient.getNokPhone();

            /* ---- the visit, equally fixed: one patient, one PV1 ---- */
            case "visit.number":
                return patient == null ? null : patient.getVisitNumber();
            case "visit.class":
                return patient == null ? null : patient.getPatientClass();
            case "visit.location":
                return patient == null ? null : patient.getLocation();
            case "visit.pointOfCare":
                return patient == null ? null : patient.getPointOfCare();
            case "visit.room":
                return patient == null ? null : patient.getRoom();
            case "visit.bed":
                return patient == null ? null : patient.getBed();
            case "visit.facility":
                return patient == null ? null : patient.getFacility();
            case "visit.admitType":
                return patient == null ? null : patient.getAdmitType();
            case "visit.hospitalService":
                return patient == null ? null : patient.getHospitalService();
            case "visit.financialClass":
                return patient == null ? null : patient.getFinancialClass();
            case "visit.admitTime":
                return patient == null ? null : format(patient.getAdmitTime(), args, DEFAULT_DATETIME_FORMAT);
            case "visit.attending.id":
                return patient == null ? null : patient.getAttending().getId();
            case "visit.attending.family":
                return patient == null ? null : patient.getAttending().getFamily();
            case "visit.attending.given":
                return patient == null ? null : patient.getAttending().getGiven();
            case "visit.attending.xcn":
                return patient == null ? null : patient.getAttending().getXcn();
            case "visit.referring.id":
                return patient == null ? null : patient.getReferring().getId();
            case "visit.referring.family":
                return patient == null ? null : patient.getReferring().getFamily();
            case "visit.referring.given":
                return patient == null ? null : patient.getReferring().getGiven();
            case "visit.referring.xcn":
                return patient == null ? null : patient.getReferring().getXcn();

            /* ---- the envelope ---- */
            case "message.controlId":
                return context.controlId;
            case "message.sequence":
                return String.valueOf(context.sequence);
            case "message.datetime":
                return format(context.now, args, DEFAULT_DATETIME_FORMAT);
            case "message.date":
                return format(context.now.toLocalDate(), args, DEFAULT_DATE_FORMAT);
            case "message.type":
                return type == null ? "" : type.getCode();
            case "message.event":
                return type == null ? "" : type.getEvent();
            case "message.structure":
                return type == null ? "" : type.getStructure();
            case "message.version":
                return context.version;
            case "message.processingId":
                return context.processingId;
            case "message.sendingApplication":
                return context.sendingApplication;
            case "message.sendingFacility":
                return context.sendingFacility;
            case "message.receivingApplication":
                return context.receivingApplication;
            case "message.receivingFacility":
                return context.receivingFacility;

            /* ---- noise ---- */
            case "random.uuid":
                return UUID.randomUUID().toString();
            case "random.bool":
                return context.random.nextBoolean() ? "Y" : "N";
            case "random.int":
                return randomInt(args, context);
            case "random.decimal":
                return randomDecimal(args, context);
            case "random.digits":
                return RandomValues.digits(context.random, positive(args, "random.digits", 4));
            case "random.alpha":
                return RandomValues.alpha(context.random, positive(args, "random.alpha", 4));
            case "random.pick":
                return randomPick(args, context);

            /* ---- the clock ---- */
            case "date.now":
                return format(context.now, args, DEFAULT_DATETIME_FORMAT);
            case "date.offset":
                return dateOffset(args, context);

            default:
                return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* argument handling                                                   */
    /* ------------------------------------------------------------------ */

    private static String randomInt(String args, MessageContext context) {
        int[] range = intRange(args, "random.int");
        return String.valueOf(RandomValues.intBetween(context.random, range[0], range[1]));
    }

    private static String randomDecimal(String args, MessageContext context) {
        if (args == null) {
            throw new IllegalArgumentException(
                    "${random.decimal} needs a range and a scale, such as ${random.decimal:3.0-15.0,1}");
        }
        int scale = 2;
        String range = args;
        int comma = args.lastIndexOf(',');
        if (comma >= 0) {
            range = args.substring(0, comma);
            scale = parseInt(args.substring(comma + 1).trim(), "random.decimal", args);
        }
        int dash = range.indexOf('-', 1);
        if (dash < 0) {
            throw new IllegalArgumentException(
                    "${random.decimal:" + args + "} is not a range, such as ${random.decimal:3.0-15.0,1}");
        }
        double min = parseDouble(range.substring(0, dash).trim(), "random.decimal", args);
        double max = parseDouble(range.substring(dash + 1).trim(), "random.decimal", args);
        return RandomValues.decimalBetween(context.random, min, max, scale);
    }

    private static String randomPick(String args, MessageContext context) {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException(
                    "${random.pick} needs a comma separated list, such as ${random.pick:CH,HM,MB}");
        }
        // -1 so a trailing comma means a deliberate empty option rather than being dropped.
        return RandomValues.pick(context.random, args.split(",", -1));
    }

    /**
     * {@code +7d}, {@code -2h}, {@code 30m} -- optionally followed by a format, as in
     * {@code ${date.offset:-2h:yyyyMMdd}}.
     */
    private static String dateOffset(String args, MessageContext context) {
        if (args == null || args.isEmpty()) {
            throw new IllegalArgumentException(
                    "${date.offset} needs an offset, such as ${date.offset:-2h} or ${date.offset:+7d:yyyyMMdd}");
        }
        String offset = args;
        String pattern = null;
        int colon = args.indexOf(':');
        if (colon >= 0) {
            offset = args.substring(0, colon);
            pattern = args.substring(colon + 1);
        }

        char unit = offset.charAt(offset.length() - 1);
        String amountText = offset.substring(0, offset.length() - 1);
        if (amountText.startsWith("+")) {
            amountText = amountText.substring(1);
        }
        long amount = parseLong(amountText, "date.offset", args);

        LocalDateTime moment;
        switch (unit) {
            case 's':
                moment = context.now.plusSeconds(amount);
                break;
            case 'm':
                moment = context.now.plusMinutes(amount);
                break;
            case 'h':
                moment = context.now.plusHours(amount);
                break;
            case 'd':
                moment = context.now.plusDays(amount);
                break;
            case 'w':
                moment = context.now.plusWeeks(amount);
                break;
            default:
                throw new IllegalArgumentException("${date.offset:" + args
                        + "} has no unit: use s, m, h, d or w, as in ${date.offset:-2h}");
        }
        return format(moment, pattern, DEFAULT_DATETIME_FORMAT);
    }

    private static int[] intRange(String args, String placeholder) {
        if (args == null) {
            throw new IllegalArgumentException(
                    "${" + placeholder + "} needs a range, such as ${" + placeholder + ":1-100}");
        }
        int dash = args.indexOf('-', 1);
        if (dash < 0) {
            throw new IllegalArgumentException("${" + placeholder + ":" + args
                    + "} is not a range, such as ${" + placeholder + ":1-100}");
        }
        return new int[] {parseInt(args.substring(0, dash).trim(), placeholder, args),
                parseInt(args.substring(dash + 1).trim(), placeholder, args)};
    }

    private static int positive(String args, String placeholder, int fallback) {
        if (args == null || args.isEmpty()) {
            return fallback;
        }
        int value = parseInt(args.trim(), placeholder, args);
        if (value <= 0 || value > 512) {
            throw new IllegalArgumentException("${" + placeholder + ":" + args
                    + "} must be a length between 1 and 512");
        }
        return value;
    }

    private static int parseInt(String text, String placeholder, String args) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("${" + placeholder + ":" + args + "} is not a number", e);
        }
    }

    private static long parseLong(String text, String placeholder, String args) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("${" + placeholder + ":" + args + "} is not a number", e);
        }
    }

    private static double parseDouble(String text, String placeholder, String args) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("${" + placeholder + ":" + args + "} is not a number", e);
        }
    }

    private static String format(LocalDateTime moment, String pattern, String fallback) {
        return moment.format(formatter(pattern, fallback));
    }

    private static String format(LocalDate date, String pattern, String fallback) {
        return date.format(formatter(pattern, fallback));
    }

    private static DateTimeFormatter formatter(String pattern, String fallback) {
        String effective = pattern == null || pattern.isEmpty() ? fallback : pattern;
        try {
            return DateTimeFormatter.ofPattern(effective);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + effective + "\" is not a date format", e);
        }
    }
}
