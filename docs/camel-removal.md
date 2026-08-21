# Removing Camel

Implementation design for taking Apache Camel out of the build entirely while every behaviour the
framework has today survives it: the request pipeline, the sweeps, the cron firing, the directory
and remote-file connectors, and the `Exchange` every processor is written against.

Written 2026-08-21, before implementation, measured against main at #943.

[http-edge.md](http-edge.md) opened with "nothing here is a decision to remove Camel" and then
decided, one job at a time, which of Camel's jobs were worth what they cost. Two survived that
pass and were deliberately left standing: the file and FTP connectors (decision 4) and
`Exchange`/`Processor` (decision 2). This document reopens both. Not because either reason was
wrong — each was right about the change it was protecting — but because both reasons were about
*sequencing*, and the sequence they protected has since shipped.

## What is true today

Everything in this section is measured against main at #943 unless marked otherwise.

### The engine's vocabulary is unused, and the last of it left with the edge

Across all main sources, what the framework asks Camel's routing engine to do:

| `direct:` | `timer:` | `quartz:` | `file:`/`sftp:`/`ftps:` | `tesseraql-*:` |
| --- | --- | --- | --- | --- |
| 95 `from(...)` | 10 | 1 | per poll job | 4 components |

And what it asks the DSL for, across every `RouteBuilder` in the reactor: `process` × 244,
`to` × 100, `routeId` × 126, `enrich` × 2, `threads` × 1. **Zero** `choice`, `split`, `aggregate`,
`saga`, `routingSlip`, `onCompletion`, or expression language — no `simple()`, no `xpath()`, no
`jsonpath()` anywhere in main sources. The last two `choice()` calls left in slice 5b-1 of the
edge campaign, when both turned out to be early returns written as one-armed branches.

### The HTTP path already runs without the engine, and pays for it anyway

`RoutePipeline` reads a compiled route's `RouteDefinition` and runs its processors itself, on a
virtual thread, with its own error envelope and its own completion drain. So for the 138 HTTP
routes a real runtime serves, **Camel is a build-time model and a `DirectConsumer` nothing calls**.

Nothing calls it, with four exceptions, and they are the whole of the non-HTTP surface:
`AppMcpServer`, `QueueConsumer`, `OpsShellRouteBuilder` and `FilePushService` address a route
through a `ProducerTemplate`. A registry of pipelines by id is what all four actually want, which
[http-edge.md](http-edge.md) decision 5 already predicted.

### What imports Camel, and where it is not

**116 of 941 main sources**, plus 25 test sources. The histogram is narrow:

| `Exchange` | `Processor` | `RouteBuilder` | `CamelContext` | component SPI | `Synchronization` |
| --- | --- | --- | --- | --- | --- |
| 76 | 48 | 28 | 25 | 4 sets | 5 |

`tesseraql-core`, `tesseraql-yaml`, `tesseraql-security`, `tesseraql-operations`,
`tesseraql-identity`, `tesseraql-test-core` and `tesseraql-maven-plugin` import none. **The test
suite an application ships does not go through Camel** — `RouteTestRunner` drives a route over
real HTTP — which is what made the edge change checkable and is what makes this one checkable.

### What the dependency costs, counted the way the image counts it

`dependency:copy-dependencies -DincludeScope=runtime`, the command `deploy/Dockerfile` runs:

| `tesseraql-host` runtime closure | Jars | Size |
| --- | --- | --- |
| Today | **205** | **48 MB** |
| Camel's share of it | 50 | 8.7 MB |

Three libraries are on the classpath only because Camel put them there: `c3p0` and
`mchange-commons-java` (under `camel-quartz`, for a JDBC job store this framework does not use),
and `jsch` and `commons-net` (under `camel-ftp` — see below, because they change the estimate).

### Slice 0, already measured

Swapping the `camel-core` aggregate for `camel-core-engine` and declaring the components the
runtime actually resolves — `direct`, `timer`, `quartz`, `file`, `ftp`, plus `camel-http-base` for
the header filter strategy the edge asks for — is a two-pom change with no source change:

| | Jars | Size |
| --- | --- | --- |
| Before | 205 | 48 MB |
| After | **181** | **46 MB** |

