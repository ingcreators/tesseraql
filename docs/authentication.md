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

The lint reads raw config — it never resolves secret placeholders — so it runs without a live
secret store.

## Coverage

The `api-key` coverage kind declares every route authenticated by `auth: apiKey` and marks it
covered when a declarative suite exercises it, so a test gap on a service-caller route is visible.
Gate it like any kind with `coverage.thresholds.api-key`. RS256 vs HS256 is a verification detail
of the same bearer path and is covered by the existing `security`/`route` kinds; its cryptographic
guarantees are pinned by the unit tests in `tesseraql-security`.

## Testing

Declarative suites exercise a route's SQL through the same pipeline regardless of authentication
method. End-to-end authentication wiring is covered by integration tests in `tesseraql-camel-runtime`
(`RsaJwksIntegrationTest` serves a JWKS document from a local HTTP server and asserts accept /
reject / rotation; `ApiKeyIntegrationTest` asserts `200`/`401`/`403` for valid, invalid, and
under-privileged keys).
