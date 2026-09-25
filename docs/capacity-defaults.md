# Capacity defaults: the front door admits what a member admits, no bound derives from a pool nothing runs on, and a new application carries a production profile that separates its pools

> **Status: designed 2026-09-25, against main `e620293a7` (0.19.0-SNAPSHOT).** It comes out of
> [gateway-performance.md](gateway-performance.md). That measurement found the front door refusing
> 65-90% of a load test with its defaults (row 2 there). The investigation then showed why: the
> bound derives from a worker pool that no route has run on since the edge moved to virtual threads.
> The maintainer then asked whether 10 connections suit an ordinary business application, whether
> the minimum is 0, and whether a new application could start from best-practice pool settings,
> batch included. They chose every recommended option.
>
> There are three slices:
>
> **S1 (the front door and the bounds).** The front door's per-member share defaults to what a
> member's own gate admits. Assets and health pass the share, as they already pass the member's
> gate. `maxInFlight` stops deriving from `workerThreads`. [capacity.md](capacity.md) describes the
> model that runs.
>
> **S2 (the pools a deployment names).** `tesseraql.batch.datasource` names the pool jobs run on.
> The stack's framework pool is sized by declaration rather than by HikariCP's own defaults.
>
> **S3 (the skeleton).** `tesseraql new` writes a production profile that separates the online and
> batch pools. The stack marker states the production posture.

## What is true today

