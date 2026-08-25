# Process-control gaps — six candidates for the business-application surface

> **Status: gap analysis; nothing here is committed.** Recorded 2026-08-25 from a
> survey of the shipped YAML schemas (`tesseraql-yaml/src/main/resources/schema/`),
> the generated [surface reference](reference-yaml-surface.md), and the recorded
> design decisions. The question asked: across the whole YAML surface, what does a
> business application need for process control that it cannot yet declare? Every
> candidate below keeps the framework's stance — declarations, guards and SQL;
> no general-purpose control flow — and sketches the smallest declaration that
> would close its gap. Sketches are starting points for their own designs, not
> specifications.

## The stance this analysis holds

The absences that look loudest are not gaps. They are decisions with recorded
reasoning, and the candidates below are chosen to fit inside them, not to reopen
them:

- **No BPMN and no embedded workflow engine** — weighed and rejected
  ([approval workflow](approval-workflow.md) design notes; roadmap decision 2).
  `dispatch:` selects among declared transitions and introduces no new control
  flow ([workflow expressiveness](workflow-expressiveness.md)).
- **No XA, no two-datasource transaction** — a business operation is one local
  transaction on one connector; cross-database consistency is the outbox and a
  projection ([multi-datasource](multi-datasource.md); roadmap Phase 53).
- **No job-net/DAG orchestration and no preemptive kill** — external schedulers
  own the net; `after:` is the ceiling; stop is cooperative
  ([batch platform](batch-platform.md)).
- **No scripting steps and no flow engine in decisions** — decisions produce
  values; branching stays at the declared conditional points
  ([decision tables](decision-tables.md); [extending](extending.md) is the
  ladder for everything else).

What remains after those decisions is the sequential `steps:`/`pipeline:` model
with per-key conditionals (`when:`, `statusWhen:`, `requiredWhen`, …) and per-key
error policies (`onError:`, `onMismatch:`, `onOverflow:`, …). The six gaps below
are the places where a typical business application falls off that surface with
no recorded decision covering the fall.

**Priorities.** P1: hit in the first weeks of a typical line-of-business app.
P2: hit at the first external integration or the first operational hardening
pass. P3: hit as an app grows past a handful of screens, or as a portfolio
accumulates.

## Gap 1 (P1) — line items: object arrays have no input contract

A header-plus-lines document — the order and its lines, the journal entry and
its postings — is the most common shape a business form submits. The input
contract stops at the array boundary: `inputField.items` accepts only `type:`
and `enum:` for scalar elements, so an array of objects cannot declare fields at
all. The procurement demo ships the consequence:

```yaml
# examples/procurement-app/web/api/requisitions/post.yml — today
input:
  lines:
    type: array
    required: true          # and that is everything the contract can say
```

`create-lines.sql` then binds `line.itemId`, `line.qty` and `line.desiredDate`
from elements nothing validated. The deny-by-default posture of `input:` —
unknown fields rejected, types coerced, bounds enforced, violations reported
against a field — silently does not apply inside the one place a form carries
most of its data. There is no per-row type coercion, no per-row field error a
grid UI could address, and `inputPolicy.unknownFields` never looks inside an
element.

**Sketch.** `items:` gains `fields:`, a map of the same `inputField` the
top level uses:

```yaml
input:
  lines:
    type: array
    required: true
    items:
      fields:
        itemId:      { type: string, required: true, codes: items }
        qty:         { type: integer, required: true, min: 1 }
        desiredDate: { type: date, required: false }
```

- Elements bind, coerce and validate exactly as top-level fields do; a violation
  reports against `lines[2].qty`, riding the existing field-error contract (the
  htmx fragment gains an indexed address, the JSON error keeps its shape).
- `domain:`, `codes:`, `enum:` and `requiredWhen:` work per element unchanged;
  `requiredWhen` expressions see the element as `item.*` plus `item_index`.
- One level deep. A line is flat; an array inside `fields:` is a lint error.
  Depth is where this feature would start reimplementing JSON Schema, and the
  flat line covers the business shape.
- Unknown element fields follow the route's `inputPolicy.unknownFields`.

What it deletes from the demo: the `linesRequired` hand-rolled rule stays (it is
a size check), but every per-line check an app would otherwise push into SQL or
skip entirely becomes a declaration. The derived form's grid rendering is a
separate, later view-layer slice — the contract comes first.

**Open here:** whether `validate:` needs an `each: <array>` scope for per-row
cross-field rules (`item.qty <= item.maxQty`), or whether the element-level
`requiredWhen`/bounds carry far enough for a first slice.

## Gap 2 (P1) — bulk actions: one transition, many documents

A workflow transition is one document per call: the synthesized route is
`POST {basePath}/{key}/<transition>`. The task inbox lists twenty requisitions,
and approving them is twenty requests — the client loops, and partial failure
reporting is whatever the client improvises. Set-oriented SQL writes already
have their answer (an array input and one `%for` statement,
[transactional writes](transactional-writes.md)); the gap is specifically the
per-document transition pipeline — security, guard, advance, stamp, command,
history — which nothing may bypass.

