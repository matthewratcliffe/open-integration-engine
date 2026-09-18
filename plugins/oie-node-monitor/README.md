# Node Monitor — OIE engine extension

Every engine at once: online or offline, how long it has been up, CPU, heap,
each disk it writes to, threads, what its channels are doing, and how many
messages it has taken — with the rate right now and an hour of history behind
it.

```
./plugins/oie-node-monitor/build.sh
cp plugins/oie-node-monitor/dist/nodemonitor-0.1.0.zip extensions/
docker compose up -d --force-recreate engine
./scripts/oie-check-extensions.sh "Node Monitor"
```

Then **Nodes** in the web console. It starts sampling on its own — there is
nothing to switch on and nothing to configure first.

## Why it exists

The engine has no view of more than one of itself. Both administrators show the
dashboard, the statistics and the system stats of *whichever engine answered the
request*, so three engines behind one address means three partial pictures and
no way to see the fourth thing you actually want: which of them is short of heap,
which one's disk is filling, which one stopped taking messages twenty minutes
ago.

It is also useful with one engine, which is why it does not require a cluster.
`/api/system/stats` gives you free memory and one disk figure, now, with no
history and no relation to what the channels are doing. This keeps a sample every
30 seconds and draws it.

## Design

| Decision | Choice | Why |
| --- | --- | --- |
| How a node's numbers reach the others | **Each node samples itself into the shared database** | Not peer HTTP. There is no credential to distribute between nodes, no timeout to handle, and — the point — a node that has stopped answering still has a last sample and a time it was taken. The moment you most want to look at a node is after it stops responding, which is exactly when a fan-out call returns nothing. |
| Where samples live | **The `configuration` table** | Two keys per node, a current sample and a bounded history. No DDL, no migration, nothing extra to back up, and the same storage every other extension here uses. A monitoring view that needs its own datastore kept alive in order to tell you the first one is unhealthy is a poor trade. |
| History size | **Bounded by count, not age** | The cost is then fixed and knowable: 120 samples of seven numbers is a few kilobytes per node, for ever, whatever the sampling interval is set to. |
| CPU | `com.sun.management.OperatingSystemMXBean`, **asked for rather than assumed** | Per-process CPU is not in the `java.lang.management` contract. It is present on every HotSpot-derived JVM including the Temurin runtime this image ships, but the sample tests for it and loses two numbers rather than the whole sample if it is ever absent. |
| Unmeasured values | **Reported as unmeasured** | The JVM returns a negative CPU load until it has two readings to compare. The view draws an empty track and a dash, not `0%`, because `0%` is a claim. |
| Rates | **From the last two samples**, not the window | This number answers "is it moving *now*". Averaged over an hour, a feed that died ten minutes ago still looks busy. The graph beside it carries the longer view. |
| Counter resets | **Clamped to zero** | Statistics can be reset from the dashboard, which makes a delta negative. A spike in the wrong direction is worse than a flat line. |
| Alerting | **None** | This reports. Volume Monitor and Sentinel already own alerting in this stack, and a second thing emailing about the same engine is how people learn to filter both. The thresholds here colour a bar and nothing else. |
| Disk | **Per watched path, deduplicated** | In this image `appdata` and `logs` are separate Docker volumes, so they are separate numbers and "the disk is full" is usually one of them. On a plain install they are two directories on one filesystem, and showing one number twice invites someone to add them up — so identical size *and* free space collapses to one row. |
| Forgetting a node | **Only on request, only when offline** | A node that has gone quiet is the thing this view exists to show, so nothing expires on its own. Refused while it is still reporting, because the row would reappear within an interval and look like the button not working. |
| Identity | **The same `OIE_CLUSTER_*` variables the cluster extension reads** | A node is called the same thing in both views without either extension knowing the other exists. They read the same deployment, not each other's storage. |
| Plugin interfaces | **One** | A class satisfying two plugin interfaces is added to the extension controller's list once per interface, and `startPlugins()` then runs its whole lifecycle twice — two schedulers, two samplers writing one node's row. Learned from the cluster extension. |

## What a sample contains

| | |
| --- | --- |
| **Identity** | server id, node name, role, engine version, OS, architecture, JVM version |
| **Liveness** | sample timestamp, JVM uptime |
| **CPU** | process load, host load, load average |
| **Memory** | heap used / committed / max, non-heap used |
| **Threads** | live count, peak |
| **Disk** | usable and total bytes for each watched path |
| **Channels** | deployed, started, paused, stopped, other, and queued messages |
| **Messages** | cumulative received, filtered, sent and errored **for this server id** |

The message counts come from `D_MS`, which Donkey keys by
`(METADATA_ID, SERVER_ID)` — so they are this node's own work even though every
node writes into the same channel tables.

## Settings

All in the console, all shared by every node.

| | |
| --- | --- |
| Sample interval | default 30s, floored at 10s. Applies when that node restarts — the scheduler fixes its period at start. |
| History samples | default 120. At 30s that is the last hour. |
| Offline after | default 120s, floored at three intervals, so one missed sample is not "offline". |
| Heap / disk warning | default 85%. Colours a bar. Presentation only. |
| Volumes to measure | comma separated. Empty means `appdata`, `logs` and the install root. |

## API

```
GET  /api/nodemonitor/status         every node: latest sample, rates, volumes, history
POST /api/nodemonitor/_sample        sample the engine serving this request, now
POST /api/nodemonitor/nodes/_forget  remove an offline node's samples and history
POST /api/nodemonitor/settings
```

One request for the whole page, deliberately: the view refreshes on a timer, and
a page that issues one request per node gets slower as the cluster gets bigger.

`_sample` reaches only the engine that served it. Every other node is on its own
timer and nothing here can reach across.

## What it does not do

**Alert.** See the design table.

**Collect anything the JVM will not give it cheaply.** No per-channel CPU, no GC
pause histograms, no thread dumps — the Thread Viewer extension already does the
last one, per node.

**Reach another node.** Everything is read from rows other nodes wrote. A node
that has never started has no row and does not appear; a node that stopped
appears with its last sample and the time it was taken.
