# Session Visibility

Design document. [session-administration.md](session-administration.md) put session
administration on the IAM Admin user page and deliberately stopped at one button: *Sign
out everywhere*. Its out-of-scope list — per-session revocation, user-agent/address
enrichment, idle-timeout policy, a cross-subject sessions view — is now asked for by
operating practice, and the pieces belong together: per-session revocation is meaningless
until a session can be *identified* (which device? since when? still active?), and a
cross-subject view is only worth a page once its rows carry those same facts.

## Decisions

### 1. Session metadata lives and dies with the session row

`tql_session` gains four columns (`V3__session_metadata.sql`, all three vendor
directories): `session_handle` (a UUID that is *not* the cookie id), `user_agent`
(truncated to 255), `remote_addr`, and `last_seen_at`. `ActiveSession` grows to
`(subject, handle, createdAt, expiresAt, lastSeenAt, userAgent, remoteAddr)` — the cookie
id stays internal for keep-checks and, per the established contract, never reaches a
template. Pre-upgrade rows keep null metadata: listed with dashes, aging out at expiry.
No retention question arises — when the session row goes, its metadata goes.

### 2. Per-session revocation names a handle, never the cookie id

The handle exists so a row can be *acted on* without the page ever holding the credential
that is the session id. An HMAC of the id was considered and rejected: the JDBC store is
shared across nodes, and a per-node key breaks display-on-A / revoke-on-B. The store
gains `invalidateByHandle(subject, handle)` — **subject-scoped**, so a leaked or guessed
handle cannot name another subject's session; the admin page's authority stays in its
route policy, not in the handle.

### 3. The address is recorded as presented; geography is not resolved

Login records the first `X-Forwarded-For` entry when present, else the peer address —
**informational**, and documented as such: an edge that does not strip inbound XFF lets a
client spoof it ([threat-model.md](threat-model.md) gains the line). GeoIP resolution is
deliberately not bundled: shipping and updating a geography database is a distribution
and licensing burden the framework should not carry, and "an unfamiliar device/address"
— the operational question — is answered by UA + address alone. If geography is ever
wanted, it enters as a resolver SPI, not a bundled database.

### 4. Idle timeout slides inside the absolute TTL

New config `tesseraql.sessions.idleTimeout` (unset = disabled, existing behavior
unchanged). A session is invalid when `now > lastSeen + idle` **or** `now > expiresAt` —
the absolute TTL stays as the ceiling. The auth path touches `last_seen_at` when it
resolves a browser session, throttled to once per 60 seconds per session, because the
naive version turns every request into an UPDATE on the shared store. A lost throttled
touch costs at most 60 seconds of staleness in the "last active" column and slightly
early idle expiry — recorded here as the accepted trade.

### 5. The cross-subject page is IAM Admin's, not the ops console's

session-administration.md's stance holds: sessions are user-centric. The new page is
`/_tesseraql/admin/sessions` (a *Sessions* entry in the iam-admin nav): every active
session — subject (linking to `/admin/users/{id}`; the subject *is* the user id),
created, last active, user agent, address — with a subject prefix filter and a per-row
*Sign out* (`iam.admin.write`, confirm-gated). The page reads `ops`-style live state, so
it lists and refuses exactly what the store holds right now. The ops console links
nowhere new.

### 6. The existing panels get the same facts and the same row action

The account app's self-service list and the IAM Admin user-page panel both gain the
metadata columns and a per-row *Sign out* — self-service revocation is the lost-device
case, and the machinery is decision 2 unchanged. *Sign out everywhere* stays on both
surfaces; ending all access with one button remains the operator-shaped default.

### 7. Rotation carries the metadata

`SessionStore.rotate` (docs/session-rotation.md) re-creates the session, so the default
implementation now carries user agent, address, and created-at forward — confirming a
TOTP enrollment must not make a session look freshly created from nowhere. `create`
becomes `create(Principal, ClientInfo)` (login, OIDC, SAML — all three sit on the HTTP
exchange); per rule 10 the old signature is deleted outright, CHANGELOG'd.

## Slices

- **A — foundation**: V3 migration ×3 vendors, both stores (metadata, handle,
  `invalidateByHandle`, `activeSessions(limit)`), `ClientInfo` through the three login
  paths, throttled touch in the auth producer, `idleTimeout` config + enforcement,
  rotation carry-over.
- **B — panels**: account list + IAM Admin user panel render UA / address / last active
  and the per-row *Sign out*.
- **C — the Sessions page**: iam-admin route + template + nav, subject filter, row
  revoke, user-page links.

## Addendum: declared session policy (2026-07-27)

The two posture questions the first cut left open are answered as *declarations with
visible consequences*, not silent framework opinions:

- **`tesseraql.sessions.maxPerSubject`** caps live sessions per account, **evict-oldest**:
  a login beyond the cap ends the subject's oldest session — the newest login wins, the
  same rule the in-memory global ceiling already applies. Reject-new was rejected as a
  default because the person it locks out is the legitimate user whose old cookie is
  stranded on another machine; evict-oldest also pushes a *stolen* session out on the
  next real login. Single-session policy is not a separate feature: it is
  `maxPerSubject: 1`. Rotation replaces a session in place and never trips the cap.
  Unset stays unlimited — the framework cannot know a deployment's compliance regime,
  but the surfaces built above explain every eviction ("your oldest device was signed
  out").
- **The scaffolded config declares `idleTimeout: 30m`** — the visible-default idiom
  ([config-consumers.md](config-consumers.md)): new apps start with a stated posture the
  author can see and adjust (long-form workloads raise it in place), while existing
  apps' behavior never flips through an upgrade. The framework's own code default stays
  unset through 0.x; whether 1.0 ships a modest built-in default (with the ASVS
  refresh) is recorded as a 1.0 decision, not smuggled in here.

## Recorded races (2026-07-27)

- **The cap race**: two concurrent logins at `maxPerSubject` can each evict the same
  oldest session and both insert — momentarily cap+1, self-healing on the next login
  (the eviction DELETE is idempotent). Accepted: a serializable check would put locks on
  the login path to fix an off-by-one.
- **Rotation is now one transaction** on the JDBC store
  (docs/framework-datasource.md): the original shape had a crash window between
  inserting the new session and deleting the old in which both stayed live.

## Out of scope

- GeoIP resolution (decision 3; SPI seam only if ever asked for).
- Ops console involvement of any kind.
- A framework-level idle-timeout code default before 1.0 (the scaffold declares one;
  see the addendum).
- Reject-new as a cap strategy, and per-route/per-role session policies.

## Testing

- Store tests (both implementations): handle uniqueness and non-derivability from the
  id; metadata round-trip; `invalidateByHandle` refuses a wrong-subject handle; idle
  expiry honors the sliding window and the throttle; rotation carries metadata and
  mints a fresh handle; pre-upgrade null-metadata rows list with dashes.
- IAM Admin IT: the user panel and the Sessions page render the metadata; a row revoke
  ends exactly that session (the subject's other session survives); the filter narrows;
  both refused without `iam.admin.write`; a handle from another subject 404s.
- Account IT: the self-service list shows the caller's devices; revoking one leaves the
  current session working; the revoked cookie is unauthenticated.
