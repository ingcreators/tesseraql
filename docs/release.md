# Release procedure

The framework releases as Maven artifacts from a git tag. Applications have their own release
tooling (`tesseraql:release-evidence`, `tesseraql:package-app`); this page covers releasing
TesseraQL itself.

## Preconditions

- All work merged to `main`; CI green.
- The direct pushes below rely on the `main-protection` ruleset's repository-admin bypass
  (non-admins must go through a PR). Release tags (`v*`) are immutable by ruleset: they can
  be created but not moved or deleted, and that ruleset has no bypass actors, so not even a
  repository admin can take one back.
- No untracked `docs/*.md` files sit in the working tree: the generated-reference drift
  guard scans the docs directory on the filesystem, so in-progress pages that are not
  part of the release commit fail `clean verify` (move them aside first).
- No open Dependabot alert against a released artifact. A release ships the Maven reactor, so
  an alert on the tooling around it — the documentation site, the VS Code extension — does not
  hold a tag. Dependabot's `scope` alone does not draw that line: the site declares astro and
  Starlight as `dependencies`, so its advisories arrive scoped `runtime` too. Match the
  ecosystem as well as the scope.

  ```bash
  gh api "repos/{owner}/{repo}/dependabot/alerts?state=open" \
    --jq '.[] | select(.dependency.scope == "runtime"
                       and .dependency.package.ecosystem == "maven")
          | "\(.security_advisory.severity)\t\(.dependency.package.name)"'
  ```

  Reading alerts needs security access on the repository, not just a token scope: without it
  the call fails and exits non-zero rather than printing an empty list, so trust an empty
  result only when the command succeeded. A row that remains is fixed before the tag, or the
  release records why it is deferred. A transitive npm floor Dependabot reports as not
  possible is raised by hand — [`SECURITY.md`](../SECURITY.md), "Dependency advisories".
- The gated dialect suites pass on the release commit. They run against live Oracle and
  SQL Server containers, so they are not part of per-PR CI. Dispatch the workflow rather than
  standing the containers up locally, and read the result back:

  ```bash
  gh workflow run dialects.yml --ref main
  gh run list --workflow=dialects.yml --limit 1
  ```

  The workflow also runs weekly, but a green scheduled run older than the release commit
  proves nothing about it — dispatch against the commit being released. The same suites run
  locally when a vendor container is already at hand:

  ```bash
  ./mvnw -pl tesseraql-coverage-core test -Dtesseraql.dialect.its=true \
    -Dtest='OraclePlanGuardIntegrationTest,SqlServerPlanGuardIntegrationTest'
  ./mvnw -pl tesseraql-runtime test -Dtesseraql.dialect.its=true \
    -Dtest='OraclePortabilityIntegrationTest,SqlServerPortabilityIntegrationTest'
  ```

## Steps

These run from a git worktree, because `CLAUDE.md` requires one for any session that changes
code and a release changes thirty POMs. One consequence runs through every push below: the main
checkout holds `main`, and a branch checked out in one worktree cannot be checked out, fetched
into, or force-updated in another — `git switch main`, `git fetch origin main:main` and
`git branch -f main origin/main` all fail outright. So the `refs/heads/main` a release worktree
sees is whatever the main checkout last left there, it cannot be caught up from here, and it is
never the commit you just built. Every push below names an explicit source — `HEAD:main`, never
`main` — and reads the branch back as `origin/main`. If a push is rejected as non-fast-forward,
git's hint suggests `git pull`; that advice is wrong in this worktree. Fix the refspec instead.

1. Land the version's `CHANGELOG.md` section through a pull request titled
   `release-prep: <version> changelog`, and merge it immediately before cutting the release
   commit. The section is not written from scratch: entries accumulate under `## Unreleased` as
   work merges, so this pass promotes that heading to `## <version> - <date>`, dated the day the
   tag will be pushed, and adds the lede — one paragraph on what the release is about, saying
   outright that it carries pre-1.0 breaking changes when it does.

   Why a pull request, when the two pushes below go straight to `main`: because nothing in the
   build ever reads this file. The docs guards (`sync-content.mjs`, `lint-prose.mjs`) walk only
   the pages `nav.mjs` maps, `clean verify` never opens `CHANGELOG.md`, and the GitHub release
   notes are generated from pull request titles. Review is the only check this change will ever
   get, and a direct push is the one way to take a change no build reads and give it no reader
   either. The version bump below is the opposite case: a mechanical rewrite of thirty POMs that
   `clean verify` covers completely, which is what makes its direct push a defensible exception
   to `AGENTS.md` rule 9. The other reason is what rides along — reading a release's worth of
   entries turns up repairs in files that *are* guarded, and they land here where the full check
   set can see them. 0.15.0's prep corrected `docs/reference-yaml-surface.md` and both copies of
   `tesseraql-view-v1.schema.json`, which had told authors that three keys require a layout key
   the parser no longer has — and that schema ships inside `tesseraql-yaml` to Maven Central,
   where a wrong instruction is permanent.

   0.12.0, 0.13.0 and 0.15.0 each took this route, merging under a minute before the release
   commit, which is what keeps the heading's date honest. 0.14.0 folded the section into its
   release commit instead, and the next docs pull request opened: "Eight published pages still
   taught the shapes v0.14.0 retired ... None of it was caught by a build, which is why it
   survived a release." Those pages were on the site and passing every docs guard; the drift was
   in what they meant. No lint was going to find it. This step is the release's one scheduled
   reading.

   No empty `## Unreleased` heading goes back afterwards. The prep commit removes it, neither
   the release commit nor the next-snapshot commit restores it, and the next pull request with
   something to record adds it back. Nothing in the build reads the heading, so a `CHANGELOG.md`
   whose first section is the release just cut is correct, not an omission.

