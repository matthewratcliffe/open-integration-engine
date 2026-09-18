/*
 * FHIR connector panels for the web administrator's channel editor.
 *
 * Registers two ConnectorSettingsPanel equivalents -- "FHIR Listener" (SOURCE) and
 * "FHIR Sender" (DESTINATION) -- against the transport names the connector metadata
 * declares. Without them the channel editor lists the connector as "FHIR Listener
 * (no web editor)" and falls back to a raw properties JSON box.
 *
 * ---------------------------------------------------------------------------
 * WHY THIS FILE IMPORTS NOTHING
 *
 * The obvious way to write this is the way the console's own bundled connector
 * plugins do:
 *
 *     import { React } from '/connectors/react-platform.js';
 *     import { ConnectorForm, CHARSETS } from '/connectors/react-forms.js';
 *
 * Those resolve through the page's import map ("/connectors/" -> "./connectors/")
 * and work for plugins that ship *inside* the web administrator's WAR. They do not
 * work for a plugin served by the engine: an extension's browser half is fetched
 * from the API and imported from a base the browser will not resolve a rooted
 * specifier against, so every such import dies at load with
 *
 *     TypeError: Failed to resolve module specifier "/connectors/react-forms.js".
 *     Invalid relative url or base scheme isn't hierarchical.
 *
 * and the whole plugin never registers -- silently, as far as the channel editor is
 * concerned, since a connector with no registered panel simply reads "no web editor".
 *
 * So this file imports nothing at all. Everything it needs comes through the
 * `platform` object handed to register(): the host's React instance (so hooks and
 * context work against the one React the app renders with) and its code editor
 * factory (so the template fields get the same Monaco the rest of the console uses,
 * and no second copy is ever loaded). The small form kit below is a reimplementation
 * of the console's <ConnectorForm> against the same CSS classes, which is what keeps
 * these panels looking like the built-in ones.
 * ---------------------------------------------------------------------------
 *
 * The defaults() shapes MUST match FhirReceiverProperties and FhirDispatcherProperties
 * field for field: the console sends this object to the engine, XStream maps it onto
 * those classes by name, and a field missing here simply arrives unset.
 */

const RECEIVER_CLASS = 'org.openintegrationengine.connectors.fhir.FhirReceiverProperties';
const DISPATCHER_CLASS = 'org.openintegrationengine.connectors.fhir.FhirDispatcherProperties';

/* Kept in step with FhirVersion. Adding a release is a line here and a line there. */
const FHIR_VERSIONS = [
    { value: 'R4', label: 'R4 (4.0.1)' },
    { value: 'R5', label: 'R5 (5.0.0)' }
];

const FORMATS = [
    { value: 'json', label: 'JSON' },
    { value: 'xml', label: 'XML' }
];

const YES_NO = [
    { value: true, label: 'Yes' },
    { value: false, label: 'No' }
];

/* The console's charset list, inlined -- see the note at the top of this file. */
const CHARSETS = [
    { value: 'UTF-8', label: 'UTF-8' },
    { value: 'ISO-8859-1', label: 'ISO-8859-1' },
    { value: 'US-ASCII', label: 'US-ASCII' },
    { value: 'UTF-16', label: 'UTF-16' },
    { value: 'windows-1252', label: 'windows-1252' }
];

/* FhirInteraction.defaultEnabledCodes(), in enum order. */
const ALL_INTERACTIONS = [
    'read', 'vread', 'update', 'patch', 'delete', 'create', 'search-type',
    'history-instance', 'history-type', 'history-system', 'search-system',
    'transaction', 'batch', 'search-compartment', 'operation'
].join(',');

/* SourceConnectorProperties.RESPONSE_DESTINATIONS_COMPLETED. */
const RESPONSE_DESTINATIONS_COMPLETED = 'Auto-generate (Destinations completed)';

const CAPABILITY_MODES = [
    { value: 'AUTO', label: 'Generate from these settings' },
    { value: 'CUSTOM', label: 'Serve the statement below' },
    { value: 'CHANNEL', label: 'Pass to the channel' },
    { value: 'DISABLED', label: 'Return 404' }
];

const INTERACTIONS = [
    { value: 'CREATE', label: 'create — POST [base]/[type]' },
    { value: 'UPDATE', label: 'update — PUT [base]/[type]/[id]' },
    { value: 'PATCH', label: 'patch — PATCH [base]/[type]/[id]' },
    { value: 'READ', label: 'read — GET [base]/[type]/[id]' },
    { value: 'VREAD', label: 'vread — GET [base]/[type]/[id]/_history/[vid]' },
    { value: 'SEARCH', label: 'search — GET [base]/[type]?params' },
    { value: 'DELETE', label: 'delete — DELETE [base]/[type]/[id]' },
    { value: 'HISTORY', label: 'history — GET [base]/[type]/_history' },
    { value: 'TRANSACTION', label: 'transaction / batch — POST [base]' },
    { value: 'OPERATION', label: 'operation — POST [base]/$name' }
];

