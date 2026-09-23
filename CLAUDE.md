# Claude Code Guidance

Read `AGENTS.md` first.

## Environment

Claude Code runs on the host, with the toolchain from `mise.toml`
(`docs/development-environment.md`). The Dev Container remains until `docs/host-development.md`
removes it; inside it, Claude state persists in the named volume at `/home/vscode/.claude`.

Never ask users to hand an agent host credentials (`~/.ssh`, `~/.aws`, `~/.gcloud`, …): on the
host the Bash sandbox denies them, and the Dev Container never mounts them.

## Worktrees

Any session that will change code must work in a git worktree (EnterWorktree), not in the
main checkout. The main checkout is the directory the IDE has open: editing files, switching
branches, or running builds there churns state under the editor and collides with other
sessions. Read-only sessions (research, review, Q&A) may stay in the main checkout.

The stash stack is shared across all worktrees — never use bare `git stash` / `git stash pop`;
prefer a WIP commit to set work aside.

## Parallel sessions

Several sessions share one machine (`docs/development-environment.md`, "Parallel sessions"):

- Run the pre-push full verify as `bash scripts/verify.sh` (output to a file as before). It holds
  a machine-wide lock and waits for another session's run instead of racing it; module runs
  (`./mvnw -pl <module> -am test`) need no lock.
- Never bind a fixed port. `dev --port 0 --embedded-db --repo .mvn/local-repo` — the origin is in
  each application's `work/dev.origin`; `mcp --transport http --port 0`. A test binds 0, or
  follows `docs/build.md` "Ports in tests".
- With `~/.mavenrc` sourcing `scripts/mavenrc`, `install` writes this worktree's
  `.mvn/local-repo`. Never `install` with `MAVEN_SKIP_RC=1`: that writes the shared `~/.m2`
  every worktree reads.
- The kind proof (`.github/kubernetes/proof.sh`) holds a machine-wide lease; a refusal naming
  another checkout means wait for its teardown.

## Permission mode

Use plan/normal mode for broad refactors. Avoid auto-accept for changes that touch:

- security
- build files
- release workflow
- devcontainer files
- generated artifact reproducibility
- route compiler behavior
