# Changelog

All notable changes to TesseraQL are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- **One-click light/dark toggle in the shell header** (hc 0.1.9 adoption; design in
  `docs/account.md`): the kit's `installThemeToggle` flips the page instantly, and the
  framework bootstrap mirrors `hc:themechange` to the account appearance route, so the
  stored `ui.theme` preference — not localStorage — remains the source of truth (the
  cookie re-sync then carries the choice onto pre-login pages). Rendered only when the
  bundled account app is mounted; app pages can opt in with the same
  `data-hc-theme-toggle` button, documented in `docs/hypermedia-ui.md`.
- **Studio route form options derive from the framework** (roadmap Phase 57
  slice 3, completing the phase; design in `docs/vscode-extension.md`): the form's
  recipe, auth, and input-type choices come from the same surfaces the shipped JSON
  Schema is drift-tested against (`AppLinter.knownRouteRecipes()` and the new
  `knownAuthModes()`/`knownInputTypes()`), replacing hand-coded lists that had
  already drifted — the form offered four auth modes where the framework accepts
  five (`public` was missing). `security.auth` is now a real `enum` in the shipped
  JSON Schema (editors gain completion), and `SchemaSyncTest` holds the schema, the
  linter, and the form to one source.
- **Studio: Open in editor** (roadmap Phase 57 slice 2; design in
  `docs/vscode-extension.md`): the source view gains the reverse half of the
  Studio–editor round trip — a `vscode://file/…` deep link next to *Edit as form*,
  landing on the same file in VS Code. Best-effort by design: the link assumes the
  browser and the files share a machine (the normal dev loop) and stays inert
  otherwise; the traversal guard covers the deep link too.
- **VS Code extension 0.3.0** (roadmap Phase 56 complete; design in
  `docs/vscode-extension.md`): the version carrying Phase 56's editor
  intelligence — single-case Test Explorer runs, Studio deep links, MCP
  registration, and the `tesseraql symbols` language layer.
- **The language layer — `tesseraql symbols` and editor intelligence** (roadmap
  Phase 56 slice 5, completing the phase; design in `docs/vscode-extension.md`):
  `tesseraql symbols --app <dir>` prints what the framework declares — security
  policies, default-locale message keys, and routes, each with source and line — as
  one sorted, deterministic JSON object. Over it the VS Code extension adds
  completion for `policy:` and `message:` values and go-to-definition from a
  `policy:` value to its declaration in `config/tesseraql.yml` and from a
  `message:` (or key-naming `title:`/`label:`) value to its catalog line, refreshed
  on save. Unknown references stay lint findings — the providers navigate, they do
  not judge.
- **VS Code extension: MCP registration** (roadmap Phase 56 slice 4; design in
  `docs/vscode-extension.md`): *TesseraQL: Register MCP Server* writes the Phase 24
  dev-tools server (`tesseraql mcp --app .`, stdio) into the chosen client
  configuration in the app home — `.vscode/mcp.json` (VS Code MCP clients) and/or
  the project `.mcp.json` (Claude Code) — merging with existing servers, no-op when
  already registered, and never overwriting a foreign `tesseraql` entry without a
  modal confirmation.
- **VS Code extension: Studio deep links** (roadmap Phase 56 slice 3; design in
  `docs/vscode-extension.md`): *TesseraQL: Open in Studio* — from the editor context
  menu, the TesseraQL explorer, or the command palette — opens the file's live
  counterpart in the running Studio's source view
  (`/_tesseraql/studio/ui/source?path=…` on `tesseraql.serverUrl`).
- **Single-case test runs — `tesseraql test --case`** (roadmap Phase 56 slice 2;
  design in `docs/vscode-extension.md`): a repeatable exact-name filter runs only
  the named case(s) — suites with no match are skipped, item coverage derives from
  what actually ran, and the `--format json` document reports only the filtered
  results. The VS Code Test Explorer passes the filter when a run request names
  specific cases, so one failing case re-runs alone.
- **VS Code extension: serve status** (roadmap Phase 55 slice 5, completing the
  phase; design in `docs/vscode-extension.md`): a status-bar item polls the served
  app's readiness probe (`/_tesseraql/health/ready`, Phase 45) on the new
  `tesseraql.serverUrl` setting while an app home is open — up, DOWN (a 503
  readiness answer), or offline — and one click opens the app. Extension version
  0.2.0.
- **VS Code extension: Test Explorer and SQL coverage** (roadmap Phase 55 slice 4;
  design in `docs/vscode-extension.md`): suites under `tests/**/*.yml` appear in the
  native Test Explorer (cases discovered by name and line — presentation, not
  semantics); a run executes `tesseraql test --format json` against the app's
  datasource and maps results back by case name, and a *Run with Coverage* run feeds
  the same document's per-file SQL `coveredLines`/`coverableLines` into the editor's
  test coverage API — covered and uncovered 2-way-SQL lines paint where the SQL is
  written.
- **VS Code extension: reference navigation** (roadmap Phase 55 slice 2; design in
  `docs/vscode-extension.md`): `file:`, `view:`, and `template:` values in app YAML
  are document links, resolved against the document's directory exactly as the
  runtime resolves them (a `frags.html::fragment` suffix links to the file; a
  `view: list` kind without a file extension is not a reference). A link appears
  only when the target exists — a broken reference stays a lint finding.
- **`tesseraql test --format json`** (roadmap Phase 55 slice 3; design in
  `docs/vscode-extension.md`): the test command can print the editor test-run
  contract — `{passed, failed, results: [{name, passed, message}], sql: [{file,
  lineRatio, branchRatio, coveredLines, coverableLines}]}` — as the one JSON object
  on stdout: the complete per-case results (the `report.json` overlay only carries
  cases joined to a route) plus per-file SQL line/branch coverage with the 1-based
  line lists, files and lines sorted for determinism. `--format text` names today's
  output and stays the default; exit semantics (1 on failure, 2 on the opt-in
  regression gate) are identical in both formats.
- **Scaffolded apps recommend the TesseraQL VS Code extension** (roadmap Phase 54
  slice 4, completing the phase; design in `docs/vscode-extension.md`):
  `tesseraql new` writes `.vscode/extensions.json` recommending
  `ingcreators.tesseraql-vscode` alongside `redhat.vscode-yaml`, so a fresh app opens
  with schema completion and the real lint loop one install away. Marketplace
  publishing stays an operator step; the CI-built `.vsix` installs from file.
- **VS Code extension (MVP)** (roadmap Phase 54 slice 3; design in
  `docs/vscode-extension.md`): `vscode-extension/` ships the editor shell over the
  existing engines — saving a file in an app home runs
  `tesseraql lint --format json` (the CLI named by the new `tesseraql.cliPath`
  setting) and publishes every finding to the Problems panel at its source, line,
  and column, with the finding code linking to the published error-code reference;
  *TesseraQL: Serve / Test / Migrate / Admission / Package* run in the integrated
  terminal and *Lint* headless; a *TesseraQL* explorer view walks routes by kind,
  views, migrations, and test suites; `TQL-*` hovers link into the reference; and
  snippets cover the blessed route shapes. TypeScript with zero runtime
  dependencies; the editor-free core is unit-tested with `node:test`; a new CI job
  typechecks, tests, and packages the `.vsix` (marketplace publishing stays an
  operator step per the design).
- **`tesseraql lint --format json`** (roadmap Phase 54 slice 2; design in
  `docs/vscode-extension.md`): the lint command can print the cross-surface findings
  document the MCP dev-tools' `lint` tool has emitted since Phase 24 —
  `{errors, warnings, findings: [{code, severity, source, message, line, column}]}` —
  as the one JSON object on stdout, so editors parse the same shape agents do.
  `--format text` names today's output and stays the default; exit semantics
  (including `--fail-on-warning`) are identical in both formats.
- **Cross-database projections — `datasource:` on transactional routes** (roadmap
  Phase 53 slice 3, completing the phase; design in `docs/multi-datasource.md`): a
  `command-json`, `webhook`, or `queue-consume` route moves its whole
  single-connection transaction to a named connector — the blessed shape being the
  **projection**: a command commits on `main` and publishes, a `queue-consume` route
  with `datasource:` idempotently upserts into the second database, while the
  channel, its claim, and the consumed-key dedup records stay on `main` (delivery
  semantics unchanged, no JTA/XA anywhere). A non-main transaction is plain SQL:
  `notify:`/`publish:`/`outbox:` and sequence allocation are refused at build time
  (`TQL-YAML-1036`) and again at route compile time (`TQL-CAMEL-3112`) — their
  tables live on `main`; fan-out projects through `main` instead. Proven end to end
  by `MultiDatasourceProjectionIntegrationTest` on two real PostgreSQL databases:
  commit projects, rollback never does, and a republished business key never
  doubles or reorders the projection (milestone M18).
- **Multi-datasource reads — `datasource:` on read routes** (roadmap Phase 53 slice 2;
  design in `docs/multi-datasource.md`): a read route (`query-json`, `query-html`,
  `page`, `query-export`, and read-only MCP tools/resources/UI) can declare
  `datasource: <name>` to run its SQL on a named connector under
  `tesseraql.datasources`, and a named query on a page can override per binding — so
  one response composes result sets from several databases. The baked SQL dialect
  (pagination clauses, streaming profiles, label normalization) now resolves per
  connector, and an explicit non-main `datasource:` is authoritative over per-tenant
  datasource routing (tenant routing replaces only `main`). Lint guards the surface:
  an undeclared connector is `TQL-YAML-1035`, a route-level connector on a
  transactional recipe is `TQL-YAML-1036` (until the projection slice), and a
  per-step connector inside a transactional pipeline is `TQL-YAML-1037`.
- **Embedded PostgreSQL version pinning** (`serve --embedded-db`): a persistent data
  directory now records the exact zonky binaries version that initialized it (in a
  `tesseraql-embedded.properties` marker) and re-resolves that version on every later
  start, so bumping the CLI's default binary version can never re-open an existing
  directory with a format-incompatible major. A new `--embedded-db-version <version>`
  flag pins the version explicitly; ephemeral runs continue to use the default.
- **Embedded PostgreSQL major-version guard**: when the version resolved for an
  `--embedded-db` run cannot open the target directory (its `PG_VERSION` records a
  different major), the CLI now fails fast with an actionable message — how to pin the
  matching major, or start fresh — instead of surfacing a cryptic `postgres` startup
  crash.
- **`tesseraql embedded-db info <data-dir>`**: reports an embedded data directory's
  on-disk PostgreSQL major, its pinned binary version, and the CLI default — and, when
  the directory sits on an older major, prints the safe dump/restore upgrade procedure.
  The embedded binaries are server-only, so a cross-major upgrade is driven with the
  operator's own `pg_dumpall`/`psql`; the command produces the exact steps.

- **Documentation site, slice 2 — the generated reference** (completing the
  documentation-site leg of roadmap Phase 35; design in `docs/docs-site.md`): a
  build-only `tesseraql-docs-reference` module generates two committed markdown pages
  under `docs/` — the **YAML surface reference** walked from
  `tesseraql-v1.schema.json` (every property with type, constraints, and description,
  nested sections per object path) and the **error-code index** scanned from the
  modules' sources (both the literal `TQL-*` form and the `TqlDomain` constructor
  form: 316 codes across 29 domains, each with raising-file provenance and links to
  the cookbook pages that mention it). A drift test fails the build when the
  committed pages no longer match their sources (refresh:
  `mvn -q -pl tesseraql-docs-reference exec:java`); the site gains a **Reference**
  sidebar section, and the links validator checks every generated link and anchor.
