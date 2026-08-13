# The unified source model

Status: **designed 2026-08-12; all twelve slices implemented 2026-08-12/13.**
Pre-1.0 breaking change (rule 10): the replaced spellings are deleted, not aliased; the
CHANGELOG records what changed and why.

One pivot, in decision 19a: the sketch put `mode: query-spool` on the step, beside the `http:`
arm. It is authored **inside the arm** instead. Decision 9 places binding attributes in the arm
that owns them, and `sql:`, `contract:` and `service:` already carry a `mode:` — so the arm is
where a reader looks for one, and a step-level `mode:` would have been a second home for a key
the `sql` arm already has. What 19a actually asks for — that spooling not be SQL-specific — is
satisfied by the binding exposing one `mode()` whichever arm answered: every arm carries a
`mode:`, and the legal values are the mechanism's (`update` on a call is refused at build time).

How a document acquires data is spelled five different ways: a privileged top-level `sql:`, a
`queries:` map, a parallel `http:` map, a `sql:`/`http:` pair inside every `enrich:` entry, and an
extraction that lives at route level on one export recipe and inside `export:` on the other. Each
spelling grew correctly in its own campaign; together they make one concept — *a named acquisition
of rows* — carry five shapes, and every seam between two shapes is either a lint stitching them
together (`TQL-CAMEL-3101`) or a capability hole (`enrich:` cannot reach an `http:` source). This
document unifies the acquisition vocabulary once, and re-derives the things that leaned on the old
shapes — the primary result, enrichment placement, the export extraction, the command pipeline,
and the execution-context names — from the unified model.

## What exists today

The evidence that the current shape is historical rather than principled:

- **`sql:` is a role wearing a mechanism's name.** The top-level `sql:` key accepts
  `file | contract | service` — a route whose `sql:` declares `service:` contains no SQL at all.
  The key denotes "the document's primary statement", not the mechanism.
- **`http` is the only mechanism outside the union.** `contract`, `service`, and `sequence` each
  joined the `sqlBinding` union as an exclusive-key arm; HTTP sources arrived as a separate
  top-level map instead, so at route level the *map* encodes the mechanism (`queries:` vs `http:`)
  while inside `enrich:` the *arm* does (`sql:` | `http:`) — two discrimination styles for the
  same choice.
