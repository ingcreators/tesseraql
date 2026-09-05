# The contract source seam

Implementation design for the seam between **where a route's SQL comes from** and **how it
executes**, and the record of the campaign that built it.

Written 2026-09-05, measured against main at `414a091f1`, and revised as it shipped through #1168 to
#1183. It exists because of a pattern rather than a bug: the contract path had acquired the
statement timeout, tracing and declarative pagination one retrofit at a time, each arriving late,
and a fourth axis — the row bound — had never arrived at all. Four symptoms, one cause.

## The cause, stated once

`RouteCompiler.execution(...)` had a branch:

```java
if (binding.isContract()) {
    return new IamStep("contract", binding.contract(), binding.effectiveMode(), resultKey);
}                                       // ^ four arguments, and it returns HERE
String datasource = bindingDatasource(binding, routeDatasource);
...
return new SqlStep(sqlPath, datasource, binding.effectiveMode(), resultKey,
        effectiveMaxRows(binding), effectiveTimeoutSeconds(binding), ...);   // nine
```

It returned **before** the execution parameters were computed. So a contract binding could not
inherit an execution axis; each one had to be handed to the second step by hand, in its own commit,
after somebody noticed. That is not four oversights. It is one branch, dropping six arguments,
four times.

The fix is not a new parameter. It is that there is no contract arm left for the next axis to be
dropped in.

## Decisions

### 1 — A contract says where a statement comes from; it is not a second thing that runs it

`SqlSource.resolve(Exchange, mode)` answers one question and returns one `Statement`: id, surface,
`DataSource`, dialect, parsed nodes, scope resolver, file-path resolver, exportability. Everything
about *how* a statement runs stays in the one `SqlStep` above it.

`IamStep` is deleted. `FileSqlSource` holds its four moved bodies verbatim; `ContractSqlSource`
holds the resolve half of the step it replaced.

`functions(Exchange)` deliberately did not move, though it sat among the others: `exportQueryNodes`
parses each named export query with it, and `Statement` carries no `ExpressionFunctions`. Four
methods moved, not five.

### 2 — The realm resolves per exchange, never at compile time

A realm baked by the compiler reads the wrong application home. Every mounted application compiles
against its materialized copy under the work directory, and that copy has no `security/` tree and
cannot have one. Seven of the nine shipped contract bindings live in such an application, so a
compile-time realm would answer "contract not found" in exactly the deployment the indirection
exists to serve — with nothing going red.

### 3 — A realm resolves its dialect from its own connector

A realm's connector is `tesseraql.identity.realms.<id>.datasource` and need not be `main`, but the
runtime could only resolve `main`'s dialect. One value feeds three things, so a realm elsewhere ran
under the wrong vendor in three ways at once: it selected the wrong `<contract>.<dialect>.sql`
variant — and all six variant contracts in the pack are upsert-shaped **writes** — folded labels
under the wrong vendor, and appended the wrong vendor's pagination clause.

The compiler had resolved a named connector correctly since Phase 53. Only the runtime was limited.

### 4 — The row bound reaches a route contract read by construction

With the branch deleted, `effectiveMaxRows(binding)` and `effectiveOnOverflow(binding)` reach a
contract binding because both arms build the same step. No new plumbing, no new key.

### 5 — Identity's own reads are bounded too, on their own key

**This is where the campaign departed from its plan**, on a maintainer decision. The plan recorded
the ~68 Java-side reads as gaps and closed only the route surface. They are bounded instead, at
`tesseraql.identity.maxRows`, default 50,000, `-1` the visible opt-out.

Its own key, never the route's: `tesseraql.resultMaterialization.maxRows` bounds what a page
renders and an operator lowers it to protect page memory, while this bounds what the identity store
hands Java on every managed sign-in. Sharing the key would let a page-memory decision lock every
user out.

50,000 rather than the route key's 10,000, because the number is set by the sign-in path:
`find-role-grants-by-user-id` is a role-by-permission cross product unioned over direct and group
grants, and a refusal there escapes `resolvePrincipal`. A number tight enough to matter would lock
out the most heavily granted administrator first — the one account able to raise it again.

A typo refuses at boot rather than falling back, following `threadCount`'s precedent and its
reason: a bound sized from a typo starts the runtime, and only the read that needed it finds out.

### 6 — The refusal must be distinguishable from an absent store, and is, twice over

The sharp edge of this campaign, and the reason two slices exist that look like housekeeping.

