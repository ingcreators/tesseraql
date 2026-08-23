# Contract SQL execution

Implementation design for the way TesseraQL runs **contract SQL** — the 2-way SQL a
*deployment* supplies to satisfy a framework contract, as distinct from the SQL an
*application* writes for its own routes and the SQL the framework writes for its own stores.

Written 2026-08-20, before implementation, measured against main at #930. It exists because
[access governance](access-governance.md)'s slice 4b could not be built: a bundled, portable
SCIM Group contract set is unwritable on the seam SCIM has, and the reason turned out not to be
about SCIM.

**Revised 2026-08-23, measured against main at #997, after slice 1 shipped.** Between the two
measurements the execution substrate this design was first written against was replaced: the
Camel removal ([camel-removal](camel-removal.md)), the HTTP edge and the Vert.x-native shape
campaigns took the route pipeline off `org.apache.camel` entirely. Structural decision 1's
original argument — that the route path could not share an executor because it was a Camel
producer reading an `Exchange` the framework did not own — is gone with the dependency: the
pipeline's `Exchange`, `Step` and `Completion` are the framework's own classes now. The
revision therefore asks the question the first measurement could not: not "how do the three
contract executors converge" but **what the statement execution layer ought to look like when
nothing about the current code is taken as given** — and re-cuts the slices toward that shape.

## The executors, measured again

The first measurement found three executors of deployment-supplied contract SQL. Measuring
the whole tree at #997 finds **twelve classes that execute SQL somebody declared** — rendered
2-way SQL from an application's routes, jobs, validations and enrichments, contract SQL from a
deployment, and a handful of framework-built statements riding the same methods. Each carries
its own hand-rolled prepare → bind → bound → execute → read → classify sequence.

| Executor | Runs | Bound | Classified | Labels | Span | Keys | Tx |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `pipeline.sql.SqlStep` | route query/export | yes | yes | normalize | yes | no | export only |
| `TransactionalCommandProcessor` | command steps | yes | yes | normalize | **no** | yes | yes |
| `batch.SqlStepRunner` | job sql step | yes | **no** (BATCH-5002) | normalize | yes | no | no |
| `batch.ChunkStepRunner` | job chunk | yes | **no** (BATCH-5002) | normalize | yes | no | yes |
| `ValidationRules` | route validate | param, **0-default** | propagates | **toLowerCase** | no | no | caller's |
| `DecisionTables` | framework-generated | param, **0-default** | no (DECISION-4723) | by-name | no | no | caller's |
| `enrich.KeyedReference` | enrich lookups | yes | propagates | **raw + lowercase alias** | no | no | caller's |
| `workflow.TransitionExecutor` | guard/stamps/load | **none** | propagates | partial | no | no | caller's |
| `JdbcFileTransferService` | transfer + import | yes, **bookkeeping bypasses** | no (LD-2810) | normalize | no | no | yes |
| `runtime.WorkflowSweeper` | escalate/reassign | **none** | no (WORKFLOW-3223) | positional | no | no | yes |
| `IdentityService` → `ContractStatement` | identity contracts | yes (slice 1) | classified, **kind discarded** (IAM-1002) | normalize | no | no | no |
| SCIM services → `ContractStatement` | SCIM contracts | yes (slice 1) | unique-violation only | raw, deliberate | no | no | no |

(`StudioDataService` executes catalog-built ad-hoc SQL for the dev-time data browser on its own
`tesseraql.studio.testRunner.queryTimeoutSeconds` bound; it renders no 2-way SQL and is
deliberately outside this design.)

Quality measurements behind the table:

- **≈ 856 lines of JDBC boilerplate** across the twelve, ≈ 726 of them around rendered 2-way
  SQL. Copy-paste detection (CPD, 80-token threshold) finds almost none of it: three token-level
  matches in the whole repository. The duplication is **structural, not lexical** — twelve
  hand-written variants of one sequence, which is worse than copy-paste, because a defect fixed
  in one variant transfers to none of the others. Slice 1's leak-safe statement close is the
  live example: `ContractStatement` closes the statement when binding fails; none of the other
  eleven do.
- **`tesseraql.sql.timeoutSeconds` is read in six places with three different default
  expressions** (RouteCompiler, TesseraqlRuntime ×3, ScimRuntimeExtension, JobCommand ×2).
  There is no single point where the key becomes a value.
- **Result labels follow four different policies**: `Labels.normalize` (route, batch,
  contracts), forced `toLowerCase` (ValidationRules), raw label plus a lowercase alias
  (KeyedReference), raw/positional (sweeper, studio).
