# Edit conflict — the declared lock, and the two choices it earns

> **Status: complete.** Four slices shipped 2026-09-03, from the design at #1134/#1135.
>
> **Slice 4 (the scaffolder, and the gallery regenerated) shipped as #1139.**
> `CrudScaffolder` emits `lock: { column: version, type: integer }` and `/*%lock*/ (1=1)`, and
> the five hand-wired halves are gone: the `version:` input on both command routes, its bind,
> the `expect:` block, the edit view's hidden `version` field and the delete fragment's hidden
> input. The demo app is regenerated rather than extended, so the committed diff and its
> checksums are the generator's proof. The lints follow decision 10 — the two heuristics stand
> down on the step that carries the directive, `TQL-SQL-2116` catches a lock the statement
> never advances, `TQL-SQL-2117` catches a directive outside the `WHERE`, and the framework
> stops telling authors to write `expect.rows`. The heuristic was itself broken: `isUpdate`
> tested the raw text, so it had never once fired on a file this framework generates. The docs
> land in [transactional-writes.md](transactional-writes.md),
> [declarative-views.md](declarative-views.md) and [hypermedia-ui.md](hypermedia-ui.md), with
> [scaffolding.md](scaffolding.md) and [two-way-sql.md](two-way-sql.md) corrected alongside.
>
> **Slice 3 (the two faces) shipped as #1138.** The dialog on the shell's third host attribute,
> the no-JS 409 page, the fifth beforeSwap marker — the one that swaps while staying an error —
> and `tql.conflict.*` in both catalogs. `tql.workflow.illegal-transition` rode along, and a
> new guard holds the two bundled catalogs to one key set. Fixed alongside:
> `response.onError` steering resolved the failing route from an exchange property nothing ever
> wrote, so it had never fired.
>
> **Slice 2 (the read side and the form) shipped as #1137.** `v.lock` off the action route, the
> fourth framework-owned hidden field, and `TQL-VIEW-3330` when the rendered row cannot supply
> the value. Review added three refusals: a `datetime` lock at build, a value with no textual
> form at render, and a read policy on the lock column at build. Open question 3 settled as a
> refusal, and `ViewEjector` caught up on all four framework fields.
>
> **Slice 1 (the declaration and the refusal) shipped as #1136.** Route-level `lock:`, the
> `/*%lock*/` directive and its renderer, the two reserved fields and the step that consumes
> and coerces them, `TQL-SQL-4094` and `TQL-FIELD-2011`, the build refusals in their two homes,
> and the `sources:`-inert fix (`TQL-ROUTE-3120`).
>
> **Design, written 2026-09-02, measured against main at #1133.** The `edit-conflict`
> adopt from the catalog ledger ([hc-recipe-alignment.md](hc-recipe-alignment.md)), item 8
> in its recommended order and the one row the ledger sent away for its own design slice.
> The upstream contract is `recipes/edit-conflict/contract.md` in the
> hypermedia-components repository. The kit's own manifest states it as "optimistic
> locking for edit forms — a hidden version rides every save, a stale save answers 409
> with a conflict dialog offering overwrite / reload, and no custom JavaScript is
> involved."
>
> The ledger asked which column, how it bumps, and what the 409 renders. The answers
> below are: a column the route declares, a column the **author's own statement**
> advances, and a page that states the row moved and offers two honest choices. Three
> live defects are fixed here rather than inherited — a no-JS form post that fails
> renders raw JSON, `expect:` under `sources:` validates and does nothing, and the
> framework tells authors to write a key that does not exist.

## What exists, and what is missing

The lock exists. It is not declared, it is not guaranteed, and nobody can see it.

`expect: { rowCount: 1, onMismatch: conflict }` on an update step, paired with a version
predicate the author writes into the SQL, is optimistic locking end to end:
`checkExpectation` compares the affected count, throws `TQL-SQL-4092`, the transaction
rolls back, and the route answers 409 with a localized `details.conflict.hint`. The
scaffolder emits every half of it — the update route's `version:` input, the
`and version = /* version */ 1` predicate, the `version = version + 1` assignment, the
edit view's `- name: version / widget: hidden` — and the shipped acceptance test posts a
stale version and gets its 409. A second operator's edit is not silently lost in a
scaffolded app today.

Four things are missing, and the last of them is the one the ledger named.

**The framework does not know the lock exists.** `version` is a literal in a generator
(`TableSchema.VERSION_COLUMN`, filtered to integer-like columns) and a substring in a
lint. `DocumentRules.lintOptimisticLocking` decides a statement is an UPDATE with
`sql.stripLeading().startsWith("update")` — which every scaffolded file defeats with its
own checksum comment on line 1 — and decides it has a version predicate with
`sql.contains("version")`, which a `conversion_rate` column satisfies. Both findings are
warnings. A hand-authored update route that declares `expect:` and forgets the predicate
passes the build, passes the lint, and is last-write-wins.

**The conflict has no face.** An htmx caller gets the generic field-errors alert with the
stale hint as its body — one sentence, no choices. The user's typed values survive,
because the swap only touches the form's errors div, but so does the stale hidden
`version`: pressing Save again fails again, forever. The only exit is a reload that
discards everything typed.

