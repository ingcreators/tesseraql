# Host development: mise pins the toolchain, parallel sessions share nothing they can collide on, and the Dev Container retires

> **Status: designed 2026-09-23, measured against main `ddcc38cd7` (0.19.0-SNAPSHOT); S1
> and S2 shipped the same day (#1445, #S2PR).** The direction is the maintainer's, given in conversation: develop on the WSL 2
> host with the toolchain pinned by [mise](https://mise.jdx.dev/); run several Claude Code
> sessions side by side under [herdr](https://github.com/ogulcancelik/herdr), each in its own
> worktree; retire the Dev Container rather than keep it as a second path; lose none of the
> agent state its volumes hold; and let no two sessions collide on a port. Three slices, and
> one hand-run step between the second and the third:
>
> **S1** — ports: `dev --port 0` binds before the members boot and records the origin it got;
> the tests that pick a port and bind it later stop doing so where they can and retry where
> they cannot; the kind proof holds a machine-wide lock. It helps inside the container too, so
> it goes first: **shipped, #1445** (as recommended, with three findings of the slice's own:
> the kind proof's lock is a lease, not a flock — each phase is its own process and the
> cluster outlives it, decision 10; `NotificationIntegrationTest` needed no retry — one
> GreenMail for the class on its own socket's port replaces the extension that re-bound a
> picked port before every method, decision 8; and the pre-relay `503` is pinned by
> `GatewayFrontTest` on the front itself, because a forked `dev` cannot be caught between its
> bind and its boot — `DevPortZeroIntegrationTest` pins the order through the issuer, and a
> revert probe that built the origin from the requested port turned it red with
> `http://localhost:0`). **S2** — the host toolchain and the parallel-session rules:
> `mise.toml` — the tools and the OS packages the host needs — and a ledger that holds it to
> CI, one local Maven repository per worktree, one full verify at a time, the credential rule
> restated for a host, the setup page rewritten host-first. The container keeps working
> throughout: **shipped, #S2PR** (as recommended; measured with mise 2026.9.12 in an isolated
> data directory: `trusted_config_paths` belongs under `[settings]` — at the top level mise
> ignores it — and then trusts a new worktree under the checkout; `mise install` resolves
> Temurin 25.0.4 and Node 22.23; a non-interactive shell on shims alone runs both and corepack's
> pnpm 11.8.0; `mise bootstrap packages status` reads the three apt packages; `scripts/mavenrc`
> moves a checkout's local repository into `.mvn/local-repo`, leaves every other directory
> alone, and `MAVEN_SKIP_RC=1` bypasses it. Two findings: the CLI's own module resolver does not
> read the tail (decision 5, and filed below); and the Bash sandbox and the IDE's JDK cannot be
> measured inside the Dev Container, whose seccomp profile refuses the user namespaces
> bubblewrap needs — both move to the cutover's check, decision 12 step 7). **The cutover** — not a pull request: the container's
> state is backed up, restored on the host, re-keyed to the host's paths and checked, while the
> volumes stay untouched. **S3** — the Dev Container is deleted, from a session on the host,
> whose own pre-push ritual is the proof that the host builds.

## What was measured

Readings of the tree on main `ddcc38cd7`, of the running container, and of the tools' own
documentation, 2026-09-23. Two rows were run rather than read: row 3 (the live volumes) and
row 7 (Maven 3.9.16, in a scratch project).

| # | Reading | Result |
| --- | --- | --- |
| 1 | The container (`.devcontainer/Dockerfile`, `devcontainer.json`, `docker-compose.yml`) | The base image is `mcr.microsoft.com/devcontainers/java:3-25-bookworm`, whose JDK is Microsoft's build (`/usr/lib/jvm/msopenjdk-current`); every `setup-java` step under `.github/workflows/` says `distribution: temurin`, `java-version: '25'`. apt adds `curl git jq ripgrep unzip zip`; Node 22 comes from NodeSource and pnpm from an unpinned `npm install -g pnpm`, while both `package.json`s declare `"packageManager": "pnpm@11.8.0"` and CI's `setup-node` says 22. Features: docker-outside-of-docker, github-cli, claude-code. Five named volumes (`.claude`, `.codex`, `.config/gh`, `.m2`, `.npm`). `remoteEnv` sets `CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `TESSERAQL_PROFILE=dev` and `DB_HOST=db` (`devcontainer.json:23`); compose adds `postgres:16` as `db` and `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` (`docker-compose.yml:22`). |
| 2 | What the rest of the tree says about the container | Guards: `PluginVersionLedgerTest.java:120` reads `.devcontainer/Dockerfile` (no apt Maven) and `:169` walks `.devcontainer` and `deploy` for Dockerfiles; `ContributorSetupLedgerTest.java:25` lists `.devcontainer/devcontainer.json` among the setup files and asserts each is a regular file. Config: `.github/dependabot.yml:80-87` (the docker ecosystem on `/.devcontainer`), `.gitignore:39`. Living guidance: `AGENTS.md:17` ("Dev environment: Dev Container"), `CLAUDE.md` "Dev Container", `CONTRIBUTING.md:5`, `README.md:56-57` and `:142`, `SECURITY.md:74-80`, `security-hardening.md:264` (the ASVS row cites that rule), [development-environment.md](development-environment.md) as a whole, `.vscode/settings.json:15-16`. Scripts: `verify-dev-env.sh` calls `ensure-devcontainer-volumes.sh`; `devcontainer-post-start.sh`. Comments: `pom.xml:625-627`, `tesseraql-cli/pom.xml:171`, `ManifestLoaderTest.java:40-41`. The records that name the container — jvm-baseline, release-and-ci-hardening, audit-low-leads, codec-discovery, deployment-maturity — say what was true when they were written. `DB_HOST` is read by one example (`examples/user-admin-app/config/application.yml:8`, default `localhost`); nothing reads `TESSERAQL_PROFILE` ([config-consumers.md](config-consumers.md), the `tesseraql.runtime.profile` row). |
| 3 | The agent state the volumes hold (run on the live container) | Volumes `tesseraql_devcontainer_tesseraql-{claude,codex,gh,m2,npm}-<devcontainerId>`, plus `tesseraql_devcontainer_tesseraql-pgdata`: 1.6 GB, 153 MB, 12 KB, 2.0 GB, 196 MB. Claude Code files a project under its absolute path with every non-alphanumeric character turned into `-`: `projects/-workspace-tesseraql` holds the memory (118 files) and 58 transcripts, and each worktree has a key of its own (`-workspace-tesseraql--claude-worktrees-<name>`, 19 present). With `CLAUDE_CONFIG_DIR` set, `.claude.json` lives inside that directory; it holds one project entry, keyed `/workspace/tesseraql`. `plugins/known_marketplaces.json` holds absolute `/home/vscode/.claude/…` paths. Five lines of memory name a container path, all as history. `~/.codex/config.toml` holds no path. **The host checkout's path differs from `/workspace/tesseraql`, so every key changes.** |
| 4 | Docker, and what the container's boundary was | The daemon is the WSL distribution's own (here the snap package; volumes under `/var/snap/docker/common/var-lib-docker/volumes/`), and the container reaches it through a bind of `/var/run/docker.sock`. A process holding that socket can start a container that mounts any host path. The container kept host credentials out of *view* — nothing mounted them — but it was never a boundary against a process that set out to reach them. |
| 5 | Who listens on a fixed port | `dev` defaults to 8080 (`TesseraqlCli.java:120`) and builds its origin from the *requested* port — `new DevMode(dbOverride, "http://localhost:" + port, …)` at `:278` — before `MultiAppGateway.start` (`:282`). The gateway boots every member first (`MultiAppGateway.java:264`) and listens last (`:176`, `actualPort()`); `MultiAppHost.java:222-224` settles that origin into the host context when the stack file declares none, and it becomes the stack issuer (`:240`) and the session tokens' and MCP routes' origin. **So `dev --port 0` today boots every member believing it is at `http://localhost:0`.** `host` defaults to 8080 (`HostCommand.java:42`) and serves a declared origin ([stack-architecture.md](stack-architecture.md) decision 22). `mcp --transport http` defaults to 8765 (`McpCommand.java:75`) and prints `http.url()`, read from the bound socket (`HttpTransport.java:65-68`) — port 0 works there already. Every example declares `server.port: 0`; `--embedded-db` binds a random port and leaves its URL at `work/embedded-db.jdbc` (`EmbeddedDbMarker.java:12-14`); `astro dev` moves to the next port when 4321 is taken. The kind proof: `kind.yaml:6` `name: two-node`, `:11` `hostPort: 30080`; `proof.sh:32-34`. Testcontainers: no fixed host port, container name, reuse flag or host network anywhere under `src/test` — every mapped port is the daemon's choice. |
| 6 | Tests that pick a port, close it, and bind it later | Most boots are port 0 since #1082 (`TesseraqlRuntime.start(appHome, 0)`, then `runtime.port()`). Nine files still pick first. **(a) No reason to:** `RouteWatchIntegrationTest.java:48`, `ReloadContentDiffIntegrationTest.java:52`. **(b) The port is written into config before the runtime boots:** `OidcLoginIntegrationTest.java:84` and `OidcUserLinkIntegrationTest.java:88` (the redirect URI), `OAuthIssuerUnificationIntegrationTest.java:69` (`externalOrigin`), `NotificationIntegrationTest.java:81` (GreenMail's SMTP port, which the config names; GreenMail 2.1.13 has `ServerSetup.dynamicPort()`, but its JUnit 5 extension is not started during `@BeforeAll`). **(c) The number is the assertion:** `BootFailureTeardownIntegrationTest.java:52` (it rebinds the same port after a failed boot), `EmbeddedDbDevIntegrationTest.java:256` (`--embedded-db-port`), `:315`, `:362`, `:379` and `StackRelayTest.java:956` (a port nothing should answer on); `EmbeddedDbShutdownIntegrationTest.java:152` holds its socket open, which is not a race. A picked port comes from the ephemeral range that every outbound connection on the machine also draws from, so the window grows with the number of builds running at once. |
| 7 | One Maven local repository for every checkout (run on Maven 3.9.16 in a scratch project) | `~/.m2/repository` is shared by the main checkout and every worktree. The reactor resolves siblings when `-am` is given, but three things read installed artifacts: a `-pl` run without `-am`, `dev` resolving a declared module ("`dev` resolves it from your local repository — which is why the first line installs", `README.md:67-69`), and the IDE. Two worktrees that both `install` write the same `0.19.0-SNAPSHOT` coordinates, and the last one wins for everyone. Measured: `.mvn/maven.config` interpolates `${maven.multiModuleProjectDirectory}`; with `-Dmaven.repo.local=<head>` and `-Dmaven.repo.local.tail=$HOME/.m2/repository`, a plugin present in the tail resolved from it, the three artifacts the tail lacked were downloaded into the head, `install` wrote the head only, and the tail was untouched. `bin/mvn` sources `~/.mavenrc` before it starts (`:41-42`) and places `$MAVEN_ARGS` before the command line's arguments (`:216`). `.mvn/maven.config` (`-B`, `-ntp`) is read by CI too, where ten `setup-java` steps cache `~/.m2/repository`. The CLI's embedded resolver honours `maven.repo.local` (`ConfigOptions.java:20-24`, `:49-54`, `--repo`); whether it honours the tail is unmeasured. |
| 8 | The tools | Claude Code 2.1.278: `claude --worktree [name]` creates a worktree for the session; the `EnterWorktree` tool puts them under `.claude/worktrees/` (S2 confirms the flag uses the same directory). herdr 0.9.1 (2026-09-16, Apache-2.0) is a terminal multiplexer whose server outlives its client; `mise use -g herdr` installs it; `herdr integration install claude` writes `$CLAUDE_CONFIG_DIR/hooks/herdr-agent-state.sh` and a `SessionStart` hook into the user's `settings.json`, so a pane is resumed with `claude --resume <id>` after a server restart; working, blocked and idle are read from the screen — "a detection heuristic, not a contract". mise trusts a `mise.toml` per path, and `trusted_config_paths` trusts every config under a listed path; shims resolve the version for the directory they run in and apply `[env]` only to the tool a shim launches; `mise activate` updates the environment from a prompt hook, which a non-interactive shell never runs. mise's `[bootstrap.packages]` declares OS packages as `"apt:<name>" = "latest"` (or a pinned version); declarations merge across the config hierarchy, so a project's `mise.toml` may add them; installation is explicit — `mise install` only prints a hint, and `mise bootstrap packages apply` runs `apt-get install -y`, elevated with sudo when needed, after reading state with `dpkg-query`. |
| 9 | What a full verify costs | The local full clean verifies of the job-inbox campaign (2026-09-22): 5,319-5,326 tests, about 13 minutes each on a 20-CPU container, every Testcontainers suite included. |
| 10 | What Claude Code's Bash sandbox needs on Linux and WSL 2 (its sandboxing documentation) | `bubblewrap` and `socat`, "with your distribution's package manager" (`sudo apt-get install bubblewrap socat`); ripgrep ships inside Claude Code; an optional seccomp filter comes from `npm install -g @anthropic-ai/sandbox-runtime`. On Ubuntu 24.04 and later, AppArmor blocks bubblewrap's user namespaces unless a profile grants them to `/usr/bin/bwrap`; this WSL 2 kernel has no `kernel.apparmor_restrict_unprivileged_userns` key (read from the container, which shares the host's kernel), so the restriction does not apply here. `docker` is incompatible with the sandbox (the fix is `excludedCommands`), and letting `/var/run/docker.sock` through `allowUnixSockets` "effectively grants access to the host system". Neither package is in mise's tool registry; conda-forge carries both (bubblewrap 0.11.2, socat 1.8.1.3), reachable through mise's conda backend, which mise marks experimental. WSL 1 is not supported. |

## The mechanism

```text
WSL 2 host — mise.toml: java temurin-25, node 22 (+ corepack → pnpm 11.8.0 from packageManager)
│
├─ main checkout ............ the IDE's; nobody builds here
├─ herdr server
│   ├─ pane: claude --worktree s1 → .claude/worktrees/s1
│   │     ./mvnw …  → installs into .claude/worktrees/s1/.mvn/local-repo   (head)
│   │               → reads downloads from ~/.m2/repository                (tail, read-only)
│   │     tesseraql dev --port 0 --embedded-db --repo .mvn/local-repo
│   │               → http://localhost:<ephemeral>, recorded in work/dev.origin
│   └─ pane: claude --worktree s2 → the same shape; shares only the tail and the Docker daemon
└─ scripts/verify.sh ........ one full verify at a time (flock), module runs unqueued
```

Nothing the design adds is WSL-specific except where a decision says so; Linux and macOS
hosts take the same steps.

## The decisions

Each is recommended unless it says otherwise; the alternatives are named where one was close.

### 1 — The host is the environment; the container is deleted, not kept

**The maintainer's direction.** A second path rots: the Dockerfile kept an apt Maven 3.8.7 for
months after the wrapper became the build's (F78, F93 in
[release-and-ci-hardening.md](release-and-ci-hardening.md) and
[audit-low-leads.md](audit-low-leads.md)), and a host path that must also keep the container
green doubles every setup instruction. The WSL 2 distribution is the documented host.

### 2 — `mise.toml` pins what the build needs and nothing else

```toml
[tools]
java = "temurin-25"
node = "22"

[settings]
node.corepack = true

[bootstrap.packages]
"apt:unzip" = "latest"      # the wrapper's only-script distribution unpacks with it
"apt:bubblewrap" = "latest" # the Bash sandbox (decision 11)
"apt:socat" = "latest"      # the Bash sandbox's network relay (decision 11)
```

**Java** is Temurin because CI is Temurin (row 1) — the container's Microsoft build was a
drift nobody chose. The major floats as CI's does; the patch is whatever mise resolves.
**Node** is CI's 22. **pnpm** comes through corepack from each `package.json`'s
`packageManager`: one pin, not two. **No Maven** — the wrapper is the build's, and the
`PluginVersionLedgerTest` assertion that the Dockerfile installs none moves to `mise.toml`.
**No `[env]`**: `DB_HOST` defaults to `localhost`, `TESSERAQL_PROFILE` is read by nothing
(row 2), and `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8` repeats `.mvn/jvm.config` and the JDK's
own UTF-8 default. herdr, `gh` and Claude Code are the user's tools and stay out of the project
file; nothing in the build needs them.

**The OS packages go in the same file**, under `[bootstrap.packages]` (row 8), so one checkout
holds everything a host needs and `mise bootstrap packages apply` installs the lot with one sudo.
`unzip` is the build's: without it the wrapper downloads the `.tar.gz` distribution instead of
the `.zip` and fails the pinned checksum with a compromise warning — the reason
`PluginVersionLedgerTest` already requires it of every Dockerfile that runs `./mvnw`, and the
container installed it with apt. `bubblewrap` and `socat` are the sandbox's (decision 11).
`git` and `curl` are on any host that cloned the repository and installed mise; Docker is not
declared — Docker Desktop, the snap and docker-ce are each a host's own choice — and
`verify-dev-env.sh` checks that it answers. Installation stays explicit: `mise install` for the
tools, `mise bootstrap packages apply` for the packages; the setup page runs both.

A new `ToolchainLedgerTest` in `tesseraql-docs-reference` holds `mise.toml`'s Java major equal
to `maven.compiler.release` and to every `setup-java` step's `java-version`, its Node major
equal to every `setup-node` step's `node-version`, refuses a `maven` entry, and requires
`apt:unzip` under `[bootstrap.packages]`.

*Weighed and declined:* mise in CI (`jdx/mise-action`) — the ledger holds the two together
without moving ten pinned `setup-java` steps; *trigger: a defect traced to local-CI drift the
ledger could not see.* `mise.lock` — it pins exact versions locally while CI floats; *trigger:
mise in CI.*

### 3 — Shells find the tools through shims, and every worktree is trusted through its checkout

`~/.bashrc`, above the interactive guard: `eval "$(mise activate bash --shims)"`.
`~/.config/mise/config.toml`: `trusted_config_paths = ["<host checkout>"]` under `[settings]`
(measured: at the top level mise ignores it as an unknown field). Claude Code's Bash
tool, a herdr pane's non-interactive command and the IDE run no prompt hook (row 8), and each
worktree is a new path whose copy of `mise.toml` would otherwise be untrusted. With no `[env]`
(decision 2), shims lose nothing. An interactive shell may add `mise activate bash` on top.
The IDE's Java extension finds the JDK through `JAVA_HOME=$(mise where java)` or its own
runtime setting; S2 measures which one it honours, and the page says that one.

### 4 — Parallel sessions: one herdr pane per session, one worktree per pane

A session starts as `claude --worktree <slice>` in a new pane; the main checkout stays the
IDE's and nobody builds there — CLAUDE.md's worktree rule, unchanged, and easier to keep. The
herdr integration is installed per user (`herdr integration install claude`); the repository
ships no herdr configuration and depends on nothing herdr does. herdr is the recommended
multiplexer; tmux or separate terminals take the same rules. CLAUDE.md gains a "Parallel
sessions" section that states decisions 5 to 10 as rules.

### 5 — Each worktree installs into its own local repository and reads downloads from the shared one

**Recommended.** A user-level `~/.mavenrc` — which Maven's own launcher sources (row 7) —
prepends `-Dmaven.repo.local=<checkout>/.mvn/local-repo -Dmaven.repo.local.tail=$HOME/.m2/repository`
to `MAVEN_ARGS` when the working directory is inside a TesseraQL checkout. The repository ships
the snippet as `scripts/mavenrc`, for `~/.mavenrc` to source; `.gitignore` gains
`.mvn/local-repo/`; `verify-dev-env.sh` says whether it is active. The head lives and dies with
its worktree, installs never cross between worktrees, and no command-line build writes the
shared repository, so no two builds race on it. The IDE's Maven import does not read
`~/.mavenrc` and may still download into the shared repository — harmless, since it installs
nothing; S2 looks at what it writes.

**`dev` in a worktree resolves modules from that worktree's head:** `--repo .mvn/local-repo`,
the flag that already sets `maven.repo.local` for the CLI's resolver. The cutover leaves
`io/tesseraql/` out of the shared repository (decision 12, step 5), so a `dev` that forgets the
flag fails to resolve its module rather than running another worktree's build. *Measured
(S2):* the CLI's resolver does not read the tail — an offline resolve with an empty head and
the shared repository as the tail fails exactly as with no tail, while the shared repository as
the head resolves — so the first `dev` in a worktree downloads the modules' third-party jars
into the head, online. The shared repository is refreshed with `MAVEN_SKIP_RC=1`, which skips
`~/.mavenrc` altogether: `MAVEN_SKIP_RC=1 ./mvnw -B -ntp -DskipTests verify`, never `install`.

*Why not `.mvn/maven.config`*, which row 7 measured to work: CI reads the file too, and the ten
`setup-java` caches of `~/.m2/repository` would stop growing while every run downloaded into
the workspace. *Weighed and declined:* one shared repository with Maven Resolver's file locks
(`aether.syncContext.named.factory=file-lock`) — concurrent writes become safe, but the last
`install` still wins for everyone, and that cross-talk is what this decision exists to remove;
Resolver's split repository with a per-worktree `localPrefix` — it shares downloads and
separates installs, but changes the shared repository's layout under the IDE and the CLI's
resolver, and is unmeasured.

### 6 — One full verify at a time per machine

`scripts/verify.sh` runs `./mvnw -B -ntp clean verify "$@"` under `flock` on
`${XDG_RUNTIME_DIR:-/tmp}/tesseraql-verify.lock` and, while it waits, says so; CLAUDE.md names
it as the pre-push command. Module runs (`./mvnw -pl <module> -am test`) do not queue. A full
verify occupies the machine for about 13 minutes with every Testcontainers suite (row 9); two
at once turn the suites' timeouts into false reds, and a red that might be load is the most
expensive kind to rule out.
*Weighed and declined:* a lock inside Maven (an extension); *trigger: a second way to run the
full reactor that bypasses the script.*

### 7 — `dev --port 0`: the gateway binds first, and the origin is the bound port

**Recommended.** Under `dev`, the front server listens before the members boot, and
`DevMode`'s default origin is built from `actualPort()` rather than from the requested number;
until the relay is installed the front answers `503` with `Retry-After: 1`. Vert.x wants the
request handler set before `listen`, so the handler delegates to one swapped in once the relay
exists. The origin is printed, as today, and written to `<appHome>/work/dev.origin` for
each application — the embedded-db marker's shape: one line, written on start, best-effort
deleted on a graceful stop, trusted by a reader only if it answers. The default stays 8080:
`README.md` and `ReadmeLedgerTest.java:93` promise that address to a person at a terminal, and
an agent's session passes `--port 0`. `host` is untouched: it serves a declared origin, and
its start order is [deployment-maturity.md](deployment-maturity.md)'s.

*Weighed and declined:* moving to the next free port when 8080 is taken — an address that
changes without being asked is the shape the silent-tolerance sweep removed; a port derived
from the worktree's path — hashes collide, and a collision would need a registry to resolve,
which is port 0 with extra steps.

### 8 — Tests pick a port only where the number is the assertion

Row 6's three groups. **(a)** Port 0 and `runtime.port()`. **(b)** A test helper in
`tesseraql-runtime`'s test sources repeats pick → prepare → start, up to three picks, when the
start fails with address-in-use, and names all three ports in the failure if every pick
loses. It stays in the test tree: `tesseraql-test-core` is the application-testing library,
not the framework's own test kit. **(c)** Stays, each with a one-line comment naming why the
number is picked. [build.md](build.md) gains the rule: a test that binds a port binds 0, and
the three shapes that may not say why.

*As shipped (S1):* `NotificationIntegrationTest` left group (b). Its port was picked for
GreenMail, whose JUnit extension restarted the server before every method on that number — a
window per method that no retry around the runtime's boot could close. One `GreenMail` for the
class now starts on `ServerSetupTest.SMTP.dynamicPort()` before the runtime's mail channel is
configured from `getSmtp().getPort()`, and the mailbox is emptied per test instead. The helper
is `PickedPort` and has three callers.

### 9 — Databases and Testcontainers

A session that runs an application uses `dev --embedded-db`: a random port, and a database
wiped on exit (or a data directory inside the worktree). Nothing replaces the container's `db`
service: `README.md:63-65` asks only for an empty PostgreSQL on `localhost:5432` or
`--embedded-db`, and a person who wants a long-lived one runs their own.
`TESTCONTAINERS_HOST_OVERRIDE` existed for docker-outside-of-docker and leaves with it; on the
host, Testcontainers reaches its containers on `localhost`.

### 10 — The kind proof takes a machine-wide lease

The cluster's name (`two-node`) and its NodePort (30080) are fixed, and the cluster outlives
the phase that made it: CI runs `proof.sh cluster`, `install`, … `teardown` as separate steps,
each its own process. A `flock` would end with each phase, so the lock is a lease: the cluster
phase takes it with an atomic `mkdir` of `${XDG_RUNTIME_DIR:-/tmp}/tesseraql-kind-proof.lease`
and writes the checkout that holds it; every phase run from another checkout is refused,
teardown included, so a second session can neither build beside the first nor delete its
cluster; the holding checkout re-running a phase is the rehearsal the cluster phase already
reuses a cluster for; teardown returns the lease. A proof that died without its teardown leaves
the lease, and the refusal says to remove the directory. CI runs one proof per runner and never
contends. *Weighed and declined:* a cluster name and host port per run — two three-node
clusters at once is nothing anyone needs, and each takes the memory a full verify wants.

### 11 — Credentials on the host

Row 4: the container's boundary was view, not enforcement. On the host an agent's shell sees
the whole home directory and, under WSL, the Windows drives. The rule `SECURITY.md` states for
the container — do not hand agents `~/.ssh`, `~/.aws`, `~/.gcloud`, `~/.azure`, `~/.docker` —
is restated for the host as user-level Claude Code configuration: the Bash sandbox, with those
directories denied.

**Its two dependencies come from apt, through `[bootstrap.packages]`** (decision 2), not from
mise's conda backend, which carries both (row 10): Claude Code's documentation names the
distribution's packages, Ubuntu 24.04's AppArmor allowance is written for `/usr/bin/bwrap`, and
the program that enforces a sandbox should take the distribution's security updates rather
than a second channel's.

**The build runs outside the sandbox; the sandbox holds for everything else.** Testcontainers
needs the Docker socket, `docker` is incompatible with the sandbox, and the socket let through
is host access (row 10), so `./mvnw` and `docker` go in `excludedCommands` — the build is the
repository's own code, run the same way it runs today — and every other command a session runs
is confined. The cutover measures that pnpm (the npm registry) and `gh` (the GitHub API) work
inside with their hosts allowed, and whether the optional seccomp filter is worth installing
(`npm:@anthropic-ai/sandbox-runtime`, a mise tool) — the sandbox cannot start inside the Dev
Container, whose seccomp profile refuses the user namespaces bubblewrap needs, so S2 could not;
if the sandbox cannot hold for the rest,
permission deny rules on the credential paths are the fallback, and the record says which was
adopted. `SECURITY.md` "Development secrets" and `security-hardening.md:264` follow. `GH_TOKEN`
moves out of `devcontainer.local.env` into the user's own environment, or `gh auth login`
replaces it; no repository file holds it.

### 12 — The cutover carries every piece of state out before anything is deleted

**The maintainer's condition.** Copy, never move; back up first, restore second. The volumes
are not touched, and are removed only by the maintainer, by hand, once the host has run for as
long as they choose. No Claude Code or Codex session runs in the container after the copy — a
session there writes memory the host copy lacks; if one must, the copy is repeated. S3 is not
opened until the check in step 7 passes, and it is written from the host.

`<host checkout>` is the checkout's absolute path on the host; `<key>` is that path with every
non-alphanumeric character turned into `-` (row 3).

0. End every agent session in the container. `docker volume ls --filter
   name=tesseraql_devcontainer` names the volumes.
1. **Back up** every volume into a directory that is not hidden (the snap daemon's home access
   covers non-hidden paths):
   `docker run --rm -v <volume>:/from:ro -v "$HOME/devcontainer-state:/to" alpine tar -C /from -czf /to/<name>.tgz .`
   — copies that no longer depend on the volumes.
2. **Claude Code:** move any existing `~/.claude` and `~/.claude.json` aside; extract
   `claude.tgz` into `~/.claude`, then move its `.claude.json` up to `~/.claude.json` — Claude
   Code's default layout. The container kept the file inside the directory because it set
   `CLAUDE_CONFIG_DIR`; the default needs no variable to reach VS Code, herdr panes or anything
   else that starts `claude`, so the host sets none.
3. **Re-key:** rename every `~/.claude/projects/-workspace-tesseraql*` so its
   `-workspace-tesseraql` prefix becomes `<key>`; in `~/.claude.json`, rewrite every string that
   starts with `/workspace/tesseraql` to start with `<host checkout>` — the project entry,
   `githubRepoPaths`, a live session's worktree — and check the file still parses (`jq`);
   rewrite `/home/vscode` to `$HOME` in `plugins/known_marketplaces.json`. Transcripts and memory
   bodies stay as they were written.
4. **Codex and gh:** extract `codex.tgz` into `~/.codex` (it holds no path, row 3) and
   `gh.tgz` into `~/.config/gh`, or run `gh auth login` instead.
5. **Maven:** extract the `repository/` of `m2.tgz` into `~/.m2/repository` **without
   `io/tesseraql/`** (decision 5). The npm cache and `pgdata` are not carried: one is a cache,
   and the examples' databases rebuild themselves.
6. **Toolchain:** install mise; add the shell line and the trusted path (decision 3); in
   `<host checkout>`, `mise install` and `mise bootstrap packages apply`; add the `~/.mavenrc`
   line (decision 5); install herdr and run `herdr integration install claude` (decision 4);
   enable the sandbox (decision 11).
7. **Check:** `claude` started in `<host checkout>` loads its memory index, and
   `ls ~/.claude/projects` shows no new key beside the renamed ones; `claude --resume` lists
   the container's sessions; `gh auth status` passes; `mise bootstrap packages status` lists
   nothing missing; `/sandbox` shows no Dependencies tab, and inside the sandbox `pnpm install`
   and `gh auth status` reach their hosts; the IDE's Java extension finds the JDK (decision 3);
   `bash scripts/verify-dev-env.sh` passes;
   two herdr panes started with `claude --worktree a` and `claude --worktree b` each run
   `dev --port 0 --embedded-db` on an example at the same time; `scripts/verify.sh` is green.

The maintainer's concrete commands — their paths and their volume ids — are prepared from this
list in the session that runs the cutover. They are not committed.

### 13 — What this design refuses, each with its trigger

- **Keeping the container as an option** (decision 1). *Trigger: a contributor whose host
  cannot run the toolchain* — the container would then come back as that contributor's path,
  generated from `mise.toml`, not as a second hand-kept image.
- **mise in CI, `mise.lock`** (decision 2).
- **A port per worktree, a port fallback** (decision 7).
- **A replacement for the `db` service** (decision 9). *Trigger: an example that cannot run
  on `--embedded-db`.*
- **Parallel kind proofs** (decision 10).
- **The sandbox's dependencies from mise's conda backend** (decision 11). *Trigger: a host
  whose distribution does not package bubblewrap or socat.*
- **Pinning herdr's or Claude Code's version in the repository.** They are the user's tools;
  a project file that pinned them would fight their own updaters.

### 14 — Docs, ledger, CHANGELOG

[development-environment.md](development-environment.md) becomes host-first in S2 — mise,
the shell line, the trusted path, `~/.mavenrc`, herdr, parallel sessions, the IDE's JDK — and
loses its container half in S3. `CLAUDE.md` gains "Parallel sessions" (S2) and loses "Dev
Container" (S3); `AGENTS.md:17` and `CONTRIBUTING.md:5` name the host (S3); `README.md:56-57`
says the toolchain is pinned by `mise.toml` (S3); [build.md](build.md) gains the port rule (S1);
the generated CLI reference picks up `dev --port`'s new description (S1). `CHANGELOG.md` per
slice, under Unreleased — see "Docs and CHANGELOG" below.

## What this breaks

- **`dev` answers `503` while its members boot** (S1), at any port, where it refused the
  connection before. A client that polled for a connection now polls for a status.
- **A worktree's `dev` needs `--repo .mvn/local-repo`** once `~/.mavenrc` is in place (S2).
  Without it, `dev` fails to resolve a declared module; it no longer runs whichever worktree
  installed last.
- **The Dev Container is gone** (S3). Before 1.0 that is recorded, not shimmed; decision 12 is
  this record's own procedure, not an upgrade guide.
- **Agent state is re-keyed** at the cutover. A session file that names `/workspace/tesseraql`
  inside a transcript keeps saying so; it is history.

## Filed, not fixed

- **The CLI's module resolver does not read `maven.repo.local.tail`** (measured in S2,
  decision 5). A worktree's first `dev` downloads the declared modules' third-party jars into
  its own repository instead of reading them from the shared one, and an offline `dev` in a
  fresh worktree cannot resolve them. *Trigger: an offline `dev` in a worktree.*
