# The pipeline, written for the runtime it has

Implementation design for the shape the request pipeline would have had if `RoutingContext` had
always been the thing on the other side of the socket — and for which parts of today's shape are
still Camel's rather than this framework's.

Written 2026-08-21, before implementation, measured against main at #958 and re-measured at #960
after that PR shipped the prose half of what became decision 7.

[camel-removal.md](camel-removal.md) replaced `Exchange`, `Processor` and `Synchronization` with
types of this framework's own, and it was explicit about the method: what the replacement carries
was **counted rather than copied**. That was the right method for the change it was making — 116
files changed type without changing behaviour, and a rewrite of the text is precisely where a
difference would have hidden.

Counting what a shape carries is not the same question as whether the shape is right. That
question was deliberately not asked, because asking it would have made the campaign a rewrite. It
is this document, and it has one test: **for each piece, what would this framework have written if
the transport had always been Vert.x?** Where the answer is "the same thing", the piece stays and
this document says so. Where it is "nothing at all", the piece is a shape inherited from a
dependency that is gone.

## What is true today

Everything in this section is measured against main at #958, and re-verified against #960 where
that sweep touched the same files; the names section says what it changed.

### The message is one bag, and the two directions inside it barely meet

[`Message`](../tesseraql-pipeline/src/main/java/io/tesseraql/pipeline/Message.java) holds a single
case-insensitive map. Four unrelated things are in it at once: real request headers, query and path
parameters, form fields, the request's own metadata (`tql.http.method`, `path`, `uri`, `query`,
`rawQuery`, the peer addresses) — and the response being written.

What the call sites actually do with it, across all 948 main sources:

| `setHeader` — 146 calls | `getHeader` — 169 calls |
| --- | --- |
| response code 52, `Content-Type` 44, `Location` 14, `Set-Cookie` 9, `Content-Disposition` 6, `Cache-Control` 4, `Retry-After` 2, `HX-Redirect` 2, `ETag`, `Link`, `Vary`, `X-Total-Count`, `HX-Retarget`, `HX-Reswap` | `Cookie` 15, `"id"` 15, request URI 11, `X-CSRF-Token` 7, `HX-Request` 7, `X-Forwarded-For` 5, `Authorization` 5, `RelayState` 4, `"member"` 4, method 4, `Accept` 3, `If-None-Match` 3, `"filter"` 3 |

**Writes are the response. Reads are the request and its parameters.** The two directions are
already disjoint in practice; what they share is a map.

The exception proves it. `Content-Type` is read 7 times and written 44 — and both are correct,
because it is genuinely a header in both directions. `Headers` says so in one line: *"The content
type, on the way in and on the way out."* So a renderer's `setHeader(CONTENT_TYPE, …)` overwrites
the value [`RequestBinder`](../tesseraql-compiler/src/main/java/io/tesseraql/compiler/binding/RequestBinder.java)
read to decide how to parse the body. Nothing is broken by that today, and nothing but step order
stops it being.

### Three mechanisms exist only to survive the one bag

**1. A filter in both directions, and a prefix rule that cannot be got wrong twice.**
[`HeaderFilter`](../tesseraql-pipeline/src/main/java/io/tesseraql/pipeline/HeaderFilter.java) (58
lines) decides which names may enter the message and which may leave it, because internal state and
wire headers share a namespace. [camel-removal.md](camel-removal.md) slice 6c records what that
costs: renaming the prefix from `tql.` to `Tesseraql` disables acting roles everywhere, silently,
because `Tesseraql-Acting-Role` is a real wire header that must *pass* the same filter. The rule is
documented next to itself because that is the only place the next reader will be standing. A
response object that starts empty needs no outbound filter, and a request object that holds no
internal state needs no inbound one.

**2. Ten calls that mean "forget the request, I am writing the response".**
`removeHeaders("*")` — sometimes with one name kept — at 10 sites across the renderers, the file
processors, the deploy route and the ops shell, plus the wildcard matcher in `Message` that
implements it. Every one of them is a step saying that the bag it is about to write into still
holds the request that arrived.