Twenty-four artifacts leave: `camel-core`, `bean`, `browse`, `controlbus`, `dataformat`,
`dataset`, `language`, `log`, `mock`, `ref`, `rest`, `saga`, `scheduler`, `seda`, `stub`,
`validator`, `xpath`, `xslt`, `xml-io`, `xml-io-util`, `xml-jaxb`, `xml-jaxp`, `yaml-io`, and
`platform-http`, whose last resident left in edge slice 5b-2.

**Two of those are worth naming individually.** `camel-xslt` and `camel-xpath` are an XML
processing stack, carried by a framework that evaluates no XPath expression anywhere — measured
zero, above. And `camel-rest` outlived the REST DSL by two slices; nothing noticed, because an
aggregate dependency has no way to say that one of its members stopped being used.

### The component guard exists because of auto-discovery

[component-guard.md](component-guard.md) is a shipped security control: `ComponentPolicy` denies
`exec`, `script`, `groovy`, `class`, `language` and `bean` by default, and `ComponentGuard`
enforces it at component-registration time through the context's lifecycle strategy, failing boot
with `TQL-SEC-4138`. Its threat model says plainly what it is defending: **Camel registers
whatever component it finds on the classpath**, so a dependency upgrade or a plugin jar can put a
scripting component into a runtime that never asked for one.

That is a control whose entire subject is the mechanism this document proposes to remove. It is
not a cost of removal; it is part of the return.

### Where Camel still does work nobody wants to write

`PollingRouteBuilder` (234 lines) and `FilePushService` (151 lines), over `RemoteFileUris`
(196 lines). What the consumer URIs ask for: `readLock=changed` for write stability,
`idempotent=true&idempotentEager=true` with a `name-size-modified` key for replica exclusivity,
`antInclude`, `move`/`moveFailed`, `localWorkDirectory` so a remote file never materialises in
heap, SFTP host-key verification, FTPS transport options, and `tempPrefix` so a push lands
atomically for the partner polling it. Two of those options are documented in that file as facts
read out of Camel's bytecode rather than its catalogue.

**This is the decision the campaign turns on, and it deserves its own slice before anything else
is spent.**

### Three beliefs about the connectors, each measured the other way

- **The protocol clients are already ours to call.** `jsch` and `commons-net` are on the
  classpath today; they arrive *under* `camel-ftp`. What Camel adds on top of them is a consumer
  loop, not SFTP or FTPS.
- **The exclusivity mechanism is already ours.** `PollConsumedRepository` (63 lines) is a JDBC
  idempotent repository this framework wrote, keyed on name, size and modified time, backed by a
  table it owns. Camel calls it; it does not provide it.
- **The safety net is already there, and it is the same kind that made the edge change
  survivable.** `PollImportLocalIntegrationTest`, `PollImportSftpIntegrationTest`,
  `PollImportFtpsIntegrationTest` and `PushSftpIntegrationTest` run against real containers.
  A rewrite is checkable against the behaviour, not against a reading of the behaviour.

What remains to be written is the loop: scan, hold until stable, claim, hand to the import
pipeline, archive to done or failed, and retry. Named as work rather than estimated as effort,
because the estimate is what slice 1 exists to produce.

### The type converter, counted rather than feared

[http-edge.md](http-edge.md) ended on a finding — "a borrowed type is a borrowed dependency" —
found when removing a component removed the `Buffer`-to-`String` converter that fifteen tests
depended on without naming. The same risk applies to `Exchange`'s typed accessors, so they are
counted here instead of discovered later. **190 typed accesses in main sources:**

| Target | Count | What it actually is |
| --- | --- | --- |
| `getHeader(name, String.class)` | 130 | a value this framework put on the message |
| `getBody(String.class)` | 25 | **a real conversion** — bytes or a stream to text |
| `getProperty(name, String.class)` | 7 | a value this framework put on the exchange |
| `Principal`, `Map`, `Throwable`, `InputStream`, `Integer`, `Date`, and four one-offs | 28 | casts of objects this framework stored |

So the registry that has to be replaced is one conversion — bytes to text — and a cast. The
remaining 165 do not need a converter registry; they need a method that does not throw when the
value is already the type asked for.

