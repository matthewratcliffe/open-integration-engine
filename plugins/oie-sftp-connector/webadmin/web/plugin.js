/*
 * SFTP connector panels for the OIE web administrator.
 *
 * Two panels, registered against the connector names in source.xml and
 * destination.xml:
 *
 *   SFTP Listener (source)      push: an SFTP server inside the engine
 *                               pull: polling a remote SFTP server
 *   SFTP Sender (destination)   writing each message to a remote server
 *
 * The form layer comes from the console's own connector library -- ConnectorForm,
 * PollSection, the charset list, the Test Connection button -- so these panels are
 * the same widgets, spacing and theme as every other connector rather than a
 * second look bolted on.
 *
 * It is imported from the BARE specifier `@oie/web-ui`, not from
 * `/connectors/react-forms.js` as the console's own bundled connector plugins do,
 * and that difference matters. A plugin the engine serves is fetched as text and
 * imported from a blob: URL, and a root-relative specifier cannot be resolved
 * against a blob: base -- the whole module fails with "Failed to resolve module
 * specifier", the panel never registers, and the channel editor silently falls
 * back to the raw-JSON editor. Bare specifiers still go through the page's import
 * map, which is why this one works. @oie/web-ui re-exports the same module, so
 * these are the same components the bundled panels use, not copies.
 *
 * `defaults()` mirrors the Java constructors field for field. That matters more
 * than it looks: a missing or misspelled field makes the engine store the whole
 * channel as an InvalidChannel while reporting a successful save -- the failure
 * scripts/oie-config-push.sh exists to catch.
 */

import {
    ConnectorForm,
    PollSection,
    connectorTestButton,
    defaultSourceProperties,
    defaultDestinationProperties,
    defaultListenerProperties,
    defaultPollProperties,
    listenerAddressField,
    CHARSETS,
    asBool
} from '@oie/web-ui';

const RECEIVER_CLASS = 'org.openintegrationengine.connectors.sftp.SftpReceiverProperties';
const DISPATCHER_CLASS = 'org.openintegrationengine.connectors.sftp.SftpDispatcherProperties';

/*
 * XStream names a list's elements after their class, so the users list arrives as
 * { 'org.openintegrationengine...SftpServerUser': {...} } for one entry and an
 * array for several. Writing the same shape back is what keeps the channel
 * readable by the engine.
 */
const USER_CLASS = 'org.openintegrationengine.connectors.sftp.SftpServerUser';

const MODE_OPTIONS = [
    { value: 'PUSH', label: 'Push (clients upload to this engine)' },
    { value: 'PULL', label: 'Pull (poll a remote server)' }
];

const AUTH_OPTIONS = [
    { value: 'PASSWORD', label: 'Password' },
    { value: 'PUBLIC_KEY', label: 'Public key' },
    { value: 'BOTH', label: 'Key, then password' }
];

const HOST_KEY_OPTIONS = [
    { value: 'TRUST_ANY', label: 'Trust any (not verified)' },
    { value: 'KNOWN_HOSTS', label: 'known_hosts file' },
    { value: 'PINNED', label: 'Pinned key' }
];

const FILE_ACTION_OPTIONS = [
    { value: 'NONE', label: 'None' },
    { value: 'MOVE', label: 'Move' },
    { value: 'DELETE', label: 'Delete' }
];

const SORT_OPTIONS = [
    { value: 'date', label: 'Date' },
    { value: 'name', label: 'Name' },
    { value: 'size', label: 'Size' }
];

const FILE_TYPE_OPTIONS = [
    { value: false, label: 'Text' },
    { value: true, label: 'Binary' }
];

/** Defaults for the nested connection object, mirroring SftpConnectionProperties(). */
function defaultConnectionProperties() {
    return {
        host: '',
        port: '22',
        username: '',
        authMethod: 'PASSWORD',
        password: '',
        privateKeyInline: false,
        privateKeyFile: '',
        privateKey: '',
        passphrase: '',
        hostKeyPolicy: 'TRUST_ANY',
        knownHostsFile: '',
        hostKey: '',
        connectTimeout: '5000',
        timeout: '10000',
        configurationSettings: { '@class': 'linked-hash-map' }
    };
}

