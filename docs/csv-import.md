# CSV import — the reviewed upload, and the three contracts it satisfies

> **Design, written 2026-09-01, measured against main at #1117.** The `csv-import`
> adopt from the catalog ledger ([hc-recipe-alignment.md](hc-recipe-alignment.md)),
> and the named trigger for the two recipes that ledger deferred behind it:
> `file-upload` (the upload leg) and `async-job` (the commit leg). It is also the
> second consumer the bulk report was built for ([bulk-report.md](bulk-report.md)
> decision 1), so the shared failure surface stops being an intention and becomes
> a fragment two feeders fill.
>
> **Amended in review, same day.** Three things the first draft left implicit or
> open: `onError:` decides what a partly-invalid file offers to confirm (decision 3),
> the always-asynchronous commit is settled rather than weighed (decision 6), and the
> format is an axis rather than a name — Excel import rides this design unchanged and
> the export side inherits the job card through the shared status mount (decision 8).

Today an import is a fire-and-forget upload: `POST` the file, get `202` and a
transfer id, poll for the outcome, and discover in the answer that row 3 had a
bad date and row 5 no name — after the write already happened, or after the
whole file rolled back. The upstream contract names the missing phase: **an
upload parses and validates without importing**, the answer is a report of what
will happen, and a token commits exactly what was reviewed. This design gives
TesseraQL that phase as a declaration on the import it already has, and builds
the browser face the ledger has been deferring.

## What exists, and what is missing

The machinery half is, again, mostly done — and mostly unreachable.

`recipe: file-import` compiles to one processor that hands the uploaded stream to
`FileTransferService.startImport`. That service spools the bytes, records a
`tql_job_execution` row plus a `tql_file_transfer` row, and runs the whole import on
a virtual thread: one connection, one savepoint per row, `codec.read(...)` driving a
handler that types each row and executes the declared per-row statement. Rejected rows
collect with their row numbers and are written into `error_json` at the end. The
route owns `GET {path}/{transferId}`, and every transfer is visible on the
operations console.

Four things are missing, and they are the campaign.

**There is no way to parse without applying.** The parse and the SQL are fused by
construction: `codec.read(content, spec, handler)` *is* the row loop and the
handler *is* the executor. There is no seam at which a validated batch exists as
a value. The upload spool is deleted in a `finally` the moment the run ends, and
an import's `spool_uri` is never written at all, so nothing about the uploaded
file survives the request that started it.

**No import route can answer HTML.** `HtmlResponseRenderer` is constructed at
exactly two sites, neither of them the file recipes, and `buildFileImport` never
reads `definition.response()`. A `response.html:` block on a file-import route
lints clean, compiles, and is silently dropped — the silent-tolerance shape this
codebase has swept twice, sitting in the compiler.

**The bulk report is not a fragment.** Decision 1 of
[bulk-report.md](bulk-report.md) promised one display contract with two feeders;
what shipped is markup inlined in `tql/view/list.html`, a render model built by a
private method reachable only from the list path, an entry whose link is
hard-derived as `"#row-" + token`, and no slot for a field name or a rejected
value. The second consumer cannot fill it without the extraction the promise
implied.

**Nothing in the framework speaks the async-job shape.** The operations console
polls whole pages every fifteen seconds and never stops; the one self-terminating
fragment in the tree is the Studio copilot's SSE placeholder. A card that carries
its own trigger, writes its own cadence, and goes quiet by not carrying a trigger
is new.

## Decision 1 — the review phase is a declaration on the import

`import:` takes one new key:

```yaml
import:
  format: csv
  review: required        # the only accepted value; absent = one shot
  columns: [sku, supplier, price]
```

With `review: required` the upload leg stops importing. It parses, validates,
parks the batch, and answers the report; a second request commits it. Without the
key the recipe keeps exactly today's behaviour — one shot, `202`, poll. The
declaration reads the way `comment: required` reads on a workflow transition, and
it does the same job: it names a human step the machinery would otherwise skip. It
borrows that key's vocabulary rule too — `required` is the only accepted value, and
any other is refused at build time rather than quietly ignored.

This is a key on the existing recipe, not a new recipe, for three reasons. The
route already owns the `{path}/{transferId}` subtree the confirm leg needs, and a
second recipe would either collide with it or fork the URL vocabulary. The import
declaration — format, columns, header row, locale, the per-row statement — is
identical in both shapes, and a fork would have to keep two copies of it honest.
And the review phase is genuinely valuable to an API caller: a careful integration
wants to validate a feed before applying it, and gets that here for free, because
the two legs are content-negotiated faces of one contract, not a browser feature.

