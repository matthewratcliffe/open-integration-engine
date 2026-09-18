/*
 * Random Generator panel for the OIE web administrator.
 *
 * One panel, registered against the connector name in source.xml: a source that
 * manufactures HL7 v2 messages on the polling schedule instead of receiving them.
 *
 * The form layer comes from the console's own connector library -- ConnectorForm,
 * PollSection, taskButton, modal -- so this panel is the same widgets, spacing and
 * theme as every other connector rather than a second look bolted on. Note which
 * half of it is React and which is DOM: see the comment above loadSampleButton.
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
    PollSection,
    defaultSourceProperties,
    defaultPollProperties,
    postConnectorProperties,
    apiErrorMessage,
    taskButton,
    confirmDialog,
    modal,
    toast,
    h as domH
} from '@oie/web-ui';

const RECEIVER_CLASS = 'org.openintegrationengine.connectors.generator.RandomGeneratorProperties';

const PREVIEW_PATH = '/connectors/generator/_preview';

/*
 * XStream names a list's elements after their class, so the patients list arrives as
 * { 'org...PatientRecord': {...} } for one row and an array for several. Writing the
 * same shape back is what keeps the channel readable by the engine.
 */
const PATIENT_CLASS = 'org.openintegrationengine.connectors.generator.PatientRecord';

/**
 * The patients table's columns: property key, heading, grid track, and what it means.
 *
 * `track` is a grid column rather than a width because the headings and the inputs are
 * two separate rows: give each its own width and they drift apart by however much
 * padding and borders the console's input style adds, which is exactly what a run of
 * nine columns makes obvious. One grid, shared by both rows, cannot drift.
 */
const PATIENT_COLUMNS = [
    { key: 'mrn', label: 'MRN', track: 'minmax(84px, 1.1fr)', title: '${patient.mrn}, and PID-3.' },
    { key: 'family', label: 'Family Name', track: 'minmax(96px, 1.25fr)' },
    { key: 'given', label: 'Given Name', track: 'minmax(96px, 1.25fr)' },
    { key: 'sex', label: 'Sex', track: 'minmax(48px, 0.45fr)', title: 'M, F, O or U.' },
    { key: 'dateOfBirth', label: 'Date of Birth', track: 'minmax(92px, 1fr)', placeholder: 'yyyyMMdd',
        title: 'yyyyMMdd or yyyy-MM-dd. Anything else fails the deploy naming the row.' },
    { key: 'visitNumber', label: 'Visit Number', track: 'minmax(92px, 1.05fr)', title: '${visit.number}, PV1-19.' },
    { key: 'patientClass', label: 'Class', track: 'minmax(48px, 0.45fr)', title: '${visit.class}, PV1-2: I, O or E.' },
    { key: 'location', label: 'Location', track: 'minmax(120px, 1.5fr)', placeholder: 'ICU^7^B^RPA',
        title: 'PV1-3, as WARD^ROOM^BED^FACILITY. Fewer components is fine: ICU alone sets the ward'
            + ' and keeps the drawn room, bed and facility.' },
    { key: 'attendingDoctor', label: 'Attending Doctor', track: 'minmax(120px, 1.5fr)', placeholder: 'DR12345^SMITH',
        title: 'PV1-7. A whole XCN, or just an id to keep the drawn name.' }
];

/* The one grid both the heading row and every patient row are laid out on. The last
   track is the Delete button; the rest share what is left. */
const PATIENT_GRID = {
    display: 'grid',
    gridTemplateColumns: PATIENT_COLUMNS.map((column) => column.track).join(' ') + ' 76px',
    gap: '6px',
    alignItems: 'center'
};

/*
 * The sample templates, one per message type. build.sh replaces this line with the
 * contents of samples/*.hl7, which is where the samples actually live: a console
 * plugin is fetched as text and imported from a blob: URL, so it cannot import a
 * sibling module or fetch a file beside itself, and a second hand-maintained copy
 * in JavaScript would drift from the one the engine sends.
 *
 * If the inlining is ever missing, this panel degrades rather than breaks: the
 * template box starts empty, and a blank template makes the engine fall back to
 * the built-in sample for the selected type.
 */
