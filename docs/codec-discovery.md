# One codec set per application

> **Status: designed 2026-09-14; every decision decided as recommended the same day.** Slices
> S1-S4 below, each its own pull request branched from fresh `origin/main` after the previous
> one merged. Each pull request flips its own line here when it merges.
>
> **S1** — the codec set is the application's, discovered once from its module loader and
> handed to every consumer (the sync `query-export` route, the import view, the reloader, the
> transfer service): **shipped as S1**. **S2** — boot refuses a format no codec serves on every arm, naming
> the site; lint judges every declared format against the run's codec set: **shipped as S2**. **S3** — the
> developer CLI's module view is the runtime's, and a module that cannot be resolved is a shaped
> refusal: **shipped as S3**. **S4** — the README's second quick start runs as written from the `-Pdist`
> archive, and CI proves it: *open*.

This is F82 slice 2 of [`audit-medium-leads.md`](audit-medium-leads.md) (decision 3 there:
"its own campaign, design-doc first"). The lead was filed three times before this record —
[`export-declarations.md`](export-declarations.md) "filed, not fixed", [`export-hygiene.md`](export-hygiene.md)
item 3, and the audit record's decision 3 — each time as a `RouteCompiler`/`ViewBinding`
change. It is that, and it is also the reason the README's second quick start has not started
since the pdf codec left the distribution ([`module-channel.md`](module-channel.md) decision 4).

Registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` by this record.
Site-excluded, so the prose lint does not read it; `sync-content.mjs` and `InternalDocsSyncTest`
do.

## What was measured

Measured 2026-09-14 on main `a35518f35` (0.17.0-SNAPSHOT), on the `-Pdist` archive built exactly
as the README says (`./mvnw -B -ntp -DskipTests -pl tesseraql-cli -am -Pdist package`, unpacked,
`bin/tesseraql`), against the Dev Container's PostgreSQL and the embedded one. Every row is a
RUN; the raw logs are under `scratchpad/m/` on this machine only. The prior measurement of the
same mechanism — in-process and on a csv override rather than pdf — is
`work/export-hygiene-measurement/m-codec-discovery.md` and its attack, both this machine only;
nothing below contradicts them, and rows 9 and 13 re-run their findings on the real archive.

| # | Run | Result |
| --- | --- | --- |
| 1 | The README's second quick start: `dev --stack examples --app-name user-admin` | **`TQL-LD-2801: No file codec for format 'pdf' - available: [csv] (the excel format needs the tesseraql-excel module on the classpath)`, exit 2.** Names neither the application nor the route (`users.print`); hints at excel for a pdf failure. The archive carries exactly one codec (`CsvFileCodec` is the only `META-INF/services/io.tesseraql.core.files.FileCodec` line in `lib/tesseraql.jar`). |
| 2 | `lint --app examples/user-admin-app` from the archive | `TQL-YAML-1408 [warning] web/api/users/print/get.yml: format: pdf needs the io.tesseraql:tesseraql-pdf module, which this application neither declares under tesseraql.modules nor carries on the classpath`. The shipped flagship example is the shape the lint warns about, and the README tells the reader to run it. |
| 3 | A copy declaring `tesseraql.modules: [io.tesseraql:tesseraql-pdf]`; `modules resolve` (17 jars into `work/modules`, `modules.lock` written); `lint`; `dev` | Lint: 1408 gone, one finding (`TQL-LD-5310`, unrelated). **`dev`: "Resolved 17 tesseraql.modules artifact(s)" then `TQL-LD-2801` at boot, exit 2.** The install `printable-documents.md:10` documents does not serve the document's own example route. |
| 4 | The same copy with the print route as `file-export` (`post.yml`) | Boots. `POST /user-admin/api/users/print` → 202, transfer `COMPLETED`, `/file` → 200 `application/pdf`, 5,689 bytes, `%PDF-1.6`. **The same declaration serves one recipe and refuses the whole application on the other.** |
| 5 | Row 3's copy with `--modules <its own work/modules>` | `TQL-LD-2801`, unchanged. No developer-side switch reaches the synchronous route. |
| 6 | The pdf closure placed in the archive's `lib/ext/` (the base classpath, `module-channel.md` decision 6's driver route) | The README example boots; `GET /user-admin/api/users/print` → 200, 5,689 bytes, `%PDF-1.6`. **The one route that works is the one decision 4 forbids** — a codec in development that a deployment does not have. |
| 7 | `format: fixedwidth` (no such codec) on the query-export | Lint: `0 finding(s)`. `dev`: `TQL-LD-2801 … 'fixedwidth' … (the excel format needs …)`, exit 2. |
| 8 | `format: fixedwidth` on a file-export | Lint: `0 finding(s)`. Boots. First POST → **500 `TQL-LD-2801`**. |
| 9 | `host --stack <row 3's copy>` (the production verb, declared and resolved) | `TQL-LD-2801`, exit 2. **A deployment cannot start an application whose query-export uses a declared module codec.** |
| 10 | The declared module with nothing to resolve it from (`--offline --repo <empty>`) | A raw `org.jboss.shrinkwrap.resolver.api.InvalidConfigurationFileException` with 18 frames, exit 1, no TQL code: "Non-resolvable import POM: … io.tesseraql:tesseraql-bom:0.17.0-SNAPSHOT". This is what the README's reader gets after S4 unless the build line installs the reactor: `-pl tesseraql-cli -am` builds `tesseraql-pdf` (the runtime test-depends on it) and installs nothing, and `tesseraql-bom` is not in that closure at all. |
| 11 | The README's curl, as written, against row 6's running example | `GET /api/users?q=sato` → **404** (the member is at `/user-admin/`). At the member address the README's hand-minted JWT → 401 `TQL-SEC-4144` (no `exp`); with `exp` → 401 `TQL-SEC-4143` (no `aud`); with both → **403 `TQL-SEC-4031`: "Principal is not granted tql.app.use.user-admin, which using this application requires"**. `tesseraql token --app examples/user-admin-app --role USER_READ` → the same 403; `--permission tql.app.use.user-admin` → 200, `sato`. |
| 12 | The README's first quick start: `tesseraql new myapp`, `dev --embedded-db`, then `getting-started.md:117`'s `tesseraql token --app . --role ADMIN` and a curl of `/myapp/api/items` | 403 `TQL-SEC-4031` — `ADMIN` names no scaffolded policy (`app.read` is `APP_READ` or `myapp.read`), and `--role APP_READ` is 403 too; `--role APP_READ --permission tql.app.use.myapp` → 200. **The documented smoke test fails on a freshly scaffolded application.** |
| 13 | An undeclared drop-in `work/modules` (the pdf closure, no `tesseraql.modules`, no lock), file-export shape | Lint: `TQL-YAML-1408 … neither declares under tesseraql.modules nor carries on the classpath`. `dev`: boots and serves the PDF from that directory (202 → `COMPLETED` → `%PDF-1.6`). The developer CLI and the runtime disagree about which codecs the application has. |
| 14 | `bin/tesseraql --version`, first run, fresh `XDG_CACHE_HOME` | **145 `[warning][cds] Skipping …: Old class has been linked` lines on stdout**, one line on stderr. The launcher's `-Xlog:cds=error:stderr` adds an output; the JVM's default stdout output (`all=warning`) keeps printing. Second run: quiet. |

Two side observations, filed below and not fixed here: `dev --app-name user-admin` prints
"Resolved 1 tesseraql.modules artifact(s) for inventory-app" and the first-administrator hint
for `scaffold-demo-app` — it resolves and advises for members it does not run; and
`modules resolve` copies `tesseraql-core-0.17.0-SNAPSHOT.jar` into `work/modules` (parent-first
loading makes it inert, but it is a second copy of core in every resolved cache).

## The mechanism

`FileCodecs.discover()` reads the thread context class loader. Under `tesseraql dev` and
`host` that loader is the process's application class loader, which holds the base classpath
and nothing an application resolved: `AppModules.load` builds a child `URLClassLoader` over
`work/modules` (or the bundled `.tesseraql/modules`) per runtime, deliberately never composed
onto the process ([`module-scope.md`](module-scope.md) structural decision 2;
[`stack-architecture.md`](stack-architecture.md) decision 28 rejects the union loader). Only
`CliModules.installAppExtensions` ever sets the context loader, and only on the single-application
CLI verbs (`lint`, `job run`, `test`, …).

