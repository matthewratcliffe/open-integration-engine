/*
 * Volume Monitor console UI.
 *
 * One surface: a "Monitoring" page listing every volume rule with its current
 * state, plus the rule editor. The nav item carries the fault count so a quiet
 * feed is visible without opening the page.
 *
 * Plain ES module JavaScript: no JSX, no imports, no build step. The console
 * dynamically import()s this file and passes its own `platform` into
 * register(), which is how the bundled plugins receive it. Importing
 * @oie/web-shell here would depend on the page's import map and risks a second
 * framework instance registering into a dead registry, which fails silently.
 */

const API = '/api/volumemonitor';

/*
 * The engine serialises a Map through XStream, so "JSON" arrives shaped like
 * {"linked-hash-map":{"entry":[{"string":"ok","boolean":true}, ...]}} rather
 * than a plain object. Same reason the shell scripts in this repo parse XML.
 *
 * Entry shapes seen in practice:
 *   {"string":"ok","boolean":true}          key and value of different types
 *   {"string":["channelId",null]}           both strings, so XStream collapses them
 *   {"string":"faults","int":0}
 */
function decode(node) {
    if (node === null || typeof node !== 'object') return node;
    if (Array.isArray(node)) return node.map(decode);

    const keys = Object.keys(node);

    // A map: {"linked-hash-map": {...}} / {"map": {...}}
    const mapKey = keys.find(k => k === 'map' || k.endsWith('-map'));
    if (mapKey && keys.length === 1) {
        const inner = node[mapKey];
        if (inner === null) return {};
        let entries = inner.entry;
        if (entries == null) return {};
        if (!Array.isArray(entries)) entries = [entries];
        const out = {};
        for (const entry of entries) {
            const [k, v] = decodeEntry(entry);
            out[k] = v;
        }
        return out;
    }

    /*
     * A collection: {"list": {"string": [...]}} / {"list": null}.
     *
     * The name test is deliberately loose. An immutable list (List.of(), .toList())
     * serialises as java.util.ImmutableCollections$ListN wrapping a CollSer blob,
     * whose elements cannot be recovered at all -- the server side must never send
     * one. Recognising the shape anyway means the view gets an empty array instead
     * of an object, so a stray one degrades into "nothing to show" rather than
     * "(x || []).map is not a function".
     */
    const listKey = keys.find(k => k === 'list' || k === 'set'
        || /(^|[.$_-])(list|set|collection)/i.test(k));
    if (listKey && keys.length === 1) {
        const inner = node[listKey];
        if (inner === null) return [];
        const elemKey = Object.keys(inner).find(k => !k.startsWith('@'));
        if (elemKey === undefined) return [];
        const items = inner[elemKey];
        // An unrecoverable payload (CollSer) yields an object, not an array of
        // elements. Return [] rather than smuggling it through as a fake list.
        if (items !== null && typeof items === 'object' && !Array.isArray(items)) {
            return [];
        }
        return (Array.isArray(items) ? items : [items]).map(decode);
    }

    const SCALARS = new Set(['string', 'boolean', 'int', 'long', 'double', 'float',
        'short', 'byte', 'char', 'big-decimal', 'big-int']);
    if (keys.length === 1 && SCALARS.has(keys[0])) {
        const v = node[keys[0]];
        return Array.isArray(v) ? v.map(decode) : decode(v);
    }

    const out = {};
    for (const k of keys) out[k] = decode(node[k]);
    return out;
}

function decodeEntry(entry) {
    if (entry === null || typeof entry !== 'object') return [String(entry), null];
    const props = Object.keys(entry);

    // Collapsed form: one property whose value is [key, value].
    if (props.length === 1 && Array.isArray(entry[props[0]])) {
        const arr = entry[props[0]];
        return [String(arr[0]), arr.length > 1 ? decode(arr[1]) : null];
    }
    const keyVal = entry[props[0]];
    const key = Array.isArray(keyVal) ? String(keyVal[0]) : String(keyVal);
    if (props.length < 2) return [key, null];
    const valueName = props[1];
    return [key, decode({ [valueName]: entry[valueName] })];
}

/*
 * The mirror of decode(): a Map parameter has to arrive XStream-shaped, so a
 * plain JSON object body is rejected outright -- plain JSON gives a 500, the XML
 * map gives a 200. The engine's own scripts in this repo talk XML to the API for
 * the same reason.
 */
