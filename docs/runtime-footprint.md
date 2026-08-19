# Runtime footprint

Status: designed 2026-08-17; **decided 2026-08-19** — the mechanism for the distribution split,
the zonky fix and its guard, the Windows test job and execution story, and the generalized
boundary guards. What a TesseraQL deployment actually carries, why, and what it should carry
instead.

This began as a question about the command line — *is it a problem that one binary does this many
things?* — and the measurement moved the answer somewhere else. **The command line's breadth is not
the defect.** Twenty-five subcommands over one domain object is an ordinary shape: `git` carries
well over a hundred, `kubectl` around forty, `cargo` around thirty, and
[cli-surface.md](cli-surface.md) Decision 5 already tests their cohesion by capability — a command
loads a manifest, compiles routes, or opens a database.

**The defect is that the deployment artifact is the developer's toolchain.** The 2026-08-17 draft
named a second defect — the runtime embedding development tooling of its own — and that one is
fixed: [studio-shell.md](studio-shell.md) structural decision 3 extracted the workshop into
`tesseraql-studio-runtime`, `tesseraql-camel-runtime` dropped its compile dependencies on
`tesseraql-studio` and `tesseraql-test-core`, and an enforcer rule
(`no-workshop-on-the-runtime`) guards the boundary. What remains is the packaging problem, and
this revision decides it.

## What is actually in the artifact

Measured 2026-08-19 (`dependency:list`, runtime scope, 0.15.0-SNAPSHOT). The deployment image
(`deploy/Dockerfile`) copies `tesseraql-cli`'s jar **and its whole resolved dependency set** into
`lib/`; the dist fat jar, the dist archive, and the jpackage app image bundle the same set.

| | Runtime-classpath artifacts |
| --- | --- |
| `tesseraql-camel-runtime` | 195 |
| `tesseraql-cli` | 261 |

The 66 artifacts only the CLI carries are `tesseraql-camel-runtime`'s own jar plus 65 additions,
and the additions decompose into four clusters — the clusters are the whole story:

| Cluster | Jars | Size | What it is | Who needs it |
| --- | --- | --- | --- | --- |
| Embedded PostgreSQL | 7 | 62.6 MB | zonky supervisor + three platform binary bundles + txz codecs | `dev --embedded-db`, `embedded-db` — development only, and the binary bundles are dead weight even there (problem 1) |
| Artifact resolver | 46 | 8.0 MB | ShrinkWrap + Maven resolver + Guice/Plexus/Sisu closure | `modules`, `dev` (resolving `tesseraql.modules`), embedded-db binary resolution — development only (see below) |
| The workshop | 8 | 2.3 MB | `tesseraql-studio`, `tesseraql-studio-runtime`, `tesseraql-test-core`, GreenMail, JUnit 4, commonmark, the editor webjar | `dev` — a `host` stack mounts no Studio by topology ([studio-shell.md](studio-shell.md) structural decision 1) |
| Operator glue | 4 | 0.5 MB | picocli, `tesseraql-apptasks`, `tesseraql-report`, `tesseraql-coverage-core` | picocli and apptasks stay in the deployment (argument parsing; the migrate/identity-schema engine). report and coverage-core render test results — development only |

**The finding that decides the mechanism: `host` needs none of the resolver stack.** `HostCommand`
starts the gateway directly; it never touches `ModulesInstaller`. An application's declared
`tesseraql.modules` are resolved into its `work/modules` cache by `dev` or the `modules` command
*before* deployment, each runtime builds its own loader over what that resolve left on disk
([module-scope.md](module-scope.md)), and a `.tqlapp` ships the cache it was verified with. So the
deployment classpath is `tesseraql-camel-runtime`'s 195 artifacts plus picocli, apptasks, and the
CLI jar itself — roughly 63 jars and 73 MB smaller than what ships today, with the zonky binaries
alone accounting for 62 MB of that.

### Problem 1 — the platform binaries are bundled against a written intention not to bundle them

`io.zonky.test:embedded-postgres` is a **compile** dependency of `tesseraql-cli`, for
`dev --embedded-db` and the `embedded-db` command. Both poms say what is supposed to happen:

> Embedded PostgreSQL process supervisor (**the binary is resolved on demand, not bundled**).
> — the root pom's dependency management, and the CLI pom repeats it

That is the right design, and the tree does something else. `embedded-postgres:2.2.2` carries
three platform bundles transitively at runtime scope, unexcluded, so they are copied into every
distribution:

| Artifact | Size | How it arrives |
| --- | --- | --- |
| `embedded-postgres-binaries-windows-amd64` | 21 MB | transitive, unexcluded |
| `embedded-postgres-binaries-darwin-amd64` | 27 MB | transitive, unexcluded |
| `embedded-postgres-binaries-linux-amd64-alpine` | 14 MB | transitive, unexcluded |

**And they are the wrong version** — 14.22.0 where the project configures 17.10.0, a different
PostgreSQL major.

**Which of the two wins at runtime is now answered — by code, not by the ten-minute Windows
measurement the last draft asked for.** `EmbeddedPostgresSupport.start` installs a custom
`PgBinaryResolver` **unconditionally**: it resolves
`embedded-postgres-binaries-<platform>:17.10.0` (or the data directory's pinned version) through
the embedded ShrinkWrap resolver and streams that jar's `.txz` payload to zonky. Zonky's own
classpath-scanning `DefaultPostgresBinaryResolver` — the only code path that would ever read the
bundled 14.22.0 jars — is never consulted, on any platform. A Windows developer gets 17.10.0
exactly like everyone else.

So the bundles are 62 MB of dead weight in every distribution, not a version defect. **The fix is
`<exclusions>` on the `embedded-postgres` dependency** for the three platform bundles, which
restores the "resolved on demand" the poms already intend — and it benefits the *developer* CLI
too, independent of the distribution split. Because this arrived once by omission it can arrive
again by upgrade, so the fix comes with its decay guard (see the guards decision): an enforcer
rule in `tesseraql-cli` banning `io.zonky.test.postgres:*` from compile and runtime scope. Test
scope stays free — the declared linux-amd64 test dependency is what keeps CI off the network. The
belt-and-suspenders half of the guard is behavioral and lives where the platform does:
`EmbeddedDbDevIntegrationTest` runs a real resolved `postgres` and, extended with a version
assertion, fails if the server that starts is not the configured major (decision 2a runs it on
Windows).

### Problem 2 — the runtime carried Studio, and Studio carried a test framework — FIXED

The chain measured on 2026-08-17 (`tesseraql-camel-runtime → tesseraql-test-core → greenmail →
junit:4.13.2`, all compile scope, because of `StudioTestService` alone) is gone.
[studio-shell.md](studio-shell.md) structural decision 3 moved everything Studio-shaped out of the
runtime module into `tesseraql-studio-runtime`, discovered through the `RuntimeExtension` SPI; the
runtime module's pom now carries the `no-workshop-on-the-runtime` enforcer rule (studio,
test-core, greenmail and junit banned at compile and runtime scope, transitively, with test scope
free). What this document adds is the packaging half that extraction deliberately left open: the
deployment *artifact* still carries the workshop jars, because the image copies the developer
CLI's dependency set. Decision 1 below is that split.

### What this is and is not

It is weight and breadth of surface. **It is not a vulnerability**, and overstating it would
misplace the fix. The zonky binaries are inert files unless something executes them; a `host`
stack mounts no Studio by topology; JUnit on a classpath is not exploitable by itself. What it
costs is real anyway: image size, a longer dependency surface to audit and to patch, and an
artifact that does not say what it is for.

### Problem 3 — Windows Server is a released target that nothing tests

**It is shipped.** `jpackage.yml` builds on `windows-latest`, `release.yml` attaches
`tesseraql-<version>-windows-x86_64.zip`, and `getting-started.md` documents
`scoop install tesseraql` against `ingcreators/scoop-bucket`. **Nothing verifies it**: the Windows
job runs `-DskipTests`, and every test job in `ci.yml` is `ubuntu-latest`. Windows is wanted as a
production target, which makes this a requirement, not a question — decision 2a below.

Two measurements sharpen what "test it" means:

- **The platform-semantics modules are cheap to run on Windows.** `tesseraql-operations` uses no
  Testcontainers at all; `tesseraql-cli` has exactly two Testcontainers classes
  (`AppLifecycleDbCommandsIntegrationTest`, `JobCommandIntegrationTest`) to exclude. Everything
  else — `AppInstaller`, `AppUpgrader`, `AppDirectory`, the path and launcher handling, and
  `EmbeddedDbDevIntegrationTest`, which resolves and boots a real `postgres` — runs on a plain
  runner.
