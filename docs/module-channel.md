# The module channel

Status: designed 2026-08-20. What a TesseraQL runtime carries on its own classpath, what reaches
it through the module channel instead, and how everything in the second group travels — into a
`.tqlapp`, onto a Windows host image, and onto a machine with no outbound network.

[runtime-footprint.md](runtime-footprint.md) decided what a deployment carries: the host, not the
workshop. This document asks the question that follows from it. Of the things a deployment does
*not* carry, which ones should it carry after all, and for the rest — which are the majority — is
there one way for them to arrive, or one way per artifact? Today it is one way per artifact, and
two of those ways do not work.

## What reaches a runtime today, and by which channel

Measured 2026-08-20 on 0.15.0-SNAPSHOT: `dependency:list` at runtime scope, jar sizes from the
local repository, incremental sizes counted against the runtime closure so a shared dependency is
never counted twice.

| Channel | What travels on it | Where it ends up |
| --- | --- | --- |
| The runtime classpath | `tesseraql-camel-runtime`'s 195 artifacts: core, yaml, compiler, camel components, security, identity, oauth, operations, observability, ops-ui, mcp, every Flyway dialect adapter, the PostgreSQL driver, angus-mail, camel-ftp, zxing, the webjars | the deployment image |
| The module channel (`tesseraql.modules` → `work/modules`) | `tesseraql-pdf`, `tesseraql-excel`, `tesseraql-s3`, `duckdb_jdbc`, the three non-PostgreSQL JDBC drivers, an application's own `ExpressionFunction` / `FileCodec` / `BlobStoreProvider` jars | resolved per application |
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

## Problem 1 — a `.tqlapp` does not carry its modules, and two documents say it does

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

Two documents assert the behavior that is missing:

- [runtime-footprint.md](runtime-footprint.md) line 48 — "a `.tqlapp` ships the cache it was
  verified with".
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
| The framework database driver (Oracle, SQL Server, MySQL) | the operator adds a jar to the image or `lib/` | prose in [hosting.md](hosting.md) |
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

Nothing about activation changes. Each is a `RuntimeExtension` whose `enabled(AppConfig)` returns
false unless its own configuration key is set, and the extension allowlist gates classpath
discovery a second time. Presence on the classpath activates nothing; configuration does — the
same posture [studio-shell.md](studio-shell.md) chose when it made Studio a topology decision
rather than a packaging one.

What this buys: corporate SSO on a Windows Server deployment becomes unzip plus configuration,
with no jar to add — and the Windows host image's classpath-extension gap (problem 3, row 2)
narrows to database drivers and an application's own extension jars.

Documentation follows. [extending.md](extending.md) and [module-scope.md](module-scope.md) both
describe these three as opt-in jars, and both stop doing so.

### 3. `package` bundles the resolved cache, and an installed application loads from it

`AppPackager` gains a step after the source scan: the resolved module cache is written into the
archive under `.tesseraql/modules/`, the reserved namespace that already exists for build outputs
and is never populated from the source tree.

Packing is where the closure is verified, because packing is the last moment a resolver is
present:

- Modules declared, cache empty → **`TQL-APP-4218`**, naming the application and
  `tesseraql modules resolve`.
- Cache disagrees with `modules.lock` → **`TQL-APP-4219`**, the pack-time twin of the host's
  `TQL-APP-4217`.

`package` does not resolve on the spot even though the developer CLI could. A `.tqlapp` records
what a human resolved and reviewed; a silent resolve during packaging would make the archive's
contents depend on the state of a repository at build time.

`AppModules.load` gains one branch: an installed application reads `.tesseraql/modules/`, a
development source tree reads `work/modules`. Both compose with the `--modules` override
unchanged, and per-application isolation ([module-scope.md](module-scope.md) structural decision
2) is untouched — the bundled directory belongs to one application, exactly as the cache did.

[runtime-footprint.md](runtime-footprint.md) line 48 becomes true rather than aspirational, and
the correction is part of this slice.

### 4. The developer distribution stops shipping the pdf/excel bag

The `assemble-format-modules` execution, the two `provided` dependencies that feed it, and the
`modules/` fileSet in `src/assembly/dist.xml` are removed. The dist archive loses 24 MB and gains
nothing to explain.

`--modules` itself stays: pointing the CLI at a directory of local jars is how an extension is
developed before it has coordinates ([extending.md](extending.md)).

### 5. One bag for everything a distribution does not carry

A new command collects, on a connected machine, every artifact a stack will need on a disconnected
one:

```sh
# connected
tesseraql modules fetch --stack <install-root-or-folder> --into <bag> \
    --platform linux-amd64,windows-amd64 [--driver oracle]

# disconnected
tesseraql modules resolve --stack <dir> --offline --repo <bag>
tesseraql dev --embedded-db --repo <bag>
```

**The bag is a repository, not a classpath.** Its layout is a partial local Maven repository
(`group/artifact/version`), so the consuming side is the existing offline path with its local
repository pointed elsewhere — no new resolution code, no new loading semantics, and
`modules.lock` still decides what is correct. A shared directory of jars would have been simpler
to produce and would have undone [module-scope.md](module-scope.md) structural decision 2: one
runtime, one module set, no visibility into a neighbor's codecs.

