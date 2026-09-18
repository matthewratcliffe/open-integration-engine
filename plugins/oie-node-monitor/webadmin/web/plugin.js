/*
 * Node Monitor console UI.
 *
 * One surface: a "Nodes" page with a card per engine -- online or offline, how
 * long it has been up, CPU, heap, each disk it writes to, threads, what its
 * channels are doing, and how many messages it has taken with the rate right
 * now and an hour of history behind it.
 *
 * Everything comes from this engine's own /api/nodemonitor, which reads samples
 * every node writes about itself into the shared database. So the page is
 * same-origin, needs no second login, and shows a node that has stopped
 * answering -- with its last sample and when it was taken, which is the moment
 * you most want to look at it.
 *
 * Plain ES module JavaScript: no JSX, no imports, no build step. The console
 * dynamically import()s this file and passes its own `platform` into
 * register(), which is how the bundled plugins receive it. Importing
 * @oie/web-shell here would depend on the page's import map and risks a second
 * framework instance registering into a dead registry, which fails silently.
 */

const API = '/api/nodemonitor';

/*
 * The engine serialises a Map through XStream, so "JSON" arrives shaped like
 * {"linked-hash-map":{"entry":[{"string":"ok","boolean":true}, ...]}} rather
 * than a plain object. Same reason the shell scripts in this repo parse XML.
 */