const AUTH_TYPES = [
    { value: 'NONE', label: 'None' },
    { value: 'BASIC', label: 'Basic' },
    { value: 'BEARER', label: 'Bearer token' }
];

const PREFER_RETURN = [
    { value: 'NONE', label: 'Do not send Prefer' },
    { value: 'minimal', label: 'minimal — no body' },
    { value: 'representation', label: 'representation — the resource' },
    { value: 'OperationOutcome', label: 'OperationOutcome' }
];

/* ---------------------------------------------------------------- form kit */

/**
 * A small reimplementation of the console's <ConnectorForm>, built only from the
 * host React instance. Field specs use the same vocabulary as the built-in
 * connectors, so these panels read like theirs:
 *
 *   { section: 'Title' }                        opens a section
 *   { key, label, type, ... }                   a row
 *
 * types: text | password | number | select | radio | checkbox | textarea | code |
 *        keyvalue | display
 * extras: width, span, placeholder, tooltip, options, disabled(p), visible(p),
 *         refresh (repaint the form after a change), mapShape ('string' | 'list')
 */
function createFormKit(platform) {
    const React = platform.React;
    const h = React.createElement;
    const { useReducer, useRef, useEffect } = React;

    const asBool = (v) => v === true || v === 'true';

    function getPath(obj, path) {
        if (!path) return undefined;
        return String(path).split('.').reduce((o, k) => (o === null || o === undefined ? undefined : o[k]), obj);
    }

    function setPath(obj, path, value) {
        const keys = String(path).split('.');
        let target = obj;
        for (let i = 0; i < keys.length - 1; i++) {
            if (target[keys[i]] === null || typeof target[keys[i]] !== 'object') target[keys[i]] = {};
            target = target[keys[i]];
        }
        target[keys[keys.length - 1]] = value;
    }

    /* XStream's map shapes, read and written exactly as the console does -- these
       round-trip through the engine, so the shape is not ours to choose. */
    function mapEntries(map) {
        const out = [];
        if (!map || typeof map !== 'object') return out;
        let entries = map.entry;
        if (entries === null || entries === undefined || entries === '') return out;
        if (!Array.isArray(entries)) entries = [entries];
        for (const entry of entries) {
            if (!entry || typeof entry !== 'object') continue;
            if (Array.isArray(entry.string) && entry.list === undefined) {
                out.push([String(entry.string[0] ?? ''), String(entry.string[1] ?? '')]);
            } else if (entry.string !== undefined && entry.list !== undefined) {
                const key = Array.isArray(entry.string) ? String(entry.string[0] ?? '') : String(entry.string);
                let values = entry.list && typeof entry.list === 'object' ? entry.list.string : null;
                if (values === null || values === undefined || values === '') values = [];
                if (!Array.isArray(values)) values = [values];
                if (!values.length) out.push([key, '']);
                else for (const v of values) out.push([key, String(v ?? '')]);
            }
        }
        return out;
    }

    function writeMapEntries(map, rows, shape) {
        const target = map && typeof map === 'object' ? map : {};
        if (!target['@class']) target['@class'] = 'linked-hash-map';
        const clean = rows.filter(([k]) => k !== '' && k !== null && k !== undefined);
        if (!clean.length) {
            delete target.entry;
            return target;
        }
        if (shape === 'list') {
            const grouped = new Map();
            for (const [k, v] of clean) {
                if (!grouped.has(k)) grouped.set(k, []);
                grouped.get(k).push(v);
            }
            target.entry = [...grouped].map(([k, values]) => ({ string: k, list: { string: values } }));
        } else {
            target.entry = clean.map(([k, v]) => ({ string: [k, v] }));
        }
        return target;
    }

    /* Monaco (or the console's dependency-free fallback) through the host factory,
       so a plugin never loads an editor of its own. */
    function CodeField({ value, language, minHeight, disabled, onChange }) {
        const hostRef = useRef(null);
        const onChangeRef = useRef(onChange);
        onChangeRef.current = onChange;
        useEffect(() => {
            const host = hostRef.current;
            if (!host || !platform.createCodeEditor) return undefined;
            const editor = platform.createCodeEditor({
                value: value === null || value === undefined ? '' : String(value),
                language: language || 'text',
                minHeight: minHeight || '240px',
                readOnly: !!disabled,
                maximizable: true,
                onChange: (v) => onChangeRef.current && onChangeRef.current(v)
            });
            host.appendChild(editor.el);
            return () => {
                try { editor.dispose && editor.dispose(); } catch (e) { /* baseline editor has none */ }
                if (host) host.replaceChildren();
            };
            // Rebuilt only when the language changes, exactly like the console's own
            // code fields; the value flows out through onChange, never back in.
            // eslint-disable-next-line react-hooks/exhaustive-deps
        }, [language, disabled]);

        // No editor factory (an older console): a textarea still edits the value.
        if (!platform.createCodeEditor) {
            return h('textarea', {
                rows: 12,
                disabled: !!disabled,
                value: value === null || value === undefined ? '' : String(value),
                onChange: (e) => onChange(e.target.value)
            });
        }
        return h('div', { ref: hostRef });
    }

    function KeyValueField({ properties, field, onChange, disabled }) {
        const [, tick] = useReducer((n) => n + 1, 0);
        const rowsRef = useRef(null);
        const lastRef = useRef(undefined);
        const current = getPath(properties, field.key);
        if (rowsRef.current === null || current !== lastRef.current) {
            rowsRef.current = mapEntries(current);
            lastRef.current = current;
        }
        const rows = rowsRef.current;
        const commit = () => {
            const written = writeMapEntries(getPath(properties, field.key), rows, field.mapShape || 'string');
            setPath(properties, field.key, written);
            lastRef.current = written;
            onChange();
        };
        return h('div', { style: disabled ? { opacity: 0.6 } : undefined },
            rows.map((row, i) => h('div', { key: i, className: 'flex gap-1.5 mb-1.5' },
                h('input', {
                    type: 'text', value: row[0], placeholder: 'Name', className: 'flex-1', disabled,
                    onChange: (e) => { row[0] = e.target.value; tick(); commit(); }
                }),
                h('input', {
                    type: 'text', value: row[1], placeholder: 'Value', className: 'flex-[2]', disabled,
                    onChange: (e) => { row[1] = e.target.value; tick(); commit(); }
                }),
                h('button', {
                    type: 'button', className: 'btn', title: 'Remove', disabled,
                    onClick: () => { rows.splice(i, 1); commit(); tick(); }
                }, '✕')
            )),
            h('button', {
                type: 'button', className: 'btn', disabled,
                onClick: () => { rows.push(['', '']); tick(); }
            }, 'Add')
        );
    }

    let radioUid = 0;

    function FieldRow({ properties, field, onChange, repaint }) {
        const f = field;
        const value = f.key === undefined ? undefined : getPath(properties, f.key);
        const disabled = typeof f.disabled === 'function' ? f.disabled(properties) : !!f.disabled;
        const label = typeof f.label === 'function' ? f.label(properties) : f.label;

        const set = (v) => {
            if (f.key !== undefined) setPath(properties, f.key, v);
            onChange();
            if (repaint) repaint();
        };

        let control = null;
        let wide = f.span === true || f.type === 'textarea' || f.type === 'code' || f.type === 'keyvalue';
        const style = !wide && f.width ? { width: f.width } : undefined;

        switch (f.type) {
            case 'checkbox':
                control = h('label', { className: 'check' },
                    h('input', {
                        type: 'checkbox', checked: asBool(value), disabled,
                        onChange: (e) => set(e.target.checked)
                    }), f.checkLabel || '');
                break;
            case 'radio': {
                const name = 'fhir-radio-' + (f.key || label || '').replace(/\W+/g, '-') + '-' + radioUid++;
                const opts = typeof f.options === 'function' ? f.options(properties) : (f.options || []);
                control = h('div', { className: 'radio-group inline-row' }, opts.map((opt, i) => {
                    const o = typeof opt === 'object' ? opt : { value: opt, label: String(opt) };
                    return h('label', { className: 'check', key: i },
                        h('input', {
                            type: 'radio', name, disabled,
                            checked: String(o.value) === String(value === undefined || value === null ? '' : value),
                            onChange: () => set(o.value)
                        }), o.label);
                }));
                break;
            }
            case 'select': {
                const opts = typeof f.options === 'function' ? f.options(properties) : (f.options || []);
                control = h('select', {
                    value: value === null || value === undefined ? '' : value, style, disabled,
                    onChange: (e) => set(f.numeric ? parseInt(e.target.value, 10) : e.target.value)
                }, opts.map((opt, i) => {
                    const o = typeof opt === 'object' ? opt : { value: opt, label: String(opt) };
                    return h('option', { key: i, value: o.value }, o.label);
                }));
                break;
            }
            case 'number':
                control = h('input', {
                    type: 'number', value: value ?? '', placeholder: f.placeholder, style, disabled,
                    onChange: (e) => set(e.target.value)
                });
                break;
            case 'textarea':
                control = h('textarea', {
                    rows: f.rows || 5, placeholder: f.placeholder, disabled,
                    value: value === null || value === undefined ? '' : String(value),
                    onChange: (e) => set(e.target.value)
                });
                break;
            case 'code':
                control = h(CodeField, {
                    value,
                    language: typeof f.language === 'function' ? f.language(properties) : f.language,
                    minHeight: f.minHeight, disabled, onChange: (v) => set(v)
                });
                break;
            case 'keyvalue':
                control = h(KeyValueField, { properties, field: f, onChange, disabled });
                break;
            case 'display':
                control = h('span', { className: 'cform-display' },
                    f.compute ? f.compute(properties) : (value ?? ''));
                break;
            default:
                control = h('input', {
                    type: f.type === 'password' ? 'password' : 'text',
                    value: value ?? '', disabled, placeholder: f.placeholder, style,
                    autoComplete: f.type === 'password' ? 'off' : undefined,
                    onChange: (e) => set(e.target.value)
                });
        }

        return h(React.Fragment, null,
            h('label', {
                className: 'cform-label' + (wide ? ' top' : ''),
                title: f.tooltip || undefined,
                style: disabled ? { opacity: 0.5 } : undefined
            }, label ? label + ':' : ''),
            h('div', { className: 'cform-control' + (wide ? ' wide' : ''), title: f.tooltip || undefined }, control)
        );
    }

    function ConnectorForm({ properties, fields, onChange }) {
        const [, repaint] = useReducer((n) => n + 1, 0);
        const notify = () => { onChange(); repaint(); };

        const sections = [];
        let current = null;
        for (const f of fields) {
            if (f.section !== undefined) {
                if (f.visible && !f.visible(properties)) { current = null; continue; }
                current = { title: f.section, rows: [] };
                sections.push(current);
                continue;
            }
            if (f.visible && !f.visible(properties)) continue;
            if (!current) { current = { title: null, rows: [] }; sections.push(current); }
            current.rows.push(f);
        }

        return h('div', { className: 'cform' }, sections.map((section, si) =>
            h('div', { className: 'cform-section', key: si },
                section.title ? h('div', { className: 'cform-section-title' }, section.title) : null,
                h('div', { className: 'cform-grid' }, section.rows.map((f, ri) =>
                    h(FieldRow, {
                        key: f.key || (typeof f.label === 'string' ? f.label : '') || ('row-' + si + '-' + ri),
                        properties, field: f, onChange: notify,
                        repaint: f.refresh ? repaint : null
                    })
                ))
            )
        ));
    }

    /** The console's validate() contract: [{key, label}] for anything required and blank. */
    function requireFields(properties, specs) {
        const errors = [];
        for (const spec of specs) {
            if (typeof spec.when === 'function' && !spec.when(properties)) continue;
            const v = getPath(properties, spec.key);
            if (v === undefined || v === null || String(v).trim() === '') {
                errors.push({ key: spec.key, label: spec.label });
            }
        }
        return errors;
    }

    return { ConnectorForm, requireFields, asBool, h };
}

