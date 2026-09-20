# Open Integration Engine — Docker stack

[Open Integration Engine](https://github.com/OpenIntegrationEngine/engine) 4.6.0
on PostgreSQL, with channel configuration held in git and pushed through the
REST API from GitLab CI.

```
docker compose up -d --build
```

That builds the engine image, brings up PostgreSQL, waits for the engine to
finish creating its schema, and rotates the admin account off the password the
release ships with. Then:

```
cp .env.example .env          # set POSTGRES_PASSWORD and OIE_ADMIN_PASSWORD first
./scripts/oie-config-push.sh  # push config/ to the engine and deploy it
```

Requires Docker Compose v2.24 or newer (the proxy overlay uses the `!override`
tag), and `bash`, `curl` and `xmllint` wherever you run the scripts.

## What is here

| Path | |
| --- | --- |
| `docker/Dockerfile` | builds 4.6.0 from the official release tarball, checksum pinned |
| `docker/entrypoint.sh` | renders `conf/` from the environment, then execs the engine |
| `compose.yaml` | PostgreSQL + engine + one-shot admin bootstrap |
| `compose.dev.yaml` | overlay: publish Postgres and channel ports locally |
| `compose.proxy.yaml` | overlay: nginx front door doing mutual TLS |
| `compose.sso-killswitch.yaml` | overlay: turn single sign-on off without the console |
| `compose.cluster.yaml` | overlay: three engines behind one address, sharing deployments |
| `deploy/k8s/` | the same, as StatefulSets |
| `config/` | channels, code templates, configuration map — the source of truth |
| `scripts/oie-config-push.sh` | idempotent upsert + deploy, for CI |
| `scripts/oie-config-pull.sh` | export a running server back into `config/` |
| `scripts/oie-bootstrap-admin.sh` | rotate the admin password, idempotently |
| `scripts/oie-check-extensions.sh` | assert required extensions actually loaded |
| `scripts/oie-api.sh` | thin curl wrapper, sourced by the others |
| `scripts/gen-dev-certs.sh` | throwaway certs for testing the mTLS overlay |
| `scripts/oie-cluster-keystore.sh` | extract the keystore every node in a cluster shares |
| `docker/Dockerfile` (`OIE_BUILTIN_PLUGIN_URLS`) | this stack's 12 first-party plugins, each its own GitHub repo, fetched and baked into the image at build time |
| `extensions/` | drop licensed extension zips here (e.g. Zen) |
| `docs/web-ui.md` | enabling the browser-based web administrator |
| `docs/mtls.md` | setting up mutual TLS, three ways |
| `docs/sso.md` | single sign-on with Microsoft Entra ID, configured in the console |
| `docs/multi-pod.md` | several engines behind one address, sharing config and deployments |
| `scripts/oie-tls-import.sh` | import certificates into TLS Manager over the API |
| `.github/workflows/docker-build.yml` | build image, push to GHCR |
| `.github/workflows/config-deploy.yml` | lint, validate against a disposable engine, deploy |

## Why the image is built rather than pulled

`openintegrationengine/engine` on Docker Hub is still on **4.5.2**, last pushed
in August 2025, while 4.6.0 was released in July 2026. So this builds from the
release tarball instead, verifying it against the `sha256sums` asset on the
GitHub release:

```
OIE_VERSION=4.6.0
OIE_SHA256=7c82e79027e671277e1d78d0f7bbb1c53ddf1be476f1e80c2ddbfcf7855900ea
```

Bump both together when a new version ships. `OIE_TARBALL_URL` overrides the
download location if you mirror releases internally.

The environment contract is deliberately identical to the upstream official
image's `configure-from-env` — `DATABASE*`, `KEYSTORE_*`, `SESSION_STORE`,
`SERVER_ID`, `_MP_*`, `VMOPTIONS`, `/run/secrets/mirth_properties`,
`*_DOWNLOAD`, `DELAY` — so you can switch to the official image when it catches
up, without rewriting your compose files.

Two deliberate differences:

- **`conf/` and `extensions/` are rebuilt from pristine copies on every boot**,
  so configuration is a pure function of the environment and a restart can never
  inherit half-applied state. This is safe because the schema version lives in
  the database's `SCHEMA_INFO` table, not in `mirth.properties`, so resetting the
  file does not confuse the migrator. Set `OIE_CONF_RESET=false` to opt out.
- **Property values are substituted with awk via the environment, not sed**, so a
  password containing `/`, `&` or a backslash cannot corrupt `mirth.properties`.

Any `FOO_FILE` variable also seeds `FOO` from a file, for Docker, GitLab and
Kubernetes secrets.

## The admin password

**OIE 4.6.0 seeds the admin account with `admin`/`admin`.** The
`server.initialadminpassword` property that lets you choose the initial password
from config — and the random-password-in-the-log behaviour described in the
upstream README — landed on `main` *after* the 4.6.0 release. On this version
the property is silently ignored.

Leaving that in place would mean every `compose up` stands up a reachable API on
well-known credentials, so the `bootstrap` service rotates the account to
`OIE_ADMIN_PASSWORD` once the engine reports healthy. It is idempotent: on later
boots it confirms the password and exits. `compose.yaml` already passes
`_MP_SERVER_INITIALADMINPASSWORD`, so when a release honours it the seeding path
starts working on its own and the bootstrap becomes a no-op.

Set `OIE_ADMIN_PASSWORD` **before the first `compose up`**. If you change it
later, either run the bootstrap with `OIE_DEFAULT_ADMIN_PASSWORD` set to the
current password, or change it in the Administrator.

## Pushing configuration

`config/` is the desired state. Everything is keyed on the id inside the XML, so
pushing a second time is an upsert, not a duplicate:

```
config/
  channels/<name>.xml                    one file per channel
  code-templates/libraries.xml           library definitions and membership
  code-templates/templates/<name>.xml    one file per code template
  channel-groups.xml                     the group tree
  configuration-map.properties           runtime key/value settings
```

```
export OIE_URL=https://localhost:8443/api
export OIE_PASSWORD=...        # or OIE_USER too, if not admin
export OIE_INSECURE=true       # the engine's cert is self-signed

./scripts/oie-config-push.sh --dry-run   # show what would change
./scripts/oie-config-push.sh             # push and deploy
./scripts/oie-config-push.sh --prune     # also delete channels not in config/
```

`--prune` removes channels the server has and git does not. That deletes their
message history too, so the CI pipeline uses it on staging and not on production.

### Authoring channels

**Export channels from the Administrator; do not hand-write the XML.** The XML is
XStream's serialisation of Java objects, and the field names have to match
exactly. This matters more than it sounds, because of the next section.

To edit an existing channel: change it in the Administrator, then

```
./scripts/oie-config-pull.sh   # writes the server's state back into config/
git diff config/               # review
```

Pull reformats each channel with `xmllint --format` so diffs stay
line-oriented, and names files after the channel while keeping the id inside the
file — so renaming a channel renames its file without changing its identity.

Values in `configuration-map.properties` may reference `${ENV_VAR}`; the push
expands them, which is how per-environment endpoints and secrets stay out of git
and come from CI variables instead.

### A channel the server cannot read is not an error

This is the one thing worth knowing before you trust a green pipeline. If the
server cannot deserialise a channel's XML, it does **not** reject it. It stores a
stub — Mirth's `InvalidChannel` — and:

- `PUT /channels/{id}` returns `200` with `{"boolean":true}`
- `POST /channels/{id}/_deploy?returnErrors=true` returns `204`
- the channel never appears on the dashboard and never runs

A single misspelled element does it. `useHeadersVariable` instead of
`useResponseHeadersVariable` inside an HTTP Listener's `<properties>` is enough,
and every API call still reports success.

So `oie-config-push.sh` reads each channel back after writing it and fails if it
came back as a stub, then checks each deployed channel actually reached a
`STARTED`/`PAUSED`/`STOPPED` state rather than trusting the `204`:

```
==> Channels
    http-ingest-echo: stored as an invalid channel -- the server could not deserialise it
        Usually an unknown or misspelled element inside a connector's <properties>,
        or an extension the server does not have installed.
```

## Mutual TLS without an extension

Core OIE 4.6.0 cannot do mutual TLS at all, so if you would rather not put Zen
on every channel, a reverse proxy covers it. What 4.6.0 actually has:

- **No TLS on channel listeners.** The HTTP Listener, TCP Listener and Web
  Service Listener are plaintext. OIE removed NextGen's in-product advertisement
  for the commercial extension but explicitly kept the HTTPS behaviour unchanged
  (upstream issue #156).
- **No client certificate verification**, except in the DICOM connectors, which
  have their own keystore/truststore/client-auth fields.
- **No truststore on the admin/API listener** — `MirthWebServer` sets a keystore
  on its `SslContextFactory.Server` and nothing more, so the API cannot request a
  client certificate either.

`compose.proxy.yaml` puts nginx in front and covers all three:

```
./scripts/gen-dev-certs.sh oie.local
docker compose -f compose.yaml -f compose.proxy.yaml up -d
```

| Port | | |
| --- | --- | --- |
| `443` | admin UI + REST API | client certificate required |
| `8444` | channel HTTP ingress → `engine:8081` | client certificate required |
| `6662` | MLLP over TLS → `engine:6661` | client certificate required |

The engine publishes no ports of its own under this overlay. nginx verifies the
client certificate and passes the verified identity through as request headers,
which a channel reads from the source map:

```javascript
var headers = sourceMap.get('headers');
var dn = headers.getHeader('X-Client-Cert-DN');   // O=Example Health,CN=ci-deployer
```

For per-connector mTLS inside the engine instead — the Zen-equivalent route,
using the installed TLS Manager plugin — see [`docs/mtls.md`](docs/mtls.md).

`config/channels/http-ingest-echo.xml` is a working example: POST to
`https://host:8444/ingest/` with a client certificate and it echoes the message
and the DN back.

```
$ curl --cacert clients-ca.crt --cert client.crt --key client.key \
       -X POST --data 'MSH|^~\&|TEST|||||ADT^A01' https://oie.local:8444/ingest/
{"received":"MSH|^~\\&|TEST|||||ADT^A01","clientCertDn":"O=Example Health,CN=ci-deployer","channel":"http-ingest-echo"}

$ curl --cacert clients-ca.crt -X POST --data '...' https://oie.local:8444/ingest/
400  # No required SSL certificate was sent
```

Note the trailing slash: Jetty answers `/ingest` with a `302` to `/ingest/`.

Also already installed: the `httpauth` extension ships with the engine, so HTTP
Basic, Digest and OAuth on a channel listener need nothing extra. Pair it with
the proxy for transport security.

## Admin interfaces, and the web console

Out of the box `https://localhost:8443/` gives you two UIs, both verified
working in this stack:

| | |
| --- | --- |
| `https://localhost:8443/` | landing page; points an [Administrator Launcher](https://docs.openintegrationengine.org/launchers/) at `/webstart.jnlp` for the Swing desktop client |
| `https://localhost:8443/api/` | Swagger API console (it injects the required `X-Requested-With` header itself, so it works in a browser even though raw curl gets a 400) |

The Swing Administrator needs the client jars, so keep `INCLUDE_ADMIN_CLIENT=true`
if anyone uses it — the launcher and `webstart/client-lib/*.jar` are served from
the same image.

**OIE 4.6.0 ships no browser-based admin console.** That is a community project,
installed as an extension:

```bash
# .env -- checksum from the release's SHA256SUMS, and it is enforced
OIE_EXTENSION_URLS=sha256:11014e5fc2a2b9ca5ef5c6750c42fc65ce832b3f7e918e076f5e1f9fa71fbf9a@https://github.com/gibson9583/oie-web-support-plugin/releases/download/v1.0.3/websupport-1.0.3.zip
```

```
docker compose up -d
./scripts/oie-check-extensions.sh "Web Support"
# then https://localhost:8443/oie-webadmin/
```

`.env` here installs four community extensions, all verified against 4.6.0:

| | serves |
| --- | --- |
| Web Support 1.0.3 | the console at `/oie-webadmin/` |
| Sentinel 1.1.0 | channel monitoring, rendered inside the console |
| Thread Viewer 1.0.6 | thread inspection, rendered inside the console |
| TLS Manager 1.0.7 | its own app at `/tls-manager/` — an open-source alternative worth weighing against Zen |

Every URL is checksum-pinned and the entrypoint refuses to start on a mismatch.
These archives hold jars and WARs the engine loads into its own JVM, so treat an
unpinned URL as remote code execution waiting to happen.

Downloads are cached in `appdata/extension-cache/` and a failed download falls
back to the verified cached copy, so a transient DNS or network failure cannot
leave the engine unable to start. It still refuses to start if a download fails
with nothing cached.

The engine serves it from its own embedded Jetty: `MirthWebServer` scans
`webapps/` for WARs once at startup, and the extension copies its WAR there from
`ServicePlugin.init()` on every boot, before that scan. The image creates
`webapps/` and clears stale WARs each boot to match — see
[`docs/web-ui.md`](docs/web-ui.md), which also covers `oie-sentinel`,
`engine-thread-viewer` and the open-source `tls-manager-plugin`.

One thing to get right: **install it via `OIE_EXTENSION_URLS` or `extensions/`,
not through the Administrator's Extensions → Install.** A UI install writes into
the container's `extensions/` directory, which this image resets from its
pristine copy on the next boot, so it would silently disappear.

### Knowing when there is an update

[`oie-update-check`](https://github.com/gibson9583/oie-update-check) (baked
into the image at build time, see `OIE_BUILTIN_PLUGIN_URLS` in
`docker/Dockerfile`) puts a chip next to the version in the console header when
a release you do not have has been published:

```
engine · local-utility · v4.6.0   ● 2 updates available
```

Clicking it opens an **Updates** page: what is running, what is published, and
the steps for this stack with the versions and checksums already filled in — the
`OIE_VERSION` and `OIE_SHA256` lines, the rebuild, the reminder that the 12
first-party plugins pinned in `OIE_BUILTIN_PLUGIN_URLS` have to be re-pinned to
builds that declare the new version because compatibility is an exact string
match, and the `oie-check-extensions.sh` that tells you it worked. It watches
the engine and the Web Support extension by default, and
`OIE_UPDATE_CHECK_EXTENSIONS` adds the rest — the 12 first-party plugins and the
community set — out of the box.

**It installs nothing.** There is no button on that page that touches the
engine. It is also the one thing in this stack that calls out on a timer: one
request per watched project per day to `api.github.com`, for the whole cluster
rather than per node, carrying nothing about this engine. `OIE_UPDATE_CHECK=false`
stops it reaching the network at all and beats the setting stored in the
database, so an egress-restricted deployment can refuse it from the deployment
rather than from the data; `OIE_UPDATE_CHECK_API_BASE` points it at a mirror
instead. See [`gibson9583/oie-update-check`](https://github.com/gibson9583/oie-update-check).

## Installing the Zen SSL extension

With a licence you get HTTPS/TCPS/LLPS listeners and senders inside the engine,
per-connector keystore selection in the Administrator, and certificate expiry
alerts. Drop the zip in `extensions/` and start:

```
extensions/zen-ssl-<version>.zip
docker compose up -d
./scripts/oie-check-extensions.sh          # what actually loaded
```

Three things to know, all verified against 4.6.0. `extensions/README.md` has the
detail.

**The version tag has to match exactly.** `ExtensionLoader.isExtensionCompatible()`
compares the server version against the extension's `<mirthVersion>` with a
literal string match over a comma-separated list — not a range. An extension
built for Mirth Connect 4.5.2 is refused by OIE 4.6.0:

```
ERROR ExtensionLoader: Extension "..." is not compatible with this version of
Open Integration Engine and was not loaded. Please install a compatible version.
```

The engine still starts, and the extension is just absent. Ask Zen for a build
declaring `4.6.0` — they [joined the OIE project](https://www.zenhealthcareit.com/article/zen-healthcare-it-joins-open-integration-engine-project-commits-to-community-driven-innovation-in-interoperability)
in 2025, so it is a reasonable request and the only option with their support
behind it. To move sooner, `OIE_EXTENSION_RETAG_VERSION=true` rewrites the tag
on extensions you installed and logs every change:

```
[entrypoint] RETAGGED zen-ssl/plugin.xml: mirthVersion '4.5.0,4.5.1,4.5.2' -> '4.6.0'
```

It is off by default because it asserts compatibility the vendor has not. It
touches only the version string, not licensing, but you are then running an
untested combination — confirm with Zen before production.

**A missing extension fails silently, so assert on it.** Nothing in the API
reports a rejected extension; the first symptom is every channel using its
connectors being stored as an invalid channel. So:

```
OIE_REQUIRED_EXTENSIONS="Zen SSL Extension" ./scripts/oie-check-extensions.sh
```

Run with no arguments to list the exact names to assert on. The CI pipeline runs
this in the validate stage and again before each deploy, so a deploy into an
environment missing the extension fails instead of quietly killing channels.

**Your CI validate engine needs the extension too.** Once channels reference
Zen's connector classes, any engine that receives them must have the extension
installed — including the throwaway one in the validate stage.
`.github/workflows/config-deploy.yml`'s `validate-config` job passes
`OIE_EXTENSION_URLS` and `OIE_DOWNLOAD_HEADER` (from repository secrets)
through to it; point them at the same artifact your runtime environments use.

**The licence key lives in PostgreSQL.** Extension settings are saved through
`configurationController.saveProperty()` into the `configuration` table, not a
file under `extensions/`. So entering it once in the Administrator survives
restarts, rebuilds and version bumps despite this image resetting `conf/` and
`extensions/` on every boot — and it is one more reason the database backup is
the one that matters. Each environment has its own database and so needs its own
licence entry.

### Keeping the proxy as well

With Zen installed `compose.proxy.yaml` becomes optional, but running both is
reasonable and fairly common:

- The **admin/API listener still cannot verify client certificates** even with
  Zen installed — that is `MirthWebServer`, not a connector. Only a proxy can
  put mTLS in front of the API.
- Certificate renewal is an nginx reload rather than a keystore import per
  connector.
- Channels stay plaintext internally, so the engine holds no private keys.

The usual split: proxy in front of the admin API, Zen connectors for channels
that need a keystore or client certificate chosen per partner.

## SFTP, both directions

[`oie-sftp-connector`](https://github.com/gibson9583/oie-sftp-connector) adds
two connectors the engine does not otherwise have, and like the other 12
first-party plugins is baked into the image at build time (`OIE_BUILTIN_PLUGIN_URLS`
in `docker/Dockerfile`) rather than built from source in this repo:

| | |
| --- | --- |
| **SFTP Listener** | *push*: the engine runs an SFTP server (default `:2222`) that partners upload into, with per-account passwords and public keys, and dispatches a message as each upload completes. *pull*: polls a remote server on the standard polling schedule. |
| **SFTP Sender** | writes each message to a remote server, through a temporary name renamed on completion |

The built-in File Reader and File Writer already speak SFTP as a client, so the
reason to reach for these is what they cannot do: run a server, and verify the
far end's host key against a `known_hosts` file or a pinned key rather than
trusting whatever answers. Both administrators have panels for them.

See [`gibson9583/oie-sftp-connector`](https://github.com/gibson9583/oie-sftp-connector),
which also covers why a connector extension has to register its own classes with
the channel serializer — without that, a channel using it saves with a `200` and
is stored as an invalid channel.

## Synthetic HL7 traffic

[`oie-random-generator`](https://github.com/gibson9583/oie-random-generator)
adds a **Random Generator** source connector:
HL7 v2 messages manufactured on the polling schedule, for when the channel is
ready and the upstream system is not.

It ships an editable sample per message type — ADT, ORM, ORU, SIU, DFT, MFN — with
`${...}` placeholders where the varying data goes, and resolves them against a
fixed population of invented patients rather than a fresh dice roll per message.
Patient 7 carries the same MRN, name, date of birth and PV1 in every message they
appear in, and the same seed produces the same population on every engine — so an
admit feed and a results feed can agree on who exists, and a bug report naming
patient 7 means something to whoever reads it.

Cadence is the polling interval plus a messages-per-poll count, with an optional
total limit for "send exactly 10,000 and stop". Both administrators have a panel,
with a Preview button that generates one message on the server through the same
code a deployed channel uses.

When the patients have to be *particular* people — the ones the downstream system is
already loaded with — sequential mode takes them from a table instead, one row per
patient. Every cell in it is optional: what you leave blank is filled from the
invented patient at that position, so a row naming only an MRN still carries a
stable address, next of kin and insurer.

See [`gibson9583/oie-random-generator`](https://github.com/gibson9583/oie-random-generator)
for the placeholder reference and the sample per message type.

## Running more than one engine

Three engines against one database, behind one address, sharing configuration
and deployments: deploy on any of them and every one of them runs it.

```
./scripts/oie-cluster-keystore.sh                                # once
docker compose -f compose.yaml -f compose.cluster.yaml up -d
```

OIE 4.6.0 has no clustering feature, but its schema was built for one — every
message, connector message and statistics row carries a `SERVER_ID`, and every
queue, recovery and statistics query filters on it, so two engines in the same
channel's tables is the schema working as designed. What is missing is the part
that tells the other engines a deployment happened, and that is
[`oie-cluster`](https://github.com/gibson9583/oie-cluster) (baked into the
image at build time, see `docker/Dockerfile`): a deploy on any node — console,
Swing Administrator, REST API, CI — becomes intent in the database, and every
node converges on it within a few seconds.

Three things make it work, and two of them the engine already had:

- **the same keystore on every node.** `appdata/keystore.jks` holds the secret
  key the engine encrypts with, so a pod that generates its own cannot read what
  another wrote. `scripts/oie-cluster-keystore.sh` takes it off a started engine;
  `KEYSTORE_SOURCE` gives it to the rest. This is the one mistake that loses data.
- **sessions in PostgreSQL.** `SESSION_STORE=true` with
  `server.api.sessioncache=none` puts them in a Jetty JDBC store over the engine's
  own datasource, so a round-robin front door needs no session affinity.
- **a stable `server.id` per node.** Queues are scoped by it. The entrypoint
  derives one from the pod's name when `appdata` is empty, and never replaces one
  an engine already has.

Channels are placed on every node, on exactly one (for pollers, which would
otherwise read the same source once per node), or pinned to a named one. Two
roles from one image keep the scheduled extensions — the data pruner, git sync,
the monitors — off the workers, through `OIE_DISABLE_EXTENSIONS`.

The web console grows two views. **Cluster** shows what each node has deployed,
message counts summed across them, and the queues left behind by a node that is
gone. **Nodes** ([`oie-node-monitor`](https://github.com/gibson9583/oie-node-monitor)) shows
the health of each engine — online or offline, uptime, CPU, heap, disk per
volume, threads, channel states and message volume with rates and an hour of
history. Each node samples itself into the shared database, so any node's console
answers for all of them, and a node that has stopped answering still shows its
last sample and when it was taken.

CI gets `--wait-cluster`, so a green pipeline means every engine deployed it
rather than just the one that answered.

Kubernetes manifests are in [`deploy/k8s/`](deploy/k8s/). The design, the
evidence behind each claim and what has and has not been verified are in
[`docs/multi-pod.md`](docs/multi-pod.md).

### Which nodes receive messages

Three different questions, and they do not have the same answer.

**Channels the utility node owns.** Always. A channel placed `SINGLETON` is
deployed on the utility node and nowhere else, and its source polls or reads
there — that is what the placement is for. Those messages are processed on the
utility node with no front door involved.

**Inbound channel traffic through the front door.** In Kubernetes, the workers
only: `oie-channels` selects `component: worker`, so `:8081` and `:6661` never
reach the utility node, which leaves its headroom for the pollers and the
pruner. In compose, all three: `proxy/cluster-lb.conf` lists `engine` — the
utility node — in the channel upstreams alongside both workers, so traffic
round-robins across every engine. The verified run in
[`docs/multi-pod.md`](docs/multi-pod.md) is that difference showing up in the
data: 27 of the messages pushed through the front door were handled by
`local-utility`. To make the local stack mirror the manifests, drop the two
`server engine:` lines from the `oie_channel_http` and `oie_channel_mllp`
upstreams; the admin upstream is a separate question, below.

**The admin API.** Every node serves it, and the console any node serves answers
for the whole cluster — but the *panels* a console can render depend on which
node answered. The utility-only extensions (git sync, data pruner, volume
monitor) are stripped from the workers by `OIE_DISABLE_EXTENSIONS`, so a console
served by a worker shows those as unavailable. In compose, `oie_admin` is
therefore pinned to `engine`: one node, every panel. In Kubernetes the
`oie-admin` Service still selects both roles, so the same panels are a coin toss
there; pin the Service to `component: utility`, or reach the utility pod through
`oie-headless`, if you need them.

One consequence worth knowing before placing a channel: `SINGLETON` goes with a
source that pulls — a File Reader, a Database Reader, a poller of any kind. A
channel whose source is a *listener*, placed `SINGLETON` under Kubernetes, binds
its port on the utility pod, which `oie-channels` does not route to; it listens
and hears nothing. Listener-source channels want `ALL`.

## Tests

A full test suite runs in **GitHub Actions** ([`.github/workflows/tests.yml`](.github/workflows/tests.yml)),
independent of the build/validate/deploy pipeline below. Six tiers:

- **js-unit** — `node --test` over any plugin- or overlay-side
  `webadmin/web/plugin.js` pure logic (XStream decode/encode, TSV parsing,
  formatting, validation) still tracked in this repo (the TLS Manager overlay,
  for example), via a mirror module beside each test. The 12 first-party
  plugins' own unit tests now live and run in their own repos.
- **java-unit** — compiles and runs any dependency-free Java test still under
  `plugins/` or `extensions/` in this repo; skips cleanly now that the 12
  first-party plugins' Java tests live in their own repos.
- **shell-unit** — `bats` over the pure logic in `docker/entrypoint.sh`
  (`set_prop`, `_MP_*` mangling, `server.id` derivation, extension-URL checksum
  parsing) and `scripts/oie-config-push.sh` (`${VAR}` expansion, XML escaping).
- **shellcheck** / **lint-static** — every script, plus channel-XML
  well-formedness, duplicate-id detection, YAML and `docker compose config`.
- **integration** — builds the engine image (which fetches and bakes in the 12
  first-party plugins via `OIE_BUILTIN_PLUGIN_URLS`, see `docker/Dockerfile`),
  stands up PostgreSQL + the engine, then pushes the fixture channels in
  `test/fixtures/` and the real `config/` and asserts none was stored as an
  `InvalidChannel` and each reached a deployed state — the same invalid-channel
  trap `oie-config-push.sh` guards against, on a throwaway engine.

See [`test/README.md`](test/README.md) for how to run each tier locally, how the
copied-mirror unit tests are kept in step with `plugin.js`, and which connector
fixtures still need an Administrator export.

## Build, validate, deploy (GitHub Actions)

Two workflows split the same three stages the old GitLab pipeline had:

1. **build** — [`.github/workflows/docker-build.yml`](.github/workflows/docker-build.yml)
   builds and pushes the image to `ghcr.io/<owner>/open-integration-engine`, checksum-pinned.
   The `latest` tag only moves on the default branch.
2. **validate** — [`.github/workflows/config-deploy.yml`](.github/workflows/config-deploy.yml)'s
   `validate-config` job starts PostgreSQL and the just-built engine, bootstraps
   the admin password, and runs the real config push against them — the same
   server-cannot-deserialise failure mode the pipeline exists to catch, on a
   server nobody depends on. Its `lint-config` job checks XML well-formedness,
   missing ids and duplicate ids without needing an engine, so a config-only PR
   gets feedback fast.
3. **deploy** — also in `config-deploy.yml`: dry run, then push. Staging
   (`deploy-staging`) auto-deploys with `--prune` on every push to the default
   branch; production (`deploy-production`) targets a GitHub **Environment**
   named `production`, which is where the manual gate lives — add required
   reviewers to that environment (Settings → Environments → production) and the
   job pauses for approval, the equivalent of GitLab's `when: manual`. Neither
   job prunes production: deleting a channel there takes its message history
   with it.

Secrets to set (Settings → Secrets and variables → Actions, plus the same names
on the `staging` / `production` environments where the value differs per
environment): `OIE_STAGING_URL`, `OIE_STAGING_PASSWORD`, `OIE_PROD_URL`,
`OIE_PROD_PASSWORD`, plus whatever `configuration-map.properties` references.
Behind the mTLS proxy, add the client certificate and key as secrets and point
`OIE_CLIENT_CERT` / `OIE_CLIENT_KEY` at them, with the CA in `OIE_CACERT`
instead of `OIE_INSECURE`.

## Operating notes

**Ports.** `compose.yaml` publishes the API on `127.0.0.1:8443` only. Channel
listener ports are whatever your deployed channels bind, and are not published
until you add them — see `compose.dev.yaml` for the pattern. The plaintext admin
listener is off (`HTTP_PORT=0`); the REST API is HTTPS-only regardless, since
`server.api.allowhttp` defaults to false.

**Heap.** The engine's shipped default is a 256MB ceiling. `OIE_HEAP_MAX`
defaults to `1g` here; raise it for real message volume. `VMOPTIONS` takes a
comma-separated list for anything else.

**Database connections.** The engine opens two pools of
`DATABASE_MAX_CONNECTIONS` each — read/write and read-only — so budget
`2 × DATABASE_MAX_CONNECTIONS × replicas` against `POSTGRES_MAX_CONNECTIONS`.

**Keystore.** `appdata/keystore.jks` holds the engine's self-signed TLS
certificate and its data-encryption secret key. The password shipped in
`mirth.properties` is the same in every 4.6.0 install, so `KEYSTORE_PASSWORD`
overrides it — but changing it after first boot makes the existing keystore
unreadable, which means losing anything encrypted with that key. Set it once,
up front.

**Logs.** The engine logs to stdout and to `logs/mirth.log`, so `docker compose
logs engine` works. Note that log4j is configured late in startup, so anything
the schema migrator says on a first boot is lost — which is exactly why the
initial admin password is set explicitly rather than read from a log.

**Backups.** Back up PostgreSQL *and* the `engine-appdata` volume. The database
holds channels and messages; the volume holds the keystore whose key decrypts
anything stored encrypted. Restoring one without the other is not a restore.

**Hardening worth reviewing.** `mirth.properties` ships
`server.api.accesscontrolalloworigin = *`. It is left at the default here rather
than changed silently; `API_CORS_ALLOW_ORIGIN` sets it, and there is no reason
for a server-to-server deployment to allow any origin.

## Troubleshooting

**`stored as an invalid channel`** — an element inside a connector's
`<properties>` does not match the Java field name, or the channel needs an
extension the server does not have. Compare against a channel exported from the
Administrator on this version.

**`authentication failed`** — after the first boot the password is
`OIE_ADMIN_PASSWORD`, not `admin`. Check `docker compose logs bootstrap`.

**`unexpected state after deploy: none`** — the channel saved and deployed but
never reached the dashboard. Usually a listener port already in use, or a
disabled channel.

**Engine exits or hangs at boot** — `docker compose logs engine`. The entrypoint
prints every property it sets and waits up to `OIE_DB_WAIT_TIMEOUT` seconds
(default 60) for the database socket before starting anyway.
