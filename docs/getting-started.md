# Getting started (without cloning the framework)

You build an application *on* TesseraQL in your own repository — a directory of 2-way SQL, YAML
routes, and templates ([app-layout.md](app-layout.md)). You obtain the framework as installed
tooling (the `tesseraql` CLI) and resolved Maven artifacts; you do **not** clone the framework
monorepo. The wider rationale is in [app-developer-distribution.md](app-developer-distribution.md).

## Prerequisites

| Item | Required? | Notes |
| --- | --- | --- |
| JDK 21+ | For the JVM channels | Not needed on the host if you use the jpackage image (bundled JVM) or a container. |
| TesseraQL CLI | Yes | The only TesseraQL-specific tool. Studio and the pdf/excel codecs ride inside it. |
| A reachable PostgreSQL | Yes | `docker compose up -d` (the scaffold ships a `compose.yaml`), or point `DB_USER`/`DB_PASSWORD` (or `config/application.yml`) at an existing server. |
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
- **From source** (until you adopt a release): `./mvnw -pl tesseraql-cli -am -Pdist -DskipTests
  package` in the monorepo produces the same archive under `tesseraql-cli/target/`.

Verify: `tesseraql --version`.

## Create and run an app

```sh
tesseraql new myapp                  # scaffold into your own repo (config, a migration, routes, tests)
cd myapp
docker compose up -d                 # a local PostgreSQL (or point config at your own)
tesseraql serve --app .              # auto-applies db/migration; Studio at /_tesseraql/studio
tesseraql scaffold crud --app . --table items
```

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

The framework artifacts resolve from a Maven repository (GitHub Packages; add it to your
`~/.m2/settings.xml`). Behind a proxy or internal mirror, see [proxy.md](proxy.md).

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