- **The primary is already optional.** [RouteCompiler.java:1039](../tesseraql-compiler/src/main/java/io/tesseraql/compiler/RouteCompiler.java#L1039)
  guards `if (definition.sql() != null)` with the comment "a route may have no data binding at
  all"; an `http:`-only route compiles and serves today. The primary's specialness is a default
  referent, not a structural requirement.
- **The extraction has two homes.** `query-export` reads the route's `sql:`; `file-export` and the
  batch `export:` step read `export.sql:` — the wrong home on either recipe is `TQL-CAMEL-3101`.
- **Enrichment has two homes.** Routes declare a document-level `enrich:` map with `into:`
  back-references (default: the magic name `sql`); a chunk step embeds `enrich:` and *refuses*
  `into:`. The chunk shape — enrichment attached to the result it transforms — is the coherent
  one; the route shape is the exception.
- **Enrichable-ness depends on the declaring vocabulary.**
  [AppLinter.java:5037](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/lint/AppLinter.java#L5037)
  accepts `into:` = `sql` or a `queries:` name; `http:` sources and `export.queries:` cannot be
  enriched — not by decision, but because they live in the wrong map.
- **The context names diverge per surface.** The primary binds as bare `rows` in route responses
  but as `sql` in export templates and view `source:` defaults; declared inputs bind as `params.*`
  on routes and `job.*` on jobs; step results bind as `steps.<name>` on routes and `step.<id>` on
  jobs. Same concepts, two names each.
- **Ordered-and-named collections have two encodings.** `pipeline:`, workflow `states:` /
  `transitions:`, scope `match:` are arrays whose items carry `id:`; route `steps:` is a map whose
  *authoring order* is semantic — the only place the surface relies on YAML map ordering.
- **`response.json.nest` is `enrich:`'s twin.** Both compose one result set into another with
  `on:` / `as:` / `merge:` / `into:`; the runtime already funnels both through `KeyedReference`.
  Only the surface keeps two join vocabularies.
- **The shared schema has drifted from the loader.** The published schema documents a top-level
  `view:` property (`list | form | detail | dashboard`) that
  [ViewSpec.java:199](../tesseraql-yaml/src/main/java/io/tesseraql/yaml/view/ViewSpec.java#L199)
  never reads — the loader reads `recipe:`, which the schema's `recipe` enum does not allow. Every
  shipped view document is schema-invalid, and the schema documents a key the loader rejects.
- **A job's `query` step counts its rows and discards them**
  ([JobExecutor.java:567](../tesseraql-operations/src/main/java/io/tesseraql/operations/batch/JobExecutor.java#L567)
  drains the `ResultSet` into `affectedRows`), so "fetch a control value, bind it into later
  steps" — the case that makes job-level `sources:` look necessary — is inexpressible for the
  wrong reason: the step exists, its result does not.
- **`query-spool` publishes a reference nothing can consume.** The spool step writes JSONL and
  publishes `step.<id>.spool`, but the only `tempStore.openInput` callers are the route-side
  export paths; no pipeline vocabulary reads a step's spool. An `httpCall` `body:` resolving to
  the ref serializes the *reference record* through a plain `ObjectMapper`
  ([HttpCallClient.java:197](../tesseraql-operations/src/main/java/io/tesseraql/operations/http/HttpCallClient.java#L197)),
  not the rows. The mode promises "later steps" a result they cannot reach — the
  [yaml-surface-consumers.md](yaml-surface-consumers.md) failure class.

## The model

One read-side map of named acquisitions; one write-side pipeline; one envelope.

```yaml
version: tesseraql/v1
id: orders.detail
kind: route
recipe: query-html

sources:                      # every read acquisition, one map
  main:                       # reserved name: the default referent
    sql:                      # the mechanism; its keys are the SQL arm's
      file: order.sql
      mode: query
      params: { id: path.id }
    enrich:                   # enrichment belongs to the result it transforms
      partner:
        on: { partner_code: code }
        sql: { file: partners.sql }
        merge: [partner_name]
  history:
    sql: { file: history.sql }
  rates:
    http:                     # the fourth arm; its keys are the call's
      url: ${tesseraql.connectors.fx.baseUrl}/v1/rates
      credential: fx-api
      select: rates

steps:                        # write side, commands only: ordered, id-carrying
  - id: order
    sql: { file: create-order.sql, mode: update, keys: [id] }
  - id: lines
    sql: { file: copy-order-lines.sql, mode: update }

response:
  html:
    view: orders.detail.view  # view source: defaults to main
```

Every source publishes the same envelope into the execution context: `<name>.rows`,
`<name>.rowCount`, `<name>.first` — plus `<name>.body` / `<name>.status` / `<name>.error` on the
`http` arm. The envelope is inviolable: spooled results cannot be indexed, so `first` is the only
head access, and metadata never needs a parallel namespace. `main` is not a slot; it is the source
name the defaults resolve to.

## Decisions — the acquisition vocabulary

### 1. The binding union gains an `http` arm, and every arm carries its own vocabulary

The arms are `sql | contract | service | http`, plus the write-side `sequence`. Each names a
**mechanism** and nests **that mechanism's own keys**:

```yaml
sources:
  main:
    sql:                                  # the mechanism
      file: order.sql                     # what to run
      mode: query                         # how to run it
      params: { id: path.id }
  rates:
    http:
      url: ${tesseraql.connectors.fx.baseUrl}/v1/rates
      credential: fx-api
      select: rates
```

Two properties fall out of that shape, and both are the point of it. **The arms read as one
list** — `sql`, `contract`, `service`, `http` are all mechanisms, where the old spelling put
`file` (a kind of value) in a list of mechanisms and let it stand for SQL. And **a key's arm is
structural rather than policed**: `mode`/`params` are the SQL arm's, `select`/`onError` are the
HTTP arm's, and neither can be written on the other because there is nowhere to write it. The
flat alternative — arm and modifiers as siblings — could not hold that line, and it forced an
asymmetry besides: `http` has a dozen keys, so it would have nested while its peers did not.

Every arm has the same internal shape: one key naming **what** to acquire — `file` for SQL,
`url` for HTTP, the name for a contract or a service — and the rest saying **how**. So the two
validations that look mechanism-specific are one rule: the acquisition target is checked by the
mechanism that owns it (a SQL file must exist, `TQL-SQL-2103`; an HTTP host must be allow-listed,
`TQL-SEC-4070`).

This is not an invention: `enrich:` entries already discriminate `sql:` | `http:` by exclusive
key. The union promotion makes that the one spelling everywhere.

**No scalar shorthand.** `sql: order.sql` as sugar for `sql: { file: order.sql }` was proposed
and rejected. Half the gallery's bindings (73 of 150) declare a file and nothing else, so the
shorthand would not be an occasional convenience — both spellings would appear constantly, and
every reader, every editor, every lint message and every doc example would carry both. The
scaffolder settles it: its output is where an author starts editing, and a generated
`sql: order.sql` makes adding `params:` a restructuring rather than a new line. Compactness is
already available without a second schema rule, because YAML flow style is formatting rather
than grammar: `main: { sql: { file: order.sql } }`. One idea, one shape — the rule that put
`export.queries:` inside `export:`, applied to itself.

`sequence` stays step-only: allocation is a write-side act, so the read-side arms are exactly
`sql | contract | service | http` (resolves former open question 1).

### 2. One `sources:` map absorbs `queries:` and top-level `http:`

Entries execute in authored order; a later entry's `params:` may reference an earlier entry's
envelope. Non-`main` entries keep the full read-side arm set — `contract` and `service` stay as
available as they are on `queries:` today. The name is `sources` rather than `queries` because
`service` and `http` entries are not queries and never were; the docs already call the HTTP map
"HTTP sources". The top-level `http:` key is deleted, which frees the word (decision 16).

### 3. The top-level `sql:` key is deleted; the primary is the reserved name `main`

The primary survives as *the source named `main`* — a naming convention, not a slot. Every default
becomes one rule, "resolves to the source named `main`": the omitted `response.json.body`, a list
view's omitted `source:`, `pagination:`'s target, an export's extraction. A `sql:` shorthand that
normalizes to `sources.main` was considered and rejected: one idea, one shape — the same rule that
put `export.queries:` inside `export:`. The scaffolder and every doc teach the `sources: main:`
form as canonical.

### 4. `main` is required by capability, not by structure

A document with no `main` is legal (a `page` route, a command, a dashboard composing three equal
sources by name). What requires `main` is using a default that resolves to it:

| Feature | Requirement on `main` |
| --- | --- |
| `response.json` without `body:` | `main` exists |
| `recipe: list` view without `source:` | `main` exists |
| `pagination:` | `main` exists and is the `file` arm |
| export extraction (decision 7) | `export.sources.main` exists, `file` arm |

Because the requirement moved from the slot to the feature, an `http`-arm `main` is now legal — a
pure API-composition page whose defaults resolve to the API rows — and the SQL-only capabilities
refuse it exactly where they are used, with a lint that names the feature. Every row of the table
is an **error**, not a warning: a document whose default referent cannot resolve fails at build,
never at request time.

## Decisions — composition

### 5. Enrichment nests under the source it transforms; `into:` is deleted

`sources.<name>.enrich:` (and `main`'s), matching the chunk step's embedded shape. Entries still
run in authored order, so multi-stage enrichment of one result reads top-to-bottom. The magic name
`sql`, the back-reference, and lint `TQL-YAML-1045` all disappear. Every source becomes enrichable
— including `http` sources and export sources, the two holes the old placement created. The chunk
step aligns fully: `chunk.enrich:` moves to `chunk.reader.enrich:`, since the reader is the source
whose window it transforms.

### 6. `response.json.nest` is retired; `enrich:` gains a `source` arm

`nest` joins two already-fetched results; `enrich` fetches a reference by key. Same join
(`on:`), same composition (`as:` | `merge:`), one runtime (`KeyedReference`). The reference arms
become `sql | http | source`, where `source: <name>` composes a sibling source's rows without a
fetch — which is `nest`, expressed in the one composition vocabulary, placed under the parent it
composes into:

```yaml
sources:
  main:
    sql: orders.sql
    enrich:
      lines:
        on: { id: order_id }
        source: orderLines        # a sibling source, already fetched
        as: lines
  orderLines:
    sql: order-lines.sql
```

### 7. Acquisition and output are separate blocks — `export:` says how to write, never what to read

A document's rows come from its `sources:`. What is done with them — a JSON body, an HTML page,
a spreadsheet, a partner drop — is a different block, and never carries an acquisition of its own.

```yaml
# route
recipe: query-export
sources:
  main:
    sql: { file: print.sql, mode: query }   # what to read
export:
  format: pdf                               # how to write it
  template: print.html

# pipeline step: the step's own arm reads, export: writes
- id: report
  sql: { file: report.sql, mode: query }
  export:
    format: csv
    filename: price-summary-{batch.businessDate}.csv
```

The first draft of this decision put the extraction *inside* `export:` — `export.sources.main` —
on the argument that a batch step has no route level to put it at. Implementing it showed the
argument backwards: a step has an arm of its own, which is exactly the route level's equivalent,
and folding the acquisition into the output block left a `query-export` route whose `sources:`
was empty while its data hid under `export:`. Output blocks do not read; `response:` never did.

What this replaces: `export.sql:` (the file-export and step extraction) and `export.queries:`
(the template's other data) both become ordinary entries of the document's or step's `sources:`.
The `main` source is the rows the codec writes; the rest are what a template composes around
them, addressed by the same envelope (`main.rows`, `header.first`). `TQL-CAMEL-3101` — the lint
that policed which of the two homes a recipe used — retires with the second home. `splitBy`
narrowing is unchanged.

**An extraction is an acquisition, so it takes any arm**: writing API rows into a spreadsheet,
or a service provider's rows into a PDF, is an ordinary thing to want, and a codec never learns
where its rows came from.

### 7b. The same separation, everywhere it was blurred

Auditing the model for the same confusion found three more places, all now on the same rule —
**a block is either an acquisition or an output, never both**:

| Block | Was | Is |
| --- | --- | --- |
| `export:` | carried the extraction (`sql:`) and the template's queries | output only; rows come from `sources:` |
| `import:` | carried the per-row write (`sql:`) | output only — the *write* is the step, the file is the source |
| `push:` | names the transfer it delivers by id | unchanged; it was already delivery-only |
| `chunk:` | `reader:`/`writer:` | unchanged; these are role slots on a *processing* block, which is neither |

`import:` is the mirror of `export:` and moves the same way: the polled file is the acquisition
(the `poll:` trigger names it), the `import:` block says how to parse it, and the per-row
statement is a `steps:` entry like any other write. That also ends a small lie — an `import.sql:`
looked like a query and was a write.

### 7a. Where a binding sits, and which arms it admits

Two questions kept being answered together and are not the same one.

**Shape** — how many bindings does the record need?

| Bindings | Shape | Examples |
| --- | --- | --- |
| Exactly one | The arms sit **directly on the record**; no slot | a pipeline step, `enrich.<name>`, `export.after:` |
| Several, referred to by name | A **map of bindings** | `sources:` |
| Several, in sequence | An **array**, each item carrying `id:` | `steps:`, `pipeline:` |
| Two, by role | **Role-named slots**, each holding a binding | `chunk: { reader:, writer: }` |

Map or array follows from what the collection *is*, and the two questions have different
answers. A route's `sources:` is a namespace: the response and the views name its entries, and
nothing in the gallery has one source read another. A pipeline is a sequence: its steps
reference each other constantly (`steps.headcount.body.total`) and the order is the meaning. So
an acquisition is always named — the name is a map key in a namespace and an `id:` in a
sequence — and that is the whole of the difference between a route source and a step.

A slot is never named for a mechanism, so a slot named `sql:` holding a `sql:` arm cannot arise.

**Arms** — what may the position mean? Not a matter of type but of what the position *is*: an
acquisition admits every arm; a write admits the arms that write. `contract:` writes today
(`mode: update` on an identity contract), so "write" does not mean "SQL only" either — the
narrow arm set belongs to `sequence` (allocation, write-side only) and nothing else.

## Decisions — the write side

### 8. Single-statement commands use `steps:`; the command `sql:` spelling dies with the key

A command's `sql:` was always sugar for a one-step pipeline. With the top-level key deleted, the
write side has one spelling: `steps:`. Read = `sources:`, write = `steps:` — the read/write split
stays at the top level, where lints, governance, and reviewers can see it.

### 9. `steps:` becomes an array of id-carrying steps, converging on the pipeline shape

The surface's rule is: *a namespace is a map; an ordered sequence is an array whose items carry
`id:`* — `pipeline:`, `states:`, `transitions:`, and `match:` already follow it, and route
`steps:` was the one map whose authoring order is semantic. A route step adopts the pipeline
step's shape (`- id: order` plus exactly one arm), restricted to the transactional arms (`sql`
with a file, `sequence`). Key placement follows the pipeline exactly: step control (`id`, `when`)
sits on the step item; binding attributes (`expect`, `keys`, `params`, `timeoutSeconds`) sit
inside the arm. A command is now literally a transactional pipeline, which [jobs.md](jobs.md) has
claimed of tasklets all along. Step results still bind as `steps.<id>.*`.

## Decisions — the execution context

### 10. The envelope is universal and inviolable

Every read source publishes `rows` / `rowCount` / `first` (+ `body` / `status` / `error` on the
`http` arm); every write step publishes `affectedRows` / `value` / `keys.<column>` / `skipped` as
today. Bare `rows` is deleted from the context: the primary result is `main.rows` everywhere — a
response body, a template, a view source, an export template, a test expectation. No implicit
unwrapping: a source reference in a row position does not coerce to its rows; the spelling is
always `main.rows`, because a binding whose meaning depends on where it is written is the
silent-tolerance failure class.

### 11. One vocabulary across routes and jobs

Declared inputs bind as `params.*` on jobs as on routes (`job.*` is deleted; the ambient
`batch.businessDate` / `batch.executionId` stay `batch.*`). Job step results bind as
`steps.<id>.*`, retiring the singular `step.`. After this, an expression means the same thing in a
route, a job, an export template, and a test.

## Decisions — the alignment sweep

### 12. A step is a binding with an `id`, plus its output blocks

The job-side HTTP step is the `http` arm of the binding union wearing a pre-union name;
`HttpCallSpec` and `HttpSourceSpec` merge into the one arm record. `contract` / `service` stay
out of jobs: they are route-plane concepts, and nothing in a batch pipeline has asked for them.

**A step's keys are not one exclusive choice.** The first draft of this decision said the
variants were `sql | http | notify | chunk | export | push`, picked one at a time — which is the
acquisition/output confusion of decision 7, restated at step level. They are three different
axes, and a step declares at least one:

| Axis | Keys | How many |
| --- | --- | --- |
| Acquisition or statement | `sql:`, `http:` | at most one — the binding arm |
| Output | `export:`, `push:`, `notify:` | any, beside the arm |
| Processing | `chunk:` | at most one; carries its own `reader:`/`writer:` |

```yaml
- id: report
  sql: { file: report.sql, mode: query }     # the step is a binding; id names it
  export: { format: csv, filename: … }       # an output block beside it
- id: deliver
  push: { transport: local, path: outbox }   # output only: no acquisition
```

**Why a step's arm is not wrapped, while a route's sources are.** The two look inconsistent and
are not: a route's reads are a *namespace* — several results the response and the views refer to
by name, which is a map — while a step is one unit of work in a *sequence*, whose name is its
`id`. Rule 7a decides the container, and it decides this the same way it decides everywhere
else. Wrapping the arm in a `source:` was considered and rejected for a second reason too: a
step's arm is often a write (`mode: update`), and calling that a source would repeat exactly the
mistake this campaign is removing — a role named after one of the things it can be.

### 13. The two `notify:` shapes are examined and kept — recorded as principled

A route's `notify:` is an id-keyed map (an unordered set of notifications enqueued at commit); a
job's `notify:` step is a single spec whose id is the step id (one notification at a position in
an ordered pipeline). These follow the collection rule of decision 9 — map for namespace, array
item for sequence — so the difference is the model, not drift. Recorded so the next audit does not
re-litigate it.

### 14. The `file` + `params` family spells one way; the bare-string `command:` dies

`validate.file`, scope arms, `rules/` entries, and workflow `assign:` are role-typed SQL
references — different contracts (violation rows, boolean predicate, assignee rows), so they stay
outside the binding union deliberately. But they share one spelling: `{ file:, params: }`.
Workflow transition `command: submit.sql` — the surface's only bare-string statement reference —
becomes `command: { file: submit.sql }`, gaining a `params:` seat it never had.

### 15. One schema per document kind; the `view:` drift is fixed

`tesseraql-v1.schema.json` splits per kind — `tesseraql-route-v1.schema.json`,
`tesseraql-job-v1.schema.json`, `tesseraql-view-v1.schema.json` — sharing `$defs` (the mcp kinds
keep reusing the route schema), following the precedent `domains/` / `rules/` / `decisions/` set.
The editors' file associations move with the split.
View-only keys leave the route schema's top level. The phantom `view:` property is deleted and the
view schema's `recipe:` enum reads `list | form | detail | dashboard` — matching the loader, whose
truth it claims to be. The split also ends the strictness gap: the loose
`additionalProperties: true` islands (`pipeline`, `export`, `import`, `errors`, `outbox`) get real
schemas, so editors validate what the loader enforces. The drift fix itself is a bugfix and may
land before everything else.

### 16. Vocabulary cleanup

- Workflow `http: { basePath: … }` becomes a top-level `basePath:` — with the route-level `http:`
  map gone, keeping the word for an unrelated meaning would squat on it.
- Accepted homonyms, recorded: `after` (an export's follow-up statement; a trigger's job chain)
  and `source` (a view's model key; a decision's backing table) stay — each lives in a
  non-overlapping context, and the replacement names tried during design were all worse.
- The `view` overload shrinks by one with the phantom top-level `view:` property gone; the
  remaining uses (`kind: view`, `response.html.view:`, panel `type: view`) are one concept.

## Decisions — job acquisition

### 17. Jobs get no `sources:` — the pipeline is a job's acquisition surface, structurally

A route's `sources:` map is unordered because its supply target — the response — composes without
order. A job has no response; everything a job reads supplies a *later step*, and later steps are
ordered. So the right home for a job's reads is the ordered collection the job already has, and a
second, unordered acquisition surface would be a parallel spelling of the same thing. Every case
that made job-level `sources:` look necessary (fetch a control value and distribute it; query
rows into a `notify:` payload or an outbound call body) traces to the same root cause: the query
step discards its rows. Decision 18 fixes the root cause.

### 18. Job `query` steps publish the envelope, bounded

A `mode: query` step publishes `steps.<id>.rows` / `.rowCount` / `.first` like any route source,
bounded by `materialize.maxRows` exactly as route results are — the count-only behavior was
memory protection, and the bound keeps that protection while the rows become usable:

```yaml
pipeline:
  - id: period
    sql: { file: current-period.sql, mode: query }
  - id: close
    sql:
      file: close-period.sql
      mode: update
      params:
        periodId: steps.period.first.id
```

Large extracts stay `query-spool` (a reference, never materialized rows). With the merged `http`
step (decision 12) publishing the same envelope, a job step and a route source finally answer to
one contract — and enrichment nests under a job query step's binding the same way it nests under
any other (decision 5), refused on `update` steps, which have no rows.

### 19. `query-spool` is wired: the spool becomes a chunk reader

Today the spool mode promises what nothing delivers (see the audit). The confirmed disposition is
**wire**, and the first consumer is the case the framework currently cannot express at all:
cross-datasource bulk load. The chunk reader gains a `spool` arm, exclusive with `file`, and a
batch **read** step may override `datasource:`:

```yaml
kind: job
datasource: erp_b                              # the load side
pipeline:
  - id: extract
    sql:
      file: extract-orders.sql
      mode: query-spool
      datasource: erp_a                        # the extract side
  - id: load
    chunk:
      reader: { spool: steps.extract.spool }   # exclusive with file:
      writer: { file: upsert-order.sql }
      key: id
      onError: skip
```

- **Doctrine-conformant.** [multi-datasource.md](multi-datasource.md)'s stance holds: no XA, one
  transaction per datasource; the copy is eventual, explicit, restartable. `TQL-YAML-1037`
  forbade per-step connectors inside a *command's* transaction; batch steps each own their
  transaction, so the read-side override splits nothing — the same reasoning that lets a route's
  read-only named query override today.
- **Both sides stream**; the spool is the consistent snapshot a re-run re-reads — a property a
  SQL-reading chunk cannot have.
- **Rerun works**: the spool ref is persisted on the step execution, so `--from-failed-step`
  (extract `SKIPPED`) hands the prior spool to the load step; spool retention must cover the
  rerun window.
- **Type fidelity is a declared caveat**: spooled rows round-trip through JSONL, so date/decimal
  writer binds cast in SQL — the `chunk.after` casting rule, applied to `row.*`, documented and
  linted the same way.
- The second consumer — an `http` step body streaming spooled rows to a partner API — is deferred
  to the open questions until a real integration asks for it.

### 19a. Any acquisition can spool, so a chunk can load what an API returned

Spooling is not a SQL feature; it is what a large result does on its way to a consumer that
reads it once. So `mode: query-spool` belongs to the binding rather than to the `sql` arm: an
`http` acquisition spools the same way, and the same `reader: { spool: … }` loads it.

```yaml
pipeline:
  - id: fetch
    http:
      url: https://directory.example/companies
      select: companies
    mode: query-spool                # the rows land in the spool, not in memory
  - id: load
    chunk:
      reader: { spool: steps.fetch.spool }
      writer: { sql: { file: upsert-company.sql } }
      key: code
```

This closes a gap the campaign would otherwise have left: fetching a large result from an API
and writing it into the database had no expressible shape — a single statement bound to an
`httpCall` result holds every row in memory, and the only alternative was a file round trip
through `push:` and a poll trigger. Routing it through the spool rather than teaching the chunk
reader an `http:` arm keeps paging and retries on the acquisition side, where the mechanism's
own vocabulary already lives, and leaves the reader with one thing to understand: a spool is a
spool, whoever filled it.

The rest follows from decision 19 unchanged — checkpoint restart, the skip policy, the
per-window commit, and the JSONL type-fidelity caveat, which bites harder here because a JSON
number reaching a numeric column is the common case rather than the exception.

## Delivery

Confirmed alongside the decisions:

- **The version const stays `tesseraql/v1`.** Rule 10's practice throughout pre-1.0 — the
  contract cleanup, the vocabulary waves, unicode identifiers — broke within `v1`; `v2` is
  reserved for the 1.0 schema freeze.
- **The design doc lands as its own PR**, followed by the slices sequentially — no stacked
  bases.
- **No release ships mid-campaign**: 0.14.0 waits for slice 10, so the published surface is never
  a mixture; the extension tags once, after the tooling slice.
- **CHANGELOG entries per slice**, breaking-change form: what changed and why, no migration
  steps.

## Lints

Retired: `TQL-YAML-1022` (http placement), `TQL-YAML-1045` (`into:` target), `TQL-CAMEL-3101`
(extraction home). Redefined: `TQL-YAML-1046` (exactly one reference arm — now three arms).
Kept: the gateway lints (`TQL-SEC-4070`/`4072`), `readOnly` on transactional recipes
(`TQL-YAML-1050`), the enrich key-bind check (`TQL-YAML-1048`), the export lint family. New
(codes assigned as slices land, all errors unless noted): *main required by feature* (one code
per row of the decision-4 table), *arm/feature mismatch* (`pagination:` on a non-`file` `main`),
*unknown key on the wrong arm* (decision 1), *`source` arm names an existing sibling* (replacing
1045's useful half), *chunk reader arm exclusivity* (`file` | `spool`, exactly one), *enrich on a
rowless binding* (`update` steps), and a warning in the `TQL-BATCH-4208` mold for a spool-fed
writer whose SQL never casts a non-string key column.

## Blast radius

Model records (`RouteDefinition`, `SqlBinding`+`HttpSourceSpec`/`HttpCallSpec`, `EnrichSpec`,
`ExportSpec`, `PipelineStep`, `ChunkSpec`, `ResponseSpec.NestSpec`), `RouteCompiler`, the
transactional command processor, `JobExecutor` (envelope, spool reader, per-step datasource,
spool-ref persistence on the step execution), `AppLinter`, the direct `.queries()` consumers (`ViewBinding`, `RouteSpecGenerator`,
`RouteGovernance`, `StudioTestService`), OpenAPI generation, coverage, the scaffolder and both
archetypes, all seven gallery apps, the docs (`connectors.md`, `lookups.md`, `file-transfers.md`,
`jobs.md`, `response-shaping.md`, `transactional-writes.md`, `declarative-views.md`, and the
generated references), Studio's source-facing panels and copilot knowledge, and the editor
extension (symbols, completion, go-to-definition for `into:` — which dies — and `sources:` —
which is born). The `YamlSurfaceConsumers` drift guard will fail on every renamed component until
its consumers move, which is the guard doing its job.

## Slices

Each lands green on its own; boundaries may re-cut at implementation.

1. **The view schema drift fix** (decision 15's bugfix half) — independent, first.
2. **Schema-per-kind split** — schema and generator groundwork, loader untouched.
3. **`sources:` + the `http` arm** — the union gains `http`; `queries:`/top-level `http:` fold in;
   gallery migrates.
4. **`main` + envelope** — `sql:` deleted, single-statement commands to `steps:`, bare `rows`
   deleted, `main.rows` everywhere, main-required lints.
5. **`steps:` arrays** — route steps adopt the pipeline shape; `step.` → `steps.`, `job.` →
   `params.` (decision 11 rides along).
6. **Job acquisition** — the query-step envelope (decision 18), the chunk `spool` reader arm and
   batch read-step `datasource:` (decision 19), spool-ref persistence for rerun.
7. **Enrichment placement** — nesting under sources, `into:` deleted, `chunk.reader.enrich:`,
   `nest` retired for the `source` arm.
8. **Acquisition/output separation** — `export:` and `import:` stop carrying statements;
   the extraction is the document's or step's own source, the per-row import write is a step.
9. **Alignment sweep** — `httpCall:` → `http:`, workflow `command:`/`basePath:`, vocabulary
   notes.
10. **Tooling catch-up** — extension, Studio, portal, scaffolder, docs regen; one slice so the
    editors flip to the new surface atomically. Its largest find was the shared `binding`
    definition: it still described the pre-nesting flat record, so the editor and the generated
    reference offered a bare `file:` and a string `contract:` — keys the parser silently ignores.
    A test now compares that definition against the creator's parameters, because the drift was
    invisible to every check the schema had.

## Out of scope

- **Per-source pagination.** Pagination stays route-level and `main`-bound; independent paging of
  several grids is URL composition (region endpoints), not response composition — the multi-grid
  analysis reaffirmed [view-composition.md](view-composition.md)'s rejected-design note.
- **A declarative region panel** (`type: region` embedding a paginated route by id) — a real gap,
  but a view-layer feature with its own design, not part of the source model.
- **The write-statement family.** `import.sql`, `chunk.writer`, and step arms stay as they are;
  merging read and write acquisition into one map would erase the top-level read/write split that
  lints and governance stand on.
- **Full symmetry.** Deleting `main` and forcing every consumer to name its source was considered
  and rejected: the defaults are the 80% case's ergonomics, and a reserved name prices them at one
  learnable rule.

## Open questions

Former questions 1 (`sequence` placement) and 2 (`sources:` on jobs) are resolved — decisions 1
and 17. Remaining:

1. `enrich.source` composition when the sibling is spooled — **deliberately not built**, and the
   reason is worth recording rather than leaving as a to-do.

   Two defects had to be cleared before the question was even askable, and both are fixed: a
   step's `enrich:` was dropped at parse time, and `source:` was read as a root key, which no
   job result is. With those closed, the remaining problem is the memory bound. `fromSibling`
   builds the whole `Map<key, List<row>>` index, and a spooled sibling is spooled precisely
   because it is large — so a naive read gives back what spooling bought. The shape that would
   work is a key-to-offset index over the spool (memory proportional to distinct keys, not
   rows), on top of the re-readable `SpooledRows`; the encodings differ too, since a job spool
   is JSONL and `SpooledRows` is the tagged binary form.

   It is not built because the framework already has a better answer for a large keyed
   reference, and decision 19a is what made it expressible: spool the extract, load it into a
   table with a `chunk:` step, and reference that table with `enrich: { sql: … }`. The database
   does the join, the load is restartable, and no join engine has to grow on top of the spool.
   The attempt fails loudly rather than quietly — `TQL-CAMEL-3114` names the spooled sibling and
   points at that path.
2. The spool's second consumer — an `http` step body streaming spooled rows (NDJSON or a JSON
   array) to a partner API. Deferred until a real integration asks; the consumer contract from
   decision 19 is the foundation it would build on.
