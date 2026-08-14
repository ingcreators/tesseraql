# Testing and coverage

A TesseraQL application is tested the same way it is written: declaratively. Test suites are
YAML files under the app's `tests/` directory; each case exercises one declared behavior — a
2-way SQL file, a route's validation rules, its notifications, a job's outbound HTTP step, an
identity contract, or a message catalog — with parameters, and asserts on the rows that come
back. The same run measures coverage: which SQL lines and branches executed, and which declared
routes, rules, and notifications the suites exercised at all.

## Writing a suite

Every `tests/**/*.yml` file with a `tests:` block is a suite, and every suite declares
`version: tesseraql/v1` as its first line (required — an unversioned suite fails the load). A
case has a `name`, exactly one target, optional `params`, and an optional `expect`:

```yaml
version: tesseraql/v1
tests:
  - name: the search narrows by title
    sql:
      file: web/requests/requests.sql
    params:
      q: desk
    expect:
      rowCount: 1
      rows:
        - id: PR-1001
```

The `sql.file` path is app-relative, including any literal path-parameter directories
(`web/items/{id}/select.sql`). `params` bind the file's named parameters — the same conditional
`/*%if*/` blocks the live route evaluates apply, so an omitted parameter skips its branch
exactly as an omitted query parameter would at runtime.

A case may also declare the **request principal it runs as** — required whenever the target
SQL carries a `/*%scope … */` directive ([data scoping](data-scoping.md)), and useful for any
ambient `principal.*` path:

```yaml
  - name: a requester sees only their own department
    sql:
      file: web/api/requisitions/requisitions.sql
    principal:
      roles: [REQUESTER]
      claims:
        departments: [engineering]
    expect:
      rowCount: 1
```