function isPull(properties) {
    return properties.mode === 'PULL';
}

function usesPassword(properties) {
    const method = properties.connectionProperties && properties.connectionProperties.authMethod;
    return method === 'PASSWORD' || method === 'BOTH';
}

function usesKey(properties) {
    const method = properties.connectionProperties && properties.connectionProperties.authMethod;
    return method === 'PUBLIC_KEY' || method === 'BOTH';
}

/**
 * The remote-server fields, shared by the listener in pull mode and the sender so
 * a partner is configured identically in both directions.
 *
 * @param channel     the channel being edited, for the Test Connection call
 * @param properties  the live properties object, likewise
 * @param testPath    the servlet endpoint to test against
 */
function connectionFields(channel, properties, testPath) {
    return [
        { section: 'Remote SFTP Server' },
        {
            key: 'connectionProperties.host', label: 'Host', type: 'text', width: '240px',
            append: () => connectorTestButton({ path: testPath, channel, properties })
        },
        { key: 'connectionProperties.port', label: 'Port', type: 'text', width: '90px' },
        { key: 'connectionProperties.username', label: 'Username', type: 'text', width: '200px' },
        {
            key: 'connectionProperties.authMethod', label: 'Authentication', type: 'select',
            options: AUTH_OPTIONS, width: '200px', refresh: true,
            tooltip: 'Key, then password offers the private key first and falls back to the password,'
                + ' which is what an OpenSSH client does by default.'
        },
        {
            key: 'connectionProperties.password', label: 'Password', type: 'password', width: '200px',
            disabled: (p) => !usesPassword(p),
            tooltip: 'A configuration map reference by its bare key -- ${partnerPassword}, not'
                + ' ${configurationMap.partnerPassword} -- keeps the secret out of the channel XML,'
                + ' and out of git.'
        },
        {
            key: 'connectionProperties.privateKeyInline', label: 'Private Key', type: 'checkbox',
            checkLabel: 'Paste the key instead of reading a file', refresh: true,
            disabled: (p) => !usesKey(p),
            tooltip: 'Useful when the engine is a container with nowhere durable to keep a key file.'
        },
        {
            key: 'connectionProperties.privateKeyFile', label: 'Private Key File', type: 'text', width: '320px',
            disabled: (p) => !usesKey(p) || asBool(p.connectionProperties.privateKeyInline),
            tooltip: 'Path on the engine, not on your workstation.'
        },
        {
            key: 'connectionProperties.privateKey', label: 'Private Key', type: 'textarea', rows: 6,
            visible: (p) => usesKey(p) && asBool(p.connectionProperties.privateKeyInline),
            placeholder: '-----BEGIN OPENSSH PRIVATE KEY-----'
        },
        {
            key: 'connectionProperties.passphrase', label: 'Passphrase', type: 'password', width: '200px',
            disabled: (p) => !usesKey(p)
        },
        {
            key: 'connectionProperties.hostKeyPolicy', label: 'Host Key', type: 'select',
            options: HOST_KEY_OPTIONS, width: '220px', refresh: true,
            tooltip: 'SSH has no certificate authorities: the only real verification is against a key you'
                + ' already hold. Trust any encrypts the transfer but proves nothing about who answered.'
        },
        {
            key: 'connectionProperties.knownHostsFile', label: 'known_hosts File', type: 'text', width: '320px',
            visible: (p) => p.connectionProperties.hostKeyPolicy === 'KNOWN_HOSTS'
        },
        {
            key: 'connectionProperties.hostKey', label: 'Pinned Host Key', type: 'textarea', rows: 3,
            visible: (p) => p.connectionProperties.hostKeyPolicy === 'PINNED',
            placeholder: 'ssh-ed25519 AAAAC3Nz...    (ssh-keyscan -t ed25519 host prints this)'
        },
        { key: 'connectionProperties.connectTimeout', label: 'Connect Timeout (ms)', type: 'text', width: '110px' },
        { key: 'connectionProperties.timeout', label: 'Timeout (ms)', type: 'text', width: '110px' },
        {
            key: 'connectionProperties.configurationSettings', label: 'Advanced Settings', type: 'keyvalue',
            tooltip: 'Raw JSch settings, applied last so they override everything above: kex,'
                + ' server_host_key, cipher.s2c and friends. Only an unusual server needs one.'
        }
    ];
}

