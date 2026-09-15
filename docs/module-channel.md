# The module channel

Status: designed 2026-08-20; decision 9 added 2026-09-15. What a TesseraQL runtime carries on its
own classpath, what reaches it through the module channel instead, and how everything in the second
group travels — into a `.tqlapp`, onto a Windows host image, and onto a machine with no outbound
network.

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
| The runtime classpath | `tesseraql-runtime`'s 195 artifacts: core, yaml, compiler, camel components, security, identity, oauth, operations, observability, ops-ui, mcp, every Flyway dialect adapter, the PostgreSQL driver, angus-mail, camel-ftp, zxing, the webjars | the deployment image |
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
`software.amazon.awssdk:s3` artifact depends on its default clients transitively, and nothing
excludes them, so they ride along — `apache-client`, `netty-nio-client`, and (found while
implementing, in SDK 2.51.4) a third, `apache5-client`, which is what actually drags Apache
HttpClient 5 in:

| Family | Jars | Size |
| --- | --- | --- |
| AWS SDK proper | 31 | 8.31 MB |
| Apache HttpClient 5, via `apache5-client` | 4 | 2.24 MB |
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

They become compile dependencies of `tesseraql-runtime`, so the developer CLI, the
deployment distribution, and the host image all carry them.

Verified before deciding: none of the three depends on `tesseraql-runtime` (their poms carry
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

The closure is the module's own, never the framework's: the resolver excludes `io.tesseraql:*`
transitively from every declared module, because the runtime that loads the module already
carries what the module compiled against, parent-first, and a framework jar in the cache, the
package or the bag was a copy that never loaded and a lock line claiming a version that did not
run ([codec-discovery.md](codec-discovery.md) S5). An application declares each module it uses
by its own coordinate.

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

**A framework surface has no module channel, and the same shape would serve it.** The stack surface
runtime — sign-in, the account pages, the portal, the ops shell, IAM Admin — runs the bundled
`portal` application from the classpath. It declares no `tesseraql.modules` and has no resolved
cache, so a provider it needed could only come from the base classpath, exactly like the framework
database's driver. Nothing needs one today: no bundled application declares attachments, and
`SystemApps` does not even merge attachment documents, so a mounted surface cannot declare one.

If that changes, the shape is this decision's, not a new one: the stack file declares
`framework.objectStorage` (the provider and the coordinate that supplies it) beside
`framework.datasource`, the operator places the jar where this table says, and the refusal already
exists — `BlobStores` answers `TQL-YAML-1108` naming the provider it could not find, which is the
symptom-shaped refusal `TQL-APP-4220` had to be written by hand for. The key is deliberately not
added now: a declaration nothing reads is a promise the runtime does not keep, and the surface's
configuration is grafted from the stack file one subtree at a time (`security:` today) rather than
wholesale.

Member runtimes are a different matter and need nothing here: an application declares its object
store in its own `tesseraql.modules`, and the loader that resolution filled is the one discovery
reads.

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

`apache-client`, `apache5-client` and `netty-nio-client` are excluded from
`software.amazon.awssdk:s3`. Measured on 2.51.4: the module's own resolution drops from 64 to 49
artifacts, and what an S3 application adds beyond the runtime closure drops from 37 jars and
11.0 MB to 31 jars and 8.3 MB — **2.7 MB of HTTP stacks it never called**.

The enforcer rule bans the three clients *and* the stacks they bring (`httpclient5`, `io.netty:*`)
by their own coordinates, so a fourth default client in a later SDK release cannot reintroduce
them under a name the rule has not heard of — which is exactly how `apache5-client` arrived.

### 9. A module's closure excludes what the runtime carries, and the runtime says what that is

Decided 2026-09-15, for the item [codec-discovery.md](codec-discovery.md) S5 filed: after the
framework's own group leaves a module's closure, the pdf example still resolves 16 jars, and
several of them are on the classpath that loads the module — inert under parent-first loading, a
copy in every cache, package and bag, and a lock line claiming a version that never runs. The
resolver cannot name those by rule the way it names `io.tesseraql:*`; it needs the runtime's
closure at resolve time. **Which closure, and how the resolver names it, is the question this
decision settles before any code**, because three routes resolve or bundle a module closure and
the classpath their own process runs on is different on each:

| Route | What the resolver's own process has on its classpath | What the module meets at run time |
| --- | --- | --- |
| The dist shaded jar (`bin/tesseraql modules resolve`, `dev`, `package`) | one jar: the developer CLI's 214-artifact closure melted together — picocli, ShrinkWrap and its Maven resolver, the embedded PostgreSQL supervisor, `jcl-over-slf4j` | under `dev`, that jar; after `package`, the deployment's |
| The reactor (the CLI's tests, an IDE run, `exec:java`) | `target/classes` directories and `.m2` jars, the same 214 | the same |
| The Maven plugin (`package-app` in a wrapper pom) | the plugin's own realm — core, yaml, apptasks, identity, report, Maven — and no runtime at all | the deployment's |

And "the deployment" is itself three shapes: the `tesseraql-host` fat jar (the Windows app image),
the container's `lib/` (the host's runtime-scope dependencies), and a wrapper-pom build that embeds
`tesseraql-runtime` as a library and never sees the host.

**Measured 2026-09-15 on `c3875e871`** (`dependency:list -DincludeScope=runtime`, sorted):
`tesseraql-runtime` carries 149 artifacts; `tesseraql-host` 156 (the 149 plus picocli,
`slf4j-jdk-platform-logging`, and `tesseraql-cli`, `-apptasks`, `-runtime` themselves); the
developer CLI 214. Of the pdf module's 16 jars after S5, six are in all three closures —
`thymeleaf`, `ognl`, `attoparser`, `unbescape`, `javassist`, `slf4j-api`, 2.4 MB. The seventh S5
counted, `commons-logging`, is in **none** of them: the package `lib/tesseraql.jar` holds under
that name is `org.slf4j:jcl-over-slf4j`, which arrived with the resolver stack the host excludes.
A resolver that read "the base classpath" as its own would have dropped `commons-logging` from
every pdf cache, and PDFBox would have failed under `host` with a class `dev` had always found —
the asymmetry decision 4 exists to forbid, shipped by the fix for a smaller one. The excel module
overlaps the runtime on four third-party artifacts (`commons-codec`, `commons-io`, `stax2-api`,
`slf4j-api`), the S3 module on sixteen.

> **The set a module's closure excludes is `tesseraql-runtime`'s own runtime-scope dependency
> closure, and the runtime names it itself: its build writes the closure into the runtime jar as
> `META-INF/tesseraql/runtime-closure.txt` — the `maven-dependency-plugin` `list` output, runtime
> scope, sorted — and every route reads that one file. No route derives the set from the classpath
> it happens to be running on.**

Why the runtime's closure and not the host's: the host's 156 is what the three deployment shapes
carry, but the wrapper-pom build embeds the runtime without the host, and in every shape the module
loader's parent is the runtime's own loader (`AppModules`, [module-scope.md](module-scope.md)
structural decision 2). The runtime's closure is the floor every parent has. What the host adds is
picocli, a logging binding and first-party jars — nothing a module would bring and be right to
carry; if one ever brings picocli, carrying it is correct.

Why a file the build writes and not something else, each considered:

- **The resolver's own classpath** — `java.class.path`, or the `META-INF/maven/*/pom.properties`
  the shade plugin keeps: a different answer on each route, and wrong on two of them (the
  `commons-logging` case above).
- **Resolving `io.tesseraql:tesseraql-runtime` transitively at resolve time and subtracting**: the
  same answer everywhere, but 149 artifacts fetched to compute an exclusion list, a bag that has to
  carry the runtime's whole POM graph, and S5's principle — the exclusion is written into the POM
  so an offline resolve never asks for what it will not use — reversed.
- **A hand-kept list beside `FRAMEWORK_GROUP`**: 149 entries drifting from the runtime's POM with
  every bump, and the guard that keeps them equal would compare against `dependency:list` — the
  generated file with an extra step.
- **A committed ledger a guard regenerates** (the reference pages' shape): reviewable, but the
  file is derived from the POM with no human judgment in it, and every dependency bump that adds or
  removes a transitive artifact would fail the guard until someone regenerated. Built, not
  committed.

How each route reads the file:

- **The dist shaded jar**: the resource is shaded into `lib/tesseraql.jar` with the runtime's
  classes; the shade filter drops signatures and module descriptors and nothing else.
- **The reactor**: `tesseraql-runtime/target/classes`, written at `generate-resources`, so a
  `-pl tesseraql-cli -am` build and the IDE's Maven run both have it. A `.m2` runtime jar installed
  before this slice has no ledger, and the resolver **refuses** rather than resolving with an empty
  set — the same `-am install` a stale jar always needed, now named by the refusal.
- **The Maven plugin**: `package-app` computes no closure — it fetches what the lock pins
  (decision 3) — so it has no set to derive; what it must not do is bundle a lock a resolver
  without the ledger wrote. It resolves `io.tesseraql:tesseraql-runtime` at its own version through
  the Maven session, the same repositories the lock's coordinates come from, and reads the ledger
  out of that jar. Its own version, as `ModulesInstaller.BOM_COORDINATE` already decides for the
  BOM: the tool's version names the framework's.

What each route does with it:

1. **`ModuleResolver`** writes every ledger `group:artifact` as an exclusion of every declared
   dependency in the synthetic POM, beside the `io.tesseraql:*` wildcard S5 added; the ledger's
   own first-party lines are covered by the wildcard and not repeated. In the POM and not as a
   filter on the result, for S5's reason.
2. **A declared coordinate the ledger names is refused** — `TQL-APP-4222`, naming the coordinate
   and the version the runtime carries. A module is what the runtime does not carry; declaring
   `org.slf4j:slf4j-api` or `io.tesseraql:tesseraql-core` asks for a copy that never loads.
   `modules add` checks before it edits the YAML, so the declaration is never written.
3. **`package` refuses a lock that names a carried artifact** — `TQL-APP-4219`, naming the
   artifacts and `tesseraql modules resolve`, on both routes from one function in
   `PackagedModules`. The CLI route's exact comparison already refused such a lock; the check
   gives the Maven route the same refusal and both routes the same sentence. `dev` keeps starting
   on such a lock: `ModulesLock.verify` stays one-directional (S5), because the cache it fills is
   the smaller one and the lock's extra lines describe jars that would not have loaded.

Versions do not enter the exclusion. It is by `group:artifact`: a module whose closure wants a
different version of a runtime-carried artifact gets the runtime's version under parent-first
loading in any case — the difference the ledger makes is that the lock stops claiming otherwise,
and a class the newer version alone has fails to load instead of loading from a jar the rest of
the library does not match. A module compiles against the BOM's versions, which are the runtime's;
this decision writes down the rule that was always in force.

Consequences to record rather than discover:

- `modules fetch` resolves through the same POM, so a bag stops carrying the six; a bag fetched
  before this slice still serves, holding more than it is asked for.
- The pdf example's cache and lock go from 16 lines to 10. No committed lock names a carried
  artifact (`inventory-app`'s pins a driver with no overlap; `user-admin-app` commits none).
- A test fixture that declared a carried artifact as its "tiny, stable module" is now refused:
  `ModulesFetchIntegrationTest` declared `org.slf4j:slf4j-api` and moves to
  `info.picocli:picocli`, which the developer CLI carries and the runtime does not — the leaf
  `ModulesCommandTest` already pins. The full verify found it; 4222 is doing what it says.
- The ledger is a build output with the reproducibility every build output here has: the same POM
  produces the same file, byte for byte, and a dependency bump changes it without anyone
  regenerating anything.
- The ledger's versions are the runtime's own resolution, and a deployment's can differ: Maven
  mediates a version per graph, so the developer CLI carries `commons-codec` 1.21.0 where the
  runtime's ledger says 1.19.0, and the host carries `org.jetbrains:annotations` 13.0 to the
  runtime's 17.0.0. The exclusion is by `group:artifact` for this reason too, and the ledger guard
  checks artifacts against the classpath, not versions.

**Measured after the slice, through the reactor CLI against the local repository** (the copies
under `scratchpad/m/`): the pdf example resolves **10** artifacts, 5.2 MB — `commons-logging`
among them, the six gone; `modules add org.slf4j:slf4j-api` answers `TQL-APP-4222` naming
`org.slf4j:slf4j-api:2.0.18`, exit 2, the YAML untouched; a lock with the two carried lines
appended is refused by `tesseraql package` with `TQL-APP-4219` naming both, and packages 10 jars
once `modules resolve` rewrites it. The excel module resolves 22 where it resolved 26 — one of
the 22 is `jcl-over-slf4j`, which the developer CLI carries and the runtime does not, kept for
the same reason `commons-logging` is; the S3 module 32. One thing observed and not changed
here: `tesseraql package`'s lock refusals (`4218`, `4219`) printed as a stack trace with exit 1,
as they did before this slice — the CLI's exception shaper knew `UsageRefusal` and the
database-unreachable shape, and `PackagedModules` throws the runtime's `TqlException` so the
Maven goal can share it. **Closed 2026-09-15 by [cli-surface.md](cli-surface.md) decision 10a**,
which measured the shape across every verb first: it was never `package`'s — twenty-two verbs
printed a route that does not parse the same way — so the shaper learned the coded exception as
its third shape (the sentence, exit 2) rather than `package` wrapping two codes. The same
measurement found the CLI route's *drifted*-lock refusal had lost its 4219 in decision 10's
slice (#1338 turned the installer's mismatch into a `UsageRefusal`, and `PackageCommand`'s
re-raise caught the exception the installer no longer threw): that refusal is the installer's
own sentence at exit 2, the one `dev` gives the same lock, and 4219 on the CLI route is
`PackagedModules`' two sentences — a carried artifact, and a cache that disagrees with the lock
after a verified resolve.

## Guards

| Module | Guard | What it refuses |
| --- | --- | --- |
| `tesseraql-oidc`, `-saml`, `-scim` | `weightless-on-the-runtime` (new enforcer rule, one per module) | any declared dependency outside `io.tesseraql:*` (plus Jackson for SCIM) at compile or runtime scope. The guard lives in the module whose invariant it is, as every other boundary rule does: what justifies the promotion is that these modules stay weightless, and that is checkable where a dependency would be added |
| `tesseraql-s3` | `no-unused-http-clients` (new enforcer rule) | `software.amazon.awssdk:apache-client`, `software.amazon.awssdk:netty-nio-client` |
| `tesseraql-apptasks` | `AppPackagerTest` (extended) | an archive missing a declared module; a declaration with no lock; a closure that disagrees with the lock |
| `tesseraql-runtime` | `AppModulesTest` (extended) | a bundled module set silently composed with, or shadowed by, a stale `work/modules` |
| `tesseraql-cli` | dist smoke assertion | a `modules/` directory in the dist archive |
| `tesseraql-cli` | launcher test (slice 4b) | a classpath change that leaves the CDS archive key untouched |
| `tesseraql-runtime` | the `runtime-closure` execution (decision 9) | a runtime jar without `META-INF/tesseraql/runtime-closure.txt`; the file is `dependency:list` at runtime scope, sorted, and no hand ever edits it |
| `tesseraql-apptasks` | `RuntimeClosureTest` | a ledger line the parser cannot read (a plugin whose output format changed) is refused, never skipped; the header, blank lines and the ` -- module` suffix are not lines. `PackagedModulesTest`: a lock naming a carried artifact is `TQL-APP-4219` naming it |
| `tesseraql-cli` | `RuntimeClosureLedgerTest` | a ledger that is not the runtime's closure: every line's jar must be on the test process's classpath at the version the line names, and the ledger must name neither picocli (the host's) nor `commons-logging` (a module's) |
| `tesseraql-cli` | `ModuleResolverTest` (extended) | a module dependency the ledger names left in the closure; a declared coordinate the ledger names not refused with `TQL-APP-4222` before any repository is asked |
| `ci.yml` dist job | the README step | a jar the shaded jar's own ledger names left in `user-admin-app/work/modules` after `dev` resolved from the archive — the one route no in-JVM test runs |

## Slices

Each slice is a PR and leaves the build green.

| # | Slice | Contents | Done means |
| --- | --- | --- | --- |
| 1 | The distribution stops carrying what it does not use | Decision 4 (dist bag removed) and decision 8 (S3 exclusions and rule) | The dist archive is 24 MB lighter; an S3 application resolves five fewer artifacts |
| 2 | SSO joins the runtime | Decision 2 and the closure enforcer rule; the `extending.md` / `module-scope.md` wording | A configured `oidc:` block works from an unzipped host image with no jar added |
| 3 | A `.tqlapp` carries its modules | Decision 3: resolve-from-lock in `AppPackager` and `PackageAppMojo`, `TQL-APP-4218` / `4219`, the `AppModules` precedence branch, the format lint, the `runtime-footprint.md` and `hosting.md` corrections | Installing a packaged PDF application onto a host with no repository starts and exports; no jar is ever committed to an application's repository |
| 4a | The bag | Decision 5: `modules fetch` resolving into the bag, `--repo`, `--platform` with the binary version, `bag.json`; decision 7; the `proxy.md` section | A stack prepared on a connected machine resolves, packages and runs `dev` on a disconnected one |
| 4b | One base classpath route | Decision 6: the stack declaration, `TQL-APP-4220`, `lib/ext/` and `TESSERAQL_CLASSPATH` in both launchers with the CDS key fix, the Windows app-image route verified on the `windows-latest` job, the `hosting.md` section | A SQL Server framework database works under `dev`, in the container, and on Windows Server, by the same placement step |
| 5 | What the runtime carries leaves the module closure | Decision 9: the runtime's build writes its closure ledger; `ModuleResolver` excludes it and refuses a declared coordinate it names (`TQL-APP-4222`); `package` on both routes refuses a lock that names one (`TQL-APP-4219`); the `codec-discovery.md` S5 note corrected | The pdf example resolves 10 jars, not 16, from the reactor and from the dist archive alike; a lock written before the slice is refused by `tesseraql package` and by `package-app` with the same sentence |

Slices 1 and 2 are independent of each other and of the rest. 4a and 4b are split so a Windows
verification problem cannot hold up the bag; 4b depends on 4a only for the fetch side of its
documentation. Slice 5 landed after all four, on the resolver S5 of
[codec-discovery.md](codec-discovery.md) left.

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

1. ~~**Whether the resolver honors `maven.repo.local`.**~~ **Settled in slice 4a: it does.**
   ShrinkWrap's `MavenSettingsBuilder` reads the property as its `ALT_LOCAL_REPOSITORY_LOCATION`,
   and `ModulesFetchIntegrationTest` proves the round trip — a bag filled by `fetch`, then an
   offline resolve out of it with no other configuration. The `settings.xml` fallback was not
   needed. One thing the implementation added: a bag must contain the BOM **and the parent POM the
   BOM inherits from**, which resolving into the bag produces on its own.
2. ~~**The Windows app-image classpath route.**~~ **Settled in slice 4b: `app\ext\` plus an
   `app.classpath` line inside the generated `.cfg`'s `[Application]` section.** The jpackage job
   asserts it in both directions on `windows-latest` — a stack whose framework datasource is SQL
   Server is refused with `TQL-APP-4220` before the jar is placed, and gets past that refusal
   after. The section matters, and the CI run is what found it: an entry appended at the end of the
   file lands in `[JavaOptions]`, where the launcher reads it as a JVM option and the classpath
   never grows. An image upgrade replaces the app directory, so the placement is repeated on
   upgrade; that is the same contract the container image's `lib/` has.
