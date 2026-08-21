# The HTTP edge

Implementation design for what serves an HTTP request before an application's own steps run:
which library owns the socket, which thread the request pipeline runs on, and what that decides
about every number [http-threading.md](http-threading.md) declared.

Written 2026-08-20, before implementation, measured against main at #934.

The question that started it: after seven slices of [http-threading.md](http-threading.md), the
defects still open all have one shape — work that has to reach storage is dispatched to a pool
sized for something else. Slice 7 fixed that for one surface by taking the surface off Camel.
This document asks whether that was a one-off or a pattern, and it does not decide the answer
from taste. It measures.

**Nothing here is a decision to remove Camel.** It is a decision about which of Camel's jobs are
worth what they cost, and the answer is different for each.

## What is true today

### Camel is the substrate, and TesseraQL uses it as a servlet chain

`RouteCompiler` is 1902 lines and builds every application route. Its use of Camel's vocabulary:

| `.process()` | `.to()` | `.from()` | `.rest()` | `.choice()` | `.threads()` | `.enrich()` |
| --- | --- | --- | --- | --- | --- | --- |
| 75 | 24 | 20 | 5 | 2 | 1 | 1 |

Across all main sources the endpoint schemes are `direct:` × 98, `timer:` × 10 and `quartz:` × 1.
There is no `choice` worth the name, no aggregation, no routing slip, no saga. **The vocabulary
that justifies an integration engine is almost entirely unused**, and what is left is a chain of
processors with an HTTP endpoint at the front.

The coupling is also shallower than it looks: 110 of 934 main sources import Camel, in 8 modules,
and 99 of those are in `tesseraql-compiler`, `tesseraql-camel-runtime` and
`tesseraql-camel-components` — modules whose names already say so. `tesseraql-core`,
`tesseraql-yaml`, `tesseraql-security`, `tesseraql-operations`, `tesseraql-identity` and the
plugin SPI import none. **Camel is not in the contract an application programs against**, so
moving it is not a breaking change.

### Four things assumed to be Camel are not

Recorded because each one was believed the other way at the start of this document, and each
belief would have inflated the estimate.

- **Route tests do not go through Camel.** `RouteTestRunner` drives a route over real HTTP;
  `tesseraql-test-core` and `tesseraql-maven-plugin` import no Camel at all. The test suite an
  application ships keeps working across any edge change, which is what makes such a change
  checkable.
- **Mail is not `camel-mail`.** `MailNotifier` is plain `jakarta.mail`.
- **`camel-cxf-rest` is not Camel routing.** `tesseraql-oauth` uses CXF's OAuth classes directly;
  the artifact arrives through Camel's BOM and nothing else.
- **The ten `timer:` routes are fixed-period sweeps** — outbox, retention, reaper, SLA, queue
  poll. A `ScheduledExecutorService` is the whole of what they need. The single `quartz:` route is
  one cron expression, and the lease that makes it safe across replicas is `tql_job_claim`, not
  Camel.

### Where Camel earns its keep, unambiguously

`PollingRouteBuilder` — `camel-file` and `camel-ftp`. `readLock=changed` for write stability,
consumer-level `idempotent` with `idempotentEager=true` for replica exclusivity, `antInclude`,
`move`/`moveFailed`, SFTP host keys, FTPS transport options. Its Javadoc records two findings read
out of Camel's bytecode rather than its catalogue. **Replacing that is writing an integration
library**, and this document does not propose it.

### The edge, by contrast, costs more than it gives

`camel-platform-http-vertx` hands every exchange to `vertx.executeBlocking`. That single choice is
what [http-threading.md](http-threading.md) has spent seven slices working around: it is why the
worker pool is a ceiling, why decision 1 had to pick a number for it, why decision 3 needed a gate
in front of it, and why slices 6 and 7 had to take one surface off Camel entirely to get an asset
served.

Vert.x is already used directly in six places — `SseRoutes`, `AssetRoutes`, `HttpAdmission`,
`UnicodePaths`, `StackRelay` and `MultiAppGateway`. **The edge is already half outside Camel.**

