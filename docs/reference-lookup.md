# The reference lookup field — code entry, one truth, and the search dialog

> **Status: slices 1 (direct entry) and 2 (the search dialog) shipped 2026-09-01.**
> Slice 2's recorded deviations: the shell did not yet provide the
> `data-hc-remote-dialog-root` host decision 4 assumed — the slice adds it to
> `tql/shell.html` beside the network-retry and toast hosts (Studio's pages keep their
> own). The dialog list is capped at the dialog's own 50 rows with the "{cap}+ results"
> notice, not the referenced route's read bound — a dialog is a picker, and rendering a
> route's 10k-row bound into a `<dialog>` serves nobody; the route's bound still applies
> above it. The search input is the referenced route's `q` when declared, else its first
> string-typed input, else the dialog renders no search form. Row picks call the resolve
> companion with `?<field>=<id>` — the recorded no-`pick`-endpoint deviation, now
> exercised. Inactive-but-visible rows stay deferred as designed.
>
> Slice 1's recorded deviations. The 🔍 button shipped with its dialog in slice 2 — a
> button whose target route does not exist yet is worse than none. The submit-time existence check
> answers the validation stage's **422** (the `TQL-FIELD-4220` shape, field code
> `invalid-reference`), not the binder's 400: `InputBinder` holds no connection, so the
> check runs beside the validation rules on the command's own connection — the
> currency decision 3 already named. "A route whose rows lack the named columns fails
> the build" became the render-time refusal `TQL-VIEW-3329`, the `TQL-VIEW-3328`
> precedent: `select *` makes a static column check a liar; the build still fails on a
> dangling or incomplete declaration (`TQL-FIELD-4623`, lint `TQL-YAML-1059`). Open
> question 1 is decided as the design suspected — re-render the SQL, not the pipeline:
> the framework wraps the rendered SQL in a key-equality derived table
> (`select * from (…) tql_lookup where <col> = ?`), stripping a trailing `ORDER BY`
> (SQL Server refuses an ordered derived table), so scopes and 2-way arms apply
> unchanged. Companions synthesize only where a form view targets the action route; the
> submit-time check applies regardless. A prefilled id (an edit form) self-resolves on
> load through the same fragment keyed by id — the no-pick-endpoint deviation extended
> to prefill. The visible code input rides the form post beside the hidden id and the
> binder drops it as declared presentation. Open question 3: tenant routing and the
> scope resolver are threaded through the fetch (the `SqlStep` seams); the IT proves
> the referenced route's `security:` on the companion, not a per-tenant master.
>
> **Original design below.** The reference-lookup campaign from
> [hc-recipe-alignment.md](hc-recipe-alignment.md): business forms above a few hundred
> master rows have no blessed answer — `codes:` renders a `<select>` and stops. This
> design gives the form surface the master-reference field: direct code entry for users
> who know the code, a search dialog for those who don't, and one truth for what the
> field holds. Measured against main at #1104; the upstream contract is
> `recipes/reference-lookup/contract.md` in the hypermedia-components repository.

The contract's core rule — **two fields, one truth**: the visible code input is what
users type, the hidden id is what the form submits, the display name is presentation.
An unresolved code means an empty id (the classic defect is a stale id riding under a
corrected code), and the consuming endpoint re-validates on submit anyway, because a
client-supplied id proves nothing.

## What exists

- `codes:` covers the small-master case end to end: a `catalogs/*.yml` catalog renders
  as a `<select>` (`ViewFields.defaultWidget`), and `InputBinder` validates the
  submitted key against the active set, reloading once on a miss. docs/lookups.md
  decision 10 draws the line this design crosses: catalog-or-enrichment is a question
  of size, and above it the select stops being an interface.
- `enrich:` (`KeyedReference`) folds reference data into read rows — the read side of
  the same masters, deliberately not a form feature.
- The kit ships the whole client half in 0.4.0: `installRemoteDialog` and
  `installCloseDialog` are auto-init behaviors, `hc-dialog`/`hc-field__hint`/
  `hc-field__message` are in the CSS, and the `reference-lookup`, `remote-dialog` and
  `live-search` recipes name the composition. None of it is pinned by the manifest
  guard yet — Studio already depends on remote-dialog unpinned, a drift gap this
  campaign closes in passing.
- Synthesized companion routes are established practice: attachments compile one
  document into three routes; workflows synthesize transition, bulk, and delegate
  routes off one basePath. The form surface has no companion routes yet.
- The generated field pattern (`tql/view/field.html`) renders no hint or message
  markup today; errors land in the form-top alert and `installFieldErrors`
  redistributes them. The lookup field is the first widget that owns a hint line.

## Decision 1 — `lookup:` is an input-field key naming a query route

```yaml
input:
  customer_id:
    type: string
    lookup:
      source: /api/customers/search
      code: customer_code
      label: name
```

`lookup:` is a new `InputField` record component (automatically legal in `domains/`
documents, where a master reference belongs — declare it once, reference it
everywhere). `source:` names a **GET query route by literal URL path** — the same
resolution the form's `action:` already uses for POST routes, gaining the missing
`getRouteByPath` analogue. The referenced route is an ordinary route: its own
`security:`, its own SQL, its own `input:`. The framework never writes the search
query; the route's author owns what "searching customers" means, which columns match,
and what the row set excludes.