- **Documentation site, slice 1 — the Starlight app** (the documentation-site leg of
  roadmap Phase 35; design v2 in `docs/docs-site.md`): `docs-site/` is an Astro
  Starlight project mirroring the Hypermedia Components docs stack — Pagefind search,
  Expressive Code highlighting, `starlight-links-validator` failing the build on
  broken internal links, and `starlight-llms-txt` emitting `/llms.txt` for AI agents.
  `docs/` stays the canonical GitHub-browsable tree: a sync step derives frontmatter
  from each H1, rewrites same-tree `*.md` links to site URLs and repo-relative links
  to GitHub, and **fails the build when a document is neither mapped into the nav nor
  explicitly excluded**. Deploys to Cloudflare Workers Static Assets (`wrangler.jsonc`
  + base-path `worker.mjs`, git-connected with per-PR preview versions); the CI
  `docs-site` job runs the same build, and the dashboard-side one-time setup is the
  runbook in `docs-site/DEPLOYMENT.md`.
- **Personal productivity, slice 2 — recents** (roadmap Phase 51, final slice — **the
  phase is complete and milestone M17 is met, closing every phase named into
  Horizon 9**): rendering a **`view: detail`** page records it in the user's bounded
  recent ring (20, deduped by URL with rapid reloads coalesced inside the cache TTL,
  bumped on revisit, labelled by the view's own title), listed and removable on the
  account page — never in the sidebar, so the chrome stays calm. The planned in-pattern
  *Pin this view* button was dropped as redundant: the header control already pins any
  page with its query string.
- **Personal productivity, slice 1 — pins** (roadmap Phase 51; design in
  `docs/productivity.md`): every shell page's header gains **Pin / Unpin** for the
  current URL — **query string included, so pinning a filtered list IS saving the
  filter** (one control covers Phase 39 list views, the Studio data browser, and
  dashboards alike). Pins render as a **Pinned** sidebar group on every page (the
  reserved `_shortcuts` variable, TTL-cached read) and are managed on the account page.
  Capped at 20 (oldest fall out), re-pinning bumps and relabels, and hrefs are
  **relative paths only** — absolute, `//`, and `/\` forms are refused
  (`TQL-ACCOUNT-4802`), so a pin can never point off-site. One managed
  `tql_user_shortcut` table (keyed on the href's SHA-256 so the composite key fits every
  dialect's index limits) behind the `ShortcutStore` SPI; recents ride the same store in
  slice 2.
- **Workflow delegation and absence, slice 2 — operator visibility + the gallery proof**
  (roadmap Phase 52, final slice — **the phase is complete and milestone M16 is met**):
  the IAM admin gains a read-only **Active delegations** panel
  (`/_tesseraql/admin/delegations`, `iam.admin.view`) — who is absent, who covers, until
  when — and the full M16 loop is proven against the **real purchase-request gallery
  app**: absence set on the account page, the submitted request lands with the delegate
  marked "for" the approver, the absent approver is refused, the delegate approves as
  themselves, and after the window new requests reach the approver again.
- **Workflow delegation and absence, slice 1** (roadmap Phase 52; design in
  `docs/delegation.md`): a standing **out-of-office rule** (one window, one delegate per
  subject, strictly self-service on the account page) redirects **new assignments at
  assignment time** — the transition `assign:` rows, the sweeper's `reassign` fallback,
  and the per-task hand-over target all resolve through one one-hop helper, so chains
  and loops are impossible by construction. **No identity is ever borrowed**: the
  delegate becomes the assignee and acts as themselves, and the absent approver holds
  nothing. The task row records `delegated_from`, so meant/received/acted is a
  structural trail on the persisted task. Candidate groups and already-open tasks are
  deliberately untouched.
- **Credential lifecycle, slice 3 — TOTP second factor** (roadmap Phase 50, final slice
  — **the phase is complete and milestone M15 is met**): RFC 6238 over
  `javax.crypto.Mac` (HmacSHA1, 6 digits, 30 s steps, ±1 window) with a hand-rolled
  Base32 — no new dependency, validated against the RFC's own test vectors. Enrollment
  lives on the account page and **confirms with a valid code before anything
  enforces**; a confirmed enrollment makes the login's optional code field required,
  with missing, wrong, and **replayed** codes all answering exactly like a wrong
  password — the replay guard is the store's `last_used_step` **compare-and-set**, so a
  captured code can never be accepted twice. Disabling re-verifies the password
  (`TQL-ACCOUNT-4804`). QR rendering and recovery codes are deliberately out of scope
  and documented.
- **Credential lifecycle, slice 2 — invitations** (roadmap Phase 50): the bundled IAM
  admin grows **Invite user** — the account is created with status `INVITED` (which the
  credential contract already refuses at login) and the one-time accept link mails over
  the outbox; **the operator never knows a password**. `/_tesseraql/invite` sets the
  first password and the existing `enable-user` contract flips the account ACTIVE.
  Re-inviting a still-INVITED account politely resends (subject to the token cooldown);
  an already-usable login answers 409 — an invite can never take over an account.
  Requires `tesseraql.identity.invite.{channel,url}` (half-set fails the boot,
  `TQL-SEC-4120`); the token store is shared with slice 1's reset.
- **Credential lifecycle, slice 1 — password reset** (roadmap Phase 50; design in
  `docs/credential-lifecycle.md`): the login page grows **Forgot password?** when
  `tesseraql.identity.recovery` is configured (enabled + a mail channel + the confirm
  URL; anything half-set fails the boot with `TQL-SEC-4120`). The request leg answers
  the same neutral "sent" for unknown logins, missing emails, and cooldowns — no
  enumeration oracle; the destination comes from the new overridable
  `find-recovery-destination-by-login` pack contract (default: the ACTIVE user's
  `tql_users.email`) and the mail rides the outbox. Tokens live in the new managed
  `tql_credential_token` (256-bit, **SHA-256 at rest**, purpose-bound, **single-use by
  check-and-set**, issue cooldown, expiry prune). The confirm leg rotates through the
  existing `update-password` contract and **invalidates every session of the subject**;
  unknown, used, and expired links all answer the same honest dead-link page.
- **The in-app notification center, slice 2 — the surface** (roadmap Phase 49, final
  slice — **the phase is complete and milestone M14 is met**): the shared shell grows a
  **bell** with the unread badge (the reserved `_inbox` variable; the count reads
  through a 15 s TTL cache, so a page render costs a map lookup and local
  deliveries/reads refresh the badge at once), and **`/_tesseraql/inbox`** joins the
  bundled account app: newest-first list, per-message **Mark read** (owner-checked —
  someone else's message answers `TQL-ACCOUNT-4806`/404), **Mark all read**, and the
  honest empty state when no inbox channel is declared (then there is no bell at all).
- **The in-app notification center, slice 1 — delivery** (roadmap Phase 49; design in
  `docs/inbox.md`): a third channel type **`inbox`** delivers `recipient:`-addressed
  `notify:` events into the managed `tql_user_notification` table — the resolved
  recipient (and acting tenant) now ride the outbox envelope, `title`/`body` render as
  the channel's inline TEXT templates, and **delivery dedupes on the outbox event id**,
  so at-least-once redelivery never doubles a message. Read-state operations
  (`unreadCount`/`recent`/`markRead`/`markAllRead`) land with the store; read messages
  prune past `tesseraql.inbox.retentionDays` (default 90). An inbox notification
  without `recipient:` fails lint (`TQL-YAML-1034`); the Phase 48 opt-out silences at
  enqueue as before. The shell bell and `/_tesseraql/inbox` page follow in slice 2.
- **The account surface, slice 5 — app-declared preference groups** (roadmap Phase 48,
  final slice — **the phase is complete and milestone M13 is met**): an app declares
  fields in `config/preferences.yml` (`boolean` | `choice` | `text`, message-catalog
  labels, defaults); the account page renders them as an App settings section, writes
  stay bounded by the declaration (`TQL-ACCOUNT-4802`), and routes/templates/SQL read
  them back through the **`preference.<key>`** namespace — stored value else declared
  default, declared keys only. Lint `TQL-YAML-1030..1033`, a `preference` NOTE coverage
  kind, and the inventory gallery app dogfoods the file.
- **The account surface, slice 4 — sessions and password** (roadmap Phase 48): the
  account page lists the caller's active sessions (`tql_session` gains an indexed,
  nullable `subject` — pre-upgrade rows age out unlisted) with a **Sign out other
  sessions** action (`POST /_tesseraql/logout-others`, runtime-wired beside
  login/logout, CSRF-checked); and the **local-realm password change** verifies the
  current credential through the same contract the login path uses before writing the
  new hash via the `update-password` identity contract — wrong current password is
  `TQL-ACCOUNT-4804`, SSO-managed deployments answer the honest `4803` and the page
  says so. `SqlScripts` now tolerates the vendor duplicate-column/key codes so the
  re-runnable V2 bootstrap stays idempotent on every dialect.
- **The account surface, slice 3 — notification opt-out** (roadmap Phase 48): a `notify:`
  declaration gains an optional **`recipient:`** expression (e.g. `principal.subject`);
  when the resolved subject opted out of the channel, the enqueue path — command routes
  and job `notify:` steps alike — **writes no outbox row** and reports `{optedOut: true}`
  instead of an event id. Channels the operator marks `userOptOut: true` appear as toggles
  on the account page; channel-level notifications (no `recipient:`) are never affected.
- **The account surface, slice 2 — language and appearance** (roadmap Phase 48): the
  account page grows the two settings; a saved language flows through the Phase 22 locale
  chain via the new `preference.<key>` source kind (default order now
  `preference.ui.locale` before `principal.claim.locale`, so the choice works with zero
  configuration), and a saved theme re-skins the shared shell (`_theme` replaces the
  hardcoded `data-theme`; the renderer re-syncs a `tesseraql_theme` cookie so pre-login
  pages follow; `tesseraql.ui.theme` sets the operator default, falling back to today's
  dark). New `TQL-ACCOUNT` error domain (48xx).
- **The account surface, slice 1** (roadmap Phase 48 — opening Horizon 9; design in
  `docs/account.md`): the shared shell grows a signed-in **user menu** (avatar + native
  popover, rendered from the reserved `_account` variable beside `_csrf`/`_menu`), a bundled
  **`/_tesseraql/account`** system app serves the session principal's profile (the `auth-ui`
  precedent — on by default with console login, `tesseraql.apps.account.enabled: false` to
  opt out), and a managed **per-user preference store** (`PreferenceStore` SPI, cached;
  `tql_user_preference` with Oracle/SQL Server variants) is bound for the settings slices to
  come. The subject is always the session principal's, by construction.
- **Studio copilot chat** (roadmap Phase 44 — completing Horizon 8 in full; decision point 8
  resolved): an in-Studio panel (`/ui/copilot`) that drives the existing gated loop as tools
  against an **operator-configured** OpenAI-compatible endpoint — TesseraQL ships no model,
  the key stays a `${secret.*}` reference resolved lazily at call time, reads
  (routes/sources/lint/schema/preview) are free, the **only write is an audited draft**
  offered to the model solely when the chatting user holds an edit role, and applying stays
  a human action in the editor's diff-confirm UI. Bounded tool turns; honest disabled state
  when unconfigured; see `docs/copilot.md`.
- **The five-minute demo** (roadmap Phase 47, final slice — the phase, **milestone M12**,
  and Horizon 8 are complete): one command (`tesseraql serve --app examples/inventory-app
  --embedded-db`) boots a seeded, browsable gallery app with Studio open, and one container
  image (`deploy/Dockerfile.demo`) does the same with embedded PostgreSQL inside — no
  compose, no external database. `docs/five-minute-demo.md` is the Studio tour that walks
  the closed low-code loop end to end.
- **Template gallery** (roadmap Phase 47): three complete, declarative-only starter apps join
  `examples/` — **`purchase-request-app`** (the approval workflow: a `kind: workflow` document
  drives draft → submitted → approved/rejected in managed mode, with synthesized transition
  endpoints, a guard, task assignment, and the history on a declarative detail view),
  **`inventory-app`** (declarative views end to end: searchable/paginated list, a dashboard
  with stats/bar chart/low-stock table, forms, and a stock adjustment guarded by a
  declarative validation rule), and **`helpdesk-app`** (an app-mode workflow over the
  ticket's own `status` column plus a transactional `notify:` the suite asserts without
  SMTP). Each app is held to the marketplace admission profile, lints clean, and passes its
  own declarative suites against a real database in CI (`GalleryAppsIntegrationTest`).
- **Marketplace admission profile** (roadmap Phase 47, first slice — realizing the Phase 37
  admission gate): `tesseraql admission --app .` and the `tesseraql:admission` Maven goal run
  the machine-checkable bar a shared app must clear — declarative-only (no plugin jars, no
  service bindings, no unauthenticated writes), deny-by-default policies actually defined
  (the `TQL-SEC-4030` warning is promoted to a failure), bounded egress (no bare `*`),
  CSP on every HTML page (documented `/fragments/` convention exempt), governance approvals
  current, and zero lint errors (`TQL-ADM-4701..4706`; see `docs/admission.md`). Dogfooded:
  the shipped example apps are held to the bar in CI — which immediately caught and fixed a
  real gap (the user-admin example's admin page served HTML without CSP headers).
- **Release diff — "what does this deploy change"** (roadmap Phase 46, final slice — the
  phase is complete; the promotion recipe is documented in
  [docs/promotion.md](docs/promotion.md)): `ReleaseDiff` compares
  two app trees deterministically — routes added/removed/changed, the OpenAPI contract diff,
  the migration list the deploy will run, security-policy changes, and the table-level schema
  delta when both trees carry the introspection sidecar. Surfaced three ways: the
  `tesseraql release-diff --app <candidate> --baseline <tree>` CLI command (Markdown or
  `--json`, `--out` to write a file), the `tesseraql:release-diff` Maven goal (writes
  `release-diff.md`/`.json` beside the release evidence), and a docs-portal **Release diff**
  page consolidating the captured-baseline diffs (API changelog, schema DDL) with the app's
  migration set.
- **Environment profiles** (roadmap Phase 46, first slice): one switch — `--env <profile>` on
  `tesseraql serve`, `TESSERAQL_ENV`, or `-Dtesseraql.env` — merges
  `config/env/<profile>.yml` between the app's base config and Studio's `overlay.yml`, so the
  profile carries the environment's tuning while dev-time Studio edits still win on top. A
  named profile without a file fails startup fast (a typo'd environment must never silently
  run another environment's config); no profile keeps today's behavior exactly.
- **Business-route audit log + custom error pages** (roadmap Phase 45, final slice — the
  phase is complete): `tesseraql.audit.routes.enabled: true` records one durable
  `tql_route_audit` row per invocation — actor, tenant, route, method, path, status,
  duration, `trace_id`, and the **declared** params as JSON with `mask:`/`classification:`
  fields excluded wholesale; a failed insert never fails the request.
  `GET /_tesseraql/ops/audit` reads the trail, gated and narrowed to the caller's
  `ops.app.<name>` grants. **Per-app custom error pages**: `templates/errors/<status>.html`
  (or `errors/error.html`) renders for failed top-level browser GETs, while htmx swaps keep
  the inline fragment and API clients keep the JSON envelope.
- **Structured logging with trace-id correlation** (roadmap Phase 45): the CLI distribution
  ships a JDK-only SLF4J provider — before it, the standalone runtime had NO log backend at
  all (every line fell into SLF4J's NOP sink). Plain text by default, `--log-format json`
  for structured lines, `--log-level` for the threshold; every line carries the MDC, the
  runtime puts the request's `traceId`/`spanId` there, and Camel bridges the keys across
  async steps. An **opt-in HTTP access log** (`tesseraql.logging.accessLog: true`) emits one
  correlated line per request on the `tesseraql.access` logger, including the authenticated
  user. The Spring distribution keeps Boot's Logback untouched.
- **Safety valves** (roadmap Phase 45): every route SQL statement is now bounded **by
  default** — 30 seconds, the app-wide `tesseraql.sql.timeoutSeconds`, or a per-binding
  `sql.timeoutSeconds` override (`0` opts a deliberately long-running statement out;
  negative values are lint error `TQL-YAML-1021`) — so a runaway query is cancelled by the
  driver instead of holding a pool connection forever. Connection pools expose the remaining
  HikariCP tuning knobs (`minimumIdle`, `idleTimeoutMillis`, `maxLifetimeMillis`,
  `keepaliveTimeMillis`, `leakDetectionThresholdMillis` beside the existing
  `maximumPoolSize`/`connectionTimeoutMillis`), and `docs/deployment.md` now states the
  **per-node semantics** of the rate/concurrency limiters and lanes on multi-node
  deployments (a budget of N allows N × node-count cluster-wide; size per node or enforce at
  the balancer).
- **Truthful health** (roadmap Phase 45, first slice): `GET /_tesseraql/health/live` is pure
  liveness (the process answers; never touches a dependency) and
  `GET /_tesseraql/health/ready` — also served by the bare `/_tesseraql/health` — is the
  readiness roll-up: every configured datasource is probed live (`Connection.isValid` per
  pool) and the status degrades to **`DOWN` with HTTP 503** when one fails, so load balancers
  actually shed traffic; `WARN` stays a 200 with active alerts. A contributor that cannot
  reach its store during an outage counts as DOWN instead of crashing the endpoint into a 500,
  and the Spring Actuator bridge maps `DOWN` to `Health.down()`. The container HEALTHCHECK now
  targets `/health/live` and the kamal proxy check `/health/ready`; a new
  `tesseraql.datasources.<name>.connectionTimeoutMillis` knob bounds both borrower waits and
  the probe's detection latency.
- **Pull-based metrics** (roadmap Phase 45; decision point 9 resolved — JDK-only scrape path):
  the `Meter` abstraction gained latency **histograms**, an always-on JDK-only
  `AggregatingMeter` records per-route invocation counters, outcome-classed error counters,
  and duration histograms (`routeId`/`method`/`outcome`, status class keeps cardinality
  bounded), and `GET /_tesseraql/metrics` exposes them in Prometheus text format 0.0.4 —
  opt-in (`tesseraql.metrics.enabled`) and bearer + `ops.metrics.view` policy gated by
  default, with an explicit `tesseraql.metrics.unauthenticated` escape hatch for
  cluster-internal scrapes. OTLP push now carries the same histograms
  (`tesseraql-observability` maps them onto OpenTelemetry), and a ready-made Grafana
  dashboard ships at `deploy/grafana/tesseraql-dashboard.json`.
- **Authoring feedback outside Studio** (roadmap Phase 43, Track J5 — the phase is complete):
  the shipped JSON Schema now covers the full route/job/view document surface (recipe enum
  kept in sync with the linter by a build-time drift test), and `tesseraql new` wires it into
  the scaffolded repo — `.vscode/tesseraql-v1.schema.json` + a `yaml.schemas` association and
  a `redhat.vscode-yaml` recommendation — so any editor with a YAML language server validates
  and completes TesseraQL documents offline. **Lint findings gained positions**: `LintFinding`
  carries optional line/column, document rules point at the first occurrence of the offending
  key, and the CLI `lint`, Maven `tesseraql:lint`, and Studio health page render
  `source:line`.
- **Studio data-browser row editing** (roadmap Phase 43, Track J4): browser rows link **Edit**
  when the row editor's own opt-in (`tesseraql.studio.dataBrowser.edit.enabled`), the caller's
  `editRoles`, and a table primary key all line up. The PK-scoped single-row UPDATE validates
  identifiers against the live catalog, binds values coerced to the column types (a ticked
  empty value sets `NULL`), never touches PK columns, must affect exactly one row, always
  requires an explicit confirm, and is audited as the row identity plus column names — never
  values. The master-data maintenance screen nobody has to build.
- **Studio test recorder** (roadmap Phase 43, Track J3): a successful API-console invocation
  of a query route can be saved as a declarative test case — the sent parameters reverse-map
  onto the route's `sql.params`, the sandbox captures the row count as the expectation (the
  case passes by construction), and the `sql:` case lands in `tests/studio-recorded-test.yml`,
  runnable from the route's test runner and in CI like any hand-written case. A citizen
  developer's manual check becomes a regression test in one click.
- **Studio connector & SSO authoring** (roadmap Phase 43, Track J2): a **Connectors** page
  edits the managed connector config through the same gated overlay-write path as policies —
  egress allow-lists for `http.outbound` and `connectors.poll` (always behind an explicit
  confirm), outbound/poll credentials, and inbound webhook verifiers — with secret
  **references** only (`${secret.env.NAME}`): a literal secret value is rejected before it can
  reach a config file, and displayed values are redacted. The OIDC/SAML/SCIM/identity wizards
  became write-through: **Write to config overlay** lands the settings in `config/overlay.yml`
  beside the existing snippet download. Everything is edit-gated, audited, and honestly
  restart-bound (these sections load at boot; the pages say so).
- **Studio form-driven route editor** (roadmap Phase 43, Track J1 — first slice): a **Route
  form** page edits a route document's governed fields as structured form fields — recipe,
  auth, policy (suggested from the app's declared policies), CSRF, and the `input:` block as
  rows (name, type, required, min/max, lengths, pattern, enum). The form parses the pending
  draft when one exists (else the served source), mutates the document tree — unknown keys and
  unmanaged attributes survive; comments are not preserved and the page says so — and saves a
  **draft** through the existing preview/diff/apply flow, so the text editor stays the escape
  hatch and applying serves immediately via the Phase 42 hot reload.
- **The instant loop — Migrate now** (roadmap Phase 42, final slice — the phase is complete):
  the Studio migration page's created view gains a confirm-gated **Migrate now** action that
  applies the app's pending Flyway migrations to the dev datasource on demand (same path as
  startup: main set, tenant pools, named per-datasource sets; edit-gated and recorded to the
  audit trail with the applied count reported). Schema &rarr; scaffold &rarr; serve now runs
  end-to-end without a process bounce, and the example app defines the starter
  `app.read`/`app.write` policies so scaffolded slices serve out of the box.
- **The instant loop — dynamic route mounting** (roadmap Phase 42, first slice): applying in
  Studio now serves immediately. The hot reloader diffs the re-read manifest against the running
  routes — brand-new route documents **mount** without a restart, removed ones **un-mount**, kept
  ones rebuild in place — and the apply endpoints (draft apply, bulk apply, scaffold apply; JSON
  API and UI alike) reload as part of the request, so "needs a restart to be served" is gone from
  the route-authoring flow. Every route compiles individually: one broken definition takes only
  itself out, serving a clear 500 (`TQL-CAMEL-3103`) that carries its compile error while every
  neighbor keeps serving; an unparseable route document on disk degrades the same way (the reload
  loads the manifest tolerantly and reports the parse failure per-route) instead of failing the
  whole reload. Each reload re-runs the cross-app route-conflict guard and reports
  `{reloaded, added, removed, failed}` (the `/_tesseraql/studio/reload` endpoint and apply responses carry it).
- **Response shaping** (roadmap Phase 41, final slice — the phase is complete; see
  [docs/response-shaping.md](docs/response-shaping.md)): every `response.json.body` leaf and
  `response.html.model` value is now a core-language **expression** compiled at build time —
  dotted paths behave exactly as before (with a legacy fallback for unparsable leaves), and
  computed fields (`params.qty * params.price`, `upper(trim(...))`) come for free. **`nest:`**
  composes a named child query's rows under each parent row of a body key (grouped by a declared
  `on:` join key, canonical-text key matching, parents copied — `TQL-YAML-1019`). And
  **`statusWhen:`** maps business conditions to HTTP statuses declaratively on both JSON and
  HTML responses (first truthy arm wins; pre-compiled, `TQL-YAML-1020`; each arm's status rides
  into the generated OpenAPI).
- **Declarative pagination** (roadmap Phase 41, first slice; see
  [docs/pagination.md](docs/pagination.md)): a `page:` block on `query-json`/`query-html`
  routes paginates the main query by appending the dialect's clause at execution time — the
  authored 2-way SQL stays plain-tool runnable with no hand-written `LIMIT`
  (`TQL-YAML-1018` warns). Offset strategy owns framework `?page=`/`?size=` parameters
  (bounded by `maxSize`); keyset (`strategy: keyset, by:`) keeps the cursor predicate in the
  SQL while the framework derives `page.next` from the last row. One row beyond the page
  answers `hasNext` without a count; `count: true` adds `totalRows`/`totalPages`. The `page`
  context entry feeds bodies (`meta: page`) and templates; responses automatically carry
  `X-Total-Count` and RFC 8288 `Link` `rel="next"`/`rel="prev"`; a paginated `view: list`
  renders the kit's `hc-pagination` nav preserving search/sort state. Machine-checkable:
  `TQL-YAML-1015..1018` lint, a `page` coverage kind, and the OpenAPI
  `page`/`size`/`after` parameters. `tesseraql scaffold crud` lists paginate out of the box
  (size 50, maxSize 200, counted; the gallery regenerated).

- **Expression-language depth** (roadmap Phase 40, final slice — the phase is complete; see
  [docs/declarative-validation.md](docs/declarative-validation.md)): the core expression
  language — shared by `validate:` rules, `requiredWhen`, `headersWhen` guards, and workflow
  guards — gains decimal-exact arithmetic (`+ - * / %`, `BigDecimal` semantics so
  `qty * price <= budget` is a declarable rule with no float drift; `+` concatenates strings;
  `null` operands propagate), unary minus, and a fixed whitelist of pure functions (`length`,
  `lower`, `upper`, `trim`, `contains`, `startsWith`, `endsWith`, `matches`, `abs`, `round`,
  `floor`, `ceil`, `min`, `max`, `coalesce`). Unknown function names and wrong arities fail at
  parse — and therefore at build/lint — so evaluation still cannot reach outside the whitelist
  (no method calls, no reflection).

- **Input, validation, and path-parameter depth** (roadmap Phase 40, first slice; see
  [docs/declarative-validation.md](docs/declarative-validation.md)): declared inputs gain
  `pattern` (anchored regex, pre-compiled by lint `TQL-YAML-1012`), `minLength`, semantic string
  `format:` validators (`email`/`uuid`/`url`; unknown values are `TQL-YAML-1013` — for
  date/datetime/number fields `format:` remains the parse pattern), and `requiredWhen`
  (conditional requiredness in the core expression language, compiled at build,
  `TQL-YAML-1014`), each rejecting with a stable field-scoped code and localized en/ja message.
  A path parameter declared under `input:` now publishes its coerced, typed value in the
  `path.*` namespace. The declared constraints ride into the generated OpenAPI (`pattern`,
  length/value bounds, `format`, enums) on parameters, bodies, and typed path parameters.

### Fixed

- **`tesseraql lint --app .` no longer crashes on a relative app home.** The linter
  relativized the manifest loader's absolute source paths against the app home as
  given, so the documented relative form threw
  `IllegalArgumentException: 'other' is different type of Path` on any app with
  routes; the app home is now absolutized on entry (the MCP dev-tools and the Maven
  goal ride the same fix).
- **`min`/`max` bounds are decimal-exact.** The bound check compared `number.longValue()`, so
  `max: 5` admitted `5.9` and `min: 0` admitted `-0.9`; bounds are now `BigDecimal`-compared and
  fractional bounds (`min: 0.5`) are declarable. (`spec.json` and OpenAPI emit the same numbers
  as before for integer bounds.)
- **`head.yml`/`options.yml` route files fail lint with a clear code** (`TQL-YAML-1011`) instead
  of exploding deep in the route compiler with `Unsupported HTTP method`.

- **Declarative dashboards** (roadmap Phase 39, slice 4 — the phase is complete; see
  [docs/declarative-views.md](docs/declarative-views.md)): a `view: dashboard` document renders
  query-backed panels on the kit's `hc-grid` — a `stat` (single value), a `sparkline`, a `chart`
  (bar or line, rendered server-side as deterministic inline SVG wearing the kit's `hc-chart`
  skin: every color a `--hc-chart-*` token, the gridline group colored by the kit's
  `[aria-label$=grid]` rule, tooltips via `<title>`, no client scripting, CSP-clean), or an
  embedded `table` — each over the route's main `sql` or a named query (`TQL-VIEW-3308` when a
  panel source is undeclared). The example gallery gains a stats dashboard
  (`examples/user-admin-app/web/users/board/stats`). No upstream component brief was needed:
  `hc-chart` and `hc-grid` already ship in Hypermedia Components as CSS-only components.

- **Scaffold on views** (roadmap Phase 39, slice 3; see
  [docs/declarative-views.md](docs/declarative-views.md)): `tesseraql scaffold crud` now emits
  declarative view documents instead of hand-written templates — one list route renders through
  the `tql/view/list` pattern (live search box, server-driven sortable headers re-rendered over
  htmx via `hx-select` on the route itself — the separate `fragments/table` route is gone), the
  create/edit forms derive their fields from the command routes' `input:` blocks, and a shared
  `frags.html` carries the slot fragments (New button, back link, and the confirmed delete the
  edit view mounts in its footer slot). The list pattern grew the composition to make that
  possible: `search:` (the filter box, `TQL-VIEW-3309` when the input is undeclared),
  `sortable: true` columns (`?sort=&dir=` header links + `aria-sort`, applied by the route's
  enum-gated inputs, `TQL-VIEW-3310`), `text:`/`link:` action columns, per-record form `action:`
  placeholder resolution, camelCase→snake_case prefill fallback (plus per-field `column:`),
  number-input `step`, and not-found empty states. `tesseraql new` now also generates
  `config/menu.yml`, so scaffolded pages navigate through the server-rendered app menu. The
  example gallery regenerated on views (byte-identical dogfood check unchanged).

### Changed

- The `tql/view/table` pattern's contract is now `table(tableId, columns, rows)` (the id anchors
  the htmx sort/search swap region), and non-sortable header labels render inside a `<span>`.


- **Declarative views: detail, relations, slots, and eject** (roadmap Phase 39, slice 2; see
  [docs/declarative-views.md](docs/declarative-views.md)). A `view: detail` renders a labelled
  value list over the route's row and composes the route's named `queries:` underneath as child
  tables (`children:`, each with the list column model; `TQL-VIEW-3308` when a source is not a
  named query). Views gain **named slots** (customization ladder L1): `header`/`footer` on every
  kind plus `actions` beside a form's submit button, each filled by an app fragment referenced as
  `template::fragment` (`TQL-VIEW-3306` for an unknown slot name). The datagrid markup moved into
  a shared overridable `tql/view/table` pattern used by lists and detail children. And the
  ladder's L3 shipped: `tesseraql scaffold eject-view --route web/…/get.yml` renders the view's
  pattern once into a checksum-stamped, hand-owned template and flips the route from `view:` to
  `template:` (a list/detail must pin explicit `columns:`/`fields:` first). The example gallery's
  board gains a header slot, per-row links, and a detail page with a groups child
  (`examples/user-admin-app/web/users/board/{name}`).
- **Declarative views** (roadmap Phase 39, slice 1; see
  [docs/declarative-views.md](docs/declarative-views.md)): a `kind: view` document colocated
  with its route (`*.view.yml`) and referenced by `response.html.view` renders the page through
  framework-shipped Hypermedia Components patterns — no hand-written template. A `view: list`
  renders an `hc-datagrid` over the route's rows (columns derived from the result set when
  `columns:` is omitted; per-row `link:` templates); a `view: form` derives its fields from the
  `action:` route's `input:` block, so the rendered HTML constraints (`required`, `maxlength`,
  `min`/`max`, enum options) are the same declarations the server enforces. Customization is a
  ladder: view-document keys (L0), pattern overrides — an app shadows `tql/view/{list,form,field}.html`
  by shipping the same-named file under `templates/` (L2, resolved app-home-first) or retargets one
  view via `template:` — and ejecting to a hand-owned template (L3). Machine-checkable:
  the `TQL-VIEW-33xx` lint family (unresolved/duplicated references, unknown view kind, action
  route without inputs, undeclared fields, unknown widgets, override fragment signatures) and a
  `view` coverage kind (`coverage.thresholds.view`). The example gallery gains a view-backed
  board page (`examples/user-admin-app/web/users/board`).

## 0.4.1 - 2026-06-20

### Fixed

- Release: the **Windows app-image launcher now writes to the console**. The jpackage build omitted
  `--win-console`, so `tesseraql.exe` was a GUI-subsystem binary that ran but printed nothing when
  invoked from cmd/PowerShell — the tool looked unresponsive. It is now a console launcher, and the
  CI smoke test asserts the launcher produces stdout so the regression cannot recur. Affects the
  `tesseraql-<version>-windows-*.zip` app-image (first shipped in 0.4.0); the
  `tesseraql-cli-*-dist.zip` console launcher was unaffected.

## 0.4.0 - 2026-06-20

### Added

- Admin console **browser-session login**, switchable to **OIDC or SAML** by config alone. The
  bundled UIs (Studio, Operations console, IAM Admin) now sign in through a login page
  (`GET /_tesseraql/login`, served by a bundled `auth-ui` app) rather than a hand-minted token —
  opening a protected page with no session redirects there. Password, OIDC, and SAML all create the
  same `tesseraql_sid` session, so enabling `tesseraql.oidc.enabled` / `tesseraql.saml.enabled`
  switches the method with no per-route change; `tesseraql.console.login.password.enabled: false`
  runs SSO-only. State-changing actions are CSRF-protected. See [authentication.md](docs/authentication.md).
- Auth: the page a user originally opened is threaded through every login method as a sanitized,
  same-origin `next` (password redirect, OIDC via a short-lived cookie, SAML via RelayState), so SSO
  returns to the requested page. A single open-redirect guard rejects off-site targets.
- CLI: `serve --embedded-db` now **prints the connection URL and port**, and **`--embedded-db-port`**
  pins the embedded PostgreSQL to a fixed (localhost-only) port so a local client can attach.
- Studio: a public **`/_tesseraql/studio` → `/_tesseraql/studio/ui` redirect**, so the bare,
  documented path resolves instead of 404ing.
- CLI: a passive **"a newer release is available" notice** (Phase 38 Tier 1). On run the CLI prints a
  one-line hint to stderr when a published GitHub release is newer than the running version. The check
  is cached per user (`~/.tesseraql/update-check.properties`, refreshed at most once a day on a daemon
  thread), so it adds no latency to a command, never touches the network on the hot path, and fails
  silent when offline. Opt out with `TESSERAQL_NO_UPDATE_NOTIFIER=1`; it is also skipped automatically
  whenever `CI` is set. See [roadmap.md](docs/roadmap.md) Phase 38.
- Release: the per-OS **jpackage app-image** (a launcher with a bundled JVM — no JRE prerequisite) is
  now attached to each GitHub release as `tesseraql-<version>-<os>-<arch>.{tar.gz,zip}`, instead of
  only being kept as a time-limited CI artifact. A stable download for users without a JRE
  (Phase 38 Tier 1).

### Changed

- The bundled admin UIs now authenticate by **browser session (`auth: browser`)** instead of
  `auth: bearer`. The hand-built Studio JSON API under `/_tesseraql/studio/*` stays `auth: bearer`
  for programmatic callers; MCP is a separate transport and unaffected.

### Fixed

- **`serve --embedded-db` no longer crashes building the manifest checksum index** when the data
  directory lives inside the app home: the index walk hashed PostgreSQL's live data files, which the
  running `postgres` holds OS locks on (a hard failure on Windows; non-deterministic hash elsewhere).
  Any PostgreSQL data directory (recognized by its `PG_VERSION` marker) is now pruned from the walk.

## 0.3.1 - 2026-06-20

### Fixed

- CLI: **`serve --embedded-db` no longer crashes with
  `NoClassDefFoundError: org.postgresql.ds.PGSimpleDataSource`** (#178). The embedded-db supervisor
  (zonky `EmbeddedPostgres`) loads the PostgreSQL JDBC driver at runtime to verify the embedded
  process is ready, but the CLI dist fat jar was missing it: `tesseraql-cli` re-declared
  `org.postgresql:postgresql` directly at `test` scope, which overrode the `compile`-scoped driver
  it otherwise inherits transitively from `tesseraql-camel-runtime`, excluding it from the runtime
  classpath and the shaded jar. The driver is now declared explicitly at compile scope so the dist
  bundles it.

## 0.3.0 - 2026-06-19

### Changed

- UI: **adopted Hypermedia Components 0.1.6**, retiring three local stand-ins for the kit's new
  auto-installed behaviors (the upstream issues this project filed, now shipped). The share-URL Copy
  buttons use **`data-hc-copy`** (`installCopy`, #270) instead of the `tesseraql.js` `[data-copy]`
  handler; the route/table "On this page" navs are an **`hc-toc`** with **`data-hc-spy`** scrollspy
  (`installSpy`, #271), so the current section's link is highlighted (`aria-current="location"`); and
  the shell sidebar opts into **`data-hc-nav-current`** (`installNavCurrent`, #272) for active-link
  marking instead of the `tesseraql.js` `aria-current` script. All three are CSP-clean (declarative
  markup, behaviors from the same-origin bundle) — `tesseraql.js` shrinks to just the htmx
  error-swap wiring and the live-editor SQL grammar.

### Added

- 2-way SQL: **embedded variables** (`/*# template *​/`, Doma-style). A `{placeholder}` in the
  template is interpolated into the SQL *text* at render time (not bound as `?`), for an
  identifier-position fragment a bind cannot drive — a dynamic `ORDER BY` column, sort direction, or
  table name. The whole fragment lives in the comment, so the statement stays runnable in a plain SQL
  tool. Because the value is written into SQL text it must be safe: the linter requires every
  placeholder to resolve to an `enum`-constrained input (`TQL-SQL-2109`), and the renderer rejects a
  resolved value carrying SQL meta-characters (`TQL-SQL-2108`) as defense in depth. See
  [transactional-writes.md](docs/transactional-writes.md#embedded-variables-dynamic-identifiers).
- Scaffolding: the **CRUD list datagrid is sortable** — every column header sorts server-side. Each
  header links to `fragments/table?sort=<col>&dir=<asc|desc>`, swapped in over htmx (the search box
  carries the current sort and vice-versa via `hx-include`), and `aria-sort` drives the kit's sort
  arrow — CSP-clean, no inline JS (`hc-datagrid` expects server-driven sort: its JS only sets
  `aria-sort`, never reordering rows). The generated `search.sql` orders by a single embedded variable
  `/*# order by t.{sort} {dir}, t.<pk> *​/` — the whole clause lives in the comment, so the file stays
  runnable in a plain SQL tool, with the primary key as a stable pagination tiebreaker — and the
  `sort`/`dir` inputs are `enum` allowlists with defaults, so an interpolated value can only be a known
  column or direction (no injection; enforced by `TQL-SQL-2109`).

### Changed

- Scaffolding: the **CRUD list table now renders as a Hypermedia Components `hc-datagrid`** instead
  of a plain `hc-table`. The generated `web/<table>/fragments/table/table.html` wraps the rows in the
  kit's datagrid (`hc-datagrid__scroll` → `hc-datagrid__table` with `__head`/`__headcell`/`__body`/
  `__row`/`__cell`), so wide scaffolded tables scroll horizontally with the header in view and pick up
  the kit's grid styling — degrading to a plain styled grid with no JavaScript (CSP-clean, no inline
  JS). Markup-only: the route, search SQL, and live-search wiring are unchanged. The dogfooded example
  gallery (`examples/scaffold-demo-app`) is regenerated to match.

### Added

- Studio: **search polish and a SQL-builder doc fix** (platform-UX track H8, completing the track).
  The docs search lifts its query operators out of the placeholder into a visible hint
  (`status:passing|failing`, `coverage:covered|untested`) and the results fragment now leads with a
  result count. The standalone SQL builder's intro is corrected to the actual bind style — a directive
  names a bind (`/* id */`) resolved against the route's `sql.params`, each binding from `params` —
  instead of the stale `/* params.id */` / "values from body" copy left over before the bind-style
  fix. This completes Track H (Studio platform UX, H1–H8).

- Studio: **clearer identity-provider setup wizards** (platform-UX track H7). The SAML/OIDC/SCIM/
  identity-realm wizards threw jargon (ACS URL, NameID, OID attributes, SCIM outbound, realm type) at
  the user with no explanation, and the index gave no "which one, in what order?" guidance. The wizard
  index now describes each wizard and says to start with the identity realm; the jargony fields carry
  concise inline help (what the field is, where it's registered, when it applies).

- Studio: **Copy buttons on the share-URL fields** (platform-UX track H6). The read-only share-link
  inputs on the route, table, and coverage pages forced a manual select+copy. Each now has a **Copy**
  button driven by a small `[data-copy]` behavior in `tesseraql.js` (copies the named field's value
  via the Clipboard API and flips its label to "Copied" briefly). Copy needs JS and the strict CSP
  forbids inline handlers, so it lives in the shared app bootstrap — a candidate to upstream into the
  hc kit. A harmless no-op where the Clipboard API is unavailable.

- Studio: **a live filter on the audit trail and the drafts list** (platform-UX track H5). Both were
  dense tables with no way to narrow them (audit grows unbounded). Each now carries the explorer's
  live-filter pattern — an htmx filter input that re-selects a swappable `#…-table` region. The audit
  filter searches **server-side over the whole log** before the newest-200 window applies (so it
  reaches older actions), and the window cap is now stated rather than silent; the drafts filter
  narrows its list in the view. (`StudioService.auditEntries(limit, query)`,
  `StudioViews.audit/drafts(…, query)`, a `q` input on both routes.)

- Studio: **breadcrumbs and an "On this page" jump nav on the detail pages** (platform-UX track H4).
  The route reference (8+ sections) and the table reference were long scrolls with no in-page
  wayfinding. Each now carries a breadcrumb in the header (Docs › ‹id› / Schema › ‹table›) and a jump
  nav of native `#anchor` links to each present section (the sections gained `id`s; the jump links
  share each section's condition, so only real anchors are offered). Pure HTML anchors — CSP-safe,
  no JS or CSS.

- Studio: **the source editor's secondary tools are collapsible panels** (platform-UX track H3). The
  editor stacked 9+ always-open panels in one card, so the page overwhelmed and Save/Apply/Discard sat
  far below the preview output. Each tool — Rendered preview, Compare, Dry-run, Tests, SQL builder —
  is now a uniform `<details class="hc-disclosure">` panel (Rendered preview open as the primary
  feedback; the on-demand tools collapsed), so the page is compact and the primary actions are within
  reach. Native `<details>`, so CSP-safe with no JS or CSS.

- Studio: **a Studio section sidebar nav** (platform-UX track H1). Studio pages used the shell's
  `page(...)` form, which renders only the 3-app system nav, so the Studio sections were reachable
  only via the explorer's header link cluster. A new `tql/shell :: studio-page(...)` form mounts a
  `studio-nav` sidebar (Explorer, Docs, Coverage, Schema, Export, Scaffold, Migration, SQL builder,
  Drafts, Audit, Wizards, then the system apps); the 20 authenticated `studio/ui/**` pages adopt it
  (the public share views keep the plain `page(...)`). `tesseraql.js` highlights the current section
  via `aria-current`. The explorer header drops its now-duplicated link cluster.
- Studio: **loading indicators on every async action** (platform-UX track H2). No template used
  `hx-indicator`/`aria-busy`, so a slow database call (live render, dry-run, run-tests, scaffold,
  migration build, SQL builder, search) gave no "working" cue and read as a hang. A reusable
  `tql/shell :: busy(label)` fragment renders an htmx-native `htmx-indicator` (announced via
  `role="status"`), and each submit form disables its button (`hx-disabled-elt`) while the request is
  in flight. CSP already allows `style-src 'unsafe-inline'`, so htmx's injected indicator style
  applies with no custom CSS or JS.
- Studio: the 2-way SQL builder is available inline in the source editor (follow-on). When editing a
  route SQL file (`web/**/*.sql`), the editor offers a **SQL builder** panel — the same table /
  operation / filter-column controls as the standalone page — whose **Append to editor** button drops
  the generated snippet straight into the editor's textarea (htmx appends it, so existing content is
  kept), instead of having to copy it from the standalone page. `StudioViews.source` flags a route SQL
  file (`isRouteSql`) and the `studio.source` provider populates its table dropdown from the schema
  overlay; the panel reuses the existing build/columns endpoints.

- Studio: IN-list and optional (`/*%if*/`) filters in the 2-way SQL builder, and a corrected,
  self-documenting bind style. The SQL builder gains **select by column (in list)** —
  `where <col> in /* <col> */ (<dummy>)` — and **select by column (optional)** —
  `where 1 = 1 /*%if <col> != null */ and <col> = /* <col> */ <dummy> /*%end*/`, the common
  optional-search-filter pattern. The generated binds now reference the **param name** the route's
  `sql.params` maps (`/* id */`, resolved against `sql.params` at render — the runtime renders 2-way
  SQL against the resolved binds, not the request namespaces), rather than a request expression, and
  every snippet is **prefixed with a `-- sql.params` comment** listing the mapping each bind needs
  (each from `params.<name>` — the coerced declared inputs, matching the `scaffold crud` convention)
  so the snippet is complete and correct. `SqlBuilder` adds the new operations and the param-mapping
  prefix.

- Studio: a by-column filter in the 2-way SQL builder (follow-on). The SQL builder gains a
  **select-by-column** operation and a **Filter column** dropdown that is cascade-loaded from the
  selected table's columns (htmx, on table change) — so you can generate
  `select … from <t> where <col> = /* <col> */ <dummy>` filtering on any column, not just the
  primary key, with the bind typed from the column. New `studio.sqlBuilder.columns` cascade provider
  and `/_tesseraql/studio/ui/sql-builder/columns` fragment; `SqlBuilder.generate` gains the filter
  column.

- Studio: a 2-way SQL builder (Studio backlog: schema-driven authoring). A new **SQL builder** page
  (linked from the explorer when editable) generates a route's `select`/`insert`/`update`/`delete`
  **2-way SQL** for an introspected table and operation — with the bind directives written for you
  (`/* id */ 0`) so the template stays runnable in a plain SQL tool — to copy into a route's
  `.sql` file. It is schema-driven: the projected/inserted/updated columns and the `where` key come
  from the table's introspected columns and primary key (identity columns are skipped on insert), and
  each bind's dummy literal is typed from the column (`0` for a number, `false` for a boolean, `'x'`
  otherwise). Binds map from `params.<name>` (the coerced declared inputs). New pure `SqlBuilder`
  and `DocService.tableByName`; the `studio.sqlBuilder.new` / `studio.sqlBuilder.build` providers and
  `/_tesseraql/studio/ui/sql-builder` page.

