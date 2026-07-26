# Session Rotation In Place

Design document. Since Phase 36 the framework's answer to privilege elevation has been
blunt: a credential change ends every session of the subject and forces a fresh login,
because the service layer cannot set a cookie — only `LoginRouteBuilder` ever mints one.
That is the right call for credential changes and it stays. But
[security-hardening.md](security-hardening.md) carries the residual as *partial*: on a
non-credential elevation — confirming a TOTP enrollment is the concrete case — the
session id should rotate *in place*, and today it cannot, so the pre-elevation id keeps
riding. This document adds the missing plumbing: a response directive, compiled into the
route, that re-issues the session cookie after a successful write.

## Decisions

### 1. `response.session.rotate: true` — a declared, compiled directive

The YAML surface gains one boolean under `response:`. On a route that declares it, the
compiler appends a rotation step after successful execution, before the response
renders. Framework-internal signalling (a reserved key in a provider's result, a
thread-local) was rejected: rotation is a route-level security property exactly like
`csrf:` — declared where an auditor can read it, linted where a machine can, and usable
by any app whose own flows elevate (approve-payment pages have the same shape). Per
[yaml-surface-consumers.md](yaml-surface-consumers.md) the key arrives fully wired or
not at all: model field, compiler behavior, reference entry, editor schema
(`SchemaSyncTest` forces that), and a registry entry when the consumer guard lands.

### 2. Rotation is an auth-component operation, not a new processor family

`tesseraql-auth:rotate` joins `authenticate`/`authorize` on the existing component: it
already resolves the `SessionStore` registry bean, and session mechanics belong in one
place. The operation reads the request's session cookie; when one resolves it calls
`SessionStore.rotate(oldId)` and sets the cookie with login's exact attributes
(`Path=/; HttpOnly; SameSite=Lax` — Secure stays the edge's concern, unchanged). No
session (a bearer or public caller) is a no-op: the directive describes browser
sessions, and a route shared by both callers must not fail for the one without a cookie.

### 3. `SessionStore.rotate` is one atomic-enough default method

`default String rotate(String sessionId)`: read the session, `create(principal)` (a
fresh id *and* a fresh CSRF token — the token is session-bound state and rotates with
it), then invalidate the old id, returning the new id — old-id invalidation happens
before the response leaves, so there is no rotate-later window. Store implementations
with a cheaper primitive can override. A null/unknown id returns null and the operation
no-ops — an expired session mid-flight is the caller's next 401, not a rotation crash.

### 4. First consumers: the account app's elevation writes

The TOTP enrollment *confirm* route declares the directive (enrollment is the elevation:
the session gains a stronger factor). Password change keeps the sign-out-everywhere
stance — rotating a session whose credential just changed would *weaken* the current
behavior. [security-hardening.md](security-hardening.md)'s "partial" row and
[threat-model.md](threat-model.md)'s residual move to covered, each naming the
directive.

## Out of scope

- Rotating on every login-adjacent event (remember-me, re-auth prompts): none exist
  today.
- A `Secure` attribute decision — unchanged, owned by the TLS/HSTS work.
- Idle-timeout policy and per-session revocation (session-administration.md's list).

## Testing

- Compiler test: the directive compiles to the rotate step on success paths only — an
  execution error must not half-rotate (no Set-Cookie on the error path).
- Account IT: confirming a TOTP enrollment answers with a new session cookie; the old
  id is unauthenticated afterwards; the new session's CSRF token differs and the next
  form post with the fresh token succeeds; a bearer call to a rotating route still
  works with no Set-Cookie in the response.
- `BundledAppSecurityPostureTest`/reference regen pick up the new key.
