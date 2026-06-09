# Claude Code Guidance

Read `AGENTS.md` first.

## Dev Container

Claude Code is expected to run inside the Dev Container when possible. The container persists Claude state in a named volume mounted at:

```text
/home/vscode/.claude
```

Do not ask users to mount host `~/.ssh`, `~/.aws`, `~/.gcloud`, or other broad secret directories into the container.

## Permission mode

Use plan/normal mode for broad refactors. Avoid auto-accept for changes that touch:

- security
- build files
- release workflow
- devcontainer files
- generated artifact reproducibility
- route compiler behavior
