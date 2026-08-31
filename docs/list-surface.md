# The list surface — an operational grid page and composite row identity

Implementation design for the target state of `recipe: list`: the declarative list view
becomes an operational grid page of the quality a commercial SaaS back office ships, and
row identity — today a single-column assumption in half the surface — becomes a declared,
possibly composite key. Written 2026-08-31, before implementation, measured against main
at #1087.

The strategy is opt-in first, one form later. The new page frame ships behind a view-level
switch, proves itself on the bundled examples and a framework screen, and then becomes the
only list form — the default flips and the old card layout is deleted. Pre-1.0, the flip
is recorded, not migrated.

The kit's `data-grid-page` template (hypermedia-components 0.4.0) is the reference for the
screen composition, but this design does not adopt it verbatim. TesseraQL is
contract-first: every region of the page derives from something the contract declares, and
a region nothing declares does not render. That rule is what makes one template able to
serve both a five-row lookup list and a five-thousand-row order desk, and it is what makes
the eventual flip safe.

## What exists, measured

The declarative list view (`ViewSpec` kind `list`, `tql/view/list.html`) renders a card:
one free-text search box bound to the declared `search:` param, a table, and a pager of
plain anchors. Search and sort already region-swap the table (`hx-get` + `hx-select`); the
pager does not — every page click re-runs the whole route as a full navigation. The pager
carries only `sort`, `dir` and the `search:` param; every other query param is dropped
(`ViewBinding.pager`, which also strips the query string from the page path). Rows are
anonymous: the cell matrix carries pre-rendered hrefs but not the row's key values, there
is no row anchor, no selection affordance, and no row-level POST. Mutating routes answer
post/redirect/get to a literal `location:` template, so the search condition, sort and
page the user came from are lost on return — IAM Admin's bulk disable demonstrably drops
the `q` filter it was showing.

Row identity today, surveyed end to end:

| Surface | State |
| --- | --- |
| Route path variables | Composite-ready — N `{var}` segments parse, bind and type-coerce |
| View `link:` templates | Composite-ready — `{a}/{b}` interpolates per row; values are not URL-encoded (defect) |
| Lookups / enrich `on:` | Composite-ready — `JoinKeys` canonicalizes 1..N columns on one code path |
| Studio data browser | Composite, own encoding — `k0/v0..k2/v2` pairs, capped at three columns |
| `CrudScaffolder` | Single only — refuses composite keys outright (`TQL-APP-5203`) |
| Keyset `pagination.by` | Single column, scalar `after` cursor |
| Workflow document key | Single column (`DocumentSpec.key`); bulk posts one scalar per row |
| Bulk selection (`ids`) | Single scalar per checkbox (IAM Admin, ops console) |
| Chunk reader checkpoint | Single `key:` column |

So the routing and lookup layers already handle composite keys; the surfaces a business
app actually touches — scaffolding, keyset paging, selection, row anchors — do not, and
each has invented (or would invent) its own encoding. There is no shared row-token
primitive in core; the closest things are `JoinKeys` (canonical values, no round-trip) and
three unrelated base64url minters in studio-runtime, runtime and oauth.

## Where this design follows the kit, and where it diverges

Followed: the screen composition (fixed chrome, only the grid scrolls, chrome height is
O(1)), the region inventory (identity row, condition chips, toolbar, selection bar, grid,
pager), the fixed-height preconditions (app shell, breakpoint fallback, print sheet, paged
rows), the snapshot-pager contract for work queues (membership frozen, state live, opaque
server-minted row keys, tombstones), and the rule that a named view is a real URL.

Diverged, deliberately:

- **No filter operator algebra.** The kit's filter panel edits field-operator-value
  conditions. TesseraQL's condition language is the route's declared inputs plus the
  authored SQL; a param *is* a condition, a range is two params. Building an operator
  grammar would be a second query language on top of the contract.
- **No inline cell editing.** Forms remain the edit surface; the kit's
  `datagrid-edit-errors` / `datagrid-edit-conflict` recipes stay unadopted. Revisit with
  a use case, after the flip.