**Sketch.** A transition (or a dispatch) opts in:

```yaml
transitions:
  - id: approve
    from: submitted
    to: approved
    bulk: true              # also synthesize POST {basePath}/_bulk/approve
    …
```

- The bulk route accepts `keys: []` and runs the full member pipeline per key
  through `TransitionExecutor` ([transition engine](transition-engine.md) built
  the engine object this loop needs) — guards, task authority and the command
  evaluate per document, exactly as the single route would.
- **Each key is its own transaction**, and the response is a per-key outcome
  report in the import idiom (`file-import` already reports rejected rows by
  number): `[{key, status, code, guard?}, …]`. A refused or conflicted key does
  not disturb the others; an all-or-nothing bulk approve is not offered, because
  a 100-document rollback on the 97th guard is not what an inbox user means.
- The route carries the transition's own security spec; `dispatch:` members
  already share one (`TQL-WORKFLOW-3112`), so a bulk dispatch inherits it.
- Idempotency: the existing `idempotency:` block applies to the bulk route as to
  any command.

**Open here:** whether the outcome report caps `keys` (an `admission:`-shaped
bound seems right), and whether a bulk `emit:` coalesces to one topic event or
one per key.

## Gap 3 (P2) — declarative retry for outbound `http:`

`retry:`/`backoff:` appear nowhere in the shipped schemas. Retry exists in
exactly one place: the outbox dispatcher's `tesseraql.outbox.dispatch.maxAttempts`.
A synchronous `http:` binding — the currency-rate lookup a command needs before
it writes, the enrichment call a page renders from — has a binary
`onError: fail | empty`. Transient faults are the normal weather of external
APIs, and today every app answers them with either a user-visible failure or a
silently empty panel.

The safety argument is already on the surface: on a command route the `http:`
arm must assert `readOnly: true` (the write can roll back and the request
cannot), so every call eligible to run inside a command is by declaration safe
to repeat.

**Sketch.** The `http:` arm gains a `retry:` block, with a config default under
`tesseraql.http.outbound.retry`:

```yaml
sources:
  rates:
    http:
      url: https://rates.example.com/v1/latest
      credential: rates
      retry: { attempts: 3, backoff: 200ms, multiplier: 2 }
      onError: empty
```

- Retried: connect failures, timeouts, 5xx. Never retried: `expectStatus`
  mismatches and 4xx — deterministic rejections, the same line the circuit
  breaker already draws.
- Each attempt counts against the per-host circuit breaker and appears as an
  attempt on the one call's span; the metering stays honest.
- Attempts are bounded by the binding's `requestTimeout` budget — a retry that
  cannot fit is not started.
- SQL statements get nothing: the transaction owns them, and the job-level
  answer remains `job rerun` ([jobs](jobs.md) — "there is no automatic retry"
  stays true at the job granularity; this is per-call, inside a step).

## Gap 4 (P2) — scheduled delivery: the outbox learns about later

Everything on the outbox — `notify:`, `publish:`, `outbox:` — is delivered
as soon after commit as the dispatcher gets to it. The only future-time
construct on the whole surface is the workflow's `deadlines:` block, which
serves workflow documents alone. "Remind the customer three days after the
order ships", "send the digest at 07:00 in the recipient's zone" — today each
of those is a cron job scanning an app table the command must remember to
populate: the denormalized-counter friction again, one surface over.

**Sketch.** Outbox entries gain a not-before instant, in two declared forms:

```yaml
notify:
  shipped-reminder:
    channel: customer-mail
    delay: 72h                       # relative to commit
    payload: { order: steps.header.keys.id }
  pickup-window:
    channel: customer-mail
    deliverAt: params.pickupStart    # a bindable path resolving to an instant
    payload: { order: steps.header.keys.id }
```

- The entry is written in the command's transaction as today; the row carries
  `not_before`, and the dispatcher — which already polls — skips rows whose
  time has not come. No new mover, no new store.
- `delay:` and `deliverAt:` are mutually exclusive; both are refused on
  `emit:` (a liveness hint about now has no meaningful later).
- At-least-once semantics unchanged; a not-before row that comes due delivers
  through the same retry and dead-letter path.

**Open here:** cancellation. An order cancelled on day two should not remind on
day three. The honest options are a declared cancel key (`cancelKey:
steps.header.keys.id` — a later command's `notify.cancel:` withdraws undelivered
entries sharing it) or a delivery-time `when:` re-evaluated against the
document. The first is more mechanism; the second re-reads app tables from the
dispatcher and needs its own authority story. This is the design's hard
question and deserves its own decision before a slice ships.

## Gap 5 (P3) — the approval join: several stamps, one advance

