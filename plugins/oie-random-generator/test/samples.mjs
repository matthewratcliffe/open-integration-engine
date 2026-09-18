/*
 * Readers for the three things that have to agree with each other: the sample
 * templates in samples/, the message types in MessageType.java, and the
 * placeholders TemplateRenderer.java knows how to resolve.
 *
 * Nothing here is shipped. The renderer is Java and is tested by running it; these
 * are the checks that catch the mistakes running it would not -- a sample that
 * spells a placeholder wrong (the renderer passes it through untouched, which is
 * the correct behaviour and looks like nothing at all), a message type added
 * without its sample file, or a field that quietly moved when the pipes were
 * counted by hand.
 */
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const PLUGIN = join(HERE, '..');
const SRC = join(PLUGIN, 'src/org/openintegrationengine/connectors/generator');

/** { ADT_A01: "MSH|...\nEVN|...", ... } */
export function loadSamples() {
    const dir = join(PLUGIN, 'samples');
    const samples = {};
    for (const file of readdirSync(dir).filter((f) => f.endsWith('.hl7'))) {
        samples[file.replace(/\.hl7$/, '')] = readFileSync(join(dir, file), 'utf8')
            .replace(/\r\n/g, '\n').replace(/\r/g, '\n').replace(/\n+$/, '');
    }
    return samples;
}

/** The enum constants, with the three parts of MSH-9 each one carries. */
export function loadMessageTypes() {
    const java = readFileSync(join(SRC, 'MessageType.java'), 'utf8');
    const types = {};
    const pattern = /^\s{4}([A-Z][A-Z0-9_]+)\("([^"]*)",\s*"([^"]*)",\s*"([^"]*)",/gm;
    let match;
    while ((match = pattern.exec(java)) !== null) {
        types[match[1]] = { code: match[2], event: match[3], structure: match[4] };
    }
    return types;
}

/** Every placeholder name the renderer answers to, taken from its switch. */
export function loadKnownPlaceholders() {
    const java = readFileSync(join(SRC, 'server/TemplateRenderer.java'), 'utf8');
    const names = new Set();
    const pattern = /^\s*case "([a-zA-Z0-9_.]+)":/gm;
    let match;
    while ((match = pattern.exec(java)) !== null) {
        names.add(match[1]);
    }
    return names;
}

/**
 * The placeholders a template uses, as names without their arguments. The scan
 * mirrors TemplateRenderer.render: from `${` to the next `}`, no nesting.
 */
export function placeholdersIn(template) {
    const found = [];
    let index = 0;
    for (;;) {
        const start = template.indexOf('${', index);
        if (start < 0) {
            return found;
        }
        const end = template.indexOf('}', start + 2);
        if (end < 0) {
            found.push({ token: template.slice(start), name: null, terminated: false });
            return found;
        }
        const token = template.slice(start + 2, end);
        const colon = token.indexOf(':');
        found.push({
            token,
            name: colon < 0 ? token : token.slice(0, colon),
            args: colon < 0 ? null : token.slice(colon + 1),
            terminated: true
        });
        index = end + 1;
    }
}

/** The segments of a sample, in order. */
export function segments(sample) {
    return sample.split('\n').filter((line) => line.trim() !== '');
}

/** The named segment's fields, 1-based: field(sample, 'PV1', 19). */
export function field(sample, name, number) {
    const segment = segments(sample).find((line) => line.startsWith(name + '|'));
    if (segment === undefined) {
        return undefined;
    }
    if (name === 'MSH') {
        // MSH-1 is the field separator and MSH-2 the encoding characters, so the text
        // after "MSH|^~\&" begins at MSH-3 and the split needs the offset back.
        const parts = segment.split('|');
        return parts[number - 1];
    }
    return segment.split('|')[number];
}
