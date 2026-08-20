# The module channel

Status: designed 2026-08-20. What a TesseraQL runtime carries on its own classpath, what reaches
it through the module channel instead, and how everything in the second group travels — into a
`.tqlapp`, onto a Windows host image, and onto a machine with no outbound network.

[runtime-footprint.md](runtime-footprint.md) decided what a deployment carries: the host, not the
workshop. This document asks the question that follows from it. Of the things a deployment does
*not* carry, which ones should it carry after all, and for the rest — which are the majority — is
there one way for them to arrive, or one way per artifact? Today it is one way per artifact, and
three of those ways do not work.

## What reaches a runtime today, and by which channel

Measured 2026-08-19 and 2026-08-20 on 0.15.0-SNAPSHOT: `dependency:list` at runtime scope, jar
sizes from the local repository, incremental sizes counted against the runtime closure so a shared
dependency is never counted twice.

| Channel | What travels on it | Where it ends up |
| --- | --- | --- |
| The runtime classpath | `tesseraql-camel-runtime`'s 195 artifacts: core, yaml, compiler, camel components, security, identity, oauth, operations, observability, ops-ui, mcp, every Flyway dialect adapter, the PostgreSQL driver, angus-mail, camel-ftp, zxing, the webjars | the deployment image |
| The module channel (`tesseraql.modules` → `work/modules`) | `tesseraql-pdf`, `tesseraql-excel`, `tesseraql-s3`, `duckdb_jdbc`, the three non-PostgreSQL JDBC drivers, an application's own `ExpressionFunction` / `FileCodec` / `BlobStoreProvider` jars | resolved per application |
| The base classpath, by hand | the framework database's JDBC driver, and anything else a stack-scoped pool needs | an operator copies a jar |
| The workshop | studio, studio-runtime, test-core, report, coverage-core, docs-reference, maven-plugin | never deployed ([runtime-footprint.md](runtime-footprint.md) decision 1) |

The candidates a reader asks about first, measured:

| Candidate | Jars beyond the runtime closure | Size |
| --- | --- | --- |
| `tesseraql-oidc` + `tesseraql-saml` + `tesseraql-scim` | 3 | **168 KB** |
| `tesseraql-pdf` + `tesseraql-excel` | 26 | 24 MB |
| `tesseraql-s3` | 37 | 11.0 MB |
| `duckdb_jdbc` | 1 | 81.5 MB |
| `ojdbc11` / `mysql-connector-j` / `mssql-jdbc` | 3 | 7.4 / 2.5 / 1.5 MB |

The developer CLI's own closure is 258 jars and 57.6 MB after the zonky exclusions; it was
118.2 MB before them.

## Problem 1 — a `.tqlapp` does not carry its modules, and three documents say it does

`AppPackager.pack` walks the application home and excludes two trees: the work home, and the
reserved `.tesseraql/` namespace. The module cache lives at `work/modules`. So an application that
declares `tesseraql.modules` packages successfully and ships an archive with none of them in it.

The runtime is not fooled. `MultiAppHost` refuses to start an application whose declared modules
are absent (`TQL-APP-4216`) or disagree with `modules.lock` (`TQL-APP-4217`) — the right behavior,
and it turns the packaging gap into a deployment-time refusal rather than a missing codec at the
first export.

The remedy that refusal names, `tesseraql modules resolve --stack`, needs an artifact resolver and
a repository on the deployment machine. [runtime-footprint.md](runtime-footprint.md) decision 1
deliberately removed the resolver from the deployment distribution: *a deployment never resolves
artifacts, because its module caches were resolved and lock-verified before it was deployed.* The
sentence describes the intended design. Nothing implements the clause after the comma.

Three documents assert the behavior that is missing:

- [runtime-footprint.md](runtime-footprint.md) line 48 — "a `.tqlapp` ships the cache it was
  verified with".
- [hosting.md](hosting.md) — "the operator runs the resolve once per install", with the command
  shown against the install root. That command belongs to the developer CLI; the `tesseraql-host`
  distribution has no resolver in it.