"Both accounting and purchasing must approve before issue" is an AND-join, and
the surface deliberately has no fork/join. The pattern is expressible today:
two self-loop transitions (`from: review, to: review`), each stamping its
column via `stamp:`, and an `advance` transition guarded on both stamps —
with `dispatch:` letting the last approver's one click both stamp and advance.
It works, and it is the same shape [workflow expressiveness](workflow-expressiveness.md)
called out for set conditions: an invariant maintained by hand, invisible to
the lints. Nothing checks that every stamp column has a stamping transition,
that the rework transition clears all of them, or that the advance guard names
the full set; each of those is a silent logic bug when missed.

**Sketch.** No new control flow — a declaration over the existing parts:

```yaml
transitions:
  - id: issue
    from: review
    to: issued
    join: { stamps: [acct_approved, purch_approved] }   # replaces the hand guard
```

- `join:` synthesizes the all-stamped guard and declares the set, so lints can
  prove: every listed stamp has a stamping transition from `review`; every
  transition leaving the join state backward (`rework`) clears the full set
  (`stamp: {…: null}`); no stamping transition writes a column outside the set.
  The `TQL-DECISION-4712` exhaustiveness machinery is the precedent.
- The task inbox can render join progress (2 of 3 stamped) from the declaration
  instead of app SQL.
- Auto-advance when the last stamp lands is deliberately **not** proposed —
  firing a transition from inside another transition is new control flow. The
  last approver advances through a `dispatch:` pair, as today.

Priority is P3 because the pattern already works; what the declaration buys is
the lint coverage and the inbox visibility, not new capability.

## Gap 6 (P3) — shared step fragments

Domains, rules, decisions, scope fragments, calendars and messages are all
shared documents referenced by name — but a step sequence is not. The audit
note, the counter refresh, the "write the interface row" tail that a dozen
commands repeat is copied into each `steps:` block, and the copies drift. There
is deliberately no `include:`/`extends:` in the document model, and that should
stay: a route a reviewer reads must be whole. The `rules/` precedent shows the
alternative — a named artifact with a typed contract, referenced where used.

**Sketch.** `fragments/*.yml`, one more shared-definition kind:

```yaml
# fragments/audit.yml
version: tesseraql/v1
fragments:
  audit-note:
    binds: { entity: string, entityId: string, note: string }
    steps:
      - id: note
        sql: { file: audit-note.sql, params: { entity: binds.entity, … } }
```

```yaml
# a route
steps:
  - id: header
    sql: { file: create.sql, keys: [id] }
  - use: audit-note
    as: audit
    params: { entity: "'requisition'", entityId: steps.header.keys.id, note: params.title }
```

- Expansion happens at manifest load: the fragment's steps become real steps of
  the referencing document (ids namespaced `audit.note`), so the transaction,
  coverage, spans and lints see ordinary steps — no runtime indirection.
- `binds:` is checked against the reference's `params:` exactly as a shared
  rule's contract is; SQL files are colocated with the fragment document.
- One level: a fragment cannot `use:` another (the delegation precedent — one
  hop, never a chain).
- Command-side first (`steps:`); `pipeline:` reuse can follow if jobs turn out
  to repeat sequences the same way.

## Addenda — recorded, not designed

Three smaller absences surfaced by the same survey, noted here so they are not
lost, deliberately left without sketches:

- **Job failure hooks.** `trigger.after:` fires on success only; failure raises
  the alert channel and stops. A declared on-failure notification (or cleanup
  job) has no home today.
- **Consumer dead-letters.** A `consume/**` document declares `idempotencyKey:`
  but no poison-message policy; what happens after the Nth failed apply of one
  message is the transport's business, not a declaration.
- **Parallel chunk partitions.** Already recorded as deferred-until-measured in
  [batch platform](batch-platform.md) (`partitionBy:` with per-partition
  checkpoints); listed here only because a control-flow survey should name it.

## Suggested slice order

Priority and size do not order identically; this order front-loads the P1 value
and keeps every slice independently shippable:

1. **Gap 1, contract only** — `items.fields:`, element binding/coercion,
   indexed field errors; convert the procurement `lines:` input. (The grid form
   derivation is a later view slice.)
2. **Gap 3** — `http.retry:`; small, self-contained, immediately useful to
   every integrating app.
3. **Gap 2** — bulk transitions over `TransitionExecutor` with the per-key
   outcome report; procurement inbox gains the bulk approve.
4. **Gap 4** — outbox `delay:`/`deliverAt:`, after its cancellation question is
   decided.
5. **Gap 5** — `join:` sugar plus its three lints.
6. **Gap 6** — `fragments/` expansion, once a real app exhibits the drift the
   design predicts.

## Open questions

- Gap 1: is `validate.each:` needed in the first slice, or do element-level
  declarations carry it?
- Gap 2: the `keys` ceiling, and whether bulk `emit:` coalesces.
- Gap 3: should the config default apply retry to every outbound call, or is
  retry opt-in per binding with config supplying only the numbers?
- Gap 4: the cancellation model — cancel key, delivery-time predicate, or
  ship-without and record the limitation.
- Gap 5: does `join:` belong on the transition (as sketched) or on the state?
- Gap 6: fragment step ids in spans and coverage — namespaced (`audit.note`) or
  flattened with a collision lint?
