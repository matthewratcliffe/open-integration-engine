import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readSet, writeSet, tlsDefaults, isSupported, isListener } from './logic.mjs';

test('readSet normalises the XStream linked-hash-set shape', () => {
    assert.deepEqual(readSet(null), []);
    assert.deepEqual(readSet({ string: null }), []);
    assert.deepEqual(readSet({ string: ['a', 'b'] }), ['a', 'b']);
    assert.deepEqual(readSet({ string: 'solo' }), ['solo']); // single-element wrap
    // The returned array is a copy: mutating it does not touch the source.
    const src = { string: ['a'] };
    const out = readSet(src);
    out.push('b');
    assert.deepEqual(src.string, ['a']);
});

test('writeSet wraps into the set shape and round-trips with readSet', () => {
    assert.deepEqual(writeSet([]), { '@class': 'linked-hash-set', string: [] });
    assert.deepEqual(writeSet(['a']), { '@class': 'linked-hash-set', string: ['a'] });
    assert.deepEqual(readSet(writeSet(['a', 'b'])), ['a', 'b']);
    // string is a slice-copy of the input.
    const input = ['a'];
    const wrapped = writeSet(input);
    input.push('b');
    assert.deepEqual(wrapped.string, ['a']);
});

test('tlsDefaults mirror the Java constructor', () => {
    const d = tlsDefaults('3.12');
    assert.equal(d['@version'], '3.12');
    assert.equal(d.isTlsManagerEnabled, false);
    assert.equal(d.crlMode, 'HARD_FAIL');
    assert.equal(d.ocspMode, 'HARD_FAIL');
    assert.equal(d.clientAuthMode, 'NONE');
    assert.equal(d.isHostnameVerificationEnabled, true);
    assert.deepEqual(d.trustedServerCertificates, { '@class': 'linked-hash-set', string: [] });
    assert.deepEqual(d.usedProtocols, { '@class': 'linked-hash-set', string: [] });
    assert.equal(d.serverCertificateAlias, null);
    assert.equal(tlsDefaults(undefined)['@version'], undefined);
});

test('isSupported matches the connector allow-list', () => {
    assert.equal(isSupported('HTTP Listener'), true);
    assert.equal(isSupported('TCP Sender'), true);
    assert.equal(isSupported('JMS Sender'), false);
    assert.equal(isSupported('anything', ['anything']), true);
});

test('isListener detects the Listener suffix', () => {
    assert.equal(isListener('HTTP Listener'), true);
    assert.equal(isListener('HTTP Sender'), false);
    assert.equal(isListener(''), false);
    assert.equal(isListener(null), false);
});