/* ------------------------------------------------- engine property defaults */

/* SourceConnectorProperties / ListenerConnectorProperties / DestinationConnector-
   Properties as the engine serialises them. Inlined for the same reason as the
   form kit: an engine-served plugin cannot import the console's helpers. */

function defaultResourceIds() {
    return { '@class': 'linked-hash-map', entry: [{ string: ['Default Resource', '[Default Resource]'] }] };
}

function defaultListenerProperties(version, port) {
    return { '@version': version, host: '0.0.0.0', port: String(port) };
}

function defaultSourceProperties(version, overrides) {
    return Object.assign({
        '@version': version,
        responseVariable: 'None',
        respondAfterProcessing: true,
        processBatch: false,
        firstResponse: false,
        processingThreads: 1,
        resourceIds: defaultResourceIds(),
        queueBufferSize: 1000
    }, overrides || {});
}

function defaultDestinationProperties(version) {
    return {
        '@version': version,
        queueEnabled: false,
        sendFirst: false,
        retryIntervalMillis: 10000,
        regenerateTemplate: false,
        retryCount: 0,
        rotate: false,
        includeFilterTransformer: false,
        threadCount: 1,
        threadAssignmentVariable: null,
        validateResponse: false,
        resourceIds: defaultResourceIds(),
        queueBufferSize: 1000,
        reattachAttachments: true
    };
}

