# Ops Console Coverage

Design document. [ops-console-actions.md](ops-console-actions.md) gave the console its
write machinery and named its follow-ups. One of them — session administration — shipped
on the IAM Admin user page ([session-administration.md](session-administration.md)). The
remaining candidates are one coherent wave over the machinery that now exists:

1. the **audit page** the follow-up list promised once the read API existed (it does:
   `GET /_tesseraql/ops/audit`, policy-gated, scope-narrowed);
2. **health detail and a version panel** — `OpsDashboard.health()` already computes
   DOWN/WARN/UP with per-datasource probe results and nothing renders it;
3. **run-with-params** — deferred because "it wants input metadata per job, which is its
   own design". That design has since shipped: declared `jobs: input:` bind and validate
   through `bindJobParams` at every entry point
   ([yaml-surface-consumers.md](yaml-surface-consumers.md)). The prerequisite is met; the
   form is what is missing.

## Decisions

### 1. The audit page is always mounted; the empty state is honest about the flag

Bundled-app routes are static YAML — a `.tqlapp` cannot mount a page conditionally on the
host's configuration — so `GET /_tesseraql/ops/console/audit` always exists
(`ops.batch.view`, an *Audit* entry in ops-nav), and the provider reports whether the
store is on. Disabled renders one line naming `tesseraql.audit.routes.enabled` instead of
an empty table pretending there was nothing to show — the "corrupt reads become visible"
stance from the Studio schema work, applied to a flag-gated store. Enabled renders
`routeAudit.recent(200, scope)`: the same rows, the same `ops.app.<name>` narrowing, the
same policy as the JSON API. The params JSON column is shown — the JSON API already
returns it to the same policy holders, so the console adds no new exposure.

### 2. Health detail and the version join the overview page, not a new page

The overview model gains a health panel — the roll-up badge and the per-datasource probe
map `health()` already computes — and a version line from `TesseraqlVersion.current()`,
the single source the CLI already reports. No new route: "is the platform healthy, and
what is deployed here" belongs on the operator's first screen, and the existing 15s
poller keeps it live.

### 3. Manual runs gain a declared-input form; the binding stays where it is

The jobs page renders, for each job with declared `input:`, input fields from the
declaration — a required marker from `required: true`, a numeric input for
`type: number`. Fields post as `param.<name>` beside `id`; the run route sets
`inputPolicy.unknownFields: ignore` (dynamic names cannot be statically declared) and
passes the posted body to the provider whole (`body` is a bindable path like any other).
The provider collects the `param.`-prefixed entries, strips the prefix, and hands the map
to the runner — where `bindJobParams`, the single binding point all three run entry
points share, coerces and validates exactly as the ops API does. The console never grows
a second validation layer. Prefixing beats posting bare names: `id` and `_csrf` can never
collide with a job parameter, and the provider's contract — everything under `param.` —
stays independent of any input the route may declare later.

### 4. A binding failure renders the error envelope, and that is acceptable here

The console deliberately has no in-page swap machinery (ops-console-actions decision 3),
so a refused binding — missing required parameter, uncoercible number — renders the same
field-error envelope the ops API returns, not an inline field error. The form's
client-side `required` and numeric input types catch the common misses before the post;
what remains is a technical operator reading the payload their own API speaks. An hc
field-errors rendering wants the htmx pattern this console rejected; revisiting that is a
UX conversation for all console writes at once, not a reason to validate twice.

## Slices

1. **Audit page**: provider `ops.audit(permissions)` (rows + enabled flag), route + page +
   nav entry, `.app-index` additions.
2. **Health + version on the overview**: `ops.overview` model gains `health` (status,
   datasources) and `version`; overview template renders the panel.
3. **Input form on the jobs page**: declared inputs in the `ops.jobs` row model, the
   form, `unknownFields: ignore` + whole-body pass-through on the run route, prefix
   stripping in `ops.jobRun`.

Each slice ships independently; the first two are read-only.

## Out of scope

- Pause/resume of schedules, redelivering `FAILED` (not yet dead) outbox events, console
  i18n — unchanged from ops-console-actions.md.
- Audit page paging/filtering beyond the newest 200: the JSON API has the same window;
  widening it is one decision for both surfaces when operating practice asks.
- Rendering job parameter *descriptions* or defaults in the form beyond name, type, and
  required: the declaration carries what it carries today.

## Testing

- `OpsConsoleIntegrationTest`: the audit page renders scoped rows when the store is
  enabled and names the flag when disabled; the overview shows the health badge, the
  per-datasource states, and the version; a run POST with declared inputs reaches the
  execution with coerced values, a missing required parameter is refused with the
  field-error envelope before the job starts, and the input form appears only on jobs
  that declare parameters.
- `BundledAppSecurityPostureTest` picks the audit route up automatically; the write path
  keeps its existing policy/CSRF tests.