The route's contract: its SQL must select the declared field's column (the id),
`code:`, and `label:`; it should declare a search input (`q`, or whatever its SQL
binds) for the dialog leg. A `lookup:` naming no GET route, or a route whose rows lack
the named columns, fails the build.

## Decision 2 — the field renders as code + hidden id, resolved by a companion route

`ViewFields` maps a `lookup:` input to a new `lookup` widget (joining
`ViewSpec.WIDGETS`): the visible code input, the hidden id input (the declared field
name — what submits), the 🔍 button (`type="button"`, `aria-haspopup="dialog"`), and
the `hc-field__hint` line carrying the resolved display name. Marked `data-hc-lookup`
per the contract.

The compiler synthesizes one companion route per form with lookup fields:
`GET <form action path>/_lookup/<field>?<code>=…` — the resolve endpoint. It runs the
**referenced route's** pipeline (its security, its SQL) filtered to code equality, and
renders the whole field fragment back:

- exactly one row → **200 resolved**: hint = label, hidden id = the key, the code
  echoed in canonical form;
- zero rows (or more than one — an ambiguous code is not a resolution) →
  **422 unresolved**: `aria-invalid`, the message in the field-errors shape, **hidden
  id emptied** — the two-fields-one-truth rule's teeth;
- empty code → **200 cleared**: empty id, hint emptied. Required-ness stays the submit
  endpoint's business.

The code input carries `hx-get` on change targeting the whole field with `outerHTML` —
the field re-renders as a unit, so code, id, and hint can never disagree.

## Decision 3 — submit-time truth is the `codes:` precedent, automatic

A `lookup:` field validates at bind time exactly as `codes:` does: `InputBinder`
resolves the submitted id against the source (one keyed row fetch through the
referenced route's SQL), and a miss rejects with the standard field error. The
upstream contract leaves re-validation to "the consuming endpoint anyway"; this
framework's stance is that a declared reference validates itself — an author should
not be able to forget the existence check any more than they can forget a `codes:`
check. The fetch is one indexed row by key inside the command's own connection, the
same currency a validation SQL rule spends.

## Decision 4 — the dialog is the kit's composition, synthesized

The 🔍 button loads `GET <form action path>/_lookup/<field>/dialog` into the page's
`data-hc-remote-dialog-root` host (the shell provides one). The synthesized dialog is
the remote-dialog + live-search composition: a search form (opting out of
close-on-success, per the contract — the first debounced 200 must not close the
dialog) posting the referenced route's declared search input, and a result list where
**each row is a button** rendering label + code, whose click re-renders the field
resolved (the same fragment as a resolved code entry) and closes the dialog
(`data-hc-close-dialog-on-success`).

Result rows come from the referenced route's rows, capped by that route's own bounds —
and the dialog should say when results are capped, which is the result-cap banner this
campaign already shipped for lists. Authorization applies per the referenced route's
own `security:` on every request; the dialog never lists masters the user may not
reference. Inactive-but-visible rows (the contract's aria-disabled refusals) need a
convention the source SQL owns; deferred until a gallery master needs it.

## Recorded deviations

- No `pick?id=` endpoint: picking a row re-renders the field through the same resolve
  fragment, keyed by id instead of code — one fragment, not two.
- Free-text-plus-id (nullable id) is out of scope for slice 1-3; the contract allows
  it and nothing here forecloses it.
- Multi-select references stay `transfer`-recipe territory, unadopted (recorded in
  hypermedia-ui.md since 0.1.9).

## Slices

1. **Direct entry end to end**: `lookup:` on `InputField` (+ schema + `.vscode` copies
   + reference regen), the `lookup` widget in `ViewFields`/`field.html`, the resolve
   companion route, bind-time existence validation, manifest-guard pins for
   `installRemoteDialog`/`installCloseDialog` and the three recipes (closing the
   Studio drift gap), lint for a dangling `source:`.
2. **The search dialog**: the dialog + results companion fragments, the live-search
   composition, close-on-success wiring, capped-results notice.
3. **Gallery + domain packaging**: a real master in a gallery app (procurement's
   suppliers or items are the natural fit), a `domains/` document carrying the
   `lookup:`, docs (`declarative-views.md` form section + `lookups.md` cross-link),
   and the hand-written docs sweep.

## Open questions

- Whether the resolve companion reuses the referenced route's compiled pipeline or
  re-renders its SQL with an equality arm — slice 1 decides against the code (the
  pipeline reuse is cleaner but the route's own page/response shape gets in the way).
- Whether `source:` should also accept a route id (the view surface resolves views by
  id, routes by path — path chosen here for consistency with `action:`, but ids are
  the more rename-stable currency; revisit at 1.0 alongside the schema freeze).
- Per-tenant masters: the referenced route's scope/tenancy governance already applies
  (it is an ordinary route), so nothing extra is designed — verify in slice 1's IT.
