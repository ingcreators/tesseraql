# Authentication

TesseraQL routes are deny-by-default (design ch. 11, 20.14): a route is reachable only when it
declares how it authenticates, and authorization policies are evaluated against the resolved
`Principal`. Every authentication method plugs in behind the same authentication step and the same
principal model, so claims, roles, permissions, and tenant resolve identically downstream
regardless of how the caller proved its identity.

```yaml
security:
  auth: bearer        # bearer | apiKey | browser | public
  policy: users.read  # authorization policy evaluated against the principal
```

This page covers the **bearer JWT** and **API-key** methods. Browser sessions are covered in
[hypermedia-ui.md](hypermedia-ui.md); corporate SSO (SAML, and OIDC) is configured separately.

All JWT and API-key crypto is JDK-only — there is no JOSE/JWT third-party dependency, matching the
SAML module's supply-chain posture.

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

The JWKS endpoint must be `https` (loopback `http` is allowed for local development). The fetched
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
keys. A route opts in with `auth: apiKey`:

```yaml
security:
  auth: apiKey
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

Only a **hex SHA-256 of the key is stored** — never the raw key — and is best supplied through the
secret SPI. Generate it with, for example, `printf %s "$RAW_KEY" | sha256sum`. The presented key is
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
- `san` — a Subject Alternative Name value the certificate carries (DNS, URI, email, or IP); for
  example a SPIFFE URI `spiffe://acme/ns/default/sa/billing`.
- `sha256` — the hex SHA-256 fingerprint of the DER certificate (colons and case are ignored); the
  strongest binding, pinning one exact certificate.

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
> the runtime must not be reachable except through that edge. Because certificates are public,
> fingerprint or DN pinning alone does not stop header injection — network isolation does. Only the
> URL-encoded (and raw) PEM convention is supported; Envoy/Istio's `x-forwarded-client-cert` (XFCC)
> envelope is not parsed.

## OpenID Connect (relying party)

OIDC logs a browser user in through an external identity provider using the **authorization-code
flow with PKCE**, then issues a TesseraQL browser session — the same session the SAML SP and
password login produce. It is an opt-in leaf module: add the `tesseraql-oidc` jar to the runtime
classpath and enable it. The provider's endpoints are **discovered** at runtime, and the ID token is
validated with the same RS256/JWKS verifier as bearer JWT.

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
    link:
      enabled: true       # resolve/authorize via local identity contracts (else IdP-asserted)
      provision: false    # JIT-provision an unknown user the first time they sign in
```

It serves three endpoints under `/_tesseraql/oidc`:

- `GET /login` — generates an anti-CSRF `state`, an ID-token `nonce`, and a PKCE `code_verifier`,
  records them server-side (single-use, in `tql_oidc_state`), and redirects to the provider's
  authorization endpoint with the `code_challenge` (S256).
- `GET /callback` — validates and consumes the `state` (a forged, replayed, or expired one is
  rejected, as is an `error=` response), exchanges the code at the token endpoint
  (`client_secret_basic` when a secret is set, else a public PKCE client), validates the ID token
  (signature via JWKS, `iss`, `exp`/`nbf`, `aud` includes the client id, `nonce` matches), resolves
  or provisions the principal, opens a session, and redirects to the fixed `postLoginUrl`.
- `GET /logout` — clears the local session and, when the provider advertises one, redirects to its
  end-session endpoint.

Discovery is **lazy**: the provider is contacted on the first login, not at app startup, so a brief
provider outage does not stop the app from booting. The expected token issuer is always the
discovered `issuer`, the post-login redirect is a fixed configured path (never a request parameter,
so there is no open redirect), and the client secret, code, and tokens are never logged. An IAM
admin wizard in Studio (**OIDC provider**) generates this config block.

## Lint rules

| Code | Severity | Meaning |
| --- | --- | --- |
| `TQL-SEC-4040` | error | RS256 JWT config declares no key source (`jwksUri` or `publicKey`). |
| `TQL-SEC-4041` | error | RS256 JWT config declares both key sources; set exactly one. |
| `TQL-SEC-4042` | error | Algorithm and key material disagree (HS256 `secret` with RS256 key material, or vice versa) — an algorithm-confusion risk. |
| `TQL-SEC-4043` | error | Unsupported JWT algorithm (use `HS256` or `RS256`; `none` is rejected). |
| `TQL-SEC-4044` | error | A route declares `auth: apiKey` but no `tesseraql.security.apiKeys` is configured. |
| `TQL-SEC-4045` | error | An API-key client declares no `secretHash`. |
| `TQL-SEC-4046` | warning | An API-key client grants no roles or permissions (least-privilege hint). |
| `TQL-SEC-4050` | error | OIDC is enabled but no `discoveryUri` is configured. |
| `TQL-SEC-4051` | error | The OIDC `discoveryUri` is not https (loopback http is allowed for dev). |
| `TQL-SEC-4052` | error | OIDC is enabled but no `clientId` is configured. |
| `TQL-SEC-4053` | error | OIDC is enabled but no `redirectUri` is configured. |
| `TQL-SEC-4060` | error | A route declares `auth: mtls` but no `tesseraql.security.mtls` is configured. |
| `TQL-SEC-4061` | error | mTLS is configured but declares no `forwardedHeader` (the certificate has no source). |
| `TQL-SEC-4062` | error | An mTLS client declares no certificate matcher (`subjectDn`/`san`/`sha256`). |
| `TQL-SEC-4063` | error | An mTLS client declares more than one certificate matcher; set exactly one. |
| `TQL-SEC-4064` | warning | An mTLS client grants no roles or permissions (least-privilege hint). |
| `TQL-SEC-4065` | warning | mTLS declares no `trustBundle`; the runtime does not independently validate the chain. |

The lint reads raw config — it never resolves secret placeholders — so it runs without a live
secret store.

## Coverage

The `api-key` coverage kind declares every route authenticated by `auth: apiKey` and marks it
covered when a declarative suite exercises it, so a test gap on a service-caller route is visible.
Gate it like any kind with `coverage.thresholds.api-key`. The `mtls` coverage kind does the same for
routes authenticated by `auth: mtls`; gate it with `coverage.thresholds.mtls`. The `oidc` coverage
kind (like `saml`)
declares the identity contracts the login path runs when user linking is on, covered by contract
test cases; gate it with `coverage.thresholds.oidc`. RS256 vs HS256 is a verification detail of the
same bearer path and is covered by the existing `security`/`route` kinds; its cryptographic
guarantees are pinned by the unit tests in `tesseraql-security`.

## Testing

Declarative suites exercise a route's SQL through the same pipeline regardless of authentication
method. End-to-end authentication wiring is covered by integration tests in `tesseraql-camel-runtime`
(`RsaJwksIntegrationTest` serves a JWKS document from a local HTTP server and asserts accept /
reject / rotation; `ApiKeyIntegrationTest` asserts `200`/`401`/`403` for valid, invalid, and
under-privileged keys; `MtlsIntegrationTest` forwards a client certificate in the configured header
and asserts `200`/`401`/`403` for a recognized, an expired/unrecognized/missing, and an
under-privileged certificate; `OidcLoginIntegrationTest` drives the full authorization-code + PKCE
flow against a local mock provider and asserts a session is issued and that tampered / replayed state
and provider errors are rejected). mTLS certificate parsing, validity, PKIX trust, and DN/SAN/sha256
matching are unit-tested in `tesseraql-security` (`MtlsAuthenticatorTest`); OIDC's PKCE S256 is
unit-tested against the RFC 7636 vector.
