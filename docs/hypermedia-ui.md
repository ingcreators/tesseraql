# Hypermedia UI patterns

TesseraQL pages are server-rendered Thymeleaf composed with
[Hypermedia Components](https://ingcreators.com/hypermedia-components) (`hc-*` markup, served
from the WebJar at version-less `/assets/vendor/` paths, see [app-layout.md](app-layout.md))
and htmx. The framework bootstrap (`/assets/_tesseraql/tesseraql.js`) imports the kit's
behaviors bundle, which auto-installs every behavior at DOMContentLoaded — including
`installNavCurrent`, which marks the current sidebar item (`aria-current="page"`, longest
path-segment prefix wins) from the `data-hc-nav-current` opt-in on the shell sidebar — and the
bootstrap itself wires the htmx error-fragment swap. This page records the blessed htmx patterns
the system apps use, so user apps can copy them instead of inventing their own.

## Confirmed actions

`data-hc-confirm` gates an action behind the kit's confirm dialog. Two forms:

**Plain form submit** (what IAM Admin's disable button uses) — the button lives in a normal
`<form method="post">`; the dialog intercepts the click and submits on confirm. Without
JavaScript the form still submits, so the action degrades gracefully:

```html
<form method="post" th:action="|/_tesseraql/admin/users/${u.user_id}/disable|">
  <button type="submit" class="hc-button" data-variant="error"
          th:attr="data-hc-confirm=|Disable user ${u.login_id}?|"
          data-hc-confirm-title="Confirm disable"
          data-hc-confirm-ok="Disable" data-hc-confirm-variant="error">Disable user</button>
</form>
```

**htmx-driven elements** must rewrite their trigger to the confirmation event. The behavior
intercepts the click in the capture phase, so htmx never sees the original activation;
confirming fires `hc:confirmed` on the element, and `hx-trigger="hc:confirmed"` is what lets
htmx observe it. Without the rewritten trigger the element is inert for htmx:

```html
<button class="hc-button" data-variant="error"
        data-hc-confirm="Delete this draft?" data-hc-confirm-ok="Delete"
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
emits these headers, while a validation failure takes the field-errors renderer (below), which does
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

## Mutating forms

A form that changes server state follows the kit's `mutating-form` recipe — the composition
the Phase 23 scaffolds emit. It posts over htmx, swaps inline field errors on a 4xx, and
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

## CSRF tokens

State-changing browser routes declare `csrf: true`. The framework shell publishes the session
token as `<meta name="csrf-token" content="…">` whenever an authenticated session resolved it,
and the kit's auto-installed `installCsrfHeader` behavior reads that tag at request time and
attaches the `X-CSRF-Token` header to every htmx request — so an htmx form needs no per-request
wiring. The no-JS path can't send a header, so the form also carries a hidden `_csrf` field;
the framework's `csrf` step accepts the header or the field (the header wins), and treats
`_csrf` as a reserved request field that never trips the mass-assignment guard. A page that
hosts a mutating form must therefore be authenticated, so the meta tag is present.
