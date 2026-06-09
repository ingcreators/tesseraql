# Security Policy

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

## Reporting vulnerabilities

Until a formal security contact is established, open a private security advisory in GitHub if available.