- **The write path is the one path with no span.** Route reads, job steps and chunks each open
  `tesseraql.sql.execute`; `TransactionalCommandProcessor` — the statements that change data —
  opens nothing, and neither do contracts, transfers, workflow or validation.

## What is actually wrong

**Generated keys are unreachable from contract SQL, so a portable insert cannot learn its own
row.** `ScimUserService.create` and `ScimGroupService.create` read the assigned id out of the row
`createSql` returns, which means the statement has to be an `insert … returning`; `replace` and
`delete` read a row back to tell "changed" from "absent". None of the three exists in MySQL or
SQL Server. **This is the gap that blocks access-governance slice 4b**, and it is a live
constraint on every deployment: SCIM inbound provisioning is documented as configurable against
any schema, and it is in practice PostgreSQL-and-Oracle only. The framework already solved this
for application SQL — `sql.keys:`, `DialectCapabilities.generatedKeys`, and
`TransactionalCommandProcessor.prepare` picking the right JDBC call per dialect, Oracle's ROWID
trap included. Contract SQL simply cannot ask for any of it.

**Contract SQL was unbounded — fixed by slice 1, and the same defect lives on in two more
executors.** A sign-in's identity contract and a provisioning call's SCIM contract now run under
`tesseraql.sql.timeoutSeconds` like a route's statement always has. But the revision's
measurement finds the identical hole elsewhere: **`TransitionExecutor` runs an application's
workflow guard SQL with no bound at all** — inside the command's open transaction, holding its
connection — and threads a `decisionTimeoutSeconds` it never applies to any statement.
**`WorkflowSweeper` runs an application's escalate and reassign SQL unbounded** on every sweep.
`ValidationRules`' 4-arg overload and `DecisionTables.evaluate(context)` default to `0` =
unbounded, so a caller that forgets to thread the value silently opts out of the bound; and
`JdbcFileTransferService`'s own bookkeeping statements bypass its `applyTimeout`. A bound that
depends on every caller remembering to pass it is not a bound.

**A SCIM create is not atomic.** `ScimGroupService.create` runs the insert, then one statement
per member, then a re-read — each on its own connection, no transaction. A failure part-way
leaves a group holding some of the members the client sent, and the client is told the create
failed. `replace` has the same shape.

**SQL that changes data is invisible.** The route pipeline and the batch runners open a
`tesseraql.sql.execute` span per statement; the transactional command processor, contracts,
transfers and workflow open none. A slow write, a slow sign-in and a slow provisioning call each
show up in a trace as an unexplained gap.

**Failures are classified unevenly, and one path classifies then throws the classification
away.** The route and command paths run `SqlErrors.classify` and map kinds to codes. Batch wraps
everything in TQL-BATCH-5002, transfers in TQL-LD-2810, the sweeper in TQL-WORKFLOW-3223 — a
foreign-key violation from bad input and a dropped table report the same code. Since slice 1,
`ContractStatement` hands `IdentityService` a classified `ContractSqlException`; the service
wraps it in blanket TQL-IAM-1002 and discards the kind.

**One thing measured and found *not* to be wrong**, recorded so nobody re-derives it. `ScimSql`
did not normalize result labels, and `ContractStatement` keeps that when no dialect is passed:
SCIM attribute names are camelCase, so a SCIM contract has to quote its aliases on every dialect
for the mapper to find them, and a quoted alias passes Oracle's folding untouched. Raw-by-choice
is a policy, not an omission — the defect is that four policies exist with none of them declared.

## Structural decision 1 (revised): one statement primitive, and every executor is a caller

The original decision extracted the statement rather than the path because the route path was a
Camel producer and could not be shared. The Camel rationale is gone; what remains true is the
half that was never about Camel: what the executors share is not orchestration but the
**statement** — render, take (or receive) a connection, prepare (asking for declared generated
keys), bind, bound, execute, read under a declared label policy, classify the failure, span it.
That sequence is `io.tesseraql.core.sql.ContractStatement`, shipped by slice 1 for contracts.

The end state this revision commits to: **that primitive is the only place in the tree where
rendered SQL meets JDBC.** Orchestration — pagination, export streaming, scope resolution,
tenant routing, chunk checkpoints and savepoints, transfer bookkeeping — stays in the callers,
above the primitive; the ~726 lines of per-executor variants collapse into calls. The route
pipeline is no longer excluded — `SqlStep` and `TransactionalCommandProcessor` adopt the
primitive's statement layer like everyone else — but it converts **last**, as its own slice with
its own tests, because it has the most behavior stacked on top and a defect introduced there is
a defect in every request.

