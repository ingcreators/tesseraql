# HTML email

> **Status: design.** This is an internal design document (docs-site `EXCLUDED`);
> the user-facing documentation lands in `notifications.md` as the slices ship.
> Scope: first-class HTML mail via the hypermedia-components email fragment
> library, bundled as framework-shared templates, plus a no-code mail template
> composer in Studio. The page/view WYSIWYG builder (editor-kit canvas over
> `template:`-mode pages) is a separate future campaign and is only referenced
> here where decisions constrain it.

## Motivation

Mail bodies render through the standard template engine
(`MailNotifier` → `Templates.render`), and `.html` templates already send
`text/html` — but nothing in the framework helps an app author produce HTML
that survives real mail clients. Every example template is plain text. Writing
robust HTML email by hand means table layouts, `role="presentation"`, inline
styles on every element, and client-specific degradations — exactly the
expertise TesseraQL's declarative surface exists to absorb.

Upstream now absorbs it for us: hypermedia-components ships an email
integration (`@hypermedia-components/cli email eject`) that generates
theme-baked, dependency-free Thymeleaf fragment libraries — token references
resolved to literal values in inline `style` attributes, because mail clients
strip external CSS and `var()`. The output is three artifacts:

- `hc-email.html` — the fragment library: `hcButton`/`hcButtonSecondary`,
  `hcHeading`/`hcSubheading`, `hcText`/`hcTextMuted`, `hcLink`, `hcSeparator`,
  `hcBadge` (+ `Info`/`Success`/`Warning`/`Error`), `hcAlertInfo`/`Success`/
  `Warning`/`Error`, `hcPanel`, `hcKvTable`, `hcFooter` — every fragment a
  `th:fragment` with an explicit parameter signature.
- `hc-email-layout.html` — `hcLayout(title, preheader, content)`: the 600px
  centered document shell with the one enhancement-only `<style>` partial
  (mobile widths, `prefers-color-scheme: dark`) that may be stripped without
  breaking the mail.
- `email-tokens.json` — resolved token values for runtime per-tenant theming
  (not bundled; see Deferred).

The fit with TesseraQL's mail path is exact, verified against the code:

- `Templates.engineFor` resolves app-home `*.html` in HTML mode (order-2
  `FileTemplateResolver`), so `th:replace` fragment references already work
  from mail templates — no engine change.
- The order-1 classpath resolver serves the `tql/*` namespace
  (`tesseraql/templates/`) to every app, which is precisely the delivery
  vehicle for a framework-bundled fragment library.
- `MailNotifier` picks `text/html; charset=UTF-8` by the `.html` extension —
  already shipped and documented.

## Design

### D1 — Bundled fragment library at `tql/email/*`

Eject the **default accent / slate neutral / thymeleaf flavor** artifacts
(slate matches the `tesseraql.ui.neutral` app-wide default) and check them in
as classpath resources in **`tesseraql-yaml`** (the module that owns
`Templates` and `MailNotifier`, so its own unit tests can render them):

```text
tesseraql-yaml/src/main/resources/tesseraql/templates/tql/email/hc-email.html
tesseraql-yaml/src/main/resources/tesseraql/templates/tql/email/hc-email-layout.html
```

Every app can then compose mail bodies with zero setup:

```html
<div th:replace="~{tql/email/hc-email-layout :: hcLayout('Ticket assigned',
    'A ticket was assigned to you', ~{templates/mail/assigned :: content})}"></div>
```

```html
<div th:fragment="content">
  <div th:replace="~{tql/email/hc-email :: hcHeading('Ticket assigned')}"></div>
  <div th:replace="~{tql/email/hc-email :: hcText(${payload.assignee} + ', a ticket needs you.')}"></div>
  <div th:replace="~{tql/email/hc-email :: hcKvTable(${rows})}"></div>
  <div th:replace="~{tql/email/hc-email :: hcButton(${payload.url}, 'Open ticket')}"></div>
  <div th:replace="~{tql/email/hc-email :: hcFooter('Sent by Helpdesk')}"></div>
</div>
```

Note the path difference from the upstream Spring docs: TesseraQL's app-home
resolver roots at the app home, so an app's own content fragment is
`templates/mail/...`, while the library is the versionless `tql/email/...`
namespace — same shape as `tql/shell` and `tql/view/*`.

**Provenance and reproducibility.** The artifacts keep their upstream manifest
comment (core version, axes, exact regen command; "do not edit by hand").
Guarding follows the `ScaffoldedConfigKeys` drift-test precedent, minus the
Node dependency: a unit test parses the bundled files and asserts (a) the
manifest comment is present with the expected axes (`color=default
neutral=slate flavor=thymeleaf`), and (b) the `th:fragment` signature set is
exactly the documented palette — so a regen that drops or renames a fragment
fails loudly, and a hand-edit that removes the manifest comment does too. The
regen ritual (run the eject, diff, rerun the drift test) is documented in the
file header and `notifications.md`; CI does not need Node.

**Version alignment.** The 0.3.2 CLI generates with core 0.1.14
email-transform; the repo pins hc 0.1.13. Slice 1 bumps
`hypermedia-components.version` to 0.1.14 (WebJar published) so the bundled
artifacts and the served runtime assets state the same core version. The bump
rides slice 1 with the usual hc-bump checks (changelog scan, Studio/console
smoke) — the email artifacts themselves have zero runtime CSS/JS dependency,
so this is consistency, not necessity.

