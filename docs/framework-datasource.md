# Framework Datasource

Design document. The JDBC session store rides the `main` Hikari pool, and so does every
other framework store — which means a long-running business query can starve *login* of
a connection. The obvious fixes are both wrong: a `sessions.datasource` key starts a
key-per-store sprawl (credential tokens, replay guards and audit share the same shape and
the same pain), and a blanket "framework vs business" split silently breaks the
guarantees that only exist *because* certain framework tables share the business
transaction. The line that actually works is **transactional coupling**, and it produces
three buckets.

## The classification

**Bucket 1 — same-transaction coupled: never movable.** These commit inside the business
transaction; moving them destroys the guarantee that is their reason to exist:

- `JdbcOutboxStore` — business SQL, document sequences, and outbox events commit
  atomically on one connection; that atomicity *is* the outbox pattern.
- `JdbcWorkflowStore` / `JdbcWorkflowTaskStore` — a transition commits with its command.
- `JdbcAttachmentStore`, `JdbcDelegationStore`, `JdbcOrgUnitStore`, `JdbcTotpStore`,
  `JdbcInboxStore`, `JdbcEventChannelStore` — business and identity data outright, or
  delivery state consumed inside business flows. Identity tables already have their own
  placement mechanism (per-realm datasources).

**Bucket 2 — integrity-coupled: must live with the data they protect.** Losing or
restoring these independently of business data silently weakens business-write
guarantees:

- `JdbcIdempotencyStore` — a marker lost while the business row survives means the
  retry double-applies.
- `JdbcWebhookReplayStore` — inbound replay protection guards business writes the same
  way.

**Bucket 3 — ambient framework state: movable.** No transactional or integrity coupling
to business writes; loss is inconvenience, never corruption:

`JdbcSessionStore` (everyone signs in again), `JdbcCredentialTokenStore` (outstanding
reset/invite links die), `SamlReplayGuard` and `OidcStateStore` (short-TTL flow state),
`JdbcRateLeaseStore` (ephemeral leases), `JdbcRouteAuditStore` (fire-and-forget
telemetry, already WARN-on-failure), `JdbcPreferenceStore` / `JdbcShortcutStore` (UI
preferences). `JdbcTempStore` stays out: spool placement already has its own mechanism
(`tesseraql.temp.*`).

## Decisions

### 1. One key: `tesseraql.framework.datasource`, default `main`

A named datasource from the ordinary `tesseraql.datasources` map. Bucket 3 honors it;
buckets 1 and 2 are **pinned to the business datasource regardless**, with this document
as the recorded reason — a config key must not be able to break outbox atomicity or
idempotency. An unknown name fails the boot loudly: a typo that silently fell back to
`main` would defeat the isolation someone deliberately configured.

### 2. Pool separation first, database separation when ops asks

The starvation pain is a *pool* phenomenon, not a database phenomenon. Because the key
names a datasource, one mechanism covers both shapes: a second pool at the **same URL**
isolates login from business saturation with zero migration, and a genuinely separate
database is the same one-line config when scale or backup/retention separation calls
for it. The deployment guide recommends starting with same-DB/separate-pool and sizing
it small — session traffic is millisecond point queries; 5–10 connections carry a lot
of logins.

### 3. Extensions learn the same distinction

`ExtensionContext` gains `frameworkDataSource()` beside `dataSource()`: the OIDC state
store and the SAML replay guard construct against it, while SCIM provisioning (identity
data) stays on `dataSource()`. Every store keeps its own re-runnable `ensureSchema` and
its own Flyway history table, so pointing the key at a fresh database bootstraps
bucket 3's schema there on first start.

### 4. The Flyway components split with the buckets — discovered by building it

The runtime's versioned framework migrations run per component. The `security`
component (sessions) is pure bucket 3 and **follows the key**; the `operations`
component stays on the business datasource, because its file set mixes buckets (outbox
and job tables beside rate leases and audit) and its Flyway checksums pin existing
deployments — restructuring the files would fail their history validation. Movable
operations-module stores therefore bootstrap their tables on the framework datasource
through their own idempotent `ensureSchema`, and the Flyway-created copies of those
tables on the business database sit unused when the key is set — cosmetic, and recorded
here rather than discovered in surprise.

### 5. Migration honesty

Switching an existing deployment: every session ends (everyone signs in again),
outstanding reset/invite links die, old audit rows stay in the business database
(readable there, not migrated), replay-guard state resets within its TTL window.
Nothing in business data is touched. These are the recorded costs; none is corruption.

## Session store hardening (bundled)

- **`V4__session_expiry_index.sql`** (three vendors): an index on `expires_at`. The
  login-path prune (`delete … where expires_at < ?`) and the cross-subject listing
  currently scan; on MySQL an unindexed DELETE also locks more than it should.
- **`rotate()` becomes one transaction**: read, insert-new, delete-old on a single
  connection with autocommit off. The previous shape left a crash window between the
  insert and the delete in which both sessions stayed live — the elevation's "old id
  stops working" promise held in-process but not across a crash. Now it holds, period.
- **The cap race, recorded**: two concurrent logins at `maxPerSubject` can each evict
  the same oldest and both insert — momentarily cap+1, self-healing on the next login,
  DELETE being idempotent. Accepted (a serializable check would put locks on the login
  path for an off-by-one), and now written down in
  [session-visibility.md](session-visibility.md).

## Out of scope

- Moving bucket 1 or 2 under any configuration — the point of the classification.
- Per-store datasource overrides (`sessions.datasource` etc.): one key, one line.
- Automatic data migration between datasources on a key change.

## Testing

- `FrameworkDataSourceIntegrationTest`: a runtime with `framework.datasource` naming a
  second database — sessions/tokens/audit tables bootstrap **there** and not in `main`;
  login and an audited business route work; the business tables stay on `main`; a
  runtime naming an unknown datasource refuses to boot with a config error.
- `JdbcSessionStoreIntegrationTest`: rotation behavior unchanged through the
  transactional rewrite (carry-over, cap interplay); `ensureSchema` stays re-runnable
  with V4.
- Existing suites prove the default (`main`) path unchanged.