- Studio: generate a migration from the schema diff (Studio backlog: migration authoring, final
  slice). When a schema **baseline** sidecar is present (`.tesseraql/docs/schema.baseline.json` — copy
  a captured `schema.json` there), the New migration page can **generate the migration DDL** that
  transforms the baseline into the current schema, dropping it into the DDL field — to capture changes
  made directly to a database back into a migration so the schema stays reproducible. A new
  `SchemaDiff` engine compares the two introspected catalogs: a table or column present only in the
  current schema becomes a real `CREATE TABLE` / `ALTER TABLE … ADD COLUMN`, while a destructive
  change (a table/column removed, or a column type changed) is emitted only as a commented-out line to
  review — additive-and-safe, never applied automatically. New `DocService.schemaDiffDdl`; the
  `studio.migration.diff` provider and `/_tesseraql/studio/ui/migration/diff` route.

- Studio: a create-table builder in the migration DDL builder (Studio backlog: migration authoring,
  follow-on). The New migration page's DDL builder gains a **Create table** form: a table name, a
  **columns** textarea (one column definition per line — `name type [modifiers]`, emitted verbatim so
  you can write `not null`/`default …` inline), and an optional comma-separated **primary key**. It
  generates `CREATE TABLE <t> (<defs>[, PRIMARY KEY (<pk>)]);` and drops it into the DDL field. The
  one-definition-per-line textarea handles a variable column count in plain HTML (no per-row fields).
  New `MigrationDdl.createTable`; a `create-table` case in the `studio.migration.build` provider.

