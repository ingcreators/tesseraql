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