/** The file-handling fields, identical in push and pull. */
function fileFields() {
    return [
        { section: 'Files' },
        {
            key: 'fileFilter', label: 'File Filter', type: 'text', width: '240px',
            tooltip: 'Wildcards, space or comma separated: *.hl7 *.txt. A leading ! negates the list,'
                + ' so !*.tmp takes everything except part files.'
        },
        { key: 'regex', label: '', type: 'checkbox', checkLabel: 'Filter is a regular expression' },
        { key: 'ignoreDot', label: '', type: 'checkbox', checkLabel: 'Ignore dot files' },
        {
            key: 'binary', label: 'File Type', type: 'radio', options: FILE_TYPE_OPTIONS, refresh: true,
            tooltip: 'Binary reads the file as bytes and base64 encodes it into the message.'
                + ' Batch processing is not available for binary data.'
        },
        {
            key: 'charsetEncoding', label: 'Encoding', type: 'select', options: CHARSETS, width: '160px',
            disabled: (p) => asBool(p.binary)
        },
        {
            key: 'afterProcessingAction', label: 'After Processing Action', type: 'radio',
            options: FILE_ACTION_OPTIONS, refresh: true
        },
        {
            key: 'moveToDirectory', label: 'Move-to Directory', type: 'text', width: '320px',
            disabled: (p) => p.afterProcessingAction !== 'MOVE'
        },
        {
            key: 'moveToFileName', label: 'Move-to File Name', type: 'text', width: '320px',
            disabled: (p) => p.afterProcessingAction !== 'MOVE'
        },
        {
            key: 'errorReadingAction', label: 'Error Reading Action', type: 'radio',
            options: FILE_ACTION_OPTIONS, refresh: true
        },
        {
            key: 'errorResponseAction', label: 'Error in Response Action', type: 'radio',
            options: FILE_ACTION_OPTIONS, refresh: true
        },
        {
            key: 'errorMoveToDirectory', label: 'Error Move-to Directory', type: 'text', width: '320px',
            disabled: (p) => p.errorReadingAction !== 'MOVE' && p.errorResponseAction !== 'MOVE'
        },
        {
            key: 'errorMoveToFileName', label: 'Error Move-to File Name', type: 'text', width: '320px',
            disabled: (p) => p.errorReadingAction !== 'MOVE' && p.errorResponseAction !== 'MOVE'
        }
    ];
}

