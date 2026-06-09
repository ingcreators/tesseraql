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
```

Do not bind-mount broad host secret directories. Use SSH agent forwarding for Git.

## Maven Wrapper

The initial scaffold does not commit `maven-wrapper.jar`. Generate the wrapper after opening the devcontainer:

```bash
./scripts/bootstrap-maven-wrapper.sh
```