| # | Subject | Finding |
| --- | --- | --- |
| 1 | Where a route runs | Each request's pipeline runs on its own virtual thread ([http-edge.md](http-edge.md) decision 1). The Vert.x worker pool holds only Vert.x's own file I/O, and the record says no request runs there any more. `tesseraql.http.workerThreads` defaults to 10 (`TesseraqlRuntime.java:357`). The Javadoc above it says the pool still bounds "the stack relay and the multi-app gateway". Neither uses it: no main source calls `executeBlocking`, and the front door runs on its own Vert.x. |
| 2 | What derives from `workerThreads` | `tesseraql.http.maxInFlight` defaults to `workerThreads` × 4 = 40 (`TesseraqlRuntime.java:395`). `maxEventStreams` defaults to `maxInFlight` (`:421`). The gateway's share defaults to the stack's `workerThreads` (row 3). **Raising the number of threads that read files raises the queue a runtime admits and the share the front door forwards.** Neither has run on those threads since row 1. |
| 3 | The front door's per-member share | `tesseraql.gateway.maxConcurrentPerMember` defaults to the stack file's `tesseraql.http.workerThreads`, else 10 (`MultiAppGateway.java:406`). `maxStreamsPerMember` defaults to the share × 4 (`:507`), and the outbound client is sized to the sum of the two. The rule comes from [http-threading.md](http-threading.md) decision 5: "a front door that admits more than a member can run only moves the queue one hop earlier, and one that admits fewer makes the member's own pool unreachable". It was written when a route ran on the worker pool. |
| 4 | So a member's own gate is unreachable | Every deployment is a stack ([stack-architecture.md](stack-architecture.md) decision 12). The front door refuses beyond 10 with `TQL-RATE-4294`, before the member's gate of 40 (`TQL-RATE-4293`) is reached. Measured: 65% refused at `c`=32 on a 33-byte route ([gateway-performance.md](gateway-performance.md) row 2). [capacity.md](capacity.md) line 24 names the share's twin as "the member's `maxInFlight`". The code disagrees. |
| 5 | What the share counts | Every non-stream forward takes a permit (`StackRelay.java:662`). That includes the member's asset mount, `<base>/assets` (`AssetRoutes.mountOf`), and its health, `<base>/_tesseraql/health`. The member's own gate lets both through (`HttpAdmission.java:129`, from http-threading.md decisions 3 and 6: a stylesheet must not wait on the database, and health must answer when nothing else can). The front door puts that coupling back. A browser opens up to six connections per origin, so two people loading a page at once can pass 10. Forwards to the stack surface (sign-in, account, the portal) take no permit. |
| 6 | The capacity page | [capacity.md](capacity.md) describes `workerThreads` as "routes executing at once: the pool every request runs on" (line 18), and `maxInFlight`'s twin as `workerThreads` (line 20). Its worked example concludes "the knob to raise here is `workerThreads`" (line 127). The connection budget reads "`maximumPoolSize` × replicas" (line 142). That counts neither the members of a stack nor the stack's own pools, and does not say that pools are fixed-size (row 7). |
| 7 | Pools are fixed-size | `DataSources` declares `maximumPoolSize` 10 (`DataSources.java:29`) and a `connectionTimeout` of 30 s (`:32`). It passes `minimumIdle` only when one is declared (`:265`). HikariCP 7.1.0 initialises `minIdle` to −1, and `validateNumerics` replaces any value below 0 or above the maximum with `maxPoolSize` (read with `javap`). **So every pool holds its full size from boot, and `idleTimeoutMillis` does nothing unless `minimumIdle` is declared below the maximum.** A connection budget is therefore a standing number, not a peak. |
| 8 | The stack's own pools | A stack whose `tesseraql-stack.yml` declares `framework.datasource` gets `tesseraql-stack-framework`. It is built by `DataSources.create(poolName, override)` (`DataSources.java:111`) on a bare `HikariConfig`, so it takes **HikariCP's defaults (10 connections, 30 s) by inheritance, not by declaration**. The coordinate carries `jdbcUrl`, `username` and `password` and nothing else (`StackSettings.java:172`). The surface runtime holds a main pool of its own on the same coordinate. Once the stack supplies the framework pool, a member may not declare `tesseraql.framework.datasource` (`TQL-APP-4212`). |
| 9 | Where a job runs | A job that declares no `datasource:` runs on `main`, in the runtime (`JobRunners.java:37`) and in the CLI's `job run` (`JobCommand.java:417`), which resolves on its own. A `perTenant` job is routed to its tenant's pool **only when it is on `main`** (`JobRunners.java:65`, `TesseraqlRuntime.java:2504`). No application-wide key chooses where jobs run. The linter checks a job's declared name with `TQL-YAML-1035` and lints the job's SQL against that datasource's engine (`DuckDbRules.java:79`). **Jobs do not run in execution lanes.** A lane is route admission (`AdmissionSpec.lane`, `threading.lanes` in `LaneConfigs`). Each firing runs on its own virtual thread, and `overlap:` governs a job only against itself, so the number of jobs holding connections is the number running at once. |
| 10 | What the skeleton writes | `tesseraql new` writes `config/application.yml` with `db.main.*`, including `maximumPoolSize: 10`, and `tesseraql.yml` with `datasources.main.maximumPoolSize: ${db.main.maximumPoolSize:10}` (`AppScaffolder.java:249`). It writes no `config/env/`. The stack marker's `framework.datasource` block is commented out (`NewCommand.java:38`). `ScaffoldedConfigKeys` fails the build on a key the skeleton emits that nothing reads. `examples/scaffold-demo-app` is regenerated byte for byte by the dogfood test. |
| 11 | Profiles | `config/env/<profile>.yml` overlays the base configuration and sits under Studio's overlay. Maps merge and a list replaces a list. A named profile whose file is missing fails startup, and an application with no `config/env/` runs its base configuration under any profile ([deployment.md](deployment.md), "Environment profiles"). [promotion.md](promotion.md) names `staging` and `prod`. |
| 12 | The framework pool, recommended | [framework-datasource.md](framework-datasource.md) decision 2: the same database with a separate pool, sized small, because session traffic is millisecond point queries and 5-10 connections carry a lot of sign-ins. |

## The decisions

### 1 — The front door's share defaults to what a member admits

`tesseraql.gateway.maxConcurrentPerMember` defaults to **40**, the member's default `maxInFlight`.
`maxStreamsPerMember` defaults to **40**, the member's default `maxEventStreams`. The stack's
`workerThreads` no longer feeds either. The declared keys keep their meaning, and the outbound client
is still sized to the sum of the two.

A member that declares its own `maxInFlight` or `maxEventStreams` above the stack's share gets a
**boot warning** that names the stack key to raise. The host already holds every member's
configuration, and the same pattern is how it tells a member that its thread sizing is the host's
decision (`warnOnMemberThreadSizing`).

**Refused: reading each member's bound live, so that the share follows it without a stack key.**
The outbound client is one per front door and is sized at start. A replace can change a member's
bound mid-flight. The share is a host decision about how much the front door holds, so a visible
warning is the smaller change. **Trigger:** the warning fires in real stacks, meaning members
diverge in practice.