- [app-developer-distribution.md](app-developer-distribution.md) work item 4 — the module set is
  "declarative and reproducible", with nothing said about how the resolved closure crosses to the
  machine that runs it.

## Problem 2 — the developer distribution ships a codec bag nothing points at

The CLI dist archive contains a `modules/` directory holding the pdf and excel closure, 24 MB,
assembled by the `assemble-format-modules` execution and placed by `src/assembly/dist.xml`.

Nothing reads it by default. The launcher script never passes `--modules`, and the normal
developer path does not involve that directory at all: `CliModules.moduleCache` resolves the
application's declared `tesseraql.modules` through the embedded resolver into that application's
own `work/modules`. The bag is reachable only by a developer who types `--modules` with the
unpacked distribution's path — a gesture no document, test, or CI job mentions.

It is also asymmetric with production in the one direction that hurts: it would give development a
codec the deployment does not have. It does not do so today only because nothing wires it up. The
right fix is not to wire it up. An application that uses PDF says so in `tesseraql.modules`, and
that declaration is what `package` must honor (decision 3), what the host verifies, and what an
audit reads.

## Problem 3 — everything a distribution does not carry travels by a different road

Four kinds of artifact are resolved rather than bundled, and each has its own answer for how it
reaches a machine with no network:

| Artifact | How it is fetched | Offline story today |
| --- | --- | --- |
| An application's declared modules | `ModulesInstaller` → `work/modules`, pinned by `modules.lock` | `modules resolve --offline` against a pre-seeded local repository ([proxy.md](proxy.md)) |
| The framework database driver (Oracle, SQL Server, MySQL) | the operator adds a jar to the image or `lib/` | prose in [hosting.md](hosting.md), and nothing at all for the developer CLI (problem 5) |
| The embedded PostgreSQL binary | `EmbeddedPostgresSupport.resolveBinaryJar`, per platform, on demand | **none** — the first `dev --embedded-db` on an air-gapped machine fails |
| A first-party extension jar (a `SecretResolver`, an `ExpressionFunction` library) | `--modules`, or the module channel once published | as for modules, when it has coordinates |

Every one of them is a Maven coordinate, resolved by the same embedded resolver, honoring the same
`~/.m2/settings.xml`, with the same `workOffline` switch. They differ only in who asks for them
and when. There is no reason for four roads.

## Problem 4 — the S3 module carries two HTTP stacks against a written intention

[tesseraql-s3/pom.xml](../tesseraql-s3/pom.xml) selects the JDK-based synchronous client and says
why: *A JDK-based synchronous HTTP client, so the SDK needs no Apache/Netty stack.* The
`software.amazon.awssdk:s3` artifact depends on `apache-client` and `netty-nio-client`
transitively, and nothing excludes them, so both ride along:

| Family | Jars | Size |
| --- | --- | --- |
| AWS SDK proper | 31 | 8.37 MB |
| Apache HttpClient 5 | 3 | 2.17 MB |
| Netty NIO client | 2 | 0.43 MB |

This is the same shape of defect as the bundled database binaries: a pom comment stating an
intention the dependency tree does not honor, unnoticed because nothing measured it.

## Problem 5 — a stack framework database that is not PostgreSQL has no route on the developer CLI

Stack-scoped pools do not go through the module channel, by design:
[module-scope.md](module-scope.md) structural decision 3 binds drivers at the pool *from the
application's loader* for application pools, and states the boundary — "the framework datasource
is stack infrastructure, and its driver ships with the deployment, not with any member".

The code follows it exactly. When `tesseraql-stack.yml` supplies a framework coordinate,
`MultiAppHost` builds the pool with `DataSources.create`, which sets only a JDBC URL on a
`HikariConfig`; HikariCP then asks `DriverManager`, which searches the **base classpath**. The
migration pool takes the same path. (When the stack file supplies no coordinate, the framework
datasource is the application's own, built by `createAll` from the application's loader — that
case is served by the module channel and works today.)

