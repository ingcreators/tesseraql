# Runtime footprint

Status: **designed 2026-08-17** — nothing shipped. What a TesseraQL deployment actually carries,
why, and what it should carry instead.

This began as a question about the command line — *is it a problem that one binary does this many
things?* — and the measurement moved the answer somewhere else. **The command line's breadth is not
the defect.** Twenty-four subcommands over one domain object is an ordinary shape: `git` carries
well over a hundred, `kubectl` around forty, `cargo` around thirty, and
[cli-surface.md](cli-surface.md) Decision 5 already tests their cohesion by capability — a command
loads a manifest, compiles routes, or opens a database.

**The defect is that the deployment artifact is the developer's toolchain, and that the runtime
embeds development tooling of its own.** Those are two separable problems and this document keeps
them separate.

## What is actually in the image

Measured 2026-08-17 against `deploy/Dockerfile`, which builds the published deployment image.

The image copies `tesseraql-cli`'s jar **and its whole dependency set** into `lib/`, starts
`java -cp 'lib/*' io.tesseraql.cli.TesseraqlCli`, and its `CMD` is `["serve", "--app", "/app"]`.

| | Runtime jars |
| --- | --- |
| `tesseraql-camel-runtime` | 192 |
| `tesseraql-cli` | 249 |

### Problem 1 — the platform binaries are bundled against a written intention not to bundle them

`io.zonky.test:embedded-postgres` is a **compile** dependency of `tesseraql-cli`, for
`dev --embedded-db` and the `embedded-db` command. Both poms say what is supposed to happen:

> Embedded PostgreSQL process supervisor (**the binary is resolved on demand, not bundled**).
> — `pom.xml:268`

> the binary is **NOT bundled**; it is resolved on demand via the ShrinkWrap resolver below.
> — `tesseraql-cli/pom.xml:98`

That is the right design. `embedded-postgres.properties` is filtered at build time from
`zonky.postgres.binaries.version`, so the CLI asks for **one** platform's binary, the running
platform's, at the moment it is needed. Only `embedded-postgres-binaries-linux-amd64` is declared,
at **`test`** scope, so CI does not reach the network.

**The dependency tree does something else.** Measured 2026-08-17:

```
tesseraql-cli
+- io.zonky.test:embedded-postgres:jar:2.2.2:compile
|  +- io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64:jar:14.22.0:runtime
|  +- io.zonky.test.postgres:embedded-postgres-binaries-darwin-amd64:jar:14.22.0:runtime
|  \- io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64-alpine:jar:14.22.0:runtime
\- io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64:jar:17.10.0:test
```

`embedded-postgres` **carries three platform bundles transitively at runtime scope, and nothing
excludes them.** They are copied into `lib/` by the deployment image and into every distribution.

| Artifact | Size | How it arrives |
| --- | --- | --- |
| `embedded-postgres-binaries-windows-amd64` | 21 MB | transitive, unexcluded |
| `embedded-postgres-binaries-darwin-amd64` | 27 MB | transitive, unexcluded |
| `embedded-postgres-binaries-linux-amd64-alpine` | 14 MB | transitive, unexcluded |

**And they are the wrong version.** The project configures **17.10.0**; the bundles are **14.22.0** —
a different PostgreSQL major. `embedded-db-version-lifecycle` already records that cross-major is
the case this project chose not to automate, because zonky's binaries are server-only and a data
directory initialised by one major is refused by another.

**Which of the two wins at runtime is not measured, and it decides what this is.** If the on-demand
resolver always fetches 17.10.0, the bundles are sixty-two megabytes of dead weight. If zonky's
resolver prefers a matching platform bundle already on the classpath, then a **Windows** developer
gets PostgreSQL **14** where every other path in this project means 17 — and a data directory
written by one and opened by the other fails outright. **Print the value before deciding which:**
run `dev --embedded-db` on Windows and log the server version. It is a ten-minute measurement and it
changes the severity by a category.

### Problem 2 — the runtime carries Studio, and Studio carries a test framework

This one is not the command line's doing. It is in the module that production cannot do without.