**The upload answers `200`, and it says so here because the code it would otherwise
inherit is wrong.** Today's upload leg calls `respondAccepted`: `202` plus a
`Location` header pointing at the status resource. Both are false for a reviewed
upload — nothing was accepted for processing, and a `Location` invites a caller to
poll an import that cannot start without a commit. A reviewed upload answers `200`
with the report and the token, in the all-valid case and the some-rows-invalid case
alike; only a file with nothing importable, or one that could not be read at all,
answers `422`, and only that case omits the confirm affordance. That `422` carries the
report, not an error envelope: the caller needs the rejected rows, and an envelope
would replace them, so this refusal deliberately has no error code of its own.
`respondAccepted` stays reachable on the no-review path alone.

**The parse is synchronous, and its bound is the body cap.** Answering with a
report means parsing before responding, which moves work that used to happen on a
virtual thread onto the request. The bound already exists and is the honest one:
`tesseraql.http.maxBodyBytes` (10 MB by default) is what a browser upload rides,
and decision 7 makes exceeding it visible instead of silent. No second cap is
invented for a limit the edge already enforces.

**A review nobody can perform is refused at build time.** A `poll:`-triggered
import and an `import:` step inside a job have no one to confirm and no session to
own the token. Both refusals are lints, and they take the domain of the document
they are refusing: a route declaration is `TQL-ROUTE-3118`, a job document is a
`TQL-JOB` lint naming the job. One code whose message says "route" for a document
that is a job would be a lie the generated error reference then makes permanent —
`ImportSpec` is shared by `RouteDefinition` and `JobDefinition`, so this is not a
hypothetical. Without the refusal the failure mode is a directory feed that
silently stops importing.

## Decision 2 — the parked batch is its own row, and the commit is an ordinary import

The tempting move is to make the transfer row the parked batch: it already carries
the route, the app, the format, the row count, the rejected rows and a spool
reference. It does not survive inspection. A transfer's *status* is not its own —
`status(transferId)` reads it from the `tql_job_execution` row whose id is the
transfer id, so parking a batch would mean adding a state to `JobStatus`, the enum
the whole batch platform reads: the operations console, the job history, the
overlap policy, the reaper, the poll-import await loop. It would be a non-terminal
execution state that nothing in that machinery can ever finish, on a row that
represents an import which never ran. The console would show a phantom import that
imported nothing.

So the parked batch is its own small row, `tql_import_batch`, in the `operations`
component beside the transfer table and owned by the same service:

- `batch_id` — the token. Opaque, and authorized on use rather than trusted for
  existing, the stance row tokens take.
- `subject`, `app_name`, `route_id` — who parked it, and where it may be committed.
  A transfer id is looked up globally today; a batch is not, because a parked batch
  is business data waiting to be written and one app's operator must not commit
  another's upload. On a stack this matters more than it looks: the framework pools
  are shared, and a subject-only scope would be one namespace across every
  application.
- `spool_id` and `spool_uri` — the uploaded bytes, kept, addressed both ways. The temp stores
  disagree about which half locates them: the file store resolves the URI, while the database
  and blob stores look the id up as a key. Parking one of the two works on one store and
  silently fails on the others — including the `db` store this design requires for more than
  one node.
- `read_spec_json` — the **resolved** read spec (decision 3 says why).
- `report_json` — the bounded report, and the *complete* set of rejected row
  numbers. The two are different things: the display is capped, the rejection index
  is not, because "commit exactly what was reviewed" is only well defined if the
  server knows every row it excluded. A set of longs is cheap; a hundred rendered
  messages is what needs bounding.
- `claimed_at` — the single-shot claim (decision 5).
- `expires_at` — the review window.

**The commit is then an ordinary import.** It claims the batch, and hands the
spooled bytes plus the frozen read spec to `startImport`, which produces a normal
transfer and a normal execution — one that runs, counts rows, records rejections and
reaches a terminal state exactly as today. `JobStatus` is untouched, the operations
console shows one import that really imported, and the async-job card of decision 6
polls a transfer that already exists. The upload phase records nothing in the batch
platform at all, which is the honest answer to "how many imports ran": one, at
commit.

**Two requests, one node.** The spool must be readable by whichever node serves the
commit, and the default `tesseraql.temp.store: file` is node-local. A reviewed
import on a multi-node deployment therefore requires `db` (or a real object-storage
`blob`), and a commit that cannot open its spool refuses with the store named
(`TQL-LD-2866`) rather than reporting an empty file. Deployment guidance says it
once, in [deployment.md](deployment.md), beside the existing cross-node download
note that says the same thing for exports.

**An abandoned batch is swept, by default.** The existing transfer retention sweep
is opt-in (`retentionDays` is unset unless an app sets it), which is a defensible
stance for produced export files and the wrong one for parked upload bytes: those
are business data the user did not choose to store, sitting on disk. The review
sweep is separate and always on, bounded by `tesseraql.transfers.reviewTtl`
(default 30 minutes), and it deletes the spool and marks the row expired. A batch
that expires unclaimed leaves a row, not bytes — which is what lets the surface say
"this batch expired, upload again" instead of "unknown token".