- Studio: the migration DDL builder's table and column inputs are populated from the schema portal
  (Studio backlog: migration authoring, follow-on). The builder's **Table** field is now a dropdown
  of the introspected tables (from the `schema.json` overlay), and the create-index **Columns** field
  autocompletes from the chosen table's columns — loaded by an htmx cascade when the table changes —
  so you pick from what exists instead of retyping names. The add-column **Type** field offers a
  datalist of common SQL types. All of it degrades to plain free-text fields when no schema overlay
  is present. New `DocService.tableNames` / `columnNames`; the `studio.migration.columns` cascade
  provider and `/_tesseraql/studio/ui/migration/columns` fragment route.

- Studio: form-driven DDL builder on the New migration page (Studio backlog: migration authoring,
  slice 3). A **DDL builder** helper generates standard DDL for two common operations from structured
  form input — **add column** (`ALTER TABLE … ADD COLUMN … [DEFAULT …] [NOT NULL]`) and **create
  index** (`CREATE [UNIQUE] INDEX … ON … (…)`, with a conventional `<table>_<cols>_idx` default name)
  — and drops it into the migration's DDL field to review and refine before creating the migration,
  so you don't hand-write the syntax. A forgiving helper (it trims input and rejects only an empty
  required field or an embedded `;`), not a validator — the result is shown in the editor. New pure
  `MigrationDdl` (`addColumn`/`createIndex`); the `studio.migration.build` provider and
  `/_tesseraql/studio/ui/migration/build` fragment route.

