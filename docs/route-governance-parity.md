# Route governance parity

> **Status: slices 1–5 shipped; 6–7 designed.** A contract-deviation sweep (2026-07-25, method
> borrowed from the Apache Camel 4.22 AI audit: learn the contract from the well-trodden
> implementation, then check every sibling for where it deviates) found that the route
> compiler's cross-cutting governance is **restated by hand in each `build*` method** and its
> SQL contract **restated in each executor**. Seven confirmed deviations follow from that one
> structural fact, including a per-tenant write landing in the shared pool and a documented
> row-authority feature that throws at request time. This document defines the two matrices
> that make "a recipe silently missing a governance step" unrepresentable, and records the
> deviations that motivated them.
>
> **Slice 4 (scope on the write path) is also shipped:** the compiled resolver reaches command
> SQL, command steps, validation rules, and workflow `assign:` SQL, so a `/*%scope … */` in an
> `UPDATE` confines the write as [data-scoping.md](data-scoping.md) has always said it does — the
> integration test's write leg returned **500 on both cases** before the fix. `TQL-SEC-4100` is
> now honest advice rather than a nudge into a broken path, and a new `TQL-SCOPE-3014` rejects a
> directive in batch-job SQL at lint time, since a job has no principal to scope against (the
> answer to open question 1). Assign SQL also gained the ambient and audit binds it was missing.
>
> **Slice 1 (tenant-correct writes) is shipped:** the routing rule lives in one shared
> `TenantRouting` the SQL producer, the transactional command processor, and workflow delegation
> all call, so `TQL-TENANT-4031` now covers writes and a tenant's rows land in the tenant's
> database. The integration test's write leg pins it — and confirmed the old behavior returned
> **201** for an unknown tenant whose reads were already refused.

The failure class is the one [config-consumers.md](config-consumers.md) closed for scaffolded
configuration, moved one layer in: there, a key was emitted and never read; here, a governance
step is *documented as universal* and applied to some recipes only. The user declares
`policy.rateLimit`, `tenancy.mode: database-per-tenant`, or `/*%scope … */` and the runtime
silently holds a different posture — on one recipe but not its sibling, with no lint, no error,
and in one case a lint rule actively steering the author into the broken path.

Why it happened is visible in the code shape. `RouteCompiler` has six route-head sites that each
list their governance steps by hand (`:346-354`, `:608-615`, `:669-676`, `:701-705`, `:736-740`,
`:893-901`) and no test asserts which steps a recipe must carry. Every finding below is an
instance of a step omitted from one copy.

## Matrix 1 — the route head

What each recipe applies today. **`—`** marks a confirmed gap against the recipe's siblings.

| Recipe | telemetry | security | i18n | tenancy | concurrency / lane | audit | emit | queries | idempotency |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `command-json` | yes | yes | yes | yes | yes | yes | yes | yes | begin + complete |
| `query-json` | yes | yes | yes | yes | yes | n/a | n/a | yes | begin + complete |
| `query-html` / `page` | yes | yes | yes | yes | yes | n/a | n/a | yes | begin + complete |
| `query-export` | yes | yes | yes | yes | yes | n/a | n/a | yes | n/a |
| `file-import` | yes | yes | yes | yes | yes | yes | n/a | n/a | n/a |
| `file-export` | yes | yes | yes | yes | yes | yes | n/a | n/a | n/a |
| `queue-consume` | yes | yes | yes | yes | yes | yes | **—** | **—** | n/a |
| MCP tool | yes | yes | yes | yes | yes | yes | **—** | yes | begin + complete |
| workflow delegate | yes | yes | yes | yes | yes | yes | n/a | n/a | n/a |
| attachment upload | yes | yes | yes | yes | n/a | n/a | n/a | n/a | n/a |
| attachment list / download | yes | yes | yes | yes | n/a | n/a | n/a | n/a | n/a |

`n/a` marks a step the recipe cannot carry (a read route has nothing to audit or emit); every
`—` is a step its siblings apply and it does not.

## Matrix 2 — the SQL execution contract

`TesseraqlSqlProducer` is the reference: it is the only executor that honors the whole contract.
Every other executor re-implements a subset.

