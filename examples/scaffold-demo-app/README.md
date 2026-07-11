# scaffold-demo

A [TesseraQL](https://github.com/ingcreators/tesseraql) application — SQL-first
hypermedia and integration. You work in this directory (2-way SQL, YAML routes,
templates); the framework is installed tooling and resolved Maven artifacts, so you do
not clone the framework monorepo.

## Prerequisites

- A reachable PostgreSQL (Docker optional). Bring up a local one with
  `docker compose up -d`, or point `DB_USER` / `DB_PASSWORD` (or
  `config/application.yml`) at an existing server.
- **CLI path:** the `tesseraql` CLI. **Maven path:** a JDK (the bundled `./mvnw` fetches
  Maven itself).

## CLI path (interactive dev loop)

```sh
tesseraql serve --app .          # auto-applies db/migration; Studio at /_tesseraql/studio
tesseraql scaffold crud --app . --table items
tesseraql lint --app .
tesseraql test --app .
tesseraql package --app .        # build a .tqlapp under work/
```

## Maven path (CI / lifecycle)

The framework artifacts resolve from GitHub Packages, which requires
authentication even for public reads: add the repository profile and a token
with `read:packages` to your `~/.m2/settings.xml` first — the snippet is in
[getting started](https://github.com/ingcreators/tesseraql/blob/main/docs/getting-started.md#the-maven--ci-path).

```sh
./mvnw verify                    # lint + governance gate (no database)
./mvnw tesseraql:migrate tesseraql:test \
    -Dtesseraql.jdbcUrl=jdbc:postgresql://localhost:5432/scaffold_demo
```

## Layout

See the [application layout](https://github.com/ingcreators/tesseraql/blob/main/docs/app-layout.md):
`config/`, `web/` (the directory tree mirrors the URL space), `db/migration/`,
`templates/`, `tests/`.
