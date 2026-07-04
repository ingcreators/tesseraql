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
Horizon 8 (Phases 39–47) was added 2026-07-03 from a low-code gap review at 0.4.1.

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
delivery — so `pg-notify` is PostgreSQL-only. A second built-in transport, `db-poll`, makes the same
durable queue **portable across every dialect** (MySQL, SQL Server, Oracle, and PostgreSQL behind a
transaction-pooling proxy): it claims with each dialect's `SKIP LOCKED` equivalent and polls on the
backstop interval instead of waiting on `NOTIFY` — identical at-least-once delivery, only higher
latency, and switching `transport:` is the whole change. The `OutboxEventSink` relay and the
`publish:`/`consume:` YAML are the seam Kafka/JMS plug into without changing the DSL. Lint
(`TQL-SEC-4090..4091`, `TQL-YAML-1009..1010`, `TQL-YAML-1106`) and a `queue-consume` coverage kind
keep it machine-checkable. The Kafka and JMS broker transports (opt-in leaf modules) remain for later
slices.

**Milestone M8** — corporate SSO login, a nightly SFTP exchange with a legacy system, and
commands emitting events consumed by another system — all declarative, all observable in
the operations console.

## Horizon 4 — LOB building blocks (0.5.x–0.6.x)

### Phase 28 — approval workflow

A SQL-contract state machine, consistent with IAM's managed/SQL realm duality: managed
`tql_workflow_*` tables or app-owned contracts. YAML declares states, transitions, guards
(expression language), and assignee resolution (SQL contracts); htmx task-inbox components;
delegation, escalation, and deadlines via the scheduler; a full audit trail; a `workflow`
coverage kind. The full design — resolving decision point 2 in favour of the native engine — is in
[docs/approval-workflow.md](approval-workflow.md).

Assignee resolution, the task inbox, and scoped transitions all build on the org-unit
foundation delivered in Phase 29 (the two are duals over one org graph — see Phase 29 and
[docs/data-scoping.md](data-scoping.md)); that foundation lands in Phase 29 and is consumed
here unchanged, rather than introducing a second org model.

Three slices, all delivered: (1) **workflow core** — the `kind: workflow` document, the transition
engine (state check → guard → advance → command → history in one transaction, reusing the Phase 18
processor), the `WorkflowStore` managed/app duality, the `TQL-WORKFLOW-31xx` lint, and the `workflow`
coverage kind; (2) **assignee resolution + task inbox** — the `assign` contract opens deadline-bearing
tasks in the managed `tql_workflow_task` inbox, with framework-enforced authority (a document with
open tasks may only be transitioned by a holder, else `403`); (3) **deadlines, escalation, delegation**
— a cluster-safe sweeper applies each breach's `onBreach` exactly once (`reassign` to a fallback
resolver, or `escalate` to auto-fire the named transition as the system), a built-in `delegate`
endpoint reassigns a task to a chosen delegate, and a workflow `notify:` block enqueues
assignment/escalation reminders on the Phase 20 channels. **Phase 28 is complete.**

### Phase 29 — organizational data scoping

Row-level/org-unit predicates derived from principal attributes (roles, groups, claims) as
named, reusable **declared SQL fragments**, the row-level complement to multi-tenancy. The full
design is in [docs/data-scoping.md](data-scoping.md); in summary:

- A `kind: scope` document under `scope/` declares an ordered list of **match arms** — each a
  `Policy`-style role/permission/claim condition paired with an effect (`all`, `none`, or a 2-way
  SQL predicate fragment). Multiple matching arms compose **additively (OR)**; no match is
  deny-by-default (`1=0`).
- The predicate is injected at an author-chosen `/*%scope name on alias */ (1=1)` site (a new 2-way
  SQL directive, sibling to `/*%if … */`), parameterized, never by rewriting `WHERE`/`FROM`. A
  fragment is alias-parameterized (`$` sentinel + `on <alias>`); a scope that needs a join is a
  correlated `EXISTS`, never a top-level join. Writes are scoped through the `UPDATE`/`DELETE`
  `WHERE` (a later slice).
- A scope lint family (`TQL-SCOPE-30xx`, like the tenant-predicate `TQL-TENANT-3001`) and a
  `data-scope` coverage kind (one item per scope) keep it machine-checkable.
- Masking integration: column-level role masking already works via `FieldPolicy.policy`; this adds
  row-level masking keyed off a scope flag column (`unmaskWhen`).