## What was measured

`HttpEdgeDispatchIntegrationTest`. One runtime, **2 workers and 8 connections**, four ways to
dispatch the same blocking work — take a connection, hold it inside `pg_sleep(1)` — with eight
requests arriving at once. Eight one-second statements take one second if the connection pool is
the ceiling and four if the worker pool is.

| Dispatch | Elapsed |
| --- | --- |
| `vertx.executeBlocking` — what the edge does today | **4032 ms** |
| `ThreadingModel.VIRTUAL_THREAD`, one context | **8041 ms** |
| `ThreadingModel.VIRTUAL_THREAD` × 8, round-robin | **1037 ms** |
| `Thread.ofVirtual()` per request | **1097 ms** |

Three findings, in order of how much they change the plan.

**The worker pool is the ceiling, and it is the wrong ceiling.** Four seconds for work that eight
connections could have done in one. This is decision 1's number acting as a limit on work decision
2's number was sized for — the two numbers answering one question, now with a measurement rather
than an argument.

**`ThreadingModel.VIRTUAL_THREAD` is an execution context, not a concurrency mechanism.** It
really does run on a virtual thread — the work landed on `vert.x-virtual-thread-1`, `isVirtual()`
true — and **one context is one thread, serial**: eight requests took eight seconds, worse than
the worker pool. Getting concurrency out of it means deploying a pool of contexts and sizing it,
which is exactly the kind of number this campaign exists to stop the runtime inheriting. Recorded
in full because "use Vert.x's virtual-thread model" is the obvious reading of the API and the
obvious reading is wrong.

**A virtual thread per request needs no number at all.** 1097 ms, and the only bound in force is
the connection pool — which is what [http-threading.md](http-threading.md) decision 2 said the
system's one question was.

### And then on a real route

The table above proves what a thread pool does and nothing about this framework: the work was a
hand-written blocking call. Slice 1 serves an actual compiled route — its security gate, its
binder, its `tesseraql-sql:` producer, its response renderer, in the order the compiler put them —
from a Vert.x handler on a virtual thread, beside the Camel route it was compiled into.

**What a compiled route turned out to be**, read out of the model rather than assumed: two
`onException` clauses, five `process` steps, one `to` (the `tesseraql-sql:` producer) and one more
`process`. Every element is a `Processor` or resolves to one, so running the route is a loop over a
list, and the adapter around it is small — build an `Exchange` the processors recognise, write a
response out of the one they produce.

| Same route, two workers, six one-second statements already in flight | Elapsed |
| --- | --- |
| Via the Camel route | **3627 ms** |
| Via the router, on a virtual thread | **1033 ms** |

The edge pays its own second and nobody else's; the route pays the queue first. And unsaturated,
the two answers are identical — same status, same body, same `Content-Type` — which is the half
that had to be true before the other half meant anything.

**What this does not prove**, said here so the next slice is scoped against it rather than
surprised by it: one route shape (a public `GET` with no path parameters, no upload, no session),
a prototype that declines any route that is not a plain chain, and no completion guarantee. Those
are slices 2 to 4. The prototype lives in test sources, because slice 1's job was to produce a
number and be cheap to abandon.

### The failure path

Slice 1 measured the happy path, which is the easy half. The hard half is the one decision 5 names
below and deliberately keeps off its list, because it is not a line item: Camel's unit of work runs
`addOnCompletion` whether the exchange succeeded or failed, and re-implementing that is easy while
noticing everything that depends on it is not.

**Counted rather than feared: five registrations, and none of them asks the unit of work for
anything else.** The route audit row, the per-route concurrency permit, the lane permit, the
telemetry span, and the SQL producer's streamed body — `getUnitOfWork` appears nowhere in main
sources. So draining the registrations is the whole of what has to be reproduced rather than a
first approximation of it, and it is drained with Camel's own
`UnitOfWorkHelper.doneSynchronizations`, so a completion runs off a route exactly as it runs on
one.

