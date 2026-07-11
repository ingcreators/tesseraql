# Notifications

A command or batch job declares the notifications it sends in YAML: a
`notify:` block on a `command-json` route, or a `notify:` pipeline step on a job. A
notification names a **channel** — SMTP mail or an HMAC-signed webhook, configured under
`tesseraql.notifications.channels` — and a payload resolved from the execution context.

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

sql:
  file: provision.sql
  mode: update
  params:
    userName: body.userName

response:
  json:
    status: 200
    body:
      affected: sql.affectedRows
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
        deactivated: step.deactivatePending.affectedRows
```

A pipeline step declares exactly one of `sql:` or `notify:`. The step reports
`affectedRows: 1` and its `eventId` when the notification enqueued, `0` when the guard
skipped it.

## Per-user opt-out

A notification that names its **recipient** — an expression resolving to a subject, such as
`principal.subject` or `body.assignee` — honors that subject's per-channel opt-out, stored
as the `notify.<channel>.optOut` preference by the [account surface](account.md). The decision runs **at enqueue**, in the command's
transaction (and equally in a job's `notify:` step): an opted-out notification writes no
outbox row — nothing to retry, nothing half-delivered — and the command's `notify` context
reports `{optedOut: true}` in place of the event id.

Two rules keep this honest:

- A notification **without** `recipient:` is channel-level and always delivered — the
  cookbook example above sends `audit` regardless of anyone's preferences.
- Only channels the operator marks **`userOptOut: true`** appear on the account page's
  notification section, so operational channels are never user-disableable. The marker
  controls the *page*; the enqueue check applies to any recipient-naming notification on
  any channel.

The preference is looked up in the acting principal's tenant on command routes; job
contexts carry no principal and check the untenanted scope.

## Mail channels

Settings: `host` (required), `port` (default 25), `transport` (`smtp`/`smtps`, default
`smtp`), `from` and `template` (required), `to` (default recipient — a `to` key in the
notification payload overrides it per message), `subject`, `username`/`password`.

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

## Delivery, retries, dead letters

```yaml
tesseraql:
  outbox:
    dispatch:
      fixedDelay: 5s      # the dispatcher poll; absent = dispatch manually/embedded
      maxAttempts: 10     # the dead-letter ceiling (default 10)
```

An event's lifecycle is `PENDING → SENDING → SENT`, with `FAILED` (retried on the next
poll) and `DEAD` (attempts exhausted; never retried automatically) on the failure path.
Dead letters raise the `TQL-OPS-9006` operational alert — which itself notifies through the
alerts channel — and stay visible until an operator acts:

- the **Outbox** screen of the operations console (`/_tesseraql/ops/console/outbox`):
  recent deliveries with status, attempts, and last error, scoped to the caller's
  `ops.app.<name>` grants
- `GET /_tesseraql/ops/outbox` — the same delivery log as JSON (`ops.batch.view`)
- `POST /_tesseraql/ops/outbox/{id}/redeliver` — requeues a `FAILED`/`DEAD` event
  (`ops.batch.run`); the attempt count is kept so the history stays honest

Delivered events are swept by the standard retention job (`tesseraql.retention.outbox`).

## Operations alerts

With `tesseraql.notifications.alerts.channel` configured, the runtime notifies through that
channel:

- `ops.jobFailure` — a batch execution failed; payload `jobId`, `executionId`, `app`,
  `error`
- `ops.alert` — a dashboard alert was raised (error-rate, slow-rate, lane saturation,
  batch-failure-rate, pinning, dead letters); payload `code`, `severity`, `message`.
  Checked every `tesseraql.notifications.alerts.checkInterval` (default `60s`); each code
  notifies once while it stays raised.

## Testing notifications in declarative suites

A suite case evaluates a route's `notify:` block or a job's notify steps — guards and
payload expressions run exactly as at runtime — and the fired notifications are the case's
rows (`notify`, `channel`, `source`, plus the payload columns). No SMTP or HTTP is touched:

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
- a notification without a `channel:`, or a job step with both or neither of
  `sql:`/`notify:` (`TQL-FIELD-2004`)
- a malformed `when:` guard (`TQL-SQL-2101`)
- a channel the config does not declare (`TQL-YAML-1102`, warning — another environment's
  config may declare it)

## Error codes

| Code | Status | Meaning |
| --- | --- | --- |
| `TQL-FIELD-2004` | — | invalid notify declaration (build/lint time) |
| `TQL-YAML-1004` | — | lint: `notify:` on a non-command recipe |
| `TQL-YAML-1102` | — | invalid or undeclared notification channel |
| `TQL-BATCH-5301` | — | delivery: the referenced channel is not configured |
| `TQL-BATCH-5302` | — | delivery: the notification envelope failed to encode/decode |
| `TQL-BATCH-5303` | — | delivery: the webhook receiver answered non-2xx |
| `TQL-BATCH-5304` | — | delivery: mail channel misdeclared or template outside the app home |
| `TQL-OPS-9006` | — | alert: outbox events are dead-lettered |
