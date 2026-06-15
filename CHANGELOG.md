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
- Durable object storage SPI (roadmap Phase 30 slice 1, see [docs/attachments.md](docs/attachments.md)):
  a `BlobStore` (`io.tesseraql.core.blob`) — a sibling of the ephemeral `TempStore` for retained,
  retention-governed objects — with a `FileBlobStore` default that streams to local disk and computes
  a SHA-256 checksum and byte count while writing (no second pass). The surface is the minimal
  portable intersection of S3-compatible stores (put/get/exists/delete plus an optional pre-signed
  GET), so an S3 implementation plugs in unchanged in slice 2. Attachment metadata has its own SPIs,
  `AttachmentStore` and `AttachmentService`, in the same SQL-first managed/app spirit as the workflow
  and org-unit stores.

### Runtime and recipes

- Attachments and object storage — S3 and S3-compatible storage (roadmap Phase 30 slice 2, see
  [docs/attachments.md](docs/attachments.md)): a new opt-in `tesseraql-s3` leaf module ships an
  `S3BlobStore` on AWS SDK for Java v2 (Apache-2.0, confined to the module), contributed by a
  `BlobStoreProvider` discovered via `ServiceLoader` (the PdfEngine idiom) and selected by
  `tesseraql.object-storage.provider: s3` — so an app moves blobs from local disk to S3 by config
  alone, no DSL change. One module covers AWS S3 and every compatible store (Cloudflare R2, Ceph,
  Backblaze B2) via `endpoint`/`region`/`pathStyle`/`checksumMode` (`when-required` restores
  compatibility with stores that reject the SDK's default request checksums). Egress is
  deny-by-default: a bucket outside `tesseraql.object-storage.allowedBuckets` is refused at runtime
  and flagged by lint `TQL-SEC-4110`; credentials resolve lazily through the SecretResolvers
  (`${secret.*}`), never logged. Uploads buffer off-heap to a temp file (SHA-256 computed while
  writing) then stream to S3 in one `putObject`; `presignGet` issues a short-lived URL. Verified
  against Adobe S3Mock over Testcontainers (MinIO is not used — its server is AGPLv3; roadmap
  decision point 6). The scan-hook and retention remain slice 3.
- Attachments and object storage — attachment core (roadmap Phase 30 slice 1, see
  [docs/attachments.md](docs/attachments.md)): a `kind: attachment` document under `attachments/`
  binds uploaded files to an owning business record and synthesizes three HTTP routes — an off-heap
  multipart upload `POST {basePath}`, a metadata list `GET {basePath}`, and a download
  `GET {basePath}/{attachmentId}`. The upload streams the body off-heap into the `BlobStore` (its
  size and SHA-256 computed while streaming), enforces the declared `limits` (size → `413`, content
  type → `415`), then records a row in the managed `tql_attachment` table; the download loads that row
  scoped to the owning record (an attachment owned by a different record reads as `404`, never leaked)
  and streams the blob with a sanitized `Content-Disposition`. The blob write is non-transactional —
  an upload that fails after the blob is written leaves an orphan the retention sweep reclaims
  (slice 3). Metadata uses the managed/app duality (`tesseraql.attachments.mode`, default `managed`);
  the managed store and the file blob store are provisioned only when the app declares attachments.
  Lint (`TQL-ATTACH-3401..3405`) checks the document's kind, base path, owning record, path-parameter
  binding, and size limit. S3-compatible storage, app-mode 2-way SQL metadata, the scan-hook, and
  retention are later slices.
- Approval workflow — `onBreach.escalate` auto-transition (roadmap Phase 28, see
  [docs/approval-workflow.md](docs/approval-workflow.md)): on a deadline breach the cluster-safe
  sweeper can now auto-fire a named transition **as the system** instead of reassigning — it advances
  the document from the deadline's state, runs the transition's command (with `/* key */` and
  `/* audit.* */` binds, so `audit.user` is `system`), completes the open tasks (so it cannot
  re-fire), and records a history row under the transition id. Works in both managed and app state
  modes; `escalate` takes precedence over `reassign` when both are declared; lint `TQL-WORKFLOW-3107`
  checks the named transition starts from the deadline's state. This completes the phase.
- Approval workflow — reminder notifications (roadmap Phase 28, Phase 20 channels, see
  [docs/approval-workflow.md](docs/approval-workflow.md)): a workflow declares a `notify:` block whose
  `assigned` reminder fires when a transition opens a task and whose `escalated` reminder fires when
  the sweeper reassigns an overdue one. Each is a `NotifySpec` (channel, optional `when` guard,
  `payload` with the resolved `assignee` in scope) enqueued as a `NOTIFICATION` outbox event in the
  same transaction as the task change — so a rolled-back transition never notifies and a committed one
  notifies at-least-once, with the same delivery, retries, and dead-lettering as a route's `notify:`.
- Approval workflow — deadlines, escalation, and delegation (roadmap Phase 28 slice 3, completing the
  phase, see [docs/approval-workflow.md](docs/approval-workflow.md)): a state's `deadlines` set the
  opened task's `due_at`; a cluster-safe sweeper (a timer claimed through `tql_job_claim` at
  `tesseraql.workflow.sweep.interval`, default 60s) reassigns each overdue task to the fallback
  resolver named by `onBreach.reassign` (a 2-way SQL `SELECT` returning the new assignee), clearing
  `due_at` so it escalates exactly once even across nodes and recording an `escalate` history row.
  Delegation is a built-in `POST {basePath}/{key}/delegate/{to}` that reassigns the document's open
  task to a chosen delegate (only a current holder may delegate, else `TQL-WORKFLOW-3203`/403). The
  `onBreach.escalate` auto-transition and Phase 20 reminder notifications remain a refinement.
