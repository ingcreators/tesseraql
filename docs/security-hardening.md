# Security hardening and parser robustness

This page is TesseraQL's security **self-assessment**: an OWASP ASVS-shaped map of the
framework's controls to the mechanisms that implement them, and the parser-robustness
work that hardens the untrusted-input edge. It is a maintainer document, not a
certification — it records what the framework does, where, and what is deliberately out
of scope, so a reviewer can check claims against code and a deployment can reason about
its own posture.

Two things live here because they belong together: the **ASVS control map** (what
protects each class of risk) and the **parser fuzzing** that proves the input parsers
fail closed. The parsers are the framework's widest attack surface — 2-way SQL,
expressions, YAML, and SCIM filters all turn bytes into structure — so their robustness
is assessed with evidence, not assertion.

## Parser robustness

### The invariant

Every parser must **fail closed on any input**: malformed, hostile, or merely huge input
yields either a valid parse or a *typed, coded rejection the caller can handle* — never
an uncaught `StackOverflowError`, `OutOfMemoryError`, `NullPointerException`, or a
non-terminating loop. A crash or hang on adversarial input is a denial-of-service vector;
on the runtime-reachable parsers it is one an authenticated client could trigger.

Exposure differs by parser, and the assessment is honest about it:

| Parser | Where it parses | Input trust |
| --- | --- | --- |
| 2-way SQL (`Sql2WayParser`) | app SQL files, at build and boot | author-controlled; **semi-trusted** for a marketplace `.tqlapp` the admission gate parses |
| Expressions (`ExpressionParser`) | route conditions, binds, `validate:` rules | author-controlled (compiled once; values bind at runtime, the tree does not re-parse) |
| YAML (`SimpleYamlParser` over Jackson/SnakeYAML) | app documents at boot **and request bodies at the Studio editor endpoints** | author files are trusted; **Studio request bodies are runtime, authenticated, client-controlled** |
| SCIM filter (`ScimFilter`, `ScimGroupPatch`) | the `?filter=` / PATCH path from a provisioning client | **runtime, authenticated, client-controlled** |

### Findings (probed 2026-07)

Adversarial probes against each parser found two real defects and confirmed the rest
safe:

- **2-way SQL: deep directive nesting overflows the stack.** A template nesting
  `/*%if …*/` / `/*%for …*/` / `/*%scope …*/` past roughly 1500 levels raises a raw
  `StackOverflowError` from the recursive-descent block parser, rather than a clean parse
  error. Author-controlled in the common case, but a marketplace app could crash the
  admission linter this way.
- **Expressions: deep nesting overflows the stack.** `((((…))))` grouping or a `!!!!…`
  unary chain past roughly the same depth overflows `ExpressionParser`. Same class, same
  fix.
- **Expressions: a number-shaped token the JDK rejects leaks a raw exception.** The
  fuzz harness (not the hand probes) surfaced this: the lexer accepts number tokens the
  JDK's `Long.parseLong`/`Double.parseDouble` still reject — an integer past `long`'s
  range, a malformed exponent — so `ExpressionParser` raised an uncoded
  `NumberFormatException` instead of a parse error. Now wrapped as a coded rejection; the
  find is exactly the off-contract-exception class the harness exists to catch.
- **YAML resource limits are the library's defaults, and the error contract is
  inconsistent.** Jackson/SnakeYAML *do* cap nesting (1000) and document size (~3 MB code
  points) by default, so an alias bomb or deep document is rejected — but the framework
  sets no explicit bound of its own, and the rejection surfaces differently by entry
  point: the **file** parse methods wrap it as a raw `UncheckedIOException` (uncoded),
  while the **string** methods that the Studio endpoints call return a coded
  `TQL-YAML-1001`. Same malformed input, two contracts.
- **SCIM filters are safe.** The filter and group-patch parsers are single anchored
  regexes (`eq`-only), not recursive descent; deep nesting simply fails the match, and
  ReDoS probes (millions of quotes, spaces, and value characters) all terminate promptly.
  No change needed — the fuzz harness locks the invariant in.

### The hardening

- **Depth guards.** `Sql2WayParser` and `ExpressionParser` count nesting depth and raise
  a coded `TqlException` when it exceeds a generous bound (far above any real template,
  far below the overflow threshold), converting a fatal `StackOverflowError` into an
  ordinary parse rejection.
- **Explicit YAML constraints and one error contract.** The YAML mapper is configured
  with explicit `StreamReadConstraints` (nesting, document, and name/string length)
  rather than relying on library defaults that a dependency change could move, and the
  file parse methods harmonize onto the same coded `TQL-YAML-1001` the string methods
  already return.