```
tesseraql-camel-runtime          ← every deployment has this
├── tesseraql-studio             compile — mounted by StudioProviders
└── tesseraql-test-core          compile — used by StudioTestService, and nothing else
    └── com.icegreen:greenmail   compile — MailCapture, test-core's own mail assertions
        └── junit:junit:4.13.2   compile
```

**Studio's "run the tests" button is why an in-memory SMTP server and JUnit 4 are on the classpath
of every TesseraQL deployment.** Each link is individually reasonable — `MailCapture` genuinely
needs GreenMail, `StudioTestService` genuinely needs the runner, Studio is genuinely a framework
surface — and the chain is not.

**GreenMail's scope in `tesseraql-test-core` is correct and should not be changed.** A declarative
test framework that asserts on mail needs a mail server at compile scope. What is wrong is one link
up: a *runtime* depending on a *test framework*.

### What this is and is not

It is weight and breadth of surface. **It is not a vulnerability**, and overstating it would misplace
the fix. The zonky binaries are inert files unless something executes them. Studio is gated by
`tesseraql.apps.studio.enabled`, so it is configuration that mounts it, not the classpath. JUnit on a
classpath is not exploitable by itself.

What it costs is real anyway: image size, a longer dependency surface to audit and to patch, and an
artifact that does not say what it is for.

### Problem 3 — Windows Server is a released target that nothing tests

Asked directly: *what if production runs on Windows Server?* The measurement changed the shape of
problems 1 and 2 and added one of its own.

**It is shipped.** `jpackage.yml` builds on `windows-latest`, `release.yml` attaches
`tesseraql-<version>-windows-x86_64.zip`, and `getting-started.md` documents
`scoop install tesseraql` against `ingcreators/scoop-bucket` — which exists, last pushed
2026-08-13.

**Nothing verifies it.** That Windows job runs `./mvnw … -DskipTests`, and every job in `ci.yml`
that runs a test is `ubuntu-latest`. The Windows artifact is built and published; no test observes
its behaviour on the platform it is published for.

**Windows is wanted as a production target**, which turns this section from a question into a
requirement and removes an option decision 2a would otherwise have had.

It also sharpens problem 1 rather than repeating it. A deployment distribution should carry no
embedded-database binaries on any platform, because `--embedded-db` is a development feature
everywhere — **the split is development against production, not one operating system against
another.** What the platform changes is which bundle is the live one in the *developer* CLI, and
that is exactly the case where 14.22.0 against a configured 17.10.0 stops being dead weight and
starts being a version a developer actually runs.

**And the Linux image is not the Windows path at all.** Decision 2 below says the deployment image
runs `host`; a Windows Server deployment never reaches that image. It runs the jpackage launcher or
the distribution zip, for which no equivalent statement exists — no documented `host` invocation, no
service wrapper.

**What is most likely to break, and is untested.** Windows refuses to delete or replace a file
another process holds open; Linux does not, because an unlinked inode stays valid for whoever has it
open. `AppInstaller` upgrades an application by `deleteRecursively(target)` followed by
`Files.move(staging, target, REPLACE_EXISTING)` — replacing a directory tree that a running host may
be holding files under, which is the ordinary case rather than an edge one. The same semantics reach
`--watch` and anything under `work/` that a pool or an embedded database keeps open.

`AppInstaller` already carries `.replace('\\', '/')` on the stored path, which is somebody having
met the platform once. Nothing generalised from it.

## Decisions

### 1. The deployment artifact carries the host, not the workshop

Two distributions from one codebase: the developer CLI as it is today, and a deployment
distribution containing what `host` needs. The command line is **not** split — its cohesion is fine
and [cli-surface.md](cli-surface.md) spent a campaign on it. What splits is what gets shipped.

The mechanism is deliberately left open between two candidates, because the choice depends on
measurements this document has not taken:

- **`optional` on the development-only dependencies**, with the deployment image excluding them.
  Cheapest, one pom change plus an image change, and it keeps one artifact. **Problem 1's own fix is
  smaller still and independent of this choice**: `<exclusions>` on `embedded-postgres` for the three
  transitive platform bundles, which restores the "resolved on demand" the poms already intend and
  removes sixty-two megabytes from *every* distribution, developer CLI included.
