# Security Policy

## Supported versions

| Version line | Status | Security fixes |
| --- | --- | --- |
| The latest released `0.x` | Supported | Yes — fixes ship in the next `0.x` release |
| Any earlier `0.x` | Not supported | No — upgrade to the latest release |

Before 1.0.0, TesseraQL follows a **latest-release-only** policy: there are no maintenance
branches and no backports, so the way to receive a security fix is to upgrade to the newest
release (the [releases page](https://github.com/ingcreators/tesseraql/releases) lists the
current one). Minor `0.x` releases may change APIs and YAML contracts; such changes are
called out in [`CHANGELOG.md`](CHANGELOG.md). The runtime supports the Java baseline (21) and
compatibility target (25) documented in `AGENTS.md`; older JDKs are not supported.

At **1.0.0** this policy tightens into a defined support window (a supported `1.x` line with a
stated maintenance duration); the statement here will be updated to name it at that release.
A framework security review and hardening self-assessment is maintained at
[`docs/security-hardening.md`](docs/security-hardening.md).

## Reporting vulnerabilities

Please report vulnerabilities privately via GitHub's private vulnerability reporting:
**Security &gt; Advisories &gt; Report a vulnerability** on this repository, or
<https://github.com/ingcreators/tesseraql/security/advisories/new>.

Do not open public issues for security reports. You should receive an initial response
within a week.

## Dependency advisories

Dependabot's alerts are triaged weekly. An alert against a direct dependency becomes a pull
request the usual way. An alert against a **transitive** npm dependency — the `docs-site/` and
`vscode-extension/` trees — regularly cannot, and the run fails with
`security_update_not_possible`. That has happened repeatedly since 2026-07 (`fast-uri`,
`undici`, `js-yaml`); those are cleared by hand, and this is how.

**Why the bot cannot do it.** Two reasons, both outside this repository. It resolves the
advisory against the package's `latest` dist-tag, which for `fast-uri` is a 4.x that `ajv`'s
declared `fast-uri: "^3.0.1"` forbids. And pinning a transitive target is a silent no-op under
pnpm (pnpm#12744): the empty `conflicting-dependencies: []` in the failure log is that no-op,
not a diagnosis.

Neither is a misconfiguration, and neither has a configuration fix. Version updates open pull
requests for direct dependencies only, and the direct parent is already newest. Nothing in this
repository prevents the recurrence — expect it again.

**Clearing it.** One command, run in the workspace the alert's manifest path names:

```bash
cd vscode-extension   # or docs-site
pnpm --version        # must match packageManager in that directory's package.json
pnpm update fast-uri --depth Infinity --lockfile-only
```

The directory is the load-bearing part: pnpm switches to the pinned version only when it runs
from the package directory, and the pnpm on `PATH` at the repository root is a different one.
A newer pnpm rewrites the lockfile with peer-dependency suffixes, which CI's
`pnpm install --frozen-lockfile` then rejects. Target the advisory's first patched version,
not the tip of the line. Commit the lockfile alone, with no manifest change. Precedents:
`e421d992c`, `9c491df44`.

**What is at stake.** Both trees are build-time only, and the extension ships nothing from
either: its `package.json` declares no `dependencies`, packaging runs
`vsce package --no-dependencies`, and `.vscodeignore` drops `node_modules/**`. That makes the
fix routine rather than urgent — not optional, because the alert stays open until the floor
moves.

## Development secrets

Do not commit secrets.

Do not bind-mount broad host secret directories into the Dev Container, such as:

- `~/.ssh`
- `~/.aws`
- `~/.gcloud`
- `~/.azure`
- `~/.docker`

Prefer:

- SSH agent forwarding for Git
- repository-scoped tokens
- short-lived CI secrets
- named volumes for agent login state
- `.devcontainer/*.local.env` for local-only environment variables