const SAMPLES = {}; /* @SAMPLES@ */

/** Mirrors the MessageType enum, which is also where each sample file is named. */
const MESSAGE_TYPES = [
    { value: 'ADT_A01', label: 'ADT_A01 - Admit / visit notification' },
    { value: 'ADT_A03', label: 'ADT_A03 - Discharge / end visit' },
    { value: 'ADT_A08', label: 'ADT_A08 - Update patient information' },
    { value: 'ORM_O01', label: 'ORM_O01 - Order message' },
    { value: 'ORU_R01', label: 'ORU_R01 - Observation result' },
    { value: 'SIU_S12', label: 'SIU_S12 - New appointment booking' },
    { value: 'DFT_P03', label: 'DFT_P03 - Post detail financial transaction' },
    { value: 'MFN_M02', label: 'MFN_M02 - Master file: staff / practitioner' }
];

const SELECTION_OPTIONS = [
    { value: 'RANDOM', label: 'Random' },
    { value: 'SEQUENTIAL', label: 'Sequential (round robin)' }
];

const PROCESSING_IDS = [
    { value: 'T', label: 'T - Test' },
    { value: 'D', label: 'D - Debug' },
    { value: 'P', label: 'P - Production' }
];

const HL7_VERSIONS = ['2.3', '2.3.1', '2.4', '2.5', '2.5.1', '2.6', '2.7', '2.8']
    .map((v) => ({ value: v, label: v }));

function sampleFor(type) {
    return SAMPLES[type] || '';
}

function readPatients(properties) {
    const list = properties.patients;
    if (!list || typeof list !== 'object') {
        return [];
    }
    const entries = list[PATIENT_CLASS];
    if (entries === undefined || entries === null) {
        return [];
    }
    return Array.isArray(entries) ? entries : [entries];
}

function writePatients(properties, patients) {
    properties.patients = patients.length ? { [PATIENT_CLASS]: patients } : {};
}

function newPatient() {
    const row = {};
    for (const column of PATIENT_COLUMNS) {
        row[column.key] = '';
    }
    return row;
}

/** True when this channel walks a named list rather than an invented population. */
function hasDefinedPatients(properties) {
    return properties.patientSelection === 'SEQUENTIAL' && readPatients(properties).length > 0;
}

/*
 * Buttons here are DOM elements, not React elements.
 *
 * A field's `append` is called by ConnectorForm and its return value is passed to
 * appendChild, and the console's own button helpers build real elements the same
 * way. Rendering one of those buttons as a React child -- or returning a React
 * element from `append` -- is React error #31, "Objects are not valid as a React
 * child", and the channel editor replaces the whole panel with "This panel failed
 * to render". So these build DOM with the console's helpers and let ConnectorForm
 * mount them; only the form components themselves are React.
 */

/** Replaces the template with the sample for the selected type, asking first if edited. */
function loadSampleButton(properties, ctx) {
    return taskButton('Load Sample', 'import', async () => {
        const sample = sampleFor(properties.messageType);
        if (!sample) {
            toast('No sample is bundled for ' + properties.messageType + '.', 'error');
            return;
        }
        if (!holdsUneditedSample(properties.template)
                && !(await confirmDialog('Load Sample',
                    'Replace the template with the built-in sample for ' + properties.messageType + '?',
                    { okLabel: 'Replace' }))) {
            return;
        }
        properties.template = sample;
        ctx.onChange();
        ctx.repaint();
    });
}

/**
 * Generates one message on the server and shows it.
 *
 * A dialog rather than the usual toast: this response is a whole HL7 message,
 * which is worth reading in a monospaced block that the delimiters line up in.
 */