Three slices, all delivered: (1) **scope core** (attribute-based, no hierarchy); (2) the **shared
org-unit foundation** (`managed` `tql_org_unit`/`tql_org_closure` maintained by an `OrgUnitStore`,
or `app`-owned tables, the IAM managed/SQL realm duality) that Phase 28 also consumes; (3) **masking
integration** (the `/*%scope … as boolean */` flag directive plus `FieldPolicy.unmaskWhen`, masking a
field in rows outside the caller's scope). **Phase 29 is complete.**

Acceptance (slice 1, met): the same query, run by principals with different roles/claims, returns
each caller's rows only (a bypass role sees all, an unscoped caller sees none, roles compose
additively); the scope is testable via the `data-scope` coverage kind; and the lint flags a
directive naming an undeclared scope.

### Phase 30 — attachments and object storage

Business records carry files (an invoice PDF, a scanned form, a product image) stored as durable
objects outside the database and addressed from SQL. The full design is in
[docs/attachments.md](attachments.md); in summary:

- A durable **`BlobStore`** SPI (`tesseraql-core`, `io.tesseraql.core.blob`), a sibling of the
  ephemeral `TempStore` rather than a replacement for it — attachments are durable and
  retention-governed where spool is create-read-delete, so conflating the two would push spool
  traffic into object storage. The streaming primitives (off-heap write, checksum-as-you-stream) are
  reused; the store is not. `FileBlobStore` is the dependency-free local default.
- A **record-attachment recipe** — a `kind: attachment` document under `attachments/` synthesizes an
  off-heap upload `POST` and a download `GET`, with metadata in the managed/app duality
  (`AttachmentStore` SPI, managed `tql_attachment` or app-owned tables). The SQL-first payoff:
  download authorization is the metadata `SELECT` under the route's `policy:` and the Phase 29
  `/*%scope ... */` directive — no second access-control path. The non-transactional blob write is
  reconciled by orphan GC, the same "commit the record, reconcile the side effect" discipline as the
  Phase 26 `http-call` and Phase 27 outbox.