**The test is a route that always fails, declaring `maxInFlight: 1`.** A permit that is not given
back is invisible on the first request and refuses the second. With the drain, three sequential
requests all produce the route's own error. Without it — checked by removing it — the second
request comes back `TQL-RATE-4291`, which is the leak, visible.

The envelope itself needs no reimplementation: the `onException` clauses are the instances the
compiler put in the model, so a refusal produced here is the framework's refusal, and the response
is identical through both edges.

### The cutover

Slices 3 and 4 are one change, and the reason is that neither is worth anything alone: a registry
that nothing serves from and a mount that has nothing to mount are both machinery waiting for the
other. Folded, they are the first slice that carries traffic.

**Every compiled HTTP route is a plain processor chain.** Measured on a real runtime with the
bundled system applications: **138 routes, none declined**. The "servlet chain" reading of this
codebase is not an impression from the compiler's source, it is a property of every route it
produces.

**The edge takes what it can and hands the rest back.** A request carrying a body falls through to
the Camel route still mounted behind it, because form and multipart arrive at a route as parsed
attributes today and reproducing that faithfully is its own change with its own tests. The
question is answered from headers alone — reading the body to find out what kind it is would
consume the stream the handler behind us needs. **That fall-through is what makes the cutover
reversible**: the route model is unchanged, both paths exist, and the whole integration suite
drives them over real HTTP either way.

| Two workers, eight connections, eight concurrent one-second routes | Elapsed |
| --- | --- |
| Before: the worker pool is the ceiling | ~4000 ms |
| After: the connection pool is | **1046 ms** |

Three details that are load-bearing rather than tidy:

- **The base path goes back on by hand.** It lives on the context-wide REST configuration
  (`restConfiguration().contextPath`), which is the one thing the REST DSL was doing that a router
  mount does not do by itself, so the mount re-applies it and Camel's `{name}` parameters are
  spelled the way this router spells them.
- **The response header filter is Camel's own.** The request's headers are on the message — they
  were put there so the route could read `Cookie` and `Accept` — so copying the message's headers
  out untouched would echo a caller's cookie back as a response header. The component's
  `HeaderFilterStrategy` already knows which headers leave a runtime, including this framework's
  one amendment to it, so it is asked rather than reimplemented.
- **Hot reload is a swap.** A recompiled route is a new list of processor instances; the mounted
  handler looks its pipeline up by id per request, so a reload replaces an entry rather than
  performing router surgery. A route that appears, disappears or moves is served by Camel until
  the next restart, which is recorded rather than hidden — live router surgery buys nothing a
  restart does not.

**What the adapter had to get right, found by running the suite rather than by reading.** The
first cut passed its own tests and failed twenty-five of everyone else's, and each failure named
something the consumer had been doing silently:

- **`Exchange.CONTENT_TYPE` is written on its own.** Camel's header filter strips it from the
  generic copy because the consumer writes it explicitly; copying headers through the filter and
  stopping there left every JSON and HTML response with no content type at all.
- **`CamelHttpPath` is the normalized path, not the raw one**, the peer addresses are exchange
  headers a route resolves a role's network condition against, and request headers and query
  parameters go through the *inbound* filter and are appended rather than assigned, so a repeated
  name becomes a list. Path parameters land after query parameters because that is the order a
  route already reads them in.
- **`exchange.setRouteStop(true)` ends the route.** One processor uses it — the role-activation
  redirect — and a loop that does not check it ran the renderer behind the redirect, overwriting a
  302 with the page the caller was being redirected away from. A 200 where a 302 belonged, on the
  authentication path, from four lines that looked complete.

- **A request served here is still an in-flight exchange.** Camel's shutdown strategy drains by
  counting what the inflight repository holds, and a pipeline run outside a route is not in it —
  so replacing a runtime cut an edge request mid-answer while the drain contract held for every
  request the edge had not taken over. Registering the exchange under its route id puts it back
  where the strategy already looks, rather than giving a stop a second thing to wait on.

