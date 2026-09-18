/*
 * Cluster console UI.
 *
 * One surface: a "Cluster" page with four sections -- the nodes, the channels
 * and how far each node has got with them, the message counts summed across
 * nodes, and the queues left behind by a node that is gone.
 *
 * Everything it shows comes from this engine's own /api/cluster, which answers
 * for the whole cluster out of the shared database. So the page is same-origin,
 * needs no second login, and stays correct while a node is down -- the node is
 * shown as down, with what it was last known to be running.
 *
 * Plain ES module JavaScript: no JSX, no imports, no build step. The console
 * dynamically import()s this file and passes its own `platform` into
 * register(), which is how the bundled plugins receive it. Importing
 * @oie/web-shell here would depend on the page's import map and risks a second
 * framework instance registering into a dead registry, which fails silently.
 */

const API = '/api/cluster';

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

    /*
     * A collection: {"list": {"string": [...]}} / {"list": null}. The name test
     * is loose on purpose -- an immutable list serialises as a CollSer blob
     * whose elements cannot be recovered, and recognising the shape anyway
     * means the view gets an empty array rather than "(x || []).map is not a
     * function". The server side never sends one.
     */
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

const NODE_FIELDS = ['nodeId', 'name', 'role', 'address', 'version', 'startedAt',
    'lastSeen', 'live', 'deployed', 'isThis', 'isOwner', 'note'];
const CHANNEL_FIELDS = ['channelId', 'name', 'desiredState', 'placement', 'revision',
    'deploySeq', 'lifecycle', 'updatedAt', 'updatedBy', 'originNode', 'done', 'expected',
    'errors', 'states'];
const PLACEMENT_FIELDS = ['channelId', 'nodeId', 'wanted', 'here', 'state', 'error'];
const UNMANAGED_FIELDS = ['channelId', 'name'];
const STAT_FIELDS = ['channelId', 'name', 'received', 'filtered', 'sent', 'error'];
const STAT_NODE_FIELDS = ['channelId', 'nodeId', 'nodeName', 'live', 'received',
    'filtered', 'sent', 'error'];
const ORPHAN_FIELDS = ['serverId', 'nodeName', 'lastSeen', 'channelId', 'channelName',
    'queued', 'unprocessed'];
const LIVE_NODE_FIELDS = ['nodeId', 'name'];

function ago(epochMs) {
    const ms = Number(epochMs);
    if (!ms) return 'never';
    const seconds = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (seconds < 90) return `${seconds}s ago`;
    const minutes = Math.round(seconds / 60);
    if (minutes < 90) return `${minutes} min ago`;
    const hours = Math.round(minutes / 60);
    if (hours < 36) return `${hours} h ago`;
    return `${Math.round(hours / 24)} d ago`;
}

function uptime(epochMs) {
    const ms = Number(epochMs);
    if (!ms) return '';
    const seconds = Math.max(0, Math.round((Date.now() - ms) / 1000));
    if (seconds < 120) return `${seconds}s`;
    const minutes = Math.round(seconds / 60);
    if (minutes < 120) return `${minutes} min`;
    const hours = Math.round(minutes / 60);
    if (hours < 48) return `${hours} h`;
    return `${Math.round(hours / 24)} d`;
}

