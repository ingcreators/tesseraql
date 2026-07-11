# Marketplace admission profile

The admission profile is the machine-checkable bar a shared app must clear before anyone
else runs it: run `tesseraql admission --app .` (or the `tesseraql:admission` Maven goal,
bound to `verify`) and ship only on exit 0.

The profile composes the existing gates and adds the marketplace constraints:

| Code | Constraint |
| --- | --- |
| `TQL-ADM-4701` | **Declarative-only.** No plugin jars, no runtime service bindings (`mode: extended`), no unauthenticated write surfaces (`mode: advanced`) — the framework is the sandbox only when every behavior is interpreted from the documents. |
| `TQL-ADM-4702` | **Deny-by-default policies defined.** A route referencing a policy the config does not define is only a lint *warning* inside one team ("another environment defines it"); for a shared app the policy must be defined here. |
| `TQL-ADM-4703` | **Egress bounded.** A bare `*` in `http.outbound`/`connectors.poll` allow-lists is not an allow-list. |
| `TQL-ADM-4704` | **CSP intact** on every HTML page (fragments under the documented `/fragments/` convention are exempt — the host page's CSP governs the document). |
| `TQL-ADM-4705` | **Governance clean.** Every review-worthy surface is approved in `governance/approvals.yml` at its current hash. |
| `TQL-ADM-4706` | **Lint clean.** Every `AppLinter` error fails admission. |

The gallery starter apps under `examples/` are held to this bar in CI — the shipped
starters pass the same gate that shared apps are held to. Provenance (signing, SBOM, hash
pinning) rides the existing `tesseraql:release-evidence` pipeline and `.tqlapp` sha256
pinning; see [promotion](promotion.md) and [deployment](deployment.md).