`dev` composes nothing onto the process classpath, deliberately: *Nothing is composed onto the
process: each runtime builds its own loader over what this resolve left on disk.* `--modules`
rides as a per-runtime override, not a base-classpath addition. And the launcher script runs
`java -jar`, which ignores `-cp`.

So a stack whose framework database is SQL Server, Oracle, or MySQL cannot be started by `tesseraql
dev` at all, and the failure surfaces as `No suitable driver` from the JDBC layer rather than as
anything TesseraQL says. The container image has an answer (`lib/`, read by `-cp 'lib/*'`); the
Windows host image and the developer CLI have none.

## Decisions

### 1. The line: first-party and weightless joins the classpath; everything else is a module

> **A module earns the runtime classpath when it is first-party, adds no third-party artifact to
> the closure, and is inert until configuration activates it. Everything else stays on the module
> channel — and the friction of being on that channel is removed by bundling (decision 3) and by
> the bag (decision 5), not by moving the artifact onto the classpath.**

The full inventory against that rule, 2026-08-20:

| Verdict | Modules | Why |
| --- | --- | --- |
| Already on the classpath | the 195 | mail, file transfer, QR, the authorization server, and the application MCP surface are framework features, not opt-ins |
| **Promoted** | `oidc`, `saml`, `scim` | 168 KB, no third-party artifact, config-gated (decision 2) |
| Stays a module | `pdf` + `excel` (24 MB, LGPL engine, format parsers with a standing CVE cadence), `s3` (37 third-party jars), `duckdb_jdbc` (81.5 MB of platform natives), the three JDBC drivers (licence) | the closure is third-party, and its audit burden should fall on the applications that opted in |
| Never deployed | the workshop | [runtime-footprint.md](runtime-footprint.md) decision 1 |

The inventory is complete: no module other than those three satisfies the rule.

### 2. `oidc`, `saml` and `scim` join the runtime classpath

They become compile dependencies of `tesseraql-camel-runtime`, so the developer CLI, the
deployment distribution, and the host image all carry them.

Verified before deciding: none of the three depends on `tesseraql-camel-runtime` (their poms carry
`tesseraql-core`, `-compiler`, `-identity`, `-security`, and `jackson-databind`, which the runtime
closure already contains), so the promotion introduces no dependency cycle and no third-party
artifact.

Nothing about activation changes. Each is a `RuntimeExtension` whose `enabled(AppConfig)` returns
false unless its own configuration key is set, and `RuntimeExtensions.discover` applies
`tesseraql.plugins.allowlist` to classpath providers as well as plugin providers — so a deployment
that locks the extension set down keeps that lock. Presence on the classpath activates nothing;
configuration does — the same posture [studio-shell.md](studio-shell.md) chose when it made Studio
a topology decision rather than a packaging one.

What this buys: corporate SSO on a Windows Server deployment becomes unzip plus configuration,
with no jar to add — and the base-classpath extension story (decision 6) narrows to database
drivers and an application's own extension jars.

Documentation follows. [extending.md](extending.md) and [module-scope.md](module-scope.md) both
describe these three as opt-in jars, and both stop doing so.

### 3. `package` resolves from the lock and bundles the closure it verified

`AppPackager` gains a step after the source scan: the resolved module cache is written into the
archive under `.tesseraql/modules/`, the reserved namespace that already exists for build outputs
and is never populated from the source tree.

**`package` resolves from `modules.lock` rather than requiring a prior command.** The lock pins
exact coordinates and checksums, so resolving at pack time is deterministic — what would not be
deterministic is packaging with no lock at all. Hence:

- Modules declared, no `modules.lock` → **`TQL-APP-4218`**, naming the application and
  `tesseraql modules resolve`. Writing the lock is the reviewable human act; everything after it
  reproduces.
- The resolved closure disagrees with the lock → **`TQL-APP-4219`**, the pack-time twin of the
  host's `TQL-APP-4217`.

