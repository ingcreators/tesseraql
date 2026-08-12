# Route governance

Some routes deserve a second pair of eyes: the one that writes without authentication, the
one that binds Java, the one that takes input nobody declared. Route governance is the
machine-checkable version of that judgement. It **assesses** every route, and **gates** the
ones your policy says need review until someone approves them.

It runs on every build — `./mvnw verify` includes it, and `tesseraql governance --app .` is
the CLI form — so the answer arrives in the pull request rather than in an audit months
later.

This is the everyday gate, and it is the one to configure first. The
[admission profile](admission.md) is a stricter, separate bar that applies only when you
ship an application for *other people* to install.

## What it assesses

Every route gets a **mode** and a **risk score**. Neither can be declared: both are derived
from what the route actually uses, so a route cannot claim to be tame.

### Modes

| Mode | Means |
| --- | --- |
| `managed` | A standard recipe under the full framework guardrails. |
| `extended` | The route binds a runtime Java service provider (a `sources.<name>.service:` arm). |
| `advanced` | The route **writes without authentication** — the guardrails are bypassed. |

`advanced` wins over `extended` when both apply. Read-only surfaces — MCP resources, UI
resources — are never `advanced`, because they cannot write.

### Risk factors

The score is a sum of weights, and every factor that contributed is reported verbatim so a
reviewer can see exactly why a route scored what it did.

| Factor | Weight |
| --- | --- |
| Write route without authentication | 4 |
| Write route (`command-json`) without an idempotency declaration | 2 |
| Binds undeclared request input | 2 |
| Public read route | 1 |
| Write route without an authorization policy | 1 |
| Binds a runtime service provider | 1 |
| Generates a file download | 1 |

## What it gates

Nothing, until you say so. Two keys turn assessment into a gate:

```yaml
# config/tesseraql.yml
tesseraql:
  governance:
    maxRiskScore: 5              # a route scoring higher needs an approval
    requireApproval: [advanced]  # these modes always need one, whatever they score
```

With neither key set, `tesseraql governance` reports and passes. A sensible starting point is
`requireApproval: [advanced]` — an unauthenticated write is worth a conversation every time —
and a `maxRiskScore` loose enough that your existing routes pass, tightened later.

## Approving a route

Approvals live in `governance/approvals.yml`, and pin each reviewed route to the SHA-256 of
its **source at review time**:

```yaml
approvals:
  - route: users.deactivate
    sha256: 4f2a...
    approvedBy: reviewer-id
```

The hash is what makes this a review rather than a checkbox. Editing an approved route
changes its hash, which invalidates the approval and fails the gate again until someone
re-approves. A change to a review-worthy surface therefore always shows up as a diff to the
ledger, in the same pull request as the change itself.

When the gate fails it prints the current hash, so approving is a copy-paste after the
review, not a separate computation.

`tesseraql governance --app . --no-fail-on-violation` reports without failing, which is
useful when adopting the gate on an existing application.

## Extending an application, under governance

Governance is not there to stop you writing Java. It is there to make sure that when you do,
someone knows. The [extension ladder](extending.md) covers what you can add and how; this is
what each rung costs you here:

| What you add | Mode | Risk | Also |
| --- | --- | --- | --- |
| Declarations only | `managed` | — | — |
| A custom expression function (a module) | `managed` | — | Fails `tesseraql admission` |
| A `service:` binding on a route | **`extended`** | +1 | Fails `tesseraql admission` |
| A plugin jar under `plugins/` | route modes unchanged | — | Fails `tesseraql admission`; must be signed |

So the everyday cost of extending is small and visible: an `extended` route shows up in the
report, and if your policy requires approval for `extended`, it needs one. The cost only
becomes absolute when you want to distribute the application
([admission.md](admission.md)).

## Where to see it

- **`tesseraql governance --app .`** — the report and the gate.
- **`./mvnw verify`** — the same gate, in CI, no database needed.
- **[Studio](studio.md)'s Security screen** — the effective policy for every route.
- **The [documentation portal](documentation-portal.md)** — assessments alongside the route
  specs.

`TQL-GOV-3001` is the violation code: a route that needs review has no approval pinning its
current hash.

## Next

- [extending.md](extending.md) — what you can add beyond declarations, and how.
- [admission.md](admission.md) — the stricter bar for applications other people install.
- [promotion.md](promotion.md) — where this gate sits in the path to production.
