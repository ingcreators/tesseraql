# Studio UX refresh

> **Status: complete.** All slices (0–7) landed 2026-08-06 (#575–#578 slices 0–3, #579
> slice 4, #580 slice 5, #581 slice 6, slice 7 = the command palette PR). The one open
> deferral is the `data-neutral="slate"` dark-ramp evaluation, which needs a screenshot A/B.
> Originally: a full-surface UX review of Studio (all 61 templates under `tesseraql-studio`,
> the shared shell, and `tesseraql.css`) against Hypermedia Components 0.1.11, and the slice
> plan that follows from it. This is an internal design document (docs-site `EXCLUDED`).

## Motivation

Studio's information architecture is sound — the four job-based sidebar groups, the
drawer-based create flows, and the htmx contracts on the pages that have them are all worth
keeping. But the surfaces predate most of the kit's structural vocabulary, and a
template-by-template review found three classes of problems:

1. **Correctness bugs.** Three destructive buttons whose `data-hc-confirm` dialog confirms
   into nothing (the kit's confirm behavior re-emits `hc:confirmed` for htmx; a native form
   submit is never re-dispatched), and four class names used in ~40 places that do not exist
   in the kit in any released version (`hc-disclosure`, `hc-empty__body`, `hc-field--grow`,
   `hc-badge[data-size]`), plus invalid variant values (`hc-badge` `neutral`, `hc-button`
   `success`). These have never rendered; they violate AGENTS.md rule 11 (emitted hc markup
   is a contract — no invented markup).
2. **Missing system plumbing.** No `data-hc-toast-region` in the shell and zero uses of the
   kit's field-errors contract, so the highest-stakes mutations (security policy rules,
   config saves, flag toggles) complete silently. Bare `<a>` elements are unstyled by the
   kit by design, and the app never styles them either — in dark mode body links render in
   browser-default blue/visited-purple on `#1f2937`, the single worst legibility failure.
3. **Divergent local dialects.** ~90 cards, none using `hc-card__header/body/footer`; four
   empty-state implementations; three label-markup patterns; two pagination systems; three
   table systems; badge colors that mean different things on different pages (`error` is a
   lint error, an unprotected route — or a calendar holiday). Governance reads as three
   separately-authored products behind one shell.

## Findings index

The complete review (file:line evidence for every finding, verified against the kit's
shipped CSS/JS) is recorded in the PR that introduced this document. The headline items each
slice addresses are listed with the slice.

## Design decisions

### Semantic color vocabulary

One badge/chip vocabulary, applied everywhere; `error`/`warning` mean problems, never
neutral facts:

- HTTP methods: `GET`=info, `POST`=success, `PUT`/`PATCH`=warning, `DELETE`=error, rendered
  as fixed-width chips so route lists column-align.
- Diff kinds: `ADDED`=success, `REMOVED`=error, `CHANGED`=warning (today export colors them
  and release-diff renders them variantless).
- Coverage percentages: one scale (success at/above threshold, warning below, error on gate
  failure) — today info-blue in one card and success/warning in the next.
- Calendar day facts (holiday, weekend, nominal) are neutral chips, not `error`.

### Shared shell fragments

`tql/shell.html` gains fragments that kill the copy-paste dialects:

- `page-header(back, actions)` — replaces the verbatim back-link block duplicated across
  seven Documentation pages (with its inconsistent trailing slashes).
- `empty(title, description, action)` — the one empty-state recipe (`hc-empty` +
  `__description` + `__actions`), replacing prose fallbacks, `colspan` rows, and silent
  card omission. The action that fixes the state is a button in the empty state, not a
  sentence link to another page.
- A `data-hc-toast-region` in the shell body, so mutations can confirm without a full-page
  flash round-trip, plus the flash-alert pattern for redirect-based POSTs.

### Form standards

- One label pattern: `div.hc-field > label.hc-field__label[for] + control`.
- Required fields are marked; "(optional)" suffixes are dropped.
- Help text is `hc-field__message`, never placeholder-only.
- The kit field-errors contract (`data-hc-field-errors`, per `docs/hypermedia-ui.md`) is
  wired on authoring forms.
- Destructive actions always carry `data-variant="error"` and a confirm; confirm strength
  scales with blast radius (today removing a live flag is unconfirmed while removing an
  egress host is confirmed — and broken).

### Guard rails (tests)

- **Template/kit drift test:** collect every `hc-*` class and `data-variant` value used in
  template resources across all modules (Studio, bundled apps, compiler templates,
  scaffolder) and validate them against the class roots and variant selectors present in
  the WebJar's `hc.css`. Catches the four ghost classes and any future kit rename — the
  `ScaffoldedConfigKeys` pattern applied to markup.
- **Confirm-wiring test:** any `data-hc-confirm` element must also carry
  `hx-trigger="hc:confirmed"` (or live on a control whose submit is re-dispatched), so a
  confirm can never silently drop a mutation again.

## Slices

### Slice 0 — correctness (no visual change)

