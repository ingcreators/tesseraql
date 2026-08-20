# Contract SQL execution

Implementation design for the way TesseraQL runs **contract SQL** — the 2-way SQL a
*deployment* supplies to satisfy a framework contract, as distinct from the SQL an
*application* writes for its own routes and the SQL the framework writes for its own stores.

Written 2026-08-20, before implementation, measured against main at #930. It exists because
[access governance](access-governance.md)'s slice 4b could not be built: a bundled, portable
SCIM Group contract set is unwritable on the seam SCIM has, and the reason turned out not to be
about SCIM. Three executors run deployment-supplied 2-way SQL, they render the same dialect of
it, and then they diverge on everything that happens after rendering. Slice 4b hit one of the
divergences. This document measures all of them and decides which are gaps to close.

## The three executors

| | route pipeline | `IdentityService` | `ScimSql` |
| --- | --- | --- | --- |
| Runs | an application's own SQL | identity contract SQL | SCIM contract SQL |
| Rendered by `SqlRenderer` | yes | yes | yes |
| Statement timeout | **yes** (`tesseraql.sql.timeoutSeconds`, default 30) | no | no |
| Generated keys | **yes** (a step's `keys:`) | no | no |
| Transaction across statements | **yes** (`TransactionalCommandProcessor`) | no | no |
| Telemetry span | **yes** (`tesseraql.sql.execute`) | no | no |
| Failure classification | **yes** (`SqlErrors.classify`) | one code for everything | unique-violation only |
| Result label normalization | yes | yes | no — and correctly so, see below |
| Tenant datasource routing | yes | per realm | fixed datasource |

The route pipeline is `TesseraqlSqlProducer` (564 lines) plus `TransactionalCommandProcessor`
(928); `IdentityService` is 283 and `ScimSql` is 72. The asymmetry in the table is not sloppiness
so much as sequence: the route pipeline has been rebuilt repeatedly by campaigns that each added
one of these capabilities to it, and nothing carried the additions sideways, because nothing
named the thing that all three are instances of.

## What is actually wrong

**Generated keys are unreachable from contract SQL, so a portable insert cannot learn its own
row.** `ScimUserService.create` and `ScimGroupService.create` read the assigned id out of the row
`createSql` returns, which means the statement has to be an `insert … returning`; `replace` and
`delete` read a row back to tell "changed" from "absent", which means `update … returning` and
`delete … returning`. None of the three exists in MySQL or SQL Server. **This is the gap that
blocks slice 4b**, and it is a live constraint on every deployment: SCIM inbound provisioning is
documented as configurable against any schema, and it is in practice PostgreSQL-and-Oracle only.
Meanwhile the framework already solved this for application SQL — a command step declares
`sql.keys: [id]`, `DialectCapabilities.generatedKeys` records that PostgreSQL and Oracle honour
requested key columns while MySQL and SQL Server hand back only the identity value, and
`TransactionalCommandProcessor.prepare` picks the right JDBC call per dialect, Oracle's ROWID
trap included. Contract SQL simply cannot ask for any of it.

**Contract SQL is unbounded.** A route's statement is cancelled by the driver after
`tesseraql.sql.timeoutSeconds` (30 by default) precisely so a runaway query cannot hold a pool
connection forever. A sign-in's identity contract and a provisioning call's SCIM contract have no
bound at all. They are the same kind of statement against the same kind of database, and one of
them is on the login path.

**A SCIM create is not atomic.** `ScimGroupService.create` runs the insert, then one statement
per member, then a re-read — each on its own connection from the pool, with no transaction. A
failure part-way leaves a group holding some of the members the client sent, and the client is
told the create failed. `replace` has the same shape with its membership reconcile.

**Contract SQL is invisible.** The route pipeline opens a `tesseraql.sql.execute` span per
statement. Identity and SCIM open none, so a slow sign-in and a slow provisioning call each show
up in a trace as an unexplained gap inside a span that names the HTTP call and nothing else.

**Failures are classified unevenly.** The producer runs `SqlErrors.classify`. `IdentityService`
turns every `SQLException` into TQL-IAM-1002 with the driver's message appended. SCIM asks
`SqlErrors.isUniqueViolation` and answers 500 for everything else — so a foreign-key violation
from a provisioning client sending a member id that does not exist is reported as a server fault.

**One thing measured and found *not* to be wrong**, recorded so nobody re-derives it. `ScimSql`
does not run `Labels.normalize` over its result labels while both other executors do, which looks
like the same kind of omission and is not one. `Labels.normalize` folds all-uppercase labels to
lower case on Oracle and leaves everything else alone; SCIM attribute names are camelCase
(`displayName`, `userName`, `externalId`), so a SCIM contract has to quote its aliases on *every*
dialect for the mapper to find them, and a quoted alias passes normalization untouched. Adding
the call would change nothing for a correct contract and would not rescue an incorrect one.

## Structural decision 1: extract the statement, not the path

The obvious reading of "SCIM has its own SQL executor" is that it should stop having one and use
the framework's. Measuring says no: the framework's path is a **Camel producer** bound to an
endpoint, driven by a compiled route, reading its parameters and its scope context off an
`Exchange`. SCIM's services are plain objects constructed with a `DataSource` and called from a
Java route builder; giving them an Exchange to satisfy an execution API would be inventing a
route that does not exist so that a lookup can pretend to be one.

What the three genuinely share is not the path but the **statement**: render the 2-way SQL, get a
connection, prepare it (asking for generated keys when the caller declared some), bind the
parameters, bound it by a timeout, execute, read the rows with label normalization, and turn a
`SQLException` into something the caller can answer with. That sequence is what gets extracted —
as `io.tesseraql.core.sql.ContractStatement` in `tesseraql-core`, where both `tesseraql-identity`
and `tesseraql-scim` can reach it and where it stays free of Camel.

**The route pipeline is not converted in this design.** It does four things around the statement
that the other two do not — declarative pagination, export streaming, scope resolution and tenant
routing — and folding it in would mean either dragging those into the shared primitive or leaving
the primitive shaped by them. It adopts the primitive when a later slice can do it as its own
change with its own tests; a defect fixed in three places at once is three chances to be wrong.

## Structural decision 2: generated keys are a declared property of the contract, reusing the shipped concept

A contract that needs the store's assigned id declares the column it comes back in, exactly as a
command step declares `sql.keys:`. `ContractStatement` then prepares with the requested column
names on a dialect whose driver honours them and with `RETURN_GENERATED_KEYS` where only the
identity value comes back — the branch `TransactionalCommandProcessor.prepare` already contains,
moved rather than rewritten.

For SCIM this replaces `returning` in three statements with a plain write plus a declared key:

- `create` runs the insert as an update and takes the id from the generated key when the contract
  declares one, and from the id the service supplied when it does not.
- `replace` runs the update; **zero affected rows is the 404**, which is what the returned row was
  standing in for.
- `delete` runs the delete; zero affected rows is the 404.

**Both halves of the create are needed, because both situations are real.** A deployment whose
table assigns its own id — the shape the shipped example fixture uses, `id serial primary key` —
declares the key column and keeps that. The bundled managed set cannot: `tql_groups.group_id` is a
supplied `varchar(64)` and the managed store already mints `grp-<uuid>` in Java, so there is
nothing for a database to generate and the id is known before the insert. Naming both makes each
deployment's answer explicit; picking one would have forced every deployment of the other kind to
change its schema to satisfy a framework preference.

**This is breaking, and pre-1.0 it is recorded rather than bridged** (AGENTS.md rule 10): an
existing SCIM contract's three `returning` clauses come out and a key column goes in.

## Structural decision 3: contract SQL is bounded by the key that already bounds route SQL

`ContractStatement` applies `tesseraql.sql.timeoutSeconds` — the same key, the same default of 30,
the same explicit `0` opt-out. A second key would be a second thing to explain and a second thing
to forget, and there is no argument for a sign-in's statement being allowed to run longer than a
page's.

## Structural decision 4: a contract write that touches several statements runs in one transaction

`ContractStatement` gains a form that takes a connection, so a caller that must do several things
at once opens one, runs them, and commits — the shape `TransactionalCommandProcessor` uses for a
command's steps. SCIM's `create` and `replace` become that: the row and its membership land
together or not at all.

**Identity's writes are deliberately not swept into this.** They are single statements by
construction, with the one exception the access-governance campaign argued explicitly — the
review close, which is *not* transactional on purpose, because each revocation is an independent
decision with its own trail row and one that fails must not silently undo the ones that worked.
That argument does not change here.

## Structural decision 5: contract SQL gets the span it already deserved

One span per contract statement, named `tesseraql.sql.contract`, carrying the contract name and
the dialect. Not `tesseraql.sql.execute`: a trace that cannot tell an application's query from an
identity contract's cannot answer "why is sign-in slow", which is the question the span exists
for. The tracer is looked up the way every other framework store looks one up, and its absence is
a no-op.

## Structural decision 6: with the seam fixed, slice 4b is small

The blocked slice becomes: `tql_groups` gains an `external_id` column across all four dialects,
because SCIM's `externalId` has nowhere to live today; a bundled contract set of nine statements
lands under `io/tesseraql/scim/pack/groups/`, dialect-suffixed only where pagination forces it
(`limit … offset …` for PostgreSQL and MySQL, `offset … rows fetch next … rows only` for Oracle
and SQL Server — the same base-plus-variant shape the operations module already uses); and
`tesseraql.scim.groups.enabled` with no `tesseraql.scim.groups.<op>` key configured uses it.

The mapping is `id` → `group_id`, `displayName` → `group_name` **and** `group_code`, `externalId`
→ `external_id`. The code is what assignment rules and the admin surface join on, so it is the
name an administrator recognises rather than the provisioning client's opaque identifier.

**A partly configured contract is refused at boot**, naming the keys that are missing. All nine
keys set means the deployment's own SQL; none set means the bundled set; some set means two
schemas would be mixed one statement at a time, which is the kind of half-working configuration
that looks like a bug in the framework rather than in the configuration.

## Slices

Five, in dependency order. Slice 5 is [access governance](access-governance.md)'s slice 4b, which
is why it is last.

1. **`ContractStatement`** — the primitive in `tesseraql-core`: render, prepare, bind, bound
   timeout, execute, labels, error classification. `IdentityService` and `ScimSql` adopt it.
   Behaviour changes exactly once here, and it is that contract SQL is now bounded.
2. **Generated keys and the end of `returning`** — the declared key column, the three SCIM
   statements rewritten as plain writes, the affected-row-count 404s, the example fixture and its
   integration test updated.
3. **Transactional SCIM writes** — `create` and `replace` on one connection.
4. **The contract span** — `tesseraql.sql.contract` around every contract statement.
5. **The bundled managed Group set** — `external_id`, the nine statements, the all-or-nothing
   configuration resolution, the boot refusal.

## Guards

- **A dialect-portability test** runs the bundled group set against all four dialect containers
  the repository already gates dialect suites on, because "portable" is the whole claim and the
  only way to know is to run it.
- **`IdentitySchemaParityTest`** (shipped with access-governance slice 8) already asserts that a
  table added to the managed schema exists in all four dialect files; `external_id` inherits it.
- **A no-`returning` guard** over the bundled contract SQL, so the statement that would work on
  the maintainer's PostgreSQL and nowhere else cannot be added back without the build saying so.
- **`GeneratedReferenceTest`** regenerates on any new configuration key or error message.

## Test plan

- **Unit** — `ContractStatement`: the timeout is applied and `0` opts out; a declared key column
  reaches the right JDBC call per dialect capability; a `SQLException` classifies to the code the
  caller expects rather than to a blanket one.
- **Store integration** (PostgreSQL container) — a SCIM create whose member add fails leaves no
  group; a replace against a missing id answers 404 from the affected-row count rather than from
  an absent returned row; the bundled group set creates, lists, patches and deletes.
- **Dialect suites** — the bundled group set against MySQL, Oracle and SQL Server. This is the
  point of the slice: the set exists *because* those three could not be served before.
- **The revert-the-fix rule** — every regression test here is proven by reverting its change and
  watching it go red.

## What moves in the docs

`iam-admin.md` gains the bundled group set under Groups. `deployment.md`'s note on
`tesseraql.sql.timeoutSeconds` gains the sentence that it now bounds contract SQL too.
`access-governance.md`'s slice 4b entry points here for the seam it was waiting on.
`testing.md`'s `scim` coverage entry gains the sentence that framework-owned bundled SQL is not
in an application's denominator, because the framework tests it.

## Deliberately not in this design

- **Converting the route pipeline to `ContractStatement`.** Named in structural decision 1 as a
  later slice, with the reason.
- **A bundled managed *User* contract set.** The seam changes make one possible; whether the
  managed `tql_users` shape should be SCIM's default target is a separate question about identity
  ownership, not about execution.
- **Retry or circuit-breaking around contract SQL.** A bounded statement that fails is an answer;
  deciding to try again belongs to the caller that knows what the call meant.
- **Pooling or connection reuse across contract calls.** Every executor takes a pooled connection
  per statement today, and the transaction form in structural decision 4 is the only place where
  holding one longer is justified.

## Open questions

1. **Does `ContractStatement` live in `tesseraql-core` or in a new module?** — *gates slice 1.*
   Recommended: core. It has no dependency beyond JDBC and what core already owns
   (`SqlRenderer`, `Labels`, `SqlErrors`, `DialectCapabilities`), and a module whose whole content
   is one class is a module to explain.
2. **Should the declared key column be one name or a list?** — *gates slice 2.* Recommended: a
   list, matching a command step's `keys:`, even though SCIM needs exactly one. The primitive is
   shared and the shipped concept is a list; narrowing it here would mean widening it later.
3. **Does a failed member add roll back the whole SCIM create, or keep the group?** — *gates
   slice 3.* Recommended: roll back. The client asked for a group with members and is told the
   request failed, so leaving a partial group is the one outcome nothing downstream expects.
4. **Is `group_code` from `displayName` or from `externalId`?** — *settled in structural
   decision 6.* From `displayName`, so an administrator writing an assignment rule types a name.
   A rename at the provisioning client therefore renames the code, which is the same consequence
   a rename has anywhere in the store and is recorded here rather than discovered.
