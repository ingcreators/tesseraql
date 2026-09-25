# Sizing a node

This page is for the person who has to decide how many connections each pool holds, how many
requests a node may queue, how many replicas to run, and what memory to give each. It gives you the arithmetic over the numbers the runtime
declares, the signals on the scrape that say which bound is binding, and `tesseraql bench`, a
harness that measures one node with the application's own routes. The method is the point:
the one measured example below is one box's numbers, and you reproduce it on yours in a minute.

## The arithmetic

Little's law is the whole of it: **concurrency = throughput × latency**. A route answering in
25 ms at 400 requests a second holds 10 requests in flight, on average. Every bound the runtime
declares is a ceiling on that concurrency, so sizing is finding the smallest ceiling and raising
it with its twin.

| Key | Default | What it bounds | Its twin |
| --- | --- | --- | --- |
| `tesseraql.datasources.<name>.maximumPoolSize` | 10 | connections a runtime holds, and so the routes that need one running at once; the rest wait for a connection | `maxInFlight` |
| `tesseraql.http.maxInFlight` | 40 | requests held at once, running or waiting, before the runtime refuses (`TQL-RATE-4293`); the queue you can see | `maximumPoolSize`: raise them together so a queue remains |
| `tesseraql.http.maxEventStreams` | same as `maxInFlight` | event streams held open at once before a refusal (`TQL-RATE-4295`) | the live pages you expect open |
| the stack's per-member share ([deployment](deployment.md#the-front-doors-share-of-each-member)) | 40 | forwards the front door holds for one member (`TQL-RATE-4294`, streams `4296`); a member's assets and health pass it | the member's `maxInFlight`: a member that declares more is named at start |
| an execution lane's `maxConcurrency` | per lane | one class of work, refused at its own ceiling (`TQL-OPS-9002` on the alerts) | the pool the lane's work uses |
| a route's `rateLimit:` with `scope: cluster` | per route | a budget shared by every node, refused as `TQL-RATE-4291` | none: it is a policy, not a capacity |
| `tesseraql.http.workerThreads` | 10 | Vert.x's own file I/O; no route runs on it | none |

A ceiling that is never reached costs nothing. A ceiling that is reached refuses with a code,
and the code names the ceiling, so the first thing to read after a refusal is which code it was.

## The signals

