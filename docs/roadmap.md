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
| AI tooling | dev-tool MCP server; app-declared MCP tools, resources, and Apps UI — for the main app and mounted apps — shipped (Phase 24) |

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

**Application MCP endpoints** (delivered): a TesseraQL application declares its own MCP tools
in YAML, the way it declares HTTP routes, so the running business application is AI-enabled. A
`query-json` / `command-json` definition placed under `mcp/` compiles to a SQL-backed tool
whose input schema derives from the route's declared `input:` constraints; the runtime serves
it over the Streamable HTTP transport at `/_tesseraql/mcp`, driving the same `McpHttpHandler`
the dev-tool server uses, under the same deny-by-default security (per-tool `auth`/`policy`,
the bearer token threaded into the tool's route), governance (risk scoring and the approval
gate, a write tool reachable unauthenticated is `advanced`), lint (a write tool must declare a
policy), and an `mcp` coverage kind. The dev-tool server was the first consumer of the protocol
core; this is the second, and the reason the core was factored out rather than built into the
CLI.

**Application MCP resources** (delivered): alongside its tools an application declares read-only
MCP *resources* — context an agent attaches — as a `kind: resource` document under `mcp/`. A
resource is a `query-json` definition addressed by a stable `uri` (it takes no arguments) with an
optional `mimeType`; the compiler builds it into a read-only internal route, and the runtime
answers `resources/list` / `resources/read` from the same `/_tesseraql/mcp` endpoint under the
same per-resource security (the bearer token rides into the resource's route; an unauthorized read
is a `resources/read` JSON-RPC error). Lint keeps resources read-only and uri-addressed, the
governance gate scores them like read routes (never `advanced`), and an `mcp-resource` coverage
kind tracks the resources declarative suites exercise.

**Application MCP Apps UI** (delivered): a tool can hand back interactive UI instead of only JSON —
the [MCP Apps extension](https://modelcontextprotocol.io/community/seps/1865-mcp-apps-interactive-user-interfaces-for-mcp)
(SEP-1865). An application declares a UI resource as a `kind: ui` document under `mcp/` — a
`query-html` / `page` definition addressed by a stable `ui://` uri — and a `kind: tool` document
links to one with a `ui:` field. The compiler builds the UI resource into a read-only internal
route that server-renders an `hc-*` fragment through the existing template pipeline (so any gap
belongs upstream in the kit, mandatory rule 11, not in app CSS); the runtime serves it over the same
`/_tesseraql/mcp` endpoint, tagging it `text/html;profile=mcp-app`, carrying its `_meta.ui` rendering
hints, advertising a linking tool's `_meta.ui.resourceUri`, and negotiating the
`io.modelcontextprotocol/ui` extension in `initialize`. Security is per-resource (the bearer token
rides into the route), lint keeps a UI resource HTML-rendering and uri-addressed (and rejects a
dangling tool link), the governance gate scores it like a read route (never `advanced`), and an
`mcp-ui` coverage kind tracks the UI resources declarative suites exercise.

**Mounted-app tools** (delivered): the runtime serves the MCP tools, resources, and UI resources
declared by mounted and bundled system apps (design ch. 32), not only the main app, from the one
`/_tesseraql/mcp` endpoint. Each app's `direct:mcp.*` routes are compiled as they already were; the
runtime now registers every hosted app's MCP surface together (negotiating the MCP Apps UI extension
when any of them serves a `ui://` resource), and the startup route-conflict guard — which already
spans HTTP routes across apps — also rejects a tool name, resource uri, or UI uri two apps would
share. Security stays per-route: the bearer token rides into the declaring app's route, and mounted
apps share the main app's config so policies and the JWT verifier resolve the same way. The single
`tesseraql.mcp.enabled` flag governs the whole endpoint.

The application MCP surface stays behind the same SQL-first, governed-recipe invariants (extension
principles 1–4); deeper Studio-copilot ambitions remain gated on the MCP loop proving its worth
(decision point 4).

**Milestone M7** — schema to verified CRUD in under ten minutes by hand, or hands-off via an
MCP-connected agent.

## Horizon 3 — enterprise sign-in and integration (0.4.x)

### Phase 25 — authentication completion

OIDC relying party (authorization code + PKCE; tested against Entra ID, Okta, Keycloak),
RS256/JWKS bearer validation, API keys for service callers, optional mTLS — all behind the
existing authentication step and principal model, with IAM admin wizards like SAML's.

**RS256/JWKS and API keys** (delivered, see [docs/authentication.md](authentication.md)): bearer
JWTs verify with `SHA256withRSA` against a static `publicKey` or a `jwksUri` (JDK-only, no JOSE
dependency); the JWKS key set caches and rotates by `kid` with an unknown-`kid` refetch floor so it
cannot be flooded, and the expected algorithm is bound from config so `alg: none` and RS256/HS256
confusion are rejected. Service callers authenticate with `auth: apiKey` against config-declared
clients holding only a key hash, mapped to an explicit principal so existing policies apply. Lint
(`TQL-SEC-4040..4046`) and an `api-key` coverage kind keep both machine-checkable.

**OIDC relying party** (delivered, see [docs/authentication.md](authentication.md)): the new opt-in
`tesseraql-oidc` leaf module self-installs via the `RuntimeExtension` SPI and serves the
authorization-code + PKCE flow at `/_tesseraql/oidc/{login,callback,logout}`. Provider endpoints are
discovered lazily; a single-use `state`/`nonce`/PKCE verifier (in `tql_oidc_state`) defeats CSRF,
nonce replay, and code injection; the ID token is validated by reusing the RS256/JWKS verifier plus
OIDC `aud`/`nonce` checks; and the principal is linked/provisioned through the identity contracts
before the standard browser session is issued. JDK-only, with an `oidc` coverage kind, config lint
(`TQL-SEC-4050..4053`), and a SAML-style Studio IAM admin wizard.