- **The upgrade path is already install-beside-and-switch-a-pointer**, which the last draft only
  hoped for. Versions install side by side under `<installRoot>/<name>/<version>`; activation is
  `AppCatalog.replace`, an `ATOMIC_MOVE` of the catalog file; canary staging, promotion and
  rollback never move files at all. The Windows held-file hazard is therefore one narrow case,
  not the whole upgrade path: `AppInstaller.place` does `deleteRecursively(target)` +
  `Files.move(staging, target)` **when the same version is placed again** — deleting a tree a
  running host may hold files open under, which Windows refuses mid-walk, leaving a half-deleted
  version directory. The shape of the fix is decision 4.

## Decisions

### 1. The deployment artifact carries the host, not the workshop — via a distribution module

Two distributions from one codebase: the developer CLI exactly as it is today, and a
**`tesseraql-host`** distribution module containing what a deployment needs. The command line is
**not** split — its cohesion is fine and [cli-surface.md](cli-surface.md) spent a campaign on it.
What splits is what gets shipped.

The 2026-08-17 draft left the mechanism open between `optional` scoping and a distribution
module, pending the jar classification. The classification (above) closed it, and so did a fact
about Maven that the draft missed: **`optional` cannot do this job at all.** Optional scope
changes what *downstream consumers* inherit; it does not remove anything from the declaring
module's own resolved runtime classpath, and every deployment channel — the image's
`copy-dependencies`, the shade fat jar, the dist assembly, jpackage — packages exactly that
classpath. The only way `optional` "works" is an exclude list maintained inside
`deploy/Dockerfile` flags, which is a stringly-typed convention that decays silently and that the
Windows zip channel cannot reuse. The draft's own criterion — the cheap option is only acceptable
if it fails loudly when a development dependency creeps back in — rules it out.

`tesseraql-host` is deliberately thin. It moves no classes and duplicates no verbs:

- **It depends on `tesseraql-cli` with exclusions** on the development-only clusters: the
  workshop chain (`tesseraql-studio`, `tesseraql-studio-runtime`, `tesseraql-test-core` and their
  mail/junit/editor transitives), the zonky supervisor, the ShrinkWrap/Maven resolver stack, and
  `tesseraql-report`/`tesseraql-coverage-core`. What remains resolves to the runtime's 195
  artifacts plus picocli and apptasks.
- **It contributes one class: the deployment's picocli root command**, listing only the verbs an
  operator runs against a production stack. The shared command implementations stay
  package-private in `io.tesseraql.cli` and are reused as-is (the host module's root command
  lives in the same package; the deployment runs on a plain `-cp lib/*` classpath, so the split
  package is legal and invisible). This is also what makes the exclusions *safe*: the developer
  CLI's root command reflects over all 25 subcommand classes at startup, so a distribution that
  merely dropped jars from under `TesseraqlCli` would gamble on lazy class loading; a root
  command that never names the dev-only verbs never loads them.
- **The enforcer rule makes the boundary a build failure** (the guards decision below), which is
  the loud failure the draft demanded — the same mechanism, verbatim, as the runtime module's
  `no-workshop-on-the-runtime`.

The verb roster of the host distribution, by the rule *a verb enters iff its dependency closure
lives inside the host set* (the enforcer arbitrates disagreements at build time):

| In `tesseraql-host` | Stays developer-CLI-only |
| --- | --- |
| `host`, `deploy`, `migrate`, `identity-schema`, `job`, `token`, `verify`, `admission`, `routes`, `duckdb` (the documented operator step: `install-extensions`) | `dev`, `new`, `scaffold`, `lint`, `test`, `coverage`, `generate`, `schema`, `symbols`, `release-diff`, `governance`, `package`, `modules`, `embedded-db`, `mcp` |

Consequences the draft's open questions asked about:

- **`dev --embedded-db` stays in the developer CLI unchanged**, and so does the `embedded-db`
  subcommand of the same binary. The resolver stack the binary resolution rides on is in the
  developer CLI anyway for `tesseraql.modules`; there is nothing to relocate.
- **`deploy/Dockerfile` builds from `-pl tesseraql-host`** and copies its dependency set; the
  entrypoint invokes the host root command; `CMD ["host", "--stack", "/stack", ...]` and the CDS
  training run (`routes` is in the host roster) survive unchanged. The existing `deploy-image` CI
  job keeps proving the image boots and routes.
- **The Windows deployment artifact is the same module through the jpackage channel** — decision
  2a's sibling statement below — which is the second reason the mechanism had to be a
  Maven-addressable artifact rather than a Dockerfile exclude list: two shipping channels derive
  from one declaration.

