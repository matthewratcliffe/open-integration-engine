# Random Generator

A source connector that manufactures HL7 v2 messages on the polling schedule,
for when the channel is ready and the upstream system is not.

```
./plugins/oie-random-generator/build.sh
cp plugins/oie-random-generator/dist/generator-0.1.0.zip extensions/
docker compose up -d --force-recreate engine
./scripts/oie-check-extensions.sh "Random Generator" "Random Generator Connector"
```

Panels are provided for both administrators: the web console at `/oie-webadmin/`
and the Swing Administrator.

## Why this exists

Proving a channel, a mapping or a downstream interface needs traffic, and the
three usual ways of getting it are all worse than they look. A test extract from
a hospital is real patient data in a test environment. A handful of messages
pasted into the Administrator's message sender exercises one path, once, by
hand. A JavaScript Reader that builds a message with `Math.random()` starts as
six lines and ends up a second, undocumented implementation of everything below.

So: an editable HL7 template per message type, a cadence, and — the part that
makes the output worth testing against — a **fixed population**. Placeholders do
not resolve to fresh random values each time. They resolve against one of a set
of invented patients, built once from a seed, so patient 7 carries the same MRN,
the same date of birth, the same ward and the same attending doctor in every
message they ever appear in. A feed where the patient changed between the admit
and the discharge would not exercise a merge, an update, or a visit that starts
and later ends.

## The cadence

The schedule is the standard **Polling Settings** — interval, time of day, or
cron — plus two fields of this connector's own:

| Setting | |
| --- | --- |
| Messages Per Poll | how many to generate each time the schedule fires. This and the interval together are the rate: 1 every 10 seconds is a trickle to watch in the dashboard, 500 every second is a load test. |
| Maximum Messages | stop after this many in total, or 0 for no limit. The count restarts when the channel starts, so a redeploy replays the run — which is what makes "send exactly 10,000 and stop" repeatable. |

Nothing runs on a thread of its own: the connector is a poll connector, so the
cadence is configured the way every other scheduled thing in the engine is.

## The template

The **Message Type** selects MSH-9 and the built-in sample. The template box is
never shown empty — a channel with a blank template is shown the sample for its
type, and what you save is what the channel sends. Changing the message type
swaps the sample in only while the box still holds an unedited one, so your own
HL7 is never overwritten by a change to a dropdown.

| Type | | Segments in the sample |
| --- | --- | --- |
| `ADT_A01` | Admit / visit notification | MSH EVN PID NK1 PV1 DG1 IN1 |
| `ADT_A03` | Discharge / end visit | MSH EVN PID PV1 DG1 |
| `ADT_A08` | Update patient information | MSH EVN PID PV1 |
| `ORM_O01` | Order message | MSH PID PV1 ORC OBR NTE |
| `ORU_R01` | Observation result | MSH PID PV1 ORC OBR OBX×3 |
| `SIU_S12` | New appointment booking | MSH SCH PID PV1 RGS AIS AIL AIP |
| `DFT_P03` | Post detail financial transaction | MSH EVN PID PV1 FT1×2 |
| `MFN_M02` | Master file: staff / practitioner | MSH MFI MFE STF PRA |

The samples live in `samples/*.hl7` — one file per type, which is what both
administrators show you and what the engine falls back to. `ADT_A08` deliberately
declares the `ADT_A01` *structure* in MSH-9.3, because that is what an A08 is
carried by and a generator that wrote `ADT_A08` there would be producing
something no conformant receiver accepts.

**Line endings are handled.** HL7 terminates segments with a carriage return and
nothing else will do, but templates are written in editors. Whatever you paste —
CRLF, LF, CR, blank lines between segments — comes out as one `\r` per non-empty
segment.

**The template is not passed through the engine's template value replacer.** It
uses the same `${...}` syntax, and the replacer would consume every placeholder
before this connector saw one. The MSH fields *around* the template are replaced
normally, so a facility code can still come from the configuration map.

## Placeholders

Four families, and the difference between them is the whole point.

