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
OIE_EXTENSION_URLS=sha256:69a6b2285000d6c7356aaa547746528fe0bf145ba9bdf5d595fb9a390aff54ff@https://github.com/gibson9583/oie-web-support-plugin/releases/download/v1.3.0/websupport-1.3.0.zip
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
| [`oie-tls-manager`](https://github.com/matthewratcliffe/oie-tls-manager) (fork of [NovaMap Health's `tls-manager-plugin`](https://github.com/NovaMap-Health/tls-manager-plugin)) | 1.1.4 | `4.5.2,4.6.0,4.7.0` | TLS connectors and certificate management, sponsored by NovaMap Health and Diridium and donated to the OIE initiative. Adds a **TLS Manager** tab under *Settings* and a **TLS Settings** connector panel in the console, and still serves its **own** WAR at `/tls-manager/`. The fork also handles certificates stored with a full chain. |
| [`oie-oidc-auth`](https://github.com/gibson9583/oie-oidc-auth) | 1.0.1 | `4.6.0` | OpenID Connect sign-in, configured from *Settings → OIDC Authentication* in the console and stored in the database. Adds the login card's SSO button; local accounts are unaffected. See [`sso.md`](sso.md). |

The pinned set, as installed:

```bash
OIE_EXTENSION_URLS=sha256:11014e5f...@https://github.com/gibson9583/oie-web-support-plugin/releases/download/v1.3.0/websupport-1.3.0.zip,sha256:c8785113...@https://github.com/gibson9583/oie-sentinel/releases/download/v1.2.2/sentinel-1.2.2.zip,sha256:7597579a...@https://github.com/gibson9583/engine-thread-viewer/releases/download/v1.0.8/thread-viewer-1.0.8.zip,sha256:c1fd7fe6...@https://github.com/matthewratcliffe/oie-tls-manager/releases/download/1.1.11/tls-manager-1.1.11.zip,sha256:a18d208c...@https://github.com/gibson9583/oie-oidc-auth/releases/download/v1.0.3/oidcauth-1.0.3.zip
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
[`matthewratcliffe/oie-sso-user-guard`](https://github.com/matthewratcliffe/oie-sso-user-guard).

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

### TLS Manager in the console

TLS Manager is `matthewratcliffe/oie-tls-manager` (a fork of NovaMap Health's
plugin), pinned at 1.1.4. Unlike upstream 1.0.x, its zip ships its own
`webadmin/` folder, so the console UI comes from the extension itself:

- **Settings → TLS Manager** is the certificate manager. It is a tab under
  *Settings*, not a sidebar entry.
- **TLS Settings** appears on the HTTP, TCP and Web Service connectors.

The standalone app is still at `/tls-manager/`. The old
`extensions/webadmin-overlay/tls-manager` bridge, which added a sidebar entry
that embedded that app, has been removed; the entrypoint's overlay mechanism is
still there for any extension that needs one. `compose.yaml` still sets the
same-origin framing headers (`_MP_SERVER_API_XFRAMEOPTIONS=SAMEORIGIN`) that the
bridge needed. The native panel does not frame anything, so they can be reverted
to `DENY` if you want the stricter default.
