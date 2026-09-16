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

The identity store is **not seeded**. No application ships with a user. `dev` detects this
at startup and prints the command; if you missed it:

```sh
printf 'change-me' > admin.pw
tesseraql identity-schema --app . --admin-login admin --admin-password-file admin.pw
```

The hint is suppressed when password login is switched off
(`tesseraql.console.login.password.enabled`), so if you see no hint and no user works, check
that key. Full surface: [authentication.md](authentication.md).

### Maven cannot resolve `io.tesseraql:*`

The framework's artifacts are on Maven Central, so nothing needs configuring — the scaffolded
`pom.xml` declares no repository because it needs none. A resolution failure is a version that is
not published yet, or a mirror in your `~/.m2/settings.xml` that does not proxy Central; behind an
internal mirror see [proxy.md](proxy.md).

Earlier releases were read from GitHub Packages and needed a `read:packages` token in
`~/.m2/settings.xml`. That is no longer required, and the entry can be removed.

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
The linter reports it, and the runtime refuses to start on it with the same code and the same
sentence, naming the route and the key (`route 'orders' sources.main.file: referenced SQL file
is missing: search.sql`). Under `dev --watch` the route serves its compile error until the
file is back, and the reload that brings it back clears the stub on its own.

### `TQL-YAML-1075` / `TQL-TPL-2001` — a file the document names is outside the app, or nowhere

Every file a document names by relative path — a statement, an export's `template:`, a
page's `response.html.template` — may sit anywhere inside the application home:
`../order.sql` from `web/orders/detail/`, `../../shared/report.xlsx`, a subdirectory. One that
resolves outside the home (`../../../outside.sql`, an absolute path elsewhere) is refused with
`TQL-YAML-1075` naming the document, the key and the value — by the linter, the admission
gate, the boot, the hot reload and `job run` alike; move the file under the home. A page
template is looked for beside the document, then under the application's `templates/`; one
found in neither is `TQL-TPL-2001`, at lint as at boot.

### `TQL-SQL-2101` / `2102` — an expression or a SQL file does not parse

Lint reports both where they are written: `2102` for a 2-way SQL template the parser cannot
read (an unterminated directive, a bind without its dummy), `2101` for an expression — a
`/*%if*/` directive's, a `validate:` rule's, a `statusWhen:`, `headersWhen:`, step `when:`,
notification `when:`/`recipient:` or workflow guard — naming the line where it has one. A
string literal knows the escapes `\'`, `\"` and `\\` only, so `'\d'` is `2101`; the regex
class is written `'\\d'`. A literal `matches()` pattern that does not compile is `2101` too.
The runtime refuses a request that renders such a file with the same code, so a route that
answers `2101` on every request has a SQL file the lint would have refused.

### `TQL-SQL-2123` — a SQL file carries a transaction-control statement

A 2-way SQL file never owns its transaction: the command pipeline does on a request, the test
runner does for a case (and always rolls it back), the Studio sandbox does for a console run. A
`commit;` at the end of the file — a DBA script's trailing terminator, an Oracle habit — ended
the transaction the runner thought it owned, so the case's write persisted behind a green
result and every later run added one more row. The parser refuses the file, naming the line
and the statement, on every surface: remove the statement. A PL/SQL or T-SQL block that starts
with `BEGIN` and continues with a statement is not one; `BEGIN` alone, `BEGIN WORK` and
`BEGIN TRANSACTION` are.

### `TQL-YAML-1410` / `1411` — the suite's case names

The reports join results to cases by name, so a name declared twice across the app's suites is
refused (`1410`, both files named): before, both twins showed the first result and a failing
duplicate rendered green on the portal's route page. A `--case` filter naming no case is
refused too (`1411`, exit 2): a CI step pinned to a since-renamed case used to run nothing,
exit 0, and with `--report` overwrite the overlay with an all-green run of nothing.

### `TQL-SQL-2122` — an expression met an operand it cannot evaluate

A relational comparison (`<`, `>`, `<=`, `>=`) on a `null` or on two values of unrelated
kinds, arithmetic on a non-number, a division by zero, or a `matches()` pattern bound at
request time that does not compile. The sentence names the operator and the operand kinds.
It answers 500 on purpose: the request is ordinary — an optional input left out — and the
template is what is defective. Guard the site (`minPrice != null && minPrice > 0`), or declare
the input `required`; a `validate:` rule on an optional field takes the guard as its `when:`.

### `TQL-YAML-1066` / `1067` — the recipe reads a piece the document does not declare

A `query-json`, `command-json` or `webhook` route answers through `response.json:` or
`response.redirect:`; a `page` or `query-html` route renders `response.html:` or
`response.file:`. A document with neither — no `response:` block at all, or one holding only
`session:`, or the page arm on a JSON recipe — is `TQL-YAML-1066`, naming the route and what
the block does declare. Every source and step runs through one arm — `sql: { file: … }`,
`contract:`, `service:`, `http:` (with its `url:`), or `sequence:` on a command step — and one
that names none is `TQL-YAML-1067`. A `file-import` route needs its `import:` block and one
`steps:` entry with a file; an export recipe needs a `main` source with a file to read; each is
`TQL-YAML-1041`. All are lint errors and boot refusals with the same sentence.

