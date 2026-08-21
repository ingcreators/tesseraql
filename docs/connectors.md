# Managed connectors

TesseraQL apps integrate with neighbouring systems through **managed connectors**: governed
recipes for files and HTTP. Camel's component catalog stays an
implementation detail — an app never writes a raw endpoint URI; it declares a connector that
runs under the framework's allow-lists, secrets, lint, and coverage.

This page covers the outbound `http:` pipeline step, the inbound directory-polling trigger
for `file-import`, and the inbound `webhook` recipe. For publish/subscribe between commands and
other systems — domain events on a broker-free database channel — see
[messaging and events](messaging.md).

## The `http:` pipeline step

An `http:` step is a batch-pipeline step that issues one synchronous outbound REST request
and publishes the response to later steps. It interleaves with SQL steps, so a job can fetch
from an API and persist the result, or read from the database and push it to a partner system.

```yaml
version: tesseraql/v1
id: rates.refresh
kind: job
recipe: batch-pipeline

input:
  base:
    type: string
    required: false

pipeline:
  - id: fetch
    http:
      method: GET                                   # defaults to GET
      url: https://api.partner.example/v1/rates     # host must be allow-listed
      query:
        base: params.base                              # bound from the step context
      credential: partner                           # a configured credential, never inline
      expectStatus: 200                             # optional; omitted, any 2xx succeeds
      connectTimeout: 5s                            # optional per-step override
      requestTimeout: 20s                           # optional per-step override

  - id: store
    sql:
      file: store-rate.sql
      mode: update
      params:
        base: params.base
        rate: steps.fetch.body.rate                  # the parsed JSON response feeds the SQL step
```

A call is an acquisition, so it publishes the envelope every read publishes, plus what is
particular to a call:

| Context path             | Value                                                         |
| ------------------------ | ------------------------------------------------------------- |
| `steps.<id>.rows` / `.rowCount` / `.first` | the response as rows — the part `select:` names, or the whole body |
| `steps.<id>.status`       | the HTTP status code (an integer)                             |
| `steps.<id>.body`         | the selected JSON (a map/list) when the response is JSON, else the raw text |
| `steps.<id>.headers`      | the response headers (first value per name)                   |
| `steps.<id>.spool` / `.rowCount` | under `mode: query-spool`: the spool the rows were streamed to, for a later `chunk:` step ([jobs](jobs.md#loading-what-another-step-read)) |

A step declares at most one binding arm — `sql:` or `http:` — beside any output blocks
(`export:`, `push:`, `notify:`); see [pipeline steps](jobs.md#pipeline-steps-and-the-step-context).
The `query:` values and
`body:` are source expressions bound from the step context exactly like a SQL step's params;
static `headers:` values may carry `${...}` config or secret placeholders, resolved at call
time. `body:` resolves a single context expression and is sent as JSON. `expectStatus:` pins
success to one exact status — without it any `2xx` succeeds — and `connectTimeout:` /
`requestTimeout:` override the configured defaults per step.

### Why a job step, not a command step

`http:` is a job-pipeline step, never a transactional `command-json` step. A command runs
every step in one database transaction, and a synchronous outbound call cannot be rolled back —
so putting it inside a command would break the all-or-nothing guarantee. A command's outbound
integration instead rides the transactional outbox as an HMAC-signed webhook (see
[notifications](notifications.md)): the event is written in the transaction and delivered
at-least-once afterwards. Use `http:` when a pipeline needs the **response** to drive
subsequent steps; use a webhook notification for fire-and-forget delivery.

## HTTP sources on query routes

The read-side counterpart of the `http:` step: a query route can compose an external JSON API
with its SQL result **in one screen or one JSON response**, declaratively. An outbound call is
a source like any other — an entry in `sources:` whose arm is `http:` instead of `sql:` — so it
lands in the execution context exactly like a query, and everything downstream refers to it by
name without knowing how it was fetched. It carries the same call vocabulary a job step does —
`method`, `url`, `headers`, `query`, `body`, `credential`, `expectStatus`, and the timeouts —
plus `select:` and `onError:`, which are the read side's own:

```yaml
# web/orders/get.yml
version: tesseraql/v1
id: orders.list
kind: route
recipe: query-json
sources:
  main:
    sql:
      file: orders.sql
  rates:
    http:
      url: ${tesseraql.connectors.fx.baseUrl}/v1/rates
      query:
        base: query.currency        # expressions over the execution context
      credential: fx-api            # tesseraql.http.outbound.credentials.fx-api
      select: rates                 # dotted path to the rows array inside the JSON
      onError: empty                # a dead upstream degrades to zero rows
response:
  json:
    status: 200
    body:
      orders: main.rows
      fx: rates.rows
```

- **`<name>.rows`** — the selected JSON as rows (an array is one row per element, an object is
  a single row), so an HTML view composes it too: a detail child or a dashboard panel with
  `source: rates` renders API rows through the same table pattern as SQL rows.
- **`<name>.body`** — the selected JSON as-is, for scalar shaping (`rates.body.base`);
  `<name>.status` carries the upstream status.
- **`onError: empty`** (default `fail`) keeps a widget-shaped source from taking the page
  down: the source yields zero rows plus `<name>.error`, and everything else renders.
- **The same discipline as an `http:` step**: sources execute through the one outbound gateway
  — the deny-by-default `allowedHosts` list, named secret-managed credentials, connect/request
  timeouts, and the per-host circuit breaker. Lint enforces the surface: read and transactional
  recipes only (`TQL-YAML-1022`), plus the same host/url/credential checks as a job step
  (`TQL-SEC-4070/4071/4072`). There is no name-collision check to make: sources share one
  namespace, so two of them cannot shadow each other.
- **The method is not the guarantee**: a source declares `method:` and `body:` like any other
  call, because a reference API is as often `POST …/search` with a list of keys as it is a
  `GET`. A non-GET method is written out rather than inferred from `body:`, and a `body:`
  beside a method that carries none is a build error (`TQL-YAML-1049`), not a body silently
  dropped.

### A command's sources run before its transaction

A write often needs a value only the partner has — the name behind a code, as of this
transaction. An `http:` source is available on the transactional recipes (`command-json`,
`webhook`, `queue-consume`) and `sources:` run **before the connection is acquired**, so the
transaction never waits on a third party:

```yaml
recipe: command-json
sources:
  partner:
    http:
      url: https://crm.example.com/partners/{...}
      readOnly: true            # required here — see below
steps:
  - id: header
    sql:
      file: insert-order.sql
      params:
        partnerName: partner.body.name
```

- **Fail-closed.** A failed fetch fails the command before a row is written. `onError: empty`
  is still available and says the value is optional.
- **`readOnly: true` is required** (`TQL-YAML-1050`). The call happens before the transaction
  and **a rollback cannot un-make it**, so the author states that the call is a reference. The
  framework guarantees the declaration exists, not that it is true — a call with a side effect
  belongs *after* the commit, on the [outbox](notifications.md).
- **Declare short timeouts.** Unlike a page, the caller waits and the write queues behind the
  call; `connectTimeout:`/`requestTimeout:` and the circuit breaker apply as everywhere else.

Calling a partner **after** the write is the outbox's job, not this one: a `notify:` webhook is
written in the same transaction and delivered at-least-once afterwards. Its response decides
success, never data — a command that must *store* the partner's answer writes a pending row and
lets a job's `http:` step complete it.

- An `http:` **test case** plans a route's sources like a job's steps, without a network
  request: `http: {route: orders.list}` rows carry the resolved url, host, allow-list
  verdict, and credential — and `send: true` performs the call for real against the runner's
  capture server ([testing](testing.md#real-send-cases)).

## Outbound policy

All outbound HTTP is governed by `tesseraql.http.outbound`. Egress is **deny by default**: a
call may only target a host in `allowedHosts`, so a step can never reach an arbitrary URL.
The same allow-list gates the [Studio copilot](copilot.md) endpoint — a configured copilot
whose endpoint host is not allow-listed fails the boot with `TQL-SEC-4085`.

```yaml
tesseraql:
  http:
    outbound:
      allowedHosts:                 # deny-by-default egress allow-list
        - api.partner.example       # an exact host
        - "*.internal.example"      # any sub-domain of internal.example
      connectTimeout: 5s            # default; per-step override via connectTimeout:
      requestTimeout: 30s           # default; per-step override via requestTimeout:
      circuitBreaker:
        failureThreshold: 5         # consecutive systemic failures before the host opens
        openDuration: 30s           # how long the host stays open (fails fast) before a trial
      credentials:
        partner:
          type: bearer              # Authorization: Bearer <token>
          token: ${secret.env.PARTNER_TOKEN}
        legacy:
          type: basic               # Authorization: Basic base64(user:pass)
          username: ${secret.env.LEGACY_USER}
          password: ${secret.env.LEGACY_PASS}
        keyed:
          type: header              # an arbitrary header carrying a key
          header: X-API-Key
          value: ${secret.vault.api_key}
```

Credential settings resolve their `${...}` placeholders **at call time**, so secrets declared
through the SecretResolver SPI are fetched per call — never at startup, never into logs or
generated artifacts. An unsupported credential `type:` fails at startup (`TQL-YAML-1109`).

### Circuit breaker

A per-host circuit breaker trips after `failureThreshold` consecutive **systemic** failures —
transport errors, timeouts, and `5xx` responses — and stays open for `openDuration`, failing
fast rather than hammering a struggling dependency until a half-open trial succeeds. A `4xx`
response or an `expectStatus` mismatch fails the step but does **not** trip the breaker: it is a
deterministic rejection, not a sign the dependency is down.

A call is successful when its status is `2xx`, or equals `expectStatus` when one is declared;
any other outcome fails the step (and so the job). The call is recorded as a
`tesseraql.http.call` span in the job's trace, visible in the [operations console](ops-console.md).

## Governance

`http:` surfaces under the existing governance model — the host allow-list is the egress
control, enforced both statically (lint) and at runtime (deny by default). Lint of a job's
pipeline catches misconfigured egress before it ships:

| Code           | Severity | Meaning                                                                 |
| -------------- | -------- | ----------------------------------------------------------------------- |
| `TQL-SEC-4070` | error    | the target host is not in `tesseraql.http.outbound.allowedHosts`        |
| `TQL-SEC-4071` | error    | the step has no absolute `http`/`https` url                             |
| `TQL-SEC-4072` | warning  | the step references a credential not declared under `credentials`       |

A url whose host is an unresolved `${...}` secret cannot be checked statically and is left to
the runtime's identical deny-by-default guard. At runtime an off-allow-list host is
`TQL-BATCH-5305`, an open circuit is `TQL-BATCH-5306`, and a failed call is `TQL-BATCH-5307`.

## Testing

An `http:` declarative test ([testing](testing.md)) **plans** a job's steps against the
case's params — resolving the url, binding query params, and applying the allow-list — without
issuing a network request.
Each planned request is a row, so a suite asserts the recipe is wired correctly and the
`http:` coverage kind tracks it.

```yaml
tests:
  - name: the refresh job calls the allow-listed partner API
    http:
      job: rates.refresh
      id: fetch                       # optional; omit to plan every http: step of the job
    params:
      job: { base: "USD" }
    expect:
      rows:
        - http: fetch
          method: GET
          host: api.partner.example
          allowed: true
          url: https://api.partner.example/v1/rates?base=USD
```

Gate coverage with `coverage.thresholds.http` like any other kind.

## The `poll:` trigger for `file-import`

A `file-import` job can be driven by a **directory-polling trigger** instead of an HTTP upload:
the runtime watches a source directory and feeds every file it finds through the job's
`import:` pipeline (the same per-row 2-way SQL a `file-import` route applies). The source is a
local directory or a remote SFTP/FTPS server.

```yaml
version: tesseraql/v1
id: orders.intake
kind: job
recipe: file-import

trigger:
  poll:
    transport: sftp              # local | sftp | ftps
    host: sftp.partner.example   # remote sources only; must be allow-listed
    port: 22                     # defaults to 22 (sftp) / 21 (ftps)
    path: /outbound/orders       # directory to poll; a leading slash is absolute on the
                                 # server, without one it resolves against the login home
    credential: partner-sftp     # a configured credential, never inline (remote sources)
    include: "*.csv"             # filename glob (default: every file)
    delay: 60s                   # poll interval (default 60s)
    move: .done                  # processed files move here (default .done)
    moveFailed: .error           # files that could not be ingested move here (default .error)
    consumeOnce: true            # one file, one replica (default false)

A **local** source needs a declared root, the same deny-by-default rule remote sources get from
`allowedHosts`:

```yaml
tesseraql:
  connectors:
    poll:
      allowedPaths:               # deny-by-default roots for transport: local
        - inbound
        - /srv/partner-drop
```

The `path:` resolves under one of those roots, is normalized, and is re-checked — so `..` cannot
climb out. Without a root the job is refused, and lint says so first (`TQL-SEC-4093`). This is
not only about reading: the poll consumer **moves** what it ingests, so an unanchored path can
relocate a live directory's contents into `.done`.

### One file, one replica

Every poll source carries a write-stability check, so a file still being written is not read
half-formed. That is not the same as deciding **which** replica gets it: on no transport —
`local` included — is there any server-side exclusion, so three replicas polling one drop
directory each import every file. The job claim does not cover this: it is per *firing*, not per
file.

`consumeOnce: true` puts a shared store behind the source, so the first replica to claim a file is
the one that imports it:

```yaml
  poll:
    transport: sftp
    host: sftp.partner.example
    path: /outbound/orders
    credential: partner-sftp
    consumeOnce: true
```

Lint warns (`TQL-YAML-1310`) when any source leaves it off, `local` included.

**It changes what a re-sent file means, which is why it is opt-in.** A file is identified by name,
size and modified time — not by path, which would suppress a partner's daily `orders.csv` forever
after the first one. So a partner re-sending a *byte-identical* file is skipped rather than
imported again, for as long as the claim is retained:

```yaml
tesseraql:
  connectors:
    poll:
      consumedRetention: 30d      # how long a consumed file is remembered (default 30d)
```

Outside that window the same bytes are imported again. With `consumeOnce` off — today's behaviour
for every source — a re-sent file is always re-imported.

`move:` and `moveFailed:` must be plain relative directory names. A value like
`${file:parent}/../archive` would write the polled file outside the poll tree — on the FTPS
transport it is evaluated as an expression, not read as a name — so such values are rejected
rather than escaped.

import:                          # the same import: block a file-import route uses
  format: csv
  columns:
    - orderNo
    - { name: qty, type: number }
  onError: skip
pipeline:
  - id: row
    sql:
      file: upsert-order.sql     # runs once per row; params are the column names
```

Each file is ingested through the same asynchronous, off-heap path an HTTP upload takes and is
tracked as a **transfer** in the operations console — so row-level outcomes (rejected rows under
`onError: skip`) show up there, exactly like an uploaded file. A file moves to `move` once it has
been ingested; a file that cannot be read moves to `moveFailed`. How a directory is reached is an
implementation detail; the YAML never names an endpoint.

### Remote sources

Reaching a remote host is **deny by default**. A remote `poll:` source may only target a host in
`tesseraql.connectors.poll.allowedHosts`, and its credentials come from
`tesseraql.connectors.poll.credentials` (resolved through the SecretResolver SPI when the consumer
starts, never inline):

```yaml
tesseraql:
  connectors:
    poll:
      allowedHosts:                 # deny-by-default egress allow-list (exact or *.wildcard)
        - sftp.partner.example
      credentials:
        partner-sftp:
          username: ${secret.env.SFTP_USER}
          password: ${secret.env.SFTP_PASS}
```

The SSH host key of an SFTP edge is verified against an OpenSSH known-hosts file when
`tesseraql.connectors.poll.knownHostsFile` is set (a path resolved against the app home, or an
absolute path). The consumer then runs with strict host-key checking, so a server whose key is
not pinned in that file is refused:

```yaml
tesseraql:
  connectors:
    poll:
      knownHostsFile: security/known_hosts   # pins the SSH host keys SFTP sources may present
```

Without it, host keys are not checked and lint nudges with `TQL-SEC-4084` (a warning, so
existing apps keep working).

FTPS rides the same recipe and runtime path with `transport: ftps`. The endpoint negotiates
`PBSZ 0`/`PROT P`, so the file's bytes are encrypted and not only the login, and it transfers in
binary and connects in passive mode. Its server identity is pinned by a trust store — the FTPS
counterpart of `knownHostsFile`:

```yaml
tesseraql:
  connectors:
    poll:
      allowedHosts: [ftps.partner.example]
      trustStore:
        file: security/partner-ca.p12      # relative to the app home, or absolute
        password: ${secret.ftps.truststore}
```

The server's certificate must chain to that keystore and its hostname must match. This one is
**required**: without it the underlying client accepts any in-date certificate from any host, so
an `ftps` source with no `trustStore` is refused at startup and lint reports `TQL-SEC-4085`
first. (`knownHostsFile` stays a warning by contrast — SSH host keys have a legitimate
trust-on-first-use posture that a CA bundle does not.)

### Governance and testing

Lint catches a misconfigured poll job before it ships, and at runtime a job that targets a
non-allow-listed host (or has no `import:` block) is logged and skipped rather than taking the
app down:

| Code           | Severity | Meaning                                                               |
| -------------- | -------- | --------------------------------------------------------------------- |
| `TQL-SEC-4080` | error    | a remote source's host is not in `tesseraql.connectors.poll.allowedHosts` |
| `TQL-SEC-4081` | warning  | the trigger references a credential not declared under `credentials`  |
| `TQL-SEC-4084` | warning  | an SFTP source polls without `tesseraql.connectors.poll.knownHostsFile` (host key unchecked) |
| `TQL-YAML-1054`| error    | the transport is not local/sftp/ftps, has no path, or a remote source has no host |
| `TQL-YAML-1055`| error    | a poll-triggered job has no `import:` block with a per-row SQL         |

A poll job is covered by the `file-poll` coverage kind when a declarative suite exercises its
per-row import SQL (a plain `sql:` case), the same SQL-file basis as route and document coverage.
Gate it with `coverage.thresholds.file-poll`.

## The `push:` step — outbound delivery

The outbound mirror of `poll:` (docs/analytics-experience.md): a batch pipeline's
[`push:` step](jobs.md#the-push-step) delivers a produced transfer — typically an
[export step](jobs.md#the-export-step)'s file — to a partner drop, local or SFTP/FTPS,
under its own policy block:

```yaml
tesseraql:
  connectors:
    push:
      allowedHosts:
        - sftp.partner.example
      allowedPaths:                 # local targets: deny-by-default roots
        - outbox
      knownHostsFile: config/known_hosts
      credentials:
        partner-sftp:
          username: svc
          password: ${secret.env.PARTNER_SFTP_PASSWORD}
          # or: privateKeyFile / privateKeyPassphrase — exactly one method
```

The push block is deliberately separate from the poll block: whom an app accepts files
from and whom it delivers files to are different trust decisions, each deny-by-default.
Everything the poll side guarantees holds here through one shared endpoint
implementation — the FTPS data channel drifted for a year when this logic had two
homes, so now it has one:

- **Host allow-list**, exact or `*.wildcard`, deny by default: an off-list target fails
  the step with `TQL-SEC-4141` before anything connects.
- **SFTP host keys** pin against the push block's `knownHostsFile` (strict checking);
  unset means unchecked, and lint nudges.
- **FTPS** requires the push block's `trustStore` (server verification + hostname
  check) and sends `PBSZ 0`/`PROT P`, so the data channel is as protected as the
  control channel; a client keystore on the credential supplies mutual TLS.
- **Credentials** live in config, resolve through the SecretResolver SPI at send, and
  declare exactly one authentication method — a job never carries one.
- **Atomic for the partner's poller**: uploads stage under a dot-name and rename on
  completion, so the other side's `poll:` (or anyone's) never reads a partial file.

Delivery failures are `TQL-BATCH-5315` on the step — the job fails, `sla:` alerting
and the rerun story apply, and a rerun re-delivers under the same name (an overwrite,
not a duplicate). The [admission profile](admission.md) bounds push egress like every
other: a bare `*` in `allowedHosts` fails admission (`TQL-ADM-4703`).

## The inbound `webhook` recipe

A `webhook` route is an HMAC-verified, replay-protected POST endpoint in front of a SQL pipeline:
the runtime authenticates the signed delivery and rejects replays **before** the route's
`command-json`-style SQL runs, so an invalid delivery never writes a row.

```yaml
version: tesseraql/v1
id: events.receive
kind: route
recipe: webhook                  # a post.yml file -> POST endpoint
webhook:
  provider: partner              # -> tesseraql.connectors.webhooks.partner
input:
  eventId: { type: string, required: true }
  amount:  { type: number }
sources:
  main:
    sql:                             # or steps: — the SQL pipeline runs once verified
      file: insert-event.sql
      mode: update
      params:
        eventId: body.eventId
        amount: body.amount
response:
  json:
    status: 202
```

The verifier is configured centrally, so the route carries no secret:

```yaml
tesseraql:
  connectors:
    webhooks:
      partner:
        secret: ${secret.env.PARTNER_WEBHOOK_SECRET}   # the HMAC-SHA256 signing key
        signatureHeader: X-TesseraQL-Signature         # default
        timestampHeader: X-TesseraQL-Timestamp         # default
        idHeader: X-TesseraQL-Delivery                 # optional; else the signature is the replay key
        tolerance: 5m                                  # default; reject timestamps outside this window
```

### Verification

The signature covers `<timestamp>.<body>` — the same scheme the [outbound
webhook notification](notifications.md) signs with, so a TesseraQL app can both send and receive
signed webhooks. The sender sends the `sha256=<hex>` signature and the epoch-seconds timestamp in the
configured headers; the recipe:

1. recomputes the HMAC over the received timestamp and **raw body** and compares in constant time
   (a mismatch is **401**);
2. rejects a timestamp outside the `tolerance` window — stale or future (**401**);
3. rejects a **replay** (**409**): the delivery id (the configured `idHeader`, else the signature)
   is recorded in a shared store until its timestamp tolerance lapses, so a delivery is processed
   at most once on any node sharing the database — the same store basis as SAML assertion replay.

The named verifier **must** be configured: an unknown `provider` fails the build, since a webhook
without a verifier would be unauthenticated. Lint catches this and the rest statically:

| Code           | Severity | Meaning                                                                |
| -------------- | -------- | ---------------------------------------------------------------------- |
| `TQL-SEC-4082` | error    | the route declares no `webhook.provider`                               |
| `TQL-SEC-4083` | error    | the named verifier is not configured under `tesseraql.connectors.webhooks` |
| `TQL-YAML-1056`| error    | the route has no `steps:` pipeline                                     |
| `TQL-YAML-1010`| error    | `webhook:` rides a non-webhook recipe                                  |

A webhook route is covered by the `webhook` coverage kind when a suite exercises its SQL (the same
SQL-file basis as route coverage); gate it with `coverage.thresholds.webhook`.

## Next

- [file-transfers.md](file-transfers.md) — the import and export routes connectors feed.
- [jobs.md](jobs.md) — polling a source on a schedule.
- [ops-console.md](ops-console.md) — watching transfers and retries.