**A no-JS caller gets worse than nothing.** `wantsHtmlLoginRedirect` returns false for
any non-GET, and the custom-error-page branch is gated on it, so a native form post that
fails falls through to `application/json` and the browser shows a raw error envelope.
Both [hypermedia-ui.md](hypermedia-ui.md) ("the server re-renders the page with the
field-errors fragment inline") and [workflow-surface.md](workflow-surface.md) claim
otherwise. Both are wrong, for every declarative form in the framework, not just this
one.

**And `expect:` under `sources:` is inert.** One shared `binding` schema definition serves
both `steps:` and `sources:`, so `expect:` and `keys:` validate under a read acquisition and
then do nothing at all — `SqlStep` has no row-count logic and captures no generated keys. The
optimistic-locking example in [transactional-writes.md](transactional-writes.md) is written
in exactly that shape, while the acceptance test it was derived from uses `steps:`, so a
reader copying the documented snippet gets a declaration the executor ignores.

`mode: update` under a source is a different thing and stays legal: it is honoured — the step
really does execute the update and publish its affected count — so refusing it would narrow
working behaviour rather than close a hole. Only the two genuinely inert keys are refused.

## Decision 1 — the lock is one route-level key, and it names a column

```yaml
id: items.update
kind: route
recipe: command-json
lock: version              # the whole declaration
input:
  id:   { domain: items.id, required: true }
  name: { domain: items.name, required: true }
steps:
  - id: main
    sql:
      file: update.sql
      params: { id: params.id, name: params.name }
```

`lock:` is a `RouteDefinition` component, beside `input:`, `import:` and `validate:` — not
a key on a step's `sql:` arm beside `expect:`. Three reasons, and the first is decisive.

**A page must be able to read it.** Every route surface `ViewBinding` reads to build a page
is contract-level — `route.input()`, `route.pagination()`, `route.sources()`,
`action.fileImport()`, `action.input()` — and no request-time render path reads `steps:` at
all. Its readers are the compiler, the command processor, the lint rules, route governance
and the build-time reference generator; none of them renders a user-facing page.
`importTarget` — the precedent this borrows — reads `action.fileImport()`, a route-level
`ImportSpec`, and its javadoc gives the reason restating it on the page would be wrong: a
second copy is free to disagree. A mechanism-arm key with a rendering consequence would be
the first of its kind, and it is exactly what AGENTS.md rule 4 calls leaking framework
internals into the surface.

**One route, one lock, one field.** Multi-step commands are ordinary here — the
procurement requisition writes a header step and a lines step. A per-step `lock:` would let
a route declare two locks against one `_lock` field, and the design would owe a refusal.
Route-level makes the ambiguity structurally impossible instead of refused, which is
cheaper and truer.

**It is part of what the route accepts.** A caller must send the lock value. That is
contract, not mechanism, and OpenAPI can say so.

`lock:` names one bare column, validated at compile with the identifier check the workflow
`stamp:` block already uses; the block form of decision 4 adds the column's type beside it. It implies `expect: { rowCount: 1, onMismatch:
conflict }`, and the implied expectation attaches to exactly one step: the one whose
rendered statement carries the `/*%lock*/` directive. Every other step of a multi-step
command is untouched. Declaring `expect:` on that same step is refused, because two
statements of one intent can disagree.

The refusals split by what each home can actually see, and saying which is part of the design
rather than an implementation detail. The command processor sees one step's parse at a time,
so the **step-shaped** refusals live there under `TQL-ROUTE-3102`, at the code the `expect:`
refusals already use. Those are: `expect:` declared beside the lock; a second directive in one
statement; a second carrier step; a lock on a step that is not an update; a directive nested
inside an `/*%if*/` or `/*%for*/`; and a directive on a route that declared no column. The processor
already holds the parse, so that half of the pairing costs it nothing.

The **route-shaped** ones live where the route is compiled, under `TQL-ROUTE-3119`. A column
that is not an identifier; an `input:` field named for the lock column; a declared type that
cannot round-trip through a form field; a `lock:` no statement carries, which is the other half
of the pairing and needs the route's whole statement set; and a `lock:` on anything but an HTTP
command route. That last one is a property of the surface rather than the recipe: `queue-consume` is
the other write recipe and an MCP tool may carry `command-json`, and neither has a request
form to carry the value back, so each builder names its own surface instead of letting the
recipe string imply it. The `sources:` defect fix lives where sources are
compiled, in `RouteCompiler`, and not under a code whose own text says "the route's steps
declaration is invalid".

**The directive is rendered, not verified, and the reason is the parser's shape.**
`Sql2WayParser` produces a flat node list, not a grammar: `SqlNode.Scope` carries a name,
an alias, a boolean and a **source line**, and nothing about which clause it sits in. So
"declared, but not in the WHERE" is not checkable without a second SQL parser — one that
would have to handle every vendor's UPDATE syntax with 2-way directives embedded in it, and
that would cross the line [contract-sql-execution.md](contract-sql-execution.md) drew when
it made the authored statement the only statement. The framework has never needed clause
position for a directive anyway: `/*%scope*/ … as boolean` renders into a SELECT list, so
the same directive is already legal in two clauses.

What the compiler does check is exact, and it is more than it sounds. The directive must be
present, exactly once across the route's steps, and — inherited free from `parseScope` — it
must be followed by a parenthesized dummy predicate, or the parse fails.

It must also be **unconditional**, and that refusal is not a nicety. A `/*%lock*/` inside a
`/*%if*/` passes every other check and then renders away on the branch that omits it: no
predicate, no bind, and the UPDATE meets its own implied row count on the author's remaining
predicates alone. The answer is 200 and the other operator's edit is gone — the exact defect
this surface exists to abolish, reintroduced by a declaration that looks right. The node tree
shows it plainly, so it is refused rather than warned about.

Clause *position* does fall to a lint warning beside decision 10's, in the one layer that
reads the SQL text. The residual
risk is small by construction: `/*%lock*/(1=1)` in a SET list is a syntax error at the
first execution, so the placements the compiler lets through are the ones the database
refuses anyway.

The `input:`-field alternative was rejected on ownership, not on typo safety: both homes
are open-mapped and `@JsonIgnoreProperties(ignoreUnknown = true)`, and `UnknownKeyRules`
reports a mistyped key under either as `TQL-YAML-1043`. The real difference is what an
`input:` field *is*. As an application input the lock is subject to the mass-assignment
guard, to `writable:` and `policy:`, and to a `mask:`-carrying domain that would drop it
from the audit row. It is the framework's value, and it should not be able to lose an
argument with a domain.

## Decision 2 — the framework compares; the author's statement advances

```sql
-- update.sql — still runnable in a plain SQL tool: the lock is a comment plus (1=1).
update items
   set name = /* name */'',
       version = version + 1
 where id = /* id */0
   and /*%lock*/(1=1)

