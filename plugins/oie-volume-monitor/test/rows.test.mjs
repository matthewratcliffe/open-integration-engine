import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
    parseResult, parseRule, parseChannel, minutesToHhmm, hhmmToMinutes,
} from './rows.mjs';

const TAB = '\t';

test('status row parser', () => {
    // Captured verbatim from the live engine.
    const row = [
        'de8e3037-f0de-4198-9a80-92af513b1980',
        '8f2c1a44-7c1e-4d6b-9a2f-3b5e6d7c8a91',
        'http-ingest-echo',
        'BREACH',
        '0', '5',
        '5 per hour',
        'always',
        '0 in the last hour, expected at least 5 (5 short).',
        '1789715750096', '1789715755899',
    ].join(TAB);

    const r = parseResult(row);
    assert.equal(r.channelName, 'http-ingest-echo');
    assert.equal(r.state, 'BREACH');
    assert.equal(r.count, 0);
    assert.equal(r.minCount, 5);
    assert.equal(r.rule, '5 per hour');
    assert.equal(r.detail, '0 in the last hour, expected at least 5 (5 short).');
    assert.equal(r.faultSince, 1789715750096);
    console.log('ok   a real breach row');

    // A count of zero must survive: `Number('0') || 0` is the trap that would turn
    // a legitimately empty window into a falsy-then-zero and hide the breach detail.
    assert.equal(parseResult(['a', 'b', 'c', 'OK', '0', '5'].join(TAB)).count, 0);
    console.log('ok   zero count preserved');

    // A healthy row has no fault start.
    const ok = parseResult(['id', 'ch', 'name', 'OK', '6', '5', '5 per hour', 'always',
        '6 in the last hour.', '0', '1789715945589'].join(TAB));
    assert.equal(ok.state, 'OK');
    assert.equal(ok.faultSince, 0);
    console.log('ok   a healthy row');

    // The name is appended, so a row without one still parses and every earlier
    // index stays where it was -- which is the reason it was appended.
    assert.equal(r.name, '');
    const named = parseResult([...row.split(TAB), 'Overnight pathology'].join(TAB));
    assert.equal(named.name, 'Overnight pathology');
    assert.equal(named.state, 'BREACH');
    assert.equal(named.count, 0);
    console.log('ok   optional trailing name');

    // Truncated rows must render as rows, not throw inside the view.
    const short = parseResult('just-an-id');
    assert.equal(short.ruleId, 'just-an-id');
    assert.equal(short.state, 'OK');
    assert.equal(short.count, 0);
    console.log('ok   a truncated row degrades');

    console.log('\nall status-row tests passed');
});

test('rule row parser', () => {
    const row = [
        'de8e3037', '8f2c1a44', 'http-ingest-echo', 'true',
        '5', '1', 'HOUR', '*', '0', '0', '60',
    ].join(TAB);
    const r = parseRule(row);
    assert.equal(r.enabled, true);
    assert.equal(r.minCount, 5);
    assert.equal(r.windowUnit, 'HOUR');
    assert.deepEqual(r.activeDays, []);
    assert.equal(r.renotifyMinutes, 60);
    console.log('ok   an always-on rule');

    const scheduled = parseRule(['id', 'ch', 'name', 'false', '100', '1', 'DAY',
        'MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY', '480', '1080', '0'].join(TAB));
    assert.equal(scheduled.enabled, false);
    assert.deepEqual(scheduled.activeDays,
        ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']);
    assert.equal(scheduled.activeFromMinute, 480);
    assert.equal(scheduled.activeUntilMinute, 1080);
    // 0 means "report once and stay quiet", which must not be replaced by the
    // default of 60 -- that would silently turn the mute into hourly nagging.
    assert.equal(scheduled.renotifyMinutes, 0);
    console.log('ok   a scheduled, disabled rule with renotify off');

    // enabled defaults to true for anything that is not the literal "false", so a
    // rule saved by an older build cannot come back silently switched off.
    assert.equal(parseRule(['id', 'ch', 'n'].join(TAB)).enabled, true);
    console.log('ok   enabled defaults on');

    // A v1 row has no name field at all; a v2 row does.
    assert.equal(parseRule(row).name, '');
    const named = parseRule([...row.split(TAB), 'Overnight pathology'].join(TAB));
    assert.equal(named.name, 'Overnight pathology');
    assert.equal(named.minCount, 5);
    console.log('ok   optional trailing name');

    console.log('\nall rule-row tests passed');
});

test('channel row parser', () => {
    // DeployedState.toString() is title case; the UI compares against STARTED.
    const c = parseChannel(['8f2c1a44', 'http-ingest-echo', 'Started'].join(TAB));
    assert.equal(c.state, 'STARTED');
    console.log('ok   title-case state normalised');

    assert.equal(parseChannel(['id', 'name'].join(TAB)).state, 'UNKNOWN');
    console.log('ok   missing state');

    console.log('\nall channel-row tests passed');
});

test('time of day conversion', () => {
    assert.equal(minutesToHhmm(0), '00:00');
    assert.equal(minutesToHhmm(480), '08:00');
    assert.equal(minutesToHhmm(1080), '18:00');
    assert.equal(minutesToHhmm(1439), '23:59');
    // 1440 is the end of the day, which on a clock is midnight. Rendering it as
    // "24:00" would be rejected by <input type="time"> and blank the field.
    assert.equal(minutesToHhmm(1440), '00:00');
    console.log('ok   minutes to clock time');

    assert.equal(hhmmToMinutes('08:00'), 480);
    assert.equal(hhmmToMinutes('8:00'), 480);
    assert.equal(hhmmToMinutes('23:59'), 1439);
    assert.equal(hhmmToMinutes('00:00'), 0);
    console.log('ok   clock time to minutes');

    // An empty or malformed time is midnight, not NaN: NaN would reach the server
    // as "NaN", parse back to the fallback, and leave the form disagreeing with
    // what was stored.
    assert.equal(hhmmToMinutes(''), 0);
    assert.equal(hhmmToMinutes(undefined), 0);
    assert.equal(hhmmToMinutes('nonsense'), 0);
    console.log('ok   bad input is midnight, never NaN');

    // Round trip for every minute of the day.
    for (let m = 0; m < 1440; m++) {
        assert.equal(hhmmToMinutes(minutesToHhmm(m)), m);
    }
    console.log('ok   round trip for all 1440 minutes');

    console.log('\nall time tests passed');
});
