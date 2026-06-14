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

- Application-declared MCP endpoints (roadmap Phase 24 follow-on, see
  [docs/ai-mcp.md](docs/ai-mcp.md)): an app declares Model Context Protocol tools under
  `mcp/` — a `query-json` or `command-json` definition (with a `description`) exposed over MCP
  instead of HTTP — and the runtime serves them over the Streamable HTTP transport at
  `/_tesseraql/mcp`, so the running business application is AI-enabled. Each tool compiles to an
  internal route running the full pipeline (the tool's own authentication and authorization,
  input validation, 2-way SQL or the transactional command), and the MCP endpoint dispatches a
  `tools/call` to it carrying the request's bearer token — so a tool is secured exactly like a
  route (per-tool `auth`/`policy`; discovery is open; an unauthorized call returns an MCP tool
  error). The advertised input schema is derived from the route's `input:` constraints. The
  governance gate scores and gates tools like routes (a write tool reachable without
  authentication is `advanced`); lint requires a write tool to declare a policy
  (`TQL-MCP-4030`, deny-by-default) and flags unknown recipes / missing descriptions; and a new
  `mcp` coverage kind tracks tools exercised by declarative suites. Disable with
  `tesseraql.mcp.enabled: false`. The transport-agnostic protocol core lives in `tesseraql-mcp`
  (added for the Phase 24 dev-tool server); the runtime reuses its `McpHttpHandler` from a Camel
  route. `AppManifest` gains `tools()` (discovered from `mcp/`).
- Application-declared MCP resources (roadmap Phase 24, see
  [docs/ai-mcp.md](docs/ai-mcp.md)): alongside its tools an app declares read-only Model Context
  Protocol *resources* — context an agent attaches — as a `kind: resource` document under `mcp/`.
  A resource is a `query-json` definition addressed by a stable `uri` (no arguments: its uri is
  the whole address) with an optional `mimeType` (default `application/json`); the compiler builds
  it into a read-only internal route running the full read pipeline (the resource's own
  `auth`/`policy`, tenancy and locale resolution, 2-way SQL), and the runtime answers
  `resources/list` / `resources/read` from it over the same `/_tesseraql/mcp` endpoint, carrying
  the request's bearer token — so a resource is secured exactly like a read route (discovery is
  open; an unauthorized read returns a `resources/read` JSON-RPC error). Lint keeps resources
  read-only and uri-addressed (`TQL-MCP-1003`/`1004`/`1006`, duplicate-uri `TQL-MCP-1007`, missing
  description `TQL-MCP-1005`); the governance gate scores a resource like a read route (never
  `advanced`); and an `mcp-resource` coverage kind tracks resources exercised by declarative
  suites. The protocol core (`tesseraql-mcp`) gains `McpResource` and the `resources/*` methods,
  advertising the resources capability only when some are registered. `AppManifest` gains
  `resources()` (discovered from `mcp/` by `kind`).