### `${patient.*}` and `${visit.*}` — fixed for the life of the population

Invented from the seed, or taken from the patients table where a row fills a column
in (see [Naming the patients](#naming-the-patients)).

| | |
| --- | --- |
| `patient.id` `patient.index` | `PAT00007`, and `7` — which patient in the pool this is |
| `patient.mrn` `patient.medicare` `patient.accountNumber` | identifiers |
| `patient.family` `patient.given` `patient.middle` `patient.prefix` | name parts |
| `patient.sex` `patient.dob` `patient.age` | `dob` takes a format: `${patient.dob:dd/MM/yyyy}` |
| `patient.street` `patient.suburb` `patient.state` `patient.postcode` | address |
| `patient.phone` `patient.mobile` | landline with the right area code for the state, and an `04` mobile |
| `patient.maritalStatus` | HL7 table 0002 |
| `patient.insurer` `patient.insurerName` `patient.policyNumber` | payer code and its matching name — drawn as a pair, never a code from one fund and a name from another |
| `patient.nok.family` `patient.nok.given` `patient.nok.relationship` `patient.nok.phone` | next of kin, who shares the surname unless they are a friend |
| `visit.number` `visit.class` `visit.financialClass` | PV1-19, PV1-2, PV1-20 |
| `visit.location` | `WARD^ROOM^BED^FACILITY` for PV1-3 |
| `visit.pointOfCare` `visit.room` `visit.bed` `visit.facility` | the same parts separately |
| `visit.admitType` `visit.hospitalService` | HL7 tables 0007 and 0069 |
| `visit.admitTime` | takes a format; defaults to `yyyyMMddHHmmss` |
| `visit.attending.xcn` `visit.referring.xcn` | the whole XCN: `DR12345^SMITH^JOHN^^^DR` |
| `visit.attending.id` `.family` `.given` | and the same parts separately, for both doctors |

### `${message.*}` — the envelope, per message

`message.controlId` (MSH-10, timestamp plus a counter so it is unique at any
cadence), `message.datetime`, `message.date`, `message.sequence`,
`message.type`, `message.event`, `message.structure`, `message.version`,
`message.processingId`, `message.sendingApplication`, `message.sendingFacility`,
`message.receivingApplication`, `message.receivingFacility`. The date ones take a
format.

### `${random.*}` — redrawn at every occurrence

| | |
| --- | --- |
| `${random.int:95-175}` | inclusive at both ends |
| `${random.decimal:3.0-15.0,1}` | range, then the number of decimal places |
| `${random.digits:8}` | a fixed-length run of digits, leading zeros included |
| `${random.alpha:5}` | uppercase letters |
| `${random.pick:CH,HM,MB,RAD}` | one of a comma-separated list |
| `${random.uuid}` `${random.bool}` | a UUID; `Y` or `N` |

### `${date.*}` — the clock

`${date.now}`, `${date.now:yyyyMMdd}`, and `${date.offset:-2h}` /
`${date.offset:+7d:yyyyMMdd}` with units `s`, `m`, `h`, `d`, `w`.

### Anything else

An unrecognised placeholder is **left exactly as written** and reported, rather
than replaced with nothing. A typo shows up as literal `${patinet.mrn}` in the
message and is named in the preview and in the deploy-time log line, instead of
appearing as a field that silently went missing. There is no nesting and no
escaping: the text between `${` and the next `}` is the placeholder.

## The population

| Setting | |
| --- | --- |
| Patients | how many exist. Every message is about one of them, so this is how many distinct MRNs, names and visits the downstream system will ever see — the dial between one patient's whole journey and a busy hospital. |
| Patient Selection | **Random** repeats patients the way a real feed does. **Sequential** walks the population in order, so every patient appears equally often. |
| Seed | the same seed always produces the same patients, on this engine and on anyone else's. Blank derives it from the channel id: stable across restarts, different per channel. Any text works; a number is used as it stands. |

### Naming the patients

Sometimes the population has to be a *particular* set of people: the three patients
the downstream system is already loaded with, the MRN in the defect report, the
visit number someone is watching in a database. Select **Sequential** and a table
appears — one row per patient, walked in the order you typed them.

| Column | |
| --- | --- |
| MRN | `${patient.mrn}`, PID-3 |
| Family Name / Given Name | PID-5 |
| Sex | PID-8: `M`, `F`, `O`, `U` |
| Date of Birth | `yyyyMMdd` or `yyyy-MM-dd` |
| Visit Number | `${visit.number}`, PV1-19 |
| Class | `${visit.class}`, PV1-2: `I`, `O`, `E` |
| Location | PV1-3 as `WARD^ROOM^BED^FACILITY` |
| Attending Doctor | PV1-7: a whole XCN, or just an id |

**Every cell is optional, and that is what makes nine columns enough.** A blank one
is filled from the invented patient at that position — so a row naming only an MRN
still produces a complete message, with an address, a next of kin, an insurer and a
referring doctor that stay the same every time that row comes round. `ICU` on its
own in Location sets the ward and keeps the drawn room, bed and facility; `DR77777`
on its own in Attending Doctor sets the id and keeps the drawn name.

Filling a cell in **does not disturb the rest of that person**. Every value is drawn
first and overridden afterwards, never drawn conditionally, because a generator
where typing an MRN into one row silently changed that patient's address would be
worse than no table at all.

A cell may hold a configuration map reference — `${testMrn}` — which is expanded
when the channel deploys, the same way the MSH fields are.

**The table is sequential mode's.** A list is a thing to walk in order, and picking
from it at random would be a different feature wearing the same table, so random
mode invents its population and ignores the rows. It does not throw them away:
switch back and they are still there, and both panels say so where the table would
be. While rows are defined, the Patients count is ignored — the population is
exactly as long as the table.

A date of birth the connector cannot parse is refused by both panels on save, and
fails the deploy naming the row if it reaches the engine anyway:
`Patient MRN 4242424 has a date of birth of "31/12/1970", which is not yyyyMMdd or
yyyy-MM-dd.`

Two channels sharing a seed are talking about the same people — an admit feed and
a results feed that agree on who exists — and a bug report naming patient 7 means
something to whoever reads it. The population is built when the channel *starts*,
so a stop and start is a clean run: the same patients, with the sequence and the
message limit reset.

Changing the number of patients does not change who anybody is. Each patient is
drawn from the seed and their own index rather than in sequence from one
generator, so patient 7 is the same person in a pool of ten and a pool of ten
thousand.

The data is Australian-shaped — suburbs with postcodes that belong to their
state, a Medicare number, an `04` mobile — because that is where this stack is
used. None of it is real: the name lists are common surnames and given names, and
every identifier comes out of a random number generator. The lists are at the top
of `SyntheticPatient.java` if another country's shape is wanted.

## Preview

Both panels have a **Preview Message** button. It generates one message on the
server, through `MessageGenerator` — the same class a deployed channel uses, with
the same seed — and shows it, along with which patient it is about and any
placeholder that was not recognised. So what you are shown before saving is
produced by the code that will run, not by a second implementation that agrees
with it today.

The preview reaches `POST /api/connectors/generator/_preview`, which is also
usable directly if you would rather script it.

## What the channel receives

The message arrives as the raw HL7 string, with a source map:

| Key | |
| --- | --- |
| `originalFilename` | `ADT_A01_20260918120000000001.hl7` — named the way the File Reader names it, so a channel switched onto this connector for testing does not have to change its filter or transformer |
| `generatorMessageType` | `ADT_A01` |
| `generatorControlId` | MSH-10, as generated |
| `generatorSequence` | how many messages this channel has generated since it started |
| `generatorPatientId` `generatorPatientIndex` | `PAT00007` and `7` |
| `generatorMrn` `generatorVisitNumber` | the identifiers, without having to parse the message to find them |
| `pollId` `pollSequenceId` | which poll produced it, and where in that poll |
| `pollComplete` | on the last message of a poll |

**Batch processing works.** With Process Batch on, a template holding several
messages is split by the inbound data type exactly as a file of many messages
would be — so one template can produce a batch per poll.

**For files on disk**, pair it with a File Writer destination. The connector
deliberately does not write files itself: that connector already exists, does it
better, and would be a second place to configure a directory.

## Deploy-time checks

The channel refuses to deploy, with the reason, when the template cannot be
rendered — a malformed `${random.int:lots}` names itself — or when Messages Per
Poll is below 1. One message is rendered at deploy rather than at the first poll,
so a broken template is a failed deploy instead of an error logged every ten
seconds by a channel that looks started.

Unrecognised placeholders are a warning in `mirth.log`, not a failure: the text
is passed through, which is recoverable and visible in the message.

## Two things about the build

**Nothing is downloaded, and nothing is bundled.** This connector needs no
third-party library — it makes text from a template using the JDK and the
engine's own classes — so the extension is three jars, eight sample files and the
console panel. `build.sh` compiles inside a container against jars taken straight
out of the engine image, so it is always built against exactly the engine it will
run on.

**The samples have one home.** `samples/*.hl7` are copied into the shared jar as
resources, which is what the engine and the Swing panel read. The web console's
plugin cannot read them: a console plugin is fetched as text and imported from a
`blob:` URL, so it can neither import a sibling module nor fetch a file beside
itself. Rather than keep a second hand-maintained copy in JavaScript, `build.sh`
inlines the same files into the staged `plugin.js` at a marker. If that ever
fails the panel degrades rather than breaks — the template box starts empty, and
a blank template makes the engine fall back to the sample anyway.

**Half the console panel is React and half is DOM.** `ConnectorForm` is a React
component, but a field's `append` callback must return a real DOM node -- the form
mounts it with `appendChild` -- and the console's button helpers (`taskButton`,
`connectorTestButton`) build elements, not React elements. Render one of those as a
React child and the channel editor replaces the whole panel with *This panel failed
to render*: React error #31, "Objects are not valid as a React child", naming an
`HTMLButtonElement`. Both buttons here -- Load Sample and Preview Message -- are
therefore built with `taskButton` and returned from `append`.

**`plugin.xml` is not decoration.** XStream refuses to instantiate any class
outside its allow list, and the engine's list covers `com.mirth.connect` only.
Without the tiny service plugin that registers this connector's package, the
engine happily *writes* a channel using it and then cannot read it back:
`PUT /channels/{id}` returns `200`, the channel is stored as Mirth's
`InvalidChannel`, it never appears on the dashboard, and nothing says why. The
same registration is done client-side for the Swing Administrator, which
deserialises channel XML in its own JVM.

## Example channel

`examples/random-generator-example.xml` generates one ADT^A01 every ten seconds
about a fixed population of 25, and logs what it produced. Push it with the usual
script after changing the id:

```
cp plugins/oie-random-generator/examples/random-generator-example.xml config/channels/
./scripts/oie-config-push.sh
```

Its template is empty on purpose, which is the built-in sample: open the channel
in either administrator and the box is filled in with it.

## Tests

`test/samples.test.mjs` (`node --test test/samples.test.mjs`) checks the samples
against the code that has to agree with them — that every `MessageType` constant
has a sample file and vice versa, that every placeholder used in a sample is one
`TemplateRenderer` actually resolves (it reads the `case` labels out of the Java,
so a renamed placeholder fails here rather than shipping as literal text), that
placeholder arguments are shapes the parser accepts, and that MSH, PID and PV1
put their fields where HL7 says they go.

That last one is worth having because the alternative is counting pipes: PV1-44
is twenty-four empty fields after PV1-20, and nothing about a message that is one
field out looks wrong until a receiver reads the admit time as the financial
class.

## Verified

**On a running engine** (OIE 4.6.0 in this stack, extension installed from
`extensions/`, `examples/random-generator-example.xml` pushed over the API):

- `oie-check-extensions.sh` reports **Random Generator** and **Random Generator
  Connector** both loaded
- The channel is stored and **read back as a channel**, not as an `InvalidChannel`
  — which is the failure a connector without its serializer plugin produces, with
  a `200` and no other symptom
- It deploys, starts, and generates: one ADT^A01 every ten seconds, gaps measured
  at 10.0s, with `received` climbing and `error` at 0. The message is stored with
  data type HL7V2 and `\r` segment terminators, and the destination reads
  `generatorPatientId`, `generatorMrn` and `generatorVisitNumber` out of the
  source map
- A burst configuration — 20 messages per poll, 5 patients, sequential — produced
  **20 messages in 0.31s**, walking the five patients in order, four appearances
  each, and **every patient's PID and PV1 byte-identical across all four of their
  messages**
- Patient 3 in that pool of 5 is the same person (MRN `3413070`, same visit, same
  attending doctor) as patient 3 in the pool of 25 the preview drew from: the pool
  size moves nobody
- Maximum Messages stopped the run at exactly 20, logged once —
  `has generated its limit of 20 message(s)` — and the channel stayed started
- `POST /api/connectors/generator/_preview` returns the generated message, the
  patient it is about and the seed, using the same population the deployed channel
  is sending from (the seed is derived from the channel id)
- **Named patients**: a channel with three rows — one filled in completely, one
  giving only an MRN, a bare ward (`DAYSURG`) and a bare doctor id (`DR22222`), and
  one left blank — round-trips through XStream and generates 1, 2, 3, 1, 2, 3. Row
  1 comes out exactly as typed (`COLE^ADA`, `19700101`, `V90000001`, PV1-3
  `ICU^7^B^RPA`, PV1-7 `DR11111^HOUSE^GREG`); row 2 keeps the drawn room, bed and
  facility under its own ward and the drawn name under its own doctor id; row 3 is
  an invented patient with its own visit

**Against the connector's classes, run outside the engine** (8 types × 6
assertions, plus the population and renderer behaviour):