function encodeMap(obj) {
    const esc = v => String(v)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
    const entries = Object.keys(obj)
        // An undefined value means "not supplied", which is different from empty:
        // the server leaves a field alone when the key is absent.
        .filter(k => obj[k] !== undefined && obj[k] !== null)
        .map(k => `<entry><string>${esc(k)}</string><string>${esc(obj[k])}</string></entry>`)
        .join('');
    return `<map>${entries}</map>`;
}

const ICON_MONITOR = 'M3 12h4l3-7 4 14 3-7h4';

/*
 * Nav items are read once when the shell mounts: registering one later does not
 * appear, and mutating a registered item's label does not re-render (both
 * verified against a running console). So the fault count has to be known
 * *before* register() runs, which is what this top-level await is for -- the
 * console awaits the module's import, so the fetch completes first.
 *
 * /summary is used rather than /status because it never counts anything: this
 * request is on the critical path for every page load, and it must not be the
 * reason the console feels slow.
 *
 * Kept on a short timeout and never allowed to reject: a plugin that fails to
 * load because a count was slow would be a bad trade for a label.
 */
async function bootstrapSummary() {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 1500);
    try {
        const res = await fetch(`${API}/summary`, {
            headers: { Accept: 'application/json', 'X-Requested-With': 'oie-webadmin' },
            credentials: 'same-origin',
            signal: controller.signal,
        });
        if (!res.ok) return null;
        return decode(await res.json());
    } catch (err) {
        return null;
    } finally {
        clearTimeout(timer);
    }
}

const BOOT = await bootstrapSummary();

/** The nav label, from whatever we learned at boot. */
function navLabel(boot) {
    if (!boot || boot.ok === false) return 'Monitoring';
    const faults = Number(boot.faults) || 0;
    if (faults > 0) return `Monitoring (${faults})`;
    return 'Monitoring';
}

/** How each evaluator state reads on screen, and how alarming it looks. */
const STATE_META = {
    OK: { label: 'OK', tone: 'ok' },
    BREACH: { label: 'Below threshold', tone: 'err' },
    CHANNEL_STOPPED: { label: 'Channel stopped', tone: 'err' },
    ERROR: { label: 'Error', tone: 'err' },
    WARMING_UP: { label: 'Warming up', tone: 'warn' },
    UNKNOWN_CHANNEL: { label: 'Unknown channel', tone: 'warn' },
    OUTSIDE_SCHEDULE: { label: 'Outside hours', tone: 'dim' },
    DISABLED: { label: 'Disabled', tone: 'dim' },
};

const DAYS = [
    { token: 'MONDAY', short: 'Mon' },
    { token: 'TUESDAY', short: 'Tue' },
    { token: 'WEDNESDAY', short: 'Wed' },
    { token: 'THURSDAY', short: 'Thu' },
    { token: 'FRIDAY', short: 'Fri' },
    { token: 'SATURDAY', short: 'Sat' },
    { token: 'SUNDAY', short: 'Sun' },
];