The primitive generalizes in name as well as in role: `ContractStatement` describes its first
adopter, not its shape. It becomes **`SqlStatement`** (same package, same API) when slice 7
makes it general; pre-1.0 the rename is a clean break recorded here, not bridged.

## Structural decision 2: generated keys are a declared property of the contract, reusing the shipped concept

A contract that needs the store's assigned id declares the column it comes back in, exactly as a
command step declares `sql.keys:`. The primitive prepares with the requested column names on a
dialect whose driver honours them and with `RETURN_GENERATED_KEYS` where only the identity value
comes back — the branch `TransactionalCommandProcessor.prepare` already contains, moved rather
than rewritten.

For SCIM this replaces `returning` in three statements with a plain write plus a declared key:

- `create` runs the insert as an update and takes the id from the generated key when the
  contract declares one, and from the id the service supplied when it does not.
- `replace` runs the update; **zero affected rows is the 404**, which is what the returned row
  was standing in for.
- `delete` runs the delete; zero affected rows is the 404.

**Both halves of the create are needed, because both situations are real.** A deployment whose
table assigns its own id — the shape the shipped example fixture uses — declares the key column
and keeps that. The bundled managed set cannot: `tql_groups.group_id` is a supplied
`varchar(64)` and the managed store already mints `grp-<uuid>` in Java. Naming both makes each
deployment's answer explicit.

**This is breaking, and pre-1.0 it is recorded rather than bridged** (AGENTS.md rule 10): an
existing SCIM contract's three `returning` clauses come out and a key column goes in.

## Structural decision 3 (extended): one key, one bound, resolved once — and no unbounded default anywhere

The bound is `tesseraql.sql.timeoutSeconds` — the same key, the same default of 30, the same
explicit `0` opt-out, for every statement the primitive runs. A second key would be a second
thing to explain and a second thing to forget, and there is no argument for a sign-in's
statement — or a workflow guard's, or a sweeper's — being allowed to run longer than a page's.

The revision extends this with what the six-read measurement demands: **the key is resolved in
one place.** A small value object (`SqlDefaults`: timeout, resolved at boot beside the other
runtime defaults) is constructed once from configuration and threaded to every executor; the six
scattered `getString(...).map(Integer::parseInt).orElse(...)` reads collapse into it. Per-binding
`timeoutSeconds:` overrides keep working exactly as they do.

And the corollary the 0-default overloads violate: **no execution API defaults to unbounded.**
An overload that runs with `0` when the caller forgets to pass a value turns "forgot" into
"opted out". The convenience overloads go; a caller that genuinely wants no bound writes `0`
where everyone can see it.

## Structural decision 4: a contract write that touches several statements runs in one transaction

The primitive gains a form that takes a connection, so a caller that must do several things at
once opens one, runs them, and commits — the shape `TransactionalCommandProcessor` uses for a
command's steps. SCIM's `create` and `replace` become that: the row and its membership land
together or not at all.

**Identity's writes are deliberately not swept into this.** They are single statements by
construction, with the one exception the access-governance campaign argued explicitly — the
review close, which is *not* transactional on purpose, because each revocation is an independent
decision with its own trail row and one that fails must not silently undo the ones that worked.

## Structural decision 5 (revised): one span name, and the surface is an attribute

The original decision minted `tesseraql.sql.contract` beside `tesseraql.sql.execute` so a trace
could tell an application's query from an identity contract's. With one primitive under every
executor that split generalizes the wrong way — twelve surfaces would mean twelve span names,
and a dashboard that wants "all SQL" would enumerate them forever.

The revision: **every statement the primitive runs opens `tesseraql.sql.execute`**, and the
caller's identity rides as attributes — `surface` (`route` | `command` | `job` | `chunk` |
`contract` | `validation` | `workflow` | `transfer` | `enrich` | `decision`) and the statement's
own name (`sqlId` for application SQL, the contract key for contract SQL). "Why is sign-in
slow" is answered by `surface = contract` plus the contract name; "all SQL time in this trace"
is answered by the one name. The three existing span sites already agree on the name and the
`sqlId`/`mode` attributes; they gain `surface` and lose their per-site plumbing when their
executor adopts the primitive. The tracer is looked up as it is today, and its absence is a
no-op.

## Structural decision 6: with the seam fixed, slice 4b is small

Unchanged from the original. The blocked slice becomes: `tql_groups` gains an `external_id`
column across all four dialects; a bundled contract set of nine statements lands under
`io/tesseraql/scim/pack/groups/`, dialect-suffixed only where pagination forces it; and
`tesseraql.scim.groups.enabled` with no `tesseraql.scim.groups.<op>` key configured uses it.
The bundled create's id is minted by the service as `grp-<uuid>` — the managed store's own
shape — because `group_id` is a supplied column with nothing for a database to generate.