- **The VS Code extension's server URL defaults to `http://localhost:8080`**
  (`vscode-extension/package.json:44`). A session on `--port 0` sets the extension's URL from
  `work/dev.origin` by hand. *Trigger: the extension reading the marker itself.*

## Deliberately not in this design

- A Windows-native path: the container was Linux and the host is WSL.
- Any change to CI's toolchain steps (decision 2).
- Codex specifics: the worktree, port and repository rules are tool-agnostic, and Codex's
  state is carried like Claude Code's.

## The slices

### S1 — ports (M)

`dev`'s front server binds first under `DevMode` and the default origin is built from
`actualPort()`; the pre-relay `503`; `work/dev.origin` written and deleted beside
`work/embedded-db.jdbc`; `--port`'s description; the two group-(a) tests to port 0, the
group-(b) helper and its four callers, the group-(c) comments; `proof.sh`'s lock; build.md's
port rule, the regenerated CLI reference, CHANGELOG.

| Guard | Variant that must fail it |
| --- | --- |
| `DevPortZeroIntegrationTest` (new, `tesseraql-cli`, an example app under `--embedded-db`): `dev --port 0` → the printed URL's port is not 0; the stack issuer's `iss` and a session-authenticated page's absolute links carry that port; `work/dev.origin` holds that origin, and is gone after a graceful stop | the origin still built from the requested port (`iss` ends in `:0`); the marker written before the bind |
| the same test: two `dev --port 0` over two copies of the app at once both answer | a fixed default slipping back in (the second start refused) |
| the same test: a request sent between the bind and the members' readiness answers `503` with `Retry-After` | the front handler installed only after the members boot (a refused connection, not a status) |
| the runtime helper's unit test: a start that fails with address-in-use on the first pick succeeds on the second; three losses fail naming three ports | the helper retrying on every exception (a real boot failure retried) |
| `KubernetesChartLedgerTest` +1: `proof.sh` takes the lock before its `create cluster` line (`proof.sh:81`) | the lock taken after the cluster exists |