- Every sample renders with **no unresolved placeholders** and no `${` left in
  the output, starts with `MSH|^~\&|`, carries the right `MSH-9` for its type —
  including `ADT^A08^ADT_A01` — and ends with a segment terminator
- **Patient identity is stable**: two messages about patient 7 have byte-identical
  PID and PV1 segments, and different control ids
- **Pool size does not move anybody**: patient 7 in a pool of 500 is the same
  person as patient 7 in a pool of 25; a different seed is a different person
- **Sequential selection** visits every patient once per lap
- **A named row sits on top of an invented patient**: setting only the MRN leaves
  that patient's name, address, insurer, next of kin and ward byte-identical to the
  same row left blank, and a row that sets only the sex moves the given name,
  middle name and title onto the matching list without redrawing the person
- Three rows make a pool of three, whatever the Patients count says; random mode
  with the same settings keeps its population of 25
- An unparseable date of birth throws before a message is generated, naming the row
  and the two formats it accepts
- A misspelled placeholder is reported *and* passed through as written
- CRLF, LF, CR and blank lines all normalise to one `\r` per segment
- A malformed argument throws, naming the placeholder:
  `${random.int:lots} is not a range, such as ${random.int:1-100}`
- `test/samples.test.mjs`: 7 suites, all passing
- `build.sh` produces `generator-0.1.0.zip` with the three jars, both metadata
  files and the console plugin; every inlined sample matches its `.hl7` file byte
  for byte, and both copies of `plugin.js` parse as ES modules

## Remaining

1. **The panels are not confirmed on screen.** Everything they call has been
   exercised over the API — the properties round-trip through XStream, the preview
   endpoint, the deploy-time validation. The web console panel was opened once and
   failed to render, because both of its buttons were built as React children out
   of helpers that return DOM elements (React error #31); that is fixed and
   documented above, but the corrected panel and the Swing panel have not been
   looked at yet.
2. **The visit is fixed per patient, including the admit time**, which is drawn
   when the population is built. A patient admitted "two days ago" is two days
   before the channel started, not before each message. Generating a patient's
   whole journey — admit, then update, then discharge, with times that move — is
   several channels sharing a seed today, and would be a state machine here.