**A re-upload supersedes.** The contract says replacing the batch is always
allowed, so an upload expires the same subject's prior unclaimed batch for the same
route as it parks the new one. Without that rule two live tokens exist and the
superseded report stays committable for its whole TTL — the user reviews one file
and can still commit the other.

**The dwell is new, and named.** The framework's scan gate covers attachments; a
transfer upload has never been retained long enough to scan, and now one sits on
disk for the review window. This design does not add scanning to transfers — that is
the attachment path's SPI and its own trigger — but it records that the dwell exists
so the decision is a decision, and it keeps the window short by default for exactly
that reason.

## Decision 3 — what "validated" means, said plainly

Phase one is the parse. It finds what a file can be wrong about:

- the file could not be read at all, or its header does not map — a renamed column,
  a missing declared one,
- a row's arity is wrong,
- a value will not parse as its declared `type:`/`format:` under the resolved
  locale,
- a declared per-column constraint fails.

That last one is the new expressiveness, and it is not invented: a CSV row is a
line item that arrived as a file, and the framework already has a language for what
a field must satisfy. **On a file-import route the row contract is `input:`**, and
`import.columns:` keeps the job it already had — which cell feeds which bind name:

```yaml
input:
  sku: { type: string, required: true, pattern: "[A-Z]{2}-[0-9]{3}" }
  qty: { type: integer, required: true, min: 1 }
  supplier: { domain: supplier }
import:
  format: csv
  columns: [sku, qty, supplier]
  review: required
```

Every constraint applies to the value it is about, whatever shape the cell arrived in.
A column's own `type:` is a *parse* instruction and an optional one, so a contract that
only checked numeric bounds on values a column had already typed would be inert on the
ordinary form above — a declared rule that lints clean and never runs. `min:` coerces
for the comparison instead, and a cell that is not a number at all fails the same
check. The keys an import's row contract does **not** honour are refused where they
are written (`TQL-YAML-1062`) rather than dropped in silence.

The first draft of this decision put the constraint keys on the column instead, and
two facts retired it. `type:` and `format:` already exist on an import column and
mean the parse pattern there, so the column would have carried two vocabularies
wearing one name — a collision this design had already flagged as a risk and would
then have had to manage forever. And a column-side constraint would have been a
second declaration of something the framework declares once: `input:` already merges
field domains at load time, already resolves `codes:` against a catalog, already
renders through the field-errors contract, and is already what the request binder
evaluates. A second spelling of one rule is the drift this campaign keeps refusing.

There is a third gain, and it is the one that decides it. `input:` on a file-import
route lints clean today and does nothing at all — declared, compiled, silently
dropped, because the recipe installs no binder. Giving it the meaning it looks like
it has turns a silent-tolerance shape into the feature, rather than leaving it
standing beside a new key that does the same job.

An `input:` name that matches no declared column is refused (`TQL-YAML-1061`): a
contract nothing is held to is worse than no contract. The reverse is fine and
common — a column may be mapped and unconstrained.

**A poll-driven import gets no row contract, and that is not an oversight.** On a job
document `input:` already means the run's parameters, and giving one key two meanings
across two document kinds is exactly the collision this decision just refused on
`type:`. A row contract for directory feeds needs its own declaration, and its own
trigger.

**Phase one is not connectionless.** Type parsing, arity and
`pattern:`/`min:`/`max:`/`required:` need no database. `codes:` and a
catalog-backed `domain:` do: catalog validation resolves through the catalog store,
and on a miss it *reloads the catalog from its source table*. That reload is per
rejected value, which is fine for a form submit and catastrophic for a file —
thirty thousand stale codes would be thirty thousand full catalog reloads,
serialized. So a reviewed import resolves each referenced catalog **once, before the
row loop**, and validates every row against that snapshot. The report is then honest
about what it is: a snapshot check, taken at upload.

**And the snapshot is frozen with the batch, because it is a third parse input.**
Decision 2 freezes the read spec so the commit parses what the review parsed; the
resolved code sets need the same treatment for the same reason. Re-resolving them at
commit would let a code retired during the review window move the rejection set,
which trips the agreement check and refuses the commit with a message blaming the
file for having changed. So the contract the review built — constraints and resolved
code sets together — is parked as data beside the read spec, and the commit is held
to it.

The consequence is worth stating rather than hiding: a code retired inside the review
window is still imported. Nothing catches it — a catalog's `active` flag is a column
on its own source table, not a constraint the database enforces, so there is no
foreign key waiting to refuse the row. That is what a snapshot check *is*, and it is
the same bargain the whole review phase makes: the author reviewed a report, and the
import must apply what they reviewed. The defence is the window's length, not a
backstop, which is one more reason the review TTL is thirty minutes and not a day.