**3. A URL re-parser, because a parameter's value is not trustworthy where it is stored.**
[`PathTemplate`](../tesseraql-core/src/main/java/io/tesseraql/core/http/PathTemplate.java) (90
lines) writes the diagnosis itself:

> The HTTP transport publishes path parameters as message headers, but it publishes query
> parameters and form-body fields there too, and under the same names: a query parameter sharing a
> path parameter's name arrives joined with it, and a body field of that name replaces it. Reading
> the header gives a value the caller can steer or spoil.

Vert.x had those three sources in three separate `MultiMap`s — `pathParams()`, `queryParams()`,
`formAttributes()` — and the edge flattens them into one map. `PathTemplate` recovers, from the URL
string, a value the router already had. It is read twice: by the request binder, and by
[`PolicyTemplate`](../tesseraql-security/src/main/java/io/tesseraql/security/policy/PolicyTemplate.java),
where the same collision was found again as an authorization defect (#927). The binder still keeps
the header read as a fallback, so both mechanisms are live.

**And a fourth thing, which is what the absence of names looks like.**
`OpsShellRouteBuilder` forwards a request to another pipeline by copying `getHeaders()` wholesale
into it, then copying the answer's `getHeaders()` wholesale back — and calls `removeHeaders("*")`
twenty-three lines later. "Pass the request on" and "take the response back" are the same
statement today, because neither has a name.

### One form arrives three times

The edge appends each form field to the header map *and* to a `Map` body, and its comment is
honest about why: "one binder reads the map and another reads the header, and a route written
against either has to keep working." Then
[`RequestBinder`](../tesseraql-compiler/src/main/java/io/tesseraql/compiler/binding/RequestBinder.java)
carries a third path — its own `parseForm` over the raw urlencoded body — for the exchanges that
were built without the edge. Three representations of one form, kept consistent by nothing but the
code that writes all three.

### Two names for the query, and a contract that quietly went false

`Headers` declares `HTTP_QUERY` as "the query string, decoded" and `HTTP_RAW_QUERY` as "as it
arrived". The edge writes `request.query()` — the raw string — **into both**, and has since #942:
the decoded contract is documented, unimplemented, and unnoticed. Unnoticed because every one of
the six read sites needs the raw value. Four rebuild a redirect target as `"?" + query`, where a
decoded `&` inside a parameter would corrupt the URL being rebuilt; the Workshop's two split on
`&` and `=` first and decode each half, which double-decodes or mis-splits if the input arrives
decoded. Two constants, one value, and the surviving contract is the one the documentation calls
the fallback.

### The attachments are a borrowed type on an undeclared dependency

`Message.attachments()` is a map of `jakarta.activation.DataHandler` — the type Camel's attachment
API used. What the three readers call on it: `getName()`, `getContentType()`, `getInputStream()`.
Nothing else; `Message`'s own Javadoc already says a part is "a name, a content type and a stream
you may read once". To supply those three strings-and-a-stream, `Message.part(...)` implements two
anonymous `DataSource`s with `getOutputStream()` methods that exist to throw — and the
`part(String, byte[], String)` overload has no caller anywhere, main or test.

The dependency is the sharper half. `tesseraql-pipeline` declares no `jakarta.activation`; the
type resolves through `tesseraql-identity` → `tesseraql-yaml` → `angus-mail` — a mail library the
yaml module declares because it sends mail, two module hops from the request pipeline that
compiles against it. That is the borrowed-dependency shape
[http-edge.md](http-edge.md) paid for once and [camel-removal.md](camel-removal.md) then checked
for by name — a jar this module compiles against because a distant module needs it for something
unrelated. (`MailNotifier`'s own `DataHandler` use is jakarta.mail's API, in the module that
declares it, and is not this finding.)

### The typed accessors are casts, and the framework already knows it

136 typed `getHeader`, 25 typed `getProperty`, 28 `getBody(T.class)`.
[camel-removal.md](camel-removal.md) decision 3 counted the same thing and drew the right
conclusion for its own scope: one real conversion — bytes or a stream to text — and the rest are
casts of values this framework stored itself. What it could not do inside that campaign is remove
the reason the casts exist. A request whose method, path, query and parameters are typed accessors
does not ask a conversion table what a `String` is.

### The runner reconstructs an artifact that needs no reconstruction

[`Pipeline`](../tesseraql-compiler/src/main/java/io/tesseraql/compiler/pipeline/Pipeline.java) is a
49-line record: steps, handlers, handoff index, lane name.
[`RoutePipeline`](../tesseraql-runtime/src/main/java/io/tesseraql/runtime/RoutePipeline.java) is
229 lines that copy all four into fields of its own and redeclare `Handler` as a structurally
identical private record. Three consequences, all of them leftovers from when resolving a pipeline
meant turning endpoint URIs into producers:

- The resolving overload of `RoutePipeline.of(...)` returns `Optional.of(...)` unconditionally.
  The edge's `Optional` can still come back empty — only when the id vanished from the registry
  between its `contains` check and this call — and in that one race the message it throws, `"is
  not a plain processor chain, so … cannot be served"`, names a shape-based decline that cannot
  happen any more.
- `start()` and `stop()` are empty methods. Their own Javadoc says they stay because their callers
  "are about a pipeline becoming live, not about Camel".
- Two independent caches hold the same objects: `RouteEdge.pipelines` and
  `RoutePipelines.resolved`. A hot reload must invalidate both, from two places in
  [`RouteReloader`](../tesseraql-runtime/src/main/java/io/tesseraql/runtime/RouteReloader.java)
  (`edge.refreshAll()` at one call site, `evict(id)` at another).

[`RoutePipelines`](../tesseraql-runtime/src/main/java/io/tesseraql/runtime/RoutePipelines.java)
(115 lines) states its own premise and the premise is false: *"Pipelines are cached because
resolving one creates producers, and a producer per MCP call is a leak with a slow fuse."* Nothing
creates a producer. It is registered as a `RuntimeContext.Service` "so the producers it resolves
are stopped", stops nothing, and carries a `running` flag whose only reader is the test that pins
the lifecycle.

The mount table has the same problem in a smaller size.
[`HttpMounts`](../tesseraql-pipeline/src/main/java/io/tesseraql/pipeline/HttpMounts.java)' state is
per-context — every method starts by looking the instance up in the context it was handed — but its
methods are `static synchronized`, so the lock is the *class*, JVM-wide. In a `MultiAppHost` every
application's boot-time mounting and every hot reload serialize on one monitor for state none of
them share.

### The completion contract has a branch nothing takes, and one thing depends on it

Five registrations, as [camel-removal.md](camel-removal.md) decision 2 enumerated: the route audit
row, the route concurrency permit, the lane permit, the telemetry span, and the SQL step's streamed
body. `Completion` has two methods because `org.apache.camel.spi.Synchronization` had two.

`RoutePipeline.done()` is the only caller of either. `setException` is called at exactly three
sites besides the setter:

| site | when |
| --- | --- |
| `RoutePipeline:131` | sets **null**, in the catch, before the renderer and before `done()` |
| `RoutePipelines:82` | after `run()` has already returned — the completions have run |
| `OpsShellRouteBuilder:70` | inside a step, and the run loop rethrows it on the next check |

So `exchange.getException() != null` is false at every `done()`, and **`onFailure` is unreachable**.
Four of the five implementations have byte-identical bodies, so this costs nothing. The fifth is
`RouteTelemetry`:

```java
public void onFailure(Exchange failed) {
    if (failed.getException() != null) {
        span.recordError(failed.getException());   // never runs
    }
```

**No span this framework emits has ever carried its exception.** The failure is available — the run
loop puts it on `TesseraqlProperties.EXCEPTION_CAUGHT` one line after clearing it, and the error
renderers read it from there — but the completion asks the wrong question.

There is a second half to the same shape: the drain lives on the runner, not on the exchange.
`Exchange.handoverCompletions()` has one caller. `PollLoop` builds an `Exchange` and invokes a
`Step` directly, without a runner, so any completion registered on that path is never drained at
all. Nothing registers one there today; nothing says it may not.

### Two beans are resolved by type, and there are exactly two

`TesseraqlHttpServer` asks `findSingleByType` for `Vertx` and for `VertxOptions`. Everything else
in the registry is looked up by name — including these two, which are *bound* by name
(`TesseraqlProperties.VERTX_BEAN`, `VERTX_OPTIONS_BEAN`) and whose Javadoc explains the mismatch:
*"Camel looks the options up by type, so the name only has to be unique."* Camel does not look
anything up any more.

`findSingleByType` returns null when more than one instance matches, so a second `Vertx` bound
anywhere makes the server silently build a third — with default pool sizing, which is the exact
default [http-threading.md](http-threading.md) decision 1 exists to prevent. And
`TesseraqlHttpServer.start` binds `"tesseraqlVertx"` as a string literal next to the constant that
spells it.

### The names still describe the dependency

#960 swept the prose: every sentence that asserted, in the present tense, that Camel is doing
something now — `TesseraqlRuntime`'s "Camel Main based", `RouteCompiler`'s "into Camel routes",
the "Camel processor that …" openers, `AGENTS.md`'s "Apache Camel based" — is gone. What survives
is 28 mentions across 18 main sources, and they are provenance worth keeping: `This was
org.apache.camel.Exchange` tells the next reader where a shape came from.

With two exceptions the sweep missed, both still asserting current fact. `HttpMounts`' third
paragraph explains that "a mount names the `direct:` endpoint rather than the route id" and that
"the edge resolves one to the other by reading the route model" — the field is `pipeline`, the
endpoint and the route model were both deleted in [camel-removal.md](camel-removal.md) slice 2b,
and this is the class's own contract paragraph. And `StudioRuntimeExtension` says "Send is a Camel
route" about a pipeline.

The names are what a prose sweep could not touch: 16 classes are called `*RouteBuilder` and no
`RouteBuilder` type exists — a builder was the thing `configure()` handed to `addRoutes`, and
slice 6a turned them all into an `install(context)` call; and `PlatformHttpHeaders` is named for
a component that left in #942 and holds two `tql.http.*` constants that belong with the others.

## What is deliberately not in scope

Named here so that a later reader does not mistake silence for oversight.

- **The virtual thread per request, and the `runOnContext` hop back.** This is not residue; it is
  the measured decision of [http-edge.md](http-edge.md) — 3629 ms against 1046 ms under the same
  saturation, and a Vert.x `ThreadingModel.VIRTUAL_THREAD` context is one virtual thread and is
  serial (8041 ms against 1097 ms for eight concurrent requests). A Vert.x-native rewrite in the
  `Future` idiom is not an improvement on this and is not proposed.
- **`WireNames`' `p0`/`p1` stand-ins.** A router-parameter-name constraint of Vert.x, not of Camel.
  It stays. Decision 2 confines it to the edge, which is the only change.
- **The connectors, the schedules and the cron.** Rewritten on `java.nio`, `jsch`, `commons-net`
  and `ScheduledExecutorService` in slices 1, 4 and 5 of [camel-removal.md](camel-removal.md).
  They are already this framework's own shape.
- **The registry as a whole.** Sixty-three of the sixty-four calls a step made on the Camel context
  were a name lookup, and `Beans` is that lookup. Threading services through constructors instead is
  a real design question and a different campaign; decision 6 takes only the two beans that are
  already inconsistent with it.

## Structural decisions

### 1. A request and a response are two objects

The decision this document turns on. `Exchange` carries an inbound `Request` and an outbound
`Response`. The request is what arrived and does not change; the response starts empty.

`setHeader(…)` becomes `response.header(…)` at ~140 sites, `getHeader("Cookie")` becomes
`request.header(…)`, and `getHeader("id")` becomes a parameter read (decision 2). What goes with it:

| deleted | why it existed |
| --- | --- |
| `HeaderFilter.leaves`, `NEVER_LEAVES` | a response built from a map that also held the request |
| `removeHeaders("*")` × 10, and the wildcard matcher | the same |
| `HeaderFilter.enters`, the `tql.` prefix rule, the acting-role trap | internal state sharing a namespace with wire headers |
| `PlatformHttpHeaders`, and most of `Headers` | request metadata stored as headers |
| `appendEntry` and the `String`-or-`List` union | one map having to be multi-valued for the sake of parameters |

**Echoing a caller's `Cookie` back as a response header becomes unrepresentable** rather than
prevented by a filter, which is the property worth having and not the line count.

Uploaded parts are the request's, and a part is this framework's own type: a name, a content type,
and a stream — the three things the readers call, as a record with a stream supplier rather than a
`DataHandler` wrapping an anonymous `DataSource` whose write half exists to throw. The
`jakarta.activation` import leaves `tesseraql-pipeline` with it, closing the undeclared transitive
path through the mail library; the caller-less `part(String, byte[], String)` overload is deleted
rather than ported.

### 2. A parameter has a source, and the source is declared

`request.pathParams()`, `queryParams()` and `formFields()` are separate, from the three `MultiMap`s
Vert.x already separates. A merged view for the binder exists, with **path first, then query, then
form** — declared once, in one place, instead of decided by insertion order in a map.

`PathTemplate` stops being a workaround. It stays as the *linter and OpenAPI* template reader it
also is, but nothing at runtime re-parses a URL to find out what the router already matched, and
the binder's header fallback goes with it. `WireNames` maps the stand-in back to the declared name
at the edge, so no later step sees a `p0`.

A form has one representation: `formFields()`. The edge stops writing fields into the header map
and a `Map` body both, and the binder's own `parseForm` goes — an exchange built without the edge
fills `formFields()` the same way the edge does, instead of carrying a raw body for the binder to
parse a third way.

The query has one accessor, `request.query()`, and it is the raw string — which is what every
reader already receives and what every reader needs, whether it rebuilds a `"?" + query` redirect
target or splits the string before decoding. `HTTP_QUERY` and `HTTP_RAW_QUERY` collapse into it,
and the "decoded" contract that has been documentation-only since #942 is retired rather than
implemented: implementing it would hand a corrupting value to every consumer the accessor has.

### 3. The exchange is one object

`Message` folds into `Exchange`. It exists because Camel's exchange had an in/out pair and could
copy a message between exchanges; this one holds exactly one, created eagerly, never replaced, so
`exchange.getMessage().getHeader(…)` is a hop with no decision in it.

Exchange properties stay as the request-scoped bag — they are the honest one, since they never
reach the wire — and the conversion table shrinks to what decision 3 of
[camel-removal.md](camel-removal.md) measured: bytes or a stream to text, and an identity cast.

### 4. A pipeline is run, not resolved

`RoutePipeline` becomes a stateless runner over a `Pipeline`. No copy, no second `Handler`, no
`Optional`, no `start`/`stop`, and no unreachable refusal. `Pipelines` is the single place a
compiled pipeline is held, and the one place a reload invalidates; it caches its own built record
rather than rebuilding it per call. `RoutePipelines` collapses into a two-line call and stops being
a service.

Error clauses match on `Class<?>` rather than on class names walked up the hierarchy as strings —
the compiler has the classes in its hand and spells them as names only because the route model did.

`HttpMounts` keeps its per-context state and gets a per-context lock: instance methods on the
object the registry already holds, synchronized on themselves. The static entry points go, and
with them the JVM-wide monitor that serializes unrelated applications' reloads.

### 5. A completion runs once, on one method, and the exchange owns the drain

`Completion` becomes one method, `onDone(Exchange)`. The failure is read from
`TesseraqlProperties.EXCEPTION_CAUGHT`, which the run loop already sets, so `RouteTelemetry`
records the error it has never recorded — **a defect fixed by the simplification, with a test that
asserts a failing route's span carries its exception.**

The drain moves onto `Exchange`, as a `close()`-shaped call the runner makes in `finally`. An
exchange that is built and run outside a pipeline then drains what was registered on it instead of
leaking it silently.

### 6. The transport is passed, not looked up

`TesseraqlHttpServer` takes the `Vertx` (or the `VertxOptions` to build one) as constructor
arguments from the code that already decides which it is. `findSingleByType` and the two by-type
lookups go; the silent "more than one, so build a third with default pools" path goes with them.

### 7. The names describe what is there

The prose half of this decision shipped as #960 while this document was in review; what remains is
the half a prose sweep could not reach. `*RouteBuilder` becomes `*Routes` across 16 classes,
`PlatformHttpHeaders` merges into `Headers`, and the two sentences #960 missed — `HttpMounts`'
contract paragraph and `StudioRuntimeExtension`'s "Send is a Camel route" — are corrected.
Past-tense provenance is kept throughout, because the reason a shape exists is the most expensive
thing in this repository to rediscover.

## Slices

**0. The names, alone.** Decision 7 — the class renames and the two missed sentences; the prose
already landed as #960. No behaviour, no test changes, lands by itself so that every diff after it
is attributable to a structural change.

**1. The completion, and the span that never carried its error.** Decision 5. Small, self-contained,
and it ships a defect fix — which is also the acceptance: a route that fails under a declared
clause produces a span with the exception on it, asserted rather than assumed.

**2. The runner and the registry.** Decision 4, including the mount table's lock. One cache, one
invalidation, one `Handler`. The existing reload suites are the acceptance: a saved route serves
its new body, a deleted one answers 404, and an MCP call after a reload runs the recompiled
pipeline.

**3. The response side.** Decision 1, outbound half. ~140 `setHeader` sites and the 10
`removeHeaders("*")` sites move to a response object; `HeaderFilter.leaves` and `NEVER_LEAVES` are
deleted. **This slice is checkable without touching a single read**, which is why it is first of the
two: the whole integration suite drives every route over real HTTP, so a response header that stops
being written fails a test rather than a deployment.

**If slice 3 cannot pass the suite without a compatibility shim, the campaign stops here.**
Decisions 1 and 2 are the expensive half, and the outbound direction is the cheap proof that the
split is real. Slices 0 to 2 stand on their own and do not depend on it.

**4. The request side.** Decision 1 inbound, and decision 2. The 169 reads split into headers and
parameters, `Headers` loses everything but the wire names, `PlatformHttpHeaders` goes, the `tql.`
prefix and both directions of `HeaderFilter` go, and the binder stops reading parameters out of a
header bag. `PathTemplate`'s runtime use retires; `PolicyTemplate` reads the declared path
parameters instead of re-deriving them. The form's three representations become one, the two query
constants become one accessor, and the attachments become the framework's own `Part` — taking the
`jakarta.activation` import out of the pipeline module.

**5. One exchange.** Decision 3. Mechanical once 3 and 4 have landed, and worth its own slice for
the same reason slice 3b of [camel-removal.md](camel-removal.md) was: a mechanical change across
many files must not share a diff with a change of behaviour.

**6. The transport by constructor.** Decision 6. Last because it is independent and small, and
because doing it earlier would put an unrelated change in the middle of the ones that need review.

## What the acceptance is, for all of it

The same thing that made the edge change and the Camel removal checkable, and it has not moved:
**`RouteTestRunner` drives every route over real HTTP**, and the application test suite does not go
through the pipeline's internals at all. A shape change that keeps every one of those green has not
changed what a request means. The unit tests that read headers off a message are the ones expected
to change, and they are the ones being made to say what they mean.