### 2 — Assets and health pass the front door's share, as they pass the member's gate

A forward whose raw target lies under `<wire prefix>/assets/` or `<wire prefix>/_tesseraql/health`
takes no permit. This is the member gate's own rule, applied at the front door. The comparison uses
the raw target, the way `isStreamForward` does. A percent-encoded or dot-segmented spelling
therefore falls into the share, which is the fail-safe direction: it is counted, as every forward is
today.

### 3 — `maxInFlight` defaults to 40, and no longer derives from `workerThreads`

The number is unchanged. What changes is that it is stated rather than computed from a pool that
reads files. `maxEventStreams` still defaults to `maxInFlight`. `workerThreads` keeps its default of
10 and sizes Vert.x's own file I/O, which is all that pool does now. The stale Javadoc above
`vertxOptions` is corrected.

**Refused: deriving it from the main pool (`maximumPoolSize` × 4).** The front door cannot see a
member's pools, and decision 1's default has to be a number the front door knows. A DuckDB `main`
pool of 4 would also quietly shrink an analytics application's queue to 16.

### 4 — capacity.md describes the model that runs

- **The table.**
  - `maxInFlight` is the runtime's ceiling on requests in flight. Its twin is `main`'s
    `maximumPoolSize`: raise them together so that a queue remains.
  - `maximumPoolSize` bounds routes that need a connection.
  - `workerThreads` sizes file I/O.
  - The front door's share mirrors the member's bounds.
- **The worked example.** Measured again in S1 under the model that runs.
- **The connection budget.** Per node, add up every pool each member declares, the surface runtime's
  `main`, and the stack framework pool when one is declared. Each is fixed-size unless `minimumIdle`
  is lowered (row 7). Multiply by nodes, and compare the total with the database's
  `max_connections`, which is 100 by default on PostgreSQL.
- **Rarely used members.** A member that sits idle most of the day can declare `minimumIdle` below
  its maximum, together with `idleTimeoutMillis`, so that idle members stop holding their share of
  the budget.

The page reads [gateway-performance.md](gateway-performance.md)'s answer as a pointer, not a copy.

### 5 — `tesseraql.batch.datasource` names where jobs run

The key names a datasource under `tesseraql.datasources`, and defaults to `main`. **A job that
declares no `datasource:` runs on it exactly as if it had declared it.** Every rule that holds for a
declared job datasource holds unchanged, including the linter's reading of the job's SQL against that
datasource's engine.

The rule is resolved in two places today, and S2 changes both:

- `JobRunners.jobDataSource` serves the runtime's start paths: a scheduled firing, a manual run and
  `runJobForAllTenants`.
- The CLI's `job run` resolves on its own, in `JobCommand.jobDataSource` (`JobCommand.java:417`).
  It opens a `DriverManagerDataSource` from the declared coordinate.

S2 makes both read the key through one shared rule, so the CLI cannot drift from the runtime.

- **A `perTenant` job keeps today's routing:** its tenant's pool, or `main` where the tenant has
  none. The key does not apply to it. A batch pool per tenant would multiply pools by tenants, and
  moving these jobs off `main` would silently drop their tenant routing (row 9).
- **An undeclared name** is `TQL-YAML-1035` from `tesseraql lint`, the existing finding for a job's
  undeclared datasource. At boot it is refused with **`TQL-BATCH-4213`**, the way the framework key's
  typo is refused (`TQL-APP-5205`). A typo that fell back to `main` would defeat the separation
  someone configured.
- **Which jobs move:** every job that names no datasource, including the file exports and imports
  that a list page or the exports inbox starts. Taking bulk work off the online pool is the point of
  the key.

**A correction to the proposal as discussed.** The conversation proposed a lint comparing an
execution lane's `maxConcurrency` with the batch pool's size. Lanes admit routes, not jobs (row 9),
so that lint would compare two unrelated numbers, and it is dropped. What decides whether a job
queues or fails is the batch pool's size together with its `connectionTimeout` (decision 7).

### 6 — The stack's framework pool is sized by declaration