### `TQL-YAML-1069` / `1070` / `1071` — a source that never binds

An input is fed by what the route declares: the path, the query, the form or JSON body. A
request header is not among them — `Host`, `Accept`, `Priority` and `Cookie` arrive on every
browser request under ordinary names, so an input spelled like one would be filled by the
browser, not the caller. The one place a header is a source is a `service:` binding's
`params:` (`cookie: header.Cookie`), read from the wire; the same spelling on a statement's
`params:`, a validation rule's, an enrichment's or an export's `after:` is `TQL-YAML-1069`.
`body.<name>` on a GET route is `TQL-YAML-1070`: a GET carries no body, so the value was
null on every request — declare the input and read `query.<name>`. `body.<name>` naming a
field the route does not declare under `input:` is `TQL-YAML-1071` when the route rejects
unknown fields (the default): a request carrying the field is refused by the mass-assignment
guard before anything binds. `1069` is a boot refusal as well; the other two are lint errors.

### `TQL-YAML-1072` — an input's `default:` is not a value the input accepts

A declared `default:` is what the binder hands every request that omits the field, so it is
judged as a caller's value would be, where the declaration is read. It is parsed into the
declared type (`default: abc` on `type: number`, `default: yesterday` on `type: date`) and held
to the field's constraints (`default: up` outside `enum: [asc, desc]`, `default: 0` under
`min: 1`, a `sort` default naming a column outside `columns:`). A default on an `array` input is refused
too — nothing binds it. The same predicate refuses the boot; the omitting request then binds
the typed value, so a quoted `"5"` on an integer input reaches the statement as a number.
Write the default as the caller would write the value.

### `TQL-YAML-1073` / `1074` — a response literal the edge writes as given

A file response's body is written as UTF-8 whatever `contentType:` says, so
`charset=Shift_JIS` was a header contradicting its bytes: declare `charset=utf-8` or omit the
parameter (`TQL-YAML-1073`). A redirect `location:` is written as given, so whitespace at
either end reaches the wire — a trailing space as `%20`, and a leading one keeps the base path
off the value (the join applies it only to a value starting with `/`), so the browser
resolves the redirect relative to the current page, outside the application. Quote the
literal without the space (`TQL-YAML-1074`). Both are lint errors and boot refusals.

### `TQL-YAML-1412` — a policy rule names two conditions

An `anyOf` rule is one condition: `role:`, `permission:` or `claim:`. A rule naming two —
`{role: ADMIN, permission: orders.approve}` — used to apply the first the runtime recognised
and drop the rest, so the permission holder was refused with the rule lint-clean. Split it
into one rule per condition (the policy grants when any holds), or bundle the shared
authority into a role. A rule naming none of the three is logged at boot and the policy
denies everyone, as before.

### `TQL-SEC-4135` / `4139` / `4152` / `4153` — a response header the wire cannot carry

A header name is a token — letters, digits and the punctuation `!#$%&'*+-.^_|~` or a
backtick, nothing else — and the transport refuses any other name where the refusal hangs the
response instead of failing it. A default under `security.responseHeaders` with a space, a colon, a trailing blank or a
non-ASCII letter in its name is refused at lint and at boot (`TQL-SEC-4135`, the value check's
code). One the transport owns (`Content-Length`, `Connection`, the `tql.` namespace) is
refused the same way (`TQL-SEC-4139`). A route's own `headers:` key with such a name is
`TQL-SEC-4152`, and the edge answers 500 rather than hanging if one reaches it.
`TQL-SEC-4153` is a warning: a
`Content-Disposition` whose `filename=` is built from a placeholder is neither quoted nor
encoded on the wire — name a download with `response.file: filename:`, which is both.

### `TQL-MCP-4265` / `4266` / `4267` / `4268` / `4269` / `4270` — the MCP endpoint refused the request, not the message

The MCP transport judges the caller before it reads the JSON-RPC message. `4265` (403): the
request named an `Origin` that is neither loopback nor one the server allows — a web page is
calling, which the MCP specification's guard against DNS rebinding refuses; a local client
page uses a loopback origin, and the dev server admits others with `--allow-origin`. `4266`
(415): the `POST` did not declare `Content-Type: application/json`. `4267` (400): the
`MCP-Protocol-Version` header names a revision the server does not speak — send the one
`initialize` negotiated, or none. `4268` (400): a request after `initialize` carried no
`Mcp-Session-Id`; `4269` (404): the one it carried names no live session — initialize
again. `4270` (413): the body exceeds the dev transport's 10 MiB ceiling. A body that is not
JSON is answered inside the protocol, as JSON-RPC `-32700`.

### `TQL-OAUTH-3005` — `tesseraql.mcp.resource` declared under the stack issuer

The stack's authorization server names a member's MCP resource from its address
(`<origin><base path>/_tesseraql/mcp`): its metadata document publishes that name and its
grants carry it. A declared `tesseraql.mcp.resource` is a name no client is told and no
token could carry, so the member is refused at boot rather than refusing every token
silently. Remove the key; the override is for a standalone runtime behind an external
identity provider ([oauth.md](oauth.md)).

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
