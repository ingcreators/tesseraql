# TesseraQL

TesseraQL is a SQL-first hypermedia and integration framework: applications are plain
directories of 2-way SQL files, declarative YAML routes, and HTML templates, compiled onto the
framework's own runtime. The SQL stays executable in any SQL tool, the YAML stays reviewable, and
the framework supplies the production machinery around them - identity, security, batch,
file transfers, observability, and supply-chain tooling.

## Highlights

- **2-way SQL engine** - bind parameters, conditional blocks, IN expansion, and orderBy
  whitelists live in SQL comments, so every file runs unchanged in plain SQL tools. Rendering
  produces coverage traces and source maps.
- **Declarative routes** - `query-json`, `command-json`, `query-html`, `page`, `query-export`,
  `file-import`, `file-export`, `webhook`, `queue-consume`, and `prompt-text` recipes compile
  YAML route definitions into compiled pipelines — the full surface is the generated
  [YAML reference](docs/reference-yaml-surface.md). No integration DSL in application code.
- **Security by default** - deny-by-default policies (role/permission/claim), JWT bearer and
  session auth, CSRF, field-level authorization, data masking, CSP, and per-app operations
  scopes (`tql.ops.view.<name>`).
- **Identity and federation** - a managed identity schema with SQL-contract realms, an admin
  UI, SAML SP (replay protection, signed redirects, SLO), and SCIM inbound/outbound
  provisioning.
- **Batch and operations** - scheduled jobs with a shared job repository, outbox and
  idempotency stores, asynchronous CSV/Excel file transfers with per-user locale formats, and
  an operations console (dashboard, traces, slow SQL, transfers).
- **Testing and coverage** - declarative test suites with SQL line/branch coverage, route /
  security / assertion / IAM-contract / SAML / SCIM coverage kinds, and JUnit / HTML / JSON /
  SARIF / Cobertura / SonarQube / Allure reports.
- **Four databases** - PostgreSQL, MySQL, Oracle, and SQL Server, with dialect-aware SQL
  resolution, streaming profiles, and per-dialect Flyway migrations.
- **Supply chain** - signed release evidence (Ed25519), CycloneDX SBOMs, signature-verified
  plugins, hash-pinned app packages, and deterministic generated contracts (OpenAPI, htmx).

## Quick start

### Build an application on TesseraQL

You work in your own repository and obtain the framework as the installed `tesseraql` CLI plus
resolved Maven artifacts — no need to clone this monorepo. Full guide:
[docs/getting-started.md](docs/getting-started.md).

```bash
# install the CLI (a release dist archive / jpackage image, or build it: see getting-started.md)
tesseraql new myapp                  # scaffold into your own repo
cd myapp
docker compose up -d                 # a local PostgreSQL (or point config at your own)
tesseraql dev                        # runs the stack; your app at /<name>/, Studio at /_tesseraql/studio
tesseraql scaffold crud --app . --table items
tesseraql lint | test | coverage     # verify, all CLI-native
tesseraql package --app .            # build a .tqlapp
```

### Develop the framework

Requirements: JDK 25+ and Docker (for Testcontainers). The repository ships a Dev Container
with everything preinstalled.

```bash
./mvnw -B -ntp verify
```

Run the bundled example. It needs only an empty PostgreSQL at
`jdbc:postgresql://localhost:5432/user_admin` (see `examples/user-admin-app/config/application.yml`),
or `--embedded-db` for one the CLI starts; the app owns its schema, so `dev` applies its
`db/migration` on start. Install the reactor, then build the CLI distribution and run the example.
The `-Pdist` archive ships `bin/` and `lib/tesseraql.jar` and no opt-in codec: the example's
printable route declares the pdf module under `tesseraql.modules`, and `dev` resolves it from
your local repository — which is why the first line installs rather than packages.

```bash
./mvnw -B -ntp -DskipTests -Pdist install
( cd tesseraql-cli/target && unzip -q tesseraql-cli-*-dist.zip )
tesseraql-cli/target/tesseraql-*/bin/tesseraql dev \
  --stack examples --app-name user-admin
```

The stack serves the application at `/user-admin/`. Its API routes take a bearer token; mint a
development one for the application (signed with its configured HS256 secret, carrying the
`USER_READ` role and the application-use grant) and call the search and the printable list:

```bash
TQL=tesseraql-cli/target/tesseraql-*/bin/tesseraql
TOKEN=$($TQL token --app examples/user-admin-app --role USER_READ)
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/user-admin/api/users?q=sato"
curl -s -H "Authorization: Bearer $TOKEN" -o users.pdf "http://localhost:8080/user-admin/api/users/print"
```

Or build a container image from the official runtime image, with the packaged app unpacked in:

```bash
$TQL modules resolve --app examples/user-admin-app   # the gallery commits no modules.lock
$TQL package --app examples/user-admin-app --out build/user-admin.tqlapp
unzip -q build/user-admin.tqlapp -d build/stack/user-admin
docker build -f deploy/Dockerfile --build-arg BASE=ghcr.io/ingcreators/tesseraql-host:latest \
  --build-arg APP_DIR=build/stack/user-admin --build-arg APP_NAME=user-admin -t user-admin .
```

See [docs/deployment.md](docs/deployment.md) for the Kamal 2 + Cloudflare Tunnel deployment
story and [docs/app-layout.md](docs/app-layout.md) for how an application directory is
organized.

