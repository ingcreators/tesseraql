# Deployment

This page is the production-operations hub — health endpoints, shipping apps, bootstrap and
migrations, environment profiles, logging, metrics, and the safety valves — and applies
whatever your stack, even where the examples use the reference setup. The reference
deployment is a small VPS (e.g. Lightsail) running Docker containers deployed by
Kamal 2, fronted by Cloudflare (DNS, CDN, WAF) through a Cloudflare Tunnel, with a managed
PostgreSQL database. [deploy/Dockerfile](../deploy/Dockerfile) and
[deploy/kamal/deploy.yml](../deploy/kamal/deploy.yml) — templates shipped in the framework
repository — are the starting points.

```
users → Cloudflare (DNS / CDN / WAF / Access)
           │ tunnel (outbound-only; no open HTTP ports on the host)
           ▼
host: cloudflared → kamal-proxy → tesseraql runtime (:8080)
                                     └ volume: /stack/app/work
managed PostgreSQL (sessions, jobs, outbox, file transfers all multi-node safe)
```

- `GET /_tesseraql/health/live` is the unauthenticated liveness endpoint (the process answers;
it never touches a dependency), and `GET /_tesseraql/health/ready` — also what the bare
`/_tesseraql/health` serves — is the readiness roll-up: it probes every configured datasource
live and answers `503 {"status":"DOWN"}` when one fails, `WARN` on active alerts, `UP`
otherwise (status word only). Point container health checks at
`/_tesseraql/health/live` and load-balancer/proxy checks at `/_tesseraql/health/ready`; the
  detailed health/metrics stay behind the authorized ops API.
- Put a Cloudflare Access policy on `/_tesseraql/*` so the system consoles sit behind both the
  Cloudflare login and the app's own authentication.
- Sessions are `jdbc` by default (shared `tql_session`, logins survive container
  replacement); `tesseraql.sessions.store: memory` is the per-node opt-out.
- `/assets/**` is CDN-cacheable (ETag/Cache-Control are set); vendor assets use version-less
  URLs, so purge the Cloudflare cache when upgrading browser libraries.

## Shipping apps

**A. Baked image (default).** The [app home](app-layout.md) is COPYed into the image; deploying the app is
`kamal deploy`. The running container maps one-to-one to a git commit, CI gates
(`lint`, `test`, `governance`, `release-evidence`) run before the build, and rollback is the
previous image.

**B. Several applications on one host.** `tesseraql host --stack <dir>` starts every application
the directory holds in its own runtime behind one port — its own runtime context, datasource
set, Studio and traces. They are addressed as `/<name>/` on one origin and share a sign-in
across them. See [hosting.md](hosting.md).

One runtime serves **one** application plus the framework's own surfaces. Mounting further
applications into it — `tesseraql.apps.<name>.path` / `.package` / `.url`, previously
documented here as shipping configurations B and C — is gone: it shared one URL space with no
per-application prefix, one Studio that could not see the mounted applications, and one trace
buffer for all of them ([app-isolation-model.md](app-isolation-model.md) decision 1).

## Bootstrap and migrations

1. `tesseraql migrate --app . --jdbc-url ...` applies the app's schema migrations, per
   datasource (`--datasource <name>` for named connections) — or rely on the mount-time
   migrations; both converge on the same per-app Flyway history. In CI, the
   `tesseraql:migrate` Maven goal does the same
   (`mvn tesseraql:migrate -Dtesseraql.appHome=. -Dtesseraql.jdbcUrl=...`).

   The history table is `tql_schema_history_<name>`, and *name* is the app's own
   `tesseraql.app.name` — read from the app by all three, so they cannot disagree about
   which table holds the history. Override it with `tesseraql.migrations.historyName`. The
   reason to is an identifier limit: a name that does not fit the database's maximum is
   refused (`TQL-APP-4208`) rather than truncated, because truncation is silent and two
   apps whose names share a long prefix would then record into one history.

   `tesseraql.app.name` is **required**. It is an identity rather than a label — it scopes
   outbox claims and job ownership, it is what `tql.ops.view.<name>` grants are checked against,
   and in a stack it is the app's address — so an app declaring none is refused at start
   (`TQL-YAML-1404`) rather than run under a name every unnamed app would share.
