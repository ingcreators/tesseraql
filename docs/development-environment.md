# Development Environment

## Standard setup

Use:

- WSL 2 on Windows
- Docker Desktop with WSL integration
- VS Code Dev Containers
- Java 21 baseline
- Maven
- Docker/Testcontainers
- Codex / Claude Code inside the Dev Container when possible

## Agent credentials

The Dev Container persists agent state in named volumes:

```text
/home/vscode/.claude
/home/vscode/.codex
/home/vscode/.config/gh
```

Do not bind-mount broad host secret directories. Use SSH agent forwarding for Git.

## GitHub CLI (gh)

The `github-cli` Dev Container feature installs `gh`, so coding agents (Claude Code) can
create pull requests and releases from inside the container. Authenticate one of two ways:

1. **Interactive device flow** (simplest): run `gh auth login` in the container terminal,
   pick `GitHub.com` / `HTTPS` / browser login. The credential lives in the
   `/home/vscode/.config/gh` named volume, so it survives container rebuilds.
2. **Repository-scoped token**: copy `devcontainer.local.env.example` to
   `devcontainer.local.env` (gitignored, loaded by the compose file) and set `GH_TOKEN` to a
   fine-grained PAT limited to this repository (Contents and Pull requests read/write).
   `gh` picks the variable up without any login.

Verify with `gh auth status`. Rebuild the container after changing `devcontainer.local.env`
(compose reads it at container creation).

## Maven Wrapper

The initial scaffold does not commit `maven-wrapper.jar`. Generate the wrapper after opening the devcontainer:

```bash
./scripts/bootstrap-maven-wrapper.sh
```