The census of `FileCodecs.discover` at `a35518f35`:

| Site | Loader | Consequence |
| --- | --- | --- |
| `TesseraqlRuntime:1010` `discover(modules.loader())` → `JdbcFileTransferService` | the application's | file-export, file-import, job export steps: the module codec **serves** |
| `RouteCompiler:1421` `discover()` in `buildQueryExport` | context loader | query-export: the module codec is **absent**, and the whole application refuses to boot |
| `ViewBinding:219` `discover()` in `importTarget` | context loader | an import view whose format is a module codec renders its file input with **no `accept`** (`th:attr` drops a null), against `csv-import.md` decision 8 |
| `RouteReloader:242/287` (`new RouteCompiler()…functions(functions)`) | context loader, through the compiler's default | a hot reload compiles with the same absent set |
| `ModuleDeclarationRules:44` `discover()` | context loader | lint 1408, pdf and excel only; on the developer CLI the loader holds the resolved declared cache and `--modules`, never an undeclared `work/modules` |
| `JobCommand:447` `discover()` | context loader, composed by `installAppExtensions` | `tesseraql job run` sees the declared cache and `--modules` |
| `StudioSupport:481/512` `discover(modulesLoader)` | the application's | the PDF previews: correct |

`ExpressionFunctions` had exactly this defect and was fixed by threading: `AppModules.functions()`
is loaded once from the module loader and handed to `RouteCompiler.functions(…)`, the reloader,
the binders and the transfer service. The codec set never got the same treatment — `module-scope.md`'s
own correction of 2026-08-20 records that "only the codec side shipped that way", meaning the
runtime's one site, and the compiler's two sites were never on the list.

## Decisions

Each with a recommendation. The mechanical ones (1, 2, 3, 5, 8) follow from the measurement;
4, 6 and 7 change a contract or a document a reader follows. *Decided 2026-09-14: all nine as
recommended, implemented in slice order.*

### 1. The codec set is the application's, discovered once, and every consumer receives that instance

`AppModules` gains `codecs()` beside `functions()`: `FileCodecs.discover(loader)` at load, with
the same failure handling (a broken jar's `ServiceConfigurationError` closes the loader it just
opened). The runtime hands the instance to `new RouteCompiler().codecs(modules.codecs())`, to the
`RouteReloader` beside `functions`, and to `JdbcFileTransferService` in place of the second
discovery at `:1010`. `RouteCompiler` keeps a default for its unit tests — a discovery on its own
class loader, never the thread's — exactly as `functions` defaults to `processDefault()`; the
runtime and the reloader always set the application's. `ViewBinding.of(…)` takes the set as a parameter — a static that discovers
on its own is the shape that produced this defect. Studio's two preview sites already discover on
the module loader and stay as they are: the same loader yields the same set, and a request-time
discovery for a preview is not a boot cost.

**The no-argument `FileCodecs.discover()` is deleted** (AGENTS.md rule 10): every discovery names
its loader. The CLI verbs that compose the context loader spell it —
`discover(Thread.currentThread().getContextClassLoader())` — so the choice is visible at the call
site. A repo-wide guard (`CodecDiscoveryLedgerTest`, tesseraql-docs-reference, the
`RetiredVerbLedgerTest` shape) asserts that no main source outside `tesseraql-cli` and the
linter's default passes the context loader to `FileCodecs.discover`.

*Rejected.* Setting the context loader per runtime: one stack hosts N runtimes on shared
threads, and the reloader and the transfer executor run on threads no boot owns. A static
registry: process-global is stack-global, which is decision 28's rejected union. Passing the
loader instead of the set: two discoveries can disagree on order, and the set is what
`FileCodecs.put`'s last-wins line describes.

### 2. Boot refuses a format no codec serves, on every arm, naming the site