What phase one cannot find is anything only the database knows at write time: a
foreign key that does not resolve, a unique conflict, a check constraint. Those
surface at commit, in the same report shape, and the surface says so rather than
implying a clean report is a guaranteed import. The summary line is "3 of 5 rows
ready", never "3 of 5 rows will import".

**The number the report speaks is the file line.** What the machinery counts today
is the data-row ordinal, which is not what the user sees in their editor once
`headerRow: true` or `startRow:` has skipped something. The parse carries the file
line, and the report cites it; a report that cites row 3 for what the user's editor
shows on line 4 is a bug report waiting to be filed against the wrong thing.

**The locale is a parse input, so it is frozen at upload.** The read spec is
resolved per request — `locale: query.locale` or `principal.claim.locale` or the
negotiated `request.locale` — and the commit is a *different* request, where that
expression may resolve differently or not at all. `1.234,56` would then parse as a
different number, the rejection set would move, and the agreement check of decision
5 would refuse a commit the user can never make. The resolved spec is parked with
the batch and the commit re-parses under it, which is what makes the parse a pure
function of (bytes, frozen spec) rather than a phrase that hides a third input.

`onError:` keeps its meaning on the commit leg and gains a sharper one: with a
review declared, `rollback` means the reviewed set applies atomically or not at
all, and `skip` means the rows the database also rejects are dropped from an
otherwise applied batch. The report distinguishes the two passes by where an entry
came from, because "your file is malformed" and "your data conflicts" are
different sentences to the person holding the file.

**And `onError:` decides what the review offers, which is the rule this design
would otherwise have left implicit.** Ten rows with three rejected by the parse is
never "all ten failed" — the pass runs to the end and reports the three
individually, by line, field and reason. What differs is the affordance under them:

| `onError:` | the confirm form offers | the answer |
| --- | --- | --- |
| `skip` | "import the valid 7" | `200` + report + confirm |
| `rollback` (the default) | nothing — all or nothing is the declaration, and three broken rows leave no committable set | `422` + report, no confirm |

One rule states both: **the confirm form exists exactly when a committable set
exists** — the clean rows under `skip`, every row or none under `rollback` — and the
status code follows the same test, which is also the contract's own rule that a file
with nothing importable answers `422` and omits the affordance. A clean file under
`rollback` therefore confirms all ten rows and answers `200` like any other.

Offering "import the valid 7" under a declared `rollback` is refused: it would let
the surface quietly overrule the declaration. Refusing `review:` *with* `rollback` at
build time is refused too — an all-or-nothing file that validates clean is a perfectly
good reviewed import, and the review is what tells the author their file is not clean
before they find out by rollback.

## Decision 4 — the report is the bulk report, generalized

`tql/view/list.html`'s inlined report markup moves to **`tql/view/report.html ::
report(r)`**, a fragment of the public rendering contract, and the list inserts it
where the markup used to be. The render model is lifted out of the list path with
the things a second feeder needs:

- **the link is a value, not a derivation.** An entry carries its `href`; the bulk
  feeder fills `#row-<token>` as before, the import feeder fills the preview
  table's line anchor. Position is not identity in a grid; in a file the line
  number *is* the identity, and the model should let each feeder say which it has.
- **an entry carries `field` and `value`.** "Row 3 — qty: 'abc' is not a number"
  is the contract's `Row` / `Field` / `Message` table, and the bulk feeder simply
  leaves both null.
- **a report carries file-level entries.** A header that does not map, or a file
  that could not be read, belongs to no row. Today that failure produces a failed
  transfer with an *empty* errors array — the most common real import failure,
  reported as nothing at all. It is the contract's "file-level error line", and it
  needs a slot above the groups rather than a fake row number.

Two defects in the shipped model are fixed by the same edit rather than inherited.
Reason groups are keyed on the code alone, and a group takes its heading from the
first outcome that created it. Two entries sharing a code with different messages
therefore merge, and the second message disappears — harmless when one transition
means one guard, wrong the moment a parse pass emits one code with many distinct
messages. Groups key on (code, message), and because that makes the *number* of
groups data-dependent for this feeder, the group list is bounded too, with the same
"…and N more" honesty the entries inside a group already use. The group cap is the
second defect: it is a constant applied when the report is *stored*, so no renderer
can widen it. The cap becomes a property of the feeder, because a validation report
the user must act on is not a bulk action's convenience banner.

**A commit-pass message is mapped, not quoted.** Today a rejected row's message is
the driver's `getMessage()`. That text belongs in the log and on the operations
console; putting it on a page shows the user SQL, and sometimes another row's
values. The report renders the framework's message for the failure class and keeps
the driver text where operators already look.

The import's error table renders as the contract's real `<table>` — a caption,
`scope="col"` headers, the line number as a `scope="row"` header — with the
grouped `hc-alert` above it carrying the summary and the reason groups. Both are
bounded, and the surface says what it bounded.