/* ---------------------------------------------------------------- listener */

const STORE_TYPES = [
    { value: 'PKCS12', label: 'PKCS12' },
    { value: 'JKS', label: 'JKS' }
];

const CLIENT_AUTH = [
    { value: 'NONE', label: 'None — do not ask for one' },
    { value: 'WANT', label: 'Want — ask, accept either way' },
    { value: 'NEED', label: 'Need — refuse without a valid one' }
];

function listenerPanel(kit) {
    const { ConnectorForm, requireFields, asBool, h } = kit;


    return {
        defaults(version) {
            return {
                '@class': RECEIVER_CLASS,
                '@version': version,
                pluginProperties: null,
                listenerConnectorProperties: defaultListenerProperties(version, '8081'),
                // A facade answers with what its destinations produced, so it has to wait
                // for them; see the same default in FhirReceiverProperties.
                sourceConnectorProperties: defaultSourceProperties(version, {
                    responseVariable: RESPONSE_DESTINATIONS_COMPLETED
                }),
                // TLS off, so an upgrade changes nothing for a deployment where something
                // in front already terminates it. Mirrors FhirReceiverProperties.
                useSsl: false,
                keyStoreFile: '',
                keyStorePassword: '',
                keyStoreType: 'PKCS12',
                keyAlias: '',
                keyPassword: '',
                clientAuth: 'NONE',
                trustStoreFile: '',
                trustStorePassword: '',
                trustStoreType: 'PKCS12',
                protocols: 'TLSv1.2,TLSv1.3',
                cipherSuites: '',
                fhirVersion: 'R4',
                basePath: '/fhir',
                charset: 'UTF-8',
                timeout: '30000',
                maxRequestSize: '0',
                messageContent: 'BODY',
                defaultFormat: 'json',
                strictVersionNegotiation: false,
                enabledInteractions: ALL_INTERACTIONS,
                resourceTypes: '',
                capabilityMode: 'AUTO',
                capabilityStatement: '',
                baseUrlOverride: '',
                responseStatusCode: '',
                responseHeaders: { '@class': 'linked-hash-map' },
                useResponseHeadersVariable: false,
                responseHeadersVariable: '',
                wrapNonFhirResponses: true
            };
        },

        component({ properties, onChange }) {
            return h(ConnectorForm, {
                properties,
                onChange,
                fields: [
                    { section: 'Listener Settings' },
                    {
                        key: 'listenerConnectorProperties.host', label: 'Local Address', type: 'text', width: '220px',
                        tooltip: 'The address to bind to. 0.0.0.0 accepts on every interface.'
                    },
                    { key: 'listenerConnectorProperties.port', label: 'Local Port', type: 'number', width: '110px' },

                    { section: 'TLS' },
                    {
                        key: 'useSsl', label: 'Serve TLS', type: 'checkbox', refresh: true,
                        checkLabel: 'Serve HTTPS on this port (TLS terminated by this connector)',
                        tooltip: 'Off means plain HTTP here, with TLS terminated in front — this stack\u2019s'
                            + ' nginx overlay, or an ingress. On means this connector presents the'
                            + ' certificate itself, which is what a partner requiring client certificates'
                            + ' checked by the application needs.'
                    },
                    {
                        key: 'keyStoreFile', label: 'Key Store File', type: 'text', width: '380px',
                        visible: (p) => asBool(p.useSsl), placeholder: 'fhir/server.p12',
                        tooltip: 'The store holding the server certificate and its private key. A relative'
                            + ' path is taken from the engine\u2019s application data directory, which is the'
                            + ' volume this stack persists.'
                    },
                    {
                        key: 'keyStorePassword', label: 'Key Store Password', type: 'password', width: '260px',
                        visible: (p) => asBool(p.useSsl),
                        tooltip: 'Channel XML is not an encrypted store: a configuration map reference by its'
                            + ' bare key — ${fhirKeyStorePassword} — or a Key Store plugin secret keeps this'
                            + ' out of git.'
                    },
                    {
                        key: 'keyStoreType', label: 'Key Store Type', type: 'select', width: '140px',
                        options: STORE_TYPES, visible: (p) => asBool(p.useSsl),
                        tooltip: 'PKCS12 is the JDK default and what openssl and certbot produce; JKS is'
                            + ' deprecated but still read.'
                    },
                    {
                        key: 'keyAlias', label: 'Key Alias', type: 'text', width: '220px',
                        visible: (p) => asBool(p.useSsl),
                        tooltip: 'Which certificate to present. Required when the store holds more than one'
                            + ' private key — the channel refuses to deploy rather than serving whichever'
                            + ' one sorted first.'
                    },
                    {
                        key: 'keyPassword', label: 'Key Password', type: 'password', width: '260px',
                        visible: (p) => asBool(p.useSsl),
                        tooltip: 'The private key\u2019s own password, when it differs from the store\u2019s.'
                    },
                    {
                        key: 'clientAuth', label: 'Client Certificates', type: 'select', width: '220px',
                        options: CLIENT_AUTH, visible: (p) => asBool(p.useSsl), refresh: true,
                        tooltip: 'NEED refuses a client that cannot present a valid certificate. WANT accepts'
                            + ' either and leaves the channel to decide, which is useful while a partner'
                            + ' migrates. The subject is in the source map as fhirClientCertSubject.'
                    },
                    {
                        key: 'trustStoreFile', label: 'Trust Store File', type: 'text', width: '380px',
                        visible: (p) => asBool(p.useSsl) && p.clientAuth !== 'NONE',
                        placeholder: 'fhir/clients.p12',
                        tooltip: 'The certificates client certificates are verified against. Required: without'
                            + ' one the JDK falls back to its public CA bundle, and "client certificate'
                            + ' required" would be satisfied by a certificate from any CA on earth.'
                    },
                    {
                        key: 'trustStorePassword', label: 'Trust Store Password', type: 'password', width: '260px',
                        visible: (p) => asBool(p.useSsl) && p.clientAuth !== 'NONE'
                    },
                    {
                        key: 'trustStoreType', label: 'Trust Store Type', type: 'select', width: '140px',
                        options: STORE_TYPES, visible: (p) => asBool(p.useSsl) && p.clientAuth !== 'NONE'
                    },
                    {
                        key: 'protocols', label: 'Protocols', type: 'text', width: '260px',
                        visible: (p) => asBool(p.useSsl), placeholder: 'TLSv1.2,TLSv1.3',
                        tooltip: 'Comma separated. Blank leaves the JDK defaults, which on some builds still'
                            + ' include TLS 1.0 and 1.1.'
                    },
                    {
                        key: 'cipherSuites', label: 'Cipher Suites', type: 'text', span: true,
                        visible: (p) => asBool(p.useSsl),
                        tooltip: 'Comma separated. Blank uses Jetty\u2019s defaults, which already exclude the'
                            + ' suites with known problems — narrow this only against a partner\u2019s'
                            + ' requirement.'
                    },

                    { section: 'FHIR Listener Settings' },
                    {
                        key: 'fhirVersion', label: 'FHIR Version', type: 'select', options: FHIR_VERSIONS, width: '200px',
                        tooltip: 'The release this endpoint serves. Sets the fhirVersion media type parameter '
                            + 'and the version in a generated CapabilityStatement.'
                    },
                    {
                        key: 'basePath', label: 'Base Path', type: 'text', width: '260px', placeholder: '/fhir',
                        tooltip: 'Where the FHIR service base sits. Requests outside it are answered with 404.'
                    },
                    {
                        key: 'defaultFormat', label: 'Default Format', type: 'select', options: FORMATS, width: '160px',
                        tooltip: 'Used when the client states no preference through Accept or _format.'
                    },
                    {
                        key: 'strictVersionNegotiation', label: 'Strict Version', type: 'radio', options: YES_NO,
                        tooltip: 'Yes answers a request whose Accept or Content-Type names a different FHIR '
                            + 'release with 406 instead of serving it anyway.'
                    },
                    {
                        key: 'messageContent', label: 'Message Content', type: 'radio',
                        options: [
                            { value: 'BODY', label: 'Body' },
                            { value: 'ENVELOPE', label: 'Request envelope (JSON)' }
                        ],
                        tooltip: 'Body hands the channel the request body, with everything parsed in the source '
                            + 'map. Envelope hands it one JSON object containing the request line, the parsed '
                            + 'FHIR coordinates, the parameters, the headers and the body.'
                    },
                    {
                        key: 'enabledInteractions', label: 'Enabled Interactions', type: 'text', span: true,
                        tooltip: 'Comma-separated interaction codes to serve; anything else is answered with 405. '
                            + 'Blank means all of them. Codes: ' + ALL_INTERACTIONS
                    },
                    {
                        key: 'resourceTypes', label: 'Resource Types', type: 'text', span: true,
                        placeholder: 'blank = any type',
                        tooltip: 'Comma-separated resource types to serve; anything else is answered with 404. '
                            + 'Listing them here is also what lets a generated CapabilityStatement name them.'
                    },

                    { section: 'CapabilityStatement' },
                    {
                        key: 'capabilityMode', label: 'GET [base]/metadata', type: 'select',
                        options: CAPABILITY_MODES, refresh: true, width: '280px'
                    },
                    {
                        key: 'capabilityStatement', label: 'Custom Statement', type: 'code', language: 'json',
                        minHeight: '220px', visible: (p) => p.capabilityMode === 'CUSTOM',
                        tooltip: 'Served verbatim. Supports ${} template values.'
                    },
                    {
                        key: 'baseUrlOverride', label: 'Base URL Override', type: 'text', width: '380px',
                        placeholder: 'https://fhir.example.org/fhir',
                        tooltip: 'The service base URL to advertise in Location headers and the '
                            + 'CapabilityStatement. Blank derives it from the request and any X-Forwarded-* '
                            + 'headers the proxy in front sets.'
                    },

                    { section: 'Response' },
                    {
                        key: 'responseStatusCode', label: 'Response Status Code', type: 'text', width: '120px',
                        placeholder: 'auto',
                        tooltip: 'Overrides the status code this connector would choose. Supports ${} values '
                            + 'resolved against the processed message. Blank uses the FHIR default for the '
                            + 'interaction: 201 for a create, 204 for a body-less delete, 202 when queued.'
                    },
                    {
                        key: 'wrapNonFhirResponses', label: 'Wrap Non-FHIR', type: 'radio', options: YES_NO,
                        tooltip: 'Yes wraps anything a destination returns that is not a FHIR resource in an '
                            + 'OperationOutcome, so the client always receives valid FHIR.'
                    },
                    {
                        key: 'useResponseHeadersVariable', label: 'Response Headers', type: 'radio', refresh: true,
                        options: [
                            { value: false, label: 'Use Table' },
                            { value: true, label: 'Use Map' }
                        ]
                    },
                    {
                        key: 'responseHeaders', label: 'Headers', type: 'keyvalue', mapShape: 'list',
                        disabled: (p) => asBool(p.useResponseHeadersVariable)
                    },
                    {
                        key: 'responseHeadersVariable', label: 'Map Variable', type: 'text', width: '280px',
                        placeholder: 'e.g. fhirResponseHeaders',
                        disabled: (p) => !asBool(p.useResponseHeadersVariable)
                    },

                    { section: 'Advanced' },
                    { key: 'timeout', label: 'Receive Timeout (ms)', type: 'number', width: '140px' },
                    {
                        key: 'maxRequestSize', label: 'Max Request Size', type: 'number', width: '160px',
                        tooltip: 'Largest accepted request body in bytes; a larger one is answered with 413. '
                            + '0 means no limit.'
                    },
                    { key: 'charset', label: 'Charset Encoding', type: 'select', options: CHARSETS, width: '200px' }
                ]
            });
        },

        validate(properties) {
            return requireFields(properties, [
                { key: 'listenerConnectorProperties.host', label: 'Local Address' },
                { key: 'listenerConnectorProperties.port', label: 'Local Port' },
                { key: 'timeout', label: 'Receive Timeout' },
                {
                    key: 'capabilityStatement', label: 'Custom Statement',
                    when: (p) => p.capabilityMode === 'CUSTOM'
                },
                {
                    key: 'responseHeadersVariable', label: 'Response Headers Map Variable',
                    when: (p) => asBool(p.useResponseHeadersVariable)
                },
                // The two rules the connector also enforces at deploy: TLS needs a
                // certificate, and client authentication needs something to verify it
                // against. Better to fail on Save than on a channel that will not start.
                {
                    key: 'keyStoreFile', label: 'Key Store File',
                    when: (p) => asBool(p.useSsl)
                },
                {
                    key: 'trustStoreFile', label: 'Trust Store File',
                    when: (p) => asBool(p.useSsl) && p.clientAuth !== 'NONE'
                }
            ]);
        }
    };
}

