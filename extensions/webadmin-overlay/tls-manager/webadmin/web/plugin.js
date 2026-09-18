/*
 * TLS Manager -> OIE web console.
 *
 * Adds the two things the console is missing for TLS Manager:
 *
 *   1. A "TLS Settings" tab on the HTTP / TCP / Web Service senders and
 *      listeners, editing the same connector property entry the Swing
 *      Administrator's tab edits. TLS Manager's connector UI is a Swing
 *      ConnectorPropertiesPlugin, so the console has no equivalent until
 *      someone registers one.
 *   2. A "TLS Manager" sidebar item that opens the plugin's certificate
 *      manager (its own app at /tls-manager/, not a console plugin).
 *
 * Written as plain ES module JavaScript on purpose: no JSX, no imports, no
 * build step. The console dynamically import()s this file and passes its own
 * `platform` into register(), which is how the bundled plugins receive it.
 * Importing @oie/web-shell here would depend on the page's import map and risks
 * a second framework instance registering into a dead registry, which fails
 * silently.
 *
 * Styling comes from the shell's own classes -- .panel, .field, .hint, .btn,
 * .form-grid, .mono -- so both surfaces inherit the app's spacing, borders and
 * themes instead of carrying a second look of their own.
 */

// The single concrete class the plugin actually stores. TLSListenerProperties /
// TLSSenderProperties also exist in the jar but are not what the connector tab
// writes -- TLSConnectorProperties is the one registered with XStream and the
// one whose getName() matches the Swing plugin's point name.
const TLS_CLASS = 'org.openintegrationengine.tlsmanager.shared.properties.TLSConnectorProperties';

// Engine parity: TLSConnectorPropertiesPlugin.isSupported().
const SUPPORTED = [
    'HTTP Listener', 'TCP Listener', 'Web Service Listener',
    'HTTP Sender', 'TCP Sender', 'Web Service Sender',
];

const REVOCATION_MODES = [
    { value: 'DISABLED', label: 'Disabled' },
    { value: 'SOFT_FAIL', label: 'Soft Fail' },
    { value: 'HARD_FAIL', label: 'Hard Fail' },
];

const CLIENT_AUTH_MODES = [
    { value: 'NONE', label: 'None' },
    { value: 'REQUESTED', label: 'Requested' },
    { value: 'REQUIRED', label: 'Required' },
];

const DN_VALIDATION_MODES = [
    { value: 'NONE', label: 'None' },
    { value: 'PARTIAL', label: 'Partial' },
    { value: 'EXACT', label: 'Exact' },
];

const PROTOCOLS = ['TLSv1.3', 'TLSv1.2', 'TLSv1.1', 'TLSv1'];

const APP_PATH = '/tls-manager/';

/*
 * The tokens the console theme exposes. Read from the live document rather than
 * hardcoded, so the embedded app follows a theme change instead of freezing at
 * whatever was current when it loaded.
 */
const THEME_TOKENS = [
    '--bg1', '--bg2', '--bg3', '--text', '--text-dim', '--text-faint',
    '--line', '--line-strong', '--accent', '--accent-ink', '--radius',
    '--font-ui', '--font-mono', '--err', '--warn', '--ok',
];

function readTheme() {
    const cs = getComputedStyle(document.documentElement);
    const out = {};
    THEME_TOKENS.forEach(t => { out[t] = cs.getPropertyValue(t).trim(); });
    out.scheme = document.documentElement.getAttribute('data-theme') === 'light'
        ? 'light' : 'dark';
    return out;
}

/*
 * A stylesheet for the embedded app.
 *
 * Two jobs. First, hide its own fixed AppBar: inside a console panel that is a
 * second logo and a second logout button, and that logout signs you out of the
 * inner app only, which is actively confusing.
 *
 * Second, carry the console's theme across. The app is MUI and light-only, so on
 * a dark console it would otherwise glare white. Mapping the surfaces onto the
 * console's own tokens is what makes it look like part of the page rather than a
 * window into a different product.
 *
 * Only library-level MUI class names are targeted, never the hashed emotion
 * ones. If their markup changes the rules stop matching and the app reverts to
 * its own styling -- degraded, not broken.
 */