The mapping is `id` → `group_id`, `displayName` → `group_name` **and** `group_code`,
`externalId` → `external_id`. **A partly configured contract is refused at boot**, naming the
missing keys: all eight operation keys set means the deployment's own SQL (`count` stays
optional, as it always was), none set means the bundled set, some set means two schemas mixed
one statement at a time.

## Structural decision 7 (new): the label policy is declared, and there are two of them

Four label behaviors exist; two are defensible and the primitive offers exactly those two:
**normalized** (`Labels.normalize` under the caller's dialect — the route/batch/identity
behavior) and **raw** (as the driver reports them — the SCIM behavior, correct for quoted
camelCase aliases). `ValidationRules`' forced `toLowerCase` becomes normalized — for the
lower-case aliases validation rules use in practice the two agree everywhere, including Oracle;
`KeyedReference`'s raw-plus-lowercase-alias double entry becomes normalized, with the change
recorded as behavioral in its slice. A policy nobody chose is a bug that has not happened yet.

## Structural decision 8 (new): the primitive classifies, the caller maps

Every failure leaves the primitive as the classified exception slice 1 introduced — a
`SQLException` carrying the cause's SQLState and vendor code plus the portable `SqlErrorKind`
and the statement's name. What the caller does with the kind is the caller's domain: SCIM turns
`UNIQUE_VIOLATION` into 409 `uniqueness`, a route maps kinds to TQL-SQL-4090..4093 as today,
batch keeps TQL-BATCH-5002 as its outer wrapper **but carries the kind and the statement name
in it** instead of the raw driver message, and `IdentityService` stops discarding the kind it
is already handed. Classification happens once, where the `SQLException` is born; naming
happens where the meaning is known.

## Slices

Seven, in dependency order. Slice 1 is shipped; slice 6 is
[access governance](access-governance.md)'s slice 4b.

1. **`ContractStatement`** — the primitive in `tesseraql-core`; `IdentityService` and the SCIM
   services adopt it; contract SQL is bounded. **Shipped.**
2. **No declared statement runs unbounded** — `TransitionExecutor` applies the bound it is
   already handed to guard, stamp and load statements; `WorkflowSweeper` gains the bound;
   the `ValidationRules`/`DecisionTables` 0-default overloads are removed in favour of explicit
   values; `JdbcFileTransferService`'s bookkeeping statements go through its own
   `applyTimeout`. Behaviour changes exactly once: those statements are now bounded. Small,
   defect-class, independent of everything below.
3. **Generated keys and the end of `returning`** — the declared key column on the primitive,
   the three SCIM statements rewritten as plain writes, the affected-row-count 404s, the
   example fixture and its integration test updated.
4. **Transactional SCIM writes** — `create` and `replace` on one connection via the
   connection-taking form.
5. **One span for every statement** — the primitive opens `tesseraql.sql.execute` with
   `surface`; `TransactionalCommandProcessor` (highest value: the write path), contracts and
   transfers stop being invisible. Existing span sites gain `surface`.
6. **The bundled managed Group set** — `external_id`, the nine statements, the all-or-nothing
   configuration resolution, the boot refusal.
