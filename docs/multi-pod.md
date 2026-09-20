# Running more than one engine

Three engine pods behind one address, sharing one PostgreSQL, with configuration
and deployments common to all of them: deploy on any pod and every pod runs it,
and one web console manages the lot.

OIE 4.6.0 has no clustering feature — NextGen's Advanced Clustering was a
commercial extension and nothing replaced it. What it does have is a **data model
that was built for one**: every message, connector message and statistics row
carries a `SERVER_ID`, and every queue, recovery and statistics query filters on
it. Two engines writing into the same channel's tables is not a collision, it is
the schema working as designed.

What is missing is the control plane — the part that tells the other engines a
deployment happened. That is what
[`oie-cluster`](https://github.com/gibson9583/oie-cluster) adds, and what this
document explains. It is one of this stack's 12 first-party plugins, baked into
the engine image at build time via `OIE_BUILTIN_PLUGIN_URLS` (see
`docker/Dockerfile`) rather than built from source in this repo.

```
compose.cluster.yaml        three engines and a round-robin front door, locally
deploy/k8s/                 the same shape as StatefulSets
scripts/oie-cluster-keystore.sh   the shared keystore, which comes first
```

```
                    ingress (one host)
                          |
              +-----------+-----------+
              |           |           |
          engine-0    engine-1    engine-2         engine-util
          worker      worker      worker           singleton work
              |           |           |                 |
              +-----------+-----------+-----------------+
                                |
                          PostgreSQL
                  config - messages - intent - sessions
```

## What already works across instances

Verified against the 4.6.0 jars from the [`oie-cluster`](https://github.com/gibson9583/oie-cluster)
and [`oie-node-monitor`](https://github.com/gibson9583/oie-node-monitor) repos
and the image that bakes them in, not assumed:

| | |
| --- | --- |
| **Message storage** | `D_M*`, `D_MM*` and `D_MS*` all carry `SERVER_ID`. `insertMessage`, `updateStatus`, `updateSendAttempts` and `updateErrorCode` all write it; `getUnfinishedMessages`, `getPendingMessageIds` and every `getConnectorMessagesByMetaDataIdAndStatus*` query filters on it. Each engine owns its own in-flight and queued messages and cannot disturb another's. |
| **Message ids** | `getNextMessageId` is `SELECT NEXTVAL('D_MSQ<localChannelId>')` on PostgreSQL — a real sequence, safe under concurrency. (Derby's path is not; this stack is PostgreSQL.) |
| **Statistics** | `D_MS*` is keyed `(METADATA_ID, SERVER_ID)`; `getChannelStatistics` and `resetStatistics` are per server. Two engines counting the same channel keep separate, correct counts. |
| **Configuration reads** | `ChannelController`, `CodeTemplateController` and friends read through `com.mirth.connect.server.controllers.Cache`, which compares a *revision map* from the database on access and reloads what changed. A channel saved on one engine is visible to the others without a restart. |
| **Channel tags and metadata** | `channelTags` and `channelMetadata` are rows in `CONFIGURATION`, so they are already common to every engine. |
| **Message browser** | `selectMessages` takes an *optional* `serverId` filter. Any engine can browse and filter every engine's messages, because they are all rows in the same channel tables. |
| **Admin sessions** | `MirthWebServer` reads `server.api.sessionstore`; when true it builds a Jetty `JDBCSessionDataStoreFactory` over the engine's own datasource (table from `server.api.sessionstoretable`, default `sessiondata`), and `server.api.sessioncache = none` switches the in-memory cache off. **Sessions in the database, no sticky sessions required** — this is the one piece of multi-node support the engine ships. |
| **The configuration map** | `configurationmap.location = database` makes the map load from `CONFIGURATION` instead of `appdata/configuration.properties`. |
| **Identical pods** | This image rebuilds `conf/` and `extensions/` from pristine copies every boot and installs extensions from checksum-pinned URLs, so every pod is byte-identical by construction. Half of what makes clustering painful elsewhere — drifted extension sets — cannot happen here. |

## What does not work, and why

| | |
| --- | --- |
| **Deployment does not propagate** | `EngineController.getDeployedIds()` and `ChannelController`'s `DeployedChannelCache` are in-JVM. A deploy on engine-0 changes nothing on engine-1, and there is no invalidation channel between them. This is the whole problem. |
| **Startup deploy ignores intent** | `server.startupdeploy` deploys every *enabled* channel. A pod joining later would deploy channels someone had deliberately undeployed. |
| **The global map is per JVM** | `DonkeyEngineController` clears it on engine start and nothing shares it. A channel keeping cross-message state in `globalMap` silently gets three independent copies. `globalChannelMap` likewise. |
| **Polling sources duplicate** | A File Reader, a Database Reader, or the SFTP Listener in pull mode polls on every pod. So does the Random Generator. Three pods means three reads of the same source. |
| **Scheduled extensions duplicate** | `datapruner` ships with the engine and its settings live in the shared `CONFIGURATION` table, so every pod prunes on the same cron. Git Sync would pull and commit three times, Volume Monitor would evaluate and notify three times, Sentinel would alert three times. |
| **The dashboard is local** | Both administrators show the statistics and channel states of the engine they are talking to. Three pods means three partial dashboards and no total. |
| **The keystore is per appdata** | `appdata/keystore.jks` holds the TLS certificate *and* the data-encryption secret key (`DefaultConfigurationController.configureEncryption`). A pod that generates its own cannot read what another encrypted. **This is the one mistake that loses data**, so it is first in the design below. |
| **No first-boot serialisation** | Nothing stops three engines running the schema migration simultaneously against an empty database. |
| **A dead pod strands its queue** | Queued messages belong to a `SERVER_ID`. If that server never comes back, nothing retries them. |

## The design

### 1. Identity, and the two things that must be shared

**One keystore for the cluster.** Generate it once and give every pod the same
one:

```
docker compose up -d                 # one engine, once
./scripts/oie-cluster-keystore.sh    # -> secrets/keystore.jks
```

`KEYSTORE_SOURCE=/run/secrets/keystore.jks` then has the entrypoint copy it into
`appdata/` at boot — copied rather than mounted in place because the engine
writes to its own keystore and a Secret volume is read-only. Every pod presents
the same certificate and decrypts the same data. Set `KEYSTORE_PASSWORD` once,
before the first boot, as today.

The script refuses to overwrite an existing `secrets/keystore.jks` without
`--force`, and says why: that file is very likely the key your cluster's data is
encrypted with.

**A stable, unique `server.id` per pod.** With `OIE_CLUSTER_ENABLED=true` and an
empty `appdata`, the entrypoint derives one deterministically — a UUID over
`<cluster name>/<pod name>` — so `engine-1` rescheduled onto another host is
still `engine-1` and still owns its queue.

It never *replaces* an id an engine already has: an existing `appdata/server.id`
is stamped on everything that engine has written and everything it has queued, so
the entrypoint keeps it and says so (`keeping the existing server.id in
appdata`). Deriving is for a pod starting empty, which is the Kubernetes case —
and there the derived value is the same on every boot anyway.

That, not a persistent volume, is what makes `appdata/` disposable: with the
keystore coming from a Secret and the configuration map coming from the database,
`appdata/` holds nothing a pod needs to survive with — `emptyDir` is enough, and
the extension download cache repopulates itself.

Two pods sharing a `server.id` would interleave their queues, their statistics
and their recovery, and the engine cannot tell: from its point of view they are
one server. The registry can, so the agent logs an error when another *live* node
heartbeats this node's id under a different name.

### 2. The control plane is the database

Not a message bus, not peer-to-peer gossip, not an external controller: an
**intent table**, written by whichever engine the operator used, read by all of
them. The database is already shared, already backed up, and already the thing
that must be up for the engine to run at all — and an intent row is durable, so a
pod that was down when the deploy happened converges when it returns, which a
broadcast cannot do.

No new tables either. Three key shapes in the `configuration` table, which is
already shared, already backed up with the database, and already where every
other extension in this repository keeps its state:

```
node.<serverId>      one row per engine, rewritten on each heartbeat
intent.<channelId>   what the cluster is meant to be doing with that channel
applied.<serverId>   what that engine has actually done about each of them
```

`intent.*` is the desired state of the cluster; `applied.*` is what each node has
done about it. The difference between the two is exactly what the console needs
to show and exactly what CI needs to wait on.

One key per node and one per channel, rather than one key holding all of them, so
two engines converging at the same moment cannot overwrite each other's progress
with a stale read-modify-write — each node writes only its own rows. No DDL means
no migration to own and nothing extra to back up.

**Capturing intent.** `ChannelPlugin` is the hook, and it sits on the real path:
`DonkeyEngineController$DeployTask` calls `ChannelPlugin.deploy(Channel,
ServerEventContext)` and `UndeployTask` calls `undeploy(String,
ServerEventContext)` for every deploy and undeploy, wherever it came from — REST,
the Swing Administrator, the web console, `oie-config-push.sh`, Git Sync. So the
extension sees every one of them without patching the engine and without parsing
the audit log.

**What counts as an instruction.** The hook also fires for the engine's own
housekeeping — startup deploy, and the undeploy of every channel as the engine
shuts down — and for this node's own convergence. Recording those would be
catastrophic in the second case: one pod being stopped would record "undeploy
this everywhere", and the rest of the cluster would obey.

So intent is recorded only for a deploy with a **real user id** behind it. Two
details matter and both were found by running it:

- `ServerEventContext.SYSTEM_USER_EVENT_CONTEXT` carries user id **`0`**, not
  null — it is constructed as `new ServerEventContext(Integer.valueOf(0))`. A
  null check alone lets every system deploy and undeploy through, which is
  exactly the failure above. Real user ids start at 1, so the test is `> 0`.
- The convergence loop deploys with that same system context, so its own work
  can never become a new instruction. The agent also holds the set of channel
  ids it is mid-convergence on, and a hook call for one of those writes nothing.

A redeploy of an unchanged channel *is* a real instruction, so it bumps
`deploy_seq` — that is how "redeploy everywhere" is expressed.

**Converging.** Every node runs one scheduled task, default every 5 seconds:

1. heartbeat into `oie_cluster_node`;
2. read intent rows newer than the last seen watermark;
3. subtract what is already true locally — `EngineController.getDeployedIds()` and
   `ChannelController.getDeployedChannelInfoById(id).getDeployedRevision()`;
4. apply the delta through `EngineController.deployChannels` /
   `undeployChannels` with an `ErrorTaskHandler`, exactly as Git Sync already
   applies changes through the controllers rather than the REST API;
5. write the outcome — applied, or the error — into `oie_cluster_node_channel`.

A node that cannot deploy a channel records the failure and keeps serving the
channels it does have. It does not retry in a tight loop and it does not fall out
of the cluster; the console shows `2/3 deployed` with the error against the node
that failed, which is the honest report and the one an operator can act on.

**`SERVER_STARTUP_DEPLOY=false` on every cluster member.** The agent performs the
initial deployment from intent instead. Otherwise a pod joining the cluster
deploys every enabled channel and undeploys some of them a few seconds later,
which is both visible and wrong. On first adoption the agent seeds intent from
whatever the engine currently has deployed, so switching this on changes nothing.

**Postgres `LISTEN`/`NOTIFY` is an optimisation, not the mechanism.** A 2-second
poll of a table with one row per channel is nothing, and it works when a
connection has been dropped or a pod has been asleep. Adding `NOTIFY` on intent
writes to cut latency to milliseconds is a later refinement that must not become
the only path.

### 3. Placement: everywhere, once, or pinned

Deploying everything everywhere is right for listeners and wrong for pollers. So
intent carries a placement per channel:

| | |
| --- | --- |
| `ALL` (default) | every node deploys it. Correct for HTTP, MLLP, FHIR, SFTP push — anything a load balancer spreads. |
| `SINGLETON` | exactly one node deploys it. Correct for File Reader, Database Reader, SFTP pull, Random Generator — anything that would otherwise read the same source three times. |
| `PINNED:<node>` | one named node, for a channel bound to something only one pod can reach. |

Placement lives in the intent table, is edited in the console's cluster view, and
is part of what CI can assert. It is deliberately **not** inferred from the
connector type — but the agent does inspect source connectors on deploy and warns
when a polling source is placed `ALL`, in the same spirit as
`oie-check-extensions.sh`: the failure is silent otherwise, and silent duplicate
ingestion is expensive to discover.

Two consequences to state plainly. A `SINGLETON` channel is **at most once, not
highly available** — if its node is down, it is not running until the node or the
placement moves. And a Channel Writer (the `vm` connector) can only reach a
channel deployed *in its own JVM*, so a channel that other channels dispatch into
must be `ALL`.

### 4. Two roles from one image

Rather than electing a leader for work that has no leader election of its own —
`datapruner` reads its schedule from the shared `CONFIGURATION` table and would
run on every pod that has it installed — split the deployment by role:

| role | replicas | runs |
| --- | --- | --- |
| `worker` | N | channels placed `ALL`. Admin API. No pruner, no Git Sync, no monitors. |
| `utility` | 1 | channels placed `SINGLETON`. The data pruner, Git Sync, Volume Monitor, Sentinel. Admin API like any other member. |

The mechanism is one new entrypoint variable, `OIE_DISABLE_EXTENSIONS`, removing
named directories after the `extensions/` reset:

```
worker:  OIE_DISABLE_EXTENSIONS=datapruner,gitsync,volumemonitor,sentinel
utility: OIE_DISABLE_EXTENSIONS=
```

Ten lines, and it keeps the property this image already has: what runs is a pure
function of the environment. A StatefulSet of one gives the utility pod
at-most-once scheduling without any lease logic of our own.

Leader election in the database (a lease row, or `pg_try_advisory_lock`) remains
the upgrade path if `SINGLETON` channels later need to fail over automatically.
It is phase 3, not phase 1, because the static split is comprehensible at 3am and
a lease is not.

### 5. The web console across pods

The console is a WAR served by each engine's own Jetty, so its JavaScript talks to
the engine it was loaded from and CORS never enters into it. Keep that, and make
the local engine answer for the whole cluster.

**`/api/cluster/*`, served by the extension on every node:**

```
GET  /api/cluster/status      the nodes, the intent, and each node's progress
GET  /api/cluster/dashboard   message counts per channel, summed across nodes
GET  /api/cluster/orphans     queued work owned by nodes that are gone
POST /api/cluster/placement   where a channel runs
POST /api/cluster/state       start, pause or stop a channel on every node
POST /api/cluster/redeploy    bump deploy_seq for a set of channels
POST /api/cluster/seed        take this node's deployed set as cluster intent
POST /api/cluster/nodes/_forget
POST /api/cluster/orphans/_resolve
POST /api/cluster/settings
```

One `status` response rather than three endpoints, because the three are read
together and have to agree with each other: a channel reported as converged on a
node the node list shows as dead is not a view, it is a puzzle.

Notably absent: a way to deploy. Channels are deployed the way they always were,
through the engine's own API and both administrators; a second way to deploy
would be a second thing to keep in step with the first.

Everything a *view* needs comes from the database — the registry, intent, applied
state, and `D_MS*` statistics per server id — so the aggregated dashboard needs no
node-to-node calls at all, and it stays correct while a node is down. Node-to-node
HTTP would only be needed for live-only data (thread dumps, the server log tail),
and the console reaches those per node instead.

**A console plugin** in `webadmin/`, the same shape as the existing ones — a
`plugin.json` and a plain ES module calling `platform.registerNavItem`,
`registerView` and `registerIcon`, exactly as
[`oie-volume-monitor`](https://github.com/gibson9583/oie-volume-monitor) and the
`tls-manager` overlay do. It adds:

- a **Cluster** view: one row per node (name, role, server id, version, uptime,
  heartbeat age, channels deployed, which one owns the singletons), and one row
  per channel showing `3/3` or `2/3`, expandable into per-node state and the error
  from any node that failed;
- **placement** per channel, as a dropdown — every node, one node, or pinned to a
  named one;
- a channel whose state differs between nodes shown as `STARTED / STOPPED` in
  amber, with one button to make a chosen state the cluster's;
- **message counts** summed across nodes, expandable per node — which the engine's
  own dashboard cannot show, since it reports whichever node answered;
- **queues left behind** by a node that is gone, named per channel with counts;
- channels running on this node that the cluster has no intent for, listed rather
  than touched, with the button that adopts them;
- a per-node link, when `OIE_CLUSTER_ADDRESS` gives one, for the tools that are
  genuinely per-node (Thread Viewer, the server log). One address for management,
  an escape hatch for debugging.

Authentication is the engine's own: the console's session cookie, backed by the
shared `sessiondata` table, already works against every pod. There is no second
login and no service account to manage, because there are no node-to-node calls in
the main path.

**Node health is a second view, in a second extension.**
[`oie-node-monitor`](https://github.com/gibson9583/oie-node-monitor) adds **Nodes**: per
engine, online or offline, uptime, CPU, heap, each disk it writes to, threads,
what its channels are doing, and message volume with rates and an hour of
history. It works the same way and for the same reason — each node samples
itself into the shared database, nothing calls anything — and it is separate
because it is useful on a single engine too, and because deployment convergence
and machine health are different jobs with different failure modes.

### Which node handled a message

Already answered, by the engine, in both administrators: **`SERVER_ID` is on
every message and on every connector message**, so the message browser can show
it for the message *and* for each destination independently.

In the web console it is the **Server Id** column in the message browser's column
menu — off by default, remembered per browser. The column definition renders for
the parent row (the message, so the node whose source connector received it) and
for each child row (the node that ran that destination), which is exactly the
question worth asking when a destination is queued on one node and sending on
another. There is a Server Id search field beside it, and the API's message
filter has always taken an optional `serverId`.

The gap is that it prints the id, and the id is a UUID.

The cheapest fix is to stop making it one. Nothing in the engine requires a UUID
— `getServerId()` returns a String and the column is `VARCHAR(36)` — so
`OIE_SERVER_ID_STYLE=name` has the entrypoint derive the id from the pod's name
instead:

```
OIE_SERVER_ID_STYLE=name     # oie-worker-1 rather than 0acc16de-d7e2-5...
```

The name is taken from `OIE_SERVER_ID_FROM`, else `OIE_CLUSTER_NODE_NAME`, else
the container's hostname — in that order, because in compose the hostname is the
container id and changes on every recreate, while in Kubernetes all three are the
pod name. Anything outside `[A-Za-z0-9._-]` becomes a dash and the result is cut
to the 36 characters the column holds.

Every message written from then on is stamped `oie-worker-1`, and the column
reads that, in the web console, in the Swing Administrator, and in any query
anyone writes against the message tables. No plugin, no console change, nothing
to keep in step.

Two things to know before turning it on:

- **It applies to nodes that have not started yet.** The entrypoint never
  replaces an id an engine already has, because that id owns that engine's queued
  messages and its statistics. An existing cluster keeps its UUIDs until its nodes
  are replaced with empty `appdata`.
- **Names must be unique across everything sharing the database.** The UUID style
  folds `OIE_CLUSTER_NAME` into the hash for exactly that reason; this one does
  not, so two clusters on one database need names that do not collide.

A *resolved* column — one that shows the node's friendly name whatever the id is
— is not possible from a plugin today. The console's plugin API has
`registerDashboardColumn`, `registerChannelTab`, `registerConnectorPanel` and
friends, but nothing for the message browser's columns. The honest routes are
the one above, or a `registerMessageColumn` hook contributed to
`oie-web-client`. Patching the rendered table from a plugin would work and is not
worth what it costs the next upgrade.

**The Swing Administrator does not get any of this.** It connects to one engine
and shows that engine's dashboard. It stays fully usable — deploy from it and the
cluster converges, because the intent hook is server side — but the cluster view is
console-only. Worth saying out loud, since it is an argument for the console this
stack did not have before.

### 6. CI and the push script

`oie-config-push.sh` keeps pushing to **one** address, which is now the ingress.
The deploy it triggers becomes intent and reaches every pod. What changes is the
verification: it re-reads each channel and asserts the deployed state on the
server it talked to, and `--wait-cluster[=<seconds>]` then polls
`/api/cluster/status` until every live node reports the intended `deploy_seq`:

```
==> Cluster
    waiting for 1 channel(s) to reach every node
    every live node has deployed all 1 changed channel(s)
```

A node that reports an error fails the run immediately rather than at the
timeout — waiting does not make a broken deploy work — and the message names the
channel and how many nodes failed, with the node and its error in the Cluster
view.

That keeps the property the script exists for. A green pipeline currently means
"the server I talked to deployed it"; in a cluster it has to mean "every server
did", or the pipeline is lying in a new way.

### 7. Kubernetes shape

The manifests are in [`deploy/k8s/`](../deploy/k8s/), and its README covers
applying them. The shape:

| | |
| --- | --- |
| **Workload** | `StatefulSet` for both roles — stable ordinals give stable `server.id`s. `podManagementPolicy: OrderedReady` so pod-0 runs the schema migration before the others start; that is the whole answer to the first-boot race. |
| **Services** | `oie-admin` (8443, ingress, round robin — no session affinity needed, though the utility-only extension panels then render only on the utility pod; pin to `component: utility` if that matters); `oie-channels` (one port per listener); `oie-headless` for per-node addressing. |
| **Probes** | Liveness: `GET /api/server/status`, which is unauthenticated and already the image's `HEALTHCHECK`. Readiness for the **channels** service: a TCP probe on a listener port — the port is open only when the channel is deployed, which is exactly the question being asked. Readiness for the **admin** service: the same status endpoint. |
| **Rollout** | `RollingUpdate`, one pod at a time, `maxUnavailable: 1`. A pod takes 60–90s to boot and converge, so plan for N−1 capacity during an update and keep a `PodDisruptionBudget` of `minAvailable: N-1`. |
| **Shutdown** | `terminationGracePeriodSeconds: 120`, matching the existing `stop_grace_period: 2m`. Kubernetes removes the pod from endpoints before SIGTERM; the engine stops channels and drains in-flight messages. Queued messages that remain stay owned by that `server.id` and are picked up when the pod returns. |
| **Secrets** | `keystore.jks`, `POSTGRES_PASSWORD`, `OIE_ADMIN_PASSWORD`, any `OIE_DOWNLOAD_HEADER`. |
| **Database** | One PostgreSQL; its own HA is its own problem (managed service, or Patroni). Budget `2 × DATABASE_MAX_CONNECTIONS × (workers + 1)` against `max_connections` — at the current default of 20 that is 160 connections for three workers and a utility pod, so `POSTGRES_MAX_CONNECTIONS` goes up, or `DATABASE_MAX_CONNECTIONS` comes down, or a pooler goes in between. |

Environment per pod, on top of what `compose.yaml` already sets:

```
SERVER_ID=<stable uuid per ordinal>
SERVER_STARTUP_DEPLOY=false
SESSION_STORE=true
_MP_SERVER_API_SESSIONCACHE=none
_MP_CONFIGURATIONMAP_LOCATION=database
JETTY_WORKER_INSTANCE=<ordinal>
KEYSTORE_SOURCE=/run/secrets/keystore.jks
OIE_DISABLE_EXTENSIONS=<by role>
OIE_CLUSTER_NAME=<cluster>
OIE_CLUSTER_ROLE=worker|utility
```

Three of those (`SESSION_STORE`, `_MP_SERVER_API_SESSIONCACHE`,
`_MP_CONFIGURATIONMAP_LOCATION`) need **no code at all** — `SESSION_STORE` is
already mapped and the entrypoint's `_MP_` passthrough covers the other two.
`JETTY_WORKER_INSTANCE` is read by Jetty 9.4's `DefaultSessionIdManager`, which
otherwise names every node `node0`.

### 8. Orphaned queues are the operator's decision

When a pod is scaled away permanently, its queued and in-flight messages stay in
the channel tables under its `SERVER_ID` and nothing retries them. The rows can be
reassigned to a live node, which will then recover them on its next queue rotation
— but that is a write into message history, and it is irreversible.

So it is surfaced, never inferred. `/api/cluster/orphans` reports each departed
node with **the channels named individually and the count on each**, and the
console offers the three answers: leave them (the default, which changes and
destroys nothing), reassign them to a named live node, or discard them. Nothing
carries over from the last time it was asked, and a node that is merely restarting
is not offered at all — only one whose heartbeat is older than a threshold the
operator can see.

## What is built

Both are first-party plugins built from their own repos, not from `plugins/`
in this repo — baked into the engine image at build time via
`OIE_BUILTIN_PLUGIN_URLS` (see `docker/Dockerfile`):

| | |
| --- | --- |
| [`gibson9583/oie-cluster`](https://github.com/gibson9583/oie-cluster) | the extension: the intent hook, the convergence agent, `/api/cluster/*`, and the Cluster view in the web console |
| [`gibson9583/oie-node-monitor`](https://github.com/gibson9583/oie-node-monitor) | the Nodes view: each engine's health, resource use and throughput, sampled into the shared database |
| `docker/entrypoint.sh` | `KEYSTORE_SOURCE`, `OIE_DISABLE_EXTENSIONS`, a derived `server.id`, `JETTY_WORKER_INSTANCE` |
| `scripts/oie-cluster-keystore.sh` | the shared keystore, taken off a started engine |
| `compose.cluster.yaml` + `proxy/cluster-lb.conf` | three engines and a round-robin front door, locally |
| `deploy/k8s/` | the same shape as StatefulSets, with the roles, probes and budgets |
| `scripts/oie-config-push.sh --wait-cluster` | a pipeline that goes green only when every node has it |

What is **not** built, and why it is not: automatic failover for `SINGLETON`
channels. A lease in the database would do it, and everything here is ready for
one — the placement, the registry and the staleness threshold are already
cluster-wide. It is left out because a static role is comprehensible at 3am and a
lease is not, and because at-most-once with a visible gap is a more honest
default than at-least-once nobody asked for.

## Alternatives considered

| | |
| --- | --- |
| **Fan out from CI only** | Simplest, and it is phase 1 — but it cannot see a deploy made in the console or the Administrator, which is the thing being asked for. A floor, not the answer. |
| **An external controller pod** | Watches the `EVENT` table for audited API operations and replays them to the other pods. No engine extension, redeployable on its own. Rejected: it reconstructs intent by parsing an audit log written for humans, needs its own admin credential on every pod, cannot express "deployed but stopped", and adds a second deployable whose failure is invisible. The `ChannelPlugin` hook gives the same information exactly and first hand. |
| **Active/passive** | One engine live, one warm, a lease deciding. Much less to build, and no duplicate-polling problem — but it is not load balancing, which is what was asked for, and the failover gap is the same gap `SINGLETON` channels already have. |
| **A database per pod, synchronised** | Doubles down on the wrong thing: message history fragments, the message browser stops being whole, and pruning, statistics and alerting all need reconciling. The shared schema is already server-aware; using it is less work and more correct. |
| **Commercial clustering** | Not available for OIE. Zen and the community extensions cover other NextGen gaps; nobody has published this one. |

## What was verified, and what was not

Everything in the first two tables is read out of the 4.6.0 jars. The cluster
itself was run: three engines (one utility, two workers) against one PostgreSQL
behind an nginx front door, on this repository's own `compose.cluster.yaml` —
round robin for channel traffic, pinned to the utility node for the admin
console so the utility-only extension panels always have a backend.

**Verified end to end**

- Three engines share one database, all report healthy, and each registers itself
  with its own `server.id` — the pre-existing engine keeping the id in its
  `appdata`, the two new pods deriving theirs from their names.
- `oie-config-push.sh --wait-cluster` against **one** engine deployed the channel
  there, and `waiting for 1 channel(s) to reach every node` became `every live
  node has deployed all 1 changed channel(s)`. Confirmed independently: all three
  engines answer `/channels/<id>/status` with `STARTED`, and both workers logged
  `deploying 1 channel(s) to match cluster intent`.
- A worker answers `/api/cluster/status` for the whole cluster — three nodes,
  `3/3` converged, no errors — which is the aggregation coming from the database
  rather than from calls between engines.
- Changing a channel's placement to `SINGLETON` undeployed it from both workers
  and left it on the utility node (`1/1`); changing it back to `ALL` redeployed it
  on both (`3/3`).
- `/api/cluster/dashboard` sums `D_MS` statistics per channel across nodes and
  breaks them down per node.
- Stopping a node leaves intent alone: the other two keep the channel deployed,
  and once the staleness threshold passes the stopped node is reported `live=0`
  and the channel reads `2/2` — converged across the nodes that are actually
  there.
- Restarting that node made it deploy the channel from intent, with
  `SERVER_STARTUP_DEPLOY=false`, so the cluster was the only thing that could have
  told it to.
- **Named server ids reach the message rows.** With `OIE_SERVER_ID_STYLE=name`
  and the three nodes restarted onto empty `server.id` files, traffic through the
  front door landed as:

  ```
  D_M1   (the message)          local-utility 27   local-w1 19
  D_MM1  metadata 0 = source    local-utility 27   local-w1 19
  D_MM1  metadata 1 = Dest 1    local-utility 27   local-w1 19
  ```

  So the message browser's Server Id column reads `local-w1` on the message and
  on each destination, rather than a UUID. Older messages keep the ids they were
  written with, which is the honest outcome: the column says which engine handled
  that message, and for those it was an engine with a UUID for a name.
- **Retiring an identity.** The three old ids appeared in both registries as
  offline; `/api/cluster/orphans` correctly reported nothing, because they own
  processed messages and no queued work; and `POST /nodes/_forget` on each
  extension removed them, leaving three named nodes.

**Two bugs the run found**, both now fixed and both invisible without it:

- A class implementing `ServicePlugin` *and* `ChannelPlugin` is added to the
  extension controller's list once per interface, so its whole lifecycle ran
  twice: two schedulers, two agents, two heartbeats racing to insert the same
  registry row. The hook is now its own class.
- `ServerEventContext.SYSTEM_USER_EVENT_CONTEXT` carries user id **`0`**, not
  null. The engine undeploys every channel as it shuts down, so with a null check
  alone, stopping one pod recorded "undeploy this everywhere" — and the other two
  did. The test is now a positive user id, with a shutdown flag behind it.

**Not yet verified**, and worth doing before this carries production traffic:

- **Database-backed sessions under a round-robin front door.** The engines run
  with `SESSION_STORE=true` and `server.api.sessioncache=none` and the front door
  does no affinity, but a console session was not deliberately bounced between
  nodes mid-use. Check that the `sessiondata` table is created and that clicking
  around does not log you out. Note that `oie_admin` is now pinned to the utility
  node, so this has to be exercised against the per-node ports
  (18443/18444/18445) rather than through `:8443`.
- **Encryption across the shared keystore.** Encrypt on one engine, read on
  another. Then confirm the negative — a pod with its own keystore *fails* — so
  the failure mode is understood before it is met by accident.
- **Queue ownership and adoption.** Fill a destination queue on one node, stop it,
  confirm the others leave those messages alone, and exercise
  `/api/cluster/orphans` with real queued messages rather than an empty scan.
- **A rolling restart under load**, which is where the probes, the grace period
  and the front door's passive health checks all have to be right at once.
- **Scale.** Three engines and one channel is a proof, not a load test. The
  convergence pass is a handful of indexed reads, but nobody has watched it with
  two hundred channels and six nodes.