`IdentityService.featureUnavailable` answers true for every IAM execution failure, and callers
across the module degrade to an empty answer on it: `SeparationOfDuties.load` returns `List.of()` —
an empty constraint set finds **no conflict** — and `RoleConditions.byRole` returns `Map.of()`,
which its own javadoc calls *an unnarrowed grant*.

So a read too large to materialize, reported as a store that is not there, **widens a permission
set instead of refusing it**. Two independent routes led there, and both are now closed by
construction rather than by care:

- **Checked.** `RowOverflow.onRowPastCap()` declared `throws SQLException`. `SqlStatementException`
  extends `SQLException`, so a refusal written to that signature arrived in a caller's
  `catch (SQLException)` wearing the same clothes as the database refusing the statement. The
  method now declares no `throws`: the unsafe shape is not discouraged, it is unwritable.
- **Degradable.** The refusal is `TQL-LD-0001` — the code the route path already raises, and
  deliberately outside the set `featureUnavailable` matches. A test asserts that against the
  exception actually thrown, not a synthetic one.

There is also no `warn` on the identity key. For route materialization a truncating warn is
reasonable; here a truncated governance read is indistinguishable from a small one, which is the
fail-open itself. Refusal is the only outcome, so there is nothing to configure wrongly.

**Prohibition, recorded because it is the tempting one-line simplification.** Do not widen
`featureUnavailable` to admit `TQL-LD-0001`. A surface that can honestly say "too large" asks
`readTooLarge` instead; every other caller keeps refusing.

### 7 — One row-shaping loop

`SqlStatement` had two row readers that agreed about labels and disagreed about values: the capped
one passed every value through `ResultRows`, the uncapped one behind `query` returned the driver's
object. One store answered a contract read and a route read differently for the same column, on
surfaces where seven shipped templates render an identity temporal directly.

`query`, `queryOne` and the private `readRows` are deleted. The statement's own readers —
`rows(maxRows, onOverflow)`, `rows()`, `firstRow()` — share one `materialize` loop and read the
statement's own dialect and label policy.

That last part is why SCIM could never be bounded before: the capped reader was a **static** factory
and could not see `rawLabels`, which SCIM declares deliberately. The missing cap there was forced by
the reader's shape, not overlooked.

### 8 — A contract binding declares its own bounds; keyset is refused

`materialize:` and `timeoutSeconds:` were parsed, accepted and dropped — `Binding.of` read both from
the `sql` arm alone. The `contract:` arm is now its own record rather than the one it shared with
`service:`, because putting those keys on the shared record would open them on `service:` too, where
a binding compiles to a step with no bounds concept — turning an unknown-key warning into silence.

`pagination: {strategy: keyset}` on a contract binding is refused. The page binder mints offset 0
for every keyset request and the `after` predicate lives in the author's own statement, which a
bundled contract does not have, so a published `next` link would advertise an endless chain of
identical pages. Offset pagination is untouched.

### 9 — The tenant bypass is preserved, and becomes a stated line

`ContractSqlSource` looks its connector up by name from the same registry tenant routing reads, and
deliberately **not** through tenant routing. Routing swaps precisely the `main` connector, and the
identity store is pinned to `main` with a `tenant_id` discriminator — so routing a contract through
it would send `find-user-by-login` to a tenant pool that does not hold the identity store at all.

Permanent, and now a commented line rather than an unlisted exception.

### 10 — The write gate gains a named call site, and does not get stronger

`requireWritable` is extracted from `executeUpdate`, which still calls it; the source calls it on an
update. Exact parity with the old dispatch, and it matters because a sql realm defaults to read-only
while `users/{id}/enable` is a shipped route-path contract update.

It does **not** strengthen the gate. `Binding.effectiveMode()` defaults to `query` and no lint
constrains a binding's mode, so a write-set contract declared under a read mode bypassed the gate
before and still does. That missing lint is filed below, not claimed here.

## The guards

The class is closed by **deleting the branch**, not by a test. After the collapse there is one
`new SqlStep(...)` serving both arms; a future axis cannot be dropped on the contract arm because
there is no contract arm. That is construction, not vigilance.

Three instruments hold what construction cannot:

| Guard | Refuses | Blind to |
| --- | --- | --- |
| `SqlSourceLedgerTest` | a new kind of route SQL growing back as a second Step | the axes each source configures |
| `ContractReadBoundLedgerTest` | a second unbounded identity read; a construction site that forgets the bound | row counts |
| `DeclaredReadParityIntegrationTest` | the two source kinds answering differently over HTTP | anything not on a response |

Each names the others in its javadoc, because each is blind where another sees.

**Why the repository's usual ledger shape was not enough.** `SqlExecutorLedgerTest` greps for
`prepareStatement(`/`createStatement(`/`prepareCall(`. It was green through the timeout retrofit,
the tracing retrofit, the pagination retrofit and the open row bound — because neither path
hand-rolls JDBC. Both are callers of the primitive, and the drift was entirely in *what each caller
asked the primitive for*. A file-path ledger polices where JDBC is touched; this class needed
something that polices what an executor configures.

`DeclaredReadParityIntegrationTest` is the instrument
[route-governance-parity](route-governance-parity.md) specified as `SqlExecutionContract` and never
built — the identifier appeared at exactly one place in the repository, that doc line.

## Recorded divergences

Gaps and permanent exceptions, not approvals.

1. **Error classification forks.** A route contract read classifies into `TQL-SQL-*` — a duplicate
   key answers 409 rather than the 500 it was flattened to. The identity service's own reads still
   flatten to `TQL-IAM-1002`. An improvement on one arm and a divergence on an axis.
2. **The span parent on the Java half.** A route contract statement joins the request's trace;
   `IdentityService.statements(...)` sets no `spanParent`, so its own reads still root their own.
3. **`service:` bindings keep the bean's reads.** `execution(...)` tests `isService()` before
   `isContract()`, so the `iam.*` providers are not reached by a route's declared bounds.
4. **One unbounded identity read.** `resolvePrincipal` reads the enabled assignment rules app-wide,
   with no user predicate, on every managed sign-in. Exempt because a bound there is an
   authentication outage rather than a guard — the real defect is the unfiltered read itself.
5. **Four construction sites cannot wire the bound.** `IdentityBootstrap` (CLI and the Maven goal),
   `AppTestRunner`, `StudioTestService` hold no application config to read the key from.
6. **Tenant routing** — permanent, decision 9.
7. **Contract SQL parses against the process default function set**, declared rather than arrived at.
8. **A sql realm's contract is re-read per request.** The route reloader's fingerprint does not
   digest `security/`, so a cached parse could never be invalidated — and swapping that file is the
   loop a sql realm exists to serve. Managed packs are classpath resources and parse once.

## Filed, not fixed

- **Page the grant-history panel.** It reads the whole append-only trail by design. #1174 makes the
  bound survivable there by answering "this trail is too large to show at once"; the surface wants
  paging.
- **The missing write-mode lint.** Refuse a role-management contract, and the user-write
  complement, under a non-update mode.
- **`FIND_ENABLED_RULE_CONDITIONS` is read app-wide and unfiltered on every sign-in.**
- **`LookupReferences` reads rows with raw labels and raw values** (`:94`, `:128`) on the `lookup:`
  surface — the only unfiled instance of the reader divergence this campaign closed elsewhere.
- **The count wrapper cannot wrap an ordered statement on SQL Server.** `countAll` wraps the
  rendered statement in a derived table and every pack list contract ends in `order by`. Not
  contract-specific — a `sql:` route hits it identically — so it is filed against the SQL path.
- **`TQL-LD-0001` has five private declarations** and no single home.

## What the plan got wrong

Recorded because the plan was measured by 22 agents against this same commit, and was still wrong in
places that mattered.

- **Its justification for the temporal slice was false.** It claimed one admin page rendered a
  store's timestamps two ways; measured, none of that page's four contract arms selects a temporal.
  The real reach is seven shipped templates.
- **"Makes `ResultRows`' javadoc true" was unachievable.** That claim holds for labels and is false
  for values in seven places, four of them deliberate — the batch and enrichment readers keep values
  typed on purpose. The javadoc now states the rule and names the departures instead.
- **The headline count included writes.** "~129 Java-side contract reads" is reads *and* writes; a
  row bound applies to reads only, which is 68.
- **The SCIM rewrite is seven call sites, not eight**, and `SqlStatementTest`'s churn was eleven,
  not ten.
- **Its ledger design would have recorded nothing useful** under the maintainer's answer: it
  specified naming every read call site as a gap, but those reads are bounded, so the ledger would
  have listed sixty-odd approved things and refused nothing.