## Structural decisions

### 1. The compiler emits a pipeline, and the route model goes

`RouteCompiler` stops building `RouteBuilder`s and emits what `RoutePipeline` currently
reconstructs: an ordered list of steps, the error handlers, the lane handoff, and the id it
answers under. `direct:<id>` becomes a pipeline id in a registry; the four `ProducerTemplate`
callers look up and run.

This deletes a round trip rather than adding a layer. Today the compiler encodes a chain into a
route model so the edge can decode it back into a chain, and the decoder has to *decline* shapes
it cannot read — a decline path that exists only because the encoder can express more than the
framework uses.

### 2. `Exchange` and `Processor` become TesseraQL's own

Reversing [http-edge.md](http-edge.md) decision 2, whose reason — "keeping them makes this an edge
change rather than a rewrite" — was about that change and has been spent.

What the replacement carries, from the measurement above rather than from Camel's interface:
a message with multi-valued headers, a body, exchange properties, the exception, the route id,
the stop flag, attachments, and completion synchronizations that run on both paths. Nothing else
in `Exchange` is called by this framework.

**The completion guarantee is the part to get right, and it is already enumerated**: five
registrations (route audit, concurrency permit, lane permit, telemetry span, streamed SQL body),
all `addOnCompletion`, none of them asking the unit of work for anything else, with the leak test
that proves the drain — a route that always fails under `maxInFlight: 1` — already written.

### 3. Conversion is a declared table, not a registry

One conversion (bytes or stream to text, with the charset the request declared), one identity
cast, and a coded failure for anything else. A registry that silently finds a path is how a
framework ends up depending on a converter it never wrote down.

### 4. The connectors are rebuilt on the clients Camel was already using

`java.nio` for local, `jsch` for SFTP, `commons-net` for FTPS — all three already resolved today —
behind an **unchanged `poll:` and `push:` YAML contract**. `transport: local | sftp | ftps` is a
published surface and does not move; the allow-lists, credentials, host-key verification and
archive directories keep their spelling and their meaning.

### 5. Schedules are a `ScheduledExecutorService`; cron stays Quartz's `CronExpression`

The ten `timer:` routes are fixed-period sweeps. The one `quartz:` route is one cron expression,
and what makes a firing single-node is `tql_job_claim`, not the scheduler — so the class that
computes the fire time stays and the framework around it goes, with `c3p0` and
`mchange-commons-java` excluded on the way past.

### 6. The component guard leaves with the mechanism it guards

`ComponentGuard`, `ComponentPolicy`, the `tesseraql.camel.components` config block, `TQL-SEC-4138`
and `TQL-SEC-4139` are retired together. A framework that resolves no components by name off the
classpath has nothing for them to refuse. Recorded in the CHANGELOG as a breaking config change;
per rule 10, no shim.

### 7. The module names follow

`tesseraql-camel-runtime` and `tesseraql-camel-components` are renamed in the last slice, when the
names would otherwise be false. The BOM, the CLI, the host and the distribution guards move with
them.

## Slices

**0. Narrow the dependency, alone.** The two-pom change measured above: 205 jars to 181, 48 MB to
46 MB, no source change. Lands first and by itself, so that every number after it is attributable
to a structural change rather than to a dependency that was always removable.

**1. One connector, without Camel, against the existing container suites.** Local first, then
SFTP, driven by `PollImportLocalIntegrationTest` and `PollImportSftpIntegrationTest` unchanged —
same YAML, same archive behaviour, same claim, same off-heap promise. **If it cannot pass them,
the campaign stops here**: Camel stays as the connector engine, this document records what the
loop needed that was not worth writing, and slices 2 to 5 are re-scoped as "Camel serves the
connectors and nothing else". Nothing later depends on being able to abandon this one.

**Done, and the abort clause did not fire.** All four connector suites pass unchanged — local,
SFTP, FTPS and push — against **644 lines** of new main code and 66 changed lines of wiring. What
the section below records is what the loop turned out to be, and the two ways its behaviour now
differs from the component's.

