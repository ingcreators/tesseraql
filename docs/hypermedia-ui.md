# Hypermedia UI patterns

This page records the blessed htmx patterns TesseraQL UIs are built from — the compositions
the bundled system apps (the signed-in shell, [IAM Admin](iam-admin.md), [the ops console](ops-console.md)) use — so user apps
can copy them instead of inventing their own. Pages are server-rendered Thymeleaf composed
with [Hypermedia Components](https://ingcreators.com/hypermedia-components) (`hc-*` markup,
served from the WebJar at version-less `/assets/vendor/` paths, see
[app-layout.md](app-layout.md)) and htmx. The framework bootstrap
(`/assets/_tesseraql/tesseraql.js`) imports the kit's behaviors bundle, which auto-installs
every behavior at DOMContentLoaded, and the bootstrap itself wires the htmx error-fragment
swap.

## Confirmed actions

`data-hc-confirm` gates an action behind the kit's confirm dialog. Two forms:

**Plain form submit** (what IAM Admin's disable button uses) — the button lives in a normal
`<form method="post">`; the dialog intercepts the click and submits on confirm. Without
JavaScript the form still submits, so the action degrades gracefully. The submit-on-confirm
leg is currently the framework bootstrap's stand-in (`tesseraql.js`; the kit's behavior only
re-emits `hc:confirmed` — hc-briefs.md brief 4 asks the kit to own this):

```html
<form method="post" th:action="|/_tesseraql/admin/users/${u.user_id}/disable|">
  <button type="submit" class="hc-button" data-variant="error"
          th:attr="data-hc-confirm=|Disable user ${u.login_id}?|"
          data-hc-confirm-title="Confirm disable"
          data-hc-confirm-label="Disable" data-hc-confirm-variant="error">Disable user</button>
</form>
```

**htmx-driven elements** must rewrite their trigger to the confirmation event. The behavior
intercepts the click in the capture phase, so htmx never sees the original activation;
confirming fires `hc:confirmed` on the element, and `hx-trigger="hc:confirmed"` is what lets
htmx observe it. Without the rewritten trigger the element is inert for htmx:

```html
<button class="hc-button" data-variant="error"
        data-hc-confirm="Delete this draft?" data-hc-confirm-label="Delete"
        data-hc-confirm-variant="error"
        hx-delete="/drafts/123" hx-trigger="hc:confirmed"
        hx-target="closest tr" hx-swap="outerHTML">Delete</button>
```

Never combine `data-hc-confirm` with htmx's own `hx-confirm` — htmx never sees the click, so
`hx-confirm` can never run.

## Live data regions

For app routes, give the region its own fragment endpoint (the
`.../fragments/<name>` URL convention) and let it refresh in place:

```html
<section id="orders-summary" hx-get="/orders/fragments/summary"
         hx-trigger="load, every 30s" hx-swap="innerHTML">
  <p class="hc-field__message">Loading…</p>
</section>
```

`innerHTML` replaces only the contents, so the container and its triggers survive each
refresh. A server can also push refreshes by answering any request with an
`HX-Trigger: {"orders:refresh": true}` header and adding `orders:refresh from:body` to the
trigger list.

When the endpoint returns a full page rather than a fragment (the ops console screens
self-refresh this way), extract the region from the response instead:

```html
<div id="page-content" class="hc-stack" hx-get="/_tesseraql/ops/console/outbox"
     hx-trigger="every 15s" hx-select="#page-content" hx-target="this" hx-swap="outerHTML">
```

## Busy indicators and double submits

`hx-indicator` points at the element that shows progress; an `.hc-spinner.htmx-indicator` is
hidden until htmx marks the request in flight. `hx-disabled-elt="this"` disables the button
for the duration, which is the double-submit protection:

```html
<div class="hc-cluster">
  <button class="hc-button" data-variant="primary"
          hx-post="/api/rebuild" hx-disabled-elt="this"
          hx-indicator="closest .hc-cluster">Rebuild index</button>
  <span class="hc-spinner htmx-indicator" aria-hidden="true"></span>
</div>
```

## Inline validation errors

