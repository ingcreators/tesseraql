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
[runtime footprint](runtime-footprint.md) campaign spent a release reducing.

**That reasoning covers routes that need a connection, and nothing else** — a correction decision
6 forced. A static asset needs no connection, so the workers this removed were ones it could have
used. The answer is not to raise the number back but to stop assets asking for a worker at all,
which is what decision 6 does. Both numbers
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

### 5. The front door forwards under a declared bound, per member

Decision 4 asked whether the gateway needed its own Vert.x sized, and answered no: it is one
instance for the process, so it does not multiply with applications installed. That was the
right answer to the question asked, and the wrong question. **The gateway's threads were never
the problem; its connections were.**

The relay is non-blocking end to end — routing is map lookups, entitlement reads an in-memory
record, and `vertx-http-proxy` forwards without touching a worker. A member blocked in JDBC
therefore costs the front door a connection and some memory, not a thread. Three things were
wrong anyway, all of them from `outboundOptions` passing the client's defaults through
untouched:

| | Default in force | Consequence |
| --- | --- | --- |
| Connections per member | 5 (`maxPoolSize`) | Fewer than a member's own worker pool, so the front door — not the member — was the ceiling |
| Wait queue | unbounded (`maxWaitQueueSize` −1) | The queue decision 3 removed inside a runtime, still present in front of it |
| h2c multiplexing | unlimited (`http2MultiplexingLimit` −1) | Turning on a *protocol* flag replaced the limit of five with no limit at all |

So the effective concurrency of the whole stack was a number nobody chose, nobody could see,
and that changed by an order of magnitude with a setting about protocols.

**One declared number, taken in the relay.** `tesseraql.gateway.maxConcurrentPerMember`
defaults to the stack's `tesseraql.http.workerThreads` — a front door that admits more than a
member can run only moves the queue one hop earlier, and one that admits fewer makes the
member's own pool unreachable. Beyond it the answer is 503 with `Retry-After` and
`TQL-RATE-4294`. The permit rides the drain counter's existing exactly-once guard, because
Vert.x keeps one `endHandler` per response and a second registration would silently replace the
count the stack's stop waits on. The outbound client is sized to the same number in **both**
protocol modes, so the protocol flag stops deciding a capacity.

The gateway's own `/health/live` is answered before the permit, for decision 3's reason.

**A read-idle timeout is offered and deliberately not defaulted.**
`tesseraql.gateway.readIdleTimeoutSeconds` is unset unless an operator sets it. A hung member
and a member running a legitimately long query are indistinguishable from the front door: both
accept the request and send nothing until they are done. Event streams are the easy half —
they heartbeat every 25 seconds — but a report with a raised `timeoutSeconds` can be silent for
minutes and is not misbehaving, so any number the framework picked would eventually cancel
somebody's report. Without it, a hung member is contained by the bound above rather than
reclaimed: it holds its own permits and nothing else, and the stack answers 503 for that member
while serving the rest.

### 6. Static assets are answered off the worker pool

Decision 1 lowered the worker pool from Vert.x's 20 to 10, reasoning that against a connection
pool of 10 the other ten workers could only wait in `getConnection()`. **That reasoning holds
only for routes that need a connection, and this decision corrects the over-generalisation.** An
asset needs none, and under the old arrangement it took a worker anyway.

Assets were a Camel route, so every stylesheet, script and icon went through `executeBlocking`
and held one of the same ten workers a slow query holds. A page's worth of them queued behind
whatever the database was doing. Each request also read the file **entirely into the heap** and
SHA-256'd it — including to answer 304, the cheapest response there is — so a twenty-megabyte
image on ten concurrent requests was two hundred megabytes of heap nobody asked for.

They now mount on the platform router, after the admission gate and ahead of every Camel route.
**The gate lets them through**: it bounds work that occupies a worker, and refusing a stylesheet
because the database is busy would put back the coupling this decision removes.

**The classpath half is cached; the filesystem half is not.** Framework assets and vendored
WebJars come out of jars and cannot change while the process runs, so they are read once, hashed
once, and answered from memory — there is no invalidation question to get wrong. Files are the
mutable half and are never cached, which is precisely why the stale-validator bug that removed
the previous cache cannot recur: the thing that changes is the thing that is re-read.

A file's validator becomes a weak `(mtime, size)` rather than a content hash. A strong validator
means reading and hashing the whole file on every request, including the 304s, which is what made
streaming pointless. Weak validators are what a web server has always used here.

**Vert.x resolved every path against the classpath first**, copying matches into a cache
directory. Nothing here wants that — the classpath half is read directly and the filesystem half
is an absolute path that can only ever miss — and the scan measured at **1328 ms for a stylesheet
on an idle runtime**. `setClassPathResolvingEnabled(false)` took that to 187 ms.

**The filesystem half needed threads of its own, and the measurement is what found that.**
`sendFile` streams instead of buffering, but Vert.x dispatches the file I/O behind it to the
worker pool, so a file asset stayed coupled to exactly what leaving the Camel route was meant to
escape: with both workers held in `pg_sleep`, a classpath asset answered in **7 ms** and a file in
**1689 ms**. The router mount alone was therefore half a decision, and the half it delivered was
not the half it was designed for. Asset reads now run on virtual threads the runtime owns — the
one place in this design where virtual threads clearly fit, since file I/O blocks on something no
connection pool bounds and a thread that costs nothing while it waits is the whole point. On the
same fixture the file now answers in **22 ms**, beside the classpath half's 23 ms: the difference
between the two halves is gone, which is the result the number was being watched for.

**One rule decides where a request runs: in memory on the event loop, storage on a virtual
thread.** Drawing it there rather than around "files" caught two reads that were on the event loop
by accident — the first read of a classpath resource, and the generated message module, whose
catalog is read live off disk so that a Studio edit is served on the next request. Answering from
the cache stays a map lookup and a write, which is what an event loop is for.

**A file is written a chunk at a time, and the thread blocks between chunks.** That wait is the
backpressure: without it a slow client would be served at the speed of the disk into Vert.x's
write queue, which is the heap cost this decision removes, spelled differently. Expressing
backpressure by blocking is what makes a thread the right shape here, and what makes it cheap is
that the thread is virtual. A client that leaves mid-file stops the read at the next chunk
boundary rather than paying for the rest of a file nobody will receive.

**No second bound guards them.** The admission gate deliberately lets assets through, so a permit
here would be that refusal under another name; what bounds the platform threads is the JDK, which
compensates a blocked carrier rather than letting the blocking spread. The executor is owned by
the runtime and stops with it, which is what a host that replaces one application while the
process keeps running needs it to do.

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
5. **The front door's per-member bound** — the relay's permit, the sized client, the opt-in
   read-idle timeout. Found by asking what limited the gateway once the runtimes were bounded.
6. **Assets off the worker pool** — the router mount, the classpath cache, the weak file
   validator, the classpath-resolving fix. Half the decision: the filesystem half is measured and
   deferred, above.
7. **Asset reads on the runtime's own threads** — the virtual-thread executor, the chunked write
   that blocks for backpressure, and the file counterpart of slice 6's decoupling test. The other
   half, found by measuring the first.

Slices 1 and 2 are independent. Slice 3 must land before slice 4: sharing the pool without a
per-runtime bound would remove the accidental bulkhead and put nothing in its place.

## Next

- [deployment.md](deployment.md) — the operator-facing view of these numbers.
- [app-isolation-model.md](app-isolation-model.md) — what running several applications in one
  process does and does not separate.
- [runtime-footprint.md](runtime-footprint.md) — the campaign the thread counts belong to.
- [jobs.md](jobs.md) — the background work that shares these pools.
