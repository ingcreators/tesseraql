# Session Administration

Design document. Session visibility and revocation exist only self-service: the account
app lists the caller's own sessions and offers "sign out other sessions"
(`SessionStore.sessionsFor` / `invalidateOthersFor`, Phase 36 follow-up). An operator
has nothing: no view of who holds live sessions, no way to end another subject's
sessions, and — the sharpest edge — **disabling a user in IAM Admin does not end their
sessions**. A disabled account keeps browsing until its cookies expire.

## Decisions

### 1. Disable invalidates, unconditionally

`IdentityService`'s disable path ends every session of the disabled subject
(`invalidateOthersFor(subject, null)` — with no session to keep, that is "all"; the
default implementation walks `sessionsFor` and invalidates each). This is not a UI
feature but a correctness fix: an account whose access was revoked is not "disabled at
next login", it is disabled. CHANGELOG entry; no opt-out.

### 2. Sessions live on the IAM Admin user page, not the ops console

Session administration is user-centric — the question is "who is this person and where
are they signed in", not "what is the system doing" — so the surface is the existing
IAM Admin user detail page (`/_tesseraql/admin/users/{id}`): an *Active sessions* panel
(created time, per `ActiveSession`'s fields) with one *Sign out everywhere* action.
Per-session revocation is deliberately not offered: the operator-shaped decision is
"end this account's access now", and one button does that honestly. The ops console
links nowhere new; a global sessions-by-subject listing is a follow-up if operating
practice asks for it.

### 3. Same write discipline as IAM Admin's existing actions

The action is a plain POST route in the iam-admin app under its existing write policy
(`iam.admin.write`), CSRF via the app's defaults, 303 + flash. The service provider
re-derives nothing new — it acts on the page's subject. Route audit
(`tesseraql.audit.routes.enabled`) covers it like every mounted route.

## Out of scope

- Rotate-in-place for the *caller's own* session id (needs response-directive cookie
  plumbing; tracked separately since Phase 36).
- Per-session revoke, session geography/user-agent enrichment, idle-timeout policy.
- A cross-subject sessions dashboard in the ops console.

## Testing

- IAM Admin IT: the user page lists the subject's sessions; *Sign out everywhere* ends
  them (a request with the old cookie is unauthenticated afterwards); disabling a user
  ends their sessions the same way; both refused without `iam.admin.write`.
