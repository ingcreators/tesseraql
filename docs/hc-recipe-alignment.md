# The hc 0.4.0 recipes and the view surface — an adoption ledger

> **Status: assessment recorded 2026-09-01; both small slices shipped and both
> campaigns designed the same day.**
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
> - **workflow-actions designed**: [workflow-surface.md](workflow-surface.md).
> - **reference-lookup designed**: [reference-lookup.md](reference-lookup.md).
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

## Recommended order

1. **result-cap slice** (small, one PR): the two status-code alignments, the mode A/B
   rendering, the silent `warn` truncation made visible, one gallery snapshot dogfood.
2. **unread-badge slice** (small, one PR): the accessible-name fragment.
3. **workflow-actions campaign**: design document first; the largest value.
4. **reference-lookup campaign**: design document first.
5. line-items and async-job wait for their named triggers.