The scrape (`/_tesseraql/metrics`, [deployment](deployment.md#metrics-prometheus)) carries one
family per question, per node.

| Family | Reads as | Points at |
| --- | --- | --- |
| `tesseraql_route_duration_seconds` p95 | is the service time growing under load | the database, or a route's own work |
| `tesseraql_http_in_flight{kind="request"}` | how full the queue is against `maxInFlight` | `maxInFlight` and `maximumPoolSize` |
| `tesseraql_http_refused_total{code}` | what was refused, by which ceiling | the key each code names above |
| `tesseraql_pool_threads_awaiting{pool}` | requests waiting for a connection: the pool, not the database, is the constraint | `maximumPoolSize` |
| `tesseraql_lane_in_use{lane}`, `tesseraql_lane_rejected_total{lane}` | how full a lane runs, and what it refused | that lane's `maxConcurrency` |
| `tesseraql_jvm_memory_used_bytes{area="heap"}` | the heap under load; what the container's limit has to leave room for | the memory request |

The same conditions page through the alerts channel as `TQL-OPS-9011` (the pool) and `9012`
(refusals at capacity), so a node that hits a ceiling reaches a human without a dashboard
([notifications](notifications.md#operations-alerts)).

## Measuring one node

`tesseraql bench` drives the application's own declared routes against a running node and
answers with exact percentiles, the status mix and the refusals classified by their code. It
knows the routes and their inputs from the manifest, the token exchange from `tesseraql token`,
and the refusal codes from the runtime, which a general-purpose load tool would have to be taught.

```sh
tesseraql bench --app . --url https://stack.example.com/orders --route orders.search --concurrency 20 --duration 30s
```

`--route` drives a GET route filled from its declared inputs' defaults. For more than that,
write a scenario, `bench/<name>.yml`, a document of its own kind:

```yaml
version: tesseraql/v1
kind: bench
description: the browse mix at the afternoon peak
concurrency: 40
duration: 1m
rampUp: 10s
requests:
  - route: orders.search
    params: { q: "widget" }
    weight: 9
  - route: orders.detail
    params: { id: 1042 }
```

| Key | Meaning |
| --- | --- |
| `requests[].route` | a route id, as `tesseraql routes` lists it |
| `requests[].params` | the route's declared inputs; a path placeholder is filled first, the rest go to the query string |
| `requests[].body` | the JSON body a write sends |
| `requests[].weight` | how often this request is chosen relative to the others (1 by default) |
| `concurrency` | workers in the closed loop: each sends its next request as soon as the last one answered (10 by default) |
| `duration`, `rampUp` | how long the run lasts, and the span the workers start across |
| `rate` | open loop instead: requests per second offered, with `concurrency` as the bound on requests in flight |
| `writes: allowed` | required before a request may drive a route that is not GET or HEAD |

`tesseraql lint` checks a scenario like a suite: an unknown route (`TQL-YAML-1414`), a param the
route does not declare (`1415`) and a write without `writes: allowed` (`1416`) are errors before
a request is sent. A write the scenario allows runs with an `Idempotency-Key` the harness mints
per request. The command line overrides the scenario's shape: `--concurrency`, `--duration`,
`--ramp-up` and `--rate`.

```sh
tesseraql bench --app . --url https://stack.example.com/orders --scenario bench/browse.yml --format json --expect 'p95<250ms,refused<1%'
```

`--expect` turns thresholds into exit code 3, the code that already means "a policy gate said
no": `p50`, `p95`, `p99` and `max` in `ms` or `s`, `refused` and `errors` as a `%` of requests
or a count, `throughput` in requests per second. A route that needs a session takes the bearer
from `--token-file`, `TESSERAQL_TOKEN`, or `--login` with `TESSERAQL_PASSWORD`. With a token
holding `ops.metrics.view` (or a scrape configured unauthenticated), the harness reads the
scrape before and after the run and prints the movement of the signals above beside the
percentiles, so the tuning readout is in the same terminal as the load.

The report reads like this: the requests and the throughput, the percentiles, the status mix,
each refusal code with the ceiling it names, the errors, the scrape's movement, and the verdict
on each expectation.

## A worked example

One box's numbers, not a promise. The box is a 20-thread development machine with PostgreSQL in
a container beside it. One runtime runs at every default, a pool of 10 and `maxInFlight` 40,
and drives a route whose query holds its connection for 5 ms
(`select 1 as one from pg_sleep(0.005)`). Each run is eight seconds at a fixed number of
workers. The last column is what the runtime refused.

| Workers | Answered | p50 | p95 | Refused |
| --- | --- | --- | --- | --- |
| 1 | 137/s | 7.5 ms | 8.2 ms | 0 |
| 10 | 1,468/s | 6.8 ms | 7.8 ms | 0 |
| 40 | 1,771/s | 22.6 ms | 24.0 ms | 0 |
| 80 | 1,809/s | mixed with the refusals | | 378,670 × `TQL-RATE-4293` |

The reading: throughput saturates at ten workers, which is the pool, and past ten the latency
grows with the workers. Little's law says why. Each request holds a connection for about 5.6 ms,
so ten connections serve at most about 1,780 a second, and forty in flight at that rate is 22 ms
each: ten running, thirty waiting for a connection. On the scrape, that wait is
`tesseraql_pool_threads_awaiting`. At eighty workers the queue of forty is full, and the runtime
refuses the rest with the code that names the bound. A closed-loop worker sends again the moment
it is refused, which is why the count is large while the answered rate stays at the ceiling.

The knob to raise here is `maximumPoolSize`, with `maxInFlight` beside it. With a pool of 20 and
a queue of 80, the same route answers:

| Workers | Answered | p50 | p95 | Refused |
| --- | --- | --- | --- | --- |
| 40 | 3,527/s | 11.0 ms | 12.5 ms | 0 |
| 80 | 3,561/s | 22.2 ms | 24.2 ms | 0 |

The ceiling doubles with the pool, and the queue raised beside it answers eighty workers at the
doubled rate without a refusal. The database is the next ceiling. A pool larger than the
database can serve moves the wait into the database, which is why the budget below counts
connections.

## From one node to replicas

Measure one node at rising concurrency until the first refusal or the p95 knee. Read which
signal moved, raise that key with its twin, and measure again. When the knee no longer moves,
that is one node's capacity. Then:

- **Replicas** = peak concurrency ÷ one node's knee, plus one for a rolling update, which takes
  one node out of the pool at a time.
- **Memory** follows the resident size at the knee, not a table: read
  `tesseraql_jvm_memory_used_bytes` at the knee, give the container that plus the JVM's own
  overhead, and set the heap ceiling as a share of the limit
  (`JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0`).
- **Shared budgets** do not scale with replicas: a `scope: cluster` rate limit is one budget,
  and so is the database's `max_connections` (100 by default on PostgreSQL).
- **Connections are a standing number, not a peak.** A pool holds `maximumPoolSize` connections
  from boot: `minimumIdle` defaults to the pool size, so nothing is handed back when idle. Per
  node, count every pool each application declares. Under a stack, add the stack surface's own
  `main` pool and, when `tesseraql-stack.yml` declares `framework.datasource`, the stack
  framework pool (10 connections each). Multiply by the nodes, and keep the total under the
  database's ceiling. An application used a few minutes a day can declare `minimumIdle` below
  its maximum, with `idleTimeoutMillis`, so that it hands its connections back while idle.

## Next

- [deployment.md](deployment.md) — the metrics families, the alert rules and the container image.
- [hosting.md](hosting.md) — several applications on one node, and a stack on more than one.
- [notifications.md](notifications.md) — the alerts a saturated node pages, and their codes.
