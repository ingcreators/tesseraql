# Hypermedia Components — improvement briefs

Briefs for upstream [Hypermedia Components](https://ingcreators.com/hypermedia-components)
improvements, per [AGENTS.md](../AGENTS.md) rule 11 (UI gaps belong upstream, not papered over
in TesseraQL). TesseraQL carries a local stand-in only until the released feature can be adopted.

Filed upstream as `ingcreators/hypermedia-components` issues
[#270](https://github.com/ingcreators/hypermedia-components/issues/270) (`data-hc-copy`),
[#271](https://github.com/ingcreators/hypermedia-components/issues/271) (`hc-toc` + `data-hc-spy`),
and [#272](https://github.com/ingcreators/hypermedia-components/issues/272)
(`data-hc-nav-current`).

> **Status: shipped and adopted.** All three landed in **hc 0.1.6** (`installCopy`, `hc-toc` +
> `installSpy`, `installNavCurrent`). TesseraQL bumped to 0.1.6 and adopted them, deleting the local
> stand-ins (`tesseraql.js` `[data-copy]` and sidebar `aria-current` marking) — the share-URL Copy
> buttons use `data-hc-copy`, the "On this page" navs are `hc-toc` + `data-hc-spy`, and the shell
> sidebar opts in with `data-hc-nav-current`. The briefs below are retained as the design record.

These three gaps were confirmed absent from hc **0.1.5** — checked against both the CSS
(`dist/hc.min.css`, `.hc-*` roots) and the behaviors bundle (`dist/*.js`). They surfaced while
building the Studio platform-UX work (Track H/I in [studio-backlog.md](studio-backlog.md)).

A hard constraint runs through all three: TesseraQL pages ship a strict
`Content-Security-Policy: default-src 'self'` (no `script-src` relaxation), so **inline event
handlers and `hx-vals='js:…'` are forbidden**. Any behavior must work from the kit's
auto-installed bundle (a same-origin module) with declarative markup only — no inline JS asked of
the consumer. The existing kit behaviors (`data-hc-confirm`, `installCsrfHeader`, …) already meet
this bar; these briefs ask the same of the proposed additions.

---

## Brief 1 — `data-hc-copy`: copy-to-clipboard behavior

*Filed: [ingcreators/hypermedia-components#270](https://github.com/ingcreators/hypermedia-components/issues/270)*

### Problem

Read-only fields that exist to be copied — share URLs, API tokens, generated SQL/config
snippets — force a manual select-all + Ctrl/Cmd-C, which is especially poor on touch. The kit has
no copy primitive. TesseraQL hand-rolled a `[data-copy]` click handler in its app bootstrap
(`tesseraql.js`) for the share-URL fields, which is exactly the kind of behavior rule 11 says
should live upstream.

### Proposed

A `data-hc-copy` behavior, auto-installed by the bundle (`installCopy()`), that copies a named
element's value (or literal text) to the clipboard on activation and signals success:

```html
<div class="hc-cluster">
  <input id="share-url" class="hc-input" type="text" readonly value="https://app.example.com/…">
  <button class="hc-button" data-variant="ghost"
          data-hc-copy="#share-url" data-hc-copy-ok="Copied">Copy</button>
</div>
```

- `data-hc-copy="<css-selector>"` — copy the referenced element's `value` (form controls) or
  `textContent` (everything else).
- `data-hc-copy-text="<literal>"` — alternatively copy a literal string (no target element).
- `data-hc-copy-ok="Copied"` *(optional)* — the transient success label; defaults to a localized
  "Copied" from the kit's i18n catalog.

### Behavior

On click, read the source, call `navigator.clipboard.writeText(...)`. On success, set
`data-hc-copied` on the button for ~1.5 s (CSS can reflect it / swap the label) and fire a
`hc:copied` `CustomEvent` (so an app can chain an `hc-toast`). Announce the success to assistive
tech via a visually-hidden `role="status"` live region the behavior owns. The button keeps its own
accessible name throughout.

### Accessibility

- Keyboard-activatable (it is a real `<button>`); success announced via `role="status"`.
- Touch works (click, not hover).

### CSP / progressive enhancement

- Lives in the kit bundle, so the consumer writes **no inline JS** — works under `default-src
  'self'`.
- The Clipboard API needs a secure context (https / localhost). Where it is unavailable the click
  is a graceful no-op (optionally select the target's text as a fallback).

### Acceptance criteria

- A `data-hc-copy` button copies the target's value with no inline handler and no app JS.
- Success is announced to screen readers and reflected visually.
- Fires `hc:copied`; integrates with `hc-toast` when present.

### TesseraQL stand-in to retire

`[data-copy]` handler in `tesseraql.js` (Studio share-URL "Copy" buttons, platform-UX H6).

---

## Brief 2 — `hc-toc` + `data-hc-spy`: in-page table of contents with scrollspy

*Filed: [ingcreators/hypermedia-components#271](https://github.com/ingcreators/hypermedia-components/issues/271)*

### Problem

Long reference pages (the Studio route- and table-doc pages have 8+ sections) benefit from an
"On this page" jump list of in-page `#anchor` links. Plain anchors navigate fine, but there is no
indication of **which section is currently in view** as the reader scrolls — the kit has no
scrollspy / TOC primitive. TesseraQL renders the jump list by hand with no active-section state.

### Proposed

An `hc-toc` styling class plus a `data-hc-spy` behavior that marks the link for the section
currently in view:

```html
<nav class="hc-toc" data-hc-spy aria-label="On this page">
  <a class="hc-toc__link" href="#sec-inputs">Inputs</a>
  <a class="hc-toc__link" href="#sec-sql">SQL</a>
  <a class="hc-toc__link" href="#sec-tests">Tests</a>
</nav>
…
<section id="sec-inputs">…</section>
<section id="sec-sql">…</section>
<section id="sec-tests">…</section>
```

- `hc-toc` / `hc-toc__link` — the styled list (CSS-only; usable without the behavior).
- `data-hc-spy` on the nav — opt into scrollspy.

### Behavior

`installSpy()` resolves each link's `href` to its target section and observes them with an
`IntersectionObserver`. The link of the top-most section in view gets `aria-current="location"`
(and a `data-active` hook for CSS). No smooth-scroll is forced; clicking a link is the browser's
native anchor jump.

### Accessibility

- The active link carries `aria-current="location"`, driving both the visual state and the SR
  signal.
- Without JS the nav is still a working list of anchor links (no active highlight) — progressive
  enhancement.

### CSP

- Behavior in the bundle; **no inline JS**. `IntersectionObserver` is standard and CSP-neutral.

### Acceptance criteria

- The active link updates as the reader scrolls; only sections that exist are tracked.
- Works as plain anchors with JS disabled.
- No inline handlers; honors `prefers-reduced-motion`.

### TesseraQL stand-in to retire

The hand-rolled "On this page" `<nav>` in the Studio route/table doc pages (platform-UX H4) — it
would gain the missing active-section highlight.

---

## Brief 3 — current-nav marking by URL (`data-hc-nav-current`)

*Filed: [ingcreators/hypermedia-components#272](https://github.com/ingcreators/hypermedia-components/issues/272)*

### Problem

Marking the active navigation item — set `aria-current="page"` on the link whose `href` matches
the current URL — is something every app needs and currently reimplements. TesseraQL does it in
`tesseraql.js` (find the sidebar link whose path is the longest prefix of `location.pathname`,
set `aria-current="page"`; documented in [hypermedia-ui.md](hypermedia-ui.md)). The kit's
`shell.js` / `navmenu.js` own the sidebar toggle and dropdown menus but **not** current-URL
marking, so the one piece of nav logic that is purely a function of the URL is left to each app.

### Proposed

A `data-hc-nav-current` behavior (or fold it into `installShell()` for `hc-shell__sidebar`) that
marks the best-matching link on load:

```html
<nav class="hc-shell__sidebar" data-hc-nav-current aria-label="Primary">
  <a class="hc-item" href="/app/explorer">Explorer</a>
  <a class="hc-item" href="/app/docs">Docs</a>
  <a class="hc-item" href="/app/docs/coverage">Coverage</a>
</nav>
```

### Behavior

On install, among the container's `a[href]`, pick the link whose pathname equals
`location.pathname` or is the longest prefix such that `location.pathname` starts with
`pathname + "/"`, and set `aria-current="page"` on it (clearing any stale one). Re-run after htmx
history navigation (`htmx:pushedIntoHistory` / `popstate`) when the nav persists across swaps.

### Accessibility

- `aria-current="page"` drives both the selected visual state (the kit already styles
  `.hc-item[aria-current]`) and the assistive-tech signal.

### CSP

- Behavior in the bundle; **no inline JS**.

### Acceptance criteria

- The correct link is marked on full page load and after htmx navigation.
- Longest-prefix wins (a section link stays current on its subpages).
- Opt-in via `data-hc-nav-current` (or automatic for `hc-shell__sidebar`).

### TesseraQL stand-in to retire

The `aria-current` block in `tesseraql.js` (platform-UX H1) — it can be deleted once the kit owns
this.

---

## Brief 4 — `data-hc-confirm`: complete the plain-form contract

*Filed: [ingcreators/hypermedia-components#421](https://github.com/ingcreators/hypermedia-components/issues/421) (found 2026-08-06, Studio UX refresh slice 0).*

> **Status: shipped and adopted.** Landed in **hc 0.1.13** — `installConfirm` calls
> `form.requestSubmit(source)` for a confirmed plain-form submit button, with the htmx-verb
> exemption. TesseraQL bumped to 0.1.13 and DELETED the `tesseraql.js` stand-in (keeping it
> would double-submit).

### Problem

`installConfirm()` intercepts the click in the capture phase, calls `preventDefault()`, and on
confirm only dispatches the bubbling `hc:confirmed` event for htmx to observe
(`dist/confirm.js`, all released versions 0.1.0 → 0.1.11). For a submit button in a plain
`<form method="post">` — the graceful-degradation form the pattern naturally suggests, since
without JavaScript the form still submits — nothing listens for the event and nothing
re-dispatches the submit: **the user confirms and the action never happens**. Every consumer
that combines `data-hc-confirm` with a native form (TesseraQL had fourteen such buttons across
Studio and IAM Admin, including "disable user" and "write to config overlay") ships a dead
control, and the mistake is invisible in HTTP-level tests because they post directly.

### Proposal

On confirm, when the source element is a submit button (`type="submit"` on a `<button>` or
`<input>`) associated with a form, and neither the element nor its form carries an htmx verb
attribute (`hx-get/post/put/patch/delete`, `data-hx-*` variants), call
`form.requestSubmit(source)` after dispatching `hc:confirmed`. `requestSubmit(source)`
preserves the submitter's `formaction`/`formmethod` and runs constraint validation, exactly as
the intercepted click would have. htmx-wired elements keep today's contract unchanged —
`hc:confirmed` fires and htmx owns the request.

### CSP

- Behavior in the bundle; **no inline JS**.

### Acceptance criteria

- A confirmed submit button in a plain form submits the form with itself as submitter
  (its `formaction` honored, validation run).
- A cancelled dialog submits nothing.
- htmx-wired elements (`hx-*` verb on the element or its form) are not double-fired.
- Without JavaScript the form still submits natively (unchanged).

### TesseraQL stand-in to retire

The `hc:confirmed` → `requestSubmit` listener in `tesseraql.js` ("Confirmed plain-form
submit") — delete once the kit owns this.

---

## Brief 5 — `hc-tree`: exempt interactive controls from row-click branch toggling

*Filed: [ingcreators/hypermedia-components#427](https://github.com/ingcreators/hypermedia-components/issues/427)
(found 2026-08-06, Studio UX refresh slice 4, migrating the Studio explorer to `hc-tree`).*

> **Status: shipped and adopted.** Landed in **hc 0.1.13** — the click guard now exempts
> `a[href], input, button, select, textarea`, matching the keydown guard. The explorer's
> "+ New route" row action deliberately stays an `<a href>` (the href is the no-JS fallback);
> buttons in rows are simply safe now.

### Problem

`installTree()`'s keyboard handler deliberately ignores keys originating from widgets inside
rows (`event.target.closest('input, button, select, textarea')` — `dist/tree.js`), but its
**click** handler only exempts links (`event.target.closest('a[href]')`). A `<button>` placed
inside a `.hc-tree__row` — a per-row action, the shape the row's flex layout invites — therefore
fires its own action **and** toggles the branch on every click, collapsing the folder the user
is acting on. The two handlers should agree on what counts as an interactive control.

### Proposal

In the click handler, skip the branch-toggle (and keep the focus move) when the click originated
inside the same control set the keydown handler exempts: `a[href], input, button, select,
textarea`. No API change; row actions become first-class without consumers routing around the
toggle.

### CSP

- Behavior in the bundle; **no inline JS** (unchanged).

### Acceptance criteria

- Clicking a `<button>` (or form control) inside a branch row does not toggle the branch.
- Clicking the row's text/whitespace still toggles a linkless branch (unchanged).
- Keyboard behavior is unchanged (already correct).

### TesseraQL workaround to retire

The Studio explorer's "+ New route" row action is authored as an `<a href>` (with `hx-get`)
instead of a `<button>` purely so the link exemption applies. It is honest markup (the href is
the no-JS fallback), so this is a soft workaround — but once the kit exempts buttons, row
actions are free to be buttons again.

---

## Brief 6 — declarative conditional field visibility

*Filed: [ingcreators/hypermedia-components#428](https://github.com/ingcreators/hypermedia-components/issues/428)
(found 2026-08-06, Studio UX refresh slice 5, converging the Studio builders on one recipe).*

> **Status: shipped and adopted.** Landed in **hc 0.1.13** as the proposed contract
> (`data-hc-show-switch` / `data-hc-show-when` / `data-hc-show-src`, auto-installed).
> TesseraQL migrated the SQL and validation builders onto it and DELETED the
> `data-tql-switch` / `data-tql-show-for` stand-in from `tesseraql.js`.

### Problem

A form whose fields depend on a mode selector — "operation: insert" needs no filter column,
"rule: range" needs a second bound — has no kit primitive to hide the fields the chosen mode
does not read. The declarative options today are all bad: leave every field visible (the form
reads as more complex than the task), re-render the form server-side on every selector change
(a round-trip that loses focus and half-typed values), or hand-rolled JS (which a strict
`default-src 'self'` CSP forces into the app bundle, where every consumer reinvents it).

### Proposal

A behavior-driven contract, mirroring the confirm/copy attribute style:

- `data-hc-show-when="<value> [<value> …]"` on any element, naming the values under which it
  is visible;
- the controlling input is the closest form's `[data-hc-show-switch]` control (or a
  `data-hc-show-src="<selector>"` override for cross-form cases);
- the behavior toggles the `hidden` attribute (never `display` inline styles), re-evaluates on
  `change`, and runs once at install so server-rendered state is honored;
- hidden controls keep submitting — filtering values is the server's job, visibility is
  presentation.

### CSP

- Behavior in the bundle; **no inline JS**.

### Acceptance criteria

- Changing the switch shows/hides the marked elements without any request or focus loss.
- Initial page state is correct before any interaction (install-time evaluation).
- Elements swapped in by htmx are picked up (the install-on-`htmx:load` idiom the other
  behaviors use).

### TesseraQL stand-in to retire

The `data-tql-switch` / `data-tql-show-for` listener in `tesseraql.js` (the Studio SQL and
validation builders) — delete once the kit owns this.

---

## Brief 7 — `hc-table` / `hc-datagrid`: tabular figures by default + a numeric-column modifier

*Filed: [ingcreators/hypermedia-components#430](https://github.com/ingcreators/hypermedia-components/issues/430)
(found 2026-08-06, extending the Studio governance dashboards' number alignment).*

> **Status: shipped and adopted.** Landed in **hc 0.1.13**: tabular figures are the table and
> datagrid cell default (nothing to adopt — it applies to every table on the bump), and
> `data-numeric` end-aligns a cell/column when a page opts in. The Studio data browser opts
> its numeric columns in, decided from the result set's JDBC metadata (the result's truth,
> not a schema guess). No TesseraQL stand-in existed; the stat tiles' own `tabular-nums`
> stays (app-owned class).

### Problem

Neither table component does anything for numbers: no `font-variant-numeric`, no numeric-cell
modifier (`data-align` exists only on the anchored-overlay components). With proportional
figures, columns of digits — counts, amounts, percentages, timestamps — wobble: `111` is
visibly narrower than `999`, rows never line up vertically, and scanning a data column becomes
a reading task instead of a glance. Every data-dense consumer rediscovers this and reaches for
custom CSS over the kit's cell classes — exactly the drift the markup contract exists to
prevent.

### Proposal

One safe default plus one declarative opt-in:

- **Tabular figures as the component default**: `font-variant-numeric: tabular-nums` on
  `.hc-table` cells and `.hc-datagrid__cell` / `.hc-datagrid__headcell`. The property affects
  digits only, so text cells render unchanged — a blanket default with no real downside in
  tabular contexts.
- **A numeric-column modifier**: right-alignment is per-column *semantics*, so a declarative
  hook the server renders — `data-numeric` on a cell/headcell, styled `text-align: end`
  (logical, so RTL flips free). Composes with the sortable-header story: numeric columns are
  the ones most often sorted.

### CSP

- CSS only; no behavior, no inline styles asked of the consumer.

### Acceptance criteria

- Digit runs in table/datagrid cells align vertically out of the box; text cells unchanged.
- `data-numeric` end-aligns a cell/column, in RTL too.
- Existing consumers need no markup changes; the modifier is opt-in.

### TesseraQL stand-in

Deliberately **none carried**: table cells are the kit's surface, and nothing is broken —
misaligned digits are a polish gap, not a defect. The stat tiles' `tabular-nums`
(`.tql-stat__value`, an app-owned class) stays either way.

---

## Brief 8 — manifest container/composition metadata for editor canvases

*Filed: [ingcreators/hypermedia-components#447](https://github.com/ingcreators/hypermedia-components/issues/447) (found 2026-08-07, page builder — docs/page-builder.md).*

> **Status: shipped and adopted.** Landed in **hc 0.1.15** — per-component `containers`
> metadata (`""` = the block root, else parts; e.g. `hc-card: [body, footer]`). The
> builder derives its droppable marking from the manifest; only the three CSS layout
> utilities (`hc-stack`/`hc-cluster`/`hc-grid`, not manifest components) remain a local
> list.

### Problem

The page builder derives its palette and inspector from `dist/manifest.json`, but **which
blocks are containers** had to be hand-curated: `page-builder.js` hardcodes the droppable set
(`hc-stack`, `hc-cluster`, `hc-card__body`, `hc-card__footer`) for `data-hc-editor-container`
marking and drag `canAccept`. Every new kit component with a content area silently rejects
drops until each downstream builder updates its own list — the exact drift the manifest
otherwise eliminates.

### Proposed

Per-component `containers` metadata (parts — or `""` for the block root — that accept
arbitrary flow children): layout primitives mark their root, composites mark their content
parts, structured components (`hc-datagrid`, `hc-tabs`) mark nothing. Editors then derive
droppable marking and `canAccept` from the injected manifest; the hardcoded list in
`page-builder.js` is the stand-in until adoption.

## Brief 9 — baked default-theme email artifacts in core + machine-readable fragment contract

*Filed: [ingcreators/hypermedia-components#448](https://github.com/ingcreators/hypermedia-components/issues/448) (found 2026-08-07, HTML email — docs/html-email.md).*

> **Status: shipped and adopted.** Landed in **hc 0.1.15** — `dist/email/<theme>/<flavor>/`
> baked artifacts (five neutrals × thymeleaf/plain) plus `dist/email/contract.json`.
> TesseraQL DELETED its checked-in `tql/email/*` copies: the build unpacks the WebJar's
> default-slate/thymeleaf artifacts (a version bump IS the regen), and the drift guard
> validates the parsed signatures against the published contract instead of a hardcoded
> set. The CLI eject remains the custom-theme path.

### Problem

TesseraQL bundles the email integration by checking in `email eject` output at `tql/email/*`,
which carries: a manual regen ritual (Node CLI, diff, re-commit) on every theme/core change; a
drift guard that can only regex `th:fragment` signatures (`BundledEmailTemplatesTest`) because
no machine-readable contract exists; and an inevitable version skew between the checked-in copy
and the served core. The core package already ships the token-referencing sources
(`src/email/*/fragment.html`) — just not the transformed output.

### Proposed

1. Core ships baked artifacts (`dist/email/…` for the default axes) generated by the same
   email-transform, so frameworks resolve them straight from the WebJar — check-in and regen
   ritual deleted; the CLI remains the custom-theme path.
2. A `dist/email/contract.json` (the recipes' `checks.json` precedent: fragment names, parameter
   names, flavor) so downstream guards validate against the published contract and
   `validate` can check composing templates.

## Brief 10 — editor-kit canvas ergonomics (element-index moves, block picker, cross-document palette drag, format-stable serialize)

*Filed: [ingcreators/hypermedia-components#449](https://github.com/ingcreators/hypermedia-components/issues/449) (found 2026-08-07, mail composer + page builder).*

> **Status: shipped (items 1–3) and adopted.** Landed in **editor-kit 0.1.0** —
> `{before: Element|null}` insertion points + exported `indexBefore`, `pickBlock`, and a
> `frame` option on the drag controller (host-document listeners + coordinate
> translation). Both canvases adopted: the whitespace normalization and the hand-rolled
> index/pick helpers are deleted, and palette drags now drop into the iframe canvas.
> Item 4 (dirty-region serialize) stays on the upstream roadmap.

### Problem / Proposed

Four gaps, each a downstream workaround in `mail-composer.js` / `page-builder.js`, in priority
order:

1. **Whitespace text nodes skew `childNodes` indices** — element-based UI (Alt+Arrow reorder,
   append-to-container) must strip whitespace at mount or duplicate `dnd.js`'s exclude-aware
   counter. Ask: export the helper, or accept `{before: Element|null}` insertion points.
2. **Nearest-block selection picker** — every canvas writes "walk up to the nearest manifest
   block, else the element, never the mount". Ask: a `pickBlock(target, {root, manifest})`
   helper.
3. **Palette drags cannot cross a document boundary** — `createDragController` is
   single-document; with the canvas in an iframe (the `Overlay({frame})` arrangement),
   `startInsert` from a parent palette can never complete; both surfaces fell back to
   click-to-insert. Ask: a documented bridge pattern or a `frames:` option doing the
   coordinate translation.
4. **Format-stable serialization** (stretch, post-1.0) — `serialize()` rewrites the whole
   region's formatting; a dirty-region serializer (the CommandStack knows which nodes changed)
   would keep review diffs minimal. Filed so the constraint is on record before the API
   freezes.

## Brief 11 — scriptless static rendering for behavior components

*Filed: [ingcreators/hypermedia-components#450](https://github.com/ingcreators/hypermedia-components/issues/450) (found 2026-08-07, page builder canvas).*

> **Status: shipped and adopted.** Landed in **hc 0.1.15** — `data-hc-static` CSS
> resting states (tabs panels, empty-shell frames, …) and a per-component `staticSafe`
> manifest flag. The builder canvas sets `data-hc-static` on its srcdoc document, and
> the inspector notes "previews approximately" on `staticSafe: false` components.

### Problem

The builder canvas is sandboxed **without** `allow-scripts` (deliberately — the canvas must be
inert; it went through CodeQL review on exactly this point), so components whose resting state
depends on a behavior render pre-init: `hc-tabs` shows every panel, `data-hc-show-when` fields
all show, dialogs render inline. The same hits any scriptless consumer (static previews,
server-side screenshots, print).

### Proposed

Make each behavior component's default resting state achievable in CSS alone (keyed off the
declarative markup the behavior already reads), plus an explicit `data-hc-static` opt-in where
JS is genuinely required, and a `staticSafe` manifest flag so editor canvases can badge
approximate previews honestly.

## Brief 12 — document-level link tokens + bare-anchor base rules

*Filed: [ingcreators/hypermedia-components#569](https://github.com/ingcreators/hypermedia-components/issues/569) (found 2026-08-26, hc 0.2.1 adoption + theme switching — docs/hypermedia-ui.md).*

> **Status: shipped and adopted.** All three tokens landed in **hc 0.3.0** together with the
> bare-anchor rules in `@layer hc.base`, the `:visited` color baked per theme off the same
> declaration the token comes from, and a `color.<name>.dark.tokens.json` for each non-default
> accent — links are the one accent value that differs between themes, since a link is text on
> the page surface rather than white text on the accent. The theme builder emits the trio too.
> TesseraQL bumped to 0.3.0 and **deleted the stand-in**: `tesseraql.css` now paints no themed
> surface from a literal — what is left is `var()` fallbacks and the white paper of the two
> preview iframes — and prose links follow `tesseraql.ui.color` and a builder stylesheet like
> every other surface. Adopting the kit's default also adopts its policy of a distinct visited
> step, which replaces the local rule that unified the two — a console is a tool, but keeping
> that unification would have meant keeping the literals, since `:visited` is exactly what a
> token cannot express.
>
> The same work fixed the **email** dark flavor, where `link` and `table` carried no `hc-em-*`
> class and so survived the dark flip on their light colors — 2.77:1 and 1.21:1 against the
> dark container. TesseraQL takes that fix with the bump, because the bundled `tql/email/*`
> fragments are unpacked from the WebJar at build time.

### Problem

`hc.base.css` takes over the document's color — `body` gets `--hc-color-bg` and
`--hc-color-text` — but stops short of bare `<a>`. Anchors outside a component fall to the UA
defaults: `-webkit-link` blue and `:visited` purple. Both are theme-blind. Neither follows
`data-theme`, `data-color`, or `data-neutral`, so the visited purple is near-illegible on a
dark surface and the blue diverges from any non-default accent.

Consumers hand-pick literals in response, and it has to be literals. `:visited` rules cannot
resolve custom properties — engines drop `var()` there deliberately, since resolving it would
leak history through the cascade. So the one part of the theme a consumer cannot delegate to
the kit is also the one part it cannot express with tokens.

0.2.0 made this worse. Regularizing the chromatic ramps on a shared lightness ladder moved
every step, so every hand-picked literal in every consumer silently drifted off the ladder.
Nothing warns, because nothing knows the literal was meant to be a ramp step.

### Proposed

A document-level token pair (`--hc-color-link`, `--hc-color-link-hover`,
`--hc-color-link-visited`), generated per theme like every other token. The existing
`*-link-fg` tokens are not this: `--hc-toc-link-fg` and its siblings are neutral-ramp values
for nav affordances, where a prose link wants the accent family.

Plus the bare-anchor rules themselves in `@layer hc.base`, with the `:visited` declaration
emitted as the resolved literal per theme block. That second half is the reason this belongs
upstream rather than in an app: the `:visited` restriction bites at runtime, when custom
properties resolve, while the token build runs at build time and already bakes concrete values
per theme. The build can therefore bake a theme-following visited color that no consumer can
write by hand.

## Notes

- Two adjacent gaps were found to be **already shipped** in hc 0.1.5 and have been adopted, not
  briefed: `hc-spinner` and `hc-breadcrumb` (Track I1). They were CSS-only components, easy to miss
  by searching the behaviors bundle alone.
- `hc-datagrid` ships sorting, but as a **server-driven** event (`hc:datagridsort`) — wiring it to
  htmx needs `hx-vals='js:…'`, which the CSP forbids. A future brief could ask for a CSP-clean
  sort→request binding (e.g. the behavior writing the sort key/direction into a named hidden input
  or `hx-get` URL the kit updates), which would let strict-CSP apps adopt sortable grids.
