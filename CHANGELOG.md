# Changelog

All notable changes to TesseraQL are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## Unreleased

### Core

- 2-way SQL `%for` directive: an optional `separator ','` (kept inside the directive comment,
  so multi-row `INSERT ... VALUES` templates stay SQL-tool-runnable) and a 0-based
  `<item>_index` loop variable.
- Dialect capability matrix: generated-key retrieval style (`columns` for PostgreSQL/Oracle,
  `auto` for MySQL/SQL Server).
- `TqlException` carries an explicit client-safe `details` payload, rendered into error
  responses (field-level errors, conflict hints) without leaking internals.

### Runtime and recipes

- Declarative validation for `command-json` (roadmap Phase 19, see
  [docs/declarative-validation.md](docs/declarative-validation.md)): a `validate:` block of
  cross-field rules in the whitelist-only core expression language plus validation SQL
  (SELECTs whose returned rows are the violations — uniqueness, existence, balance checks)
  executed inside the command's transaction, before any step writes. Violations answer a
  field-scoped `422` (`TQL-FIELD-4220`) with a stable error model — rule ids, field paths,
  rule codes, message keys — as JSON or as an inline `hc-alert` fragment for htmx. Lint
  checks the block statically (`TQL-YAML-1003`, `TQL-FIELD-2003`, `TQL-SQL-2101`/`2103`).
- Transactional write depth for `command-json` (roadmap Phase 18, see
  [docs/transactional-writes.md](docs/transactional-writes.md)): an ordered `steps:` list
  executes in a single transaction; later steps bind values produced by earlier ones,
  including generated keys (`keys:`, published as `steps.<name>.keys.<column>`).
- Canonical audit binds `/* audit.user */` and `/* audit.now */`, resolved from the
  principal and one clock reading per command.
- Declared row-count expectations (`expect: { rows: 1, onMismatch: conflict }`) turn silent
  lost updates into `409 Conflict` (`TQL-SQL-4092`) with a usable conflict hint; lint nudges
  the version-predicate/expectation pairing on UPDATEs (`TQL-SQL-2104`/`2105`).
- Constraint-violation mapping (`errors.constraints`): unique/foreign-key SQLState failures
  map to field-level error payloads, rendered as JSON or as an inline `hc-alert` fragment
  for htmx requests. Outbox commands now classify constraint failures like the standard
  pipeline (a NOT NULL violation answers 400, not 500).
- Document-number sequences as a managed SQL contract (`sequence:` steps backed by
  `tql_doc_sequence`, V2 framework migration): gapless allocation under the sequence row's
  lock, riding the command transaction.

### Quality and supply chain

- Declarative suites gain `validate:` cases (roadmap Phase 19): a case evaluates a route's
  validation rules — SQL rules against the test database, expression rules against the
  case's params — and asserts on the violations as rows, recording SQL coverage along the
  way. A new `validation` coverage kind declares every rule as `<routeId>.<ruleId>`, tracks
  the rules the suites evaluated, and gates via `coverage.thresholds.kinds.validation`.

## 0.1.0 - 2026-06-11

First public release: the complete framework, built and
verified per feature against live databases.

### Core

- 2-way SQL engine: bind comments with dummy values, `%if`/`%elseif`/`%else`, IN-list
  expansion, orderBy whitelists, a dependency-free expression evaluator, source maps, and
  coverage traces. Every SQL file stays executable in plain SQL tools.
- Simple YAML route/job model with manifest loading, app-home path confinement, hierarchical
  config with `${VAR:default}` resolution, and a pluggable SecretResolver SPI (`env`, `file`).

### Runtime and recipes

- Camel Main runtime (`tesseraql serve`) and a Spring Boot adapter; route recipes
  `query-json`, `command-json`, `query-html`, `page`, `query-export`, `file-import`,
  `file-export`, plus batch jobs (`batch-tasklet`, `batch-pipeline`) with quartz/timer
  triggers. Unknown recipes fail compilation.
- Multi-app hosting: mounted apps (ops-console, studio, iam-admin, `.tqlapp` packages or
  hash-pinned URL fetch) compile like the main app with their own migrations, scheduled jobs,
  outbox attribution, and per-app operations scope.
- Large-data: streaming SQL with dialect profiles, off-heap spooling (TempStore SPI),
  materialization guards, backpressure lanes with virtual-thread policies.
- Asynchronous CSV/Excel file transfers: YAML column mapping with per-login-user locale and
  time-zone formats, multipart and raw uploads streamed off-heap, all-or-nothing or skip
  error modes, exactly-once download hooks, and synchronous `query-export` downloads through
  the same codecs. Excel via the optional `tesseraql-excel` module (fastexcel + jxls).
- Live route reload from Studio with rollback: a broken edit never takes a serving endpoint
  down.

### Security, identity, federation

- Deny-by-default policy engine (roles/permissions/claims), JWT bearer and JDBC-backed
  session auth, CSRF, field-level authorization and mass-assignment guards, data
  classification and masking, CSP headers.
- Managed identity schema (`tql_*`) with Identity SQL Contracts, identity packs for all four
  databases, an admin UI, and seeded initial administrators (no default credentials).
- SAML SP: metadata, signed AuthnRequests, assertion validation, replay protection
  (single-use InResponseTo, assertion-id cache), signed HTTP-Redirect, IdP-initiated SLO,
  optional link-or-provision of local users.
- SCIM: inbound Users/Groups endpoints over app-authored contract SQL, PATCH normalization,
  and outbox-driven outbound provisioning.

### Operations

- Job repository with app-scoped claims across nodes, outbox dispatch with per-app
  attribution, idempotency store, retention sweeps.
- Operations console and `/_tesseraql/ops` API: batch dashboard, trace trees with app
  attribution, slow SQL, lanes, pinning diagnostics, alerts, file transfers - all scoped to
  the caller's `ops.app.<name>` grants (deny by default).
- Deployment: multi-stage container image, Kamal 2 + Cloudflare Tunnel reference setup,
  unauthenticated `/_tesseraql/health`.

### Quality and supply chain

- Declarative test suites (`tests/**.yml`) with SQL line/branch coverage and route, security,
  assertion, IAM-contract, SAML, and SCIM coverage kinds, gated per `coverage.thresholds.*`.
- Query Plan Guard (static/explain/analyze) with dialect plan inspectors and baselines.
- Reports: JUnit XML, HTML, JSON, SARIF, Cobertura, SonarQube generic coverage, Allure.
- Maven plugin goals: `lint`, `test`, `coverage`, `generate` (OpenAPI + htmx contract),
  `package-app`, `migrate`, `identity-schema`, `release-evidence`, `verify-evidence`,
  `governance`.
- Supply chain: Ed25519-signed release evidence, CycloneDX SBOM with license data,
  signature-verified plugin jars in isolated classloaders, hash-pinned `.tqlapp` packages,
  byte-stable generated artifacts.

### Databases

- PostgreSQL 16, MySQL 8, Oracle (23ai), and SQL Server 2022: dialect-aware SQL file
  resolution, label normalization, pagination/claim variants, Flyway migrations per
  component, datasource, and vendor - verified by live integration tests.