2. `tesseraql identity-schema --jdbc-url ... --admin-login admin
   --admin-password-file ./admin.pw --admin-roles ADMIN
   --admin-permissions tql.ops.view.*,tql.ops.run.*,tql.app.deploy.*,tql.studio.edit.*,tql.app.use.*`
   applies the managed IAM schema and seeds the first administrator; the
   `tesseraql:identity-schema` Maven goal is the CI alternative. There are no default
   credentials; the role names must match the app's `tesseraql.security.policies`.
   `tql.ops.view.<name>` permissions scope what an operator sees in the
   [ops console](ops-console.md) and the `/_tesseraql/ops` API: [batch jobs](jobs.md),
   executions, and traces are attributed to their owning app and hidden outside the caller's
   grants (deny by default), and acting — running jobs, redelivering events — is granted
   separately as `tql.ops.run.<name>`; the terminal `*` grants a verb everywhere.
3. `kamal setup` / `kamal deploy`.

Old and new versions briefly overlap in every deploy shape — Kamal swaps containers with both
serving, and a [`tesseraql deploy`](hosting.md#deploying-one-application) starts the new runtime
beside the old one before traffic moves. So migrations must stay expand/contract (backward
compatible): that is the deploy window's contract, and the old version serves over the migrated
schema for the length of the window.

The stack also stops gracefully: on SIGTERM, `host` flips the gateway's readiness to 503 while
liveness stays 200, keeps serving until in-flight work drains, and then closes every runtime
under its own `tesseraql.shutdown.timeout`. Give the platform a grace period —
`terminationGracePeriodSeconds`, Kamal's `deploy_timeout`, and kin — longer than the slowest
member's declared timeout, or the platform's SIGKILL cuts the drain short.

## Multi-server notes

- Sessions, [scheduled-job](jobs.md) claims, [outbox](notifications.md) dispatch and file
  transfers are app- and node-safe on
  a shared database; adding a host is a `servers:` entry.
- Generated export files follow you across nodes when you pick a shared temp store (below);
  the `file` default keeps them on the producing node, which then needs session affinity.
- Framework and app migrations take Flyway's lock, so concurrent node startups serialize.

## Request threads

Every HTTP request runs on the **worker pool**: route processing is blocking work, and the
platform HTTP layer hands each exchange to a pool of platform threads. The pool size is
therefore this runtime's ceiling on concurrent route execution, and it is one of two numbers
that decide how much work the runtime does at once.

| Key | Default | What it sizes |
| --- | --- | --- |
| `tesseraql.http.workerThreads` | 10 | Concurrent route executions |
| `tesseraql.http.eventLoopThreads` | `2 x cores` | Connection I/O; blocking work never runs here |
| `tesseraql.http.maxInFlight` | `workerThreads x 4` | Requests held at once before refusing |

**Beyond `maxInFlight` the runtime answers 503 with `Retry-After`**, immediately, rather than
adding the request to a queue with no bound. Four times the worker count leaves room for the
ordinary burst a queue exists to absorb while keeping the queue a number you can see. A caller
that gets this refusal should retry; a monitor that sees it should read it as "this runtime is
at capacity", which is `TQL-RATE-4293`.

### The front door's share of each member

Under `tesseraql host`, requests reach a member through the gateway, which applies its own bound
first. It is declared in `tesseraql-stack.yml`:

| Key | Default | What it does |
| --- | --- | --- |
| `tesseraql.gateway.maxConcurrentPerMember` | `tesseraql.http.workerThreads` | Forwards in flight to one member |
| `tesseraql.gateway.readIdleTimeoutSeconds` | off | Reclaim a forward whose member has sent nothing for this long |

Beyond the bound the gateway answers 503 with `Retry-After` and `TQL-RATE-4294` — **for that
member only**. A member whose database has stalled holds its own permits and nothing else, so
the rest of the stack keeps serving. That containment is the reason to leave the read-idle
timeout off unless you need it: a hung member and one running a legitimately long report look
the same from the front door, so a timeout short enough to catch the first will eventually
cancel the second. Set it only if you know your slowest legitimate response.

Health (`/_tesseraql/health` and below) is checked before the bound, so the gate never refuses
it. It still needs a worker to answer, so it can be slow when every worker is blocked — bounded
now by `maxInFlight` rather than unbounded, which is the improvement rather than a promise of
promptness. Use `/health/live` for liveness: it touches no dependency.

**Raise it together with the connection pool.** The worker pool feeds
`tesseraql.datasources.<name>.maximumPoolSize`, so a worker count above the pool size buys
nothing except threads waiting in connection acquisition — for up to `connectionTimeoutMillis`
each. The defaults are deliberately the same number.

Each datasource takes its pool settings under `tesseraql.datasources.<name>`:

| Key | Default | What it does |
| --- | --- | --- |
| `maximumPoolSize` | 10 | Connections this datasource may open |
| `connectionTimeoutMillis` | 30000 | How long a borrower waits before failing |
| `minimumIdle` | pool size | Connections kept open when idle |
| `idleTimeoutMillis` | Hikari's | When a surplus idle connection is retired |
| `maxLifetimeMillis` | Hikari's | When a connection is retired regardless of use |
| `keepaliveTimeMillis` | Hikari's | How often an idle connection is probed |
| `leakDetectionThresholdMillis` | off | Logs a stack trace for a connection held this long |

The first two are TesseraQL's own defaults rather than the driver pool's, so they cannot
change under you when a dependency changes its mind. `leakDetectionThresholdMillis` stays off
because it is a debugging aid whose log volume is an operator's decision, not a default.

Background work — [jobs](jobs.md), file transfers, streams — borrows from these same pools
outside the worker pool. That is deliberate: contention shows up as request latency you can
measure rather than hiding in a second pool. Watch `tesseraql_pool_threads_awaiting` in the
[metrics](#metrics-prometheus) below; a non-zero reading is the pool, not the database, being
the constraint.

Size it from measured latency rather than from a guess: concurrency is throughput times
latency, so routes averaging 50 ms saturate 10 workers at roughly 200 requests a second, and
routes averaging a second saturate them at 10. If the answer is "many more threads", check
first whether the database can absorb the connections that come with them — the pool that
matters is the one at the far end.

A count that is not a positive integer refuses at startup (`TQL-YAML-1112`) rather than
starting with a pool nobody asked for.

## Transport security (TLS and HSTS)

TesseraQL serves HTTP and **assumes TLS terminates at the deployment edge** — a reverse
proxy, ingress controller, or load balancer in front of the runtime. This is a deliberate
boundary, not a gap: the edge is where certificate lifecycle, cipher policy, and HTTP
security headers already live in a production deployment. The operator's responsibilities:

- **Terminate TLS at the edge and forward only HTTPS traffic** to the runtime. The browser
  session cookie and the CSRF token are secured on the assumption that the transport is
  HTTPS in production; do not expose the plain-HTTP port to clients.
- **Set HSTS at the edge** (`Strict-Transport-Security`) so browsers refuse to downgrade.
  Per-route response headers (CSP, `X-Content-Type-Options`, `X-Frame-Options`,
  `Referrer-Policy`) are declared in the app and emitted by the runtime; HSTS is a
  connection-level header that belongs on the terminating proxy.
- **`auth: mtls`** authenticates a client certificate for service-to-service calls
  ([authentication](authentication.md)): the edge performs the TLS client-cert handshake and
  forwards the verified certificate (subject DN / SAN / SHA-256) in a header the runtime
  reads. Configure the proxy to set that header only from a verified handshake and to strip
  any client-supplied copy.
- **Outbound** calls (`http:`, connectors, the analytics engine's remote tier) use HTTPS
  by their configured URLs and are bounded by the deny-by-default egress allow-list; the
  runtime does not disable certificate verification.

The framework does not ship a TLS listener or manage certificates itself, so a deployment
that exposes the runtime directly without an HTTPS edge is misconfigured. See the
[security hardening](security-hardening.md) self-assessment (ASVS V9) for the control map.

## Embedded database lifecycle

`tesseraql dev --embedded-db [dir]` runs a real PostgreSQL inside the process — for
development and demos, not multi-node production (it is single-process; point multiple app
nodes at a shared server instead). An ephemeral run gets a fresh database wiped on exit; a
directory argument makes the data persistent.

A **persistent directory is pinned to its PostgreSQL version.** On first use the CLI records
the binary version that initialized the directory (a `tesseraql-embedded.properties` marker)
and re-resolves exactly that version on later starts, so upgrading the CLI — which may bump
the default binary version — never leaves an existing directory unopenable by a newer,
format-incompatible major. Pin a specific version yourself with
`--embedded-db-version 17.10.0`; an ephemeral run always uses the default. If a directory was
created by a different major than the run resolves, the CLI stops with a clear message (pin
the matching major, or start fresh) rather than a cryptic `postgres` crash.

To see where a directory stands — its on-disk major, its pinned version, and whether the CLI
default has moved past it — run `tesseraql embedded-db info ./pgdata`. When an upgrade to a
newer major is available it prints the safe dump/restore procedure to follow. That procedure
uses your own `pg_dumpall`/`psql`: the embedded binaries are server-only (no client tools
bundled), and crossing a PostgreSQL major means dumping from the old server and restoring
into a fresh one. To graduate embedded data to a standalone server, point
`tesseraql.datasources.main.jdbcUrl` at the new server after the restore.

## Environment profiles

One switch selects a per-environment overlay layer (see [promotion](promotion.md) for the
full dev → staging → prod loop): `--env staging` on `tesseraql dev` (or `TESSERAQL_ENV=staging`, or
`-Dtesseraql.env=staging`) merges `config/env/staging.yml` **between** the app's base config
(`application.yml` → `tesseraql.yml`) and Studio's `overlay.yml` — the profile is the
environment's tuning, and dev-time Studio edits still win on top. A named profile whose file
does not exist fails startup fast: a typo'd environment must never silently run another
environment's config. No profile means no layer — existing apps are unchanged.

This replaces ad-hoc `${...}` indirection for the common cases: put the per-environment
datasource, pool sizing, metrics/audit switches and timeouts in `config/env/<profile>.yml`
and keep secrets in real environment variables or the secret provider as before.

## Business-route audit log and error pages

Opt in with `tesseraql.audit.routes.enabled: true`: every route invocation lands one durable
row in `tql_route_audit` — who (`actor`, `tenant_id`), what (`route_id`, method, path,
status, duration), when, correlated by `trace_id` — with the **declared** input params as
JSON. Fields carrying a `mask:` or `classification:` are excluded wholesale, so sensitive
values can never reach the trail; a failed audit insert never fails the request.
`GET /_tesseraql/ops/audit` reads the newest rows, bearer-gated (any `tql.ops.view` grant) and
narrowed to the caller's `tql.ops.view.<name>` grants like every other per-app ops read.

**Custom error pages** are app-authoring content: drop `templates/errors/<status>.html` into
the app to brand what a failed browser navigation renders — see
[hypermedia-ui.md](hypermedia-ui.md#custom-error-pages).

## Logging

The CLI distribution ships a JDK-only SLF4J provider: one line per event on
stderr, plain text by default, `--log-format json` (or `-Dtesseraql.logging.format=json`) for
structured lines, `--log-level` for the threshold. Every line carries the MDC, so a log
aggregator correlates each line with the request that produced it:

| Key | What it is |
| --- | --- |
| `traceId`, `spanId` | The request's trace ids, set when the route starts. |

Route identity is not on the MDC; it is on the access-log line below as `route=`.

The ids travel on the exchange rather than on the thread, and are copied into the MDC around
each step, so a step handed to an execution lane still logs under the request that started it.

An **opt-in HTTP access log** rides the same correlation: `tesseraql.logging.accessLog: true`
emits one line per request on the `tesseraql.access` logger —
`GET /api/users 200 12ms route=users.search user=alice`.

## Safety valves and multi-node semantics

**SQL statement timeout.** Every route SQL statement is bounded by default: 30 seconds, the
app-wide `tesseraql.sql.timeoutSeconds`, or a per-binding `timeoutSeconds:` override —
an explicit `0` opts a deliberately long-running statement out. A runaway query is cancelled
by the driver instead of holding a pool connection forever.

**Connection pools.** Each `tesseraql.datasources.<name>` block tunes its HikariCP pool:
`maximumPoolSize`, `minimumIdle`, `connectionTimeoutMillis`, `idleTimeoutMillis`,
`maxLifetimeMillis`, `keepaliveTimeMillis`, and `leakDetectionThresholdMillis`. Unset keys
keep Hikari's defaults.

**Concurrency limiters and lanes are per-node — deliberately.** The `concurrency` guard and
the `threading.lanes` bulkheads protect a node's own resources (threads, memory, its pool
connections), so their budgets scale with the node count by design: lane saturation on one
node does not shed load on another, and adding a node adds capacity.

**Rate limits can be cluster-wide.** A `rateLimit` is usually a budget for something shared —
the database behind the route, a partner API's contract quota — so per-node enforcement
(N × node-count cluster-wide) defeats it. Declare the scope:

```yaml
admission:
  rateLimit:
    requestsPerSecond: 50
    scope: cluster        # default: node
```

With `scope: cluster` the declared rate is one budget across every node sharing the main
database. Enforcement stays a local token bucket — the request path never touches the
database — but tokens are *leased* from a small `tql_rate_lease` ledger (one row per route
per second-window, plain atomic updates, every supported dialect, created on first use like
the inbox table). At most one lease claim runs per second per node per route; claims are
first-come-first-served, so a quiet node leaves its share for the busy ones, and `burst`
remains node-local smoothing. Precision is bounded, not perfect: a volley straddling a window
boundary can briefly see up to two windows' budget. When the ledger is unreachable the
limiter degrades to the per-node budget for that window and logs with backoff — rate limiting
protects resources; it must never become the outage itself.

**Shared export files.** Spooled exports (`query-export`, `query-spool`, batch intermediate
results) default to the producing node's local disk — fine for one node, but a download can
then only be served where it was made. Pick the store per deployment:

```yaml
tesseraql:
  temp:
    store: db          # file (default) | db | blob
    maxBytes: 67108864 # db only: per-spool cap, default 64 MB
```

- **`db`** — spools live in the `tql_temp_spool` table on the main database (created on
  first use, like the inbox), so **any node serves any download**: no session affinity, no
  shared filesystem, no new infrastructure. Writes and reads stage through a local scratch
  file, so memory stays bounded and no pooled connection is pinned while a slow client
  streams. Right for the modest export sizes LOB screens produce; a spool over
  `tesseraql.temp.maxBytes` fails loudly and points at `blob`.
- **`blob`** — spools ride the configured object store
  (`tesseraql.object-storage.provider`, e.g. S3 via the opt-in `tesseraql-s3` module,
  bucket named by `tesseraql.temp.bucket`): shared across nodes and right for heavy export
  volumes. With the local `file` provider this is still node-local — the boot warns.
- **`file`** — the default and the pre-cluster behavior: node-local under
  `work/tmp/tesseraql`; keep session affinity at the load balancer, or point the directory
  at a shared filesystem if you already run one.

## Framework datasource

Ambient framework state — sessions, credential tokens, replay guards, OIDC flow state,
rate leases, route audit, preferences — rides the `main` pool by default, which means a
saturating business query can starve *login* of a connection. Point
`tesseraql.framework.datasource` at any named datasource to isolate it
(docs/framework-datasource.md has the full store classification):

```yaml
tesseraql:
  datasources:
    framework:
      jdbcUrl: ${DB_URL}        # the SAME database: pool isolation, zero migration
      maximumPoolSize: 8        # sessions are millisecond point queries
  framework:
    datasource: framework
```

Start with same-DB/separate-pool — the starvation pain is a pool phenomenon. A genuinely
separate database is the same one-line change when scale or backup/retention separation
calls for it; bucket-3 schemas bootstrap there on first start. Switching an existing
deployment: sessions end (everyone signs in again), outstanding reset/invite links die,
old audit rows stay behind in the business database — inconvenience, never corruption.
The transactionally-coupled stores (outbox, workflow, idempotency, webhook replay)
deliberately ignore this key.

## Metrics (Prometheus)

Opt in with `tesseraql.metrics.enabled: true` and scrape `GET /_tesseraql/metrics`
(text format 0.0.4). The exposition is fed by a JDK-only in-process aggregator that is always
recording — per-route invocation counters (`tesseraql_route_invocations_total`), an
outcome-classed error counter (`tesseraql_route_errors_total`), and latency histograms in
seconds (`tesseraql_route_duration_seconds_*`) labelled `routeId`/`method`/`outcome`.
Batch runs ride the same exposition: `tesseraql_job_runs_total` labelled
`job`/`app`/`status` and `tesseraql_job_duration_seconds_*` per job
([jobs](jobs.md#observing-runs)) — alert on
`increase(tesseraql_job_runs_total{status="FAILED"}[1d]) > 0` and on the expected
nightly run *not* appearing.

Beyond the route metrics, the scrape carries the node's poll-source health — the
registry behind the console's jobs page, rendered as gauges at scrape time so a silent
poll source is alertable without anyone watching a screen — and an egress-denial counter
for `http:` steps. `jobId` (or `host`) is the only label; source strings and skip
reasons stay on the console page.

| Family | Meaning | Sample alert |
| --- | --- | --- |
| `tesseraql_poll_source_wired` | `1` polling, `0` refused at wire time | `tesseraql_poll_source_wired == 0` |
| `tesseraql_poll_source_consecutive_failures` | current import-failure streak | `tesseraql_poll_source_consecutive_failures >= 3` |
| `tesseraql_poll_source_last_poll_age_seconds` | seconds since the last poll; absent until one completes | `tesseraql_poll_source_last_poll_age_seconds > 3600` |
| `tesseraql_egress_denied_total` | `http:` refusals per denied host | `rate(tesseraql_egress_denied_total[5m]) > 0` |

The scrape is **bearer + `ops.metrics.view` policy** by default (labels reveal route ids);
give the scraper a token via `bearer_token_file`, or set
`tesseraql.metrics.unauthenticated: true` for a cluster-internal scrape the network already
guards. OTLP push (`tesseraql.otel.otlp.endpoint`) is independent and now carries the same
histograms. A ready-made Grafana dashboard ships at
[deploy/grafana/tesseraql-dashboard.json](../deploy/grafana/tesseraql-dashboard.json).

## Next

- [promotion.md](promotion.md) — moving a change between environments.
- [upgrading.md](upgrading.md) — moving to a new framework release.
- [reference-config.md](reference-config.md) — every configuration key, with what reads it.
- [ops-console.md](ops-console.md) — watching the running system.
