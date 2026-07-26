# Ops Console Actions

Design document. The ops console today is a read-only telemetry surface: five GET
pages over `ops.batch.view`. Every write an operator actually needs during an
incident already exists — as a bearer-token JSON API in `OperationsRouteBuilder`
(`POST /_tesseraql/ops/outbox/{id}/redeliver`, `POST
/_tesseraql/ops/batch/jobs/{jobId}/run`) — so the console's job is to stop telling
operators to curl. `outbox.html` literally prints the curl instruction, and
`OpsViews.outbox` has computed a per-row `dead` flag "so the screen can offer
redelivery" since the page shipped, with no template ever reading it.

This document decides how the console gains write actions, and cuts the first two:
**outbox redelivery** and a **jobs page with manual run**.

## Decisions

### 1. Console writes are plain YAML routes, one per action

Each action is a `command-json` route in the ops-console `.tqlapp`, exactly the
shape every other bundled app already uses (studio `flags/set`, account
`password`): `sql.service` to a runtime provider, `response.redirect` with an
explicit `status: 303` back to the page with a flash query parameter
(`?redelivered=1`). No Java REST routes, no new machinery — the app-level
`security.defaults.routes` rule (`match: /**, auth: browser, csrf: auto`) already
makes CSRF mandatory on any non-GET route, and `applyCommonGovernance` gives every
new route telemetry + audit (`tesseraql.audit.routes.enabled`) for free.

### 2. Parity with the JSON ops API, policy for policy

A console write is a hypermedia face over an existing ops API semantic — never a
new power. Write routes declare `security.policy: ops.batch.run` per-route (the
same policy the JSON endpoints enforce); GET pages stay on `ops.batch.view`. The
backing providers re-derive app scope from `principal.permissions` through
`OpsScope.allowedApps` and treat out-of-scope exactly like unknown (the JSON
handlers' stance), so the service layer holds its own gate even if a route
declaration drifts.

### 3. Plain forms, not htmx swaps

Action forms are classic `<form method="post">` with the hidden `_csrf` input and
the kit's confirm gate (`data-hc-confirm`) where the action is consequential. The
303 redirect reloads the page and the existing 15s poller keeps it fresh
afterwards. The studio drafts pattern (`hx-post` + `hc:confirmed`) is deliberately
not used here: every console page wraps its whole content in a poll-driven
`outerHTML` swap, and a row-level htmx swap racing a page-level poller is a bug
farm for zero UX gain — after a redeliver or a run, the whole page's state is what
changed.

### 4. Buttons render unconditionally

Templates cannot evaluate policies (`ops.batch.run` resolves against the caller's
permissions inside the auth processor, not in the model), and inventing a
provider-computed `canRun` flag would duplicate policy logic in a second place.
Buttons render for every viewer; an unauthorized POST is refused by the route's
policy. Honest, simple, and consistent with deny-by-default doing the real work.

## Slice 1 — outbox redelivery

- Provider `ops.outboxRedeliver(id, permissions)`: find → scope test →
  `JdbcOutboxStore.redeliver(id)` (already guarded to `FAILED`/`DEAD`, attempts
  deliberately not reset).
- Route `web/_tesseraql/ops/console/outbox/redeliver/post.yml`: input `id`
  (required), policy `ops.batch.run`, redirect to
  `/_tesseraql/ops/console/outbox?redelivered=1`.
- `outbox.html`: an Actions column with a Redeliver button on **DEAD rows only**
  (`row.dead`, finally consumed). `FAILED` rows are still the dispatcher's to
  retry; manual requeue is for events the dispatcher has given up on. The curl
  prose at the bottom of the page goes away.
- Flash banner on `redelivered=1`, mirroring studio's `saved=1` pattern.

## Slice 2 — jobs page and manual run

- Provider `ops.jobs(permissions)`: the job catalog (id, owning app, trigger
  summary) filtered by scope, each joined with its most recent execution (status,
  startedAt, executionId) so the page answers "when did this last run and how did
  it go" — the question the JSON API's bare id array cannot.
- Page `GET /_tesseraql/ops/console/jobs` (`ops.batch.view`), a `Jobs` entry in
  `ops-nav` between Overview and Traces, rows linking to the existing
  `/executions/{id}` detail page.
- Provider `ops.jobRun(jobId, permissions, actor)` over the same runner lambda the
  JSON API uses; route `jobs/run/post.yml` (`ops.batch.run`) redirects to the new
  execution's detail page.
- **The actor is finally recorded.** `JobExecution` gains a nullable
  `triggeredBy`; manual runs store the principal's loginId, scheduled runs store
  null. Pre-1.0: column added to the execution schema outright, CHANGELOG entry,
  no shim. The execution detail page renders it ("manual, by
  ops@example.com").
- Manual runs go out with empty params in this slice. A params form (the JSON API
  accepts a body) is a later extension — it wants input metadata per job, which is
  its own design.

## Follow-up candidates (not in these slices)

- A read-only **audit page** over `JdbcRouteAuditStore.recent` (the API exists,
  flag-gated; the page renders only when the store is enabled).
- Health detail (per-datasource probe map, the DOWN state), a version/build panel,
  and session administration — each rides the same action machinery once it
  exists, and each is its own design conversation.

## Testing

- `OpsConsoleIntegrationTest`: the jobs page renders the scoped catalog; a run
  POST creates an execution, records the actor, and redirects to its detail; a
  redeliver POST flips a DEAD event back to PENDING and the button only renders on
  dead rows; both POSTs 403 for a caller holding only `ops.batch.view`; both POSTs
  refuse without a CSRF token.
- `BundledAppSecurityPostureTest` picks the new routes up automatically; the new
  route files join `.app-index`.

## Out of scope

- i18n of console labels: every bundled app is hardcoded English today; localizing
  them is one campaign across studio/account/ops, not a per-page side quest.
- Redelivering `FAILED` (not yet dead) events, run-with-params, pausing/resuming
  schedules: all real, all separate.
