# Hypermedia UI patterns

TesseraQL pages are server-rendered Thymeleaf composed with
[Hypermedia Components](https://ingcreators.com/hypermedia-components) (`hc-*` markup, served
from the WebJar at version-less `/assets/vendor/` paths, see [app-layout.md](app-layout.md))
and htmx. The framework bootstrap (`/assets/_tesseraql/tesseraql.js`) imports the kit's
behaviors bundle — it auto-installs every behavior at DOMContentLoaded — wires htmx error
swapping, and marks the current sidebar item (`aria-current="page"`, longest path prefix
wins). This page records the blessed htmx patterns the system apps use, so user apps can
copy them instead of inventing their own.

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
