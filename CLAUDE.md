# Claude Code Guidance

Read `AGENTS.md` first.

## Dev Container

Claude Code is expected to run inside the Dev Container when possible. The container persists Claude state in a named volume mounted at:

```text
/home/vscode/.claude
```

Do not ask users to mount host `~/.ssh`, `~/.aws`, `~/.gcloud`, or other broad secret directories into the container.

## Worktrees

Any session that will change code must work in a git worktree (EnterWorktree), not in the
main checkout. The main checkout is the directory the IDE has open: editing files, switching
branches, or running builds there churns state under the editor and collides with other
sessions. Read-only sessions (research, review, Q&A) may stay in the main checkout.

The stash stack is shared across all worktrees — never use bare `git stash` / `git stash pop`;
prefer a WIP commit to set work aside.

## Permission mode

Use plan/normal mode for broad refactors. Avoid auto-accept for changes that touch:

- security
- build files
- release workflow
- devcontainer files
- generated artifact reproducibility
- route compiler behavior