- **No per-user column chooser or width prefs.** Columns are contract-declared. Per-user
  preferences need a per-user store that does not exist yet; that store is its own design.
- **Named views are contract presets, not user-created saved views.** User-created views
  wait for the same per-user store.
- **No import affordance.** Export already has a pipeline and can occupy the toolbar via
  slots; import is not this design's problem.

## Decision 1 — one list template that scales down; `layout: page` now, the default later

The list `ViewSpec` gains `layout:` with values `card` (today's form, the default until
the flip) and `page` (the operational frame). The page frame is a single template whose
regions render only when declared: no `filters:` — no chips line; no `actions:` — no
selection column; no `views:` — no view menu; no `count: true` — the pager shows "Page N"
instead of "N–M of T". A minimal contract in the page frame renders essentially today's
card plus a sticky header, which is what makes eventual unification a default flip rather
than a rewrite. The frame satisfies the kit's fixed-height preconditions itself: it is
shell-aware (shell negotiation already exists), it degrades to page scrolling below the
breakpoint, and it carries the print stylesheet. All chrome strings stay in the
`tql.view.*` message keys. The pager anchors keep real hrefs and gain the same
table-region swap search and sort already use, with the URL pushed — every page state
remains a bookmarkable URL. The pager also stops dropping params: it carries the full
declared state (search, filters, sort, size).

## Decision 2 — row identity is declared once, minted as one opaque token

