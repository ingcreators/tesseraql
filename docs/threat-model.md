# Threat model

This is TesseraQL's framework-level threat model: the actors, the trust boundaries, and a
STRIDE pass over each surface that turns untrusted input into action — mapped to the control
that mitigates each threat and the residual risk that remains. It is a maintainer artifact
kept beside the [hardening self-assessment](security-hardening.md) (which maps the same
controls to OWASP ASVS); together they let a reviewer check the claims against code and let a
deployment reason about its own posture.

It models the **framework runtime and its surfaces**, not any one deployment's
infrastructure. Where a control lives at the deployment edge — TLS termination, WAF, network
egress — the model says so and points to the [operator guide](deployment.md); those are the
operator's to configure, and this model assumes they are.

## Assets

What an attacker would want, in rough order of value:

- **Business data** — the rows an app reads and writes, scoped by tenant and by
  organizational unit.
- **Credentials and secrets** — passwords, API keys, TOTP seeds, session tokens, and the
  datasource/egress credentials resolved from the secret provider.
- **Sessions** — an authenticated browser or service identity.
- **App definitions** — the YAML/SQL that defines routes, policies, and datasources; trusted
  when authored by the operator, semi-trusted when a shared package.
- **The audit trail** — the record of who did what, whose integrity underpins accountability.
- **Availability** — the runtime's ability to keep serving.

## Actors

| Actor | Trust | Reaches |
| --- | --- | --- |
| Anonymous visitor | none | `auth: public` routes and the liveness probe only |
| Authenticated end user | low–medium (their own data) | browser-session routes under their policies and tenant |
| Authenticated API client | low–medium | bearer / API-key / mTLS routes under their policies |
| Provisioning client (SCIM) | medium (holds `scim.manage`) | the SCIM user/group endpoints and the `?filter=` parser |
| Identity provider (SAML / OIDC) | medium (federated trust) | the assertion/token callback path |
| Studio editor user | high (holds an edit role) | the authenticated editor endpoints that parse request-body YAML |
| Operator | high | configuration, secrets, the ops console, deployment |
| App author | high in single-tenant; **semi-trusted** for a shared package | the app's YAML/SQL, parsed by the admission gate and at boot |

The model's sharpest boundary is between an **authenticated but untrusted request payload**
(a SCIM filter, a Studio draft, an API body) and the runtime — most runtime-reachable threats
live there.

## Trust boundaries

A request crosses these boundaries, each a place where the model asks "what can the far side
do to the near side?":

1. **The network edge → the runtime.** TLS terminates at a reverse proxy / ingress that
   forwards HTTPS; the runtime speaks HTTP behind it (see [deployment](deployment.md),
   "Transport security"). Everything past this boundary assumes the transport was
   authenticated and encrypted at the edge.
2. **The request → authentication.** A route selects an auth mode; the authenticator resolves
   a principal or denies. No principal and no `auth: public` is a closed door.
3. **The principal → authorization.** A resolved principal is checked against the route's
   policy, the tenant, and any row/field scope before any SQL runs.
4. **The bound request → SQL.** Declared inputs are validated and coerced, then bound as
   parameters; the 2-way SQL text is author-written, never assembled from request values.
