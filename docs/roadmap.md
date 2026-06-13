# Roadmap

Drafted 2026-06-11, after the 0.1.0 release. 0.1.0 implements the full original design
document (56 chapters): the 2-way SQL engine, the YAML route/job model and compiler, the
Camel Main and Spring runtimes, security and IAM (managed and SQL-contract realms, SAML SP,
SCIM), batch and operations (console, UI, traces), large-data streaming and file transfer,
the test/coverage/reporting stack, multi-tenancy and app packaging, dialect portability,
the Maven plugin, supply-chain tooling, Studio, and generated OpenAPI/htmx contracts. See
[CHANGELOG.md](../CHANGELOG.md).

This document plans what comes next: closing the gap between "the framework is
feature-complete against its design" and "teams build and operate real line-of-business
(LOB) applications on it". Horizons are ordered by what blocks a real application soonest.
Phase numbers continue the original implementation plan (which ended at Phase 17). The
further out a phase sits, the more it is direction rather than commitment; revisit this
document at every minor release. Release trains per horizon are indicative, not promised.

## Gap analysis

What a typical business application (forms over data, approvals, printable documents, batch
exchange with neighboring systems) still needs, against what 0.1.0 provides:

| Need | In 0.1.0 |
| --- | --- |
| Multi-statement transactional writes (header + detail) | `command-json` runs a single statement (plus an atomic outbox event) |
| Optimistic locking, audit columns | not provided; hand-written per app |
| Business-rule validation | input constraints only (type/required/range/enum/mask); no rule hooks |
| Mail, outbound webhooks, alert delivery | absent |
| Printable documents (PDF) | absent (CSV/TSV/Excel codecs exist) |
| UI and message localization | absent (locale handling exists only in export formatting) |
| Scaffolding / code generation | absent (CLI has `serve` and `routes` only) |
| Corporate SSO | SAML SP yes; OIDC relying party not yet (JWT HS256 bearer only) |
| File/queue/HTTP integration recipes | `file-import`/`file-export` exist; no polling triggers, no HTTP-call step, no broker recipes |
| Approval workflow | absent (route governance reviews routes, not business documents) |
| Org-unit data scoping | tenancy yes; organizational scoping not formalized |
| Object storage | `FileTempStore` only (the `TempStore` SPI is ready) |
| Full-text search | absent |
| HTTP caching | absent |
| HA building blocks | solid base: cluster-safe job claiming, JDBC session store; missing K8s assets and zero-downtime guidance |
| Distribution | GitHub Releases only; no Maven Central, no Gradle plugin, no docs site |
| AI tooling | dev-tool MCP server shipped (Phase 24); app-declared MCP endpoints next |

## Horizon 1 — application completeness (0.2.x)

Everything here is needed by nearly every LOB application; without it, teams fall back to
hand-rolled glue inside the first week.

### Phase 18 — transactional write depth

`command-json` grows from "one statement" to "one business operation".

- A command may declare an ordered list of SQL steps executed in a single transaction;
  later steps can bind values produced by earlier ones (generated keys via
  `RETURNING`/`getGeneratedKeys` per dialect capability). Each step stays a plain,
  SQL-tool-runnable 2-way SQL file.
- Declared row-count expectations (`expect: { rows: 1, onMismatch: conflict }`) turn silent
  lost updates into `409 Conflict`; a lint nudges version-column predicates on `UPDATE`.
- Canonical audit binds — `/* audit.user */`, `/* audit.now */` — resolved from the
  principal and clock, so audit columns are explicit in the SQL, never injected.
- Constraint-violation mapping: unique/foreign-key SQLState errors map to field-level error
  payloads (and htmx inline errors) instead of opaque 500s.
- Document-number sequences (gapless option with row-lock semantics) as a managed SQL
  contract.

Acceptance: an order header+lines form posts once, writes atomically, replays safely via
the existing idempotency machinery, and a concurrent edit yields a 409 with a usable
conflict hint.

### Phase 19 — declarative validation

- A `validate:` block: cross-field rules using the core expression language (already
  whitelist-only), plus validation SQL contracts (SELECTs returning violations — uniqueness,
  existence, balance checks) executed in the command's transaction.
- A stable error model: rule codes, field paths, message keys (translations arrive in
  Phase 22).
- A `validation` coverage kind: rules exercised by declarative suites.

Acceptance: a unique-email rule rejects with a field-scoped 422 in JSON and an inline error
fragment in htmx, and the rule is testable in a declarative suite.

### Phase 20 — notifications

- A `notify:` step for command pipelines and jobs: SMTP mail (camel-mail) with templates
  rendered by the existing engine (template trust modes apply), credentials via the
  SecretResolver SPI.
- Delivery rides the existing outbox: at-least-once, retries and dead-letters visible in
  the operations console.
- Outbound webhooks: HMAC-signed, retried from the outbox, delivery log in the ops UI.
- Operations alerts (job failure, threshold breach) reuse the same channels.

### Phase 21 — printable documents (PDF)

- A `pdf` codec behind the existing file-codec SPI (`query-export`/`file-export`),
  rendering HTML templates with page-oriented CSS (headers, footers, page numbers) and
  embedded CJK fonts.
