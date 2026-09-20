# Tests

A CI-runnable suite (GitHub Actions: [`.github/workflows/tests.yml`](../.github/workflows/tests.yml))
covering the parts of this repository we own — the scripts, the entrypoint, the
channel config and the whole stack end to end. The vendor engine (OIE 4.6.0)
has no source here; it is exercised through its behaviour in the integration
tier.

**The 12 first-party plugins formerly built from `plugins/` in this repo now
live in their own GitHub repos** (`gibson9583/oie-<name>`), each with its own
test suite. They are fetched and baked into the engine image at build time via
`OIE_BUILTIN_PLUGIN_URLS` (see `docker/Dockerfile`) rather than built here, so
`js-unit` and `java-unit` below now only cover whatever is still tracked under
`plugins/` or `extensions/` in this repo (currently the TLS Manager overlay).

Six tiers, fast to slow. The first five need no Docker and finish in well under a
minute; `integration` builds the engine image and runs last.

| Tier | What runs | Covers |
| --- | --- | --- |
| **js-unit** | `node --test` in every `plugins/*/test` and `extensions/*/test` dir still tracked here | Pure logic in a plugin/overlay's `webadmin/web/plugin.js` — XStream decode/encode, TSV row parsing, byte/duration/relative-time formatting, validation, provider tables |
| **java-unit** | `javac` + run each `plugins/*/test/java/*Test.java` still tracked here | Dependency-free Java logic (skips cleanly if none is tracked here) |
| **shell-unit** | `bats test/shell/*.bats` | Pure shell logic in `docker/entrypoint.sh` (`set_prop`, `_MP_*` mangling, `server.id` derivation, extension-URL checksum parsing) and `scripts/oie-config-push.sh` (`${VAR}` expansion, XML escaping) |
| **shellcheck** | `shellcheck -x` over `scripts/`, `docker/`, any `plugins/*/build.sh` still tracked here | Static analysis of every script |
| **lint-static** | `xmllint`, `yamllint`, `docker compose config` | Channel XML well-formedness, missing/duplicate channel ids, YAML validity, compose parses |
| **integration** | Build engine image (fetches and bakes in the 12 first-party plugins via `OIE_BUILTIN_PLUGIN_URLS`), stand up Postgres + engine, push fixture + real config | The whole stack: a channel the engine cannot deserialise is stored as an `InvalidChannel` while the API still reports success — this tier reads each channel back and asserts it did not become a stub and reached a deployed state |

## Running locally

Everything except `integration` needs only Node 20, a JDK 17+, `bats` and
`shellcheck`.

```bash
# JS unit — anything still tracked under plugins/ or extensions/
for d in plugins/*/test extensions/*/test; do [ -d "$d" ] && (cd "$d" && node --test); done

# Java unit — compile a tracked plugin's src with its test, then run it (none
# tracked here by default; the 12 first-party plugins test this in their own
# repos now)
# javac -d /tmp/vt <plugin>/src/.../Foo.java <plugin>/test/java/FooTest.java
# java -cp /tmp/vt FooTest

# Shell unit
bats test/shell/*.bats

# Static
shellcheck -x scripts/*.sh docker/*.sh plugins/*/build.sh 2>/dev/null || shellcheck -x scripts/*.sh docker/*.sh
xmllint --noout config/channels/*.xml test/fixtures/channels/*.xml
```

The integration tier is the workflow's `integration` job; it is easiest to run
on CI, but the steps are ordinary `docker` commands and reproduce locally if you
have Docker.

## How the unit tests are arranged

Each plugin's browser half is a single ES module the console loads and calls
`register()` on. Its pure functions are often trapped in that closure or not
exported, and importing the module would pull in the whole page (React, the
console shell, DOM). So each test dir holds a **mirror** module — the pure
functions **copied verbatim** from `plugin.js`, exported — beside the test that
drives it. This is the arrangement the pre-existing `oie-backup`,
`oie-git-sync`, `oie-random-generator` and `oie-volume-monitor` tests already
used; the new ones follow it.

The one adaptation: functions that read `Date.now()` (`ago`, `uptime`, `since`,
`when`) take an injected `now` in the mirror so their thresholds are
deterministic under test. The default preserves the production behaviour.

**Keeping them in step:** because the logic is copied, a change to a
`plugin.js` pure function must be mirrored into its `test/logic.mjs`. The tests
are cheap insurance against exactly the class of bug that is otherwise invisible
— a `plugin.js` change that quietly breaks XStream decoding, or a Java change
that breaks version ordering — not a substitute for reading the diff.

## Integration fixtures

[`test/fixtures/channels/`](fixtures/channels/) holds one channel per connector
type this repo builds. Four are **real Administrator exports** committed to the
tree; three more are **generated from the engine in CI** (see below).

| Fixture | Connector exercised | Source |
| --- | --- | --- |
| `http-ingest-echo.xml` | HTTP Listener (core) | committed export |
| `random-generator-example.xml` | Random Generator source (ours) | committed export |
| `sftp-push-example.xml` | SFTP Listener source (ours) | committed export |
| `null-sender-fixture.xml` | Null Sender destination (ours) | committed export |
| `fhir-listener-fixture.xml` | FHIR Listener source (ours) | generated in CI |
| `fhir-sender-fixture.xml` | FHIR Sender destination (ours) | generated in CI |
| `sftp-sender-fixture.xml` | SFTP Sender destination (ours) | generated in CI |

**Why three are generated, not committed.** Hand-writing an XStream channel
export is the one thing this stack's own README warns against: XStream
serialises Java objects, element names must match field names exactly, and a
single mismatch makes the engine store the channel as an `InvalidChannel` while
every API call still returns success — a fixture built that way would *pass its
own push* and prove nothing. For the three connectors with no shipped example,
[`generate-fixtures.sh`](fixtures/generate-fixtures.sh) lets the engine produce
them instead: it POSTs a minimal seed per connector (only the class attribute
plus the fields the connector needs, from the plugin's own `defaults()`), reads
it back, **asserts it did not come back as an `InvalidChannel` stub**, and
writes the engine's canonical serialisation. Because the engine produces the
XML, it is valid by construction; if a connector's fields ever drift, the assert
fails the job with the connector named. The integration tier runs this against
its throwaway engine, then pushes and deploy-verifies the result like the rest.
Run it by hand against any engine with the plugins installed:

```bash
OIE_URL=https://localhost:8443/api OIE_PASSWORD=... OIE_INSECURE=true \
  ./test/fixtures/generate-fixtures.sh          # write them
  ./test/fixtures/generate-fixtures.sh --check   # assert without changing the tree
```

The `js-unit` tier also covers those connectors' panel logic (defaults,
validation) directly.

## What the suite found

Writing `java-unit` surfaced a real `equals`/`hashCode` contract violation in
`Version`: `4.6` and `4.6.0` compared equal but hashed differently, so a
`HashSet<Version>` would treat them as distinct. Fixed in the same change —
`hashCode` now ignores trailing-zero core segments, matching `compareTo`.
