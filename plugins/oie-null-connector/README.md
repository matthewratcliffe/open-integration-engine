# Null Connector — OIE engine extension

A **Null Sender**: a destination connector that records every message, acknowledges it, and
delivers it nowhere.

**Status: built, installed, and exercised end to end on this stack.** A channel using it
deployed on the running engine, accepted HL7 v2 messages, returned a generated ACK, counted
them on the dashboard, and wrote nothing anywhere — see [Verified](#verified) for the
transcript.

## Why this exists

Every integration has a point where a feed has to be accepted before there is anything to
do with it:

- a partner needs an endpoint they can send to and be acknowledged by, weeks before the
  downstream system is ready;
- a feed has to keep flowing while the system behind it is migrated or down for a weekend;
- a load test should exercise the source connector, the transformer and the message store
  without writing to anything real;
- a channel is being cut over and one of its destinations has to be retired without
  disturbing the rest.

All of these are done today with a JavaScript Writer containing `return;`, or a File Writer
pointed at `/dev/null`. Both work and both lie about themselves: the dashboard says
"JavaScript Writer", the message browser shows a script or a file path, and whoever reads
the channel next has to open the destination to find out that it does nothing. A connector
called **Null Sender** says so on the dashboard.

## What it does per message

| | |
| --- | --- |
| **Recorded** | Nothing special is needed. The engine writes the connector message and its status either side of the connector, so the message appears in the message browser and counts towards the dashboard's **Sent** statistic exactly as it would for a destination that delivered it. |
| **Acknowledged** | The connector returns a response, which is what the source connector sends back when the channel's **Response** is set to this destination. Three modes: an HL7 v2 ACK built from the inbound message, a template, or nothing. |
| **Dropped** | There is no delivery step at all. The payload is read only to build the acknowledgement and to count what was discarded. |

The status is always `SENT`, and the status message records what happened —
`Discarded 144 characters; ACK AA returned`.

## Settings

| Field | Default | |
| --- | --- | --- |
| **Acknowledgement** | `HL7 ACK` | `None`, `Template` or `HL7 ACK`. Never affects whether the message is dropped; only what comes back. |
| **ACK Code** | `AA` | MSA-1: `AA`, `AE`, `AR`, or the enhanced-mode `CA`, `CE`, `CR`. Supports `${}`, so a transformer can decide it per message. |
| **ACK Text Message** | empty | MSA-3. Supports `${}`. |
| **Response Template** | empty | The response content in `Template` mode. Supports `${}`. |
| **Server Log** | off | One `INFO` line per discarded message. |

## Design

| Decision | Choice | Why |
| --- | --- | --- |
| Acknowledgement | Configured, not assumed | Discarding a message and acknowledging it are separate decisions. A channel whose source is set to `Auto-generate` already answers its sender without help from any destination, and for that channel the right setting here is `None`. A channel whose **Response** names this destination needs the ACK to come from here, and then it has to be a real one. |
| HL7 ACK | The engine's own `ACKGenerator` | The class the source connectors' `Auto-generate` setting uses, reached through the HL7 v2 data type plugin's auto-responder. So the ACK a partner receives is the one they would have received from a channel that acknowledged at the source — same control id echo, same MSH field swap, same timestamp format — rather than a second implementation of the same thing that drifts from it. |
| Default acknowledgement | `HL7 ACK` | The connector's reason for existing is usually to terminate an HL7 feed, and a default that acknowledges is more useful than one that does not. It degrades cleanly: a payload that is not HL7 v2 is still discarded and still recorded `SENT`, with the reason in the status message. |
| Failure | There isn't one | A connector that cannot fail should not invent failures. A message no ACK can be built from has still been successfully discarded, so it comes back `SENT` with an explanatory status message instead of `ERROR` or `QUEUED`. Nothing here can ever fill a destination queue or trip an alert. |
| Templates | Resolved in `send()`, not in `replaceConnectorProperties` | The engine stores a destination's **Sent** content by serialising the connector properties *after* template replacement. Resolving `${message.rawData}` in the usual place would therefore write a second copy of the message into the message store, filed under the connector whose job was to discard it. Nothing in the properties is ever replaced against the message, so the Sent content is configuration and only configuration. |
| Per-message logging | Off by default | The message store is already the record of what arrived, and a channel ending here is usually a busy one. The `ACK could not be generated` warning is logged once per channel start and at `DEBUG` after that, for the same reason. |
| Queue | Available, pointless | The standard destination queue settings are left on the panel rather than hidden, because they are the engine's, not this connector's. The queue can never engage: it only holds messages a destination returned `QUEUED` for. |
| Response validation | Not offered | `canValidateResponse()` is `false`. The response was generated locally, so validating it would only ever check this connector against itself. |
| Third-party jars | **None** | The only dependencies are the engine itself — donkey for the connector API, `mirth-server` for the ACK generator, log4j-api, and Swing. Nothing is downloaded at build time, so there is no checksum step in `build.sh`. |
| Service plugin | Ships alongside the connector | The engine deserialises channel XML through an XStream that permits nothing outside `com.mirth.connect.**`. Without registering this properties class, every channel using the connector would be stored as an `InvalidChannel` while `PUT /channels/{id}` still answered `200`. Registered on the server at startup and in the desktop Administrator at login. |
| Package | `org.openintegrationengine.connectors.nullsender` | Not `...connectors.null`, because `null` is a Java literal and cannot be a package segment — the extension's directory and jars are named `nullsender` to match. Not `com.mirth.connect.connectors.*` either, which would have been covered by the engine's built-in allowlist for free: a package is a claim about who maintains the code, and it is baked into every channel that ever uses the connector. |

## Building

```
./plugins/oie-null-connector/build.sh
cp plugins/oie-null-connector/dist/nullsender-0.1.0.zip extensions/
docker compose up -d --force-recreate engine
./scripts/oie-check-extensions.sh "Null Sender" "Null Connector Service Plugin"
```

No local JDK required: it compiles in a container against the engine jars taken straight out
of `oie/engine:4.6.0`, so it is always built against exactly the engine it will run on.

Two checks run as part of the build, both for failures this engine reports quietly or not
at all:

- **The properties round-trip through XStream.** The defaults are serialised and read back
  with the engine's own `ObjectXMLSerializer`, after the same `allowTypes()` call the
  service plugin makes at startup. A properties class the engine can write but not read is
  the `InvalidChannel` failure `scripts/oie-config-push.sh` exists to catch — this catches
  it a step earlier, without a server.
- **The metadata is well-formed XML.** Easier to get wrong than it sounds: a comment
  containing a double hyphen is illegal XML, and the launcher's answer to metadata it cannot
  parse is one `ERROR` line at startup followed by carrying on without the extension. That
  happened once while writing this, which is why the check is there.

## Verified

Against OIE 4.6.0 on this stack, with a channel whose destination was the Null Sender and an
ADT^A01 injected through `POST /channels/{id}/messages`.

**It loads.**

```
$ ./scripts/oie-check-extensions.sh "Null Sender" "Null Connector Service Plugin"
ok       Null Sender
ok       Null Connector Service Plugin
```

**The channel is readable.** Pushed through the API and read back as a `<channel>`, not an
`<invalidChannel>`, with the properties intact:

```xml
<properties class="org.openintegrationengine.connectors.nullsender.NullDispatcherProperties" version="4.6.0">
  ...
  <ackMode>HL7_ACK</ackMode>
  <ackCode>AA</ackCode>
  <ackTextMessage>Received and discarded</ackTextMessage>
  <logEachMessage>true</logEachMessage>
</properties>
```

**It acknowledges.** Sent `MSH|^~\&|SENDAPP|SENDFAC|RECVAPP|RECVFAC|...||ADT^A01|MSG00042|P|2.3`,
and the destination's response was:

```
MSH|^~\&|RECVAPP|RECVFAC|SENDAPP|SENDFAC|20260918130007||ACK|20260918130007|P|2.3
MSA|AA|MSG00042|Received and discarded
```

Applications and facilities swapped, `MSG00042` echoed into MSA-2, MSA-3 from the
configuration.

**It records.** Destination status `SENT`, `<sendAttempts>1</sendAttempts>`, and the channel
statistics moved:

```xml
<received>2</received><sent>1</sent><error>0</error><filtered>0</filtered><queued>0</queued>
```

**It drops.** The destination's **Sent** content is the serialised properties and nothing
else — no copy of the payload was written under this connector.

**Template mode.** With `ackMode` `TEMPLATE` and
`<responseTemplate>RECEIVED ${message.messageId} ON ${message.channelName}</responseTemplate>`,
the response was `RECEIVED 4 ON null-sink-test`.

**The log line.** With **Server Log** on:

```
INFO  2026-09-18 13:02:26.112 [Null Sender Process Thread on null-sink-test ..., Destination 1 (1)]
  org.openintegrationengine.connectors.nullsender.server.NullDispatcher:
  Null Sender discarded message 3 on channel 11111111-... (Destination 1):
  Discarded 144 characters; ACK AA returned
```

Note that this needs an engine image built from the current `docker/entrypoint.sh`: the
shipped `log4j2.properties` sets `rootLogger = ERROR`, and the entrypoint raises
`org.openintegrationengine` — the whole package, not just `.plugins` — to `INFO`. On an
older image the connector's line is discarded before it reaches an appender.

## Operating notes

**The message is still stored.** "Dropped" is about delivery, not about retention. The
destination's Raw and Encoded content are written by the engine according to the channel's
storage settings, exactly as for any other destination. To keep less, turn the channel's
storage down — that is a channel-level decision and this connector does not override it.
What the connector guarantees is that it adds no copy of its own.

**Set the source's Response deliberately.** If the source is already set to
`Auto-generate (...)`, the sender is acknowledged by the source and this connector's
acknowledgement goes nowhere — so set **Acknowledgement** to `None` and save the work. It is
when **Response** names this destination that the ACK mode here is what the partner
receives.

**Replacing a real destination temporarily.** Disable the real destination rather than
deleting it, add a Null Sender beside it, and the message history keeps both connectors'
rows — which is what you want when explaining afterwards where a day's messages went.

## Limitations

- **No delay, no failure injection, no sampling.** This is a sink, not a test harness. A
  connector that could be told to fail 5% of the time would be useful and is a different
  connector.
- **`HL7 ACK` mode understands HL7 v2 only**, in either ER7 or XML encoding. Anything else
  is discarded and recorded with `no ACK generated: ...` in its status message. Use
  `Template` for other formats.
- **Source connector only in the sense that there isn't one.** There is no Null Listener,
  and there is no sensible one: a source connector that receives nothing is a channel that
  is switched off.
