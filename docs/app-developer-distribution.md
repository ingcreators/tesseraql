# Distribution and onboarding

How a developer obtains TesseraQL and builds an application on it without cloning the
framework repository. Everything below is available today except the items called out
inline as planned — Maven Central + signing (GitHub Packages ships now) and a Gradle
plugin.

## Goal

A developer who *builds an application on* TesseraQL should never need to clone the framework
monorepo. They work in their own repository — a directory of 2-way SQL, YAML routes, and
templates per [app-layout.md](app-layout.md) — and obtain the framework as installed tooling
and resolved Maven artifacts. Cloning the monorepo stays a *framework-developer* activity.

The design already supports this (the CLI scaffolds and serves external app homes, the BOM
exists, the Maven plugin operates on an external app home, Studio is a bundled app). The
missing layer is **distribution**: nothing is published to a Maven repository, the CLI has no
packaged distribution, and the scaffold emits no build files. Everything below closes that
layer.

## Non-goals

- Gradle plugin (planned, not currently supported).
- Changing the app-layout contract or the runtime.
- Replacing the Maven plugin. It stays as the CI / lifecycle-integration surface.

## Target workflow (end state)

```text
install the tesseraql CLI            # one of the distribution channels below
tesseraql new myapp                  # scaffold into the developer's own repo
cd myapp                             # point config at a PostgreSQL (Docker optional)
tesseraql serve --app .              # auto-applies db/migration; Studio at /_tesseraql/studio/ui
tesseraql scaffold crud --table ...  # iterate
tesseraql lint | test | coverage     # verify, all CLI-native
tesseraql package                    # build a .tqlapp
# deploy per deployment.md (container, plain JVM, or .tqlapp)
```

Prerequisites the developer installs once:

| Item | Required? | Notes |
| --- | --- | --- |
| JDK 21+ | Yes (JVM channels) | Not needed on host if the CLI ships as a native/jpackage image or container. |
| TesseraQL CLI | Yes | The only TesseraQL-specific tool. Studio and the pdf/excel codecs ride inside it. |
| A reachable PostgreSQL | Yes | Docker is a convenience, not a requirement — a natively installed PostgreSQL works; point `DB_HOST`/`DB_USER`/`DB_PASSWORD` (or `config/application.yml`) at it. |
| Docker | Optional | Convenience DB, Testcontainers (framework dev only), container image builds. |
| Maven | No | The CLI loop needs none. The CI/Maven path bootstraps via the bundled `mvnw` (JDK only). |
| Node/npm | No | UI is Hypermedia Components served from a WebJar; no JS build. |

## Two surfaces, one engine

| Surface | Role | How it is obtained |
| --- | --- | --- |
| **CLI** (`tesseraql ...`) | Interactive dev loop | Installed (distribution channel below). |
| **Maven plugin** (`tesseraql:...`) | CI / lifecycle integration | Resolved from a Maven repo by a thin wrapper `pom.xml`; no separate install (uses `mvnw`). |

Both call the same engine library classes, so behavior is identical. A developer picks the
surface by context; neither is more capable than the other once command parity (below) lands.

## Work item 1 — Publish artifacts to a Maven repository

