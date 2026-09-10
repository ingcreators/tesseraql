# Audit medium leads: verification and remediation plan

Internal planning document. The whole-repo audit of 2026-09-04 left 48 unverified leads. The seven
HIGH ones were verified and remediated on 2026-09-09 (#1287-#1295). This document records the
verification of the 22 MEDIUM ones and the campaign that follows from it.

## Verification — 2026-09-09

Measured against main `9817e2cc9` (0.17.0-SNAPSHOT), 145 commits after the audit base `1e84a5dab`.
Scope: `findings.json` `unverified`, `severity=medium`, minus F92 and F111 (closed by #1294/#1295 —
re-confirmed here: `examples/juchu-kanri-app/README.md:22` now reads `tesseraql dev --app-name`, and
`bin/tesseraql`'s CDS fingerprint splits on `IFS=:` with the mechanism recorded in its own comment).

Method carried over verbatim from `VERIFICATION-high-2026-09-09.md`: per lead one measurer (read
current code, then run a probe), one adversarial refuter (told to break the verdict, with its own
independent probe), one archaeology/worth lens (`git log`/`git log -S` over the window + a
recorded-decision search), then a per-lead adjudicator that runs a decisive probe wherever the
lenses disagree, then a completeness critic per surface family. No audit finder was re-run. No
`mvn` was run by any agent. No tracked file changed.

All 22 carried `reproduce-lens-missing` + `deliberate-lens-missing`; 21 also carried
`worth-lens-missing` (F82 had none of the third). No lens had ever run on any of them.

## Verdicts

| Lead | Verdict at HEAD | Severity now | Touched since base? |
|---|---|---|---|
| F129 identity validity windows vs DB clock | **LIVE (widens)** | **high** (was medium) | untouched |
| F125 Content-Disposition has no RFC 6266 | **LIVE (widens)** | medium | untouched |
| F126 export `timezone:`/`format:` → 500 | **LIVE (widens)** | medium | untouched |
| F127 bundled apps hard-coded English | **LIVE (reframed)** | medium | untouched; class GREW in-window |
| F82 README front-door drift | **LIVE (reframed)** | medium | partially killed |
| F87 extension test never runs | **LIVE (widens)** | medium | untouched |
| F88 `.dockerignore` stale exclusions | **LIVE (reframed)** | medium | untouched |
| F90 `mvn verify` presented as the whole check | **LIVE (reframed)** | medium | untouched |
| F91 extension palette names | **LIVE (widens)** | medium | untouched |
| F96 phantom `--hc-*` tokens | **LIVE (reframed)** | medium | untouched |
| F97 `:target` row rules | **LIVE (reframed)** | medium | untouched |
| F98 hypermedia-ui.md teaches a deleted stand-in | **LIVE (reframed)** | medium | untouched |
| F106 failures logged message-only | **LIVE (reframed)** | medium | half-killed by #1291 |
| F113 `dev --embedded-db` shutdown race | **LIVE** | medium | fixed #1299 |
| F114 CLI usage errors are stack traces | **LIVE (widens)** | medium | untouched |
| F118 `MessageCatalog.live()` per resolution | **LIVE (widens)** | medium | untouched |
| F119 rate-limiter monitor across JDBC claim | **LIVE (reframed)** | medium | untouched |
| F120 `JdbcSessionStore.touched` unbounded | **LIVE (widens)** | medium | untouched |
| F128 CSV export has no BOM option | **LIVE (widens)** | low / `dx` as filed | untouched |
| F99 Studio preview pins `data-theme="dark"` | **LIVE (reframed)** | low (was medium) | untouched |
| F112 `serve` in CLI help text | **LIVE (narrowed)** | low | headline killed |
| F89 development-environment.md wrapper step | **LIVE (reframed)** | low | mechanism killed, page orphaned |

**Nothing is fully dead.** Four leads had their headline killed and a residual survive; none was
`WRONG_AT_BASE`. Severity now: 1 high, 17 medium, 4 low.

One structural fact worth recording: **none of these 22 appears in any of the 12 planned campaigns**
in `remediation.json` — that plan was built from the 79 *confirmed* findings only. Everything here
is unscheduled work.

## What the campaigns killed

- **F112 headline — DEAD, twice.** `#1211` (`a8c34e6f7`) rewrote all six `EmbeddedDbStatus` sites
  and added `EmbeddedDbStatusTest.everyCommandTheProcedurePrintsIsOneTheCliStillHas`; `#1244`
  (`de288f83b`, C7) rewrote `IdentitySchemaCommand`'s `--app` description, regenerated
  `docs/reference-cli.md`, and added the model-derived `DocumentedCommandLineTest`. Verified by
  running the real verb: `embedded-db info` prints `tesseraql dev --app-name …`.
  *Correction to the campaign map: `#1211` is this lead's headline fix; the `serve`→`dev` rename
  itself was `#846` (`96b03c5bb`, 0.15.0).*
- **F82 — partially killed.** `serve` at `README.md:64` by `#1211`/`#1244`; the module table's
  Studio row, the 16-of-25 CLI list, the Maven goal roster and seven absent modules by `#1257`
  (`ee73cd6c3`). Five other measured defects on the same front door survive.
- **F106 — half-killed.** `#1291` (`ac88ae935`, the F104 remediation) rewrote
  `RingTracer$RingSpan.recordError` to store `error.type`/`error.message`, killing the lead's
  second evidence clause. It touched two production files and shipped a route-scoped guard; it
  never reached `JobExecutor`, `JdbcFileTransferService` or `StackRelay`.
- **F89 — mechanism killed, page orphaned.** `#1225` (`a5ffe126e`) deleted
  `scripts/bootstrap-maven-wrapper.sh` and swept `AGENTS.md`, `CONTRIBUTING.md`, `docs/build.md`,
  the PR template, `run-ci-local.sh` and `verify-dev-env.sh` — and missed
  `docs/development-environment.md`, whose only actionable step now exits 127.
- **F127 — the class GREW inside the window.** `#1167` (`414a091f1`) added hard-coded
  `&lsaquo; Prev` / `Next &rsaquo;` to `users/list.html:117-125` while `tql.view.prev`/`next` were
  already bilingual. Nothing lints a framework template for hard-coded text.
- **F120 — the near-miss.** `#1261` (JDBC transaction close-out) worked *inside* `rotate()`, one of
  the only two sites that calls `touched.remove`, added 65 lines to the integration test, and never
  looked at the map's growth.
- **F118 — precedent, not a kill.** `#1259` (module boundary guards) fixed the same defect class
  next door in `DocService.schema()` without reaching this instance.

## Where measurement changed the lead

- **F113's harm was measured, and the "damage is modelled" caveat is discharged.** A real signal to
  a shipped `dev --embedded-db`, with one request inside `pg_sleep` against the embedded database,
  returns HTTP 500 about 60 ms into a 45 s drain budget, with `57P01 FATAL: terminating connection
  due to administrator command` in the log and a `draining 1 in-flight request(s)` line above it —
  the `inFlight > 0` nobody had produced. The same harness on the fix returns 200 after the query's
  own 20 s. Slice 3 also measured that the shape this plan prescribed is a **regression**: with only
  the flag and the hoisted hook, three separate paths leave a live PostgreSQL running.

- **F129 was filed as the smaller half.** The unfiled direction is the security one: with the JVM
  east of the database, a grant end-dated 60 minutes ago **still resolves**. Measured on MySQL 8.4:
  `rows = 1 EXPIRED GRANT STILL RESOLVES`. `docs/access-governance.md:260` makes this predicate the
  *sole* enforcement of expiry ("expiry needs no sweeper — the row stops resolving"), and there is
  no DELETE or UPDATE anywhere targeting an expired window. 39 predicates in 12 files; MariaDB is a
  fourth affected dialect the lead does not name, reached because `jdbc:mariadb://` infers
  `Dialect.MYSQL`. Oracle is safe *because* the SQL uses `current_timestamp` — do not "fix" it
  toward `systimestamp`.
- **F125 is wider than its helper.** `RouteEdge.headers()` (568-572) is the single funnel for every
  route response header and its only validation is a reserved-name drop and a CR/LF throw. Any char
  above U+00FF becomes `0x3f` in **any** header value. `reference-yaml-surface.md:304` documents
  `response.headers` as accepting a per-request `{expression}`, and ~15 `Location:` writers emit
  their target raw with no outbound percent-encoding helper (`UnicodePaths` is inbound only) — a
  `?` inside a URL is a query delimiter, so that case may be worse than the filename one.
- **F126's quiet halves outnumber the loud one.** The filed defect (invalid zone → 500) is the
  least harmful of three. See N2/N3 below.
- **F119's blast radius is right and its headline number was wrong.** See "Retracted" below.
- **F118's amplifier needs two author opt-ins.** The per-row `#{}` at `table.html:52` is gated on
  `selectable`, set only when the list declares bulk `actions:` — which **no shipped example or
  bundled app does**. On a default list page the measured per-row cost is zero; what remains is
  `list.html`'s ~16 fixed calls per render. The verdict stands; the framing "38% of the fragment
  render on the framework's headline page type" is not earned.
- **F98's mechanism is not the one filed.** Re-adding the stand-in to a plain form gives 2 submit
  events but **1 POST** in Chromium 153 (the second `requestSubmit`'s planned navigation replaces
  the first). The reproducible double-*request* is the htmx-wired case (2 POSTs plus a full-page
  navigation away from the swap) — and the doc invites it precisely because it says the kit "only
  re-emits `hc:confirmed`" and never mentions the htmx-verb exemption at `confirm.js:50-52`.
- **F112 is a sweep, not a fix.** Both user-facing sites are correct and guarded; 22 stale `serve`
  lines survive in 4 modules (9 of them in `tesseraql-runtime`/`tesseraql-yaml`, which the lead
  never listed). `ManifestLoader.java:70` must become "`dev`/`host`", not `dev` — `--env` exists on
  both, so the obvious substitution writes a new inaccuracy of the same class.

## Defects surfaced that the audit does not carry

Ranked. The first two are larger than most of the leads that found them.

1. **The framework's Japanese catalog is unreachable by default** (medium-high, i18n).
   `I18nSettings.from` computes the served locale set as `{defaultLocale} ∪ appCatalog.tags()` — the
   *app's* `messages/` dir only — never the built-in catalog merged one line earlier. **None of the
   six bundled system apps ships a `messages/` dir**, so zero-config `supportedTags = [en]` and
   `Accept-Language: ja` negotiates to `null`. Exactly one artefact in the repo declares
   `tesseraql.i18n.locales`. `docs/internationalization.md:54` and `docs/account.md:139` both
   promise the language picker works "with zero configuration"; it renders one option. Candidate
   one-line fix (`catalog.tags()`) is unverified and is a default-behaviour decision.
2. **`export: timezone:`/`locale:` are silently inert for any column without `type:`**, and all four
   shipped example exports are in exactly that shape. Measured: with `timezone: Asia/Tokyo` declared,
   an untyped column renders in the *host* JVM zone (`2026-01-15 22:30` on UTC vs `14:30` on
   `America/Los_Angeles`); the typed control renders `2026-01-16 07:30` on both.
3. **`CatalogLocaleRules.lintExportLocale` is green on its own stated purpose for the scheduled
   case** — it iterates `manifest.routes()` only, so a *job* export step with no `locale:` raises
   nothing, and there is no `timezone:` counterpart at all. `docs/jobs.md:484` says a job's
   `locale:`/`timezone:` are literals with no request to resolve them from, i.e. lint is the entire
   answer for the batch path.
4. **Two more instances of F119's mechanism, on a path with no opt-in key** —
   `JdbcCatalogStore.java:221-251` and `:303-316` hold a monitor across a JDBC borrow + query on the
   per-request catalog path, with no `setQueryTimeout` anywhere in the file.
5. **A grep-invisible main-source file.** `JdbcCatalogStore.java` carries 2 literal NUL bytes inside
   Java string literals, so GNU `grep` classifies it as binary and returns **zero matches**;
   `git grep` and `grep -a` see it. It is the only such file in the repo — and it is why item 4 was
   missed by two lenses. Every `grep`-shaped census in this pass (F106's 12/40/32, F120's 41 CHM
   initializers, F118's call-site inventory) was taken over a corpus containing it.
6. **`tesseraql identity-schema --admin-login … --admin-password-file …`, taught by the product's
   own login page** (`LoginMethods.java:35`), exits 1 with a stack trace because it names no
   `--app`/`--jdbc-url`. Invisible to any model-derived guard: the defect is a *missing* flag, and
   existence guards cannot see absence.
7. **The scaffolder's generated `index.html`** (`AppScaffolder.java:404-415`) teaches three command
   lines in `<code>` elements into **every** `tesseraql new` app, opened in a browser on first run —
   in neither guard's reach. 30 of 130 shipped HTML pages sit outside the guard's hand-written root
   list (`tesseraql-compiler` 13, `tesseraql-identity` 16, `tesseraql-pdf` 1).
8. **`HcMarkupContractTest`'s own javadoc repeats F98's false sentence**, and
   `docs/hc-recipe-alignment.md:264` marks `confirm-action` **Adopted** while citing the page that
   describes the pre-adoption state.
9. **A failed async file export reports FAILED with no reason** — `TransferStatus` has no field for
   one, so F126's better error code does nothing for the async recipe.
10. **`AggregatingMeter.counters/histograms`** are uncapped and nothing enforces label discipline —
    F120's mechanism one hop away, exported on every scrape.

## Retracted / corrected during this pass

- **F119: "the lead's own fix is worse than the bug (31 of 32 false 429s on a healthy cluster)" is
  WRONG and must not appear in a PR.** That figure came from a cold-start latch releasing N requests
  at once. Under steady arrival at a healthy 5 ms claim, the current shape, the lead's shape and the
  recommended bounded-park shape are **indistinguishable** (0% refused at the limit, identical
  beyond it). The real cost of the lead's shape is a *slow* ledger: 4.8% extra refusals at 50 ms.
  Overturning command: `java -DclaimMs=5 -Doffer=100 -Ddur=4 … Steady.java`.
- **F87's proposed fix does not work.** `node --test out/` exits 1 (`MODULE_NOT_FOUND`) on Node
  v22.23.2 — a directory positional is `require()`d, not walked. The correct form is the **quoted**
  glob `node --test 'out/**/*.test.js'`. Unquoted and matching nothing returns exit 0 / `# tests 0`,
  so the guard's count assertion is mandatory, not decoration.
- **F99's severity drops to low.** Its medium rested on "under a configured prefix the preview
  iframe loads no stylesheet at all", re-read from a 2026-08-10 measurement in `docs/base-path.md`
  and never run. `StackRelay.insideTheOriginFence` (`:446`) routes root-level `/assets/**` to the
  surface runtime, which is started unconditionally — so all three linked sheets are served. What
  survives is narrow: an author-written `@{/assets/…}` inside the previewed template resolves to the
  surface runtime, which does not hold the member app's assets.
- **F106's prescribed "silent SLF4J trap" guard variant is unbuildable as red.** At SLF4J 2.0.18
  `AbstractLogger.getThrowableCandidate` extracts a trailing `Throwable` regardless of placeholder
  count, so the 4-placeholder variant prints the stack *and* a literal leftover `{}`. Measured
  against both the CLI's own provider and `slf4j-simple`.
- **The F96/F97 guard as specified false-positives on the kit's own idiom.** 23 `--hc-*` tokens are
  read by the kit with a fallback and never defined — that *is* the extension-point authoring form.
  Use "the kit neither defines nor reads it" (flags exactly the 3 phantoms today). Also strip CSS
  comments from the def-set: 2 of the 1016 definitions in `hc.css` are comment-only.

## Verdicts not to trust without more work

- **F90's required-check picture.** `gh api repos/…/rulesets/17554230 --jq .bypass_actors` was never
  run; an admin bypass or an org-level ruleset would change it.
- **F128's severity.** The mechanical gap is real and measured on three surfaces; the impact chain
  (Excel/CP932 on a ja-JP Windows host) is not executable in this container. Leave it at `dx` as
  filed until someone runs it.
- **F82's `Location:`/`response.headers` sibling of F125** — never driven through a real route.
- **The whole frontend family was specified, not executed.** No guard in F96/F97/F98/F99 was written
  or run as a JUnit test; no stack was booted for F99; the post-fix `:target` appearance is
  *disputed between two adjudicators* (F96 says the kit's dashed outline becomes visible, F97 says
  it is inside `@media (forced-colors: active)` and nothing appears). One of the two CHANGELOG lines
  would be wrong.

## The campaign — slices in severity order

Chosen order: severity first, then reachability. The cheap sweeps are batched at the end so a
reviewer sees the behavioural fixes while the measurement is fresh. Every slice branches from a
fresh `origin/main`. Nothing here is scheduled in `remediation.json` — this is new work.

| # | Slice | Leads | Size | Note |
|---|---|---|---|---|
| 1 | Bind one clock to every identity validity window | F129 | M | The only high. Seed `now` at `IdentityService`, and separately at `ScimGroupService` — the central seam does not reach SCIM. |
| 2 | Release the limiter monitor across the lease claim | F119 | M | SHIPPED #1298. The scope written here was wrong in both directions: of the two `JdbcCatalogStore` sites one is unreachable dead code and the other needs a promise change rather than a lock change, so both are filed instead; `setQueryTimeout` does belong here, because the fix's own liveness depends on it. |
| 3 | An interrupted `dev --embedded-db` stops the database last | F113 | M | SHIPPED #1299. Three pieces, not two, and the two written here do not work alone: the window the library's own hook covers is *inside* `builder.start()`, which no hoisted hook can reach, so the CLI must also choose the data directory and claim the instance before that call. With only the first two, an interrupt during startup — or a gateway port already in use, with no signal at all — leaves a live PostgreSQL behind. Carries a cost of its own, filed: a `kill -9` during the drain now leaks what it used to have already stopped. |
| 4 | A download keeps its name and its bytes | F125, F128 | S+M | One response, two halves. RFC 6266 `filename*` at the single helper; `bom:` on the `export` block. |
| 5 | An export declaration is refused, or it takes effect | F126 | M | Subsumes the two unfiled halves: the inert untyped-column zone, and the routes-only lint gate. |
| 6 | Bound the accumulators by their unit of work | F120, F118 | M+M | Same shape, shared design review: age-swept session map; per-render catalog memo. |
| 7 | A failure leaves its throwable | F106 | M | After slice 2 — both edit `ClusterRateLimiter`. |
| 8 | The framework's own locales are reachable | (unfiled N1), F127 | S+decision | N1 first, or the template swap has no observable effect. |
| 9 | The CLI refuses before it works | F114 | M | Blocked on the exit-code decision below. |
| 10 | Sweeps | F82, F87, F88, F89, F90, F91, F96, F97, F98, F99, F112 | S each | Batched; F96+F97+F98 are one file plus its doc, F89+F90 are the contributor entry points. |

### Decisions this campaign needs before its slices run

1. **The exit code of a CLI pre-flight refusal (slice 9).** Two conventions are live: 51
   hand-written `return 2` sites and `docs/jobs.md:197` publish 2 for "a request that cannot run at
   all", while `UnreachableDatabaseHandlerTest` pins the shaped operator error at 1 and the 0.12.0
   CHANGELOG published it. Recommendation: refusals that mean "nothing ran" use 2; leave the
   unreachable-database shaping at 1.
2. **Whether every app serves `ja` by default (slice 8).** The one-line candidate
   (`catalog.tags()` in place of `appCatalog.tags()`) changes the default served locale set for
   every application. That is a product decision, not a bug fix.
3. **Whether the second README quick start's framework defect is in scope.** F82's slice 2 is not a
   docs fix: the `-Pdist` archive cannot boot the example it tells the reader to run, and the
   codecs cannot be declared in `tesseraql.modules`. It is a `RouteCompiler`/`ViewBinding` change
   and is more severe than every docs lead in this set combined. It should be its own campaign.

### Rules carried into every slice

- Build the broken variant and run the guard against it. Four guards in the HIGH pass were green on
  the very defect they were written for, and nine in the YAML-surface campaign; re-reading never
  caught one. Each slice above names its variants in the verification section.
- `-am` builds dependencies, not dependents: add `tesseraql-docs-reference` to the verify set, or a
  repo-wide guard fails in CI after a green local verify.
- Run every census as `git grep` or `LC_ALL=C grep -a`. One main-source file is invisible to plain
  `grep` (see "Defects surfaced", item 5).
