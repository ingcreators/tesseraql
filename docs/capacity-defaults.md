# Capacity defaults: the front door admits what a member admits, no bound derives from a pool nothing runs on, and a new application carries a production profile that separates its pools

> **Status: designed 2026-09-25, against main `e620293a7` (0.19.0-SNAPSHOT).** It comes out of
> [gateway-performance.md](gateway-performance.md). That measurement found the front door refusing
> 65-90% of a load test with its defaults (row 2 there). The investigation then showed why: the
> bound derives from a worker pool that no route has run on since the edge moved to virtual threads.
> The maintainer then asked whether 10 connections suit an ordinary business application, whether
> the minimum is 0, and whether a new application could start from best-practice pool settings,
> batch included. They chose every recommended option.
>
> There are four slices:
>
> **S1 (the front door and the bounds).** The front door's per-member share defaults to what a
> member's own gate admits. Assets and health pass the share, as they already pass the member's
> gate. `maxInFlight` stops deriving from `workerThreads`. [capacity.md](capacity.md) describes the
> model that runs. **Shipped, #1458**, as designed, with one correction. Beyond 40 the refusal
> stays the front door's `TQL-RATE-4294`: its count includes the relay's own time, so it reaches
> the shared number first ("What this breaks"). Measured again at the defaults, 32 workers are
> refused none of the time, where 65% were refused before. capacity.md's worked example, measured
> again on a 5 ms route, saturates at the pool, and doubles with a pool of 20 and a queue of 80.
>
> **S2a (two role pools on `main`).** `main` may declare a `jobPool`, which every job the batch
> platform runs uses, and a `fileTransferPool`, which the asynchronous file transfers a route starts
> use. Both open onto main's own database. In a per-tenant mode, each tenant's pool gains the same
> two roles. A transfer's bookkeeping follows the database rather than the pool object.
> **Shipped, #1460**, as amended. One addition: the ops dashboard's pool alert and the scrape both
> report main's role pools (`main.jobPool`, `main.fileTransferPool`), so a role pool with waiters
> pages `TQL-OPS-9011` like any other. The readiness probe still walks only the named pools. A
> revert probe that sent route transfers back to the online pool turned the integration test red.
>
> **S2b (the stack's framework pool).** It is sized by declaration rather than by HikariCP's own
> defaults. **Shipped, #1461**, as designed. The pool is built by the same builder as the role
> pools. Under `--embedded-db`, the embedded server supplies the coordinate and the stack file
> still supplies the sizing. A revert probe that restored the bare HikariCP configuration turned
> the integration test red. Two pools built from an override still read no sizing keys: an
> application's `main` under `--embedded-db`, and the stack surface runtime's `main`, a fixed 10
> in production. Neither is in this record's decisions, so both are left open.
>
> **S3 (the skeleton).** `tesseraql new` writes a production profile that separates the online pool
> from the two role pools. The stack marker states the production posture. **Shipped, #1462**, as
> amended, and with it the campaign is complete. The generated README gained a short
> "Environments" section, and the base configuration's comment says the same: an application
> that declares `prod` and `staging` refuses any other profile. A freshly generated application
> boots under `prod` against PostgreSQL with the layout above, and a job holds its connection on
> `tesseraql-main-jobs`. The same test without the profile turns all three cases red. The two
> override pools S2b left open are still open.
>
> **Amended 2026-09-25, after S1: decisions 5 and 7 are replaced, and decisions 5a and 5b are
> new.** The record first designed `tesseraql.batch.datasource`, a key naming another datasource
> for jobs. The maintainer's questions in conversation took it apart:
>
> - **Per-tenant modes.** Tenant routing replaces only `main`, and a named datasource is never a
>   tenant's home (row 13). So a batch datasource would have skipped the routing, and every
>   tenant's job would have run on the shared coordinate. The record's answer had been to exclude
>   `perTenant` jobs. The maintainer asked why the batch pool was not built from
>   `tenancy.datasources.<id>` the way a tenant's pool is. It should be: a second pool onto main's
>   database is part of `main`, so it goes wherever `main` goes.
> - **The name.** `batch` is the job platform's namespace, and `transfer` alone reads as a payment
>   or a network transfer. The pools are named for their users instead: `jobPool` and
>   `fileTransferPool`.
> - **The scope.** The first record said a list page's export was a job. It is not: route-started
>   transfers run on the request's `main` (row 15). They are the commonest long-held connection in a
>   business application, and nothing bounds how many run at once. The maintainer asked for the
>   online batch and the real batch to be pooled and named apart, and they are.

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

### Amended facts (2026-09-25, after S1)

| # | Subject | Finding |
| --- | --- | --- |
| 13 | Tenant pools, and what routing replaces | The modes are `shared-schema`, `schema-per-tenant` and `database-per-tenant`. In the two per-tenant modes each key of `tenancy.datasources` becomes a pool of its own, `tesseraql-tenant-<id>`, with the same pool keys as any datasource and fixed at 10 by default (`TenantDataSources.java:84`). `shared-schema` has none. `TenantRouting` replaces **only** `main`: "an explicit non-main `datasource:` is authoritative — named connectors are deployment-shared infrastructure, not tenant homes" ([TenantRouting.java](https://github.com/ingcreators/tesseraql/blob/main/tesseraql-pipeline/src/main/java/io/tesseraql/pipeline/tenant/TenantRouting.java)). A tenant with no pool is refused with `TQL-TENANT-4031`, never sent to `main`. Grouping tenants onto one pool is not supported: one pool per tenant id, and the mode is application-wide. |
| 14 | What a job's SQL runs on | `JobExecutor` runs each step on the `DataSource` that `JobRunners` hands it (`runStepTracked`). Its bookkeeping (`JobRepository`) is short writes on its own datasource. A job's `export:` step extracts on the job's datasource through `exportInline` (`ExportStepRunner.java:52`). **A poll-triggered import job** goes through `FileTransferService.startImport` with no pool, which means `main` (`PollImportProcessor.java:74`). |
| 15 | Route-started transfers are not jobs | A `file-export` or `file-import` route starts a transfer on the request's `main`, tenant-routed (`TransferPools.java:31`). That is where a list page's export and a CSV import go, not the job platform. The transfer service runs each one on its own virtual thread, **with no bound on how many run at once** (`JdbcFileTransferService.java:115`). Only the pool they borrow from limits them, and today that is the online pool. |
| 16 | Transfer bookkeeping splits on the pool object | `Bookkeeping` writes the transfer's record and verdict on the work connection when the work pool **is** the service's `main`, and on a second connection otherwise (`pool != dataSource`, `JdbcFileTransferService.java:216`). The second connection is opened **with the work connection, at the start of the run** (`:1193`, `:1905`). It is used only at the end (`recordRows`/`recordSpool` and the verdict's compare-and-set, `:1288`, `:1962`). **So in a per-tenant mode, every running transfer pins one online `main` connection for its whole duration.** A different pool object onto main's own database would be split the same way. |

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

### 5 — `main` may declare two role pools: `jobPool` and `fileTransferPool` (amended)

```yaml
tesseraql:
  datasources:
    main:
      maximumPoolSize: 10            # online: requests
      jobPool:                       # every job the batch platform runs
        maximumPoolSize: 3
        minimumIdle: 0
      fileTransferPool:              # asynchronous file-export / file-import a route starts
        maximumPoolSize: 5
        minimumIdle: 0
```

**A role pool is a second pool onto main's own database.** It takes main's coordinate
(`jdbcUrl`, `username`, `password` and the driver) and reads its sizing from its own block, with
the same keys and the same defaults as any pool. The pools are named `tesseraql-main-jobs` and
`tesseraql-main-transfers`. **An undeclared role pool means that work stays on `main`,** which is
today's behaviour, so an existing application changes nothing.

Which work runs where is decided by the executor, not by who started it:

| Pool | What runs on it |
| --- | --- |
| `jobPool` | Every execution of a `kind: job`: a scheduled firing, an external scheduler's `tesseraql job run`, a manual run from the ops console, an `after:` chain, `runJobForAllTenants`. Its steps' SQL and its `export:` steps (row 14). A poll-triggered import, which S2a hands to the transfer service with the job pool as the request's pool |
| `fileTransferPool` | A transfer that a `file-export` or `file-import` route starts: a list page's export, **My exports**, a CSV import (row 15). Because nothing else bounds how many run at once, this pool's size is that bound, and its `connectionTimeoutMillis` decides whether a transfer beyond it queues or fails |
| `main` (online) | Requests, the synchronous `query-export` a user downloads in place, an export's first-download follow-up (a request), the outbox, consumers, and everything else |

- **A job that names `datasource: main` is a job on `main`,** so it runs on `jobPool` just as one
  that names nothing does. There is no difference between spelling `main` and omitting it.
- **A job that names another datasource runs on that datasource's own pool,** as today.
- **A role pool is declared only under `main` or a tenant's block (decision 5a).** Under any other
  datasource it would configure work that never runs there: jobs on other datasources take their
  pool, and transfers run only on `main`. That is refused at boot with **`TQL-YAML-1115`**, not
  read and ignored.
- **The CLI's `job run` does not change.** It opens its own connection to main's coordinate
  (`JobCommand.java:417`). A role pool is the same database, and a single-run process has no online
  traffic to protect. The first record changed the CLI because a batch datasource could have
  pointed elsewhere, and a role pool cannot.

**Why roles on `main`, and not a named datasource.** A second pool onto main's database is part of
`main`, not deployment-shared infrastructure. So tenant routing, which replaces `main` (row 13),
carries it with it (decision 5a). The coordinate is written once. A misspelt name cannot silently
fall back, because there is no name to misspell. **Why two, and named for their users.** They
differ in when they run (a transfer during the working day, a job mostly in a night window) and in
how long they should wait: a card a user watches can queue briefly, and a job inside an SLA can
queue longer and should then fail visibly. With separate pools, each can be sized for that without
starving the other. The names are the subsystems' own ([jobs.md](jobs.md),
[file-transfers.md](file-transfers.md), and the ops console's "File transfers"). `batch` is the job
platform's namespace, and would make a user's export read as part of it. A bare `transferPool` reads
as a payment or a network transfer in a business application. The documentation calls
`fileTransferPool` the online-batch pool, a term a Japanese enterprise team already uses.

**Corrections to the first record.** A list page's export and the exports inbox are route
transfers, not jobs (row 15). Lanes admit routes, not jobs (row 9), so there is no lint of a lane
against a job pool.

### 5a — In a per-tenant mode, each tenant's pool gains the same roles

When `main` declares a role pool, **each tenant in a per-tenant mode gets one too**. It is built
from that tenant's coordinate in `tenancy.datasources.<id>`, with main's role sizing, or with the
tenant block's own `jobPool` / `fileTransferPool` block when it declares one. The routing follows
`main`'s:

- a `perTenant` job runs on its tenant's `jobPool`
- a route transfer for a tenant runs on that tenant's `fileTransferPool`
- `shared-schema` has no tenant pools, so its tenants use main's role pools, scoped by the
  `tenant.id` bind as ever

A tenant block may declare a role that `main` does not. That tenant alone then gets it.

The cost is up to two more HikariCP pools per tenant, each with a housekeeping thread of its own.
At `minimumIdle: 0` they hold no connections while idle. Each tenant database's connection budget
is then its own `main` plus its role pools at their maximum.

This replaces the first record's refusal of a batch pool for `perTenant` jobs.

### 5b — A transfer's bookkeeping follows the database, not the pool object

A transfer whose work runs on one of main's pools (`main` itself, or its `fileTransferPool`, or its
`jobPool` for a poll import) is on the database its record and verdict live in. **Its record and
verdict ride the work connection, in one transaction, as on `main` today.** `Bookkeeping` stops
comparing pool objects (row 16).

A transfer on a tenant's pool still splits, because the framework tables are on `main`. **Its
record connection is opened when it is first used, at the end of the run,** instead of beside the
work connection at the start, and it is taken from main's `fileTransferPool` (or `jobPool`) when
one is declared. The record connection is used only for the final rows, spool reference and
compare-and-set (row 16), so opening it late changes no statement's order: the verdict's
compare-and-set still runs before either commit. What changes is that a running tenant transfer no
longer pins an online connection for its whole duration.

### 6 — The stack's framework pool is sized by declaration

`framework.datasource` in `tesseraql-stack.yml` accepts `maximumPoolSize`, `minimumIdle` and
`connectionTimeoutMillis`, read through the same builder as an application's datasource. The
defaults are TesseraQL's own: 10 connections and 30 s. Those are the numbers in force today, but now
they are declared rather than inherited from a dependency. This is the same move `DataSources` made
for applications ([http-threading.md](http-threading.md) decision 2). The stack marker shows `5`
(decision 8).

### 7 — A new application carries a production profile that separates its pools (amended)

`tesseraql new` writes `config/env/prod.yml`:

```yaml
# The production profile: selected by TESSERAQL_ENV=prod (docs/deployment.md, "Environment
# profiles"). Pools are fixed-size unless minimumIdle is lower, so the connection budget is a
# standing number: per node, every pool of every member, plus the stack's own. Keep it under the
# database's max_connections (PostgreSQL's default is 100). docs/capacity.md has the arithmetic.
tesseraql:
  datasources:
    main:                                   # online: requests
      maximumPoolSize: ${db.main.maximumPoolSize:10}
      connectionTimeoutMillis: 10000        # a request that waited 10 s has lost its reader
      fileTransferPool:                     # the online batch: a list page's export, My exports,
        maximumPoolSize: 5                  # a CSV import. Its size is how many run at once
        minimumIdle: 0
        idleTimeoutMillis: 600000
        connectionTimeoutMillis: 120000     # a queued transfer waits two minutes, then fails
      jobPool:                              # every kind: job run: scheduled, external, manual
        maximumPoolSize: 3
        minimumIdle: 0
        idleTimeoutMillis: 600000
        connectionTimeoutMillis: 300000     # jobs that fire together queue, then fail visibly
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
- **`fileTransferPool` holds 5 and waits 2 min.** Five transfers run at once, and the next queues
  for a connection. A user watching a card can wait that long. Past that, the pool is too small for
  the working day, and a failed card says so.
- **`jobPool` holds 3 and waits 5 min.** Jobs that fire together at the top of a night window
  should queue briefly rather than fail. A longer wait means the pool is too small for the schedule,
  which should fail visibly rather than stretch the window.
- **Neither role pool holds anything when idle** (`minimumIdle: 0`). A pool used in bursts should
  not hold its share of the budget all day. At idle the database sees `main`'s 10. At a busy moment
  it sees at most 10 + 5 + 3 = 18 per application per node.
- **The role pools take main's coordinate (decision 5),** so the profile repeats no credentials.
- **`ScaffoldedConfigKeys` registers every new key,** so each one is read somewhere by the time the
  skeleton emits it. S2a's keys therefore exist first.

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
| A `from: main` datasource inheritance | Role pools take main's coordinate by construction (decision 5) | A second pool onto a datasource other than `main` being needed |
| `tesseraql.batch.datasource`, a named datasource for jobs (the first record's decision 5) | A named datasource is not a tenant's home, so it would have skipped tenant routing (row 13), and it had to exclude the work that most needed it (row 15) | None: replaced by decisions 5-5b |
| Role pools under a datasource other than `main` | Jobs on another datasource take that datasource's pool, and transfers run only on `main`, so the block would configure nothing. Refused with `TQL-YAML-1115` | A route transfer that runs on a named datasource, or jobs on one needing isolation from its routes |
| Grouping tenants onto one pool (A, B and C on one, D, E and F on another) | One pool per tenant id, the mode is application-wide, and structural isolation has no `tenant.id` predicate to separate tenants that share tables (row 13). Grouping would be a shard mode of its own, with its own routing, lint and migrations | A deployment whose tenant count makes a pool per tenant unaffordable. Tenant pools already take `maximumPoolSize` / `minimumIdle` per block in the meantime |
| A lint of lanes against a job pool | Lanes do not govern jobs (row 9) | Jobs gaining lanes |
| epoll, an in-process handoff, process separation | [gateway-performance.md](gateway-performance.md) | The triggers recorded there |

### 10 — Docs, CHANGELOG, codes

- **S1:** [capacity.md](capacity.md) is rewritten (decision 4). [deployment.md](deployment.md)'s
  front-door table gets the new defaults and the exemption. [http-threading.md](http-threading.md)
  decision 5 gets a dated note pointing here. [hosting.md](hosting.md) is checked for the old
  default.
- **S2a:** [deployment.md](deployment.md)'s pool section gains the two roles. Its paragraph saying
  background work borrows from the same pools "deliberately", so that contention shows as request
  latency, is rewritten: sharing stays the default, and the roles are the named way out of it,
  recommended for production by S3's profile. That paragraph was found while S1 rewrote the
  section around it. [jobs.md](jobs.md) and [file-transfers.md](file-transfers.md) name the pool
  their work runs on. [multi-tenancy.md](multi-tenancy.md) gains the tenant roles and the grouping
  refusal. [capacity.md](capacity.md)'s budget counts tenant pools and role pools. The
  configuration reference is regenerated, and `TQL-YAML-1115` is allocated.
- **S2b:** [deployment.md](deployment.md) and [hosting.md](hosting.md) describe the stack
  framework pool's keys.
- **S3:** [deployment.md](deployment.md)'s profile section points at the generated files, and the
  getting-started text `tesseraql new` prints is checked. The configuration schema the editor
  reads (`tesseraql-config-v1`) describes `jobPool` and `fileTransferPool`. Its
  `maximumPoolSize` description, which still says HikariCP's default applies, is corrected. Both
  schema copies are regenerated with the dogfood ritual that S3 runs anyway. S2a left the schema
  alone: its `additionalProperties: true` already accepts the keys.
- **CHANGELOG:** each slice, under Changed or Added.

## What this breaks

1.0 has not shipped, so no migration steps follow. This records what changes and why.

- **The front door forwards up to 40 requests per member, where it forwarded 10.** Beyond that
  the answer is still the front door's `TQL-RATE-4294`, because its count includes the relay's
  own time and so reaches the shared number first. What changes is that the member's whole queue
  of 40 is usable. (The record first said the member's `TQL-RATE-4293` would answer instead; S1's
  reading corrected it.)
- **Asset and health forwards no longer count against the share.**
- **An application that raised `workerThreads` to raise its queue** gets 40 unless it declares
  `maxInFlight`.
- **Applications generated after S3 declare `prod` and `staging`,** and refuse any other profile
  name. Existing applications and the examples not regenerated by the dogfood test are unchanged.
- **Work moves only where `main` declares a role pool.** Nothing declares one by default, so
  existing applications are unchanged.
- **Where a role pool is declared, a transfer's rows and verdict commit in one transaction on the
  role pool, as they did on `main`.** A tenant transfer opens its record connection at the end of
  the run instead of at the start.

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
  and on the same harness shape. The expectation is no refusals at `c`=32, and the front door's
  `TQL-RATE-4294` beyond 40 at `c`=64. The result is recorded there.
- **Docs:** capacity.md with its worked example re-measured, deployment.md, and http-threading.md's
  note.

### S2a — two role pools on `main`, and their tenants (M-L)

Decisions 5, 5a and 5b.

- **Tests:** Testcontainers integration tests, per the rule that database-backed stores are tested
  against a real database. Which pool served a piece of work is read from the pools themselves,
  by HikariCP pool name: the pool's own counters, or a connection the work holds while the test
  observes it.
  - With both roles declared, a scheduled job, a manual run and a poll-triggered import run on
    `tesseraql-main-jobs`. A `file-export` and a `file-import` route's transfers run on
    `tesseraql-main-transfers`. A request's SQL stays on `tesseraql-main`.
  - With neither declared, all of it runs on `main`, as today.
  - A transfer on the role pool commits its rows and verdict together: a revert probe of the
    database-keyed bookkeeping shows the split.
  - `database-per-tenant`: a `perTenant` job lands on its tenant's `jobPool`, and a transfer
    started for a tenant lands on that tenant's `fileTransferPool`. A tenant block's own sizing
    wins. The record connection is taken at the end of the run.
  - `shared-schema`: tenants use main's role pools.
  - A role pool under another datasource is refused with `TQL-YAML-1115`.
- **Docs:** as decision 10 lists for S2a.

### S2b — the stack's framework pool (S)

Decision 6.

- **Tests:** the stack framework pool honours `maximumPoolSize`, `minimumIdle` and
  `connectionTimeoutMillis`, and its defaults are declared as 10 and 30 s.
- **Docs:** as decision 10 lists for S2b.

### S3 — the skeleton (S)

Decisions 7 and 8.

- **Tests:**
  - `AppScaffolder` renders `config/env/prod.yml` and `staging.yml` with the layout above.
  - `ScaffoldedConfigKeys` registers every new leaf key.
  - `examples/scaffold-demo-app` is regenerated through the dogfood ritual.
  - A generated application boots under `TESSERAQL_ENV=prod` against Testcontainers PostgreSQL with
    `main` fixed at 10 and both role pools at 0 idle, and a job lands on `tesseraql-main-jobs`.
- **Docs:** deployment.md's profile section and the `new` command's next steps.

## Error codes

| Code | Slice | Meaning |
| --- | --- | --- |
| `TQL-YAML-1115` | S2a | A `jobPool` or `fileTransferPool` block under a datasource other than `main` or a tenant's block; the runtime refuses to start, because the block would configure nothing |

The first record allocated `TQL-BATCH-4213` for an undeclared `tesseraql.batch.datasource`. That
key is gone, so the code was never used.