/** One tab-separated row from /status. See the note on VolumeMonitorService. */
function parseResult(row) {
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

/** One tab-separated row from /rules. */
function parseRule(row) {
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

/** One tab-separated row from /channels. */
function parseChannel(row) {
    const f = String(row).split('\t');
    // Normalised because DeployedState.toString() is title case ("Started"),
    // while the enum name is not -- comparing the raw text silently never matched.
    return {
        id: f[0] || '',
        name: f[1] || '',
        state: (f[2] || 'UNKNOWN').toUpperCase(),
    };
}

/** 480 -> "08:00". 1440 is rendered as midnight, which is what it means. */
function minutesToHhmm(minutes) {
    const m = (Number(minutes) || 0) % 1440;
    return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

/** "08:00" -> 480. Anything unparseable is midnight rather than an error. */
function hhmmToMinutes(text) {
    const match = /^(\d{1,2}):(\d{2})$/.exec(String(text || '').trim());
    if (!match) return 0;
    return Math.min(1440, Math.max(0, Number(match[1]) * 60 + Number(match[2])));
}

/**
 * How long ago, as a phrase that already reads correctly on its own.
 *
 * Returns "just now" rather than a duration under a minute and a half, so
 * callers must not append "ago" themselves -- "just now ago" is what that
 * produced. Hence `ago()` below, which is what every caller actually wants.
 */
function since(epochMs) {
    if (!epochMs) return '';
    const seconds = Math.max(0, Math.round((Date.now() - epochMs) / 1000));
    if (seconds < 90) return 'just now';
    const minutes = Math.round(seconds / 60);
    if (minutes < 90) return `${minutes} min ago`;
    const hours = Math.round(minutes / 60);
    if (hours < 36) return `${hours} h ago`;
    return `${Math.round(hours / 24)} d ago`;
}

/** A bare duration, for "continuing for X". */
function duration(epochMs) {
    if (!epochMs) return '';
    const seconds = Math.max(0, Math.round((Date.now() - epochMs) / 1000));
    if (seconds < 90) return 'less than a minute';
    const minutes = Math.round(seconds / 60);
    if (minutes < 90) return `${minutes} min`;
    const hours = Math.round(minutes / 60);
    if (hours < 36) return `${hours} h`;
    return `${Math.round(hours / 24)} d`;
}

export function register(platform) {
    const React = platform.React;
    const h = React.createElement;

    // ------------------------------------------------------------------
    // API helper
    // ------------------------------------------------------------------
    async function call(method, path, body) {
        const headers = {
            Accept: 'application/json',
            'X-Requested-With': 'oie-webadmin',
        };
        if (body !== undefined) {
            headers['Content-Type'] = 'application/xml';
        }
        const res = await fetch(API + path, {
            method,
            headers,
            credentials: 'same-origin',
            body: body === undefined ? undefined : encodeMap(body),
        });

        let payload = null;
        const text = await res.text();
        if (text) {
            try {
                payload = decode(JSON.parse(text));
            } catch (err) {
                payload = { error: text };
            }
        }
        if (!res.ok) {
            const error = new Error(
                (payload && (payload.error || payload.message)) || `HTTP ${res.status}`);
            error.status = res.status;
            error.payload = payload;
            throw error;
        }
        return payload || {};
    }

    // ------------------------------------------------------------------
    // Presentation helpers
    // ------------------------------------------------------------------

    /*
     * The shell's .view class is a flex column with overflow-y: hidden, so child
     * sections flex-shrink to fit the viewport instead of overflowing -- scrollHeight
     * ends up equal to clientHeight and there is nothing to scroll, which bites as
     * soon as the rule editor is open.
     *
     * .view is our own element here, so an inline display/overflow wins over the
     * class. block rather than flex means the sections keep their natural height and
     * the container scrolls.
     */
    const viewStyle = extra => Object.assign({
        display: 'block',
        overflowY: 'auto',
        height: '100%',
        boxSizing: 'border-box',
        padding: '1rem',
    }, extra || {});

    /*
     * Everything below renders with the shell's own classes -- .panel,
     * .panel-header, .panel-body, .field, .hint, .btn, .tag, .mono, .form-grid --
     * rather than inline styling. They carry the app's spacing, borders, radii and
     * both themes, so this view matches the rest of the console and follows it when
     * the theme changes. Inline style is used only for what those classes do not
     * cover, and then in terms of the app's CSS variables.
     */
    function Panel(title, children, opts) {
        const o = opts || {};
        return h('div', { className: 'panel', style: { marginBottom: '11px' } }, [
            title ? h('div', { key: 'h', className: 'panel-header' }, [
                h('span', { key: 't' }, title),
                o.badge || null,
            ]) : null,
            h('div', { key: 'b', className: 'panel-body' }, children),
        ]);
    }

    /**
     * One labelled control in a .form-grid.
     *
     * `opts.span` makes it claim the whole row. The day checkboxes need that:
     * sharing a row with the channel picker put them immediately beside it, so
     * the open dropdown rendered over the top of them.
     */
    function Field(label, control, hint, opts) {
        const o = opts || {};
        return h('div', {
            className: 'field',
            style: o.span ? { gridColumn: '1 / -1' } : undefined,
        }, [
            h('label', { key: 'l' }, label),
            control,
            hint ? h('div', { key: 'h', className: 'hint' }, hint) : null,
        ]);
    }

    function Button(label, onClick, opts) {
        const o = opts || {};
        const classes = ['btn'];
        if (o.primary) classes.push('btn-primary');
        if (o.danger) classes.push('btn-danger');
        return h('button', {
            type: 'button',
            className: classes.join(' '),
            disabled: !!o.disabled,
            title: o.title || '',
            onClick,
            style: { marginRight: '6px' },
        }, label);
    }

    function Banner(kind, children) {
        const token = { error: '--err', warn: '--warn', ok: '--ok', info: '--accent' }[kind]
            || '--accent';
        return h('div', {
            className: 'hint',
            style: {
                background: `color-mix(in srgb, var(${token}) 14%, var(--bg1))`,
                border: `1px solid color-mix(in srgb, var(${token}) 55%, transparent)`,
                borderRadius: 'var(--radius)',
                color: 'var(--text)',
                fontSize: '11px',
                padding: '7px 11px',
                marginBottom: '9px',
                whiteSpace: 'pre-wrap',
            },
        }, children);
    }

    /** A state chip. Colour carries the same information as the words, not instead. */
    function StateChip(state) {
        const meta = STATE_META[state] || { label: state, tone: 'dim' };
        const token = { ok: '--ok', err: '--err', warn: '--warn', dim: '--text-faint' }[meta.tone];
        return h('span', {
            className: 'tag',
            style: {
                background: `color-mix(in srgb, var(${token}) 18%, transparent)`,
                borderColor: `color-mix(in srgb, var(${token}) 55%, transparent)`,
                color: meta.tone === 'dim' ? 'var(--text-dim)' : `var(${token})`,
                whiteSpace: 'nowrap',
            },
            title: state,
        }, meta.label);
    }

    // ------------------------------------------------------------------
    // The Monitoring page
    // ------------------------------------------------------------------
    function MonitoringView() {
        const [status, setStatus] = React.useState(null);
        const [rules, setRules] = React.useState([]);
        const [channels, setChannels] = React.useState([]);
        const [busy, setBusy] = React.useState('');
        const [error, setError] = React.useState(null);
        const [notice, setNotice] = React.useState(null);
        const [editing, setEditing] = React.useState(null);
        const [showSettings, setShowSettings] = React.useState(false);
        const [confirmDelete, setConfirmDelete] = React.useState(null);

        const load = React.useCallback(async (opts) => {
            const o = opts || {};
            setBusy(o.label || 'Loading');
            setError(null);
            try {
                const s = await call('GET', `/status?evaluate=${o.evaluate ? 'true' : 'false'}`);
                setStatus(s);
                const r = await call('GET', '/rules');
                setRules((r.rules || []).map(parseRule));
                const c = await call('GET', '/channels');
                setChannels((c.channels || []).map(parseChannel));
            } catch (err) {
                setError(err.message);
            } finally {
                setBusy('');
            }
        }, []);

        React.useEffect(() => { load(); }, [load]);

        const run = async (label, fn) => {
            setBusy(label);
            setError(null);
            setNotice(null);
            try {
                return await fn();
            } catch (err) {
                setError(err.message);
                return null;
            } finally {
                setBusy('');
            }
        };

        if (status === null) {
            return h('div', { className: 'view', style: viewStyle() },
                busy ? Banner('info', `${busy}…`) : null,
                error ? Banner('error', error) : null);
        }

        const results = (status.results || []).map(parseResult);
        const byRule = {};
        for (const r of results) byRule[r.ruleId] = r;
        const faults = results.filter(r => STATE_META[r.state]
            && ['err'].includes(STATE_META[r.state].tone));

        const rows = [];

        // ---------------- tasks ----------------
        rows.push(h('div', { key: 'tasks', style: { marginBottom: '9px' } }, [
            Button('Check now', () => load({ evaluate: true, label: 'Counting messages' }),
                { primary: true, disabled: !!busy, title: 'Count messages for every rule now' }),
            Button('Add rule', () => {
                setEditing({
                    id: '',
                    name: '',
                    channelId: '',
                    enabled: true,
                    minCount: 1,
                    windowCount: 1,
                    windowUnit: 'HOUR',
                    activeDays: [],
                    activeFromMinute: 0,
                    activeUntilMinute: 0,
                    renotifyMinutes: 60,
                });
                setConfirmDelete(null);
            }, { disabled: !!busy }),
            Button(showSettings ? 'Hide settings' : 'Settings',
                () => setShowSettings(v => !v), { disabled: !!busy }),
        ]));

        if (busy) rows.push(h('div', { key: 'busy' }, Banner('info', `${busy}…`)));
        if (error) rows.push(h('div', { key: 'err' }, Banner('error', error)));
        if (notice) rows.push(h('div', { key: 'note' }, Banner('ok', notice)));

        // ---------------- summary ----------------
        if (rules.length === 0) {
            rows.push(h('div', { key: 'empty' }, Banner('info',
                'No rules yet. Add one to say how much traffic a channel is expected to '
                + 'carry, and this page will report when it falls short.')));
        } else if (faults.length > 0) {
            rows.push(h('div', { key: 'sum' }, Banner('error',
                `${faults.length} of ${results.length} rule(s) are not being met.`)));
        } else {
            rows.push(h('div', { key: 'sum' }, Banner('ok',
                `All ${results.length} rule(s) met`
                + (status.lastPassAt ? `, last checked ${since(status.lastPassAt)}.` : '.'))));
        }

        // ---------------- rules ----------------
        if (rules.length > 0) {
            rows.push(Panel(`Rules (${rules.length})`,
                h('div', {
                    style: {
                        border: '1px solid var(--line)',
                        borderRadius: 'var(--radius)',
                        overflow: 'hidden',
                    },
                }, rules.map((rule, i) => {
                    const result = byRule[rule.id];
                    const state = result ? result.state : (rule.enabled ? 'OK' : 'DISABLED');
                    const isEditing = editing && editing.id === rule.id;
                    return h('div', {
                        key: rule.id,
                        style: {
                            borderTop: i === 0 ? 'none' : '1px solid var(--line)',
                            background: isEditing ? 'var(--bg3)' : 'transparent',
                            padding: '7px 10px',
                        },
                    }, [
                        h('div', {
                            key: 'r',
                            style: { display: 'flex', alignItems: 'center', gap: '10px',
                                flexWrap: 'wrap' },
                        }, [
                            h('div', { key: 'c', style: { flex: '1 1 220px', minWidth: 0 } }, [
                                // The name leads when there is one. A channel can carry
                                // several rules, and then the channel name alone makes
                                // the rows indistinguishable.
                                h('div', { key: 'n', style: { fontWeight: 600 } },
                                    rule.name
                                    || rule.channelName || rule.channelId || '(no channel)'),
                                h('div', { key: 'd', className: 'hint',
                                    style: { marginTop: '2px' } },
                                    (rule.name
                                        ? `${rule.channelName || rule.channelId} · `
                                        : '')
                                    + `at least ${rule.minCount} per `
                                    + (rule.windowCount === 1
                                        ? rule.windowUnit.toLowerCase()
                                        : `${rule.windowCount} ${rule.windowUnit.toLowerCase()}s`)
                                    + (result && result.schedule && result.schedule !== 'always'
                                        ? ` · ${result.schedule}`
                                        : '')),
                            ]),
                            h('div', {
                                key: 'o',
                                className: 'mono',
                                style: { flex: '0 0 auto', fontSize: '11px', textAlign: 'right',
                                    minWidth: '5.5rem' },
                                title: 'Observed in the window / expected',
                            }, result ? `${result.count} / ${rule.minCount}` : '— / '
                                + rule.minCount),
                            h('div', { key: 's', style: { flex: '0 0 auto' } }, StateChip(state)),
                            h('div', { key: 'a', style: { flex: '0 0 auto' } }, [
                                Button(isEditing ? 'Close' : 'Edit',
                                    () => {
                                        setConfirmDelete(null);
                                        setEditing(isEditing ? null : Object.assign({}, rule));
                                    },
                                    { disabled: !!busy }),
                                Button(rule.enabled ? 'Disable' : 'Enable', async () => {
                                    const r = await run(rule.enabled ? 'Disabling' : 'Enabling',
                                        () => call('POST', '/rules',
                                            { id: rule.id, enabled: String(!rule.enabled) }));
                                    if (r && r.ok) load({ label: 'Reloading' });
                                }, { disabled: !!busy }),
                                Button('Delete', () => {
                                    setEditing(null);
                                    setConfirmDelete(rule.id);
                                }, { disabled: !!busy, danger: true }),
                            ]),
                        ]),

                        result && result.detail
                            ? h('div', {
                                key: 'det',
                                className: 'hint',
                                style: { marginTop: '4px' },
                            }, result.detail
                                + (result.faultSince
                                    ? ` Continuing for ${duration(result.faultSince)}.`
                                    : ''))
                            : null,

                        confirmDelete === rule.id
                            ? h('div', { key: 'cd', style: { marginTop: '6px' } }, [
                                Banner('error', 'Delete this rule? The channel keeps running; '
                                    + 'only the expectation about it goes away.'),
                                Button('Delete rule', async () => {
                                    const r = await run('Deleting', () => call('POST',
                                        `/rules/_delete?id=${encodeURIComponent(rule.id)}`));
                                    setConfirmDelete(null);
                                    if (r && r.ok) {
                                        setNotice('Rule deleted.');
                                        load({ label: 'Reloading' });
                                    }
                                }, { danger: true, disabled: !!busy }),
                                Button('Cancel', () => setConfirmDelete(null),
                                    { disabled: !!busy }),
                            ])
                            : null,

                        isEditing
                            ? h('div', { key: 'ed', style: { marginTop: '8px' } },
                                h(RuleEditor, {
                                    rule: editing,
                                    channels,
                                    onCancel: () => setEditing(null),
                                    onSaved: () => {
                                        setEditing(null);
                                        setNotice('Rule saved.');
                                        load({ label: 'Reloading' });
                                    },
                                }))
                            : null,
                    ]);
                }))));
        }

        // A new rule has no row to sit under, so it gets its own panel.
        if (editing && !editing.id) {
            rows.push(h('div', { key: 'new' }, Panel('New rule',
                h(RuleEditor, {
                    rule: editing,
                    channels,
                    onCancel: () => setEditing(null),
                    onSaved: () => {
                        setEditing(null);
                        setNotice('Rule created.');
                        load({ label: 'Reloading' });
                    },
                }))));
        }

        if (showSettings) {
            rows.push(h('div', { key: 'settings' },
                h(SettingsPanel, {
                    status,
                    onSaved: note => {
                        setNotice(note);
                        load({ label: 'Reloading' });
                    },
                })));
        }

        return h('div', { className: 'view', style: viewStyle({ maxWidth: '980px' }) }, rows);
    }

    // ------------------------------------------------------------------
    // Rule editor
    // ------------------------------------------------------------------
    function RuleEditor(props) {
        const { channels, onCancel, onSaved } = props;
        const [form, setForm] = React.useState(() => Object.assign({}, props.rule));
        const [busy, setBusy] = React.useState(false);
        const [error, setError] = React.useState(null);
        const [problems, setProblems] = React.useState([]);

        const set = (key, value) => setForm(f => Object.assign({}, f, { [key]: value }));

        const toggleDay = token => setForm(f => {
            const days = f.activeDays.includes(token)
                ? f.activeDays.filter(d => d !== token)
                : f.activeDays.concat([token]);
            return Object.assign({}, f, { activeDays: days });
        });

        const save = async () => {
            setBusy(true);
            setError(null);
            setProblems([]);
            try {
                const body = {
                    name: form.name || '',
                    channelId: form.channelId,
                    enabled: String(!!form.enabled),
                    minCount: String(form.minCount),
                    windowCount: String(form.windowCount),
                    windowUnit: form.windowUnit,
                    activeDays: form.activeDays.length ? form.activeDays.join(',') : '*',
                    activeFromMinute: String(form.activeFromMinute),
                    activeUntilMinute: String(form.activeUntilMinute),
                    renotifyMinutes: String(form.renotifyMinutes),
                };
                if (form.id) body.id = form.id;
                const r = await call('POST', '/rules', body);
                if (r.ok) {
                    onSaved();
                } else {
                    setError(r.error || 'The rule could not be saved.');
                    setProblems(r.problems || []);
                }
            } catch (err) {
                setError(err.message);
            } finally {
                setBusy(false);
            }
        };

        const allDay = form.activeFromMinute === form.activeUntilMinute;

        return h('div', null, [
            error ? Banner('error', [
                h('div', { key: 'm', style: { fontWeight: 600 } }, error),
                problems.length
                    ? h('ul', { key: 'p', style: { margin: '4px 0 0', paddingLeft: '17px' } },
                        problems.map((p, i) => h('li', { key: i }, p)))
                    : null,
            ]) : null,

            h('div', { key: 'g', className: 'form-grid' }, [
                Field('Name',
                    h('input', {
                        type: 'text',
                        value: form.name || '',
                        placeholder: form.channelId
                            ? (channels.find(c => c.id === form.channelId) || {}).name || ''
                            : 'Pathology results overnight',
                        onInput: e => set('name', e.target.value),
                        style: { minWidth: '260px' },
                    }),
                    'Optional. What this expectation is called in the rule list, the '
                    + 'server log and the event log — worth setting when one channel '
                    + 'carries more than one rule. Defaults to the channel name.'),

                Field('Channel',
                    h('select', {
                        value: form.channelId,
                        onChange: e => set('channelId', e.target.value),
                        style: { minWidth: '260px' },
                    }, [h('option', { key: '', value: '' }, '— select a channel —')].concat(
                        channels.map(c => h('option', { key: c.id, value: c.id },
                            `${c.name}${c.state === 'STARTED' ? '' : ` (${c.state.toLowerCase()})`}`)))),
                    form.channelId && !channels.some(c => c.id === form.channelId)
                        ? 'This channel is not on this server. The rule will report an '
                            + 'unknown channel until it is deployed here.'
                        : null),

                Field('Expect at least',
                    // Wraps rather than clipping: three controls plus two words of text
                    // reach the edge of a grid column, and "weeks" is wider than "hour".
                    h('div', {
                        style: {
                            display: 'flex', alignItems: 'center', gap: '6px',
                            flexWrap: 'wrap',
                        },
                    }, [
                        h('input', {
                            key: 'n',
                            type: 'number',
                            min: '1',
                            value: form.minCount,
                            onInput: e => set('minCount', Number(e.target.value) || 0),
                            style: { width: '7rem' },
                        }),
                        h('span', { key: 'l', className: 'hint' }, 'messages per'),
                        h('input', {
                            key: 'w',
                            type: 'number',
                            min: '1',
                            value: form.windowCount,
                            onInput: e => set('windowCount', Number(e.target.value) || 1),
                            style: { width: '4.5rem' },
                        }),
                        h('select', {
                            key: 'u',
                            value: form.windowUnit,
                            onChange: e => set('windowUnit', e.target.value),
                            // Without a width the control collapses to the arrow plus
                            // one character, so "hour" rendered as "h".
                            style: { minWidth: '6.5rem' },
                        }, [
                            h('option', { key: 'HOUR', value: 'HOUR' },
                                form.windowCount === 1 ? 'hour' : 'hours'),
                            h('option', { key: 'DAY', value: 'DAY' },
                                form.windowCount === 1 ? 'day' : 'days'),
                            h('option', { key: 'WEEK', value: 'WEEK' },
                                form.windowCount === 1 ? 'week' : 'weeks'),
                        ]),
                    ]),
                    'Counted over a rolling window ending now, from messages that arrived '
                    + 'on the source connector — whatever happened to them afterwards.'),

                Field('Active days',
                    h('div', { style: { display: 'flex', gap: '14px', flexWrap: 'wrap' } },
                        DAYS.map(d => h('label', {
                            key: d.token,
                            style: { display: 'flex', alignItems: 'center', gap: '4px' },
                        }, [
                            h('input', {
                                key: 'c',
                                type: 'checkbox',
                                checked: form.activeDays.includes(d.token),
                                onChange: () => toggleDay(d.token),
                            }),
                            d.short,
                        ]))),
                    form.activeDays.length === 0
                        ? 'None selected — checked every day.'
                        : null,
                    { span: true }),

                Field('Active hours',
                    h('div', { style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
                        h('input', {
                            key: 'f',
                            type: 'time',
                            value: minutesToHhmm(form.activeFromMinute),
                            onInput: e => set('activeFromMinute', hhmmToMinutes(e.target.value)),
                        }),
                        h('span', { key: 'd', className: 'hint' }, 'to'),
                        h('input', {
                            key: 'u',
                            type: 'time',
                            value: minutesToHhmm(form.activeUntilMinute),
                            onInput: e => set('activeUntilMinute', hhmmToMinutes(e.target.value)),
                        }),
                    ]),
                    allDay
                        ? 'Equal times mean all day.'
                        : (form.windowCount * (form.windowUnit === 'HOUR' ? 60
                            : form.windowUnit === 'DAY' ? 1440 : 10080) < 1440
                            ? 'Server local time. The whole window must fall inside these '
                                + 'hours, so a one-hour rule starting at '
                                + minutesToHhmm(form.activeFromMinute) + ' is first checked an '
                                + 'hour later — otherwise every morning would report the quiet '
                                + 'hour before opening.'
                            : 'Server local time. A window of a day or more necessarily '
                                + 'reaches back into the quiet hours, so only the moment of '
                                + 'checking is restricted.')),

                Field('Repeat the alert every',
                    h('div', { style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
                        h('input', {
                            type: 'number',
                            min: '0',
                            value: form.renotifyMinutes,
                            onInput: e => set('renotifyMinutes', Number(e.target.value) || 0),
                            style: { width: '7rem' },
                        }),
                        h('span', { className: 'hint' }, 'minutes'),
                    ]),
                    '0 reports each breach once and then stays quiet until it recovers.'),

                Field('Enabled',
                    h('div', null,
                        h('label', {
                            style: { display: 'flex', alignItems: 'center', gap: '6px' },
                        }, h('input', {
                            type: 'checkbox',
                            checked: !!form.enabled,
                            onChange: e => set('enabled', e.target.checked),
                        }))),
                    'A disabled rule is kept but never evaluated.'),
            ]),

            h('div', { key: 'b', style: { marginTop: '9px' } }, [
                Button(form.id ? 'Save rule' : 'Create rule', save,
                    { primary: true, disabled: busy || !form.channelId }),
                Button('Cancel', onCancel, { disabled: busy }),
            ]),
        ]);
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------
    function SettingsPanel(props) {
        const { status, onSaved } = props;
        const [intervalSeconds, setIntervalSeconds] =
            React.useState(status.intervalSeconds || 300);
        const [events, setEvents] = React.useState(status.writeServerEvents !== false);
        const [busy, setBusy] = React.useState(false);
        const [error, setError] = React.useState(null);

        return Panel('Settings', h('div', null, [
            error ? Banner('error', error) : null,
            h('div', { key: 'g', className: 'form-grid' }, [
                Field('Check every',
                    h('div', { style: { display: 'flex', alignItems: 'center', gap: '6px' } }, [
                        h('input', {
                            type: 'number',
                            min: '60',
                            value: intervalSeconds,
                            onInput: e =>
                                setIntervalSeconds(Number(e.target.value) || 60),
                            style: { width: '7rem' },
                        }),
                        h('span', { className: 'hint' }, 'seconds'),
                    ]),
                    'Minimum 60. Each check is one date-ranged count per rule against the '
                    + 'message tables, so this is the plugin’s whole cost. Takes effect '
                    + 'when the server restarts.'),

                Field('Write to the engine event log',
                    h('div', null,
                        h('label', {
                            style: { display: 'flex', alignItems: 'center', gap: '6px' },
                        }, h('input', {
                            type: 'checkbox',
                            checked: events,
                            onChange: e => setEvents(e.target.checked),
                        }))),
                    'Breaches appear under Events, and engine alerts watching server '
                    + 'events can act on them.'),
            ]),
            h('div', { key: 'b', style: { marginTop: '9px' } },
                Button('Save settings', async () => {
                    setBusy(true);
                    setError(null);
                    try {
                        const r = await call('POST', '/settings', {
                            intervalSeconds: String(intervalSeconds),
                            writeServerEvents: String(events),
                        });
                        onSaved(r.note || 'Settings saved.');
                    } catch (err) {
                        setError(err.message);
                    } finally {
                        setBusy(false);
                    }
                }, { primary: true, disabled: busy })),
        ]));
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------
    /*
     * Each registration is attempted independently.
     *
     * The shell calls register() once and lets an exception propagate, so a fault
     * part-way through leaves whatever ran before it registered and silently drops
     * everything after -- which in the git sync plugin meant a sidebar item vanishing
     * with nothing on the page to say why. Isolating each one turns that into a
     * console warning and a single missing item.
     */
    const safely = (label, fn) => {
        try {
            fn();
        } catch (err) {
            console.warn('[volumemonitor] could not register ' + label, err);
        }
    };

    safely('icon', () => {
        platform.registerIcon('volume-monitor', ICON_MONITOR);
    });

    safely('monitoring view', () => {
        platform.registerView('/monitoring', platform.reactView(MonitoringView), {
            title: 'Monitoring',
        });
        // The label carries the fault count as of page load. The nav registry is read
        // once when the shell mounts and mutating a registered label does not
        // re-render, so it cannot tick up live; the page itself is current.
        platform.registerNavItem({
            id: 'volume-monitoring',
            label: navLabel(BOOT),
            icon: 'volume-monitor',
            path: '/monitoring',
            section: 'Plugins',
            order: 70,
        });
    });
}