- Studio: dry-run a migration's DDL before it lands (Studio backlog: migration authoring, slice 2).
  A migration file's source editor now offers a **Dry-run** action that runs the DDL — the live
  editor buffer — against the dev datasource inside a **sandboxed, auto-rollback** transaction, so it
  applies and then rolls back without persisting, surfacing "applies cleanly" or the database error
  before the next migrate. **Postgres only**: its DDL is transactional and rolls back cleanly, whereas
  MySQL/Oracle/SQL Server auto-commit DDL, so a dry-run there is declined with a clear note. Gated
  like the test runner (`tesseraql.studio.testRunner.enabled`) and reusing the same `SandboxDataSource`.
  `StudioService.dryRunMigration` / `DdlDryRun` / `isMigrationPath`; `StudioTestService.dryRunDdl`;
  the `studio.migration.dryRun` provider and `/_tesseraql/studio/ui/dry-run` fragment route.

- Studio: author Flyway migrations from the editor (Studio backlog: migration authoring). A new
  **New migration** page (linked from the explorer when editable) creates a migration under
  `db/…/migration` — a **versioned** one auto-numbered `V<n>` (plain sequential, no zero-padding; the
  framework orders versions numerically so `V2` precedes `V10`) or a **repeatable** `R__<name>` for
  views/functions. It targets a chosen datasource and optional vendor overlay, writes the DDL through
  the same gated, audited write path as scaffolding (read-only master switch + per-role
  `editRoles` + the audit trail), refuses to overwrite an existing file unless forced, and links the
  result to the source editor. The new file needs a restart + migrate to be applied (the running app
  only lists it); Flyway has no built-in undo on the free edition, so the UI notes that rollback is
  fix-forward (write a follow-up migration). `StudioService.createMigration` / `nextMigrationVersion`;
  `studio.migration.new` / `studio.migration.create` providers; the `/_tesseraql/studio/ui/migration`
  page.