**Rejected: splitting `tesseraql-cli` into `cli-core` + `cli`.** It moves every operator command
class for the same resulting classpath, and re-litigates the verb cohesion cli-surface already
settled. **Rejected: `optional` scoping** — see above; it does not reach the channels that
package the classpath, and its failure mode is silence.

### 2. The production image runs `host`, not `dev` — SHIPPED

`deploy/Dockerfile` ends with `CMD ["host", "--stack", "/stack", "--port", "8080"]` and registers
the ordered drain on SIGTERM; the `deploy-image` CI job asserts the image builds, lists routes,
and keeps its trained CDS archive. Kept as a decision because it is the shortest possible
statement of what this document is about: **the artifact should say what it is for.**

### 2a. Windows Server is a production target, so it is tested — and it has an execution story

**The test job.** A `windows-latest` job in `ci.yml` runs the modules where platform semantics
actually live, not the Testcontainers suite (slow and flaky on Windows agents, and it exercises a
database, not a filesystem):

```
./mvnw -B -ntp -pl tesseraql-operations,tesseraql-cli -am test \
  -Dtest='!AppLifecycleDbCommandsIntegrationTest,!JobCommandIntegrationTest'
```

What that buys, concretely: `AppInstaller`/`AppUpgrader`/`AppDirectory`/`AppCatalog` run on a
real Windows filesystem (the held-file and path semantics get coverage where they live, including
decision 4's refusal once it ships); the CLI's path, launcher and `--watch` handling run under
`\` separators; and `EmbeddedDbDevIntegrationTest` resolves the **windows-amd64** binary over the
network and boots a real `postgres` — the "which binary wins on Windows" measurement the draft
wanted, permanent and asserted instead of logged once. The test gains an explicit version
assertion (the started server's major equals the configured major) so resolver drift fails the
build. The linux-amd64 test dependency keeps the Ubuntu jobs offline; the Windows job reaching
Maven Central for one ~20 MB binary jar per run is the price of testing the real resolution path,
paid on the runner where it is the live one.

**The execution story — the sibling statement decision 2 owed.** A Windows Server deployment
never reaches `deploy/Dockerfile`. It runs the **`tesseraql-host` jpackage app image**, and it is
supervised as a **Windows service through a service wrapper**:

- `release.yml`/`jpackage.yml` gain a host-image leg: `tesseraql-host-<version>-windows-x86_64.zip`,
  built from the host module's fat jar exactly as the CLI image is built today (`--win-console`
  included — the service wrapper captures the console streams as the service log).
- The zip ships a sample service definition for **WinSW** (MIT-licensed, single-exe, wraps any
  console executable): `tesseraql-host.exe host --stack C:\ProgramData\tesseraql\stack --port 8080`,
  with the wrapper's graceful-stop mode sending the console signal that triggers the JVM
  shutdown hook — the same ordered drain SIGTERM reaches in the container. Verifying the drain
  line on a service stop is part of the distribution slice, not assumed. WinSW is recommended
  over Apache Commons Daemon/procrun because procrun wants the JVM wired as a DLL with dedicated
  start/stop entry points — more invasive for no additional guarantee — and over NSSM on
  maintenance grounds. The framework does not embed a service API; supervision stays outside the
  process, exactly as systemd and the container runtime stay outside it on Linux.
- `hosting.md` gets the Windows Server section: install layout, the service definition, where
  `work/` lives, and the upgrade flow (`deploy` + promote — identical to Linux because the
  catalog pointer switch is platform-neutral).
- The developer CLI's Scoop channel is untouched; whether the host image also gets a package
  manager channel is deferred until someone asks for it.

### 3. Studio leaves the runtime's compile scope — SHIPPED

[studio-shell.md](studio-shell.md) structural decision 3, recorded here because this document
supplied the second justification (the deployment classpath) that changed how that campaign was
scheduled. The remaining packaging half is decision 1.

### 4. A placed version directory is immutable; same-version replacement is refused

The measured upgrade path (problem 3) already installs beside and switches an atomic pointer, so
the fix for the held-file hazard is not a redesign — it is removing the one operation that
contradicts the model. `AppInstaller.place` currently deletes and re-creates the target when the
incoming package carries a version that is already on disk. Under a running host that is exactly
the delete-a-held-tree operation Windows refuses (and Linux silently tolerates, which is worse —
the platforms diverge). The fix:

- **`place` refuses an existing version directory** with an actionable message: bump the version.
  A version directory, once placed, is immutable — the same contract the catalog's
  rollback already relies on ("the previous version's files must still be present").
- Pre-1.0 there is no compatibility carve-out (AGENTS.md rule 10); iterating on an unreleased
  package is a `dev` workflow against a source tree, not repeated `deploy` of one version.
- This removes `deleteRecursively` from the install path entirely rather than special-casing
  Windows — the "install beside and switch a pointer" answer the draft predicted, minus the parts
  that already existed. With the target guaranteed absent, the final `Files.move(staging, target)`
  is a same-volume rename, so a crash mid-install leaves a stray staging directory, never a
  partial version directory that would then block its own retry.

The stored-path `.replace('\\', '/')` normalization stays: catalog entries are portable across
platforms by construction.

### 5. The boundary guards: one enforcer rule per module that owns a boundary

`no-workshop-on-the-runtime` (the runtime module's rule from the Studio extraction) generalizes
into a pattern: **each module that owns a classpath boundary carries a `bannedDependencies`
enforcer rule naming what must never cross it, at compile and runtime scope, transitively, with
test scope free.** A guard lives in the module whose invariant it protects, fails the module's
own `verify`, and names the design document that explains it.

| Module | Rule | Bans (compile/runtime, transitive) |
| --- | --- | --- |
| `tesseraql-camel-runtime` | `no-workshop-on-the-runtime` (exists) | studio, studio-runtime, test-core, greenmail, junit |
| `tesseraql-cli` | `no-bundled-database-binaries` (new) | `io.zonky.test.postgres:*` — the supervisor stays, binaries resolve on demand; the declared test-scope linux-amd64 is untouched |
| `tesseraql-host` | `no-workshop-in-the-deployment` (new) | studio, studio-runtime, test-core, greenmail, junit, `io.zonky.test:*`, `io.zonky.test.postgres:*`, `org.jboss.shrinkwrap.resolver:*`, report, coverage-core — plus tripwire artifacts from the resolver closure (`org.apache.maven.resolver:maven-resolver-api`, `com.google.inject:guice`) so the stack cannot return under a different root |

Why enforcer rules and not a test: the rule runs inside the module's own build on every `verify`,
needs no fixture to keep in sync with the pom, and its failure message names the banned
coordinate and the scope — the same reasons the Studio extraction chose it. The `deploy-image`
and dist smoke jobs stay as behavioral backstops, but the contract is enforced where the
dependency would be declared, which is where it decayed last time.

## Slices

Each slice is a PR; each leaves the build green and the boundary it touches guarded.

| # | Slice | Contents | Done means |
| --- | --- | --- | --- |
| 1 | The binaries stop shipping | `<exclusions>` on `embedded-postgres`; the `no-bundled-database-binaries` rule; the version assertion in `EmbeddedDbDevIntegrationTest` | Every distribution is 62 MB lighter; a zonky upgrade that re-bundles binaries fails `tesseraql-cli`'s own verify |
| 2 | `tesseraql-host` | The module, its root command over the operator roster, exclusions, `no-workshop-in-the-deployment`; `deploy/Dockerfile` builds from it; the `deploy-image` job proves the result boots | The image carries ~199 jars, none of them the workshop; a convenient dev dependency added to the CLI does not reach deployments |
| 3 | Windows runs the tests | The `windows-latest` job over `tesseraql-operations` + `tesseraql-cli` (Testcontainers classes excluded) | The platform-semantics modules are tested on the platform they are released for; the resolver measurement is a permanent assertion |
| 4 | Version directories are immutable | Decision 4's refusal in `AppInstaller.place`, tested (and exercised on Windows by slice 3's job) | No code path deletes a tree a running host may hold open |
| 5 | Windows Server has an artifact and a story | The host-image jpackage/release leg, the WinSW sample definition, the `hosting.md` Windows Server section, drain-on-service-stop verified | A Windows deployment runs a named artifact under named supervision, with the same drain contract as the container |

Slices 1, 3 and 4 are independent of each other and of slice 2. Slice 5 depends on slice 2.

## Open

1. **The exact host verb roster** is settled at slice 2 by the enforcer: a verb whose closure
   escapes the host set either loses the dependency or stays developer-CLI-only. The table in
   decision 1 is the intent, not a contract.
2. **Drain verification on Windows service stop** (slice 5): the mechanism for asserting the
   "Stack stopping" line under WinSW's graceful stop on a CI runner is chosen when the slice is
   built — a wrapper-driven stop in the `windows-latest` job if runner privileges allow, a
   documented manual verification if they do not.
3. **`--watch` and `work/` under Windows held-file semantics** beyond the installer: slice 3's
   job will surface what the suites cover; anything it finds is filed against this document.
