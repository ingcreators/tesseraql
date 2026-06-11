# Release procedure

The framework releases as Maven artifacts from a git tag. Applications have their own release
tooling (`tesseraql:release-evidence`, `tesseraql:package-app`); this page covers releasing
TesseraQL itself.

## Preconditions

- All work merged to `main`; CI green on Java 21 and 25.
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

2. Full verification on the release commit:

   ```bash
   ./mvnw -B -ntp clean verify
   ```

3. Tag and push. The tag triggers `.github/workflows/release.yml`, which re-verifies the tag
   and publishes a GitHub release with generated notes:

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

## Publishing to Maven Central (later)

Central publication needs `<developers>` metadata, javadoc/source jars, and PGP signing on
top of this procedure; it is intentionally out of scope for 0.1.0, which releases as a git
tag plus GitHub release.