- Approval workflow — assignee resolution and task inbox (roadmap Phase 28 slice 2, see
  [docs/approval-workflow.md](docs/approval-workflow.md)): a transition's `assign` contract — a 2-way
  SQL `SELECT` returning `assignee`/`candidate_group` rows (consuming the Phase 29 org-unit
  foundation unchanged) — opens a task in the managed `tql_workflow_task` inbox for the resulting
  state, completing the prior state's tasks in the same transaction. Authority is framework-enforced:
  a document with open tasks may only be transitioned by a caller who holds one (the direct assignee
  or a candidate group), else `TQL-WORKFLOW-3203` (403) — the dual of a scope over the task table,
  which an app may still author for its inbox query. The `WorkflowTaskStore`/`JdbcWorkflowTaskStore`
  is provisioned whenever any transition assigns, independent of the state mode (managed instance row
  or app column), so one inbox spans every workflow. Deadlines, escalation, and delegation are
  slice 3.
- Approval workflow — workflow core (roadmap Phase 28 slice 1, see
  [docs/approval-workflow.md](docs/approval-workflow.md)): a SQL-contract state machine driving a
  business document through declared states by transitions, with the IAM managed/SQL realm duality.
  A `kind: workflow` document under `workflow/` declares the `document`, `states` (one `initial`,
  zero or more `terminal`), and `transitions` (`from`/`to`, an optional whitelist-only `guard` over
  `document.*`/`principal.*`, a 2-way SQL `command`); the compiler synthesizes one
  transactional-command route per transition (`POST {basePath}/{key}/{transitionId}`). A transition
  reuses the Phase 18 command engine to, in one transaction, load the document, check the current
  state allows the transition, evaluate the guard, advance the state, run the command, and append an
  immutable history row — so a rejected transition rolls back entirely. `tesseraql.workflow.mode:
  managed` provisions `tql_workflow_instance` / `tql_workflow_history` behind a `WorkflowStore` SPI
  (`tesseraql-core`) with a `JdbcWorkflowStore` impl; `mode: app` (the default) keeps state in the
  business table's `stateColumn` and provisions nothing (per-workflow override allowed). An illegal
  or concurrent transition is `TQL-WORKFLOW-3201` (409), a falsy guard `TQL-WORKFLOW-3202` (422).
  Lint (`TQL-WORKFLOW-31xx`: undeclared/unreachable states, bad guards, missing files, mode
  mismatch) and a `workflow` coverage kind (one item per transition) keep it machine-checkable. The
  task inbox, assignee resolution, deadlines, and escalation are later slices.
