# SFTP connector

Two connectors in one extension:

| | |
| --- | --- |
| **SFTP Listener** (source) | **push**: the engine runs an SFTP server that partners connect to and upload into. **pull**: the engine polls a remote SFTP server on a schedule. |
| **SFTP Sender** (destination) | writes each message to a remote SFTP server as a file |

```
./plugins/oie-sftp-connector/build.sh
cp plugins/oie-sftp-connector/dist/sftp-0.1.0.zip extensions/
docker compose up -d --force-recreate engine
./scripts/oie-check-extensions.sh "SFTP Listener" "SFTP Sender" "SFTP Connector"
```

Panels are provided for both administrators: the web console at `/oie-webadmin/`
and the Swing Administrator.

## Why this exists

The built-in File Reader and File Writer already speak SFTP as a *client*, with
a username, a password and an optional key file. They cannot run a server, and
they cannot verify the far end: there is no host key policy, no `known_hosts`,
nothing to pin. This connector covers both — and puts the same client settings
behind one object shared by the pull listener and the sender, so a partner is
configured identically in both directions.

## Push: an SFTP server inside the engine

Partners connect to the engine and drop files. A message is dispatched **as each
upload completes**, so there is no polling interval to tune and no window in
which a half-written file can be read.

Completion is taken from two events, never from a timer:

- the client closing a handle it opened for writing, and
- a rename into place, which is how a well-behaved client publishes a file it
  wrote under a temporary name.

A rename only dispatches when the *source* name was one the filter rejects —
the signature of exactly that pattern. So the working configuration for a
partner whose client writes `x.hl7.tmp` and renames is a filter of `!*.tmp`,
and that single rule is what stops the same content being dispatched twice.

| Setting | |
| --- | --- |
| Listener Address / Port | `0.0.0.0:2222` by default. The engine runs unprivileged, so port 22 means publishing 2222 as 22 on the container rather than changing it here. |
| Root Directory | where uploads land; relative paths are under the engine's application data directory, which is the volume this stack persists. Clients cannot see or reach anything above it. |
| Host Key File | generated on first start and reused. A key that changed on every restart would make every client report the host identity as compromised. The default path is shared by every channel, so the engine has one identity; give a channel its own path to separate them. |
| Users | username, password, home directory, read-only, and authorized keys |
| Allow downloads | off by default: a drop box that also hands files back is an exposure nobody asked for |
| Process existing on start | picks up an upload that arrived while the channel was stopped. Only safe with an after-processing action of Move or Delete. |

Only the SFTP subsystem is enabled — no shell, no exec, no port forwarding — and
each session's filesystem is rooted at the account's directory, which is what
confines it.

**Accounts.** A user with no password refuses password authentication; a user
with no authorized key refuses public key authentication. A user with neither
cannot be logged into at all, and the channel refuses to deploy rather than
pretending to work until a partner tries to connect.

**Secrets.** Channel XML is not an encrypted store. Put the password in the
configuration map and reference it by its bare key — `${partnerPassword}`, not
`${configurationMap.partnerPassword}` — and it stays out of git and is expanded
when the channel deploys. The engine loads configuration map entries into the
template context as top-level names; the `configurationMap.` prefix is the
JavaScript API's, and as a template it silently resolves to nothing, leaving the
account with the literal text as its password.

### Source map

| Key | |
| --- | --- |
| `originalFilename` | the uploaded file's name |
| `fileDirectory` | where it landed on the engine |
| `fileSize`, `fileLastModified` | as at dispatch |
| `sftpUser` | the account that uploaded it |
| `sftpRemoteAddress` | the client's address |

A pull adds `sftpHost`, `pollId` and `pollSequenceId`, and `pollComplete` on the
last file of a poll.

## Pull: polling a remote server

The same connector, with Mode set to Pull. The schedule is the standard Polling
Settings — interval, time of day or cron — and everything below the connection
settings (filter, encoding, after-processing action) behaves exactly as it does
in push mode.

## Sender: writing to a remote server

Writes through a temporary name and renames on completion by default. The rename
is atomic on the far side, so a partner polling the directory can never pick up a
file this connector is still writing — the same discipline push mode expects of
its own clients. Appending cannot use it, and the field is disabled there.

Sessions are pooled per connection signature and reused between messages, because
an SSH handshake costs more than sending a small message. Credentials are part of
that signature, so a destination whose host or username is templated per message
gets a pool per distinct value rather than someone else's session.

## Authenticating to a remote server

Both client halves take the same settings:

| | |
| --- | --- |
| Authentication | password, public key, or key-then-password (what an OpenSSH client does by default) |
| Private key | a path on the engine, or pasted into the channel for a container with nowhere durable to keep one |
| Passphrase | if the key has one |
| Host Key | **Trust any**, **known_hosts file**, or **pinned key** |
| Advanced settings | raw JSch settings (`kex`, `server_host_key`, `cipher.s2c`…), applied last, for a server that needs an algorithm outside the defaults |

**Host keys are the part worth reading twice.** SSH has no certificate
authorities, so there is no signature to validate: the only meaningful
verification is against a key you already hold. *Trust any* encrypts the
transfer and proves nothing about who answered — it is the default because it
matches the File connector this replaces, not because it is safe. For anything
crossing a network you do not control, pin the key:

```
ssh-keyscan -t ed25519 partner.example.com
```

and paste that line into **Pinned Host Key**. A host pattern in front of the key
is ignored; the host already comes from the connector. A mismatch then fails the
connection with `HostKey has been changed`.

**Test Connection** in either panel opens a real session with the settings in
front of you and lists the configured directory, through the same code path a
running channel uses.

## Two things about the build

**Apache MINA SSHD is bundled; the client is not.** MINA is the SFTP *server* in
push mode and the only realistic option in Java. The client half deliberately
uses the engine's own `jsch-2.27.7` — already on the server classpath, and what
the built-in File connector uses for its SFTP scheme — so the client adds no jars
and behaves the way a migrated File Reader channel already behaves. MINA logs
through slf4j, and the engine ships both slf4j-api and a binding, so
authentication and key-exchange failures land in `mirth.log` on their own.

**`plugin.xml` is not decoration.** XStream refuses to instantiate any class
outside its allow list, and the engine's list covers `com.mirth.connect` only.
Without the tiny service plugin that registers this connector's package, the
engine happily *writes* a channel using these connectors and then cannot read it
back: `PUT /channels/{id}` returns `200`, the channel is stored as Mirth's
`InvalidChannel`, it never appears on the dashboard, and nothing says why. The
same registration is done client-side for the Swing Administrator, which
deserialises channel XML in its own JVM.

The alternative is `xstream.allowtypes=org.openintegrationengine.connectors.sftp.**`
in `mirth.properties`, which works, is undocumented, and is one more thing to
forget on the next environment.

## Example channel

`examples/sftp-push-example.xml` is a working push-mode channel: an SFTP server
on 2222 with one account, `!*.tmp` as the filter so a temporary-file client is
handled properly, and the file deleted once the channel has taken it. Push it
with the usual script after changing the id, the account and the password:

```
cp plugins/oie-sftp-connector/examples/sftp-push-example.xml config/channels/
./scripts/oie-config-push.sh
```

Export from an administrator rather than hand-writing the XML for anything else —
the field names have to match the Java exactly, and a channel the server cannot
read is stored as a stub rather than rejected.
