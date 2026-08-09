# The operations console

The operations console answers one question: **what is the running system doing right
now?** It is the screen an operator opens during an incident, and the one they keep open
during a release. Every page is live against the running application, and every action it
offers is a face over an ops API that already exists.

Open it at `/_tesseraql/ops/console`. It is mounted by default in every application, and
its pages refresh themselves every 15 seconds.

## Who can open it

Two policies gate the console:

| Policy | Grants |
| --- | --- |
| `ops.batch.view` | Every page. Read-only. |
| `ops.batch.run` | The write actions: run a job, redeliver an outbox message or a dead-lettered event. |

Grant them like any other policy ([authentication.md](authentication.md)). A viewer sees
every button, including the ones they may not press: templates cannot evaluate policies,
so an unauthorized action is refused by the route rather than hidden. The refusal is the
real gate.

In a deployment that hosts several applications, an operator holding `ops.app.<name>`
sees only that application's rows. The narrowing happens in the service layer, so it
holds for the JSON ops API and the console alike.

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
