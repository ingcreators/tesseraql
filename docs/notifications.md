# Notifications

A command or batch job declares the notifications it sends in YAML: a
`notify:` block on a `command-json` route, or a `notify:` pipeline step on a
[job](jobs.md). A
notification names a **channel** — SMTP mail, an HMAC-signed webhook, or the in-app
[inbox](inbox.md), configured under `tesseraql.notifications.channels` — and a payload
resolved from the execution context.

Delivery rides the transactional outbox: the notification is written as a `NOTIFICATION`
event in the command's transaction (a rolled back command never notifies), then delivered
at-least-once by the outbox dispatcher. A failing delivery retries on later polls and
dead-letters at the configured attempt ceiling; both states stay visible in the operations
console. Operations alerts (job failures, threshold breaches) reuse the same channels.

## Channels

```yaml
tesseraql:
  notifications:
    channels:
      user-mail:
        type: mail
        host: ${MAIL_HOST:localhost}
        port: ${MAIL_PORT:2525}
        from: noreply@example.com
        to: ops@example.com                          # default recipient
        subject: "Account provisioned: [(${payload.userName})]"
        template: templates/mail/provisioned.txt     # rendered by the standard engine
        username: ${secret.env.SMTP_USER}            # optional, SecretResolver SPI
        password: ${secret.env.SMTP_PASSWORD}
      audit-webhook:
        type: webhook
        url: https://hooks.example.com/tessera
        secret: ${secret.env.WEBHOOK_SECRET}         # HMAC-SHA256 signing key
    alerts:
      channel: audit-webhook                         # operations alerts go here
```

A **webhook channel delivers through the one outbound gateway** every other call in the
framework uses, so its host must be in `tesseraql.http.outbound.allowedHosts` — deny by default,
like any other egress. A channel may also name a `credential:` and its own `connectTimeout:` /
`requestTimeout:`; unset, the app-wide outbound defaults apply. The HMAC signature is still
computed here, over the exact bytes sent.

> **Upgrading**: a webhook whose host is not allow-listed stops being delivered (its outbox rows
> fail and dead-letter with the reason). Add the host to `allowedHosts`. Deliveries used to
> bypass the allow-list entirely, which contradicted the deny-by-default posture the rest of
> this documentation states.

Channel settings resolve their `${...}` placeholders **at send time**, so credentials
declared through the SecretResolver SPI are fetched per delivery — never at startup, never
into logs or generated artifacts. A missing secret fails that delivery (retried and
dead-lettered like any other failure) instead of the runtime. An unsupported `type:` fails
at startup (`TQL-YAML-1102`).

## The notify block on a command

```yaml
version: tesseraql/v1
id: users.apiProvision
kind: route
recipe: command-json

notify:
  confirmation:
    channel: user-mail
    when: body.active == true        # optional guard; a falsy guard skips the notification
    recipient: principal.subject     # optional: honors that subject's opt-out
    payload:
      userName: body.userName
      givenName: body.givenName
  audit:
    channel: audit-webhook
    payload:
      userName: body.userName
      actor: principal.loginId

sources:
  main:
    sql:
      file: provision.sql
      mode: update
      params:
        userName: body.userName

response:
  json:
    status: 200
    body:
      affected: steps.main.affectedRows
      auditEventId: notify.audit.eventId   # each fired notification publishes its event id
```

Notifications enqueue **last in the command's transaction**, after validation and the steps:
a `422` or a constraint violation rolls everything back and nothing is sent. Each fired
notification publishes `notify.<id>.eventId` into the execution context.

## The notify step on a job

```yaml
pipeline:
  - id: deactivatePending
    sql:
      file: deactivate-pending.sql
      mode: update
  - id: report
    notify:
      channel: audit-webhook
      payload:
        deactivated: steps.deactivatePending.affectedRows
```

A pipeline step declares exactly one of `sql:` or `notify:` (or the other step bodies —
see [jobs](jobs.md)). The step reports `affectedRows: 1` and its `eventId` when the
notification enqueued, `0` when the guard skipped it.

