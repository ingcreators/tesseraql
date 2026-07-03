# Deployment

The reference deployment is a small VPS (e.g. Lightsail) running Docker containers deployed by
Kamal 2, fronted by Cloudflare (DNS, CDN, WAF) through a Cloudflare Tunnel, with a managed
PostgreSQL database. `deploy/Dockerfile` and `deploy/kamal/deploy.yml` are the templates.

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
otherwise (status word only, roadmap Phase 45). Point container health checks at
`/health/live` and load-balancer/proxy checks at `/health/ready`; the
  detailed health/metrics stay behind the authorized ops API.
- Put a Cloudflare Access policy on `/_tesseraql/*` so the system consoles sit behind both the
  Cloudflare login and the app's own authentication.
- `tesseraql.sessions.store: jdbc` keeps logins across container replacement.
- `/assets/**` is CDN-cacheable (ETag/Cache-Control are set); vendor assets use version-less
  URLs, so purge the Cloudflare cache when upgrading browser libraries.

## Shipping apps

**A. Baked image (default).** The app home is COPYed into the image; deploying the app is
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

1. `mvn tesseraql:migrate -Dtesseraql.appHome=. -Dtesseraql.jdbcUrl=...` per datasource
   (`-Dtesseraql.datasource=<name>` for named connections), or rely on the mount-time
   migrations - both converge on the same per-app Flyway history.
2. `mvn tesseraql:identity-schema -Dtesseraql.jdbcUrl=... -Dtesseraql.adminLogin=admin
   -Dtesseraql.adminPasswordFile=... -Dtesseraql.adminRoles=ADMIN
   -Dtesseraql.adminPermissions=ops.app.*` seeds the first administrator. There are no default
   credentials; the role names must match the app's `tesseraql.security.policies`.
   `ops.app.<name>` permissions scope what an operator sees in the ops console and the
   `/_tesseraql/ops` API: batch jobs, executions, and traces are attributed to their owning app
   and hidden outside the caller's grants (deny by default); `ops.app.*` grants everything.
3. `kamal setup` / `kamal deploy`.

Kamal swaps containers with old and new briefly overlapping, so migrations must stay
expand/contract (backward compatible) - the same discipline the canary flow already requires.

## Multi-server notes

- Sessions, scheduled-job claims, outbox dispatch and file transfers are app- and node-safe on
  a shared database; adding a host is a `servers:` entry.
- Generated export files live on the node that produced them: keep session affinity at
  Cloudflare, or plug a shared TempStore implementation behind the SPI.
- Framework and app migrations take Flyway's lock, so concurrent node startups serialize.

## Environment profiles

One switch selects a per-environment overlay layer (roadmap Phase 46):
`--env staging` on `tesseraql serve` (or `TESSERAQL_ENV=staging`, or
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

**Custom error pages**: drop `templates/errors/<status>.html` (or the catch-all
`templates/errors/error.html`) into the app and a top-level browser GET that fails renders it
(model: `status`, `error.code`, `error.message`); htmx swaps keep the inline fragment and API
clients keep the JSON envelope. No template — today's JSON behavior.

## Logging

The CLI distribution ships a JDK-only SLF4J provider (roadmap Phase 45): one line per event on
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
by the driver instead of holding a pool connection forever (roadmap Phase 45).

**Connection pools.** Each `tesseraql.datasources.<name>` block tunes its HikariCP pool:
`maximumPoolSize`, `minimumIdle`, `connectionTimeoutMillis`, `idleTimeoutMillis`,
`maxLifetimeMillis`, `keepaliveTimeMillis`, and `leakDetectionThresholdMillis`. Unset keys
keep Hikari's defaults.

**Rate/concurrency limiters and lanes are per-node.** The route `policy:` block's
`rateLimit`/`concurrency` guards and the `threading.lanes` bulkheads keep their state in
process memory (token bucket, semaphores). On a multi-node deployment each node enforces its
own budget: a route limited to N requests/second allows up to N × node-count cluster-wide,
and lane saturation on one node does not shed load on another. Size the budgets per node (or
enforce a cluster-wide budget at the load balancer); shared-state limiters are a deliberate
non-goal until a coordination store earns its place.

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
