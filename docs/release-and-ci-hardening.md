# Release and CI hardening

> **Status: complete.** Design plus nine slices, shipped 2026-09-07 as #1220-#1229, closing the
> 2026-09-04 whole-repo audit's F69, F70, F71, F72, F73, F75, F76, F77 and F78. It replaced the
> remediation plan's twelve-slice version of them.
>
> | # | PR | What |
> | --- | --- | --- |
> | 1 | #1220 | This document |
> | 2 | #1221 | `WorkflowLedgerTest`, the two SHA pins, the four `env:` hoists |
> | 3 | #1222 | `timeout-minutes` on 13 jobs, `concurrency` on all five workflows |
> | 4 | #1223 | `jdk.jfr`, the bytecode module ledger, the `*/pom.xml` trigger |
> | 5 | #1224 | The plugin-resolution ledger and three pins |
> | 6 | #1225 | One Maven, declared and enforced |
> | 7 | #1226 | `distributionSha256Sum`, and the `unzip` the wrapper needs to honour it |
> | 8 | #1227 | The attach choreography as two rehearsable scripts |
> | 8b | #1228 | Only the tag-gated job can write to the repository |
> | 9 | #1229 | One publish target: Maven Central |
>
> **Four things in this document were wrong and were corrected while building it** — the ledger
> count, the job count, the plugin guard's predicate, and the attach budget's arithmetic ("40
> attempts" is a cut, not a rise). Two rehearsals proved nothing on their first attempt and had to
> be redone. Every correction is recorded where it belongs, not only here.
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
| F70 | The jlinked image omits a module the code imports | `JfrPinningSource.java:4-5` imports `jdk.jfr.consumer`; both `--add-modules` lists omit `jdk.jfr` (found by token, never by line — they moved by seven in slice 3) | BFS over `java --describe-module` from the eleven roots on JDK 25.0.4: **31-module closure, `jdk.jfr` absent** |
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

### 3 — The module list is checked from bytecode, one way, and only half of it can be checked at all

F70 is two tokens of YAML. The guard is the interesting half, and re-measurement replaced all three
of its parts: the instrument, its home, and the escape hatch that justified its scope.

**Bytecode, not imports.** The first specification scanned `import` statements. That is falsified by
a file already in the tree: `tesseraql-test-core/.../CaptureServer.java` declares
`private final com.sun.net.httpserver.HttpServer server;` at `:20` and calls
`HttpServer.create(...)` at `:30`, all fully qualified, its only imports being four `java.util`
types. Run as specified against that module the scan returns nothing, while the truth is
`jdk.httpserver`. And the habit is not rare — 354 of the 1021 main sources write some system class
fully qualified. A constant pool has no such blind spot, and this repository already scans compiled
classes for exactly this reason (`ModelFieldConsumerScan`, docs/yaml-surface-consumers.md).

**In `tesseraql-maven-plugin`, not `tesseraql-docs-reference`.** A bytecode scan needs every
sibling's `target/classes`. `tesseraql-maven-plugin` builds 30th of 30, last in the reactor, which
is why
`YamlSurfaceConsumerGuardTest` already lives there. Its reactor walk lists direct `tesseraql-*`
children rather than walking from `..`, so it also cannot read the six worktrees under `.claude/`,
where a naive walk finds 6110 main sources instead of 1021.

**One way, and honest about the half it cannot see.** The assertion is that nothing the framework's
own classes reach for is *missing* from the list — never that a listed module is unnecessary. Of the
twelve roots, this scan justifies two. The rest are reached by name and leave no trace: `jdk.localedata`
through the `Locale.forLanguageTag` calls the compiler makes in ten places, `jdk.crypto.ec` through a
TLS cipher suite, `jdk.crypto.cryptoki` through an operator's `PKCS11` keystore type. Dropping any of
them degrades silently — non-root locales fall back to ROOT formatting in a framework whose feature
list includes Japanese identifiers. The failure message says this, so a green run is never read as
permission to trim.

**The escape hatch was false and is withdrawn.** The first version of this decision said a
dependency's transitive need "stays the `jdeps` step's job". There is no `jdeps` step: `grep -rn
jdeps` over the repository returned exactly one hit, which was that sentence. Worse, the obvious step
would miss the same module — netty's `PlatformDependent` references `jdk.jfr.FlightRecorder`, and
`jdeps --list-deps` on `netty-common` prints only `java.base java.logging jdk.unsupported` unless it
is given `--add-modules ALL-SYSTEM`. So `jdk.jfr` was owed twice over, and the dependency half is
**unguarded**. See open question 3.

**And the guard's failure direction had to be inverted.** Every way it can go blind produced a green
run: an index that cannot see the package, a scan that matches nothing, a workflow with no
`--add-modules` line. Each is now asserted before the membership check — two canary mappings, a
non-empty required set naming both modules, and exactly two lists whose parsed sets are equal. The
lists are found by the `--add-modules` token and parsed into a `Set` after stripping an optional `=`;
a `contains` test would pass on a prefix, and JDK 25 ships `jdk.management` against
`jdk.management.jfr` and `java.sql` against `java.sql.rowset`. Line numbers are never pinned — slice
3 moved both lines by seven while this document was being written.

Finally, `java.se` membership is resolved rather than derived from the `java.` prefix. Exactly one
`java.*` module sits outside that closure on JDK 25, `java.smartcardio`, and this repository ships
the SAML, OIDC and OAuth signing surfaces where an HSM path would land.

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
property with nothing that fails if it is deleted; both assertions were verified able to fail by
removing what they hold and watching them go red.

**The `maven-help-plugin` pin was rehearsed, not assumed.** This document first recorded it as a
risk — documented Maven behaviour that could not be tried here. It can: `./mvnw -N help:evaluate`
resolved `help:3.5.2` before the pin and the pinned version after it, so `pluginManagement` does
bind a goal resolved by prefix from the command line. The pinned values are the ones the wrapper's
Maven already supplied, so nothing about the build changes except that it is now this repository's
choice.

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
margin is variance rather than decay, so the budget rises once — to **40 minutes** — and the reason
is recorded rather than tuned again next tag.

**Correction: this decision first said "40 attempts", which is a cut, not a rise.** Sixty attempts
at twenty seconds is the twenty-minute budget that exists today; forty attempts is thirteen minutes.
The reasoning beside it ("doubling … a 21-minute margin") means forty *minutes*, which is 120 polls.
The scripts take `ATTACH_TIMEOUT_MINUTES` and derive the count, so the number in the workflow now
means what it says.

### 7 — What this campaign does not do

It does not touch the `demo-image` job's threat model beyond hoisting its four interpolations to
`env:`. F76 reads as privilege escalation in the plan; it is not. Reaching `release.yml:283` requires
the checkout at `:269` to succeed on the attacker's ref, which requires push access already. It is
hygiene, and hoisting is how hygiene is written down.

It does not rewrite the release job's recovery path — [decision 8](#8--github-packages-is-deleted-and-f73-goes-with-it) deletes the step instead.

**Correction, found while building slice 2.** The slice list first put the `jpackage.yml`
`contents: write` fix in slice 2, described as moving the grant onto the tag-gated jobs. That does
not work: `permissions:` takes no expression, and `jpackage` and `host-image` run on
`pull_request` as well as on a tag, so a job-level grant is the same grant. The honest fix is a job
split — the build jobs drop to `contents: read` and upload a CI artifact, and a separate tag-gated
job with `contents: write` downloads and attaches it. That is a restructure of the attach
mechanism, so it belongs to slice 8, which rewrites that mechanism anyway. Note the exposure is
the same class as F76: a fork's `GITHUB_TOKEN` is read-only whatever the block says, so reaching
this needs push access already.

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

Removing `<distributionManagement>` does not endanger the Central publish, and slice 9 proved it by
running it rather than by reading. `-Pcentral` activates `central-publishing-maven-plugin` with
`<extensions>true</extensions>`, whose `DeployLifecycleParticipant` injects an
`injected-central-publishing` execution binding the `publish` goal to the `deploy` phase and calls
`maybeSkipMavenDeployPlugin`. Two measurements, in order:

1. `./mvnw -Pcentral -N validate` logs **"Installing Central Publishing features"** — the
   participant runs.
2. With `<distributionManagement>` deleted, `./mvnw -Pcentral -N -Dgpg.skip=true deploy` reaches
   `central-publishing:0.11.0:publish (injected-central-publishing)` and fails with a **401 at the
   Portal** — not with "repository element was not specified". `maven-deploy-plugin` does not run.

The first attempt at measurement 2 proved nothing and is worth recording: without `-Dgpg.skip`, the
build failed at `maven-gpg-plugin:sign` for want of a key, which is the **verify** phase — the
`deploy` phase was never reached. A probe that stops before the thing it is probing is not a probe.

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
| 2 | `WorkflowLedgerTest` with assertions 1 and 3; the two SHA pins; the four `env:` hoists | F71, F76 | M |
| 3 | Assertion 2; `timeout-minutes` on 13 jobs, each bound measured; `concurrency` on all five workflows; the two `### Fixed` headings merged | F77 | M |
| 4 | `jdk.jfr` in both lists; the bytecode module ledger in `tesseraql-maven-plugin`; the `*/pom.xml` trigger path | F70, F75 | L |
| 5 | The plugin-resolution ledger; `maven-help`, `maven-dependency`, `maven-resources` pinned; the `outputTimestamp` row | F78 (part) | M |
| 6 | `requireMavenVersion`; the `mvn` → `./mvnw` sweep; `.devcontainer` and `scripts/` de-duplicated; the four reference banners regenerated | F78 (part) | L |
| 6b | The scaffolded app's own welcome page names the wrapper it ships | — | S, Docker |
| 7 | `distributionSha256Sum`, derived from the signed distribution, after slice 6 removes the script that regenerated the file without it | F72 | S |
| 8 | The two attach scripts, the loop ledger, the raised budget | F69 | M |
| 8b | The attach job split that lets the build jobs drop to `contents: read` | unfiled | M |
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

### How the checksum is derived, and how to renew it

Never paste the value. `wrapper:wrapper` has no parameter for it, so a Dependabot bump of the
distribution leaves it stale — loudly, but somebody has to know the recipe:

```sh
URL=$(grep '^distributionUrl=' .mvn/wrapper/maven-wrapper.properties | cut -d= -f2-)
curl -fsSL -o m.zip "$URL" && curl -fsSL -o m.zip.asc "$URL.asc"
curl -fsSL https://downloads.apache.org/maven/KEYS | gpg --import   # the ASF's own host
gpg --verify m.zip.asc m.zip                                        # must say "Good signature"
sha256sum m.zip                                                     # the value, from signed bytes
```

**The checksum is only valid for the archive format the machine picks, and that depends on
`unzip`.** `mvnw:178-182` rewrites the URL from `.zip` to `.tar.gz` when `unzip` is not on the PATH,
and `mvnw:252-257` chooses its extraction command by the same test. So a single
`distributionSha256Sum` can only ever match one of the two files, and the mismatch is reported as
"your Maven distribution might be compromised" — an alarming message for a benign cause. Pointing
the URL at the `.tar.gz` instead does not help: it breaks every machine that *does* have `unzip`,
which would then try to unzip a tarball. Ensuring the tool is the only stable answer, and
`mvnw.cmd` needs nothing — it always uses `Expand-Archive` on the `.zip`.

This cost a red build. The checksum was rehearsed in both directions on a machine that has `unzip`;
the two `maven:` base images that run `./mvnw` in `deploy/Dockerfile` and `deploy/Dockerfile.demo`
ship `tar` and not `unzip`, so `ci.yml`'s Deployment image job failed 0.459s into its build. Both
Dockerfiles now install it, `PluginVersionLedgerTest` refuses a Dockerfile that runs the wrapper
without it, and the fix was proved by building the real image rather than by reasoning about it.

The signature is the provenance, not the `.sha512` beside the zip: that file is served by the same
host as the zip, so it proves integrity of the download and nothing about who produced it. At
3.9.16 the signature verified as "Good signature from Slawomir Jaranowski <sjaranowski@apache.org>",
whose key is in the ASF KEYS file.

Both directions were rehearsed rather than assumed. With the cache cleared and the property present,
`./mvnw -version` re-downloaded and ran. With a deliberately wrong value it stopped:
`Failed to validate Maven distribution SHA-256, your Maven distribution might be compromised.`

## The guards

Everything this campaign leaves behind, and whether it is red today:

| Guard | Red at `8c83854bc` on |
| --- | --- |
| `uses:` is a 40-hex SHA | 2 of 30 lines |
| Every job declares `timeout-minutes` | 13 of 14 jobs |
| No `${{ }}` inside a `run:` body | 4 lines |
| `maven-wrapper.properties` declares a checksum | the file |
| Every system module the compiled classes reach for is in both `--add-modules` lists | `jdk.jfr` |
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

**Answered while building slice 3: `concurrency` goes on all five workflows, not three.** The
slice list said the three push-triggered ones. Leaving `dialects.yml` out is wrong for a different
reason than the others: it is scheduled, it holds vendor containers, and two overlapping runs
contend for them — so it gets a group with `cancel-in-progress: false`, which serialises rather
than cancels. `cancel-in-progress` is `${{ github.event_name == 'pull_request' }}` on `ci.yml` and
`jpackage.yml` and plain `false` on the two release workflows: a superseded pull-request run has
been answered by the push that superseded it, while a cancelled tag run leaves a published release
with missing assets. No assertion is added for `concurrency` — it is a policy, and a ledger that
freezes a policy is a ledger that argues with the next maintainer rather than catching a defect.

**Found while building slice 6: `tesseraql new` ships a wrapper and then tells you not to use
it.** `AppScaffolder.java:416` writes `mvn tesseraql:test -Dtesseraql.appHome=.` into the generated
welcome page, while the same scaffolder writes `mvnw` and `mvnw.cmd` beside it — visible in the
gallery at `examples/scaffold-demo-app/web/index.html:14`. It is the same defect one audience over,
but it edits a generated artefact, so it regenerates the gallery under
`-Dtesseraql.scaffold.regenerate=true` and needs Docker. Split out as slice 6b rather than folded in,
so this slice's diff stays reviewable and its gate stays Docker-free.

**Found while building slice 7: the scaffolded wrapper is two minor versions behind and
unchecksummed.** `tesseraql-yaml/src/main/resources/scaffold/maven-wrapper.properties` pins 3.9.9
while the framework's own wrapper is on 3.9.16, and it declares no `distributionSha256Sum` — so
every application `tesseraql new` creates downloads and executes an unverified distribution. It is
a generated artefact carried in the gallery at
`examples/scaffold-demo-app/.mvn/wrapper/maven-wrapper.properties`, so fixing it regenerates the
gallery and needs Docker. It belongs with slice 6b, which already pays that cost.

**4 — Answered 2026-09-07: yes, split it.** `jpackage.yml` granted `contents: write` for the whole
workflow and triggers on `pull_request`, so every same-repo pull request ran `./mvnw` — arbitrary
branch code — holding a token that could write to the repository. `permissions:` takes no
expression and both image jobs run on both events, so a job-level grant is the same grant; the only
real fix is the restructure. Slice 8b does it: the image jobs drop to `contents: read` and hand
their archive over as a CI artifact, and a tag-gated `attach` job with `contents: write` collects
and attaches. The handover is exercised by every pull request; the collect-and-attach half is only
ever reached by a tag, which is the cost the maintainer accepted. `always()` on that job is
deliberate — a plain `needs` would let a Windows failure keep the Linux and macOS images off the
release, which the per-job attach it replaces could not do.

**3 — Answered 2026-09-07, and the question's own premise was wrong.** A `jdeps` step now runs in
`ci.yml` and `jpackage.yml`, but it buys much less than this document claimed, and the claim is
corrected here rather than quietly left standing.

- **The motivating example was already guarded.** This document rested the case on netty's
  `PlatformDependent` reaching `jdk.jfr.FlightRecorder`. But `jdk.jfr` is also reached by
  first-party code — `JfrPinningSource` imports `jdk.jfr.consumer` — and `JlinkModuleLedgerTest`
  already asserts it. `jdk.jfr` was over-determined; netty demonstrated nothing that was unguarded.
- **`--add-modules ALL-SYSTEM` is a measured no-op** on the shipped fat jars: with and without it,
  `jdeps` names the same twenty modules, `jdk.jfr` included. The flag is not in the step.
- **`--ignore-missing-deps` is mandatory.** Without it the command produces no module list at all.
- **A one-way diff defends four of the twelve roots and is green for eight**, measured by dropping
  each root in turn. Two of those four are already covered by the first-party ledger. The honest
  marginal value is `jdk.unsupported`, `java.se`, and forward cover for a new dependency.
- **It is red today for the wrong reason** unless ignored carefully: `jdk.attach` and `jdk.jdi` are
  reached only by `javassist.util.HotSwapAgent`, `HotSwapper` and `HotSwapper$1`, which have no
  callers anywhere in the 51 MB jar. The ignore list is keyed on the **reaching class**, never on
  the module — keyed on the module it would also hide a real dependency that reached them.
- **`jdeps --list-deps` exits 0 on a path that does not exist**, printing its warning to stdout and
  then a plausible module list. The step checks the jar first and refuses an empty parse, so it
  fails closed where bare `jdeps` would go silently green.
- **It is not in `mvn verify` and cannot be**: every `-Pdist` invocation passes `-DskipTests`, so a
  test would never see a fat jar. It runs in the two jobs that already build one, at about 17s each.

Positively validated rather than only observed red: a synthetic dependency reaching
`javax.smartcardio` and `com.sun.nio.sctp` is caught, both modules named with the reaching class.

**2 — Is 40 minutes the right attach budget?****2 — Is 40 minutes the right attach budget?** The measured margins are 4m51s, 1m48s and 8m04s, all
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
