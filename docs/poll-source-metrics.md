# Poll Source Metrics

Design document. [poll-source-status.md](poll-source-status.md) closed the poll-source
observability hole *inside the console*: the registry knows which sources were refused at
wire time and which are failing repeatedly, the jobs page shows it, the overview alerts on
it. All of that requires a person looking at a page. Outside the console the alerting
story is unchanged — a partner noticing missing files — because the registry never reaches
the scrape: `/_tesseraql/metrics` exposes route counters and latency histograms, nothing
about polling. Both building blocks exist; this document connects them, and rides one
adjacent counter along: egress denials from `http:` steps, the "same idea, different
subsystem" item the poll-status design left out of scope.

## Decisions

### 1. Gauges rendered at scrape time from the registry, not new meter machinery

`AggregatingMeter` is a push-side aggregator (counters and histograms); poll-source state
is registry-derived, and the interesting number — how long since the last poll — must be
computed at scrape time. So the metrics route appends a gauge exposition rendered from
`PollSourceStatus` at each scrape, rather than teaching the meter a third instrument kind:
one consumer does not justify adding callback gauges to every `Meter` implementation
(aggregating, composite, no-op, OpenTelemetry). `PrometheusTextFormat` gains a public
gauge-family renderer so name sanitization and label escaping stay in one place, and a
small `PollSourceMetrics` (ops-ui, beside the registry) maps registry state to three
families:

- `tesseraql_poll_source_wired{jobId="..."}` — `1` polling, `0` skipped at wire time.
  The alertable fact: `== 0` means the source an author declared is not running.
- `tesseraql_poll_source_consecutive_failures{jobId="..."}` — the streak the console's
  alert threshold already watches; an external rule can pick its own threshold.
- `tesseraql_poll_source_last_poll_age_seconds{jobId="..."}` — present only once a poll
  has completed. Absence is honest: the age of a poll that never happened is not zero,
  and `absent()` is the right PromQL question for a source that has never fired.

### 2. `jobId` is the only label

The source string is not a label: it can embed connection detail, it churns time series
when configuration changes, and the console already shows it beside the reason. Skip
reasons are unbounded prose and stay off the exposition entirely — the gauge value is the
alertable fact, the jobs page holds the words. Metrics carry identity; pages carry
explanation.

### 3. Per-node exposition is correct, not a limitation

The registry answers "is this node polling", and Prometheus scrapes per instance, so each
node's scrape carrying its own registry is exactly the model — no aggregation layer
belongs here. Cross-node aggregation stays where the poll-status design left it, with the
cluster work.

### 4. Egress denials become a counter on the existing meter

`HttpCallClient` takes the runtime `Meter` and increments `tesseraql.egress.denied`
(label `host`) when it refuses a call with `TQL-BATCH-5305`. Unlike poll state this is an
event, so the existing counter machinery is the right shape and the OTLP push path carries
it for free. The label is bounded — target hosts come from authored YAML, and denied ones
from the same place. A denial is already loud per-execution (the step fails with the error
code); the counter adds the fleet-level view: a *rate* of denials after a config rollout
is an alertable regression that no individual failed step makes visible.

### 5. The deployment page documents the families

The [deployment.md](deployment.md) metrics section lists the new families next to the
route metrics, with one sample alert expression per gauge family, so an operator wiring
alerts does not have to reverse-engineer names from a scrape.

## Out of scope

- **Grafana dashboard additions** (`deploy/grafana/`): the dashboard is a curated
  artifact; a poll-health row can follow once the families exist in a scrape to design
  against.
- **Circuit-breaker state gauges** (`TQL-BATCH-5306`): breaker state is per-host and
  in-memory in the client; exposing it wants a think about what the label means across
  nodes, not a stow-away line in this slice.
- **Cross-node aggregation** — unchanged from poll-source-status.md.

## Testing

- `PrometheusTextFormat` unit: gauge family rendering (sanitization, label escaping, an
  empty sample list renders nothing).
- `PollSourceMetricsTest`: a polling source renders `wired 1`; a skipped source renders
  `wired 0`; failures render the streak; the age line appears only after an import and
  computes from a fixed clock.
- `MetricsEndpointIntegrationTest`: a runtime with a poll-triggered job exposes the
  poll families on the scrape.
- `HttpCallClientTest`: a denied host increments `tesseraql.egress.denied` with the host
  label (recording meter); an allowed call does not.