## Documentation

The full documentation is at **[ingcreators.com/tesseraql](https://ingcreators.com/tesseraql)**
— tutorials, guides by use case, the consoles, and generated references. The same pages are
browsable here under [docs/](docs/).

**Start here**

- [What TesseraQL is](docs/overview.md) - the shape of an application, and what it is not
- [Five-minute demo](docs/five-minute-demo.md) - a seeded application running, in one command
- [Getting started](docs/getting-started.md) - install the CLI and scaffold your own
- [Your first app](docs/your-first-app.md) - an empty directory to a tested feature
- [Concepts](docs/concepts.md) - documents, recipes, and how a request travels

**Guides by use case**

- [An approval application](docs/guide-approval-workflow.md)
- [Integration and batch](docs/guide-integration.md)
- [Reporting and analytics](docs/guide-analytics.md)
- [An API over an existing database](docs/guide-existing-database.md)

**The consoles**

- [Studio](docs/studio.md) - the browser IDE
- [Operations console](docs/ops-console.md) - what the running system is doing
- [IAM Admin](docs/iam-admin.md) - users, sessions, delegations

**Reference**

- [YAML surface](docs/reference-yaml-surface.md) · [CLI](docs/reference-cli.md) ·
  [Configuration](docs/reference-config.md) · [Error codes](docs/reference-error-codes.md)

**Evaluating TesseraQL**

- [Admission profile](docs/admission.md) · [Security self-assessment](docs/security-hardening.md)
  · [Threat model](docs/threat-model.md)

**Working on the framework itself**

- [docs/development-environment.md](docs/development-environment.md) - Dev Container details
- [docs/build.md](docs/build.md) - build, test reports, coverage gates, dialect test suites
- [docs/release.md](docs/release.md) - release procedure
- [docs/style-guide.md](docs/style-guide.md) - the house style the documentation is written to
- [docs/roadmap.md](docs/roadmap.md) - post-0.1 roadmap toward an LOB application platform
- [AGENTS.md](AGENTS.md) and [CONTRIBUTING.md](CONTRIBUTING.md) - conventions and rules

## Modules

| Module | Purpose |
| --- | --- |
| `tesseraql-core` | 2-way SQL engine, expression evaluator, file codecs, spool/outbox/telemetry/threading primitives (dependency-free) |
| `tesseraql-yaml` | Route/job model, manifest loader, config, secrets SPI, OpenAPI & htmx contract generators, SBOM / evidence / governance |
| `tesseraql-compiler` | Compiles route definitions into pipelines (recipes, security, telemetry, transfers) |
| `tesseraql-pipeline` | The pipeline contract and its steps: exchange, message, SQL, auth |
| `tesseraql-runtime` | The runtime: HTTP edge, app mounting, migrations, scheduling, ops API, app MCP endpoints. Studio rides `tesseraql-studio-runtime` through the RuntimeExtension SPI |
| `tesseraql-security` | Policy engine, JWT/session auth, CSRF, principal model |
| `tesseraql-identity` | Managed identity schema, Identity SQL Contracts, realm resolution |
| `tesseraql-scim` / `tesseraql-saml` / `tesseraql-oidc` | SCIM provisioning, SAML SP federation, OIDC sign-in — inert until configured |
| `tesseraql-oauth` | The stack's authorization server: token issuance and the grant layer |
| `tesseraql-operations` | Job repository, outbox dispatch, idempotency, file transfers, app installer |
| `tesseraql-observability` | OpenTelemetry integration |
| `tesseraql-test-core` / `tesseraql-coverage-core` / `tesseraql-report` | Declarative tests, coverage kinds, report exporters |
| `tesseraql-studio` / `tesseraql-studio-runtime` / `tesseraql-ops-ui` | Bundled Studio and operations console apps, and the workshop's runtime extension |
| `tesseraql-excel` / `tesseraql-pdf` / `tesseraql-s3` | Opt-in codecs and stores: Excel (fastexcel, jxls), printable PDF, S3-compatible attachment storage |
| `tesseraql-mcp` | Model Context Protocol server core: JSON-RPC dispatch, tool model, stdio and HTTP transports |
| `tesseraql-cli` | The developer command line: every verb is generated into the [CLI reference](docs/reference-cli.md) from the command model the binary parses with |
| `tesseraql-maven-plugin` | `admission`, `coverage`, `generate`, `governance`, `identity-schema`, `lint`, `migrate`, `package-app`, `release-diff`, `release-evidence`, `report`, `schema`, `test`, `verify-evidence` |
| `tesseraql-apptasks` | Shared app-lifecycle tasks — package, migrate, identity bootstrap — so the CLI and the Maven plugin stay thin adapters over one engine |
| `tesseraql-host` | The deployment distribution's entry point and its operator verbs |
| `tesseraql-docs-reference` | Generates the committed reference pages under `docs/`; build-only, never published |
| `tesseraql-bom` | Dependency BOM for applications |

## Java policy

TesseraQL targets Java 25 (`--release 25`), the baseline for 1.x. The framework runs its own
process on every documented deployment, so it picks the JVM rather than fitting into someone
else's — see [docs/jvm-baseline.md](docs/jvm-baseline.md).

## License

Apache License 2.0 - see [LICENSE](LICENSE).
