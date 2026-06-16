# TesseraQL

TesseraQL is a SQL-first hypermedia and integration framework: applications are plain
directories of 2-way SQL files, declarative YAML routes, and HTML templates, compiled onto an
Apache Camel runtime. The SQL stays executable in any SQL tool, the YAML stays reviewable, and
the framework supplies the production machinery around them - identity, security, batch,
file transfers, observability, and supply-chain tooling.

## Highlights

- **2-way SQL engine** - bind parameters, conditional blocks, IN expansion, and orderBy
  whitelists live in SQL comments, so every file runs unchanged in plain SQL tools. Rendering
  produces coverage traces and source maps.
- **Declarative routes** - `query-json`, `command-json`, `query-html`, `page`, `query-export`,
  `file-import`, and `file-export` recipes compile YAML route definitions into Camel routes.
  No Camel DSL in application code.
- **Security by default** - deny-by-default policies (role/permission/claim), JWT bearer and
  session auth, CSRF, field-level authorization, data masking, CSP, and per-app operations
  scopes (`ops.app.<name>`).
- **Identity and federation** - a managed identity schema with SQL-contract realms, an admin
  UI, SAML SP (replay protection, signed redirects, SLO), and SCIM inbound/outbound
  provisioning.
- **Batch and operations** - scheduled jobs with a shared job repository, outbox and
  idempotency stores, asynchronous CSV/Excel file transfers with per-user locale formats, and
  an operations console (dashboard, traces, slow SQL, transfers).
- **Testing and coverage** - declarative test suites with SQL line/branch coverage, route /
  security / assertion / IAM-contract / SAML / SCIM coverage kinds, a query plan guard, and
  JUnit / HTML / JSON / SARIF / Cobertura / SonarQube / Allure reports.
- **Four databases** - PostgreSQL, MySQL, Oracle, and SQL Server, with dialect-aware SQL
  resolution, streaming profiles, and per-dialect Flyway migrations.
- **Supply chain** - signed release evidence (Ed25519), CycloneDX SBOMs, signature-verified
  plugins, hash-pinned app packages, and deterministic generated contracts (OpenAPI, htmx).

## Quick start

Requirements: JDK 21+ and Docker (for Testcontainers). The repository ships a Dev Container
with everything preinstalled.

```bash
./mvnw -B -ntp verify
```

Run the example application. It needs only an empty PostgreSQL at
`jdbc:postgresql://localhost:5432/user_admin` (see `examples/user-admin-app/config/application.yml`);
the app owns its schema, so `serve` applies its `db/migration` on start — creating the `users`
table and a little demo data. (The Dev Container ships this database as the `db` service; set
`DB_USER`/`DB_PASSWORD` to point at your own.)

The example app's print route exports PDF. `csv` is built into the runtime, but `pdf` and `excel`
are opt-in codec modules (so apps that never print don't drag in PDFBox/POI). `package` assembles
them under `tesseraql-cli/target/modules`; point `serve --modules` at that directory to load them.

```bash
./mvnw -ntp -DskipTests -pl tesseraql-cli -am package
./mvnw -ntp -DskipTests -pl tesseraql-cli dependency:copy-dependencies \
  -DincludeScope=runtime -DoutputDirectory=target/dependency
java -cp 'tesseraql-cli/target/*:tesseraql-cli/target/dependency/*' \
  io.tesseraql.cli.TesseraqlCli serve --app examples/user-admin-app \
  --modules tesseraql-cli/target/modules
```

`GET /api/users` is a `bearer`-authenticated route, so mint a dev JWT (HS256, the
`tesseraql.security.jwt` dev secret, a `USER_READ` role) and call it:

```bash
JWT_SECRET="dev-only-secret-change-me-in-production"
b64url(){ openssl base64 -e -A | tr '+/' '-_' | tr -d '='; }
h=$(printf '%s' '{"alg":"HS256","typ":"JWT"}' | b64url)
p=$(printf '%s' '{"sub":"dev","roles":["USER_READ"]}' | b64url)
s=$(printf '%s' "$h.$p" | openssl dgst -binary -sha256 -hmac "$JWT_SECRET" | b64url)
curl -s -H "Authorization: Bearer $h.$p.$s" "http://localhost:8080/api/users?q=sato"
```

Or build a container image with the app baked in:

```bash
docker build -f deploy/Dockerfile --build-arg APP_HOME=examples/user-admin-app -t user-admin .
```

See [docs/deployment.md](docs/deployment.md) for the Kamal 2 + Cloudflare Tunnel deployment
story and [docs/app-layout.md](docs/app-layout.md) for how an application directory is
organized.

## Modules

| Module | Purpose |
| --- | --- |
| `tesseraql-core` | 2-way SQL engine, expression evaluator, file codecs, spool/outbox/telemetry/threading primitives (dependency-free) |
| `tesseraql-yaml` | Route/job model, manifest loader, config, secrets SPI, OpenAPI & htmx contract generators, SBOM / evidence / governance |
| `tesseraql-compiler` | Compiles route definitions into Camel routes (recipes, security, telemetry, transfers) |
| `tesseraql-camel-components` | `tesseraql-sql`, `tesseraql-auth`, and related Camel components |
| `tesseraql-camel-runtime` | Camel Main runtime: app mounting, migrations, scheduling, ops API, Studio, app MCP endpoints |
| `tesseraql-camel-spring-runtime` | Spring Boot runtime adapter |
| `tesseraql-security` | Policy engine, JWT/session auth, CSRF, principal model |
| `tesseraql-identity` | Managed identity schema, Identity SQL Contracts, realm resolution |
| `tesseraql-scim` / `tesseraql-saml` | SCIM provisioning and SAML SP federation |
| `tesseraql-operations` | Job repository, outbox dispatch, idempotency, file transfers, app installer |
| `tesseraql-observability` | OpenTelemetry integration |
| `tesseraql-test-core` / `tesseraql-coverage-core` / `tesseraql-report` | Declarative tests, coverage kinds, plan guard, report exporters |
| `tesseraql-studio` / `tesseraql-ops-ui` | Bundled Studio and operations console apps |
| `tesseraql-excel` | Optional Excel codec (fastexcel reads/writes, jxls report templates) |
| `tesseraql-mcp` | Model Context Protocol server core: JSON-RPC dispatch, tool model, stdio and HTTP transports |
| `tesseraql-cli` | `tesseraql serve` / `routes` / `new` / `scaffold crud` / `mcp` |
| `tesseraql-maven-plugin` | `lint`, `test`, `coverage`, `generate`, `package-app`, `migrate`, `identity-schema`, `release-evidence`, `verify-evidence`, `governance` |
| `tesseraql-bom` | Dependency BOM for applications |

## Java policy

TesseraQL 1.x uses Java 21 as the baseline and tests Java 25 compatibility in CI
(build target `--release 21`; virtual threads are used on the Java 21 baseline).

## Documentation

- [docs/app-layout.md](docs/app-layout.md) - application directory anatomy and URL mapping
- [docs/build.md](docs/build.md) - build, test reports, coverage gates, dialect test suites
- [docs/deployment.md](docs/deployment.md) - container deployment (Kamal 2 + Cloudflare Tunnel)
- [docs/release.md](docs/release.md) - release procedure
- [docs/development-environment.md](docs/development-environment.md) - Dev Container details
- [docs/roadmap.md](docs/roadmap.md) - post-0.1 roadmap toward an LOB application platform

## License

Apache License 2.0 - see [LICENSE](LICENSE).