- **A `tesseraql-runtime` distribution module** that depends on the runtime and a `host`-only entry
  point. Heavier, and it makes the boundary a build failure rather than a convention.

The second is only worth its cost if the first cannot be made to fail loudly when a development
dependency creeps back in.

### 2. The production image runs `host`, not `dev`

`deploy/Dockerfile` ends with `CMD ["serve", "--app", "/app"]`. Under
[stack-architecture.md](stack-architecture.md) Decision 12 and
[cli-surface.md](cli-surface.md) Decision 4 that verb becomes `dev`, so the published production
image would literally invoke the development command.

It becomes `host --stack /app`. The line is one word, and it is worth having as a decision because
it is the shortest possible statement of what this document is about: **the artifact should say what
it is for.**

### 2a. Windows Server is a production target, so it is tested

An earlier draft offered a choice — test it or stop offering it — because a published release asset
and a documented `scoop install` with no test on the platform is a claim nobody verified. **Windows
is wanted as a supported production target, so the choice collapses to the first branch** and the
open questions below become work items rather than alternatives.

The answer is not the whole suite on a Windows runner: the Testcontainers integration tests dominate
the build and are slow and flaky on Windows agents, and they exercise a database rather than a
filesystem. It is **the modules where platform semantics actually live** — `tesseraql-operations`
(install, upgrade, catalogue, `AppDirectory`) and `tesseraql-cli` (paths, launchers, `--watch`) — as
a separate `windows-latest` job.

**And a deployment distribution for Windows is part of decision 1**, not an afterthought to it. A
Windows deployment never reaches `deploy/Dockerfile`, so "the production image runs `host`" needs a
sibling statement for the jpackage launcher: what a Windows Server installation runs, and how it is
supervised. Neither exists today.

### 3. Studio leaves the runtime's compile scope, which is already the plan

[stack-architecture.md](stack-architecture.md) Decision 14 makes framework surfaces stack-level, and
its slice 8 extracts Studio — recorded there as "a campaign, not a slice" because `StudioService`
couples preview, source editing, apply and reload, the scaffolder, the migration author and the test
runner to a runtime.

**This document adds a reason that slice did not have.** The extraction is not only about
authorisation and about one console per stack: it is what removes a test framework, an SMTP test
double and JUnit from every deployment. That is worth recording, because a campaign with two
independent justifications is scheduled differently from one with a single justification.

The extraction is designed in [studio-shell.md](studio-shell.md) (2026-08-19): a
`tesseraql-studio-runtime` extension module the runtime discovers, so the runtime module's
compile scope drops `tesseraql-studio` and `tesseraql-test-core` in its slice 1. **That slice
is shipped**: the chain above is gone from the runtime module, and an enforcer rule — the
guard open question 5 asked for — fails the build if Studio, test-core, GreenMail or JUnit 4
ever reach its compile or runtime scope again. The deployment *image* still carries the jars
until Decision 1 splits the distributions; what changed is that the split is now a packaging
decision, and the jars are inert under a host by topology.

## Open

1. **Which mechanism for decision 1** — `optional` scoping versus a distribution module. Needs the
   remaining 57 jars classified: how many are dev-only beyond zonky, and how many the deployment
   image would keep anyway.
2. **Where `dev --embedded-db` lives** once the distributions split. The developer CLI keeps it; the
   question is whether the `embedded-db` command stays a subcommand of the same binary.
3. **Which binary the on-demand resolver actually returns on Windows** — the configured 17.10.0 or
   the transitively bundled 14.22.0. Ten minutes with `dev --embedded-db` and a logged server
   version, and it decides whether problem 1 is weight or a version defect. **Measure before
   deciding.**
4. **`AppInstaller`'s replace-the-directory upgrade on Windows.** Not an open question about whether
   to care — Windows is a target — but about the shape of the fix. Installing beside and switching a
   pointer avoids the held-file problem and gives atomic rollback on every platform, which is why it
   is likely the answer rather than a Windows special case.
5. **Whether a guard can assert the boundary.** A test that fails when a development-only artifact
   reaches the deployment classpath is what makes decision 1 hold; without one the scoping decays
   the first time a convenient dependency is added, which is how it arrived here.
