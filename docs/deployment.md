# Deployment

This page is the production-operations hub — health endpoints, shipping apps, bootstrap and
migrations, environment profiles, logging, metrics, and the safety valves — and applies
whatever your stack, even where the examples use the reference setup. The reference
deployment is a small VPS (e.g. Lightsail) running Docker containers deployed by
Kamal 2, fronted by Cloudflare (DNS, CDN, WAF) through a Cloudflare Tunnel, with a managed
PostgreSQL database. [deploy/Dockerfile](https://github.com/ingcreators/tesseraql/blob/main/deploy/Dockerfile) and
[deploy/kamal/deploy.yml](https://github.com/ingcreators/tesseraql/blob/main/deploy/kamal/deploy.yml) — templates shipped in the framework
repository — are the starting points.

```
users → Cloudflare (DNS / CDN / WAF / Access)
           │ tunnel (outbound-only; no open HTTP ports on the host)
           ▼
host: cloudflared → kamal-proxy → tesseraql runtime (:8080)
                                     └ volume: /stack/<name>/work
managed PostgreSQL (sessions, jobs, outbox, file transfers all multi-node safe)
```

- `GET /_tesseraql/health/live` is the unauthenticated liveness endpoint (the process answers;
it never touches a dependency). An application's `GET /<name>/_tesseraql/health/ready` — also
what its bare `/<name>/_tesseraql/health` serves — is that application's readiness roll-up: it
probes every configured datasource and answers `503 {"status":"DOWN"}` when one fails, `WARN`
on active alerts, `UP` otherwise (status word only). The roll-up is held and refreshed behind
the answer, so a probe on any cadence — Kubernetes' default is every ten seconds — is answered
from the last roll-up and starts the next; `DOWN` is also the answer when a refresh has hung for
three times `tesseraql.diagnostics.readinessTtl` (`1s` by default). The origin's
`GET /_tesseraql/health/ready` is the stack's readiness over every member. It answers
`503 {"status":"DRAINING"}` while the stack stops and `503 {"status":"DOWN"}` when every
member's roll-up is down. Otherwise it answers `200` and names the members that are not `UP`
(`{"status":"DEGRADED","down":["orders"]}`). A partial outage stays routable on purpose: two
nodes share every member's database, so a readiness that failed on any one member would empty
the pool for the healthy ones too. Point container health checks at `/_tesseraql/health/live` and
load-balancer/proxy checks at the origin's `/_tesseraql/health/ready`; the detailed
health/metrics stay behind the authorized ops API.
- Put a Cloudflare Access policy on `/_tesseraql/*` so the system consoles sit behind both the
  Cloudflare login and the app's own authentication.
- Sessions are `jdbc` by default (shared `tql_session`, logins survive container
  replacement); `tesseraql.sessions.store: memory` is the per-node opt-out.
- `/assets/**` is CDN-cacheable (ETag/Cache-Control are set); vendor assets use version-less
  URLs, so purge the Cloudflare cache when upgrading browser libraries.

## Shipping apps

**A. Derived image (default).** The official runtime image,
`ghcr.io/ingcreators/tesseraql-host:<version>` — also tagged `<major.minor>` and `latest`, for
`linux/amd64` and `linux/arm64`, published from every release tag — carries the host and the
operator verbs and no application. Your image derives from it and unpacks the package you built
under `/stack/<name>`; `deploy/Dockerfile` is that template:

```sh
tesseraql package --app . --out build/orders.tqlapp
unzip -q build/orders.tqlapp -d build/stack/orders
docker build -f deploy/Dockerfile \
  --build-arg BASE=ghcr.io/ingcreators/tesseraql-host:0.19.0 \
  --build-arg APP_DIR=build/stack/orders --build-arg APP_NAME=orders -t my-org/orders .
```

A package rather than the source tree, because the package carries the modules the
application declared — drivers, the pdf/excel/s3 codecs — and the host refuses to start an
application that declares modules and carries none
([hosting.md](hosting.md#modules-are-resolved-before-the-host-starts)). Deploying the app is
then `kamal deploy`. The running container maps one-to-one to a git commit, CI gates
(`lint`, `test`, `governance`, `release-evidence`) run before the build, and rollback is the
previous image. The image sets no heap size: give the container a memory limit and
`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`. Its `HEALTHCHECK` probes liveness over `bash`'s
`/dev/tcp` (the base image ships no HTTP client); Kubernetes ignores it and probes over HTTP.

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
under its own `tesseraql.shutdown.timeout` (`45s` by default). The close that follows the drain
is bounded too — five seconds per transport, an abandoned close logged — so the process exits
`143` within a few seconds of the drain. Give the platform a grace period longer than the
slowest member's declared timeout plus that margin, or the platform's SIGKILL cuts the drain
short; every platform's default is shorter. `docker stop` waits ten seconds (`docker stop -t 60`;
Compose's `stop_grace_period: 60s`), Kamal stops a proxied role with Docker's ten unless
`stop_timeout` says otherwise (the shipped template sets `stop_timeout` and `drain_timeout` to
60), and Kubernetes' `terminationGracePeriodSeconds` is 30 and includes any `preStop` hook. CI
stops the container image with `docker stop -t 60` on every pull request and reads the exit code.

## Multi-server notes

- Sessions, [scheduled-job](jobs.md) claims, [outbox](notifications.md) dispatch and file
  transfers are app- and node-safe on
  a shared database; adding a host is a `servers:` entry.
- Generated export files follow you across nodes when you pick a shared temp store (below);
  the `file` default keeps them on the producing node, which then needs session affinity.
- Framework and app migrations take Flyway's lock, so concurrent node startups serialize.

## Request threads

Every HTTP request runs on a **virtual thread** of its own. Route processing is blocking work, and
a virtual thread that blocks costs nothing while it waits, so no thread count is this runtime's
ceiling on concurrent route execution. Two numbers are. The **connection pool** is one, because a
route that reads or writes the database runs only while it holds a connection. **`maxInFlight`**
is the other: the requests the runtime holds at once, running or waiting for a connection,
before it refuses.

| Key | Default | What it sizes |
| --- | --- | --- |
| `tesseraql.http.maxInFlight` | 40 | Requests other than event streams, held at once (running or waiting) before refusing |
| `tesseraql.http.maxEventStreams` | same as `maxInFlight` | Event streams held open at once before refusing |
| `tesseraql.http.workerThreads` | 10 | Vert.x's own file I/O, such as an upload spooled to disk; no route runs here |
| `tesseraql.http.eventLoopThreads` | `2 x cores` | Connection I/O; blocking work never runs here |
| `tesseraql.http.maxBodyBytes` | 10 MB | Largest request body, uploads included; takes units (`25MB`); `-1` removes the bound |
| `tesseraql.http.maxFormFields` | 10,000 | Fields one form body may carry; `-1` removes the bound |
| `tesseraql.http.idleTimeoutSeconds` | 300 | Silence on a connection before the transport closes it; `-1` removes the bound |

**Beyond `maxInFlight` the runtime answers 503 with `Retry-After`**, immediately, rather than
adding the request to a queue with no bound. Forty is a pool's worth of routes running and three
times that waiting. That leaves room for the ordinary burst a queue exists to absorb, while
keeping the queue a number you can see. It is a number of its own rather than a multiple of
`workerThreads`, because that pool runs no route. A caller
that gets this refusal should retry; a monitor that sees it should read it as "this runtime is
at capacity", which is `TQL-RATE-4293`.

**Event streams are counted separately, under `maxEventStreams`.** A stream holds its connection
for up to fifteen minutes, so counting it as a request meant a handful of open live pages stood
permanently in the number every other route is refused from. Beyond this bound the answer is 503
with `TQL-RATE-4295` and a longer `Retry-After`, because what a stream waits for is another
stream ending. The two codes are different on purpose: a monitor that cannot tell a refused
route from a refused stream cannot tell which number to raise.

A `maxInFlight`, `maxEventStreams`, `workerThreads` or `eventLoopThreads` that is not a positive
integer refuses at startup (`TQL-YAML-1112`) rather than starting with a bound nobody asked for.

**Beyond `maxBodyBytes` the runtime answers 413 with `TQL-SEC-4150`**, draining what remains of
the upload so the refusal actually arrives (an unread stream leaves the client stuck writing).
The one number covers JSON bodies and streamed file uploads alike, so a deployment taking large
imports raises it — and `-1` removes the bound where an edge proxy already enforces one.

**A single form field is bounded by `maxBodyBytes` too, not by a second, smaller number.** The
transport's own per-field ceiling is derived from it rather than configured, and deliberately
sits one delivery above it: two bounds on the same bytes race, and the decoder wins that race
with an untyped 400 where the body limit answers a drained 413. Opting the body bound out with
`-1` does not opt out the decoder's memory bound, which nothing in front of the runtime can
enforce; the ceiling then takes the framework's own default body size.

**`maxFormFields` is transport safety, not a page size.** The count is the one dimension
`maxBodyBytes` cannot bound — a 10 MB body of empty pairs is millions of decoder objects — so it
has a number of its own, set where a form body stops being a page and starts being an attack. A
route's own `pagination.cap` remains the refusal an honest page meets, answered 422 with
`TQL-FIELD-4222`; a deployment where this bound fires first is misconfigured.

**A connection carrying no traffic is closed after `idleTimeoutSeconds`.** A response is written
one chunk at a time and the route waits for each, so a peer that reads a response head and then
stops reading held a thread, a connection and an admission permit for as long as it liked. The
bound is at the transport rather than on the chunk, because only the transport's own close
reclaims all three.

The bound is all-idle, not write-only, so it also closes a connection whose route has read its
request and written nothing for the interval. 300 seconds clears every silent interval this
runtime declares: a statement is bounded at 30 seconds by default, and a live stream heartbeats
every 25. An application that removes its statement bound raises this key or sets `-1`. Under
`tesseraql host` the same number bounds the front door, which is the socket a client actually
connects to.

**A form the decoder refuses says which bound it crossed**, as `TQL-FIELD-2012` at 400 — and as a
renderable fragment when the caller is htmx. That is the boundary worth knowing: below the
transport count a caller meets the route's own 422, above it the transport's 400. Requests the
transport refuses for reasons of its own, such as a missing `Host` header, keep the transport's
own answer rather than being described as form problems.

### The front door's share of each member

Under `tesseraql host`, requests reach a member through the gateway, which applies its own bound
first. It is declared in `tesseraql-stack.yml`:

| Key | Default | What it does |
| --- | --- | --- |
| `tesseraql.gateway.maxConcurrentPerMember` | 40, what a member's own `maxInFlight` admits by default | Non-stream forwards in flight to one member |
| `tesseraql.gateway.maxStreamsPerMember` | same as `maxConcurrentPerMember` | Event-stream forwards held open to one member |
| `tesseraql.gateway.readIdleTimeoutSeconds` | off | Reclaim a forward whose member has sent nothing for this long |

**The share mirrors a member's own gate**, so under a stack a member's whole queue is usable.
The front door reads only the stack file and cannot follow a member's own settings. A member
that declares a larger `maxInFlight` or `maxEventStreams` is therefore named in a warning at
start, with the stack key to raise. **A member's assets and its own health take no permit here,**
as they take none at the member's gate: a stylesheet does not wait on the database, and health
answers when nothing else can.

**Event streams are counted separately here too**, under `maxStreamsPerMember`. A forwarded
response holds its permit until it ends, and an event stream does not end while the page is
open. So a member's live users used to consume its whole forwarding share: with the per-subject
stream cap at four, roughly three signed-in users saturated a member's front door, and every
ordinary request to it was answered 503 while the member itself was idle. Beyond the stream
share the answer is `TQL-RATE-4296`.

A stream is recognised by the path the member mounts it at, not by the `Accept` header. A member
serves MCP over the same endpoint shape and its clients send `Accept: text/event-stream` on
calls that are not streams, and a header is the caller's to set in any case.

The outbound client is sized to the **sum** of the two shares, eighty by default, so an admitted
stream never queues in the transport behind the requests it was separated from.

Beyond the bound the gateway answers 503 with `Retry-After` and `TQL-RATE-4294` — **for that
member only**. A member whose database has stalled holds its own permits and nothing else, so
the rest of the stack keeps serving. That containment is the reason to leave the read-idle
timeout off unless you need it: a hung member and one running a legitimately long report look
the same from the front door, so a timeout short enough to catch the first will eventually
cancel the second. Set it only if you know your slowest legitimate response.

Health (`/_tesseraql/health` and below) is checked before the bound, so no gate refuses it.
Use `/health/live` for liveness: it touches no dependency.

### Connection pools

**Raise `maxInFlight` together with the connection pool.** The pool decides how many routes that
need the database run at once, and `maxInFlight` decides how many more may wait for a connection.
Raising the pool alone shrinks the queue. Raising `maxInFlight` alone lengthens the wait, each
waiter for up to `connectionTimeoutMillis`. The defaults keep four to one.

Each datasource takes its pool settings under `tesseraql.datasources.<name>`:

| Key | Default | What it does |
| --- | --- | --- |
| `maximumPoolSize` | 10 | Connections this datasource may open |
| `connectionTimeoutMillis` | 30000 | How long a borrower waits before failing |
| `minimumIdle` | pool size | Connections kept open when idle |
| `idleTimeoutMillis` | Hikari's, 10 minutes | When a surplus idle connection is retired |
| `maxLifetimeMillis` | Hikari's, 30 minutes | When a connection is retired regardless of use |
| `keepaliveTimeMillis` | Hikari's, 2 minutes | How often the pool pings a connection waiting in it; one in use is covered by TCP keepalive ([dead connections](#dead-connections)) |
| `leakDetectionThresholdMillis` | off | Logs a stack trace for a connection held this long |

The first two are TesseraQL's own defaults rather than the driver pool's, so they cannot
change under you when a dependency changes its mind. `leakDetectionThresholdMillis` stays off
because it is a debugging aid whose log volume is an operator's decision, not a default.

They apply under `dev --embedded-db` too: the embedded server replaces where `main` connects, not
what it declares about its pool.

The stack's framework pool takes the same keys, with the same defaults, under
`framework.datasource` in `tesseraql-stack.yml`
([hosting](hosting.md#the-stacks-own-settings--tesseraql-stackyml)).

Every connection says whose it is, so the database's session views tell the pools apart:

| Opened by | Its name |
| --- | --- |
| An application's pool | `tesseraql/<app>/<pool>`: `main`, `main-jobs`, `main-transfers`, `tenant-<id>`, or a named datasource's name |
| The stack's framework pool | `tesseraql/stack-framework` |
| `tesseraql job run` | `tesseraql/<app>/job-run` |
| Any other CLI or Maven plugin command | `tesseraql/tool` |

Each database keeps the name in its own place:

| Database | The driver property TesseraQL sets | Where the name shows |
| --- | --- | --- |
| PostgreSQL | `ApplicationName` | `pg_stat_activity.application_name` |
| SQL Server | `applicationName` | `sys.dm_exec_sessions.program_name`, `APP_NAME()` |
| Oracle | `v$session.program` | `v$session.program` |
| MySQL, MariaDB | `connectionAttributes` (`program_name`) | `performance_schema.session_connect_attrs`, which MariaDB keeps only with the Performance Schema on |

A character outside printable ASCII is percent-encoded, as are `:` and `,`, and the value is cut
at 63 bytes, so the same name fits every database. A `jdbcUrl` that declares the property keeps
its own value. TesseraQL adds nothing a URL declares, for any database.

Background work — [jobs](jobs.md), [file transfers](file-transfers.md), streams — borrows from
these same pools by default. Contention then shows up as request latency you can measure. Jobs
and file transfers can be given
[pools of their own](#role-pools-jobs-and-file-transfers-off-the-online-pool), as the production
profile a new application carries does ([environment profiles](#environment-profiles)). Watch
`tesseraql_pool_threads_awaiting` in the [metrics](#metrics-prometheus) below: a non-zero reading
means the pool, not the database, is the constraint.

Size it from measured latency rather than from a guess. Concurrency is throughput times latency,
so routes holding a connection for 50 ms saturate a pool of 10 at roughly 200 requests a second,
and routes holding one for a second saturate it at 10. If the answer is "many more connections",
check first whether the database can absorb them: the limit that matters is the one at the far
end, and every pool holds its full size from boot unless `minimumIdle` says otherwise
([capacity](capacity.md#from-one-node-to-replicas)).

### Role pools: jobs and file transfers off the online pool

A long job, or a user's export that takes minutes, holds a connection for as long as it runs.
On the shared pool, that connection is not there for a page. `main` may declare a **second pool
onto its own database** for each kind of such work:

```yaml
tesseraql:
  datasources:
    main:
      jdbcUrl: jdbc:postgresql://db:5432/app
      maximumPoolSize: 10          # requests
      jobPool:                     # every job run
        maximumPoolSize: 3
        minimumIdle: 0
      fileTransferPool:            # the online batch: a list page's export, My exports, a CSV import
        maximumPoolSize: 5
        minimumIdle: 0
```

| Pool | What runs on it |
| --- | --- |
| `jobPool` | Every execution of a `kind: job`: scheduled, from an external scheduler's `tesseraql job run`, manual from the ops console, an `after:` chain. Its steps, its `export:` steps, and a poll-triggered import |
| `fileTransferPool` | The asynchronous transfers a `file-export` or `file-import` route starts. Nothing else bounds how many run at once, so this pool's size is that bound |
| `main` | Requests, including a synchronous `query-export` downloaded in place, the outbox, consumers, and everything else |

- **Each role pool is optional, and takes main's coordinate.** The blocks are
  `tesseraql.datasources.main.jobPool` and `tesseraql.datasources.main.fileTransferPool`, and
  each uses the same keys and defaults as the table above. Where a role is undeclared, that work
  stays on `main`, as before.
- **What decides the pool is the executor, not who started the work.** A manual run from the
  console is a job. A job that names `datasource: main` is a job on `main`, so it runs on
  `jobPool`. A job that names another datasource runs on that datasource's pool.
- **A transfer on a role pool commits its rows and its verdict together,** as on `main`: a role
  pool is main's own database.
- **The scrape reports them as `main.jobPool` and `main.fileTransferPool`,** and a role pool
  with waiters pages like any other (`TQL-OPS-9011`).
- **Declaring one under another datasource refuses the boot with `TQL-YAML-1115`,** because it
  would configure nothing there. So does declaring one on a duckdb `main`, an in-process engine
  with no second pool to open.
- **In a per-tenant mode,** each tenant's pool gets the same roles
  ([multi-tenancy](multi-tenancy.md#schema-per-tenant-and-database-per-tenant)).

`connectionTimeoutMillis` decides what happens beyond a role pool's size: the work queues for a
connection, then fails. A transfer a user is watching can wait a couple of minutes, and a job
inside a night window longer. Past that, the pool is too small for the load, and a visible
failure says so.

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
environment's config. An application with no `config/env/` directory declares no
environments and runs its base configuration under any profile, which is what lets one
`TESSERAQL_ENV` govern a stack whose members, and whose bundled applications, differ. No
profile means no layer — existing apps are unchanged.

This replaces ad-hoc `${...}` indirection for the common cases: put the per-environment
datasource, pool sizing, metrics/audit switches and timeouts in `config/env/<profile>.yml`
and keep secrets in real environment variables or the secret provider as before.

`tesseraql new` writes `config/env/prod.yml` and `config/env/staging.yml` with one pool layout.
`main` stays fixed at its size and waits 10 s for a connection. A `fileTransferPool` of 5 and a
`jobPool` of 3 hold nothing while idle
([role pools](#role-pools-jobs-and-file-transfers-off-the-online-pool)). The files repeat no
credentials, because a role pool takes main's coordinate. The base configuration, which
`tesseraql dev` runs, keeps one pool. With both files present the application declares its
environments, so a profile it has no file for refuses to start.

The profiles also take the JWT secret from `JWT_SECRET`, with no fallback. The base
configuration falls back to a development secret that the framework's own template publishes,
so anyone could mint a token with it. A profile started without `JWT_SECRET` refuses to start
with `TQL-YAML-1101`, naming it. An application generated earlier, whose profile still inherits
the fallback, is refused under any named profile with `TQL-SEC-4154`.

## Time and language

Three things follow the JVM's zone or locale unless the application declares its own:

| Key | What it decides | Undeclared |
| --- | --- | --- |
| `tesseraql.files.timezone`, `tesseraql.files.locale` | How an export renders dates, times and numbers: every temporal cell of a workbook, and the columns typed `date`, `datetime` or `number` on csv and pdf. An export's own `timezone:` and `locale:` win | The JVM's |
| `tesseraql.security.conditions.zone` | The zone a role grant's `hours` condition is judged in | The JVM's |
| `tesseraql.i18n.defaultLocale` | The language messages fall back to | `en` |

The JVM's zone is the developer's machine's in development, and UTC in the container image,
which sets no `TZ`. So an application that leaves the zone to the JVM exports different times
in development and in production, and judges business hours in UTC. `tesseraql new` declares
all four keys (UTC, `en`) for its owner to change. A lint warns (`TQL-YAML-1116`) about an
export that renders dates with neither zone declared.

## Business-route audit log and error pages

Opt in with `tesseraql.audit.routes.enabled: true`: every compiled route invocation lands one
durable row in `tql_route_audit` — who (`actor`, `tenant_id`), what (`route_id`, method, path,
status, duration), when, correlated by `trace_id` — with the **declared** input params as
JSON. Fields carrying a `mask:` or `classification:` are excluded wholesale, so sensitive
values can never reach the trail; a failed audit insert never fails the request. The
framework's Java-mounted system routes are outside the trail: sign-in, the three sign-outs,
elevation, the session-token exchange, invite and reset acceptance, and the IAM Admin bulk
disable land no row (the token mint is logged at INFO instead).
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

**The framework's own `System.Logger` lines ride the same provider.** `tesseraql-core` may not
depend on SLF4J, so it logs through `System.Logger`, and six other modules follow it — forty-nine
call sites in all. Until 2026-09-09 nothing bridged them: with no `System.LoggerFinder` on the
classpath the JDK falls back to `java.util.logging`, and those lines arrived as two-line
unstructured records with no MDC, unmoved by `--log-format` and ungoverned by `--log-level`. The
distribution now ships `slf4j-jdk-platform-logging` beside the provider, so every line the
framework emits goes through one backend.

The bridge is declared in the CLI, never in the runtime: a `System.LoggerFinder` is a JVM-global,
once-per-process choice, and only the process owner may make it — a host embedding the runtime
keeps its own. `tesseraql-host` inherits it transitively and the container image copies the runtime
closure, so neither needed a change.

The ids travel on the exchange rather than on the thread, and are copied into the MDC around
each step, so a step handed to an execution lane still logs under the request that started it.

An **opt-in HTTP access log** rides the same correlation: `tesseraql.logging.accessLog: true`
emits one line per request on the `tesseraql.access` logger —
`GET /api/users 200 12ms route=users.search user=alice`.

## Safety valves and multi-node semantics

**SQL statement timeout.** Every route SQL statement is bounded by default: 30 seconds, the
app-wide `tesseraql.sql.timeoutSeconds`, or a per-binding `timeoutSeconds:` override —
an explicit `0` opts a deliberately long-running statement out. A runaway query is cancelled
by the driver instead of holding a pool connection forever. The same key bounds **every
declared statement**: contract SQL (an identity realm's, SCIM inbound provisioning's),
workflow guards, stamps and escalation SQL, validation rules, decision lookups, enrichment
lookups, and file-transfer SQL — because there is no argument for any of them being allowed
to run longer than a page.

**SQL statement spans.** Every executed statement opens one `tesseraql.sql.execute` span
carrying a `surface` attribute (`route` | `command` | `job` | `chunk` | `contract` |
`transfer` | `workflow` | `validation` | `decision`), the statement's `sqlId` (a path for
application SQL, the contract key for contract SQL), and its row or affected count. One span name answers "all SQL time in this
trace"; the `surface` attribute answers "why is sign-in slow" without a second name to
enumerate.

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
the inbox table). At most one lease claim runs per second per node per route — an upper
bound, not a lower one, because no second claim starts while one is still out. Claims are
first-come-first-served, so a quiet node leaves its share for the busy ones, and `burst`
remains node-local smoothing. Precision is bounded, not perfect: a volley straddling a window
boundary can briefly see up to two windows' budget. When the ledger does not answer the
limiter degrades to the per-node budget for that window and logs with backoff — rate limiting
protects resources; it must never become the outage itself. "Does not answer" is measured
against the ledger's own normal latency — four times that, or one window if it has never
answered — because a fixed deadline cannot tell a slow ledger from an unreachable one. While
it is down each node serves the declared rate, so a cluster of N nodes serves N times it. A
claim that never returns at all leaves its node on the per-node budget until it ends, and is
reported at `ERROR` naming that consequence.

**Held results are per node; their invalidation crosses nodes through a version row.** A
source's `cache: {maxAge, tables}` ([response-shaping.md](response-shaping.md#holding-a-result))
holds its statement's rows in the node's own memory, bounded by `tesseraql.cache.maxEntries`
and `tesseraql.cache.maxEntryRows`. A command's `invalidates:` drops them on the node that
served the write at once and raises a per-table version in `tql_catalog_version` on the main
database — the row the code catalogs already read. Every other node re-reads that row at most
once every five seconds, so a peer serves the old rows for at most that long after a write it
did not see. Underneath sits `maxAge`: a write nothing declares shows when the hold expires.
`tesseraql.cache.enabled: false` makes every declaration execute, for the incident where a
hold misbehaves; `POST /_tesseraql/ops/cache/invalidate?tables=…` drops by table cluster-wide.

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
- **`file`** — the default and the pre-cluster behavior: node-local under `tmp/tesseraql`
  inside the work directory; keep session affinity at the load balancer, or point the
  directory at a shared filesystem if you already run one.

Whichever store is chosen, an in-flight upload spools into `tmp/tesseraql/uploads` inside the
work directory before any of it applies: a request body is on disk before the route runs. Both
paths resolve through `tesseraql.app.work`, so relocating the work tree moves the temp store and
the upload spool together.

The runtime creates the upload directory at startup and writes a probe file into it. If either
fails, the boot fails with `TQL-YAML-1113` naming the directory. That is deliberate: every
url-encoded and multipart POST spools through it, sign-in included, so a runtime that cannot
write there can answer no form at all. Under `tesseraql host` this stops the whole stack, not
one member. The upload subtree stays node-local even where the `file` store points at shared
storage, because every form post stats it from the event loop.

## Dead connections

A connection can die at either end without a word. The database host crashes, or the network
drops packets in the middle of a statement. Or a TesseraQL node is killed without closing its
pools. Each end has its own defence.

**TesseraQL's end.** On PostgreSQL, every connection runs TCP keepalive with TesseraQL's own
timings. After 30 s with no traffic the kernel probes the database host, and three unanswered
probes 10 s apart reset the socket. So a statement whose host has gone fails in about a minute,
and the pool drops the connection, where the request used to wait on a read forever. A
statement that is long and silent but alive is never cut, because the host's kernel answers the
probes while the backend works. HikariCP covers the connections waiting in the pool: one idle
for more than half a second is validated when borrowed, and an idle one is pinged every
`keepaliveTimeMillis`.

A `jdbcUrl` can change this. `socketFactory=` names another factory, such as a cloud provider's
connector, and `tcpKeepAlive=false` turns keepalive off. Where the JDK cannot set the timings on
the platform, the operating system's apply, and the log says so once. Data the driver sent that
the host never acknowledged is bounded by TCP retransmission instead: up to about 15 minutes on
Linux.

The other databases' drivers keep alive as each allows:

| Database | TesseraQL's end |
| --- | --- |
| SQL Server | The driver turns keepalive on itself, with 30 s idle and probes 1 s apart. TesseraQL adds nothing |
| Oracle | TesseraQL sets `oracle.net.keepAlive` and the same timings: `oracle.net.TCP_KEEPIDLE` 30, `oracle.net.TCP_KEEPINTERVAL` 10, `oracle.net.TCP_KEEPCOUNT` 3 |
| MariaDB | Keepalive is on by default. TesseraQL sets the timings: `tcpKeepIdle` 30, `tcpKeepInterval` 10, `tcpKeepCount` 3 |
| MySQL | Keepalive is on by default, with the operating system's timings, because the driver has no property for them. Set them on the host that runs TesseraQL |

For MySQL, these host settings give the same minute:

```
net.ipv4.tcp_keepalive_time = 30
net.ipv4.tcp_keepalive_intvl = 10
net.ipv4.tcp_keepalive_probes = 3
```

On a machine they go in `/etc/sysctl.d/`. On Kubernetes they go in the pod's
`securityContext.sysctls`: each network namespace has its own, recent versions accept these
three as safe, and older ones need them allowed on the kubelet. A per-socket timing, as on
PostgreSQL, Oracle and MariaDB, overrides them.

**The database's end.** When a node dies without closing its pools, each backend it held stays
until the server notices. With the operating system's defaults, that takes more than two hours.
Meanwhile the backend holds a connection slot, and one inside a transaction holds its locks.
On PostgreSQL, these server settings shorten that:

| Setting | What it does | A starting value |
| --- | --- | --- |
| `tcp_keepalives_idle`, `tcp_keepalives_interval`, `tcp_keepalives_count` | The server probes an idle client and ends the backend of one that has gone, freeing its slot and its locks | 60 s, 10 s, 6: about two minutes |
| `idle_in_transaction_session_timeout` | Ends a session that sits inside a transaction doing nothing. TesseraQL does not idle inside a transaction, so any value longer than the longest gap between two statements of one transaction is safe | 10 min |
| `client_connection_check_interval` | PostgreSQL 14 and later, on Linux: a query whose client has gone is cancelled rather than run to completion | 10 s |

On the other databases:

| Database | Setting | What it does |
| --- | --- | --- |
| SQL Server | None needed | It probes idle clients itself (the TCP/IP "Keep Alive" setting, 30 s by default) and ends a vanished client's session |
| Oracle | `SQLNET.EXPIRE_TIME` in the server's `sqlnet.ora` | Dead Connection Detection: the server probes an idle client every that many minutes. It is off by default, and 1 to 10 is a start |
| MySQL, MariaDB | `wait_timeout` | Ends a session idle this long, 8 hours by default. HikariCP pings its idle connections every 2 minutes, so any value above that leaves the pool alone. 10 min is a start |
| MariaDB | `idle_transaction_timeout` | Ends a session that sits inside a transaction doing nothing, as PostgreSQL's does |

They are the database's parameters, so TesseraQL does not set them per session. On a managed
database they are set in its parameter group or equivalent.

Every connection says whose it is ([connection pools](#connection-pools)), so a query lists what
each node holds, and ends a dead node's leftovers. On PostgreSQL:

```sql
select pid, application_name, client_addr, state, state_change
from pg_stat_activity
where application_name like 'tesseraql/%'
order by client_addr, application_name;

select pg_terminate_backend(pid)
from pg_stat_activity
where application_name like 'tesseraql/%' and client_addr = '10.0.3.17';
```

On the other databases, the views in the connection-pools table find them by the same name:
`sys.dm_exec_sessions` and `KILL` on SQL Server, `v$session` and `ALTER SYSTEM KILL SESSION` on
Oracle, `performance_schema.session_connect_attrs` and `KILL` on MySQL and MariaDB.

Behind a pooler such as PgBouncer, each keepalive runs between its own two ends, `client_addr`
is the pooler's, and the pooler has settings of its own for both.

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
| `tesseraql_http_in_flight{kind}` | requests (`request`) and event streams (`stream`) holding a permit at this runtime's gate; `forward` and `streamForward` are the gateway's share for this member | `sum by (instance) (tesseraql_http_in_flight{kind="request"}) > 30` |
| `tesseraql_http_refused_total{code}` | admission refusals by code: `TQL-RATE-4293`/`4295` at the member, `4294`/`4296` at the front | `rate(tesseraql_http_refused_total[5m]) > 1` |
| `tesseraql_lane_in_use{lane}`, `tesseraql_lane_rejected_total{lane}` | a lane's units in flight against its `maxConcurrency`, and what it refused | `increase(tesseraql_lane_rejected_total[5m]) > 0` |
| `tesseraql_pool_threads_awaiting{pool}` | threads waiting for a connection: the pool, not the database, is the constraint | `tesseraql_pool_threads_awaiting > 0` for a minute |

The last four are the capacity signals: which bound is binding, and what was refused because
of it. The same conditions page through the alerts channel as `TQL-OPS-9011` and `9012`
([notifications](notifications.md#operations-alerts)), so an operator without a Prometheus
still hears them.

The scrape is **bearer + `ops.metrics.view` policy** by default (labels reveal route ids);
give the scraper a token via `bearer_token_file`, or set
`tesseraql.metrics.unauthenticated: true` for a cluster-internal scrape the network already
guards. OTLP push (`tesseraql.otel.otlp.endpoint`) is independent and now carries the same
histograms. A ready-made Grafana dashboard ships at
[deploy/grafana/tesseraql-dashboard.json](https://github.com/ingcreators/tesseraql/blob/main/deploy/grafana/tesseraql-dashboard.json),
with a Capacity row over the four families above, and the alerting rules at
[deploy/prometheus/tesseraql-alerts.yml](https://github.com/ingcreators/tesseraql/blob/main/deploy/prometheus/tesseraql-alerts.yml)
carry the sample alerts of this table. Every expression in both names a family the sources
declare; a test holds that.

Under a stack, scrape the origin too. Each member's scrape is at `/<name>/_tesseraql/metrics`,
and the stack surface's, which serves sign-in, is the origin's `/_tesseraql/metrics` once the
stack file declares `metrics:` ([hosting](hosting.md#the-stacks-own-settings--tesseraql-stackyml)).
It reports the pool sign-in rides as `pool="main"`, so the pool-waiters alert above covers
sign-in as well.

## Next

- [capacity.md](capacity.md) — sizing a node: the arithmetic, the signals, `tesseraql bench`.
- [kubernetes.md](kubernetes.md) — the chart, the manifests, the probes and the drain on a cluster.
- [promotion.md](promotion.md) — moving a change between environments.
- [upgrading.md](upgrading.md) — moving to a new framework release.
- [reference-config.md](reference-config.md) — every configuration key, with what reads it.
- [ops-console.md](ops-console.md) — watching the running system.
