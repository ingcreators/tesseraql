# Development environment

The framework is developed on the host — a WSL 2 distribution on Windows, or Linux or macOS —
with its toolchain pinned by `mise.toml` at the repository root, and with several agent
sessions running side by side, each in its own worktree. The design and its reasons are in
[host-development.md](host-development.md). The Dev Container still works until that record's
last slice removes it; its section is at the end.

## The toolchain

Install [mise](https://mise.jdx.dev/) (2026.9.12 or later; `mise bootstrap` is recent), then,
in the checkout:

```bash
mise install                      # Temurin 25, Node 22; pnpm through corepack
mise bootstrap packages apply     # unzip, bubblewrap, socat (apt, one sudo)
bash scripts/verify-dev-env.sh    # says what is missing
```

Two lines of user configuration make every shell and every worktree see the tools:

```bash
# ~/.bashrc, above the "If not running interactively" guard: agents, herdr panes and the IDE
# run non-interactive shells, which never fire mise's prompt hook — shims work there.
eval "$(mise activate bash --shims)"
```

```toml
# ~/.config/mise/config.toml: every worktree is a new path, and mise trusts a config per path.
# Under [settings] — at the top level mise ignores it as an unknown field.
[settings]
trusted_config_paths = ["/home/<you>/workspace/tesseraql"]
```

Leave `JAVA_HOME` unset, or set it to `$(mise where java)`: `./mvnw` prefers `JAVA_HOME` over
the `java` on the `PATH`, so one pointing at another JDK builds with that JDK instead.

Docker is the host's own — Docker Desktop's WSL integration, the distribution's Docker
Engine, or its snap — and Testcontainers reaches its containers on `localhost`.

The IDE opens the main checkout over VS Code's WSL remote. Its Java extension needs the same
JDK: point `java.jdt.ls.java.home` at `$(mise where java)` in your user settings (not the
committed `.vscode/settings.json`, whose path would be one machine's).

## Maven

The wrapper is committed (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` —
`distributionType=only-script`, so no jar is ever needed) and Dependabot bumps it. It is the
build's Maven: `mise.toml` declares none.

```bash
./mvnw -B -ntp verify
```

**One local repository per checkout.** Worktrees that install into one shared `~/.m2` install
over each other's `0.19.0-SNAPSHOT`s, and whichever ran last is what every `dev` resolves.
Source `scripts/mavenrc` from `~/.mavenrc`:

```bash
echo '. /home/<you>/workspace/tesseraql/scripts/mavenrc' >> ~/.mavenrc
```

Maven's own launcher sources `~/.mavenrc`, so a run inside a TesseraQL checkout installs into
`<checkout>/.mvn/local-repo` and reads downloads from `~/.m2/repository`, which no build writes
any more; a run anywhere else is unchanged, and so is CI. A `dev` in a worktree resolves its
declared modules from that worktree's repository: `--repo .mvn/local-repo`. The CLI's own
resolver does not read the shared repository behind it, so the first such `dev` downloads the
modules' third-party jars into the worktree's repository. Refresh the shared
repository after a dependency bump with `MAVEN_SKIP_RC=1 ./mvnw -B -ntp -DskipTests verify` —
never `install`, which would put the framework's own snapshots back where every worktree reads
them.

## Parallel sessions

[herdr](https://herdr.dev/) keeps each agent session in a pane of its own and tells which one
is waiting for an answer; tmux or separate terminals take the same rules.

```bash
mise use -g herdr
herdr integration install claude   # lets herdr resume a pane's Claude Code session
herdr                              # then, per pane: claude --worktree <slice>
```

- **One worktree per session.** The main checkout is the IDE's; nobody builds there.
- **One full verify at a time.** `scripts/verify.sh` runs `./mvnw -B -ntp clean verify` under a
  machine-wide lock and waits for another session's run; module runs
  (`./mvnw -pl <module> -am test`) do not queue.
- **No fixed ports.** `dev --port 0 --embedded-db --repo .mvn/local-repo` binds a free port and
  writes its origin to each application's `work/dev.origin`; `mcp --transport http --port 0`
  prints the address it bound; the embedded database binds a free port of its own.
- **One kind proof at a time.** `.github/kubernetes/proof.sh` holds a machine-wide lease from
  its cluster phase to its teardown and refuses every phase another checkout runs.

## Agent credentials

On the host an agent's shell can read the whole home directory and, under WSL, the Windows
drives. Do not hand agents credentials: turn on Claude Code's Bash sandbox (`/sandbox`) with
`~/.ssh`, `~/.aws`, `~/.gcloud`, `~/.azure` and `~/.docker` denied. Testcontainers needs the
Docker socket and `docker` is incompatible with the sandbox, so `./mvnw` and `docker` run as
excluded commands; the sandbox holds for everything else. Its two dependencies are the
`bubblewrap` and `socat` packages `mise.toml` declares.

## GitHub CLI (gh)

`mise use -g github-cli` installs `gh`; authenticate with `gh auth login` (browser flow), or
export a fine-grained `GH_TOKEN` limited to this repository (Contents and Pull requests
read/write) from your own shell profile — no repository file holds it. Verify with
`gh auth status`.

## The Dev Container (until it retires)

`.devcontainer/` still builds the previous environment: VS Code Dev Containers over Docker,
Java 25, Node 22, Docker-outside-of-Docker, and the agents' state in named volumes
(`/home/vscode/.claude`, `/home/vscode/.codex`, `/home/vscode/.config/gh`). Do not bind-mount
broad host secret directories into it; use SSH agent forwarding for Git. Its `gh` reads
`GH_TOKEN` from `.devcontainer/devcontainer.local.env` (gitignored; copy the `.example`), and a
rebuild picks up a change. Moving off it without losing the volumes' state is
[host-development.md](host-development.md) decision 12.