- Rewire the three dead confirms (`data/edit`, `connectors` ×2) onto the htmx
  `hc:confirmed` idiom that `drafts.html` already demonstrates.
- Sweep the ghost classes: `hc-disclosure` → `hc-collapsible` (+ proper `__trigger` /
  `__content` parts), `hc-empty__body` → `hc-empty__description`, drop `hc-field--grow` /
  `hc-badge[data-size]` / `hc-button[data-variant=success]` / `hc-badge[data-variant=neutral]`
  (a variantless badge is the neutral badge). The sweep covers the bundled apps
  (`auth-ui`, `account`, docs share pages), which carry the same ghosts.
- Fix `data.html`'s empty-row `colspan`, which ignores the actions column.
- Land both guard-rail tests.

### Slice 1 — CSS and attributes (the dark-mode fix)

- Token-driven link palette in `tesseraql.css` (the kit's base deliberately leaves bare
  anchors to the app): light `#1d4ed8`-family via `--hc-color-info`, dark `#93c5fd`,
  visited unified, hover thickness change. This is app CSS, not an hc paper-over — the kit
  documents that it does not own bare links; an upstream brief proposes a
  `--hc-color-link` token pair so apps stop hand-picking (hc-briefs.md).
- `data-density="compact"` on the framework shells (Studio, Ops console, IAM admin) — the
  kit's density sheet, one attribute.
- `scrollbar-width: thin` + `scrollbar-color` from tokens.
- Evaluate `data-neutral="slate"` for the dark ramp (closer to the brand navy, better
  card/page separation); decide by screenshot A/B before adopting.

### Slice 2 — shell plumbing and feedback

- Toast region + flash contract in the shell; adopt on the silent-mutation pages
  (security, config, flags) and the plain-POST authoring saves (messages, menu, migration,
  route-form).
- `page-header` and `empty` fragments; migrate all pages onto them.
- Surface config's restart-required state as state, not prose.

### Slice 3 — card anatomy and vocabulary

- `hc-card__header/body/footer` rollout: header = title + toolbar (actions, filters),
  body = content, footer = counts/meta. Delete the `h2` override in `tesseraql.css`.
- Apply the semantic color vocabulary (method chips, diff kinds, severity, coverage).
- Wrap every bare `hc-table` in the kit's scroll container; replace inline
  `style="overflow-x: auto"`.
- One empty-state recipe everywhere.

### Slice 4 — Explorer and Documentation

- Explorer tree migrates from hand-rolled disclosures + `.tql-tree__*` CSS to `hc-tree`
  (APG keyboard model, `aria-current`, token guides); "+ New route here" becomes a
  hover/focus-revealed row action; the create card merges into the Routes & jobs card
  header (primary button + "Create ▾" `hc-menu`); compact filter with `hc-command` hint.
- `route.html` / `table.html`: tabs (Contract / SQL / Tests) or completed TOCs; Share
  becomes a header popover instead of a card; per-statement SQL listings collapse.
- domains/rules/decisions consolidate onto one shared catalog fragment.
- release-diff reuses the line-numbered, state-shaded diff renderer route.html already has;
  coverage gate failures render as an error alert, not a bare `<ul>`.

### Slice 5 — Authoring flows

- `source.html`: the five stacked panels become tabs; preview beside the editor; sticky
  Save / Apply / Discard bar (Discard gains its missing confirm); Ctrl/Cmd+S saves.
- Wizards become wizards: `hc-stepper` (Configure → Review YAML → Apply), the persistent
  write is the emphasized action, the generated YAML is previewed before either path.
- Migration: generators stop silently overwriting the DDL textarea (append-or-replace, or
  confirm on non-empty); DDL uses the `hc-code` editable surface.
- Builders converge on one recipe: 300 ms live regeneration, `hc-code` output,
  `data-hc-copy` button, irrelevant fields hidden per operation.

### Slice 6 — Governance dashboard grammar

- Health/Security: stat tiles (tabular numerals, semantic color only where it carries
  meaning), severity stripes, filter chips, search; htmx swaps with indicators.
- Config gains search; flags gain `hc-switch` + confirmed remove; calendars move to
  `hc-calendar` with click-to-toggle holidays; jobs gains a real list (id, trigger story,
  calendar, next fire) above the policy form, grouped by fieldset.
- All pages converge on the drafts/audit htmx idiom and `hc-pagination`.

### Slice 7 — command palette

- `hc-command` (⌘K) over routes, jobs, and the sidebar's 25 destinations: navigation,
  "new route here", open-in-editor.

## Non-goals

- No information-architecture changes: the sidebar groups, page inventory, and URL space
  stay as they are.
- No new hc components or custom CSS over `hc-*` internals (rule 11); gaps found on the way
  become upstream briefs in `hc-briefs.md`. The review found no blocking kit gaps — every
  slice uses components the 0.1.11 WebJar already ships.
- The kit upgrade 0.1.9 → 0.1.11 (waterfall-chart tokens, behavior fixes) is assumed and
  orthogonal; it does not gate any slice.