2. Set the release version across the reactor and commit:

   ```bash
   ./mvnw -ntp versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
   git commit -am "release: 0.1.0"
   ```

   Every version-bearing surface — the CLI `--version`, the embedded resolver's BOM coordinate,
   and the scaffolded wrapper POM — derives from the reactor version via
   `io.tesseraql.core.TesseraqlVersion` (a build-filtered resource), so `versions:set` updates
   them too; there are no version literals to edit by hand inside the reactor. Two outside it
   are deliberately not part of a release. `vscode-extension/package.json` is bumped by the pull
   request that changes the extension and ships on its own `ext-v*` tag, never on this one
   ([vscode-extension.md](vscode-extension.md)); 0.14.0 swept a bump into its release commit,
   which is where that commit stopped being checkable at a glance.
   `examples/scaffold-demo-app/pom.xml` pins a `<tesseraql.version>` that `versions:set` cannot
   reach, since `examples` is not a reactor module, and it is left to drift on purpose: the
   scaffold dogfood test normalizes the pin away so the gallery comparison does not churn at
   release time.

   Then read the commit back. `git commit -am` stages every tracked modification in the
   worktree, so an unrelated edit lying around joins the release commit silently — that is how
   0.14.0's came to carry a changelog and an extension bump. It should be the reactor POMs and
   nothing else, thirty of them today:

   ```bash
   git show --stat HEAD
   ```

3. Full verification on the release commit:

   ```bash
   ./mvnw -B -ntp clean verify
   ```

4. Push the release commit, confirm the remote took it, then tag what the remote accepted. Five
   commands on purpose, run one at a time and read each result — the last one is irreversible:

   ```bash
   git push origin HEAD:main
   git fetch origin
   git rev-parse HEAD origin/main    # must print the same commit twice; if it does not, stop
   git tag -a v0.1.0 -m "TesseraQL 0.1.0" origin/main
   git push origin v0.1.0
   ```

   **Never `git push origin main v0.1.0`.** A multi-refspec push is not atomic by default, so
   the tag is sent whatever becomes of the branch — and the branch half cannot work from a
   worktree, because the source refspec `main` is the shared `refs/heads/main` the main checkout
   holds, not the commit you built. How it fails depends only on where that ref happens to sit,
   and both ways leave the tag on a commit that is not on `main`. Behind the remote, git drops
   the branch half on its own local fast-forward check and sends the tag anyway:

   ```text
    * [new tag]         v0.1.0 -> v0.1.0
    ! [rejected]        main -> main (non-fast-forward)
   ```

   That exits non-zero, which reads as "nothing happened". Level with the remote — the main
   checkout pulled recently — the branch half is a no-op, so there is nothing to reject:

   ```text
    * [new tag]         v0.1.0 -> v0.1.0
   ```

   That exits zero, which reads as a clean release, and is the worse of the two. `--atomic` does
   not rescue either case: it turns the first into a clean total failure and leaves the second
   exactly as it is, because an up-to-date refspec is not a failure for a transaction to roll
   back. The defect is the source refspec, not the transaction.

   Push the tag by name for the same reason — not `--tags`, not `--follow-tags`. Framework and
   extension tags sit on the same commits, so a sweeping push fires the extension release too.

   **Why the comparison before the tag.** `git push origin HEAD:main` is rejected whenever
   `main` moved while you were verifying — one dependency bump merging during `clean verify` is
   enough — and these commands are not chained, so nothing stops you carrying on. The `git
   fetch` then pulls *that* commit into `origin/main`. Tagging `origin/main` rather than `HEAD`
   guarantees only that the tag names a commit the remote accepted; the comparison is what
   guarantees the commit is yours. Tag `origin/main` all the same, because the two mistakes cost
   differently: a tag on someone else's `main` names a `-SNAPSHOT` reactor and fails the
   workflow's version check before anything ships, while a tag on your unpushed `HEAD` matches
   the version, passes, and publishes everything.

   **Why this order.** Pushing `main` is reversible; pushing a `v*` tag is not. The
   `release-tags` ruleset denies deletion, update and force-update on `v*` and has no bypass
   actors, and `release.yml` asks only whether the reactor version matches the tag name — never
   whether the tag is on `main`. A misplaced tag therefore verifies green and publishes
   normally, and Maven Central never un-publishes. The cost of getting this wrong is a burned
   version number.

   **What the tag sets in motion.** `release.yml` runs four jobs: `release` re-verifies the tag,
   checks the reactor version against it, publishes every module to GitHub Packages
   (`-Pdist deploy`) and creates the GitHub release with the CLI dist archives;
   `central-publish` rebuilds from the tag and uploads the signed bundle to Maven Central;
   `bump-package-managers` commits to the `homebrew-tap` and `scoop-bucket` repositories; and
   `demo-image` pushes `ghcr.io/ingcreators/tesseraql-demo` at `:<version>` and `:latest`.
   `jpackage.yml` fires on the same tag and attaches the per-OS app images. The release is not
   finished when the `release` job goes green: `bump-package-managers` polls the release for the
   CLI tarball and jpackage's Windows zip for up to twenty minutes and fails if either never
   arrives, so a releaser who walks away can find a red workflow later.

   Both pushes to `main` report the ruleset bypass named in the preconditions:

   ```text
   remote: Bypassed rule violations for refs/heads/main
   ```

   — one line for the pull-request rule and one for the required check. That is the admin bypass
   working, not a warning to act on.