function frameCss(theme) {
    const v = n => theme[n] || '';
    return `
:root { color-scheme: ${theme.scheme}; }

/* Its own chrome: the console already provides a header and navigation. */
header.MuiAppBar-root { display: none !important; }
.MuiToolbar-root.mui-fixed { display: none !important; }
body > div > .MuiToolbar-root:first-child { display: none !important; }
body { padding-top: 0 !important; }

/* Surfaces and text from the console's palette. */
html, body {
    background: ${v('--bg1')} !important;
    color: ${v('--text')} !important;
    font-family: ${v('--font-ui')} !important;
}
.MuiPaper-root, .MuiCard-root, .MuiDialog-paper, .MuiPopover-paper, .MuiMenu-paper {
    background-color: ${v('--bg2')} !important;
    color: ${v('--text')} !important;
    border-color: ${v('--line')} !important;
}
.MuiTypography-root, .MuiFormLabel-root, .MuiInputBase-input,
.MuiTableCell-root, .MuiListItemText-primary {
    color: ${v('--text')} !important;
}
.MuiFormHelperText-root, .MuiTypography-colorTextSecondary,
.MuiTableCell-head, .MuiInputLabel-root {
    color: ${v('--text-dim')} !important;
}
.MuiDivider-root, .MuiTableCell-root {
    border-color: ${v('--line')} !important;
}
.MuiOutlinedInput-notchedOutline {
    border-color: ${v('--line-strong')} !important;
}
.MuiInputBase-root {
    background-color: ${v('--bg2')} !important;
}
/* Monospace for the PEM and fingerprint fields, matching the rest of the app. */
.MuiInputBase-inputMultiline, code, pre {
    font-family: ${v('--font-mono')} !important;
}
.MuiTabs-indicator { background-color: ${v('--accent')} !important; }
.Mui-selected { color: ${v('--accent')} !important; }
`;
}

// A 24x24 stroke-only glyph: padlock over a shield.
const ICON_TLS = 'M12 3l7 3v5.5c0 4.2-2.9 7.9-7 8.5-4.1-.6-7-4.3-7-8.5V6l7-3z'
    + ' M9.5 11.5h5v4h-5z M10.75 11.5v-1.25a1.25 1.25 0 0 1 2.5 0v1.25';

/*
 * Java Set<String> serialises through XStream as
 * { '@class': 'linked-hash-set', string: [...] }, and a single-element set can
 * arrive with `string` as a bare value rather than an array. Normalise both.
 */
function readSet(value) {
    if (!value) return [];
    const items = value.string;
    if (items == null) return [];
    return Array.isArray(items) ? items.slice() : [items];
}

function writeSet(values) {
    return { '@class': 'linked-hash-set', string: values.slice() };
}

