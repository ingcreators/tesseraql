# Authentication

TesseraQL routes are deny-by-default: a route is reachable only when it
declares how it authenticates, and authorization policies are evaluated against the resolved
`Principal`. Every authentication method plugs in behind the same authentication step and the same
principal model, so claims, roles, permissions, and tenant resolve identically downstream
regardless of how the caller proved its identity.

```yaml
security:
  auth: bearer        # bearer | api-key | browser | mtls | public
  policy: users.read  # authorization policy evaluated against the principal
```

This page covers every method: **bearer JWT**, **API keys**, **mTLS**, **browser sessions** and
the login page, and **OIDC**; SAML setup has its own configuration surface and is only summarized
here. Browser-session *usage patterns* (forms, CSRF in templates) are in
[hypermedia-ui.md](hypermedia-ui.md).

All JWT and API-key crypto is JDK-only — there is no JOSE/JWT third-party dependency, matching the
SAML module's supply-chain posture.

## A policy resolved from the route's own path

A route addressed to one thing can check that thing's own grant, by interpolating a path
parameter into the policy id:

```yaml
# /_tesseraql/admin/applications/{name}/roles/assign
security:
  policy: tql.iam.write.{path.name}   # checks tql.iam.write.orders under /applications/orders
```

This exists for per-subject delegation — administering one application, not the whole store
(see [access-governance.md](access-governance.md)) — where one fixed policy id cannot express
what the route is actually gating.

The rules are narrow on purpose:

- **Only `{path.<name>}`.** A gate resolves from the addressed resource, never from a query
  string or a body field, which the caller shapes freely. The value is read off the request's
  URL, matched against the route's own template.
- **The name must be one the route's own path declares.** Otherwise it would resolve to nothing
  on every request.
- **Only under the `tql.` mark.** An id under the mark is the synthesized atom check, derived
  from the granted code itself; a declared policy is a fixed name, so an interpolated one would
  name no policy at all.
- **The resolved value must be a single atom segment** — no `.`, `*`, `/`, `%` or whitespace. An
  asterisk would otherwise resolve to a family's terminal wildcard, and a dot would forge a
  neighbouring atom out of the segment the request supplied. A request that resolves to nothing
  usable is refused with `TQL-SEC-4031`, like any other denial.

The first three are reported by the linter and refused at boot as `TQL-YAML-1409`, so a policy
that cannot resolve fails at its source rather than as a puzzling 403 on every request.

A framework atom checked this way is also satisfied by the store-wide grant it narrows, where
one exists, so a route naming the per-application atom still admits the store-wide
administrator without saying so.

## Route security defaults

Routes of the same kind almost always share the same `security:` choices — every `/api/**` route
is `bearer`, every page is `browser` with CSRF on writes. Instead of restating them per route,
declare path-matched defaults once under `tesseraql.security.defaults.routes`:

```yaml
tesseraql:
  security:
    defaults:
      routes:
        - match: /api/**       # first matching rule wins, firewall-style
          auth: bearer
        - match: /**
          auth: browser
          csrf: auto           # required exactly on browser writes (non-GET)
```

Rules are evaluated **in declaration order against the served URL path**, and the first match
contributes defaults, so the effective rule for any path is decidable by reading the list top to
bottom. In a `match` pattern `*` stays within one path segment, `**` crosses segments, and a
trailing `/**` also matches the bare prefix (`/api/**` matches `/api`).

The merge is per key and **route-local keys always win**: a route may declare only its `policy:`
and inherit `auth`/`csrf`, or override everything. Two guardrails keep the merge safe:

- A route whose effective auth is `public` never inherits a `policy` from a rule — public means
  fully open. Under a rule that declares a policy, the combination is flagged
  (`TQL-SEC-4131`) so a deliberately open route is confirmed, not accidental.
- `csrf: auto` resolves to required exactly when the effective auth is `browser` and the method
  is state-changing — bearer/API-key routes never inherit CSRF.

Resolution happens when the app manifest loads: the compiler, linter, coverage, and Studio all
see the fully explicit effective values. A malformed rule fails the load (`TQL-SEC-4132`) rather
than silently defaulting a security control.

`recipe: webhook` routes are exempt: webhook deliveries authenticate by signature (the declared
verifier — the compiler rejects a webhook route without one), so a browser/bearer default would
demand a session no sender has.