function decode(node) {
    if (node === null || typeof node !== 'object') return node;
    if (Array.isArray(node)) return node.map(decode);

    const keys = Object.keys(node);

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

    const listKey = keys.find(k => k === 'list' || k === 'set'
        || /(^|[.$_-])(list|set|collection)/i.test(k));
    if (listKey && keys.length === 1) {
        const inner = node[listKey];
        if (inner === null) return [];
        const elemKey = Object.keys(inner).find(k => !k.startsWith('@'));
        if (elemKey === undefined) return [];
        const items = inner[elemKey];
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
 * The mirror of decode(): a Map parameter has to arrive XStream-shaped. A plain
 * JSON object body gives a 500; the XML map gives a 200.
 */
function encodeMap(obj) {
    const esc = v => String(v)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
    const entries = Object.keys(obj)
        .filter(k => obj[k] !== undefined && obj[k] !== null)
        .map(k => `<entry><string>${esc(k)}</string><string>${esc(obj[k])}</string></entry>`)
        .join('');
    return `<map>${entries}</map>`;
}

/* Rows arrive tab-separated; see Tsv.java for why they are not nested objects. */
function rows(list, names) {
    return (list || []).map(line => {
        const parts = String(line).split('\t');
        const out = {};
        names.forEach((name, i) => { out[name] = parts[i] === undefined ? '' : parts[i]; });
        return out;
    });
}

const NODE_FIELDS = ['nodeId', 'name', 'role', 'version', 'online', 'lastSampleAt',
    'uptimeMs', 'cpuProcess', 'cpuSystem', 'load', 'heapUsed', 'heapMax', 'heapPct',
    'nonHeapUsed', 'threads', 'peakThreads', 'deployed', 'started', 'paused', 'stopped',
    'other', 'queued', 'received', 'sent', 'errored', 'filtered', 'receivedRate',
    'sentRate', 'erroredRate', 'osName', 'osArch', 'jvmVersion', 'isThis'];
const VOLUME_FIELDS = ['nodeId', 'path', 'usable', 'total', 'usedPct'];
const HISTORY_FIELDS = ['nodeId', 'timestamp', 'cpu', 'heapPct', 'received', 'sent',
    'errored', 'queued'];

const num = v => {
    const n = Number(v);
    return Number.isFinite(n) ? n : 0;
};

function bytes(value) {
    const n = num(value);
    if (n <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    let v = n;
    while (v >= 1024 && i < units.length - 1) {
        v /= 1024;
        i++;
    }
    return `${v >= 100 || i === 0 ? Math.round(v) : v.toFixed(1)} ${units[i]}`;
}

function duration(ms) {
    const seconds = Math.max(0, Math.round(num(ms) / 1000));
    if (seconds < 90) return `${seconds}s`;
    const minutes = Math.round(seconds / 60);
    if (minutes < 90) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    if (hours < 48) return `${hours}h ${minutes % 60}m`;
    return `${Math.floor(hours / 24)}d ${hours % 24}h`;
}

function ago(epochMs) {
    const ms = Number(epochMs);
    if (!ms) return 'never';
    return `${duration(Date.now() - ms)} ago`;
}

const ICON_NODES = 'M4 5h16M4 12h16M4 19h16M7 5v0M7 12v0M7 19v0';

export function register(platform) {
    const React = platform.React;
    const h = React.createElement;

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
            throw error;
        }
        if (payload && payload.ok === false) {
            throw new Error(payload.error || 'the server rejected that');
        }
        return payload || {};
    }

    // ------------------------------------------------------------------
    // Presentation, in the shell's own classes and CSS variables so the page
    // follows the app's spacing, borders and both themes.
    // ------------------------------------------------------------------

    const viewStyle = {
        display: 'block',
        overflowY: 'auto',
        height: '100%',
        boxSizing: 'border-box',
        padding: '1rem',
    };

    function Panel(title, children, right) {
        return h('div', { className: 'panel', style: { marginBottom: '11px' } }, [
            title ? h('div', { key: 'h', className: 'panel-header' }, [
                h('span', { key: 't' }, title),
                right || null,
            ]) : null,
            h('div', { key: 'b', className: 'panel-body' }, children),
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
            style: {
                border: `1px solid var(${token})`,
                borderLeftWidth: '3px',
                borderRadius: '4px',
                padding: '8px 10px',
                marginBottom: '10px',
                fontSize: '0.9em',
            },
        }, children);
    }

    /*
     * A labelled bar. `pct` below zero means "not measured" -- the JVM reports a
     * negative CPU load until it has two readings to compare -- and that is drawn
     * as an empty track with a dash rather than as 0%, which would be a claim.
     */
    function Meter(label, pct, caption, warnAt) {
        const measured = pct >= 0;
        const value = Math.max(0, Math.min(100, pct));
        const token = !measured ? '--border'
            : (warnAt && value >= warnAt ? '--warn' : '--accent');
        return h('div', { style: { marginBottom: '8px' } }, [
            h('div', {
                key: 'l',
                style: {
                    display: 'flex',
                    justifyContent: 'space-between',
                    fontSize: '0.85em',
                    marginBottom: '3px',
                },
            }, [
                h('span', { key: 'a' }, label),
                h('span', { key: 'b', className: 'mono' },
                    measured ? `${value.toFixed(0)}%` : '—'),
            ]),
            h('div', {
                key: 'track',
                style: {
                    height: '6px',
                    borderRadius: '3px',
                    background: 'var(--border)',
                    overflow: 'hidden',
                },
            }, h('div', {
                style: {
                    width: `${measured ? value : 0}%`,
                    height: '100%',
                    background: `var(${token})`,
                },
            })),
            caption ? h('div', {
                key: 'c',
                className: 'hint',
                style: { marginTop: '2px' },
            }, caption) : null,
        ]);
    }

    /*
     * Inline SVG, no chart library: one polyline over a normalised series. The
     * console's bundle is not ours to add a dependency to, and a sparkline is
     * twenty lines of arithmetic.
     */
    function Spark(series, opts) {
        const o = opts || {};
        const width = o.width || 260;
        const height = o.height || 34;
        const points = (series || []).filter(v => Number.isFinite(v));
        if (points.length < 2) {
            return h('div', { className: 'hint', style: { height: `${height}px` } },
                'not enough history yet');
        }
        const max = Math.max(...points, o.min === undefined ? 0 : o.min);
        const min = Math.min(...points, 0);
        const span = max - min || 1;
        const step = width / (points.length - 1);
        const path = points
            .map((v, i) => `${(i * step).toFixed(1)},${(height - ((v - min) / span) * height).toFixed(1)}`)
            .join(' ');
        return h('svg', {
            width: '100%',
            height,
            viewBox: `0 0 ${width} ${height}`,
            preserveAspectRatio: 'none',
            style: { display: 'block' },
        }, [
            h('polyline', {
                key: 'p',
                points: path,
                fill: 'none',
                stroke: `var(${o.token || '--accent'})`,
                strokeWidth: 1.5,
                vectorEffect: 'non-scaling-stroke',
            }),
        ]);
    }

    function Stat(label, value, hint) {
        return h('div', { style: { minWidth: '92px' } }, [
            h('div', { key: 'v', style: { fontSize: '1.15em', fontWeight: 600 } }, value),
            h('div', { key: 'l', className: 'hint' }, label),
            hint ? h('div', { key: 'h', className: 'hint' }, hint) : null,
        ]);
    }

    // ------------------------------------------------------------------
    // One node
    // ------------------------------------------------------------------

    function NodeCard(props) {
        const { node, volumes, history, settings, onForget, busy } = props;
        const online = node.online === '1';
        const heapPct = num(node.heapPct);
        const cpu = num(node.cpuProcess);

        const received = history.map(p => num(p.received));
        // Counters are cumulative, so the interesting series is the difference
        // between consecutive samples -- that is the traffic, and it is what the
        // number beside it is measuring.
        const receivedDelta = received.slice(1).map((v, i) => Math.max(0, v - received[i]));
        const cpuSeries = history.map(p => num(p.cpu)).filter(v => v >= 0);

        const header = h('div', {
            style: { display: 'flex', alignItems: 'baseline', gap: '8px', flexWrap: 'wrap' },
        }, [
            h('span', { key: 'n', style: { fontWeight: 600 } }, node.name),
            h('span', {
                key: 's',
                className: 'tag',
                style: {
                    borderColor: `var(${online ? '--ok' : '--err'})`,
                    color: `var(${online ? '--ok' : '--err'})`,
                },
            }, online ? 'online' : 'offline'),
            node.role ? h('span', { key: 'r', className: 'tag' }, node.role) : null,
            node.isThis === '1'
                ? h('span', { key: 't', className: 'hint' }, '(serving this page)') : null,
        ]);

        return h('div', {
            className: 'panel',
            style: {
                marginBottom: '11px',
                opacity: online ? 1 : 0.75,
            },
        }, [
            h('div', { key: 'h', className: 'panel-header' }, [
                header,
                h('span', { key: 'a', className: 'hint' },
                    online ? `up ${duration(node.uptimeMs)}` : `last seen ${ago(node.lastSampleAt)}`),
            ]),
            h('div', { key: 'b', className: 'panel-body' }, [
                !online ? Banner('warn', [
                    h('strong', { key: 't' }, 'Not reporting. '),
                    `Everything below is this node's last sample, taken ${ago(node.lastSampleAt)}.`,
                ]) : null,

                h('div', {
                    key: 'meters',
                    style: {
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))',
                        gap: '0 18px',
                    },
                }, [
                    h('div', { key: 'cpu' }, [
                        Meter('CPU (process)', cpu,
                            num(node.cpuSystem) >= 0
                                ? `host ${num(node.cpuSystem).toFixed(0)}%`
                                + (num(node.load) >= 0 ? ` · load ${num(node.load).toFixed(2)}` : '')
                                : null),
                        Spark(cpuSeries, { height: 26 }),
                    ]),
                    h('div', { key: 'heap' }, Meter('Heap', heapPct,
                        `${bytes(node.heapUsed)} of ${bytes(node.heapMax)}`
                        + ` · non-heap ${bytes(node.nonHeapUsed)}`,
                        num(settings.heapWarnPct))),
                    h('div', { key: 'vols' }, volumes.length
                        ? volumes.map(v => h('div', { key: v.path },
                            Meter(v.path, num(v.usedPct),
                                `${bytes(v.usable)} free of ${bytes(v.total)}`,
                                num(settings.diskWarnPct))))
                        : h('div', { className: 'hint' }, 'no volumes measured')),
                ]),

                h('div', {
                    key: 'stats',
                    style: {
                        display: 'flex',
                        flexWrap: 'wrap',
                        gap: '14px',
                        marginTop: '10px',
                        paddingTop: '10px',
                        borderTop: '1px solid var(--border)',
                    },
                }, [
                    Stat('channels', node.deployed,
                        `${node.started} started · ${node.stopped} stopped`
                        + (num(node.paused) ? ` · ${node.paused} paused` : '')),
                    Stat('queued', node.queued, 'messages waiting to send'),
                    Stat('threads', node.threads, `peak ${node.peakThreads}`),
                    Stat('received', node.received, `${num(node.receivedRate).toFixed(1)}/min`),
                    Stat('sent', node.sent, `${num(node.sentRate).toFixed(1)}/min`),
                    Stat('errored', num(node.errored) > 0
                        ? h('span', { style: { color: 'var(--err)' } }, node.errored)
                        : node.errored,
                    `${num(node.erroredRate).toFixed(1)}/min`),
                ]),

                h('div', { key: 'vol', style: { marginTop: '10px' } }, [
                    h('div', { key: 'l', className: 'hint' }, 'messages received per sample'),
                    Spark(receivedDelta, { token: '--ok' }),
                ]),

                h('div', {
                    key: 'foot',
                    className: 'hint',
                    style: { marginTop: '10px', display: 'flex', gap: '10px', flexWrap: 'wrap' },
                }, [
                    h('span', { key: 'id', className: 'mono' }, node.nodeId),
                    node.version ? h('span', { key: 'v' }, `engine ${node.version}`) : null,
                    node.jvmVersion ? h('span', { key: 'j' }, `jvm ${node.jvmVersion}`) : null,
                    node.osName ? h('span', { key: 'o' }, `${node.osName} ${node.osArch}`) : null,
                    !online && node.isThis !== '1'
                        ? h('span', { key: 'f' },
                            Button('Forget this node', () => onForget(node),
                                { danger: true, disabled: busy,
                                  title: 'Remove its samples and history. Nothing else is touched.' }))
                        : null,
                ]),
            ]),
        ]);
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    function Settings(props) {
        const { status, onSave, busy } = props;
        const [form, setForm] = React.useState({
            sampleIntervalSeconds: String(status.sampleIntervalSeconds || 30),
            historySamples: String(status.historySamples || 120),
            offlineAfterSeconds: String(status.offlineAfterSeconds || 120),
            heapWarnPct: String(status.heapWarnPct || 85),
            diskWarnPct: String(status.diskWarnPct || 85),
            volumePaths: status.volumePaths || '',
        });

        const field = (label, key, hint) => h('div', { className: 'field', key }, [
            h('label', { key: 'l' }, label),
            h('input', {
                key: 'i',
                type: 'text',
                value: form[key],
                disabled: busy,
                onChange: e => setForm(Object.assign({}, form, { [key]: e.target.value })),
            }),
            hint ? h('div', { key: 'h', className: 'hint' }, hint) : null,
        ]);

        return h('div', null, [
            h('div', { key: 'g', className: 'form-grid' }, [
                field('Sample every (seconds)', 'sampleIntervalSeconds',
                    'Floored at 10. Applies when each node restarts.'),
                field('Keep this many samples', 'historySamples',
                    '120 at 30s is the last hour.'),
                field('Offline after (seconds)', 'offlineAfterSeconds',
                    'Floored at three intervals, so one missed sample is not "offline".'),
                field('Heap warning at (%)', 'heapWarnPct', 'Colours the bar. No alerting.'),
                field('Disk warning at (%)', 'diskWarnPct', 'Percentage used.'),
                field('Volumes to measure', 'volumePaths',
                    'Comma separated. Empty means appdata, logs and the install root.'),
            ]),
            h('div', { key: 'b', style: { marginTop: '10px' } },
                Button('Save', () => onSave(form), { primary: true, disabled: busy })),
        ]);
    }

    // ------------------------------------------------------------------
    // The view
    // ------------------------------------------------------------------

    function NodesView() {
        const [status, setStatus] = React.useState(null);
        const [error, setError] = React.useState('');
        const [message, setMessage] = React.useState('');
        const [busy, setBusy] = React.useState(false);
        const [showSettings, setShowSettings] = React.useState(false);

        const load = React.useCallback(async () => {
            try {
                setStatus(await call('GET', '/status'));
                setError('');
            } catch (e) {
                setError(e.message);
            }
        }, []);

        React.useEffect(() => {
            load();
            // Refreshes on its own, a little faster than the default sampling
            // interval so a new sample shows up promptly, and no faster: a page
            // that polls quicker than the data changes just redraws the same
            // numbers.
            const timer = setInterval(load, 15000);
            return () => clearInterval(timer);
        }, [load]);

        async function act(fn, done) {
            setBusy(true);
            setMessage('');
            try {
                const result = await fn();
                setMessage(done(result));
                setError('');
                await load();
            } catch (e) {
                setError(e.message);
            } finally {
                setBusy(false);
            }
        }

        function onForget(node) {
            act(() => call('POST', `/nodes/_forget?nodeId=${encodeURIComponent(node.nodeId)}`,
                {}), () => `${node.name} removed. It will reappear if that engine starts again.`);
        }

        function onSample() {
            act(() => call('POST', '/_sample', {}), r => r.note || 'Sampled.');
        }

        function onSaveSettings(form) {
            act(() => call('POST', '/settings', form), r => r.note || 'Saved.');
        }

        if (!status) {
            return h('div', { className: 'view', style: viewStyle },
                error ? Banner('error', error) : h('div', { className: 'hint' }, 'Loading…'));
        }

        const nodes = rows(status.nodeRows, NODE_FIELDS);
        const volumes = rows(status.volumeRows, VOLUME_FIELDS);
        const history = rows(status.historyRows, HISTORY_FIELDS);

        const banners = [];
        if (error) banners.push(Banner('error', error));
        if (message) banners.push(Banner('ok', message));
        if (status.lastError) {
            banners.push(Banner('error',
                `This node's last sample failed: ${status.lastError}`));
        }
        if (Number(status.offline) > 0) {
            banners.push(Banner('warn',
                `${status.offline} of ${status.nodes} node(s) have not reported in the last `
                + `${status.offlineAfterSeconds}s.`));
        }
        if (!nodes.length) {
            banners.push(Banner('info',
                'No samples yet. Each node takes its first one about a minute after it '
                + 'starts.'));
        }

        return h('div', { className: 'view', style: viewStyle }, [
            h('div', { key: 'banners' }, banners),

            Panel('Across every node', h('div', {
                style: { display: 'flex', flexWrap: 'wrap', gap: '18px' },
            }, [
                Stat('nodes', `${status.online}/${status.nodes}`, 'online'),
                Stat('received', status.totalReceived,
                    `${num(status.totalReceivedPerMin).toFixed(1)}/min`),
                Stat('sent', status.totalSent),
                Stat('errored', num(status.totalErrored) > 0
                    ? h('span', { style: { color: 'var(--err)' } }, status.totalErrored)
                    : status.totalErrored),
                Stat('queued', status.totalQueued, 'waiting to send'),
                Stat('sampling', `${status.sampleIntervalSeconds}s`,
                    `history ${status.historySamples} samples`),
            ]), h('span', null, [
                Button('Sample this node', onSample, { disabled: busy, key: 's' }),
                Button(showSettings ? 'Hide settings' : 'Settings',
                    () => setShowSettings(!showSettings), { key: 'c' }),
            ])),

            showSettings
                ? Panel('Settings', h(Settings, { status, onSave: onSaveSettings, busy }))
                : null,

            ...nodes.map(node => h(NodeCard, {
                key: node.nodeId,
                node,
                volumes: volumes.filter(v => v.nodeId === node.nodeId),
                history: history.filter(p => p.nodeId === node.nodeId),
                settings: status,
                onForget,
                busy,
            })),
        ]);
    }

    // One failed registration must not take the rest of the console's plugins
    // down with it: the shell imports these modules in turn and an exception
    // escaping here stops the ones after it registering at all.
    const safely = (label, fn) => {
        try {
            fn();
        } catch (err) {
            console.warn('[nodemonitor] could not register ' + label, err);
        }
    };

    safely('icon', () => {
        platform.registerIcon('nodes', ICON_NODES);
    });

    safely('nodes view', () => {
        platform.registerView('/nodes', platform.reactView(NodesView), {
            title: 'Nodes',
        });
        platform.registerNavItem({
            id: 'nodes',
            label: 'Nodes',
            icon: 'nodes',
            path: '/nodes',
            section: 'Plugins',
            order: 55,
        });
    });
}