Extracting the fragment changes what an application's own `tql/view/list.html`
override renders — a level-2 customization break, recorded in the CHANGELOG as
breaking under the pre-1.0 rule, with no shim. The override lint cannot catch it
(it checks the fragment signature, not what the file contains), which is a reason
to say it loudly in the CHANGELOG, not a reason to avoid the extraction.

## Decision 5 — the commit is single-shot, and the claim is the batch row

**A reviewed import needs an authenticated route, and that is refused at build time.** A batch
belongs to the principal who parked it, and an unauthenticated route has no principal to be:
left alone, every batch gets the same empty owner and anyone can confirm anyone's upload while
the code still reads as though it were scoping. `review: required` without a `security.auth:`
declaration is `TQL-ROUTE-3118`, beside the bad-value refusal.

**And a refusal has to arrive as a sentence.** The error envelope renders the code and a status
phrase, nothing else, so a message the caller is meant to act on rides `details.message` — the
channel a thrower uses to declare text safe to show. Without it all four ways to lose a token
read as "Conflict", and the distinction between "a newer upload replaced this" and "this was
already committed" — the distinction supersession exists to be able to draw — never reaches the
person holding the token. The bulk report's guard text learned this the same way.

`POST {path}/{transferId}/commit` is the confirm leg — addressed by the batch id,
which the confirm form carries both in the action path and as a hidden `token`
field, per the contract's machine-checkable anchor; the server reads one and
refuses a mismatch with the other. It is authorized by the route's own `security:`
block, plus the batch's `subject`, `app_name` and `route_id`, plus a conditional
update that sets `claimed_at` where it is null. The winner commits; a second
request — a double click, a replayed form, a back-button re-post — loses the race
and answers `409` with the re-upload fragment, which is the contract's rule that
the fix for a stale token is always a fresh upload and never a retry.

Nothing is added to the binder's reserved fields for this. The commit leg is a
framework-mounted sub-route with no `input:` block and no request binder, so
`token` never meets the mass-assignment guard — and reserving the bare word `token`
globally would weaken that guard for every application that has a field by that
name. The `ids` precedent from the bulk report's first slice was the opposite
situation: that field was posted to an author's own route, which does bind.

**Claim before run, and say what a crash leaves.** The claim is taken first, so a
crash between claiming and finishing cannot be replayed into a double import — the
strictly safer failure, given that the alternative loses a write the user believes
happened. What it leaves is a claimed batch whose transfer exists: the surface shows
that transfer's state, which is the truthful answer, and the durable record is the
transfer and its execution history. A claimed batch is never re-committable, and the
answer is a fresh upload.

It deliberately does **not** compose with `_idempotency`. The idempotency design
excludes uploaded bytes from the request hash and says an attachment-bearing
command should not declare a key; two single-shot mechanisms on one form would be
one too many, and the claim on the row is the stronger of the two because it is the
same row the write is about.

**The sub-routes get governance, not just security.** `mountTransferStatus` applies
`applySecurity` alone — no tenancy, no i18n, no audit, no telemetry — so a transfer
status response resolves no tenant and localizes no error. The commit and cancel
legs are writes and must not repeat that; they take `applyCommonGovernance`, and
the status leg joins them. There is a precedent for exactly this retrofit and
exactly this reasoning: `applyAttachmentGovernance` exists because all three
attachment routes used to skip tenancy, and its Javadoc says so.

## Decision 6 — the commit is always asynchronous, and the card is the contract

The commit answers `202` with a running job card. Not "asynchronous above some
row count" — always, because one shape is worth more than a threshold nobody can
predict, and because the import path is already asynchronous by construction:
`startImport` submits to a virtual-thread executor and returns a transfer id, and
there is no synchronous import path at all (exports have `exportInline`; imports have
no equivalent). For a small file the card's first poll finds the terminal state,
which is the contract working, not overhead — and the running card triggers on
`load` as well as its interval, so that first poll is immediate rather than one
cadence away.

**The alternative was weighed and declined.** The commit could start the import,
wait a bounded moment, and answer `200` with the result summary when it finished in
time — the csv-import contract's own shape — falling back to the card when it did
not. It is affordable: every request already runs on its own virtual thread, so the
wait parks one of those and holds no pooled connection. It was declined for what it
costs on the other side: one endpoint with two response shapes, a second branch in
the no-JS path, and an operational number (how long to wait) that has to be chosen
and then defended. What it buys back is a single round trip that the `load` trigger
already makes imperceptible. Simplicity wins, and the divergence is recorded below
rather than engineered around.

The card is the upstream shape: it carries its own `hx-get` and `hx-trigger`,
targets itself, swaps `outerHTML`, carries the contract's dialect-neutral markers
`data-hc-job` and `data-state`, and **a terminal card carries no trigger**. The
cadence is the server's — the fragment writes the interval it wants, tight while a
small import runs, backed off for a long one — because a client-side backoff would
be a second policy for something the server already knows.