Defaults cover the HTTP routes under `web/`. Workflow documents and attachment documents mount
their own HTTP surfaces and keep their explicit `security:` blocks — a document that governs
transitions or files should say so on its face.

### The MCP floor

MCP primitives get their own block rather than a path rule, because they have no path: a tool or a
resource is reached **by name over one shared endpoint**, so a `match:` pattern would have nothing
to match and "which rule applies here?" would have no answer.

```yaml
tesseraql:
  security:
    defaults:
      mcp:
        auth: bearer
        policy: mcp.read
```

One block covers every MCP document, and the same merge rules apply: what the primitive declares
wins, and a primitive whose effective auth is `public` never inherits a policy.

A **write** tool has always needed a policy of its own — `TQL-MCP-4030` refuses one without,
because an agent must not mutate data without authorization. A **read** primitive had no floor at
all, so the answer to "who may read this?" was whoever could reach the endpoint. `TQL-MCP-4261`
warns when nothing at all is in force: neither the document's own `security:`, nor this block. It is
a warning rather than an error because a genuinely public read is a real design — what is worth
saying out loud is the case that is indistinguishable in the YAML from one somebody meant to govern.

## Exchanging a session for a token

A browser session can buy a short-lived bearer token, for callers that have a session but cannot
carry a cookie — CI, scripts, and the assistants that run on the user's own machine:

```yaml
tesseraql:
  security:
    token:
      enabled: true     # off by default
      ttl: 15m
```

`POST /_tesseraql/token` with the session cookie and its CSRF token returns
`{"token": "...", "tokenType": "Bearer", "expiresAt": "..."}`. The claims are the ones the bearer
path already reads, taken from the principal the session already holds, so the token this
application mints is one it verifies itself.

### Getting one

Two paths, and neither involves developer tools.

**The console page.** `/_tesseraql/ops/console/token` issues a token and offers it for copying. It
needs no operations grant: a token carries the caller's own subject, roles and permissions and
nothing else, so anyone who may sign in may mint their own.

**The command line.** `tesseraql token --url <base-url> --login <id>` signs in and exchanges in one
step, printing only the token on stdout so it pipes:

```bash
export TESSERAQL_TOKEN=$(tesseraql token --url https://app.example.com --login alice)
```

The password comes from `--password`, from `TESSERAQL_PASSWORD`, or from a prompt — in that order.
Add `--tenant` for a multi-tenant realm and `--otp` when the account has an authenticator enrolled.
Nothing is cached: no cookie jar, no token file. Claim and lifetime options are refused here rather
than ignored, because the application decides both.

That command works because a JSON login answers with the session's CSRF token:

```json
{"ok": true, "loginId": "alice", "csrfToken": "..."}
```

The exchange endpoint requires that token, and without it in the response a command-line client
could authenticate and then go nowhere. Returning it grants no new capability — the same value
already reaches any authenticated browser through the `<meta name="csrf-token">` tag, and a hostile
page still cannot read a cross-origin response body.

Three things are worth knowing before turning it on.

**It only works where TesseraQL holds the signing key.** An application verifying `HS256` against
`tesseraql.security.jwt.secret` can issue; one verifying `RS256` against a `publicKey` or `jwksUri`
cannot, because there is no private key — its tokens come from the identity provider that holds one.
Enabling it there fails the boot with `TQL-SEC-4146` rather than mounting an endpoint that answers
500 per request.

**It is guarded like a state change**, because it is one: it converts a session cookie into a
credential that carries none of the cookie's protections and outlives it. The CSRF token is required
exactly as the sign-out routes require it, so a page that can make the browser POST cannot mint a
token from a visitor's session.