/* ------------------------------------------------------------------ sender */

function senderPanel(kit) {
    const { ConnectorForm, requireFields, asBool, h } = kit;

    const isBody = (p) => ['CREATE', 'UPDATE', 'PATCH', 'TRANSACTION', 'OPERATION'].indexOf(p.interaction) >= 0;

    return {
        defaults(version) {
            return {
                '@class': DISPATCHER_CLASS,
                '@version': version,
                pluginProperties: null,
                destinationConnectorProperties: defaultDestinationProperties(version),
                fhirVersion: 'R4',
                serverUrl: '',
                interaction: 'CREATE',
                resourceType: '',
                resourceId: '',
                versionId: '',
                operationName: '',
                content: '${message.encodedData}',
                contentFormat: 'json',
                acceptFormat: 'json',
                charset: 'UTF-8',
                parameters: { '@class': 'linked-hash-map' },
                useParametersVariable: false,
                parametersVariable: '',
                headers: { '@class': 'linked-hash-map' },
                useHeadersVariable: false,
                headersVariable: '',
                authenticationType: 'NONE',
                username: '',
                password: '',
                bearerToken: '',
                ifMatch: '',
                ifNoneExist: '',
                ifNoneMatch: '',
                preferReturn: 'NONE',
                useProxyServer: false,
                proxyAddress: '',
                proxyPort: '',
                socketTimeout: '30000'
            };
        },

        component({ properties, onChange }) {
            return h(ConnectorForm, {
                properties,
                onChange,
                fields: [
                    { section: 'FHIR Sender Settings' },
                    { key: 'fhirVersion', label: 'FHIR Version', type: 'select', options: FHIR_VERSIONS, width: '200px' },
                    {
                        key: 'serverUrl', label: 'Server URL', type: 'text', width: '440px',
                        placeholder: 'https://server.example.org/fhir',
                        tooltip: 'The FHIR service base URL. Supports ${} values.'
                    },
                    {
                        key: 'interaction', label: 'Interaction', type: 'select', options: INTERACTIONS,
                        refresh: true, width: '360px'
                    },
                    {
                        key: 'resourceType', label: 'Resource Type', type: 'text', width: '220px',
                        placeholder: 'Patient', disabled: (p) => p.interaction === 'TRANSACTION',
                        tooltip: 'Supports ${} values. Optional for an operation, which may be system level.'
                    },
                    {
                        key: 'resourceId', label: 'Resource Id', type: 'text', width: '280px',
                        disabled: (p) => ['CREATE', 'SEARCH', 'TRANSACTION'].indexOf(p.interaction) >= 0,
                        tooltip: 'Leave blank on update, patch or delete to use the conditional form, which '
                            + 'selects the target with the query parameters below.'
                    },
                    {
                        key: 'versionId', label: 'Version Id', type: 'text', width: '140px',
                        disabled: (p) => p.interaction !== 'VREAD'
                    },
                    {
                        key: 'operationName', label: 'Operation', type: 'text', width: '220px',
                        placeholder: '$everything', disabled: (p) => p.interaction !== 'OPERATION'
                    },

                    { section: 'Content' },
                    {
                        key: 'content', label: 'Template', type: 'code',
                        language: (p) => (p.contentFormat === 'xml' ? 'xml' : 'json'),
                        minHeight: '260px', disabled: (p) => !isBody(p),
                        tooltip: 'The request body. Supports ${} values.'
                    },
                    {
                        key: 'contentFormat', label: 'Content Format', type: 'select', options: FORMATS,
                        refresh: true, width: '160px', disabled: (p) => !isBody(p)
                    },
                    { key: 'acceptFormat', label: 'Accept Format', type: 'select', options: FORMATS, width: '160px' },
                    { key: 'charset', label: 'Charset Encoding', type: 'select', options: CHARSETS, width: '200px' },

                    { section: 'Query Parameters' },
                    {
                        key: 'useParametersVariable', label: 'Parameters', type: 'radio', refresh: true,
                        options: [
                            { value: false, label: 'Use Table' },
                            { value: true, label: 'Use Map' }
                        ]
                    },
                    {
                        key: 'parameters', label: 'Parameters', type: 'keyvalue', mapShape: 'list',
                        disabled: (p) => asBool(p.useParametersVariable)
                    },
                    {
                        key: 'parametersVariable', label: 'Map Variable', type: 'text', width: '280px',
                        disabled: (p) => !asBool(p.useParametersVariable)
                    },

                    { section: 'Headers' },
                    {
                        key: 'useHeadersVariable', label: 'Headers', type: 'radio', refresh: true,
                        options: [
                            { value: false, label: 'Use Table' },
                            { value: true, label: 'Use Map' }
                        ]
                    },
                    {
                        key: 'headers', label: 'Headers', type: 'keyvalue', mapShape: 'list',
                        disabled: (p) => asBool(p.useHeadersVariable),
                        tooltip: 'Applied last, so an entry here overrides a header this connector would '
                            + 'otherwise set — which is how you send a JSON Patch content type.'
                    },
                    {
                        key: 'headersVariable', label: 'Map Variable', type: 'text', width: '280px',
                        disabled: (p) => !asBool(p.useHeadersVariable)
                    },

                    { section: 'Authentication' },
                    {
                        key: 'authenticationType', label: 'Type', type: 'select', options: AUTH_TYPES,
                        refresh: true, width: '200px',
                        tooltip: 'Credentials are sent preemptively, without waiting for a 401 challenge.'
                    },
                    {
                        key: 'username', label: 'Username', type: 'text', width: '280px',
                        disabled: (p) => p.authenticationType !== 'BASIC'
                    },
                    {
                        key: 'password', label: 'Password', type: 'password', width: '280px',
                        disabled: (p) => p.authenticationType !== 'BASIC'
                    },
                    {
                        key: 'bearerToken', label: 'Bearer Token', type: 'password', width: '380px',
                        disabled: (p) => p.authenticationType !== 'BEARER',
                        tooltip: 'With or without the leading "Bearer ". Supports ${} values, which is how a '
                            + 'channel passes a token it fetched from an OAuth2 token endpoint.'
                    },

                    { section: 'Conditional Interactions' },
                    {
                        key: 'ifMatch', label: 'If-Match', type: 'text', width: '220px', placeholder: 'W/"2"',
                        tooltip: 'Version-aware update or delete.'
                    },
                    {
                        key: 'ifNoneExist', label: 'If-None-Exist', type: 'text', width: '380px',
                        placeholder: 'identifier=http://acme.org/mrn|12345',
                        tooltip: 'Conditional create: the search must match nothing for the create to proceed.'
                    },
                    { key: 'ifNoneMatch', label: 'If-None-Match', type: 'text', width: '220px' },
                    {
                        key: 'preferReturn', label: 'Prefer Return', type: 'select', options: PREFER_RETURN,
                        width: '300px'
                    },

                    { section: 'Advanced' },
                    { key: 'useProxyServer', label: 'Use Proxy Server', type: 'radio', options: YES_NO, refresh: true },
                    {
                        key: 'proxyAddress', label: 'Proxy Address', type: 'text', width: '280px',
                        disabled: (p) => !asBool(p.useProxyServer)
                    },
                    {
                        key: 'proxyPort', label: 'Proxy Port', type: 'number', width: '110px',
                        disabled: (p) => !asBool(p.useProxyServer)
                    },
                    { key: 'socketTimeout', label: 'Send Timeout (ms)', type: 'number', width: '140px' }
                ]
            });
        },

        validate(properties) {
            return requireFields(properties, [
                { key: 'serverUrl', label: 'Server URL' },
                { key: 'socketTimeout', label: 'Send Timeout' },
                {
                    key: 'resourceType', label: 'Resource Type',
                    when: (p) => ['TRANSACTION', 'OPERATION'].indexOf(p.interaction) < 0
                },
                {
                    key: 'resourceId', label: 'Resource Id',
                    when: (p) => ['READ', 'VREAD'].indexOf(p.interaction) >= 0
                },
                { key: 'versionId', label: 'Version Id', when: (p) => p.interaction === 'VREAD' },
                { key: 'operationName', label: 'Operation', when: (p) => p.interaction === 'OPERATION' },
                { key: 'proxyAddress', label: 'Proxy Address', when: (p) => asBool(p.useProxyServer) },
                { key: 'proxyPort', label: 'Proxy Port', when: (p) => asBool(p.useProxyServer) }
            ]);
        }
    };
}

export function register(platform) {
    if (!platform || !platform.React) {
        console.warn('[connector-fhir] no host React instance; connector panels not registered');
        return;
    }
    const kit = createFormKit(platform);
    // The transport names must match <name> in source.xml / destination.xml exactly:
    // that string is the key the channel editor looks a panel up by.
    platform.registerConnectorPanel('FHIR Listener', 'SOURCE', listenerPanel(kit));
    platform.registerConnectorPanel('FHIR Sender', 'DESTINATION', senderPanel(kit));
}
