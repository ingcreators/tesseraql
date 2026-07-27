# Credential Throttle

Design document. [framework-surface-parity.md](framework-surface-parity.md)'s long tail
names the last substantive security gap: login and reset carry no rate limiting, and the
shipped limiters cannot be pointed at them — `RateLimiter` and `ClusterRateLimiter` hold
**one bucket per route**, so attaching one to `/login` trades an online-guessing weakness
for a trivial full lockout of every user. What credential surfaces need is a *keyed*
throttle. This document designs it.

## Scope: the surfaces where the framework verifies a credential

The throttle guards the six framework endpoints that accept something guessable:

| Surface | Endpoint | What is guessed | Keys |
| --- | --- | --- | --- |
| `login` | `POST /_tesseraql/login` | password, TOTP code (same POST) | login + address |
| `reset` | `POST /_tesseraql/reset` | nothing — the threat is mail-bombing | login + address, **all requests** |
| `confirm` | `POST /_tesseraql/reset/confirm` | recovery token | address |
| `invite` | `POST /_tesseraql/invite` | invite token | address |
| `oidc` | OIDC callback | nothing (see below) | address, failures |
| `saml` | SAML ACS | nothing (see below) | address, failures |

**SAML/OIDC are deliberately secondary.** The password behind an SSO login is typed at
and verified by the IdP; brute-force protection for that credential is the IdP's duty and
capability, and throttling our callback would not slow guessing at all. What the callback
and ACS *do* expose is cheap garbage: state mismatches and signature-validation failures
— the latter computationally non-trivial — so failures there ride the address key as
inexpensive insurance, with the honest note that the real volumetric defense is the edge.

**Flow-start flooding is out of scope, for the NAT reason.** `GET /oidc/login` writes a
pending state row per hit (TTL-pruned, so bounded in time). Throttling starts per address
would re-import exactly the false positive the failures-only rule below avoids: the 9am
rush is an office full of people behind one NAT starting SSO *successfully*. TTL pruning
plus edge volumetric control own this.

## Decisions

### 1. Two keys, failures only, fixed windows, never a lockout

- **Per-login is the primary control**: keyed on the *submitted* login id (trimmed,
  lower-cased), applied **before any existence check or hash computation** — a
  nonexistent account throttles identically to a real one, so the throttle is not an
  enumeration oracle, and a throttled request never pays the hashing cost.
- **Per-address is secondary**: the first `X-Forwarded-For` entry, else the peer
  (the `ClientInfo.of` resolution). Behind an edge the peer is the edge for everyone, so
  XFF-first is mandatory; an attacker who can spoof XFF can rotate this key, which is
  why per-login is the one that must hold. The asymmetry is accepted and recorded.
- **Failures only** (except `reset`, where every request counts — issuing mail *is* the
  cost): the morning login rush behind one NAT is a burst of successes and must not
  throttle. A success clears the login key, so nine mistakes followed by the right
  password leave nothing smoldering.
- **No lockout, ever.** An account lock is a denial-of-service device anyone can aim by
  knowing a login id. Windows expire; nothing needs an operator to unlock.

### 2. `CredentialThrottle` is keyed, bounded, node-local

One class in `tesseraql-security` (no Camel dependency): two bounded maps
(`key → window start + failure count`, 50k cap with oldest-window eviction, expired
windows pruned on write — the session-store idiom), constructed with the config and the
runtime `Meter`. API: `retryAfter(surface, loginKey, addressKey)` returning the wait when
throttled (and counting `tesseraql.credential.throttled{surface,key}`),
`recordFailure(...)`, `recordSuccess(loginKey)`.

**Node-local, honestly.** Behind a round-robin balancer the budget multiplies by the
node count. With failures-only counting and the defaults below that degradation is
accepted; a shared-store keyed window (the `JdbcRateLeaseStore` shape, keyed) is the
recorded follow-up if a deployment needs cluster-exact budgets.

### 3. On by default, generously

`tesseraql.security.credentialThrottle`: `enabled: true`,
`loginAttempts: 10` / `loginWindow: 15m`, `addressAttempts: 100` /
`addressWindow: 15m`. Unlike the idle timeout (a visible scaffolded default, because it
can cost a legitimate user their form), a failure throttle at these values costs a
legitimate user nothing — and anti-automation on credential endpoints is a baseline
control, not a posture choice. CHANGELOG records the behavior change.

### 4. The response reveals the throttle, not the account

- Browser form POST: `303 → /_tesseraql/login?error=rate` — the login page renders "Too
  many attempts — wait a few minutes and try again", without a countdown.
- API callers: **429**, the `TQL-RATE-4292` envelope, and a `Retry-After` header.
- **`reset` keeps its neutral answer even when throttled** — a 429 there would itself be
  an oracle ("this account is under attack"); the throttle silently suppresses issuing.

### 5. Wiring follows the bean pattern

The runtime constructs the throttle from config and binds it
(`TesseraqlProperties.CREDENTIAL_THROTTLE_BEAN`); `LoginRouteBuilder` and
`RecoveryRouteBuilder` take it by constructor, the OIDC/SAML extensions resolve it via
`ExtensionContext.bean(...)` exactly as they resolve the session store. No new routes, so
`FrameworkSurfaces` is untouched.

## Out of scope

- A shared-store (cluster-exact) keyed window — recorded follow-up.
- CAPTCHA / proof-of-work escalation tiers, device cookies.
- Flow-start throttling for SSO (above), and any throttle on session-authenticated
  surfaces (TOTP enrollment confirm guesses nothing an attacker with a session lacks).
- Account lockout, in any form.

## Testing

- `CredentialThrottleTest` (unit): window expiry recovers; success clears only the login
  key; keys are independent; eviction at the cap; `attempts: 1` boundary; disabled is
  inert.
- `CredentialThrottleIntegrationTest` (own runtime, tiny limits): N failed logins → the
  browser form bounces to `error=rate` and the API answer is 429 + `Retry-After` +
  `TQL-RATE-4292`; **the right password is also refused while throttled** (proof the
  check precedes verification); the window expiring readmits; another login id is
  unaffected; a throttled `reset` still answers its neutral "sent" while issuing stops;
  garbage POSTs at the SAML ACS and forged callbacks at OIDC hit the address budget,
  while repeated *successful* SSO flow starts never throttle (the rush, reproduced).
- Existing ITs that deliberately fail credentials (TOTP, recovery, invite, the SAML
  suites) declare `credentialThrottle.enabled: false` in their fixtures — the visible
  disable, rather than tests that pass while quietly consuming budget. The OIDC login IT
  keeps the default budgets (its config appends to a copied base whose `security:` block
  a second one would duplicate); its handful of deliberate failures sits far under them.
- security-hardening.md's anti-automation row, threat-model.md, authentication.md, the
  error-code reference (4292), and framework-surface-parity.md slice 7 all update with
  the implementation.
