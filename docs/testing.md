# Testing and coverage

A TesseraQL application is tested the same way it is written: declaratively. Test suites are
YAML files under the app's `tests/` directory; each case exercises one declared behavior — a
2-way SQL file, a route's validation rules, its notifications, a job's outbound HTTP step, an
identity contract, or a message catalog — with parameters, and asserts on the rows that come
back. The same run measures coverage: which SQL lines and branches executed, and which declared
routes, rules, and notifications the suites exercised at all.

## Writing a suite

Every `tests/**/*.yml` file with a `tests:` block is a suite. A case has a `name`, exactly one
target, optional `params`, and an optional `expect`:

```yaml
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

## Case kinds

- **`sql`** — runs a 2-way SQL file; the result rows are the case's rows. The file is executed
  as a query, so only files that return rows can be targets: an `UPDATE`/`INSERT`/`DELETE` file
  cannot be a `sql` case. Exercise write routes through their `validate:` and `notify:`
  declarations instead, and assert on read queries. Because of this rule, a test run only ever
  issues reads against the database.
- **`contract`** — runs a named identity contract (for example
  `identity.find-roles-by-user-id`) against the configured realm; its rows are the case's rows.
- **`validate`** — evaluates a route's `validate:` rules against the case's `params` (the
  execution context the rules see, typically a `body:` map). The violations are the rows, each
  carrying `rule`, `field`, `code`, and `message` — so `rowCount: 0` asserts a clean pass.
  Name a single rule with `validate: {route: ..., rule: ...}`; omit `rule` to evaluate them all.
- **`notify`** — evaluates a route's or a job's `notify:` declarations
  (`notify: {route: ...}` or `notify: {job: ...}`, plus an optional `id`). Each notification
  that fires is one row with `notify` (its id), `channel`, `source`, and the resolved payload
  columns. Guards and payload expressions evaluate exactly as at runtime, but no mail or
  webhook is ever sent.
- **`http-call`** — plans a job's outbound HTTP steps without a network request. Each matching
  step is one row with `http` (its id), `method`, the resolved `url` and `host`, `allowed`
  (whether the host is in the egress allow-list), and `credential`.
- **`messages`** — resolves keys from the app's `messages/<locale>.yml` catalogs: one row per
  key with `key`, `locale`, and `text`. Omit `keys` to resolve every key the locale sees; an
  unresolvable key yields a null `text`, so the expectation fails visibly.

## Expectations

`expect.rowCount` asserts the exact number of rows. `expect.rows` is a list of partial
matchers: the first map is checked against the first row, the second against the second, and
each entry must be present in that row (extra columns are ignored; values compare as strings,
so `id: 42` matches a numeric column). A case without `expect` passes when its target executes
without error — useful for data-independent smoke cases that still record coverage.

Suites run against a real database — the schema and any seed data your migrations create.
Apply migrations first (`tesseraql migrate --app .` or the `tesseraql:migrate` goal), and
either seed known rows in a test migration or write data-independent expectations (a filter
for `no-such-row` expecting `rowCount: 0` passes against any contents).

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
- `validation` / `notification` / `http-call` — every `validate:` rule, `notify:` declaration,
  and outbound HTTP step, covered by the cases that evaluate it.
- `message` — every message catalog, covered by the `messages` cases that read it.
- `file-poll`, `queue-consume`, `data-scope`, `workflow`, `mcp`, `mcp-resource`, `mcp-ui` —
  the corresponding declarations, covered by the cases exercising their SQL.
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

You do not have to write every case by hand. In Studio's API console, a successful invocation
of a query route offers **Save as test case**: the sent query and body are mapped back onto the
route's SQL parameters, the row count observed in the console's sandboxed run is captured as
`expect.rowCount`, and the resulting `sql` case is appended to `tests/studio-recorded-test.yml`
— from then on it runs in CI exactly like a hand-written case. Recording currently covers query
routes with a bound SQL file and no path parameters; anything else states why it is not
recordable.

## Further reading

- [documentation-portal.md](documentation-portal.md) — how test results and coverage overlay
  the generated per-route reference.
- [vscode-extension.md](vscode-extension.md) — the Test Explorer integration and in-editor SQL
  coverage rendering.
- [promotion.md](promotion.md) — the test and coverage evidence a release promotion carries.
- [admission.md](admission.md) — the machine-checkable bar shared apps must clear alongside
  their tests.
