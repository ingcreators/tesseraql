# Route defaults

> **Status: shipped (mechanisms).** The path-matched security defaults
> (`tesseraql.security.defaults.routes`, user-facing docs in
> [authentication.md](authentication.md#route-security-defaults)) and the default response
> headers (`tesseraql.security.responseHeaders`, user-facing docs in
> [response-shaping.md](response-shaping.md#default-response-headers)) are implemented. The
> remaining slice is the gallery/scaffolder migration off the per-route header blocks.

**Route defaults** let the application declare, once in `config/tesseraql.yml`, the per-route
settings that are the same for every route of a kind — and let route files state only what
differs. The evidence for the gap is mechanical: the scaffolder pastes an identical four-header
security block (`Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`,
`Referrer-Policy`) into every generated HTML route, and the gallery apps now carry ninety verbatim
copies of it, with the first divergent CSP variants already present. A security control that lives
in ninety places is edited in eighty-seven of them.

It builds on:

- **[Security hardening](security-hardening.md)** — the headers being centralized and the
  deny-by-default stance the merge rules must preserve.
- **[Response shaping](response-shaping.md)** — `response.html.headers`, the per-route map the
  default merges into.
- **The central policy table** ([authentication](authentication.md)) — `security.policies` is
  already declare-once/reference-by-name; path-matched defaults extend where a policy *applies*,
  not what it *is*.

## Shipped: path-matched security defaults

The original design here had two mechanisms: wiring the kind-keyed `security.defaults.api`/`htmx`
config shape (which the scaffolder emitted but no compiler code ever read), and a separate
path-prefix policy default. Implementation collapsed them into one, because the kind-keyed shape
is not decidable: `command-json` serves both browser htmx writes and bearer API writes, so
nothing in a route file says which family it belongs to. What does discriminate — by explicit
framework convention ("API vs page vs fragment is a URL convention, not a folder rule") — is the
served URL path. The retired shape is flagged by lint (`TQL-SEC-4130`) and was dropped outright
(pre-1.0, no compatibility shims).

The shipped mechanism, declared once:

```yaml
tesseraql:
  security:
    defaults:
      routes:
        - match: /api/**
          auth: bearer
        - match: /**
          auth: browser
          csrf: auto
```

Semantics (full user-facing description in
[authentication.md](authentication.md#route-security-defaults)):

- Rules are evaluated in declaration order against the served URL path; the **first matching
  rule** contributes defaults — firewall-style, decidable by reading top to bottom. (The draft's
  "most specific match wins" was dropped: specificity over globs is ill-defined; order is not.)
- `*` matches within a segment, `**` across segments, a trailing `/**` also matches the bare
  prefix.
- Merge is per key; **route-local keys always win**. A rule can default `auth`, `csrf`
  (`auto`/`true`/`false`), and `policy`.
- A route whose effective auth is `public` never inherits a policy; the combination of an
  explicit `public` under a policy-carrying rule is linted (`TQL-SEC-4131`, warning).
- `csrf: auto` resolves to required exactly when effective auth is `browser` and the method is
  not `GET`.
- Resolution happens at **manifest load**: the compiler, linter, coverage, OpenAPI, and Studio
  all see fully explicit effective values; generated artifacts stay reproducible and reviewable.
- A malformed rule fails the load (`TQL-SEC-4132`, error) — a mis-typed security control must
  not silently no-op, which is exactly how the kind-keyed shape failed.

The scaffolder emits this shape in new apps' config, the CRUD scaffolder generates slim
security blocks (policy only) when the target app's defaults cover its pages, and all five
gallery apps rely on the defaults — their route files state only `policy:` and the two
deliberately public shells. A posture test pins every gallery route to an explicit effective
auth mode so the defaults cannot silently stop covering a route.

## Shipped: default response headers

```yaml
security:
  responseHeaders:
    Content-Security-Policy: "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
    X-Content-Type-Options: nosniff
    X-Frame-Options: DENY
    Referrer-Policy: no-referrer
```

Merge rules, per header name:

- The default set is merged into every HTML, file, and stream response at compile time.
- A route-local `response.html.headers` entry **overrides** the default for that header name.
- A route declares a header with the literal value `unset` to **suppress** a default it must not
  send (rare; the case is a route embedded by an external page needing a different
  `frame-ancestors`).
- Route-locally restating a header **identically** to the default is a warning-level lint — it is
  the leftover copy-paste this feature deletes.
- Overriding a default with a **weaker** value (removing `X-Frame-Options`, relaxing CSP) is a
  warning-level lint. The lint cannot judge every CSP delta; it flags removal and
  wildcard-broadening, and stays silent on genuinely ambiguous edits.

The scaffolder stops pasting the four-header block into routes and emits the
`security.responseHeaders` default once per app. Hardening the whole app becomes a one-line
config edit.

## Out of scope

- Defaults for non-security blocks (`page:`, `inputPolicy:`, `cache:`). The model defaults
  already cover them (`PageSpec.effectiveSize()`, `InputPolicy.defaults()`), and current usage
  shows no duplication worth new surface.
- Per-environment default sets. Environment overlays are [deployment](deployment.md) territory;
  this design keeps one default set per app.
- Method-scoped `match` rules (`match: POST /api/**`). Policies that differ between read and
  write on the same path stay route-local; adding a method dimension to rules doubles the
  reasoning surface for little corpus evidence.

## Resolved questions (response headers)

1. Suppression is the explicit marker `X-Frame-Options: unset` — YAML `null` cannot reach the
   response model (header maps reject null values), and the marker reads unambiguously.
2. A deliberate weakened header is owned by carrying the warning (`TQL-SEC-4134`); no dedicated
   acknowledgment surface until the warning proves noisy in practice.
3. The draft's "route sets a header to `null`" merge rule shipped as `unset`; info-level lint
   severity shipped as warning (the linter has two severities by design).

## Related designs

[Field domains](field-domains.md) shares field-level knowledge; [ambient
parameters](ambient-params.md) shares SQL parameter wiring. The three are independent slices and
ship separately.
