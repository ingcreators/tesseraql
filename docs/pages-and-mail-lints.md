# Pages overview + mail wiring lints

> **Status: design.** Internal design document (docs-site `EXCLUDED`). Two small,
> independent slices closing gaps the HTML email (docs/html-email.md) and page builder
> (docs/page-builder.md) campaigns exposed: the authoring surfaces exist but have no
> overview page, and mail template wiring is validated only at delivery time.

## Motivation

- **Discoverability**: the builder, the mail composer and the eject ramp are all entered
  from a single route's source page. There is no "what pages does this app have" view —
  the ladder (view document → eject → visual edit) is invisible as a whole, and the mail
  page lists channels but not the page-side of the app.
- **Late failures**: the helpdesk example shipped for months with a mail template whose
  binds could never resolve (`${ticket}` vs `${payload.ticket}`) and a channel missing
  `to:` — undetectable because nothing renders mail templates before delivery, and the
  declarative tests assert the notify without SMTP. The `template:` file itself is only
  checked at send time (`TQL-BATCH-5304`).

## D1 — Studio Pages overview (`ui/pages`)

A read-only listing (the `ui/mail` shape) of every route with an HTML response, from a
fresh `ManifestLoader().load(appHome)` (the eject precedent — the boot-snapshot manifest
can be stale):

- Columns: route id, `method path`, rendering (**view** badge with its kind —
  list/form/detail/dashboard — or **template** badge), the view/template ref, and
  actions.
- Actions are links into the existing surfaces (never new write endpoints): route
  source, view document source, template source, "Edit visually" when the resolved
  template is builder-eligible (PageBuilder-eligible and not mail-composable — the
  source-page rule), and "Eject…" linking to the route's source page where the confirmed
  eject button lives.
- Entry: a "Pages" link in the explorer header (browse tool, not a create tool — it does
  not join the Create-with menu); Mail already links from there.

## D2 — Mail wiring lints (compile time)

A new `lintMailChannels(...)` in `AppLinter`, over every
`tesseraql.notifications.channels.<name>` with `type: mail` whose `template:` is a
literal path (a `${...}` placeholder value is env-dependent and skipped):

1. **Template file missing** → error, reusing **`TQL-BATCH-5304`** ("mail channel
   misdeclared") — the same fact the runtime raises at send time, surfaced at build
   time. App-home escape is also checked (same code).
2. **Unknown `tql/email` fragment** → error, new code **`TQL-TPL-2002`**: an `.html`
   template referencing `~{tql/email/hc-email(-layout) :: <name>}` where `<name>` is not
   in the fragment library. The contract is read from the app's shadow copy
   (`templates/tql/email/*.html`) when present, else the bundled classpath library —
   whichever file will actually resolve at render.
3. **Unresolvable model root** → warning, new code **`TQL-TPL-2003`**: a `${root...}`
   expression in the template body (or the channel's `subject` inline template) whose
   root is neither `payload` nor `event`, nor a `th:each`/`th:with` alias defined in the
   same template, nor a `#utility`. This is exactly the class of the helpdesk
   `${ticket}` bug; warning severity because expression aliasing can be arbitrarily
   clever.

Fragment-signature parsing moves from `MailComposer` (tesseraql-studio) down to a shared
`EmailFragments` helper in **tesseraql-yaml** (where the bundled library and the linter
live); `MailComposer.palette()` delegates. New codes are literal-registered
(`ErrorIndex` scans them); `reference-error-codes.md` regenerates, and the
`notifications.md` code table gains the two rows.

## Slices

1. **Pages overview** — `studio.pages` service + `ui/pages` page + explorer link +
   integration test.
2. **Mail wiring lints** — `EmailFragments` extraction + `lintMailChannels` (checks 1-3)
   + `AppLinterNotifyTest` + reference regen + docs.

## Deferred

- Fragment **arity** checking (invocation argument count vs. signature) — name existence
  only in v1; an arity mismatch still fails loudly in the render preview.
- Rendering mail templates in declarative tests (a `notify:` test case asserting the
  rendered body) — a testing-surface feature, not a lint.
- Pages-side write actions (eject directly from the listing) — the listing links to the
  confirmed button instead of duplicating the confirm/force flow.