- Application-declared MCP Apps UI (roadmap Phase 24, see [docs/ai-mcp.md](docs/ai-mcp.md)): a tool
  can hand back interactive UI instead of only JSON — the [MCP Apps
  extension](https://modelcontextprotocol.io/community/seps/1865-mcp-apps-interactive-user-interfaces-for-mcp)
  (SEP-1865). An app declares a UI resource as a `kind: ui` document under `mcp/` — a `query-html` /
  `page` definition addressed by a stable `ui://` uri, with optional `ui:` rendering hints
  (`prefersBorder`, content-security-policy domains) — and a `kind: tool` document links to one via a
  `ui:` field. The compiler builds the UI resource into a read-only internal route that
  server-renders an `hc-*` fragment through the existing template pipeline (UI work follows the
  blessed `hc-*` patterns, mandatory rule 11), and the runtime serves it over the same
  `/_tesseraql/mcp` endpoint: `resources/list` / `resources/read` answer with the rendered fragment
  tagged `text/html;profile=mcp-app` and its `_meta.ui`, a linking tool advertises
  `_meta.ui.resourceUri`, and `initialize` negotiates the
  `capabilities.extensions["io.modelcontextprotocol/ui"]` extension when the app serves any UI
  resource. Security is per-resource (the bearer token rides into the route; an unauthorized read is
  a `resources/read` JSON-RPC error). Lint keeps a UI resource HTML-rendering and uri-addressed
  (`TQL-MCP-1008`/`1009`/`1011`), warns on a missing description (`TQL-MCP-1010`), rejects a dangling
  tool link (`TQL-MCP-1012`), and folds UI uris into the duplicate-uri check (`TQL-MCP-1007`); the
  governance gate scores a UI resource like a read route (never `advanced`); and an `mcp-ui` coverage
  kind tracks UI resources exercised by declarative suites. The protocol core (`tesseraql-mcp`)
  carries an opaque `_meta` on `McpTool`/`McpResource` and negotiates extensions in `initialize`.
  `AppManifest` gains `uiResources()` (discovered from `mcp/` by `kind`).
- Mounted-app MCP (roadmap Phase 24 mounted-app tools, see [docs/ai-mcp.md](docs/ai-mcp.md)): the
  runtime serves the MCP tools, resources, and UI resources declared by mounted and bundled system
  apps (design ch. 32) — not only the main app — from the one `/_tesseraql/mcp` endpoint. Each app's
  `direct:mcp.*` routes compile as before; the runtime registers every hosted app's MCP surface
  together and negotiates the MCP Apps UI extension when any hosted app serves a `ui://` resource.
  Security stays per-route (the bearer token rides into the declaring app's route; mounted apps share
  the main app's config, so policies and the JWT verifier resolve the same way). The startup
  route-conflict check now spans the MCP surface too: a tool name, resource uri, or UI uri shared by
  two apps (resources and UI resources share one uri namespace) is rejected with a clear error rather
  than failing as a raw duplicate-route-id error. The endpoint is wired whenever any hosted app
  declares an MCP surface, still governed by the single `tesseraql.mcp.enabled` flag.
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
- Authentication completion — RS256/JWKS and API keys (roadmap Phase 25, see
  [docs/authentication.md](docs/authentication.md)), both behind the existing authentication
  step and `Principal` model, JDK-only (no JOSE dependency):
  - **RS256 bearer validation.** `tesseraql.security.jwt.algorithm: RS256` verifies tokens with
    `SHA256withRSA` against a static `publicKey` (PEM, X.509 certificate, or JWK JSON) or a
    `jwksUri`. The JWKS key set is cached and refreshed by `kid`; an unknown `kid` (a rotated-in
    key) triggers at most one refetch per `jwks.refreshFloor`, so random-`kid` tokens cannot
    flood the JWKS endpoint, and an unknown `kid` that survives a permitted refetch fails closed.
    The expected algorithm is bound from configuration and checked against the token header before
    any key is consulted, so an `alg: none` or RS256/HS256-confusion token is rejected. A
    configurable `clockSkew` leeway applies to `exp` and the now-honored `nbf`.
  - **API keys for service callers.** A route declares `auth: apiKey`; the key is presented in a
    configured header (default `X-API-Key`) or as `Authorization: ApiKey <key>`. Clients are
    declared under `tesseraql.security.apiKeys.clients` with a stored hex SHA-256 of the key
    (never the raw key, resolvable via the secret SPI), an explicit subject/tenant/roles/
    permissions, and an enabled flag; the presented key is hashed and compared in constant time,
    deny-by-default, and the matched client's principal flows through the same authorization
    policies with its tenant bound from the key, not the request.
  - **Machine-checkable.** Lint adds `TQL-SEC-4040..4043` (RS256 key-source and algorithm-
    confusion rules) and `TQL-SEC-4044..4046` (an `auth: apiKey` route needs API-key config; a
    client needs a `secretHash`; a client granting nothing is warned); a new `api-key` coverage
    kind tracks API-key-authenticated routes, gatable via `coverage.thresholds.api-key`.
  - **Breaking change.** `SecurityConfig.JwtConfig` gains `algorithm`, `publicKey`, `jwksUri`,
    `jwks`, and `clockSkew` components, and `SecurityConfig` gains an `apiKeys` component
    (a two-arg constructor keeps the no-API-key case). HS256 with a `secret` is unchanged.

### Developer experience

- Hypermedia Components 0.1.2 (the upstream answer to the Phase 23 feedback issues
  #244/#245/#246). Adopted across the framework and the scaffolds:
  - **CSRF on by default** for scaffolded mutations. State-changing browser routes declare
    `csrf: true`; the framework shell publishes the session token as
    `<meta name="csrf-token">` (browser authentication stashes it, the HTML renderer injects
    the reserved `_csrf` model variable), and the kit's auto-installed `installCsrfHeader`
    behavior attaches the `X-CSRF-Token` header to every htmx request. The no-JS path carries
    a hidden `_csrf` form field; the `csrf` step accepts the header or the field, and the
    request binder treats `_csrf` as reserved so it passes the mass-assignment guard.
  - **The mutating-form recipe** in scaffolded create/edit forms: an htmx post with inline
    field errors, a success redirect (the redirect renderer answers htmx callers `204` +
    `HX-Redirect` and no-JS callers a plain `303 Location`), a double-submit guard and busy
    spinner, the confirmed-destructive delete variant, and a no-JS fallback — see
    [docs/hypermedia-ui.md](docs/hypermedia-ui.md).
  - The kit's field-errors fix (same-name groups resolve to the visible control) corrects the
    ARIA wiring of the boolean checkbox the scaffolds already emit; the boolean field pattern
    is now blessed upstream.
- Scaffolding and project generation (roadmap Phase 23, see
  [docs/scaffolding.md](docs/scaffolding.md)): `tesseraql new <app>` generates a runnable
  skeleton — config, a Flyway migration whose starter table follows the Phase 18 write
  conventions, the shared nav template, a home page, a query-json search route, and a smoke
  suite covering both branches of its 2-way SQL. `tesseraql scaffold crud --table <t>`
  introspects the table over plain JDBC (the app's main datasource or `--jdbc-url`) and
  generates its CRUD slice: a list page with htmx live search over a table fragment, create
  and edit forms in Hypermedia Components markup (hc-field stanzas, the blessed
  `hc-datepicker` date fields, confirmed deletes), 2-way SQL with canonical audit binds and
  the optimistic-locking pairing (version predicate + `expect.rows`, `409` on stale edits),
  unique-index constraint mappings, and a declarative suite with data-independent
  expectations. Every bind reads the coerced `params.*` input view, so browser form posts
  and path parameters hit typed columns as typed parameters.
- Regeneration is idempotent and detects user edits (design ch. 22.20): each generated file
  carries a `tesseraql-scaffold-checksum` comment over its own content; pristine files
  regenerate, edited files are skipped and reported (exit 1), unmarked files are never
  touched, and `--force` overrides both. No ledger outside the files themselves.
- The example gallery gains `examples/scaffold-demo-app`, built exclusively by the two
  commands and dogfooded in CI: a Maven-plugin integration test regenerates it from the
  migration applied to PostgreSQL and asserts the committed tree is byte-identical, lints
  it, and runs its suites (100% branch coverage on the generated search templates); a
  runtime integration test drives the full CRUD flow over HTTP, including the stale-version
  `409` and the duplicate-key field error.
- AI-assisted development over MCP (roadmap Phase 24, see [docs/ai-mcp.md](docs/ai-mcp.md)):
  `tesseraql mcp --app <dir>` serves the framework's developer surfaces as Model Context
  Protocol tools, so an agent connected only over MCP scaffolds a table-backed route and
  iterates until lint, tests, and coverage pass with no direct filesystem access. Read tools
  (`manifest_summary`, `source_read`, `schema_introspect`, `lint`, `test`, `ops_status`) and
  gated write tools (`scaffold_crud` through the checksum-aware writer; `draft_save` /
  `draft_preview` / `draft_apply` through Studio's draft/apply, so an edit only lands if it
  compiles) each reuse the same service the CLI and Maven plugin use, all confined to the app
  home (design ch. 20.2). Two transports: stdio (the default — an agent launches the process;
  stdout is reserved for protocol frames), or `--transport http` for a shared development
  server whose Streamable HTTP endpoint reuses the app's `tesseraql.security.jwt` bearer
  verification and refuses to bind off-loopback without auth unless `--insecure`;
  `--read-only` drops the write tools. The protocol core is a new dependency-light module,
  `tesseraql-mcp` (JSON-RPC dispatch, the tool model, and the stdio and HTTP transports),
  reusable beyond the dev tool. New error domain `MCP` in the `TQL-*` taxonomy.
- The lint engine and the declarative test/coverage runner moved out of the Maven plugin into
  libraries so non-Maven callers (the MCP server) reuse them: `AppLinter`/`LintFinding` are
  now in `tesseraql-yaml` (`io.tesseraql.yaml.lint`), and `AppTestRunner` (with
  `DriverManagerDataSource` and `CoverageThresholdResolver`) in `tesseraql-report`
  (`io.tesseraql.report`). The Maven goals are unchanged.

### Quality and supply chain

- Lint fix: dotted policy ids (`users.read`) now resolve as literal keys of the
  `tesseraql.security.policies` map, so `TQL-SEC-4030` no longer fires for every defined
  policy.
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