-- rendered, ordinary save:  ... where id = ? and (version = ?)   binds [id, lock]
-- rendered, waived save:    ... where id = ? and (1=1)           binds [id]
```

`/*%lock*/(1=1)` is a control directive in `Sql2WayParser`, dispatched the way `/*%scope*/`
is and rendered by a `renderLock` modelled on `renderScope`, including its save and restore
of the bind scope so the lock's bind cannot leak into an unrelated predicate. The framework
expands one predicate inside the author's own WHERE. It never writes the SET list, never
composes a statement, and never edits a rendered clause — the rule
[transactional-writes.md](transactional-writes.md) already states as "nothing is injected
behind the template's back", and [data-scoping.md](data-scoping.md) states as invariant 1.

The alternative worth naming is the one that looks most like the engine we already have: a
framework-issued conditional bump, `update <table> set <col> = <col> + 1 where <key> = ?
and <col> = ?`, taken as the command's first statement. That is `advanceState` one column
over, it takes the row lock for the rest of the transaction, and it is wrong here for a
reason that is not stylistic. **It cannot be scoped.** `ScopeResolver` is keyed by the
scope id the author wrote into the directive, its unsupported resolver throws rather than
degrading, and there is no table-to-scope map at runtime — the only such association
anywhere is a lint-time regex sweep. So on a scope-governed table the framework's bump
would affect one row while the author's own scoped UPDATE affects zero, the row-count check
would pass, and the command would commit and answer "saved" with nothing written. A new
silent lost update, shipped by the campaign that exists to abolish them. It also needs the
table and the key restated in YAML, where the authored UPDATE already encodes both, and
nothing can cross-check the two because the framework does not parse the author's WHERE.

Comparing but never advancing is also what keeps the lock type-agnostic *in the statement*.
The framework needs equality and nothing else, and never has to know how to advance a value
whose type it did not choose: the author writes `version = version + 1`, or
`updated_at = /* audit.now */'…'`, or nothing at all where the database advances the column
itself. A bigint counter, a ULID and an ETag string all work, and so does any column an API
caller reads and echoes.

What is *not* type-agnostic is the round trip through a browser form, and decision 4 records
where it stops: the value has to render into a hidden field and parse back to the same value.
A `datetime` fails that today — a result row renders a timestamp as an ISO instant and the
input coercion reads a space-separated pattern — and a binary `rowversion` has no textual form
at all. Both are refused rather than half-supported.

## Decision 3 — the lock's read side is a declared column of the rendered row

A locked route is written to by a form the framework rendered, and that form's value comes
from somewhere. `ViewBinding.formModel` already holds the row the form was rendered from —
the view's own `source:` projection — so `v.lock` is a lookup of the action route's lock
column in that row. The lookup is verbatim, like every other prefill: `valueFrom` is a bare
`row.get`, and the camel-to-snake guessing bridge was deleted with the verbatim identifier
policy. `lock:` therefore names the result-set column label exactly, resolved the way the
lookup field resolves its declared columns — an exact key, then a case-insensitive scan for
the dialects that fold labels, and nothing further.

When the action route declares `lock:` and the rendered row carries no such column, the
render **refuses**: `TQL-VIEW-3330`, at render rather than at build, on the `TQL-VIEW-3329`
precedent — `select *` makes a static column check a liar, so the check belongs where the
row is. A column that is present and null is a different thing and refuses too, because a
null lock compares against nothing: an equality predicate on null matches no row, so the
form would be unsaveable rather than unlocked.

This is the decision's whole point. A framework-owned hidden field guarded by
`th:if="${v.lock != null}"` would silently vanish from a form whose read forgot to project
the column, and the save that followed would be unlocked with nothing to say so. That would
be a regression: *today* the scaffolded route declares `version: { required: true }`, an
unprojected column prefills to the empty string, and the submit fails loudly at 400
(`TQL-FIELD-2001`, the binder's required rejection). A design that turns a loud failure
into a quiet one has lost, however elegant the declaration.

Every renderer of a form owes the field, not just the pattern. `ViewEjector.form` emits
`_csrf` and nothing else, so an ejected form has already drifted away from `_idempotency`
and `_return`; it catches up on all four in the same slice, because an ejected edit form
that silently loses the lock is precisely the breaking-change shape
[declarative-views.md](declarative-views.md) records. A scaffolded slot fragment — the
delete form on the edit page — is generated markup, so the generator emits its lock field
too. An L2 override that drops it is the app's own choice, and the design says so rather
than pretending the pattern is the only renderer.

A create form has no row and no lock, and a `lock:` on a route no form view targets is
legal: an API caller sends the value it read. A create form whose action route *does* declare
one renders no `_lock` and every submit answers `TQL-FIELD-2011` — the loud failure this
decision asks for, rather than a build-time refusal the design does not need.

A view that declares a read policy for the lock column is refused at build. A masked value
survives as a present, non-null key, so it would pass every render check and land in a form
whose save can never match — "This record changed", reported for a masking decision.

The ejected form is the one renderer that cannot refuse. It has no view binding and no `v`, so
an unprojected column renders an empty value and the save fails at the write instead — at 400
for a typed lock, 409 for an opaque one. Loud either way, with a different code, which is the
price of ejecting and is recorded rather than papered over.

## Decision 4 — `_lock` and `_overwrite` are framework-owned, consumed by their own step

The form gains a fourth conditional hidden input beside `_csrf`, `_idempotency` and
`_return`. **Two** framework names join `RequestBinder.RESERVED_FIELDS`, not one: `_lock`,
the value the user saw, and `_overwrite`, the waiver of decision 6. Each goes in with its
reason commented in place, the way `ids` and the snapshot pager's `keys`/`page`/`size` did;
`_csrf`, `_idempotency` and `_return` went in bare, and a reader two campaigns later
deserves better.

Reserving only the first would refuse the second. `guardMassAssignment` runs before any
`input:` lookup and rejects every undeclared body key with `TQL-FIELD-2002`, and rejecting
is the framework **default** rather than the scaffolder's opt-in: `unknownFields` unset
means reject. An unreserved `_overwrite` would answer 400 on every overwrite, on all three
legs, before the lock is read at all.

The governing precedent is `ids`, not the `token` refusal. [csv-import.md](csv-import.md)
refused reserving the bare word `token` because the commit leg is a framework-mounted
sub-route with no `input:` block and no binder — the token never meets the guard at all —
and named the opposite case in the same breath: `ids` is posted to an author's own route,
which does bind. A lock value is posted to the author's own route. Reserving the bare word
`version` is still refused, for the reason that survives: it weakens the mass-assignment
guard for every application with a column by that name, and it pins the lock to a spelling
instead of a declaration.

Because a reserved field is skipped by the binder, the value does not arrive in `params` by
itself, and it is not coerced either. It is read by a dedicated step and seeded into the
write's bind map under a reserved name — the shape `AuthStep`'s `csrf` gate operation and
the idempotency processors already use, where a framework field is consumed by the thing
that owns it. The SQL executor never reads a request body.

That step owes one thing the binder used to do for free. Today the lock is an `input:`
field with a domain, so a posted string is coerced to the declared type before it becomes a
bind; a reserved field never is, and a raw `String` bound against an integer column is a
dialect-by-dialect coin flip. So the lock step coerces through the same scalar coercion
declared inputs use, typed from a `domains/` entry named for the lock column when the app
declares one and opaque otherwise. Reading a domain's *type* is not the same as subjecting
the field to `writable:`, `policy:` or `mask:`, which is all decision 1 rejected. A form
post and a JSON number therefore normalize identically.

**And the type has to be declared, which is why `lock:` has a block form.** Every form value
arrives as a string. An untyped lock on an integer column would send a string to an integer
comparison and the driver would refuse it — a 500 on every browser save, while the same
route's JSON leg worked, because its number arrived as a number. So the declaration carries
the type wherever the column is not opaque:

```yaml
lock: version                                 # the bare column: compared exactly as it arrived
lock: { column: version, type: integer }      # typed, so a form's "3" and a JSON 3 are one bind
```

The declared type is also a **bound**, not just a hint: it is refused at build unless the
value's rendered form parses back to the same value. `integer`, `number`, `string` and `date`
do; `datetime` does not, because the two halves disagree about the pattern. A value with no
textual form at all — SQL Server's binary `rowversion` is the case that matters — refuses at
render instead, because its string form is an identity hash that differs on every paint, so
the lock could never match and the record would be permanently unsaveable.

The type is declared rather than looked up by the column's name. Reading it out of a
`domains/` entry named for the column was the first shape, and it is wrong twice over. Every
domain the framework generates is keyed `<table>.<column>`, so a bare `version` would never
match one. And this codebase does not infer a column's declared knowledge from its spelling
anywhere else — a `columns:` entry and a detail `fields:` entry both take an explicit
`domain:` for exactly that reason.

A locked route reached with neither `_lock` nor `_overwrite` answers **400
`TQL-FIELD-2011`** — the next free number beside the missing-framework-field refusals that
already exist in that family, where the status arm answers 400 with no switch change and no
new arm. A missing framework field is a `FIELD` refusal, not a SQL one; the statement never
ran. It is a malformed caller rather than a value a user can fix: every form the framework
renders carries the field, and decision 3 refuses to render one that cannot.

## Decision 5 — the refusal is thrown, and it answers `TQL-SQL-4094`

`checkExpectation` gains one arm: a mismatch on a route that declared `lock:` throws
`TQL-SQL-4094` instead of `TQL-SQL-4092`. `4092` keeps its published meaning for a
hand-authored `expect:` and keeps rendering today's alert. `4093` (serialization failure)
keeps the alert too, because it is retryable rather than stale, and offering a stale-write
dialog for a deadlock would be the wrong affordance for the right status.

The payload keeps `details.conflict` exactly as it is — `step`, `expectedRows`,
`actualRows`, `hint` — so `localizeConflict` still resolves the hint key against the
request locale, and adds a **sibling** `details.lock` carrying the column, the field name
and the waiver's field name. A sibling rather than more entries inside `conflict`, because
the renderer passes the whole conflict map to `MessageCatalog.interpolate` as the hint's
parameters: a key named like a placeholder would silently rewrite the sentence.

**One sentence channel per refusal, and the renderer already chose it.** `conflict.hint`
and `details.message` are mutually exclusive in the fragment renderer, with `conflict`
winning; filling `conflict` therefore *spends* the sentence channel. That is a rule to
state, not a defect to work around, so the next refusal to reach this renderer does not
learn it the way csv-import did. **A refusal that fills `conflict` says its why through
`conflict.hint`; one that does not says it through `details.message`.** The stale write
fills `conflict`, so its sentence is the hint, and a workflow guard's declared refusal text
keeps `details.message`.

The other 409s stay where they are, and each for its own reason rather than one. **`4093`
is retryable, not stale** — a serialization failure is cured by sending the request again,
so a dialog offering overwrite and reload would be the wrong affordance behind the right
status. **`4090` and `4091` are constraint violations**, whose right face is a field error;
they already have a declaration for a human sentence in `errors.constraints:`, and a
default that speaks the vocabulary of constraints rather than of locks. Adding hints to
those three is deliberately out of scope, recorded below. **`TQL-WORKFLOW-3201` is a
defect, not a decision**: its hint key exists in neither message catalog, so today an
illegal transition renders the literal string `tql.workflow.illegal-transition` to a user.
That is a missing catalog entry and it is fixed in slice 3, not absorbed into this
vocabulary — state-as-lock stays the transition's lock, exactly as
[workflow-surface.md](workflow-surface.md) decision 2 recorded.

The constraint that makes all of this work is not aesthetic, and it binds any design of
this surface. **The conflict answer must not be produced by a step that lets the chain
reach `Complete`.** `applyIdempotencyComplete` is appended after the renderer on every
command builder and stores the response as the intent key's replayable answer, with no
success filter. A conflict *rendered* in-pipeline would therefore be stored as the answer
to that intent — and the deliberate overwrite that follows would replay the dialog forever,
or answer 422 for a payload the user cannot clear without reloading. A thrown
`TqlException` is caught outside the step chain, the renderer runs there, and
`PipelineRunner`'s `finally` releases the claim, which is the sole reason the overwrite can
be posted at all on a route that declares `idempotency:`.

## Decision 6 — two choices, and the overwrite is the form's own submit button

```html
<dialog class="hc-dialog" data-tql-conflict-dialog aria-labelledby="tql-conflict-title">
  <div class="hc-dialog__header">
    <h2 class="hc-dialog__title" id="tql-conflict-title">This record changed</h2>
  </div>
  <div class="hc-dialog__body">
    <p>The record may have been changed or deleted by another user; reload it and
       retry the operation.</p>
  </div>
  <div class="hc-dialog__footer">
    <form method="dialog">
      <button class="hc-button" data-variant="ghost" autofocus>Keep editing</button>
    </form>
    <a class="hc-button" data-variant="ghost" href="/items/7">Discard mine and reload</a>
    <button class="hc-button" data-variant="primary" type="submit"
            form="items-edit-form" name="_overwrite" value="1">Save mine anyway</button>
  </div>
