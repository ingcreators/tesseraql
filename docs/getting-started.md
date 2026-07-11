# Getting started

You build an application *on* TesseraQL in your own repository — a directory of 2-way SQL, YAML
routes, and templates ([app-layout.md](app-layout.md)). You obtain the framework as installed
tooling (the `tesseraql` CLI) and resolved Maven artifacts; you do **not** clone the framework
repository to build an app. (Want to see a finished app before building one? The
[five-minute demo](five-minute-demo.md) boots a seeded gallery app in one command.)

This documentation tracks the current development line. If a documented feature is missing
from your installed CLI, it is newer than that release — check the release notes.

## Prerequisites

| Item | Required? | Notes |
| --- | --- | --- |
| JDK 21+ | For the JVM channels | Not needed on the host if you use the jpackage image (bundled JVM) or a container. |
| TesseraQL CLI | Yes | The only TesseraQL-specific tool. Studio and the pdf/excel codecs ride inside it. |
| A reachable PostgreSQL | Yes — or none, with `--embedded-db` | `docker compose up -d` (the scaffold ships a `compose.yaml`), point `DB_USER`/`DB_PASSWORD` (or `config/application.yml`) at an existing server, or run with an [embedded database](#try-it-without-a-database). |
| Docker | Optional | Convenience database and container image builds. |
| Maven | No | The CLI loop needs none; the Maven path uses the bundled `./mvnw` (JDK only). |
| Node/npm | No | The UI is Hypermedia Components served from a WebJar; no JS build. |

## Install the CLI

- **Distribution archive** — download `tesseraql-cli-<version>-dist.zip` (or `.tar.gz`) from a
  [GitHub release](https://github.com/ingcreators/tesseraql/releases), unpack it, and put its
  `bin/` on your `PATH`. It is a fat jar plus `tesseraql`/`tesseraql.cmd` launchers (JDK 21+ on
  `PATH`).
- **Native image** — the release / CI also builds a jpackage app image per OS (a launcher with a
  bundled JVM; no separate JDK needed).

Verify: `tesseraql --version`.

## Create and run an app

```sh
tesseraql new myapp                  # scaffold into your own repo (config, a migration, routes, tests)
cd myapp
docker compose up -d                 # a local PostgreSQL (or point config at your own)
tesseraql serve --app .              # auto-applies db/migration; Studio at /_tesseraql/studio
tesseraql scaffold crud --app . --table items
```

### First login

Studio and the ops console sign in against the identity store, which is **not seeded** — create
the first administrator once (in a second terminal):

```sh
printf 'change-me' > admin.pw
tesseraql identity-schema --app . --admin-login admin --admin-password-file admin.pw
```

Then open `http://localhost:8080/_tesseraql/studio` and sign in with that login. (With
`--embedded-db` there is no config-declared database — pass the JDBC URL that `serve` prints as
`--jdbc-url` instead of `--app .`.) The full identity surface — roles, policies, SSO — is in
[authentication.md](authentication.md).

### Try it without a database

To run with no external database at all, add `--embedded-db`. The CLI starts an embedded
PostgreSQL and points the app's `main` datasource at it:

```sh
tesseraql serve --app . --embedded-db            # ephemeral: a fresh DB, wiped on exit
tesseraql serve --app . --embedded-db ./pgdata   # persistent: data survives restarts
```

It is a real `postgres`, so everything behaves exactly as it would against a server you run
yourself — only the URL differs. The platform binary is downloaded on first use and cached (so the
first run needs network); pass a directory to graduate the same data to a standalone server later
by setting `tesseraql.datasources.main.jdbcUrl`. Embedded mode is single-process — for multiple app
nodes, point them at a shared external PostgreSQL.

A persistent directory is **pinned to the PostgreSQL version that initialized it**, so a CLI
upgrade never leaves your data unopenable; `tesseraql embedded-db info ./pgdata` shows where a
directory stands and prints the upgrade procedure when one applies. Version pinning and
cross-major upgrades are covered in [deployment.md](deployment.md#embedded-database-lifecycle).

The interactive dev loop is all CLI-native:

```sh
tesseraql lint --app .
tesseraql test --app . --report      # also writes the documentation-portal overlay
tesseraql coverage --app .
tesseraql generate --app .           # OpenAPI, htmx contract, docs spec
tesseraql package --app .            # build a .tqlapp under work/
```

`migrate` (apply/info/validate/repair), `schema`, `governance`, `identity-schema`, and `verify`
round out the surface. Every subcommand calls the same engine as the matching Maven goal.

## The Maven / CI path

`tesseraql new` also scaffolds a thin wrapper `pom.xml` and the Maven Wrapper, so CI needs only a
JDK:

```sh
./mvnw verify                        # lint + governance gate (no database)
./mvnw tesseraql:migrate tesseraql:test \
    -Dtesseraql.jdbcUrl=jdbc:postgresql://localhost:5432/myapp
```

The framework artifacts resolve from GitHub Packages, which **requires authentication even for
public reads**. The scaffolded `pom.xml` declares no repository, so add both the repository and
a personal access token with `read:packages` to your `~/.m2/settings.xml` (in CI, the workflow
`GITHUB_TOKEN` works the same way):

```xml
<settings>
  <activeProfiles><activeProfile>tesseraql</activeProfile></activeProfiles>
  <profiles>
    <profile>
      <id>tesseraql</id>
      <repositories>
        <repository>
          <id>github-tesseraql</id>
          <url>https://maven.pkg.github.com/ingcreators/tesseraql</url>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <servers>
    <server>
      <id>github-tesseraql</id>
      <username>YOUR_GITHUB_USER</username>
      <password>ghp_your_token_with_read_packages</password>
    </server>
  </servers>
</settings>
```

The BOM version-manages the opt-in JDBC drivers (`ojdbc11`, `mssql-jdbc`,
`mysql-connector-j`), so a consumer declares bare coordinates. Behind a proxy or internal
mirror, see [proxy.md](proxy.md).

## Opt-in modules (drivers and codecs)

Base = the PostgreSQL driver + CSV codec. Everything else (Oracle/SQL Server/MySQL drivers, the
pdf/excel/s3 modules) is declared in `tesseraql.modules` and resolved on demand:

```sh
tesseraql modules add io.tesseraql:tesseraql-pdf --app .     # edits tesseraql.yml, writes modules.lock
tesseraql modules add com.oracle.database.jdbc:ojdbc11 --app .
```

`modules.lock` pins the exact resolved closure (committed, reproducible). `serve` resolves the
declared set on start.

## Next

- [app-layout.md](app-layout.md) — the application directory and URL mapping.
- [deployment.md](deployment.md) — container deployment.
- [proxy.md](proxy.md) — corporate proxy / internal mirror / air-gapped networks.