7. **One executor** — `ContractStatement` renames to `SqlStatement` (its exception to
   `SqlStatementException`, `contract()` to `sqlId()`); `SqlDefaults` becomes the single read
   of `tesseraql.sql.timeoutSeconds` (the six reads with three default expressions collapse);
   the primitive gains the general seams — a declared `surface`, a span parent, caller-rendered
   `BoundSql` forms, a positional-values form for framework-built statements, and a
   `ResultSetReader` so a streaming or capping caller owns its read while the primitive owns
   prepare/bind/bound/classify/span; writes go through `execute()`/`getUpdateCount()` so a
   DuckDB maintenance call stops being special. **Adopted here**: `WorkflowSweeper` (all three
   statements). **Recorded rather than converted, each with its reason, each already bounded
   (slice 2) and classified or spanned where it matters**: the batch runners and
   `KeyedReference` read under row caps or into spools — their reads stay their own until a
   capped/streaming read is worth pulling into the primitive, and their failure wrappers now
   carry the classified kind (structural decision 8); `ValidationRules`,
   `TransitionExecutor` and `DecisionTables` are compiler-built objects whose tracer exists
   only per-request — threading a per-call `SqlStatement` through them is `SqlStep`/
   `TransactionalCommandProcessor`'s conversion, deferred with it as its own change with its
   own tests (the original structural decision 1's stance, kept); `JdbcFileTransferService`
   keeps slice 5's per-phase spans — a span per imported row would be noise.
   `StudioDataService` and the fixed-SQL stores stay out by design. **The ledger guard below
   is the teeth**: every `prepareStatement` site in main sources is named in
   `SqlExecutorLedgerTest`, a new hand-rolled executor fails the build, and an adoption
   shrinks the list — `WorkflowSweeper` already left it.

## Guards

- **A dialect-portability test** runs the bundled group set against all four dialect containers
  the repository already gates dialect suites on.
- **`IdentitySchemaParityTest`** already asserts a managed-schema table exists in all four
  dialect files; `external_id` inherits it.
- **A no-`returning` guard** over the bundled contract SQL.
- **`GeneratedReferenceTest`** regenerates on any new configuration key or error message.
- **The JDBC-boilerplate ledger** (`SqlExecutorLedgerTest`, shipped with slice 7): every main
  source file calling `prepareStatement` is named; a new entry is refused by default (route it
  through `SqlStatement`, or add it in review with a reason), and an adoption shrinks the
  list.

## Test plan

- **Unit** — the primitive: the bound applies and `0` opts out; a declared key column reaches
  the right JDBC call per dialect capability; both label policies; classification carries kind,
  SQLState and vendor code; the span opens with `surface` and closes on failure.
- **Per-slice revert-the-fix** — every regression test is proven by reverting its change and
  watching it go red; slice 2's tests each pin one formerly-unbounded site.
- **Store integration** (PostgreSQL container) — a SCIM create whose member add fails leaves no
  group; a replace against a missing id answers 404 from the affected-row count; the bundled
  group set creates, lists, patches and deletes.
- **Dialect suites** — the bundled group set against MySQL, Oracle and SQL Server: "portable"
  is the whole claim.
- **Slice 7 conversions are behaviour-preserving by suite**: each adopter converts under its
  existing tests, and the batch-classification and label changes ship with their own new ones.

## What moves in the docs

`iam-admin.md` gains the bundled group set under Groups. `deployment.md`'s
`tesseraql.sql.timeoutSeconds` note now says it bounds contract SQL (done, slice 1) and — after
slice 2 — every declared statement. `observability.md` (or the deployment telemetry section)
documents the `surface` attribute when slice 5 lands. `access-governance.md`'s slice 4b entry
already points here.

## Deliberately not in this design

- **A bundled managed *User* contract set.** The seam changes make one possible; whether the
  managed `tql_users` shape should be SCIM's default target is a separate question about
  identity ownership.
- **Retry or circuit-breaking around statements.** A bounded statement that fails is an answer;
  trying again belongs to the caller that knows what the call meant.
- **Pooling or connection reuse across contract calls.** The transaction form in structural
  decision 4 is the only place holding a connection longer is justified.
- **`StudioDataService`.** Dev-time, catalog-built ad-hoc SQL on its own deliberate 5-second
  bound; converting it buys nothing a deployment ever runs.
- **The framework's fixed-SQL stores** (`Jdbc*Store`). Their SQL is compiled into the jar and
  reviewed with it; the risks this design closes — somebody else's SQL running unbounded,
  unclassified, unobserved — do not apply. If a store wants the primitive later, it can adopt
  it as a local cleanup, not as a campaign.

## Open questions

1. **Does `ContractStatement` live in `tesseraql-core` or in a new module?** — *settled by
   slice 1*: core. It has no dependency beyond JDBC and what core already owns.
2. **Should the declared key column be one name or a list?** — *gates slice 3.* Recommended: a
   list, matching a command step's `keys:`; the shipped concept is a list and narrowing it here
   would mean widening it later.
3. **Does a failed member add roll back the whole SCIM create, or keep the group?** — *gates
   slice 4.* Recommended: roll back; a partial group is the one outcome nothing downstream
   expects.
4. **Is `group_code` from `displayName` or from `externalId`?** — *settled in structural
   decision 6.* From `displayName`, so an administrator writing an assignment rule types a
   name; a rename at the provisioning client renames the code, recorded here rather than
   discovered.
5. **Does slice 5 also stamp `surface` on the three existing span sites before their executors
   adopt the primitive, or do they gain it only on adoption in slice 7?** — *gates slice 5.*
   Recommended: stamp immediately — it is three one-line changes, and a trace vocabulary that
   is half-deployed teaches dashboards the wrong query.
6. **`DecisionTables` binds positionally over framework-generated SQL — does it adopt the
   primitive's full read path or only bound + classify?** — *gates slice 7.* Recommended: only
   bound + classify via the connection-taking form; its by-declared-output read is not a label
   policy problem, and forcing it through row maps would be shape for shape's sake.
