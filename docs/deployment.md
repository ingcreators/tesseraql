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
                                     └ volume: /app/work
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
- `tesseraql.sessions.store: jdbc` keeps logins across container replacement.
- `/assets/**` is CDN-cacheable (ETag/Cache-Control are set); vendor assets use version-less
  URLs, so purge the Cloudflare cache when upgrading browser libraries.

## Shipping apps

**A. Baked image (default).** The [app home](app-layout.md) is COPYed into the image; deploying the app is
`kamal deploy`. The running container maps one-to-one to a git commit, CI gates
(`lint`, `test`, `governance`, `release-evidence`) run before the build, and rollback is the
previous image.

**B. Local `.tqlapp`.** One runtime hosts several apps mounted from packages:
`tesseraql.apps.<name>.package` + `sha256`. App updates replace the file and restart; the
runtime refuses a package whose hash does not match.

**C. Fetched `.tqlapp` (multi-server).** The configuration names a URL and pins the hash; every
node fetches and verifies the same bytes at boot:

```yaml
tesseraql:
  apps:
    orders:
      url: https://artifacts.example.com/orders-1.2.0.tqlapp
      sha256: 4f2a...   # required - remote content is never mounted unpinned
```

Downloads land under `work/downloads` and are reused across restarts while the hash matches,
so nodes reboot without the artifact store being reachable. Rolling out a new version is a
config change (new url + sha) plus a rolling restart; pointing one host at the new hash first
is a per-host canary.

## Bootstrap and migrations

1. `tesseraql migrate --app . --jdbc-url ...` applies the app's schema migrations, per
   datasource (`--datasource <name>` for named connections) — or rely on the mount-time
   migrations; both converge on the same per-app Flyway history. In CI, the
   `tesseraql:migrate` Maven goal does the same
   (`mvn tesseraql:migrate -Dtesseraql.appHome=. -Dtesseraql.jdbcUrl=...`).
2. `tesseraql identity-schema --jdbc-url ... --admin-login admin
   --admin-password-file ./admin.pw --admin-roles ADMIN --admin-permissions ops.app.*`
   applies the managed IAM schema and seeds the first administrator; the
   `tesseraql:identity-schema` Maven goal is the CI alternative. There are no default
   credentials; the role names must match the app's `tesseraql.security.policies`.
   `ops.app.<name>` permissions scope what an operator sees in the ops console and the
   `/_tesseraql/ops` API: [batch jobs](jobs.md), executions, and traces are attributed to their
   owning app
   and hidden outside the caller's grants (deny by default); `ops.app.*` grants everything.
3. `kamal setup` / `kamal deploy`.

Kamal swaps containers with old and new briefly overlapping, so migrations must stay
expand/contract (backward compatible) - the same discipline the canary flow already requires.

## Multi-server notes

- Sessions, [scheduled-job](jobs.md) claims, [outbox](notifications.md) dispatch and file
  transfers are app- and node-safe on
  a shared database; adding a host is a `servers:` entry.
- Generated export files follow you across nodes when you pick a shared temp store (below);
  the `file` default keeps them on the producing node, which then needs session affinity.
- Framework and app migrations take Flyway's lock, so concurrent node startups serialize.

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
- **Outbound** calls (`http-call`, connectors, the analytics engine's remote tier) use HTTPS
  by their configured URLs and are bounded by the deny-by-default egress allow-list; the
  runtime does not disable certificate verification.

The framework does not ship a TLS listener or manage certificates itself, so a deployment
that exposes the runtime directly without an HTTPS edge is misconfigured. See the
[security hardening](security-hardening.md) self-assessment (ASVS V9) for the control map.

## Embedded database lifecycle

`tesseraql serve --embedded-db [dir]` runs a real PostgreSQL inside the process — for
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
full dev → staging → prod loop): `--env staging` on `tesseraql serve` (or `TESSERAQL_ENV=staging`, or
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
`GET /_tesseraql/ops/audit` reads the newest rows, bearer + `ops.batch.view` gated and
narrowed to the caller's `ops.app.<name>` grants like every other per-app ops read.

**Custom error pages** are app-authoring content: drop `templates/errors/<status>.html` into
the app to brand what a failed browser navigation renders — see
[hypermedia-ui.md](hypermedia-ui.md#custom-error-pages).

## Logging

The CLI distribution ships a JDK-only SLF4J provider: one line per event on
stderr, plain text by default, `--log-format json` (or `-Dtesseraql.logging.format=json`) for
structured lines, `--log-level` for the threshold. Every line carries the MDC — the runtime
puts the request's `traceId`/`spanId` there and Camel bridges them across async steps — so a
log aggregator correlates each line with the request that produced it. The Spring distribution
keeps Boot's Logback (add `logstash-logback-encoder` there if you want JSON).

An **opt-in HTTP access log** rides the same correlation: `tesseraql.logging.accessLog: true`
emits one line per request on the `tesseraql.access` logger —
`GET /api/users 200 12ms route=users.search user=alice`.

## Safety valves and multi-node semantics

**SQL statement timeout.** Every route SQL statement is bounded by default: 30 seconds, the
app-wide `tesseraql.sql.timeoutSeconds`, or a per-binding `sql.timeoutSeconds` override —
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
policy:
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

## Metrics (Prometheus)

Opt in with `tesseraql.metrics.enabled: true` and scrape `GET /_tesseraql/metrics`
(text format 0.0.4). The exposition is fed by a JDK-only in-process aggregator that is always
recording — per-route invocation counters (`tesseraql_route_invocations_total`), an
outcome-classed error counter (`tesseraql_route_errors_total`), and latency histograms in
seconds (`tesseraql_route_duration_seconds_*`) labelled `routeId`/`method`/`outcome`.

The scrape is **bearer + `ops.metrics.view` policy** by default (labels reveal route ids);
give the scraper a token via `bearer_token_file`, or set
`tesseraql.metrics.unauthenticated: true` for a cluster-internal scrape the network already
guards. OTLP push (`tesseraql.otel.otlp.endpoint`) is independent and now carries the same
histograms. A ready-made Grafana dashboard ships at
[deploy/grafana/tesseraql-dashboard.json](../deploy/grafana/tesseraql-dashboard.json).
