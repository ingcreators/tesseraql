# Security Policy

## Supported versions

Security fixes target the latest 0.x release. Until 1.0.0 there are no maintenance branches;
upgrade to the newest release to receive fixes.

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