**The card does not land in the report slot.** The csv-import contract makes the
report slot `aria-live="polite"`; the async-job contract forbids `aria-live` on the
card, because every poll would re-announce the whole card including its buttons.
Both hold at once only if the commit response retargets: the card swaps into its own
region beside the report, and the polite line lives inside the card, on the progress
text alone. That is the one place the two contracts pull against each other, and
resolving it in the markup rather than picking a winner is the whole job.

Its five states map onto machinery that mostly exists:

| card state | source |
| --- | --- |
| running | the transfer's execution row, with the progress line |
| done | `COMPLETED`, with the report of what the database rejected |
| failed | `FAILED`, saying whether anything was written |
| cancelled | the cooperative stop request, once the row loop reads it |
| expired | an unknown or swept transfer id — `200`, a tombstone, no trigger |

Two of those are not free. **Cancel has no source today**: the job repository holds
a cooperative stop request and the import row loop never looks at it, so a Cancel
button would answer `200` and change nothing. The loop checks the flag between rows,
which is where a per-row transaction makes cancellation cheap and exact. And the
**expired** case is a `404` with a `TQL-LD-2822` envelope today, which is right for
the JSON API and wrong for a polling card, because a card that receives an error
keeps polling an error. Staleness is a state, so the HTML face answers a tombstone
that stops.

**Progress becomes observable.** The row count and the rejected rows are written by
a single update after the loop, so a poller sees `RUNNING` and zero rows for the
whole import and then the final number. The card wants "12,000 of 30,000 rows" —
and the review phase is what makes the denominator knowable, because the file was
already parsed once. The loop flushes its counter on a time interval, not per row.

**The completion signal is `emit:`, and the grid refreshes itself.** The contract
asks the commit response to carry an `HX-Trigger` with a toast and a domain event so
that data-region listeners refresh the grid the rows landed in. The framework
already renders that pairing a different way, and the ledger already records it as
aligned: a write declares `emit:`, a view declares `refreshOn:`, and the refresh
arrives over the existing event stream. An import is a write, so it emits on commit
like any other, and a products list watching that topic refreshes when the import's
transaction commits — which is *better* than the header, because the refresh reaches
every open page rather than only the tab that pressed the button. The toast rides
the done card. The substitution is recorded as a deviation below.

**Without JavaScript every leg still works.** The upload form posts natively and the
server answers the full report page. The confirm form posts natively and answers
`303` to the transfer's own status page — post/redirect/get, the shape both
contracts specify — and that page is a real URL showing the same card, refreshable
by hand. Nothing in the flow requires the fragment path.

## Decision 7 — the upload form is the kit's, and the 413 must be visible

The upload leg is the `file-upload` contract with nothing invented: both encodings
(`enctype` for the native submit, `hx-encoding` for htmx), a labelled file input,
`<progress class="hc-progress htmx-indicator" data-hc-upload-progress>` inside the
requesting form with the form's `hx-indicator` pointing at it, `hx-disabled-elt` as
the double-submit guard, and the out-of-band pristine form on success, because a
file input cannot be reset from markup. Every piece of that is already in the
pinned kit and auto-installed; the framework has been shipping none of it.

The page is two documents at one URL, the way a snapshot list already is: a `get.yml`
renders the import view, a `post.yml` is the file-import route whose `response.html.view`
names the same document. Honouring `response.html:` on a file recipe — instead of
dropping it silently — is part of the slice that adds the face. A `file-import`
route that declares `response.html:` without `review:` is refused at build time:
there is no report for a one-shot import to render, and silently rendering the 202
envelope as a page would be the same silent tolerance in a new costume.

Two edge defects are in scope because an upload surface makes them user-visible.
The body cap (`tesseraql.http.maxBodyBytes`, 10 MB by default) does apply to
multipart, and the `413` it produces is a router-level JSON envelope emitted by a
handler that runs before any route context exists — so it carries no marker, htmx
declines to swap it, and an over-cap upload from a browser renders *nothing at all*.
The `413` gains an htmx arm that answers the field-errors fragment with the same
marker every other refusal carries, which is the only way the swap gate lets it
through; being pre-route, it says the limit and names the key, not the route. And
[file-transfers.md](file-transfers.md) currently says in one paragraph that the
upload rides the body bound and in another that there is no framework size cap on
transfer uploads; the second is stale and wrong, and an import surface is the wrong
place to leave it standing.

The bootstrap's `htmx:beforeSwap` allowance gains its fourth marker,
`data-tql-import-report`, so the `422` "nothing importable" report and the `409`
stale-token fragment swap. The allowance is substring-gated per fragment kind
precisely so each new kind states itself; reusing `data-hc-field-errors` to sneak
past the gate would be a lie about what the fragment is.

## Decision 8 — the format is a declaration, so Excel rides this unchanged

