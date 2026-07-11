# Credential lifecycle

The [account surface](account.md) gives the local realm a self-service password
*change* — for users who know their current password. The credential lifecycle
features finish the story around it: a **password reset** for users who do not, an
**invitation** entry point so an account can start from nothing, and an optional
**TOTP second factor** (available wherever password login is; enrollment confirms
before anything enforces). Everything stays JDK-only and rides machinery that already
exists: the identity contract pack, the [notification channels and
outbox](notifications.md), the bundled auth-ui and account apps, and the
managed-table patterns.

Re-inviting a still-INVITED account is a polite resend under the token cooldown; an
already-usable login refuses, so an invite can never take over an account.
Misconfigured recovery fails the boot with `TQL-SEC-4120` — a half configuration must
not produce a reset page that goes nowhere.

## One token store for reset and invitations

`CredentialTokenStore` SPI in core, `JdbcCredentialTokenStore` in operations, over
`tql_credential_token` — outside the Flyway component set, `ensureSchema`-only (the
established pattern):

```sql
create table if not exists tql_credential_token (
  token_hash varchar(64) primary key,   -- SHA-256 of the token; the token itself is never stored
  login_id   varchar(255) not null,
  purpose    varchar(16)  not null,     -- reset | invite
  expires_at timestamp    not null,
  used_at    timestamp,
  created_at timestamp    not null
);
```

- The mailed token is 256 random bits, URL-safe; only its **SHA-256** lands in the row.
- **Single-use** is a check-and-set (`set used_at ... where token_hash = ? and used_at
  is null and expires_at >= now` → row count), so two racing confirms cannot both win.
- Expiries: reset 30 minutes, invite 7 days (both configurable). Issue prunes expired
  rows opportunistically.
- **Cooldown**: a login with an unexpired, unused token of the same purpose is not
  issued another (silently — see anti-enumeration), which also caps mail volume.

## Password reset

```yaml
tesseraql:
  identity:
    recovery:
      enabled: true
      channel: user-mail        # a configured mail channel; fail-fast if missing/not mail
      url: https://app.example.com/_tesseraql/reset/confirm   # absolute; the mailed link base
```

- The login page grows **Forgot password?** (only when enabled). `/_tesseraql/reset`
  (public, auth-ui app) takes a login id; the POST always answers the same neutral
  "if that account can be recovered, a link is on its way" — **whether or not the
  account exists, has an email, or is cooling down**. No enumeration oracle.
- The destination comes from a pack contract,
  **`find-recovery-destination-by-login`** — default: the `tql_users.email` of an
  ACTIVE user. A `sql` realm overrides the contract like any other.
- The mail rides the [outbox](notifications.md) as a NOTIFICATION on the configured
  channel (the `ops.alert` direct-enqueue precedent): at-least-once, retries,
  dead-letters; payload `{resetUrl, loginId, displayName}` feeds the channel template.
- `/_tesseraql/reset/confirm?token=…` (public) shows the new-password form; the POST
  verifies + consumes the token, writes the hash through the existing
  `update-password` contract, **invalidates every session of that subject**, and lands
  on the login page with a success flag. Used/expired/unknown tokens all render the
  same honest "this link is no longer valid".

## Invitations

The bundled IAM admin (`/_tesseraql/admin/users`) has **Invite user**: login id,
display name, email, roles. The provider runs the existing `create-user` contract with
**status `INVITED`** — no credential columns at all, and `find-credential-by-login`
already refuses any status but ACTIVE, so an invited account **cannot sign in** until
accepted — then issues an invite token and enqueues the mail (same machinery, purpose
`invite`, `tesseraql.identity.invite.url` as the link base).

`/_tesseraql/invite?token=…` (public) sets the first password: verify + consume, the
`update-password` contract, then the existing `enable-user` contract flips the status
to ACTIVE. From zero to signed-in without an operator ever knowing a password.

## TOTP second factor

- `Totp` in `tesseraql-security`: RFC 6238 over `javax.crypto.Mac` (HmacSHA1, 6 digits,
  30 s steps, ±1 step window) plus a small Base32 codec — no new dependency.
- Enrollment lives on the account page ([account surface](account.md)): generate a
  secret, show the `otpauth://` URI and the Base32 text (QR rendering is deliberately
  out of scope — JDK-only has no QR; authenticators accept manual entry), and
  **confirm with a valid code** before anything is stored in
  `tql_user_totp(subject, secret, confirmed_at, last_used_step)`. Disabling requires
  the current password (`TQL-ACCOUNT-4804` on mismatch).
- Login: the password form (and the JSON login) gains an optional **code** field. A
  confirmed enrollment makes it required — wrong or missing code fails exactly like a
  wrong password (one neutral message; no "password ok, code wrong" oracle). The
  accepted step is recorded and codes at or before `last_used_step` are refused — a
  captured code cannot replay inside its window.
- Recovery codes are not currently supported: an operator removes the enrollment row
  to restore access (self-service recovery codes are a possible later addition).
  Documented, not implied.

## Security posture

- Tokens: 256-bit, hashed at rest, single-use by check-and-set, short-lived, purpose-
  bound; the confirm pages never echo the token back into markup.
- Anti-enumeration end to end: the reset request answers identically for unknown
  logins, missing emails, and cooldowns; the confirm page answers identically for
  unknown, used, and expired tokens; the TOTP login failure is indistinguishable from
  a wrong password.
- A consumed reset invalidates every session of the subject (the [account
  surface](account.md) session machinery with an empty keep-id).
- The TOTP secret is server-side data in the identity database (like the password
  hashes beside it); the cookbook says so plainly.