**2. The pipeline model and the registry.** The compiler emits pipelines; `RoutePipeline` stops
reading `RouteDefinition`; the four `ProducerTemplate` callers move to the registry. Measure
runtime start time here, before and after, because it is the one number nobody has.

**2a done: the application routes.** The 138 routes `RouteCompiler` emits are pipelines; the edge,
the reloader, the MCP server and the queue consumer address them by id. The framework's own route
builders still declare consumers and are slice 2b. What it cost and what it caught is below.

**2b done: the framework's own routes, and the last `direct:`.** The 95 routes across 16 builders
are pipelines; a mount names the pipeline that answers it rather than an endpoint URI; the edge's
resolver and the route-model reader are deleted. **`from("direct:` appears zero times in main
sources.**

**And the number decision 2 asked for.** Starting the `CamelContext` now costs **1–10 ms, median
6**, measured across 332 context starts in one runtime suite run. That bounds what removing Camel
entirely can still save at boot, and the answer is: not enough to justify anything. Recorded so the
remaining slices are argued on footprint, attack surface and ownership — which is what they were
always about — rather than on a number nobody had checked.

**3. `Exchange`, `Processor`, and the conversion table.** Mechanical across 116 files, with the
five completions and the leak test as the acceptance.

**4. The sweeps and the cron.** `ScheduledExecutorService`, `CronExpression` over the existing
claim; `camel-quartz`, `c3p0` and `mchange-commons-java` leave. **Done**, and one virtual thread
per schedule rather than a pool — the same shape slice 1's poll cycle took, for the same reason:
a pool is another number to pick and these threads spend their lives asleep. `from("timer:` and
`from("quartz:` are gone; **181 jars to 178, 46 MB to 45**. What it found is below.

**5. The connectors cut over.** FTPS and push join local and SFTP; `camel-file` and `camel-ftp`
leave; `jsch` and `commons-net` become declared dependencies rather than inherited ones. **Done:
178 jars to 168, 45 MB to 44 — and Camel is down to 15 jars with no component among them.**

**6. Camel leaves the build.** `camel-core-engine` and the rest go; the component guard and its
config are retired; the modules are renamed; the CHANGELOG records the breaking change and the
jar count is re-measured the way slice 0 measured it.

## What slice 1 found

**A file consumer is four operations and six rules.** The operations are per transport — list the
directory, re-read one file's fingerprint, put its bytes on local disk, move it aside — and they
are the whole of `PollSource`. Everything else is the same for every transport and lives in one
loop: the `include:` glob, the write-stability check, the cross-replica claim, the archive
directories, the wait for the import to resolve, and what to do when even the archive fails.
**That split is the point rather than tidiness**: the FTPS data channel stayed unencrypted for a
year because one transport's settings lived somewhere the other's did not, and a rule that exists
once cannot hold for one transport and not the other.

**Removing the URI removed a class of defect with it.** Every value that needed `RAW(...)`
wrapping — a password containing an `&`, an `include:` glob that could bind further consumer
options if it were read as query text — is now an argument to a method call. There is no query
string for a secret to be re-encoded in and nothing for a glob to smuggle, which is asserted
rather than asserted-about: the test that used to check for correct wrapping now checks that a
glob containing `&` matches a file name containing `&`, and nothing else happens.

**The security rules became checkable without a server.** Host-key strictness, the
exactly-one-authentication-method rule and the remote path grammar were asserted against the
endpoint URI, which is to say against a string whose meaning lived in another project's bytecode.
`SftpPollSource.settings` resolves them before anything connects, with the same error codes
(`TQL-SEC-4088`, `TQL-SEC-4089`), and nine unit tests now assert the rules themselves.

**Two deliberate differences from the component**, recorded because neither is visible in a test
that passes:

- **The write-stability wait is per cycle, not per file.** `readLock=changed` re-reads each
  candidate's fingerprint after a wait; this collects the candidates, waits once, then re-reads
  all of them. A directory holding fifty files costs one wait rather than fifty. The wait is
  bounded by the declared poll interval, so a source polling every 500 ms does not spend a second
  on it.
- **The `consumeOnce:` key is spelled by this framework now.** It is still name, size and
  modification time — the property that matters, and the reason it is not the path alone — but not
  character-for-character what Camel's `${file:modified}` rendered. A file consumed before the
  upgrade and still inside the retention window can therefore be imported once more. Pre-1.0, so
  it is recorded rather than shimmed.