With the application's set in hand at compile, `buildFileExport` and `buildFileImport` judge
`format:` where `buildQueryExport` does today, and `requireValidJobDeclarations` judges every
export step and poll-job import against the runtime's set — so a `file-export` with no codec
refuses at boot instead of answering 500 at its first POST (row 8), and a job step refuses at
registration instead of failing its first run. `tesseraql job run` judges with the CLI's set
before wiring, as [`export-declarations.md`](export-declarations.md) 5a already has it judge
the declaration.

The refusal keeps `TQL-LD-2801` (one meaning: no codec for this format in this codec set) and
gains the site: `app 'user-admin': route 'users.print' export.format: 'pdf' - no file codec …
available: [csv]` through `ExportDeclarations.Site.prefix`, the message shape decision 23 of
`export-declarations.md` fixed. **The hard-coded excel hint goes**: the message names the two
opt-in formats and the mechanism ("pdf and excel are modules: declare them under
tesseraql.modules — docs/printable-documents.md, docs/file-transfers.md") whatever the format
asked for, since row 1 hinted at excel for pdf and row 7 for `fixedwidth`.

A hot reload that introduces a format no codec serves fails that route's compile and serves the
per-route 500 stub with the reason, as any compile error does; nothing new.

*Rejected.* A lint-only answer: row 8's 500 is a runtime the author already shipped. Refusing
at `FileCodecs.discover` when a format is claimed twice: `export-hygiene.md` P7 measured that
shape as refusing every application carrying the module, exports or not, and kept last-wins with
a warning; unchanged here.

### 3. Lint judges every declared format against the run's codec set

`ModuleDeclarationRules` generalises: every export and import `format:` on routes and job steps
that is not `csv` (the built-in the runtime closure always carries) and not in the run's codec set
draws `TQL-YAML-1408`. For `pdf` and `excel` the message names the coordinate as today; for any
other name it names the mechanism ("no codec for format 'fixedwidth' on this classpath — a
module codec is declared under tesseraql.modules, or passed with --modules while it is being
developed"). One code, because it is one condition: this classpath cannot serve the format.

It stays a **warning**, for the reason the rule already records: an application built through a
wrapper pom lints on a CLI that lacks a codec its own runtime image carries. What changes is
that after decision 4 the developer CLI's set is the set `dev` will boot with, so on the CLI
"lint green" implies "dev boots" for formats — the property row 3 broke.

The linter takes the codec set as it takes functions: `AppLinter.lint(appHome, functions, codecs)`;
the two-argument form discovers on the context loader for the CLI. `LintContext.codecs()`
serves the rule. Studio's health lint and the Copilot's `lint` tool pass the runtime's sets —
functions as well as codecs, since `StudioService.health()` lints with the process default today
and never sees a module function either (the twin of this defect, one line away).
`ExportDeclarationsTest.aFormatNameInMixedCaseIsRefusedAndAnUnknownNameIsNot` keeps its second
half: the *predicate* still does not judge a name; the *lint* does, with a set.

### 4. The developer CLI's module view is the runtime's

`CliModules.moduleCache` today: resolver present and nothing declared → `Optional.empty()`, so
`lint`, `job run`, `test` and `coverage` never read an undeclared `work/modules` that `dev`,
`host` and the deployment CLI (`tesseraql-host job run`, which has no resolver and reads the
directory as-is) all load (row 13; the attack's finding 2). One rule for every runner: **resolve
when the application declares modules, otherwise read the directory the runtime reads** — the
bundled `.tesseraql/modules` when it holds jars, else `work/modules` — which is the deployment
CLI's branch, applied to both. The directory choice moves out of `AppModules.load` into one
function beside `WorkHome.bundledModules` that both the runtime and the CLI call, so the two
cannot drift again.

*Rejected.* Making the runtime ignore an undeclared `work/modules`: `AppModules`' contract says a
source tree reads that directory, `MultiAppHostIntegrationTest`, `ModuleDriverBindingTest` and
the module fixture pattern (a services-file-only jar in `work/modules`, the provider on the test
classpath) all rely on it, and it is the directory `--modules` composes with. The runtime's
rule is the documented one; the CLI's was an accident of the resolver branch.

### 5. A module that cannot be resolved is a shaped refusal

`ModulesInstaller.install` lets the resolver's own exceptions escape raw
(`InvalidConfigurationFileException` in row 10; the missing-artifact one measured in S3). They
become `TQL-APP-4221` — one line
naming the coordinate that failed, the BOM coordinate the resolver needed, and the fix (`./mvnw
install` from the monorepo; `--repo <bag>` offline; the repository settings otherwise) — thrown
as the CLI's `UsageRefusal` shape so `dev`, `host`, `lint`, `job run` and `modules resolve` all
exit 2 before any work, per [`cli-surface.md`](cli-surface.md) decision 10. 4221 is the next
free `TQL-APP` code after the module-channel's 4216-4220.

### 6. The README's second quick start runs as written, and CI proves it

- `examples/user-admin-app/config/tesseraql.yml` declares `tesseraql.modules:
  [io.tesseraql:tesseraql-pdf]`. **No `modules.lock` is committed for it**: the lock pins a
  checksum, and a first-party module at the framework's own SNAPSHOT version changes checksum on
  every rebuild, so a committed lock would refuse `dev` (`modules.lock verification failed`) for
  every contributor after their next `install`. `inventory-app` keeps its lock — `duckdb_jdbc` is
  a third-party artifact at a fixed version, which is what a lock is for. The README says why in
  one sentence.
- The README's build line becomes `./mvnw -B -ntp -DskipTests -Pdist install`: the reactor
  installs `tesseraql-bom` and `tesseraql-pdf` into the local repository, which is where
  `dev` resolves the declaration from (row 10 is what the current `-pl tesseraql-cli -am … package`
  line would produce after this change). One command; S4 records its duration.
- The README's curl block becomes the member address and the CLI mint — see decision 7 — and the
  JWT paragraph goes; `getting-started.md:110-118` names `APP_READ`, the role the scaffolded
  policy actually reads.
- `ci.yml`'s "Run a gallery app from the dist archive" step additionally runs the README's
  example: installs the reactor (`-DskipTests install` replaces the `-am … package` line, the
  dist assembly rides on `-Pdist`), boots `dev --stack examples --app-name user-admin
  --embedded-db`, mints the token with the CLI, and asserts `GET /user-admin/api/users/print`
  answers `application/pdf` with `%PDF` as its first bytes — the codec seam end to end from the
  shipped archive, which no in-JVM test can see. **This edits a CI workflow file**; it is called
  out in S4's pull request rather than slipped in.

*Rejected.* Moving the example's print route to `file-export`: the route is the documented
printable recipe (`printable-documents.md` shows `query-export`), and the README would then
demonstrate the defect's workaround. Running a pdf-less example: CI already runs `inventory`;
the README's example is `user-admin` because it is the flagship, and the quick start exists to
show the front door works.

### 7. A development token minted for an application can use that application

`tesseraql token --app <home>` mints a token whose `aud` is that application's audience and whose
`permissions` carry nothing, so the token cannot pass the application-use fence
(`tql.app.use.<name>`, [`application-roles.md`](application-roles.md)) of the very application
it is minted for — rows 11 and 12. **Recommended: the local mint stamps `tql.app.use.<name>`
into `permissions` for the application it mints for**, `<name>` read from `tesseraql.app.name`,
unless the caller passes `--permission` explicitly (an explicit list is the caller's). The
`--url` mint already does this by construction (the server mints the member's active view).
The README and `getting-started.md` then read `tesseraql token --app examples/user-admin-app
--role USER_READ`, and the reference-cli page regenerates from the option's description.

*Alternative, if the fence is meant to stay a separate, visible grant:* leave the mint alone
and have every recipe spell `--permission tql.app.use.<name>`. That keeps a scaffolded
application's first curl a two-flag incantation for a fact the mint already knows.

### 8. The launcher's first run is quiet

`bin/tesseraql` sets `-Xlog:cds=error:stderr`, which *adds* a stderr output for cds errors and
leaves the JVM's default stdout output at `all=warning` (row 14). The fix is measured in S4
against a fresh cache directory — the candidate is `-Xlog:disable -Xlog:all=warning,cds=error:stderr`,
or the shortest spelling that leaves stdout empty on the run that writes the archive while
keeping the "archive refused" line an operator wants. `tesseraql.cmd` gets the same. The
first command a README reader types should print the version, not 145 warnings on the stream
`routes --format json | jq` reads.

### 9. Filed, not fixed

- `dev --app-name X` resolves every stack member's modules and prints the first-administrator
  hint for members it does not run (rows 1 and 6). Its own small slice; it touches the
  stack-discovery contract, not codecs.
- `modules resolve` writes `tesseraql-core-<version>.jar` into `work/modules`: the resolver
  keeps the module's whole closure, framework jars included. Inert under parent-first loading;
  wasteful, and a trap the day a module is built against a different core. Belongs to the
  module channel (`ModuleResolver`'s exclusion set).
- Studio's `StudioService.health()` and the Copilot's `lint` never saw module functions
  (decision 3 fixes it as a by-product; recorded so the fix is not mistaken for scope creep).
- `FileCodecs.put`'s last-wins warning prints once per codec set; after S1 the compiler no
  longer discovers, so it prints once per boot — the sentence `export-hygiene.md` P7 predicted
  becomes true.
- `ExpressionFunctions.install` and `ModuleDrivers.register` mutate process state on the CLI
  while the runtime threads instances; the codec seam follows the runtime's shape. Retiring the process-global
  installs on the CLI is [`module-scope.md`](module-scope.md) open question 1's territory.

## Slices

| Slice | Change | Modules | Guards and their red proof |
| --- | --- | --- | --- |
| **S0** | this record, registered in both internal-doc lists | docs, docs-site, docs-reference | `InternalDocsSyncTest`; `sync-content.mjs` |
| **S1** | decision 1: `AppModules.codecs()`; `RouteCompiler.codecs(…)`; `ViewBinding.of(…, codecs)`; the reloader and the transfer service take the instance; the no-arg `discover()` deleted; `CodecDiscoveryLedgerTest` | core, compiler, runtime, cli, yaml (call sites), docs-reference | **`ModuleCodecIntegrationTest`** (runtime, PostgreSQL): a test codec class on the test classpath, a jar under `work/modules` carrying only its `META-INF/services` line (the `MultiAppHostIntegrationTest` fixture shape), format `marker`; a query-export, a file-export and a job step all declared `format: marker`; an import view over a `file-import` of the same format. Asserts: the runtime **boots**; the sync GET serves the codec's bytes; the async POST and the job step do too; the import page's file input carries the codec's `accept`; a route edit + `RouteReloader` reload keeps serving the codec. Red on `origin/main`'s jars (installed `-Dmaven.test.skip=true`, the jar proven by its constant pool): boot refuses 2801. Variants built and run before shipping: **v-reloader** (reloader constructs the compiler without the set) → the reload leg 500-stubs; **v-view** (`ViewBinding` discovers on its own) → the `accept` leg red; **v-transfer** (the transfer service discovers a second time) → green by construction, disclosed as such. Compiler unit: `ViewBinding` with an explicit set holding `excel` renders `.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`. |
| **S2** | decisions 2 and 3: the site-naming 2801 on every arm at compile/registration; `job run` judges with the CLI's set; `ModuleDeclarationRules` over every format with the run's set; `AppLinter.lint(…, codecs)`; Studio/Copilot pass the runtime's sets; the excel hint gone; reference regen | yaml, compiler, runtime, cli, studio, studio-runtime, docs-reference | Compiler: `ExportDeclarationCompileArmsTest` gains the codec arm — a file-export and a file-import declared `fixedwidth` refuse at compile naming app, route and key; a job step at `requireValidJobDeclarations`. Runtime IT leg: a file-export with no codec refuses **boot** (red on HEAD: boots, 500 at POST). Lint: `AppLinterModuleDeclarationTest` — `fixedwidth` warns naming the mechanism; a set that supports it is silent; `csv` never fires; Studio's health lint with a set holding a module format is silent (red on HEAD: 1408). CLI: `tesseraql job run` on a `fixedwidth` step exits 2 naming the step before any row (red on HEAD: `FAILED … 'fixedwidth'` after wiring). |
| **S3** | decisions 4 and 5: `CliModules.moduleCache` reads an undeclared `work/modules`; `ModulesInstaller` shapes the resolver's failure as `TQL-APP-4221` | cli, docs-reference | `CliModulesTest`: a services-file-only jar in `work/modules`, nothing declared, resolver present → `lint` silent on 1408 and `job run` serves the codec (red on HEAD: 1408; 2801). `ModulesInstallerTest`: an empty `--repo` offline → `UsageRefusal` `TQL-APP-4221` naming the coordinate and the BOM, no frame (red on HEAD: the shrinkwrap type). |
| **S4** | decisions 6, 7, 8: the example declares its module; README + getting-started; the mint's app-use atom; the launcher; the CI smoke on the README's example | examples, docs, cli, `bin/tesseraql(.cmd)`, `.github/workflows/ci.yml`, docs-reference (reference-cli) | `TokenCommandTest`: `--app` stamps `tql.app.use.<name>`; `--permission x` replaces it (red on HEAD). Launcher: a shell test under `tesseraql-cli/src/test` runs `bin/tesseraql --version` with a fresh `XDG_CACHE_HOME` and asserts stdout is the version line alone (red on HEAD: 146 lines). `ReadmeLedgerTest` gains: the README's build line installs; its curl names the member address; the example's config declares every opt-in format it uses (red on HEAD on all three). The CI step is the end-to-end guard and is red on HEAD by rows 1 and 11. |

Order: S1 → S2 → S3 → S4. S2 needs S1's set at compile; S4's CI step needs S1-S3 (row 1 boots,
row 11 answers). S3 is independent of S1 and S2 and could land between them without conflict.

### Rules carried into every slice

- Build the broken variant and run the guard against it. Every guard above names its variants;
  a guard whose variant is green is disclosed as such in the record, never left to a reviewer.
- The head column is proven by the jar's constant pool, not by the worktree
  (`unzip -p … | strings | grep <new symbol>` = 0), and installs use `-Dmaven.test.skip=true`.
- `-am` builds dependencies, not dependents: the verify set names `tesseraql-docs-reference`,
  `tesseraql-cli` and `tesseraql-studio`, whose ledger and docs guards walk `docs/`.
- A `TQL-<DOMAIN>-<n>` in a Javadoc lands in the generated reference; prose about another
  component's code does not spell it.
- Slice branches start from fresh `origin/main`; `gh pr view --json mergeStateStatus` before
  and after; "Maven verify on Java 25" on the head SHA before the merge.

## Verification record

### S1 — the seam

`ModuleCodecIntegrationTest` (tesseraql-runtime): five legs — the sync GET's bytes and
`Content-Type`, the async transfer's bytes, the job step's completion with the codec's write
counter advanced, the import page's `accept=".marker,text/x-marker"`, and a reload leg that
rewrites the route's `filename:` and asserts the reloaded route still writes the marker.
`ViewBindingImportTargetTest` (tesseraql-compiler): an explicit set holding a workbook codec
renders `.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`; the
compiler's own classpath renders no list. `CodecDiscoveryLedgerTest` (tesseraql-docs-reference):
every `FileCodecs.discover(` in a main source is a ledger row naming its loader, and every row
still discovers.

Red proofs, each on a jar proven by its constant pool (`RouteCompiler.class` with zero `codecs`
symbols for the head column; `ViewBinding.class` with a `getClassLoader` symbol for v-view):

| Column | Result |
| --- | --- |
| head (`a35518f35`, the IT copied in alone) | the boot fails: `TQL-LD-2801: No file codec for format 'marker' - available: [csv, excel, pdf]` — one `initializationError`, all five legs unreachable |
| v-reloader (the reloader constructs the compiler without the set) | 4 green, the reload leg red |
| v-view (`ViewBinding` discovers on its own loader, the parameter ignored) | 4 green, the accept leg red. **The first build of this variant failed on PMD's unused-parameter gate and the IT ran green against the previous jar** — the constant-pool check is what said so, not the test |
| v-transfer (the transfer service discovers a second time on the same loader) | green by construction, not built: the same loader yields the same set |
| the ledger, on a tree with `AppModules`' second discovery missing from its row | red, naming the site and the loader expression |

### S2 — boot refuses on every arm, lint judges every format

**One defect the slice found that the measurement had not:** a `file-import` route without
`format:` — which [`file-transfers.md`](file-transfers.md) says reads csv — handed the
processor the literal null, and the first upload answered 500 `TQL-LD-2801` for the format
`'null'` (`ImportFormatDefaultIntegrationTest`, red on the S1 tip with exactly that body, green
after: the compile defaults it as the export's is defaulted).

Guards: `ExportDeclarationCompileArmsTest.aFormatNoCodecServesIsRefusedAtCompileOnEveryArm` (a
`query-export`, a `file-export` and a `file-import` naming `fixedwidth` refuse at compile naming
the app, the route and the key, without the excel hint; the same declarations compile against a
set that names the format); `ExportDeclarationBootTest.aFormatNoCodecServesRefusesTheBootNamingTheStepOrTheRoute`
(a job step at registration, a file-export route at compile — the runtime boot);
`JobCommandIntegrationTest.aStepWhoseFormatNoCodecServesIsRefusedBeforeAnyExecutionRowExists`
(`tesseraql job run` exits 2 naming the step, no execution row);
`AppLinterModuleDeclarationTest.aFormatOutsideTheRunsSetWarnsAndInsideItIsSilent` (an export and
an import naming `fixedwidth` draw one 1408 naming the mechanism; a set that carries it is
silent); `StudioServiceHealthCodecsTest` (the health lint with the application's set is silent on
a module format, and warns without it); `CodecDiscoveryLedgerTest` re-rowed (the lint's default
moved to `AppLinter`, the MCP tools discover per application, Studio's default is its own loader;
`StudioSupport` and `ModuleDeclarationRules` no longer discover).

Red on the S1 tip (the test sources copied in alone; the lint and Studio tests do not compile
there — the three-argument `lint` and the four-argument `StudioService` are this slice's):
the arms test red (`file-export` and `file-import` compiled); the boot test red ("Expecting code
to raise a throwable"); the CLI test red (exit 1 after wiring, an execution row recorded); the
import-default test red (500 `TQL-LD-2801`). The head evidence for the lint is row 7 of the
measurement: `0 finding(s)` on `fixedwidth`.

### S3 — one module view, one refusal

`WorkHome.moduleSet(appHome, config)` is the directory choice both the runtime
(`AppModules.load`) and the CLI (`CliModules.moduleCache`) read: the bundled set when it holds a
jar, else `work/modules`. The CLI resolves when the application declares modules and reads that
directory when it does not — on both distributions. `ModuleResolver.resolve` turns the
resolver's `ResolutionException` and `InvalidConfigurationFileException` into `TQL-APP-4221`, a
`UsageRefusal` (exit 2, one line) naming the coordinates, the resolver's first sentence, the BOM
the versions come from and the three ways to supply them.

Guards: `WorkHomeTest.theModuleSetIsTheBundledDirectoryWhenItHoldsAJarElseWorkModules`;
`CliModuleSetTest` (an undeclared `work/modules` jar joins the context loader, and `lint` on
that application is silent on 1408 for the format the jar serves);
`ModuleResolverRefusalTest` (an unresolvable coordinate, offline, is the refusal — not the
resolver's type). Red on the S1 tip: the loader lacked the jar's codec; the resolver's
`NoResolvedResultException` escaped raw. The lint case is red only once S2's rule judges every
format, which is why S3 follows S2.

## Sources

The runs above: `scratchpad/m/` on this machine — `readme-dev.log` (row 1), `m3-*.log`,
`m4-*` (the 5,689-byte PDF is `m4-file.bin`), `m5-dev.log`, `m6-dev.log` + `m6.pdf`,
`m7-dev.log`/`m7b-dev.log`, `m8-host.log`, `m11-dev.log`, `m12-dev.log`, `cds-out.txt`/`cds-err.txt`
(row 14), `gs/dev.log` + `probe-gs.sh` (row 12), `probe-m4.sh`/`cmp-tokens.sh`/`jwt.sh` (row 11);
the copies `stack1`-`stack6` and `gs/myapp`. Not committed.
