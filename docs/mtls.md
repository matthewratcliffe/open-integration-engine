# Mutual TLS

Three ways to do mTLS on this stack, in the order most people should consider them.

| | Where TLS terminates | Per-connector control | Cost |
| --- | --- | --- | --- |
| **TLS Manager plugin** | in the engine, per connector | yes | free (MPL-2.0), installed here |
| **Zen SSL extension** | in the engine, per connector | yes | commercial licence |
| **nginx proxy** (`compose.proxy.yaml`) | in front of the engine | no, one policy for all | free |

Core OIE 4.6.0 on its own does none of it: the HTTP, TCP and Web Service
listeners are plaintext, and nothing outside the DICOM connectors verifies a
client certificate. See the mTLS section in the [README](../README.md).

## TLS Manager — the Zen-equivalent route

This is the closest analogue to what Zen HTTP gives you, and it is already
installed. It does **not** add new connectors: it upgrades the existing HTTP,
Web Service and TCP senders and listeners with a TLS tab, so existing channels
keep working untouched and you opt in per connector. It provides mTLS both
directions, CRL and OCSP (with hard-fail), Subject DN validation, hostname
verification, cipher and protocol selection, TCP client/server mode, and a
connection tester.

Certificates are referenced **by alias**, so the alias is the contract between
the material in the store and the channels that use it. Keep aliases identical
across environments and the same channel XML deploys everywhere.

### 1. Load the certificates

In the console sidebar: **PLUGINS → TLS Manager** (or `/tls-manager/` directly).
Three tabs:

| Tab | What goes in it |
| --- | --- |
| **Native Java Certificate Store** | the JDK's ~146 public roots, read-only |
| **Additional Trusted Certificates** | your truststore — the CAs whose certificates you accept |
| **Local Key Pairs** | your keystore — certificates *this engine presents* |

For an **inbound** mTLS listener you need:

- a **key pair** in *Local Key Pairs* — the server certificate the listener
  presents. **Import Certificate** takes a PEM chain, either pasted or from a
  file, and asks for an alias.
- the **client CA** in *Additional Trusted Certificates* — the issuer of your
  partners' client certificates. **Import Certificate** or **Import from URL**.

For an **outbound** mTLS sender you need:

- a **key pair** in *Local Key Pairs* — the client certificate you present.
- the peer's **server CA** in *Additional Trusted Certificates*, unless it
  chains to a public root already in the native store.

The UI parses the PEM, tells you how many certificates it found, previews
subject/issuer/validity/fingerprint, and lets you pick and alias each one.
**Edit Alias** renames afterwards; aliases are what channels reference, so get
them right before wiring channels up.

Everything lands in **PostgreSQL** by default (`OIE_TLS_PLUGIN_PERSISTENCE_BACKEND`),
which is why the database backup is the one that matters — see
[`web-ui.md`](web-ui.md) for the filesystem alternative.

### 2. Turn it on for a connector

Open the channel, pick the source or destination connector, and use the **TLS
Settings** tab.

Note which client you are in:

- **Swing Administrator** — TLS Manager ships its own tab here
  (`TLSConnectorPropertiesPlugin`, a Swing `ConnectorPropertiesPlugin`).
- **Web console** — TLS Manager ships *no* console plugin, so there was no tab
  at all. This repo adds one: see
  [`web-ui.md`](web-ui.md#a-tls-settings-tab-in-the-console). It writes the same
  `TLSConnectorProperties` entry, so the two clients read each other's work and
  channel XML round-trips either way.

Both attach to the same six connectors: HTTP, TCP and Web Service, listener and
sender. Anything else (Channel Reader, File Reader, JavaScript Writer…) has no
TLS tab because TLS means nothing there.

**Inbound (HTTP/WS/TCP Listener) — requiring a client certificate:**

| Setting | Value |
| --- | --- |
| Enable TLS Manager | on |
| Server certificate alias | the key pair from step 1 |
| Client auth mode | **Required** (`Requested` accepts anonymous callers too) |
| Trusted server certificates | the client CA alias |
| Trust system truststore | off, unless clients chain to a public root |
| CRL / OCSP mode | `Hard Fail` is the default and the right one |
| Subject DN validation | `Exact` or `Partial` + filter, to pin *which* client |

Client auth mode is the switch that makes it mutual TLS rather than plain
HTTPS. `Required` rejects the handshake without a valid client certificate;
`Requested` asks but proceeds regardless, which is rarely what you want.

Subject DN validation is worth using: without it any certificate signed by the
trusted CA is accepted, so the CA becomes your entire authorisation boundary.

**Outbound (HTTP/WS/TCP Sender) — presenting a client certificate:**

| Setting | Value |
| --- | --- |
| Enable TLS Manager | on |
| Client certificate alias | the key pair you present |
| Server certificate validation | **on** |
| Hostname verification | **on** (default) |
| Trusted server certificates | the peer's CA alias, if not a public root |

Leaving server certificate validation or hostname verification off turns the
connection into encryption without authentication — it stops nobody.

There is a **connection tester** in the plugin; use it before deploying, because
a TLS misconfiguration otherwise shows up as a runtime message error.

### 3. Keep it in git

These settings serialise into the connector's `<pluginProperties>` inside the
channel XML, so they round-trip through this repo's pipeline with no extra work:

```
./scripts/oie-config-pull.sh    # brings the TLS settings into config/
git diff config/
./scripts/oie-config-push.sh    # deploys them to the next environment
```

What does **not** travel in channel XML is the certificate material — that lives
in each environment's database, by alias. So provisioning is two steps per
environment: import the certificates (aliases matching), then push the channels.

`scripts/oie-tls-import.sh` does the import over the API for CI:

```
./scripts/oie-tls-import.sh keypair oie-server server.crt server.key
./scripts/oie-tls-import.sh trust   client-ca  clients-ca.crt
./scripts/oie-tls-import.sh list
```

The API is at `/api/tlsmanager` (not under `/api/extensions`): `GET`/`PUT`
`/localCertificates` and `/trustedCertificates`, plus `/systemCertificates`,
`/remoteCertificates` and the `testTcpConnection` / `testHttpsConnection` /
`testWsConnection` probes. Both write endpoints **replace the whole list**, which
is why the script reads, merges by alias, and writes back.

## Zen SSL, if you prefer it

You have licences, and it does the same job with per-connector keystore
selection and certificate expiry monitoring in the Administrator. The one thing
to sort out first is the version gate — extension compatibility is an exact
string match, so you need a build declaring `4.6.0`. See
[`../extensions/README.md`](../extensions/README.md).

Running Zen and TLS Manager together is not a good idea: both replace the TLS
behaviour of the same HTTP/WS/TCP connectors. Pick one.

## The proxy, and what it still buys you

`compose.proxy.yaml` terminates mTLS in nginx. Even with TLS Manager installed
there is one thing only the proxy can do: **put mTLS in front of the admin API
and web console**. That listener is `MirthWebServer`, not a connector, and it
sets a keystore and no truststore, so neither plugin can make it verify client
certificates.

So the sensible split once TLS Manager is in place:

- **proxy** in front of `:8443` — protects the console, the REST API, and by
  extension your CI credentials.
- **TLS Manager** on the channels — per-partner keystores, DN pinning, CRL/OCSP,
  all chosen per connector, which a single proxy policy cannot express.

The proxy also keeps certificate renewal out of the engine (an nginx reload
rather than a keystore import) and means channel traffic inside the compose
network stays plaintext, so the engine holds no private keys for that hop.