This is what keeps binaries out of an application's git repository: the repository holds the
declaration and the lock, `work/` is ignored, and the jars exist only in a build's working tree
and inside the artifact. An offline build resolves from the bag (decision 5) with
`--offline --repo`.

`AppModules.load` gains one branch, with the precedence stated so a stale directory cannot
shadow: **when `.tesseraql/modules/` exists it is the application's module set, and `work/modules`
is not consulted**; a source tree without it reads `work/modules` as before. The two are never
composed. The `--modules` development override composes with either, unchanged, and
per-application isolation ([module-scope.md](module-scope.md) structural decision 2) is untouched
— the bundled directory belongs to one application, exactly as the cache did.

`PackageAppMojo` applies the same rule through Maven's own repository system, so the Maven route
produces an archive with the same contents as the CLI route.

Two consequences to record rather than discover:

- **Reproducibility now rests on the lock.** `AppPackager`'s comment says `.tesseraql/` is never
  populated from the source tree, so run-dependent overlays cannot leak into a reproducible
  archive. Module jars come from a run-dependent tree, and what makes them reproducible is the
  lock plus `TQL-APP-4219`. The comment says that after this slice.
- **The two ways in still differ.** An application that declares its codecs as ordinary Maven
  dependencies of a wrapper pom (["Maven / wrapper-pom"](app-developer-distribution.md)) declares
  nothing in `tesseraql.modules`, so its `.tqlapp` carries nothing and the host has no declaration
  to refuse on — the gap surfaces later as `TQL-LD-2801` at the first export. That route targets
  an application's own runtime build, not a shared host, and this document says so; a lint warns
  when a route uses a format whose codec is neither on the build classpath nor in
  `tesseraql.modules`.

[runtime-footprint.md](runtime-footprint.md) line 48 becomes true rather than aspirational, and
the correction is part of this slice.

### 4. The developer distribution stops shipping the pdf/excel bag

The `assemble-format-modules` execution, the two `provided` dependencies that feed it, and the
`modules/` fileSet in `src/assembly/dist.xml` are removed. The dist archive loses 24 MB and gains
nothing to explain. Verified before deciding: no CLI test, test fixture, document, or CI job
references either the directory or those dependencies.

`--modules` itself stays: pointing the CLI at a directory of local jars is how an extension is
developed before it has coordinates ([extending.md](extending.md)).

### 5. One bag for everything a distribution does not carry

A new command collects, on a connected machine, every artifact a stack will need on a disconnected
one:

```sh
# connected
tesseraql modules fetch --stack <install-root-or-folder> --into <bag> \
    --platform linux-amd64,windows-amd64

# disconnected
tesseraql modules resolve --stack <dir> --offline --repo <bag>
tesseraql package --app <dir> --offline --repo <bag>
tesseraql dev --embedded-db --repo <bag>
```

**The bag is a repository, not a classpath.** Its layout is a partial local Maven repository
(`group/artifact/version`), so the consuming side is the existing offline path with its local
repository pointed elsewhere — no new resolution code, no new loading semantics, and
`modules.lock` still decides what is correct. A shared directory of jars would have been simpler
to produce and would have undone [module-scope.md](module-scope.md) structural decision 2: one
runtime, one module set, no visibility into a neighbor's codecs.

**The bag is built by resolving into it, never by copying jars into a directory tree.** `fetch`
points the resolver's local repository at the bag and resolves, so the result is a repository
Maven itself produced — with poms, metadata and the `_remote.repositories` markers an offline
resolve checks. A hand-assembled jar tree fails offline resolution in ways that look like
corruption. For the same reason the bag necessarily contains **`io.tesseraql:tesseraql-bom`**:
`ModulesInstaller` resolves unversioned coordinates through it, so a bag without the BOM fails on
its first offline use.

**What `fetch` collects is decided by declarations, not by flags.** The scope rule:

| Artifact | Declared by | Fetch scope |
| --- | --- | --- |
| An application's modules | that application's `tesseraql.modules` + `modules.lock` | **application** (`--stack` walks every member's) |
| The stack's base-classpath artifacts | `tesseraql-stack.yml` (decision 6) | **stack** |
| The embedded PostgreSQL binary | nothing declares it — the target machine's platform does | **machine**, hence `--platform` |

So `--app` can never supply a base-classpath artifact: no application declares one. `--platform`
stays an explicit flag because it describes the machine being prepared, not the software being
prepared for it, and it accepts the binary version alongside the classifier — a persistent data
directory pins its own version, and fetching only the CLI's default would miss it.

`fetch` writes a `bag.json` manifest recording what was collected, from which locks and
declarations, with a SHA-256 per artifact.

**The host still never resolves.** For a deployment the bag is *placed*, not consulted: an
operator copies stack-scoped jars into the extension directory (decision 6), and an application's
modules ride inside its `.tqlapp` (decision 3). The bag serves the developer machine, the CI
runner that builds a `.tqlapp` offline, and the operator preparing an image — never a running
host. That boundary is what keeps [runtime-footprint.md](runtime-footprint.md) decision 1 intact.

### 6. The stack declares what its base classpath needs, and every distribution extends it the same way

`tesseraql-stack.yml` gains a declaration beside the coordinate it belongs to:

```yaml
framework:
  datasource:
    jdbcUrl: "jdbc:sqlserver://db:1433;databaseName=stack"
    username: tesseraql
    modules:
      - com.microsoft.sqlserver:mssql-jdbc   # version from the BOM
```

The declaration is what `fetch --stack` reads (decision 5), what documentation can point at, and
what an error message can name. It is deliberately **not** a load-bearing check on its own: a
Maven coordinate does not map to a class, so nothing can verify "this coordinate is on the base
classpath". The refusal fires on the observable symptom instead — when no `Driver` accepts the
stack framework URL, the stack refuses with **`TQL-APP-4220`**, naming the declared coordinate and
the placement step, in place of the JDBC layer's `No suitable driver`.

**One placement route, three distributions.** Each launcher composes `lib/ext/*` and
`$TESSERAQL_CLASSPATH` onto the classpath ahead of running the CLI class, so the container image's
existing `lib/` habit, the developer CLI, and the Windows host image answer the same way:

| Distribution | Where a stack-scoped jar goes |
| --- | --- |
| Container image | `/opt/tesseraql/lib/` (already read by `-cp 'lib/*'`) |
| Developer CLI dist, `tesseraql-host` dist | `lib/ext/` beside the fat jar, or a path in `TESSERAQL_CLASSPATH` |
| Windows host app image | the app-image equivalent, chosen and verified in slice 4b |

Two mechanical consequences the slices must handle. The launchers run `java -jar` today, and
`-jar` ignores `-cp`, so they switch to an explicit classpath and main class. And their CDS
archive is keyed on the fat jar's size alone — a classpath the archive was not trained on is
silently refused by the JVM, costing start-up time with nothing printed — so the cache key becomes
the whole classpath, the extension jars included.

### 7. MariaDB Connector/J becomes a BOM-managed coordinate

`mysql-connector-j` is GPLv2 with the FOSS exception, which makes redistribution a question every
organization answers for itself. MariaDB Connector/J is LGPL, speaks the MySQL protocol, and can
be redistributed as a separate jar. Adding it to the BOM's managed set is one entry and gives
MySQL users a documented exit; [app-developer-distribution.md](app-developer-distribution.md)
gains one sentence about driver licences when a `.tqlapp` leaves the organization.

Fetching a driver into an operator's own bag is not redistribution, so `fetch` collects whatever
the stack declares without a licence flag; what the licences govern is the archive an organization
hands to someone else.

### 8. The S3 module excludes the HTTP stacks it does not use

`apache-client` and `netty-nio-client` are excluded from `software.amazon.awssdk:s3`, removing
2.6 MB and five artifacts from every application that opts into S3, and an enforcer rule in
`tesseraql-s3` keeps them out.

## Guards

| Module | Guard | What it refuses |
| --- | --- | --- |
| `tesseraql-camel-runtime` | `sso-modules-add-no-third-party` (new enforcer rule) | any third-party coordinate arriving through `tesseraql-oidc` / `-saml` / `-scim`; the promotion is justified by that closure being empty, so the closure is what is guarded |
| `tesseraql-s3` | `no-unused-http-clients` (new enforcer rule) | `software.amazon.awssdk:apache-client`, `software.amazon.awssdk:netty-nio-client` |
| `tesseraql-apptasks` | `AppPackagerTest` (extended) | an archive missing a declared module; a declaration with no lock; a closure that disagrees with the lock |
| `tesseraql-camel-runtime` | `AppModulesTest` (extended) | a bundled module set silently composed with, or shadowed by, a stale `work/modules` |
| `tesseraql-cli` | dist smoke assertion | a `modules/` directory in the dist archive |
| `tesseraql-cli` | launcher test (slice 4b) | a classpath change that leaves the CDS archive key untouched |

## Slices

Each slice is a PR and leaves the build green.

| # | Slice | Contents | Done means |
| --- | --- | --- | --- |
| 1 | The distribution stops carrying what it does not use | Decision 4 (dist bag removed) and decision 8 (S3 exclusions and rule) | The dist archive is 24 MB lighter; an S3 application resolves five fewer artifacts |
| 2 | SSO joins the runtime | Decision 2 and the closure enforcer rule; the `extending.md` / `module-scope.md` wording | A configured `oidc:` block works from an unzipped host image with no jar added |
| 3 | A `.tqlapp` carries its modules | Decision 3: resolve-from-lock in `AppPackager` and `PackageAppMojo`, `TQL-APP-4218` / `4219`, the `AppModules` precedence branch, the format lint, the `runtime-footprint.md` and `hosting.md` corrections | Installing a packaged PDF application onto a host with no repository starts and exports; no jar is ever committed to an application's repository |
| 4a | The bag | Decision 5: `modules fetch` resolving into the bag, `--repo`, `--platform` with the binary version, `bag.json`; decision 7; the `proxy.md` section | A stack prepared on a connected machine resolves, packages and runs `dev` on a disconnected one |
| 4b | One base classpath route | Decision 6: the stack declaration, `TQL-APP-4220`, `lib/ext/` and `TESSERAQL_CLASSPATH` in both launchers with the CDS key fix, the Windows app-image route verified on the `windows-latest` job, the `hosting.md` section | A SQL Server framework database works under `dev`, in the container, and on Windows Server, by the same placement step |

Slices 1 and 2 are independent of each other and of the rest. 4a and 4b are split so a Windows
verification problem cannot hold up the bag; 4b depends on 4a only for the fetch side of its
documentation.

## What moves in the docs

- [runtime-footprint.md](runtime-footprint.md): line 48's claim becomes accurate at slice 3.
- [hosting.md](hosting.md): the "operator runs the resolve" instruction is corrected at slice 3
  (the host has no resolver); driver placement is described against `lib/ext/` and the bag at
  slice 4b, and the SSO paragraph loses its "add the jar" step at slice 2.
- [extending.md](extending.md), [module-scope.md](module-scope.md): the SSO trio stops being
  described as opt-in jars at slice 2.
- [proxy.md](proxy.md): the air-gapped section becomes the bag's procedure at slice 4a.
- [app-developer-distribution.md](app-developer-distribution.md): the module set's reproducibility
  claim gains its packaging half; the driver licence sentence lands with decision 7.

## Open questions

1. **Whether the resolver honors `maven.repo.local`.** `--repo` is specified against that property
   because it is the Maven-native way to relocate a local repository, and the embedded ShrinkWrap
   resolver's support for it is unverified. Slice 4a settles it; the fallback, if it does not, is a
   generated `settings.xml` carrying `<localRepository>`, which the resolver already honors.
2. **The Windows app-image classpath route.** Adding entries to the generated `.cfg` is the
   candidate; whether it survives an image upgrade, and whether a wrapper-managed service picks it
   up, is verified on the `windows-latest` job in slice 4b rather than asserted here.