That last pair is the argument for the fall-through and against a big-bang cutover, in two lines:
the adapter is small, and small is not the same as obvious. Both were found by the existing suite,
which is the point of having one that drives every route over real HTTP.

**Downloads stop paying twice as a side effect.** A route whose body is an `InputStream` is
streamed here on the virtual thread that is already ours, so an attachment or an export costs
neither a worker nor the heap it used to be read into. Decision 1 expected that coupling to die by
evacuation when the worker pool emptied; it dies earlier, by ownership.

## Structural decisions

### 1. The request pipeline runs on a virtual thread per request

Not on a worker pool sized to guess at it, and not on a pool of Vert.x virtual-thread contexts
sized to guess at it either. The measurement above is the whole argument: the connection pool
becomes the only bound on concurrent work that needs a connection, and work that needs none stops
queueing behind work that does.

**`tesseraql.http.maxInFlight` becomes more important, not less.** With no worker pool in the
request path, the admission gate is the runtime-wide bound — the one number an operator sets to
say how much this runtime does at once, refusing beyond it instead of accepting unboundedly.
Decision 3 of [http-threading.md](http-threading.md) was written as a floor under a pool; it
becomes the ceiling itself.

**`tesseraql.http.workerThreads` does not disappear — it stops being shared.** Vert.x dispatches
its own file I/O to that pool (`AsyncFileImpl` calls `workerPool().executor()`, in 4.5.31 and in
5.1.6 alike), so it stays the pool that reads and writes files. What changes is that nothing else
is in it. **That is also how the open download and upload defect dies**: Camel's `AsyncInputStream`
reads a response body on the worker pool one chunk at a time, paced by the client, and it stops
mattering when the worker pool has no other tenants. The coupling ends by evacuation rather than
by a rewrite.

### 2. `Exchange` and `Processor` stay

The 184 processors the compiler emits are the framework. `org.apache.camel.Exchange` and
`org.apache.camel.Processor` are a small, stable pair of interfaces, and a `DefaultExchange` can be
constructed and driven without a route. **Keeping them makes this an edge change rather than a
rewrite** — the compiler keeps emitting what it emits, and only what calls it changes.

Whether to grow TesseraQL's own request type later is a separate question, deliberately not
answered here. Answering it now would double the size of the first change and halve the confidence
in what any measurement afterwards meant.

### 3. Vert.x stays at 4.5.31 until the edge is off Camel

Camel 4.22's `camel-platform-http-vertx` pins `vertx-web` **4.5.31**, so Vert.x 5 is unreachable
while the Camel edge is in the build. It is not an independent choice, and it is not urgent
either: `ThreadingModel.VIRTUAL_THREAD` already exists in 4.5.31, so nothing decision 1 needs waits
on the upgrade.

What Vert.x 5.1.6 adds, checked against the artifact: `ThreadingModel.EXTERNAL`,
`HttpVersion.HTTP_3` with `Http3ServerConfig`, a Java 11 baseline, and a matching
`vertx-http-proxy` for the gateway. What it does **not** add is a fix for the thing this campaign
keeps hitting: `AsyncFileImpl` still uses the worker pool, and `FileSystemOptions` still has no
executor of its own. What it costs is that the `Vertx` interface has 13 callback overloads in 4.5
and **0** in 5 — Vert.x 5 is Future-only, across the API.

So: **one variable at a time.** Move the edge on 4.5.31, then upgrade, and let each change be
attributable.

### 4. The connectors keep Camel, and that is not a compromise

`camel-file` and `camel-ftp` do work nobody here wants to write. A runtime that serves HTTP
without Camel and polls SFTP with it is not an inconsistency; it is Camel used for the one thing
this codebase measured it to be good at.

**Whether to drop FTP is a separate decision and must stay separate.** It is tempting to fold it
in — without the connectors, Camel leaves the build entirely — but the two have different risk:
the edge is reversible and internal, while dropping the connectors removes `local`, `sftp` and
`ftps` from `PollSpec.transport`, which is a published YAML contract, and the local `file://`
poller goes with them. Binding them into one decision would make the reversible half
irreversible.