- Add `distributionManagement` and a publish job. **GitHub Packages first** (covers the
  internal audience immediately), **Maven Central later** (fills the `release.md` "Publishing
  to Maven Central (later)" section, with signing).
- Published set: `tesseraql-bom`, `tesseraql-maven-plugin`, the runtime
  (`tesseraql-camel-runtime`, `-spring-runtime`), `tesseraql-studio`, and every module an app
  resolves, **including the opt-in `tesseraql-pdf` / `tesseraql-excel` / `tesseraql-s3`**.
- The BOM version-manages the opt-in codecs/connectors **and** the opt-in JDBC drivers
  (`ojdbc11`, `mssql-jdbc`, `mysql-connector-j`) so a consumer specifies only coordinates.

## Work item 2 — CLI command parity

Every Maven goal whose engine is a reusable library is exposed as a CLI subcommand. Today the
CLI has only `new`, `scaffold`, `serve`, `routes`, `mcp`; the build/verify/package goals are
Maven-only as terminal commands (though `tesseraql mcp` already drives `lint`/`test`/`coverage`
through the same engine).

| Capability | New CLI command | Engine (already CLI-reachable unless noted) |
| --- | --- | --- |
| lint | `tesseraql lint` | `yaml.lint.AppLinter` |
| test | `tesseraql test` | `report.AppTestRunner` |
| coverage | `tesseraql coverage` | `report.AppTestRunner` + `coverage.CoverageGate` |
| reports | `tesseraql test --report` | `report.docs.ReportGenerator` / `ReportHistory` (JSON overlay; a `<fmt>` selector is reserved, the engine is JSON-only today) |
| generate (OpenAPI/htmx/docs) | `tesseraql generate` | `yaml.openapi.*`, `report.docs.AppDocGenerator` |
| schema (schema.json sidecar) | `tesseraql schema` | `report.docs.SchemaGenerator` + `yaml.scaffold.CatalogSchema` |
| governance check | `tesseraql governance` | `yaml.governance.GovernanceGate` |
| migrate (apply/info/validate/repair) | `tesseraql migrate [op]` | `apptasks.AppMigrator` (extracted) |
| identity-schema | `tesseraql identity-schema` | `apptasks.IdentityBootstrap` (extracted) |
| package-app | `tesseraql package` | `apptasks.AppPackager` (extracted) |
| verify evidence (consumer) | `tesseraql verify` | `yaml.release.ReleaseEvidenceVerifier` |

All of the above subcommands are implemented (Suggested sequencing step 3). Each is a thin picocli
adapter calling the same engine class as the matching mojo / `mcp` dev-tool, with a shared
`CliDatasource` mixin centralizing `--jdbc-url/--username/--password` and the
`tesseraql.datasources.main.*` config fallback. CLI defaults are dev-friendly (generated artifacts
and the `.tqlapp` under `work/`); the engines are identical so behavior matches the goals.

**Kept Maven/CI-only: `release-evidence`.** It signs Ed25519 evidence, emits the SBOM, and
assumes a deterministic release build — a producer/pipeline operation that does not belong in a
dev CLI. Its consumer-side counterpart (`verify`) *is* exposed (read-only, no keys).

**Structural change — shared app-tasks library (done, step 2).** Three helpers used to live inside
`tesseraql-maven-plugin` and now live in the new leaf module **`tesseraql-apptasks`** (package
`io.tesseraql.apptasks`), so the mojo and the CLI are both thin adapters over one implementation,
with no duplicated wiring or drift:

- `AppPackager` (package-app)
- `AppMigrator` (migrate)
- `IdentityBootstrap` (identity-schema)

`tesseraql-apptasks` depends only on `tesseraql-core`/`-identity`/`-security` + Flyway (no
Camel/Spring/CLI/Maven), and is version-managed by the BOM. `AppMigrator` gained `info`,
`validate` and `repair` alongside `migrate` (each over a shared Flyway `configure(...)` helper);
the `migrate` mojo gained a `tesseraql.migrate.operation` parameter so the Maven and CLI surfaces
expose the same four operations.

`migrate` is worth exposing despite `serve` auto-migrating on start: it covers non-serving
apply for CI, a single pre-roll apply step in production (so replicas do not race), and
`info`/`validate`/`repair`.

## Work item 3 — CLI distribution channel

Ship the CLI as a **jpackage / jlink image (a JVM inside a platform launcher)**, not a GraalVM
native-image. Rationale: TesseraQL's value model is runtime-pluggable codecs, drivers, and
connectors discovered via `ServiceLoader` over a dynamic classpath (`--modules` child
classloader; signed `plugins/`). A true AOT native image is closed-world and cannot load
external jars at runtime, which breaks `--modules`, the SPI discovery, and `plugins/`, and
forces every driver/codec to be compiled in at build time (heavy reflection for
PDFBox/POI/AWS/Oracle, losing the opt-in design). jpackage keeps dynamic loading and still
removes the separate-JDK install.

Audience is "both internal and public", so phase it:

- **Now (internal):** publish to GitHub Packages; distribute the CLI as a container image / Dev
  Container feature (host needs only Docker) and/or a jpackage image.
- **Now (public):** package managers on top of the release assets — a Homebrew tap
  ([`ingcreators/homebrew-tap`](https://github.com/ingcreators/homebrew-tap): the portable jar
  dist on Homebrew's OpenJDK, covering macOS Intel/ARM and Linux; the macOS app image is
  arm64-only) and a Scoop bucket
  ([`ingcreators/scoop-bucket`](https://github.com/ingcreators/scoop-bucket): the Windows app
  image with its bundled JRE). `release.yml` bumps both on each release once the
  `DIST_REPOS_TOKEN` secret (contents:write on the two repos) is configured.
- **Later (public):** Maven Central.

Studio and the pdf/excel codecs are **bundled inside** the runtime/CLI; they add no separate
distribution channel. In a Docker-less environment the container channel is unavailable, so use
the jpackage image (or a fat jar + launcher).

## Work item 4 — Opt-in modules (drivers, codecs, connectors)

Drivers and the pdf/excel/s3 modules all use the same model: a `ServiceLoader` SPI discovered
on the classpath (`java.sql.Driver`, `FileCodec`, `BlobStoreProvider`, and — for
[custom expression functions](declarative-validation.md#custom-functions) —
`ExpressionFunction`). Base = PostgreSQL driver + CSV codec; everything else is opt-in,
injected one of two ways:

- **Maven / wrapper-pom:** declare the dependency (BOM-managed version); it lands on the
  classpath and the SPI finds it. No `--modules` needed.
- **Standalone CLI:** an embedded Maven artifact resolver (`maven-resolver`/Aether or coursier
  — no Maven install) resolves coordinates into a module cache that the existing `--modules`
  child classloader consumes.

The CLI module set is **declarative and reproducible**:

```yaml
# tesseraql.yml — source of truth, reviewed and committed
tesseraql:
  modules:
    - com.oracle.database.jdbc:ojdbc11   # version from the BOM
    - io.tesseraql:tesseraql-pdf
```

- `modules.lock` pins exact resolved versions + checksums (reproducible, offline, supply-chain).
  Committed.
- `tesseraql modules add <coord>` is an ergonomic helper that **edits `tesseraql.yml` and
  refreshes the lock** (like `cargo add`), not a separate imperative cache mutation.
- `serve` resolves the declared set on start; the resolver verifies repository checksums.
- This stays distinct from signed third-party `plugins/` (Ed25519, isolated classloader).

### JDBC driver licensing policy

Bundling a driver into a distributed image is redistribution, so the policy differs per driver
(not legal advice; confirm license text on each version bump):

| Driver | License | Policy |
| --- | --- | --- |
| PostgreSQL | BSD-2-Clause | Bundled in the base. |
| SQL Server (`mssql-jdbc`) | MIT | Safe to bundle if needed. |
| MySQL (`mysql-connector-j`) | GPLv2 + Universal FOSS Exception | **Opt-in, user-supplied.** A downstream proprietary app may fall outside the FOSS Exception. MariaDB Connector/J (LGPL-2.1) is an alternative worth offering. |
| Oracle (`ojdbc11`) | Oracle Free Use Terms (OFUTC) | **Opt-in, user-supplied.** Proprietary terms; cannot be relicensed under Apache-2.0. |

Letting the CLI resolver *fetch* MySQL/Oracle at the user's explicit request keeps the framework
out of the redistribution path — the user pulls from the vendor repo under the vendor's terms.

## Work item 5 — Scaffold updates (`tesseraql new`)

The skeleton currently emits app files only. Add, for the CI/Maven path and a frictionless
first run:

- A thin wrapper `pom.xml` (imports the BOM, binds the `tesseraql-maven-plugin`) **and** the
  Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/`) so the Maven surface needs only a JDK.
- A `compose.yaml` for a local PostgreSQL (Docker optional; native PostgreSQL documented).
- `tesseraql.studio.enabled` profile defaults in `tesseraql.yml` (`local` on; production off or
  `readOnly`, since Studio is a privileged `/_tesseraql/` surface).
- A generated `README` describing both the CLI-only and Maven paths.

## Work item 6 — Docs and quickstart

- Rewrite the README quick start from "build the monorepo, `java -cp ...`" to "install the CLI,
  `tesseraql new`".
- Add a "getting started without cloning" guide.
- Link this plan from the README documentation index.

---

## Proxy and restricted-network support (cross-cutting)

Corporate environments route outbound HTTP(S) through a proxy (often authenticated, often
TLS-intercepting) and/or replace direct Maven Central access with an internal mirror. The
operational guide is [proxy.md](proxy.md); this section records the design and what is implemented.

### Current state (implemented)

- The embedded resolver reads `~/.m2/settings.xml` (`<proxies>`/`<mirrors>`/`<servers>`).
- The CLI bridges `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` to the JVM proxy system properties at
  startup (never overwriting an explicit property) — see `ProxyEnvironment`.
- The previously proxy-blind runtime clients in `tesseraql-oidc/OidcHttp`,
  `tesseraql-operations/http/HttpCallClient`, and `tesseraql-camel-runtime/WebhookNotifier` now
  build `HttpClient` with `.proxy(ProxySelector.getDefault())`, so they honor the JVM proxy
  configuration (and the env bridge).
- S3 (AWS SDK `UrlConnectionHttpClient`) honors `https.proxyHost` system properties.
- The Maven Wrapper download honors `MVNW_REPOURL` for an internal mirror.

Still open: a single first-class proxy configuration object honored uniformly (with proxy
authentication via an `Authenticator` on every runtime client) is a later refinement; today proxy
auth flows through `settings.xml` (resolution) and the `*.proxyUser`/`Password` properties.

### Build / resolution-time outbound

`mvnw`, the embedded CLI resolver, and the published-artifact fetch must all be proxy-aware:

- **Reuse `~/.m2/settings.xml`** (`<proxies>`, `<mirrors>`, `<servers>` credentials) — the
  least-friction path; enterprises already have it, and the embedded resolver can read it or
  feed its `ProxySelector` from it.
- Also honor JVM system properties and **bridge `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY`
  env vars** (the container/CI standard the JVM does not read by default). Document precedence:
  explicit flag > `settings.xml` > JVM props > env.
- Allow a **repository / mirror URL override** (internal Nexus/Artifactory) via `settings.xml`
  `<mirrors>` or `tesseraql.yml`/flag. This is often the real enterprise answer and also covers
  air-gapped sites.
- Point `mvnw`'s `distributionUrl` / `MVNW_REPOURL` at the internal mirror.

### Runtime outbound

- Introduce a **single proxy configuration** (host/port/nonProxyHosts/credentials) honored by
  **all** outbound clients, and **fix the `.proxy(...)` omission** in `OidcHttp`,
  `HttpCallClient`, and `WebhookNotifier` (pass a `ProxySelector`); wire S3's
  `ProxyConfiguration` consistently.
- **Resolve modules at build/CI time and bake `modules.lock` + the cache into the image**, so a
  production `serve` performs no module-resolution outbound and the proxy concern collapses to
  build time.

### TLS-intercepting proxy

A proxy that intercepts TLS presents a corporate root CA; the JVM must trust it (add to
`cacerts`, or `-Djavax.net.ssl.trustStore`). Independent of proxy-host config; document it for
all outbound paths.

### Air-gapped / offline

`modules.lock` + a pre-seeded cache (or internal mirror) makes resolution reproducible offline
after the first fetch.

### Near-term standalone ticket

Fix the `HttpClient` proxy omission (`OidcHttp` / `HttpCallClient` / `WebhookNotifier`)
independently of the rest of this plan — it is a correctness bug for any proxied deployment
today.

---

## Open decisions

- ~~Final CLI distribution channel mix (jpackage vs container vs fat jar).~~ **Resolved: they
  coexist** — jpackage app images and the fat-jar dist ship on every release, the demo container
  image covers Docker-centric hosts, and the Homebrew tap / Scoop bucket sit on top as the
  polished installers.
- Maven Central timing and signing setup.
- `tesseraql.modules` key name and `modules.lock` format.
- ~~Whether `identity-schema` folds into `migrate` or stays a separate command.~~ **Resolved:
  stays a separate command** (`tesseraql identity-schema`) — admin seeding and the file/env-only
  password handling are distinct concerns from schema migration, and it maps 1:1 to the mojo.

## Suggested sequencing

1. ✅ **Done** — Publish (BOM + plugin + runtime + studio + codecs) to GitHub Packages:
   `distributionManagement` (the `github` repo) plus a `deploy` step in the release workflow; the
   BOM version-manages the opt-in drivers (`ojdbc11`, `mssql-jdbc`, `mysql-connector-j`). Maven
   Central + signing stay a later step.
2. ✅ **Done** — Extract the shared app-tasks library (`AppPackager`, `AppMigrator`,
   `IdentityBootstrap`) into `tesseraql-apptasks`.
3. ✅ **Done** — Add the CLI subcommands (work item 2).
4. ✅ **Done** — Embedded resolver + `tesseraql.modules` + `modules.lock` + `tesseraql modules add`.
5. ✅ **Done** — Proxy cross-cutting: the resolver reads `~/.m2/settings.xml`
   (`<proxies>`/`<mirrors>`); the CLI bridges `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` to JVM proxy
   properties; the `HttpClient` proxy omission is fixed (`ProxySelector.getDefault()`); mirror
   override via `settings.xml`/`MVNW_REPOURL`; CA-truststore guidance in
   [proxy.md](proxy.md).
6. ✅ **Done** — CLI distribution. Fat jar + launchers (`-Pdist`: a shaded executable jar with
   `bin/tesseraql`(`.cmd`) launchers and the opt-in codec cache, zipped/tarred, attached to the
   GitHub release; a CI `dist` job smoke-tests it) **and** jpackage app images (a platform launcher
   with a bundled JVM) built per OS (Linux/macOS/Windows) by `.github/workflows/jpackage.yml`,
   smoke-tested on each, and uploaded as artifacts on release/dispatch. jpackage (not native-image)
   keeps the dynamic classpath the runtime needs.
7. ✅ **Done** — Scaffold updates (wrapper pom + `mvnw`, `compose.yaml`, Studio profile defaults).
8. ✅ **Done** — Docs / README rewrite (CLI-first quick start + `getting-started.md`).