Nothing in this design is CSV. The name comes from the upstream recipe; the
mechanism is the framework's existing `format:` axis, and `format: excel` is
already a legal import today — the codec is an opt-in module resolved through the
standard mechanism, and `FileCodec.read(in, spec, handler)` is the format SPI the
parse pass drives. So "excel-import" is not a future recipe to design: it is
`recipe: file-import` with `review: required` and `format: excel`, and it works
the day the codec is on the classpath. The one format that can never take a review
is PDF, and it is already refused as output-only (`TQL-LD-2830`) upstream of
anything here.

Three things must therefore be **supplied by the format** rather than assumed, and
they are cheap only if they are decided now:

- **The row reference.** Decision 3 says the report speaks the file line, which is
  the right answer for a text format and the wrong word for a workbook, where the
  reference is a sheet and a row (`sheet:` and `startRow:` are already import keys).
  The parse asks the codec for the row's location and the report carries it as a
  **label**, the way decision 4 already makes the link a value rather than a
  derivation. Same slot, one more thing the feeder fills.
- **The upload's `accept` list.** The file input's accepted types come from the
  declared format, never a literal — a CSV import offering `.xlsx` and an Excel
  import offering only `.csv` are the same bug in two directions.
- **The size bound.** A workbook is several times the bytes of the same rows as
  text, so the 10 MB body cap bites sooner for the same feed. That is a
  configuration fact, not a design one, but it belongs in the prose beside the
  format table so nobody discovers it by 413.