**One dependency rule applied before it bit.** JSch was already on the classpath, under
`camel-ftp`, and using it from there is exactly the borrowed-dependency mistake
[http-edge.md](http-edge.md) paid for once when a component left and took a type converter with
it. It is declared. In the other direction, `camel-file`'s direct declaration left with the local
consumer: nothing resolves the `file:` component any more, and it remains only as the base
`camel-ftp` is built on — the same reasoning that found `camel-rest` outliving the REST DSL.

**What did not change**, which is most of it: the `poll:` and `push:` YAML, the import pipeline,
`PollImportProcessor`, the consumption store, the poll-source registry the console and the
Prometheus gauges read, and the FTPS transport, which keeps its consumer until slice 5.

**No performance claim is made here.** The suites' wall-clock times moved, in both directions, on
a path that did not change as much as on the paths that did — which is what machine variance looks
like. Slice 1 was about behaviour, and the number that would matter (time from a file landing to
its rows being visible) has not been measured on either implementation.

## What slice 2a found

**The DSL and the builder are the same shape, which is what made the change reviewable.** The
compiler threaded a `ProcessorDefinition<?>` through a few dozen helpers, calling `process` and
`to` on it; Camel's fluent DSL appends to the current definition and returns the same object, and
so does `PipelineBuilder`. So the diff is a type and seventeen construction sites, not 1,900 lines
of rewritten control flow — and a rewrite of the text is precisely where a difference would have
hidden.

**The tests that read the route model got shorter.** Four compiler tests walked Camel's output
definitions recursively to find the processors inside them. A pipeline is the list, so the walk is
a loop, and what they assert — how often a processor is mounted, in what order, with which endpoint
URI — is now asked of the artifact rather than of an encoding of it.

**A borrowed drain stopped working the moment its premise left, and the suite said so.** The edge
registered each request in Camel's inflight repository under its route id, so the shutdown strategy
would wait for it (docs/runtime-replace.md). That works exactly as long as there is a route by that
id. There is not any more: the strategy had nothing to count, and replacing a runtime went back to
cutting an in-flight request mid-answer — the defect the edge campaign had already found and fixed
once, reappearing through a different door. `MultiAppReplaceIntegrationTest` failed on it before
anybody had to suspect it. The edge counts its own requests now and `close()` drains them under the
same declared `tesseraql.shutdown.timeout`, which is decision 5's table entry arriving on schedule.

**A leak test survived the thing it was guarding.** `QueueConsumerTemplateTest` pinned a property
about a `ProducerTemplate` being a context service, because a template caches producers and holds
their connections. The template is gone; the cache is not — `RoutePipelines` keeps resolved
pipelines and the producers inside them for the same reason — so the test was rewritten against the
new holder rather than deleted. **A test that only made sense with its subject was a test about the
subject, not about the property.**

**A security guard was reading how a surface is served, not which surfaces exist.**
`FrameworkSurfaceGuardTest` asserts that every framework HTTP route authenticates or is recorded as
deliberately open. It read the Camel route model — so the bundled Studio app's two share routes,
which the compiler builds and which are now pipelines, dropped out of the audit. What made that
visible rather than silent is the guard's own honesty probe: it also asserts that the fixture still
mounts a whole surface, and that assertion failed beside the first one. Without it the guard would
have reported a clean surface while having quietly stopped looking at two routes — the exact
failure mode its own comment describes. It reads both models now.

**One signal genuinely narrows.** `RouteHealthSignals` reports Camel routes that are not started.
An application route has no started state any more: it resolves at boot or the boot fails, which is
the stricter half of the same guarantee, but the health detail now covers the framework's routes
only. Recorded because it is a change in what an operator sees, not only in how it works.

## What slice 2b found