</dialog>
```

The overwrite is a **submit button for the page's own form**, associated by the HTML `form`
attribute, carrying its waiver as its own submit value. Four properties fall out of that
one choice, and no other shape has all four.

The user's typed values are never copied, never escaped and never stale, because the
submission is the form's, not the dialog's. The request rides the form's own htmx
attributes, so the kit's dirty guard cleans on success — its `onAfterRequest` requires the
requesting element to be the guarded form itself, so a dialog button issuing its own
request would leave the form `data-dirty` and the follow-on `HX-Redirect` would raise the
leave-page prompt on a save that worked. The waiver is single-shot by construction: a
submit button's value travels only when that button submits, so nothing else on the page
can arm it. And the form's own Save button still sends the stale `_lock` and still
refuses, which is what "single-shot" has to mean.

That last property is the one to hold on to. The tempting shape — swapping a fresh lock
value into the page out of band when the 409 arrives — disarms the lock for *every*
subsequent submission, not for one deliberate act. Dismissing the dialog, pressing Escape,
or clicking the backdrop would all leave the page armed, and the next ordinary Save would
overwrite the other operator's change with no dialog, no consent and no trace.

Two names, two meanings: `_lock` is the value the user saw, `_overwrite` is the act of
waiving it. One name serving both would arrive twice in one submission and turn a scalar
bind into a list — the trap the bulk report recorded when it kept the current page on the
action buttons' submit value and off a hidden input. `_overwrite` is never a hidden input,
for the same reason.

The mechanism is htmx's, not ours, and it is worth checking rather than assuming. htmx
2.0.10 has a handler documented as "handle submit buttons/inputs that have the form
attribute set", which records the clicked button and, for a non-GET, collects the related
form's fields and the submitter's own name and value into one priority payload. Its
serializer excludes `type="submit"` from ordinary field collection, so a submit button's
value travels only when that button submits. But the form's fields and the submitter land
in the *same* payload and are appended, not merged — which is the verified reason one name
cannot serve both meanings, on the htmx leg and on the native one alike.

The form's id comes from the request's `HX-Trigger` header: htmx sends the triggering
element's id, a form with `hx-post` is its own trigger, and `form.html` already gives the
form `th:id="${v.formId}"`. This is the framework's first read of a request `HX-Trigger`,
and it is exact where reconstructing the id from `HX-Target` by stripping `-errors` would
be string surgery. A request with no `HX-Trigger` gets a dialog with Reload only — an
honest short answer rather than a button pointing at nothing.

**Reload goes where a successful save would have gone.** A locked command route already
declares that destination — its own `response.redirect.location`, `/items/{path.id}` on the
scaffolded update — and the renderer interpolates it against the request it is refusing.
The delivery mechanism half existed. `ErrorResponseRenderer` already takes a per-route map
keyed by route id, but the property it resolved that id from was **written nowhere**, so the
`onError` steering it serves had never fired in a running application. Slice 3 falls back to
the id the HTTP edge stamps on every exchange, which fixes that steering as a side effect and
is what makes the reload destination reachable at all. The destination rides its own map: the
column is already in `details.lock`, and decision 8 forbids rendering anything else from the
row.

That source is better than the two headers a reviewer reaches for first, and the reason is
not taste. `Referer` and `HX-Current-URL` are both absolute URLs, so neither can be handed
to the `BasePaths.isLocal` gate `_return` uses — it requires a leading slash — and every
scaffolded app sends `Referrer-Policy: no-referrer`, so the first yields nothing at all in
the framework's own dogfood. A route with no declared redirect renders no Reload link: the
same "omitted rather than guessed" rule, now with a source that actually yields a value.

Three details the surface owes and the markup above carries. The dismissal is
**`autofocus`**, because `showModal()` focuses the first focusable descendant and the
destructive choice must never be the one a reflex Enter commits. The retarget rides with
`HX-Reswap: innerHTML`, because htmx keeps the requesting element's swap style unless it is
overridden, and an app form or an L2 override using `outerHTML` would otherwise replace the
host itself and destroy the mount for every later dialog. And the conflict's swap is the one
allowance that stays an *error*: the other four clear htmx's error flag so a field-errors
alert lands quietly, but doing that here would tell the kit's unsaved-changes guard the save
succeeded, and it would re-baseline a form whose work is still unsent. Nothing about a
refused save is clean, so the page stays dirty — and the Reload link, a real navigation away
from it, gets the browser's own leave-page prompt on top of the modal. That is honest: the
link really does discard typed work, which is why it says so.

"Keep editing" is the dialog's own `<form method="dialog">`, plus Escape and the backdrop.
It is a **dismissal**, not a third server choice, and the design counts two choices for
that reason.

The dialog is retargeted with `HX-Retarget: [data-tql-conflict-host]` into a third
attribute co-located on the shell's one host element. Co-location is a requirement, not a
convenience: the kit's remote-dialog behavior checks that its target matches
`[data-hc-remote-dialog-root]` before calling `showModal()`, and the unique attribute is
what keeps Studio's own in-card hosts, earlier in document order on its explorer and
scaffold pages, from swallowing the dialog. The fragment's marker is deliberately **not** a
prefix of that host attribute, because the swap allowance is a raw substring test over the
response body: a marker the shell's own markup contains would open the gate for any 4xx
body that carries the shell. `tesseraql.js`'s beforeSwap allowance gains
its fifth substring, `data-tql-conflict-dialog`. A new fragment kind states itself; borrowing
`data-hc-field-errors` to get past the gate would be a lie about what the fragment is,
which is what that file's own comment already says.

## Decision 7 — overwrite is a waiver, and the design says what it waives

`_overwrite` expands `/*%lock*/` to `(1=1)`, and nothing else. Every other predicate in the
author's WHERE still stands, and the row-count expectation still holds, so a waived save
that still matches no row refuses again with the identical answer. That covers more than
the obvious case: a row that was deleted rather than changed, a state guard such as
`and status = 'draft'` that no longer holds, and a `/*%scope*/` arm the principal falls
outside of all refuse a waived write. The waiver means "apply mine over whatever the lock
was", not "apply mine to whatever row I like", and the button says the first.

This is a recorded deviation. The upstream contract's overwrite posts the **fresh version**
and refuses again if the row moved between the dialog and the retry. That shape needs the
framework to know the fresh version, which needs a governed read of the contested row it
does not have (Decision 8) — and it keeps a race the waiver does not, because the row can
move again between the read and the post. Re-reading inside the transaction is not portable
either: MySQL InnoDB's default repeatable-read would answer the command's own snapshot,
which is the stale row, while the UPDATE's current read is what returned zero.

What the waiver costs, stated rather than hidden: two operators who both press Overwrite
both succeed, and the second overwrites the first with no second dialog. The property the
design does hold is the one that matters for a lock — the waiver is armed by exactly one
deliberate press and never rides the page, so no ordinary save is ever silently unlocked.
A waiver that could be armed by a swap, and a page that re-arms itself, fail that property
in opposite directions.

## Decision 8 — yours is the caller's own body; theirs is not rendered

The answer states that the row moved and offers the choices. It renders no value from the
contested row, no column-level diff, and no other operator's name.

There is no governed read available at conflict time, and inventing one would be a
data-disclosure decision made by accident. The transaction is rolled back before the
renderer sees the exception. A framework-issued select from a table named in YAML would
carry no `/*%scope*/` arm and could show a row the caller may not read. And even a governed
read would render unmasked: the HTML path collects read policies only from a view's
explicit `domain:` references, builds them with `visible`, `policy` and `unmaskWhen` all
null, and a fragment built in a processor and rendered through `Templates.render` bypasses
even that — as the lookup resolve companion does today.

The house precedent for the identity half is explicit. Workflow task-authority blocking
renders "Assigned to someone else" from a boolean and never puts the assignee in the model.
State the fact; do not invent the payload.

The path to closing this is named rather than worked around: a `read:` key on the block form
of `lock:`, naming a query route whose own `security:` and SQL govern the fetch — the
reference-lookup companion shape, where the referenced route's security governs every leg.
That is also what the upstream `datagrid-edit-conflict` recipe's "theirs in the cells" half
would need. It waits for a use case, not for a slice.

## Decision 9 — the no-JS 409 is a page, and it is the first HTML answer a failing POST has had

```html
<!-- 409 text/html, native form post -->
<div class="hc-alert" data-variant="warning" role="alert">
  <p class="hc-alert__title">This record changed</p>
  <p class="hc-alert__body">The record may have been changed or deleted by another
     user; reload it and retry the operation.</p>
