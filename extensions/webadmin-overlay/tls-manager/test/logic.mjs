/*
 * The pure helpers out of webadmin/web/plugin.js, so they can be tested without
 * a browser or a React runtime. This is the webadmin overlay for the vendor TLS
 * Manager, not one of the plugins/ extensions, but the same test convention
 * applies.
 *
 * Copied rather than imported, the same arrangement the Backup / Git Sync /
 * Volume Monitor extensions use. readSet/writeSet/tlsDefaults and the SUPPORTED
 * list are copied verbatim; isSupported/isListener are the registration
 * predicates, lifted to named functions.
 */

// Engine parity: TLSConnectorPropertiesPlugin.isSupported().
export const SUPPORTED = [
    'HTTP Listener', 'TCP Listener', 'Web Service Listener',
    'HTTP Sender', 'TCP Sender', 'Web Service Sender',
];

/*
 * Java Set<String> serialises through XStream as
 * { '@class': 'linked-hash-set', string: [...] }, and a single-element set can
 * arrive with `string` as a bare value rather than an array. Normalise both.
 */
export function readSet(value) {
    if (!value) return [];
    const items = value.string;
    if (items == null) return [];
    return Array.isArray(items) ? items.slice() : [items];
}

export function writeSet(values) {
    return { '@class': 'linked-hash-set', string: values.slice() };
}

/*
 * Defaults mirror the TLSConnectorProperties() constructor exactly. Getting
 * these wrong makes the engine store the whole channel as an InvalidChannel,
 * reported as a successful save.
 */
export function tlsDefaults(version) {
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

export function isSupported(transportName, supported = SUPPORTED) {
    return supported.indexOf(transportName) !== -1;
}

export function isListener(transportName) {
    return /Listener$/.test(transportName || '');
}
