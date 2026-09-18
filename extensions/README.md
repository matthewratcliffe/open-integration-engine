# Third-party and licensed extensions

Drop extension **zip files** in this directory. Compose mounts it read-only at
`/opt/engine/custom-extensions`, and the entrypoint unpacks every `*.zip` into
`/opt/engine/extensions` before the engine starts.

The zips are gitignored — licensed extensions are not ours to redistribute.

## Installing the Zen SSL extension

```
extensions/zen-ssl-<version>.zip
docker compose up -d
```

Then check it loaded:

```
docker compose logs engine | grep -iE 'installed extension|not compatible'
```

There is one gate to get past first.

### The version gate

**OIE checks extension compatibility with an exact string match**, not a range.
`ExtensionLoader.isExtensionCompatible()` splits the extension's
`<mirthVersion>` on commas and compares each entry literally against the server
version. An extension built for Mirth Connect 4.5.2 therefore does not load on
OIE 4.6.0 at all:

```
ERROR ExtensionLoader: Extension "Zen SSL Extension" is not compatible with
this version of Open Integration Engine and was not loaded. Please install a
compatible version.
```

The engine still starts; the extension is simply absent, and every channel that
uses one of its connectors will be stored as an invalid channel.

**Ask Zen for a build that declares `4.6.0` first.** Zen
[joined the OIE project](https://www.zenhealthcareit.com/article/zen-healthcare-it-joins-open-integration-engine-project-commits-to-community-driven-innovation-in-interoperability)
in 2025, so an OIE-tagged build is a reasonable thing to request, and it is the
only option that comes with their support behind it.

If you need to move before that build exists, the entrypoint can rewrite the tag:

```
OIE_EXTENSION_RETAG_VERSION=true
```

It rewrites `<mirthVersion>` in `plugin.xml`, `source.xml` and `destination.xml`
for extensions *you* installed — never the bundled ones — and logs every change:

```
[entrypoint] RETAGGED zen-ssl/plugin.xml: mirthVersion '4.5.0,4.5.1,4.5.2' -> '4.6.0'
```

It is off by default and deliberately loud, because it asserts a compatibility
claim the vendor has not made. It only changes the metadata's version string —
it does not touch licensing — but it does mean you are running a configuration
Zen has not tested. Confirm with them before relying on it in production, and
treat any resulting misbehaviour as your own to diagnose. Since OIE 4.6.0 forked
from Mirth Connect 4.5.x with the same `com.mirth.connect` packages, a 4.5.x
extension is usually binary compatible in practice — but "usually" is doing real
work in that sentence.

### Where the licence key lives

Extension settings are saved with
`configurationController.saveProperty(pluginName, ...)`, which writes to the
`configuration` table in PostgreSQL — not to a file under `extensions/`. So:

- Enter the licence once in the Administrator and it survives container
  restarts, image rebuilds and version bumps, even though this image resets
  `conf/` and `extensions/` from pristine copies on every boot.
- It lives in the database, so **back up PostgreSQL** or you will be re-keying
  after a restore.
- Each environment has its own database, so staging and production each need
  their own licence entry. Zen licenses per production instance — check what
  your agreement says about non-production instances before standing up more.

### Fetching from a registry instead

Better for CI/CD, where the artifact belongs in a registry rather than on each
deployment host:

```
OIE_EXTENSION_URLS=https://gitlab.example.com/api/v4/projects/1/packages/generic/zen-ssl/1.0.0/zen-ssl.zip
OIE_DOWNLOAD_HEADER=PRIVATE-TOKEN: <token>
```

`OIE_EXTENSION_URLS` takes a comma- or space-separated list. `EXTENSIONS_DOWNLOAD`
also works and expects a single zip containing extension zips, matching the
upstream official image.

## Consequences for config-as-code

Once channels use Zen's connectors, their XML references Zen's classes — for
example `class="com.zenhealthcareit.connectors.https.HttpsReceiverProperties"`
rather than the core `com.mirth.connect.connectors.http.HttpReceiverProperties`.

**Any engine that receives those channels must have the extension installed**,
including the throwaway engine in the CI validate stage. Without it the server
cannot deserialise the channel, stores the invalid-channel stub, and
`oie-config-push.sh` fails with:

```
<channel>: stored as an invalid channel -- the server could not deserialise it
```

That is the check working correctly: a channel pushed to an engine missing its
extension would never have run. `.gitlab-ci.yml` passes `OIE_EXTENSION_URLS` to
the validate-stage engine service for exactly this reason — keep it pointing at
the same artifact your runtime environments use.

## What you no longer need the proxy for

With Zen installed you have HTTPS/TCPS/LLPS listeners and senders inside the
engine, with per-connector keystore selection and certificate expiry alerts in
the Administrator UI. `compose.proxy.yaml` becomes optional rather than the only
way to do mutual TLS.

Reasons to keep the proxy anyway, and they are not weak ones:

- Certificate renewal is an nginx config reload, not an engine restart or a
  keystore import per connector.
- One trust anchor and one TLS policy for the admin API *and* every channel, set
  in one file — the admin/API listener still cannot verify client certificates
  even with Zen installed, because that is `MirthWebServer`, not a connector.
- Channels stay plaintext internally, so the engine never holds private keys.

Running both is fine and fairly common: proxy in front of the admin API, Zen
connectors for channels that need per-partner keystores or client certificates
chosen per connector.

## Already bundled, no extension needed

`httpauth` ships with the engine, so HTTP Basic, Digest and OAuth on a channel
listener need nothing extra. The DICOM connectors also have their own
keystore/truststore and client-auth settings built in.