5. Move the remote `main` to the next development version and commit:

   ```bash
   ./mvnw -ntp versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
   git commit -am "chore: start 0.2.0-SNAPSHOT"
   git push origin HEAD:main
   ```

   Same refspec, same reason. A successful push advances `origin/main`, never the local `main`,
   so the shared ref is exactly as stale here as it was in step 4 and the bare form fails again.
   The failure is merely confusing this time — there is no tag to outrun it — but it is one
   rule, not two. Nothing else is owed: this commit is POMs only, with no `CHANGELOG.md` edit
   and no empty `## Unreleased` heading to restore.

## Versioning

Semantic versioning. Until 1.0.0, minor releases may change APIs and YAML contracts; such
changes are called out in `CHANGELOG.md`. The Java baseline is 25
([jvm-baseline.md](jvm-baseline.md)), and 1.x will be declared on it.

## Publishing to GitHub Packages

The release workflow runs `./mvnw -B -ntp -DskipTests -Pdist deploy` against the `github`
`distributionManagement` repository (`https://maven.pkg.github.com/ingcreators/tesseraql`),
authenticated with the workflow `GITHUB_TOKEN` (no extra secrets). The `dist` profile is what
also builds the CLI archives the same job attaches to the release, and the Homebrew and Scoop
bumps wait on. Every reactor module — the
BOM, the Maven plugin, the runtime, Studio, and the opt-in `tesseraql-pdf`/`-excel`/`-s3`
codecs — is published, so an application resolves the framework from GitHub Packages by
declaring the BOM. Consumers add the repository to their `~/.m2/settings.xml` (GitHub Packages
requires authentication even for reads). The BOM version-manages the opt-in JDBC drivers
(`ojdbc11`, `mssql-jdbc`, `mysql-connector-j`) so a consumer specifies bare coordinates.

## Publishing to Maven Central

Release tags also publish the reactor to Maven Central: the `central-publish` job in
`release.yml` rebuilds from the tag with the root POM's `central` profile, which attaches
sources + javadoc jars, signs every artifact with the org-wide
`ingcreators Release <release@ingcreators.com>` PGP key (public key on
`keyserver.ubuntu.com`), and uploads the bundle through the Central Portal
(`central-publishing-maven-plugin`, auto-publish after validation). Credentials are the
`CENTRAL_TOKEN_USERNAME`/`CENTRAL_TOKEN_PASSWORD` Portal user token and the
`GPG_PRIVATE_KEY`/`GPG_PASSPHRASE` secrets; the job soft-skips while any of them are
missing, so a release never fails on absent Central credentials. Namespaces `io.tesseraql`
and `com.ingcreators` are DNS-verified on the Portal account. GitHub Packages remains the
deploy target for the profile-less build (SNAPSHOTs and internal consumption).