`principal` takes `subject`, `loginId`, `roles`, `permissions`, `groups`, and `claims` — the
same shape every authentication mechanism produces. Scope directives resolve through the app's
`scope/` declarations exactly as at runtime: matching arms bind the principal's claims, no
matching arm renders deny-by-default (`1=0`). One case per role is how a suite proves each
scope posture, and how the `data-scope` [coverage kind](#coverage-kinds) is earned.

## Case kinds

- **`sql`** — runs a 2-way SQL file. A query's result rows are the case's rows; a write file
  (`UPDATE`/`INSERT`/`DELETE`) is just as valid a target — its affected-row count is asserted
  with `expect.updateCount`, and `verify:` read-backs observe the write (see
  [Testing write routes](#testing-write-routes)). Every `sql` case runs inside a transaction
  the runner always rolls back, so a test run never commits anything to the database.
- **`contract`** — runs a named identity contract (for example
  `identity.find-roles-by-user-id`) against the configured realm; its rows are the case's rows.
- **`validate`** — evaluates a route's `validate:` rules against the case's `params` (the
  execution context the rules see, typically a `body:` map). The violations are the rows, each
  carrying `rule`, `field`, `code`, and `message` — so `rowCount: 0` asserts a clean pass.
  Name a single rule with `validate: {route: ..., rule: ...}`; omit `rule` to evaluate them all.
- **`notify`** — evaluates a route's or a job's `notify:` declarations
  (`notify: {route: ...}` or `notify: {job: ...}`, plus an optional `id`). Each notification
  that fires is one row with `notify` (its id), `channel`, `source`, and the resolved payload
  columns. Guards and payload expressions evaluate exactly as at runtime; by default no mail
  or webhook is sent — add `send: true` for [real-send mode](#real-send-cases).
- **`http:`** — plans a job's outbound HTTP steps or a query route's
  [`http:` sources](connectors.md#http-sources-on-query-routes)
  (`http: {job: ...}` or `{route: ...}`, plus an optional `id`) without a network
  request. Each matching step is one row with `http` (its id), `method`, the resolved `url`
  and `host`, `allowed` (whether the host is in the egress allow-list), and `credential` —
  add `send: true` for [real-send mode](#real-send-cases).
- **`messages`** — resolves keys from the app's `messages/<locale>.yml` catalogs: one row per
  key with `key`, `locale`, and `text`. Omit `keys` to resolve every key the locale sees; an
  unresolvable key yields a null `text`, so the expectation fails visibly.
- **`transition`** — fires one declared workflow transition
  (`transition: {workflow: ..., key: ..., id: ...}`) against the named document, inside the
  case's always-rolled-back transaction, through the documented pipeline
  ([approval workflow](approval-workflow.md)): state legality, `decide:` resolution after
  the document binds, the guard, the conditional state advance, the command with its
  `/*%scope */`, and the zero-row contract. The outcome is the case's single row —
  `from`/`to` on an advance, or a `code` row (`TQL-WORKFLOW-3201/3202/3204`,
  `TQL-DECISION-4720/4721`) so a refusal is assertable as data; `verify:` read-backs
  observe the uncommitted command under the same `principal`. Task opening, history,
  notifications, and the task-holder authority check are runtime concerns a rolled-back
  case does not model — the HTTP surface stays the place to prove those.
- **`dispatch`** — runs a workflow's one-action selector
  (`dispatch: {workflow: ..., key: ..., id: ...}`,
  [transition engine](transition-engine.md)): the dispatch-level `decide:` evaluates
  once after the document binds, then each member fires through the same pipeline the
  `transition` target runs — a wrong-state (`TQL-WORKFLOW-3201`) or guard (`3202`)
  refusal rolls back to its savepoint and falls through. The outcome row is the
  winner's `from`/`to` plus `transition` (which member fired) and `dispatch`; a
  non-selectable member outcome comes back as its `code` row; no member holding is a
  `code: TQL-WORKFLOW-3202` row with `attempted` naming the members tried,
  comma-joined — the button the UI actually calls, asserted without HTTP.

Both workflow targets accept **`given:` fixture steps** — transitions fired before the
target, in the same always-rolled-back transaction, through the same documented
pipeline, so stamps, decisions, and state advances are real:

```yaml
- name: the manager's one button fires the stamped lane
  given:
    - workflow: requisition
      key: REQ-1002
      id: submit
      principal: { loginId: sato, roles: [REQUESTER] }   # the fixture's own actor
  dispatch: { workflow: requisition, key: REQ-1002, id: submit_decision }
  principal: { loginId: kishi, roles: [MANAGER] }
  expect:
    rows:
      - { transition: approve, from: submitted, to: approved }
```

A `given:` step is unasserted but must advance — a refusal fails the case naming the
step and its code, never a half-seeded state. Each step may carry its own
`principal:` (the requester submits, the manager approves), defaulting to the
case's. Documents no longer have to start at the initial state to be assertable.

## Real-send cases

Planning and evaluation prove the declarations resolve; `send: true` proves the **wire**. The
runner starts a local capture server for the case, delivers for real over a real socket, and
the case's rows carry what actually arrived — no external mock server to install, nothing
listening after the case ends:

```yaml
version: tesseraql/v1
tests:
  - name: the audit webhook is signed on the wire
    notify:
      route: users.apiProvision
      id: audit
      send: true
    params:
      body: {userName: sato}
    expect:
      rows:
        - channel: audit-webhook
          delivered: true      # plus `signature` (the HMAC header) and `wireBody`

  - name: the rates call carries its credential
    http:
      route: orders.list
      id: rates
      send: true
    expect:
      rows:
        - sent: true
          authorization: Bearer live-token   # the credential exactly as runtime builds it
          requestPath: /v1/rates?base=USD
          responseStatus: 200
```

- A **`notify` send** delivers through the production senders — only the destination is the
  runner's capture. A **webhook** channel's JSON body, timestamp header, and HMAC signature
  are built by the same code the outbox dispatcher uses (`signature` and `wireBody` columns
  carry the wire truth). A **mail** channel renders its template and inline subject, resolves
  `to`/`from`, and delivers over real SMTP to an in-process capture — the row carries the
  rfc822 truth (`to`, `from`, `subject`, `wireBody`), and the channel's real host is never
  touched. Inbox channels keep their evaluate-only rows: delivery there is a database write
  the outbox integration tests own.
- An **`http:` send** performs the request — declared headers, the resolved credential
  header, the body — against the capture server, preserving the original path and query. The
  row keeps the plan columns (including the true `allowed` verdict for the *declared* host)
  and adds `sent`, `requestPath`, `authorization`, `requestBody`, and `responseStatus`.

## Expectations

`expect.rowCount` asserts the exact number of rows. `expect.rows` is a list of partial
matchers: the first map is checked against the first row, the second against the second, and
each entry must be present in that row (extra columns are ignored; values compare as strings,
so `id: 42` matches a numeric column). For a write target, `expect.rowCount`/`rows` do not
apply — `expect.updateCount` asserts the affected-row count instead, and mixing the two fails
the case with a message naming the right assertion. A case without `expect` passes when its
target executes without error — useful for data-independent smoke cases that still record
coverage.

Suites run against a real database — the schema and any seed data your migrations create.
Apply migrations first (`tesseraql migrate --app .` or the `tesseraql:migrate` goal), and
either seed known rows in a test migration or write data-independent expectations (a filter
for `no-such-row` expecting `rowCount: 0` passes against any contents). Write cases never
change that state: they roll back (below), so the database is identical before and after every
run.

## Testing write routes

A write file is tested by running it for real. The runner executes every `sql` case inside a
manual-commit transaction it always rolls back — pass or fail — so nothing a case writes ever
persists, suites stay order-independent, and repeated runs are idempotent:

```yaml
version: tesseraql/v1
tests:
  - name: deactivating sato affects one row and the search sees the new status
    sql:
      file: web/api/users/deactivate/deactivate.sql
    params:
      name: sato
    expect:
      updateCount: 1
    verify:
      - sql:
          file: web/api/users/search.sql
        params:
          q: sato
          limit: 50
          offset: 0
        expect:
          rowCount: 1
          rows:
            - status: INACTIVE
```

`expect.updateCount` asserts the number of rows the statement affected. The optional `verify:`
list holds read-back steps — each a query file with `params` and its own `expect` — that run on
the same connection, inside the same transaction, after the target: they observe the case's
uncommitted write and roll back with it. A verify step must return rows (a write file is not a
legal read-back), and `verify:` is only legal on a `sql` case. A write file ending in a
`RETURNING` clause produces result rows instead of an update count and is asserted with
`rowCount`/`rows` like a query.

Write cases record SQL coverage like read cases, and a `verify:` read-back exercises its file
for route and item coverage exactly as a case target does — so a `command-json` route's UPDATE
now counts toward the `route` and `security` coverage kinds. The route's `validate:` and
`notify:` declarations remain separately testable through their own case kinds.

## Running the suites

```sh
tesseraql test --app .
tesseraql coverage --app .
```

`test` runs every suite and exits non-zero on any failure. It connects to the app's `main`
datasource from `tesseraql.yml`, or to an explicit `--jdbc-url` (with `--username` /
`--password`). Useful options:

- `--case <name>` — run only the named case(s), exact match, repeatable. This is how the
  editor's Test Explorer re-runs one failing case.
- `--format json` — machine-readable output: per-case results plus per-file SQL coverage with
  the covered and coverable line lists.
- `--report` — additionally writes the documentation portal's report overlay
  (`.tesseraql/docs/report.json` and `history.json`); `--run-id` labels the run in the trend,
  and `--fail-on-regression` exits with code 2 when SQL coverage dropped against the previous
  run beyond `--regression-tolerance`.
- `--report-dir` — where the report files go (default `<app>/work/reports`).

`coverage` runs the same suites and then enforces the coverage gate: it fails when SQL line or
branch coverage, or any configured kind, is below its threshold.

In CI the Maven goals are the equivalents, bound to the `integration-test` phase:

```sh
./mvnw tesseraql:migrate tesseraql:test tesseraql:coverage \
    -Dtesseraql.appHome=. \
    -Dtesseraql.jdbcUrl=jdbc:postgresql://localhost:5432/myapp
```

The goals write their reports under `target/tesseraql-reports/` (override with
`-Dtesseraql.reportDir`).

## Reports and artifacts

A run writes, under the report directory:

- `junit/TEST-tesseraql.xml`, `tesseraql-result.json`, `index.html` — the test results as
  JUnit XML (for any CI test publisher), JSON, and a standalone HTML page.
- `allure-results/*-result.json` — Allure 2 result files, ready for `allure generate`.
- `coverage/sql-coverage.json` — SQL line/branch coverage per file plus the item-coverage
  kinds below.
- `coverage/cobertura.xml`, `coverage/sonarqube.xml` — Cobertura and SonarQube generic
  coverage exports for CI coverage publishers.
- `coverage/coverage.sarif` — every coverage gap as a SARIF finding, so code scanning can
  annotate uncovered SQL files and unexercised routes directly on pull requests.

With `--report` (or the `tesseraql:report` goal), the run also lands in the app home as
`.tesseraql/docs/report.json` plus a `history.json` trend of recent runs — the overlay the
[documentation portal](documentation-portal.md) renders as per-route pass/fail badges and
per-line SQL coverage highlighting.

## Coverage kinds

SQL coverage is measured from the 2-way SQL structure itself: every line and conditional
branch a case's rendered statement includes counts as covered, so an `/*%if*/` block no case
ever triggers shows up as an uncovered branch.

Beyond SQL lines and branches, the run derives *item coverage* — covered-of-declared per kind:

- `assertion` — cases that actually assert something (an assertion-free suite is visible).
- `route` / `security` — declared routes, and the subset declaring `security:`, whose SQL the
  suites exercise; `api-key`, `mtls`, `webhook`, `view`, `page`, and `document` narrow the
  same idea to routes of those shapes.
- `validation` / `notification` / `http` — every `validate:` rule, `notify:` declaration,
  and outbound call, covered by the cases that evaluate it.
- `message` — every message catalog, covered by the `messages` cases that read it.
- `file-poll`, `queue-consume`, `data-scope`, `workflow`, `mcp`, `mcp-resource`, `mcp-ui`,
  `mcp-prompt` — the corresponding declarations, covered by the cases exercising their SQL.
  Only prompts that read data are declared: a prompt rendering from its arguments alone
  executes no SQL a case could exercise.
- `iam-contract`, `saml`, `oidc`, `scim`, `preference` — inventories of the standard identity
  and account surfaces in use; their gaps are reported as notes, not warnings.

A kind with nothing declared reports 1.0, so enabling a threshold before the app uses a
feature is harmless.

## Threshold gating

The `coverage` command and goal gate on `coverage.thresholds.*` percentages from the app
config, so the bar lives with the app:

```yaml
coverage:
  thresholds:
    sqlLine: 80
    sqlBranch: 80
    route: 90        # a kind's name gates that kind; absent kinds are not gated
    security: 100
    validation: 100
```

`-Dtesseraql.sqlLineThreshold` / `-Dtesseraql.sqlBranchThreshold` (or `--sql-line-threshold` /
`--sql-branch-threshold` on the CLI) supply defaults when the config sets none. Any kind above
can be named as a threshold, except `preference` and `queue-consume`, which are report-only.

## Recording cases from Studio

You do not have to write every case by hand. In [Studio](studio.md)'s API console, a
successful invocation of a query route offers **Save as test case**. The sent query and body
are mapped back onto the route's SQL parameters, the row count observed in the console's
sandboxed run is captured as `expect.rowCount`, and the resulting `sql` case is appended to
`tests/studio-recorded-test.yml`. From then on it runs in CI exactly like a hand-written
case.

Recording currently covers query routes with a bound SQL file and no path parameters.
Anything else states why it is not recordable.

## Further reading

- [documentation-portal.md](documentation-portal.md) — how test results and coverage overlay
  the generated per-route reference.
- [vscode-extension.md](vscode-extension.md) — the Test Explorer integration and in-editor SQL
  coverage rendering.
- [promotion.md](promotion.md) — the test and coverage evidence a release promotion carries.
- [admission.md](admission.md) — the machine-checkable bar shared apps must clear alongside
  their tests.

## Next

- [governance.md](governance.md) — the review gate that runs beside the suites.
- [admission.md](admission.md) — the bar a shared application must clear.
- [promotion.md](promotion.md) — how a tested change reaches production.
- [studio.md](studio.md) — running suites and recording cases in the browser.
