# Sizing a node

This page is for the person who has to decide what `workerThreads` to set, how many replicas
to run, and what memory to give each. It gives you the arithmetic over the numbers the runtime
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
| `tesseraql.http.workerThreads` | 10 | routes executing at once: the pool every request runs on | `maximumPoolSize` of the pool it borrows from |
| `tesseraql.datasources.<name>.maximumPoolSize` | 10 | connections a runtime holds; a worker waits when none is free | `workerThreads` |
| `tesseraql.http.maxInFlight` | `workerThreads × 4` | requests held at once before the runtime refuses (`TQL-RATE-4293`); the queue you can see | `workerThreads` |
| `tesseraql.http.maxEventStreams` | same as `maxInFlight` | event streams held open at once before a refusal (`TQL-RATE-4295`) | the live pages you expect open |
| an execution lane's `maxConcurrency` | per lane | one class of work, refused at its own ceiling (`TQL-OPS-9002` on the alerts) | the pool the lane's work uses |
| a route's `rateLimit:` with `scope: cluster` | per route | a budget shared by every node, refused as `TQL-RATE-4291` | none: it is a policy, not a capacity |
| the stack's per-member share ([hosting](hosting.md)) | per member | forwards the front door holds for one member (`TQL-RATE-4294`, streams `4296`) | the member's `maxInFlight` |

A ceiling that is never reached costs nothing. A ceiling that is reached refuses with a code,
and the code names the ceiling, so the first thing to read after a refusal is which code it was.

## The signals

The scrape (`/_tesseraql/metrics`, [deployment](deployment.md#metrics-prometheus)) carries one
family per question, per node.

| Family | Reads as | Points at |
| --- | --- | --- |
| `tesseraql_route_duration_seconds` p95 | is the service time growing under load | the database, or a route's own work |
| `tesseraql_http_in_flight{kind="request"}` | how full the queue is against `maxInFlight` | `workerThreads` and `maxInFlight` |
| `tesseraql_http_refused_total{code}` | what was refused, by which ceiling | the key each code names above |
| `tesseraql_pool_threads_awaiting{pool}` | workers waiting for a connection: the pool, not the database, is the constraint | `maximumPoolSize` |
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

One box's numbers, not a promise: a 20-CPU development container against a local PostgreSQL,
one runtime with `workerThreads: 10`, `maxInFlight: 40` and a pool of 10, driving a route that
runs `select 1`. Each run is eight seconds at a fixed number of workers.

| Workers | Throughput | p50 | p95 | Refused |
| --- | --- | --- | --- | --- |
| 1 | 924/s | 0.9 ms | 2.3 ms | 0 |
| 10 | 2,691/s | 3.5 ms | 5.7 ms | 0 |
| 40 | 2,765/s | 14.0 ms | 21.6 ms | 3 × `TQL-RATE-4293` |
| 80 | 2,729/s | 28.0 ms | 43.5 ms | 60 × `TQL-RATE-4293` |

The reading: throughput saturates at ten workers, which is the worker count, and latency grows
linearly with the workers past it. Little's law says why: ten workers over a 3.6 ms service
time is 2,700 a second, and every request beyond ten waits its turn in the queue `maxInFlight`
bounds. At eighty workers the queue is full and the runtime refuses the rest with the code that
names the bound. Nothing else moved: the pool never had a borrower waiting, so the pool was not
the constraint. The knob to raise here is `workerThreads`, with `maximumPoolSize` beside it.

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
  and the database's connection ceiling is `maximumPoolSize` × replicas.

## Next

- [deployment.md](deployment.md) — the metrics families, the alert rules and the container image.
- [hosting.md](hosting.md) — several applications on one node, and a stack on more than one.
- [notifications.md](notifications.md) — the alerts a saturated node pages, and their codes.