**Two framework surfaces had no error envelope at all, and the failure mode was silence.**
`OAuthRouteBuilder`'s seven routes and `McpRouteBuilder`'s three declared no `onException` clause,
where every sibling declares two. The edge throws when no clause claims a failure, `serve` did not
catch it, and the caller was left holding an open connection until it timed out — the one answer an
HTTP surface must never give. Giving every pipeline its clauses explicitly is what asked the
question; a route builder could omit them silently before because the DSL let the omission look
like nothing. Both are fixed, **and the edge now answers a 500 for any failure nothing rendered**,
because fixing only the two would leave the hole open for the next builder that forgets.

**A mount and a route id were two strings for the same thing.** A mount named `direct:tql.login`
while the route was `system.login`, and 25 of the 95 framework routes differed that way — which is
the entire reason the edge carried a resolver that scanned the route model. One string now, and the
resolver is gone with the reader it used.

**A blind rename would have changed the wrong `direct()`.** `HttpMounts.Mount.direct()` became
`pipeline()`, and a repository-wide rename also hit `ModelFieldConsumerScan.Consumers.direct()` —
an unrelated type about which code reads a model field. The compiler caught it; it is recorded
because the next mechanical rename in this campaign will look just as safe.

## What slice 4 found

**A Camel dependency was installed and doing nothing, for the third time in this campaign.**
`camel-mdc`'s `MDCService` carries the trace ids across a thread handoff by wrapping every
processor **a route reifies** — and a pipeline reifies nothing. So it stopped working when the edge
started running pipelines, which means a step handed to an execution lane had been logging without
the request's ids since the HTTP edge campaign, and nothing said so. The lane handoff carries them
explicitly now, through one method that also **clears** the MDC afterwards: a lane is a pool, and a
thread that kept the last request's ids would attribute the next request's log lines to it.

**The test that guarded it proved the mechanism, not the property.** `MdcAcrossThreadsTest` built
its own Camel route and asserted that `MDCService` bridged it — so it kept passing while production
had no routes for the service to wrap. It is rewritten against what actually runs. **And it stayed
in the CLI module**, where its own docstring says it must: the runtime's tests run on
`slf4j-simple`, whose MDC adapter is a no-op, so the same test moved one module over would pass
without proving anything.

**Quartz's scheduler leaves and Quartz's cron expression stays**, which is the whole of decision 5
in one sentence. What makes a cron firing safe across replicas is not the scheduler: it is that
every node computes the *same* fire time and exactly one wins the claim in `tql_job_claim`.
`CronExpression.getNextValidTimeAfter` is that computation. What leaves with the scheduler is its
thread pool, its JDBC job store, and `c3p0` — a second connection pool, in a runtime that declares
its own.

**The component floor shrank to one entry, and two tests argued the change into shape.**
`direct`, `timer`, `quartz`, `rest`, `rest-api`, `log`, `seda` and `platform-http` are no longer
components this framework registers, so they left `ComponentPolicy.FRAMEWORK_FLOOR`.
`ComponentGuardTest` failed first — it had used `direct` as its example of a component the floor
always admits. Then the first replacement floor put the remote file transports on it, and
`ComponentPolicyTest` failed on a fixture that denies `ftp`: **a deny list beats the floor**, so a
floor entry that is also deniable is not "always admitted" at all. That argument settled the
question the right way round: a remote transport is admitted by *the job that declares it*, which
the guard already does, and an app declaring no SFTP source should not have SFTP resolvable. What
is left on the floor is `properties`, which Camel registers on every context whether anybody asked
or not.

## What slice 5 found

**Both directions call one client per protocol, and that is the finding rather than the tidiness.**
The poll consumers and the push producers were assembled from one endpoint-URI builder because the
FTPS data channel had already stayed unencrypted for a year when that logic had a consumer home and
a lookalike copy. Take the URI away and the temptation returns immediately: a poll source and a
push target look like different things. They are one `RemoteFiles` implementation per protocol, so
the host-key check, `PBSZ 0`/`PROT P`, the trust-store refusal and the
exactly-one-authentication-method rule are written once and true in both directions by
construction.

**`FilePushService` stopped owning a Camel context.** It kept one — the runtime's, or its own for a
CLI `job run` — because a `ProducerTemplate` was how a file got uploaded. A push connects when it
delivers and disconnects afterwards, so nothing is held between deliveries and a job with no push
step costs nothing.