export function register(platform) {
    const React = platform.React;
    const h = React.createElement;

    /* ---------------------------------------------------------------- */
    /* the users table                                                   */
    /* ---------------------------------------------------------------- */

    function readUsers(properties) {
        const list = properties.users;
        if (!list || typeof list !== 'object') {
            return [];
        }
        const entries = list[USER_CLASS];
        if (entries === undefined || entries === null) {
            return [];
        }
        return Array.isArray(entries) ? entries : [entries];
    }

    function writeUsers(properties, users) {
        properties.users = users.length ? { [USER_CLASS]: users } : {};
    }

    function newUser(index) {
        return {
            username: 'user' + index,
            password: '',
            authorizedKeys: '',
            homeDirectory: '',
            readOnly: false
        };
    }

    /*
     * A row per account, with the key box under the one being edited rather than
     * in a second table: an authorized_keys line is 80 characters of base64 and
     * would make every column unreadable if it were one.
     */
    function UsersSection({ properties, onChange }) {
        const [, repaint] = React.useReducer((n) => n + 1, 0);
        const [selected, setSelected] = React.useState(0);
        const users = readUsers(properties);

        const notify = () => { onChange(); repaint(); };

        const update = (index, field, value) => {
            users[index][field] = value;
            writeUsers(properties, users);
            notify();
        };

        const rows = users.map((user, index) => h('div', {
            key: index,
            className: 'flex gap-1.5 mb-1.5 items-center',
            onFocus: () => setSelected(index)
        },
            h('input', {
                type: 'text', value: user.username ?? '', placeholder: 'Username',
                className: 'w-[160px]',
                onChange: (e) => update(index, 'username', e.target.value)
            }),
            h('input', {
                type: 'password', value: user.password ?? '', placeholder: 'Password',
                autoComplete: 'off', className: 'w-[160px]',
                onChange: (e) => update(index, 'password', e.target.value)
            }),
            h('input', {
                type: 'text', value: user.homeDirectory ?? '', placeholder: 'Home directory',
                className: 'w-[160px]',
                title: 'Relative to the root directory. Blank means the root itself.'
                    + ' The account cannot see anything above it.',
                onChange: (e) => update(index, 'homeDirectory', e.target.value)
            }),
            h('label', { className: 'check', title: 'Allow listing and downloading, refuse writes.' },
                h('input', {
                    type: 'checkbox', checked: !!user.readOnly,
                    onChange: (e) => update(index, 'readOnly', e.target.checked)
                }), 'Read only'),
            h('button', {
                type: 'button', className: 'btn',
                title: 'Edit this account’s authorized keys',
                onClick: () => { setSelected(index); repaint(); }
            }, 'Keys'),
            h('button', {
                type: 'button', className: 'btn',
                onClick: () => {
                    users.splice(index, 1);
                    writeUsers(properties, users);
                    setSelected(0);
                    notify();
                }
            }, 'Delete')));

        const current = users[selected];

        return h('div', { className: 'cform-section' },
            h('div', { className: 'cform-section-title' }, 'Users'),
            h('div', { className: 'hint mb-1.5' },
                'An account with no password refuses password authentication, and one with no'
                + ' authorized key refuses public key authentication. An account with neither cannot'
                + ' be logged into at all, and the channel refuses to deploy.'),
            rows,
            h('div', { className: 'mt-1.5' },
                h('button', {
                    type: 'button', className: 'btn',
                    onClick: () => {
                        users.push(newUser(users.length + 1));
                        writeUsers(properties, users);
                        setSelected(users.length - 1);
                        notify();
                    }
                }, 'New')),
            current ? h('div', { className: 'mt-3' },
                h('label', { className: 'cform-label top' },
                    'Authorized keys for ' + (current.username || '(unnamed)') + ':'),
                h('textarea', {
                    rows: 4,
                    value: current.authorizedKeys ?? '',
                    placeholder: 'ssh-ed25519 AAAAC3Nz... partner@example',
                    onChange: (e) => update(selected, 'authorizedKeys', e.target.value)
                }),
                h('div', { className: 'hint' },
                    'One key per line, exactly as it would appear in ~/.ssh/authorized_keys.')) : null);
    }

    /* ---------------------------------------------------------------- */
    /* SFTP Listener                                                     */
    /* ---------------------------------------------------------------- */

    const listener = {
        defaults(version) {
            return {
                '@class': RECEIVER_CLASS,
                '@version': version,
                pluginProperties: null,
                listenerConnectorProperties: defaultListenerProperties(version, 2222),
                sourceConnectorProperties: defaultSourceProperties(version),
                pollConnectorProperties: defaultPollProperties(version),
                mode: 'PUSH',
                rootDirectory: 'sftp/${channelId}',
                users: {},
                hostKeyFile: 'sftp/hostkey.ser',
                hostKeyAlgorithm: 'EC',
                hostKeySize: '256',
                maxSessions: '10',
                authTimeout: '30000',
                idleTimeout: '300000',
                allowDownloads: false,
                dispatchOnRename: true,
                processExistingOnStart: false,
                connectionProperties: defaultConnectionProperties(),
                remoteDirectory: '',
                directoryRecursion: false,
                sortBy: 'date',
                fileFilter: '*',
                regex: false,
                ignoreDot: true,
                binary: false,
                charsetEncoding: 'DEFAULT_ENCODING',
                afterProcessingAction: 'NONE',
                moveToDirectory: '',
                moveToFileName: '',
                errorReadingAction: 'NONE',
                errorResponseAction: 'NONE',
                errorMoveToDirectory: '',
                errorMoveToFileName: ''
            };
        },

        component({ properties, channel, onChange }) {
            const [, repaint] = React.useReducer((n) => n + 1, 0);
            // The mode switch changes which whole sections exist, not just which
            // fields are enabled, so the panel repaints rather than the form.
            const notify = () => { onChange(); repaint(); };
            const pull = isPull(properties);

            return h('div', null,
                h(ConnectorForm, {
                    properties, onChange: notify, fields: [
                        { section: 'SFTP Listener' },
                        {
                            key: 'mode', label: 'Mode', type: 'radio', options: MODE_OPTIONS, refresh: true,
                            tooltip: 'Push runs an SFTP server inside the engine and dispatches a message as'
                                + ' each upload completes. Pull polls a remote server on the schedule below.'
                        },

                        /*
                         * Spread in, rather than marked `visible`, because ConnectorForm
                         * hides a section HEADER whose visible() is false and then renders
                         * the fields that follow it anyway -- they fall into an untitled
                         * section and stay on screen. Building the list per mode is the
                         * only way to say "these fields do not exist in pull mode".
                         */
                        ...(pull ? [] : [
                        { section: 'SFTP Server' },
                        listenerAddressField('listenerConnectorProperties.host'),
                        { key: 'listenerConnectorProperties.port', label: 'Listener Port', type: 'text', width: '90px',
                            tooltip: 'The engine runs unprivileged, so port 22 usually means publishing this'
                                + ' port as 22 on the container rather than changing it here.' },
                        {
                            key: 'rootDirectory', label: 'Root Directory', type: 'text', width: '320px',
                            tooltip: 'Where uploads land. A relative path is taken from the engine’s'
                                + ' application data directory, which is the volume this stack persists.'
                                + ' Clients cannot see or reach anything above it.'
                        },
                        {
                            key: 'hostKeyFile', label: 'Host Key File', type: 'text', width: '320px',
                            tooltip: 'Generated on first start and reused after that. A key that changed on'
                                + ' every restart would make every client refuse to connect.'
                        },
                        {
                            key: 'hostKeyAlgorithm', label: 'Host Key Type', type: 'select', width: '120px',
                            options: [{ value: 'EC', label: 'EC' }, { value: 'RSA', label: 'RSA' }]
                        },
                        { key: 'hostKeySize', label: 'Host Key Size', type: 'text', width: '90px' },
                        { key: 'maxSessions', label: 'Max Sessions', type: 'text', width: '90px' },
                        { key: 'authTimeout', label: 'Auth Timeout (ms)', type: 'text', width: '110px' },
                        { key: 'idleTimeout', label: 'Idle Timeout (ms)', type: 'text', width: '110px' },
                        {
                            key: 'allowDownloads', label: 'Downloads', type: 'checkbox',
                            checkLabel: 'Allow clients to list and download',
                            tooltip: 'Off by default: a drop box that also hands files back is an exposure'
                                + ' nobody asked for.'
                        },
                        {
                            key: 'dispatchOnRename', label: 'Renames', type: 'checkbox',
                            checkLabel: 'Treat a rename into place as a completed upload',
                            tooltip: 'Handles clients that write to a temporary name and rename when'
                                + ' finished: exclude the temporary name in the filter and the message is'
                                + ' dispatched at the rename.'
                        },
                        {
                            key: 'processExistingOnStart', label: 'On Start', type: 'checkbox',
                            checkLabel: 'Process files already in the directory',
                            tooltip: 'Picks up an upload that arrived while the channel was stopped. Only'
                                + ' safe with an after-processing action of Move or Delete -- with None it'
                                + ' re-sends everything in the directory on every deploy.'
                        }
                        ])
                    ]
                }),

                pull ? null : h(UsersSection, { properties, onChange: notify }),

                pull ? h(ConnectorForm, {
                    properties, onChange: notify, fields: [
                        ...connectionFields(channel, properties, '/connectors/sftp/_testConnection'),
                        { section: 'Remote Directory' },
                        {
                            key: 'remoteDirectory', label: 'Directory', type: 'text', width: '320px',
                            tooltip: 'Blank polls the login directory.'
                        },
                        {
                            key: 'directoryRecursion', label: 'Subdirectories', type: 'checkbox',
                            checkLabel: 'Include subdirectories'
                        },
                        { key: 'sortBy', label: 'Sort Files By', type: 'select', options: SORT_OPTIONS, width: '140px' }
                    ]
                }) : null,

                pull ? h(PollSection, { properties, onChange: notify }) : null,

                h(ConnectorForm, { properties, onChange: notify, fields: fileFields() }));
        },

        /** Mirrors SftpListenerPanel.checkProperties in the Swing panel. */
        validate(properties) {
            const errors = [];
            if (isPull(properties)) {
                const connection = properties.connectionProperties || {};
                if (!String(connection.host || '').trim()) {
                    errors.push('Host is required.');
                }
                if (!String(connection.username || '').trim()) {
                    errors.push('Username is required.');
                }
                if (connection.authMethod === 'PUBLIC_KEY') {
                    const key = asBool(connection.privateKeyInline) ? connection.privateKey : connection.privateKeyFile;
                    if (!String(key || '').trim()) {
                        errors.push('Public key authentication needs a private key.');
                    }
                }
                if (connection.hostKeyPolicy === 'KNOWN_HOSTS' && !String(connection.knownHostsFile || '').trim()) {
                    errors.push('A known_hosts file is required for that host key policy.');
                }
                if (connection.hostKeyPolicy === 'PINNED' && !String(connection.hostKey || '').trim()) {
                    errors.push('A pinned host key is required for that host key policy.');
                }
            } else {
                if (!String(properties.rootDirectory || '').trim()) {
                    errors.push('Root Directory is required.');
                }
                const users = readUsers(properties);
                if (!users.length) {
                    errors.push('At least one user is required.');
                }
                for (const user of users) {
                    if (!String(user.username || '').trim()) {
                        errors.push('Every user needs a username.');
                        break;
                    }
                }
                for (const user of users) {
                    if (!String(user.password || '') && !String(user.authorizedKeys || '').trim()) {
                        errors.push('User "' + (user.username || '')
                            + '" has neither a password nor an authorized key, so nothing could log in as it.');
                        break;
                    }
                }
            }
            if (!String(properties.fileFilter || '').trim()) {
                errors.push('File Filter is required.');
            }
            return errors;
        }
    };

    /* ---------------------------------------------------------------- */
    /* SFTP Sender                                                       */
    /* ---------------------------------------------------------------- */

    const sender = {
        defaults(version) {
            return {
                '@class': DISPATCHER_CLASS,
                '@version': version,
                pluginProperties: null,
                destinationConnectorProperties: defaultDestinationProperties(version),
                connectionProperties: defaultConnectionProperties(),
                remoteDirectory: '',
                outputPattern: '',
                template: '${message.encodedData}',
                binary: false,
                charsetEncoding: 'DEFAULT_ENCODING',
                outputAppend: false,
                errorOnExists: false,
                temporary: true,
                temporarySuffix: '.tmp',
                createDirectories: false,
                filePermissions: '',
                keepConnectionOpen: true
            };
        },

        component({ properties, channel, onChange }) {
            return h(ConnectorForm, {
                properties, onChange, fields: [
                    ...connectionFields(channel, properties, '/connectors/sftp/_testConnection'),

                    { section: 'File' },
                    {
                        key: 'remoteDirectory', label: 'Directory', type: 'text', width: '320px',
                        tooltip: 'Blank writes to the login directory.'
                    },
                    {
                        key: 'outputPattern', label: 'File Name', type: 'text', width: '320px',
                        placeholder: '${message.messageId}.hl7'
                    },
                    {
                        key: 'createDirectories', label: 'Directories', type: 'checkbox',
                        checkLabel: 'Create the directory if it does not exist'
                    },
                    {
                        key: 'outputAppend', label: 'Existing File', type: 'checkbox',
                        checkLabel: 'Append to an existing file', refresh: true
                    },
                    {
                        key: 'errorOnExists', label: '', type: 'checkbox',
                        checkLabel: 'Fail the message if the file already exists'
                    },
                    {
                        key: 'temporary', label: 'Temporary File', type: 'checkbox',
                        checkLabel: 'Write to a temporary name and rename on completion',
                        refresh: true, disabled: (p) => asBool(p.outputAppend),
                        tooltip: 'The rename is atomic on the far side, so a partner polling the directory'
                            + ' can never pick up a file this connector is still writing. Not available when'
                            + ' appending.'
                    },
                    {
                        key: 'temporarySuffix', label: 'Temporary Suffix', type: 'text', width: '120px',
                        disabled: (p) => asBool(p.outputAppend) || !asBool(p.temporary)
                    },
                    {
                        key: 'filePermissions', label: 'Permissions', type: 'text', width: '90px',
                        placeholder: '640',
                        tooltip: 'Octal, such as 640. Blank leaves whatever the server’s umask produced.'
                    },
                    {
                        key: 'keepConnectionOpen', label: 'Connection', type: 'checkbox',
                        checkLabel: 'Keep the connection open between messages',
                        tooltip: 'An SSH handshake costs more than sending a small message, so reusing the'
                            + ' session matters for throughput.'
                    },
                    {
                        key: 'binary', label: 'File Type', type: 'radio', options: FILE_TYPE_OPTIONS, refresh: true,
                        tooltip: 'Binary decodes the template’s value as base64 and writes the bytes.'
                    },
                    {
                        key: 'charsetEncoding', label: 'Encoding', type: 'select', options: CHARSETS, width: '160px',
                        disabled: (p) => asBool(p.binary)
                    },

                    { section: 'Template' },
                    { key: 'template', label: 'Template', type: 'code', minHeight: '300px' }
                ]
            });
        },

        /** Mirrors SftpSenderPanel.checkProperties in the Swing panel. */
        validate(properties) {
            const errors = [];
            const connection = properties.connectionProperties || {};
            if (!String(connection.host || '').trim()) {
                errors.push('Host is required.');
            }
            if (!String(connection.username || '').trim()) {
                errors.push('Username is required.');
            }
            if (connection.authMethod === 'PUBLIC_KEY') {
                const key = asBool(connection.privateKeyInline) ? connection.privateKey : connection.privateKeyFile;
                if (!String(key || '').trim()) {
                    errors.push('Public key authentication needs a private key.');
                }
            }
            if (connection.hostKeyPolicy === 'KNOWN_HOSTS' && !String(connection.knownHostsFile || '').trim()) {
                errors.push('A known_hosts file is required for that host key policy.');
            }
            if (connection.hostKeyPolicy === 'PINNED' && !String(connection.hostKey || '').trim()) {
                errors.push('A pinned host key is required for that host key policy.');
            }
            if (!String(properties.outputPattern || '').trim()) {
                errors.push('File Name is required.');
            }
            if (String(properties.filePermissions || '').trim()
                && !/^[0-7]{3,4}$/.test(String(properties.filePermissions).trim())) {
                errors.push('Permissions must be octal, such as 640.');
            }
            return errors;
        }
    };

    // The names must match <name> in source.xml and destination.xml: that string is
    // how the console finds the panel for a connector, and how the engine finds the
    // class for the properties.
    platform.registerConnectorPanel('SFTP Listener', 'SOURCE', listener);
    platform.registerConnectorPanel('SFTP Sender', 'DESTINATION', sender);
}