- Studio: multi-binding live render preview (Studio backlog category 3). The route render panel's
  **Use live data** toggle now runs not only a route's main `sql` but **every named `query`** through
  the sandbox, injecting each result under its model name — so a `query-html`/`query-json` route whose
  template/body references `<query>.rows` previews over real data, not just `sql.rows`. The queries run
  in authored order against an accreting context (a later query may read an earlier one's result),
  matching the runtime. `StudioService.RowSource` now returns the results keyed by model name;
  `StudioTestService.liveRows` runs the main query plus each named query. (Command `steps` — writes —
  are still not previewed live.) The declarative **Run tests** action already covered every binding.

- Coverage regression gate (Studio backlog category 3). Beyond the existing **absolute** coverage
  gate, the build can now fail when SQL coverage **drops against the previous run** — the guard that
  catches a change quietly lowering coverage while every absolute threshold still passes. The
  `report` goal compares this run's aggregate SQL line/branch coverage to the most recent
  `history.json` entry (captured before this run is appended) and, with
  `tesseraql.failOnCoverageRegression` set, fails the build if it dropped by more than
  `tesseraql.coverageRegressionTolerance` percentage points (default 0); a regression is always
  logged as a warning. `tesseraql test --report --fail-on-regression [--regression-tolerance N]`
  exits `2` for the same. New `CoverageRegression` (tesseraql-coverage-core) and `ReportRegression`
  (tesseraql-report). For a meaningful baseline, `history.json` must persist across runs (committed
  or CI-cached).

- Docs portal: API spec diff / changelog on the Export page (Studio backlog). When an OpenAPI
  **baseline** sidecar is present (`.tesseraql/docs/openapi.baseline.json` — copy a released
  `openapi.json` there), the Export page shows **what changed** in the API since that baseline:
  operations **added**, **removed**, or **changed**, and for a changed operation what about it changed
  (parameters added/removed/required/typed, request body, responses, security). A new canonical
  `OpenApiDiff` engine (`tesseraql-yaml`) diffs the current generated OpenAPI against the baseline by
  HTTP method and path (so a route re-ordering is not a change), deterministically; added/changed
  entries link to their route page. Off until a baseline is captured; a corrupt baseline degrades to
  a note. `DocService.apiChangelog`; `DocViews.export` gains the changelog projection.

- Docs portal: SQL&rarr;table dependency graph on the route page (Studio backlog, v3.1 deferred
  slice). A route's reference now shows the **tables its SQL reads from and writes to**, inferred
  from the bound 2-way SQL by a new dependency-free extractor (`SqlTableReferences` in
  `tesseraql-core`) — `FROM`/`JOIN`/`USING` are reads, `INSERT INTO`/`UPDATE`/`DELETE FROM`/
  `MERGE INTO` are writes — skipping comments, directives, string literals, CTE names, and
  derived-table subqueries. A read/write table that the `schema` goal introspected into the schema
  portal cross-links to its table page; an un-introspected one stays plain text. It is a best-effort
  navigation aid, not an execution fact, and is computed live from the spec (no `spec.json` change).
  `DocService.tableLinks`; `DocViews.route` gains the data-dependency projection. The schema **table**
  page now shows the reverse: a **Used by routes** card listing the routes whose SQL reads from and
  writes to that table, each linking back to its route reference — so the dependency graph is
  navigable both ways. Built once as a cached reverse index over every route's bound SQL
  (`DocService.routesForTable`); the public shared-table view deliberately omits it.

- MCP: application-declared prompts (`kind: prompt`). An app can now declare its own MCP **prompt**
  under `mcp/` — the application-side counterpart of the dev-tool `studio_copilot` prompt — as a
  parameterized message template the runtime serves at `/_tesseraql/mcp` alongside its tools,
  resources, and UI resources. A `kind: prompt` document declares its `input:` (the prompt's
  arguments) and a colocated `template` (rendered in Thymeleaf TEXT mode against the supplied
  argument values); `prompts/list` advertises it and `prompts/get` returns the rendered text as a
  `user` message. A prompt is pure text — no recipe, no SQL, no embedded LLM — so it carries no
  per-prompt security beyond the endpoint's own auth. New `PromptFile` + `AppManifest.prompts()`;
  `ManifestLoader` parses the new kind; `AppMcpServer` registers each prompt and renders its template.

- OpenAPI: structured JSON response schemas. The generated OpenAPI document (the `generate` goal /
  the docs portal export) now describes a JSON route's response **shape** instead of an opaque
  `{type: object}`: `OpenApiGenerator` mirrors the `response.json.body` template's object/array
  structure with property names, and types each leaf source expression by convention — `…rows` is a
  row array, a row count is an integer, and a `params.X` leaf takes the declared type of input `X`.
  Unclassifiable leaves stay an open schema, and the output remains deterministic (sorted property
  keys), so client generators and Swagger UI get a real response model.
- Docs portal: signed share links for schema tables and the coverage dashboard (Studio backlog F8
  slice 3, extended). The opt-in `tesseraql.docs.share.secret` sharing that route pages had now also
  covers a **schema table** page and the **coverage** dashboard: an authenticated user gets a Share
  card with a signed, expiring link that opens that one page **read-only without signing in**. The
  HMAC now binds a **per-kind label** (route / table / coverage) plus the page's identity and expiry,
  so a link of one kind can't be replayed as another. The public coverage view withholds the
  per-test failure detail; the public table view drops the bearer-gated navigation links. New public
  `auth: public` routes `/_tesseraql/docs/share/table` and `/.../share/coverage`; `ShareLinks`
  generalized to `mintTable`/`mintCoverage` (+ verify); `DocViews.shareTable`/`shareCoverage`.

- Studio editor: confirm-the-diff-before-every-apply (Studio backlog D5 follow-up). A new opt-in
  `tesseraql.studio.confirmApply` flag makes the editor acknowledge the compare-panel diff before
  **every** draft apply, not only when there's a concurrent-edit conflict. When on, the source page
  shows a `required` "I reviewed the diff" checkbox next to Apply, and the UI apply route rejects an
  unacknowledged apply (`STUDIO-4223 → 422`); a conflict's existing force checkbox counts as the
  acknowledgment. The gate is UI-only — the programmatic JSON and MCP apply paths are unaffected
  (they have no human diff to review). Runtime `StudioAccess.requireConfirm` / `confirmApply()`.

- Studio copilot: the MCP "describe → draft → preview → apply" loop (Studio backlog G). The
  protocol core (`tesseraql-mcp`) gains the third MCP primitive — **prompts** (`prompts/list` /
  `prompts/get`, advertised in `initialize` only when registered) via a new `McpPrompt` /
  `McpPromptResult` model — and the dev-tool MCP server (`tesseraql mcp`) offers a `studio_copilot`
  prompt (write mode only) that turns a plain-language `task` (and optional `table`) into guidance
  steering the connecting agent's model through the existing tools: orient (`manifest_summary` /
  `source_read`), draft (`scaffold_crud` / `draft_save`), verify (`draft_preview` / `lint` /
  `test`), then `draft_apply`. This is "describe" without an embedded model — TesseraQL ships the
  workflow, the agent's own model does the reasoning, and each step stays a separately-gated tool
  call, so the copilot adds no LLM dependency, API key, or new privilege (honoring the roadmap's
  decision point 4: the MCP loop, not an in-app model, is the AI surface).

