import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
    loadSamples, loadMessageTypes, loadKnownPlaceholders, placeholdersIn, segments, field,
} from './samples.mjs';

const samples = loadSamples();
const types = loadMessageTypes();
const known = loadKnownPlaceholders();

test('every message type ships a sample, and every sample belongs to a type', () => {
    assert.ok(Object.keys(types).length >= 8, 'MessageType.java parsed');
    for (const name of Object.keys(types)) {
        assert.ok(samples[name], `samples/${name}.hl7 exists`);
    }
    for (const name of Object.keys(samples)) {
        assert.ok(types[name], `${name}.hl7 has a MessageType constant`);
    }
});

test('every placeholder used in a sample is one the renderer resolves', () => {
    for (const [name, sample] of Object.entries(samples)) {
        for (const placeholder of placeholdersIn(sample)) {
            assert.ok(placeholder.terminated, `${name}: ${placeholder.token} is closed`);
            assert.ok(known.has(placeholder.name),
                `${name}: \${${placeholder.name}} is handled by TemplateRenderer`);
        }
    }
});

test('MSH carries the envelope, and MSH-9 comes from the message type', () => {
    for (const [name, sample] of Object.entries(samples)) {
        assert.ok(sample.startsWith('MSH|^~\\&|'), `${name}: starts with MSH and the standard delimiters`);
        assert.equal(field(sample, 'MSH', 3), '${message.sendingApplication}', `${name}: MSH-3`);
        assert.equal(field(sample, 'MSH', 4), '${message.sendingFacility}', `${name}: MSH-4`);
        assert.equal(field(sample, 'MSH', 5), '${message.receivingApplication}', `${name}: MSH-5`);
        assert.equal(field(sample, 'MSH', 6), '${message.receivingFacility}', `${name}: MSH-6`);
        assert.equal(field(sample, 'MSH', 7), '${message.datetime}', `${name}: MSH-7`);
        assert.equal(field(sample, 'MSH', 9),
            '${message.type}^${message.event}^${message.structure}', `${name}: MSH-9`);
        assert.equal(field(sample, 'MSH', 10), '${message.controlId}', `${name}: MSH-10`);
        assert.equal(field(sample, 'MSH', 11), '${message.processingId}', `${name}: MSH-11`);
        assert.equal(field(sample, 'MSH', 12), '${message.version}', `${name}: MSH-12`);
    }
});

test('PID puts the patient where HL7 says it goes', () => {
    // Every sample with a PID, which is all of them but the master file notification.
    const withPid = Object.entries(samples).filter(([, s]) => segments(s).some((x) => x.startsWith('PID|')));
    assert.equal(withPid.length, Object.keys(samples).length - 1);

    for (const [name, sample] of withPid) {
        assert.match(field(sample, 'PID', 3), /^\$\{patient\.mrn\}\^\^\^/, `${name}: PID-3 starts with the MRN`);
        assert.ok(field(sample, 'PID', 3).includes('${patient.medicare}'), `${name}: PID-3 carries the Medicare number`);
        assert.equal(field(sample, 'PID', 5),
            '${patient.family}^${patient.given}^${patient.middle}^^${patient.prefix}', `${name}: PID-5`);
        assert.equal(field(sample, 'PID', 7), '${patient.dob}', `${name}: PID-7 date of birth`);
        assert.equal(field(sample, 'PID', 8), '${patient.sex}', `${name}: PID-8 sex`);
        assert.ok(field(sample, 'PID', 11).includes('${patient.suburb}'), `${name}: PID-11 address`);
        assert.equal(field(sample, 'PID', 18), '${patient.accountNumber}', `${name}: PID-18 account number`);
    }
});

test('PV1 puts the visit where HL7 says it goes', () => {
    const withPv1 = Object.entries(samples).filter(([, s]) => segments(s).some((x) => x.startsWith('PV1|')));
    assert.ok(withPv1.length >= 7);

    for (const [name, sample] of withPv1) {
        assert.equal(field(sample, 'PV1', 2), '${visit.class}', `${name}: PV1-2 patient class`);
        assert.equal(field(sample, 'PV1', 3), '${visit.location}', `${name}: PV1-3 assigned location`);
        assert.equal(field(sample, 'PV1', 7), '${visit.attending.xcn}', `${name}: PV1-7 attending doctor`);
        assert.equal(field(sample, 'PV1', 8), '${visit.referring.xcn}', `${name}: PV1-8 referring doctor`);
        assert.equal(field(sample, 'PV1', 19), '${visit.number}', `${name}: PV1-19 visit number`);
        assert.equal(field(sample, 'PV1', 20), '${visit.financialClass}', `${name}: PV1-20 financial class`);
        assert.equal(field(sample, 'PV1', 44), '${visit.admitTime}', `${name}: PV1-44 admit date/time`);
    }

    // The discharge is the one that also fills PV1-36 and PV1-45.
    assert.ok(field(samples.ADT_A03, 'PV1', 36).startsWith('${random.pick:'), 'A03: PV1-36 discharge disposition');
    assert.equal(field(samples.ADT_A03, 'PV1', 45), '${date.now}', 'A03: PV1-45 discharge date/time');
});

test('placeholder arguments are the shapes the renderer parses', () => {
    for (const [name, sample] of Object.entries(samples)) {
        for (const { name: key, args } of placeholdersIn(sample)) {
            if (key === 'random.int') {
                assert.match(args, /^-?\d+-\d+$/, `${name}: ${key}:${args}`);
            } else if (key === 'random.decimal') {
                assert.match(args, /^-?[\d.]+-[\d.]+,\d+$/, `${name}: ${key}:${args}`);
            } else if (key === 'random.digits' || key === 'random.alpha') {
                assert.match(args, /^\d+$/, `${name}: ${key}:${args}`);
            } else if (key === 'random.pick') {
                assert.ok(args.includes(','), `${name}: ${key} has more than one option`);
            } else if (key === 'date.offset') {
                assert.match(args, /^[+-]?\d+[smhdw](:.+)?$/, `${name}: ${key}:${args}`);
            }
        }
    }
});

test('a sample is a plausible message of its type', () => {
    // Segments a receiver of each type would expect to find, beyond MSH.
    const expected = {
        ADT_A01: ['EVN', 'PID', 'NK1', 'PV1', 'DG1', 'IN1'],
        ADT_A03: ['EVN', 'PID', 'PV1', 'DG1'],
        ADT_A08: ['EVN', 'PID', 'PV1'],
        ORM_O01: ['PID', 'PV1', 'ORC', 'OBR'],
        ORU_R01: ['PID', 'PV1', 'ORC', 'OBR', 'OBX'],
        SIU_S12: ['SCH', 'PID', 'RGS', 'AIS', 'AIL', 'AIP'],
        DFT_P03: ['EVN', 'PID', 'PV1', 'FT1'],
        MFN_M02: ['MFI', 'MFE', 'STF', 'PRA'],
    };
    for (const [name, required] of Object.entries(expected)) {
        const present = segments(samples[name]).map((line) => line.slice(0, 3));
        for (const segment of required) {
            assert.ok(present.includes(segment), `${name}: has a ${segment} segment`);
        }
    }
});
