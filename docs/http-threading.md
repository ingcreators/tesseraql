# HTTP threading and pool sizing

Implementation design for the numbers that decide how much work one runtime does at once:
the size of the thread pool every HTTP request runs on, the size of the connection pool
those threads borrow from, what happens to requests arriving beyond both, and how many of
each a multi-application host ends up with.

Written 2026-08-20, before implementation, measured against main at #923.

The question that started it: *if many JDBC calls are slow, does TesseraQL hit the
virtual-thread connection-pool collapse people write about?* The answer is no, for two
reasons that turned out to matter less than what the investigation found instead.

## What is true today

**Requests do not run on virtual threads.** `camel-platform-http-vertx` hands each exchange
to `vertx.executeBlocking(..., ordered=false)`, so route processing runs on the Vert.x
**worker pool** — a fixed pool of platform threads. TesseraQL registers no `VertxOptions`
bean, so the pool is Vert.x's default size, **20**. There is no amplification of ten
thousand cheap threads onto ten connections, because there are twenty threads and no way to
have more.

**Pinning is not a concern.** The baseline is Java 25, and JEP 491 (Java 24) removed
`synchronized` pinning. `PinningMonitor` and `TQL-OPS-9005` already watch for what remains.

So the article's failure mode does not apply. Three others do.

### 1. Twenty and ten were chosen by two libraries that never met

`DataSources.base` maps every Hikari knob from config with `ifPresent` and sets no default,
so an application that does not configure `maximumPoolSize` gets Hikari's default of **10**
against a worker pool of **20**. Ten workers are therefore structurally a waiting room: they
can only ever block in `getConnection()`, for up to `connectionTimeout` — also unset, so
Hikari's **30 seconds**. A saturated runtime spends thirty seconds per request acquiring
nothing, then up to `tesseraql.sql.timeoutSeconds` (30) executing.

Neither number is wrong on its own. The defect is that they are two numbers where the
system has one question — *how much concurrent work does this runtime do* — and nothing in
TesseraQL states either of them.

### 2. Saturation takes down endpoints that never touch the database

When all workers are blocked in JDBC, further requests queue in Vert.x's blocked-task queue,
which is unbounded. Everything else the runtime serves queues with them: static assets,
rendered templates, and — the one that converts a slowdown into an outage — health and
readiness. An orchestrator polling `/_tesseraql/health` gets no answer, concludes the
process is dead, and restarts it, discarding the in-flight work that was about to finish.

The runtime cannot report that it is saturated because reporting travels the queue that
saturation filled.

### 3. A multi-application host multiplies both, and cannot cap them

`MultiAppHost` runs each installed application in its own `CamelContext` on its own port.
`VertxPlatformHttpServer.lookupVertx()` searches that application's own Camel registry, and
TesseraQL binds no shared `Vertx`, so **each runtime creates its own Vert.x instance** —
its own worker pool of 20, and its own event-loop pool of `2 × cores`. On a 20-core host,
five applications is 100 worker threads and 200 event loops. No configuration reduces it,
and the total is a function of how many applications are installed rather than of anything
an operator chose.

## Structural decisions

### 1. The worker pool is a declared number

A `VertxOptions` bean goes into each runtime's Camel registry before the HTTP server starts,
built from two new keys:

| Key | Default |
| --- | --- |
| `tesseraql.http.workerThreads` | 10 |
| `tesseraql.http.eventLoopThreads` | `2 × cores`, Vert.x's own |

Vert.x's 20 was chosen for a framework where `executeBlocking` is the exception. TesseraQL
routes every request through it, so the default is being used outside the assumption that
produced it, and inheriting it silently is the part worth fixing regardless of the value.

**The default becomes 10, not 20.** With `maximumPoolSize` at 10 the effective ceiling was
already 10; the other ten workers could do nothing but wait. Removing them costs no
throughput, and it removes threads from a footprint the
[runtime footprint](runtime-footprint.md) campaign spent a release reducing. Both numbers
now say 10 because they answer one question, and decision 2 makes that relationship
explicit rather than coincidental.

### 2. The connection pool declares its default beside the worker pool

`maximumPoolSize` and `connectionTimeoutMillis` stop inheriting Hikari's defaults and get
TesseraQL's own, in the same place the worker count is decided: **10** and **30s**, the
values applications already get, now stated rather than inherited. `leakDetectionThreshold`
stays off by default — it is a debugging aid whose log volume an operator should opt into.

The point is not the numbers. It is that `docs/reference-config.md` can now answer "how many
connections will this use" from TesseraQL's own configuration surface instead of from
Hikari's release notes, and that raising one without the other is visible as a diff.

**Background work shares these pools.** Jobs, file transfers and SSE streams borrow from the
same datasources outside the worker pool, so a runaway job competes with the web path. That
is deliberate — it makes the contention observable as latency rather than hiding it in a
second pool — and bounding it is decision 3's job, not a second set of numbers.

### 3. The HTTP edge admits or refuses, and never queues without a bound