On a **mail** channel, a notify step can carry a produced file with it — the
[export step](jobs.md#the-export-step)'s published transfer id names it:

```yaml
  - id: report
    sql: { file: report.sql, mode: query }
    export:
      format: csv
      filename: price-summary-{batch.businessDate}.csv
  - id: send
    notify:
      channel: reports              # a mail channel
      attach: steps.report.transferId
      payload:
        rows: steps.report.rows
```

`attach:` resolves at enqueue to a transfer id that rides the outbox envelope; the
**bytes are read from the transfer store at delivery time**, so the event stays small and
the at-least-once/retry/dead-letter policy is untouched. The mail goes multipart — the
rendered template body plus the file under its transfer filename. Attachments ride mail
channels only: a webhook posts JSON and an inbox message links, so `attach:` on either is
a build error rather than a silently dropped file.

## Per-user opt-out

A notification that names its **recipient** — an expression resolving to a subject, such as
`principal.subject` or `body.assignee` — honors that subject's per-channel opt-out, stored
as the `notify.<channel>.optOut` preference by the [account surface](account.md). The decision runs **at enqueue**, in the command's
transaction (and equally in a job's `notify:` step): an opted-out notification writes no
outbox row — nothing to retry, nothing half-delivered — and the command's `notify` context
reports `{optedOut: true}` in place of the event id.

Two rules keep this honest:

- A notification **without** `recipient:` is channel-level and always delivered — the
  example above sends `audit` regardless of anyone's preferences.
- Only channels the operator marks **`userOptOut: true`** appear on the account page's
  notification section, so operational channels are never user-disableable. The marker
  controls the *page*; the enqueue check applies to any recipient-naming notification on
  any channel.

The preference is looked up in the acting principal's tenant on command routes; job
contexts carry no principal and check the untenanted scope.

## Mail channels

Settings: `host` (required), `port` (default 25), `transport` (`smtp`/`smtps`, default
`smtp`), `from` and `template` (required), `to` (default recipient — a `to` key in the
notification payload overrides it per message), `subject`, `username`/`password`, and
`maxAttachmentBytes` (default 10485760) — the cap an [attached
transfer](#the-notify-step-on-a-job) must fit, a channel setting because the mail
server's limit is the operator's fact. An oversize attachment fails delivery naming the
setting; the attachment is buffered for the send, which is exactly what the cap bounds.

The body renders the channel's `template` with the standard engine and the standard trust
model: the template is app-authored and confined to the app home — it is never taken from
the payload. `.html` templates send `text/html`, everything else `text/plain`. The `subject`
is an inline TEXT template. Both render against the same model:

```text
Hello [(${payload.givenName})],

your account "[(${payload.userName})]" has been provisioned.

This message was sent by [(${event.app})] (event [(${event.id})]).
```

`payload` is the notification's resolved payload; `event` carries `id`, `source`
(`<routeOrJobId>.<notifyId>`), and `app`.

## HTML mail

The framework bundles the [hypermedia-components email fragment
library](https://ingcreators.com/hypermedia-components/integrations/html-email/) under the
`tql/email/*` template namespace, so an `.html` mail template composes robust HTML email —
table layout, `role="presentation"`, every style inline, because mail clients strip
external CSS — without hand-writing any of it:

```html
<div th:replace="~{tql/email/hc-email-layout :: hcLayout('Ticket assigned',
    |Ticket ${payload.ticket} was assigned to you|, ~{:: content})}">
  <div th:fragment="content">
    <div th:replace="~{tql/email/hc-email :: hcHeading('Ticket assigned')}"></div>
    <div th:replace="~{tql/email/hc-email :: hcText(|Ticket "${payload.ticket}" was assigned.|)}"></div>
    <div th:replace="~{tql/email/hc-email :: hcButton(${payload.url}, 'Open ticket')}"></div>
    <div th:replace="~{tql/email/hc-email :: hcFooter(|Sent by ${event.app}|)}"></div>
  </div>
</div>
```

`hcLayout(title, preheader, content)` is the 600px centered document shell
(`tql/email/hc-email-layout`); the wrapper hands its own `content` fragment to it, so one
file carries the whole mail (the helpdesk example's `templates/mail/assigned.html` is the
working reference). The fragment palette in `tql/email/hc-email`:

| Fragment | Signature |
| --- | --- |
| Button | `hcButton(href, label)`, `hcButtonSecondary(href, label)` |
| Heading | `hcHeading(text)`, `hcSubheading(text)` |
| Text | `hcText(text)`, `hcTextMuted(text)` |
| Link | `hcLink(href, label)` |
| Separator | `hcSeparator` |
| Badge | `hcBadge(label)`, `hcBadgeInfo/Success/Warning/Error(label)` |
| Alert | `hcAlertInfo/Success/Warning/Error(title, text)` |
| Panel | `hcPanel(content)` — content is a fragment expression |
| Key-value table | `hcKvTable(rows)` — rows iterable with `key`/`value` |
| Footer | `hcFooter(text)` |

The bundled library is theme-baked at the framework defaults (default accent, `slate`
neutral — the `tesseraql.ui.neutral` default), and comes straight from the
hypermedia-components package (`dist/email`, unpacked at build time with its published
`contract.json` as the signature contract — nothing generated is checked in). The one embedded `<style>` block in the
layout is enhancement-only (mobile widths, dark scheme) and may be stripped by clients
without breaking the mail; the load-bearing styling is inline. Known degradations are the
upstream-documented ones: Outlook's Word engine drops border radii, Gmail may auto-invert
colors in dark mode.

**Custom theme.** Eject your own artifacts and shadow the bundled ones file-for-file —
the same L2 move as view-pattern shadowing (docs/declarative-views.md):

```bash
npx @hypermedia-components/cli email eject --tokens my-theme.json \
    --dir <appHome>/templates/tql   # writes templates/tql/email/*
```

The generated files carry a manifest comment (core version, axes, regen command) — edit
the theme and regenerate rather than hand-editing.

**Build-time wiring checks.** A mail body is otherwise only exercised at delivery, so lint
validates the wiring:

- A mail channel's literal `template:` must be a file inside the app home
  (`TQL-BATCH-5304` — the send-time code, surfaced at build time).
- An `.html` body may reference only fragments the `tql/email` library declares
  (`TQL-TPL-2002`, read from the app's shadow copy when present).
- A `${...}` root in the body or `subject` that is neither `payload`/`event` nor a
  `th:each`/`th:with` alias warns (`TQL-TPL-2003`). A bind like `${ticket}` written for
  `${payload.ticket}` renders empty at delivery and is otherwise invisible to tests.

A `template:` value carrying a `${...}` config placeholder is environment-dependent and
skipped.

**Studio.** Studio's Mail page (sidebar → Mail) lists the app's mail channels and opens
an `.html` template composed from these blocks in a no-code composer: add/reorder/remove
blocks, edit their arguments, and preview the rendered mail against sample data before
applying — the draft/apply flow is the source editor's. A template outside the block
grammar opens read-only with the source editor as the escape hatch. **Send test mail**
delivers the exact draft body the preview shows over the channel's own SMTP to one
explicit recipient (subject rendered like a real delivery) — the last gap between the
preview and a real client. It needs the sandboxed dev tools opt-in
(`tesseraql.studio.testRunner.enabled`) and a mail channel declaring the template.

## Webhook channels

Settings: `url` (required) and `secret` (optional but recommended — without it the POST is
unsigned). The delivery is a JSON POST:

```json
{"source": "users.apiProvision.audit", "eventId": "…", "app": "user-admin",
 "payload": {"userName": "suzuki", "actor": "admin"}}
```

with headers:

- `X-TesseraQL-Timestamp` — epoch seconds at send time
- `X-TesseraQL-Signature` — `sha256=<hex>` of HMAC-SHA256 over `<timestamp>.<body>` with the
  channel secret

A receiver authenticates by recomputing the HMAC over the received timestamp header and the
raw body, comparing in constant time, and rejecting stale timestamps to bound replay. Any
non-2xx answer (or transport failure) counts as a failed attempt and is retried.

## Scheduled delivery

Everything on the outbox — `notify:`, `publish:`, `outbox:` — is delivered as soon after commit
as the dispatcher gets to it. An entry can instead name the instant before which it must not be
delivered.

### Choose this or a batch job, deliberately

Most recurring business reminders belong in a [batch job](jobs.md), not here. A job that queries
the current truth is the more common shape, and usually the better one:

```sql
-- batch/shipping-reminder/pick.sql — run daily by a cron trigger
select * from orders
where shipped_at = current_date - 3
  and reminder_sent_at is null
  and status <> 'cancelled'
```

That query decides *at run time* who gets a reminder, and everything follows from that:

- A cancelled order simply stops matching, so **no cancellation mechanism is needed at all**.
- Changing the window from three days to two takes effect immediately, for orders already
  shipped as much as for the next one.
- The run has an execution record you can inspect, and re-run for a given date.
- A hundred thousand reminders are one pass, not a hundred thousand rows waiting in a table.

Scheduled delivery makes the opposite trade: it **freezes the decision at commit time**. That is
worth having when the decision genuinely cannot be re-derived later, which is a narrower set than
it first appears:

| Use a batch job | Use `delay:`/`deliverAt:` |
| --- | --- |
| The audience is a query over business columns ("shipped three days ago") | The instant is per-record and irregular (`deliverAt: params.pickupStart`) |
| Cancellation is implied by the data ("not cancelled") | The payload is only reconstructible at commit |
| The cadence is daily or coarser | The delay is minutes, not days |
| Volume is high | Volume is low and event-shaped |

If a reminder can be expressed as "everything matching this predicate today", write the job. The
rest of this section is for the cases that cannot.

### The two declared forms

```yaml
notify:
  shipped-reminder:
    channel: customer-mail
    delay: 72h                       # relative to the commit
    cancelKey: steps.header.keys.id  # what a later command can withdraw it by
    payload: { order: steps.header.keys.id }
  pickup-window:
    channel: customer-mail
    deliverAt: params.pickupStart    # a bindable path resolving to an instant
    payload: { order: steps.header.keys.id }
```

- The entry is written in the command's transaction as before; the row carries `not_before`, and
  the dispatcher — which already polls — skips rows whose time has not come. No new mover, no
  new store.
- `delay:` and `deliverAt:` are two answers to one question: declaring both fails the build
  (`TQL-BATCH-5317`). A `deliverAt:` path that resolves to nothing means "no schedule", the way
  an absent optional input does; one that resolves to something that is not an instant is
  refused rather than guessed at.
- A `delay:` is measured from **one instant per command**, so two entries declaring `72h` come
  due together rather than microseconds apart.
- At-least-once semantics are unchanged: a not-before row that comes due delivers through the
  same retry and dead-letter path.
- `emit:` takes no schedule — it is a list of topic names, a liveness hint about *now*, so there
  is nowhere to write one.

### Cancelling a scheduled entry

An order cancelled on day two must not remind on day three. An entry filed under a `cancelKey:`
can be withdrawn by a later command, which declares the withdrawing form of the block —
`cancel:` instead of `channel:`:

```yaml
# the command that cancels the order
notify:
  shipped-reminder:
    cancel: params.orderId      # withdraws undelivered entries filed under this key
```

- The withdrawal runs **in the withdrawing command's own transaction**, where the authority to
  cancel has already been established by the command itself, and it touches only the outbox's
  own rows. A rolled-back cancellation withdraws nothing. The alternative — a delivery-time
  predicate re-evaluated against the document — would have the dispatcher reading application
  tables with no principal, tenant or scope to read them under, which is an authority story the
  outbox does not have and should not acquire for this.
- Withdrawn entries become `CANCELLED`, which is terminal like `SENT`: they are never claimed
  again, and they stay visible to operators rather than vanishing.
- `PENDING`, `FAILED` and `SENDING` entries are all withdrawn. `SENT` has happened and cannot be
  un-happened, and `DEAD` has stopped. A `SENDING` row is one a dispatcher is holding: withdrawing
  it cannot recall a request already on the wire, but it stops **every attempt after it** — a
  delivery failure will not write `FAILED` over the cancellation and put the entry back in the
  claim. If the in-flight delivery does succeed, the row records `SENT`, because it did send.
- A block declaring both `cancel:` and `channel:`, or `cancel:` with a schedule, fails the build
  (`TQL-FIELD-2004`): an entry either sends or withdraws, and later is what a withdrawal undoes.
- `steps.*` context is available, so the withdrawing command can name a key it just read.
- The withdrawal reports as `notify.<id>.withdrawn` — how many entries it withdrew — the way a
  send reports `notify.<id>.eventId`.

A withdrawal matches on the application and the key alone, not on the notification's name: two
entries filed under the same `cancelKey:` are withdrawn together. For "cancel the order, cancel
everything scheduled for it" — the case this exists for — that is the wanted behaviour; if you
need to withdraw one of several independently, file them under different keys.

`cancelKey:`/`cancel:` are declared on `notify:` in this first slice. `delay:`/`deliverAt:`
apply to `publish:` and `outbox:` too, because they are the same outbox row; a withdrawal for
those surfaces waits for a case that asks for it.

## Delivery, retries, dead letters

```yaml
tesseraql:
  outbox:
    dispatch:
      fixedDelay: 5s      # the dispatcher poll; absent = dispatch manually/embedded
      maxAttempts: 10     # the dead-letter ceiling (default 10)
```

An event's lifecycle is `PENDING → SENDING → SENT`, with `FAILED` (retried on the next
poll), `DEAD` (attempts exhausted; never retried automatically) and `CANCELLED` (withdrawn
before delivery, see above) on the failure path.
A sink may classify a failure as one no retry can fix — a SCIM provider host outside the
egress allow-list, for example. Such an event dead-letters on the first attempt instead of
burning the ceiling on identical refusals; fix the configuration, then redeliver it.
Dead letters raise the `TQL-OPS-9006` operational alert — which itself notifies through the
alerts channel — and stay visible until an operator acts:

- the **Outbox** screen of the [operations console](ops-console.md) (`/_tesseraql/ops/console/outbox`):
  recent deliveries with status, attempts, and last error, scoped to the caller's
  `tql.ops.view.<name>` grants
- `GET /_tesseraql/ops/outbox` — the same delivery log as JSON (view-scoped)
- `POST /_tesseraql/ops/outbox/{id}/redeliver` — requeues a `FAILED`/`DEAD` event
  (requiring `tql.ops.run.<name>`); the attempt count is kept so the history stays honest

Delivered events are swept by the standard retention job (`tesseraql.retention.outbox`).

## Operations alerts

With `tesseraql.notifications.alerts.channel` configured, the runtime notifies through that
channel:

- `ops.jobFailure` — a batch execution failed; payload `jobId`, `executionId`, `app`,
  `error`
- `ops.jobSla` — a job's declared `sla:` was missed ([jobs](jobs.md)): payload `jobId` and
  `kind` (`runningLongerThan` with `executionId`/`threshold`/`startedAt`, or `completeBy`
  with `deadline`/`businessDate`). Checked every `tesseraql.batch.slaSweepInterval`
  (default `60s`); each miss alerts once — per execution, or per business date
- `ops.alert` — a dashboard alert was raised (error-rate, slow-rate, lane saturation,
  batch-failure-rate, pinning, dead letters); payload `code`, `severity`, `message`.
  Checked every `tesseraql.notifications.alerts.checkInterval` (default `60s`); each code
  notifies once while it stays raised.

## Testing notifications in declarative suites

A suite case ([testing](testing.md)) evaluates a route's `notify:` block or a job's notify
steps — guards and payload expressions run exactly as at runtime — and the fired
notifications are the case's rows (`notify`, `channel`, `source`, plus the payload
columns). No SMTP or HTTP is touched:

```yaml
tests:
  - name: provisioning an active user notifies mail and webhook
    notify:
      route: users.apiProvision      # or job: user.dailyMaintenance
      # id: confirmation             # optional: narrow to one declaration
    params:
      body:
        userName: sato
        active: true
    expect:
      rowCount: 2
      rows:
        - notify: confirmation
          channel: user-mail
          userName: sato
```

## The notification coverage kind

Every route notification is declared as `<routeId>.<notifyId>` and every job notify step as
`<jobId>.<stepId>`; a notify case covers the declarations it evaluates. Gaps surface in the
coverage report and as SARIF findings, and `coverage.thresholds.notification` gates
the build like any other kind.

## Lint

- `notify:` on a non-command recipe (`TQL-YAML-1004`)
- a notification without a `channel:`, a job step with both or neither of
  `sql:`/`notify:`, or `attach:` on a channel that is not mail (`TQL-FIELD-2004`)
- a malformed `when:` guard (`TQL-SQL-2101`)
- a channel the config does not declare (`TQL-YAML-1102`, warning — another environment's
  config may declare it)
- a mail channel's `template:` that is not a file inside the app home
  (`TQL-BATCH-5304`, at build time), an `.html` body referencing an unknown `tql/email`
  fragment (`TQL-TPL-2002`), and a `${...}` root outside `payload`/`event`/template
  aliases in the body or `subject` (`TQL-TPL-2003`, warning) — see
  [HTML mail](#html-mail)
- a mail channel with no default `to:` (`TQL-BATCH-5304`, warning — delivery fails
  unless every notification payload carries a `to` key)

## Error codes

| Code | Meaning |
| --- | --- |
| `TQL-FIELD-2004` | invalid notify declaration (build/lint time) |
| `TQL-YAML-1004` | lint: `notify:` on a non-command recipe |
| `TQL-YAML-1102` | invalid or undeclared notification channel |
| `TQL-BATCH-5301` | delivery: the referenced channel is not configured |
| `TQL-BATCH-5302` | delivery: the notification envelope failed to encode/decode |
| `TQL-BATCH-5303` | delivery: the webhook receiver answered non-2xx |
| `TQL-BATCH-5304` | delivery/lint: mail channel misdeclared or template missing/outside the app home |
| `TQL-TPL-2002` | lint: mail template references an unknown `tql/email` fragment |
| `TQL-TPL-2003` | lint: mail body/subject `${...}` root outside the mail model (warning) |
| `TQL-BATCH-5317` | a scheduled entry declares both `delay:` and `deliverAt:`, or an unusable instant |
| `TQL-OPS-9006` | alert: outbox events are dead-lettered |

## Next

- [inbox.md](inbox.md) — the in-product side of the same messages.
- [messaging.md](messaging.md) — events to other systems rather than people.
- [ops-console.md](ops-console.md) — watching delivery, and redelivering a failure.
