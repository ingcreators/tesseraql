# Upgrading

How an application team moves to a new TesseraQL release. Two things carry a version: the
`tesseraql` CLI you installed ([getting-started.md](getting-started.md)), and the framework
version your app builds against (the `tesseraql.version` property in the scaffolded
`pom.xml`, plus the resolved module closure in `modules.lock`). Keep them on the same
release — the scaffold pins them together, and this page keeps them together through an
upgrade.

## Compatibility before 1.0

TesseraQL is pre-1.0: backward compatibility is not yet a goal, so a minor release may
change Java APIs, YAML contracts, and generated artifacts without a deprecation period.
Every notable change — breaking ones called out explicitly — is recorded per release in
[CHANGELOG.md](../CHANGELOG.md) (Keep a Changelog format, semantic versioning). Read the
entries for the whole span you are crossing, not just the latest version, before touching
anything.

## Learning a release exists

- **The CLI tells you.** Every CLI run prints a one-line notice to stderr when a newer
  release is available — `A newer TesseraQL is available: <latest> (current <yours>)` with
  the download link. The check is passive: the notice comes from a small per-user cache
  (under `~/.tesseraql/`), a background thread refreshes that cache at most once every
  24 hours from the GitHub Releases API, and a failed check is silently ignored — it never
  slows or breaks a command. It is disabled in CI, and you can silence it with
  `TESSERAQL_NO_UPDATE_NOTIFIER=1`.
- **GitHub releases.** Each release at
  <https://github.com/ingcreators/tesseraql/releases> carries the distribution archives,
  the per-OS app images, and release notes.

## Upgrading the CLI

There is no self-update command yet — you replace the installation the same way you made
it:

- **Distribution archive**: download the new `tesseraql-cli-<version>-dist.zip` (or
  `.tar.gz`), unpack it, and point your `PATH` at the new `bin/`.
- **App image**: download and replace the jpackage image for your OS.

Verify with `tesseraql --version`. The install channels are described in
[getting-started.md](getting-started.md#install-the-cli).

## Upgrading an app

1. **Bump the wrapper pom.** Set `tesseraql.version` in the scaffolded `pom.xml` to the
   new release, so the Maven/CI path builds against the same version the CLI runs. The
   framework artifacts resolve from GitHub Packages as before.
2. **Refresh `modules.lock`** if the app declares `tesseraql.modules`. Framework module
   coordinates declared without a version (the normal form, e.g.
   `io.tesseraql:tesseraql-pdf`) resolve through the BOM at the CLI's own version, so
   after a CLI upgrade the resolved closure no longer matches the committed lock and
   `tesseraql serve` refuses to start until it does:

   ```sh
   tesseraql modules resolve --app .
   ```

   Commit the rewritten `modules.lock` — the version bump and the lock refresh belong in
   the same change. Third-party coordinates (JDBC drivers) keep their pinned versions.
3. **Lint and test** with the new CLI:

   ```sh
   tesseraql lint --app .
   tesseraql test --app .
   ```

   Pre-1.0 releases may tighten lint rules or change YAML contracts; findings here are the
   changelog entries landing on your app. Fix what they name before going further.

## Database concerns

- **Framework tables migrate themselves.** The framework's own schema (sessions,
  operations, and the other framework-managed tables) is migrated automatically when the
  runtime starts, through Flyway with a per-component history table. The migrations are
  idempotent and take Flyway's lock, so concurrent node startups serialize; there is no
  manual step. This is one reason to roll staging first: the first boot of a new version
  is what applies its framework schema changes.
- **App migrations are unchanged.** Your own `db/migration` scripts run exactly as before
  — `serve` auto-applies them, or run `tesseraql migrate` / the Maven goal explicitly. A
  framework upgrade never rewrites app migrations.
- **Embedded database directories are safe.** A persistent `--embedded-db` directory is
  pinned to the PostgreSQL version that initialized it, so a CLI upgrade that bumps the
  default binary never leaves existing data unopenable. `tesseraql embedded-db info
  ./pgdata` shows where a directory stands and prints the upgrade procedure when one
  applies — see [deployment.md](deployment.md#embedded-database-lifecycle).

## Pre-flight checklist

Before an upgrade reaches production:

1. Read [CHANGELOG.md](../CHANGELOG.md) for every version between the one you run and the
   one you are adopting; note the breaking changes.
2. Upgrade the CLI, bump `tesseraql.version`, and refresh `modules.lock` in one change.
3. `tesseraql lint --app .` and `tesseraql test --app .` pass with the new version.
4. If the app is shared with other teams, `tesseraql admission --app .` still exits 0
   ([admission.md](admission.md)).
5. Roll staging before production, through the ordinary promotion loop
   ([promotion.md](promotion.md)) — `tesseraql release-diff` against the deployed baseline
   shows what the upgrade changes in routes, contract, and migrations.

## Next

- [getting-started.md](getting-started.md) — the install channels and the dev loop.
- [promotion.md](promotion.md) — the dev → staging → prod pipeline the upgrade rides.
- [deployment.md](deployment.md) — container deployment and the embedded database
  lifecycle.