**The export side inherits the card for free, and that is not a coincidence.**
`mountTransferStatus` is the *shared* status endpoint: `buildFileImport` and
`buildFileExport` both call it, and a transfer row already carries its `direction`.
So the HTML negotiation decision 6 adds lands on one mount and answers for both
directions, and `file-export` already owns `{path}/{transferId}/file` — which is
exactly the async-job contract's "the artifact itself, `Content-Disposition:
attachment`, idempotent". An asynchronous Excel export therefore becomes: kick off,
get the card, watch it, download from the done card. The card's `done` state is the
only direction-aware part — an import's done card shows what the database rejected,
an export's shows the download link — so the state is written direction-aware from
the start rather than retrofitted when the second consumer arrives.

What an export deliberately does *not* inherit is the review phase. There is nothing
to validate before writing a file the user has not received yet, and the contract's
two phases exist to put a human between a parse and a write. An export's equivalent
question — "is this the right filtered set?" — is answered by the page that kicked it
off, not by a report.

## Decision 9 — what this surface refuses

- **No merge or diff UI.** The answer to a conflict is a fresh upload, per the
  contract. Nothing here edits a parked row.
- **No partial retry.** The reviewed set is committed or it is not; a batch with
  rows the database rejected under `skip` is a completed import with a report, not
  a resumable one.
- **No file field on generic form views.** A declarative form gaining a `file`
  widget is a real feature with its own trigger; the import view owns its own form
  markup, and the second consumer of the upload recipe is the operations console's
  deploy page, which already posts multipart and only lacks the progress bar and
  the reset.
- **No new stream.** Job progress over server-sent events would need a new signal
  family and a choke point; polling is what the contract specifies and what the
  card's self-terminating shape makes cheap. The *completion* signal is different
  and does ride the existing stream, because `emit:`/`refreshOn:` already exists.
- **No transfer scanning.** The retained upload is a new dwell (decision 2) but the
  scan gate belongs to the attachment path and its SPI; widening it is its own
  trigger, and pretending otherwise would put a claim in the threat model that the
  code does not keep.

## The three contracts, and where each clause lands

| contract clause | where |
| --- | --- |
| upload parses and validates without importing | decision 1, `review: required` |
| all rows valid → `200` + report + confirm form | decision 1 |
| some rows invalid → `200` + report + "import the valid N" | decision 1, decision 4; `onError: skip` is what makes the partial set committable |
| nothing valid / unreadable → `422`, no confirm form | decision 1 (the report, not an envelope), decision 4's file-level entry |
| re-upload replaces the batch with a fresh token | decision 2, supersession |
| report = summary + real error table + tokened confirm form | decision 4 |
| token in the path **and** a hidden input, pair must match | decision 5 |
| commit executes exactly what was validated | decision 2, decision 3 |
| token single-shot, second commit `409` | decision 5 |
| commit success → toast + domain event refreshes the grid | decision 6, `emit:`/`refreshOn:` (deviation) |
| both encodings, progress bar, OOB fresh form | decision 7 |
| server validates; client `accept` is UX only | phase one is the server |
| `422` retargeted to the error container | decision 7, the fourth allowance marker |
| proxy/edge `413` | decision 7 |
| kick-off `202` + self-polling card | decision 6 (deviation: the contract's commit answer is `200`) |
| card polls itself, `outerHTML`, terminal carries no trigger | decision 6 |
| `data-hc-job` marker, `data-state` on non-running cards | decision 6 |
| server owns the cadence | decision 6 |
| expired id → `200` tombstone | decision 6 |
| cancel is a no-op `200` when already finished | decision 6, once the loop reads the flag |
| progress in a polite live line, never on the card | decision 6, the retargeted card region |
| no-JS: native post, `303`, one page at a time | decision 6 |

## Slices

1. **The review phase, machinery first.** `import.review:`, the `tql_import_batch`
   row and its three vendor migrations, the parse-only pass with its frozen read
   spec, the retained spool and its always-on review sweep, supersession on
   re-upload, the commit leg with its conditional claim, the `200`/`422` upload
   answers, the new error codes *and their status mappings*, governance on the
   sub-routes, and the two build-time refusals in their own domains. JSON only — an
   API caller can validate and commit before a single template exists. Adjacent: the
   `LD` arm of the status switch defaults to `500`, and the ledger that would have
   caught that only inspects numbers 4000–4999; this slice adds codes that would
   otherwise answer "Internal Server Error", so it fixes the mapping and records why
   the ledger could not see it.
2. **The row contract.** `input:` on a file-import route becomes what each row must
   satisfy, evaluated as frozen data so the two passes of a reviewed import cannot
   disagree, with each referenced catalog resolved once before the row loop. This is
   what makes the report worth reading, and it is separable from the phase that
   carries it.
3. **The report, generalized.** `tql/view/report.html`, the lifted render model,
   `href`/`field`/`value` on an entry, file-level entries, groups keyed on
   (code, message) and bounded, the cap owned by the feeder, mapped messages on the
   commit pass, the list consuming the fragment unchanged on screen. A breaking
   CHANGELOG entry for the override contract.
4. **The HTML face.** The import view, the file-upload recipe markup, the report
   slot, the confirm form, the `422`/`409` fragments and the fourth allowance
   marker, `response.html:` honoured on a file recipe (and refused without
   `review:`), the `413` htmx arm, the i18n keys.
5. **The job card.** HTML negotiation on the status endpoint, the self-polling
   card and its five states in their own region, the server-written cadence, the
   cancel leg with the row loop reading the stop flag, the expired tombstone, the
   flushed progress counter, the `emit:` on commit, and the no-JS `303`.
6. **The dogfood and the second consumer.** The supplier-price import in
   `examples/inventory-app` end to end, with a products list declaring `refreshOn:`
   so the completion signal is visible; the operations console's deploy form
   retrofitted to the upload recipe; the drift guard's recipe and behaviour entries;
   and the user-facing prose in [file-transfers.md](file-transfers.md) and
   [hypermedia-ui.md](hypermedia-ui.md). The dogfood declares `format: csv`, and the
   same route with `format: excel` is what proves decision 8 — one integration test
   over the Excel codec, asserting the report's reference reads as a sheet row, is
   what keeps the format axis honest rather than aspirational.

Slices 4 and 5 are adjacent for a reason worth stating: slice 4 ships a confirm
button, and what its success answers is slice 5's card. Slice 4 therefore answers
the no-JS `303` from the start, and the card is what replaces the redirect for an
htmx caller — the button is never broken, only plainer, between the two.

## Recorded deviations

**The commit answers `202` and a card, not `200` and a summary.** This is the
largest divergence from the csv-import contract, and it is deliberate: the framework
has no synchronous import path, and inventing one for small files would mean two
execution shapes and two report paths for one declaration. The async-job contract
is the one that fits, and the csv-import contract itself names batch imports as
async-job's subject — which is worth noting for what it is: the two upstream
contracts overlap here and upstream did not resolve the overlap, so this is a choice
between them more than a departure from one. Settled in review after the bounded-wait
alternative was weighed and declined (decision 6): simplicity of one response shape,
against a round trip the `load` trigger already hides.

**The completion signal is `emit:`/`refreshOn:`, not `HX-Trigger`.** Same job, a
different mechanism, already recorded as aligned for `data-region` in the ledger:
the refresh reaches every open page rather than the tab that pressed the button.

**Attribute dialect.** Every upstream recipe is written `data-hx-*`; every template
this framework emits uses bare `hx-*`, and the emitted markup is a public contract
of the route compiler. The framework keeps `hx-*`, which means the recipes' own
`checks.json` rules keyed on the prefixed spelling do not run against these
templates — so the markers those checks look for (`data-hc-job`, the hidden `token`,
`data-hc-upload-progress`) are emitted deliberately and asserted by TesseraQL's own
tests instead.

**The report is not the record.** The parked batch and its report are bounded by a
TTL and swept; the durable record is the transfer row and its execution history,
which is where the operations console already looks. The same stance the bulk
report takes, for the same reason.

**One-shot imports stay.** `review:` is optional, and a poll-driven import cannot
declare it at all. That is not a compatibility shim: a directory feed has no one to
review, so one shot is the only shape it can have.
