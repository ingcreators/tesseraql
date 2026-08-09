# Build an integration or batch application

Files arrive and have to be loaded. Data has to be pushed somewhere overnight. Another system
has to be told when something happens. This guide is the reading order for applications whose
work is not driven by a person clicking a button.

## The shape you are building

Scheduled or triggered work, running against files, HTTP APIs, or message channels, with runs
you can watch and failures you can retry. `examples/inventory-app` carries a working ETL chain:
a CSV drop is summarized to the database, a snapshot is appended, and a chained job writes the
day's report.

## The order to read

**1. Start with the job, not the connector.**
[jobs.md](jobs.md) is the main page. A job declares what triggers it and what steps it runs.
Read the trigger section and the pipeline steps before choosing how data arrives, because the
job is what holds everything else.

**2. Get the data in and out.**
[connectors.md](connectors.md) covers the managed recipes for files and HTTP: an application
declares a source, not a raw endpoint URI. For tabular files specifically,
[file-transfers.md](file-transfers.md) covers import and export as routes.

**3. Handle the business calendar.**
Overnight work is rarely "every day at 2am". Business-day calendars, the business date, and
shifted month-end rules are all in [jobs.md](jobs.md), and getting them right early saves
reworking every query later.

**4. Make failure survivable.**
Jobs restart. Chunked steps checkpoint, so a rerun resumes rather than repeats. Read the
cluster-safety, overlap, and stopping sections of [jobs.md](jobs.md) before your first
production run.

**5. Tell other systems.**
[messaging.md](messaging.md) publishes domain events on a transactional outbox, so an event is
emitted if and only if the write committed. [notifications.md](notifications.md) is the same
machinery aimed at people.

**6. Watch it run.**
[ops-console.md](ops-console.md) is where executions, transfers, the outbox, and dead-lettered
events live, and where you retry one. Set this up before you need it.

## What people usually get wrong

- **Polling a directory from a job step.** Declare a poll source on the trigger instead; the
  framework handles the claim, the move-on-success, and the failure directory.
- **Publishing the event in the same step as the write.** Use `publish:` so the outbox
  guarantees the pairing. A crash between the two is not a hypothetical.
- **Assuming the run date is the business date.** A job that fires at 00:05 for yesterday's
  business is normal. Declare the business date and bind it.
- **No SLA.** A job that silently stops running looks exactly like a job with nothing to do.
  Declare the overlap and SLA behaviour so someone is paged.

## Next

- [jobs.md](jobs.md) — the main page for this shape.
- [connectors.md](connectors.md) — files and HTTP, declared rather than coded.
- [ops-console.md](ops-console.md) — watching runs and retrying failures.
