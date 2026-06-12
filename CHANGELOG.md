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

- Internationalization (roadmap Phase 22, see
  [docs/internationalization.md](docs/internationalization.md)): per-app message catalogs
  (`messages/<locale>.yml`, nested maps flattened to dotted keys, layered over framework
  built-ins shipped in English and Japanese); per-request locale resolution after
  authentication — configured preference sources (`principal.*` claims, `query.*`
  parameters), then `Accept-Language` (RFC 4647 lookup against `tesseraql.i18n.locales`),
  then `tesseraql.i18n.defaultLocale` — published as the `request.locale` format source;
  `#{key}` message lookup in templates with the shell's `lang` following `#locale`;
  localized validation and error messages (the declared key rides as `messageKey` /
  `data-message-key`, `message` carries the resolved text, constraint violations fall back
  to `tql.constraint.<code>`, the conflict hint is the `tql.conflict.stale` key, and the
  top-level message localizes the status phrase); input-constraint rejections become
  field-scoped errors with `tql.input.<code>` keys; and locale-aware input parsing —
  `date`/`datetime`/`number` inputs with an optional `format` pattern parse with the request
  locale through the file-transfer column machinery. Breaking: a field error's `message` is
  now display text (the key moved to `messageKey`), and `Templates.render` without a locale
  renders English instead of `Locale.ROOT`.
- A client-side message catalog for Hypermedia Components:
  `/assets/_tesseraql/messages.js?locale=<tag>` serves an ES module merging the app's
  catalog over the kit's strings via `setMessages`, loaded by the shell before behaviors
  install (hc adoption Theme 6, folded into Phase 22).
- Hypermedia Components 0.1.1 (the upstream answer to the Phase 22 feedback issues
  #216–#219): the kit's i18n catalog is now a shared singleton across dist bundles, so
  `setMessages` works from any entry; the client catalog module imports the kit's official
  `dist/locales/ja.js` pack instead of a framework-maintained translation copy (the
  hand-kept catalog is gone — packs are completeness-checked upstream); field-error items
  carry `data-message-params`, so client-side catalog overrides interpolate the violation's
  values (`{min}`, custom SQL-rule columns) after a swap; and the kit documents the blessed
  date-field pattern the Phase 23 scaffolds will emit.
- Hypermedia Components 0.1.0 (from 0.0.1-alpha.0). htmx error fragments now follow the
  kit's documented field-errors contract — `hc-alert` with `data-variant="error"`,
  `role="alert"`, `data-hc-field-errors`, `hc-alert__error` items carrying
  `data-message-key` (was `data-message`) — so the kit's auto-installed
  `installFieldErrors` behavior distributes violations next to their inputs with ARIA
  wiring and no app JS. Breaking markup change: the invented
  `hc-alert-error`/`hc-alert-message`/`hc-field-errors`/`hc-field-error`/`hc-alert-hint`
  classes are gone. The system bootstrap now swaps 4xx field-errors fragments for htmx
  callers (htmx 2 leaves error responses unswapped by default).
- Printable documents (roadmap Phase 21, see
  [docs/printable-documents.md](docs/printable-documents.md)): the optional `tesseraql-pdf`
  module adds a `pdf` codec behind the file-codec SPI — `format: pdf` on
  `query-export`/`file-export` renders an app-authored XHTML print template (or a built-in
  plain grid) through the standard template engine and converts it to PDF with page-oriented
  CSS (`@page` size/margins, `counter(page)`/`counter(pages)` margin boxes, repeating table
  headers). Fonts under the app home's `fonts/` directory embed automatically under their own
  family names, CJK included; template resource resolution is confined to the app home and
  never fetches the network. Output is normalized — fixed producer, no timestamps or XMP, a
  seeded trailer `/ID` — so identical data yields byte-identical documents (design ch. 48).
  Rendering goes through openhtmltopdf, adopted at the ch. 50 decision point after
  prototyping an Apache PDFBox alternative behind the module's `PdfEngine` SPI; the SPI (and
  `tesseraql.pdf.engine`) remains the seam for drop-in replacement, and the LGPL dependency
  stays confined to the opt-in module - apps that never print do not install it.