const ICON_CLUSTER = 'M4 6h5v5H4zM15 6h5v5h-5zM9.5 17h5v4h-5zM6.5 11v3h11v-3M12 14v3';

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
    // Presentation helpers, in the shell's own classes so the page follows
    // the app's spacing, borders and both themes.
    // ------------------------------------------------------------------

    const viewStyle = {
        display: 'block',
        overflowY: 'auto',
        height: '100%',
        boxSizing: 'border-box',
        padding: '1rem',
    };

    function Panel(title, children, badge) {
        return h('div', { className: 'panel', style: { marginBottom: '11px' } }, [
            title ? h('div', { key: 'h', className: 'panel-header' }, [
                h('span', { key: 't' }, title),
                badge || null,
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

    function Table(headers, body) {
        return h('div', { style: { overflowX: 'auto' } },
            h('table', { style: { width: '100%', borderCollapse: 'collapse' } }, [
                h('thead', { key: 'h' }, h('tr', null, headers.map((label, i) =>
                    h('th', {
                        key: i,
                        style: {
                            textAlign: 'left',
                            padding: '4px 8px',
                            borderBottom: '1px solid var(--border)',
                            fontWeight: 600,
                            whiteSpace: 'nowrap',
                        },
                    }, label)))),
                h('tbody', { key: 'b' }, body),
            ]));
    }

    function cell(content, style) {
        return h('td', {
            style: Object.assign({
                padding: '4px 8px',
                borderBottom: '1px solid var(--border)',
                verticalAlign: 'top',
            }, style || {}),
        }, content);
    }

    function tag(text, token) {
        return h('span', {
            className: 'tag',
            style: token ? { borderColor: `var(${token})`, color: `var(${token})` } : undefined,
        }, text);
    }

    // ------------------------------------------------------------------
    // Nodes
    // ------------------------------------------------------------------

    function NodeTable(props) {
        const { status, onForget, busy } = props;
        const nodes = rows(status.nodeRows, NODE_FIELDS);
        if (!nodes.length) {
            return h('div', { className: 'hint' }, 'No node has registered yet.');
        }
        return Table(
            ['Node', 'Role', 'Server id', 'Version', 'Up', 'Heartbeat', 'Deployed', ''],
            nodes.map(node => {
                const live = node.live === '1';
                return h('tr', { key: node.nodeId }, [
                    cell([
                        h('span', { key: 'n' }, node.name),
                        node.isThis === '1' ? h('span', { key: 't', className: 'hint',
                            style: { marginLeft: '6px' } }, '(this node)') : null,
                        node.address ? h('div', { key: 'a', className: 'hint' },
                            h('a', { href: node.address, target: '_blank',
                                rel: 'noreferrer' }, node.address)) : null,
                        node.note ? h('div', { key: 'note', className: 'hint' }, node.note) : null,
                    ]),
                    cell([
                        h('span', { key: 'r' }, node.role),
                        node.isOwner === '1' ? h('div', { key: 'o', className: 'hint' },
                            'runs singleton channels') : null,
                    ]),
                    cell(h('span', { className: 'mono' }, node.nodeId)),
                    cell(node.version),
                    cell(live ? uptime(node.startedAt) : '—'),
                    cell(live ? tag('live', '--ok') : tag(ago(node.lastSeen), '--err')),
                    cell(node.deployed === '-1' ? 'starting' : node.deployed),
                    cell(live ? null : Button('Forget', () => onForget(node), {
                        danger: true,
                        disabled: busy,
                        title: 'Remove from the registry. Queued messages are not touched.',
                    })),
                ]);
            }));
    }

    // ------------------------------------------------------------------
    // Channels
    // ------------------------------------------------------------------

    function ChannelTable(props) {
        const { status, onPlacement, onRedeploy, onState, busy } = props;
        const [expanded, setExpanded] = React.useState(null);
        const channels = rows(status.channelRows, CHANNEL_FIELDS);
        const placements = rows(status.placementRows, PLACEMENT_FIELDS);
        const nodes = rows(status.nodeRows, NODE_FIELDS);
        const nodeName = id => (nodes.find(n => n.nodeId === id) || {}).name || id;

        if (!channels.length) {
            return h('div', { className: 'hint' },
                'The cluster has no intent yet. Deploy a channel, or seed the intent from '
                + 'what this node is already running.');
        }

        const body = [];
        channels.forEach(channel => {
            const done = Number(channel.done);
            const expected = Number(channel.expected);
            const errors = Number(channel.errors);
            const undeployed = channel.desiredState !== 'DEPLOYED';
            let state;
            if (errors > 0) {
                state = tag(`${done}/${expected} · ${errors} failed`, '--err');
            } else if (undeployed) {
                state = tag('undeployed', '--warn');
            } else if (expected === 0) {
                state = tag('nowhere to run', '--err');
            } else if (done === expected) {
                state = tag(`${done}/${expected}`, '--ok');
            } else {
                state = tag(`${done}/${expected}`, '--warn');
            }

            const states = (channel.states || '').split(',').filter(Boolean);
            const diverged = states.length > 1;

            body.push(h('tr', { key: channel.channelId }, [
                cell([
                    h('a', {
                        key: 'n',
                        href: '#',
                        onClick: e => {
                            e.preventDefault();
                            setExpanded(expanded === channel.channelId
                                ? null : channel.channelId);
                        },
                    }, channel.name || channel.channelId),
                    h('div', { key: 'i', className: 'hint' },
                        `rev ${channel.revision} · ${ago(channel.updatedAt)} by `
                        + `${channel.updatedBy || 'unknown'}`),
                ]),
                cell(h('select', {
                    value: channel.placement.startsWith('PINNED:')
                        ? channel.placement : channel.placement,
                    disabled: busy,
                    onChange: e => onPlacement(channel, e.target.value),
                    style: { maxWidth: '190px' },
                }, [
                    h('option', { key: 'all', value: 'ALL' }, 'Every node'),
                    h('option', { key: 'one', value: 'SINGLETON' }, 'One node (singleton)'),
                    ...nodes.map(n => h('option',
                        { key: n.nodeId, value: `PINNED:${n.nodeId}` },
                        `Pinned to ${n.name}`)),
                ])),
                cell(state),
                cell(diverged
                    ? h('span', { style: { color: 'var(--warn)' } }, states.join(' / '))
                    : (states[0] || '—')),
                cell([
                    Button('Redeploy', () => onRedeploy(channel), { disabled: busy }),
                    diverged ? Button('Make uniform', () => onState(channel), {
                        disabled: busy,
                        title: 'Pick one state and apply it on every node.',
                    }) : null,
                ]),
            ]));

            if (expanded === channel.channelId) {
                const perNode = placements.filter(p => p.channelId === channel.channelId);
                body.push(h('tr', { key: `${channel.channelId}-detail` }, [
                    h('td', {
                        colSpan: 5,
                        style: {
                            padding: '6px 8px 12px 24px',
                            borderBottom: '1px solid var(--border)',
                        },
                    }, perNode.length ? Table(['Node', 'Wanted here', 'Converged', 'State', ''],
                        perNode.map(p => h('tr', { key: p.nodeId }, [
                            cell(nodeName(p.nodeId)),
                            cell(p.wanted === '1' ? 'yes' : 'no'),
                            cell(p.wanted === '1'
                                ? (p.here === '1' ? tag('yes', '--ok') : tag('waiting', '--warn'))
                                : '—'),
                            cell(p.state || '—'),
                            cell(p.error
                                ? h('span', { style: { color: 'var(--err)' } }, p.error)
                                : ''),
                        ])))
                        : h('div', { className: 'hint' }, 'No live node.')),
                ]));
            }
        });

        return Table(['Channel', 'Runs on', 'Converged', 'State', ''], body);
    }

    // ------------------------------------------------------------------
    // Orphaned queues
    //
    // Both actions here write into message history and cannot be undone, so the
    // page names every channel and its own count, offers the three answers, and
    // defaults to the one that changes nothing. Nothing is applied in bulk and
    // no choice is remembered between visits.
    // ------------------------------------------------------------------

    function Orphans(props) {
        const { data, onResolve, busy } = props;
        const orphans = rows(data.orphanRows, ORPHAN_FIELDS);
        const live = rows(data.liveNodeRows, LIVE_NODE_FIELDS);
        const [choice, setChoice] = React.useState({});
        const [confirming, setConfirming] = React.useState(null);

        if (!orphans.length) {
            return h('div', { className: 'hint' },
                'Nothing queued belongs to a node that is gone.');
        }

        const byServer = {};
        orphans.forEach(o => {
            (byServer[o.serverId] = byServer[o.serverId] || []).push(o);
        });

        return h('div', null, Object.keys(byServer).map(serverId => {
            const group = byServer[serverId];
            const label = group[0].nodeName || serverId;
            return h('div', { key: serverId, style: { marginBottom: '14px' } }, [
                h('div', { key: 'h', style: { marginBottom: '6px' } }, [
                    h('strong', { key: 'n' }, label),
                    h('span', { key: 'i', className: 'hint', style: { marginLeft: '8px' } },
                        `${serverId} · last seen ${ago(group[0].lastSeen)}`),
                ]),
                Table(['Channel', 'Queued', 'Unfinished', 'What to do'],
                    group.map(o => {
                        const key = `${serverId}|${o.channelId}`;
                        const target = choice[key] || '';
                        return h('tr', { key }, [
                            cell(o.channelName),
                            cell(o.queued),
                            cell(o.unprocessed),
                            cell(confirming === key
                                ? h('div', null, [
                                    h('div', { key: 'q', style: { marginBottom: '6px' } },
                                        target
                                            ? `Move ${o.queued} queued and ${o.unprocessed} `
                                              + `unfinished message(s) on ${o.channelName} to `
                                              + `${(live.find(n => n.nodeId === target) || {})
                                                  .name || target}?`
                                            : `Mark ${o.queued} queued message(s) on `
                                              + `${o.channelName} as errored? They stay in the `
                                              + 'message browser and stop being retried.'),
                                    Button('Yes, do it', () => {
                                        setConfirming(null);
                                        onResolve(target ? 'adopt' : 'discard', o, target);
                                    }, { danger: true, disabled: busy }),
                                    Button('Cancel', () => setConfirming(null)),
                                ])
                                : h('div', null, [
                                    h('select', {
                                        key: 's',
                                        value: target,
                                        disabled: busy,
                                        onChange: e => setChoice(
                                            Object.assign({}, choice, { [key]: e.target.value })),
                                        style: { marginRight: '6px', maxWidth: '170px' },
                                    }, [
                                        h('option', { key: '', value: '' }, 'Leave them'),
                                        ...live.map(n => h('option',
                                            { key: n.nodeId, value: n.nodeId },
                                            `Reassign to ${n.name}`)),
                                    ]),
                                    target
                                        ? Button('Reassign', () => setConfirming(key),
                                            { disabled: busy })
                                        : Button('Mark as errored', () => setConfirming(key),
                                            { disabled: busy, danger: true }),
                                ])),
                        ]);
                    })),
            ]);
        }));
    }

    // ------------------------------------------------------------------
    // Statistics
    // ------------------------------------------------------------------

    function Stats(props) {
        const { data } = props;
        const [expanded, setExpanded] = React.useState(null);
        const totals = rows(data.statRows, STAT_FIELDS);
        const perNode = rows(data.statNodeRows, STAT_NODE_FIELDS);
        if (!totals.length) {
            return h('div', { className: 'hint' }, 'No messages counted yet.');
        }
        const body = [];
        totals.forEach(row => {
            body.push(h('tr', { key: row.channelId }, [
                cell(h('a', {
                    href: '#',
                    onClick: e => {
                        e.preventDefault();
                        setExpanded(expanded === row.channelId ? null : row.channelId);
                    },
                }, row.name)),
                cell(row.received), cell(row.filtered), cell(row.sent),
                cell(Number(row.error) > 0
                    ? h('span', { style: { color: 'var(--err)' } }, row.error) : row.error),
            ]));
            if (expanded === row.channelId) {
                const detail = perNode.filter(p => p.channelId === row.channelId);
                body.push(h('tr', { key: `${row.channelId}-d` }, [
                    h('td', {
                        colSpan: 5,
                        style: {
                            padding: '6px 8px 12px 24px',
                            borderBottom: '1px solid var(--border)',
                        },
                    }, Table(['Node', 'Received', 'Filtered', 'Sent', 'Errored'],
                        detail.map(p => h('tr', { key: p.nodeId }, [
                            cell([
                                h('span', { key: 'n' }, p.nodeName || p.nodeId),
                                p.live === '1' ? null : h('span',
                                    { key: 'd', className: 'hint',
                                        style: { marginLeft: '6px' } }, '(gone)'),
                            ]),
                            cell(p.received), cell(p.filtered), cell(p.sent), cell(p.error),
                        ])))),
                ]));
            }
        });
        return Table(['Channel', 'Received', 'Filtered', 'Sent', 'Errored'], body);
    }

    // ------------------------------------------------------------------
    // The view
    // ------------------------------------------------------------------

    function ClusterView() {
        const [status, setStatus] = React.useState(null);
        const [dashboard, setDashboard] = React.useState(null);
        const [orphans, setOrphans] = React.useState(null);
        const [error, setError] = React.useState('');
        const [message, setMessage] = React.useState('');
        const [busy, setBusy] = React.useState(false);

        const load = React.useCallback(async () => {
            try {
                const [s, d] = await Promise.all([
                    call('GET', '/status'),
                    call('GET', '/dashboard'),
                ]);
                setStatus(s);
                setDashboard(d);
                setError('');
            } catch (e) {
                setError(e.message);
            }
        }, []);

        React.useEffect(() => {
            load();
            // The page is a live view of a converging system, so it refreshes on
            // its own. Ten seconds is slower than the default convergence pass on
            // purpose: a page that updates faster than the thing it watches just
            // shows the same numbers more often.
            const timer = setInterval(load, 10000);
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

        function onPlacement(channel, placement) {
            act(() => call('POST', '/placement',
                { channelId: channel.channelId, placement }),
            () => `${channel.name} now runs on: ${placement === 'ALL' ? 'every node'
                : placement === 'SINGLETON' ? 'one node' : placement}.`);
        }

        function onRedeploy(channel) {
            act(() => call('POST', '/redeploy', { channelIds: channel.channelId }),
                () => `${channel.name} will be redeployed on every node that runs it.`);
        }

        function onState(channel) {
            const wanted = window.prompt(
                `Which state should ${channel.name} have on every node?\n`
                + 'STARTED, PAUSED or STOPPED', 'STARTED');
            if (!wanted) return;
            act(() => call('POST', '/state',
                { channelId: channel.channelId, state: wanted.trim().toUpperCase() }),
            () => `${channel.name} will be ${wanted.trim().toLowerCase()} on every node.`);
        }

        function onForget(node) {
            act(() => call('POST', `/nodes/_forget?nodeId=${encodeURIComponent(node.nodeId)}`,
                {}), () => `${node.name} removed from the registry. Any messages it still `
                + 'owns are untouched and still listed below.');
        }

        function onSeed() {
            act(() => call('POST', '/seed', {}),
                r => `Cluster intent seeded from ${r.channels} channel(s) deployed here.`);
        }

        async function loadOrphans() {
            setBusy(true);
            try {
                setOrphans(await call('GET', '/orphans'));
                setError('');
            } catch (e) {
                setError(e.message);
            } finally {
                setBusy(false);
            }
        }

        function onResolve(action, orphan, targetNodeId) {
            act(() => call('POST', '/orphans/_resolve', {
                action,
                serverId: orphan.serverId,
                channelId: orphan.channelId,
                targetNodeId: targetNodeId || '',
            }), r => action === 'adopt'
                ? `${r.messages} message(s) on ${orphan.channelName} moved. Redeploy that `
                  + 'channel for the new owner to pick its queue up.'
                : `${r.messages} queued message(s) on ${orphan.channelName} marked as errored.`)
                .then(loadOrphans);
        }

        if (!status) {
            return h('div', { className: 'view', style: viewStyle },
                error ? Banner('error', error) : h('div', { className: 'hint' }, 'Loading…'));
        }

        const header = [];
        if (!status.enabled) {
            header.push(Banner('info', [
                h('strong', { key: 't' }, 'Convergence is off on this node. '),
                'It registers itself and reports what it is running, but no deploy made '
                + 'anywhere is applied here and nothing this node does becomes an '
                + 'instruction to anyone else. Set OIE_CLUSTER_ENABLED=true to switch it on.',
            ]));
        }
        if (Number(status.unownedSingletons) > 0) {
            header.push(Banner('error', [
                h('strong', { key: 't' },
                    `${status.unownedSingletons} singleton channel(s) have nowhere to run. `),
                'A channel placed on one node runs on the live utility node, and there is '
                + 'none. They are deliberately not deployed anywhere rather than deployed '
                + 'everywhere, which would poll every source once per node.',
            ]));
        }
        if (status.lastError) {
            header.push(Banner('error',
                `The last convergence pass on this node failed: ${status.lastError}`));
        }
        if (error) header.push(Banner('error', error));
        if (message) header.push(Banner('ok', message));

        const unmanaged = rows(status.unmanagedRows, UNMANAGED_FIELDS);

        return h('div', { className: 'view', style: viewStyle }, [
            h('div', { key: 'header' }, header),

            Panel(`Cluster "${status.clusterName}"`, [
                h('div', { key: 'summary', className: 'hint', style: { marginBottom: '8px' } },
                    `${status.liveNodes} of ${status.knownNodes} node(s) live · `
                    + `${status.channels} channel(s) managed · ${status.converged} converged`
                    + (Number(status.pending) ? `, ${status.pending} in progress` : '')
                    + (Number(status.failed) ? `, ${status.failed} failed` : '')
                    + ` · last pass here ${ago(status.lastTick)}`),
                h(NodeTable, { key: 'nodes', status, onForget, busy }),
            ]),

            Panel('Channels', [
                h(ChannelTable, {
                    key: 'channels', status, onPlacement, onRedeploy, onState, busy,
                }),
                unmanaged.length ? h('div', {
                    key: 'unmanaged',
                    className: 'hint',
                    style: { marginTop: '10px' },
                }, [
                    h('div', { key: 't' },
                        `${unmanaged.length} channel(s) deployed on this node are not `
                        + 'managed by the cluster and are left alone: '
                        + unmanaged.map(c => c.name).join(', ') + '.'),
                    h('div', { key: 'b', style: { marginTop: '6px' } },
                        Button('Seed cluster intent from this node', onSeed, { disabled: busy })),
                ]) : null,
            ]),

            Panel('Messages, across every node', h(Stats, { data: dashboard || {} })),

            Panel('Queues left behind', orphans
                ? h(Orphans, { data: orphans, onResolve, busy })
                : h('div', null, [
                    h('div', { key: 'h', className: 'hint', style: { marginBottom: '8px' } },
                        'Messages queued by an engine that is no longer here belong to its '
                        + 'server id and nothing else will pick them up. Counting them reads '
                        + 'every channel’s message tables, so it is asked for rather '
                        + 'than done on every page load.'),
                    Button('Look for orphaned queues', loadOrphans,
                        { disabled: busy, primary: true }),
                ])),
        ]);
    }

    // One failed registration must not take the rest of the console's plugins
    // down with it: the shell imports these modules in turn and an exception
    // escaping here stops the ones after it registering at all.
    const safely = (label, fn) => {
        try {
            fn();
        } catch (err) {
            console.warn('[cluster] could not register ' + label, err);
        }
    };

    safely('icon', () => {
        platform.registerIcon('cluster', ICON_CLUSTER);
    });

    safely('cluster view', () => {
        platform.registerView('/cluster', platform.reactView(ClusterView), {
            title: 'Cluster',
        });
        platform.registerNavItem({
            id: 'cluster',
            label: 'Cluster',
            icon: 'cluster',
            path: '/cluster',
            section: 'Plugins',
            order: 60,
        });
    });
}
