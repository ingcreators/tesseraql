# Poll Source Status

Design document. A misconfigured poll source is "logged and skipped rather than failing
the whole runtime" — the right availability call, with the consequence that the only
signal an operator gets is a log line at startup: not wired, refused credential,
egress-denied host, missing trust store. A healthy-looking runtime can be silently not
polling anything, and a partner noticing missing files is the current monitoring story.
Runtime failures (a poll that starts failing after boot) are equally invisible outside
the log.

## Decisions

### 1. A per-node, in-memory status registry, fed at both wire time and poll time

`PollSourceStatus` (ops-ui or operations module): one entry per poll-triggered job —
`state` (`POLLING` / `SKIPPED` with the wire-time refusal reason), `lastPollAt`,
`lastResult` (imported count or error), `consecutiveFailures`. `PollingRouteBuilder`
records the skip reasons it already logs; the poll route records each completion or
failure. In-memory per node like the trace ring — polling is node-local work, and the
registry answers "is this node polling", which is the question.

### 2. Surfaced on the ops console jobs page, plus an overview alert

Poll-triggered jobs already appear on the jobs page (trigger `poll`); their rows gain
the status: skipped-with-reason renders as an error badge with the reason text, a
healthy source shows its last poll time and result. `OpsDashboard.alerts()` gains one
alert kind: a poll source skipped at wire time or failing repeatedly
(`consecutiveFailures` over a threshold) — so the console's existing alert surface and
the outbox-riding alert notifier both carry it without new plumbing.

### 3. Read-only in this slice

No pause/resume/trigger-now actions: pausing a Camel consumer has lifecycle
implications that deserve their own design, and trigger-now already exists for
schedule-triggered jobs via the run button. This slice is purely closing the
observability hole.

## Out of scope

- Cross-node aggregation (a cluster view needs a store; the per-node registry is the
  building block).
- Egress-denial counters for `httpCall` steps (same idea, different subsystem).
- Prometheus exposition of the registry (rides the existing meter once the registry
  exists).

## Testing

- Integration: a poll job with an unreachable/refused configuration boots the runtime,
  the jobs page shows the skip reason, and the overview shows the alert; a working
  local-directory poll source shows its last poll facts after a file lands.
