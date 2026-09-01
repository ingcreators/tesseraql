# The hc recipes and the view surface — an adoption ledger

> **Status: assessment recorded 2026-09-01; both small slices shipped and both
> campaigns designed the same day. Widened the same day to the full recipe
> catalog — see "The pre-0.4.0 catalog" below for the sweep of everything this
> ledger had never weighed.**
>
> - **result-cap shipped (#1103)**: mode B (the snapshot search's in-page reject, 200),
>   the 400→422 flip on the keys guard, mode A (the warn-truncation banner + "cap+"
>   count + the `truncated` context flag), the gallery snapshot dogfood. One recorded
>   deviation: mode A rides the `materialize: { maxRows, onOverflow: warn }` execution
>   bound — a declared `page.cap` on offset/keyset lists stays refused, because on an
>   unpaged list the bound IS the cap declaration and a paged offset list never
>   over-fetches.
> - **unread-badge shipped (#1104)**: the bell's `aria-label` is gone (it froze the
>   announced name); the name is subtree text — the shell's hidden localized
>   "Notifications" stem plus the fragment's hidden "(N)" — the badge is `aria-hidden`,
>   the count caps at 99+, and `sse-connect` stayed outside the swapped span. The
>   fragment is deliberately locale-free so one pushed payload serves every session.
> - **workflow-actions designed**: [workflow-surface.md](workflow-surface.md), and the
>   campaign shipped all three slices 2026-09-01 (#1106–#1108).
> - **reference-lookup designed**: [reference-lookup.md](reference-lookup.md), and the
>   campaign shipped all three slices 2026-09-01 (#1109–#1111): `lookup:` + the resolve
>   companion, the search dialog, and the purchase-request supplier dogfood. The
>   manifest guard now pins `installRemoteDialog`/`installCloseDialog` and the three
>   recipes — the Studio drift gap this ledger flagged is closed.
>
> hypermedia-components 0.4.0 shipped nine new recipe contracts (#1081 adopted the
> bump). Two were settled the same week: network-retry became the shell host (#1083)
> and idempotency-key became the form-recipe bridge campaign (#1084–#1087). This
> document weighs the remaining seven against what the framework actually renders,
> measured against main at #1101, and names the recommended order. Contracts cited live
> under `recipes/<name>/contract.md` in the hypermedia-components repository — they are
> not in the WebJar.

TesseraQL is contract-first: a recipe is adopted when a declaration renders it, not when
its markup is pasted somewhere. So each verdict below answers two questions. Does the
framework already own the machinery the recipe choreographs? And which declaration, if
any, should grow the rendering?

## The ledger

| Recipe | Verdict | Anchor |
| --- | --- | --- |
| datagrid-snapshot-pager | **Adopted** (#1098), deviations recorded below | `strategy: snapshot` |
| result-cap | **Adopt — recommended first slice** | list views, `page:` |
| unread-badge | **Align — small accessibility slice** | shell inbox bell |
| workflow-actions | **Adopt — its own design campaign** | `workflow:` + detail views |
| reference-lookup | **Adopt — its own design campaign** | `input:` fields |
| line-items | **Defer**, trigger named | `items.fields:` |
| async-job | **Defer**, trigger named | file transfers |

## Already settled

**datagrid-snapshot-pager** is the upstream statement of what `strategy: snapshot`
shipped as list-surface slice 9 (#1098): membership tokens frozen at search time, hidden
`keys` inputs outside the swapped region, submit-button paging, tombstones for vanished
rows, current state rendered live, and the "(as of search)" count. Three deviations are
deliberate and stay. The pager is a whole-document POST rather than a fragment swap with
out-of-band status — the view surface renders no out-of-band fragment anywhere, and the
native submit is the contract's own no-JS baseline. Page rows are reordered app-side by
token, the contract's blessed variant, instead of a per-dialect ordinal join. Composite
keys stop at the snapshot boundary (the tuple-IN follow-up recorded in
[list-surface.md](list-surface.md)). Two small misalignments move to the result-cap slice
below, where they belong: the status codes on the two over-cap paths are each the
opposite of the contract's.

**network-retry** (#1083) and **idempotency-key** (#1084–#1087,
[idempotency-key.md](idempotency-key.md)) are done and not re-argued here.

## result-cap — adopt, recommended first

The contract: bound what one search may return via cap+1 detection; over the cap is
**always 200** — a user state, not an error. Lookup screens truncate with a persistent
`hc-alert` banner that names the sort order (mode A); process-everything queues render no
rows and an `hc-empty` reject asking the user to narrow (mode B). The companion rule for
snapshot pagers: a posted `keys` list longer than the cap is a broken or hostile client
and gets **422**.

What the framework has today is the inverse, plus a silence:

- The snapshot **search** over the cap throws `TQL-FIELD-4222`, rendering the app's 422
  error page — an error page for what the contract calls a user state, and the user
  loses the list screen entirely.
- The snapshot **page fetch** with too many posted keys rejects as `TQL-FIELD-2001`,
  a 400 — the one place the contract does want 422.
- Non-snapshot list views have no cap surface at all: no banner, no "cap+" count.
  `maxRows` with `onOverflow: warn` truncates the result **silently to the user** — the
  log warns, the page pretends the truncated set is everything. That is the
  silent-tolerance shape this codebase has swept twice.

Recommended slice (one PR): over-cap on a snapshot search renders mode B in-page — the
`hc-empty` reject block where the table would be, HTTP 200, count withheld; the page-fetch
keys guard flips 400 → 422; and a capped non-snapshot list (a declared `page.cap`, or
`onOverflow: warn` firing on a list view) renders the mode A banner with the "cap+"
count, naming the declared sort. The banner belongs in the list model
(`ViewBinding.listModel`), not the template alone, so the JSON surface can carry the same
truncation flag. Route-compiler behavior changes ride this slice, so old-contract tests
(`SnapshotPagingIntegrationTest` asserts the 422) get swept in the same PR.

Also recorded: no gallery app declares `strategy: snapshot` — the slice should convert
one queue-shaped gallery list so the reject block has a dogfood.

## unread-badge — align, small

The contract's core rule: the count and the accessible name change together, so the swap
unit is the nav item carrying `aria-label="Notifications, 3 unread"`, with the visual
badge `aria-hidden`. Zero renders silence. Corrections after the user's own read actions
must not wait for the next tick.

The shell bell (`tql/shell.html`, `InboxBadge`) got two of these right — zero renders
nothing, and SSE push corrects the badge on every mutation faster than the contract's
out-of-band rule requires. But the swap unit is the inner `<span>`, and the anchor pins a
**static** `aria-label="Notifications"`. An `aria-label` overrides the subtree, so the
count is never announced to a screen reader in any state — the exact defect the
change-together rule exists to prevent.

Recommended slice (small): the SSE fragment grows to carry the badge **and** a visually
hidden count line, and the anchor drops the static label in favor of visible text plus
the fragment-owned name. The `sse-connect` attribute stays on the anchor, which is why
the swap unit cannot naively become the anchor itself — swapping the connecting element
would tear down and reopen the event stream on every update. The fragment contract is
what must hold, not the literal element boundary. Polling is deliberately not added; the
SSE variant is the contract's own alternative and the htmx extension reconnects.

## workflow-actions — adopt, as its own campaign

The contract: the server renders only the legal transitions for this user on this
version; wrong-role actions are absent, wrong-state actions render disabled with the
reason; an `hc-stepper` shows the lifecycle position; 409 re-renders current truth;
comment-required transitions 422 with the field rendered only then.

The framework owns everything below the markup and renders none of it. `workflow:`
declares transitions, guards, per-transition security, join stamps, and bulk; the
executor's state-as-lock already yields exactly the contract's 409 semantics —
`TQL-WORKFLOW-3201` fires on stale and illegal alike, keyed on the state column rather
than a `version` field, which satisfies the same invariant for state transitions. What is
missing is the entire surface: detail views render fields and children only (`ViewSpec`
rejects `actions:` off list views), `hc-stepper` appears nowhere outside the Studio
wizard, and `WorkflowTaskStore` has no "my tasks" page — the upstream contract's note
that the queue side is the snapshot pager describes a pairing this framework is one half
short of.

This is the sharpest incompleteness the ledger found: a shipped engine whose 409s,
guards, and per-transition security have no rendered face. Recommended as the next
design-first campaign: a transitions region on detail views derived from the `workflow:`
declaration (legality evaluated per principal at render), the stepper from the state
graph, comment-on-demand as the contract's 422 shape, and a task-queue preset that pairs
the list surface's snapshot strategy with `WorkflowTaskStore`. It needs its own design
document; this ledger only fixes its place in line.

## reference-lookup — adopt, as its own campaign

The contract: a master-reference field is a visible code input plus a hidden opaque id,
resolving on change (200 resolved / 422 unresolved with the id **cleared** / 200
cleared), with a search dialog for users who don't know the code, and re-validation at
submit regardless.

The framework covers only the small-master case: `codes:` renders a plain `<select>` of
active catalog codes, validated server-side. There is no code+id pair, no resolve
endpoint, no dialog; `KeyedReference` enrichment is read-side folding, and remote-dialog
markup exists only in Studio authoring surfaces. Business forms above a few hundred
master rows have no blessed answer today.

Recommended as the second feature campaign, after workflow-actions: a `lookup:` shape on
an input field naming a query route as its search source, compiling to the field markup,
a synthesized resolve endpoint, and the dialog. The two-fields-one-truth rule (an
unresolved code must clear the hidden id) and the token rule (ids are opaque, composite
keys fold) are both already house positions — RowTokens is the same rule on the list
surface. Deliberately out: multi-select references (upstream routes them to the transfer
recipe, which needs a membership-editing feature first — recorded in
[hypermedia-ui.md](hypermedia-ui.md) since 0.1.9).

## line-items — defer, with the trigger named

The contract: N rows in one form, positionally aligned repeated names, every mutation the
same whole-form round trip, the server owning all arithmetic, 422 echoing raw values with
totals rendered as "—".

The binding half already exists and matches: `items.fields:` (#1062) binds, coerces, and
validates array elements, and addresses violations as `lines[2].qty` — precisely the
field-error shape the recipe's 422 needs. What does not exist is the widget:
`ViewSpec.WIDGETS` has no repeater, and no gallery HTML view edits lines (procurement
adds lines one at a time through API routes). Building the widget now would repeat the
step-fragments mistake (#1067, reverted): a whole new form capability with no dogfood
demanding it. **Trigger: the first gallery or scaffolded HTML view that needs multi-row
editing** — at which point the widget is mostly template work over machinery that already
speaks the contract. Two contract details worth recording now so they aren't relearned:
never name the remove button `remove` (named controls shadow the form's DOM API and break
htmx's outerHTML swap), and array inputs keep single-value list-ness (the list-surface
campaign's trap).

## async-job — defer, with the trigger named

The contract: 202 plus a self-polling job card whose polling attributes travel with the
fragment; terminal cards carry no trigger; expired ids are 200 tombstones; the server
owns the cadence.

The transfer machinery already matches the endpoint table almost line for line: async
file routes return 202 with `{transferId, statusUrl, fileUrl}`, status is a GET, the
artifact is a separate idempotent GET. But that contract is JSON-only — no HTML surface
kicks off an async transfer, and the two gallery exports are synchronous `query-export`
downloads. The ops console polls page regions on a fixed 15s cadence, which is a live
dashboard's correct shape, not this recipe's. **Trigger: the first HTML async kick-off —
most plausibly the list surface growing an "export this filtered set" action**, which is
where the card, the terminal-stop rule, and the tombstone become user-visible. Until
then there is nothing for the card to render.

## The pre-0.4.0 catalog — the recipes this ledger never weighed

The assessment above weighed the nine contracts hc 0.4.0 added, because the bump
carried them. That framing hid a hole: recipes live in the hypermedia-components
repository, not in the WebJar, so the house rule for verifying a bump — diff the two
WebJARs, never the release notes — is structurally blind to recipe additions. hc 0.2.1
(#1076) added eleven recipe contracts and hc 0.3.0 (#1079) added eight more, and no
assessment pass ever ran over either batch. Three escaped by luck: `datagrid-sort` was
absorbed by [hc-briefs.md](hc-briefs.md) and list-surface slice 6, and the
`datagrid-edit-errors` / `datagrid-edit-conflict` pair is recorded as unadopted in
[list-surface.md](list-surface.md). The rest were never looked at.

This section closes the hole: every recipe in the catalog that the ledger above does
not already cover, swept 2026-09-01 against main at #1111. The verdict vocabulary is
the same, plus two new ones. **Aligned** means the framework renders the contract's
substance already, and this row is the record tying the two names together.
**Deviation** means the framework deliberately renders a different shape for the same
job, and the row records why.

| Recipe | Verdict | Anchor |
| --- | --- | --- |
| session-expiry | **Adopt — recommended first slice** | shell + browser auth |
| unsaved-changes | **Adopt — small slice** | form views |
| datagrid-bulk-errors | **Adopt — the bulk HTML face** | `actions:`, `_bulk` routes |
| edit-conflict | **Adopt — needs its own design slice** | `update` command routes |
| csv-import | **Adopt — its own campaign, when named** | file transfers |
| row-detail | Aligned (list-surface decision 11) | `location: back`, `#row-<token>` |
| datagrid-pager | Aligned (list-surface slice 1) | the in-place pager |
| datagrid-sort | Aligned (list-surface slice 6, hc-briefs) | `type: sort` |
| data-region | Aligned (live regions, `refreshOn:`) | [hypermedia-ui.md](hypermedia-ui.md) |
| datagrid-filter | Deviation — chips + dialog, recorded below | `filters:` |
| saved-views | Deviation — stateless presets, recorded below | `presets:` |
| filter-popover | Deviation — rides the `filters:` dialog | `filters:` |
| datagrid-columns | Defer — first column-chooser demand | grid page |
| datagrid-prefs | Defer — needs a per-user preference store | account app precedent |
| datagrid-infinite | Defer — paged back office, no feed consumer | — |
| datagrid-tree / lazy-tree | Defer — first hierarchical view | — |
| inline-edit + datagrid-edit-* | Defer — recorded in [list-surface.md](list-surface.md) | inline cell editing |
| conditional-fields | Defer — first mode-dependent form | `input:` fields |
| cascading-select | Defer — first hierarchical catalog | `codes:` |
| postal-address | Defer — first address-entry form | gallery forms |
| autosave | Defer — needs a draft store | pairs with unsaved-changes |
| undo-delete | Defer — needs soft delete + grace period | delete routes |
| sortable | Defer — first user-ordered list | display-order column |
| multi-step-form | Defer — first multi-step business form | Studio wizard is hand-built |
| file-upload | Defer — first HTML upload surface | csv-import names it |
| lazy-panel | Defer — first deferred-region demand | — |
| sse-toast | Defer — the inbox + badge is the chosen push surface | — |
| transfer | Defer — recorded in [reference-lookup.md](reference-lookup.md) | multi-select references |
| request-action, mutating-form, confirm-action, copy, live-search, toast, remote-dialog, field-errors, chart, datagrid-bulk-actions | Adopted | [hypermedia-ui.md](hypermedia-ui.md) |
| chat-messages, streaming-response | Adopted verbatim | [copilot.md](copilot.md) |
| sse-updates | Adopted | [realtime.md](realtime.md), inbox badge |

## session-expiry — adopt, recommended first

The contract: an expired session must not cost the user their work. A protected
endpoint hit by an htmx request answers 401 with a login `<dialog>` retargeted at the
shared `data-hc-remote-dialog-root` host — refusing **before acting**, which is what
makes replay safe. A successful re-login answers 200 with `HX-Trigger:
hc:sessionrenewed`; the kit's `installSessionExpiry` bridge then closes the dialog and
replays the interrupted request. A failed login 422s the same dialog with field errors
inline. Non-htmx requests keep the classic 303-to-login fallback.

What the framework has today is only the fallback: opening a protected page without a
session redirects to `/_tesseraql/login?redirect=…`
([authentication.md](authentication.md)), which is correct for a full-page GET and
wrong for everything htmx. A fragment action on an expired session gets the redirect
followed by htmx, and the login page's markup lands inside whatever region the action
targeted — the user's form state is stranded around a broken swap. The shell already
carries the dialog host (`tql/shell.html`, from reference-lookup slice 2), and the
beforeSwap allowance in `tesseraql.js` is substring-gated per fragment kind, so the
401 dialog rides the same rule with its own marker.

Recommended slice (one PR): browser-auth'd pipelines answer htmx requests (the
`HX-Request` header) with the contract's 401 dialog fragment instead of the 302; the
login dialog posts to the session endpoint and answers the 200/422 shapes; the
allowance gains the dialog's marker. One deviation to design in the open: a new
session may rotate the CSRF token, and the replayed mutation must not fail on the
stale page token — the login success response has to hand the fresh token to the page.

## unsaved-changes — adopt, small

The contract is client-only: `data-hc-dirty-guard` on a form arms the kit's
auto-installed guard — baseline snapshot on first focus, `data-dirty` toggling with
`hc:dirtychange`, the browser's own tab-close prompt, a confirm on boosted navigation,
clean-on-save. No endpoint changes; the save stays the mutating-form contract. Without
JavaScript nothing guards and nothing breaks.

Recommended slice (small): the declarative form view's `<form>` gains the attribute,
scaffolded edit forms follow, and a visible unsaved badge styles off `data-dirty`.
The one rule worth recording now: a request from inside the form that is not the
form's own save deliberately does not clean the state — a draft is not the record,
which is the same line the autosave recipe draws.

## datagrid-bulk-errors — adopt, the bulk HTML face

The contract builds on datagrid-bulk-actions: execution semantics named per action
(best-effort vs atomic), failures reported grouped by reason in a bounded
`aria-live` region above the grid, every named row linked and marked
`data-attention` with the reason on the row, the retry set left selected. The atomic
branch refuses with 409/422, changes no row state, and must preserve the selection.

The framework is halfway there by construction and silent at the surface. The
workflow `_bulk` endpoint already runs each key in its own transaction and answers a
per-key outcome report — exactly the best-effort branch — but as JSON only; no HTML
face renders the report, links the rows, or keeps the retry set selected.
List-surface decision 9's line that failures render bounded is a design intent no
slice has shipped. Recommended as one slice once its consumer is live: the bulk
report region on the grid page, fed by the outcome report the endpoint already
produces. The atomic branch stays out of scope until an invariant-shaped bulk action
exists; recording that TesseraQL bulk is best-effort per key is part of the slice.

## edit-conflict — adopt, needs its own design slice

The contract: optimistic locking for edit forms — a hidden `version` rides every
save, a stale save answers 409 with a conflict dialog (theirs/yours diff, overwrite
with the fresh version, reload, keep editing), and the no-JS branch is a full 409
page offering the same choices.

The framework's only optimistic lock today is the workflow engine's state-as-lock,
which covers transitions and nothing else. A plain `update` command route rendered as
an edit form is last-write-wins: two operators editing the same record silently lose
one operator's work — the same silent-tolerance shape this codebase has swept twice,
now on the write path. Adopting needs a declared version column on the route (which
column, how it bumps, what the 409 renders), so this is a design-first slice, not a
template patch. The shared dialog host and the 409 allowance arrive with the
session-expiry slice, which is one reason it goes first.

## csv-import — adopt, as its own campaign when named

The contract: bulk CSV in two phases — upload parses and validates without importing,
the response is a validation report (summary, real error table, tokened confirm
form), and committing the token executes exactly what was validated.

The transfer machinery already speaks the hard half: import routes, validation, the
async endpoints. What is missing is the entire HTML surface, which is the same
one-sentence verdict async-job and file-upload carry — and that is the point: a
csv-import campaign is the named trigger for all three at once. The first HTML
upload surface is csv-import's upload leg; the first HTML async kick-off is its
commit leg on a large file. Deliberately not scheduled here: it is a feature
campaign with a design document, and the move stays the user's to name.

## The deviations, recorded

**datagrid-filter / filter-popover**: the upstream shape is per-column filter entry —
a popover off each header cell's filter button, `f-<col>` params, the trigger marked
`data-filtered`. The framework's `filters:` (list-surface slice 5) renders one
filter dialog plus an applied-chips bar instead: filters are declared route inputs,
not per-column affordances, and the chips make the applied set visible where column
glyphs would hide it. Same job, different shape, deliberate — revisit only if a
wide-grid consumer demands column-scoped entry.

**saved-views**: upstream saves the current querystring under a user-chosen name and
re-renders a server-owned views strip. The framework's `presets:` (list-surface
slice 7) are declared links — shareable by URL, storage-free, and visible in the
contract. The stateless choice is deliberate; user-saved views wait for the first
per-user-preference store demand, the same trigger `datagrid-prefs` waits on.

## Recommended order

1. ~~result-cap slice~~ — shipped (#1103).
2. ~~unread-badge slice~~ — shipped (#1104).
3. ~~workflow-actions campaign~~ — designed and shipped (#1105–#1108).
4. ~~reference-lookup campaign~~ — designed and shipped (#1105, #1109–#1111).
5. **session-expiry slice** (small, one PR): the 401 dialog + replay; brings the
   shared error-dialog host into full use and the 401 allowance with it.
6. **unsaved-changes slice** (small, one PR): the dirty guard on form views.
7. **datagrid-bulk-errors slice**: the bulk report region, once its consumer is live.
8. **edit-conflict**: design-first slice — the declared version column.
9. **csv-import campaign**: design document first, when the user names it; it is the
   trigger that unlocks file-upload and async-job.
10. line-items and the deferred catalog rows wait for their named triggers.