| Executor | dialect variant | query timeout | maxRows | `/*%scope%*/` | ambient `principal.*` | `audit.*` binds | per-tenant datasource | ISO temporals / label folding |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `TesseraqlSqlProducer` (route `sql:`, named queries) | yes | yes (30s) | yes (10k) | yes | yes | n/a | yes | yes |
| `TransactionalCommandProcessor` (command, steps) | yes | yes | yes | yes | yes | yes | yes | **—** |
| `ValidationRules` | yes | yes | n/a | yes | yes | **—** | rides the command | **—** |
| `query-export` URI (hand-built) | **—** | **—** | n/a | yes | yes | n/a | yes | **—** |

| `TransactionalCommandProcessor` (command, steps) | yes | **—** | **—** | yes | yes | yes | yes | **—** |
| `ValidationRules` | yes | **—** | **—** | yes | yes | **—** | rides the command | **—** |
| `query-export` URI (hand-built) | yes | yes | n/a | yes | yes | n/a | yes | **—** |
| `JdbcFileTransferService` (row / query / after SQL) | **—** | **—** | n/a | **—** | partial | **—** | **—** | n/a |
| `JobExecutor` (batch steps) | **—** | **—** | n/a | **—** | yes | **—** | **—** | n/a |
| workflow `assign:` | yes | **—** | **—** | yes | yes | yes | rides the command | n/a |

## The deviations

### Per-tenant writes land in the shared pool — FIXED

`TransactionalCommandProcessor:321-322` resolves its `DataSource` by a compile-time constant
name; the read path (`TesseraqlSqlProducer:440-463`) consults the `TENANT` exchange property and
the resolver. `applyTenancy` has already put the tenant on the exchange before the processor
runs, so the information is present and ignored.

Proven with a probe binding `main` and the resolver to distinct marker datasources: the command
returned `USED:main-shared-pool`. Under `database-per-tenant` / `schema-per-tenant` a
`command-json` write — and `queue-consume`, MCP write tools, `WorkflowDelegateProcessor:61-62`,
and validation SQL riding the command's connection — commits to `main` while every read on the
same request goes to the tenant pool. The `TQL-TENANT-4031` 403 never fires on this path either:
an unknown tenant whose reads are rejected still gets its write committed.

[multi-tenancy.md](multi-tenancy.md) states the violated contract in as many words — the
resolved tenant's pool replaces `main`, "rather than silently falling back to a shared pool".
`AppLinter.lintTenantPredicate` returns early unless the mode is `shared-schema`, so per-tenant
apps get no lint here at all, and `TenantDataSourceRoutingIntegrationTest` covers reads only.

### `/*%scope … */` is inert on every write path — FIXED on the request path

The two-argument `SqlRenderer.render` passes `ScopeResolver.UNSUPPORTED`, which throws
`TQL-SQL-2106`. The only scope-aware call site in `src/main` is `TesseraqlSqlProducer:90`. So
row scoping works on query-json / query-html / page / query-export route SQL and named queries,
and throws at request time in command SQL and steps, validation rules, file import/export SQL,
batch jobs, and workflow sweeps.

