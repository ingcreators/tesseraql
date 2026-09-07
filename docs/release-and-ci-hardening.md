# Release and CI hardening

> **Status: design.** This document opens the campaign the 2026-09-04 whole-repo audit filed as
> F69, F70, F71, F72, F73, F75, F76, F77 and F78, and it replaces the remediation plan's twelve-slice
> version of them.
>
> **The plan was re-measured against `8c83854bc` on 2026-09-07** — after the deterministic-output
> campaign (#1212-#1219) moved the root POM underneath it. Nine of the plan's claims did not
> survive; three of its four load-bearing justifications are false; and one of its own BLOCKING
> reviewer corrections would publish, at a tag, a module the repository deliberately never
> publishes. The corrections are in [What the plan got wrong](#what-the-plan-got-wrong). Read the
> decisions below, not the plan.
>
> **F73 dissolved rather than shipped.** Its open question — "does the GitHub Packages deploy still
> need to exist?" — was answered no by the maintainer on 2026-09-07, so the step F73 exists to make
> re-runnable is deleted instead. See [decision 8](#8--github-packages-is-deleted-and-f73-goes-with-it).
>
> **And re-measure this document too.** Every number here was produced by a command re-run at
> `8c83854bc`, and each is dated by that commit. The deterministic-output campaign corrected five of
> its own design's six decisions once slices started measuring them. A slice that inherits a count
> or a line number from here without re-running the command will inherit a wrong one.

## The class of defect

Two things are true of this repository at once.

The release path is the only path it cannot rehearse. `release.yml:22` gates the whole job on
`github.event_name == 'push'`, and the push it means is a `v*` tag. No pull request enters it, no
workflow-dispatch run reaches it, and the only recovery gesture available after it fails is GitHub's
"Re-run failed jobs". Everything on that path is therefore load-bearing and unproven.

And nothing in this repository looks at `.github/` at all. `tesseraql-docs-reference/src/test/java`
holds twenty-one tests, eighteen of them built on `Path.of("..")` and fifteen of those walking the
repository root — but fourteen immediately filter to `/src/main/java/`, so exactly one reads
`.github/`: `NoConflictMarkersTest`, whose comment at `:43` says in as many words that "`.github/`
stays scanned — its workflows are tracked here". It scans them for merge-conflict markers. Nothing
asserts anything else about them.

So the workflows have drifted the way anything drifts when only review holds it: two actions
unpinned, thirteen of fourteen jobs unbounded, a wrapper distribution fetched with no checksum, a
jlinked runtime missing a module its own code imports, and the same twenty-minute polling loop
copied three times. None of that is a bug in the ordinary sense. All of it is a claim the project
makes about its own build that nothing checks.

## What is broken

Every row was re-measured at `8c83854bc`; the command that produced it is in the last column.

| # | What | Measured | How |
| --- | --- | --- | --- |
| F71 | Two actions are floating tags, not SHAs | **2 of 30** `uses:` lines — `jpackage.yml:144` and `:429`, both `actions/upload-artifact@v7` | `grep -E '^\s*uses:' .github/workflows/*.yml \| grep -vE '@[0-9a-f]{40}'` |
| F77 | Jobs run under GitHub's 360-minute default | **13 of 14 jobs** declare no `timeout-minutes`; the only one that does is `dialects.yml:20`. No workflow declares `concurrency` | `grep -n 'timeout-minutes\|concurrency' .github/workflows/*.yml` → one hit |
| F70 | The jlinked image omits a module the code imports | `JfrPinningSource.java:4-5` imports `jdk.jfr.consumer`; both `--add-modules` lists (`jpackage.yml:102`, `:245`) omit `jdk.jfr` | BFS over `java --describe-module` from the eleven roots on JDK 25.0.4: **31-module closure, `jdk.jfr` absent** |
| F76 | Untrusted-shaped values are interpolated into shell | **4 sites**, all in `release.yml` — `:276`, `:279`, `:283`, `:285` | `${{ … }}` inside a `run:` body |
| F72 | The Maven distribution is fetched unverified | `.mvn/wrapper/maven-wrapper.properties` is three lines with no `distributionSha256Sum`; `mvnw:226` verifies only when the property is present | `cat .mvn/wrapper/maven-wrapper.properties` |
| F78 | Two Mavens, and the plugins that write shipped bytes float | `.devcontainer/Dockerfile:20` installs `maven` (3.8.7); the wrapper is 3.9.16. **`maven-help-plugin` is unpinned and `help:evaluate` runs at 5 tag-path sites**. `tesseraql-yaml` declares `maven-dependency-plugin` and `maven-resources-plugin` with no version and nothing manages them | See [decision 4](#4--the-unpinned-plugins-that-matter-are-the-two-that-write-shipped-bytes) |
| F69 | The attach step polls a fixed budget | Three copies of `for i in $(seq 1 60)` with `sleep 20` — `jpackage.yml:183`, `:442`, `release.yml:187`. Margin at v0.14.0: **1m48s** | `gh run view` step timestamps, recomputed below |
| F75 | A module POM bump does not trigger the image build | `jpackage.yml:10-15` filters on `pom.xml`, not `*/pom.xml` | The root `<modules>` holds 29 entries, all direct children |
| — | *Unfiled.* `jpackage.yml` grants `contents: write` at workflow level and triggers on `pull_request` | `:23-24` against `:9-15` | Every same-repo pull request runs `./mvnw` — arbitrary branch code — holding a write token |

### The attach margin, recomputed

The plan says the margin "shrinks with the suite". It does not. Recomputed from the real runs, the
deadline is the earliest attach step's start plus 60 × 20s, and the release job is what it waits for:

| Tag | Earliest attach starts | 20-minute deadline | Release job completes | Margin |
| --- | --- | --- | --- | --- |
| v0.13.0 | 2026-08-08 | — | — | 4m51s |
| v0.14.0 | 05:34:41Z | 05:54:41Z | 05:52:53Z | **1m48s** |
| v0.15.0 | 09:38:04Z | 09:58:04Z | 09:50:00Z | 8m04s |

I recomputed v0.14.0 and v0.15.0 myself from `gh run view --json jobs`. The margin moves with the
*release* job's duration (19m42s at v0.14.0, 13m44s at v0.15.0), not with the jpackage build's. So
the risk is real and the trend is not. That distinction is what shrinks the slice: this wants one
shared script and a message that names the missing asset, not the `gh run view` state machine the
plan specifies — which would need permissions `jpackage.yml:23-24` does not grant anyway.

## Decisions

### 1 — The guard is one ledger in `tesseraql-docs-reference`, and it resolves `.github/workflows` directly

Every assertion this campaign can make about a workflow is a statement about a tracked text file,
which is precisely the shape of the ledgers that module already holds. One new `WorkflowLedgerTest`
joins them. It runs inside `mvn verify` on every pull request, so a workflow edit that reintroduces
any of these is refused before it merges.

It resolves `REPO.resolve(".github/workflows")` and lists that directory. It does **not** walk the
repository root. Both `NoConflictMarkersTest.java:43-46` and `AppNameReadLedgerTest.java:55-57` carry
an explicit skip for `/.claude/` because that directory holds worktrees, and a walk-based ledger
would read other branches' in-progress files on the machine the work is being done on. Listing one
directory has no such failure mode, and there is no second place a workflow can live.

### 2 — Four assertions, each red today, each shipped with its own fix

The ledger carries four assertions. Each was verified red at `8c83854bc`, and each ships in the same
pull request as the change that makes it green — never a PR apart, or the branch is red between them.

1. Every `uses:` matches `@[0-9a-f]{40}`. Red on `jpackage.yml:144` and `:429`.
2. Every job declares `timeout-minutes`. Red on 13 of 14 jobs.
3. No `${{ … }}` appears inside a `run:` body. Red on `release.yml:276`, `:279`, `:283`, `:285`.
4. `.mvn/wrapper/maven-wrapper.properties` declares `distributionSha256Sum`. Red today.

Assertions 1 and 3 are regex-shaped and cheap. Assertion 2 needs the file parsed far enough to know
what a job is, and it forces thirteen edits across four files — so it lands with the timeout slice,
not with the pin slice. Assertion 4 is one line and lands with the checksum.

This is the campaign's whole answer to "the release path cannot be rehearsed". None of these four
assertions rehearses it. All four make the *configuration* of it checkable, which is the part that
actually drifted.

### 3 — The module list is checked one way: sources → `--add-modules`, never the reverse

F70 is two tokens of YAML. The guard is the interesting half, and it must be stated one-way.

A ledger builds a package → module index from `ModuleFinder.ofSystem()` (verified: `jdk.jfr.consumer
→ jdk.jfr`, `com.sun.net.httpserver → jdk.httpserver`, 915 system packages). It scans every
first-party `*/src/main/java/**.java` import, keeps the ones that resolve to a non-`java.*` system
module, and asserts each appears in **both** `--add-modules` lists. Red today on `jdk.jfr`.

The objection to a source scan is that it cannot see nine of the eleven modules already listed —
`jdk.net`, for one, is required by Netty through `vertx-core` and appears in no first-party import.
That objection applies only to the reverse assertion. This ledger never claims the list is minimal
or that every entry is justified; it claims that nothing the framework's own code imports is
*missing* from it. A dependency's transitive need stays the `jdeps` step's job, which reads the fat
jar and sees what no source scan can.

The repo-wide scan returns exactly two non-`java.*` package families today — `jdk.jfr.consumer` and
`com.sun.net.httpserver` — so `jdk.jfr` is one missing module, not the first of many.

### 4 — The unpinned plugins that matter are the two that write shipped bytes

The plan's slice 6a says the lifecycle plugins float and must be pinned. Six of its seven named
plugins do not float: Maven 3.9.16 binds `maven-resources:3.4.0`, `install:3.1.4`, `deploy:3.1.4`
and `jar:3.5.0` in `META-INF/plexus/default-bindings.xml`, `clean:3.2.0` in `components.xml`, and
`dependency:3.7.0` in the super-POM's `pluginManagement`. #1215 already pinned `maven-jar-plugin` to
3.5.0 — the same version 3.9.16 binds.

The premise is wrong and the conclusion survives, for a sharper reason the plan does not name.

**`maven-help-plugin` genuinely floats.** It appears in neither the super-POM nor the bindings, and
`help:evaluate` is invoked at five tag-path sites: `release.yml:56`, `jpackage.yml:69`, `:158`,
`:226`, `:419`. A prefix-resolved goal with no managed version resolves the newest release from the
plugin repository, so the version that computes the release's `VERSION` variable is whatever was
published most recently.

**And two plugins bake shipped bytes with a version the Maven distribution chooses.** Auditing every
`<build><plugins>` entry in the reactor against the root `pluginManagement` returns exactly two that
resolve to neither a declared nor a managed version: `tesseraql-yaml/pom.xml:73` `maven-dependency-plugin`
and `:96` `maven-resources-plugin`. They are not incidental. They unpack the Hypermedia Components
WebJAR and copy the email templates into `${project.build.outputDirectory}/tesseraql/templates/tql/email`
— bytes that ship inside `tesseraql-yaml.jar`.

Both halves of that were observed rather than reasoned. A `./mvnw clean verify` at `8c83854bc` logs
`dependency:3.7.0:unpack (unpack-hc-email) @ tesseraql-yaml` and
`resources:3.4.0:copy-resources (copy-hc-email) @ tesseraql-yaml` — versions the wrapper's Maven
supplies, from its super-POM `pluginManagement` and its `default-bindings.xml` respectively. The
3.8.7 installed by `.devcontainer/Dockerfile:20`, which `AGENTS.md:28`, `CONTRIBUTING.md:8` and
`docs/build.md:4` all tell a contributor to invoke as `mvn`, supplies `maven-dependency-plugin:2.8`
and `maven-resources-plugin:2.6` from the same two files.

That is the same subject #1215 just closed. `project.build.outputTimestamp` makes the archive
reproducible; a resource plugin whose version depends on which Maven you happened to invoke makes
its contents not. The eleven other version-less plugin declarations in the reactor are honest — the
root `pluginManagement` covers them — so the guard's predicate is **unresolved by root
`pluginManagement`**, not "declares no `<version>`". Written the second way it is red on thirteen
POMs, eleven of them for no reason.

The same ledger asserts that `project.build.outputTimestamp` is still set. #1215 shipped the
property with nothing that fails if it is deleted.

The sharpest instance of the two-Mavens half is `scripts/bootstrap-maven-wrapper.sh:6`, the script
whose entire job is to install the wrapper: it runs `mvn -N wrapper:wrapper`. It invokes the Maven
the wrapper exists to replace, and `wrapper:wrapper` has no parameter for a distribution checksum —
so it also regenerates `maven-wrapper.properties` without the property slice 7 adds. That is why
slice 7 waits on slice 6. `scripts/run-ci-local.sh:4` (`mvn -B -ntp verify`, in a script named for
reproducing CI) and `scripts/verify-dev-env.sh:11` (`mvn -version`, which reports 3.8.7 and then
prints that the environment looks ready) are the other two.

### 5 — `-Dmaven.deploy.skip` is only ever passed as `=true`

The plan's reviewer raised a BLOCKING correction on the release job's re-runnability: pass
`-Dmaven.deploy.skip=${{ … == 'complete' }}`. On the ordinary path that expands to
`-Dmaven.deploy.skip=false`.

A CLI user property beats a POM property. `tesseraql-docs-reference/pom.xml:23` sets
`<maven.deploy.skip>true</maven.deploy.skip>` precisely so that module is never published, and
`=false` on the command line overrides it — at a tag, for a module that exists to hold the
repository's own ledgers. The flag is only ever appended conditionally, and only ever as `=true`.

Decision 8 deletes the step this would have been passed to, so the flag never appears. It is
recorded because the plan still prescribes it, and because `tesseraql-docs-reference/pom.xml:23` is
the kind of deliberate POM property a future `-D` on a command line can quietly override.

### 6 — Two attach scripts, not a state machine

Three copies of `for i in $(seq 1 60); do … sleep 20` wait on two different things:
`jpackage.yml:183` and `:442` wait for the GitHub release that `release.yml` creates, and
`release.yml:187` waits for the assets `jpackage.yml` attaches. They are two scripts, not one, and
the campaign ships them as two scripts under `scripts/` with the budget in one place and a failure
message that names the asset it gave up on. A ledger asserts no `.github/workflows/*.yml` file
contains a `seq 1 ` polling loop, which is red today on three lines and keeps the fourth copy from
being written.

Raising the budget is a separate question from de-duplicating it, and the measurement above says the
margin is variance rather than decay, so the budget rises once — to 40 attempts — and the reason is
recorded rather than tuned again next tag.

### 7 — What this campaign does not do

It does not touch the `demo-image` job's threat model beyond hoisting its four interpolations to
`env:`. F76 reads as privilege escalation in the plan; it is not. Reaching `release.yml:283` requires
the checkout at `:269` to succeed on the attacker's ref, which requires push access already. It is
hygiene, and hoisting is how hygiene is written down.

It does not rewrite the release job's recovery path. See [open questions](#open-questions).

### 8 — GitHub Packages is deleted, and F73 goes with it

Nothing consumes it. Measured at `8c83854bc`:

- **Zero of the repository's 31 POMs declare `<repositories>` or `<pluginRepositories>`** — the
  root POM has only `<distributionManagement>`, which is a publishing target. The scaffolder's
  generated POM (`AppScaffolder.java:529-588`) declares none either, and
  `deploy/Dockerfile{,.demo}` build the reactor from source with `./mvnw install`.
- **The CLI never contacts it.** `ModuleResolver.java:59-65` resolves opt-in modules through an
  in-process ShrinkWrap/Aether resolver against a synthetic POM that declares no repositories, so
  the effective remote set is MIMA's built-in `central` plus whatever the operator's own
  `~/.m2/settings.xml` adds. `modules.lock` records coordinate and SHA-256 only, never an origin
  (`ModulesLock.java:30`), so nothing pins provenance to it either.
- **Maven Central already carries the same artifacts, publicly and unauthenticated.**
  `io/tesseraql/tesseraql-bom/maven-metadata.xml` reads `<release>0.15.0</release>` with
  `lastUpdated 20260903103356`, and `io/tesseraql/tesseraql-report/` carries 0.11.0 through 0.15.0.
- **No SNAPSHOT is ever published anywhere.** `deploy` runs at exactly two places, both in
  `release.yml` (`:67` and `:134`), and the workflow is tag-gated — so the root POM's
  `<snapshotRepository>` has never been written to.

And it is not merely unused. `release.yml:88` declares `needs: release` on the Central publish, so a
401, a 409 or a network blip in the GitHub Packages upload at `:67` fails the `release` job and the
job that publishes to the channel consumers actually read **never starts**. The redundant channel
gates the real one. That is F73's whole scenario, and deleting the step removes the scenario rather
than making it recoverable.

Removing `<distributionManagement>` does not endanger the Central publish. `-Pcentral` activates
`central-publishing-maven-plugin` with `<extensions>true</extensions>`, whose
`DeployLifecycleParticipant` injects an `injected-central-publishing` execution binding the
`publish` goal to the `deploy` phase and calls `maybeSkipMavenDeployPlugin` — verified by reading
the class in the plugin jar, not from its documentation. `maven-deploy-plugin`, the only consumer of
`<distributionManagement>`, does not run on that path. The slice proves it before deleting anything:
`./mvnw -Pcentral -DskipTests validate` must log "Installing Central Publishing features", and a
`deploy` on the branch with the element already removed must fail at the Portal or at GPG, never at
"repository element was not specified".

`release.yml:67` becomes `-Pdist package`; the next step still uploads
`tesseraql-cli/target/tesseraql-cli-*-dist.zip`, so the release assets are unchanged.

The user-facing half is the point of the slice. `docs/getting-started.md:131-158` tells every new
reader that "the framework artifacts resolve from GitHub Packages, which **requires authentication
even for public reads**" and walks them through minting a `read:packages` token. That text landed in
#327, before Central carried anything; Central has held releases since 0.7.1. The onboarding page's
first instruction is a barrier that has not been necessary for eight releases.

## The slices

| # | Slice | Closes | Size |
| --- | --- | --- | --- |
| 1 | This design document, plus `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` | — | S |
| 2 | `WorkflowLedgerTest` with assertions 1 and 3; the two SHA pins; the four `env:` hoists; `contents: write` moved off `jpackage.yml`'s workflow level onto its tag-gated jobs | F71, F76, unfiled | M |
| 3 | Assertion 2; `timeout-minutes` on 13 jobs; `concurrency` on the three push-triggered workflows | F77 | M |
| 4 | `jdk.jfr` in both lists; the system-module ledger; the `*/pom.xml` trigger path | F70, F75 | M |
| 5 | The plugin-resolution ledger; `maven-help`, `maven-dependency`, `maven-resources` pinned; the `outputTimestamp` row | F78 (part) | S |
| 6 | `requireMavenVersion`; the `mvn` → `./mvnw` sweep; `.devcontainer` and `scripts/` de-duplicated | F78 (part) | L |
| 7 | `distributionSha256Sum`, after slice 6 removes the script that regenerates the file without it | F72 | S |
| 8 | The two attach scripts, the loop ledger, the raised budget | F69 | M |
| 9 | The GitHub Packages deploy, `<distributionManagement>` and the `read:packages` onboarding step are deleted | F73 (dissolved) | M |

Slice 1 must carry the document *and* both registration lists in one pull request.
`docs-site/scripts/sync-content.mjs:38-41` fails the Astro job on an `EXCLUDED` entry whose file does
not exist, and `InternalDocsSyncTest` fails `mvn verify` if the two lists disagree — so a batched
registration PR, which the remediation plan recommends, reddens the build in both directions.

Ordering that binds: slices 2, 4 and 8 all edit `jpackage.yml`; 3 and 8 edit `release.yml`; slice 7
depends on slice 6 having deleted `scripts/bootstrap-maven-wrapper.sh`, which regenerates the wrapper
properties file and cannot emit the checksum. Each branches from a fresh `origin/main`.

Slice 3 also merges the two `### Fixed` headings under `## Unreleased` in `CHANGELOG.md` (`:64` and
`:674`). Three separate measurements have now reported them and no campaign has owned them.

## The guards

Everything this campaign leaves behind, and whether it is red today:

| Guard | Red at `8c83854bc` on |
| --- | --- |
| `uses:` is a 40-hex SHA | 2 of 30 lines |
| Every job declares `timeout-minutes` | 13 of 14 jobs |
| No `${{ }}` inside a `run:` body | 4 lines |
| `maven-wrapper.properties` declares a checksum | the file |
| Every imported system module is in both `--add-modules` lists | `jdk.jfr` |
| Every `<build>` plugin resolves through root `pluginManagement` | 2 declarations |
| `project.build.outputTimestamp` is set | nothing — a forward guard for #1215 |
| No workflow holds a `seq 1 ` polling loop | 3 lines |

Seven of the eight are red before the fix and green after, in `mvn verify`, on every pull request,
in one process, with no network. The eighth guards a property that already exists.

## What this breaks

Nothing an application author can see. Two changes are visible to a contributor: `mvn` stops being
the documented command in favour of `./mvnw`, and the devcontainer stops installing a second Maven.
Both are slice 6, and both are the point of it.

`cancel-in-progress` is a policy change rather than a defect fix. It cancels an almost-finished
`Maven verify on Java 25` when a fix-up push lands on the same branch, and that job is the required
check. Slice 3 sets it on `pull_request` only; `push` and tag runs are never cancelled.

## Open questions

**1 — Answered 2026-09-07: no.** "Does the GitHub Packages deploy still need to exist?" was the
question F73 turned on, and the maintainer's answer is that it has no users. It is now
[decision 8](#8--github-packages-is-deleted-and-f73-goes-with-it) and slice 9, and F73 is closed by
deletion rather than by a fix.

**2 — Is 40 attempts the right attach budget?** The measured margins are 4m51s, 1m48s and 8m04s, all
against a 20-minute budget. Doubling the attempts makes the worst observed case a 21-minute margin
rather than a 1m48s one, at the cost of a jpackage job that hangs for forty minutes when the release
genuinely never appears. The failure is recoverable either way — a timed-out attach reddens jpackage
alone, the release exists, and re-running jpackage re-attaches.

## What the plan got wrong

Recorded so the next reader does not re-derive it. Each was measured at `8c83854bc`.

1. **Slice 6a's founding premise.** Maven 3.9.16 already binds six of the seven plugins it says
   float. See [decision 4](#4--the-unpinned-plugins-that-matter-are-the-two-that-write-shipped-bytes).
2. **Its reviewer's BLOCKING fix for F73** passes `-Dmaven.deploy.skip=false`, which would publish
   `tesseraql-docs-reference` at a tag. See [decision 5](#5--dmavendeployskip-is-only-ever-passed-as-true).
3. **"The attach margin shrinks with the suite."** It grew, twice, because the release job got
   faster. The margin tracks the release job's duration.
4. **F72's justification.** "All eight CI sites fetch and execute the zip on every run" is false —
   `setup-java` v6 caches the wrapper distribution under its own key. The defect is real; the sites
   it actually covers are the two Docker builds, `ci.yml:166` and `release.yml:286`.
5. **F76 as privilege escalation.** It is hygiene. Reaching the sink requires push access already.
6. **F74 is closed**, by #1215, not by this campaign — but with no assertion behind it, which slice 5
   adds.
7. **`**/pom.xml`** as the widened trigger path drops the root POM, which is the one the filter
   covers today. It is `pom.xml` plus `*/pom.xml`; the reactor has 29 modules, all direct children.
8. **"Gate the deploy step on the packages pre-flight"** reaches an empty `target/`, because that
   same invocation builds the dist archives the next step uploads. Recorded by the plan's own
   reviewer and repeated here because the slice text still carries the original.
9. **The reviewer's correction to F77's line citation** ("`ci.yml:144` is `kill "$DEV_PID"`; cite
   `:145`") is itself off by one. At HEAD `:143` is `kill "$DEV_PID"` and `:144` is
   `wait "$DEV_PID" || true`. The slice text was right.

And two corrections to the *re-measurement* that produced this document, both found while writing it:

10. A guard phrased "no `<plugin>` lacks `<version>`" is red on thirteen POMs, eleven of them
    honest. The predicate is "unresolved by root `pluginManagement`".
11. The job count is 14, not 13: `dialects`, `publish`, `release`, `central-publish`,
    `bump-package-managers`, `demo-image`, `verify`, `windows-tests`, `dist`, `deploy-image`,
    `docs-site`, `vscode-extension`, `jpackage`, `host-image`. Thirteen lack a timeout.
12. **The ledger count in this document's own first draft.** It said sixteen ledgers and one
    repo-root walk. Recounted: eighteen tests resolve `Path.of("..")`, fifteen of those walk from
    it, and fourteen filter to `/src/main/java/` on the next line. One reads `.github/`.