What `fetch` collects, in one pass over the stack's members:

- every member's `tesseraql.modules` closure, pinned by that member's `modules.lock`;
- the embedded PostgreSQL binary for each `--platform` — the one artifact whose coordinate depends
  on the target machine rather than on the application, which is why the flag exists;
- the framework database driver for each `--driver` named, never by default, because
  redistributing `ojdbc11` or `mysql-connector-j` is a licence decision the operator makes and not
  a default the tool makes;
- a `bag.json` manifest recording what was collected, from which locks, with a SHA-256 per
  artifact.

**The host still never resolves.** For a deployment the bag is *placed*, not consulted: an
operator copies the driver into the image's `lib/`, and an application's modules ride inside its
`.tqlapp` (decision 3). The bag serves the developer machine, the CI runner that builds a
`.tqlapp` offline, and the operator preparing an image — never a running host. That boundary is
what keeps [runtime-footprint.md](runtime-footprint.md) decision 1 intact.

### 6. MariaDB Connector/J becomes a BOM-managed coordinate

`mysql-connector-j` is GPLv2 with the FOSS exception, which makes redistribution a question every
organization answers for itself. MariaDB Connector/J is LGPL, speaks the MySQL protocol, and can
be redistributed as a separate jar. Adding it to the BOM's managed set is one entry and gives
MySQL users a documented exit; [app-developer-distribution.md](app-developer-distribution.md)
gains one sentence about driver licences when a `.tqlapp` leaves the organization.

### 7. The S3 module excludes the HTTP stacks it does not use

`apache-client` and `netty-nio-client` are excluded from `software.amazon.awssdk:s3`, removing
2.6 MB and five artifacts from every application that opts into S3, and an enforcer rule in
`tesseraql-s3` keeps them out.

## Guards

| Module | Guard | What it refuses |
| --- | --- | --- |
| `tesseraql-camel-runtime` | `sso-modules-add-no-third-party` (new enforcer rule) | any third-party coordinate arriving through `tesseraql-oidc` / `-saml` / `-scim`; the promotion is justified by that closure being empty, so the closure is what is guarded |
| `tesseraql-s3` | `no-unused-http-clients` (new enforcer rule) | `software.amazon.awssdk:apache-client`, `software.amazon.awssdk:netty-nio-client` |
| `tesseraql-apptasks` | `AppPackagerTest` (extended) | an archive missing a declared module; a cache that disagrees with the lock |
| `tesseraql-cli` | dist smoke assertion | a `modules/` directory in the dist archive |

## Slices

Each slice is a PR and leaves the build green.

| # | Slice | Contents | Done means |
| --- | --- | --- | --- |
| 1 | The distribution stops carrying what it does not use | Decision 4 (dist bag removed) and decision 7 (S3 exclusions and rule) | The dist archive is 24 MB lighter; an S3 application resolves five fewer artifacts |
| 2 | SSO joins the runtime | Decision 2 and the closure enforcer rule; the `extending.md` / `module-scope.md` wording | A configured `oidc:` block works from an unzipped host image with no jar added |
| 3 | A `.tqlapp` carries its modules | Decision 3: the packager step, `TQL-APP-4218` / `4219`, the `AppModules` branch, the `runtime-footprint.md` correction | Installing a packaged PDF application onto a host with no repository starts and exports |
| 4 | The bag | Decision 5: `modules fetch`, `--repo`, the embedded-db binary and `--platform`, `bag.json`; decision 6; the `proxy.md` and `hosting.md` sections | A stack prepared on a connected machine runs `dev` and builds a `.tqlapp` on a disconnected one |

Slices 1 and 2 are independent of each other and of the rest. Slice 4 depends on slice 3 for its
documentation, not for its code.

## What moves in the docs

- [runtime-footprint.md](runtime-footprint.md): line 48's claim becomes accurate at slice 3.
- [extending.md](extending.md), [module-scope.md](module-scope.md): the SSO trio stops being
  described as opt-in jars at slice 2.
- [proxy.md](proxy.md): the air-gapped section becomes the bag's procedure at slice 4.
- [hosting.md](hosting.md): driver placement is described against the bag, and the SSO paragraph
  loses its "add the jar" step.
- [app-developer-distribution.md](app-developer-distribution.md): the module set's reproducibility
  claim gains its packaging half; the driver licence sentence lands with decision 6.

## Open questions

1. **How the bag is named on the command line.** `--repo <dir>` reads as a Maven concept and maps
   directly onto `maven.repo.local`; `--bag`, or an environment variable a shell profile sets
   once, may serve an operator better. Settled when slice 4 is built.
2. **Whether `fetch` also emits an archive.** A directory copies over a share; a single
   `stack-modules.zip` travels through a review gate more easily. The manifest makes either
   verifiable, so this is a convenience decision rather than a correctness one.
3. **Whether `package` should offer `--resolve`.** Decision 3 refuses to resolve silently. An
   explicit opt-in flag for a CI job that resolves and packages in one step is plausible, and is
   left out until someone has that job.