A handler on the Vert.x router, registered the way `SseRoutes` registers its endpoints, takes
a permit before the Camel handler runs and releases it when the response completes. Beyond
the bound the answer is **503 with `Retry-After`**, immediately, on the event loop — not a
place in an invisible queue.

| Key | Default |
| --- | --- |
| `tesseraql.http.maxInFlight` | `workerThreads × 4` |

Four times the worker count leaves room for the ordinary burst that a queue exists to absorb,
while keeping the queue a number an operator can see and reason about instead of "however
much heap it takes".

**Health and readiness are exempt.** They are checked before the permit, so the gate itself
never refuses the one surface whose whole purpose is to be answerable when nothing else is.

**Exempt from admission is not exempt from the worker pool** — recorded here because the first
draft of this decision claimed more than the mechanism delivers. Route processing still needs a
worker, so a health request behind a saturated pool still waits for one; what changes is that
the wait is now bounded by `maxInFlight` instead of by however many requests arrived. Answering
without a worker at all means serving the readiness roll-up from the event loop, off the result
its TTL cache already holds (`tesseraql.diagnostics.readinessTtl`). That is a separate change to
how readiness is computed rather than to how requests are admitted, and it is the remaining half
of this decision.

This is a runtime-wide floor, not a replacement for what routes already declare.
`admission.concurrency.maxInFlight` stays the per-route limit and
`admission.lane` stays the way a route moves onto a named
[execution lane](reference-yaml-surface.md); both are per-route opt-ins for shaping traffic,
and neither ever bounded the runtime as a whole.

### 4. One Vert.x per host, not one per application

`MultiAppHost` creates a single `Vertx` and binds it into every runtime's registry, so all
applications share one worker pool and one event-loop pool sized by decision 1. The host
owns it and closes it at shutdown; `VertxPlatformHttpServer` closes only an instance it
created itself (`localVertx`), so stopping or replacing one application — a canary
activation, a `runtime-replace` — leaves the shared instance alone, which is the property
that makes this safe to do at all.

**Module isolation is unaffected.** Vert.x captures the thread context classloader when a
context is created and restores it on dispatch, so a shared worker thread runs each
application's work under that application's own loader. TesseraQL does not depend on the
TCCL in any request path regardless — the only `getContextClassLoader` call in main sources
is CLI startup, and module drivers are handed to Hikari explicitly. Camel contexts,
registries, datasources, ports, Studio instances and traces are all untouched.

**One isolation property is genuinely lost, and is replaced rather than dropped.** A worker
pool per application is an accidental bulkhead: application A saturating its twenty workers
cannot reach application B. Sharing the pool removes that boundary — precisely in the
slow-JDBC scenario that started this document. Decision 3's gate is per runtime, so each
application still has its own bound and the sum across applications is bounded by
construction. The bulkhead survives; it moves from an undeclared side effect of pool
allocation to a declared number that refuses with a 503 instead of silently consuming a
shared resource.

**A hosted member does not size the transport it shares.** The host builds the one instance
from its own `tesseraql-stack.yml`, using the same keys decision 1 defines, so a member's own
`workerThreads`/`eventLoopThreads` reach nothing. Read, parsed and then ignored is the shape
this codebase removes wherever it finds it, so the runtime says so in a warning naming the key
and the file that decides it. It warns rather than refuses: the same declaration is correct for
the same application run standalone, and an application is not wrong for having been hosted.
`maxInFlight` is deliberately *not* on that list — that gate is per runtime, and per-member
bounds are exactly what keep one application from consuming the shared pool.

[app-isolation-model.md](app-isolation-model.md) decision 3 lists what mode ② separates —
Camel contexts, URL spaces, Studio instances, traces, configuration. Thread pools were never
on that list, so this is not a contract change; it is recorded here because "not promised"
and "not relied upon" are different things.

## Slices

1. **The worker pool is configurable** — `VertxOptions` in the registry, both keys, reference
   config regenerated. Nothing else changes behaviour.
2. **Pool defaults are declared** — `maximumPoolSize` and `connectionTimeoutMillis` get
   TesseraQL's defaults, documented beside the worker count.
3. **The admission gate** — the router handler, the 503, the health exemption, and the test
   that proves a saturated runtime refuses ordinary traffic without refusing health. The
   event-loop readiness answer is deferred with direction, above.
4. **The shared Vert.x** — `MultiAppHost` owns one instance; the isolation model gains the
   paragraph decision 4 records.

Slices 1 and 2 are independent. Slice 3 must land before slice 4: sharing the pool without a
per-runtime bound would remove the accidental bulkhead and put nothing in its place.

## Next

- [deployment.md](deployment.md) — the operator-facing view of these numbers.
- [app-isolation-model.md](app-isolation-model.md) — what running several applications in one
  process does and does not separate.
- [runtime-footprint.md](runtime-footprint.md) — the campaign the thread counts belong to.
- [jobs.md](jobs.md) — the background work that shares these pools.