`command-json` routes answer htmx callers with the kit's field-errors fragment (the exact
shape is in [declarative-validation.md](declarative-validation.md); conflict hints in
[transactional-writes.md](transactional-writes.md)). The wiring is already in place:

- htmx 2 leaves error responses unswapped by default; the framework bootstrap swaps any
  4xx response that carries `data-hc-field-errors` — 422 validation, 409 optimistic-locking
  conflict, and 400 constraint fragments all surface inline. 5xx keeps htmx's default
  handling.
- The kit's `installFieldErrors` behavior distributes each `hc-alert__error` next to the
  input whose `name` matches its `data-field`, sets `aria-invalid`/`aria-describedby`, and
  focuses the first invalid control. Inputs composed as `hc-field` stanzas get the error
  slot created for them; unknown fields stay in the alert summary.
- When the alert renders away from its form (an out-of-band swap), point it at the form
  with a selector: `data-hc-field-errors="#member-form"`.
- The item text arrives server-localized per the request locale, and `data-message-key`
  plus `data-message-params` still ride along: the kit's catalog — loaded by the shell from
  `/assets/_tesseraql/messages.js?locale=<tag>` (the official locale pack layered under the
  app's entries) before the behaviors install — can re-resolve and interpolate it
  client-side (see [internationalization.md](internationalization.md)).

## Network failures

Every contract above assumes a response arrived. The one error with no server response to
narrate with — offline, a dropped socket, a declared timeout — is owned by the kit's
auto-installed `installNetworkRetry` behavior and the shell's `data-hc-network-retry` host
at the top of the main region. When htmx raises `htmx:sendError` or `htmx:timeout`, the
behavior renders a Retry alert into the host; repeat failures re-render in place, so a
poller that lost the network never stacks banners. Any real response — success or error —
clears the alert, because an error response belongs to the contracts above.

Retrying is the user's verb: the behavior never auto-retries, and the Retry button
re-issues the request through the full htmx pipeline with the form's current input values.
For a command route that declares `idempotency:`, the form's `_idempotency` hidden field
rides the retry unchanged, so a retried POST that already committed replays the original
response instead of writing twice ([transactional-writes](transactional-writes.md)).
The alert's strings come from the kit's i18n catalog (`networkRetry.failed` /
`networkRetry.retry`) and follow the request locale like every other kit message.

Timeouts are declared, not defaulted: only hard send failures fire unless a form opts in
with `data-hx-request='{"timeout": 10000}'` or the page sets a global
`htmx.config.timeout`.

## Session expiry

A session that expires mid-page must not cost the user their work — the kit's
`session-expiry` recipe, and the framework renders it end to end. A full-page navigation
without a session keeps the classic bounce to `/_tesseraql/login?redirect=…`
([authentication.md](authentication.md)). An **htmx** request instead answers 401 with a
re-login `<dialog>` retargeted at the shell's shared host (`data-hc-remote-dialog-root
data-hc-session-expiry`, one per page, at body end): `installRemoteDialog` opens it, and
the kit's auto-installed `installSessionExpiry` remembers the interrupted request. The
server refuses before acting, which is what makes the replay of a mutation safe.

The dialog's own form posts back to `POST /_tesseraql/login` and answers three shapes:

- **Success** is `200` with no body and `HX-Trigger: {"hc:sessionrenewed": {"csrfToken":
  …}}` — the kit closes the dialog and replays the interrupted request through the full
  htmx pipeline. The payload carries the **fresh session's CSRF token** because the page's
  `<meta name="csrf-token">` still holds the dead session's; the bootstrap swaps the meta
  in a capture-phase listener, so the replay's `installCsrfHeader` reads the new value.
- **Bad credentials** answer `422` re-rendering the dialog in place with the error inline
  — never the login page's 303 bounce, which would navigate the page whose work the
  dialog exists to preserve. Wrong password, wrong code, and replayed code all read the
  same, exactly like the login page.
- **A throttled attempt** answers `429` the same way, with `Retry-After` and the rate
  message ([credential-throttle.md](credential-throttle.md)).

The offered methods mirror the login page's own model: the password form when password
login is enabled, and an enabled SSO method as a full-page link — a provider round trip
cannot happen inside a dialog, so that leg forfeits the replay and says so by navigating.
The dialog fragment carries the `data-tql-session-expired` marker, which is what the
bootstrap's beforeSwap allowance gates the 401 swap on: a 401 without the marker (an
API-shaped credential failure) keeps htmx's default no-swap handling.

## Response-header signals (HX-Trigger)

A route's `response.html.headers` are emitted on the rendered response. A nested map value is
serialized to JSON — which is exactly htmx's `HX-Trigger` shape — and `{expression}` placeholders
in any value are resolved against the execution context (the same bindings the model uses), so a
header can carry per-request data. This is how a route fires a client-side event (e.g. the kit's
`hc:toast`) from the server without coupling the endpoint to a page location:

```yaml
response:
  html:
    template: saved.html
    headers:
      HX-Trigger:
        "hc:toast":
          message: "Saved {result.name}"
          variant: success
```

htmx dispatches each event on `<body>` after the swap, and the kit's auto-installed `installToast`
behavior renders the notification (a `data-hc-toast-region` container must exist in the shell). A
value with no `{…}` placeholder (the CSP, `X-Frame-Options`, …) is emitted verbatim.

For a command route, the success/error split makes this conditional for free: a successful render
emits these headers, while a validation failure takes the field-errors renderer (above), which does
not. `HX-Reswap` / `HX-Retarget` can likewise be set as (static or interpolated) header values when
a response needs to override its swap strategy or target.

When a single fragment carries both outcomes (a `200` whose body shows success *or* a handled
error), gate a header with `headersWhen` — a boolean expression per header name — so it fires only
when the condition is truthy:

```yaml
response:
  html:
    template: result.html
    headers:
      HX-Trigger:
        "hc:toast": { message: "Applied", variant: success }
    headersWhen:
      HX-Trigger: result.applied   # the toast fires only when the apply succeeded
```

A header with no `headersWhen` entry is always emitted; the guard expression is the same language as
a validation/notification `when:` and is compiled at build time.

To steer an htmx caller's **error** response — send the error fragment to a flash region instead of
the triggering element, or override its swap — declare `response.onError`. The shared error renderer
sets `HX-Retarget` / `HX-Reswap` on the `4xx`/`5xx` reply to an `HX-Request` for that route (resolved
from the failing route id), leaving routes without `onError` on htmx's defaults (the field-errors
fragment swaps into the form's own target):

```yaml
response:
  redirect: { location: /members/{params.id} }
  onError:
    retarget: "#flash"     # send the error fragment to a flash region…
    reswap: outerHTML      # …replacing it whole
```

## CSRF tokens

State-changing browser routes declare `csrf: required`. The framework shell publishes the session
token as `<meta name="csrf-token" content="…">` whenever an authenticated session resolved it,
and the kit's auto-installed `installCsrfHeader` behavior reads that tag at request time and
attaches the `X-CSRF-Token` header to every htmx request — so an htmx form needs no per-request
wiring. The no-JS path can't send a header, so the form also carries a hidden `_csrf` field;
the framework's `csrf` step accepts the header or the field (the header wins), and treats
`_csrf` as a reserved request field that never trips the mass-assignment guard. A page that
hosts a mutating form must therefore be authenticated, so the meta tag is present.

## Mutating forms

A form that changes server state follows the kit's `mutating-form` recipe — the composition
the [scaffolding](scaffolding.md) generators emit. It posts over htmx, swaps inline field errors on a 4xx, and
redirects on success, while degrading to a plain form post with no JavaScript:

```html
<form id="member-form" method="post" action="/members"
      hx-post="/members" hx-target="#member-form-errors" hx-swap="innerHTML"
      hx-disabled-elt="find button[type=submit]" hx-indicator="find .hc-spinner">
  <input type="hidden" name="_csrf" th:value="${_csrf}">
  <div id="member-form-errors"></div>
  <div class="hc-field">
    <label class="hc-field__label" for="email">Email</label>
    <input class="hc-input" id="email" name="email" type="email" required>
  </div>
  <span class="hc-action">
    <button class="hc-button" data-variant="primary" type="submit">Create</button>
    <span class="hc-spinner htmx-indicator" aria-hidden="true"></span>
  </span>
</form>
```

- **Keep `method`/`action` alongside `hx-post`.** Without JavaScript the form submits
  natively: the server re-renders the page with the field-errors fragment inline, or
  redirects. The double-submit guard and spinner are htmx enhancements that simply don't run.
- **Failure (4xx)** swaps the field-errors fragment into the in-form container (the bootstrap
  already allows the swap, see above). Because the container is inside the form,
  `installFieldErrors` distributes items to the inputs.
- **Success** branches on the `HX-Request` header (the framework's redirect renderer does this
  automatically): an htmx caller gets `204` + `HX-Redirect` and htmx navigates with a full
  `window.location` (post/redirect/get intact); a no-JS caller gets the plain `303 Location`.
  `HX-Location` is deliberately avoided — it does a boosted in-page swap, not a redirect.
- **A destructive submit** (delete) gates on `data-hc-confirm` and moves htmx's trigger to the
  confirm event: `hx-trigger="hc:confirmed"` on the form. The no-JS path posts straight
  through (the server re-validates anyway).

## Unsaved changes

Every declarative form view renders with the kit's `unsaved-changes` guard:
`data-hc-dirty-guard` on the `<form>`, and the auto-installed `installDirtyGuard` does the
rest — baseline snapshot on first focus, `data-dirty` toggling with `hc:dirtychange`, the
browser's own prompt on tab close, a `confirm` on boosted navigation, clean again when the
form's own save answers 2xx. The framework adds a visible badge next to the Save button
(`tql.view.modified`, styled off `form[data-dirty]` with `visibility` so the action row
never shifts). Client-only by construction: no endpoint changes, and without JavaScript
nothing guards and nothing breaks — the form submits natively.

Two rules worth knowing before extending it. A request from inside the form that is not
the form's own save deliberately does **not** clean the state — a draft is not the record,
the same line the kit's autosave recipe draws. And the baseline compares canonical wire
values (`FormData`), so display regrouping by `installFormat` is never "dirty". A
hand-written console form opts in with the same single attribute.

## Bulk actions

A list that offers one action against many rows follows the kit's `datagrid-bulk-actions`
recipe, and [IAM Admin](iam-admin.md)'s users list is the blessed example. A form wraps the
`hc-datagrid` and the toolbar, and each row carries a checkbox serialized as repeated `ids`
fields. The header's select-all deliberately has **no `name`**, so it never posts. The
auto-installed `installDatagridActions` behavior reveals the toolbar, with a live
`data-hc-datagrid-count`, while anything is selected:

```html
<form method="post" action="/members/bulk">
  <input type="hidden" name="_csrf" th:value="${_csrf}">
  <div class="hc-toolbar" role="toolbar" aria-label="Bulk actions"
       data-hc-datagrid-actions="#members" hidden>
    <span data-hc-datagrid-count></span>
    <button class="hc-button" data-variant="error" type="submit" name="action" value="disable"
            data-hc-confirm="Disable the selected members?" data-hc-confirm-label="Disable"
            data-hc-confirm-variant="error">Disable selected</button>
  </div>
  <div class="hc-datagrid" id="members">…rows with
    <code>&lt;input type="checkbox" class="hc-checkbox" name="ids" value="…"&gt;</code>…</div>
</form>
```

The server names the verb from the submit button (`action=disable`), validates every
submitted id itself (a selection is client state — never trust it), and answers
post/redirect/get like any other mutating form. A destructive action gates on
`data-hc-confirm`, which intercepts the plain submit and posts on confirm. The htmx
enhancement from the recipe (swap the tbody in place instead of reloading) can layer on
later; the plain-form shape above is the no-JS baseline it must keep.

### The declarative face and the bulk report

A declarative list view gets all of this from `actions:` alone ([declarative
views](declarative-views.md)), and a bulk action against a workflow's `_bulk` endpoint
also gets the failure surface — the kit's `datagrid-bulk-errors` contract, rendered the
TesseraQL way ([bulk-report.md](bulk-report.md)). The grid shows a row-number column.
After the action, the same page renders a bounded report above the grid: totals, then
the failures grouped by reason — a guard's declared message when one exists, the
`TQL-*` code otherwise — each group capped with "…and N more". Every named row links by
its anchor: "Row 12 — PR-1003" on a snapshot list, the key alone elsewhere, because
only a frozen membership makes a number authoritative. Failed rows are marked
(`data-attention="error"`, `aria-describedby` naming their reason group) and stay
checked, so pressing the action again applies to exactly the failures.

Execution is **best-effort per key by construction** — one transaction per key is the
`_bulk` contract, and the report says what happened. The round trip is a redirect
carrying a short-lived, subject-scoped report handle: a snapshot list answers 307 (the
browser re-posts the intact form, so the frozen membership survives), an offset or
keyset list the ordinary 303 to its own URL. An expired or foreign handle simply renders
the plain list — the durable record is workflow history, not the report.

## Marking the current navigation item

The kit's auto-installed `installNavCurrent` behavior marks the current sidebar item with
`aria-current="page"` from the `data-hc-nav-current` opt-in on the shell sidebar; when several
items share a prefix, the longest path-segment prefix wins. Apps composing the framework shell
get this for free — a custom sidebar only needs the opt-in attribute.

## Theme toggle

The signed-in shell header carries the kit's light/dark toggle, and any app page can add
its own — a plain button opts in:

```html
<button class="hc-button" data-variant="ghost" data-hc-theme-toggle type="button">
  <svg class="hc-icon" aria-hidden="true"><use href="/assets/_tesseraql/icons.svg#sun-moon"/></svg>
</button>
```

The kit's auto-installed `installThemeToggle` behavior flips `data-theme` on `<html>`
instantly and reflects state via `aria-pressed`; with no visible text it labels the button
from the catalog (`themeToggle.label`, localized). The framework bootstrap listens for the
kit's `hc:themechange` event and mirrors every change to the account app's appearance
route, so the choice lands in the user's stored preference and follows them across devices
and onto pre-login pages (the cookie re-sync in [account.md](account.md)). **Never add
`data-persist`** — the kit's localStorage persistence would shadow the stored preference,
and the two would fight after the next sign-in. Signed-out pages have no CSRF meta tag, so
a toggle there flips the current page only.

## UI defaults: accent, neutral ramp and density

Every page rendered through the framework shell (`tql/shell`) carries three app-wide visual
defaults, all operator-overridable in `config/tesseraql.yml`:

```yaml
tesseraql:
  ui:
    color: default       # default | teal | lime | orange | fuchsia  (default: default)
    neutral: slate       # neutral | slate | zinc | stone            (default: slate)
    density: compact     # comfortable | compact | dense             (default: compact)
```

- **`color`** picks the kit's accent axis — the primary action color, the focus ring, the
  checked checkbox, the current pagination item. The five built-in axes sit 72° apart around
  the hue wheel, so no two read as shades of each other and none collides with the error,
  warning, or success colors. The default is the kit's own blue, which renders no attribute
  and links no extra stylesheet; the other four link their token sheet
  (`hc.tokens.color-<axis>.css`) on top of `hc.min.css`. A name that is not one of the five is
  a **custom theme** — see below.
- **`neutral`** picks the kit's neutral color ramp — the grays behind pages, cards, borders,
  and muted text, in both themes. The default is **slate** (a cool, blue-leaning neutral):
  it sits in the same hue family as the brand navy and the kit's blue action/link colors, the
  mainstream choice for data-dense business applications. `neutral` (the kit's warm-gray
  default) renders no attribute and links no extra stylesheet; the other ramps link their
  token sheet (`hc.tokens.neutral-<ramp>.css`) on top of `hc.min.css`.
- **`density`** sets the control density for **app pages** (the public `shell(...)` form).
  The default is **compact** — TesseraQL apps are data-dense work surfaces — but a
  touch-first app should set `comfortable`: compact controls are 32px, below the 44px
  touch-target guideline. The framework consoles (Studio, Operations, IAM Admin) always pin
  `compact`; they are keyboard-and-mouse work surfaces by design.

Values outside the kit's enums are ignored (the theme's rule). All three apply on the next
restart; nothing is stored per user — these are the app's defaults, and the per-user choice
surface remains the theme toggle above.

## Custom themes

The framework hard-codes no color. Every surface it renders reads the kit's `--hc-*` tokens,
so a theme built with the kit's
[theme builder](https://ingcreators.com/hypermedia-components/tokens/theme-builder/) drops in
whole. Point one config key at the generated stylesheet:

```yaml
tesseraql:
  ui:
    color: brand              # the axis name you gave the theme, if it is an accent theme
    stylesheet: theme/brand.css   # under the app's assets/ directory
```

`stylesheet` is a path relative to the app's `assets/` directory — `assets/theme/brand.css`
in the example — and it is linked **after** the kit's own token sheets. That order is what
makes it work: both the kit's sheets and a generated one declare their variables inside
`@layer hc.tokens`, so the last one loaded wins.

Which of the builder's exports you use decides whether you also set `color`:

- The **Theme CSS block** export defines one named accent as a `[data-color="<name>"]` block.
  Set `color` to that name. The framework emits the attribute and links your stylesheet, and
  links no vendor sheet — the kit ships no axis by that name, and your block is what defines
  it.
- The **Full token CSS** export customises the default look instead, including the neutral
  ramp, the control radius, and the typography. It needs no `color` at all: drop the file in
  and set `stylesheet` alone.

A theme is a set of about fifty component variables, not seven semantic ones. Components read
their own `--hc-button-primary-bg`, `--hc-checkbox-checked-bg`, and so on, each baked to a
concrete value per theme. Overriding only the semantic variables therefore recolors nothing
visible, which is why the builder generates the whole block rather than a handful of lines.

Bare links in prose follow a custom theme too, since hc 0.3.0. The kit's base layer colors
`a` and `a:hover` from `--hc-color-link` and `--hc-color-link-hover`, and bakes `a:visited` as
a resolved literal per theme, because a `:visited` rule cannot read a token — browsers drop
`var()` there to avoid leaking history through the cascade. The builder emits the same trio,
so a custom accent recolors prose links as well as components.

Two further limits are worth knowing before you commit to a custom accent. The value must be
an ordinary axis name — lower-case letters, digits, and dashes — and the stylesheet must live
under the app's own `assets/`; anything else is ignored rather than served. And a custom
accent does not reach mail: the bundled `tql/email/*` fragments are baked at the default
accent with the slate neutral. To theme mail as well, eject the fragments against your
theme's token file and check them in under the app's `templates/tql/email/`, which shadows
the bundled library — see [HTML mail](notifications.md#html-mail).

## Charts

Charts are the kit's [chart recipe](https://ingcreators.com/hypermedia-components/recipes/chart/):
a `data-hc-chart` figure whose contained `hc-table` **is** the data source, the
no-JavaScript fallback, and the screen-reader data — the kit's `installChart` enhances
it into an Observable Plot SVG on load and after every htmx swap. Column one is the x
axis; every further column is a series; `<th data-mark="bar|line|area">` assigns
per-series marks under the combo kind.

The blessed way to get one is a [dashboard view](declarative-views.md#dashboard-views)
`chart` panel — the framework emits the recipe markup and loads the two scripts (the
self-hosted Plot bundle and `/assets/_tesseraql/charts.js`) only on pages that render a
chart. A hand-written template can emit the same markup and include the same two script
tags; `installChart` is deliberately outside the kit's auto-init bundle because Plot is
its optional peer, so nothing chart-shaped loads on pages without charts. Both scripts
are same-origin webjar assets — the CSP stays `default-src 'self'`, and without
JavaScript (or without Plot) the table simply stays visible.

## Custom error pages

Drop `templates/errors/<status>.html` (or the catch-all `templates/errors/error.html`) into
the app and a top-level browser GET that fails renders it, with `status`, `error.code`,
`error.message`, and any structured `error.details` in the model. htmx swaps keep the inline error fragment and API clients keep
the JSON envelope; with no template, every caller gets the JSON envelope. A broken error
template never masks the original failure — the response falls back to JSON.

## Next

- [declarative-views.md](declarative-views.md) — declaring a page instead of writing its markup.
- [internationalization.md](internationalization.md) — the message catalogs the templates read.
- [studio.md](studio.md) — previewing and editing templates live.