### 5. What must be rebuilt, named before it is discovered

Not estimates — a list, so that the first slice can be scoped against it rather than surprised by
it.

| Camel does | Replacement |
| --- | --- |
| `onException` × 57 → `ErrorResponseRenderer` | `try`/`catch` around the processor chain; the renderer is already ours |
| `direct:` × 98 and `ProducerTemplate` | a registry of pipelines by route id — what MCP, the ops shell and the queue consumer actually want |
| route lifecycle, hot reload (`stopRoute`/`removeRoute`/`addRoutes`) | swapping an entry in that registry, which is simpler than what it replaces |
| the shutdown drain (`ShutdownStrategy`) | an in-flight counter; `StackRelay` already has one, exactly-once `endHandler` guard included |
| REST DSL + `restConfiguration().contextPath()` | mounting under the base path on the router, as `AssetRoutes` and `SseRoutes` already do |
| `camel-mdc`, `camel-attachments` | a request-scoped MDC; Vert.x Web handles multipart natively |
| `timer:` × 10, `quartz:` × 1 | `ScheduledExecutorService`; one cron, over the existing claim |

**The item most likely to be got wrong is not on that list**, which is why it is said here instead:
Camel's unit of work runs `addOnCompletion` whether the exchange succeeded or failed. Audit rows,
span closure and resource release ride on that guarantee. Re-implementing it is easy; noticing
every place that depends on it is not, and the failure mode is silent and only on the error path.
Slice 2 counted them — five, all `addOnCompletion` — and proved the drain by removing it.

## Slices

1. **One route through the new edge, measured** — a single compiled route served by a Vert.x
   handler running its own `Processor` chain on a virtual thread, beside the Camel edge rather
   than replacing it, with the dispatch measurement above repeated against a real route. If the
   numbers do not hold on a real pipeline, everything after this is abandoned and this document
   records why. **Done: 3627 ms to 1033 ms, and the same answer. The abandon clause did not
   fire.**
2. **The error envelope and the completion guarantee** — `try`/`catch` to `ErrorResponseRenderer`,
   and an explicit completion hook with a test that proves it runs on the failure path. **Done:
   the envelope is the route's own instances, and the drain is proven by a route that always fails
   under `maxInFlight: 1` — remove it and the second request is refused with `TQL-RATE-4291`.**
3. **and 4. The cutover** — folded, because neither pays off alone. Pipelines by id, the router
   mount under the base path, hot reload as a swap, and a request carrying a body handed back to
   Camel. **Done: 138 routes served, none declined; eight concurrent one-second routes in 1046 ms
   against two workers.** `direct:` and `ProducerTemplate` turned out not to need replacing at
   all — camel-core stays, and of the external callers exactly one addresses an HTTP route
   pipeline.
5. **The edge becomes the only edge** — request bodies (form and multipart) move across, the REST
   DSL and `camel-platform-http-vertx` leave the build, and the upload coupling is re-measured to
   confirm it died. The download half already has.
6. **Sweeps and cron** — the `timer:` and `quartz:` routes.

Slice 5 must not land before the rest: it removes the mechanism they replace, and the hand-back it
removes is what has been keeping the cutover reversible.

**Deliberately not a slice:** the connectors, `Exchange`/`Processor`, and the Vert.x 5 upgrade.
Each is a decision this document declines to make, not work it forgot.

## Next

- [http-threading.md](http-threading.md) — the numbers this design changes the meaning of.
- [connectors.md](connectors.md) — what `camel-file` and `camel-ftp` deliver.
- [audit-hardening.md](audit-hardening.md) — the earlier audit of what Camel does for this
  codebase, and the rule this document followed: the catalogue is the advertisement, the bytecode
  is the contract.
- [runtime-replace.md](runtime-replace.md) — the drain the shutdown strategy serves.
