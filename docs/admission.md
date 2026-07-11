# Marketplace admission profile

You built an app that other people will install, or you are vetting one before running it:
the admission profile is the machine-checkable bar a shared app must clear before anyone
else runs it. Run `tesseraql admission --app .` (or the `tesseraql:admission` Maven goal,
bound to `verify`) and ship only on exit 0.

The profile composes the existing gates and adds the marketplace constraints:

| Code | Constraint |
| --- | --- |
| `TQL-ADM-4701` | **Declarative-only.** No plugin jars, no `mode: extended` routes (a route is *extended* when it binds a runtime Java service instead of being interpreted from the documents), no `mode: advanced` routes (a route is *advanced* when it writes without authentication, bypassing the guardrails) — the framework is the sandbox only when every behavior is interpreted from the documents. |
| `TQL-ADM-4702` | **Deny-by-default policies defined.** A route referencing a policy the config does not define is only a lint *warning* inside one team ("another environment defines it"); for a shared app the policy must be defined here. |
| `TQL-ADM-4703` | **Egress bounded.** A bare `*` in `http.outbound`/`connectors.poll` allow-lists is not an allow-list. |
| `TQL-ADM-4704` | **CSP intact** on every HTML page (fragments under the `/fragments/` convention described in [hypermedia-ui.md](hypermedia-ui.md) are exempt — the host page's CSP governs the document). |
| `TQL-ADM-4705` | **Governance clean.** Every review-worthy surface is approved in `governance/approvals.yml` at its current hash. |
| `TQL-ADM-4706` | **Lint clean.** Every lint error fails admission. |

Declarative-only also covers the expression language: `tesseraql admission` lints without
the app's `tesseraql.modules` classpath, so an expression calling an operator-installed
[custom function](declarative-validation.md#custom-functions) fails the lint gate
(`TQL-ADM-4706`) as an unknown function. Custom Java — whether a runtime service, a plugin
jar, or an expression function — is `extended` territory by design.

## When it fails

Each failure prints one line — the code, the subject (a route id or file), and the reason —
and the command exits 1. Fix the surface the finding names and rerun. For `TQL-ADM-4705`
the fix is a review: approve the surface at its current hash in `governance/approvals.yml`
(the route review ledger — see [application layout](app-layout.md)), so a change to a
review-worthy surface always shows up as a diff to the ledger.

The gallery starter apps under `examples/` are held to this bar in CI — the shipped
starters pass the same gate that shared apps are held to. Provenance (signing, SBOM, hash
pinning) rides the existing `tesseraql:release-evidence` pipeline and `.tqlapp` sha256
pinning; see [promotion](promotion.md) and [deployment](deployment.md).