**Expiry is the revocation story.** A minted token is validated statelessly, so a password change —
which invalidates every session of that subject — does **not** invalidate tokens already minted from
them. They expire. Keep `ttl` short. A credential that needs revoking on demand should be an
[API key](#api-keys), which has a status column and a client registry.

## Bearer JWT

A `bearer` route reads the `Authorization: Bearer <jwt>` header, verifies the signature, validates
the claims, and maps them to a `Principal`. Configure it under `tesseraql.security.jwt`.

### Claim mapping (all algorithms)

```yaml
tesseraql:
  security:
    jwt:
      issuer: https://idp.example.com/   # optional; checked against the `iss` claim
      clockSkew: 60s                     # optional leeway for exp/nbf (default 0)
      rolesClaim: roles                  # claim names; these are the defaults
      permissionsClaim: permissions
      groupsClaim: groups
      tenantClaim: tenant_id
      loginClaim: preferred_username
      nameClaim: name
```

`sub` becomes the principal subject; the mapped claims become its roles, permissions, groups,
tenant, login id, and display name; the full claim set is available to SQL binds as
`principal.claim.<name>`.

### HS256 (shared secret)

The default algorithm. Verifies with an HMAC secret — appropriate when your own service issues the
tokens.

```yaml
tesseraql:
  security:
    jwt:
      algorithm: HS256                       # default; may be omitted
      secret: ${secret.env.JWT_SECRET}
```

A `${secret.env.…}` placeholder resolves the value through the secret provider at use time
instead of inlining it in config — never at startup, never into logs or generated artifacts;
[connectors.md](connectors.md#outbound-policy) describes how these references resolve. The same
form works for every credential setting on this page.

### RS256 with a static public key

Verifies with an RSA public key — appropriate for tokens issued by an external identity provider
when you pin a single signing key. The key may be a PEM `PUBLIC KEY` (SubjectPublicKeyInfo), an
X.509 `CERTIFICATE`, or a JWK / JWK Set in JSON.

```yaml
tesseraql:
  security:
    jwt:
      algorithm: RS256
      issuer: https://idp.example.com/
      publicKey: ${secret.file.idp_signing_pub}   # PEM, certificate, or JWK JSON
```

### RS256 with a JWKS endpoint

Verifies against the key set the identity provider publishes, selecting the key by the token's
`kid` and following key rotation automatically.

```yaml
tesseraql:
  security:
    jwt:
      algorithm: RS256
      issuer: https://idp.example.com/
      jwksUri: https://idp.example.com/.well-known/jwks.json
      jwks:
        cacheTtl: 10m        # how long a fetched key set is trusted before refresh (default 10m)
        refreshFloor: 1m     # min interval between unknown-kid refetches (default 1m)
        requestTimeout: 5s   # JWKS connect/request timeout (default 5s)
```

The JWKS endpoint must be `https` (loopback `http` is allowed for local development), and its
host must be in `tesseraql.http.outbound.allowedHosts`: the fetch leaves through the same
outbound gateway as every other framework-issued call, under the deny-by-default egress
allow-list, the configured timeouts, and the per-host circuit breaker. The fetched
key set is cached for `cacheTtl`. A token whose `kid` is not in the cache — typically a key the IdP
rotated in — triggers **at most one** refetch per `refreshFloor`, so a flood of tokens carrying
random `kid`s cannot become a flood of JWKS requests; an unknown `kid` that survives a permitted
refetch is rejected (fail closed). On a transient JWKS fetch failure the last good key set keeps
serving; if none was ever fetched, authentication fails closed.

### Algorithm confusion is rejected by design

The expected algorithm is bound from configuration, never from the token header. Before any key is
consulted, the token's header `alg` must equal the configured algorithm — so an `alg: none` token,
or an HS256 token presented to an RS256 config (the classic "use the RSA public key as the HMAC
secret" attack), is rejected. The lint enforces the same statically (see below).

## API keys

API keys authenticate **service callers** — machine clients with a small, mostly-static set of
keys. A route opts in with `auth: api-key`:

```yaml
security:
  auth: api-key
  policy: invoices.write
```

The key is presented either in the configured header (default `X-API-Key: <key>`) or as
`Authorization: ApiKey <key>` for gateways that forward only `Authorization`. Clients are declared
in config:

```yaml
tesseraql:
  security:
    apiKeys:
      header: X-API-Key
      clients:
        billing-service:
          secretHash: ${secret.env.BILLING_API_KEY_SHA256}  # hex SHA-256 of the raw key
          subject: svc:billing      # defaults to the client id
          tenantId: tenant-a
          roles: [SERVICE]
          permissions: [invoices:write]
          status: ACTIVE            # ACTIVE (default) | DISABLED
```

Only a **hex SHA-256 of the key is stored** — never the raw key — and is best supplied as a
`${secret.…}` reference. Generate it with, for example, `printf %s "$RAW_KEY" | sha256sum`. The presented key is
hashed and compared in constant time against every active client; the raw key is never stored or
logged. A match resolves to that client's principal — with its tenant bound from the key, not the
request, so a key cannot escalate across tenants — and the route's authorization policy then applies
as for any other caller. No match denies (`401`); an authenticated key that fails the policy is
forbidden (`403`).

## Mutual TLS (client certificates)

mTLS authenticates **service callers** by an X.509 client certificate. The runtime does not
terminate TLS itself; a trusted edge — a reverse proxy, ingress controller, or service-mesh sidecar
(nginx, Envoy/Istio, HAProxy) — terminates TLS, validates the client certificate, and forwards it to
the runtime in a configured header (URL-encoded PEM, the de-facto `ssl_client_escaped_cert`
convention). A route opts in with `auth: mtls`:

```yaml
security:
  auth: mtls
  policy: ledger.write
```

Clients are declared in config, each mapping a certificate identity to an explicit principal:

```yaml
tesseraql:
  security:
    mtls:
      forwardedHeader: ssl-client-cert        # the header the edge forwards the cert in (no default)
      trustBundle: ${secret.file.client_ca}   # optional PEM CA bundle; enables in-app PKIX validation
      clockSkew: 60s                          # leeway for the certificate validity window (default 0)
      clients:
        billing-service:
          subjectDn: "CN=billing-service,O=Acme"   # exactly one matcher (see below)
          subject: svc:billing                # principal subject; defaults to the client id
          tenantId: tenant-a
          roles: [SERVICE]
          permissions: [invoices:write]
          status: ACTIVE                      # ACTIVE (default) | DISABLED
```

Each client declares **exactly one** certificate matcher:

- `subjectDn` — the certificate's subject distinguished name, compared order- and case-insensitively
  over its RDNs (so a CA that orders or cases the DN differently still matches).
- `sanDns`, `sanUri`, `sanEmail`, `sanIp` — a Subject Alternative Name of that specific kind; for
  example `sanUri: spiffe://acme/ns/default/sa/billing` for a SPIFFE identity. A matcher only ever
  compares against names of its own kind, so a certificate carrying `api.internal` as an email or a
  URI can never satisfy `sanDns: api.internal`. DNS names compare case-insensitively (RFC 4343); the
  other kinds compare exactly.
- `sha256` — the hex SHA-256 fingerprint of the DER certificate (colons and case are ignored); the
  strongest binding, pinning one exact certificate.

> **Removed: the untyped `san:`.** It compared its value against every kind of name at once, so a
> certificate carrying the value under a kind you did not mean still authenticated — worst case, a
> DNS name defeating a SPIFFE URI pin. It is an error (`TQL-SEC-4066`) at lint and at startup rather
> than a silent alias, because the failure it caused was precisely a config that kept working while
> meaning something weaker than it read. Replace it with the matching typed key.

The forwarded certificate is parsed (JDK only — there is no third-party PKI dependency), its
validity window checked against `clockSkew`, and its identity matched against the declared clients.
A match resolves to that client's principal — with its tenant bound from the certificate binding,
not the request — and the route's authorization policy then applies as for any other caller. No
match, an expired or malformed certificate, or a missing header denies (`401`); an authenticated
certificate that fails the policy is forbidden (`403`). Unlike an API key, a certificate is public —
possession of the private key was proven during the handshake at the edge — so identity matching is
a lookup, not a secret comparison; the certificate is never logged.

When `trustBundle` is set, the runtime additionally **PKIX-validates** the forwarded certificate
against the configured CA(s) as defense-in-depth, in addition to the edge's own validation
(revocation checking is left to the edge, which is positioned to do CRL/OCSP). Omitting it is allowed
but means the runtime fully trusts the edge's validation — see the lint warning below.

> **Trust contract.** A forwarded certificate header is only trustworthy if callers cannot set it
> themselves: the edge must overwrite (or strip) the `forwardedHeader` on every inbound request, and
> the runtime must not be reachable except through that edge. This holds unchanged when several
> applications are [hosted behind one gateway](hosting.md): the gateway relays the header rather
> than filtering it, because it cannot tell the edge's value from a caller's, so the edge remains
> the only place the contract can be discharged. A deployment can name its edge with
> `--trusted-proxies`, which strips the header from requests arriving from anywhere else — defence
> in depth on top of the contract, not a replacement for it. Because certificates are public,
> fingerprint or DN pinning alone does not stop header injection — network isolation does. Only the
> URL-encoded (and raw) PEM convention is supported; Envoy/Istio's `x-forwarded-client-cert` (XFCC)
> envelope is not parsed.

## OpenID Connect (relying party)

OIDC logs a browser user in through an external identity provider using the **authorization-code
flow with PKCE**, then issues a TesseraQL browser session — the same session the SAML SP and
password login produce. It travels with the runtime and is inert until enabled: set
`tesseraql.oidc.enabled: true` and there is no jar to add. The provider's endpoints are
**discovered** at runtime, and the ID token is
validated with the same RS256/JWKS verifier as bearer JWT. The provider's host must be in
`tesseraql.http.outbound.allowedHosts`: discovery and the token exchange leave through the
same outbound gateway as every other framework-issued call, under the deny-by-default
egress allow-list.

```yaml
tesseraql:
  oidc:
    enabled: true
    discoveryUri: https://idp.example.com/.well-known/openid-configuration
    clientId: my-app
    clientSecret: ${secret.env.OIDC_CLIENT_SECRET}  # omit for a public (PKCE-only) client
    redirectUri: https://app.example.com/_tesseraql/oidc/callback
    scopes: [openid, profile, email]                # "openid profile email" (a string) also works
    postLoginUrl: /                                  # fixed same-origin path after login
    clockSkew: 60s
    claims:                                          # ID-token claim → principal mappings
      login: preferred_username
      name: name
      roles: roles
      groups: groups
      tenant: tenant_id
      map:                                           # declared attribute capture (linking on)
        department: department                       # ID-token claim → store attribute
    link:
      enabled: true       # resolve/authorize via local identity contracts (else IdP-asserted)
      provision: false    # JIT-provision an unknown user the first time they sign in
```

It serves three endpoints under `/_tesseraql/oidc`:

- `GET /login` — generates an anti-CSRF `state`, an ID-token `nonce`, and a PKCE `code_verifier`,
  records them server-side (single-use, in `tql_oidc_state`), and redirects to the provider's
  authorization endpoint with the `code_challenge` (S256).
- `GET /callback` — five steps, each failing closed. It validates and consumes the `state`,
  rejecting a forged, replayed, or expired one, and rejecting an `error=` response. It
  exchanges the code at the token endpoint, using `client_secret_basic` when a secret is set
  and a public PKCE client otherwise. It validates the ID token: signature via JWKS, `iss`,
  `exp`/`nbf`, `aud` including the client id, and a matching `nonce`. It resolves or
  provisions the principal. Finally it opens a session and redirects to the fixed
  `postLoginUrl`.
- `GET /logout` — clears the local session and, when the provider advertises one, redirects to its
  end-session endpoint.

With `link.enabled: true`, the resolution key is an **immutable identity link**
(`tql_user_identities`): the token's `iss` + `sub` pair links to the local user on first sign-in,
and every later sign-in resolves through the link. A `preferred_username` change at the OP
therefore re-syncs the **same** account instead of provisioning a duplicate — login id, display
name and email are mutable, re-synced profile fields. `claims.map` is the declared attribute capture:
each entry re-syncs one ID-token claim into a
[store attribute](iam-admin.md#attributes-and-assignment-rules) at every login — set when the
token carries it, deleted when it stops — written before the principal resolves, so the same
sign-in's assignment rules already see the fresh value. Unmapped claims stay discarded.

Discovery is **lazy**: the provider is contacted on the first login, not at app startup, so a brief
provider outage does not stop the app from booting. The expected token issuer is always the
discovered `issuer`, the post-login redirect is a fixed configured path (never a request parameter,
so there is no open redirect), and the client secret, code, and tokens are never logged. An IAM
admin wizard in Studio (**OIDC provider**) generates this config block.

## Browser sessions and the admin console

The bundled admin console — **Studio** (`/_tesseraql/studio`), the **Operations console**
([the ops console](ops-console.md)), and **[IAM Admin](iam-admin.md)** (`/_tesseraql/admin/users`) — authenticates with a
**browser session** (`auth: browser`): it is opened in a browser, not with a hand-minted token.
Opening a protected page without a session redirects (302) to the login page,
`GET /_tesseraql/login?redirect=<original-path>`; after signing in, the browser returns to the
`redirect` target. In a hosted stack the bounce is origin-absolute — a member serves no sign-in
of its own, so the stack's one login answers and `redirect` carries the member page's prefixed
path back through the round trip ([hosting.md](hosting.md)).

A session is established the same way regardless of method — password, OIDC, and SAML all create one
session cookie (`tesseraql_sid`) — so a route's `auth: browser` is satisfied however the user signed
in. The login method is therefore a **config switch**, with no per-route changes:

- **Password (default).** The login form posts to `POST /_tesseraql/login`, which verifies a
  credential in the identity store and opens the session. The store is not seeded automatically —
  create the first administrator once:

  ```bash
  tesseraql identity-schema --app . --admin-login admin --admin-password-file ./admin.pw
  ```

- **OIDC.** Set `tesseraql.oidc.enabled: true` (see *OpenID Connect* above). The login page then
  shows **Sign in with OIDC**, linking to `GET /_tesseraql/oidc/login`.
- **SAML.** Set `tesseraql.saml.enabled: true` and configure the SP. The login page shows **Sign in
  with SAML**, linking to `GET /_tesseraql/saml/login`.

To run **SSO-only**, hide the password form with `tesseraql.console.login.password.enabled: false`;
turn the login page off entirely with `tesseraql.console.login.enabled: false`. Logging out is
`POST /_tesseraql/logout` (a CSRF-carrying state change like its logout-device/others
siblings; invalidates the session, clears the cookie — there is no CSRF-exempt GET).

Credential guessing has a budget (docs/credential-throttle.md): failed sign-ins throttle
per submitted login id (10/15m) and per presented address (100/15m), on by default and
tunable under `tesseraql.security.credentialThrottle` — never a lockout, windows simply
expire; a throttled browser sees the login page's "too many attempts" message, an API
caller gets `429` `TQL-RATE-4292` with `Retry-After`.

Session lifetime is `tesseraql.sessions.ttl` (default `12h`, absolute from login).
`tesseraql.sessions.idleTimeout` additionally ends a session unseen for that long — a sliding
window inside the absolute ttl, off unless set (newly scaffolded apps declare `30m` in their
config, visibly). `tesseraql.sessions.maxPerSubject` caps live sessions per account: a login
beyond the cap evicts the subject's oldest session — the newest login wins — and
`maxPerSubject: 1` is the single-session policy. Each session records the user agent and the
address the edge presented at login; the account page lists your own sessions per device with a
per-row sign-out, and IAM Admin holds the administrative views.

State-changing console actions are CSRF-protected (`csrf: required`): the page publishes the session's
token as `<meta name="csrf-token">`, the Hypermedia Components kit replays it as the `X-CSRF-Token`
header on htmx requests, and no-JS forms carry it as a hidden `_csrf` field.

> **Returning to the requested page.** The page the user originally opened is threaded through
> every method: password login carries `redirect`, OIDC carries it across the IdP round-trip in a
> short-lived cookie, and SAML uses RelayState. The target is always sanitized to a same-origin
> path (no open redirect); `tesseraql.oidc.postLoginUrl` is the fallback when none was requested.

> The hand-built Studio **JSON API** under `/_tesseraql/studio/*` (distinct from the `/ui` pages)
> stays `auth: bearer` for programmatic callers; only the browser UI uses sessions.

## Where a session may be established from

`tesseraql.security.network.allow` is a comma-separated CIDR list. When it names anything, a
sign-in presenting an address outside it is refused with `TQL-SEC-4149` **before a session
exists** — no cookie, nothing to carry forward — however the session was being established:
the password login, the OIDC callback and the SAML assertion consumer all pass through the
same admission.

```yaml
tesseraql:
  security:
    network:
      allow: "10.0.0.0/8, 192.168.0.0/16, 203.0.113.7"
```

A bare address is the single host it names. An unset or empty list admits everybody, which is
the shipped behaviour: a deployment that names no network has not asked for this control.

The check runs **after the credential is proven**, so a refusal from outside the office says
nothing about whether the password was right. The address judged is the one the edge presented
— the first `X-Forwarded-For` entry when there is one, else the peer — so this control is worth
exactly as much as the edge's discipline about that header, which is the same duty the
[mTLS section](#mutual-tls-client-certificates) puts on it. A restricted deployment refuses a
request whose address it cannot read at all rather than admitting the unjudgeable.

This is the deployment-wide layer. Its per-role counterpart — a held role usable only from
named networks or during named hours — is a [context condition](iam-admin.md#context-conditions),
which narrows what a signed-in caller may do rather than deciding whether they may sign in.

## Acting roles (activation)

A user holding several [application roles](iam-admin.md) for the same hosted application —
a concurrent assignment — acts as **one of them at a time**, and the choice rides the address:
`/<member>/_as/<role>/…`. A tab *is* its URL, so two tabs run two capacities side by side and
can never mix; the segment survives reload, works without JS, and shows in the access log. The
stack gateway strips the segment before forwarding, so application routes never see it, and
hands the role to the member as an internal header it validates against the caller's **own**
grants — a forged segment or header can only select among held roles, never add one.

The line, drawn once: **reachability reads the union; conduct reads the active view.** The
`tql.app.use` fence, every framework atom check, and bearer minting see everything the
principal holds. Inside the application — route policies, [scope arms](data-scoping.md),
menus, field policies, ambient `principal.*` binds — the principal is the active view: all
stack-wide roles, the one activated role, and the permissions those deliver plus direct
grants. Absence denies: with no role activated, no application role is in effect.

Entry is automatic. A signed-in browser navigation to a member where the caller holds exactly
one application role is redirected into that role's address; holding several redirects to the
**role picker** at the origin (`/_tesseraql/roles`); holding none changes nothing — an
application without application roles never sees this machinery. The member page's chrome
carries a **role switcher** listing the caller's other roles for that member as links
that swap the segment in place. A role the caller does not hold answers `TQL-SEC-4148`: the
picker for a browser, 403 for everyone else. Non-HTML callers are never redirected — an API
caller states its capacity in the address, or runs with no application role active.

Tokens state a capacity too: `tesseraql token --as <role>` (and the role selector on the ops
console's token page) mints the **active view** — the narrowed roles and permissions — plus an
`acting_role` claim, so the [audit trail](iam-admin.md) writes the same sentence for a machine
caller as for a tab. Nothing selected mints the union, exactly as before. Claim-asserted
principals (bearer JWT, API keys, mTLS) carry no store attribution and are always their full
claimed selves; asking them to activate is refused.

## Runtime error codes

Returned at request time (distinct from the lint codes below, which are static checks):

| Code | HTTP | Meaning & what to do |
| --- | --- | --- |
| `TQL-SEC-4011` | 401 | **Unauthorized** — the route needs authentication and the request carried no valid credential (missing/expired session cookie, or missing/invalid/expired bearer token). For the admin console, sign in at `/_tesseraql/login`; for a bearer route, present a valid `Authorization: Bearer <jwt>`. A browser navigation (`Accept: text/html`) is redirected to the login page automatically. |
| `TQL-SEC-4031` | 403 | **Forbidden** — authenticated, but the principal does not satisfy the route's `policy` (missing role/permission), or the policy is undefined (deny by default). Grant the role/permission, or define the policy. |
| `TQL-SEC-4001` | 500 | **The authenticator is not configured** — the route's `auth:` mode needs a bean the application never bound, usually because its `tesseraql.security.<mode>` block is missing. No credential can succeed, which is why this is a server fault and not a 401: a 401 would send clients into token-refresh retries against a server where nothing could work. The build-time counterpart is `TQL-SEC-4047`. |
| `TQL-SEC-4032` | 403 | **CSRF check failed** — a state-changing `auth: browser` request arrived without a valid CSRF token. Send the page's `X-CSRF-Token` header (htmx does this automatically) or the `_csrf` form field from a live session. |
| `TQL-SEC-4148` | 403 | **Wrong capacity** — the caller asked to act as an application role they do not hold (a revoked bookmark, someone else's link, an unheld `--as`). A browser navigation is redirected to the role picker instead; choose a held role there. |
| `TQL-SEC-4149` | 403 | **Sign-in not allowed from this network** — the deployment names its sign-in networks in `tesseraql.security.network.allow` and the address this request presented is not inside one. The credential is not the problem, so it is a refusal and not a challenge. Sign in from a listed network, or add the network. Also raised at startup when a configured entry is not a valid CIDR block. |

## Lint rules

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-SEC-4040` | error | RS256 JWT config declares no key source (`jwksUri` or `publicKey`). |
| `TQL-SEC-4041` | error | RS256 JWT config declares both key sources; set exactly one. |
| `TQL-SEC-4042` | error | Algorithm and key material disagree (HS256 `secret` with RS256 key material, or vice versa) — an algorithm-confusion risk. |
| `TQL-SEC-4043` | error | Unsupported JWT algorithm (use `HS256` or `RS256`; `none` is rejected). |
| `TQL-SEC-4044` | error | A route, queue consumer, or MCP tool declares `auth: api-key` but no `tesseraql.security.apiKeys` is configured. |
| `TQL-SEC-4045` | error | An API-key client declares no `secretHash`. |
| `TQL-SEC-4046` | warning | An API-key client grants no roles or permissions (least-privilege hint). |
| `TQL-SEC-4047` | warning | A route, queue consumer, or MCP tool declares `auth: bearer` but no `tesseraql.security.jwt` is configured, so no token can be verified. |
| `TQL-SEC-4050` | error | OIDC is enabled but no `discoveryUri` is configured. |
| `TQL-SEC-4051` | error | The OIDC `discoveryUri` is not https (loopback http is allowed for dev). |
| `TQL-SEC-4052` | error | OIDC is enabled but no `clientId` is configured. |
| `TQL-SEC-4053` | error | OIDC is enabled but no `redirectUri` is configured. |
| `TQL-SEC-4060` | error | A route declares `auth: mtls` but no `tesseraql.security.mtls` is configured. |
| `TQL-SEC-4061` | error | mTLS is configured but declares no `forwardedHeader` (the certificate has no source). |
| `TQL-SEC-4062` | error | An mTLS client declares no certificate matcher (`subjectDn`/`sanDns`/`sanUri`/`sanEmail`/`sanIp`/`sha256`). |
| `TQL-SEC-4063` | error | An mTLS client declares more than one certificate matcher; set exactly one. |
| `TQL-SEC-4064` | warning | An mTLS client grants no roles or permissions (least-privilege hint). |
| `TQL-SEC-4065` | warning | mTLS declares no `trustBundle`; the runtime does not independently validate the chain. |
| `TQL-SEC-4066` | error | An mTLS client declares the removed untyped `san:`; name the kind with `sanDns`/`sanUri`/`sanEmail`/`sanIp`. Also thrown at startup. |
| `TQL-SEC-4130` | warning | The retired kind-keyed `security.defaults.api`/`htmx` shape is present; it has no effect — use the path-matched `security.defaults.routes` rules. |
| `TQL-SEC-4131` | warning | A route is `public` while a matching security default rule declares a policy for its path — confirm the route is deliberately open. |
| `TQL-SEC-4132` | error | A `security.defaults.routes` rule is malformed (missing `match`, invalid `csrf`, or an empty rule); the app fails to load. |

The lint reads raw config — it never resolves secret placeholders — so it runs without a live
secret store.

## Coverage

Three coverage kinds make authentication test gaps visible (see
[testing.md](testing.md#coverage-kinds) for how kinds and threshold gating work):

- `api-key` — every route authenticated by `auth: api-key`, covered when a declarative suite
  exercises it; gate with `coverage.thresholds.api-key`.
- `mtls` — the same for routes authenticated by `auth: mtls`; gate with
  `coverage.thresholds.mtls`.
- `oidc` (like `saml`) — the identity contracts the login path runs when user linking is on,
  covered by contract test cases; gate with `coverage.thresholds.oidc`.

RS256 vs HS256 is a verification detail of the same bearer path and is covered by the existing
`security`/`route` kinds.

## Testing

Declarative suites exercise a route's SQL through the same pipeline regardless of authentication
method, so app test cases need no per-method setup. The authentication wiring itself — JWKS
rotation, API-key privilege checks, mTLS trust and matching, the OIDC code + PKCE flow — is held
by the framework's own test suite; an application does not re-test it.

## Next

- [saml.md](saml.md) — signing in through a corporate identity provider.
- [data-scoping.md](data-scoping.md) — confining what an authenticated principal may read.
- [iam-admin.md](iam-admin.md) — administering the accounts.