function previewButton(properties, channel) {
    const button = taskButton('Preview Message', 'eye', async () => {
        button.disabled = true;
        try {
            const result = await postConnectorProperties(PREVIEW_PATH, properties, channel);
            const type = result && typeof result === 'object' ? String(result.type ?? '') : '';
            const message = (result && typeof result === 'object' && result.message) || type
                || 'No response received';
            if (type !== 'SUCCESS') {
                toast(message, 'error');
                return;
            }
            modal({
                title: 'Generated Message',
                size: 'wide',
                body: domH('pre', {
                    style: {
                        margin: '0',
                        maxHeight: '60vh',
                        overflow: 'auto',
                        whiteSpace: 'pre',
                        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
                        fontSize: '12px'
                    }
                }, message),
                buttons: [{ label: 'Close', primary: true }]
            });
        } catch (e) {
            toast(apiErrorMessage(e), 'error');
        } finally {
            button.disabled = false;
        }
    });
    return button;
}

/** True if the box holds a sample nobody has edited, whichever type it came from. */
function holdsUneditedSample(template) {
    const current = String(template || '').trim();
    if (!current) {
        return true;
    }
    return Object.keys(SAMPLES).some((type) => current === String(SAMPLES[type]).trim());
}

export function register(platform) {
    const React = platform.React;
    const h = React.createElement;

    /* ---------------------------------------------------------------- */
    /* the patients table                                                */
    /* ---------------------------------------------------------------- */

    /*
     * Sequential mode's table: the patients to walk through, in order. Every cell is
     * optional -- what is left blank comes from the invented patient at that position --
     * which is the only reason nine columns can describe a patient a message carries
     * thirty-odd fields of.
     */
    function PatientsSection({ properties, onChange }) {
        const [, repaint] = React.useReducer((n) => n + 1, 0);
        const patients = readPatients(properties);

        const notify = () => { onChange(); repaint(); };

        const update = (index, key, value) => {
            patients[index][key] = value;
            writePatients(properties, patients);
            notify();
        };

        const headings = h('div', { style: { ...PATIENT_GRID, marginBottom: '2px' } },
            PATIENT_COLUMNS.map((column) => h('div', {
                key: column.key,
                className: 'hint',
                style: { overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' },
                title: column.title || undefined
            }, column.label)),
            h('div', null));

        const rows = patients.map((patient, index) => h('div', {
            key: index,
            style: { ...PATIENT_GRID, marginBottom: '6px' }
        },
            PATIENT_COLUMNS.map((column) => h('input', {
                key: column.key,
                type: 'text',
                value: patient[column.key] ?? '',
                placeholder: column.placeholder,
                title: column.title || undefined,
                // The grid track is the width. minWidth:0 stops an input's intrinsic
                // width from pushing its own column wider than the track it was given,
                // which is the other half of keeping the headings above it honest.
                style: { width: '100%', minWidth: 0, boxSizing: 'border-box' },
                onChange: (e) => update(index, column.key, e.target.value)
            })),
            h('button', {
                type: 'button', className: 'btn',
                style: { width: '100%' },
                onClick: () => {
                    patients.splice(index, 1);
                    writePatients(properties, patients);
                    notify();
                }
            }, 'Delete')));

        return h('div', { className: 'cform-section' },
            h('div', { className: 'cform-section-title' }, 'Defined Patients'),
            h('div', { className: 'hint mb-1.5' },
                patients.length
                    ? patients.length + ' patient(s), walked in this order; the Patients count above is'
                        + ' ignored. A blank cell is filled from the invented patient at that position, so a'
                        + ' row naming only an MRN still carries a stable address, next of kin and insurer.'
                    : 'No patients defined, so sequential mode walks the invented population above.'
                        + ' Add rows to name them.'),
            // Nine columns do not always fit a narrow window: scroll the table rather
            // than squeezing the inputs down to nothing or clipping the last one.
            h('div', { style: { overflowX: 'auto', paddingBottom: '2px' } },
                patients.length ? headings : null,
                rows),
            h('div', { className: 'mt-1.5' },
                h('button', {
                    type: 'button', className: 'btn',
                    onClick: () => {
                        patients.push(newPatient());
                        writePatients(properties, patients);
                        notify();
                    }
                }, 'New')));
    }

    const generator = {
        defaults(version) {
            return {
                '@class': RECEIVER_CLASS,
                '@version': version,
                pluginProperties: null,
                sourceConnectorProperties: defaultSourceProperties(version),
                pollConnectorProperties: defaultPollProperties(version),
                messageType: 'ADT_A01',
                // Blank is legal and means "the sample for the message type"; the panel
                // fills the box in on the first render so what is saved is what is shown.
                template: '',
                messagesPerPoll: '1',
                maxMessages: '0',
                patientCount: '25',
                patientSelection: 'RANDOM',
                seed: '',
                // An empty XStream list. A row per named patient goes in here under
                // PATIENT_CLASS, which is the shape the engine reads back.
                patients: {},
                sendingApplication: 'OIE',
                sendingFacility: 'GENERATOR',
                receivingApplication: 'DOWNSTREAM',
                receivingFacility: 'TEST',
                processingId: 'T',
                hl7Version: '2.5.1'
            };
        },

        component({ properties, channel, onChange }) {
            const [, repaint] = React.useReducer((n) => n + 1, 0);
            const lastType = React.useRef(properties.messageType);

            // A channel whose template is blank -- a new connector, or hand-written XML --
            // is shown the sample rather than an empty box, so the editor always displays
            // the HL7 the channel will actually send.
            if (!String(properties.template || '').trim() && sampleFor(properties.messageType)) {
                properties.template = sampleFor(properties.messageType);
            }

            const notify = () => {
                if (properties.messageType !== lastType.current) {
                    // Swap the sample in only while the box still holds an unedited one:
                    // somebody's own template is never overwritten by a change to a select.
                    if (holdsUneditedSample(properties.template)) {
                        properties.template = sampleFor(properties.messageType);
                    }
                    lastType.current = properties.messageType;
                }
                onChange();
                repaint();
            };


            return h('div', null,
                h(ConnectorForm, {
                    properties, onChange: notify, fields: [
                        { section: 'Message' },
                        {
                            key: 'messageType', label: 'Message Type', type: 'select',
                            options: MESSAGE_TYPES, width: '320px', refresh: true,
                            append: (props, ctx) => loadSampleButton(props, ctx),
                            tooltip: 'Fills in MSH-9, and -- while the template below is still an'
                                + ' unedited sample -- swaps in the sample for the type you pick.'
                        },
                        { key: 'sendingApplication', label: 'Sending Application', type: 'text', width: '200px' },
                        { key: 'sendingFacility', label: 'Sending Facility', type: 'text', width: '200px' },
                        { key: 'receivingApplication', label: 'Receiving Application', type: 'text', width: '200px' },
                        { key: 'receivingFacility', label: 'Receiving Facility', type: 'text', width: '200px' },
                        {
                            key: 'processingId', label: 'Processing ID', type: 'select',
                            options: PROCESSING_IDS, width: '160px',
                            tooltip: 'MSH-11. Generated traffic marked P is one mis-routed channel away'
                                + ' from being taken seriously by something downstream.'
                        },
                        { key: 'hl7Version', label: 'HL7 Version', type: 'select', options: HL7_VERSIONS, width: '120px' }
                    ]
                }),

                h(ConnectorForm, {
                    properties, onChange: notify, fields: [
                        { section: 'Template' },
                        {
                            key: 'template', label: 'Template', type: 'code', minHeight: '320px',
                            append: (props) => previewButton(props, channel),
                            tooltip: '${patient.*} and ${visit.*} are fixed per patient: the same MRN, name'
                                + ' and PV1 every time that patient appears. ${message.*} is the envelope.'
                                + ' ${random.*} is redrawn at every occurrence. Line endings are normalised'
                                + ' to HL7 carriage returns on the way out.'
                        }
                    ]
                }),

                h('div', { className: 'hint mb-3 mt-1' },
                    'Preview generates one message on the server with these settings, through the same'
                    + ' code a deployed channel uses, and names any placeholder it did not recognise --'
                    + ' those are sent through as literal ${...} text.'),

                h(ConnectorForm, {
                    properties, onChange: notify, fields: [
                        { section: 'Cadence' },
                        {
                            key: 'messagesPerPoll', label: 'Messages Per Poll', type: 'text', width: '90px',
                            tooltip: 'This and the polling interval below are the cadence: one every ten'
                                + ' seconds is a trickle to watch, five hundred a second is a load test.'
                        },
                        {
                            key: 'maxMessages', label: 'Maximum Messages', type: 'text', width: '110px',
                            tooltip: 'Stop after this many in total; 0 keeps going until the channel is'
                                + ' stopped. The count restarts when the channel starts.'
                        }
                    ]
                }),

                h(PollSection, { properties, onChange: notify }),

                h(ConnectorForm, {
                    properties, onChange: notify, fields: [
                        { section: 'Population' },
                        {
                            key: 'patientCount', label: 'Patients', type: 'text', width: '90px',
                            disabled: (p) => hasDefinedPatients(p),
                            tooltip: 'Every message is about one of them, so this is how many distinct MRNs,'
                                + ' names and visits the downstream system will ever see. Ignored while the'
                                + ' patients below are named.'
                        },
                        {
                            key: 'patientSelection', label: 'Patient Selection', type: 'radio',
                            options: SELECTION_OPTIONS, refresh: true,
                            tooltip: 'Random repeats patients the way a real feed does. Sequential walks the'
                                + ' population in order -- and lets you name the patients in a table below.'
                        },
                        {
                            key: 'seed', label: 'Seed', type: 'text', width: '200px',
                            placeholder: 'blank: derived from the channel id',
                            tooltip: 'The same seed always produces the same patients, here and on anyone'
                                + " else's engine. Any text works; a number is used as it stands."
                        }
                    ]
                }),

                /*
                 * The table is sequential mode's: a list is a thing to walk in order, and
                 * picking from it at random would be a different feature wearing the same
                 * table. Rows are kept when the mode changes, so switching back does not
                 * lose what someone typed -- the note here says as much rather than leaving
                 * stored rows invisible.
                 */
                properties.patientSelection === 'SEQUENTIAL'
                    ? h(PatientsSection, { properties, onChange: notify })
                    : (readPatients(properties).length
                        ? h('div', { className: 'hint mt-2' },
                            readPatients(properties).length + ' defined patient(s) are stored on this'
                            + ' connector and kept, but random mode invents its population instead.'
                            + ' Switch to sequential to use them.')
                        : null));
        },

        /** Mirrors RandomGeneratorPanel.checkProperties in the Swing panel. */
        validate(properties) {
            const errors = [];
            if (!String(properties.template || '').trim()) {
                errors.push('Template is required.');
            }
            // A templated value cannot be checked here: the configuration map it refers to
            // lives on the server, and the connector validates it on deploy.
            const atLeast = (value, minimum, what) => {
                const text = String(value === undefined || value === null ? '' : value).trim();
                if (text.includes('${')) {
                    return;
                }
                if (!/^\d+$/.test(text) || Number(text) < minimum) {
                    errors.push(what + ' must be a number of at least ' + minimum + '.');
                }
            };
            atLeast(properties.messagesPerPoll, 1, 'Messages Per Poll');
            atLeast(properties.maxMessages, 0, 'Maximum Messages');
            atLeast(properties.patientCount, 1, 'Patients');
            // A date the connector cannot parse would otherwise fail the deploy rather than
            // the save, which is a much worse place to find a typo in a table.
            for (const patient of readPatients(properties)) {
                const dob = String(patient.dateOfBirth || '').trim();
                if (dob && !dob.includes('${') && !/^(\d{8}|\d{4}-\d{2}-\d{2})$/.test(dob)) {
                    errors.push('Patient "' + (patient.mrn || patient.family || '')
                        + '" has a date of birth of "' + dob + '", which is not yyyyMMdd or yyyy-MM-dd.');
                }
            }
            return errors;
        }
    };

    // The name must match <name> in source.xml: that string is how the console finds
    // the panel for a connector, and how the engine finds the class for the properties.
    platform.registerConnectorPanel('Random Generator', 'SOURCE', generator);
}