`framework.datasource` in `tesseraql-stack.yml` accepts `maximumPoolSize`, `minimumIdle` and
`connectionTimeoutMillis`, read through the same builder as an application's datasource. The
defaults are TesseraQL's own: 10 connections and 30 s. Those are the numbers in force today, but now
they are declared rather than inherited from a dependency. This is the same move `DataSources` made
for applications ([http-threading.md](http-threading.md) decision 2). The stack marker shows `5`
(decision 8).

### 7 — A new application carries a production profile that separates its pools

`tesseraql new` writes `config/env/prod.yml`:

```yaml
# The production profile: selected by TESSERAQL_ENV=prod (docs/deployment.md, "Environment
# profiles"). Pools are fixed-size unless minimumIdle is lower, so the connection budget is a
# standing number: per node, every pool of every member, plus the stack's own. Keep it under the
# database's max_connections (PostgreSQL's default is 100). docs/capacity.md has the arithmetic.
tesseraql:
  datasources:
    main:                                   # online routes
      maximumPoolSize: ${db.main.maximumPoolSize:10}
      connectionTimeoutMillis: 10000        # a request that waited 10 s has lost its reader
    batch:                                  # jobs: same database, a small pool, closed when idle
      jdbcUrl: ${db.main.url}
      username: ${db.main.username}
      password: ${db.main.password}
      maximumPoolSize: ${db.batch.maximumPoolSize:3}
      minimumIdle: 0
      idleTimeoutMillis: 600000
      connectionTimeoutMillis: 300000       # jobs that fire together queue briefly, then fail visibly
  batch:
    datasource: batch
```

It also writes `config/env/staging.yml` with the same pool layout. Staging exists to rehearse
production ([promotion.md](promotion.md)). An application that declares any environment also
refuses one it does not declare (row 11), so without the file `TESSERAQL_ENV=staging` would fail
startup.

- **The base configuration does not change.** The development loop runs one pool, and
  `tesseraql dev --embedded-db` works as before.
- **`main` waits 10 s for a connection.** A request that has waited that long has lost its reader.
  Failing it frees its admission permit, and it makes the pool alert (`TQL-OPS-9011`) the signal
  instead of a queue of timeouts. The same timeout also bounds how quickly readiness notices a
  database that is down (the comment at `DataSources.java:258`).
- **`batch` waits 5 min.** Jobs that fire together at the top of a night window should queue briefly
  rather than fail. A longer wait means the pool is too small for the schedule, which should fail
  visibly rather than stretch the window.
- **`batch` holds nothing when idle** (`minimumIdle: 0`). A pool used for minutes a day should not
  hold its share of the budget all day.
- **The connection coordinate comes from the existing `db.main.*` placeholders,** so there is no
  second place to put credentials and no inheritance mechanism is needed.
- **`ScaffoldedConfigKeys` registers every new key,** so each one is read somewhere by the time the
  skeleton emits it. S2's keys therefore exist first.

### 8 — The stack marker states the production posture

The commented `framework.datasource` block in the marker `tesseraql new` writes gains two lines. The
first says it is recommended in production: sign-in then rides its own pool, and a long business
query cannot starve it. The second shows `maximumPoolSize: 5`. It stays commented out, because it
names a real coordinate.

### 9 — What this design refuses, each with its trigger

| Refused | Why | Trigger |
| --- | --- | --- |
| Writing best-practice values into the base configuration | A written value is frozen per application: when the framework improves a default, generated applications keep the old one. What is right everywhere becomes a framework default (decisions 1-3). What depends on the deployment goes in a profile (decision 7). | None |
| Raising the default `maximumPoolSize`, or making pools elastic by default | HikariCP recommends fixed-size pools for responsiveness to bursts, 10 is ample for a business application's rate, and a larger default multiplies into every member on every node (decision 4's budget) | Evidence that typical deployments have budget to spare and are pool-bound |
| Lowering `minimumIdle` by default | A burst would pay connection setup (authentication, TLS) on the requests that can least afford it | Stacks with many rarely used members reaching `max_connections` at idle, which decision 4's guidance answers per member first |
| A `from: main` datasource inheritance | The skeleton's placeholders already reuse one coordinate | Hand-written configurations duplicating coordinates in practice |
| A batch pool for `perTenant` jobs | It multiplies pools by tenants, and today's routing is correct | A database-per-tenant application asking for batch isolation |
| A lint of lanes against the batch pool | Lanes do not govern jobs (decision 5) | Jobs gaining lanes |
| epoll, an in-process handoff, process separation | [gateway-performance.md](gateway-performance.md) | The triggers recorded there |

