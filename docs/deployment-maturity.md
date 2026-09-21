# A stack on Kubernetes: the image, the drain, the probes, the chart, the harness and the alerts

> **Status: designed 2026-09-20 (#1408); S1 shipped 2026-09-20 (#1409); S2 shipped 2026-09-21 (#1410); S3 shipped 2026-09-21 (#1411); S4 shipped 2026-09-21 (#1412).** Phase 33 of `docs/roadmap.md` owes
> "Kubernetes manifests and a Helm chart (probes, graceful drain of lanes and in-flight jobs,
> rolling-deploy guidance on top of reload safety), official container images, a
> `tesseraql bench` load harness for routes, a capacity/tuning guide, and alert routing through
> the Phase 20 channels", and Milestone M10: "two-node HA on Kubernetes: rolling deploys without
> dropped requests, exactly-once scheduled firings, shared sessions, alerts delivered." This
> record measures what of that already stands — most of the drain, the claims and the shared
> state do — and decides the rest in five slices, the user naming each: **S1** the official image
> and the stop path on every platform; **S2** the signals and the alerts; **S3** `tesseraql bench`
> and the capacity guide; **S4** the chart, the manifests and the Kubernetes page; **S5** the M10
> proof on a two-replica kind cluster.
>
> **S1** — `deploy/Dockerfile.host` (the official image: the host closure, the `tesseraql` user,
> `/stack` owned and empty, the bash health check, no application and no archive) and
> `deploy/Dockerfile` as the derived template (`BASE`, `APP_DIR`, `APP_NAME`; an unpacked
> `.tqlapp`; the optional training line); `release.yml` `host-image` (buildx, two platforms,
> three tags, `packages: write`, republish on dispatch); `ci.yml` `deploy-image` rebuilt around
> the packaged `user-admin-app` and a running container; `StackReadiness`,
> `StackRelay.memberReadiness` and `MultiAppHost.memberReadiness` (the origin roll-up, decision
> 3); `ReadinessMemo` (staleness from the attempt, row 6); `BoundedClose` (decision 4); the Kamal
> template's `stop_timeout`/`drain_timeout` and build arguments; `deployment.md`, `hosting.md`,
> the README. Two deviations from the text below: the base image declares no `VOLUME` (an
> inherited volume is where the legacy builder discards what a derived image writes beneath it;
> the derived template declares its own over the member's work directory), and the post-drain
> bound is three seconds rather than five, because four closes sit in sequence on the gateway's
> stop path and all four must fit the fifteen-second margin. Guards: `BoundedCloseTest`,
> `ReadinessMemoTest`, `StackReadinessTest`, `StackRelayTest` (two rows),
> `StackReadinessIntegrationTest`, `HealthProbeIntegrationTest` (the ten-second row),
> `WorkflowLedgerTest` (`--push` counts as a push), and the `deploy-image` job itself.
>
> **S2** — `EdgeMetrics` (the in-flight gauge by kind, the refusal counter by code through the
> meter, the lane gauges), counted at `HttpAdmission` and, for the front's `4294`/`4296`, by
> `StackRelay` through the host on the member the refusal was for; `OpsDashboard` gains 9010
> (from the held readiness, or the fresh probe inside a roll-up), 9011 (borrowers waiting at
> every sample across the alert interval), 9012 (the refusal count's rate over a closed
> interval window) and 9013 (`stopCut`, recorded by the runtime's drain or the stack's front and
> paged by one immediate sweep before the pools close); `AlertNotifySweep` claims database-wide
> codes through `tql_job_claim` with `(<app>:alert:<code>, epoch)`, names `node` and `scope`,
> and emits `ops.alertCleared`; `deploy/prometheus/tesseraql-alerts.yml`, the dashboard's
> Capacity row, `notifications.md`, `deployment.md`, the reference. Three deviations from the
> text below: the gateway's families render on each member's scrape rather than on a scrape of
> their own, because the origin has none and a refusal at the front is that member's capacity;
> the rules file carries the conditions a scrape can express — 9011, 9012, a rejecting lane, a
> scrape that vanished — while 9010 and 9013 are events rather than levels and reach an
> operator through the channel; and a lost claim is retried every tick rather than remembered,
> so the seven-day prune re-pages a condition that outlived the node that paged it. Guards:
> `AlertNotifySweepTest`, `OpsDashboardTest` (+4), `HttpAdmissionIntegrationTest` (+1),
> `MetricsEndpointIntegrationTest` (+1), `StackRelayTest` (+1), `PrometheusRulesLedgerTest`.
>
> **S3** — `BenchCommand` + `BenchHarness` in the developer CLI (closed loop by workers, open
> loop by rate with the workers as the in-flight bound, every sample kept, nearest-rank
> percentiles, refusals classified by the `TQL-RATE` code in the body, `--expect` → exit 3,
> the scrape read before and after when it can be); `BenchScenario` + `BenchScenarios`
> (`kind: bench`, the checks the linter's `BenchRules` and the verb share: `TQL-YAML-1413`
> malformed, `1414` unknown route, `1415` undeclared param, `1416` write without
> `writes: allowed`); `tesseraql-bench-v1.schema.json` shipped by `tesseraql new` and mapped in
> `.vscode/settings.json`; `TokenCommand.signInAndExchange` extracted for `--login`;
> `docs/capacity.md` published under "Running in production"; `app-layout.md`, `cli-surface.md`
> (twenty-six), CHANGELOG, `reference-cli.md` and `reference-error-codes.md` regenerated. Two
> deviations from the text above: the bearer is `--token-file`, `TESSERAQL_TOKEN` or
> `--login`, never `--token` on the command line (the repository's stance since `deploy`: a
> token in the process list is a credential nobody asked for), and `reference-yaml-surface.md`
> is unchanged because that page renders the route, job, view and shared-definition schemas
> only — suites are not on it either, and a bench scenario is documented on the capacity page.
> Guards: `BenchHarnessTest` (6), `BenchCommandTest` (8, a `com.sun.net.httpserver` stub with
> scripted latencies and a rotating refusal code), `BenchIntegrationTest` (a booted runtime with
> `maxInFlight` 4: eight workers see 4293 and two do not, and the scrape's counter moved by the
> same count), `AppLinterBenchTest` (6), `OptionSetShapeTest` and `DocumentedCommandLineTest`
> unchanged and green with the verb on the roster.
>
> **S4** — `deploy/helm/tesseraql/` (Chart.yaml with a placeholder version the release
> overrides, values.yaml, `_helpers.tpl` sharing the env, the mounts and the volumes between the
> Deployment and the migration hook, deployment, service, ingress, configmap, serviceaccount,
> pdb, hpa, migrate-job, NOTES.txt); `deploy/kubernetes/tesseraql.yaml` rendered with the example
> image `ghcr.io/example/orders-stack:1.0.0`; `.github/workflows/kubernetes.yml` (lint, the
> rendering diffed, kubeconform strict against 1.31.0, the grace arithmetic, the refused
> `forceOnTimeout: false`, the stack file / secrets / hook placement) on pull requests touching
> `deploy/helm/**` or `deploy/kubernetes/**`; the `chart` job in `release.yml` (`helm package
> --version` from the tag, `helm push` to `oci://ghcr.io/<owner>/charts`, `packages: write`,
> republish on dispatch; `WorkflowLedgerTest` counts `helm push` as a push); `NodeIdentity`
> reads `TESSERAQL_NODE_ID` when nothing is configured, so the pod's name is the node's without
> a configuration line; `docs/kubernetes.md` published; `hosting.md`'s topology row; CHANGELOG.
> Two deviations from the text above: the chart hands `tesseraql.shutdown.timeout` and
> `tesseraql.temp.store` to the members as environment variables the members' configuration
> declares placeholders for (`${TESSERAQL_SHUTDOWN_TIMEOUT:45s}`, `${TESSERAQL_TEMP_STORE:file}`),
> because the stack file carries the stack's settings and not a member's, and a chart that
> rewrote a member's configuration would own what the member owns; and no
> `helm.sh/chart` or `app.kubernetes.io/version` label, so the committed rendering does not
> change with every release. Guards: `KubernetesChartLedgerTest` (the grace = bound + 15, no
> preStop, surge one / never below, the PDB, the anti-affinity, the pod's name, the probe paths
> and numbers equal to values.yaml and the 60 s startup budget, the example image, the
> placeholder version and the release's `--version`, every top-level value named on the page),
> `NodeIdentityTest`, `WorkflowLedgerTest` (+1 rule), the `kubernetes.yml` job itself.

The runtime already stops the way an orchestrator wants: SIGTERM flips readiness to 503, keeps
serving, asks every run and every stream to stop, waits for what is in flight under a declared
bound, and closes ([runtime-replace.md](runtime-replace.md), the stack's own stop). Scheduled
firings, the outbox, the workflow sweep, rate leases, sessions, spooled exports and the result
hold's invalidation are already arbitrated through the shared database, each with its own
integration test. What does not exist is the shape that puts two of these processes behind one
Service: no image an operator can pull, no chart, no probe contract written down for a
platform, no load harness to size a node with, and an alert sweep that pages once per node.
And five of the things this record measured on the shipped deployment path were defective:
the container health check cannot pass, the baked image refuses an application that declares
a module, every platform's default stop grace but one is shorter than the drain it is supposed
to cover, the origin readiness the templates probe never consults a member, and a member's
readiness answers `DOWN` to any prober that comes less often than every three seconds — which
a Kubernetes probe does by default. None of them was visible to the CI that builds the image,
because it never runs the container, and none to the integration tests, which poll faster
than a probe.

Registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` by this record.
Site-excluded, so the prose lint does not read it; `sync-content.mjs` and `InternalDocsSyncTest`
do.

## What was measured

Measured 2026-09-20 on main `286a87c11` (0.19.0-SNAPSHOT), on a 20-CPU devcontainer with Docker
29.6.1. Rows 1-9 are **RUNs**: rows 1-3 and 9 Docker (the base image, two baked images, their
containers); rows 4-8 the developer CLI of the installed jars of this tree (`~/.m2`,
0.19.0-SNAPSHOT) hosting a scratch copy of `examples/user-admin-app` with two added public
`query-json` routes — `/slow` running `select pg_sleep(15)` and `/fast` running `select 1` —
against PostgreSQL 16 in Docker. The scripts live in the session scratchpad, not in the tree.
Rows 10-24 are readings of the code, the workflows and the records.

| # | Run or reading | Result |
| --- | --- | --- |
| 1 | RUN — `docker run eclipse-temurin:25-jre`, the base of both Dockerfiles | **No `curl`, no `wget`** (`which` finds neither; `curl: command not found`), Ubuntu 26.04.1, JDK 25.0.4, 336 MB, runs as root. Both `deploy/Dockerfile:65-66` and `Dockerfile.demo:50-51` declare `HEALTHCHECK … CMD curl -fsS http://localhost:8080/_tesseraql/health/live`. **The check can never pass**: Docker reports the container `unhealthy` after the start period, on every host, with nothing in CI to see it — the `deploy-image` job runs `routes` inside the image and never starts the server. |
| 2 | RUN — `docker build -f deploy/Dockerfile --build-arg APP_HOME=examples/user-admin-app`, then `docker run --network host` against the database | Builds in 188 s here (CI: 1 m 47 s on the latest main run, job `Deployment image` 1 m 55 s). 388 MB, 156 jars / 50 MB under `lib/`, an 11.5 MB `cds.jsa`, user `tesseraql`, 13 layers. **The container exits 2 at once: `TQL-APP-4216`** — `user-admin` declares `io.tesseraql:tesseraql-pdf` under `tesseraql.modules` and "carries none and its `work/modules` holds no jars". `.dockerignore:17` drops `**/work/`, so `hosting.md`'s operator step ("run `tesseraql modules resolve` before hosting") cannot reach the image, and the Dockerfile's `COPY ${APP_HOME} /stack/app` copies a source tree, never a package. The documented baked-image path hosts only an application that declares no module; CI's fixture (`scaffold-demo-app`) declares none. |
| 3 | RUN — the same recipe with a module-free copy of `scaffold-demo-app` plus the two probe routes, run with `--network host` against the database | Builds in 150 s (the Maven layer cached). **Ready in 2.9 s** from `docker run`, cold and warm alike (the CDS archive; the gateway answers `live` and `ready` in the same instant — there is no window in which the process answers one and not the other). Docker's health: `starting` through t=40 s, **`unhealthy` at t=60 s** (start period 30 s, three failed checks), the streak still growing at t=100 s; the check's own output is the shell's `curl: not found`. **`docker stop` with the 15 s statement in flight: returned at +11.7 s, exit 137**, the request dead at 11.6 s with no status (`HTTP 000`). **`docker stop -t 90` with the same request: returned at +15.6 s, exit 143**, the request `200` after 15.1 s, `Stack stopping … draining 1 in-flight request(s)` logged — the JVM was gone half a second after the drain. |
| 4 | RUN — `tesseraql dev --stack <scratch> --port 8091` (the folder-of-homes shape; no CDS archive) | Gateway `/_tesseraql/health/live` and `/ready` both answer at **4.5 s** from the JVM's start, after twelve Flyway migrations, the app runtime and the surface runtime (five system apps mounted). No warning logged. The member's own `/user-admin/_tesseraql/health/ready` answers `{"status":"UP"}`. |
| 5 | RUN — a closed-loop load on `/user-admin/fast` (8 s each; `workerThreads` 10, `maxInFlight` 40, pool 10) | c=1: **924 req/s**, p50 0.9 ms, p95 2.3 ms. c=10: **2,691 req/s**, p50 3.5 ms, p95 5.7 ms. c=40: 2,765 req/s, p50 14.0 ms, p95 21.6 ms, **3 × 503**. c=80: 2,729 req/s, p50 28.0 ms, p95 43.5 ms, p99 56.5 ms, **60 × 503**. Throughput saturates at ten workers — ten workers over a 3.6 ms service time is 2,750 req/s, which is Little's law, and every worker added past the pool size would wait in `connectionTimeoutMillis` — while latency grows linearly with concurrency and the `503`s begin where `maxInFlight` says they will. |
| 6 | RUN — `/user-admin/_tesseraql/health/ready` polled once about 35 s after row 5's last request, then (rounds 3 and 4) polled every 2 s through a 12 s load at c=80 and at idle gaps of 1, 2, 4, 5 and 10 s | The first poll after the quiet spell: **`{"status":"DOWN"}` 503 — with the database up and serving.** Through the load and for 8 s after it (763 refusals in the run): `UP` on every poll. Then, idle: after 1 s `UP`, after 2 s `UP`, **after 4 s `DOWN` 503, polled again at once `UP`**, after 5 s `DOWN` then `UP`, after 10 s `DOWN` then `UP`; a route request in the gap changes nothing. The memo refreshes only when a poll finds it older than its TTL (`HealthRoutes.readiness:96-107`), and the staleness rule — `DOWN` at three TTLs — reads the poller's own silence as a refresh that failed. **A readiness probe slower than one poll every three seconds — Kubernetes' default `periodSeconds` is 10 — answers `DOWN` on every probe**, and the pod never enters the Service. Not the pool: the saturation hypothesis a first reading of this row offered is refuted by round 3. |
| 7 | RUN — `docker stop` the database; probe both readiness paths; request a route; start it again | Gateway `/_tesseraql/health/ready`: **`{"status":"UP"}` 200 in 1 ms, throughout.** Member `/user-admin/_tesseraql/health/ready`: `{"status":"DOWN"}` 503 in 1.6 ms; member live 200; `/user-admin/fast` **500 after 30.01 s** (Hikari's `connectionTimeout`). Database back: the route answers 200 in 0.59 s. `deployment.md:21-27` promises that `/_tesseraql/health/ready` "probes every configured datasource live and answers 503 when one fails" — true of the member path, false of the origin path the Kamal template (`proxy.healthcheck.path`) and the Dockerfile point at: `StackRelay.java:507-511` answers the origin path from its `draining` flag alone. |
| 8 | RUN — SIGTERM with the 15 s statement in flight, 1.5 s after it started | Readiness `{"status":"DRAINING"}` 503 at **+0.5 s** after the signal; liveness 200; a request arriving mid-drain answered 200 in 5 ms; the in-flight request answered **200 after 15.03 s**; log: `Stack stopping: readiness now answers 503; draining 1 in-flight request(s) for up to PT45S`, both pools `Shutdown completed` at +14 s. A first reading of this run said the JVM then lingered for ten minutes; it had not — `/proc/<pid>` showed `State: Z`, one thread, no sockets, parent 1: **this devcontainer's PID 1 does not reap orphans, so a `kill -0` or `/proc` check outlives the JVM.** Round 3 below times the exit by the zombie transition, and the image (row 3) times it by `docker stop`'s return. |
| 9 | RUN — resident memory of row 3's container five seconds after ready (`docker stats`; the stack surface, one application, no `-Xmx`, the JVM's default 25 % of the host's 31 GiB as the heap ceiling) | **308 MiB**, idle. A quiet SIGTERM on the dev runtime (round 3, nothing in flight; exit read as the zombie transition, row 8) ended the process **515 ms** after the signal, and 513 ms after a database outage and recovery — the close after the drain is milliseconds when nothing hangs. |
| 10 | The drain as built | `HostCommand.java:85` registers `gateway::close` as the shutdown hook; `TesseraqlCli.java:230` registers `dev`'s (gateway, then the embedded database). `MultiAppGateway.close:503-531`: the reconciler closes first, `relay.beginDrain()` flips readiness (`StackRelay.java:381`), then a 50 ms loop until the relay's in-flight count is zero or the **derived bound** — the maximum of the members' `tesseraql.shutdown.timeout`, 45 s each by default (`MultiAppHost.java:586-590`) — then the front closes, `host.close()` closes every slot's runtime, the surface runtime, the stack's framework pool and the host's Vert.x (30 s cap), then the outbound client and the gateway's Vert.x (60 s cap each, `START_TIMEOUT_SECONDS`). `TesseraqlRuntime.close:2511-2570`: watcher, `jobExecutor.requestDrainStop(reason)`, `fileTransfers.requestDrainStop(reason)`, `LiveStreams.close()`, `edge.drain(bound)` (`RouteEdge.java:363-386`, waits on the edge's own in-flight count), `runtimeContext.close()`, then the tracer, the OTel SDK, the transfer service's bounded close, the heartbeats, the lanes, the tenant pools, the pools, the module loader. `forceOnTimeout: false` makes the bound `Long.MAX_VALUE` (`:2043-2047`). |
| 11 | Lanes at a stop | A `Lane` is a semaphore plus an executor with **no queue**: `tryAdmit` takes a permit or increments `rejected`, and `LaneGate:37-41` refuses at capacity before dispatch. `ExecutionLanes.close` → `Lane.close` → `executor.shutdownNow()`, reached after `edge.drain` and `runtimeContext.close`, so by then every admitted unit has finished or been force-cut with its request. "Graceful drain of lanes" needs no mechanism: a lane holds nothing a request does not. |
| 12 | The stop grace each platform grants, against the 45 s default bound | Docker `docker stop`: **10 s** then SIGKILL. Kamal 2: `stop_timeout` "default is the drain_timeout for non-proxied roles and **10s (Docker default) for proxied roles**"; `drain_timeout` 30; `deploy_timeout` 30 is the *readiness* wait. `deploy/kamal/deploy.yml` declares a proxied role and sets neither, and `deployment.md:94` names `deploy_timeout` as the knob to lengthen — the wrong key. Kubernetes: `terminationGracePeriodSeconds` **30 s** by default, and "the Pod's termination grace period countdown begins before the PreStop hook is executed", so a `preStop` sleep spends the same budget. WinSW: `<stoptimeout>60 sec</stoptimeout>` (`deploy/windows/tesseraql-host-service.xml`) — **the only shipped platform whose grace exceeds the bound.** |
| 13 | What CI proves about the stop path today | `ci.yml:150-172` (dist smoke): `kill $DEV_PID`, `wait`, assert `Stack stopping` in the log — 43 s for the whole step on the latest main run, so the dev CLI's exit is prompt there. `jpackage.yml:412-425`: WinSW `stopwait` then the same line. **Nothing stops the container image**, nothing runs its `HEALTHCHECK`, and nothing observes the exit code or how long the exit took. |
| 14 | The images that exist | `deploy/Dockerfile`: a template each deployment builds from **the framework's sources** (multi-stage `./mvnw … -pl tesseraql-host -am install`), the app COPYed in, a CDS archive trained on it, `VOLUME /stack/app/work`, `ENTRYPOINT java -cp lib/* … TesseraqlHostCli`, `CMD host --stack /stack --port 8080`, no heap flag (the JVM's container default: 25 % of the limit). `Dockerfile.demo`: the developer CLI with an embedded database, published as `ghcr.io/ingcreators/tesseraql-demo:{version,latest}` by `release.yml:253-297` (`packages: write`, rebuilt from the tag). The Windows zip. **No runtime image is published**: the roadmap says so twice (Phase 35: "the runtime image (Tier 3 item 6 below) remain open"; Phase 38 Tier 3 item 6 is the *CLI* image "for CI/ephemeral use"). |
| 15 | The two readiness answers | The **member**'s `/_tesseraql/health/ready` is a memoised roll-up (`HealthRoutes`, `OpsDashboard.health:260-281`): `DOWN` when the datasource probe fails or a contributor throws, `WARN` when alerts are raised, else `UP`; refreshed behind the answer at most once per `tesseraql.diagnostics.readinessTtl` (1 s), stale — and therefore `DOWN` — after three TTLs without a refresh (`STALE_AFTER_TTLS`). The **gateway**'s is the `draining` flag (`StackRelay.java:501-515`). `hosting.md:321-322`: "`/_tesseraql/health/live` and `/ready` stay the gateway's own answer, so a load balancer's probe never depends on [the surface runtime]" — nor, today, on any member. |
| 16 | Alerts today | `tesseraql.notifications.alerts.channel` names a Phase 20 channel (mail, webhook, inbox); three event kinds ride the outbox: `ops.jobFailure` (`TesseraqlRuntime.java:1330-1343`, the executor's hook), `ops.jobSla` (`:1682-1694`; `JobSlaSweeper` "deduplicated cluster-wide through the same claim table"), `ops.alert` (`AlertNotifySweep`, every `checkInterval` 60 s, **deduplicated per node in memory** — `notified`, a `Set<String>` of codes). The dashboard raises nine codes (`OpsDashboard.alerts:308-384`): 9001 trace error rate, 9002 lane saturation, 9003 slow spans, 9004 batch failure rate, 9005 pinning, 9006 outbox dead letters, 9007 poll source, 9008 queue dead letters, 9009 calendar fail-open. **9004, 9006 and 9008 read the shared database, so N nodes raise them N times**, and 9001/9002/9003/9005 are node-local with no node in the payload. No alert for a member `DOWN`, for a saturated pool, for admission refusals, or for a stop that cut requests at the bound (`MultiAppGateway.java:525`, `RouteEdge.java:386`: a `WARN` line each). |
| 17 | The scrape | `GET /_tesseraql/metrics` (`OperationsRoutes.java:277-295`): the meter's counters and fixed-bucket histograms (5 ms … 10 s, `AggregatingMeter.BUCKET_BOUNDS_MILLIS`) — routes, jobs, cache, egress, credential throttle — poll-source gauges, and `RuntimeMetrics`: heap, threads, GC, `tesseraql_pool_connections_{active,idle,total}`, `tesseraql_pool_threads_awaiting`. **No in-flight or permit gauge, no refusal counter**: `HttpAdmission.java:200` logs a `WARN` on 4293/4295 and `StackRelay` on 4294/4296, and the lane counters reach only the ops dashboard. `deploy/grafana/tesseraql-dashboard.json` has five route panels and no alert rules. |
| 18 | What every node runs beside its requests (`Schedules.every`, ten sweeps) | `system.alerts.notifier`, `system.attachments.scan`, `system.outbox.dispatcher`, `system.queue.poll`, `tql.job.reaper`, `tql.job.sla`, `tql.retention`, `tql.transfers.retention`, `tql.transfers.review`, `tql.workflow.sweep`. Arbitrated across nodes: scheduled firings and the workflow sweep (`tql_job_claim`, `JobRepository.java:131-155`), SLA alerts (the same table), outbox delivery (`FOR UPDATE SKIP LOCKED`, five-minute abandoned reclaim, `JdbcOutboxStore.java:119-125`), the reaper (conditional write, "one write and one winner"), attachment scans (CAS claims). **Not arbitrated: the alerts notifier** (row 16). |
| 19 | Multi-node behaviour already pinned by integration tests (all Testcontainers PostgreSQL, two runtimes on one database) | `JobClaimIntegrationTest` (exactly one node runs a firing), `SharedSessionIntegrationTest` (`sessions.store: jdbc` — the default since `TesseraqlRuntime.java:788`), `CrossNodeLiveViewIntegrationTest` (`pg_notify` topics), `ClusterRateLimitIntegrationTest`, `SharedTempStoreIntegrationTest` (`temp.store: db`), `ResultHoldIntegrationTest` (a peer follows within the 5 s stamp), `JobOwnershipIntegrationTest` (heartbeat and reaper), `MultiAppReplaceIntegrationTest` (the drain from both directions, readiness 503 during it). Three of M10's four sentences have a JUnit proof; "on Kubernetes, rolling" and "alerts delivered" have none. |
| 20 | How an application reaches a host, and where its modules are | `AppModules.load:47-63`: **when `.tesseraql/modules/` exists it is the application's module set and `work/modules` is not consulted** (`module-channel.md:216`); `tesseraql package` resolves the lock and bundles that directory into the `.tqlapp`; a folder of application homes hosts identically to an install root (`hosting.md:19-25`); `deploy` refuses a directory with no `catalog.json` (`TQL-UPGRADE-4092`), so **no verb creates an install root from a package** — unpacking the archive into `/stack/<name>/` is the whole recipe, and the runtime reads the bundled set. |
| 21 | Configuration a chart has to reach | `${VAR:default}` in every config file; `${secret.env.NAME}`; `${secret.file.NAME}` reads `/run/secrets/NAME` (or `TESSERAQL_SECRETS_DIR`), "the Kubernetes/Docker secrets mount convention" (`FileSecretResolver`); `TESSERAQL_ENV` selects `config/env/<profile>.yml`; `tesseraql-stack.yml` in the stack directory, resolved through the same placeholders; `tesseraql.batch.nodeId` (default hostname + PID, `NodeIdentity`) names the node in heartbeats and reaper verdicts; `JAVA_TOOL_OPTIONS` is honoured by the fixed `ENTRYPOINT` (row 4's log opens with "Picked up JAVA_TOOL_OPTIONS"). |
| 22 | The CLI surface a harness joins | 25 developer verbs (`TesseraqlCli` `subcommands`), 10 on the host roster (`reference-cli.md`, "The deployment roster"); exit code **3** already means "the command ran and a policy gate said no" (`test --fail-on-regression`, `job run` filtered); `symbols` prints every route as `{id, source, method, path, recipe}`; the declarative suites (`tests/*.yml`) are SQL cases with `params`, never HTTP requests, so they cannot be replayed as load; `token --app` mints a bearer from the app's own secret. |
| 23 | Kubernetes and the runner, read once | Rolling update defaults `maxSurge` 25 % / `maxUnavailable` 25 %; a `PodDisruptionBudget` covers voluntary disruptions (drains) and not node failure; endpoint removal and SIGTERM are concurrent, so a request already routed can arrive after the signal — which the relay serves (row 8). GitHub's `ubuntu-24.04` runner image ships **kubectl 1.37.0, helm 3.21.4, kind 0.33.0, docker 28.0.4** — a kind cluster needs no install step. |
| 24 | Free numbers and inherited positions | `TQL-OPS-9001`-`9009` are used, **9010 onward free**; `TQL-RATE-4291`-`4296` used. Phase 61's positions stand and are not reopened here: no node roles, no placement, no fleet registry, no pull-based deploy; "multi-node coordination is the orchestrator's job" (`runtime-replace.md`, deliberately not in that design). Phase 45 was sequenced before this phase for the health and metrics signals; both shipped. |

Three rounds, because two first readings were wrong and this record says so: row 8's
"ten-minute linger" was a zombie the container's init never reaped, and row 6's "the pool is
the probe" was refuted by polling through a load. What survived is worse than either — a
readiness that answers `DOWN` to any prober slower than three seconds — and it was found only
by measuring the idle gaps a probe actually has.

## The mechanism

One container runs one stack — `tesseraql host --stack /stack --port 8080`, exactly the shipped
image's `CMD` — and a Deployment runs two of them behind a Service; the framework database is
the only thing they share, and it already arbitrates everything two hosts contend for. An
operator's image is two lines on the official one. The chart writes the probes and the grace
period from numbers the runtime already declares, the harness measures a node the way row 5
did, and the alert sweep learns to page once per condition instead of once per node.

```
                 ingress (TLS, HSTS, body limits — the edge contract, deployment.md)
                                     │
                          Service — readiness-gated endpoints
               ┌─────────────────────┴─────────────────────┐
     pod A  tesseraql host :8080                 pod B  tesseraql host :8080
            ├ gateway   /_tesseraql/health/{live,ready}    ├ gateway            Deployment: 2 replicas,
            ├ surface runtime (sign-in, portal, ops)       ├ surface runtime    maxSurge 1 / maxUnavailable 0,
            └ app runtime(s), each from /stack/<name>/     └ app runtime(s)     PDB minAvailable 1, anti-affinity
               ▲ image = FROM ghcr.io/ingcreators/tesseraql-host:<v> + the unpacked .tqlapp
               └─────────────────────┬─────────────────────┘
     PostgreSQL: sessions · tql_job_claim (firings, workflow sweep, SLA, alerts) · outbox · rate leases
                 · tql_temp_spool · tql_catalog_version · pg_notify (live topics)

     SIGTERM ──▶ readiness 503 ──▶ in-flight drained (≤ bound) ──▶ runtimes close ──▶ exit 143
     terminationGracePeriodSeconds = bound + margin, derived by the chart from tesseraql.shutdown.timeout
```

## The decisions

Each is recommended. The direction — the roadmap's sentence and M10 — is the user's; these are
the shape it takes against what rows 1 through 24 found.

### 1 — The unit Kubernetes runs is the stack image; the application rides a derived image

**Recommended: one container = one `host` process serving the stack baked into it; a deployment
builds its image `FROM` the official runtime image and unpacks its `.tqlapp` packages into
`/stack/`; the chart deploys that image.** This is `hosting.md`'s "baked image" topology
("deploying is building a new image and rolling the nodes — your orchestrator's job") stated as
the Kubernetes default, and it is what rows 14 and 20 make cheap: the unpacked archive carries
its bundled modules, so `TQL-APP-4216` cannot fire, and a folder of homes needs no catalogue.

*Not recommended, and why.* A **shared install root on a ReadWriteMany volume** (the other
supported shape) keeps `tesseraql deploy` as the pen, but every managed Kubernetes offering
prices RWX storage as the exception, the reconcile sweep then decides when each replica moves,
and the rolling update — the thing the orchestrator is good at — no longer carries the deploy.
It stays supported and documented as the alternative for fleets that already have the volume.
An **init container fetching the package** is the pull-based deploy Phase 61 parks as the last
resort. **Building the framework inside the deployment's image** (today's template) costs every
deployment a four-minute Maven build of sources it does not own, which is why the official
image exists.

### 2 — The official image is `ghcr.io/ingcreators/tesseraql-host`, and its health check works

**Recommended: `release.yml` gains a `host-image` job publishing `ghcr.io/ingcreators/tesseraql-host:{<version>,<major.minor>,latest}` from the tag**, the demo-image job's shape
(`packages: write`, rebuilt from the tag, `workflow_dispatch` republish), with:

- **Contents**: `tesseraql-host`'s runtime closure under `/opt/tesseraql/lib/` (row 2: 156 jars,
  50 MB), the `tesseraql` user, `/stack` empty and owned, `VOLUME /stack/work` for the node-local
  spools, the same `ENTRYPOINT` and `CMD`. **No application, therefore no CDS archive**: the
  archive records the classpath and the trained routes, and the derived image is where both are
  known — its recipe carries the one `RUN` line that trains it (the same line as today, optional).
- **Base**: `eclipse-temurin:25-jre`, kept — the JDK the reactor builds on, an LTS, a
  distribution with a published CVE process. Distroless and Alpine are named for review and not
  recommended: the runtime spawns nothing, but the health check below and every operator's
  `docker exec` want a shell, and the base's 336 MB is the JRE plus Ubuntu, not fat to trim.
- **Two architectures**, `linux/amd64` and `linux/arm64`, built with `buildx` and a
  `FROM --platform=$BUILDPLATFORM` build stage: the Maven build runs once natively and only the
  JRE stage is per-platform, so no compilation runs under emulation. Graviton and Apple
  silicon are the reason; the cost is one manifest list.
- **The health check**: `HEALTHCHECK CMD /opt/tesseraql/bin/health`, a five-line bash script
  over `/dev/tcp` (bash is in the base, row 1) that sends `GET /_tesseraql/health/live` and
  greps the status line. **Not `curl` installed into the image**: a second HTTP client in the
  image is a second CVE surface for one probe, and Kubernetes ignores `HEALTHCHECK` anyway — the
  script exists for `docker run`, Compose and Kamal, where today's image reads `unhealthy` from
  minute two. `Dockerfile.demo` takes the same script.
- **Heap**: still no `-Xmx` in the image; the chart sets `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`
  (decision 6), because a container's limit is the chart's number, not the image's.
- **Provenance**: an `actions/attest-build-provenance` step is *filed* (below), not shipped —
  the tag path cannot be rehearsed (`docs/release-and-ci-hardening.md`), and a job that publishes
  the image must not gain a second way to fail on its first real tag.

**The repository's own `deploy/Dockerfile` becomes the derived-image template** — `FROM
ghcr.io/ingcreators/tesseraql-host:<version>`, `COPY` the unpacked package, the optional CDS
line — and `deploy/Dockerfile.host` is the image's own recipe (today's multi-stage file minus
the app). CI's `deploy-image` job builds the host recipe, then the template against it with
`--build-arg BASE=tesseraql-host:local`, using **`user-admin-app`, packaged** — the gallery app
that declares a module, so row 2's refusal becomes the job's own red variant.

*Not recommended.* Publishing the **developer CLI image** (Phase 38 Tier 3 item 6, "for CI/
ephemeral use") in the same job: it is a different artefact for a different audience, its job
would be a copy of this one, and nothing in this phase consumes it. It stays where the roadmap
put it. **Rebasing the demo image** on the host image: the demo runs `dev --embedded-db`, which
the host distribution deliberately lacks.

### 3 — The probes: liveness on `live`, readiness on `ready`, and the gateway's readiness rolls up its members

**Recommended: `startupProbe` and `livenessProbe` on `/_tesseraql/health/live`,
`readinessProbe` on `/_tesseraql/health/ready`, both at the origin — and the gateway's readiness
answer changes: it stays 503 while draining, becomes 503 when *every* member's roll-up is `DOWN`,
and otherwise answers 200 with the members that are not `UP` named in the body.** Row 7 is the
reason: the path the Kamal template and the Dockerfile already probe never looks at a member, so
a stack whose only application cannot reach its database keeps receiving traffic and answers each
request with a 500 after thirty seconds. The host holds every member's runtime in process, so the
roll-up is a map read of memos the members already keep — no HTTP hop, no new probe.

*Why "every member", not "any".* Two replicas share every member's database; when application
A's database is down it is down on both pods, so a readiness that fails on *any* `DOWN` member
empties the Service and takes application B down with it. A partial outage is a routable pod:
the body names what is degraded (`{"status":"DEGRADED","down":["a"]}`) for the operator and the
per-member path stays the per-application truth for an ingress that health-checks per prefix.

*The prober's silence is not a failed refresh (row 6).* The member's memo refreshes only when a
poll finds it due, and reads `DOWN` when it is three TTLs old — so a prober that comes every
five or ten seconds is answered `DOWN` every time, from a runtime that is entirely well, and a
pod carrying that answer never joins the Service. The rule was written for a refresh that hangs
(`http-threading.md` decision 3: "a roll-up that cannot be refreshed is not a readiness answer"),
and it is right about that case; it is wrong to count from the last *completion* when nothing
was *attempted*. **Recommended: staleness counts from the last refresh attempt — a poll after
a quiet spell answers the held status and starts a refresh; `DOWN` is the answer only when a
refresh started three TTLs ago has not landed.** No scheduled probe: the memo keeps costing
nothing while nobody asks, which is the audit-hardening bound the design was built to keep. The
gateway's roll-up (above) reads the same memos, so the correction reaches the origin path too.

*Numbers the chart writes.* `startupProbe`: period 2 s, `failureThreshold` 30 (a 60 s budget
against row 3's measured boot — a stack with more migrations raises it, and the value is a
chart value). `livenessProbe`: period 10 s, `failureThreshold` 3. `readinessProbe`: period 5 s,
`failureThreshold` 2, `successThreshold` 1 — each probe is answered from the held roll-up and
starts the refresh behind it (the correction above), so a poll never pays for a probe and a
five-second cadence is no longer a permanent `DOWN`.

### 4 — The drain on every platform: the grace exceeds the bound, and the exit is bounded too

**Recommended: the chart derives `terminationGracePeriodSeconds` from the declared drain bound
(`.Values.shutdownTimeout`, default `45s`) plus a 15 s margin for the close that follows the
drain; no `preStop` sleep; the Kamal template gains `stop_timeout` and `drain_timeout` above the
bound; `deployment.md` names the right keys; and the close that follows the drain is bounded
inside the margin, so a stop ends at `exit 143` before the grace does.** Row 12 is the reason
the grace moves: every shipped platform but WinSW kills the process before the 45 s the runtime
was told it may take. Row 10 is the reason the close is inside the decision: after the drain,
`host.close()` waits up to 30 s for its Vert.x and the gateway up to 60 s each for the client
and its own Vert.x — caps on futures that complete in milliseconds when they complete at all,
but 150 s of permitted waiting behind a 15 s margin. A close that has not completed in five
seconds will not complete; the caps shrink to that, the abandoned close is logged, and the exit
is bounded by construction rather than by luck. A drain that completes and then holds the
process past the grace would turn every rolling restart into a SIGKILL, with an exit code the
orchestrator reads as a crash.

*No `preStop` sleep.* The idiom exists because most servers stop accepting at SIGTERM while the
endpoint removal is still propagating; this one keeps accepting and answering through the drain
(row 8: a request arriving mid-drain answered in 5 ms), so the propagation window is served, not
lost. A sleep would spend grace (row 12) to buy nothing.

*Under steady traffic a stop takes the whole bound.* The gateway waits for the in-flight count
to reach zero; while an ingress keeps sending, it may not. That is the contract — nothing
accepted is cut before the bound — and the Kubernetes page says a rollout under load takes up
to the bound per pod, which `maxSurge: 1` makes a cost in time, never in capacity.

*`forceOnTimeout: false` is an unbounded drain* (row 10), and no grace period can cover it. The
chart refuses to render it (`fail` in the template with the sentence), because a value that
means "wait forever" and a platform that kills at 60 s is a contradiction the operator should
meet at `helm install`, not at 2 a.m.

### 5 — The rolling contract: surge one, never below the count, and the database already arbitrates the overlap

**Recommended: `RollingUpdate` with `maxSurge: 1` and `maxUnavailable: 0`, a
`PodDisruptionBudget` with `minAvailable: 1` when replicas exceed one, preferred pod
anti-affinity across nodes, `tesseraql.batch.nodeId` set from the pod name through the Downward
API, `tesseraql.temp.store: db` as the chart's default, and the migration discipline restated
once for Kubernetes.** The overlap window is the one `runtime-replace.md` already specifies for
a replace — "a runtime that starts is a whole runtime": the new pod's jobs, pollers and outbox
work from its start, claim-arbitrated against the old pod's, and the business schema it
migrated at boot must stay expand/contract for the length of the window. Two hosts migrating at
once serialise on Flyway's lock (`deployment.md`, multi-server notes). Sessions are shared by
default, the cache follows a write within the stamp interval, live topics ride `pg_notify`,
rate limits declare `scope: cluster` when the budget is shared — nothing new, each already
tested (row 19), each named on the Kubernetes page with its test.

*The node's name.* `NodeIdentity` defaults to hostname plus PID — a pod's hostname is its name,
so the default already reads well in a reaper verdict ("owner `orders-7c9d…` went silent"); the
chart sets `tesseraql.batch.nodeId` from `metadata.name` anyway, so the name survives a container
restart inside the pod and a payload (decision 9) names something `kubectl` can find.

*An optional migration hook.* The runtime migrates at boot, so no hook is required; the chart
offers `migrations.hook: true`, a pre-upgrade `Job` running `tesseraql-host migrate --app
/stack/<name>` per member, off by default. Its value is a failed migration failing `helm
upgrade` with the message, instead of a crash-looping pod with the message in `kubectl logs`.

*Not recommended.* A **StatefulSet**: nothing needs a stable identity or storage; the spool is
in the database. **Leader election** for the sweeps: the claim table is the election, and it
already elects per firing.

### 6 — The chart is the source; the manifests are its rendering; both ship from the tag

**Recommended: `deploy/helm/tesseraql/` (Chart.yaml, values.yaml, templates for the
Deployment, Service, optional Ingress, the stack-file ConfigMap, ServiceAccount, PDB, optional
HPA, the optional migration hook, NOTES.txt); `deploy/kubernetes/` holds `helm template`'s
rendering with the default values, committed and drift-checked; the chart is pushed as an OCI
artefact to `oci://ghcr.io/ingcreators/charts/tesseraql` by the same release job, versioned with
the framework.** Plain manifests are what an operator without Helm applies and what a reviewer
diffs; generating them from the chart keeps "manifests and a Helm chart" one artefact (AGENTS.md
rule 1: generated files are not edited).

*Values worth naming.* `image.repository` (the deployment's derived image — the chart has no
useful default and says so in NOTES), `replicaCount: 2`, `stackFile` (inline
`tesseraql-stack.yml`, mounted with `subPath` over the baked `/stack`), `env` and
`envFrom.secretRefs` (for `${VAR}` and `${secret.env.X}`), `secretsMount` (a Secret at
`/run/secrets` for `${secret.file.X}`), `shutdownTimeout`, `resources` (requests 500 m /
768 Mi, limits 1 Gi by default, from row 9), `javaToolOptions`, the probe numbers of decision
3, `pdb.minAvailable`, `hpa` (off), `migrations.hook` (off), `tempStore: db`.

*CI per pull request, on `deploy/**` and the chart's tests*: `helm lint`, `helm template`
diffed against `deploy/kubernetes/`, `kubeconform` against the pinned Kubernetes schema
version, and a `WorkflowLedgerTest` row for the new job. Cheap, container-free, and where the
render's arithmetic (grace = bound + margin) is asserted by reading the output.

### 7 — The capacity guide is arithmetic over declared numbers, with one measured example

**Recommended: `docs/capacity.md`, a published page under "Running in production", built on
three things: Little's law over the knobs the runtime declares, the signals that say which
bound is binding, and row 5 as the worked example — labelled as one box's numbers.** The
arithmetic: concurrency = throughput × latency; `workerThreads` is the ceiling on concurrent
route execution and `maximumPoolSize` its twin; `maxInFlight` is the queue you can see; a lane
is a smaller ceiling for a named class of work; `scope: cluster` makes a rate a shared budget.
The signals, per node: `tesseraql_route_duration_seconds` p95 (is the service time growing),
`tesseraql_pool_threads_awaiting` (the pool, not the database, is the constraint),
`tesseraql_http_in_flight` and `tesseraql_http_refused_total{code}` (S2 adds both — the queue's
depth and the refusals by code, which today are a `WARN` line nobody scrapes), the lane
gauges, heap. The sizing method: measure one node with `tesseraql bench` (decision 8) at
rising concurrency until the first refusal or the p95 knee, read which signal moved, raise
that knob with its twin, repeat; then replicas = peak concurrency ÷ one node's knee, plus one
for the rollout. Requests and limits follow from the knee's resident memory, not from a table.

*The example is a fixture, not a promise.* Row 5's 2,700 req/s is a `select 1` on a 20-CPU
box against a local database; the page says so in the sentence before the table, and the
guide's value is the *method* — the knee at ten workers, the linear latency, the 503s at forty
— which any reader reproduces on their own node in a minute.

### 8 — `tesseraql bench` is a developer verb that drives declared routes and answers with percentiles and refusals by code

**Recommended: `bench`, the 26th developer verb, not on the host roster; JDK-only (`HttpClient`
on virtual threads, no new dependency); closed-loop by default (`--concurrency N --duration
30s`), open-loop with `--rate`; the targets come from the application's own manifest.**

Two invocations, written inline because the verb does not exist yet and the documented-command
guard reads every fence in this tree:
`tesseraql bench --app . --url https://stack.example.com --route users.search --concurrency 20 --duration 30s`,
and `tesseraql bench --app . --url … --scenario bench/checkout.yml --format json --expect 'p95<250ms,refused<1%'`.

- **Targets**: `--route <id>` (repeatable) fills a `GET` from the route's declared inputs and
  their defaults, or a scenario file `bench/<name>.yml` lists `requests:` — route id, params,
  body, weight — and the runtime shape (`concurrency`, `duration`, `rampUp`, `rate`). A scenario
  is a document of its own kind (`kind: bench`), lint-checked like a suite: an unknown route id,
  a param the route does not declare, a write route without `writes: allowed` are `TQL-YAML`
  errors before a single request is sent.
- **Writes are refused unless the scenario says `writes: allowed`**, and a `command-json`
  target then runs with `Idempotency-Key`s the harness mints — a load run that mutates data is
  a decision the author writes down, never a flag typed in a hurry.
- **Auth**: `--token`, `TESSERAQL_TOKEN`, or `--login` through the existing `token --url`
  exchange; the bearer is scoped by the token, not by the harness.
- **Output**: a table — requests, throughput, p50/p95/p99/max, the status mix with the
  refusals **classified by their `TQL-RATE` code** read from the body (4293 the runtime's
  in-flight bound, 4295 its stream bound, 4294/4296 the front door's per-member shares, 4291 a
  route's own limiter), errors — and `--format json` for a pipeline. `--expect` turns
  thresholds into exit code **3**, the code that already means "a policy gate said no".
- **What it reads back**: with a token holding `ops.metrics.view`, one scrape before and after
  the run, printing the deltas of the capacity signals (decision 7) beside the percentiles —
  so the tuning readout is in the same terminal as the load.

*Not recommended.* **wrk/k6/Gatling** as a documented recipe instead of a verb: each is a
dependency the reader installs, none knows the routes, the inputs, the token exchange or the
refusal codes, and the M10 test (decision 10) needs a harness the runner already has. A
**histogram-bucket percentile** from the meter's fixed buckets: the harness keeps its samples
(a `long[]` per request is cheap at this scale) and reports exact quantiles.

### 9 — Alerts page once per condition, name the node when the condition is the node's, and cover the four silences

**Recommended, four parts.**

1. **Cluster-wide deduplication for database-wide conditions**: `AlertNotifySweep` claims
   `<app>:alert:<code>` per raise through `tql_job_claim` — the table and the shape
   `JobSlaSweeper` already uses — so 9004, 9006 and 9008 page once across N nodes; the claim is
   released when the condition clears on the node that holds it, so a re-raise pages again.
   Node-local conditions (9001, 9002, 9003, 9005, 9007, 9009) keep the per-node dedup and gain
   `node` in the payload (`NodeIdentity`, decision 5).
2. **Four new conditions**, minted in S2 (row 24; the record describes, the slice numbers):
   `TQL-OPS-9010` a member's readiness is `DOWN` (raised from the memo, so the same signal the
   probe sheds on reaches a human); `9011` pool saturation — `threads_awaiting` above zero
   across a whole check interval; `9012` admission refusals — the refusal counter's rate over
   the interval above a threshold (the runtime is at capacity, `deployment.md`'s own reading of
   4293); `9013` a stop cut requests at the bound (`MultiAppGateway.java:525`, `RouteEdge.java:386`)
   — enqueued on the outbox *before* the pools close, so the surviving node delivers it.
3. **`ops.alertCleared`**, one event when a code clears, same payload — a page that never
   says "over" is a page an operator learns to ignore.
4. **`deploy/prometheus/tesseraql-alerts.yml`** beside the Grafana dashboard — the same four
   conditions plus the job and poll rules `deployment.md` already spells out as PromQL — and a
   "Capacity" row on the dashboard for the S2 families. Operators who alert from metrics get the
   file; operators who do not get the channel; both get the same conditions.

*Not recommended.* **Routing alerts to a Kubernetes Event or a Slack-shaped channel type**: a
`webhook` channel already delivers to anything with a URL, and Phase 20's channels are the
roadmap's stated route.

### 10 — M10 is proven on a two-replica kind cluster, on the runner, with `bench` as the load

**Recommended: `.github/workflows/kubernetes.yml` — per-PR `helm lint`/`template`/`kubeconform`
(decision 6), and a `two-node` job on `workflow_dispatch`, weekly, and on `deploy/**` changes:
a kind cluster with two workers, PostgreSQL as a Deployment, the probe application's derived
image built from the just-built host image and `kind load`ed, `helm install` with two replicas,
then the four sentences of M10 asserted.** The `dialects.yml` precedent: gated and scheduled,
because the job is ten minutes and a real cluster, not a per-PR unit.

- **Rolling deploys without dropped requests**: `tesseraql bench` at c=8 against the NodePort
  through `kubectl rollout restart` and `rollout status`; **zero** non-200 in the report — no
  502, no connection reset, no 503 of either kind (c=8 is far under the bound on purpose).
- **Exactly-once scheduled firings**: the probe app declares a `fixedDelay: 10s` job; after the
  window, the ops API lists executions and every `fire_time` has exactly one — the claim IT's
  assertion across two pods and a rollout.
- **Shared sessions**: a session minted through pod A's `/_tesseraql/login` (port-forward by
  pod) reads a browser route on pod B.
- **Alerts delivered**: an outbox channel pointed at an unreachable host dead-letters after
  `maxAttempts`; the app's own `recipe: webhook` route is the alerts channel's target (the
  framework receives its own alert into a table); the assertion reads that table: **one row**
  for `TQL-OPS-9006` across two pods — decision 9's claim, red on the variant that skips it.
- **The stop**: `kubectl delete pod` with a slow request in flight — the request completes, the
  pod's last state is exit 143, within the derived grace.

The probe application lives under `.github/kubernetes/app/` — `user-admin-app` copied by the
workflow, plus the job, the channel and the two routes — never a gallery app edited for a test.

### 11 — The pages: `kubernetes.md` and `capacity.md` published; the deployment hub corrected

**Recommended: `docs/kubernetes.md` under "Running in production" (the chart, the values, the
probes, the grace, the rolling contract with its tests, the derived-image recipe, the shared
install root as the alternative), `docs/capacity.md` beside it (decision 7); `deployment.md`
corrects rows 7 and 12 and teaches the image; `hosting.md`'s "A stack on more than one node"
gains the Kubernetes row; `reference-cli.md` regenerates for `bench`; `notifications.md`
"Operations alerts" gains the four conditions, the node, the cleared event and the dedup
sentence.** The site's completeness guard refuses roadmap vocabulary on a published page, so
neither page says "Phase 33" or "M10"; this record does.

## What this breaks

- **The origin readiness body** gains `down` when a member is not `UP`; a client comparing the
  body to `{"status":"UP"}` byte-for-byte sees a change only while something is degraded.
- **`deploy/Dockerfile` changes meaning**: from "build the framework and bake this source tree"
  to "derive from the official image and unpack this package". A deployment that built the old
  template keeps working from the old file until it moves; the CHANGELOG says which line to
  change and why (pre-1.0, no migration steps).
- **The Dockerfiles' `HEALTHCHECK`** changes command; nothing could have depended on a check that
  never passed.
- **`AlertNotifySweep`'s dedup** changes from per-node to per-condition for three codes; an
  operator who counted on N pages for one dead letter gets one.
- **New metric families and four new `TQL-OPS` codes**; the reference regenerates.
- **The Kamal template** gains two keys; a copied `deploy.yml` without them keeps Docker's 10 s.

## Filed, not fixed

- **Provenance attestation and signing** for the image and the chart (decision 2): one step,
  once the publish job has run green on a real tag.
- **The developer CLI image** (Phase 38 Tier 3 item 6): the same job shape, when something
  consumes it.
- **Node roles, placement, a fleet view, pull-based deploy** (Phase 61): the positions stand.
- **A readiness that watches the database's replica lag or a queue depth**: not a readiness
  question; a `WARN` and an alert if ever.
- **HPA on a custom metric** (in-flight requests would be the honest one): the chart ships the
  CPU form off by default; the custom form needs an adapter the cluster may not have.
- **The mid-flight 502 window** of a replace (`runtime-replace.md` question 6): unchanged; the
  rolling proof runs idempotent reads, which is what the retry rule already covers.
- **Multi-cluster, service-mesh mTLS, a gateway API `HTTPRoute`**: the Ingress is the edge
  contract's home; a mesh is an edge with a different name.
- **A chart for the demo image**: the demo is one `docker run`; a chart for it would teach the
  wrong topology.

## The slices

### S1 — the official image, and a stop that ends on every platform

`deploy/Dockerfile.host` (the image), `deploy/Dockerfile` (the derived template), the bash
health check in both Dockerfiles plus the demo's, the `host-image` job in `release.yml`
(buildx, two platforms, three tags, `packages: write`, republish on dispatch), the `deploy-image`
CI job rebuilt around a **packaged `user-admin-app`** and a running container (readiness,
`docker stop -t 60`, exit 143 within the margin, `Stack stopping` and no `still in flight` in the
log), the gateway readiness roll-up and the staleness-from-the-attempt correction (decision 3), the
close caps shrunk inside the margin (decision 4), `stop_timeout`/`drain_timeout` in the Kamal
template, `deployment.md` and `hosting.md` corrections, CHANGELOG.

| Guard | Proves | Red on the variant |
| --- | --- | --- |
| `ci.yml` `deploy-image` (rebuilt) | the host image builds; the derived template builds against it; the container answers ready against a service database with a module-declaring app; one `HEALTHCHECK` interval reports `healthy`; `docker stop -t 60` returns exit 143 inside the margin with the drain line logged | `curl-healthcheck`, `work-modules-ignored` (row 2's refusal), `no-stop-hook`, `exit-unbounded` |
| `WorkflowLedgerTest` (+ rows) | the new job checks out, may write packages, pins its actions by SHA, declares `timeout-minutes` | the ledger's own variants |
| `MultiAppReplaceIntegrationTest` (+1 row) / `StackRelayTest` (NEW rows) | a `DOWN` member is named in the origin body; every member `DOWN` → 503; draining → 503 regardless | `readiness-ignores-members`, `any-down-is-503` |
| `HealthRoutesTest` (+ rows; an injected clock) | a poll after a 10 s gap answers the held `UP` and starts a refresh; a refresh that has not landed three TTLs after it started answers `DOWN`; the next landed refresh answers `UP` again — row 6 as an assertion | `gap-is-down` (today's rule), `hung-refresh-is-up` |
| `MultiAppGatewayStopTest` (NEW) | `close()` with a stub that never completes its Vert.x close returns within the margin and logs what it abandoned | `close-waits-forever` |

### S2 — the signals and the alerts

`tesseraql_http_in_flight{kind}`, `tesseraql_http_refused_total{code}` (member 4293/4295,
gateway 4294/4296), `tesseraql_lane_{in_use,rejected_total}{lane}`; `AlertNotifySweep` claims
database-wide codes and carries `node`; `TQL-OPS-9010`-`9013`; `ops.alertCleared`;
`deploy/prometheus/tesseraql-alerts.yml`; the Grafana capacity row; `notifications.md`,
`deployment.md` metrics table, reference regen, CHANGELOG.

| Guard | Proves | Red on the variant |
| --- | --- | --- |
| `AlertNotifySweepTest` (NEW; two sweeps over one dashboard state and one claim table) | a database-wide code pages once across two sweeps; a node-local code pages per node with its node; a cleared code emits `alertCleared` once and may page again | `per-node-dedup`, `no-node-in-payload`, `no-cleared` |
| `OpsDashboardTest` (+ rows) | 9010 from a `DOWN` memo; 9011 from `awaiting > 0` across an interval, not a sample; 9012 from the counter's rate; 9013 from a cut stop | `single-sample-9011` |
| `HttpAdmissionIntegrationTest` (+1; the held permit **is** the assertion — never close the stream) | the refusal counter increments by code and the in-flight gauge reads the held permits | `no-refusal-counter` |
| `TelemetryIntegrationTest` (+1) | the families render on the scrape with the labels named here | — |
| A rules-file test in `tesseraql-docs-reference` (NEW) | every alert rule's expression names a family the scrape renders | `rule-on-unknown-family` |

### S3 — `tesseraql bench` and the capacity guide

`BenchCommand` in the developer CLI, `kind: bench` scenario documents (schema, lint arms,
the `.vscode` mapping from `tesseraql new`), the JSON contract, `--expect` → exit 3, the
before/after scrape readout, `docs/capacity.md`, `cli-surface.md`'s row (26 verbs),
`reference-cli.md` and `reference-yaml-surface.md` regenerated, CHANGELOG.

| Guard | Proves | Red on the variant |
| --- | --- | --- |
| `BenchCommandTest` (NEW; a `com.sun.net.httpserver` stub with scripted latencies and refusals) | concurrency and duration honoured; exact percentiles from known samples; the three 503 codes classified from the body; writes refused without `writes: allowed`; `--expect` red exits 3; JSON shape | `percentile-from-buckets`, `writes-by-default`, `expect-exit-1` |
| `BenchIntegrationTest` (NEW; a booted runtime, `maxInFlight` lowered to 4) | the report shows 4293 refusals at c=8 and none at c=2 — row 5 as an assertion, in seconds | `refusals-uncounted` |
| `AppLinterBenchTest` (NEW) | unknown route, undeclared param, write without the declaration | `lint-noarm` |
| `OptionSetShapeTest` (unchanged) | the verb joins the option-set walk | — |

### S4 — the chart, the manifests and the Kubernetes page

`deploy/helm/tesseraql/`, `deploy/kubernetes/` rendered and drift-checked, the per-PR half of
`kubernetes.yml` (lint, template diff, kubeconform), the `chart` job in `release.yml` (OCI push
on the tag), `docs/kubernetes.md`, `hosting.md`'s row, CHANGELOG.

| Guard | Proves | Red on the variant |
| --- | --- | --- |
| `kubernetes.yml` per-PR job | `helm lint` clean; the rendering equals `deploy/kubernetes/`; kubeconform against the pinned schema; the rendered grace equals the bound plus the margin; `forceOnTimeout: false` fails the render with its sentence | `grace-below-bound`, `unbounded-renders` |
| `WorkflowLedgerTest` (+ rows) | the two new jobs | — |
| `helm template` with `stackFile` and `secretsMount` set | the ConfigMap lands at `/stack/tesseraql-stack.yml` over the baked directory; the Secret at `/run/secrets` | `subpath-missing` |

### S5 — M10 on kind

The `two-node` job of `kubernetes.yml` (decision 10), the probe application under
`.github/kubernetes/app/`, the roadmap's M10 line, CHANGELOG.

| Guard | Proves | Red on the variant |
| --- | --- | --- |
| the `two-node` job | zero non-200 through a rolling restart under `bench`; one execution per fire time; a session across pods; one `9006` delivery across two pods; a deleted pod finishes its slow request and exits 143 | `maxUnavailable-1`, `alert-per-node` (S2's dedup off), `grace-30s` |

## Docs and CHANGELOG

Each slice's PR: `CHANGELOG.md` **Added** (S1: "An official runtime image, and a container
stop that ends"; S2: "The scrape says how full the node is, and an alert pages once"; S3:
"`tesseraql bench` and the capacity guide"; S4: "A Helm chart and Kubernetes manifests"; S5:
"Two replicas on Kubernetes, proven") and **Changed/Fixed** (S1: the health check that could
not pass, the baked image that refused a module, the origin readiness, the Kamal template's
stop; S2: the alert dedup); `deployment.md`, `hosting.md`, `notifications.md`, `kubernetes.md`,
`capacity.md`; `reference-cli.md`, `reference-config.md`, `reference-error-codes.md`,
`reference-yaml-surface.md` regenerated **last**; the status block of this record and the
roadmap's Phase 33 paragraph updated as each slice lands; the roadmap's M10 line when S5 is
green.

## Error codes

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-OPS-9010` | warning (alert, S2) | a member's readiness roll-up is `DOWN` |
| `TQL-OPS-9011` | warning (alert, S2) | a pool had borrowers waiting for a whole check interval |
| `TQL-OPS-9012` | warning (alert, S2) | admission refusals over the interval exceeded the threshold — the runtime is at capacity |
| `TQL-OPS-9013` | warning (alert, S2) | a stop reached its drain bound with requests still in flight and cut them |
| `TQL-APP-4216` | unchanged | the image recipe stops tripping it; row 2 becomes CI's red variant |
| `TQL-RATE-4293`, `4294`, `4295`, `4296`, `4291` | unchanged | gain a counter label and a `bench` classification |
| `TQL-YAML-…` (bench) | error, lint (S3) | a `kind: bench` document naming an unknown route, an undeclared param, or a write without `writes: allowed` — numbered at implementation, grep-before-numbering |
