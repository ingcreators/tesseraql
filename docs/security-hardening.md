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

*Authored alongside this page; each chapter above expands to its requirement-level
met/partial/gap findings with code citations, and the gaps carried into follow-up work.*

## Deliberately out of scope (documented, not implied)

- **A certified audit.** This is a maintainer self-assessment; it makes no compliance
  claim.
- **Coverage-guided fuzzing on the CI path** — the harness is ready for a gated campaign
  when the dependency is worth it.
- **A full threat-model refresh and the SECURITY.md supported-versions/LTS statement** —
  tracked as follow-up hardening work, not part of this assessment.
