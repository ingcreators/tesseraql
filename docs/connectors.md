# Managed connectors

TesseraQL apps integrate with neighbouring systems through **managed connectors** (roadmap
Phase 26): governed recipes for files and HTTP. Camel's component catalog stays an
implementation detail — an app never writes a raw endpoint URI; it declares a connector that
runs under the framework's allow-lists, secrets, lint, and coverage.

This page covers the outbound `http-call` pipeline step and the inbound directory-polling
trigger for `file-import`. (The inbound webhook recipe arrives in a later slice of the same
phase.)

## The `http-call` pipeline step

An `http-call` step is a batch-pipeline step that issues one synchronous outbound REST request
and publishes the response to later steps. It interleaves with SQL steps, so a job can fetch
from an API and persist the result, or read from the database and push it to a partner system.

```yaml
version: tesseraql/v1
id: rates.refresh
kind: job
recipe: batch-pipeline

params:
  base:
    type: string
    required: false

pipeline:
  - id: fetch
    http-call:
      method: GET                                   # defaults to GET
      url: https://api.partner.example/v1/rates     # host must be allow-listed
      query:
        base: job.base                              # bound from the step context
      credential: partner                           # a configured credential, never inline
      requestTimeout: 20s                           # optional per-step override

  - id: store
    sql:
      file: store-rate.sql
      mode: update
      params:
        base: job.base
        rate: step.fetch.body.rate                  # the parsed JSON response feeds the SQL step
```

The response is published as:

| Context path             | Value                                                         |
| ------------------------ | ------------------------------------------------------------- |
| `step.<id>.status`       | the HTTP status code (an integer)                             |
| `step.<id>.body`         | the parsed JSON (a map/list) when the response is JSON, else the raw text |
| `step.<id>.headers`      | the response headers (first value per name)                   |

A step declares exactly one of `sql:`, `notify:`, or `http-call:`. The `query:` values and
`body:` are source expressions bound from the step context exactly like a SQL step's params;
static `headers:` values may carry `${...}` config or secret placeholders, resolved at call
time. `body:` resolves a single context expression and is sent as JSON.

### Why a job step, not a command step

`http-call` is a job-pipeline step, never a transactional `command-json` step. A command runs
every step in one database transaction, and a synchronous outbound call cannot be rolled back —
so putting it inside a command would break the all-or-nothing guarantee. A command's outbound
integration instead rides the transactional outbox as an HMAC-signed webhook (see
[notifications](notifications.md)): the event is written in the transaction and delivered
at-least-once afterwards. Use `http-call` when a pipeline needs the **response** to drive
subsequent steps; use a webhook notification for fire-and-forget delivery.

## Outbound policy

All outbound HTTP is governed by `tesseraql.http.outbound`. Egress is **deny by default**: a
call may only target a host in `allowedHosts`, so a step can never reach an arbitrary URL.

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
generated artifacts. An unsupported credential `type:` fails at startup (`TQL-YAML-1103`).

### Circuit breaker

A per-host circuit breaker trips after `failureThreshold` consecutive **systemic** failures —
transport errors, timeouts, and `5xx` responses — and stays open for `openDuration`, failing
fast rather than hammering a struggling dependency until a half-open trial succeeds. A `4xx`
response or an `expectStatus` mismatch fails the step but does **not** trip the breaker: it is a
deterministic rejection, not a sign the dependency is down.

A call is successful when its status is `2xx`, or equals `expectStatus` when one is declared;
any other outcome fails the step (and so the job). The call is recorded as a
`tesseraql.http.call` span in the job's trace, visible in the operations console.

## Governance

`http-call` surfaces under the existing governance model — the host allow-list is the egress
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

An `http-call` declarative test **plans** a job's steps against the case's params — resolving
the url, binding query params, and applying the allow-list — without issuing a network request.
Each planned request is a row, so a suite asserts the recipe is wired correctly and the
`http-call` coverage kind tracks it.

```yaml
tests:
  - name: the refresh job calls the allow-listed partner API
    http-call:
      job: rates.refresh
      id: fetch                       # optional; omit to plan every http-call step of the job
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

Gate coverage with `coverage.thresholds.http-call` like any other kind.

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
    source: sftp                 # local | sftp | ftps
    host: sftp.partner.example   # remote sources only; must be allow-listed
    port: 22                     # defaults to 22 (sftp) / 21 (ftps)
    path: /outbound/orders       # directory to poll (a local path, or the remote directory)
    credential: partner-sftp     # a configured credential, never inline (remote sources)
    include: "*.csv"             # filename glob (default: every file)
    delay: 60s                   # poll interval (default 60s)
    move: .done                  # processed files move here (default .done)
    moveFailed: .error           # files that could not be ingested move here (default .error)

import:                          # the same import: block a file-import route uses
  format: csv
  columns:
    - orderNo
    - { name: qty, type: number }
  onError: skip
  sql:
    file: upsert-order.sql       # runs once per row; params are the column names
```

Each file is ingested through the same asynchronous, off-heap path an HTTP upload takes and is
tracked as a **transfer** in the operations console — so row-level outcomes (rejected rows under
`onError: skip`) show up there, exactly like an uploaded file. A file moves to `move` once it has
been ingested; a file that cannot be read moves to `moveFailed`. The underlying Camel
`file`/`sftp`/`ftps` consumer is an implementation detail; the YAML never names an endpoint.

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

The SSH host key of an SFTP edge is expected to be verified out of band; set a `knownHostsFile` for
strict checking in production. FTPS rides the identical recipe and runtime path with
`source: ftps`; only the endpoint scheme differs.

### Governance and testing

Lint catches a misconfigured poll job before it ships, and at runtime a job that targets a
non-allow-listed host (or has no `import:` block) is logged and skipped rather than taking the
app down:

| Code           | Severity | Meaning                                                               |
| -------------- | -------- | --------------------------------------------------------------------- |
| `TQL-SEC-4080` | error    | a remote source's host is not in `tesseraql.connectors.poll.allowedHosts` |
| `TQL-SEC-4081` | warning  | the trigger references a credential not declared under `credentials`  |
| `TQL-YAML-1005`| error    | the source is not local/sftp/ftps, has no path, or a remote source has no host |
| `TQL-YAML-1006`| error    | a poll-triggered job has no `import:` block with a per-row SQL         |

A poll job is covered by the `file-poll` coverage kind when a declarative suite exercises its
per-row import SQL (a plain `sql:` case), the same SQL-file basis as route and document coverage.
Gate it with `coverage.thresholds.file-poll`.
