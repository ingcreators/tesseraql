# The gateway hop: what it costs, what was measured to lighten it, and why the request path stays as it is

> **Status: measured 2026-09-25 against main `e620293a7` (0.19.0-SNAPSHOT). Decision: the request
> path does not change.** This is the record to read before asking again whether TesseraQL is fast
> enough, why `tesseraql host` is slower than a runtime on its own, or whether the gateway could be
> made lighter. Every number below was taken on one machine in one session. The harness was
> throwaway and is described closely enough to rebuild ([Reproducing](#reproducing)).
>
> The conversation that produced it ran as a chain of questions. Is the 10,790 req/s of the Jackson 3
> bench reading ([jackson-3.md](jackson-3.md) row 19h) competitive? How does the runtime compare
> with Spring Boot under the same conditions? Is the gateway's relay HTTP, and could it be something
> else? What does nginx cost in the same place? Can the gateway be made as light as nginx? What
> would process separation, or an in-process handoff, change? Two defects in the defaults surfaced on
> the way. They are designed in [capacity-defaults.md](capacity-defaults.md).

## The answer in brief

- **The runtime is on par with Spring MVC.** Same machine, same PostgreSQL, same SQL, identical
  response bytes, separate processes with the same JVM flags. Spring MVC on Tomcat reads −8..+3% of
  the runtime's throughput, and the runtime's p99 under load is half of Spring's (row 1).
- **Every deployment pays the gateway's hop.** Under [stack-architecture.md](stack-architecture.md)
  decision 12 every deployment is a stack, so every request crosses `MultiAppGateway` and a loopback
  HTTP/1.1 relay to its member, even with one application. On a 33-byte answer that costs 27-47% of
  throughput, and on a 22 KB answer 15-23%. An nginx hop in the same place costs 14-22% on the tiny
  answer (row 3).
- **The production shape, behind nginx, reaches 42-60% of Spring+nginx on tiny answers.** On
  22 KB answers it is 14-17% behind, with a better p99 (row 3).
- **Lightening the hop in place stops at a Vert.x relay's own floor.** That is +4..+25% on tiny
  answers. Native epoll is the only knob that moves it. Dropping the interceptors and moving the
  inner hop to a domain socket move nothing (rows 5 and 7).
- **Only removing the second HTTP layer closes the gap.** An in-process handoff would do that. It is
  not built, for the reasons and with the triggers under [The decision](#the-decision).
- **The default front door refuses most of a load test.** With the defaults, 65-90% of a closed-loop
  load test's requests are refused with 503 at the front door (row 2). That, and a capacity page that
  describes a worker pool no route runs on any more, are what
  [capacity-defaults.md](capacity-defaults.md) fixes. Since its S1, 32 workers are refused none of
  the time.

## How it was measured

| | |
| --- | --- |
| Machine | 12th Gen Intel Core i7-12700K, 20 hardware threads, 31 GB. WSL2 (kernel 5.15.133.1) running the Dev Container. The load generator, the servers and the database all share the box. |
| Software | Microsoft OpenJDK 25.0.4.1. Vert.x 5.1.8, Netty 4.2.18, vertx-http-proxy 5.1.8, HikariCP 7.1.0. PostgreSQL `postgres:16-alpine` in Testcontainers. Spring Boot 4.1.1 (`spring-boot-starter-webmvc`, embedded Tomcat, `JdbcTemplate`). nginx `nginx:1.29-alpine`. |
| Servers | Each target is its own process with `-Xms1g -Xmx1g`, started, polled until it answers, loaded, and stopped before the next. TesseraQL targets use the `tesseraql-host` distribution's runtime classpath. |
| Routes | Two `query-json` routes, `auth: public`, answering `{"data": rows}`. `one` is `select 1 as id, 'item' as name`: 33 bytes. `rows` is 200 generated rows of five columns: 22,114 bytes. The Spring app serves the same SQL through `JdbcTemplate.queryForList` behind `@GetMapping`, and the bodies are byte-identical. |
| Pools | HikariCP, 10 connections everywhere (TesseraQL's default, and Spring's set to match). |
| Load | `tesseraql bench --no-scrape`: a closed loop over HTTP/1.1, each of `c` workers sending its next request when the last one answers. There is a 20 s warm-up per route at `c=64`, then 15 s at each of `c` = 32, 64 and 128. |
| Bounds lifted | The benchmark application declares `tesseraql.http.maxInFlight: 1024`, and the stack declares `tesseraql.gateway.maxConcurrentPerMember: 1024`. With the defaults the front door refuses most of the load (row 2), which measures the refusal and not the hop. |
| Noise | Run to run, one target moves ±5-7%. Compare targets within one table: each table is one run. |

The targets:

| Label | What runs |
| --- | --- |
| `tesseraql-direct` | One runtime, no gateway: `TesseraqlRuntime.start(appHome, port)` |
| `tesseraql-host` | `tesseraql host --stack <dir>`: the shipped topology, with the gateway and the member in one JVM, each on its own Vert.x |
| `spring-mvc` / `spring-mvc-vthreads` | The Spring Boot app, with Tomcat's 200 platform threads, or with `spring.threads.virtual.enabled=true` |
| `…+nginx` | The same target behind nginx, which runs in the Dev Container's network namespace (127.0.0.1 both ways, no extra network hop). It uses `worker_processes auto`, upstream `keepalive 256`, HTTP/1.1 upstream and `access_log off` |
| `tesseraql-host-h2c` | `host --http2`: h2c on both of the gateway's ends. The load generator speaks HTTP/1.1, so only the inner hop changes |
| `tesseraql-host-shared` | A throwaway patch: the front door and the members on **one** Vert.x instance |
| `front-raw-vertx` | One runtime plus the smallest honest relay, on its own Vert.x in the same JVM: same method, URI and headers out, status, headers and body back through `pipeTo` |
| `front-vertx-http-proxy` | The same, with a vanilla `HttpProxy.reverseProxy(client).origin(port, host)`: the library with none of TesseraQL's interceptors |
| `host-lean`, `host-native`, `host-native-uds` … | Row 7's throwaway patches, one switch each |

## What was measured

Throughput is in requests per second. p99 is in milliseconds at `c=128`, unless the row says
otherwise.

| # | Question | Finding |
| --- | --- | --- |
| 1 | The runtime against Spring Boot | **1 row, `c`=32/64/128:** direct 44.1k/54.9k/55.3k (p99 6.1). Spring MVC 41.9k/52.5k/56.9k (p99 12.2). Spring MVC on virtual threads 34.3k/41.5k/41.9k (p99 9.0). `host` 29.1k/28.8k/25.2k (p99 11.2). **200 rows:** direct 16.7k/17.2k/17.5k (p99 19.1). Spring MVC 16.1k/15.9k/16.3k (p99 36.2). On virtual threads 13.0k/13.7k/14.4k (p99 21.8). `host` 14.8k/14.8k/14.6k (p99 18.2). Spring MVC against direct: −5/−4/+3% and −4/−8/−7%. **A second reading, with the load generator in the runtime's own JVM**, compared the runtime with a hand-written Vert.x server over the same stack: the worker pool at 10, HikariCP at 10, the PostgreSQL driver and Jackson 3, with `executeBlocking` around the query. 1 row: runtime 44.7k/53.2k/53.9k, hand-written 49.7k/73.9k/88.7k. 200 rows: 17.8k/18.4k/18.4k against 16.9k/17.7k/19.3k. The framework's per-request work shows on the tiny answer and disappears on the larger one. **The 10,790 req/s that started this** was 8 workers on the 200-row route. In the same in-process harness the runtime reads 1.6k at `c`=1, 10.9k at 8, 19.0k at 32 and 18.6k at 64 and 128 on that route, so 10,790 is a latency-bound reading at 8 workers, not a ceiling. |
| 2 | The default front door | `tesseraql-host` with the bounds at their defaults: `tesseraql.gateway.maxConcurrentPerMember` = the stack's `tesseraql.http.workerThreads` = 10. The gateway refuses the excess with `TQL-RATE-4294` at once, by design ([http-threading.md](http-threading.md) decision 5). **Refused:** on the 1-row route 65% at `c`=32 (243,615 answered 200, 449,353 answered 503 in 15 s), 81% at 64 and 87% at 128. On the 200-row route 79%, 88% and 90%. The member's own gate admits 40 (`maxInFlight` = `workerThreads` × 4), so under a stack it is never reached. See [capacity-defaults.md](capacity-defaults.md) rows 3-4. **Measured again after S1**, with the share defaulting to the member's 40 and no stack file at all. **1 row:** no refusal at `c`=32 (31.5k answered, the same as with the bounds lifted); 18% refused at 64 and 61% at 128. **200 rows:** none at 32 (15.1k); 25% at 64 and 73% at 128. Every refusal is the front door's `TQL-RATE-4294`, not the member's `4293`. The door's count includes the relay's own time, so it reaches the shared forty first. A closed loop of 64 workers against a 40-request bound is expected to see refusals. |
| 3 | Behind nginx | One run, 1 row \| 200 rows, `c`=32/64/128. **direct** 44.1k/54.2k/55.3k \| 17.0k/16.9k/16.8k. **direct+nginx** 37.9k/46.1k/43.2k \| 14.0k/14.0k/15.8k. **host** 31.6k/30.8k/29.5k \| 14.1k/14.4k/12.9k. **host+nginx** 22.6k/23.9k/20.4k \| 12.1k/12.1k/12.1k (p99 21.2). **Spring** 43.9k/54.5k/57.5k \| 16.0k/16.2k/16.3k (p99 36.4). **Spring+nginx** 37.9k/45.2k/48.4k \| 14.1k/14.5k/14.2k (p99 34.2). **The nginx hop** on direct costs −14/−15/−22% and −18/−17/−6%, and on Spring −14/−17/−16%. **The gateway hop** on direct costs −28/−43/−47% and −17/−15/−23%: two to three times an nginx hop on the tiny answer. **Production shapes:** host+nginx against Spring+nginx is −40/−47/−58% on 1 row and −14/−17/−14% on 200 rows. direct+nginx ≈ Spring+nginx. **Latency cost at the median, `c`=32:** 0.7 → 1.0 ms (1 row) and 1.8 → 2.1 ms (200 rows). |
| 4 | Two cheap knobs: h2c inside, one Vert.x | One run, `host` 29.7k/29.0k/27.3k \| 14.8k/14.9k/14.5k. **`--http2`** (h2c on the inner hop) 31.2k/34.6k/34.8k \| 10.1k/9.9k/13.3k: +5/+19/+27% on the tiny answer, **−32/−34/−8% on 22 KB**. **One Vert.x** for the front door and the member 21.4k/19.8k/18.7k \| 12.5k/12.1k/11.6k: **−28/−32/−32%** and −16/−19/−20%. One Vert.x with h2c 22.8k/25.7k/26.5k \| 10.5k/10.8k/10.7k. Neither knob recovers the hop: h2c trades small answers against large ones, and sharing the event loops between the two HTTP layers is worse on both. |
| 5 | The floor any Vert.x relay sets here | One run, 1 row. **direct** 44.4k/53.7k/54.1k. **The smallest honest relay** 35.6k/41.8k/38.5k: −20/−22/−29% against direct. **Vanilla vertx-http-proxy** 33.8k/37.0k/33.7k: the library costs −5/−11/−13% over the smallest relay. **`host`** 30.3k/30.3k/29.4k: TesseraQL's own layer costs −10/−18/−13% over the vanilla library. On 200 rows the three relays are within 4% of each other at `c`=32 and 64 (15.3k-16.0k), 4-21% under direct (16.3k/18.1k/18.2k). **Most of the hop is the relay itself:** two HTTP codecs, a second connection and two event-loop handoffs. |
| 6 | Where the time goes (JFR) | `host` under JFR's `profile` settings. Execution samples in the window t+40..85 s (the three 33-byte points), 4,323 samples, each classified by the first rule its stack matches: **member route pipeline 38.8%**, other (JDK, pools, idle spin) 18.6%, **transport (Netty/Vert.x HTTP, both hops) 17.2%**, member JDBC and pool 10.9%, **`StackRelay` and what it calls 10.0%**, vertx-http-proxy 4.5%. Under `StackRelay` the innermost frame is `LiveOrigin.create` on 238 samples: building the origin request and borrowing a client connection, which is Future chaining and pool lookups. `BodylessRequestsHaveZeroLength` has 66 and `ActivationAddress` 7: **the interceptors are not the cost.** vertx-http-proxy's top self frames are header-name validation and `DateTimeFormatter` (the `Date` header). |
| 7 | Lightening in place (throwaway patch, reverted) | Three switches, each a system property. **E1:** each member's proxy carries its origin and no interceptors. **E2:** both Vert.x instances prefer the native transport, with `netty-transport-native-epoll` 4.2.18 `linux-x86_64` on the classpath (`isNativeTransportEnabled()` confirmed true). **E3:** each member also listens on a Unix domain socket, and the relay's origin connects there. One run, 1 row \| 200 rows. **direct** 42.7k/52.8k/54.1k \| 16.9k/17.9k/18.2k. **direct, epoll** 43.3k/53.0k/54.8k \| 16.9k/17.5k/18.0k. **host** 31.3k/32.9k/30.3k \| 14.7k/14.7k/14.8k. **E1** 31.8k/33.6k/31.1k \| 14.5k/14.5k/14.4k: +2/+2/+3%, noise. **E2** 32.7k/36.2k/34.7k \| 15.0k/15.0k/15.1k: +5/+10/+15% on the tiny answer and p99 8.4 → 6.5 ms; a second run read 33.1k/35.3k/34.4k. **E2+E3** 32.8k/34.8k/36.2k \| 14.1k/15.1k/15.1k: nothing over E2. **All three** 32.4k/39.2k/37.9k \| 15.1k/15.4k/15.4k: +4/+19/+25% over `host`, still −24/−26/−30% against direct. That is row 5's floor. **A trap met on the way:** Vert.x's HTTP client over a domain socket writes no `Host` header, and the member refuses the request with a router-level 400 ("For HTTP/1.x requests, the 'Host' header is required"). The patch had to set `RequestOptions` host and port. |

## What each option would cost

### Native epoll: the one knob that moves

Row 7 measured +5..+15% on the tiny answer and nothing measurable on 22 KB. What shipping it would
take, and what it risks:

- **It can fall back to NIO silently.** Vert.x logs a warning and runs NIO when the native library
  does not load, and each of these conditions triggers that:
  - The release builds the image for `linux/amd64` and `linux/arm64` (`release.yml:349`), and the
    native jar is per architecture.
  - The native jar must match Netty's version exactly, so a Vert.x or Netty bump that moves one
    without the other breaks it.
  - Netty extracts the `.so` into `java.io.tmpdir`, which a hardened pod mounts `noexec` or
    read-only.
  - A later JDK may deny native access by default (JEP 472).

  Shipping it would mean surfacing the transport in use at startup and in the ops metrics, and a
  Linux CI assertion that epoll is on.
- **It would be the first JNI library the shipped runtime loads.** [jvm-baseline.md](jvm-baseline.md)
  deferred `--enable-native-access` until an observation stood behind it. Without the flag, every
  start prints a restricted-method warning. With it, every launcher changes: the Dockerfile
  entrypoints, the jpackage images, the Windows zip and WinSW, and the CLI scripts.
- **Socket-level behaviour differs.** Close, half-close and reset timing, and the exception types,
  differ between the transports, and every test runs on NIO today. The relay has depended on a
  transport detail before: #1282, where `EarlyResponseDrain` relied on a reset closing the
  connection. Running Linux CI on epoll would leave the NIO path that macOS and Windows users run
  without Linux coverage, and running both doubles that CI time.
- It adds about 200 KB per architecture to the runtime closure, with the footprint rules to match.

### Process separation

The hop is already HTTP ([stack-architecture.md](stack-architecture.md) decision 15), so separating
the gateway from its members changes an address, not a cost. Two JVMs would be the same or slightly
worse, because of separate GC and CPU scheduling. That comparison is unmeasured.

Separation pays only if the edge routes to the members **directly**. At `c`=64, nginx → member
answered 46.1k where nginx → gateway → member answered 23.9k on the tiny answer, and 14.0k against
12.1k on 22 KB (row 3). The price is moving what the gateway does today to the edge:

- `/<name>` routing and the ingress header strip
- the per-member admission and its 503
- the live swap (`ActivationAddress`, `RetryOnceAcrossTheSwap`), which becomes a platform rolling or
  blue-green update
- the stack surface (sign-in, account, the portal) and the root redirect, which become a process of
  their own

That rewrites decisions 12 and 13.

The costs:

- Each process pays the JVM baseline decision 15 measured: 22 MB of heap, 29 MB of metaspace and
  about 2,200 ms to start. An additional runtime in the same JVM pays 8 MB, 0 MB and about 600 ms.
- Process supervision, port allocation, shutdown ordering and a new class of failure states.
- The shared-schema migration has to finish before any member starts, and that ordering must now
  hold across processes (decision 16).
- Secret resolution is per process.
- The ops console has to aggregate across processes.
- The development loop stays one JVM (decision 12), so there are two topologies to test.

Decision 15's triggers are failure isolation, per-application upgrade and canary in production, and
per-application resource limits. Speed is not among them.

### An in-process handoff: the only change that removes the second HTTP layer

The gateway would hand the request to the member's router in the same JVM, with no second HTTP layer
to parse and encode. It could reach nginx's cost or better. It is also where the regressions are,
because member code assumes the connection belongs to its own server and that the caller is the
gateway:

1. **Execution context.** `RouteEdge` captures `ctx.vertx().getOrCreateContext()` and writes the
   answer back with `runOnContext` (`RouteEdge.java:322`). The front door and the members run on
   separate Vert.x instances, so a handoff needs one instance. With the relay still in place, one
   instance measured −28..−32% (row 4). Without the relay it is unmeasured, and that measurement
   decides whether the handoff gains anything at all.
2. **A member's connection close reaches the caller.** `HttpBodyLimit` (`:118`, `:126`, the 413
   paths), `RouteEdge` (`:530`, `:642`, a failure mid-stream) and `SseRoutes` (`:197`) close
   `request.connection()`. Today that is the loopback connection, and `EarlyResponseDrain` keeps the
   caller's keep-alive connection where it can. With a handoff it is the caller's own connection,
   and over h2c that means every multiplexed stream on it.
3. **Per-application server options collapse into the front door's.**
   `TesseraqlHttpServer.serverOptions` (`:121`) sets `maxFormFields`, `maxFormAttributeSize` (the
   body limit plus one transport delivery, so that the documented 413 wins by construction) and the
   idle timeout per application. The form decoder reads the options of the server that owns the
   connection.
4. **A slow client holds member resources.** A streamed answer waits for each chunk's write to
   complete (`RouteEdge.java:657`). Today the loopback socket buffers and the relay absorb a slow
   reader. With a handoff, the route's virtual thread, its admission permit and, mid-export, a
   database connection are paced by the client. An edge that buffers, nginx's default, removes this.
5. **Failure containment.** The front door's own Vert.x keeps readiness answerable when a member's
   event loop stalls, and a shared loop stalls with it. The swap-race retry, a refused connection
   retried once, needs an in-process equivalent.
6. **The member would see the real peer.** The relay adds no `X-Forwarded-For`:
   `ForwardedHeadersOptions.DEFAULT_ENABLED` is `false` in vertx-http-proxy 5.1.8, and the gateway
   passes no options. So without an edge every caller presents as 127.0.0.1 to the credential
   throttle and the sign-in allow-list, which both read `X-Forwarded-For` first and the peer
   otherwise. A handoff would show them the real peer, which is an improvement, but an allow-list
   that names 127.0.0.1 would stop admitting. [deployment.md](deployment.md) already requires an HTTPS
   edge, and [authentication.md](authentication.md) puts overwriting the header on it, so a
   conforming deployment sees no change.
7. **The activation path rewrite needs a request wrapper.** Whether vertx-web leans on internal
   interfaces for that was not checked.

Items 2-5 are how `tesseraql serve` behaves already, because it has no gateway. A handoff makes a
hosted member meet its callers the way a standalone runtime does.

## The decision

**The request path stays as it is.** `tesseraql host` keeps the gateway and its members in one JVM.
The relay stays loopback HTTP/1.1 through vertx-http-proxy, on NIO. Production sits behind the edge
[deployment.md](deployment.md) already requires, which terminates TLS, buffers answers and overwrites
`X-Forwarded-For`. More throughput comes from more nodes: the M10 proof ran two
([deployment-maturity.md](deployment-maturity.md)).

- **The gap is on answers that cost almost nothing to make.** The hop adds about 0.3 ms at the
  median under load (row 3), and a request that spends milliseconds in the database dilutes it to a
  few percent. On 22 KB the production shape is 14-17% behind Spring+nginx, with a better p99.
- **Every way of lightening the hop is either small or expensive.** In place, the ceiling is the
  relay's floor, and the one knob that reaches it carries the operational risks above. Otherwise it
  means rewriting decisions 12, 13 and 15 and accepting the regressions listed.
- **The HTTP boundary is what keeps separation available.** Decision 15 chose loopback HTTP so that
  separating processes stays a change of address.

### Revisit when

- **One node's throughput falls short and more nodes cannot absorb it.** Consider the in-process
  handoff. Start with a throwaway measurement of whether one shared Vert.x gains anything once the
  relay is gone, because row 4 says it might not. Then design items 1-7.
- **One of decision 15's own triggers appears.** Consider process separation, with the edge routing
  to members directly.
- **A node's throughput is needed and the handoff has been refused, or Vert.x and Netty make the
  native transport their default.** Consider epoll, with the fallback made visible.

## Reproducing

None of the harness was committed. To rebuild it:

1. **A JUnit test in `tesseraql-cli`'s test scope** (`SpringComparisonBenchTest`) starts
   `postgres:16-alpine` through Testcontainers. It writes the two routes and an
   `application.yml` into a temporary stack directory, plus a `tesseraql-stack.yml` that lifts the
   front door's bound.
2. **The test starts each target as a child process.** The classpath is
   `dependency:build-classpath -DincludeScope=runtime` of `tesseraql-host` plus its jar. Note that
   `-Dmdep.includeScope` is silently ignored and puts test jars on the path. Two throwaway mains sit
   beside the test: `TesseraqlRuntime.start` for `direct`, and the relay of row 5.
3. **The load comes from the CLI's own `bench` command,** called in-process:
   `bench --app <dir> --url <base> --route <id> --concurrency <c> --duration 15s --format json --no-scrape`.
4. **nginx runs in the Dev Container's network namespace,** with
   `docker create --name <n> --network container:<devcontainer-id> nginx:1.29-alpine`, then
   `docker cp` of the configuration and `docker start`. A bind mount would name a path on the
   Docker host, not in the Dev Container.
5. **The patches for rows 4 and 7 were one-line switches** behind `-Dbench.*` system properties,
   reverted afterwards, with the reinstalled runtime jar checked for leftovers.

The Spring app is `spring-boot-starter-webmvc`, `spring-boot-starter-jdbc` and the PostgreSQL
driver, with one `@RestController` and `spring.datasource.hikari.maximum-pool-size=10`.

Compare targets only within one run.

## Filed from this measurement

[capacity-defaults.md](capacity-defaults.md) covers two findings:

- the front door's per-member bound derives from a worker pool no route runs on (row 2)
- the capacity page's model and budget describe that pool

It also designs the pool layout a new application gets for production.
