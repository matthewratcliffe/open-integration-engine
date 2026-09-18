# Kubernetes

Three worker engines and one utility engine against one PostgreSQL, sharing
configuration, deployments and admin sessions. The design and the reasoning are
in [`docs/multi-pod.md`](../../docs/multi-pod.md); this is the shape of it.

```
kubectl apply -f postgres.yaml        # or point DATABASE_URL at a real one
kubectl apply -f config.yaml          # after replacing the placeholder secret
kubectl apply -f engine.yaml
kubectl apply -f services.yaml
kubectl apply -f bootstrap-job.yaml
```

| | |
| --- | --- |
| `config.yaml` | everything every node must agree on, plus a Secret template |
| `engine.yaml` | the worker StatefulSet, the utility StatefulSet, headless Services |
| `services.yaml` | the admin address, the channel address, disruption budgets |
| `bootstrap-job.yaml` | rotates the admin account off `admin`/`admin`, idempotently |
| `postgres.yaml` | a database for trying this out, not for running it |

## Before the first apply

**The shared keystore.** Stand up one engine, take its keystore, and make it a
Secret. Every node needs the same one: it holds the key the engine's `Encryptor`
uses, and an engine that generates its own cannot read what another encrypted.

```
docker compose up -d
./scripts/oie-cluster-keystore.sh
kubectl create secret generic oie-keystore --from-file=keystore.jks=secrets/keystore.jks
```

**The passwords.** `config.yaml` carries a placeholder Secret so the manifests
apply as they stand; replace it before you put anything in the database.
`KEYSTORE_PASSWORD` must be the one the keystore was made with, on every node.

```
kubectl create secret generic oie-secrets \
    --from-literal=POSTGRES_PASSWORD=... \
    --from-literal=OIE_ADMIN_PASSWORD=... \
    --from-literal=KEYSTORE_PASSWORD=...
```

**The scripts the bootstrap Job runs**, which the image does not carry:

```
kubectl create configmap oie-scripts \
    --from-file=scripts/oie-api.sh \
    --from-file=scripts/oie-bootstrap-admin.sh
```

**The cluster extension.** Convergence needs it installed on every node. Pin it
in `OIE_EXTENSION_URLS` in `config.yaml` alongside the rest, or bake it into the
image; `OIE_CLUSTER_ENABLED` is already `true` there and does nothing at all
without the extension.

## What differs per pod, and why

Almost nothing. The ConfigMap holds everything the nodes must agree on, and the
StatefulSets hold the three things they must not:

| | |
| --- | --- |
| `OIE_CLUSTER_ROLE` | `worker` or `utility`. Decides which node runs the channels placed `SINGLETON`. |
| `OIE_DISABLE_EXTENSIONS` | the workers drop the data pruner, git sync, the volume monitor and Sentinel. Their settings live in the shared `configuration` table, so they cannot be told apart per node — the installation is what differs. |
| `server.id` | derived by the entrypoint from `OIE_CLUSTER_NAME` and the pod name. Stable, unique, and the thing every queued message is stamped with. |

That last one is why these are StatefulSets. Donkey scopes a queue by server id:
a node's queued messages are recovered by the node that owns them and by nothing
else. A pod that comes back with a fresh id abandons whatever it had queued —
the rows stay in the tables under an id nothing answers to. A stable pod name
means a stable id means a rescheduled pod picks its own queue back up.

It is also why `appdata` is an `emptyDir`. With the keystore in a Secret, the
configuration map in the database and the server id in the pod's name, nothing
in there has to survive the pod.

## Rolling updates

`OrderedReady` and `RollingUpdate`: one pod at a time, each taking 60–90s to
boot and converge, so plan for N−1 capacity for a few minutes. The
`PodDisruptionBudget` keeps two workers up through voluntary disruptions.

The utility node is one replica and the budget says `maxUnavailable: 0`, which
stops routine maintenance evicting it but does not stop a node drain. That is
the at-most-once guarantee being honest: while it is down, the channels placed
`SINGLETON` are not running anywhere. The cluster view says so in as many words.

## Readiness, for two different questions

The probes here ask "is the API up?", which is the right question for the admin
Service and the wrong one for channel traffic: a listener port is open only when
its channel is deployed on that pod, and a pod can serve the API perfectly while
having deployed nothing.

So if you publish channel ports through a Service, add a TCP readiness probe on
the port that matters to you:

```yaml
readinessProbe:
  tcpSocket:
    port: channel-mllp
```

Then a pod joins the channel Service when it can actually accept messages. It is
deliberately not the default, because which port matters is a property of your
channels and not of this stack.

## Reaching one node

Everything you would normally do goes through `oie-admin` — the console served
by any node answers for the whole cluster. For the per-node tools (Thread
Viewer, that node's own log), the headless Services give each pod stable DNS:

```
kubectl port-forward pod/oie-worker-1 8443:8443
```

Set `OIE_CLUSTER_ADDRESS` per pod only if you publish a real per-node address
through an ingress; the console renders it as a link, and an in-cluster DNS name
is not something a browser can open.

## Scaling

```
kubectl scale statefulset/oie-worker --replicas=5
```

A new worker registers itself, reads the cluster's intent and deploys what it
says. Nothing else is required — there is no membership list to update.

Scaling **down** leaves the removed pod's queued messages behind, owned by a
server id that no longer runs. That is not silently cleaned up: the cluster view
lists them per channel with counts, and offers to leave them, reassign them to a
live node, or mark them as errored. Check it before reusing the ordinal.
