# Troubleshooting

Symptoms, in the order you are likely to meet them, with the cause and the fix. Where a
failure carries a `TQL-*` code, the [error code reference](reference-error-codes.md) has the
raising site; this page is the other direction — you have a symptom and no code.

## Installing and first run

### `tesseraql: command not found`

The CLI is not on your `PATH`. Check the install method you used
([getting-started.md](getting-started.md#install-the-cli)) and verify with
`tesseraql --version`. With the distribution archive, it is the unpacked `bin/` directory
that goes on `PATH`, not the jar.

### "Could not connect to the database"

The CLI prints this instead of a stack trace when it cannot reach the database at all. Three
things to check, in order:

1. Is the database running? `docker compose up -d` starts the one the scaffold ships.
2. Does `tesseraql.datasources.main.jdbcUrl` point at it? A `--jdbc-url` argument overrides
   both the config and any running embedded database.
3. Are you expecting an embedded database? A `tesseraql dev --embedded-db` in another
   terminal leaves a `work/embedded-db.jdbc` marker, and the database commands pick it up —
   but only when you pass `--app .`.

### The login page appears and no password works

The identity store is **not seeded**. No application ships with a user. `serve` detects this
at startup and prints the command; if you missed it:

```sh
printf 'change-me' > admin.pw
tesseraql identity-schema --app . --admin-login admin --admin-password-file admin.pw
```

The hint is suppressed when password login is switched off
(`tesseraql.console.login.password.enabled`), so if you see no hint and no user works, check
that key. Full surface: [authentication.md](authentication.md).

### Maven cannot resolve `io.tesseraql:*` — 401 Unauthorized

GitHub Packages **requires authentication even for public reads**. The scaffolded `pom.xml`
declares no repository on purpose; add both the repository and a token with `read:packages`
to your `~/.m2/settings.xml`. The exact block is in
[getting-started.md](getting-started.md#the-maven--ci-path). In CI the workflow `GITHUB_TOKEN`
works unchanged.

### `--embedded-db` refuses to start on an existing directory

A persistent data directory is pinned to the PostgreSQL version that initialized it, so a CLI
upgrade never silently makes your data unopenable. Run `tesseraql embedded-db info ./pgdata`
— it reports where the directory stands and prints the upgrade procedure when one applies.

### A driver class is missing at runtime

Only the PostgreSQL driver and the CSV codec are in the base distribution. Oracle, SQL
Server, MySQL, and the pdf/excel/s3 modules are opt-in because their licences differ:

```sh
tesseraql modules add com.oracle.database.jdbc:ojdbc11 --app .
```

That edits `tesseraql.yml` and writes `modules.lock`. See
[getting-started.md](getting-started.md#opt-in-modules-drivers-and-codecs).

## Building an application

### A route I just added returns 404

The server does not watch the filesystem by default. Routes mount at start, when Studio
applies an edit, or when `--watch` sees the file change:

```sh
tesseraql dev --watch
```

Jobs, queue consumers, and `config/` changes still need a full restart, whichever loop you
use.

### `TQL-SQL-2103` — referenced SQL file is missing

A source's `sql.file:` resolves **relative to the route document's own directory**, not the app
root. A route at `web/orders/get.yml` naming `search.sql` looks for `web/orders/search.sql`.

### `TQL-YAML-1004` and friends — a key is refused on this recipe

Keys are recipe-scoped. `notify:` is command-only, `cache:` is query-only, `refreshOn:` is not a
form-view key. A source with an `http:` arm has its own rule (`TQL-YAML-1022`): query recipes and
transactional ones, where the call runs before the write's transaction. The [YAML surface
reference](reference-yaml-surface.md) lists which root properties apply to which `kind` and
`recipe`.

A key the document does not have is `TQL-YAML-1043`, a warning saying it is ignored; a key that
moved before v1 is `TQL-YAML-1044`, an error naming where it went. Both check a block whose shape
is fixed — `export:`, `import:`, `outbox:`, `errors:` — as well as the document itself, so
`export.sql:` is reported rather than dropped.

### `TQL-VIEW-3304` / `3308` / `3309` — a view names something the route does not declare

A view's `fields:`, `children:`, and `search:` are checked against the route it belongs to.
Usually the input exists under a different name: the column name is the name everywhere, so
check the DDL rather than guessing a camel-case variant
([identifiers.md](identifiers.md)).

### `TQL-SEC-4031` — 403 on a route that should be open

Routes are deny-by-default. A route is reachable only when it declares how it authenticates,
and path-matched defaults under `tesseraql.security.defaults.routes` may be supplying an
`auth:` you did not intend. Rules are first-match-wins in declaration order, so read the list
top to bottom ([authentication.md](authentication.md#route-security-defaults)).

### `TQL-SEC-4032` — CSRF token missing or invalid

A browser write needs the CSRF field in the form. The bundled patterns include it; a
hand-written form must too ([hypermedia-ui.md](hypermedia-ui.md)). Bearer and API-key routes
never require CSRF, so this code on an API route means the route resolved to `browser` auth.

### `TQL-SEC-4070` / `4080` — an outbound host is refused

Outbound HTTP and poll sources are allow-listed, deny-by-default. Add the host to
`tesseraql.http.outbound.allowedHosts` or `tesseraql.connectors.poll.allowedHosts`
([connectors.md](connectors.md)).

### Migrations fail with a checksum mismatch

A migration that has already been applied was edited. Do not edit applied migrations — add a
new one. If the mismatch is a known-good edit, `tesseraql migrate repair --app .` rewrites the
history table; `tesseraql migrate info` shows the current state first.

## Running in production

### Studio refuses every write with a 403

The caller does not hold this application's `tql.studio.edit.<name>` atom (or the
`tql.studio.edit.*` wildcard). Editing is deny-by-default: grant the atom through IAM Admin
or a role that bundles it. See [studio.md](studio.md#editing-is-a-grant).

### A notification never arrived

Open the [ops console](ops-console.md)'s **Outbox** page. A message that exhausted its
attempts is dead-lettered with its last error, and **Redeliver** retries it. If nothing is
there at all, the command never enqueued it — check that `notify:` is declared on the command
and that lint passes (`TQL-BATCH-5301` names an unconfigured channel).

### A scheduled job did not run

Three separate causes, distinguishable on the jobs page:

- **A calendar filtered the firing.** Calendar-filtered firings leave no execution row by
  design. The **Calendar next** column shows the next date the calendar admits.
- **Another node holds it.** Firings are claimed cluster-wide so exactly one node runs each.
- **It never fired.** Check the trigger, and that `TQL-BATCH-4201`–`4203` did not flag the
  calendar reference at build time.

See [jobs.md](jobs.md) and [ops-console.md](ops-console.md#jobs).

### An import or export finished but produced nothing

The **Transfers** page carries the row counts and the produced file. An import route with
`onError: skip` ends `COMPLETED` even when rows were rejected — `rowCount` counts only the
applied rows, and the status response lists each rejected row with its number and message. A
zero-row `COMPLETED` therefore means every row was rejected, not that the file was empty
([file-transfers.md](file-transfers.md)).

For a batch job's chunk step the equivalent is the managed `tql_job_skips` table, which
records the row key and message for each skipped row until `skipLimit` fails the step
([jobs.md](jobs.md#the-chunk-step)).

### Health is DOWN but the application answers

The health probe covers the datasources, not just the HTTP port. The ops console overview
shows the per-datasource probe results behind the roll-up badge
([deployment.md](deployment.md)).

## When none of this helps

- **Find the code.** Every framework refusal carries `TQL-<DOMAIN>-<n>`, and the
  [error code reference](reference-error-codes.md) indexes all of them with the file that
  raises each — including the ones no page discusses.
- **Check the key.** The [configuration reference](reference-config.md) lists every
  configuration key the framework reads and what reads it.
- **Run the linter.** `tesseraql lint --app .` catches at build time most of what would
  otherwise surface at request time.

## Next

- [reference-error-codes.md](reference-error-codes.md) — every `TQL-*` code with its
  provenance.
- [reference-config.md](reference-config.md) — every configuration key.
- [faq.md](faq.md) — questions about adopting and using the framework.