### S2 — the host toolchain and the parallel-session rules (M)

`mise.toml` with `[bootstrap.packages]`; `ToolchainLedgerTest`; the `PluginVersionLedgerTest`
assertion moved from the
Dockerfile to `mise.toml` (the Dockerfile check stays until S3); `scripts/mavenrc`,
`scripts/verify.sh`; `verify-dev-env.sh` answering on both the host and the container;
`.gitignore` `.mvn/local-repo/`; [development-environment.md](development-environment.md)
host-first; `CLAUDE.md` "Parallel sessions"; `SECURITY.md` "Development secrets"; the five
measurements: the oldest mise with `mise bootstrap` (the setup page names it), the IDE's JDK
(decision 3), the CLI resolver and the tail and the refresh command (decision 5), the Bash
sandbox with `./mvnw` and `docker` excluded (decision 11), `pnpm --version` = 11.8.0 in a fresh
shell through corepack (decision 2).

| Guard | Variant that must fail it |
| --- | --- |
| `ToolchainLedgerTest` (new): `mise.toml` Java major = `maven.compiler.release` = every `setup-java`'s major; Node major = every `setup-node`'s; no `maven` tool; `apt:unzip` under `[bootstrap.packages]` | `java = "temurin-21"`; `node = "24"`; a `maven = "…"` line; the `apt:unzip` line dropped |
| `PluginVersionLedgerTest`, the moved assertion | `mise.toml` declaring Maven |
| `verify-dev-env.sh` run on the host with `~/.mavenrc` present and absent | the script passing silently without the snippet |
| `scripts/mavenrc` sourced in a scratch shell inside a checkout and outside one: `MAVEN_ARGS` gains the two properties inside only | the snippet applying to every Maven run on the machine |

