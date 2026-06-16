# Release procedure

The framework releases as Maven artifacts from a git tag. Applications have their own release
tooling (`tesseraql:release-evidence`, `tesseraql:package-app`); this page covers releasing
TesseraQL itself.

## Preconditions

- All work merged to `main`; CI green on Java 21 and 25.
- The direct pushes below rely on the `main-protection` ruleset's repository-admin bypass
  (non-admins must go through a PR). Release tags (`v*`) are immutable by ruleset: they can
  be created but not moved or deleted.
- `CHANGELOG.md` has a section for the version with the release date filled in.
- The gated dialect suites pass against live containers (they are not part of regular CI):

```bash
./mvnw -pl tesseraql-coverage-core test -Dtesseraql.dialect.its=true \
  -Dtest='OraclePlanGuardIntegrationTest,SqlServerPlanGuardIntegrationTest'
./mvnw -pl tesseraql-camel-runtime test -Dtesseraql.dialect.its=true \
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

## Publishing to Maven Central (later)

Central publication needs `<developers>` metadata, javadoc/source jars, and PGP signing on
top of this procedure; it is intentionally out of scope for 0.1.0, which releases as a git
tag, a GitHub release, and GitHub Packages artifacts.
