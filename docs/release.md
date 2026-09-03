# Release procedure

The framework releases as Maven artifacts from a git tag. Applications have their own release
tooling (`tesseraql:release-evidence`, `tesseraql:package-app`); this page covers releasing
TesseraQL itself.

## Preconditions

- All work merged to `main`; CI green.
- The direct pushes below rely on the `main-protection` ruleset's repository-admin bypass
  (non-admins must go through a PR). Release tags (`v*`) are immutable by ruleset: they can
  be created but not moved or deleted.
- `CHANGELOG.md` has a section for the version with the release date filled in.
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

1. Set the release version across the reactor and commit:

   ```bash
   ./mvnw -ntp versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
   git commit -am "release: 0.1.0"
   ```

   Every version-bearing surface — the CLI `--version`, the embedded resolver's BOM coordinate,
   and the scaffolded wrapper POM — derives from the reactor version via
   `io.tesseraql.core.TesseraqlVersion` (a build-filtered resource), so `versions:set` updates
   them too; there are no version literals to edit by hand.

2. Full verification on the release commit:

   ```bash
   ./mvnw -B -ntp clean verify
   ```

3. Tag and push. The tag triggers `.github/workflows/release.yml`, which re-verifies the tag,
   **deploys the artifacts to GitHub Packages**, and publishes a GitHub release with generated
   notes:

   ```bash
   git tag -a v0.1.0 -m "TesseraQL 0.1.0"
   git push origin main v0.1.0
   ```

4. Move `main` to the next development version and commit:

   ```bash
   ./mvnw -ntp versions:set -DnewVersion=0.2.0-SNAPSHOT -DgenerateBackupPoms=false
   git commit -am "chore: start 0.2.0-SNAPSHOT"
   git push origin main
   ```

## Versioning

Semantic versioning. Until 1.0.0, minor releases may change APIs and YAML contracts; such
changes are called out in `CHANGELOG.md`. The Java policy (21 baseline / 25 compatibility)
holds for all 1.x releases.

## Publishing to GitHub Packages

The release workflow runs `./mvnw -DskipTests deploy` against the `github`
`distributionManagement` repository (`https://maven.pkg.github.com/ingcreators/tesseraql`),
authenticated with the workflow `GITHUB_TOKEN` (no extra secrets). Every reactor module — the
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