</div>
<form method="post" action="/items/7/update">
  <input type="hidden" name="_csrf" value="…">
  <input type="hidden" name="_idempotency" value="…">
  <input type="hidden" name="name" value="Second item">
  <input type="hidden" name="note" value="…">
  <button class="hc-button" data-variant="primary" type="submit"
          name="_overwrite" value="1">Save mine anyway</button>
</form>
<a class="hc-button" data-variant="ghost" href="/items/7">Discard mine and reload</a>
```

A narrow branch answers `TQL-SQL-4094` with `Accept: text/html` and no `HX-Request`: 409,
a full page through the shell, with the caller's submitted values echoed as hidden inputs.
Echoing a request body is a first for this framework, and it is safe for the one reason
that matters — the disclosure is to the person who typed it. The `_idempotency` value the
caller sent rides unchanged, because the overwrite is the same intent as the save it
replaces and the claim was released when the conflict was thrown. Reload is the same link
decision 6 derives: the route's own declared redirect destination, interpolated against
this request, and omitted when the route declares none.

The page is rendered through the shell, and its **title is the announcement**. On a native
navigation nothing changes after load, so `role="alert"` announces nothing — an assertive
region reports content changes, not markup that was already there. A user arriving without
JavaScript lands at the top of a fresh document, so the conflict has to be in the title the
browser reads out, not only in an alert a third of the way down the page.

**The no-JS branch offers two choices, and Back is not the third.** Back restores a form
whose lock value is stale, so the next save refuses again, forever. Calling it "keep
editing" would be a lie about a page the user cannot leave except by discarding.

`wantsHtmlLoginRedirect` is left exactly as it is. It is the login-bounce predicate, it is
reused as the custom-error-page gate, and widening it to non-GET changes the answer of
every 4xx on every no-JS form at once. That hole is real and worth its own slice; a
code-specific branch is the right blast radius for this one. The markup follows what the
framework already emits rather than inventing kit parts: `hc-alert__title` is a `<p>` at
every emitting site, the alert ships no footer, and the form and the reload link therefore
sit beside the alert rather than inside it. The dialog's own title is an `<h2>`, matching
`SessionExpiredDialog` and the kit's `hc-dialog__title` part.

## Decision 10 — the generator guesses, the author states, the compiler checks

`TableSchema.VERSION_COLUMN` and its integer filter stay, demoted from a hidden contract to
what they always were: how the scaffolder guesses which column to declare. The generator
stays integer-only because it must also write the advance; a hand-authored route may lock
on any equality-comparable column, because the framework only compares.

Its output changes. The update and delete routes emit
`lock: { column: version, type: integer }` and `/*%lock*/ (1=1)`,
and stop emitting the `version:` input, the `version: params.version` bind, the `expect:`
block, the edit view's `- name: version / widget: hidden` entry and the delete fragment's
hand-written hidden input. The delete leg is covered by the same declaration because it is
a route with its own `lock:`, rendering its own `_lock` — not because one page-level field
serves two forms.

The lints follow, but they are not retired. `TQL-SQL-2104` and `TQL-SQL-2105` keep their
heuristic for undeclared routes; on a declared one the pairing question is answered by the
compiler, so the two warnings give way to a third the compiler cannot answer. **A lock the
statement never advances is a lock that never fires**: `lock: version` with a SET list that
never touches `version` compiles, renders `and (version = ?)`, matches every time, and is
silently last-write-wins — the exact failure this design exists to abolish, reintroduced by
a declaration that looks correct. Only the lint sees the SET list, because it reads the SQL
text and `TransactionalCommandProcessor` sees a parse with no clause positions, so the lint
is where that warning belongs. The heuristic itself is fixed while it is open: `isUpdate`
learns to skip leading comments, which every scaffolded file's checksum line defeats today,
and the predicate check uses the declared column name where there is one. A second warning
joins it for the placement decision 1 leaves to a lint: a `/*%lock*/` that is not in the
statement's WHERE. And the framework
stops telling authors to write `expect.rows`: the key is `rowCount`, and the lint message,
the boot refusal and the scaffolded SQL comment all say `rows` today.

## The contract, clause by clause

| Upstream clause | Where it lands |
| --- | --- |
| A hidden `version` rides every save | `_lock`, framework-owned, from the rendered row (decisions 3, 4) |
| The version is the record's, not the form's | `lock:` names a column; the read must project it or the render refuses (decision 3) |
| A stale save answers 409 | `TQL-SQL-4094`, thrown so the idempotency claim is released (decision 5) |
| The 409 opens a conflict dialog | `ConflictDialog`, retargeted to the shell's host, fifth beforeSwap marker (decision 6) |
| Overwrite with the fresh version | **Deviation**: a waiver, not a fresh version — no governed read exists (decision 7) |
| Reload | A link to the route's own declared redirect destination (decisions 6, 9) |
| Keep editing | The dialog's own dismissal, `autofocus`; a dismissal, not a choice (decision 6) |
| Theirs/yours diff | **Deviation**: not rendered; the closing path is `lock.read:` (decision 8) |
| The success answer re-arms the lock | The redirect re-renders the form, and decision 3 reads `v.lock` off the fresh row — no out-of-band version input, deliberately (decisions 3, 6) |
| The no-JS branch is a full 409 page | A narrow renderer branch, the title carrying the announcement (decision 9) |
| No custom JavaScript | The dialog is server-rendered; the overwrite is a submit button (decision 6) |

## Slices

1. **The declaration and the refusal.** Route-level `lock:`, the `/*%lock*/` directive and
   its renderer, the `_lock` and `_overwrite` reserved fields and the step that consumes
   and coerces them, `TQL-SQL-4094` and `TQL-FIELD-2011` with their status assertions in
   the same commit, the build refusals in their two homes, the `sources:`-inert fix, and
   the JSON envelope's sibling `details.lock`. Proven by a hand-authored `lock:` route
   written inline by a runtime integration test, the way the data-scoping test writes its
   own `/*%scope*/` fixture app — the scaffolded CRUD test proves the *old* path and is
   untouched until slice 4, so it is a regression anchor rather than this slice's proof.
2. **The read side and the form.** `v.lock` off the action route, the fourth hidden input in
   `tql/view/form.html`, `TQL-VIEW-3330` when the rendered row lacks the column, and
   `ViewEjector.form` catching up on all four framework fields.
3. **The two faces.** `ConflictDialog` beside `SessionExpiredDialog`, the retarget and the
   third host attribute on the shell, the fifth beforeSwap substring, the no-JS 409 page,
   the `edit-conflict` recipe pinned in the manifest guard, and `tql.conflict.*` keys in
   both catalogs. `tql.workflow.illegal-transition` rides along: it is raised by the
   transition executor and exists in neither catalog, so the only other refusal that
   reaches the hint localizer renders a raw message key to a user today.
4. **The scaffolder, and the gallery regenerated.** `CrudScaffolder` emits
   `lock: { column: version, type: integer }` — the typed form, because slice 2 made the
   declared type a build-enforced bound and an untyped lock would compare the string a form
   posted — and `/*%lock*/ (1=1)`, and drops the five hand-wired halves. The gallery's one edit form
   is not new: `examples/scaffold-demo-app`'s `items.edit` has shipped the whole hand-wired
   lock since Phase 18, and it is this design's worked example throughout. So the dogfood
   is a **regeneration**, not an addition — the demo app is held byte-for-byte by the
   scaffold dogfood test, which carries its own regenerate flag, and the committed diff
   over the edit view, both command routes, both SQL files, the delete slot fragment and
   their checksums is what proves the generator. The docs land in
   [transactional-writes.md](transactional-writes.md),
   [declarative-views.md](declarative-views.md) and [hypermedia-ui.md](hypermedia-ui.md).

Nothing breaks between slices, and that is a property of the ordering rather than luck.
`lock:` is optional and nothing declares it until slice 4, so slices 1 to 3 add a
declaration no shipped app uses: the scaffolded apps keep their hand-wired `version` input,
their `expect:` block and today's `TQL-SQL-4092` throughout. Slice 4 is the flip, and it is
the first commit where a route declares `lock:` and a form renders `_lock` — by which point
both halves exist.

## Test plan

The house's three layers, in the shape [list-surface.md](list-surface.md) used. **Template
render tests** for the derived field: a declared lock renders the hidden input, an
undeclared route renders nothing, and an unprojected column refuses with `TQL-VIEW-3330`.
**Renderer unit tests** for the three answers off one exception — the JSON envelope with its
sibling `details.lock`, the retargeted dialog with its marker, and the no-JS page with the
echoed body — plus the status assertions the mapping ledger test requires for both new
codes. **A Testcontainers integration test** for the story only a real database can tell:
two browser sessions load the same edit form, the first saves, the second is refused with
the dialog, the overwrite lands, and a third attempt against a deleted row is refused again.
It runs against slice 1's hand-authored fixture route until slice 4, then against the
regenerated demo app, so the story is proven before the generator changes and again after.
The scaffolded CRUD integration test keeps asserting today's `TQL-SQL-4092` path until
slice 4 flips it, which is what makes it a regression anchor rather than a proof. The
dialect suites run before the directive slice merges, because it renders SQL and the
vendors disagree about everything that renders SQL.

## The API caller, unchanged in shape

A JSON caller sends `_lock` in the body and gets today's envelope with one added sibling.
There is no dialog, no page, and no waiver affordance beyond the `_overwrite` field the
envelope names. `lock:` is emitted into OpenAPI as part of what the route accepts, because
it is contract rather than presentation — the line
[declarative-views.md](declarative-views.md) draws when it excludes presentation hints from
emission puts the lock on the emitted side. Both schema copies gain the key: the one under
`tesseraql-yaml/src/main/resources/schema/` and the editor copy under
`examples/scaffold-demo-app/.vscode/`, which the schema sync test pins against the record's
creator parameters.

## What this breaks

An app that hand-wired the lock keeps working. `expect:` with an author-written version
predicate is untouched, still raises `TQL-SQL-4092`, and still renders today's alert.
Adopting `lock:` is a rewrite of the route — the `version:` input goes, the predicate
becomes the directive, the form field becomes framework-owned — so it is a breaking change
for any app that copied the scaffolder's output, and it belongs in the CHANGELOG under
AGENTS.md rule 10. There is no shim and no alias: both shapes stay legal, one declared and
one authored, and the pairing lints keep pointing the authored one at the declared one.

## Recorded deviations

- **Overwrite is a waiver, not a fresh-version retry** (decision 7). Two operators who both
  waive both succeed. The framework has no governed read of the contested row, and a
  re-read inside the transaction would answer the command's own snapshot on repeatable-read
  engines.
- **No theirs/yours diff** (decision 8). Not taste — disclosure. The HTML path has no
  masking pipeline outside the response renderer, and a processor-built fragment bypasses
  even that.
- **No out-of-band fresh version on the success leg.** The upstream contract re-arms the
  next save by shipping the new version with the answer; here the save redirects and the
  form is re-rendered from the fresh row, so the lock is re-read rather than pushed. That
  keeps decision 6's rule intact — nothing arms a lock value except a render of the record.
- **No region-granular htmx choreography.** The conflict answer is a dialog and a redirect,
  the same stance [workflow-surface.md](workflow-surface.md) decision 3 took.

## Not in this design, and worth its own slice

A statement carrying `/*%lock*/` cannot be a declarative-suite `sql` case: the runner renders
with the case's plain YAML map, and only the command pipeline builds the `LockBinding` the
directive needs, so an unseeded render refuses at `TQL-SQL-2115`. The generated suite only ever
exercised the reads, so slice 4 broke no shipped suite — but it made every scaffolded app's
update and delete statements unreachable from `tesseraql test`, and the gap is recorded in
[testing.md](testing.md) rather than closed here. Closing it means a case-level key that builds a
lock the way `principal:` builds a scope context, which is a change to the suite runner and
belongs to it.

## Deliberately not in this design

- **Hints for `TQL-SQL-4090`, `4091` and `4093`.** They render the bare status phrase to a
  browser today, which is worth fixing — but not here, and not in one motion. The
  constraint pair already has `errors.constraints:` for a declared sentence, and the
  serialization failure needs the vocabulary of retrying rather than of staleness.
- **A second SQL parser** (open question 1). The directive is rendered, not verified.
- **Inline cell editing, and with it `datagrid-edit-conflict` and `datagrid-edit-errors`.**
  [list-surface.md](list-surface.md) refused them with "forms remain the edit surface", and
  this adopts exactly the half that refusal left alone. The two recipe names are one word
  apart, so saying it out loud is part of the design.
- **A field-level merge or three-way diff.** Refusing to merge is a position; the surface
  states it rather than implying a merge it does not perform.
- **Naming the other operator.** The framework stamps nothing for a plain row, route audit
  is off by default and records no values, and the one surface that could name another
  principal deliberately does not.
- **A server-held conflict store.** Nothing survives the round trip, so nothing needs a
  handle, a TTL or a new JDBC store registration.
- **A transaction-isolation knob.** The pooled datasource maps ten settings and none is
  isolation, and a lock that needed one would not be optimistic.
- **A lock on a relative update.** `stock = stock + /* delta */0` is already correct under
  concurrency, and a version predicate would make it wrongly fail. The inventory app is the
  worked example.
- **Reopening the workflow deviation.** State-as-lock stands for the transition itself;
  whether a transition's other column writes may also declare a lock is a separate
  sentence, not this one.
- **Widening `wantsHtmlLoginRedirect`.** The general "every no-JS 4xx renders raw JSON" hole
  is real, bigger than this campaign, and would ship untested HTML for statuses nobody has
  designed a page for.
- **Studio's data-browser row editing**, which builds ad-hoc SQL from the catalog and sits
  outside the SQL primitive by design.
- **Autosave, drafts, soft delete, undo-delete.** Ledger rows deferred behind stores that do
  not exist.

## Open questions

1. ~~How exact can the build-time refusal be?~~ **Settled 2026-09-02: rendered, not
   verified.** The compiler checks presence, uniqueness and the parenthesized dummy;
   position falls to a lint. A second SQL parser would cross the authored-statement line,
   and a misplaced directive is a syntax error at the first execution anyway. Written into
   decision 1.
2. ~~Does the conflict vocabulary absorb the other 409s?~~ **Settled 2026-09-02: no, and
   the channel rule is now explicit.** A refusal that fills `conflict` says its why through
   `conflict.hint`; one that does not says it through `details.message`. `4093` is
   retryable, `4090`/`4091` are constraint violations with their own declaration, and
   `TQL-WORKFLOW-3201`'s missing catalog entry is a defect fixed in slice 3. Written into
   decision 5.
3. ~~What does an `input:` field named for the lock column mean?~~ **Settled 2026-09-02 with
   slice 2: refused**, `TQL-ROUTE-3119`. Two owners of one column is the disagreement the whole
   design avoids, and a form derives its fields from `input:`, so the page would render a
   writable control for the column beside the framework's own hidden field. Route-shaped,
   because the check needs only the route's own declaration.
4. ~~Does the scaffolder's delete leg keep its hand-written slot fragment?~~ **Settled
   2026-09-03 with slice 4: it keeps it**, and the generator emits its `_lock` field from
   `v.lock`. The confirmed delete is a second locked form on one page, and each form carries
   its own route's lock — the fragment is generated markup, so the generator owns that field
   exactly as the form pattern owns its own.
