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

## Notes

- Two adjacent gaps were found to be **already shipped** in hc 0.1.5 and have been adopted, not
  briefed: `hc-spinner` and `hc-breadcrumb` (Track I1). They were CSS-only components, easy to miss
  by searching the behaviors bundle alone.
- `hc-datagrid` ships sorting, but as a **server-driven** event (`hc:datagridsort`) — wiring it to
  htmx needs `hx-vals='js:…'`, which the CSP forbids. A future brief could ask for a CSP-clean
  sort→request binding (e.g. the behavior writing the sort key/direction into a named hidden input
  or `hx-get` URL the kit updates), which would let strict-CSP apps adopt sortable grids.
