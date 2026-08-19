# The operations console

The operations console answers one question: **what is the running system doing right
now?** It is the screen an operator opens during an incident, and the one they keep open
during a release. Every page is live against the running application, and every action it
offers is a face over an ops API that already exists.

Open it at `/_tesseraql/ops/console` — the stack's origin scope. The console is one shell
per stack ([hosting.md](hosting.md)): its sidebar is an application switcher, one entry per
application the caller may see, with a staged canary as a second entry
(`orders (canary)`, addressed by `?slot=canary`). Selecting an application delegates its
pages over loopback to that application's own runtime with the caller's session, so what
each page shows is that runtime's own data — its trace ring, its lanes, its jobs. The
pages refresh themselves every 15 seconds. On an unhosted boot (integration tests, library
embedding) the console mounts locally and the switcher simply lists that one application.

## Who can open it

The console checks the framework's permission atoms, per application
([stack-shells.md's model](hosting.md#operating-a-host)):

| Atom | Grants |
| --- | --- |
| `tql.ops.view.<name>` | Seeing that application's operational data: its switcher entry and every read page. |
| `tql.ops.run.<name>` | Acting on it: run a job, redeliver an outbox message or a dead-lettered event. |
| `tql.app.deploy.<name>` | The [Deploy page](#deploy): uploading a new version of that application. |

The wildcard is a terminal `*` (`tql.ops.view.*`). The verbs are granted separately —
*view broadly, act narrowly* — and deny by default: a caller with no `tql.ops.view` atoms
sees an empty switcher. A viewer sees every button, including the ones they may not press:
templates cannot evaluate grants, so an unauthorized action is refused by the selected
application's own runtime rather than hidden. The refusal is the real gate, and it stays at
the member — the shell forwards the caller's own session and the member re-runs its own
checks, so the shell adds reach, never authority. Stack-wide vitals on each application's
overview (lanes, JVM pinning, slow SQL) open to any holder of any `tql.ops.view` grant.

## Overview

The landing page carries the roll-up an operator wants first:

- **Health** — the platform status badge (UP, WARN, or DOWN) with the per-datasource
  probe results behind it, and the deployed framework version.
- **Recent executions** — job runs with their status, trigger, start time, and duration.
  Each row links to its execution detail.
- **Lanes** — the execution lanes with their in-use, maximum, and admitted counts, so a
  saturated lane is visible before it becomes a queue.

## Jobs

The jobs page lists every job the application declares, with its trigger, the next fire
time its calendar resolves to, its policy, its source, and its last run.

Press **Run** to start a job by hand. A job that declares `input:` renders a form for its
parameters, with required markers and numeric fields taken from the declaration. The
parameters bind and validate exactly as they do when the scheduler fires the job or when
the JSON API starts it, because all three paths share one binding step. A parameter the
job refuses comes back as the same error envelope the API returns.

Jobs, their triggers, and their calendars are declared in the application, not here.
See [jobs.md](jobs.md) to author them.

## Executions

An execution row opens the run in detail: its steps, their timings, the row counts they
moved, and the failure if there was one. This is where a nightly job that "took too long"
becomes a specific slow step.

## Traces

Spans for recent requests, with total and self time in milliseconds. Use it to find which
route is slow and which part of it is slow. The audit page links each entry to its trace,
so a report of "this screen hung at 09:14" is one click from the span that hung.

## Transfers

Every asynchronous file transfer: its route, direction, format, status, row count, the
produced file, whether it has been downloaded, and when it started. Produced files are
downloadable from this page.

File transfers are declared as routes ([file-transfers.md](file-transfers.md)); this page
is where their runs are watched.

## Outbox

The transactional outbox holds notifications and events recorded with a command and
delivered after it commits. The page shows each message's type, source, status, attempt
count, last error, and timestamps.

A message that has exhausted its attempts is dead. Press **Redeliver** to try it again.
This is the page to open when someone reports that an approval email never arrived.

## Events

The same view for messaging channels: channel, topic, key, status, attempts, last error,
and the publish and consume times. Dead-lettered events carry their failure reason and can
be redelivered from here.

See [notifications.md](notifications.md) and [messaging.md](messaging.md) for how messages
get here.

## Audit

The business-route audit log: when, which route, which request, which actor, the resulting
status, the duration, the bound parameters, and a link to the trace.

The audit store is off unless `tesseraql.audit.routes.enabled` is set. When it is off, the
page says so and names the key rather than showing an empty table.

## Deploy

The browser face of the stack's deploy endpoint (`POST /_tesseraql/deploy` —
[hosting.md](hosting.md#operating-a-host)): upload a `.tqlapp`, optionally staged as a canary
with a traffic weight, and the running host's reconciler converges the stack to it without a
restart. The page appears in the sidebar only for a signed-in holder of a `tql.app.deploy`
grant, and it lists the members those grants cover with the version each serves right now.

The gate is display only. The form posts to the endpoint itself, which checks the caller's
`tql.app.deploy.<name>` grant against the **package's declared name** — never a form field —
so the page can widen what is listed, never what is deployed. A refusal (wrong application,
stale version, failed preflight) renders inline on the form and writes nothing.

## What the console does not do

The console watches; it does not author. Three neighbouring surfaces do the rest:

- **[Studio](studio.md)** edits the application: routes, SQL, migrations, feature flags,
  configuration, and data.
- **[IAM Admin](iam-admin.md)** manages people: users, invitations, sessions, and
  delegations.
- **Your metrics stack** holds the history. The console shows recent state; Prometheus
  scrapes the same runtime for trends and alerting
  ([deployment.md](deployment.md#metrics-prometheus)).

## Next

- [jobs.md](jobs.md) — declaring the jobs this console runs.
- [deployment.md](deployment.md) — health endpoints, metrics, and the safety valves
  behind the overview page.
- [studio.md](studio.md) — the authoring console.
- [troubleshooting.md](troubleshooting.md) — what the symptoms on these pages mean.
