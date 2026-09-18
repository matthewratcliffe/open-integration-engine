# Cluster — OIE engine extension

Runs several engines against one database as one cluster. A deploy made on any
node is applied on all of them; channels can be placed on every node or on
exactly one; and the web console shows every node, what each has deployed, and
the message counts summed across them.

```
./plugins/oie-cluster/build.sh
cp plugins/oie-cluster/dist/cluster-0.1.0.zip extensions/
docker compose up -d --force-recreate engine
./scripts/oie-check-extensions.sh "Cluster"
```

**Status: built, installed, and exercised end to end on a three-engine stack.**
One utility node and two workers against one PostgreSQL: a push to one engine
deployed the channel on all three, placement moved it between them, stopping a
node left intent alone, and restarting it made it converge from intent with
`SERVER_STARTUP_DEPLOY=false`. The console view is **not** yet exercised in a
browser, and neither are queue adoption with real queued messages or a
round-robin session bounce. See
[What was verified](../../docs/multi-pod.md#what-was-verified-and-what-was-not).

Installing it changes nothing. Until a node is told it belongs to a cluster it
registers itself, reports what it is running, and applies nothing:

```
OIE_CLUSTER_ENABLED=true
OIE_CLUSTER_ROLE=worker|utility
```

The design, the evidence behind it and the deployment shape are in
[`docs/multi-pod.md`](../../docs/multi-pod.md). This file is about the
extension.

## Why it is needed at all

OIE 4.6.0's schema is ready for several engines: every message, connector
message and statistics row carries a `SERVER_ID`, and every queue, recovery and
statistics query filters on it. Two engines writing into the same channel's
tables is the schema working as designed, not a collision.

What is missing is the control plane. `EngineController.getDeployedIds()` and
`ChannelController`'s deployed-channel cache are in-JVM, and there is no
invalidation between engines — so a deploy on one changes nothing on another.
This extension is that missing piece and nothing else.

## Design

| Decision | Choice | Why |
| --- | --- | --- |
| Where cluster state lives | **The `configuration` table** | The database is already shared, already backed up, and already the thing that must be up for the engine to run at all. No new tables means no DDL, no migration, nothing extra to back up, and the same storage every other extension in this repository uses. |
| How a deploy reaches other nodes | **Intent in the database, polled** | Not a broadcast: a node that was down when the deploy happened converges when it returns, which is a property no push has. The poll is a handful of indexed reads every few seconds. |
| What counts as an instruction | **A deploy with a real user id behind it** | `ChannelPlugin.deploy` fires for every deploy there is, including startup deploy and the undeploy of everything during shutdown. Recording those would mean an engine stopping undeploys every channel on every other engine — which is exactly what happened the first time this ran, because `SYSTEM_USER_EVENT_CONTEXT` carries user id **`0`**, not null. The test is `> 0`, with a shutdown flag behind it. |
| Two server classes | **Hook and service are separate classes** | `DefaultExtensionController` adds a loaded class to the list it later starts once per plugin interface it implements, so one class implementing both `ServicePlugin` and `ChannelPlugin` has its whole lifecycle run twice: two schedulers, two agents, two heartbeats racing to insert the same registry row. |
| A channel with no intent row | **Never touched** | Intent is created by a person deploying something. Until that has happened the cluster has no opinion, and "no opinion" must mean "leave it alone" — otherwise installing this extension is an act that stops channels. |
| Channel state (started / stopped) | **Converged on a change of intent, never continuously** | A reconciler comparing observed state to intent on every pass would restart a channel that stopped itself on one node — a listener port taken, a deploy script that threw — in a loop, and would undo a channel someone stopped by hand. Divergence is reported instead, with one button to make a chosen state the cluster's. |
| Placement | `ALL`, `SINGLETON`, `PINNED:<node>`, per channel | Deploying everything everywhere is right for listeners and wrong for pollers. Not inferred from the connector type: the agent warns when a polling source is placed `ALL`, and the operator decides. |
| A singleton with no live owner | **Deployed nowhere**, loudly | Falling back to "everywhere" so the channel keeps running would turn the loss of one pod into three engines polling the same directory, which is the exact failure the placement exists to prevent. |
| Which node owns singletons | **The live `utility` node** | A static role is comprehensible at 3am; a lease is not. Role comes from the environment, so it is a property of the deployment. A lease can replace this later without changing anything else. |
| Default role | `utility` | So a single engine — the stack this repository ships — runs everything exactly as it did before the extension existed. |
| Deploy API of its own | **None** | Channels are deployed the way they always were, through the engine's own API and both administrators. A second way to deploy would be a second thing to keep in step with the first. |
| Views | **Built from the database, never from peer calls** | The cluster view is correct while a node is down, there is no peer credential to distribute, and the console stays a same-origin app talking to whichever engine served it. |
| Orphaned queues | **Named, never resolved automatically** | Reassigning a message to another server writes into message history and cannot be undone. See below. |
| Wire format | Tab-separated rows in a plain `ArrayList` | Both scars, inherited from the other extensions here: XStream renders an immutable list as an undecodable `CollSer` blob and a list of maps in a shape the console's decoder cannot unpick. A stray tab inside a value would shift every later field, so control characters are stripped on the way in. |

## How a deploy travels

1. Someone deploys a channel — console, Swing Administrator, REST API, CI push,
   Git Sync. `DonkeyEngineController$DeployTask` calls `ChannelPlugin.deploy`.
2. The hook writes an intent row: channel, revision, a new `deploySeq`, who
   asked, and which node they asked. It also records *this* node as converged,
   so the node that performed the deploy does not deploy it again.
3. Every other node's agent — one scheduled task, every 5s by default — reads
   intent, compares it against `getDeployedIds()` and each channel's deployed
   revision, and deploys the difference through `EngineController`, exactly as
   Git Sync applies its changes through the controllers rather than the API.
4. Each node writes what it did into its own applied record. The console shows
   `3/3`, or `2/3` with the node that failed and its error.

Undeploy is the same in reverse. A redeploy of an unchanged channel bumps
`deploySeq`, which is how "redeploy everywhere" is expressed.

Sequence numbers are wall-clock milliseconds and are compared for **inequality**,
never for order, so two pods whose clocks disagree still converge.

## What it deliberately does not do

**Propagate a channel being started or stopped on one node.** There is no engine
hook for it, and inferring intent from observed state is how one node's fault
becomes the cluster's configuration. The console shows the divergence and offers
`STARTED` / `PAUSED` / `STOPPED` as an explicit cluster-wide instruction.

**Make singleton channels highly available.** One utility node means at most
once, with a gap while it restarts. That is stated rather than hidden.

**Share the global map.** `globalMap` and `globalChannelMap` are per JVM and
nothing here changes that: a channel keeping cross-message state in them gets
one independent copy per node.

**Deploy a channel that no node can run.** A `SINGLETON` with no live utility
node, or a `PINNED` node that is gone, is reported as having nowhere to run.

## Placement

| | |
| --- | --- |
| `ALL` (default) | every node. HTTP, MLLP, FHIR, SFTP push — anything a load balancer spreads. |
| `SINGLETON` | the live utility node. File Reader, Database Reader, SFTP pull, Random Generator — anything that would otherwise read one source once per node. |
| `PINNED:<serverId>` | one named node, for a channel bound to something only one pod can reach. |

Set it in the console's Cluster view, or over the API. One thing to keep in
mind: a Channel Writer (the `vm` connector) can only reach a channel deployed in
its own JVM, so a channel other channels dispatch into has to be `ALL`.

## Queues left behind

Donkey scopes a queue by `SERVER_ID`. When a pod is scaled away for good, the
messages it had queued belong to an id nothing answers to, and no other node
picks them up.

`GET /api/cluster/orphans` counts them — **per channel, with that channel's own
count**, never as one total — and the console offers three answers:

| | |
| --- | --- |
| **Leave them** | the default. Changes nothing. Right whenever the node might come back. |
| **Reassign to a live node** | `UPDATE` on both `D_M` and `D_MM` together, because Donkey's recovery reads them as a pair. The new owner picks them up when the channel next starts its queue, so redeploy it afterwards — the response says so. |
| **Mark as errored** | queued connector messages become `ERROR` and their messages become processed. Nothing is deleted: every message, its content and its history stay in the message browser. |

Both actions write into message history and cannot be undone, so each is taken
one channel and one node at a time, with the counts in view, and confirmed. No
bulk apply, and no choice remembered between visits.

## Storage

Three key shapes in the `configuration` table, category `Cluster`:

```
node.<serverId>      one row per engine, rewritten on each heartbeat
intent.<channelId>   what the cluster is meant to be doing with that channel
applied.<serverId>   what that engine has actually done about each of them
```

One key per node and one per channel — not one key holding all of them — so two
engines converging at the same moment cannot overwrite each other's progress
with a stale read-modify-write. Each node writes only its own rows. On
PostgreSQL `saveProperty` is a single `UPDATE` under a JVM-level statement lock
(the `vacuumConfigurationTable` path does not exist in the PostgreSQL statement
set), so a heartbeat every few seconds from a handful of nodes is not a load
worth engineering around.

## Settings

Identity comes from the environment, because a deployment that can be changed
from a web page is one a redeploy silently reverts. Behaviour comes from the
database, because every node must have the same answer.

| Environment | |
| --- | --- |
| `OIE_CLUSTER_ENABLED` | `true` to apply cluster intent. Default `false`. |
| `OIE_CLUSTER_ROLE` | `worker` or `utility`. Default `utility`, so a lone engine runs everything. |
| `OIE_CLUSTER_NAME` | names the cluster, and seeds the derived `server.id`. |
| `OIE_CLUSTER_NODE_NAME` | what the console calls this node. Defaults to the hostname. |
| `OIE_CLUSTER_ADDRESS` | an address the console can link to for per-node tools. Unset means no link rather than a guessed one. |

| Console setting | |
| --- | --- |
| Convergence interval | how often each node reads intent. Default 5s, floor 2s. Applies at each node's next restart. |
| Node staleness | how long a node may be silent before it stops counting as present. Default 60s, floored at four convergence intervals — a threshold close to the heartbeat would hand singleton ownership back and forth on a slow database write. |
| Converge channel state | whether a change of state intent is applied. Default on. |

## API

Served by every node, answering for all of them.

```
GET  /api/cluster/status      nodes, intent, and each node's progress
GET  /api/cluster/dashboard   message counts per channel, summed across nodes
GET  /api/cluster/orphans     queued work owned by nodes that are gone
POST /api/cluster/placement   {channelId, placement}
POST /api/cluster/state       {channelId, state}
POST /api/cluster/redeploy    {channelIds}
POST /api/cluster/seed        take this node's deployed set as cluster intent
POST /api/cluster/nodes/_forget?nodeId=
POST /api/cluster/orphans/_resolve  {action, serverId, channelId, targetNodeId}
POST /api/cluster/settings    {convergeIntervalSeconds, nodeStaleSeconds, ...}
```

`scripts/oie-config-push.sh --wait-cluster` uses `/status` to hold a pipeline
open until every live node has the channels it just pushed, and to fail with the
node and the error when one does not. A green pipeline should mean every server
deployed it, not just the one that answered.

## Adoption

Switching an existing engine into a cluster must not mean the cluster decides
nothing should be deployed. The first node to converge on an empty intent takes
what it is already running as the starting position, once, under a marker so it
never happens again — and a new pod joining an established cluster never seeds
anything. `POST /api/cluster/seed` does the same thing deliberately, and the
console offers it when it finds channels running here that the cluster has no
intent for.