### 10 — Docs, CHANGELOG, codes

- **S1:** [capacity.md](capacity.md) is rewritten (decision 4). [deployment.md](deployment.md)'s
  front-door table gets the new defaults and the exemption. [http-threading.md](http-threading.md)
  decision 5 gets a dated note pointing here. [hosting.md](hosting.md) is checked for the old
  default.
- **S2:** [jobs.md](jobs.md) and [deployment.md](deployment.md) describe the batch key and the
  stack pool keys, and the generated configuration reference is regenerated.
  `TQL-BATCH-4213` is allocated.
- **S3:** [deployment.md](deployment.md)'s profile section points at the generated files, and the
  getting-started text `tesseraql new` prints is checked.
- **CHANGELOG:** each slice, under Changed or Added.

## What this breaks

1.0 has not shipped, so no migration steps follow. This records what changes and why.

- **The front door forwards up to 40 requests per member, where it forwarded 10.** Above that, a
  member's own gate usually refuses first (`TQL-RATE-4293` where the answer was `TQL-RATE-4294`).
- **Asset and health forwards no longer count against the share.**
- **An application that raised `workerThreads` to raise its queue** gets 40 unless it declares
  `maxInFlight`.
- **Applications generated after S3 declare `prod` and `staging`,** and refuse any other profile
  name. Existing applications and the examples not regenerated by the dogfood test are unchanged.
- **Jobs move only where `tesseraql.batch.datasource` is set.** The default is `main`, so existing
  applications are unchanged.

## The slices

### S1 — the front door and the bounds (M)

Decisions 1-4.

- **Tests:**
  - The gateway's default share is 40, with streams also 40, and a declared key wins.
  - The warning appears for a member that declares a larger `maxInFlight`.
  - At the front door, an asset and a member's health answer 200 while the member's share is
    exhausted, and a percent-encoded asset path is counted.
  - The runtime's `maxInFlight` is 40 with `workerThreads: 20` declared.
  - `HttpServerOptionsLedgerTest` and `HttpClientLedgerTest` stay green.
- **Measurement:** row 2 of [gateway-performance.md](gateway-performance.md) again, at the defaults
  and on the same harness shape. The expectation is no refusals at `c`=32, and the member's
  `TQL-RATE-4293` beyond 40 at `c`=64. The result is recorded there.
- **Docs:** capacity.md with its worked example re-measured, deployment.md, and http-threading.md's
  note.

### S2 — the pools a deployment names (M)

Decisions 5 and 6.

- **Tests:** a Testcontainers integration test, per the rule that database-backed stores are tested
  against a real database:
  - A job with no `datasource:` runs on the `batch` pool (read from the pool's name in
    `pg_stat_activity`, or from the pool's own metrics).
  - A `perTenant` job keeps its tenant's pool.
  - An undeclared name is refused at boot with `TQL-BATCH-4213` and reported by the linter with
    `TQL-YAML-1035`.
  - The CLI's `job run` lands on the same datasource as the runtime for the same configuration.
  - The stack framework pool honours `maximumPoolSize`, and its default is declared as 10.
- **Docs:** jobs.md, deployment.md, and the regenerated configuration reference.

### S3 — the skeleton (S)

Decisions 7 and 8.

- **Tests:**
  - `AppScaffolder` renders `config/env/prod.yml` and `staging.yml` with the layout above.
  - `ScaffoldedConfigKeys` registers every new leaf key.
  - `examples/scaffold-demo-app` is regenerated through the dogfood ritual.
  - A generated application boots under `TESSERAQL_ENV=prod` against Testcontainers PostgreSQL with
    two pools, `main` fixed at 10 and `batch` at 0 idle, and a job lands on `batch`.
- **Docs:** deployment.md's profile section and the `new` command's next steps.

## Error codes

| Code | Slice | Meaning |
| --- | --- | --- |
| `TQL-BATCH-4213` | S2 | `tesseraql.batch.datasource` names a datasource that `tesseraql.datasources` does not declare; the runtime refuses to start |