Lint blesses all of it: `lintScopeDirectives` scans `sql`, `steps`, and `queries` for every route
and consumer, checking only that the scope name is declared and the alias is an identifier —
never *where* the directive sits. And [data-scoping.md](data-scoping.md) promises the missing
half explicitly ("a `/*%scope ... */` in the `WHERE` of an `UPDATE`/`DELETE` confines the write
to authorized rows — this is how an approval workflow state transition carries its row
authority"), as do [approval-workflow.md](approval-workflow.md) and the `TransitionSpec` javadoc.

Worst of all, `TQL-SEC-4100` steers authors into it: it warns when a write route touches a
scope-governed table with no `/*%scope … */` predicate. Following that warning produces a route
that 500s on every request.

The one mitigating fact, which shapes the fix's priority: this is **fail-closed**. `renderScope`
throws before the statement executes. It is an undelivered feature and a docs-vs-code
contradiction, not a silent authorization bypass.

### Command steps and validation SQL are unbounded — FIXED

No `setQueryTimeout`, and the query-step row loop has no cap, where the route-level path applies
a 30s default and `DEFAULT_MAX_ROWS = 10_000`. Hikari's `connectionTimeout` is pool-acquire wait,
not a statement timeout, and there is no transaction manager to supply one — the command opens
its own JDBC transaction. So `tesseraql.sql.timeoutSeconds` and a per-binding `timeoutSeconds:`
silently do not apply inside a command, and unbounded row materialization happens inside an open
write transaction.

### `query-export` bypasses `executionUri` — FIXED

`RouteCompiler:664-667` hand-builds the `tesseraql-sql:` URI with `datasource`, `mode`, and
`filename` only, skipping the shared `executionUri` builder that supplies `dialect`, `maxRows`,
`onOverflow`, `queryTimeoutSeconds` and honors a binding-level `datasource:`. Consequences, in
increasing order of surprise: a `foo.mysql.sql` variant is never picked up (a null dialect makes
`DialectSqlResolver` return the base file; there is no metadata probing); the timeout is a no-op
at the endpoint default of `0`; a binding-level `sql.datasource:` is ignored; and — beyond the
original finding — `StreamingProfiles.forDialect(null)` yields `autoCommitOff = false`, so on
PostgreSQL the driver ignores `setFetchSize`, no server-side cursor opens, and the whole result
set is buffered. That is exactly what the surrounding comment claims the code prevents.

### The audit trail misses message-driven and agent-driven writes

`applyAudit` is instantiated at one site and neither `buildQueueConsume` nor `buildMcpTool` calls
it. With `tesseraql.audit.routes.enabled: true`, an HTTP route write is audited and the identical
write applied via a queue message or an MCP tool leaves no record.

Sharpest on MCP: `lintTool` mandates a `security.policy` on write tools because an AI agent must
not mutate data without authorization — and those exact agent-driven writes are the ones missing
from the trail. Mechanical note for the fix: `applyAudit` takes a `RouteFile` and tools are
`ToolFile`, so the signature must widen.

The sibling `emit:` gap is smaller than it looks: `lintEmit` restricts emit to `command-json` and
the YAML surface reference documents it as a command key, so the compiler matches the documented
contract. The defect there is the **missing lint** — `lintConsumer` and `lintTool` never call
`lintEmit`, so an unsupported key is silently accepted (and on tools it passes even the recipe
check that would otherwise reject it).

### File routes skip tenancy, concurrency, and lane

`buildFileImport` and `buildFileExport` apply telemetry, audit, security, and i18n; their
`buildQueryExport` sibling additionally applies concurrency, lane, and tenancy. Nothing in
`JdbcFileTransferService` enforces them instead, and no lint restricts `policy:`/`lane:` by
recipe.

Tenancy is the material half: `TenantResolution` sets the property `RequestBinder` reads into the
context as `tenant`, so `tenant.*` never resolves on file routes and the tenant is absent from
the span. Compounding it, `lintTenantPredicate` inspects only `definition.sql()` — null on file
recipes — so the shared-schema `TQL-TENANT-3001` warning never fires for an export query.
Combined with the dropped `export.sql params:` below, a shared-schema file export has no working
tenant filter except the ambient `principal.tenantId`.

rateLimit/lane is latent (no example declares either on a file route) but conceptually the worst
fit: a heavy export is exactly what an operator would rate-limit.

### Smaller instances of the same shape

- **`page` begins idempotency records it never completes.** `applyIdempotencyComplete` is invoked
  at three sites and `buildTemplatePage` is a terminal builder with none, so a begun record stays
  `IN_PROGRESS` and every retry inside the 24h TTL gets `TQL-IDEM-4090` → 409. Narrow in practice:
  it needs `required: true` or a client that volunteers a key, and no shipped app hits it.
- **Workflow `assign:` SQL misses the ambient and audit binds.** `AmbientBinds.seed` is called at
  three sites and assign is not one. The failure is *silent*: a missing segment resolves to null,
  so `/* principal.loginId */` binds NULL. That also contradicts the `AmbientBinds` javadoc
  ("fails loudly as an unbound parameter instead of binding null") — a claim that appears wrong
  generally, not only here. [ambient-params.md](ambient-params.md) scopes shipped coverage to
  query, command-step, named-query, and validation SQL, so this is an unclosed edge rather than a
  broken promise.
- **`export.sql params:` are dropped.** `RequestBinder.resolveSqlParams` fills `SQL_PARAMS` from
  `route.sql().params()`, null for file-export whose binding lives at `export.sql`; only `.file()`
  and `.datasource()` are ever read off that binding. Silent null binds again. An unintended
  workaround exists — a route-level `sql: {params:}` on a file-export route *does* reach the
  export query, because nothing rejects it.
- **Command query-steps return raw JDBC temporals and force-lowercased labels** where the producer
  returns ISO-8601 and dialect-normalized (Oracle-aware, quoted-alias-preserving) labels, so a
  response binding written against one path breaks on the other.

## The guard: make the matrices executable

The audit must not be a one-time cleanup, for the same reason the config-key audit was not: the
next recipe added will re-list its steps by hand and drop one. Both matrices above become code,
and the tests assert the code against the compiler's real output.

1. **One head applier.** `applyCommonGovernance(route, definition)` applies telemetry, security,
   i18n, tenancy, concurrency, and lane in one place; each `build*` method calls it and then adds
   only its recipe-specific steps (audit, emit, queries, idempotency). A recipe that genuinely
   must skip a step declares the skip at the call site, where a reviewer sees it.
2. **A recipe governance matrix in code.** `RecipeGovernance` maps each recipe to the steps it
   must carry — the `yes`/`n/a` cells of Matrix 1, with `n/a` an explicit enum value, not an
   absence. This is the `ScaffoldedConfigKeys` pattern: one source of truth, everything else
   tested against it.
3. **The test compiles and inspects.** For each recipe, a test compiles a minimal route and walks
   the resulting Camel route's processor list, asserting the declared steps are present in order.
   A new recipe with no matrix entry fails the build; a step dropped from a `build*` method fails
   the build naming the recipe and the step.
4. **The SQL contract gets the same treatment.** `SqlExecutionContract` enumerates the Matrix 2
   axes; each executor declares which it honors, and a test asserts the declaration against
   behavior (a probe datasource proves tenant routing; a sleeping statement proves the timeout; a
   scope directive proves the resolver is wired). An executor that declares an axis it does not
   honor fails the build — the honesty probe that keeps the registry from becoming decorative.

## Slices

Ordered so that each lands independently and the guard arrives before the long tail.

1. ~~**Tenant-correct writes.**~~ **Shipped.** The routing rule moved into a shared
   `TenantRouting` that all three executors call, the `TQL-TENANT-4031` 403 now covers the write
   path, and `TenantDataSourceRoutingIntegrationTest` grows a write leg on its own table (seeded
   in every tenant schema *and* the main pool's default schema, so a misrouted write lands
   somewhere observable rather than failing on a missing table).
2. ~~**Bound the command path.**~~ **Shipped.** A `Bounds` record (timeout, maxRows, onOverflow)
   resolved by the compiler from the same config keys the route path reads, applied per step with
   the binding's own `timeoutSeconds:`/`materialize:` winning — the same precedence and the same
   `TQL-LD-0001` overflow error. Workflow `assign:` SQL inherits the app defaults too. Validation
   SQL gets the timeout, which is the half that matters there: a rule that hangs pins the open
   write transaction. Its **rows are deliberately uncapped** — a rule returns violations, so a
   cap would silently drop the reasons a write was refused; if that ever needs bounding it should
   be a distinct error, not a truncation. The integration test proves both: before the fix the
   runaway step returned 200 after the full ten seconds, and the 50,000-row step returned 200.
3. ~~**The head applier and its matrix test.**~~ **Shipped.** `applyCommonGovernance` replaced
   eight hand-written head sequences, and `RecipeGovernanceTest` compiles one route per served
   recipe and reads the processors back off the Camel model — so a future recipe that forgets to
   call the applier fails the build even if its source looks right. Confirmed by dropping
   `applyTenancy` from the applier: the test names `TenantResolution` as missing.
   That single move closed file-route tenancy and rate limiting, the queue-consume and MCP audit
   gap, the workflow-delegate head (it had only security and tenancy), the attachment tenancy
   gap and its i18n asymmetry, and the `page` idempotency pairing.
   One production change the guard required: the admission guards and the idempotency pair
   returned **lambdas**, which have no readable name, so they are named classes now. An
   unnameable step is a step a matrix cannot assert — worth knowing before writing the next one.
   Attachments get their own narrower applier: with no `policy:` or `input:` of their own,
   concurrency, lane and audit have nothing to read, and saying so at the call site is the
   "declare the skip where a reviewer sees it" rule this design asked for.
4. ~~**Scope directives on the write path.**~~ **Shipped for the request path.** The resolver is
   threaded into the command processor (its own SQL and every step), `ValidationRules`, and
   workflow `assign:` SQL. `TQL-SEC-4100` needed no narrowing after all — once scoping works on
   writes, following its advice produces a working route, which was the whole complaint.
   `TQL-SCOPE-3014` (new, error) rejects a directive in batch-job SQL, resolving open question 1
   in favour of failing at build time rather than wiring a resolver with no principal to resolve
   against. The scope directive lint also reaches MCP tools now.
   **Still open:** `JdbcFileTransferService` (file import/export SQL) and `WorkflowSweeper`. Both
   run outside a request; whether they should carry a scope at all is the same question
   `TQL-SCOPE-3014` answers for jobs, and they deserve the same treatment or a resolver of their
   own — decide before extending 3014 to them.
5. ~~**`query-export` through `executionUri`.**~~ **Shipped**, though not literally: the export
   URI's `mode` and `filename` are not a binding's, so the shared part was extracted as
   `executionParams` (dialect, maxRows, onOverflow, timeout) and both callers use it, with
   `bindingDatasource` shared too. That carries the streaming-profile fix: with a dialect the
   producer picks the right profile instead of the default that leaves autocommit on, which is
   what made PostgreSQL ignore the fetch size and buffer the whole result set.
   The regression test is a `.postgres.sql` variant beside a query-export route's base file —
   the marker row only appears if the URI carries `dialect=`.
6. **The SQL contract registry and its honesty probes** (guard step 4), covering the dialect and
   binding gaps in the file-transfer and batch executors.
7. **The long tail:** temporal/label normalization in command query-steps remains.
   **Shipped:** the `lintEmit`/`lintValidation` calls missing from `lintConsumer` and `lintTool`.
   Chasing the last of those turned up more than a missing lint call — the compiler never added
   the topic-emit step to an MCP tool at all, so `emit:` on a tool was accepted, documented, and
   inert. Both halves are fixed together, because either alone leaves the surface lying: wiring
   without the lint accepts a malformed topic, and linting without the wiring reports on
   something that does nothing. `lintEmit` also lost its unused `RouteFile` parameter, which is
   what had made it look like a route-only lint.
   **`export.sql params:` is shipped too:** the binder now reads every binding the recipe can
   carry, with a route-level `sql:` last so an explicit route key still wins. The integration
   test filters an export by a declared bind and was confirmed to return both rows without the
   fix. Assign-SQL's ambient binds turned out to have landed already, with the write-scoping
   slice.

## Lint and tooling

- Lint codes are registry-assigned at implementation. Two new checks: a directive that cannot
  resolve where it sits (scope in an unreachable executor — error), and a governance step declared
  in the matrix but absent from a compiled route (build-time, not lint).
- `TQL-SEC-4100` narrows to the reachable set until slice 4 lands, and widens back afterwards.
- The docs portal's route page gains nothing new; the matrices are maintainer-facing.

## Out of scope

- **Per-recipe governance overrides in YAML.** The matrix is a framework contract, not an app
  knob; an app that wants a step skipped is describing a different recipe.
- **Making every executor use `TesseraqlSqlProducer`.** Tempting, and wrong for the transactional
  path: the command owns its connection and transaction boundary deliberately. The contract is
  shared; the execution stays where it is.
- **Retrofitting the audit trail over historical writes.** The gap is closed forward.

## Open questions

1. Should the scope resolver reach `JobExecutor` at all? A batch job has no principal, so
   `/*%scope … */` there can only mean a job-parameter-driven scope. Leaning: make it a lint error
   in batch SQL rather than wiring a resolver with nothing to resolve — which is the fail-fast half
   of slice 4 doing the work.
2. Does the per-tenant fix belong in the processor or one layer down, in a routing `DataSource`
   bound under the connector name? The wrapper would fix every current and future executor at once
   and make Matrix 2's tenant column unconditional. Leaning wrapper, if the pool-per-tenant
   lifecycle survives it — the processor fix is the safe slice, the wrapper is the right one.
3. Is `n/a` in Matrix 1 always defensible, or should a read route emit an access-log audit row
   when `audit.routes.enabled` is set? Leaning: read auditing is a separate feature with its own
   volume characteristics, and conflating them would make the matrix lie.