- Organizational data scoping — row-level masking (roadmap Phase 29 slice 3, completing the phase,
  see [docs/data-scoping.md](docs/data-scoping.md)): a field is masked in the rows outside the
  caller's scope. The query selects the scope predicate as a per-row flag with the new
  `/*%scope <name> on <alias> as boolean */ (1=1)` directive — rendered as a portable
  `case when … then 1 else 0 end` — and a response `fields` policy keys off it with
  `unmaskWhen: <flag column>`, masking the field when the flag is falsy and stripping the flag column
  from the response. No per-row predicate evaluation in Java. Column-level role masking
  (`FieldPolicy.policy`) is unchanged.
- Organizational data scoping — shared org-unit foundation (roadmap Phase 29 slice 2, see
  [docs/data-scoping.md](docs/data-scoping.md)): a managed org-unit hierarchy that subtree scopes
  (and, later, Phase 28 approval-workflow assignee resolution) build on — one org graph, the IAM
  managed/SQL realm duality. `tesseraql.orgunit.mode: managed` provisions `tql_org_unit` (units +
  `parent_id`) and `tql_org_closure` (the transitive closure, depth 0 = the unit itself); the
  `OrgUnitStore` SPI (`tesseraql-core`) and `JdbcOrgUnitStore` impl maintain it — `upsert`/`delete`
  units then `rebuildClosure()` recomputes the closure from the parent graph in Java, so it is
  dialect-agnostic (no recursive CTE) and a subtree scope stays a plain, portable
  `owner_unit in (select descendant_id from tql_org_closure where ancestor_id in /* my_units */ (…))`.
  `mode: app` (the default) provisions nothing — the app owns its org tables and writes the fragment
  against them. `OrgUnitStore.descendants(...)` is the Java seam Phase 28 reuses. Lint
  (`TQL-SCOPE-3020`) validates the mode.
- Organizational data scoping — scope core (roadmap Phase 29 slice 1, see
  [docs/data-scoping.md](docs/data-scoping.md)): named, reusable row-level predicates derived from
  the request principal, the row-level complement to multi-tenancy. A `kind: scope` document under
  `scope/` declares an ordered list of **match arms** — each a `Policy`-style role/permission/claim
  `when` paired with an effect (`apply: all`, `apply: none`, or a 2-way SQL predicate `file`).
  Multiple matching arms compose **additively (OR)**; matching none is deny-by-default (`1=0`). A
  query opts in with a new 2-way SQL directive, `/*%scope <name> on <alias> */ (1=1)` (sibling to
  `/*%if … */`, in `tesseraql-core`), whose parenthesized dummy keeps the template runnable in a SQL
  tool; at execution a `ScopeResolver` replaces it with the principal-derived predicate,
  parameterized — never by rewriting `WHERE`/`FROM`. Fragments are alias-parameterized with a `$`
  sentinel (`$.region`) the call site qualifies via `on <alias>`, and a scope needing a join is a
  correlated `EXISTS`. The resolver is bound only when an app declares scopes; a directive rendered
  without one fails closed (`TQL-SQL-2106`). Lint (`TQL-SCOPE-3011..3013`) and a `data-scope`
  coverage kind keep it machine-checkable. The shared org-unit foundation and masking integration
  are later slices.
