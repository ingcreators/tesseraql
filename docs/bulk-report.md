# The bulk report — one failure surface for bulk actions and imports

> **Status: slice 1 (the snapshot leg) shipped as #1116, 2026-09-01.** Two adjacent
> fixes rode it: `ids` joined the binder's framework-owned fields (a pager press with
> rows checked used to read as mass assignment), and an expression guard's declared
> message now rides `details.message` like the SQL-guard form's. One recorded detail:
> the current page travels as the action buttons' own submit value, never a hidden
> input — a hidden `page` would pair with the pager buttons' and turn the scalar bind
> into a list. Slice 2 (the non-frozen strategies + the hypermedia-ui.md section) is
> open.
>
> **Design, written 2026-09-01, measured against main at #1114.** The
> datagrid-bulk-errors adopt from the catalog ledger
> ([hc-recipe-alignment.md](hc-recipe-alignment.md)), shaped in review: the report
> references rows by number **and** identity, links by row token, and bounds every
> reason group by construction — and the same report model is written to serve the
> csv-import validation report later, so the two surfaces cannot drift apart.

A bulk action that partly fails today reports nothing a browser user can see: the
workflow `_bulk` endpoint answers a per-key outcome report as JSON only, and
list-surface decision 9's line that "failures render bounded" is a design intent no
slice shipped. The upstream `datagrid-bulk-errors` contract names what the surface owes:
execution semantics stated per action, failures grouped by reason in a bounded region,
every named row linked and marked, and the retry set left selected. This design gives
TesseraQL that surface — as a declaration-derived rendering, in the house's no-JS-first
shape.

## What exists, and what is missing

The machinery half is done. `_bulk/<id>` runs the full transition pipeline once per key,
each in its own transaction, and answers `{requested, succeeded, failed, outcomes}` —
exactly the contract's **best-effort** branch, per key by construction. The grid page
already owns stable row identity (`key:` → RowTokens), row anchors (`#row-<token>` with
the `:target` highlight, decision 11), the selection form wrapping the grid, and — on
snapshot lists — membership frozen at search time as hidden `keys` inputs inside that
same form.

What is missing is everything the user sees: no HTML face renders the outcome report,
links the rows, marks the failures, or keeps the retry set selected. A browser posting a
bulk action to `_bulk` today receives raw JSON.

## Decision 1 — one report model, two consumers

`BulkReport` is a render model, not a store shape: totals (`requested`, `succeeded`,
`failed`) plus reason groups. A group is keyed by the outcome's error code, headed by
its resolved message (a workflow guard's declared refusal text, else the localized
status phrase), and holds row entries. A row entry carries a **reference label**, an
optional **anchor**, and the per-row detail the reason needs.

The bulk-actions consumer fills it from `_bulk`'s outcomes: reference = row number
(where authoritative, decision 4) plus the row's key values ("row 12 — PR-1003"),
anchor = `#row-<token>`. The csv-import consumer (recorded here, built when that
campaign is named) fills it from the validation pass: reference = the file line, anchor
= the preview table's line row, plus field and rejected value. Same fragment, same
bounds, different feeders — the display contract cannot fork.

## Decision 2 — bounded by construction, not by scrolling

The review's driving concern: one reason with two hundred rows must not eat the page.
Each reason group renders its count in the header, the first few row entries (a small
fixed cap), and an "…and N more" line for the rest. The region carries the upstream
contract's `max-block-size` scroll as a backstop, never as the design. Nothing is lost
by the cap: every failed row is also marked in the grid itself (decision 5), so the
grid is the full enumeration and the report is the summary that names reasons and
jumps to examples.

## Decision 3 — reference by number and identity, link by token

A report entry reads "row 12 — PR-1003: already approved". The row number locates; the
key values identify; the anchor — the existing `#row-<token>` — is what actually
navigates, because position is not identity: rows re-sort, pages turn, and a
positional link would drift exactly when the report matters most. The upstream
contract's stable-`id` rule and decision 11's anchors are the same rule, already
shipped; this design adds the human-readable half on top rather than replacing it.

