# Volume Monitor

An engine extension that watches how many messages each channel **receives**
and reports a fault when a channel falls below an expected volume.

The failure it exists to catch is the quiet one. A feed that has silently
stopped looks exactly like a healthy one on a dashboard of green channels,
because nothing is erroring — there is simply nothing arriving. The engine's own
alerting triggers on errors, so that case has no cover.

## Design

| Decision | Choice | Why |
| --- | --- | --- |
| Threshold direction | Floor only, never a ceiling | A quiet feed is the failure people care about. A burst above normal is usually a backlog clearing, which is not a fault. |
| Window | Rolling, ending now | A feed that dies at 09:00 is reported within one evaluation cycle. Calendar buckets ("yesterday") would not flag it until the period closed, which for a daily rule means up to 24 hours of silence. |
| What is counted | Arrivals on the source connector, any outcome | A channel receiving its usual traffic and erroring on all of it is a different fault with different handling, and the engine already alerts on it. Conflating the two would make a volume alert unreadable without further digging. Restricted to metadata id 0, or a channel with three destinations would count each message four times. |
| Where breaches surface | Plugin UI, engine event log, server log | Three audiences: whoever has the console open, whoever reads Events or has an engine alert watching server events, and whatever scrapes the container log. |
| Notification repeats | On transition, then every `renotifyMinutes` | A feed that dies at 02:00 would otherwise write an identical event every cycle until someone noticed — hundreds of rows, which is how people learn to filter out a monitor rather than read it. |
| Stopped channel | Reported as a fault, in its own state | A channel someone bothered to write a volume rule for is meant to be running. "The channel is stopped" is a more actionable sentence than "0 of 500 messages". |
| New channel below threshold | `WARMING_UP`, not a breach | A channel deployed an hour ago cannot yet have met a daily target. Reporting that as a fault on its first day is how a monitor loses its audience. Established by one extra count over everything older than the window. |
| Evaluation state | In memory | Entirely derived — every count is recomputed each cycle — so persisting it would add a write path and a migration for no information. A restart re-notifies an ongoing breach once, which is the right way round. |
| Rule name | Optional, free text, sanitised | One channel can carry several rules, and then the channel name alone makes the rows indistinguishable in the list and in the log. It is the only free-text field, so tabs and newlines are stripped on the way in: a single stray tab would shift every later field of the stored row by one and quietly corrupt the rule. Capped at 120 characters, past which it stops being a label. |
| Rule storage | One row per rule in `configuration` | Deleting a rule is `removeProperty`, not a read-modify-write of one blob, so two administrators editing different rules cannot drop each other's work. This image also resets `conf/` and `extensions/` on every boot, so a file inside the extension would not survive a restart. |
| Wire format | Tab-separated strings, plain `ArrayList` | Both are scars. XStream renders an immutable list as an undecodable `CollSer` blob, and a list of maps in a shape the console's decoder cannot reliably unpick — the first of those crashed a view in the git sync plugin. A flat list of strings survives the round trip. |

## Active hours

A rule can be limited to certain days and a time range, in **server local
time**. Without that, an hourly rule on a daytime feed alerts every night and
people learn to ignore the plugin.

The schedule governs when the rule is *evaluated*, not which messages are
counted. One wrinkle follows from that, and it is deliberate:

- **A window shorter than a day** must have its *start* inside the active period
  too. Otherwise "100 per hour, 08:00–18:00" is checked at 08:05 against
  07:05–08:05 — an hour that is mostly before opening — and reports a breach
  every single morning. Requiring the whole window pushes the first check out to
  09:00, the first hour the rule can actually be true about.
- **A window of a day or more** only has the moment of checking tested. A
  24-hour window checked on Monday necessarily reaches into Sunday, so demanding
  an active window start would mean a Mon–Fri daily rule never ran on a Monday.
  Such a window already spans the quiet hours and the threshold is set knowing
  that.

## States

| State | Fault? | Meaning |
| --- | --- | --- |
| `OK` | no | Threshold met. |
| `BREACH` | **yes** | Below threshold, with enough history for that to mean something. |
| `CHANNEL_STOPPED` | **yes** | The channel is not started, so nothing can arrive. |
| `ERROR` | **yes** | The count itself failed. Never silently an `OK`. |
| `WARMING_UP` | no | Below threshold, but the channel has no messages older than the window. |
| `UNKNOWN_CHANNEL` | no | The rule names a channel this server does not have — a configuration problem, not a traffic one. |
| `OUTSIDE_SCHEDULE` | no | Outside the rule's active hours. |
| `DISABLED` | no | Switched off, but kept. |

## API

Everything lives under `/api/volumemonitor`.

```
GET  /status?evaluate=false     rules with their current state
GET  /summary                   fault count only, never counts anything
GET  /rules                     the rules themselves
POST /rules                     create or update (XStream-shaped map body)
POST /rules/_delete?id=         delete
GET  /channels                  channels and deployed state, for the picker
POST /settings                  interval, and whether to write engine events
```