- Docs portal: longer-term coverage trends (Studio backlog F9). The run-history ring that feeds the
  coverage dashboard's trend sparklines is no longer fixed at 20 runs — a non-positive
  `tesseraql.historyLimit` (Maven `report` goal) / `--history-limit` (`tesseraql test --report`) now
  keeps the **full history**, so the trend can span far more than the former cap. The trend panel
  shows its depth (the run count and the retained date span) instead of a hard-coded "last 20 runs"
  note. `ReportHistory.append` treats a non-positive cap as unbounded; `DocViews.trend` adds the
  date span.

- Docs portal: opt-in signed shareable links (Studio backlog F8, slice 3, completing F8). A route's
  documentation is bearer-only by default; when the operator configures a signing secret
  (`tesseraql.docs.share.secret`, with an optional `tesseraql.docs.share.ttl` lifetime, default 7
  days), an authenticated user gets a **Share** card on a route page with a signed, expiring link that
  opens that one route's **read-only contract** — method/path/recipe, inputs, security summary,
  validations, notifications, response shape — **without signing in**. The link carries an
  HMAC-SHA256 signature over the route id and expiry, so it cannot be retargeted or extended; the
  public `auth: public` share route verifies the signature (constant-time) and the expiry before
  rendering, and shows an "invalid or has expired" notice otherwise — nothing leaks. The public view
  deliberately omits the bound SQL, tests, and coverage (implementation internals), and the signing
  secret is dedicated (not the JWT key) so docs sharing and request authentication rotate
  independently. Sharing stays off until the secret is set. New runtime `ShareLinks`;
  `DocViews.share`; the `docs.share` provider and the `/_tesseraql/docs/share/route` route.
- Docs portal: printable route catalog (Studio backlog F8, slice 2). The Export page gains a
  **Printable route catalog** view that renders the app's routes (id, method, path, recipe, covering
  tests) to a PDF table through the **canonical PDF codec** — the same `FileCodecs.discover()` path
  the export routes use, via its built-in grid (no template) — shown inline in a preview frame with a
  `routes.pdf` download link. Studio stays free of the optional `tesseraql-pdf` stack: the runtime
  renders the PDF and the page degrades to a clear note when the module is absent (the editor's PDF
  preview pattern). `DocService.routeCatalog`; `DocViews.routesPdf`; the `docs.routesPdf` provider and
  the `/ui/docs/export/pdf` route (CSP allows the `data:` preview frame).
- Docs portal: export the API specs (Studio backlog F8, slice 1). The documentation portal gains an
  **Export** page (linked from the docs chrome) that serves the app's **OpenAPI 3** document and its
  **htmx interaction contract** as downloadable JSON, generated live from the route manifest by the
  same canonical generators the `generate` build goal uses — so the portal downloads are byte-identical
  to the build's `openapi.json` / `htmx-contract.json` artifacts (no reimplementation). The download
  endpoints (`/_tesseraql/studio/ui/docs/export/openapi`, `/.../export/htmx`) stream the spec with a
  `Content-Disposition` attachment via the standard `response.file` recipe and stay bearer-gated like
  the rest of the portal, so the URLs can be shared with API tooling that carries the same token.
- Studio editor: richer syntax tokens and a 2-way SQL live grammar (Studio backlog E, completing it;
  hc 0.1.5 / #264). The server-side read-only highlighters now emit hc 0.1.5's new semantic tokens —
  YAML mapping keys as `property`, HTML element names as `tag`, plain attributes as `attribute`
  (Thymeleaf/htmx/`data-` directives stay `meta`) — so the read-only/diff views read correctly and
  match the live overlay's built-in grammars. And a consumer `tql-sql` grammar (registered through
  hc's new `registerCodeLanguage`, mirroring the server `SqlHighlighter`) gives the editable SQL field
  live highlighting that classifies 2-way SQL block-comment directives (`/*%if … */`, binds) as
  `meta` — which a generic SQL grammar can't — so the editor matches the read-only view for 2-way SQL
  too. Editable `.sql` fields use `data-lang="tql-sql"`.
- Studio editor: live syntax highlighting of the editable field (Studio backlog E; adopts Hypermedia
  Components 0.1.5 / hc #264). The editable `hc-code` source and sample fields now opt into hc's
  `installCodeEditor` `data-lang` overlay — a synced, CSP-safe highlight layer behind the textarea
  that re-tokenizes as you type and reuses the same `hc-code__tok` palette as the read-only/diff
  views, so the editor matches them. The grammar is chosen by file type (`sql`/`yaml`/`html`/`json`
  built-in grammars); the bundled hc WebJar is bumped 0.1.4 → 0.1.5. Degrades cleanly to a plain
  textarea with no JS or an unknown type.

- Studio editor: PDF preview for export routes (Studio backlog A1 follow-up). A `query-export`
  `format: pdf` route now renders an actual PDF in the editor's rendered-preview panel — the route's
  print template is converted to PDF from the sample's `sql.rows` and shown in an `<iframe>` (a
  `data:` URL) with a download link, so print layout (`@page`, fonts, pagination) can be checked
  without running an export. It reuses the canonical PDF codec, so the preview matches a real export;
  Studio stays free of the heavy, optional `tesseraql-pdf` stack — the runtime supplies the renderer
  through a new `StudioService.PdfRender` callback (the A1 live-rows/`FieldMask` pattern) and degrades
  to a clear message when the `tesseraql-pdf` module is not on the classpath. The source/render CSP
  gains `data:` in `frame-src` for the embedded PDF.
- Studio editor: output-field masking in the JSON rendered preview (Studio backlog A1 follow-up). A
  `query-json` route's `response.json.fields` policy is now applied to the rendered preview, so the
  preview shows what a caller would actually see — fields hidden (`visible: false` / a `policy:` the
  viewer fails) or redacted (`mask`/`classification`) just as in production. It reuses the canonical
  `FieldPolicyApplier`, evaluated for the sample principal the developer can put under `principal`
  (e.g. `permissions`/`roles`) in the render sample, so a privileged view can be previewed too;
  policy-gated fields default to hidden for an anonymous sample. Studio stays free of the
  security/compiler stack — the runtime supplies the mask through a `StudioService.FieldMask`
  callback (the same pattern as the A1 live-rows `RowSource`).
- Studio editor: per-role edit permission (Studio backlog D6). The all-or-nothing
  `tesseraql.studio.readOnly` switch is refined by an optional `tesseraql.studio.editRoles`
  allow-list: when set (and Studio is writable), only a caller holding one of those roles may mutate
  (save/apply a draft, discard, create a route, apply a scaffold) — every mutating endpoint and UI
  action answers `403` for everyone else, and the explorer/source pages render the read-only view
  (no edit chrome) for them. With no allow-list, any authenticated caller may edit a writable Studio,
  as before. The decision is per-caller from `principal.roles` (a new runtime `StudioAccess` gate),
  with the database-free `StudioService` still enforcing the master read-only switch as defense in
  depth.
- Studio editor: audit trail (Studio backlog D6). Every source-writing action — applying a draft and
  applying a scaffold — is now stamped to an append-only `work/studio/audit/audit.jsonl` log with
  **who** (the authenticated caller's login id), **what** (apply / scaffold), the **target** (the
  applied path or scaffolded table), and **when**. A new **audit** page (linked from the explorer when
  Studio is writable) lists the trail newest-first, with applied paths linking back to their editor.
  Database-free `StudioService.applyDraft(path, force, actor)` / `scaffoldApply(table, force, actor)`
  record the entry at the single point each write happens (so no caller path can bypass it) and
  `auditEntries(limit)` reads it; the `studio.audit` provider and `GET /_tesseraql/studio/audit`
  endpoint expose it. The Studio routes bind the actor from `principal.loginId`.
- Studio editor: pending-draft overview (Studio backlog D5). A new **drafts** page (linked from the
  explorer when Studio is writable) lists every unsaved draft under `work/studio/drafts` with a link
  to its editor, whether it is a new file or an edit, and whether it conflicts with a source that
  changed underneath it — so edits in flight, and any that need attention, are visible in one place.
  Database-free `StudioService.drafts()`; the `studio.drafts` provider and `GET
  /_tesseraql/studio/drafts` endpoint.
- Studio editor: concurrent-edit conflict detection (Studio backlog D5). Saving a draft now records
  the source it is based on, so applying detects when the source changed underneath it and refuses to
  silently overwrite the other change (no more last-apply-wins): the editor shows a conflict warning
  and requires an explicit **overwrite** confirmation, and the apply endpoint answers `409 Conflict`
  unless `force` is set. Database-free `StudioService.draftConflicts` + `applyDraft(path, force)` (the
  base is a sidecar beside the draft); the `studio.apply` provider and `POST
  /_tesseraql/studio/apply` endpoint take `force`; the source page carries the warning and a
  review-gated force checkbox.
- Studio editor: directory tree and filter in the explorer (Studio backlog C4). The flat route/job
  tables become a single **directory tree** folded from the source paths (folders as nested
  disclosures, each route/job a leaf linking to its source, with a method/`job` badge), and a
  **filter** box narrows it live as you type — a case-insensitive match over each entry's id, source
  path, recipe, and (for a route) HTTP method and URL path, which prunes the tree to matching
  branches. The filter re-renders server-side via htmx (`hx-get` the explorer with `hx-select` on the
  tree), so it needs no bespoke client JS. Database-free `StudioService.explorer(query)`; the tree is
  built in `StudioViews`; the `studio.explorer` provider and `GET /_tesseraql/studio/explorer?q=…`
  endpoint take the query.
- Studio editor: scaffold a table's CRUD slice from the explorer (Studio backlog B3). A new
  **scaffold** page lists the dev datasource's tables (introspected live with the same
  `CatalogIntrospector` the documentation portal's schema view uses); choosing a table **previews**
  the full CRUD slice the generator would produce — list, detail, and edit pages, 2-way SQL, and a
  declarative test suite — reusing the very `TableIntrospector` + `CrudScaffolder` the CLI `scaffold
  crud` command does, so the generated files are byte-identical to the command line. Each previewed
  file is shown syntax-highlighted with the disposition an apply would give it
  (`new`/`unchanged`/`regenerate`/`conflict`). **Create these files** then writes the slice into the
  app home through the scaffold's edit-detection contract (a pristine generated file is regenerated, a
  file you edited or own is skipped and reported unless you force it), and reports which newly written
  routes need a restart to be served (the hot reloader only swaps existing routes). Gated: available
  only when Studio is writable and `tesseraql.studio.scaffold.enabled` is set. Studio stays
  database-free — a new runtime `StudioScaffoldService` owns the introspection and hands the
  `TableSchema` to the database-free `StudioService.scaffoldPreview`/`scaffoldApply`. Backed by the
  `studio.scaffold.tables`/`studio.scaffold.preview`/`studio.scaffold.apply` providers, the `GET
  /_tesseraql/studio/scaffold/tables` + `POST /_tesseraql/studio/scaffold/preview` + `POST
  /_tesseraql/studio/scaffold/apply` JSON endpoints, and the `/_tesseraql/studio/ui/scaffold` page.
  Ties to milestone M7 ("schema → verified CRUD in ten minutes"). See
  [docs/studio-backlog.md](docs/studio-backlog.md).
