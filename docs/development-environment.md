# Development environment

> **Designed:** the Dev Container retires in favour of the host, with the toolchain pinned by
> `mise.toml` and rules that let several agent sessions run side by side without colliding —
> [host-development.md](host-development.md). Until its last slice ships, the setup below
> stands.

## Standard setup

Use:

- WSL 2 on Windows
- Docker Desktop with WSL integration
- VS Code Dev Containers
- Java 25 baseline
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

The wrapper is committed (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` —
`distributionType=only-script`, so no jar is ever needed) and Dependabot bumps it. Nothing to
generate: run the same command CI runs.

```bash
./mvnw -B -ntp verify
```
