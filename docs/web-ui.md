# The web administrator

OIE 4.6.0 ships **no browser-based admin UI**. Out of the box `https://host:8443/`
serves a landing page that points an [Administrator Launcher](https://docs.openintegrationengine.org/launchers/)
at `/webstart.jnlp` to start the Swing desktop client, plus the Swagger API
console at `/api/`. Both work in this stack — verified — but neither is a web
console.

The browser console is a community project, in two halves:

| Project | What it is |
| --- | --- |
| [`oie-web-client`](https://github.com/gibson9583/oie-web-client) | the web administrator itself, released as `oie-webadmin.war` |
| [`oie-web-support-plugin`](https://github.com/gibson9583/oie-web-support-plugin) | packages that WAR as an OIE extension (`websupport`) and adds the engine-side APIs it needs |

Install the extension and the console is served by the engine itself at
`https://host:8443/oie-webadmin/`.

## How it hangs together

`MirthWebServer` scans `<OIE_HOME>/webapps/` for `*.war` **once at startup** and
mounts each one at `/<warname>`. The Web Support plugin's
`WebClientDeployServicePlugin.init()` copies its bundled `oie-webadmin.war` from
its own extension directory into `webapps/`, and that runs on every engine start,
before the scan. So the deployment is re-established on each boot rather than
being a one-off install action.

Three consequences for this stack, all already handled:

- **The image creates `webapps/`.** The release tarball has no such directory.
  Jetty tolerates it missing (`listFiles()` returning null is guarded) and the
  plugin would `mkdir` it anyway, but creating it up front with the right
  ownership keeps that off the critical path.
- **`webapps/*.war` is cleared on every boot**, alongside the `conf/` and
  `extensions/` reset. Safe precisely because the plugin re-copies at `init()`,
  and it stops a WAR from an extension you removed or downgraded being served
  indefinitely — upstream warns that this is exactly what happens if the engine
  is killed mid-uninstall.
- **The extension must live in `extensions/` or come from a URL**, not be
  installed through the Administrator's *Extensions → Install*. A UI install
  writes into the container's `extensions/` directory, which this image resets
  from its pristine copy on the next boot, so the extension would silently
  vanish. That is the deliberate trade for reproducible boots.

## Installing

Pick one of the two declarative routes. Both survive restarts and rebuilds.

### Fetch at boot (preferred)

Nothing binary in the repo, and the checksum is pinned:

```bash
# .env
OIE_EXTENSION_URLS=sha256:11014e5fc2a2b9ca5ef5c6750c42fc65ce832b3f7e918e076f5e1f9fa71fbf9a@https://github.com/gibson9583/oie-web-support-plugin/releases/download/v1.0.3/websupport-1.0.3.zip
```

```
docker compose up -d
```

That checksum is `websupport-1.0.3.zip` as published in the release's
`SHA256SUMS`, and it is verified here. **Pin it.** The archive contains a jar and
a WAR that the engine loads into its own JVM, so an artifact that changes under
you is arbitrary code execution inside the engine. The entrypoint refuses to
start on a mismatch:

```
ERROR: checksum mismatch for https://.../websupport-1.0.3.zip:
       expected 0000..., got 8e9164...
```

and warns when a URL carries no checksum at all.

**Downloads are cached, and a failed download falls back to the cache.** The
cache lives in `appdata/extension-cache/`, which is a persistent volume, keyed by
checksum so a version bump is a separate entry. Without this, every restart
would depend on the network being up: a single transient DNS failure inside the
container is enough to make the entrypoint abort, and the engine then sits in a
restart loop until the network returns. Found the hard way — a Docker DNS blip
during a container recreate did exactly that.

The pinned checksum is re-verified against the cached copy, so the fallback
cannot quietly serve something other than what you asked for, and the cache is
only refreshed from a verified download:

```
WARNING: download failed for https://.../websupport-1.0.3.zip, using cached copy
checksum verified for extension 1 (from cache)
installed extension ext-1.zip
```

It still aborts when a download fails and nothing is cached, because starting
without an extension the channels depend on is worse than not starting.

For production, the mounted-zip route avoids the question entirely: no network
dependency at boot at all.

### Mounted zip

```
extensions/websupport-1.0.3.zip
docker compose up -d
```

Verify the checksum yourself first:

```
curl -sSL https://github.com/gibson9583/oie-web-support-plugin/releases/download/v1.0.3/SHA256SUMS
sha256sum extensions/websupport-1.0.3.zip
```

### Then check it loaded

```
docker compose logs engine | grep -iE 'installed extension|not compatible|webadmin'
./scripts/oie-check-extensions.sh "Web Support"
curl -sk -o /dev/null -w '%{http_code}\n' https://localhost:8443/oie-webadmin/
```

`websupport-1.0.3.zip` declares `<mirthVersion>4.6.0</mirthVersion>`, which
matches this engine exactly, so it needs no `OIE_EXTENSION_RETAG_VERSION`. Older
releases ship a separate `-oie4.5.2` variant — take the one built for your
engine version rather than retagging, when the choice exists.

## Behind the mTLS proxy

`compose.proxy.yaml` proxies `location /` on :443 to the engine, so
`/oie-webadmin/` is reached through it with no extra configuration, and a browser
can present a client certificate for it. Two things to know:

- **The Swing Administrator is the awkward one behind mTLS**, not the web
  console. Its launcher and the client itself would each need to present a client
  certificate. If you use the desktop client, either reach the engine on a path
  that does not demand one, or use the web console through the proxy instead —
  which is a real argument for installing it.
- The JNLP `codebase` follows the request host, so through the proxy it correctly
  reports `https://oie.local:443` rather than the container address. Set
  `SERVER_URL` if you need to override it.

## Related community extensions

Same install mechanism, same version gate.

All five are installed here and verified against this engine: every one declares
a `<mirthVersion>` that includes `4.6.0`, so none needs
`OIE_EXTENSION_RETAG_VERSION`. Installing the OIDC one does not switch anything
on — it adds the settings page, and single sign-on stays off until someone fills
it in and ticks Enabled.

| Extension | Version | `mirthVersion` | What it adds |
| --- | --- | --- | --- |
| [`oie-web-support-plugin`](https://github.com/gibson9583/oie-web-support-plugin) | 1.0.3 | `4.6.0` | the web console at `/oie-webadmin/`, plus the APIs plugin UIs hang off |
| [`oie-sentinel`](https://github.com/gibson9583/oie-sentinel) | 1.1.0 | `4.6.0` | channel monitoring and alerting — inactivity, low volume, anomaly, error rate, queue depth, channel state — with email/SNS/webhook delivery. UI embeds in the console, so it needs Web Support. MPL-2.0. |
| [`engine-thread-viewer`](https://github.com/gibson9583/engine-thread-viewer) | 1.0.6 | `4.6.0` | thread inspection; UI embeds in the console |
| [`tls-manager-plugin`](https://github.com/NovaMap-Health/tls-manager-plugin) | 1.0.7 | `4.5.2,4.6.0` | TLS connectors and certificate management, sponsored by NovaMap Health and Diridium and donated to the OIE initiative. Serves its **own** WAR at `/tls-manager/`. |
| [`oie-oidc-auth`](https://github.com/gibson9583/oie-oidc-auth) | 1.0.1 | `4.6.0` | OpenID Connect sign-in, configured from *Settings → OIDC Authentication* in the console and stored in the database. Adds the login card's SSO button; local accounts are unaffected. See [`sso.md`](sso.md). |

The pinned set, as installed:

```bash
OIE_EXTENSION_URLS=sha256:11014e5f...@https://github.com/gibson9583/oie-web-support-plugin/releases/download/v1.0.3/websupport-1.0.3.zip,sha256:c8785113...@https://github.com/gibson9583/oie-sentinel/releases/download/v1.1.0/sentinel-1.1.0.zip,sha256:7597579a...@https://github.com/gibson9583/engine-thread-viewer/releases/download/v1.0.6/thread-viewer-1.0.6.zip,sha256:67a2391c...@https://github.com/NovaMap-Health/tls-manager-plugin/releases/download/1.0.7/tls-manager-1.0.7.zip,sha256:a18d208c...@https://github.com/gibson9583/oie-oidc-auth/releases/download/v1.0.1/oidcauth-1.0.1.zip
```

The full hashes are in `.env`. Note where each came from: Web Support, Sentinel
and Thread Viewer publish `SHA256SUMS`/`.sha256` assets and those were checked
against the downloads. **TLS Manager and OIDC Auth publish no checksum** — that
release carries the zip and nothing else — so their pins are the hash of the
artifact as downloaded and inspected here. That protects against the file
changing under you from now on, but it is not a vendor-published value.
Re-derive it yourself if that distinction matters to you.

Always confirm a zip's tag before installing a version not listed above:

```
unzip -p <ext>.zip '*/plugin.xml' | grep mirthVersion
```

### Which UIs end up where

```
/oie-webadmin/   the console       (Web Support's WAR)
   +-- Sentinel and Thread Viewer render inside it, sharing its session
/tls-manager/    separate web app  (TLS Manager's own WAR)
```

`GET /api/extensions/websupport/webplugins` lists the extensions that registered
a UI with the console. TLS Manager originally did not appear there — it ships a
standalone WAR, not a console plugin — so this repo supplies the missing console
half and the list now includes it: `["sftp","fhir","volumemonitor","gitsync","sentinel",
"ssouserguard","tls-manager","cluster","oidcauth","nullsender","updatecheck",
"nodemonitor","thread-viewer","keystore","generator"]` on this stack, community
extensions and this stack's own first-party plugins alike — the latter now
fetched from their own repos and baked into the image at build time via
`OIE_BUILTIN_PLUGIN_URLS` (see `docker/Dockerfile`) rather than built from
`plugins/` in this repo.

**The list is built at engine startup, not per request**, so a newly installed console
plugin needs a restart before the console will serve it — and since the image resets
`extensions/` on every boot, copying files into a running container achieves nothing at
all. Install the zip and restart.

`ssouserguard` is the one extension here with no server code whatsoever: no
`serverClasses`, no jar, no `apiProvider`, just a `plugin.xml` carrying a `webadmin/`
folder. The engine registers it like any other extension and loads nothing into its JVM,
which makes it the cheapest way to add a console-only surface. See
[`gibson9583/oie-sso-user-guard`](https://github.com/gibson9583/oie-sso-user-guard).

### Two surfaces the console has no hook for

The platform a plugin's `register(platform)` receives covers nav items, routed
views, settings panels, channel and dashboard tabs, connector panels, commands
and login authenticators. Two things this repo wanted are not in that list, and
both are done by finding the element in the DOM instead:

| | wants | finds |
| --- | --- | --- |
| `ssouserguard` | the Edit User dialog, to lock it for provider-managed accounts | `.modal-overlay [role="dialog"]` whose header begins *Edit User* |
| `updatecheck` | the header, to put an "updates available" chip beside the version | `.server-chip` inside `header.topbar` |

Both fail the same way, which is why the trade is acceptable: **if the console
changes that markup the feature stops appearing**, and nothing else changes. No
half-locked form, no chip in the wrong place, no broken header. The update
check's page stays reachable from the sidebar and the command palette
regardless, and the guard's dialog behaves exactly as it does without it.

The chip additionally re-asserts its position when the header re-renders: React
owns that header's children and never removes a node it did not create, but it
inserts its own relative to its own, so a foreign node can drift. Both belong
upstream in `oie-web-client` as extension points — a header slot and a user-page
hook — and this is what they look like until then.

### Putting TLS Manager in the console sidebar

TLS Manager's certificate manager is a separate app at `/tls-manager/`, which
means it is not in the sidebar and needs its own navigation. The console
discovers a plugin UI from a `webadmin/plugin.json` inside an extension's
directory; TLS Manager's zip has no `webadmin/` folder at all.

`extensions/webadmin-overlay/tls-manager/webadmin/` provides one: a manifest and
a `web/plugin.js` that registers a nav item and a routed view embedding the
existing app. The entrypoint copies any `webadmin-overlay/<extension>/` tree over
that extension after unpacking the zips, on every boot, so it survives the
`extensions/` reset:

```
[entrypoint] applied overlay to extension 'tls-manager'
```

Two details make it work:

- **`plugin.js` is hand-written plain ES module JavaScript** — no JSX, no
  imports, no build step. The console dynamically `import()`s it and passes its
  own `platform` into `register(platform)`, which is how the bundled plugins
  receive it. Importing `@oie/web-shell` instead would depend on the page's
  import map and risks a second framework instance registering into a dead
  registry, which fails silently.
- **The engine must permit same-origin framing.** `ClickjackingFilter` sends
  `X-Frame-Options: DENY` and `frame-ancestors 'none'` on every WAR by default,
  which leaves the panel blank. `compose.yaml` sets
  `_MP_SERVER_API_XFRAMEOPTIONS=SAMEORIGIN` and
  `_MP_SERVER_API_CONTENTSECURITYPOLICY=frame-ancestors 'self'`. That still
  refuses framing by any other origin. Revert both (`API_XFRAME_OPTIONS=DENY`,
  `API_CSP="frame-ancestors 'none'"`) if you would rather not, and the view
  degrades to a message and an open-in-new-tab link rather than a blank panel.

The embedded app shares the console's session — same origin, same cookie — so
there is no second login. Its own fixed header is hidden by a small stylesheet
injected into the frame, since a second logo and logout button inside a console
panel is actively confusing (that logout signs you out of the inner app only).
The rule targets library-level MUI classes, and if their markup changes it
simply stops matching and the header comes back.

**This is a bridge, not a port.** NovaMap's app already implements the whole
certificate manager against `/api/tlsmanager`; reimplementing it as a native
React console plugin would duplicate that work and drift from it. A native
plugin is the better long-term answer and belongs upstream with them — their own
README notes that the "overarching UI container into which new plugins could add
their web UIs" was a question they parked, and the console is now that container.
This exists so the sidebar works today.

### A TLS Settings tab in the console

The same overlay adds the other half TLS Manager is missing. Its connector UI is
a Swing `ConnectorPropertiesPlugin`, so the web console had no **TLS Settings**
tab on any connector — the settings existed but were only reachable from the
desktop client.

`web/plugin.js` registers a native panel through
`platform.registerConnectorPropertiesPanel`, the same hook the bundled
`httpauth` plugin uses. Verified registered in the console's own registry
alongside it:

```
connectorPropertiesPanels() -> ["httpauth:Authentication", "tls-manager:TLS Settings"]
```

It attaches to exactly the six connectors the Swing plugin does — HTTP, TCP and
Web Service, listener and sender — and edits
`connector.properties.pluginProperties["org.openintegrationengine.tlsmanager.shared.properties.TLSConnectorProperties"]`,
which is the same entry the Swing tab writes. So the two clients read each
other's configuration, and `oie-config-pull` / `oie-config-push` carry it like
any other channel property.

The panel shows listener fields (server certificate, client auth mode) or sender
fields (client certificate, hostname verification) depending on the connector,
plus the shared truststore, Subject DN, CRL/OCSP and protocol settings.
Certificate dropdowns are populated live from `/api/tlsmanager/localCertificates`
and `/trustedCertificates`, and an alias a channel references but the store
lacks is still shown and preserved rather than silently dropped.

Two things it deliberately does not do: cipher-suite selection, which needs the
JVM's own enumerated list and is better done in the Swing tab; and the
connection tester. Both remain available there.

`defaults()` mirrors the `TLSConnectorProperties()` constructor field for field.
That matters more than it looks — a missing or misspelled field makes the engine
store the entire channel as an `InvalidChannel` while reporting a successful
save, which is the failure mode `oie-config-push.sh` exists to catch.

See [`mtls.md`](mtls.md) for using it.

### TLS Manager configuration

It reads its own settings from the environment, defaulting to storing
certificates in the **database**:

| Variable | |
| --- | --- |
| `OIE_TLS_PLUGIN_PERSISTENCE_BACKEND` | `DATABASE` (default) or `FILESYSTEM` |
| `OIE_TLS_PLUGIN_FS_KEYSTOREPATH` / `..._FS_KEYSTOREPASS` | filesystem mode only |
| `OIE_TLS_PLUGIN_FS_TRUSTSTOREPATH` / `..._FS_TRUSTSTOREPASS` | filesystem mode only |
| `OIE_TLS_PLUGIN_DISABLE_UI` | `true` skips deploying its WAR entirely |

The database default is the right one for this stack: certificates then travel
with your PostgreSQL backup rather than living in a container filesystem that
gets rebuilt. If you switch to `FILESYSTEM`, the paths must point at a mounted
volume or the material is lost on recreate.

Unlike Web Support, TLS Manager **throws** if it cannot write its WAR, so a
read-only or unwritable `webapps/` fails the plugin loudly rather than silently
skipping the UI.

Once any of these is in use, remember the CI consequence: the validate-stage
engine needs the same extensions, or channels referencing their connectors come
back as invalid channels. `.gitlab-ci.yml` passes `OIE_EXTENSION_URLS` through
for that reason, and `OIE_REQUIRED_EXTENSIONS` should name them so a missing one
fails the pipeline instead of the deploy.