### D2 — App shadowing: `tql/email/*` joins the override chain

The order-0 override resolver in `Templates.engineFor` currently resolves only
`tql/view/*` from `<appHome>/templates/`. Add `tql/email/*` to its resolvable
patterns, so a custom-themed app ejects its own artifacts into
`templates/tql/email/` (via `npx @hypermedia-components/cli email eject
--tokens my-theme.json` or different axes) and shadows the bundled slate
library file-for-file — the exact L2 move from the view customization ladder,
no new concept. Apps that want a differently-named private copy can already
put it anywhere under `templates/` and reference it by app-home path; the
override pattern is for keeping `tql/email/...` references portable.

### D3 — Example + user docs

- Convert the helpdesk example's `templates/mail/assigned.txt` to
  `assigned.html` composed from the bundled fragments (layout + heading + text
  + kv-table + button + footer) and update its channel `template:`. The
  user-admin and procurement examples stay `.txt`, keeping both modes visible
  in the gallery.
- `notifications.md` grows an "HTML mail" section: the fragment palette with
  signatures, the layout/content composition pattern, the path-prefix note,
  custom theming via eject + shadowing, and the client-support facts worth
  repeating (inline styles are load-bearing; the `<style>` partial is
  enhancement-only; Outlook Word engine drops radii; Gmail may auto-invert).

### D4 — Studio: no-code mail composer

A new Studio page (`ui/mail`) makes mail templates authorable without touching
HTML, using `@hypermedia-components/editor-kit` (0.0.1 WebJar, experimental —
acceptable because upstream is ours and the usage surface is small) for the
canvas mechanics. This is deliberately the *smallest possible* editor-kit
adoption, and doubles as the proving ground for the future page builder.

**Surface.**

- **Channel list**: mail channels from the app manifest (name, from/to,
  subject template, template path, attach binding), each linking to the
  composer; templates referenced but missing offer "create".
- **Block canvas**: the content fragment as a linear block list. Each block
  *is* the literal `div[th:replace]` element — the canvas DOM is the saved
  document, per editor-kit's no-parallel-IR principle. Block chrome (label,
  drag handle, remove) rides `data-hc-editor-*` attributes and
  `data-hc-editor-only` children, which `editor.serialize()` strips. editor-kit
  supplies selection, drag-reorder (`moveNode`), insert/remove, and
  `editor.stack` undo/redo; the palette is the finite fragment set from D1,
  described by the upstream per-fragment contracts.
- **Inspector**: a form for the selected block's fragment arguments — free
  text with `${...}` expressions allowed (`payload.*`, `event.*`); writes back
  via `setAttribute` on the `th:replace` expression so edits participate in
  undo.
- **Preview**: server-rendered with a sample payload (JSON textarea, source
  editor precedent), through the real `Templates` engine with the
  `MailNotifier` model shape (`payload` + `event{id,source,app}`), wrapped in
  the layout and shown in a sandboxed iframe — mail fidelity, no Studio CSS
  bleed. Refresh on change via the existing debounced-htmx pattern.
- **Save**: htmx post of `serialize()` output through the Studio save path
  (app-home confined). Saving runs the same render as a smoke test; a
  Thymeleaf error blocks the save with the message.

**Round-trip rule.** The composer opens templates whose content fragment
consists solely of the block grammar (`div[th:replace~=tql/email/hc-email]`
children plus the layout wrapper) — which includes everything it saves and the
slice-1 helpdesk example. Anything else (arbitrary hand-written Thymeleaf)
renders a read-only preview with an "open in source editor" escape hatch: no
lossy parsing, no corruption risk.

**Not in scope for the composer**: subject editing beyond the channel's
existing inline TEXT template shown as a plain input; creating/wiring channels
(config authoring stays in the config editor); attachments (declared via
`attach:`, displayed read-only).

## Slices

1. **Framework HTML mail** — bundle `tql/email/*` artifacts in
   `tesseraql-yaml` + drift test; add `tql/email/*` to the override resolver
   patterns; hc 0.1.13 → 0.1.14; helpdesk `assigned.html`; `notifications.md`
   section. Renders end-to-end with the existing GreenMail coverage.
2. **Studio mail composer** — editor-kit WebJar dependency + `ui/mail` page
   (channel list, block canvas, inspector, sample-payload preview, save), per
   D4.

## Deferred

- **Per-tenant runtime theming** (`email-tokens.json` interpolation) — verbose
  by design upstream; no multi-tenant theming story in TesseraQL mail yet.
- **Page/view WYSIWYG builder** — the editor-kit canvas over `template:`-mode
  pages and L1 slot fragments, with a Studio eject ramp (`ViewEjector` has no
  UI today). Own campaign; slice 2's editor-kit plumbing (WebJar, canvas/
  inspector/save patterns) is designed to be lifted into it.
- **Plain flavor** — TesseraQL mail is Thymeleaf-only; the plain flavor's
  manual-interpolation mode has no consumer here.
- **Localized mail bodies** — mail renders locale-less (English/default
  catalogs) today; unchanged by this design.
