# A stack on Kubernetes

This page is for the person who runs a stack on a Kubernetes cluster and has to choose the
image, the values and the numbers behind the probes and the drain. It gives you the chart, the
plain manifests it renders to, the contract a rolling update keeps, and the lines each member's
configuration declares to receive what the chart hands it. The rest of production operation is
on the [deployment](deployment.md) page.

## The unit the cluster runs

One container is one `host` process serving the stack baked into it. Your image derives from
the official runtime image and unpacks your packages under `/stack`, one directory per
application ([deployment](deployment.md#shipping-apps)); the chart deploys that image and
nothing else. Two replicas share every member's database, so sessions, scheduled firings,
the outbox, live topics and rate budgets are already one stack's ([hosting](hosting.md)).

## Installing

The chart ships from every release as an OCI artefact, versioned with the framework:

```sh
helm install orders oci://ghcr.io/ingcreators/charts/tesseraql --version 0.19.0 \
  --set image.repository=ghcr.io/my-org/orders-stack --set image.tag=1.4.2 \
  --values values-production.yaml
```

`image.repository` is required and has no default: the runtime image alone hosts no application,
so the chart refuses to render without yours. Without Helm, apply the rendered manifests
instead: `deploy/kubernetes/tesseraql.yaml` in the framework repository is the chart's rendering
with the default values and an example image, regenerated and checked on every change. Replace
the image and apply it with `kubectl apply -f`.

## The values

| Value | Default | What it decides |
| --- | --- | --- |
| `image.repository`, `image.tag`, `image.pullPolicy`, `imagePullSecrets` | none | your derived image; the tag defaults to the chart's version |
| `replicaCount` | `2` | the pods; two and a budget of one is the smallest shape that rolls without a gap |
| `stackFile` | empty | the stack's own settings, written to `/stack/tesseraql-stack.yml` over the baked directory; a change rolls the pods |
| `env`, `envFrom.secretRefs` | empty | environment for `${VAR:default}` and `${secret.env.NAME}` in the members' configuration |
| `secretsMount.secretName`, `secretsMount.mountPath` | empty, `/run/secrets` | a Secret mounted as files, one per key, for `${secret.file.NAME}` |
| `shutdownTimeoutSeconds` | `45` | the drain bound the members declare; the grace period is this plus 15 |
| `forceOnTimeout` | `true` | `false` is an unbounded drain, and the chart refuses to render it |
| `tempStore` | `db` | where spools live, so an export one replica produced is served by any other |
| `javaToolOptions` | `-XX:MaxRAMPercentage=75.0` | the heap as a share of the container's limit; the image sets no `-Xmx` |
| `profile` | empty | `TESSERAQL_ENV`: the configuration profile every member selects; a member with no `config/env/` runs its base configuration |
| `service`, `ingress` | `ClusterIP` on 8080, off | how traffic reaches the origin; `service.nodePort` pins the port a `NodePort` publishes |
| `resources` | 500 m and 768 Mi requested, 1 Gi limit | from a measured node; size yours by the knee ([capacity](capacity.md)) |
| `probes` | the numbers below | the startup, liveness and readiness cadences |
| `pdb` | on, `minAvailable: 1` | rendered when there is more than one replica |
| `hpa` | off | CPU-based autoscaling; the in-flight count would be the honest signal and needs an adapter |
| `migrations.hook`, `migrations.members` | off | a pre-install and pre-upgrade Job running `migrate apply` per named member |
| `serviceAccount`, `podSecurityContext`, `podAnnotations`, `podLabels`, `nodeSelector`, `tolerations`, `affinity` | a non-root context, a preferred anti-affinity | the usual pod knobs; the official image runs as uid 999 |
| `nameOverride`, `fullnameOverride` | empty | the resource names |

## What the chart hands the members

The chart passes four things through the environment, because a member's configuration is the
member's and the chart does not edit it. Declare the two the runtime does not read on its own,
in `config/env/<profile>.yml` or wherever the member keeps its production settings:

```yaml
tesseraql:
  shutdown:
    timeout: ${TESSERAQL_SHUTDOWN_TIMEOUT:45s}
  temp:
    store: ${TESSERAQL_TEMP_STORE:file}
```

| Variable | Set from | Read by |
| --- | --- | --- |
| `TESSERAQL_NODE_ID` | the pod's name | the runtime directly: heartbeats, reaper verdicts and alert payloads name the pod |
| `TESSERAQL_SHUTDOWN_TIMEOUT` | `shutdownTimeoutSeconds` | `tesseraql.shutdown.timeout` through the placeholder above |
| `TESSERAQL_TEMP_STORE` | `tempStore` | `tesseraql.temp.store` through the placeholder above |
| `JAVA_TOOL_OPTIONS` | `javaToolOptions` | the JVM, before the fixed entrypoint's own flags |
| `TESSERAQL_ENV` | `profile` | the profile selection, when set |
| `TESSERAQL_SECRETS_DIR` | `secretsMount.mountPath` | `${secret.file.NAME}`, when a Secret is mounted |

The stack file is the one thing the chart writes into `/stack`: a ConfigMap mounted as a single
file over the baked directory, with `${VAR}` and `${secret.…}` resolved at boot like any
configuration. A `subPath` mount never sees a ConfigMap update, so the pod template carries a
checksum of the file and a change rolls the pods.

## The probes

| Probe | Path | Cadence | Reads as |
| --- | --- | --- | --- |
| startup | `/_tesseraql/health/live` | every 2 s, 30 failures | a 60 s boot budget; a stack with many migrations raises the threshold |
| liveness | `/_tesseraql/health/live` | every 10 s, 3 failures | the process answers; it never touches a dependency, so an outage does not restart the pod |
| readiness | `/_tesseraql/health/ready` | every 5 s, 2 failures, 1 success | the stack's roll-up: `503` while draining, `503` when every member is down, `200` with the degraded members named otherwise |

Each probe is answered from a roll-up the runtime already holds and starts the next refresh
behind the answer, so a cadence costs no probe of its own. Readiness fails only when every
member is down: two replicas share every member's database, so a readiness that failed on any
one member would empty the Service for the healthy ones.

## The drain

On `SIGTERM` the origin's readiness answers `503`, the runtime keeps serving what arrives, asks
every job run and stream to stop, waits for what is in flight under `shutdownTimeoutSeconds`,
and closes. The close after the drain is bounded too, three seconds per transport, which is why
the grace period is the bound plus 15: the process exits `143` inside it. There is no `preStop`
sleep. The idiom exists for servers that stop accepting at the signal; this one keeps answering
through the drain, so a request routed after the signal is served rather than lost. From the
signal on, every response also ends its connection: `Connection: close` on HTTP/1.1, GOAWAY
and then the close once the stream is done on HTTP/2. A client that pools connections
reconnects through the Service to a pod that stays,
so the in-flight count reaches zero within a round trip of the signal. A stop takes the whole
bound only when a request runs that long, and a rolling update takes up to the bound per pod.

`forceOnTimeout: false` is an unbounded drain, and no grace period can cover one: a stop that
waits forever meets the platform's `SIGKILL` at the grace with the requests it was waiting for
cut anyway. The chart refuses to render it and says so at `helm install`, not at two in the
morning.

## The rolling contract

A rolling update surges one pod and never drops below the count: the new pod serves before an
old one leaves. The budget keeps one pod through a node drain; the anti-affinity prefers
different nodes. What the overlap needs, the framework already arbitrates through the shared
database, each with its own integration test:

| Shared state | How two replicas agree |
| --- | --- |
| scheduled firings | one claim per firing in `tql_job_claim`; exactly one replica runs it |
| the outbox, the workflow sweep, the reaper | `SKIP LOCKED` rows, conditional writes |
| sessions | `jdbc` by default, so a sign-in survives the pod that took it |
| live topics | `pg_notify`, so a page on one replica sees a write on another |
| rate limits | `scope: cluster` makes a budget shared |
| held results | a write's `invalidates:` reaches every replica within the stamp interval |
| operational alerts | a database-wide condition pages once per cluster; a node's names the pod |

The new pod is a whole runtime from its start: its jobs, pollers and outbox work at once,
claim-arbitrated against the old pod's, and the schema it migrated at boot must stay
expand/contract for the length of the window ([deployment](deployment.md#bootstrap-and-migrations)).
The optional migration hook runs `migrate apply` per member before the upgrade proceeds, so a
failed migration fails `helm upgrade` with its message instead of crash-looping a pod.

The contract is proven, not promised. The `two-node` job of the repository's `kubernetes.yml`
workflow installs the chart with two replicas on a kind cluster with two workers, every week
and on every change under `deploy/`, and asserts each sentence as a step of its own:

- eight workers of `tesseraql bench` see no answer but a 200 through a rolling restart;
- a job every ten seconds runs once per fire time, on one replica or the other;
- a session signed in on one pod reads a browser route on the other;
- an alert raised from a dead-lettered event reaches the channel once across both pods;
- a pod deleted with a slow request in flight answers it and exits `143` inside the grace.

The probe application and the script are under `.github/kubernetes/`, and both run on a
developer's machine with the same three binaries.

## The shared install root

The chart deploys the baked shape, where a deploy is a new image and a rollout. The other
supported shape, a shared install root on a `ReadWriteMany` volume that every replica mounts and
`tesseraql deploy` writes to once, keeps working on Kubernetes ([hosting](hosting.md)); it needs
the volume, and the reconcile sweep rather than the rollout then decides when each replica
moves.

## Next

- [deployment.md](deployment.md) — the image, the health endpoints, the metrics and the alerts.
- [capacity.md](capacity.md) — sizing a node and choosing the replica count.
- [hosting.md](hosting.md) — the stack, its file, and a stack on more than one node.