- Notifications (roadmap Phase 20, see [docs/notifications.md](docs/notifications.md)): a
  `notify:` block on `command-json` routes and a `notify:` pipeline step on batch jobs send
  through configured channels — SMTP mail (camel-mail) with the body and subject rendered by
  the standard template engine (app-home-confined templates, credentials resolved at send
  time through the SecretResolver SPI), and outbound webhooks signed with HMAC-SHA256 over
  `timestamp.body` (`X-TesseraQL-Signature`/`X-TesseraQL-Timestamp`). Notifications enqueue
  in the command's transaction and ride the outbox; each publishes `notify.<id>.eventId`.
- Outbox retries and dead-letters: `FAILED` events retry on later dispatch polls and
  dead-letter at `tesseraql.outbox.dispatch.maxAttempts` (default 10). The operations
  console gains an outbox delivery-log screen and API (`GET /_tesseraql/ops/outbox`,
  `POST /_tesseraql/ops/outbox/{id}/redeliver`), and dead letters raise `TQL-OPS-9006`.
- Operations alerts reuse the notification channels: with
  `tesseraql.notifications.alerts.channel` configured, failed job executions notify as
  `ops.jobFailure` and newly raised dashboard alerts as `ops.alert`.
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
- System apps recomposed on Hypermedia Components 0.1.0 primitives: `hc-field` form stanzas
  (label association, automatic required marks), a single status→variant mapping rendered
  through `hc-status`/`data-fill` (the `status-*` classes and their hand-kept dark-theme
  overrides are gone), `hc-empty` empty states, `hc-chips`/`hc-badge` counters, the `kv`
  table variant, `hc-item` sidebar navigation with an `aria-current` marker set by the
  bootstrap, and `hc-spacer`/`hc-cluster` page headers. The app stylesheet shrinks from
  40 lines to the card heading scale and the Studio source editor. The blessed htmx
  patterns — confirmed actions (`data-hc-confirm` × `hx-trigger="hc:confirmed"`), live data
  regions, busy indicators, inline field errors — are documented in
  [docs/hypermedia-ui.md](docs/hypermedia-ui.md).
- Document-number sequences as a managed SQL contract (`sequence:` steps backed by
  `tql_doc_sequence`, V2 framework migration): gapless allocation under the sequence row's
  lock, riding the command transaction.

### Quality and supply chain

- Declarative suites gain `messages:` cases (roadmap Phase 22): a case resolves keys of the
  app's message catalogs (exact tag, then bare language, like the runtime) and asserts on
  the texts as rows (`key`/`locale`/`text`). A new `message` coverage kind declares every
  shipped catalog by its language tag and counts it covered when a messages case reads it,
  gated via `coverage.thresholds.message`. Lint checks the catalogs statically:
  malformed files or non-BCP-47 names raise `TQL-YAML-1007`, a declared locale without a
  catalog warns `TQL-YAML-1103`, translation gaps against the default locale warn
  `TQL-YAML-1008`, and a validation-rule or constraint-mapping message key with no
  default-locale text warns `TQL-FIELD-2005`.
- A `document` coverage kind (roadmap Phase 21) declares every route exporting a printable
  document (`format: pdf`) and counts it covered when a suite case exercises one of its SQL
  artifacts, gated via `coverage.thresholds.document`. Lint checks pdf exports
  statically: workbook-only options raise `TQL-YAML-1005`, a non-`.html` or missing template
  raises `TQL-YAML-1006`.
- Declarative suites gain `notify:` cases (roadmap Phase 20): a case evaluates a route's
  `notify:` block or a job's notify steps against its params — guards and payload
  expressions run exactly as at runtime — and asserts on the fired notifications as rows,
  without touching SMTP or HTTP. A new `notification` coverage kind declares every route
  notification as `<routeId>.<notifyId>` and every job notify step as `<jobId>.<stepId>`,
  gated via `coverage.thresholds.notification`. Lint checks the declarations
  statically (`TQL-YAML-1004`, `TQL-FIELD-2004`, `TQL-SQL-2101`, `TQL-YAML-1102`).
- Declarative suites gain `validate:` cases (roadmap Phase 19): a case evaluates a route's
  validation rules — SQL rules against the test database, expression rules against the
  case's params — and asserts on the violations as rows, recording SQL coverage along the
  way. A new `validation` coverage kind declares every rule as `<routeId>.<ruleId>`, tracks
  the rules the suites evaluated, and gates via `coverage.thresholds.validation`.

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
