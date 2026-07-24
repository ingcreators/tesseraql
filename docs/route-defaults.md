# Route defaults

> **Status: design.** This document precedes implementation. Nothing described here is part of
> the released YAML surface yet; it is the agreed target for review.

**Route defaults** let the application declare, once in `config/tesseraql.yml`, the per-route
settings that are the same for every route of a kind — and let route files state only what
differs. The evidence for the gap is mechanical: the scaffolder pastes an identical four-header
security block (`Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`,
`Referrer-Policy`) into every generated HTML route, and the gallery apps now carry ninety verbatim
copies of it, with the first divergent CSP variants already present. A security control that lives
in ninety places is edited in eighty-seven of them.

This design covers three defaults, in increasing order of ambition:

1. **Wiring `security.defaults`** — the config key already exists and is documented, but the
   compiler does not read it; every route still spells out `auth:` and `csrf:`. The first slice
   is closing that gap, not adding surface.
2. **Default response security headers** — an app-level header set merged into every HTML/file
   response.
3. **Path-prefix policy defaults** — an authorization policy applied to a route subtree.

It builds on:

- **[Security hardening](security-hardening.md)** — the headers being centralized and the
  deny-by-default stance the merge rules must preserve.
- **[Response shaping](response-shaping.md)** — `response.html.headers`, the per-route map the
  default merges into.
- **The central policy table** ([authentication](authentication.md)) — `security.policies` is
  already declare-once/reference-by-name; path-prefix defaults extend where a policy *applies*,
  not what it *is*.

## 1. `security.defaults` — finish the wiring

The scaffolder emits, and the docs describe, per-kind defaults:

```yaml
security:
  defaults:
    htmx: { auth: browser, csrf: auto }
    api:  { auth: bearer }
```

Today no compiler code consumes this block, which makes it documentation of an intention. The
slice: the route compiler resolves a route's effective `security:` as *route-local value, else
kind default, else the framework's current built-in behavior*. Routes may then drop their
`auth:`/`csrf:` lines; the scaffolder stops emitting them. A route can still opt out explicitly
(`auth: public`) — explicit always beats ambient.

Because the key already ships in scaffolded apps, wiring it must not change the effective posture
of an app whose routes all state `auth:` explicitly — which is every app today. The slice is
behavior-adding only for routes that omit the key.

## 2. Default response headers

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
- A route sets a header to `null` to **suppress** a default it must not send (rare; the case is a
  route embedded by an external page needing a different `frame-ancestors`).
- Route-locally restating a header **identically** to the default is an info-level lint — it is
  the leftover copy-paste this feature deletes.
- Overriding a default with a **weaker** value (removing `X-Frame-Options`, relaxing CSP) is a
  warning-level lint. The lint cannot judge every CSP delta; it flags removal and
  wildcard-broadening, and stays silent on genuinely ambiguous edits.

The scaffolder stops pasting the four-header block into routes and emits the
`security.responseHeaders` default once per app. Hardening the whole app becomes a one-line
config edit.

## 3. Path-prefix policy defaults

```yaml
security:
  defaults:
    paths:
      - match: web/admin/**
        policy: iam.admin.view
      - match: web/api/**
        auth: bearer
```

- `match` is an ant-style path pattern over the route file's app-home path — the same identity
  the route id derives from, so the mapping is reviewable without running anything.
- The **most specific matching prefix** contributes defaults; route-local keys still win.
- A path default never weakens: if both a path default and a route declare a policy, the route's
  policy is used, but a route under a policy-defaulted prefix that declares `auth: public` gets a
  warning-level lint — that combination is either deliberate (annotate to acknowledge) or the
  exact mistake the default exists to catch.

## Resolution is compile-time

Like [field domains](field-domains.md), all three defaults resolve in the route compiler. The
compiled route carries fully explicit values: the runtime, the operations console, and the docs
portal see effective settings, and generated artifacts remain reproducible — a config default
change produces a visible diff in every affected compiled route, which is exactly what a security
reviewer wants to see.

## Lint

Final numbers are assigned against the registry at implementation, in the `TQL-SEC-*` family:
identical-restatement (info), weakened-header override (warning), `auth: public` under a
policy-defaulted prefix (warning), unknown policy name in a path default (error — same check
route-local policies get today).

## Out of scope

- Defaults for non-security blocks (`page:`, `inputPolicy:`, `cache:`). The model defaults
  already cover them (`PageSpec.effectiveSize()`, `InputPolicy.defaults()`), and current usage
  shows no duplication worth new surface.
- Per-environment default sets. Environment overlays are [deployment](deployment.md) territory;
  this design keeps one default set per app.

## Open questions

1. Does `null`-suppression read clearly in YAML, or should suppression be an explicit marker
   (`X-Frame-Options: unset`)? `null` round-trips awkwardly through some editors.
2. Should the acknowledgment for a deliberate `auth: public` under a defaulted prefix reuse the
   existing lint-suppression comment convention, or a dedicated `security.acknowledge:` key?

## Related designs

[Field domains](field-domains.md) shares field-level knowledge; [ambient
parameters](ambient-params.md) shares SQL parameter wiring. The three are independent slices and
ship separately.