**Mutual TLS** (delivered, see [docs/authentication.md](authentication.md)): a route declares
`auth: mtls` to authenticate a service caller by the X.509 client certificate a TLS-terminating edge
(reverse proxy, ingress, or mesh sidecar) forwards in a configured header (URL-encoded PEM). The
runtime parses it JDK-only, checks validity against an optional clock skew, optionally
PKIX-validates it against a `trustBundle` as defense-in-depth, and matches its subject DN (order- and
case-insensitive RDNs), a SAN value, or its DER SHA-256 fingerprint against declared clients
deny-by-default — resolving to an explicit principal so existing policies apply. An `mtls` coverage
kind and config lint (`TQL-SEC-4060..4065`) keep it machine-checkable. **Phase 25 is complete.**

### Phase 26 — managed connectors (files and HTTP)

- Polling triggers (SFTP/FTPS/local directory) feeding `file-import` pipelines.
- An `http-call` step for outbound REST in pipelines and jobs: secret-managed credentials,
  timeouts, circuit breaking, recorded in traces.
- An inbound webhook recipe: signature verification and replay protection in front of a SQL
  pipeline.
- All of it surfaces as recipes under the existing governance (route modes, allowlists,
  risk scoring) — Camel's component catalog stays an implementation detail, not user API.

**Outbound `http-call`** (delivered, see [docs/connectors.md](connectors.md)): a batch-pipeline
`http-call` step issues one synchronous outbound REST request and publishes the response
(`step.<id>.status`/`.body`/`.headers`) to later SQL steps. It is a job step, not a transactional
`command-json` step — a synchronous call cannot be rolled back, so a command's outbound
integration keeps riding the Phase 20 outbox webhook. All outbound HTTP is governed by
`tesseraql.http.outbound`: deny-by-default egress allow-list (exact or `*.wildcard` hosts),
SecretResolver-backed credentials resolved at call time, config timeouts with per-step overrides,
and a per-host circuit breaker that fails fast on systemic failures. Each call is a
`tesseraql.http.call` trace span. Lint (`TQL-SEC-4070..4072`) checks egress statically and an
`http-call` coverage kind tracks the steps suites plan. Polling triggers and the inbound webhook
recipe remain for later slices.

**Polling file triggers** (delivered, see [docs/connectors.md](connectors.md)): a `file-import`
job declares a `poll:` trigger (a local directory or a remote SFTP/FTPS server) instead of an HTTP
upload; the runtime watches the source and feeds every file through the job's `import:` pipeline
via the existing asynchronous, off-heap, operations-tracked transfer path, moving each file to a
done/failed sub-directory. Reaching a remote host is deny-by-default
(`tesseraql.connectors.poll.allowedHosts`), with SecretResolver-backed credentials; the Camel
`file`/`sftp`/`ftps` consumer stays an implementation detail. Lint (`TQL-SEC-4080..4081`,
`TQL-YAML-1005..1006`) and a `file-poll` coverage kind keep it machine-checkable.

**Inbound webhook recipe** (delivered, see [docs/connectors.md](connectors.md)): a `webhook` route
is an HMAC-verified, replay-protected POST endpoint in front of a SQL pipeline. It authenticates
the signed delivery (HMAC over `<timestamp>.<body>`, symmetric with the Phase 20 outbound webhook),
rejects a stale timestamp outside the tolerance, and rejects a replay via a shared JDBC store
(`tql_webhook_seen`, the SAML-replay basis) — all before a row is written. The verifier is
configured centrally (`tesseraql.connectors.webhooks`), so the route carries no secret, and an
unknown provider fails the build (never served unverified). A bad signature maps to 401, a replay
to 409. Lint (`TQL-SEC-4082..4083`, `TQL-YAML-1008`) and a `webhook` coverage kind keep it
machine-checkable. **Phase 26 (managed connectors) is complete.**

### Phase 27 — messaging and events

Outbox relay to a broker (Kafka/JMS) and a `queue-consume` recipe (broker → SQL pipeline
with idempotency keys); at-least-once semantics documented end to end.

**Postgres-native event channel** (delivered, see [docs/messaging.md](messaging.md)): the built-in
broker-free `pg-notify` transport answers "can we do messaging without Kafka or JMS?". A command's
`publish:` block emits a domain event on the transactional outbox; a relay moves it onto a durable
`tql_event` log and issues a PostgreSQL `NOTIFY`; a `queue-consume` route under `consume/` claims it
with `FOR UPDATE SKIP LOCKED` (woken by the notification, swept by a polling backstop) and runs its
SQL pipeline, deduplicated by an idempotency key so at-least-once becomes effectively exactly-once
per business key. `NOTIFY` is only the low-latency signal — the durable table is what guarantees
delivery — so the transport is PostgreSQL-only (the portable `SKIP LOCKED` table queue is the seam a
broker transport reuses). The `OutboxEventSink` relay and the `publish:`/`consume:` YAML are the
seam Kafka/JMS plug into without changing the DSL. Lint (`TQL-SEC-4090..4091`,
`TQL-YAML-1009..1010`, `TQL-YAML-1106`) and a `queue-consume` coverage kind keep it
machine-checkable. The Kafka and JMS broker transports (opt-in leaf modules) remain for later slices.

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