export function register(platform) {
    const React = platform.React;
    const h = React.createElement;

    /*
     * Defaults mirror the TLSConnectorProperties() constructor exactly. Getting
     * these wrong is the failure mode that matters: a missing or misspelled
     * field makes the engine store the whole channel as an InvalidChannel,
     * which it reports as a successful save.
     */
    function tlsDefaults(version) {
        return {
            '@version': version,
            isTlsManagerEnabled: false,
            trustSystemTruststore: true,
            trustedServerCertificates: writeSet([]),
            subjectDnValidationMode: 'NONE',
            subjectDnValidationFilter: null,
            crlMode: 'HARD_FAIL',
            ocspMode: 'HARD_FAIL',
            isUseServerDefaultProtocols: true,
            usedProtocols: writeSet([]),
            isUseServerDefaultCiphers: true,
            usedCiphers: writeSet([]),
            serverCertificateAlias: null,
            clientAuthMode: 'NONE',
            isHostnameVerificationEnabled: true,
            clientCertificateAlias: null,
        };
    }

    // ------------------------------------------------------------------
    // Field helpers, using the shell's .field / .hint classes so the panel
    // matches every other connector settings tab.
    // ------------------------------------------------------------------

    function Field(label, control, hint) {
        return h('div', { className: 'field' }, [
            h('label', { key: 'l' }, label),
            control,
            hint ? h('div', { key: 'h', className: 'hint' }, hint) : null,
        ]);
    }

    function Check(entry, key, onChange, force) {
        return h('input', {
            type: 'checkbox',
            checked: !!entry[key],
            onChange: function (e) {
                entry[key] = e.target.checked;
                onChange();
                force();
            },
        });
    }

    function Select(entry, key, options, onChange, force) {
        return h('select', {
            value: entry[key] == null ? '' : entry[key],
            className: 'max-w-[220px]',
            onChange: function (e) {
                entry[key] = e.target.value;
                onChange();
                force();
            },
        }, options.map(function (o) {
            return h('option', { key: o.value, value: o.value }, o.label);
        }));
    }

    function Text(entry, key, onChange, placeholder) {
        return h('input', {
            type: 'text',
            value: entry[key] == null ? '' : entry[key],
            placeholder: placeholder || '',
            className: 'w-full max-w-[420px]',
            onInput: function (e) {
                // Empty string back to null, matching the Java default.
                entry[key] = e.target.value === '' ? null : e.target.value;
                onChange();
            },
        });
    }

    // A select over certificate aliases. An alias the channel references but the
    // store no longer has is kept and labelled, never silently dropped.
    function AliasSelect(entry, key, aliases, onChange, force) {
        const current = entry[key];
        const options = [{ value: '', label: '-- none --' }].concat(
            aliases.map(function (a) { return { value: a, label: a }; })
        );
        if (current && aliases.indexOf(current) === -1) {
            options.push({ value: current, label: current + '  (not found in store)' });
        }

        return h('select', {
            value: current == null ? '' : current,
            className: 'max-w-[280px]',
            onChange: function (e) {
                entry[key] = e.target.value === '' ? null : e.target.value;
                onChange();
                force();
            },
        }, options.map(function (o) {
            return h('option', { key: o.value || '__none', value: o.value }, o.label);
        }));
    }

    // Multi-select over a Java Set<String>.
    function SetCheckboxes(entry, key, choices, onChange, force) {
        const selected = readSet(entry[key]);
        return h('div', { className: 'cform-control' }, choices.map(function (choice) {
            return h('label', {
                key: choice,
                style: { display: 'inline-flex', alignItems: 'center', gap: '5px' },
            }, [
                h('input', {
                    key: 'i',
                    type: 'checkbox',
                    checked: selected.indexOf(choice) !== -1,
                    onChange: function (e) {
                        const next = readSet(entry[key]).filter(function (v) {
                            return v !== choice;
                        });
                        if (e.target.checked) next.push(choice);
                        // Keep the declared order rather than click order.
                        entry[key] = writeSet(choices.filter(function (c) {
                            return next.indexOf(c) !== -1;
                        }));
                        onChange();
                        force();
                    },
                }),
                h('span', { key: 's' }, choice),
            ]);
        }));
    }

    // Trusted-certificate aliases as a checkbox set.
    function TrustSelect(entry, key, aliases, onChange, force) {
        const selected = readSet(entry[key]);
        const choices = aliases.slice();
        // Never hide an alias the channel references but the store lacks.
        selected.forEach(function (a) {
            if (choices.indexOf(a) === -1) choices.push(a);
        });

        if (choices.length === 0) {
            return h('div', { className: 'hint' },
                'No trusted certificates imported yet — add them under TLS Manager '
                + 'in the sidebar.');
        }

        return h('div', {
            style: { display: 'flex', flexDirection: 'column', gap: '3px' },
        }, choices.map(function (alias) {
            const missing = aliases.indexOf(alias) === -1;
            return h('label', {
                key: alias,
                style: { display: 'inline-flex', alignItems: 'center', gap: '5px' },
            }, [
                h('input', {
                    key: 'i',
                    type: 'checkbox',
                    checked: selected.indexOf(alias) !== -1,
                    onChange: function (e) {
                        const next = readSet(entry[key]).filter(function (v) {
                            return v !== alias;
                        });
                        if (e.target.checked) next.push(alias);
                        entry[key] = writeSet(next);
                        onChange();
                        force();
                    },
                }),
                h('span', { key: 's', className: missing ? 'hint' : undefined },
                    missing ? alias + '  (not found in store)' : alias),
            ]);
        }));
    }

    // ------------------------------------------------------------------
    // Certificate aliases, read once per panel mount from TLS Manager's API.
    // Mounted at /api/tlsmanager, not under /api/extensions.
    // ------------------------------------------------------------------
    function useCertificateAliases() {
        const [state, setState] = React.useState({ local: [], trusted: [], error: null });

        React.useEffect(function () {
            let cancelled = false;

            const load = function (path) {
                return fetch('/api/tlsmanager/' + path, {
                    headers: { Accept: 'application/json', 'X-Requested-With': 'oie-webadmin' },
                    credentials: 'same-origin',
                }).then(function (res) {
                    if (!res.ok) throw new Error(path + ': HTTP ' + res.status);
                    return res.json();
                }).then(function (body) {
                    // { list: null } when empty; the item key varies by store.
                    const list = (body && body.list) || {};
                    const items = list.localCertificate || list.trustedCertificate || [];
                    const arr = Array.isArray(items) ? items : [items];
                    return arr.map(function (i) { return i && i.alias; }).filter(Boolean);
                });
            };

            Promise.all([load('localCertificates'), load('trustedCertificates')])
                .then(function (results) {
                    if (!cancelled) setState({ local: results[0], trusted: results[1], error: null });
                })
                .catch(function (err) {
                    // Aliases are a convenience; the panel still works without
                    // them because a referenced alias is always preserved.
                    if (!cancelled) {
                        setState({ local: [], trusted: [], error: String(err.message || err) });
                    }
                });

            return function () { cancelled = true; };
        }, []);

        return state;
    }

    // ------------------------------------------------------------------
    // The connector TLS Settings panel
    // ------------------------------------------------------------------
    function TlsSettingsPanel(ctx) {
        const getEntry = ctx.getEntry;
        const setEntry = ctx.setEntry;
        const connector = ctx.connector;
        const onChange = ctx.onChange;

        const certs = useCertificateAliases();
        const [, setTick] = React.useState(0);
        const force = React.useCallback(function () {
            setTick(function (t) { return t + 1; });
        }, []);

        const isListener = /Listener$/.test((connector && connector.transportName) || '');
        const entry = getEntry();

        if (!entry) {
            return h('div', null, [
                h('div', { key: 'm', className: 'hint', style: { marginBottom: '7px' } },
                    'TLS is not configured on this connector.'),
                h('button', {
                    key: 'b',
                    type: 'button',
                    className: 'btn',
                    onClick: function () {
                        const version = (connector && connector.properties
                            && connector.properties['@version']) || undefined;
                        setEntry(tlsDefaults(version));
                        onChange();
                        force();
                    },
                }, 'Configure TLS'),
            ]);
        }

        const rows = [];

        rows.push(Field('Enable TLS Manager',
            Check(entry, 'isTlsManagerEnabled', onChange, force),
            'Off leaves this connector exactly as it behaves without the plugin.'));

        const on = !!entry.isTlsManagerEnabled;

        if (on && isListener) {
            rows.push(Field('Server certificate',
                AliasSelect(entry, 'serverCertificateAlias', certs.local, onChange, force),
                'The key pair this listener presents. Imported under TLS Manager.'));

            rows.push(Field('Client authentication',
                Select(entry, 'clientAuthMode', CLIENT_AUTH_MODES, onChange, force),
                'Required is mutual TLS. Requested asks for a certificate but still '
                + 'accepts callers without one.'));
        }

        if (on && !isListener) {
            rows.push(Field('Client certificate',
                AliasSelect(entry, 'clientCertificateAlias', certs.local, onChange, force),
                'The key pair presented to the peer. Leave as none for one-way TLS.'));

            rows.push(Field('Verify hostname',
                Check(entry, 'isHostnameVerificationEnabled', onChange, force),
                'Off accepts a valid certificate issued for any name, which defeats most '
                + 'of the point.'));
        }

        if (on) {
            rows.push(Field('Trust system truststore',
                Check(entry, 'trustSystemTruststore', onChange, force),
                'Accept peers chaining to the JDK public roots, in addition to those '
                + 'selected below.'));

            rows.push(Field('Trusted certificates',
                TrustSelect(entry, 'trustedServerCertificates', certs.trusted, onChange, force),
                isListener
                    ? 'The CA that issues your callers’ client certificates.'
                    : 'The CA that issues the peer’s server certificate.'));

            rows.push(Field('Subject DN validation',
                Select(entry, 'subjectDnValidationMode', DN_VALIDATION_MODES, onChange, force),
                'Without this, any certificate the trusted CA signed is accepted, so the '
                + 'CA becomes the whole authorisation boundary.'));

            if (entry.subjectDnValidationMode && entry.subjectDnValidationMode !== 'NONE') {
                rows.push(Field('Subject DN filter',
                    Text(entry, 'subjectDnValidationFilter', onChange,
                        'CN=partner,O=Example Health'),
                    'Exact matches the whole DN; Partial matches a substring.'));
            }

            rows.push(h('div', { key: 'revocation', className: 'form-grid' }, [
                Field('CRL checking',
                    Select(entry, 'crlMode', REVOCATION_MODES, onChange, force)),
                Field('OCSP checking',
                    Select(entry, 'ocspMode', REVOCATION_MODES, onChange, force),
                    'Hard Fail rejects the connection when revocation cannot be '
                    + 'determined.'),
            ]));

            rows.push(Field('Server default protocols',
                Check(entry, 'isUseServerDefaultProtocols', onChange, force)));

            if (!entry.isUseServerDefaultProtocols) {
                rows.push(Field('Protocols',
                    SetCheckboxes(entry, 'usedProtocols', PROTOCOLS, onChange, force)));
            }

            rows.push(Field('Server default cipher suites',
                Check(entry, 'isUseServerDefaultCiphers', onChange, force),
                'Cipher suites are best left at the JVM defaults unless a peer forces '
                + 'otherwise; edit the list in the Swing Administrator, which enumerates '
                + 'what this JVM offers.'));
        }

        if (certs.error) {
            rows.push(h('div', { key: 'e', className: 'hint', style: { marginTop: '7px' } },
                'Could not read the certificate stores (' + certs.error
                + '). Aliases already configured are preserved.'));
        }

        rows.push(h('div', { key: 'r', style: { marginTop: '9px' } },
            h('button', {
                type: 'button',
                className: 'btn btn-danger',
                onClick: function () {
                    setEntry(null);
                    onChange();
                    force();
                },
            }, 'Remove TLS configuration')));

        return h('div', null, rows);
    }

    // ------------------------------------------------------------------
    // The certificate manager view
    // ------------------------------------------------------------------
    function TlsManagerView() {
        const [blocked, setBlocked] = React.useState(false);
        const frameRef = React.useRef(null);

        // Applied on load and re-applied whenever the console's theme changes, so
        // the embedded app follows rather than freezing at the theme it loaded with.
        const applyTheme = React.useCallback(function () {
            const el = frameRef.current;
            if (!el) return;
            try {
                const doc = el.contentDocument;
                if (!doc || !doc.head) return;
                let style = doc.getElementById('oie-console-bridge-style');
                if (!style) {
                    style = doc.createElement('style');
                    style.id = 'oie-console-bridge-style';
                    doc.head.appendChild(style);
                }
                style.textContent = frameCss(readTheme());
            } catch (err) {
                // Cross-origin read: framing is being refused, reported below.
            }
        }, []);

        const onFrameRef = React.useCallback(function (el) {
            frameRef.current = el;
            if (!el) return;

            // The inner app replaces its document on login, which would otherwise
            // drop the injected stylesheet.
            el.addEventListener('load', applyTheme);
            applyTheme();

            window.setTimeout(function () {
                try {
                    const doc = el.contentDocument;
                    if (!doc || !doc.body || doc.body.childElementCount === 0) {
                        setBlocked(true);
                    } else {
                        applyTheme();
                    }
                } catch (err) {
                    setBlocked(true);
                }
            }, 2500);
        }, [applyTheme]);

        React.useEffect(function () {
            // The console flips data-theme on <html>; watching it is what makes the
            // embedded app change with the rest of the page.
            const observer = new MutationObserver(applyTheme);
            observer.observe(document.documentElement, {
                attributes: true,
                attributeFilter: ['data-theme', 'data-font-ui', 'data-font-mono'],
            });
            return function () { observer.disconnect(); };
        }, [applyTheme]);

        if (blocked) {
            return h('div', {
                className: 'view',
                style: { display: 'block', overflowY: 'auto', height: '100%', padding: '1rem' },
            }, h('div', { className: 'panel' }, [
                h('div', { key: 'h', className: 'panel-header' }, 'TLS Manager'),
                h('div', { key: 'b', className: 'panel-body' }, [
                    h('div', { key: 't', style: { fontWeight: 600, marginBottom: '5px' } },
                        'TLS Manager did not load in this panel.'),
                    h('div', { key: 'm', className: 'hint' },
                        'The engine denies framing unless server.api.xframeoptions is '
                        + "SAMEORIGIN and server.api.contentsecuritypolicy allows "
                        + "frame-ancestors 'self'."),
                    h('div', { key: 'l', style: { marginTop: '9px' } },
                        h('a', {
                            className: 'btn',
                            href: APP_PATH,
                            target: '_blank',
                            rel: 'noopener noreferrer',
                        }, 'Open TLS Manager in a new tab')),
                ]),
            ]));
        }

        // Framed in a .panel so it sits in the app's visual language -- border,
        // radius and surface -- rather than bleeding to the window edges.
        return h('div', {
            className: 'view',
            style: {
                display: 'flex',
                flexDirection: 'column',
                height: '100%',
                minHeight: 0,
                padding: '11px',
                boxSizing: 'border-box',
            },
        }, h('div', {
            className: 'panel',
            style: { display: 'flex', flexDirection: 'column', flex: '1 1 auto', minHeight: 0 },
        }, h('iframe', {
            ref: onFrameRef,
            src: APP_PATH,
            title: 'TLS Manager',
            style: {
                flex: '1 1 auto',
                width: '100%',
                minHeight: 0,
                border: 0,
                display: 'block',
                borderRadius: 'var(--radius)',
            },
        })));
    }

    // ------------------------------------------------------------------
    // Registration
    // ------------------------------------------------------------------
    platform.registerConnectorPropertiesPanel({
        id: 'tls-manager',
        title: 'TLS Settings',
        propertiesClass: TLS_CLASS,
        isSupported: function (transportName) {
            return SUPPORTED.indexOf(transportName) !== -1;
        },
        defaults: tlsDefaults,
        component: TlsSettingsPanel,
    });

    platform.registerIcon('tls-manager', ICON_TLS);

    platform.registerNavItem({
        id: 'tls-manager',
        label: 'TLS Manager',
        icon: 'tls-manager',
        // Console-relative: /oie-webadmin/tls-manager, which does not collide
        // with the WAR mounted at the server root.
        path: '/tls-manager',
        section: 'Plugins',
        order: 90,
    });

    platform.registerView('/tls-manager', platform.reactView(TlsManagerView), {
        title: 'TLS Manager',
    });
}
