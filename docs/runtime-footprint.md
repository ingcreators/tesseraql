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

### Problem 1 — the CLI's 57 extra jars include a PostgreSQL server for two operating systems

| Artifact | Size | Runs in this image? |
| --- | --- | --- |
| `embedded-postgres-binaries-windows-amd64` | **21 MB** | no |
| `embedded-postgres-binaries-darwin-amd64` | **27 MB** | no |
| `embedded-postgres-binaries-linux-amd64-alpine` | 14 MB | no — the image is `eclipse-temurin:25-jre`, Debian |

`io.zonky.test:embedded-postgres` is a **compile** dependency of `tesseraql-cli`. It exists for
`dev --embedded-db` and the `embedded-db` command, and it is development-only by its own name. Every
production image carries roughly sixty megabytes of database server binaries, most of which cannot
execute on the platform they are shipped to.

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

**This corrects a claim made while measuring problem 1.** "Cannot execute in this image" was true of
`deploy/Dockerfile`, which is `eclipse-temurin:25-jre` on Debian. On Windows Server the Windows
binaries are the live ones and the Linux and macOS ones become the dead weight. The ratio flips per
platform; the conclusion does not, because `--embedded-db` is a development feature on every
platform and a deployment distribution should carry none of them. **The split is development against
production, not one operating system against another.**

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
  Cheapest, one pom change plus an image change, and it keeps one artifact.
- **A `tesseraql-runtime` distribution module** that depends on the runtime and a `host`-only entry
  point. Heavier, and it makes the boundary a build failure rather than a convention.

The second is only worth its cost if the first cannot be made to fail loudly when a development
dependency creeps back in.

### 2. The production image runs `host`, not `serve`

`deploy/Dockerfile` ends with `CMD ["serve", "--app", "/app"]`. Under
[stack-architecture.md](stack-architecture.md) Decision 12 and
[cli-surface.md](cli-surface.md) Decision 4 that verb becomes `dev`, so the published production
image would literally invoke the development command.

It becomes `host --stack /app`. The line is one word, and it is worth having as a decision because
it is the shortest possible statement of what this document is about: **the artifact should say what
it is for.**

### 2a. Windows Server is tested or it is not offered

A published release asset, a documented `scoop install`, and no test on the platform is a claim
nobody verified — the shape this campaign exists to remove, arrived at from the distribution side
rather than the code side.

The cheapest credible answer is not the whole suite on a Windows runner: the Testcontainers
integration tests dominate the build and are slow and flaky on Windows agents. It is **the modules
where platform semantics actually live** — `tesseraql-operations` (install, upgrade, catalogue,
`AppDirectory`) and `tesseraql-cli` (paths, launchers, `--watch`) — run on `windows-latest` as a
separate job.

If that is not worth its cost, the honest alternative is to say so in `getting-started.md` and stop
attaching the asset. Either is defensible. Publishing without testing is the one that is not.

### 3. Studio leaves the runtime's compile scope, which is already the plan

[stack-architecture.md](stack-architecture.md) Decision 14 makes framework surfaces stack-level, and
its slice 8 extracts Studio — recorded there as "a campaign, not a slice" because `StudioService`
couples preview, source editing, apply and reload, the scaffolder, the migration author and the test
runner to a runtime.

**This document adds a reason that slice did not have.** The extraction is not only about
authorisation and about one console per stack: it is what removes a test framework, an SMTP test
double and JUnit from every deployment. That is worth recording, because a campaign with two
independent justifications is scheduled differently from one with a single justification.

Until then the chain is measured and documented rather than quietly carried.

## Open

1. **Which mechanism for decision 1** — `optional` scoping versus a distribution module. Needs the
   remaining 57 jars classified: how many are dev-only beyond zonky, and how many the deployment
   image would keep anyway.
2. **Where `dev --embedded-db` lives** once the distributions split. The developer CLI keeps it; the
   question is whether the `embedded-db` command stays a subcommand of the same binary.
3. **Whether `AppInstaller`'s replace-the-directory upgrade works on Windows at all**, and what it
   should do instead if it does not — install beside and switch a pointer, rather than delete and
   move. Decision 2a's job is what would answer it; until then the upgrade path on Windows is
   unknown rather than working.
4. **Whether a guard can assert the boundary.** A test that fails when a development-only artifact
   reaches the deployment classpath is what makes decision 1 hold; without one the scoping decays
   the first time a convenient dependency is added, which is how it arrived here.