The list view declares its row key: `key: id` or `key: [order_id, line_no]`. A new core
primitive `RowTokens` (beside `JoinKeys`, sharing its value canonicalization) encodes the
key values of one row as a single opaque token — base64url, unpadded, over the canonical
value list — and decodes a token back into typed parts against a declared column list.
Tokens are not signed and not secrets: per the kit's contract they prove nothing, and
every consumer re-authorizes what it fetches. A null key component is a refusal at render
time, not a silent skip (Studio's data browser set this precedent). The token is the row's
machine identity everywhere: the `id="row-<token>"` anchor, the selection checkbox value,
the snapshot membership key, the keyset cursor. Human-facing URLs are exempt — see
decision 3. For a single-column key the decoded form is the bare scalar, so existing
single-key consumers (workflow bulk, IAM Admin's `ids`) accept it unchanged.

## Decision 3 — links stay human URLs, and substitution learns to encode

Row links remain authored path templates — `link: /orders/{order_id}/lines/{line_no}`
already interpolates today, and a browsable, guessable URL is worth more than an opaque
one. Two fixes ride along: interpolated values are URL-path-encoded (today a value
containing `/`, `?` or `#` breaks the href), and a new view lint requires link
placeholders to name real declared columns (today an unresolved placeholder silently
renders as empty string). The ejector's placeholder pattern and the runtime's diverge
(`{a.b}` works at runtime, ejects wrong); the lint closes that gap by refusing dotted
placeholders in `link:`.

## Decision 4 — the scaffolder learns composite keys

`TableSchema` and the introspectors already read the full key sequence; only
`CrudScaffolder` collapses it. The single-PK refusal (`TQL-APP-5203`) is lifted: detail
routes become nested path segments (`/{order_id}/{line_no}/get.yml` — the routing layer
already binds N path vars), WHERE predicates and-join the key columns, the update/delete
actions and edit-form `action:` templates carry every column, the unique-rule exclusion
binds the full key, and the search tiebreaker orders by all key columns in sequence. The
Studio scaffold gate (`primaryKey().size() == 1`) follows. No arity cap in the contract;
the data browser's three-slot cap is lifted in the same slice it stops being the only
composite precedent. Tables with no primary key remain unscaffoldable — that refusal is
correct.

## Decision 5 — keyset pagination goes composite

`pagination.by` accepts a list. The `next` cursor becomes a `RowTokens` token over the
`by:` columns' last-row values; the framework decodes an incoming `after` token and
publishes the typed parts for the authored SQL to bind (`params.after.<column>`), so the
author writes the tuple predicate — row-value syntax where the dialect has it, the
and/or expansion where it does not — exactly as they already write the scalar one. The
scalar form stays valid: a single-column `by:` binds `params.after` as today. Keyset still
has no Prev link; work queues that need stable walking use decision 10 instead.

## Decision 6 — filters are declared params rendered as chips and a dialog

The list view gains `filters:` — an ordered list of declared route input params, each with
an optional label. The frame renders them as the condition line (one removable chip per
applied param, showing label and value; a clear-all link) and a filter dialog (a GET form
whose fields derive from the route's `input:` types — dates get date inputs, domain-backed
params get selects, the rest get text). Removing a chip is a link to the current URL minus
that param. The chips line and dialog are pure URL manipulation; the route re-runs and the
authored SQL applies the condition, same as today. A lint requires every `filters:` entry
to name a declared route input. Relative date expressions (`@week-start`) are deferred
until a consumer exists — a chip shows the literal value for now.

## Decision 7 — the sort set is one param

Multi-sort serializes as `sort=-ship_date,order_no` — comma-separated columns, `-` prefix
for descending; `dir` retires (recorded, not migrated). Column headers keep single-column
toggle behavior (click replaces the primary sort); the toolbar's sort control edits the
full set and shows it (`Sort (2): Ship date ↓, Order no ↑`). The SQL side expands the sort
set into the order-by clause, validated against the view's `sortable: true` columns and
`SqlIdentifiers` — never raw interpolation. The exact directive surface that replaces the
`{sort} {dir}` pair in authored SQL is settled in the slice (open question 1).

## Decision 8 — named views are contract presets, and real URLs

The list view gains `views:` — named param presets (`Open orders: {status: open}`)
rendered as the view-selector menu beside the title. Selecting one navigates to the list
URL with that preset's params; the active preset is the one whose params are a subset of
the current URL, and a "Modified" badge shows when the current params differ from the
active preset. Reset is a link to the preset's own URL. No storage anywhere: a preset is a
link the contract declares, which is also why sharing one is just copying the address bar.

## Decision 9 — selection and bulk actions post row tokens

The list view gains `actions:` — each entry a label plus a POST route (optionally a
confirm gate). Declaring any action renders the selection column (checkboxes valued with
row tokens, named `ids`) and the selection bar that appears when rows are checked, per the
kit's `datagrid-bulk-actions` recipe. The action route receives repeated `ids` tokens; the
framework decodes them against the acting view's declared key before the pipeline runs, so
single-column keys arrive as the scalars today's endpoints expect. Failures render
bounded: a one-line status plus per-row detail in an overlay, never unbounded chrome.
Workflow bulk transitions are the first consumer — their endpoint already accepts repeated
keys, and single-column document keys mean tokens decode to exactly what it takes today.

## Decision 10 — snapshot paging is a pagination strategy

`pagination.strategy` gains `snapshot`, for work queues: membership frozen at search time,
row state live. It requires a declared `key:` and a `cap:` (default 500, hard reject —
over the cap the search answers 422 and tells the user to narrow, the kit's `result-cap`
contract). The search response renders every hit's row token as hidden `keys` fields
outside the swap target; the pager becomes POST buttons that resubmit the token list plus
`page`; the framework fetches the requested slice in received order through a declared
`keys` directive in the authored SQL, expanded per dialect as a values-with-ordinal join.
Rows that vanished since the search render as tombstones ("No longer in this queue"); rows
whose state changed render their current state — an approved row shows approved, and the
count line says "of N (as of search)". Every page fetch re-authorizes every key; the
tokens prove nothing. `refreshOn:` on a snapshot list refreshes row state, never
membership — the same axis the whole strategy is built on. Reload is a new search, by
design; snapshot pages have no URL and want none.

## Decision 11 — returning to the list is a framework guarantee

`location:` gains the sentinel `back`. A page-frame list embeds its own current URL —
app-base-relative, query included — as a `_return` hidden field in every row and bulk
action form it renders; `RedirectRenderer` resolves `back` to a validated `_return` (a
same-app relative path or the redirect is refused — no open-redirect surface), appends
`#row-<token>` when the acting row is known, and falls back to the route's list URL when
the field is absent. Combined with decision 1's pager state carry, "back to the list"
lands on the same conditions, same sort, same page, focused on the row the user left —
which is the baseline the whole page frame is judged against.

## Slices

1. **The page frame** — `layout: page`, the chrome regions, sticky header, pager range
   display, the pager's region swap + full state carry, the `link:` URL-encoding fix and
   placeholder lint. No new data surface; examples opt one list in.
2. **Row identity** — `key:` on the list view, `RowTokens` in core, row anchors,
   `location: back` + `_return` + refocus. Ships the decision 11 guarantee.
3. **Composite scaffolder** — decision 4, including the Studio gate and the data
   browser's three-slot cap.
4. **Composite keyset** — decision 5; docs gain the per-dialect tuple predicate patterns.
5. **Filters** — decision 6, chips + dialog + lint.
6. **Multi-sort** — decision 7, including the SQL directive change and scaffolder regen.
7. **Named views** — decision 8.
8. **Selection and bulk actions** — decision 9, workflow bulk as the proving consumer.
9. **Snapshot strategy** — decision 10. Depends on slices 2 and 8.
10. **The flip** — `layout:` default becomes `page`, the card template and the `layout:`
    switch are deleted, examples and scaffolder regenerate. Gated on the criteria below.

Slices 1–2 are the foundation and ship first; 3–4 are independent of the frame and can
interleave; 5–8 are the chrome filling in; 9 needs 2 and 8; 10 is last.

## The flip criteria

The default flips only when all of these hold, verified, not assumed:

- Every bundled example runs its lists on the page frame and its flows stay green.
- At least one framework screen (IAM Admin's user list or approval queue) has run on the
  frame — selection, bulk action, return-to-list included.
- Eject (L3), shell negotiation and `refreshOn:` all work against the page frame, with
  the ejector emitting the frame faithfully (today it drops search/sort/pagination; the
  frame's eject must not lose declared regions).
- The breakpoint fallback and print sheet are demonstrated, not just present.
- The detail-child and dashboard table fragments are confirmed untouched — the page frame
  is the list view's, never theirs.

## Guards

- View lints: link placeholders name declared columns and contain no dots; `filters:`
  entries name declared route inputs; `key:` present when `actions:` or
  `strategy: snapshot` is declared; keyset `by:` columns exist in the declared sort/column
  space where checkable.
- An ejector parity test per frame region: what the frame renders, the eject preserves.
- `RowTokens` round-trip property tests: unicode values, embedded delimiters, arity
  mismatch refusal, null component refusal.
- The two docs guards and `InternalDocsSyncTest` cover this document's registration.

## Test plan

Template render tests per region (declared → rendered, undeclared → absent). Scaffolder
composite matrix on the Testcontainers dialect suites (two- and three-column keys,
including a key column with unicode values). Composite keyset IT walking three pages and
proving insert-stability. Snapshot IT: search, approve on page 1, walk to page 2 — no row
slides, approved rows show approved, a concurrently deleted row tombstones, over-cap
answers 422. Navigation IT for decision 11: filter + sort + page 2, act on a row, land
back on page 2 with the filter intact and the row focused.

## Deliberately not in this design

- Inline cell editing and the edit-conflict/edit-errors recipes.
- Per-user preferences of any kind (saved views, column choice, widths) — blocked on a
  per-user store design that does not exist yet.
- A filter operator algebra or stored filter expressions.
- Composite workflow document keys — `DocumentSpec.key` stays single-column; the token
  layer already degrades to scalars for it.
- Import.
- Chunk reader (`ChunkSpec.key`) and export `splitBy:` composites — batch concerns,
  separate designs if ever needed.

## Open questions

1. The multi-sort SQL surface: what replaces the `{sort} {dir}` directive pair — a single
   framework-expanded order-by directive validated against sortable columns is the
   recommendation — *gates slice 6.*
2. Whether `layout: page` should be the scaffolder's output before the flip, so new apps
   soak the frame from day one. Recommended: yes, from slice 3 on — *settled by slice 3.*
3. Whether the snapshot `keys` directive belongs in the SQL surface
   (`/*# keys */`-style, framework-expanded) or as a framework-issued second statement.
   Recommended: the directive, keeping the authored statement the only statement —
   *gates slice 9.*