## Decision 4 — row numbers, per pagination strategy

A list that declares `actions:` renders a leading row-number column, because its report
will speak in numbers. The number's meaning follows the strategy:

- **snapshot**: absolute position in the frozen membership — stable for the snapshot's
  whole life by construction, hedged like the count ("as of search"). The bulk route
  can number report entries authoritatively: the posted `keys` order *is* the
  membership order.
- **offset**: absolute (`offset + i`), honest but drift-prone as data changes.
- **keyset**: page-relative — keyset's whole point is not counting, so an absolute
  number would cost what the strategy saves.

Report entries carry a number only where it is authoritative at build time: snapshot
(from the posted keys) and csv-import (from the file). Elsewhere the entry stands on
identity, marks, and anchors alone — a wrong number is worse than none.

## Decision 5 — marks on the rows, severity from what the row needs

A failed row renders `data-attention="error"` with `aria-describedby` pointing at its
report entry, per the upstream severity rule: the mark states what the row requires,
not when it was discovered. A row whose transition was refused as stale
(`TQL-WORKFLOW-3201`) renders its **current** state — on a snapshot list that already
falls out of the machinery: row state is live, membership is frozen, and a row that
left the queue entirely is the existing tombstone.

## Decision 6 — the response is a redirect that keeps the frozen page

The browser leg must not lose the snapshot. A plain 303 to the list URL is a **new
search**: membership re-freezes, settled rows vanish instead of showing their new
state, and the numbering the report just cited is gone. The bulk form already carries
everything the acting page needs — the `keys` membership, sort, filters, `_csrf` — so
the answer is a **307 redirect to the list URL plus a report handle**: the browser
re-posts the intact form to the list route's existing snapshot POST leg, which renders
the same frozen membership, now carrying the report. The bulk action itself is not
re-run on refresh — a refresh re-posts the *page fetch*, which is idempotent, the
post/redirect/get property in method-preserving form.

Two supporting details. The selection region gains a hidden `page` input so the re-post
lands on the page the user acted from, not page 1. And non-frozen strategies (offset,
keyset) take the ordinary **303** to the validated `_return` with the same report
handle — their membership was never frozen, so a fresh GET is the honest re-render.

The report itself is server-held for the round trip: stored under a handle, scoped to
the acting principal, TTL-bounded, re-readable (a refresh re-reads it), and rendered
only for the subject who acted. An expired or foreign handle renders the plain list —
the report is a convenience of the moment, not a record; the durable record is workflow
history. The JSON contract is untouched: an API caller keeps today's outcomes envelope,
and the browser leg is detected the way the login endpoint detects it (a form-encoded
post).

## Decision 7 — best-effort is the declared semantics, atomic waits

TesseraQL bulk is best-effort per key by construction — one transaction per key is the
`_bulk` contract, and the report's copy says what happened ("17 succeeded, 3 failed").
The upstream contract's **atomic** branch (409/422, rows unchanged, selection preserved
mandatorily, the pre-flight) is out of scope until an invariant-shaped bulk action
exists; recording that refusal is part of the first slice's documentation. The retry
set stays selected in the best-effort shape too: failed rows re-render checked, so
pressing the action again applies to exactly the failures.

## Slices

1. **The snapshot leg end to end**: the `BulkReport` model and fragment, the `_bulk`
   browser leg (307 + handle, subject-scoped TTL store), the report region above the
   grid, `data-attention` marks with `aria-describedby`, the retry set re-checked, the
   row-number column on `actions:`-declaring snapshot lists, the hidden `page` carry.
   Proven the workflow-surface way: a browser-auth'd workflow plus a snapshot list in
   the integration-test app — gallery workflows stay bearer, their bulk face is the
   API's.
2. **The non-frozen strategies**: the 303 + handle leg, offset/keyset numbering rules,
   and the docs section in [hypermedia-ui.md](hypermedia-ui.md).
3. **csv-import consumes the fragment** — recorded as the future consumer with its
   campaign ([hc-recipe-alignment.md](hc-recipe-alignment.md)); not scheduled here.