- Messaging and events — Postgres-native event channel (roadmap Phase 27, see
  [docs/messaging.md](docs/messaging.md)): a broker-free publish/subscribe transport built on a
  durable table plus PostgreSQL `LISTEN`/`NOTIFY` — no Kafka, no JMS. A command's `publish:` block
  emits a domain event on the transactional outbox (so a rolled-back command never publishes); a
  relay moves committed `EVENT` events onto a durable `tql_event` log and issues a `NOTIFY`; and a
  `queue-consume` route under `consume/` claims messages with `FOR UPDATE SKIP LOCKED` — woken the
  instant an event is published, swept by a polling backstop — and runs its SQL pipeline,
  deduplicated by an idempotency key in `tql_queue_consumed` so at-least-once delivery is effectively
  exactly-once per business key. `NOTIFY` is only the low-latency signal; the durable table is what
  makes delivery survive, so the `pg-notify` transport runs on a PostgreSQL main datasource, and the
  `OutboxEventSink` relay plus the `publish:`/`consume:` YAML are the seam a later Kafka/JMS leaf
  module plugs into unchanged. Channels are configured centrally
  (`tesseraql.messaging.channels.<name>`), lint covers them (`TQL-SEC-4090..4091`,
  `TQL-YAML-1009..1010`, `TQL-YAML-1106`), and a `queue-consume` coverage kind tracks the consumers
  declarative suites exercise. A second built-in transport, `db-poll`, makes the channel **portable
  across every dialect** (MySQL, SQL Server, Oracle, and PostgreSQL behind a transaction-pooling
  proxy that breaks `LISTEN`): the same durable `tql_event` queue, claimed with each dialect's
  `SKIP LOCKED` equivalent (PostgreSQL/MySQL `LIMIT … FOR UPDATE SKIP LOCKED`, Oracle `ROWNUM`,
  SQL Server `TOP … WITH (UPDLOCK, READPAST)`, mirroring the outbox dispatcher), polled on the
  `backstop` interval instead of woken by `NOTIFY`. Same at-least-once, idempotent delivery — only
  the latency differs; switching `transport:` is the whole change.
- Managed connectors — inbound webhook recipe (roadmap Phase 26, see
  [docs/connectors.md](docs/connectors.md)): a `webhook` route is an HMAC-verified,
  replay-protected POST endpoint in front of a SQL pipeline. The recipe authenticates the signed
  delivery (HMAC over `<timestamp>.<body>`, the scheme the Phase 20 outbound webhook signs with),
  rejects a stale/future timestamp outside the configured tolerance, and rejects a replay — all
  before request binding, so an invalid delivery never writes a row. The verifier is configured
  centrally (`tesseraql.connectors.webhooks.<name>`: secret resolved lazily through the
  SecretResolver SPI, header names, an optional delivery-id header for the replay key, and the
  tolerance), so the route carries no secret; the named verifier must be configured (an unknown
  provider fails the build, since a webhook without a verifier would be unauthenticated). Replay
  protection is a shared JDBC store (`tql_webhook_seen`, the same basis as SAML assertion replay),
  so a delivery is processed at most once on any node sharing the database. A bad signature or
  stale timestamp maps to 401, a replay to 409. Lint (`TQL-SEC-4082..4083`, `TQL-YAML-1008`) and a
  `webhook` coverage kind keep it machine-checkable. `RouteDefinition` gains a `webhook:` block;
  the runtime binds the `WebhookReplayStore`. **Phase 26 (managed connectors) is complete.**
- Managed connectors — polling file triggers (roadmap Phase 26, see
  [docs/connectors.md](docs/connectors.md)): a `file-import` job can be driven by a `poll:`
  trigger instead of an HTTP upload — the runtime watches a local directory or a remote
  SFTP/FTPS server and feeds every file it finds through the job's `import:` pipeline (the same
  per-row 2-way SQL a `file-import` route applies), ingesting each file through the existing
  asynchronous, off-heap, operations-tracked transfer path and moving it to a done/failed
  sub-directory. Reaching a remote host is **deny by default** (`tesseraql.connectors.poll.allowedHosts`,
  exact or `*.wildcard`); credentials come from `tesseraql.connectors.poll.credentials`, resolved
  through the SecretResolver SPI when the consumer starts. The underlying Camel `file`/`sftp`/`ftps`
  consumer stays an implementation detail, not user API. Lint catches a misconfigured poll job
  (`TQL-SEC-4080` off-allow-list host, `TQL-SEC-4081` undeclared credential, `TQL-YAML-1005`
  invalid source, `TQL-YAML-1006` missing import block), a job that targets a non-allow-listed host
  is skipped at startup rather than failing the runtime, and a new `file-poll` coverage kind tracks
  the poll jobs declarative suites exercise. `TriggerSpec` gains a `poll` member beside `schedule`,
  and `JobDefinition` an `import:` block. Adds `camel-file`/`camel-ftp`.