5. **The runtime → outbound.** Every framework-issued outbound call (HTTP, connectors, the
   analytics engine's remote tier) passes a deny-by-default egress allow-list.
6. **The runtime → files.** Uploads pass a scan gate before they can be served; the embedded
   analytics engine reads files only inside a locked, declared-directory fence.
7. **App source → the compiler.** App YAML/SQL/expressions are parsed at build and boot; for a
   shared package they are semi-trusted input the admission gate must survive.

## STRIDE by surface

Each table reads: **threat → vector → control → residual**. Controls are cited to the
[hardening self-assessment](security-hardening.md), which locates them in code.

### Authentication and sessions

| Threat | Vector | Control | Residual |
| --- | --- | --- | --- |
| Spoofing (token) | forged or `alg:none` JWT | JDK-only signature verification; algorithm confusion rejected by design | key management is the deployment's (JWKS/secret rotation) |
| Spoofing (API key) | guessing/replaying a key | keys stored as SHA-256, constant-time compared; the raw key is never stored or logged | key distribution is the operator's |
| Spoofing (session) | stealing/fixating the cookie | a fresh id minted at login (no pre-auth adoption); the cookie is HTTPS-secured at the edge | id is not re-issued on privilege elevation — a small residual, tracked as follow-up |
| Elevation (MFA bypass) | replaying a TOTP step | RFC 6238 window with a recorded last-used step | — |
| Information disclosure (enumeration) | probing reset/login for valid accounts | neutral responses end to end | — |

### Access control

| Threat | Vector | Control | Residual |
| --- | --- | --- | --- |
| Elevation (missing authorization) | calling a route without rights | deny-by-default `PolicyEngine` — no principal → 401, unsatisfied/undefined policy → 403 | a policy the app forgot to define fails closed, not open |
| Elevation (tenant crossover) | reading another tenant's rows | tenant resolved per request and enforced in SQL; shared-schema queries lint-warned if unscoped | correctness of the tenant predicate is the author's, backed by lint |
| Information disclosure (field) | reading a masked column | role-conditional column masking + row `unmaskWhen` | — |
| Tampering (unscoped write) | an `UPDATE`/`DELETE` that omits its scope predicate | the `/*%scope*/` mechanism confines writes when used | a *forgot-to-scope* guard is a planned defense-in-depth enhancement, not yet a runtime check |

### Input handling and injection

| Threat | Vector | Control | Residual |
| --- | --- | --- | --- |
| Tampering (SQL injection) | request value in a SQL string | bind values are always parameterized; the one text-interpolating path (embedded `{var}`) is enum-gated for request inputs | — |
| Tampering (XSS) | script in a rendered value | Thymeleaf context-aware auto-escaping; a Content-Security-Policy is required on every HTML page | app-authored inline scripts are the author's CSP to declare |
| Denial of service (parser) | deeply nested / hostile 2-way SQL, expression, YAML, or SCIM input | depth guards + explicit YAML read constraints + a deterministic fuzz harness asserting fail-closed (see the hardening page) | covered; a coverage-guided campaign stays gated-ready |
| Denial of service (payload size) | an oversized request body | explicit YAML document/nesting bounds; the edge should also cap body size | body-size caps are best set at the edge too |

### Egress and SSRF

| Threat | Vector | Control | Residual |
| --- | --- | --- | --- |
| Server-side request forgery | an `http-call` / connector / remote-lake URL aimed at an internal host | deny-by-default `allowedHosts`; a bare `*` is refused at admission; outbound keeps certificate verification | the allow-list is the operator's to keep tight; pair it with network egress rules |
| Spoofing (credential leak to a rogue host) | tricking the engine into authenticating to an attacker prefix | object-storage secrets are prefix-scoped — an out-of-scope URL fails authentication | — |

### Files and resources

| Threat | Vector | Control | Residual |
| --- | --- | --- | --- |
| Tampering (malicious upload) | uploading malware | every upload is scanned; the download gate refuses any non-clean object, `pending` included (fail-closed) | the scanner's efficacy is its own (an SPI; a no-op default until a scanner is installed) |
| Tampering (path traversal) | a `..`/absolute path into the analytics engine | the DuckDB fence locks the engine to declared roots; file paths come only from validated placeholders | — |
| Elevation (engine escape) | app SQL trying to widen the engine's reach | `INSTALL`/`LOAD`/`ATTACH`/`SET` are refused (runtime fence + build-time lint on every duckdb datasource) | authored SQL on a remote-lake datasource is lint-governed rather than engine-caged — recorded |

### Secrets, errors, and repudiation

| Threat | Vector | Control | Residual |
| --- | --- | --- | --- |
| Information disclosure (secret) | reading a credential from source, logs, or an artifact | secrets are references resolved lazily per use, never written to logs or generated artifacts | the secret provider's own storage is the operator's |
| Information disclosure (error) | provoking a stack trace or internal detail | generic error bodies; federated-auth failures return generic 401/400 with contents never echoed | — |
| Repudiation | denying an action | opt-in `tql_route_audit` records who/what/when over declared inputs, excluding masked/classified fields; trace ids correlate logs | the audit is opt-in and the operator's retention |

### The shared-app (marketplace) surface

A forward-looking boundary: distributing an app as a package makes its source **semi-trusted**.

| Threat | Vector | Control | Residual |
| --- | --- | --- | --- |
| Elevation (arbitrary code) | shipping custom Java in a package | the trust model constrains shared packages to declarative recipes — the framework is the sandbox, so no bytecode is isolated | enforced by the admission gate; the curated-marketplace pipeline is future work |
| Denial of service (admission gate) | a package crafted to crash the linter/parser | the parser depth guards and fuzz-proven fail-closed invariant apply to the admission parse too | — |
| Tampering (package swap) | serving different bytes than reviewed | packages are hash-pinned; the runtime refuses a package whose hash does not match | signature/provenance verification is future marketplace work |

## Assumptions

The model holds only where these hold:

- **TLS terminates at a trusted edge** and HTTPS reaches the runtime; the plain-HTTP port is
  not exposed to clients.
- **The operator is trusted** — they hold the configuration, secrets, and deployment; a
  compromised operator is out of scope.
- **The app author is trusted in a single-tenant deployment.** The semi-trusted case is a
  shared package, which the admission gate and the fail-closed parsers must survive.
- **The datastore and secret provider are trusted** to enforce their own access control;
  at-rest encryption and DB hardening are the deployment's.
- **Deny-by-default is not weakened** — an app that leaves a policy undefined, an egress host
  unlisted, or a scope predicate off gets closed behaviour or a lint error, not an open door.

## Residual risks and follow-ups

Carried forward, none an actively-exploitable hole in a correctly-configured deployment:

- **A forgot-to-scope write guard** — the scope mechanism works; the automatic check that a
  write which *should* be scoped carries the predicate is a planned defense-in-depth
  enhancement.
- **Session id rotation in place on a non-credential elevation** — the fixation case is handled
  at login, and both a password reset and a self-service password change now end the subject's
  sessions; the residual is re-issuing the current id in place on a non-credential elevation
  (e.g. MFA enrollment), which the service layer signs out for instead.
- **Curated-marketplace provenance** — signature and publisher verification for shared
  packages, beyond today's hash pinning and the declarative-only admission gate.
- **This model is living.** Each new surface — a new recipe, a new egress path, a new parser —
  extends the tables above; that upkeep is part of adding the surface, not an afterthought.