**A URI test's disappearance took a real property with it, and it had to be put back somewhere.**
The `consumeOnce:` assertions were about `idempotent=true&idempotentEager=true` and the
`name-size-modified` key — options, not behaviour. The behaviour they stood for is that a file is
**claimed before it is imported**, because two replicas can both pass a "have I seen this?" check
and only the insert settles it. `PollLoop` now takes that claim as a one-method interface, so the
rule is asserted with a fake instead of a database, and the property outlived the option that used
to imply it.

**No Camel-supplied component is left in the build.** Fifteen jars remain and every one is
engine, model or support: `camel-api`, `camel-core-engine`, `camel-support`, and what they drag in.

**The framework's own four components were still components, though**, and saying "no components
left" over-claimed it: `tesseraql-sql:`, `tesseraql-auth:`, `tesseraql-iam:` and
`tesseraql-service:` were `Component`/`Endpoint`/`Producer` implementations of Camel's SPI, living
in this repository. Slice 3a is where they stop being that.

## What slice 3a found

**A URI built from typed values and parsed back into typed fields is a round trip with a defect
class in it.** The compiler wrote
`tesseraql-sql:file:/…/q.sql?datasource=main&mode=query&maxRows=200&dialect=postgres`; the endpoint
parsed it back into a `String`, a `String`, an `int` and a `String`. The step is constructed with
those values now, and the `Component`/`Endpoint` layer — 406 lines of URI parsing and parameter
binding across four components — is gone.

**The security gate was percent-encoding its own atom.** A parameterised policy
(`tql.iam.write.{path.name}`) and the route's URL template had to be URL-encoded into the query
string, because braces are not URI characters, and decoded again at the endpoint. Both are
arguments now. The test that guarded the round trip — *does the decode give the gate back what the
encode took?* — no longer has a subject: it is rewritten to assert that nothing is encoded on the
way, with the reason recorded in it, because **a test whose subject was removed should say so
rather than disappear quietly**.

**One flake, recorded rather than explained away.** `MultiAppReplaceIntegrationTest`'s
no-request-dropped assertion saw a single 502 during a swap, in one full-suite run, and did not
reproduce in four runs afterwards (three isolated, one full). Slice 3a does move work from context
start to first use, so it was not dismissed on sight — but the failure is in the same family as the
`BindException` this campaign already saw on `main`: a port race that shows up under load. If it
recurs, the first thing to read is `StackRelay`'s per-port `HttpProxy` cache, which is never
evicted — a replaced runtime that lands on a port a previous one used would inherit a dead
connection pool.

**A step has no lifecycle, and one producer was using it.** `TesseraqlSqlProducer` parsed its SQL
file in `doStart()`, which is what a Camel service gets. `SqlStep` parses on first use instead,
which keeps the property that mattered — the file is read once, not per request — without inventing
a lifecycle to hang it on.

## What this does not buy, said before anyone expects it

- **Not throughput.** The edge already runs pipelines itself, and the numbers that mattered were
  taken in [http-edge.md](http-edge.md): 8 concurrent one-second routes in 1046 ms against two
  workers. This campaign should change none of them, and if a measurement moves, that is a
  regression to investigate rather than a win to report.
- **Not startup, until slice 2 measures it.** A `CamelContext` boots per application, and in a
  stack that is once per member — but nobody has measured what it costs, so nobody should claim
  it.
- **It does cost optionality.** Camel's catalogue stops being one dependency away. That is a real
  loss and it is smaller than it sounds here: [roadmap.md](roadmap.md) states in two places that
  the catalogue is an implementation detail rather than user API, [messaging.md](messaging.md) is
  a broker-free database channel by design, and the connectors this framework publishes are
  files and HTTP. Nothing on the roadmap wants a component that is not already in the build.

## Next

- [http-edge.md](http-edge.md) — the pass that took the edge off Camel, and the two decisions this
  document reopens.
- [http-threading.md](http-threading.md) — the numbers this campaign must not move.
- [connectors.md](connectors.md) — the published `poll:` and `push:` contract that does not move.
- [component-guard.md](component-guard.md) — the control that is retired with its subject.
- [runtime-footprint.md](runtime-footprint.md) — how the distribution's jars are counted.