- Studio editor: create a new route from the explorer (Studio backlog B3). A **New route** form on
  the explorer (when Studio is writable) takes a `web/**/<method>.yml` path and a recipe
  (`query-json`/`query-html`/`command-json`) and saves a parseable starter skeleton as a draft, then
  opens it in the source editor to finish — so creation reuses the existing validate → apply flow
  (the new route needs a restart to be served). Database-free `StudioService.newRouteDraft`; the
  `studio.newRoute` provider and the `/_tesseraql/studio/ui/new` route.
- Studio editor: run a route's or job's declarative tests from the editor (Studio backlog A2). A
  route or job source page gains a **Run tests** action that runs every declarative test case kind
  covering it — `sql` queries **and writes** (an `INSERT … RETURNING` runs and is rolled back),
  `validate` rules (their SQL runs against the sandbox), `contract` cases (through a sandboxed
  identity service built over the same datasources), and the pure, no-DB `notify` and `http-call`
  evaluations — against the dev datasource and shows inline pass/fail with the failure message — no
  edit → apply → restart → CI loop. Gated and sandboxed: enabled only when
  Studio is writable and `tesseraql.studio.testRunner.enabled` is set; every case runs through a
  `SandboxDataSource` — an auto-rollback transaction (commits suppressed, rolled back on close) with
  a statement timeout (`tesseraql.studio.testRunner.queryTimeoutSeconds`, default 5) and a row cap
  (`tesseraql.studio.testRunner.maxRows`, default 1000) — so a case can neither run away nor persist
  a write. Backed by a new
  `StudioTestService` + `SandboxDataSource` reusing the declarative `TestRunner`, the
  `studio.runTests` provider, the `POST /_tesseraql/studio/runTests` JSON endpoint, and the
  `/_tesseraql/studio/ui/run-tests` editor fragment. See
  [docs/studio-backlog.md](docs/studio-backlog.md).
- Studio editor: live data in the rendered preview (Studio backlog A1 "real bound params" × the A2
  sandbox). The route render panel gains a **Use live data** toggle (when the test runner is
  enabled): instead of a hand-authored `sql.rows` fixture, the preview runs the route's main `sql`
  query through the same `SandboxDataSource` (bind params resolved from the sample's `params`/
  `query`) and injects the real `rows`/`rowCount` — so editing a route previews the actual page/JSON
  over live dev data. Studio stays database-free via a `StudioService.RowSource` callback the runtime
  fills with `StudioTestService.liveRows`; `live` flows through the `studio.render` provider, `POST
  /_tesseraql/studio/render`, and the `/_tesseraql/studio/ui/render` fragment.

- Studio editor: rendered preview against sample data (Studio backlog A1). A renderable file
  opened in the editor now renders against a sample model — not just the empty-model "parses" check
  `preview()` gave — and shows the actual output two ways: the generated HTML/text/JSON, hc-code
  syntax-highlighted, and (for HTML) a sandboxed `iframe` visual preview styled with the Hypermedia
  Components stylesheet. Two shapes render: a **template file** (`.html`/`.tpl`) against the sample
  as its template variables, and a **web route** (`web/**/<method>.yml`) against the sample as the
  execution context (`params`, `sql.rows`, …) — a `query-html`/`page` route resolves
  `response.html.model` and renders the route's template; a `query-json` route resolves
  `response.json.body` and pretty-prints it (output-field masking `response.json.fields` is not
  applied in preview). The sample is a YAML/JSON map typed in the editor and prefilled from a
  colocated `<name>.sample.yml` fixture when present (a blank sample falls back to that fixture).
  Backed by `StudioService.render`/`sampleModel`, the `studio.render` provider, the
  `POST /_tesseraql/studio/render` JSON endpoint, and the `/_tesseraql/studio/ui/render` editor
  fragment; the source page CSP gains `frame-src 'self'` to admit the sandboxed preview frame. The
  `.sample.yml` fixture lives beside its file and is ignored by the route loader (only HTTP-method
  `*.yml` files under `web/` are routes). See [docs/studio-backlog.md](docs/studio-backlog.md).
- `tesseraql serve --embedded-db [<data-dir>]` runs a tqlapp with no external database: the CLI
  starts an embedded PostgreSQL and points the runtime's `main` datasource at it. With no directory
  the data is ephemeral; with one it persists across restarts (a single-server option). Because it
  is a real `postgres`, the framework migrations and `ensureSchema` bootstrap run unchanged — no
  new dialect. The platform binary is resolved on demand through the same embedded resolver as
  `tesseraql.modules` (pinned via `zonky.postgres.binaries.version`), so the fat jar is not bloated.
  See [docs/getting-started.md](docs/getting-started.md).

### Changed

- Studio: **the route catalog is a sortable `hc-datagrid`** (platform-UX track I). The docs route
  table sorts by any column (id / method / path / recipe / tests / coverage) — server-driven: each
  header is a link that re-requests sorted, the server sets `aria-sort` on the active column, and the
  kit renders the sort arrow from it. No JS and no `hx-vals='js:'` — it works under the strict CSP
  because the arrow is pure CSS keyed off `aria-sort` and the datagrid's click handler does not
  `preventDefault` a header link. `DocViews.index(…, sort, dir)` does the sort.
- Studio: **the schema table list is a sortable `hc-datagrid`** (platform-UX track I) — each
  datasource's tables sort by name / type / columns / FKs, with the same CSP-clean server-driven
  pattern as the route catalog (`DocViews.schema(…, sort, dir)`).
- Studio: **the audit trail is sortable** (platform-UX track I), composing the new column sort with
  the existing filter and pagination: a sort header link carries the filter `q` and resets the page,
  a page link carries `q` + the sort, and the filter input keeps the sort across an htmx re-filter
  via a static-JSON `hx-vals` (CSP-clean). The whole filtered log is sorted before paging
  (`StudioService.auditPage(query, sort, dir, page, size)`).
- Studio: **the audit trail is paginated with `hc-pagination`** (platform-UX track I). H5 capped the
  page at the newest 200 entries; the whole log is now navigable in 50-entry pages (newest first) via
  an `hc-pagination` nav of plain styled links (no JS, CSP-clean). The H5 filter still searches the
  whole log and composes with paging. `StudioService.auditPage(query, page, size)` returns the page
  slice plus the filtered total.
- Studio: **the route reference's test-result detail uses `hc-tooltip`** instead of a `title=`
  tooltip (platform-UX track I). The pass/fail badge carried its failure message in `title=`, which
  screen-reader and keyboard users can't reach; it now references a sibling `.hc-tooltip` via
  `aria-describedby` (shown on hover + keyboard focus, dismissible with Escape).
- Studio: **adopt the kit's `hc-spinner` and `hc-breadcrumb`** instead of the hand-rolled equivalents
  (platform-UX track I). The shared loading affordance (`tql/shell :: busy`) now renders the CSS-only,
  reduced-motion-aware `hc-spinner` with a contextual label rather than a bare "Working…" text fade,
  and the route/table breadcrumbs use the semantic `hc-breadcrumb` (CSS-injected separators) instead
  of a hand-built cluster with a literal `›`. Both components already ship in Hypermedia Components
  0.1.5 — they were missed earlier because they are CSS-only (absent from the behaviors bundle), so
  no version bump is needed.
- Upgraded Testcontainers 1.20.4 → 2.0.5 (docker-java 3.4.0 → 3.7.1). docker-java 3.4.0 could not
  validate Docker Engine 29's raised API floor (`MinAPIVersion` 1.40), so every Testcontainers IT
  failed with "Could not find a valid Docker environment" on hosts running Docker 29 (e.g. the dev
  container); docker-java 3.7.1 negotiates correctly. The 2.0 module artifacts gained a
  `testcontainers-` prefix and the JDBC container classes moved to per-database packages and dropped
  their self-type generics, so the test deps and imports were migrated accordingly
  (`org.testcontainers.containers.PostgreSQLContainer<>` → `org.testcontainers.postgresql.PostgreSQLContainer`,
  and likewise for MySQL/SQL Server). Test-only change.

## 0.2.0 - 2026-06-16

### Distribution and onboarding

Building an application on TesseraQL no longer requires cloning the framework monorepo
(see [docs/app-developer-distribution.md](docs/app-developer-distribution.md) and
[docs/getting-started.md](docs/getting-started.md)):

- Shared `tesseraql-apptasks` library (`AppPackager`/`AppMigrator`/`IdentityBootstrap`) so the
  Maven plugin and the CLI are thin adapters over one engine.
- CLI command parity with the Maven goals: `lint`, `test` (`--report`), `coverage`, `generate`,
  `schema`, `governance`, `migrate` (apply/info/validate/repair), `identity-schema`, `package`,
  `verify`, plus `modules` (the opt-in driver/codec resolver).
- Embedded module resolver: `tesseraql.modules` plus a committed `modules.lock` (declarative,
  reproducible), resolved via an embedded Maven resolver that honors `~/.m2/settings.xml`.
- Artifacts published to GitHub Packages on release; the BOM version-manages the opt-in JDBC
  drivers (`ojdbc11`, `mssql-jdbc`, `mysql-connector-j`).
- Installable CLI distribution: a self-contained fat jar with `bin/tesseraql`(`.cmd`) launchers
  (`-Pdist`) and per-OS jpackage app images (Linux x64, Windows x64, macOS arm64).
- Scaffold (`tesseraql new`) emits a wrapper POM + the Maven Wrapper, `compose.yaml`, Studio
  config, and a README.
- Proxy / restricted-network support: outbound `HttpClient`s honor the JVM proxy; the CLI bridges
  `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY`; internal mirrors and TLS-intercepting proxies are
  documented in [docs/proxy.md](docs/proxy.md).
- The framework version is single-sourced (`io.tesseraql.core.TesseraqlVersion`).

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

- Attachments and object storage — scanning and retention (roadmap Phase 30 slice 3, completing the
  phase, see [docs/attachments.md](docs/attachments.md)): a malware scan-hook SPI and age-based
  retention. `AttachmentScanner` (`tesseraql-core` `io.tesseraql.core.scan`, ServiceLoader-discovered
  via `AttachmentScanners.discover()`, no-op default) is the seam for ClamAV or a cloud scanner — an
  app enables real scanning by adding a scanner module, no config flag. Scanning is synchronous on
  upload: the verdict is recorded as `scan_status`, an `INFECTED` object is never served (the
  download gate refuses any non-clean object with `409`, `TQL-LD-2848`) and is kept or removed per
  `tesseraql.attachments.scan.onInfected` (`quarantine` default / `delete`), and a scanner `ERROR`
  fails the upload closed (`503`, `TQL-LD-2847`). Retention wires into the ch. 44 `RetentionSweeper`:
  when `tesseraql.retention.attachments` is set and the managed store is bound, the cluster-safe
  sweep also deletes attachment metadata past the window and reclaims each blob (best-effort, so a
  racing node or an already-removed blob is harmless). Orphan GC (a blob with no metadata row) is a
  later refinement — it needs a `BlobStore` listing capability the minimal SPI does not yet expose;
  the upload path's best-effort delete-on-failure covers the common case. This completes the
  attachments leg of Milestone M9.
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