- Engine choice is a license-policy decision (ch. 50): openhtmltopdf (LGPL) vs an Apache
  PDFBox-based stack (Apache-2.0). Prototype both behind the SPI.
- Deterministic output (fixed metadata timestamps) so exports stay reproducibility-friendly.

### Phase 22 — internationalization

- Per-app message catalogs (`messages/<locale>.yml`), locale resolution (user preference →
  `Accept-Language` → app default), message lookup in templates, localized validation and
  error messages, and locale-aware input parsing (dates/numbers) mirroring the export-side
  `FormatSources`.

**Milestone M6** — a complete LOB slice: header+detail order entry with optimistic locking,
declarative validation, a Japanese/English UI, a confirmation mail, and a printable PDF —
built only with YAML, 2-way SQL, and templates, fully covered by declarative suites.

## Horizon 2 — developer experience and AI (0.3.x)

### Phase 23 — scaffolding and project generation

- `tesseraql new <app>`: a runnable skeleton (manifest, config, migration, smoke suite).
- `tesseraql scaffold crud --table <t>`: JDBC schema introspection generates list/detail/edit
  YAML, 2-way SQL with the Phase 18 audit/locking conventions, htmx pages, and declarative
  tests. Regeneration is idempotent and detects user edits (checksums, ch. 22.20).
- Rebuild the example gallery on scaffolds so the generator is dogfooded in CI.

### Phase 24 — AI-assisted development (MCP)

TesseraQL's artifacts are declarative and machine-checkable (lint, declarative tests,
coverage kinds, plan guard, reproducible generated output) — exactly the feedback loop a
coding agent needs. Turn that into a product surface:

- An MCP server exposing read tools (manifest, route/SQL sources, schema introspection,
  lint/coverage results, ops status) and gated write tools (scaffold, draft edits through
  Studio's draft/apply mechanism, with the same path confinement and review gates).
- Acceptance: an agent connected only via MCP scaffolds a table-backed route and iterates
  until lint, tests, and coverage pass — no direct filesystem access.

Delivered (see [docs/ai-mcp.md](ai-mcp.md)): `tesseraql mcp` serves those tools over two
transports — stdio for a local agent subprocess, and a Streamable HTTP endpoint for a shared
development server (reusing the app's `tesseraql.security.jwt` bearer verification, loopback
by default, `--read-only` to drop the write tools). The protocol machinery — JSON-RPC
dispatch, the tool model, and the transports — is a dependency-light, use-case-neutral module
(`tesseraql-mcp`), so it is reusable beyond the dev tool.

Next step — **application MCP endpoints**: let a TesseraQL application declare its own MCP
tools (and later resources, and MCP Apps UI) in YAML, the way it declares HTTP routes today,
so the running business application is AI-enabled. A `mcp:` recipe compiles to a SQL-backed
`McpTool` whose input schema derives from the route's declared `input:` constraints; the
runtime serves it through its own HTTP server driving the same `McpHttpHandler`, under the
same deny-by-default security, governance (route modes, risk scoring, approvals), lint rules,
and coverage kinds as any route. The dev-tool server above is the first consumer of the
protocol core; this is the second, and the reason the core was factored out rather than built
into the CLI. Scoped behind the same SQL-first, governed-recipe invariants (extension
principles 1–4); deeper Studio-copilot ambitions remain gated on the MCP loop proving its
worth (decision point 4).

**Milestone M7** — schema to verified CRUD in under ten minutes by hand, or hands-off via an
MCP-connected agent.

## Horizon 3 — enterprise sign-in and integration (0.4.x)

### Phase 25 — authentication completion

OIDC relying party (authorization code + PKCE; tested against Entra ID, Okta, Keycloak),
RS256/JWKS bearer validation, API keys for service callers, optional mTLS — all behind the
existing authentication step and principal model, with IAM admin wizards like SAML's.

### Phase 26 — managed connectors (files and HTTP)

- Polling triggers (SFTP/FTPS/local directory) feeding `file-import` pipelines.
- An `http-call` step for outbound REST in pipelines and jobs: secret-managed credentials,
  timeouts, circuit breaking, recorded in traces.
- An inbound webhook recipe: signature verification and replay protection in front of a SQL
  pipeline.
- All of it surfaces as recipes under the existing governance (route modes, allowlists,
  risk scoring) — Camel's component catalog stays an implementation detail, not user API.

### Phase 27 — messaging and events

Outbox relay to a broker (Kafka/JMS) and a `queue-consume` recipe (broker → SQL pipeline
with idempotency keys); at-least-once semantics documented end to end.

**Milestone M8** — corporate SSO login, a nightly SFTP exchange with a legacy system, and
commands emitting events consumed by another system — all declarative, all observable in
the operations console.

## Horizon 4 — LOB building blocks (0.5.x–0.6.x)

### Phase 28 — approval workflow

A SQL-contract state machine, consistent with IAM's managed/SQL realm duality: managed
`tql_workflow_*` tables or app-owned contracts. YAML declares states, transitions, guards
(expression language), and assignee resolution (SQL contracts); htmx task-inbox components;
delegation, escalation, and deadlines via the scheduler; a full audit trail; a `workflow`
coverage kind.

### Phase 29 — organizational data scoping

Org-unit/row-level predicates derived from principal attributes as declared SQL fragments,
with a scope lint (like the tenant-predicate lint) and masking integration.

### Phase 30 — attachments and object storage

An S3-compatible `TempStore`/blob implementation behind the existing SPI; a record-attachment
pattern (metadata table + the file-transfer machinery); a scan-hook SPI; retention wired to
the ch. 44 backup/retention machinery.

### Phase 31 — search

A dialect-aware full-text directive (PostgreSQL `tsvector` first, then per the capability
matrix), still runnable in plain SQL tools via the 2-way mechanism. External engines
(OpenSearch) only ever as an optional adapter.

**Milestone M9** — an approval-workflow application with org-scoped data and attachments.

## Horizon 5 — scale and operations (0.7.x)

### Phase 32 — caching

ETag/`Cache-Control` with 304 handling for `query-json`/`query-html` (htmx-aware), declared
invalidation keys (a command declares which query caches it invalidates), and an opt-in
result cache with TTL. Tenancy-safe keys; correctness over hit rate.

### Phase 33 — deployment and operations maturity

Kubernetes manifests and a Helm chart (probes, graceful drain of lanes and in-flight jobs,
rolling-deploy guidance on top of reload safety), official container images, a
`tesseraql bench` load harness for routes, a capacity/tuning guide, and alert routing
through the Phase 20 channels.

**Milestone M10** — two-node HA on Kubernetes: rolling deploys without dropped requests,
exactly-once scheduled firings, shared sessions, alerts delivered.

## Horizon 6 — the 1.0 contract (0.8.x → 1.0)

### Phase 34 — compatibility contract

Freeze YAML schema v1; SPI stability annotations (`@Stable`/`@Evolving`); a deprecation
policy; cross-version upgrade tests (the ch. 30/31 machinery) running in CI.

### Phase 35 — distribution and documentation

Maven Central publication (GPG signing + central publishing; release evidence is already in
place), the Gradle plugin (promised "later" by the build policy), official images, and a
documentation site: tutorial, cookbook, and reference generated from the schemas and error
taxonomy.

### Phase 36 — security review and support policy

Threat-model refresh, a hardening checklist pass (OWASP ASVS), fuzzing the parsers (2-way
SQL, YAML, SCIM filters), and a supported-versions/LTS statement in SECURITY.md.

**Milestone M11** — 1.0 GA on Maven Central with a documentation site and a compatibility
contract.

## Continuous tracks

- **Platform maintenance**: weekly Dependabot triage (policy encoded in
  `.github/dependabot.yml`); Camel LTS-line upgrades; the Spring Framework 7 / Boot 4
  migration is a deliberate project (majors are Dependabot-ignored) — schedule it alongside
  Horizon 5 so 1.0 does not freeze on aging majors; review the JDK baseline at each LTS.
- **Documentation**: every phase ships its cookbook entry; the example gallery stays green
  in CI.
- **Coverage**: every new declarative surface ships its own coverage kind and thresholds.

## Extension principles

Invariants every phase must keep:

1. **SQL-first**: any capability touching data is expressed in plain-SQL-tool-runnable
   2-way SQL. No ORM, no hidden runtime SQL generation.
2. **Declarative surface, governed escape hatches**: new integrations appear as recipes
   under the same lint/governance/risk model — never as raw Camel in user apps.
3. **Deny-by-default security** and the `TQL-*` error taxonomy extend to every new endpoint.
4. **Machine-checkable everything**: each feature adds its lint rules, coverage kind, and
   deterministic generated artifacts (reproducibility, ch. 48).
5. **Module boundaries hold**: `tesseraql-core` stays dependency-free; heavy dependencies
   live in leaf modules behind SPIs.

## Decision points

None block Phase 18; flagged for the maintainer as their horizons approach.

1. **PDF engine** (Phase 21): openhtmltopdf (LGPL) vs Apache PDFBox stack (Apache-2.0) —
   a license-policy call. Resolved 2026-06-12: both were prototyped behind the codec's engine
   SPI and openhtmltopdf was adopted (full page-oriented CSS; the LGPL dependency stays
   confined to the opt-in `tesseraql-pdf` module). The SPI remains the seam for replacing it.
2. **Workflow engine** (Phase 28): native SQL-contract implementation (proposed — fits the
   philosophy, adds no runtime dependency) vs embedding an external engine.
3. **Adoption timing**: Maven Central and the docs site sit in Horizon 6; pull them forward
   if external adoption becomes a near-term goal.
4. **AI ambition** (Phase 24): the dev-tool MCP server is the scoped bet, and it shipped with
   its protocol core factored out (`tesseraql-mcp`) so the runtime can later serve
   application-declared MCP endpoints from YAML (the Phase 24 "next step"). Deeper
   Studio-copilot features only if the MCP loop proves valuable.
5. **Spring 7 / Boot 4 timing**: proposed alongside Horizon 5, before the 1.0 freeze.
