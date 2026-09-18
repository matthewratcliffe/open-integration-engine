/*
 * Mirrors the row parsers and time helpers in webadmin/web/plugin.js.
 *
 * /status, /rules and /channels each send a list of tab-separated strings
 * rather than a list of objects, because XStream renders a list of maps in a
 * shape the console's decoder cannot reliably unpick. These functions are the
 * other half of that contract, so they are worth testing directly.
 */

export function parseResult(row) {
    const f = String(row).split('\t');
    return {
        ruleId: f[0] || '',
        channelId: f[1] || '',
        channelName: f[2] || '',
        state: f[3] || 'OK',
        count: Number(f[4]) || 0,
        minCount: Number(f[5]) || 0,
        rule: f[6] || '',
        schedule: f[7] || '',
        detail: f[8] || '',
        faultSince: Number(f[9]) || 0,
        evaluatedAt: Number(f[10]) || 0,
        name: f[11] || '',
    };
}

export function parseRule(row) {
    const f = String(row).split('\t');
    return {
        id: f[0] || '',
        channelId: f[1] || '',
        channelName: f[2] || '',
        enabled: f[3] !== 'false',
        minCount: Number(f[4]) || 1,
        windowCount: Number(f[5]) || 1,
        windowUnit: f[6] || 'HOUR',
        activeDays: !f[7] || f[7] === '*' ? [] : f[7].split(','),
        activeFromMinute: Number(f[8]) || 0,
        activeUntilMinute: f[9] === undefined ? 1440 : (Number(f[9]) || 0),
        renotifyMinutes: f[10] === undefined ? 60 : (Number(f[10]) || 0),
        name: f[11] || '',
    };
}

export function parseChannel(row) {
    const f = String(row).split('\t');
    return {
        id: f[0] || '',
        name: f[1] || '',
        state: (f[2] || 'UNKNOWN').toUpperCase(),
    };
}

export function minutesToHhmm(minutes) {
    const m = (Number(minutes) || 0) % 1440;
    return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

export function hhmmToMinutes(text) {
    const match = /^(\d{1,2}):(\d{2})$/.exec(String(text || '').trim());
    if (!match) return 0;
    return Math.min(1440, Math.max(0, Number(match[1]) * 60 + Number(match[2])));
}