### The cutover (not a pull request)

Decision 12, run by the maintainer on the host with a session's help, after S2 merges. Its step
7 is the gate for S3.

### S3 — the Dev Container retires (S)

Delete `.devcontainer/`, `scripts/devcontainer-post-start.sh`,
`scripts/ensure-devcontainer-volumes.sh`; `verify-dev-env.sh` loses its container branch;
`ContributorSetupLedgerTest`'s list swaps `.devcontainer/devcontainer.json` for `mise.toml`;
`PluginVersionLedgerTest` loses the Dockerfile check and walks `deploy` only;
`.github/dependabot.yml` loses the `/.devcontainer` entry; `.gitignore:39`; `AGENTS.md`,
`CLAUDE.md`, `CONTRIBUTING.md`, `README.md`, `SECURITY.md`, `security-hardening.md:264`,
`.vscode/settings.json:15-16`, and the three comments of row 2; CHANGELOG. Written from the
host; its pre-push ritual runs there.

| Guard | Variant that must fail it |
| --- | --- |
| `ContributorSetupLedgerTest` with `mise.toml` in its list | the file missing |
| `PluginVersionLedgerTest` Dockerfile walk over `deploy` | a leftover `.devcontainer` entry in the list (the directory is gone) |
| the docs-site build and `InternalDocsSyncTest` | a living page still linking `.devcontainer/` |
| `git grep -n -i -E 'devcontainer\|Dev Container'` outside the design records and the CHANGELOG | any hit |

## Docs and CHANGELOG

`CHANGELOG.md`, Unreleased:

- **S1 `### Added`** — **`dev --port 0`.** The development gateway binds an ephemeral port
  before its applications boot, gives them that origin, prints it, and records it in each
  application's `work/dev.origin`, so several `dev` runs share a machine without choosing
  ports.
- **S1 `### Changed`** — `dev` answers `503` with `Retry-After` while its applications boot,
  instead of refusing the connection.
- **S2 `### Changed`** — **The framework's toolchain is pinned by `mise.toml`** (Temurin 25,
  Node 22, pnpm from `packageManager` through corepack, and the OS packages a host needs —
  `unzip`, and bubblewrap and socat for the agent sandbox — under `[bootstrap.packages]`), held
  to CI's by a ledger test. For
  contributors running sessions in parallel: one local Maven repository per worktree over a
  shared read-only one, and one full verify at a time.
- **S3 `### Removed`** — **The Dev Container.** The toolchain is `mise.toml`'s on the host;
  a second, hand-kept environment had drifted from the build once already.

## Error codes

No new code. A `dev --port 0` whose bind fails reports as today ("Could not start the gateway
on port 0").