- **The fuzz harness proves it.** A deterministic generative harness (below) drives each
  parser with structure-aware and mutated inputs on every build, asserting the
  fail-closed invariant — so a regression that reintroduces a crash fails the build, not
  production.

### The fuzzing approach

TesseraQL fuzzes with **deterministic, generative property tests that run in ordinary
CI**, not a coverage-guided native fuzzer. A seeded PRNG drives structure-aware
generators (each parser's own alphabet of directives, operators, and delimiters) plus
mutation of a seed corpus of valid inputs; a fixed iteration budget runs on every build.
The oracle is the invariant above: the only Throwable a parser may raise is its declared,
coded exception type; anything else — `StackOverflowError`, `OutOfMemory`,
`NullPointerException`, `StringIndexOutOfBounds`, a raw library exception, or a timeout —
is a failure. Each specific finding above also gets a fixed regression test, so the exact
defect can never return even if the generator's seed moves.

This is a deliberate choice. A coverage-guided fuzzer (Jazzer/libFuzzer) explores deeper
but adds a native, non-deterministic dependency on the CI path — against the framework's
JDK-only, reproducible-build grain. The harness is written so its per-parser entry points
could be wrapped by a gated Jazzer campaign later (the same way the vendor-dialect
portability suites run out of the per-change path), the day the depth of exploration is
worth the dependency. Until then, deterministic generation catches the whole class of
crash bug — it already found the two above — while staying green and reproducible.

## ASVS control map

A self-assessment against [OWASP ASVS](https://owasp.org/www-project-application-security-verification-standard/)
Levels 1–2, by chapter. Each row names the control class, its status
(**met** / **partial** / **gap** / **n/a**), and where it lives; the per-requirement
detail is filled in the [assessment](#asvs-assessment) below.

| ASVS chapter | TesseraQL surface | Where |
| --- | --- | --- |
| V1 Architecture | module boundaries, SPI seams, deny-by-default posture | this page |
| V2 Authentication | bearer/apiKey/mTLS/browser + SAML/OIDC; PBKDF2; TOTP | [authentication](authentication.md), [credential-lifecycle](credential-lifecycle.md), [saml](saml.md) |
| V3 Session management | server session store, per-session CSRF, fixation/rotation | [account](account.md) |
| V4 Access control | deny-by-default `PolicyEngine`, field/row scoping, tenancy | [data-scoping](data-scoping.md), [multi-tenancy](multi-tenancy.md) |
| V5 Validation / encoding / injection | 2-way SQL bind discipline, embedded-var enum gate, Thymeleaf escaping, CSP, **parser robustness** | [two-way-sql](two-way-sql.md), [declarative-validation](declarative-validation.md), this page |
| V6 Cryptography | JDK-only crypto, secret references, no keys in source | [authentication](authentication.md) |
| V7 Errors / logging | `TQL-*` taxonomy, route audit with masked fields, MDC trace id | [reference-error-codes](reference-error-codes.md) |
| V8 Data protection | field masking/classification, attachment scan gate | [attachments](attachments.md) |
| V9 Communications | TLS termination stance, mTLS | [deployment](deployment.md) |
| V11 Business logic / rate | per-node and cluster rate limits, workflow guards | [deployment](deployment.md) |
| V12 Files / resources | attachment scanning, blob egress allow-list, the DuckDB fence | [attachments](attachments.md), [duckdb](duckdb.md) |
| V13 API / web service | SCIM, MCP, REST recipes; CSRF for browser auth | [authentication](authentication.md) |
| V14 Configuration | deny-by-default egress allow-list, admission profile, secret hygiene | [admission](admission.md) |

### ASVS assessment

The requirement-level view, by chapter. Status is **met** (a control implements it),
**partial** (implemented with a caveat or a deployment dependency), **gap** (a known
shortfall, carried into follow-up work), or **n/a** (outside the framework's
responsibility). Citations name the class or the doc; this is a self-assessment, so a
reviewer can check each claim against the source.

#### V1 Architecture, design, threat modeling

| Requirement | Status | Evidence |
| --- | --- | --- |
| Deny-by-default security posture | met | routes require an auth mode and a policy unless `auth: public`; egress, uploads, and marketplace admission all default closed |
| Trust boundaries and component isolation | met | module boundaries hold (`tesseraql-core` dependency-free); heavy/optional capabilities sit behind SPIs (`FileCodec`, `BlobStore`, `SecretResolver`, `PasswordVerifier`); the DuckDB engine runs behind a per-connection fence |
| A documented threat model | gap | this page is the first hardening artifact; a full threat-model refresh is follow-up work |

#### V2 Authentication

| Requirement | Status | Evidence |
| --- | --- | --- |
| Passwords stored with an approved KDF | met | `Pbkdf2PasswordEncoder` — PBKDF2 (100k iterations, 256-bit key, 16-byte random salt); Argon2id/bcrypt plug in behind `PasswordVerifier` |
| Credentials verified in constant time | met | `ApiKeyAuthenticator` SHA-256-hashes the presented key and constant-time compares; the raw key is never stored or logged |
| Token/signature verification is sound | met | `JwtAuthenticator` (JDK-only HS256/RS256 with JWKS) rejects algorithm confusion by design; SAML pins the IdP key with XXE off and a DB replay guard; OIDC is authorization-code + PKCE |
| A second factor is available | met | RFC 6238 TOTP (`Totp`) with recovery codes and a last-used-step replay block |
| Anti-enumeration on credential flows | met | reset/invite/login return neutral responses end to end (credential-lifecycle) |
| Memory-hard KDF at L2 | partial | PBKDF2 is the default (ASVS-acceptable); Argon2id is available but not the shipped default |

#### V3 Session management

| Requirement | Status | Evidence |
| --- | --- | --- |
| Server-side session with an unpredictable id | met | `SessionStore` (`tesseraql_sid`), in-memory or shared JDBC store |
| No session fixation | met | `BrowserAuthenticator.create()` mints a fresh id at login; no pre-auth session is adopted |
| Logout and "sign out others" invalidate server-side | met | `invalidate()` / `invalidateOthersFor()`; a consumed password reset drops every session of the subject |
| Session id re-issued on privilege change | gap | no explicit mid-session id rotation on elevation — a verify item carried forward |

#### V4 Access control

| Requirement | Status | Evidence |
| --- | --- | --- |
| Deny-by-default authorization | met | `PolicyEngine.authorize()` — no principal → `TQL-SEC-4011` (401), undefined/unsatisfied policy → `TQL-SEC-4031` (403) |
| Field- and row-level enforcement | met | `FieldPolicyApplier` (role-conditional column masking + row `unmaskWhen`); the `/*%scope*/` data-scoping directive fails closed (`TQL-SQL-2106/2107`) |
| Multi-tenant isolation | met | shared-schema / schema-per-tenant / db-per-tenant, tenant resolved per request and enforced in SQL; `TQL-TENANT` lint |
| Write-scope enforcement | gap | `TQL-SEC-4100` (write-scope bypass) is documented as planned, not yet implemented |

#### V5 Validation, sanitization, encoding, injection

| Requirement | Status | Evidence |
| --- | --- | --- |
| SQL injection prevented structurally | met | 2-way SQL bind values are always parameterized; the only text-interpolating path (embedded `{var}`) is enum-gated for request inputs (`TQL-SQL-2109`) |
| Output encoding / XSS | met | HTML renders through Thymeleaf context-aware auto-escaping; every HTML page must carry a CSP (`TQL-ADM-4704`) |
| Declared input validation | met | declarative input constraints + `validate:` rules ([declarative validation](declarative-validation.md)) |
| Parsers fail closed on hostile input | met | depth guards + explicit YAML constraints + the deterministic fuzz harness (this page) — three defects found and fixed |

#### V6 Cryptography

| Requirement | Status | Evidence |
| --- | --- | --- |
| Approved primitives, no home-rolled crypto | met | JDK-only crypto throughout (JCA); no custom cipher/MAC construction |
| Keys and secrets out of source | met | `SecretResolver` SPI (`${secret.*}`, env/file/vault); resolved lazily per use, never written to logs or generated artifacts |
| Randomness for security tokens | met | `SecureRandom` for salts, session ids, PKCE verifiers, reset tokens |

#### V7 Errors and logging

| Requirement | Status | Evidence |
| --- | --- | --- |
| Errors don't leak internals | met | `ErrorResponseRenderer` returns generic bodies; SAML/OIDC failures are generic 401/400, contents never echoed |
| A structured, greppable error taxonomy | met | 336 `TQL-<DOMAIN>-<NNNN>` codes across 33 domains, generated into the reference and drift-guarded |
| Audit trail without sensitive data | met | `tql_route_audit` records who/what/when over *declared* inputs only, and excludes any field marked `mask:`/`classification:` wholesale |
| Log correlation | met | MDC bridges the OTel `traceId`/`spanId` into logs |

#### V8 Data protection

| Requirement | Status | Evidence |
| --- | --- | --- |
| Sensitive fields masked/classified | met | field masking + `classification:` drive both response shaping and audit exclusion |
| Uploads gated before release | met | the attachment download gate refuses any non-clean object, including `pending` in async scan mode — fail-closed |
| At-rest encryption | n/a | a database/storage-layer concern; the framework stores blobs through a pluggable store and never mandates cleartext |

#### V9 Communications

| Requirement | Status | Evidence |
| --- | --- | --- |
| TLS in transit | met | TLS termination is the deployment edge's responsibility (reverse proxy / ingress), stated in the operator guide ([deployment](deployment.md), "Transport security"): HTTPS forwarded to the runtime, HSTS set at the edge, mTLS client-cert forwarding, and outbound cert verification kept on |

#### V11 Business logic and anti-automation

| Requirement | Status | Evidence |
| --- | --- | --- |
| Rate limiting | met | per-node token bucket (`RateLimiter`) and a cluster-wide leased budget (`ClusterRateLimiter`, `TQL-RATE-4291`) that degrades to per-node |
| Sequential/idempotent business flows | met | the transactional command engine (single-connection commit) + at-least-once messaging with idempotency keys; workflow transition guards |

#### V12 Files and resources

| Requirement | Status | Evidence |
| --- | --- | --- |
| Untrusted files handled safely | met | attachment scanning (fail-closed gate); the DuckDB fence confines engine file access to declared roots with a traversal-proof path-placeholder discipline |
| Object-store egress bounded | met | `BlobStore` S3 access is deny-by-default via `allowedBuckets`; the DuckDB remote tier uses prefix-scoped secrets |
| Resource-exhaustion parsing bounded | met | the parser depth guards and explicit YAML read constraints (this page) |

#### V13 API and web service

| Requirement | Status | Evidence |
| --- | --- | --- |
| Authentication on every service endpoint | met | SCIM and MCP routes gate on bearer + a policy; REST recipes carry the same `security:` contract |
| CSRF protection for browser sessions | met | `CsrfValidator` — state-changing `auth: browser` requests must present `X-CSRF-Token` matching the per-session token (`TQL-SEC-4032`) |
| Untrusted query input parsed safely | met | the SCIM `?filter=` parser is anchored and fuzz-verified ReDoS-safe (this page) |

#### V14 Configuration

| Requirement | Status | Evidence |
| --- | --- | --- |
| Deny-by-default egress | met | `tesseraql.http.outbound.allowedHosts` (and the copilot endpoint, `TQL-SEC-4085`); a bare `*` is rejected at marketplace admission (`TQL-ADM-4703`) |
| A hardening gate for shared apps | met | the admission profile enforces declarative-only, defined policies, bounded egress, and CSP before publish |
| Secret hygiene in development | met | `SECURITY.md` — never commit secrets; do not bind-mount host credential directories into the Dev Container |
| A published security policy surfaced to users | met | [`SECURITY.md`](https://github.com/ingcreators/tesseraql/blob/main/SECURITY.md) states the supported-versions policy (latest-release-only pre-1.0, tightening to a support window at 1.0), private vulnerability reporting, and dev-secret hygiene; linked from this page |

#### Gaps carried forward

The honest shortfalls, none blocking for the current pre-1.0 posture:

1. **A full threat-model refresh** (V1) — this assessment is the first artifact.
2. **Session id rotation on privilege elevation** (V3) — not explicit today; the fixation
   case is handled (a fresh id at login) and a password reset invalidates every session, so
   the residual is a role change applied to a live session.
3. **Argon2id as the shipped default KDF** (V2) — a deliberate choice, not a shortfall:
   PBKDF2 (100k iterations) is an ASVS-approved KDF and keeps the default JDK-only, where
   Argon2id would require a non-JDK dependency; Argon2id remains available behind
   `PasswordVerifier` for deployments that add it.
4. **Write-scope enforcement** (V4) — the scoping mechanism *works* for writes (a
   `/*%scope*/` predicate in an `UPDATE`/`DELETE` `WHERE` confines it, see
   [data scoping](data-scoping.md)); what is planned (`TQL-SEC-4100`) is a defense-in-depth
   guard that flags a write which *should* be scoped but omits the directive — an
   enhancement, not an open hole.

Two shortfalls recorded here were closed in this pass: the edge TLS/HSTS operator
expectations are now stated in [deployment](deployment.md), and `SECURITY.md` carries a
proper supported-versions policy and is linked above. A full threat-model refresh remains
the substantive follow-up.

## Deliberately out of scope (documented, not implied)

- **A certified audit.** This is a maintainer self-assessment; it makes no compliance
  claim.
- **Coverage-guided fuzzing on the CI path** — the harness is ready for a gated campaign
  when the dependency is worth it.
- **A full threat-model refresh and the SECURITY.md supported-versions/LTS statement** —
  tracked as follow-up hardening work, not part of this assessment.