- An opt-in **`tesseraql-s3` leaf module** — `S3BlobStore` on AWS SDK for Java v2 (Apache-2.0,
  confined to the module like `tesseraql-pdf`'s engine), self-installing via `RuntimeExtension` when
  `provider: s3`. One module covers AWS and every S3-compatible store (R2, Ceph, B2) via
  `endpoint`/`region`/`pathStyle`/`checksumMode`; switching `provider` is the whole change. Egress is
  deny-by-default (`tesseraql.object-storage.allowedBuckets`), credentials ride the SecretResolver.
- A **scan-hook SPI** (`AttachmentScanner`, ServiceLoader, no-op default) gating downloads on a clean
  scan, and **retention** wired to the ch. 44 `RetentionSweeper` (orphan GC plus an optional age
  policy) — driven by the sweep rather than provider lifecycle rules, which vary across compatible
  stores.

Three slices, all delivered: (1) **attachment core** — the `BlobStore`/`FileBlobStore` SPI, the
`kind: attachment` recipe and metadata duality, the `TQL-YAML-12xx` lint, and the `attachment`
coverage kind, on local storage alone; (2) **`tesseraql-s3`** — the S3/S3-compatible implementation
with deny-by-default egress and Adobe S3Mock compatibility ITs; (3) **scanning and retention**,
completing the phase. Machine-checkable throughout: lint (`TQL-YAML-12xx`, `TQL-SEC-411x`, next free
in each family) and an `attachment` coverage kind. **Phase 30 is complete.**

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

## Horizon 7 — ecosystem and a trusted app marketplace (post-1.0)

### Phase 37 — pluggable apps and a curated marketplace

A forward-looking direction (recorded 2026-06-19, not scheduled). Make TesseraQL apps distributable
as plugins and, on top, a marketplace that shares only **trusted** apps. Three increments, each
building on existing seams:

1. **Pluggable generators** — a `Scaffolder` SPI: the CRUD generator becomes the default provider and
   third-party JARs contribute alternative scaffold styles, mirroring the existing
   `FileCodec`/`PdfEngine`/`BlobStore` SPIs (extension principle 5).
2. **Plugin apps + an SDK** — whole apps already mount as plugins via `AppSourceProvider` +
   `ServiceLoader` (design ch. 32, 47 — the system apps *are* this), and `AppSource` already
   anticipates **`.tqlapp` packages** (a materializable file tree, not a bytecode JAR). The gap is
   ergonomics: a `tesseraql new --plugin` / Studio flow that scaffolds the module skeleton
   (`pom`, `resources/tesseraql/apps/<name>/`, the `AppSourceProvider` registration) — turning "ship
   an app as a plugin" from tribal knowledge into one command.
3. **A trusted-only marketplace** — share only curated/verified apps. The de-risking decision is to
   constrain marketplace apps to **declarative `.tqlapp` only** (no custom Java / `RuntimeExtension`
   SPI): the capability surface is then the framework's interpreted recipes — bounded, lintable,
   analyzable — so *the framework is the sandbox* and arbitrary bytecode never has to be isolated. The
   trust pipeline composes primitives that already exist:
   - **Admission gate** — the `AppLinter` (+ `GovernanceGate`) run as a "marketplace profile" the app
     must pass before publish: policies defined (deny-by-default), egress allowlisted, embedded
     variables enum-gated (`TQL-SQL-2109`), CSP intact, declarative-only.
   - **Provenance** — sign the package; attach an SBOM (`SbomGenerator`) and release evidence
     (`ReleaseEvidence`); pin the content hash (`ScaffoldChecksum`); the runtime verifies signature +
     hash before mounting.
   - **Capability permits** — the app declares the capabilities it needs (datasources, egress hosts,
     policies, MCP tools); the installer reviews and grants, extending `tesseraql.modules` opt-in and
     per-app `enabled`.
   - **Curation** — a marketplace operator approves publishers/apps (human trust atop the automated gate).

   Open questions (security-design-first — a design doc precedes code): the capability-permit manifest
   schema + install-time review; signature/key management + a publisher registry; the distribution
   registry + Studio browse/install UI; datasource-access scoping (which host data a marketplace app may
   reach, atop tenancy + scopes); resource/query budgets (a declarative app can still write expensive
   SQL); and multi-app Studio scope. Keeps extension principles 2 (declarative, governed), 3
   (deny-by-default), 4 (machine-checkable), and 5 (module boundaries / SPI).

## Horizon 8 — the low-code completion (0.5.x–0.7.x, interleaving Horizons 5–6)

Added 2026-07-03 from a low-code gap review at 0.4.1 (the DSL surface, the runtime and
operations story, and the end-to-end authoring experience were each inventoried against
what a low-code platform owes its authors). Phases 18–30 closed the *capability* gaps;
what remains is structural:

1. **The screen is the only layer that is not declarative.** Routes, validation,
   workflows, scopes, menus, and MCP surfaces are YAML; pages are hand-written
   Thymeleaf + htmx (~50 lines per form), and `input:` constraints are duplicated into
   HTML attributes by hand.
2. **"Save and it is live" breaks exactly at creation.** The hot reloader swaps existing
   route ids only; a scaffolded or newly created route needs a restart.
3. **The declarative floor sits below everyday needs** — no declarative pagination, no
   regex/length/format validators, an expression language without arithmetic, untyped
   path parameters.
4. **A semi-technical author still drops into raw YAML and ops territory** for
   connectors, SSO, secret references, and everything production.

Phases 39–44 close those gaps in DSL-then-Studio order; Phases 45–47 harden the
production and adoption story they feed into. By value this horizon interleaves with
Horizons 5–6: Phase 45 should land *before* Phase 33 (Kubernetes probes presuppose a
truthful health surface), and Phase 47 rides Phase 35's release train.

**Corrections shipped ahead of any phase** (ordinary fixes, no phase ceremony): the
`min`/`max` bound check truncates decimals (`InputBinder` compares via
`number.longValue()`, so `max: 5` admits 5.9 and `min: 0` admits -0.9 — compare as
decimals); and a `head.yml`/`options.yml` route file is accepted by the manifest loader
but fails deep in the route compiler (`Unsupported HTTP method`) — reject it at lint
time with a clear code.

### Phase 39 — declarative views

The UI layer joins the declarative surface: a `kind: view` document declares a list,
form, or detail page over a table/route, and the compiler renders it through the
existing template pipeline into Hypermedia Components markup (the same emitted-markup
contract the route compiler already owns, mandatory rule 11). Fields derive from schema
introspection plus the route's `input:` constraints — one source of truth, ending the
`required`/`maxLength` duplication into HTML — with explicit per-field overrides
(label, widget, order, visibility). The full design — resolving decision point 7 in
favour of interpretation — is in [docs/declarative-views.md](declarative-views.md).

Declarative does not mean locked-in HTML (recorded 2026-07-03): the
freedom-vs-declarativeness trade-off is resolved as a **customization ladder**, every
rung an app-author surface:

- **L0 — view options**: label, widget, order, visibility, formatting — plain keys in
  the view document. No HTML.
- **L1 — slots**: a view declares named insertion points (`header`, `actions`,
  `after-field`, …) the app fills with its own fragments — the
  `tql/shell :: shell(...)` parameterized-fragment pattern applied to views.
- **L2 — pattern overrides**: views render through framework-shipped pattern fragments
  (`tql/view/list`, `tql/view/form`, per-widget `tql/view/field-date`, …) resolved
  app-home-first with classpath fallback — one app-override resolver ahead of the
  shared `tql/*` resolver in `Templates`. Dropping `templates/tql/view/form.html` into
  the app restyles every form; a per-widget file retargets one widget everywhere; a
  `template:` key points a single view at a custom fragment. The
  Django-widget-template / Rails-form-builder model: fix a pattern once and every view
  follows, where ejected copies drift.
- **L3 — eject**: generate the full template into the app tree and own it (the
  scaffolder's checksum/edit-detection contract already models "generated until you
  touch it"), the view document remaining for data binding or dropped.

The pattern-fragment signatures (`th:fragment="form(view, model)"`) and the view-model
shape thereby become public API: versioned with the YAML schema, covered by the
Phase 34 stability annotations, and linted — `TQL-VIEW-*` flags an unknown slot name or
a signature-mismatched override. Framework-shipped patterns stay hc-conformant
(rule 11); an override is app-owned markup with the same status as a hand-written
template today.

- Slice 1: `list` and `form` views (the CRUD 80%), rendered through the overridable
  `tql/view/*` pattern fragments (the L2 resolver ships from day one) onto the hc
  datagrid and mutating-form recipes; the `TQL-VIEW-*` lint family (unknown
  column/field, widget/type mismatch, unknown slot, signature-mismatched override) and
  a `view` coverage kind.
- Slice 2: `detail` views and relations (parent + child-list composition), plus named
  slots (L1).
- Slice 3: `scaffold crud` emits view documents instead of raw templates; the example
  gallery regenerates on views (dogfooded in CI).
- Slice 4: dashboards — `view: dashboard` panels (`stat`/`sparkline`/`chart`/`table`) on
  the kit's `hc-grid`; charts are deterministic server-rendered SVG wearing the kit's
  CSS-only `hc-chart` skin (no upstream brief was needed — the component already ships;
  the earlier "only `hc-sparkline`" note repeated the Track-I mistake of grepping only
  the behaviors bundle).

Acceptance: adding a column to a table means a migration plus a view-YAML edit — zero
HTML — and the scaffolded CRUD slice ships as views. This is also the substrate the
form-driven editors (Phase 43) and the copilot (Phase 44) operate on: structured view
documents are safely machine-editable where free-form Thymeleaf is not. All four slices
are delivered (design + slices in [docs/declarative-views.md](declarative-views.md)).
**Phase 39 is complete.**

### Phase 40 — input, validation, and expression depth

The declared-input vocabulary grows to what LOB forms actually need, so rules stop
leaking into hand-written SQL:

- `pattern` (anchored regex), `minLength`, and `format: email|uuid|url` validators;
  decimal-typed `min`/`max`; conditional requiredness (`requiredWhen`, the same
  expression language as `validate:` rules).
- Typed path parameters: `path.*` values bind through `input:` (coercion + constraints)
  instead of arriving as raw strings.
- The core expression language gains arithmetic, string functions, and a whitelisted
  function set (still no method calls, no reflection), so `qty * price <= budget` is a
  declarable rule rather than a SQL contract.
- Every addition extends the existing machinery: `TQL-FIELD-*` codes, localized
  messages, the `validation` coverage kind, and OpenAPI schema emission.

Delivered in two slices (the input vocabulary + typed path params + the pre-phase
corrections; then the expression depth) — see
[docs/declarative-validation.md](declarative-validation.md). **Phase 40 is complete.**

### Phase 41 — declarative pagination and response shaping

- A `page:` block on `query-json`/`query-html`: offset or keyset strategy, declared
  page-size bounds, optional total count, and the response metadata/headers emitted
  automatically (htmx-aware for table fragments). The 2-way SQL stays
  plain-tool-runnable — the block appends the dialect's pagination clause the way
  scaffolded SQL hand-writes it today.
- Response shaping: computed/formatted fields over the row source, nested composition
  (a parent row with named child queries keyed into one document), and declarative
  mapping of business conditions to HTTP statuses (generalizing `expect.onMismatch`).
- Lint and coverage kinds per surface; the OpenAPI generator learns both.

Delivered in two slices (pagination — [docs/pagination.md](pagination.md); then response
shaping — [docs/response-shaping.md](response-shaping.md)). **Phase 41 is complete.**

### Phase 42 — the instant loop (hot-reload completeness)

"Save and it is serving" becomes true for creation, not only edits:

- Dynamic route addition and removal: a newly applied route (scaffold, new-route,
  Studio apply) compiles and mounts without a restart; removal un-mounts. The startup
  route-conflict guard runs incrementally.
- Per-route failure isolation on reload: one broken definition takes only itself out
  (a clear 500 carrying the compile error) instead of poisoning whole-manifest
  recompiles — which also lets the menu editor and config edits stop avoiding route
  reloads.
- Migrations: a migration created in Studio can be applied to the dev datasource from
  Studio, so schema → scaffold → serve needs no process bounce.

Acceptance: Studio's scaffold-apply serves the new CRUD immediately; a deliberately
broken route 500s alone while its neighbors keep serving; the M7 "ten minutes" flow
never touches a terminal.

Delivered in two slices (dynamic route mounting with per-route failure isolation and
tolerant manifest loads; then Studio's **Migrate now** closing the
migration &rarr; scaffold &rarr; serve loop, proven end-to-end by an integration test
that never restarts the process). **Phase 42 is complete.**

### Phase 43 — Studio authoring completion

Studio closes the remaining "drops into raw YAML" edges (slices tracked as Track J in
[docs/studio-backlog.md](studio-backlog.md)):

- A form-driven route editor: recipe/auth/policy/inputs as structured fields (the
  menu-editor pattern applied to route YAML itself); the text editor stays the escape
  hatch.
- Connector and SSO authoring: `http.outbound`, `connectors.poll`,
  `connectors.webhooks`, OIDC, and SAML through the same gated overlay-write path as
  policies — editing secret *references* (`${secret.env.*}`), never values, with
  egress allow-list changes behind the confirm gate. The IAM wizards become
  write-through instead of snippet downloads.
- A test recorder: an API-console invocation saves as a declarative test case, so a
  citizen developer's manual check becomes a regression test.
- Data-browser row editing: PK-scoped single-row edit via a generated command, under
  audit + `editRoles` + confirm — the master-data maintenance screen nobody has to
  build.
- Authoring feedback outside Studio: deepen the shipped JSON Schema and wire it into
  scaffolded repos (`.vscode` association); lint findings gain line/column.

Delivered in five slices (J1 route form, J2 connector/SSO write-through, J3 test recorder,
J4 data-browser row edit, J5 authoring feedback — see
[docs/studio-backlog.md](studio-backlog.md) Track J for the per-slice detail).
**Phase 43 is complete.**

### Phase 44 — Studio copilot chat

The remaining half of decision point 4, now that the MCP loop has proven out (the
`studio_copilot` prompt, the app-declared MCP surface): an in-Studio chat panel that
drives the *existing* gated tools (draft → preview → lint/test → apply) as an MCP
client. TesseraQL still ships no model and stores no key in app source — the operator
configures a model endpoint, or the panel rides a connected agent (decision point 8) —
and every mutation stays a separately gated, audited tool call with the human approving
applies in the same diff-confirm UI. Describe → verified route, without leaving Studio.

Delivered as the in-Studio panel over an operator-configured OpenAI-compatible endpoint
(decision point 8 resolved; see [docs/copilot.md](copilot.md)): reads free, writes only as
audited drafts, apply human-only. **Phase 44 is complete — Horizon 8 is now complete in
full.**

### Phase 45 — production observability and safety

What a team hits in the first production week; sequenced ahead of Phase 33, whose
probes and dashboards presuppose these signals:

- Metrics a pull-based stack can consume: per-route latency/error histograms (today the
  `Meter` is counter-only and OTLP-push-only). Transport is decision point 9; ship a
  Grafana dashboard definition alongside.
- Truthful health: a liveness/readiness split, a real datasource probe, and a `DOWN`
  state (today the roll-up never degrades past `WARN`).
- Structured JSON logging with trace-id correlation, and an opt-in HTTP access log.
- Safety valves: a default per-route SQL statement timeout (today a runaway query is
  unbounded), surfaced connection-pool tuning knobs, and documented — or shared-state —
  semantics for the per-node rate/concurrency limiters on multi-node deployments.
- An opt-in business-route audit log (who called what, with declared decision-relevant
  params) riding the existing per-app ops scoping; per-app custom error pages.

Delivered in five slices (truthful health; Prometheus histograms + Grafana dashboard,
resolving decision point 9; structured logging with trace-id correlation and the access
log; the safety valves — default SQL timeout, pool knobs, per-node limiter semantics; the
business-route audit log and custom error pages). **Phase 45 is complete.**

### Phase 46 — environments and promotion

The dev → staging → prod story becomes first-class rather than implied git practice:

- Config profiles: a per-environment overlay layer resolved by one switch, replacing
  ad-hoc `${...}` indirection for the common cases; Studio's overlay writes compose
  with it.
- A release diff — "what does this deploy change": routes added/removed/changed, the
  API diff (`OpenApiDiff`), the schema diff (`SchemaDiff`), the migration list, and
  policy changes, as a CLI/Maven report and a docs-portal page, generated from two app
  trees or a captured baseline.
- A documented promotion recipe (git-native: Studio edits in dev → PR → CI governance
  gate → tagged `.tqlapp`/image), aligning the read-only-prod-Studio posture with an
  explicit path for how an edit gets there.

Delivered as environment profiles (`config/env/<profile>.yml`, one `--env` switch), the
release diff (`ReleaseDiff` engine; CLI `release-diff`, Maven `tesseraql:release-diff`, and
the docs-portal page), and the promotion recipe in
[docs/promotion.md](promotion.md). **Phase 46 is complete.**

### Phase 47 — adoption surface

Discovery has fallen behind capability. Complements Phase 35 (docs site, Maven Central)
and the deferred Phase 38 tiers, on Phase 35's release train:

- A template gallery: complete starter applications (approval workflow, inventory,
  helpdesk) as declarative-only `.tqlapp` packages — each one also dogfoods the
  Phase 37 marketplace admission profile.
- The five-minute demo: one command (`serve --embedded-db` plus seeded data and a
  Studio tour) and one container image that boots a browsable example with Studio open.

**Milestone M12** — the low-code loop, closed: a semi-technical author adds a column
and its screen behavior entirely in Studio — migration, view, recorded test — writing
no HTML and never restarting; the change promotes through a release diff; and the
route's latency shows up on a scraped dashboard.

Delivered as the marketplace admission profile (`tesseraql admission`, realizing the
Phase 37 gate), the three-app template gallery (purchase-request / inventory / helpdesk,
each admission-held in CI), and the five-minute demo
([docs/five-minute-demo.md](five-minute-demo.md), `deploy/Dockerfile.demo`).
**Phase 47 is complete**, and with it **milestone M12 is met** — every leg of the loop is
held green by an integration test (the zero-restart M7 loop, the recorded test, the release
diff, the Prometheus exposition). Phase 44 followed, completing **Horizon 8 in full**.

## Horizon 9 — the business-application platform (post-M12)

Added 2026-07-04. With the low-code loop closed, the next gap is what every business
application re-implements *around* its pages: the cross-cutting surfaces end users
expect from an enterprise platform — the shell's user menu, a settings screen for
language / theme / notifications, session self-service. The direction (set by the
maintainer) is that the platform should own these common foundations the way it owns
routes and views: one framework answer, extended declaratively per app, honest about
what each deployment supports.

The horizon opens with one phase; further phases join as the direction develops.

### Phase 48 — the account surface

A framework-owned user menu and self-service settings surface. The full design is in
[docs/account.md](account.md); the shape:

- A `PreferenceStore` SPI over a managed `tql_user_preference` table (the
  `EventChannelStore` pattern), keys namespaced `ui.*` / `notify.*` / `app.*`, the
  subject always the session principal's by construction.
- A reserved `_account` shell variable (beside `_csrf` / `_menu`) rendering an
  avatar + popover menu in the shared shell — Studio, the docs portal, and the ops
  console inherit it for free.
- A bundled `/_tesseraql/account` system app (the `auth-ui` precedent): profile,
  language (a new `preference.<key>` source in the Phase 22 locale chain), theme
  (replacing the shell's hardcoded `data-theme`), notification opt-out (an optional
  `recipient:` on `notify:` plus operator-marked user-facing channels), sessions
  ("sign out other sessions"; `tql_session` gains an indexed subject), and local-realm
  password change — with honest disabled states wherever a deployment delegates to an
  IdP.
- App extension via `config/preferences.yml`: declared preference groups render as
  settings sections and read back through a `preference.<key>` namespace in routes,
  templates, and 2-way SQL.

Machine-checkable throughout: a `TQL-ACCOUNT` error domain (48xx), `preferences.yml`
lint, and a `preference` NOTE coverage kind. Five slices (store + chrome; language +
theme; notifications; sessions + password; preference groups).

**Milestone M13** — an end user of a gallery app signs in, switches language and theme
(persisted server-side), opts out of a notification channel, signs out their other
sessions, and changes their local password — the app contributing nothing but
`preferences.yml`; the same app under SSO shows provider-managed states instead.

Delivered 2026-07-04 across five slices: the preference store + shell user menu +
bundled account app; language + theme through the locale chain and the model-driven
shell theme; recipient-aware notification opt-out decided at enqueue; session
self-service (`tql_session` subject, sign-out-others) and the local-realm password
change over the login path's own contracts; and app-declared preference groups
(`config/preferences.yml`, lint `TQL-YAML-1030..1033`, the `preference` NOTE coverage
kind, the `preference.<key>` request namespace, dogfooded by the inventory gallery
app). Every leg is held green by `AccountSurfaceIntegrationTest` (17 scenarios on real
PostgreSQL, including the honest disabled/SSO states). **Phase 48 is complete and
milestone M13 is met.**

### Phase 49 — the in-app notification center

The shell's bell: business events land in a per-user inbox instead of (or beside)
mail. A third channel type, `inbox`, delivers a `notify:` event into a managed
`tql_user_notification` table — addressed by the Phase 48 `recipient:` expression, so
the outbox's at-least-once retries, the per-user opt-out, and the coverage/lint
surfaces all apply unchanged. The shell grows an unread-badge bell (a reserved
`_inbox` variable beside `_account`/`_theme`); `/_tesseraql/inbox` lists, marks read,
and marks all read. Deliveries dedupe on the outbox event id (a retry after a crash
never doubles a message); retention prunes read messages by age. An inbox-channel
notification without `recipient:` is a lint error — an inbox message must be
addressed. Design: [docs/inbox.md](inbox.md). Two slices: delivery (channel type,
store, dedupe, lint), then the surface (bell, page, docs).

**Milestone M14** — a gallery app declares one inbox channel and one
`recipient:`-addressed `notify:`; the event shows up as an unread badge in the shell
and a message in the inbox, reading clears it, opting out silences it — zero app code
beyond those declarations.

### Phases 50–52 — recorded candidates (maintainer names which proceeds)

Direction candidates for this horizon, recorded 2026-07-04; each gets its own design
doc when named:

- **Phase 50 — credential lifecycle completion.** What slice 4 of Phase 48 opened,
  finished: password **reset** via a mailed one-time token (identity pack + notify
  channels + an auth-ui page), an **invitation** flow (admin invites, the user sets
  the first password — the entry point user-admin's CRUD lacks), and optional
  **TOTP** second factor for the local realm (JDK-only HMAC, no new dependency).
- **Phase 51 — personal productivity surfaces.** Saved filters for the data browser
  and list views (the Phase 39/41 filter state, persisted per user through the
  preference store), favorite pages pinned into the shell menu, and recently viewed
  records.
- **Phase 52 — workflow delegation and absence.** Approval delegation for the
  Phase 28 workflow engine over the Phase 29 org-unit foundation: absence windows,
  delegate resolution at assignment time, and a full audit trail of who acted for
  whom. The heaviest candidate — a security-design-first phase like Phase 37.

## CLI distribution and upgrade delivery (cross-cutting)

### Phase 38 — CLI distribution and upgrade delivery

A cross-cutting distribution track (recorded 2026-06-20). Tier 1 ships immediately; Tiers 2–3 mature
alongside Phase 35. Motivated by the 0.3.0 → 0.3.1 hotfix: the 0.3.0 CLI distribution shipped without
the PostgreSQL JDBC driver (`serve --embedded-db` died with `NoClassDefFoundError`), and a user on the
broken build had **no in-tool signal** that 0.3.1 fixed it. How users *get* and *update* the CLI is
its own concern, distinct from Phase 35's artifact publishing (Maven Central / Gradle plugin, aimed at
app developers).

**Constraint.** The CLI keeps a *dynamic* classpath on purpose — ServiceLoader codecs/drivers, the
`--modules` child classloader, signed plugins (the `jpackage.yml` rationale) — so a closed-world
GraalVM single binary is out. The deliverable is therefore the existing **fat jar + JRE**
(`dist.zip`/`.tar.gz`, already a `bin/`+`lib/` layout) or the **jpackage app-image** (bundled JVM, no
JRE prerequisite, built per-OS on `v*` tags). This shapes every channel below.

- **Tier 1 — immediate (delivered now).**
  1. *In-CLI passive update notifier*: on run, an async, daily-cached, opt-out check of the GitHub
     Releases "latest" tag; if newer than `TesseraqlVersion.current()`, print one hint line
     (`TesseraQL <new> is available — <releases URL>`). Never blocks the command; fails silent when
     offline. Directly closes the 0.3.0 blind spot.
  2. *Attach the jpackage app-images to the GitHub Release* — they are built on tags but currently
     only uploaded as time-limited CI artifacts, so the JRE-free build has no stable download URL.
- **Tier 2 — primary channels (automate the version bump in the release workflow).**
  3. *SDKMAN!* vendor listing — the idiomatic JVM-CLI channel (mvn/gradle/quarkus/jbang);
     `sdk install/upgrade tesseraql`. The existing `dist.zip` already matches SDKMAN's `bin/`+`lib/`
     shape, making this the lowest-friction primary.
  4. *Homebrew tap (macOS/Linux) + Scoop/WinGet (Windows)* — payload is the bundled-JVM app-image (no
     JRE prerequisite); the package manager owns `upgrade`; the release workflow bumps the
     formula/manifest.
- **Tier 3 — complements / later.**
  5. *install.sh / install.ps1* (`curl … | sh`): detect OS/arch → fetch the latest app-image → unpack
     to `~/.tesseraql` + symlink; re-run to upgrade. Fallback where no package manager exists.
  6. *Container image on GHCR* (`docker run ghcr.io/ingcreators/tesseraql:<v>`) for CI/ephemeral use;
     "upgrade" = a new tag.
  7. *`tesseraql self-update` subcommand* — fetch the latest release asset and replace the install in
     place, with checksum/signature verification (mind the Windows exe-lock on self-replacement).
     Deferred until the notifier + package managers cover the common path.

Keeps extension principle 4 (the notifier is opt-out and deterministic) and 5 (no new
`tesseraql-core` dependency — the version check lives in the CLI module). Cross-references Phase 35
(distribution maturity) and Decision point 3 (pull distribution forward if adoption is near-term).

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
2. **Workflow engine** (Phase 28): native SQL-contract implementation vs embedding an external
   engine. Resolved 2026-06-15 in favour of the native state machine: it reuses the transactional
   write engine (Phase 18), row scoping and the org-unit foundation (Phase 29), the core expression
   language, and the cluster-safe scheduler, adding no runtime dependency — where an external engine
   would break SQL-first (principle 1) and the module boundaries (principle 5). The `WorkflowStore`
   SPI and the `kind: workflow` document keep the seam an external engine could later plug into. The
   full design is in [docs/approval-workflow.md](approval-workflow.md).
3. **Adoption timing**: Maven Central and the docs site sit in Horizon 6; pull them forward
   if external adoption becomes a near-term goal.
4. **AI ambition** (Phase 24): the dev-tool MCP server is the scoped bet, and it shipped with
   its protocol core factored out (`tesseraql-mcp`) so the runtime can later serve
   application-declared MCP endpoints from YAML (the Phase 24 "next step"). Deeper
   Studio-copilot features only if the MCP loop proves valuable.
5. **Spring 7 / Boot 4 timing**: proposed alongside Horizon 5, before the 1.0 freeze.
6. **Object-store client and test fixture** (Phase 30): the S3 client is AWS SDK for Java v2
   (Apache-2.0), confined to the opt-in `tesseraql-s3` leaf module — one module covers AWS and all
   S3-compatible stores; a JDK-only SigV4 alternative (in the spirit of the JDK-only OIDC/mTLS) stays
   available behind the `BlobStore` SPI. MinIO is **not** adopted, even as a test fixture: the MinIO
   server is AGPLv3 and its community edition has entered maintenance mode (admin UI removed in
   2025), so — though a separate-process test container would not propagate AGPL — the
   compatibility ITs use Adobe S3Mock (Apache-2.0), keeping the supply chain permissive. See
   [docs/attachments.md](attachments.md).
7. **View rendering strategy** (Phase 39): compile `kind: view` into the existing template
   pipeline at build time (a deterministic, diffable generated artifact) vs interpret the
   view model at render time (one live source, no regeneration step). The customization
   ladder weighs toward interpretation: a pattern override (L2) is then pure
   template-chain resolution at render time — the resolver order in `Templates` already
   models it — where the build-time variant needs a regeneration step after every
   override edit. A design document precedes code, like approval-workflow.md; either way
   the emitted hc markup stays the public contract (mandatory rule 11). Resolved
   2026-07-03 in favour of **interpretation** (see
   [docs/declarative-views.md](declarative-views.md)): pattern overrides stay pure
   resolver-chain lookups, derived list columns need the live row shape, and rendering
   is a pure function of (document, fragments, model) so reproducibility needs no
   generated artifact — while parsing, reference checks, and lint stay at build time.
8. **Copilot model access** (Phase 44): an operator-configured model endpoint (credentials
   via the SecretResolver SPI) vs riding a connected MCP client's model. Invariant either
   way: TesseraQL ships no model, stores no key in app source, and every write remains a
   separately gated, audited tool call. Resolved 2026-07-03 in favour of the
   **operator-configured endpoint** (OpenAI-compatible chat completions; key resolved
   lazily through the config placeholder chain) — riding a connected MCP client's model
   stays available through the Phase 24 `studio_copilot` prompt, so both halves of the
   decision are served.
9. **Metrics transport** (Phase 45): a JDK-only Prometheus text-format endpoint (no new
   dependency, in the spirit of the JDK-only OIDC/mTLS choices) vs adopting OTel/Micrometer
   histograms inside `tesseraql-observability`. Module boundaries (principle 5) decide
   where it lives; both keep the in-process ring tracer. Resolved 2026-07-03 in favour of
   **both, split by module boundary**: the `Meter` abstraction gained a histogram, a JDK-only
   `AggregatingMeter` + Prometheus text exposition live in `tesseraql-core`/the runtime (no
   new dependency on the scrape path), and `tesseraql-observability` maps the same histograms
   onto OTLP — the ring tracer stays. The scrape endpoint is opt-in and policy-gated.
10. **Account-surface delivery** (Phase 48): a bundled system app riding the shared shell
    vs scaffolded pages vs a mountable app. Resolved 2026-07-04 at design time in favour
    of the **bundled system app** (the `auth-ui` precedent): zero setup, updates flow
    with the framework, and one consistent chrome across the app, Studio, docs, and ops
    — while the customization ladder keeps app control real (shell/pattern overrides,
    `menu.yml`, `preferences.yml`, and a kill switch for apps that own the surface
    themselves). See [docs/account.md](account.md).
