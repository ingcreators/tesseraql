# Module boundary guards

Implementation design for the tenth campaign of the 2026-09-04 whole-repo audit: the claims the
framework makes *about itself* — which modules exist, which are published, which verbs a binary
carries, which routes are governed, where a default lives — and the fact that almost none of them
are held by anything but review.

Written 2026-09-08, measured against main at `2fce3f1c0` (#1246). This campaign absorbs the
audit's twelfth ("cleanup sweep") whole: three independent ranking lenses found it a duplicate of
eight of these slices plus one finding that had already shipped.

The remedy is the one [duplication consolidation](duplication-consolidation.md) proved and
[YAML surface drift](yaml-surface-drift.md) repeated: **a test holds the claim, then the instance
is corrected** — never the instance alone, because the instance is a symptom of a roster nobody
checks.

## What a re-measurement changed

The audit's remediation plan wrote this campaign on 2026-09-04. Fifteen slices were re-measured
against `2fce3f1c0` with three adversarial lenses each. **169 of the plan's claims did not survive
contact with the code.** Three matter enough to state before anything else:

1. **F11 is not a maintainability finding.** Its reproduce section says "no live behavioral
   defect". That is false. `JobCommand.wire()` threads the SQL timeout from configuration and
   never calls `.resultBounds(…)`, so `tesseraql job run` — and `tesseraql-host job run`, since
   `JobCommand` is on the deployment roster — has never read
   `tesseraql.resultMaterialization.maxRows` or `.onOverflow`. The same job caps differently
   depending on how it is started. Repo-wide, `resultBounds` has two references: its declaration
   and one caller.
2. **`InternalDocsSyncTest` does not prove a registration.** The plan names it as the test that
   proves a new design doc reached both coupled lists. Both of its assertions are set
   *differences*, so a document in **neither** list makes both differences empty and the build
   green. All four states were built and run against this very document before the claim was
   written:

   | This document is in | `mvn verify` | `sync-content.mjs` |
   | --- | --- | --- |
   | neither list | **green** | red — "neither mapped into a nav.mjs section nor excluded" |
   | `nav.mjs` only | red — "add to `ErrorIndex.INTERNAL_DOCS`" | green |
   | `ErrorIndex` only | red — "published by nav.mjs" | green |
   | both | green | green |

   So the registration is guarded only by a script that `mvn verify` never runs. The three edits
   are one atomic change, and the campaign's other slices cannot lean on the Maven build to catch
   a half-registration.
3. **The plan's two headline counts are wrong in both directions.** The BOM omits six, not five;
   the README module map omits eight, not seven.

## The class of defect

| The framework asserts | Where | Enforced by |
| --- | --- | --- |
| These modules exist and are published | `README.md` map, `tesseraql-bom` | Nobody |
| This module carries no dependencies | `AGENTS.md` rules 2-3, `README.md` "(dependency-free)" | Nobody |
| This binary carries these verbs | `docs/reference-cli.md` | The picocli model — for one of the two binaries |
| Every route carries the governed head | `RecipeGovernanceTest` | A nine-row matrix with no MCP read in it |
| This default lives in one place | `SqlDefaults` | Nobody |
| This page lists every key the framework reads | `docs/reference-config.md` | Its own scanner, which is narrower than the code |

Six rows, one shape: the assertion is published, the enforcement is a habit. The campaign's whole
content is turning the middle column into the right column.

## What is broken

| Where | What is wrong | Who feels it |
| --- | --- | --- |
| `JobCommand.wire()` | Builds a `JobExecutor` without `.resultBounds(…)` | A CLI-run or host-run job silently ignores the configured row cap |
| `RouteCompiler.buildMcpResource` / `buildMcpUi` | Hand-written heads; `applyAudit` is never reached | An agent's `resources/read` writes no audit row and no access-log line |
| `RouteTelemetry` 4-arg constructor | Delegates with `accessLog=false` | The mechanism that made both hand-written heads look correct |
| `tesseraql-core/pom.xml` | The one boundary named first in `AGENTS.md` has no enforcer rule | A convenience dependency on core passes every CI job |
| `tesseraql-bom` | Omits `scim`, `saml`, `oidc`, `oauth`, `ops-ui` | `docs/release.md` promises a consumer can resolve any module through the BOM |
| `README.md` module map | Omits eight modules; places Studio inside `tesseraql-runtime` | The row asserts exactly the dependency `no-workshop-on-the-runtime` bans |
| `README.md` cli row | Lists 16 of the 25 subcommands `TesseraqlCli` declares | A hand-typed roster, drifting, one line under the row being fixed |
| `docs/reference-cli.md` | Generated from the developer root only | An operator cannot tell which verbs the binary in the container has |
| `docs/reference-config.md` | "All 236 keys the framework reads" | 26 keys read through a helper are absent from a page that promises completeness |
| `docs/runtime-footprint.md` decision 5 | States one enforcer shape when the tree has two, and has no row for core | An allow-list rule reads as a deviation from the documented pattern rather than the second shape it is |
| `RouteCompiler.formViewForAction` | Re-parses every view per lookup-bearing POST route | `ViewFile` already carries the parsed spec; the catch guards a case the loader filtered |
| `CliModules` | A stack-spanning union loader with a retired-design javadoc | Invites the cross-application leakage decision 28 removed |
| `tesseraql-studio-runtime/pom.xml` | A test-scoped `tesseraql-scim` that narrows a compile dependency | The first `io.tesseraql.scim` import in main fails to compile with no obvious cause |
| Both poms | Present-tense explanations of a Camel that left | The document a reader consults to learn why a jar is on the classpath |
| `CrudScaffolder` | A rule's id and its filename are composed at several sites each | A convention change is a multi-site edit; miss one and the generated app lints red |
| `StudioProviders` | Five comment blocks stranded in the previous method by the #987 split | Each closed by a bare `;`, describing a registration in the next method |
| `DocService.schema()` | An unconditional parse, six times per builder page | The cache exists, is kept fresh, and is used by the neighbouring provider |

## Decisions

### 1 — The BOM predicate and the README predicate are different, and that is the point

The plan wrote one exemption list for both. They cannot share one.

The **BOM** exempts exactly three, each on delivery mechanics and never on "not published":
`tesseraql-bom` (a BOM cannot manage itself), `tesseraql-maven-plugin` (an imported BOM
contributes only `dependencyManagement`; plugin versions come from `pluginManagement` inherited
through `<parent>`, so the entry would be inert — the framework's own scaffolded consumer proves
it, importing the BOM and still pinning `${tesseraql.version}` on the plugin), and
`tesseraql-docs-reference` (the only module in the tree that opts out of publishing, so a managed
version would name a coordinate that is not on Central). Five real additions remain.

The **README** exempts nothing. All 29. The table already names `tesseraql-bom` and
`tesseraql-maven-plugin`, neither a consumable library, so its predicate is demonstrably "a module
in this repository" — and an exemption-free rule is the only one that cannot silently exempt a
module added next year.

`packaging: pom` is deliberately **not** an exemption. A future aggregator goes red and gets a
decision, rather than vanishing from both lists.

### 2 — The module set is read from `<modules>`, never from a directory listing

There are **32** `tesseraql-*/` directories in a working checkout against 29 `<module>` entries:
`tesseraql-camel-components/`, `tesseraql-camel-runtime/` and `tesseraql-camel-spring-runtime/`
survive the Camel removal holding only `target/`. A guard that lists directories skips them by
*accident* — they have no `pom.xml` — not by design, and would pick them up the moment a build
artifact changed shape. `PluginVersionLedgerTest.poms()` derives its set this way; copy its
namespace-aware `parse()` and its anti-vacuity habits, not that helper.

A module named in `<modules>` whose `pom.xml` is missing must **throw**, never `continue`. A skip
turns a mistyped module name into a silent exemption, which is the failure this campaign exists
to close.

### 3 — Every match is delimited on both sides

`tesseraql-studio` is a proper prefix of `tesseraql-studio-runtime` — the only such pair among the
29 names, live in the BOM today and created in the README by this campaign. So the BOM is matched
by DOM element-text equality over direct children (which structurally cannot see an
`<exclusion>`'s artifactId, a grandchild), and the README by the literal backticked token
`` `NAME` ``.

The backtick is not decoration. `tesseraql-cli` occurs **unbackticked** three times in the
quick-start block, so a bare `contains("tesseraql-cli")` stays green with the table row deleted.
No backticked `` `tesseraql-*` `` occurs anywhere outside the `## Modules` section today, which is
what makes the delimited form non-vacuous — and the section is scanned line-by-line from the line
equal to `## Modules` to the next line starting `## `, never by `indexOf("## ", start)`, which
matches the heading itself and yields an empty window.

### 4 — A guard is not written until its broken variant has been built

Nine guards in the previous campaign were **green against the very defect they were written for**.
Not one was visible by re-reading the test source; every one was found by constructing the broken
variant and running the guard against it. Three ways the "evidence" itself lied: a stale surefire
report replayed as a pass, a regeneration run against a jar whose install had failed, and
`-Dtest='A+B'` — whose separator is a comma, so it selects nothing and reports BUILD SUCCESS.

Every slice below therefore carries a list of variants to construct, and two of this campaign's
guards were already caught green before a line was written:

- **The host roster.** `docs/reference-cli.md:8` already renders `` [`host`](#host) `` and the
  other nine with working anchors, and all ten already have their own sections. Any whole-page
  name or anchor assertion **passes on the unfixed page**. The redness can come only from the
  roster section existing, so the extractor must fail loudly when the heading is absent — a
  `Math.max(0, indexOf)` or an `orElse(wholePage)` silently re-points the assertion at the table
  of contents.
- **`everyHostVerbIsADeveloperVerb`**, which the plan named as F8's closing guard, is green before
  and after: the host roster is a subset of the developer roster by construction.

Where the honest answer is that nothing can be red, the slice says so rather than inventing a
test. Four slices are pure refactors and are shipped as such.

### 5 — The applier is the only way a recipe gets a head, and the convenience constructor goes

`buildMcpResource` and `buildMcpUi` are replaced by `applyCommonGovernance`. That closes the
instance. What closes the *class* is deleting the 4-arg `RouteTelemetry` constructor, which
delegates with `accessLog=false`: it is the mechanism that let a hand-written head compile and
look right, and after the change it has no main-tree caller.

This is non-negotiable for a measured reason. `RecipeGovernanceTest` records
`step.getClass().getSimpleName()` and cannot observe the access-log flag at all, so the matrix
rows close the audit half of F9 and **nothing** closes the access-log half. The deletion is the
guard.

The change is behaviour-identical for every legal MCP document, but **not for the plan's stated
reason**. The plan argues from `csrfEnforced`; in fact `BrowserAuthenticator` throws on an empty
session and `applySecurity` emits authenticate before csrf, so an MCP `auth: browser` read is
refused *today*. What changes is the compiled shape only: an unreachable csrf step. No
security-posture claim belongs in the CHANGELOG or the PR body.

### 6 — There are two enforcer shapes, and both registers already exist

A tempting reading of the tree is that `docs/runtime-footprint.md` decision 5 lists three rules
against the seven the poms carry, and is therefore four rows stale. It is not. The other four are
registered in [module channel](module-channel.md)'s own Guards table, which the campaign that
added them wrote. All seven are recorded; the split is by campaign, which is how this repo has
always kept its registers.

What *is* wrong is narrower and worth stating precisely, because it is the reason core's rule
looks like a deviation. Decision 5 describes one shape — coordinates that must not arrive through
anyone's closure, searched transitively — and the tree has two. The second is an allow-list naming
everything a module may declare, searched on direct declarations only, because an allow-list
already refuses the parent of any transitive. `oidc`, `saml`, `scim` and now `core` are that
shape, and `searchTransitive=false` on them is load-bearing rather than drift:
`BannedDependenciesBase` defaults the field to true, so deleting the line silently turns the walk
on.

### 7 — A default lives on the primitive; the config reader only defers to it

The timeout precedent this campaign is named after splits in two: the value lives on
`SqlStatement` and `SqlDefaults` reads the key and defers with `.orElse(…)`. The row cap and the
overflow policy mirror it exactly, which is what makes the constants reachable from
`tesseraql-operations` — where `JobExecutor`'s field initializers restate them — without
`tesseraql-yaml` on that path.

No clamp on the row cap. Unlike the timeout, a negative ceiling is the live "unbounded" sentinel
that three consumers honour and that `export.maxRows:` documents as its opt-out.

### 8 — F81 splits by mechanism, and only its helper class ships here

The config index misses 115 key-shaped literals over its own corpus. They are not one defect:
roughly 35 are Maven mojo `@Parameter` properties, roughly 28 are Micrometer metric and span
names, and a further group are prefix fragments composed at runtime. **Widening the scanner
naively would pour metric names into the configuration reference.**

Only the helper-read class — 26 keys the framework genuinely reads through a wrapper, zero false
positives, verified three times — ships in this campaign. The composed-prefix class needs a
different instrument and is filed, not fixed.

## Slices

| # | Slice | Size | Closes |
| --- | --- | --- | --- |
| 1 | This document, registered in both coupled lists | S | — |
| 2 | `build(core)`: the dependency-free boundary is an enforcer rule, and the guard register learns its own guards | S | F5 |
| 3 | `docs(modules)`: every reactor module on the README map, every publishable one in the BOM, held by `PublishedModuleLedgerTest` | M | F1, F4 |
| 4 | `fix(views)`: the companion scan reads the manifest's parsed view | S | F10 |
| 5 | `fix(compiler)`: MCP resource and UI routes carry the governed head | M | F9 |
| 6 | `docs(governance)`: Matrix 1 says what the compiler does | S | — |
| 7 | `docs(reference)`: the deployment host's verbs are generated too | M | F8 |
| 8 | `fix(batch)`: a CLI-run job honours the row cap, resolved once like its timeout | M | F11 + an unfiled live defect |
| 9 | `refactor(cli)`: the union module loader goes | S | F2 |
| 10 | `build(studio-runtime)`: the surface guard gets scim from the runtime | S | F3 |
| 11 | `build`: the present tense stops describing a Camel that left | S | F6 |
| 12 | `refactor(scaffold)`: a rule's id and its file have one producer | S | F14 |
| 13 | `refactor(studio)`: each provider comment rejoins its registration | S | F16 |
| 14 | `fix(studio)`: the schema overlay is read once per file version | M | F15 |
| 15 | `fix(docs)`: the config index sees a key read through a helper | S | F81 (helper class) |

Merge order: 1 → {2, 9, 10, 11, 12 in parallel} → 4 → 5 → 6 → 7 → 8 → 3 → 13 → 14 → 15.

The edges that are not arbitrary: slices 4, 5 and 8 touch three disjoint regions of
`RouteCompiler.java` and rebase rather than conflict, but 8 must be the last of them to move
`docs/reference-config.md`; 7 and 8 both regenerate, and every regeneration rewrites all four
generated pages, so they are never parallel; 13 precedes 14 to keep 14's diff to the
`DocService` call sites; and 5, 7, 8 and 14 all append under the same CHANGELOG subheading.

## Guards

| Guard | Closes | New? |
| --- | --- | --- |
| `core-is-dependency-free` enforcer rule | F5 | yes |
| `PublishedModuleLedgerTest` | F1, F4 | yes |
| Two `GOVERNED_ROUTES` rows + deletion of the 4-arg `RouteTelemetry` constructor | F9 | rows yes, deletion yes |
| `CliReferenceHostRosterTest` | F8 | yes |
| `SqlDefaultsReadLedgerTest` | F11 | yes |
| `ConfigKeyLedgerTest` | F81 (helper class) | yes |
| `DocServiceTest` identity + `StudioIntegrationTest` staleness case | F15 | yes |

Seven guards, none of which exists today. That density is why this campaign was ranked third of
the audit's twelve rather than by the severity of what it fixes.

## Deliberately not in this design

- **The composed-prefix half of F81.** A different instrument; filed.
- **The 73 undescribed schema properties** in the config, tests and calendars schemas that
  `SchemaDescriptionCoverageTest`'s nine-file list is blind to. Real, and 73 sentences of prose.
- **A JSON Schema validator.** Nothing in the repo validates a shipped document against a shipped
  schema. Named again here because two campaigns have now wanted it.

## Filed, not fixed

Three live defects found while measuring, each a second behaviour change that does not belong in
a slice scoped to something else:

1. **The file-export download route carries no head.** `pipelines.pipeline(routeId + ".file")`
   receives `applySecurity` and a download processor and nothing else — no telemetry, audit,
   tenancy or locale. It sits eight lines above `mountTransferStatus`, whose comment describes
   exactly this defect as already fixed for the `.status` route. It is the same class as F9 and
   the guard extension is free, because its pipeline id is compiled today.
2. **`response.session.rotate` is silently dropped on an MCP resource or UI route.**
   `requireRotationHonoured` is called from two builders and none of the four MCP builders calls
   `applySessionRotation`, so the key passes the recipe switch and does nothing — the
   silent-tolerance shape this repo has swept twice.
3. **The `## Unreleased` subheading order** is Added/Fixed/Changed while every released section is
   Added/Changed/Fixed. Not worth a PR of its own, and reordering it as a side effect would
   conflict with every appending PR in this campaign.

## Open question

**What CHANGELOG bar does this campaign use?** Nothing in the build reads `CHANGELOG.md` —
`docs/release.md` says so outright — so no guard can ever exist for it, and practice has diverged:
the release-hardening campaign touched the file in seven of eight PRs, and the YAML-surface
campaign that followed wrote zero lines across twelve, including a new declared input type and a
new CLI flag.

The bar this campaign uses, absent a decision: an entry for a behaviour change a deployment can
observe, none for a refactor. That is slices 3, 5, 7, 8 and 14.