- Managed connectors — outbound HTTP (roadmap Phase 26, see
  [docs/connectors.md](docs/connectors.md)): an `http-call` batch-pipeline step issues one
  synchronous outbound REST request and publishes the response to later steps
  (`step.<id>.status` / `.body` parsed JSON or text / `.headers`), so a job can fetch from an
  API and persist the result, or push database rows to a partner system. It is a job step,
  never a transactional `command-json` step — a synchronous call cannot be rolled back, so a
  command's outbound integration rides the Phase 20 outbox webhook instead. All outbound HTTP
  is governed by `tesseraql.http.outbound`: egress is **deny by default** (a call may only
  target a host in `allowedHosts`, exact or `*.wildcard`), credentials (`bearer`/`basic`/`header`)
  resolve from the SecretResolver SPI at call time so a step never carries a secret, timeouts
  come from config with per-step overrides, and a per-host circuit breaker trips on consecutive
  systemic failures (transport errors and `5xx`) and fails fast for a cooldown. Each call is a
  `tesseraql.http.call` trace span. Lint catches misconfigured egress before it ships
  (`TQL-SEC-4070` off-allow-list host, `TQL-SEC-4071` no absolute url, `TQL-SEC-4072` undeclared
  credential), and a new `http-call` coverage kind tracks the steps declarative suites plan
  (resolving url, query bindings, and the allow-list without a network call). Camel's component
  catalog stays an implementation detail, not user API. `PipelineStep` gains an `http-call`
  member beside `sql` and `notify`.
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
- Authentication completion — OIDC relying party (roadmap Phase 25, see
  [docs/authentication.md](docs/authentication.md)): a new opt-in leaf module `tesseraql-oidc`
  self-installs via the `RuntimeExtension` SPI (like SAML/SCIM) when on the classpath and
  `tesseraql.oidc.enabled` is true, serving the authorization-code + PKCE flow at
  `/_tesseraql/oidc/{login,callback,logout}`. The provider endpoints are discovered lazily (so app
  boot does not depend on the OP); `/login` records a single-use `state`/`nonce`/PKCE verifier in
  `tql_oidc_state` and redirects with an S256 `code_challenge`; `/callback` consumes the state
  (rejecting a forged, replayed, or `error=` response), exchanges the code (`client_secret_basic`
  or public PKCE), validates the ID token by reusing the RS256/JWKS verifier (signature, `iss` from
  discovery, `exp`/`nbf`) plus OIDC `aud`/`nonce` checks, links or provisions the principal via the
  identity contracts, and issues the standard browser session. JDK-only — no external OIDC/JOSE
  dependency. Ships an `oidc` coverage kind, config lint (`TQL-SEC-4050..4053`), a Studio **OIDC
  provider** IAM admin wizard, and a `TqlDomain.OIDC` error domain.
- Authentication completion — mutual TLS (roadmap Phase 25, see
  [docs/authentication.md](docs/authentication.md)): a route declares `auth: mtls` to authenticate
  a service caller by an X.509 client certificate that a TLS-terminating edge (reverse proxy,
  ingress, or mesh sidecar) forwards in a configured header (URL-encoded PEM). The runtime parses
  the certificate (JDK only — no third-party PKI dependency), checks its validity window against an
  optional `clockSkew`, optionally PKIX-validates it against a `trustBundle` CA bundle as
  defense-in-depth (revocation left to the edge), and matches its identity — exact subject DN
  (order/case-insensitive RDNs), a SAN value, or its DER SHA-256 fingerprint — against declared
  clients deny-by-default, resolving to an explicit principal so existing policies apply (`401` on
  no match / expired / malformed / missing, `403` on policy failure). A certificate is public, so
  matching is a lookup, not a secret compare; it is never logged. Ships an `mtls` coverage kind and
  config lint (`TQL-SEC-4060..4065`, including a warning when no `trustBundle` is set). **Phase 25
  is complete** (RS256/JWKS, API keys, OIDC, and mTLS).

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