`/status?evaluate=false` reports the last scheduled pass and costs nothing;
`evaluate=true` counts now. The page loads with `false` and only counts when
someone presses **Check now**, because each evaluation is a date-ranged count
per rule against the message tables — the most expensive thing this plugin does.

`/summary` exists for the navigation badge, which is drawn on every page load
and so must never trigger a count.

## Building

```bash
PLUGIN_VERSION=0.2.0 ./plugins/oie-volume-monitor/build.sh
cp plugins/oie-volume-monitor/dist/volumemonitor-0.2.0.zip extensions/
docker compose up -d --force-recreate engine
```

Compiles in a container against engine jars taken from the image this repo
builds, so it is always built against exactly the engine it will run on. It
bundles **no** third-party jars: it needs only the engine's own controllers, so
unlike the git sync extension there is nothing extra loaded into the engine's
JVM and no checksums to verify.

`plugin.xml` declares `<mirthVersion>4.6.0</mirthVersion>`, which
`ExtensionLoader` compares by **exact string match**. An extension declaring
anything else is refused outright and silently not loaded.

## Extension logging

The engine's shipped `log4j2.properties` sets `rootLogger = ERROR`, and a logger
only emits if its own level allows it — so an extension logging at `WARN` went
nowhere and the plugin looked silent. The container entrypoint now appends

```properties
logger.oiePlugins.name = org.openintegrationengine.plugins
logger.oiePlugins.level = INFO
```

to `conf/log4j2.properties` on every boot (`conf/` is reset from `conf.dist`
each time, so this is always a fresh file). Set `OIE_PLUGIN_LOG_LEVEL` to change
the level, or `OFF` to skip it. This also fixed the git sync extension's log
lines, which had never appeared either.

## Verified

Against a live engine:

- Extension loads: `"name":"Volume Monitor"` in `/api/extensions/plugins`
- Rule created through the API and read back:
  `de8e3037…  8f2c1a44…  http-ingest-echo  true  5  1  HOUR  *  0  0  60`
- **Breach detected**: `BREACH`, `0 / 5`,
  `0 in the last hour, expected at least 5 (5 short).`, `faults: 1`
- **Server log**: `WARN … volume monitor below threshold: channel
  'http-ingest-echo' (8f2c1a44…) expected 5 per hour -- 0 in the last hour,
  expected at least 5 (5 short).`
- **Engine event log**: a `WARNING` / `FAILURE` event named *Message volume below
  threshold*, carrying `channel`, `channelId`, `rule`, `observed`, `expected`,
  `window` and `detail`
- **Recovery**: six messages posted to the channel, re-evaluated to `OK`, `6 / 5`,
  `faults: 0`, and `volume monitor recovered: … meets 5 per hour again -- 6 in
  the last hour.`
- **Repeat suppression**: threshold raised to 100 to force a breach, then three
  further evaluations — one notification, then silence
- **Unknown channel**: a rule naming a nonexistent id reports
  `UNKNOWN_CHANNEL` and is *not* counted as a fault
- **Validation**: an empty channel and a threshold of 0 return
  `ok:false` with both problems named
  (`a channel must be selected`, `the expected count must be at least 1`)
- **Console UI**: nav item `Monitoring`, panel `Rules (1)`, row rendering
  `http-ingest-echo / at least 5 per hour / 6 / 5 / OK / 6 in the last hour.`
- Row parsers and time-of-day conversion: 4 suites, all passing
  (`test/rows.test.mjs`), including a round trip across all 1440 minutes of the
  day
- **Rule names**: a name containing a literal tab came back as a single field
  with the tab replaced by a space, leaving the row intact
- **Format compatibility**: a rule written as `v1`, before names existed, still
  parses after the bump to `v2` — it simply reads back with no name. The name is
  appended to both stored rows and API rows rather than inserted, so every
  existing field index is where the browser already expects it
- **Form layout**: `Active days` occupies its own full-width row rather than
  sitting beside the channel picker, where the open dropdown rendered over it

## Remaining

1. **`CHANNEL_STOPPED` has not been exercised against a real stopped channel.**
   The branch is a single enum comparison and the state is reported distinctly,
   but stopping a channel on someone's running engine to prove it was not worth
   the disruption. Worth confirming once in a scratch environment.
2. **No ceiling rule.** Only a floor is supported. A "no more than X" rule would
   be a small addition to the same evaluator if a runaway loop or a duplicate
   feed ever needs catching.
3. **Nothing distinguishes arrivals from successful processing.** A channel
   receiving its usual traffic and erroring on all of it reads as healthy here,
   deliberately — but a per-rule status filter (`count only SENT`) would make
   that expressible for anyone who wants it.
4. **The nav badge is fixed at page load.** The nav registry is read once when
   the shell mounts and mutating a registered item's label does not re-render
   (verified against a running console), so the count is as of page load. The
   page itself is current. A live badge would need a status-bar extension point
   the console does not have.
5. **Cost on a large message store.** Each pass is one date-ranged count per
   rule, and a weekly window on a busy channel is not a cheap query. The
   interval is floored at 60s for that reason. If it ever matters, the channel
   statistics tables would give a cheaper approximation at the cost of exactness.
