/*
 * Null Sender panel for the OIE web administrator.
 *
 * One panel, registered against the connector name in destination.xml:
 *
 *   Null Sender (destination)   records and acknowledges every message, delivers nowhere
 *
 * The form layer comes from the console's own connector library, so this panel is
 * the same widgets, spacing and theme as every other connector rather than a second
 * look bolted on.
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
 * `defaults()` mirrors the Java constructor field for field. That matters more
 * than it looks: a missing or misspelled field makes the engine store the whole
 * channel as an InvalidChannel while reporting a successful save -- the failure
 * scripts/oie-config-push.sh exists to catch.
 */

import {
    ConnectorForm,
    defaultDestinationProperties
} from '@oie/web-ui';

const DISPATCHER_CLASS = 'org.openintegrationengine.connectors.nullsender.NullDispatcherProperties';

/*
 * The values are AckMode's constant names, which is what XStream writes to the
 * channel XML. The labels are the enum's toString(), so both administrators offer
 * the same three choices under the same words.
 */
const ACK_MODE_OPTIONS = [
    { value: 'NONE', label: 'None' },
    { value: 'TEMPLATE', label: 'Template' },
    { value: 'HL7_ACK', label: 'HL7 ACK' }
];

/** Matches NullSenderPanel.checkProperties in the desktop Administrator. */
const ACK_CODE_PATTERN = /^(A[ARE]|C[ARE])$/i;

export function register(platform) {
    const React = platform.React;
    const h = React.createElement;

    const sender = {
        defaults(version) {
            return {
                '@class': DISPATCHER_CLASS,
                '@version': version,
                pluginProperties: null,
                destinationConnectorProperties: defaultDestinationProperties(version),
                ackMode: 'HL7_ACK',
                responseTemplate: '',
                ackCode: 'AA',
                ackTextMessage: '',
                logEachMessage: false
            };
        },

        component({ properties, onChange }) {
            return h(ConnectorForm, {
                properties, onChange, fields: [
                    { section: 'Null Sender' },
                    {
                        key: 'ackMode', label: 'Acknowledgement', type: 'select',
                        options: ACK_MODE_OPTIONS, width: '200px', refresh: true,
                        tooltip: 'Every message is recorded and discarded whichever of these is chosen;'
                            + ' this only decides what comes back. None returns no content, which is right'
                            + ' when the source connector generates its own acknowledgement. HL7 ACK builds'
                            + ' a v2 acknowledgement from the inbound message, the same way the source'
                            + ' connectors’ Auto-generate setting does.'
                    },
                    {
                        key: 'ackCode', label: 'ACK Code', type: 'text', width: '120px',
                        placeholder: 'AA', disabled: (p) => p.ackMode !== 'HL7_ACK',
                        tooltip: 'MSA-1: AA, AE or AR, or the enhanced-mode CA, CE or CR. Supports ${} values.'
                    },
                    {
                        key: 'ackTextMessage', label: 'ACK Text Message', type: 'text', width: '360px',
                        disabled: (p) => p.ackMode !== 'HL7_ACK',
                        tooltip: 'MSA-3, the human-readable text on the acknowledgement. Supports ${} values;'
                            + ' blank is fine.'
                    },

                    { section: 'Logging' },
                    {
                        key: 'logEachMessage', label: 'Server Log', type: 'checkbox',
                        checkLabel: 'Log one line per discarded message',
                        tooltip: 'Off by default: the message store is already the record of what arrived,'
                            + ' and a channel ending here is usually a busy one.'
                    },

                    { section: 'Response Template' },
                    {
                        key: 'responseTemplate', label: 'Template', type: 'code', minHeight: '220px',
                        disabled: (p) => p.ackMode !== 'TEMPLATE',
                        tooltip: 'The response content for the Template acknowledgement. Supports ${} values.'
                    }
                ]
            });
        },

        /**
         * A blank response template is deliberately allowed: answering with nothing while
         * still being recorded as having answered is a real configuration. Only the ACK
         * code can actually be wrong, and only when it is not itself a template.
         */
        validate(properties) {
            const errors = [];
            if (properties.ackMode === 'HL7_ACK') {
                const code = String(properties.ackCode || '').trim();
                if (!code.includes('${') && !ACK_CODE_PATTERN.test(code)) {
                    errors.push('ACK Code must be AA, AE, AR, CA, CE or CR.');
                }
            }
            return errors;
        }
    };

    // The name must match <name> in destination.xml: that string is how the console
    // finds the panel for a connector, and how the engine finds the class for the
    // properties.
    platform.registerConnectorPanel('Null Sender', 'DESTINATION', sender);
}
