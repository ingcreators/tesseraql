# Audit low leads and the completeness critic: verification and remediation plan

Internal planning document. The whole-repo audit of 2026-09-04 left 48 unverified leads and a
completeness critic that was never run. The seven HIGH leads were verified and remediated on
2026-09-09 (#1287-#1295), the 22 MEDIUM ones on 2026-09-09 to 2026-09-14
([`audit-medium-leads.md`](audit-medium-leads.md)). This document records three things measured on
2026-09-15 against the same tree: the 17 LOW leads, every "Filed, not fixed" bullet of the seven
campaign records that shipped since the audit (68 bullets, 121 atoms once the compound bullets
were split), and the critic's first run — six finder areas no dimension had owned, 43 findings,
each verified with the audit's own three lenses — all 43 confirmed, 8 of them high.

## Verification — 2026-09-15

Measured against main `1e81041f4` (0.18.0-SNAPSHOT), 209 commits (PRs #1148-#1357) after the
audit base `1e84a5dab`. Nothing tracked changed; no `mvn` build ran. Probes ran the CLI from the
installed 0.18.0-SNAPSHOT jars (main as of 07:24 UTC that day, before #1354-#1357, which touched
only `.github/`, the extension, docs and `SymbolsCommand` — HEAD-equivalent for every class cited
here), scratch Java sources on the same classpath, and Docker PostgreSQL / SQL Server / Oracle
containers where a database decided a verdict. Working files: `work/audit-low-leads-2026-09-15/`
(gitignored): `POOL.md` (the atomized pool), `measure/<group>/{measurer,archaeology,refuter,verdict}.md`,
`confirm/<group>.md`, `critic/`, `probes/`.

**The pool.** The 17 `severity=low` entries of `findings.json`'s `unverified` array, all carrying
the three `*-lens-missing` flags, plus the "Filed, not fixed" sections of `export-hygiene.md`
(26 bullets), `edge-hygiene.md` (8), `export-declarations.md` (12), `download-name-and-bytes.md`
(7), `codec-discovery.md` (5), `temporal-semantics.md` (4) and `editor-named-sources.md` (6). The
bullets in `export-declarations.md:1432-1504` and `download-name-and-bytes.md:874-928` were
ledgers of ledgers — one bullet carried twenty-two claims — so every bullet was split one atom per
claim by hand before any agent read it: **121 atoms**. Atom ids name the record (XH export-hygiene,
EH edge-hygiene, XD export-declarations, DN download-name-and-bytes, CD codec-discovery, TS
temporal-semantics, EN editor-named-sources; F the audit's own). 78 atoms were filed as open and
were measured; 23 were filed as fixed by a later slice and were only confirmed; 18 the filing itself
calls not a defect, by design, a product decision, unmeasurable or a design question and are carried
as notes; 2 are duplicates (XH-25 = XH-11, DN-02b = EH-06).

**Method**, inherited from the two earlier passes and given probes this time: the 78 open atoms in
18 surface groups, each with one measurer (read the code at HEAD, run the deciding probe), one
archaeology/worth lens (the window's `git log`, the recorded-decision search, worth-a-PR), an
adversarial refuter that reads the measurer's file and is told to break every verdict with its own
probe, then an adjudicator that reads all three and runs the decisive probe wherever two lenses
disagree. One archaeology confirmer per target campaign for the 23 filed-as-fixed atoms. The
critic round is the original audit workflow's own critic stage: it read the 17 finders' coverage
notes, the 129 known findings and the 209 commits, named six areas, and each area's finder ran
under the original finder preamble; every finding then went through the original three lenses
(reproduce, deliberate, worth) plus a probe-running adjudicator wherever a lens was uncertain or
the lenses disagreed with each other.

Two incidents shaped what the tables below can claim. An API safeguard refused every lens agent of
the critic's first run (the prompt asked for "your full reasoning"; rephrased to "the lines you
read, every probe and its output, and your verdict" it passed) — so the 43 findings were first
judged by one adjudicator each, then re-verified with the three lenses. A session usage limit then
cut that re-verification and two measurement adjudicators short; the lenses were re-run for the
findings that lacked them, and the two adjudicators' `verdict.md` files, written before the failed
return, were read by hand. Every finding below carries the three lenses; the second-pass
adjudications are named where they ran.

## Verdicts — the 78 measured atoms

Vocabulary: LIVE (as filed), WIDENED (the class is wider than filed), NARROWED (part killed; the
residue is named), REFRAMED (real, different mechanism or consequence), DEAD (killed since the
filing, by the named PR), NOT A DEFECT. Severity on the audit's scale. All 78 carry three lenses
and an adjudicator; XD-07j, XD-07l, XD-09b, DN-01c, XD-07k, EH-03, EH-07 and EH-08 carry the
adjudicator's file rather than its structured return.

| Atom | Filed as | Verdict | Now | Touched / killed | What measurement found |
|---|---|---|---|---|---|
| XH-01 | `job run` before the first boot poisons the framework schema | **WIDENED** | **high** | #1154 widened it (V14 into the bootstrap); mechanism untouched | Reproduced a fourth time: `job run` on a fresh database exits 0 and creates nine `tql_*` tables with no `tql_schema_history__operations`; the next `dev` baselines at 0 and dies on V3 (42701), every boot after. Not one script: **eleven of the fourteen** common operations scripts fail under Flyway on a bootstrapped database (V5:23 and V12:45-46 are bare statements after `if not exists` tables); Oracle V1 and SQL Server V9 too. Every wiring verb (`cancel`, `run`, `rerun`) triggers it; there is no product recovery, and the naive hand repair makes the database unbootable a second way. `FrameworkMigrations.java:17-20`'s "the scripts stay idempotent" was true at `f4aea0d9b` (V1 only) and false since #484. Fix S: `JobCommand.wire` calls `FrameworkMigrations.migrateOperations` before the first `ensureSchema()`; do NOT edit the scripts (checksum pin, `framework-datasource.md:84-88`). |
| XD-09c | `dev` loads manifests outside its `TqlException` catch | DEAD | none | #1349 + #1351 (10a) | Probed on a stack and, decisively, on an install root (where the filed `:207` site is the first parse): one sentence, exit 2, no trace. |
| F115 | `test --fail-on-regression` exits 2, picocli's usage code | **WIDENED** | medium | #1338 (decision 10) published the vocabulary it now contradicts | `ExitCodes` declares 2 = "nothing ran", 3 = "did not run by policy", printed by `--help` and generated into `reference-cli.md`, "for a script that retries on 1 and fixes its arguments on 2" — while `testing.md:306` and `vscode-extension.md:262` publish 2 as the regression gate. Fix S: return 3 and widen `SKIPPED`'s sentence (decision 10b); 1 is the acceptable alternative; 2 is the one answer decision 10 rules out. |
| F83 | `roadmap.md` calls Central/Homebrew/Scoop open | NARROWED | low | none; #1229 re-affirmed Central | The live stale lines are `:436-437`, `:1209-1211`, `:1458` and `docs-site.md:7-8`; the `:21-42` gap table is a dated 0.1.0 snapshot, not live status. Central's first real publish was 0.7.1, not 0.7.0. |
| F84 | `upgrading.md` tells package-manager users to re-download | LIVE | low | #1229, #1244, #1348 nearby | Two bullets first at `upgrading.md:38` (`brew upgrade`, `scoop update`); `UpdateNotifier.java:26-28` says the same. |
| F85 | `docs-site.md` names a sidebar taxonomy the site no longer has | LIVE | low | 17 `nav.mjs` commits in the window only appended EXCLUDED entries | Replace `:39-40` with a pointer to `nav.mjs` `SECTIONS` and `documentation-ia.md`; the site has ten sections, `documentation-ia.md:76` says seven over an eight-row table. |
| F122 | `lookups.md` claims request-scoped enrichment memoization | LIVE | low | none on the path | `KeyedReference.enrich:189-245` and `EnrichProcessor:45-55` have no branch on which a memo could exist. Docs S (strike `:147-148` and the `:694` ledger item) or code M (an exchange-scoped map). |
| F116 | CLI reference header claims every verb has a Maven goal | WIDENED | low | six PRs edited around the sentence | The parity on record is goal → verb (`app-developer-distribution.md:78`); reworded that way it is true for every goal but `release-evidence`. `getting-started.md:101` is hand-typed and also wrong. `ReadmeLedgerTest` already scans `@Mojo` names — reuse it. |
| XD-04c | `lookups.md` decision 12's Export row / #742's CHANGELOG framing | WIDENED | medium | #1296 (fact), #1306 (narrowed, deferred) | No export surface renders a catalog name: the only readers of `codes` are the HTML renderer and `ViewBinding`; `code-catalogs.md:122-130` (published) implies `${codes.x.of(...)}` in a pdf template — probed: HTTP 500, OGNL `source is null`, TQL-LD-2831. The #742 entry is in the 0.14.0 section; record the supersession under Unreleased. |
| DN-01e | reconcile `unicode-identifiers.md:160` with `ApplicationName.java:59` | DEAD | none | #1329 (R3) | The sentence at HEAD says what the code does; `download-name-and-bytes.md:874-876` already annotates it complete. |
| DN-03d | `defs-v1.schema.json:97` description split | LIVE | medium | schema edited around the string by eleven PRs | The `$ref`-shared `export.filename` description says `{dotted.path}` interpolates — true for a job step (`ExportStepRunner:55`), false for a route (`RouteCompiler:1660` passes it verbatim; lint judges `{key}` and the extension only). `route-v1:531/:577`'s "a literal or a bindable path" for `response.file/stream.filename` is false too. Hover text, a published reference row, and a silent wrong filename on a user path. Split per arm; regenerate; the lint companion is a separate design call. |
| XD-07a | `timezone: principal.subject` answers 400 blaming the caller | WIDENED | medium | #1306/#1307 built the mechanism | Wider in consequence: `principal.zoneinfo` (a forgotten `claim.`) is lint-, boot- and request-silent and renders at the platform zone. `principal.claims.<name>` works undocumented today; the fix (require `principal.claim.<non-empty>`, one arm) retires it — record that. |
| XD-07b | an input `default:` is judged as the caller's | WIDENED | medium | M1 wrote the line; #1307 the judge | The class is "a default is judged by nobody": `default: abc` on `type: number` → lint 0, boot UP, 200 on omission while `?n=abc` is 400; bound into a numeric compare → 500 on every request omitting it. A default also replaces a BLANK caller value (`InputBinder:137`), so decision 29's "unset → rung 3" is untrue with a `default:`. Fix M: coerce+validate the default at `InputBinder` construction plus a lint twin; the filed instance alone is S. |
| XD-07d | `after:` on a query-export is lint-silent, boot-3101 | LIVE | low | decision 26 pinned the silence | A predicate arm (`surface == QUERY_EXPORT && after != null → INVALID`) retires the compiler's own throw; take it inside the lint-hygiene sweep, never alone. |
| XD-07e | a WARNING for `{key}` in `push.as:` | DEAD | none | #1316 (P7) | Shipped as an ERROR (TQL-YAML-1042), stronger than filed; the ledger line at `:1490` was never struck. |
| XD-07i | SEC-4048 `[]`/`""`/`[""]` and PolicyCodes lint/boot drift | WIDENED | medium | nothing in the window | 4048: `[]`/`""`/`[""]`/`[" "]` all lint clean; `[]`/`""` boot-refuse, `[""]` boots and 401s every IdP token (the CLI's own mint emits `aud: ""`, so a local smoke test passes). Policy, the wider half: an IN-namespace two-key rule `{role: ADMIN, permission: <app>.x}` lints clean, boots, and applies the role only — the permission holder is 403. Fix S each: one `audiences()` predicate both sides call; one two-key refusal both sides call. |
| EH-06 | literal-value lints: `contentType` charset, OWS in `location:` | LIVE | medium | #1323 (E3) beside it | `charset=Shift_JIS` over UTF-8 bytes: lint 0, 200, `Buffer.buffer(String)` is always UTF-8 — ERROR or honour, never WARNING. New: a LEADING OWS in `location:` is a relative redirect with no app prefix (`BasePaths.join:42` skips a value not starting with `/`), not the `%20` cosmetic decision 13 recorded for the trailing case. |
| TS-02 | constraint keys on a `result:` entry accepted, not applied | DEAD | none | #1332 (decision 24) | TQL-YAML-1064 at lint AND boot — both altitudes, one code; the T3 ledger line was never struck. |
| XH-22 | `lint` crashes with a stack trace on `as: {nope}` | NARROWED | medium | #1351 killed the trace and exit 1 | Survives as the filing's own origin measurement said: the whole lint aborts on one unparseable document — `--format json` prints **0 bytes** with exit 2; the extension words the empty stdout as "may predate lint --format json" and keeps a stale Problems panel. Fix S: catch the loader's `TqlException` in `AppLinter.lint` and return one ERROR finding (the `ViewRules:320-326` shape). |
| XD-07c | a file-import route without `import:` NPEs before the predicate | WIDENED | medium | #1306 shaped the export twin; #1346 moved the NPE | Three lint-green null paths, all reproduced under `dev` and `host`: `import:` absent, `steps:` absent, a step with `sql: {mode: update}` and no `file:` (message-less `Path.resolve(null)`). The export twin got TQL-YAML-1041 in #1306. Fix S at `buildFileImport` + the lint rows. |
| XD-07g | the lint crash on a header-less document | NARROWED | medium | #1351 | XH-22's fix covers it; guard fixtures should be the empty route and the empty job. |
| XD-07f | `conditions.zone` and bad-cron unshaped boot refusals | LIVE | medium | none in the window | `Asia/Tokio` → "Failed to start TesseraQL runtime: Unknown time-zone ID", exit 1, 26 frames, naming neither key nor app; a five-field cron → uncoded `IllegalArgumentException` while `fixedDelay` beside it is TQL-YAML-1301. No lint reads either. Medium for the cron half. Quartz is runtime-only: a cron lint in `tesseraql-yaml` needs a dependency decision; sequence with F60. |
| XH-10 | Studio's export preview writes outside `ExportWrite` | **REFRAMED** | medium | #1315, #1346 | The `ExportWrite` dispatch buys a one-document preview nothing. The real defect on the same lines: the `PdfRender` seam carries `main.rows` only and passes `Map.of()` as values, while the route passes every declared source — the documented header-and-lines template (`file-transfers.md:200-225`) throws TQL-LD-2831 in the preview and renders on the route. Fix M: widen the seam's arguments. |
| XH-19 | the preview's columns are locale- and zone-blind | LIVE | low | #1315, #1327 | Thread `FileDefaults.of(config)` into the `PdfRender` lambda — `literal()` first, or a source expression reaches the codec as a locale tag. |
| XD-10 | `renderExportPdf` builds its spec without `withFormatting` | DEAD | none | #1315 (same day as the filing) | Residue is XH-19. |
| DN-06f | Studio's `listTables` is schema-blind on PostgreSQL | **WIDENED** | **high** | branch from #895; #1301/#1314/#1327 nearby | `getTables(catalog, null, …)`, `columnTypes` and `primaryKey` all pass a null schema, and `primaryKey` keeps the LAST row per `KEY_SEQ`; two same-named properly keyed tables with PostgreSQL's default `<table>_pkey` constraint name hand the browser the OTHER schema's key. With the row editor on: `{ref: ACME}` **commits a three-row overwrite while reporting a rejection** — `updateRow` checks `affected != 1` AFTER an auto-committed `executeUpdate`. The multi-schema shared-database topology `cli-surface.md` §4b recommends triggers it. Fix S: pass `connection.getSchema()`; make `updateRow` transactional. |
| DN-06g | the Studio note carries the driver's multi-line message | WIDENED | low | #1301 | Fold at the note (`replaceAll("\\R+", " ")`); the browse-page error model shares the text. Pair with F108's log line. |
| F108 | Studio export answers 200 with the failure in the CSV | NARROWED | low | #1301 decision 1 | The audit's fix (rethrow as a coded 4xx) reverses decision 1 and turns three shipped guards red; only a `LOG.warn` at the two catches survives. |
| EN-01 | bindable paths (`model:`, `body:`, `payload:`) | LIVE | low | #1356 filed it | 59 source-rooted and 28 `steps.`-rooted bindable paths in the tree against 16 view `source:` scalars; `params:` and `location:` positions are the same class by value shape. Worth only as one slice with EN-02 (M). |
| EN-02 | `steps.<id>` in `enrich: source:`, `spool:`, `attach:`, `file:` | NARROWED | low | #1356 decision 2 | A route cannot carry `enrich: source: steps.<id>` (TQL-YAML-1046); the step form is a job's `pipeline[]`, intra-document — no contract change needed. |
| EN-03 | embedded views read the embedding route's sources | LIVE | low | #1356 decision 4 | Contract-side: `routes[].embeds` from the parsed `ViewSpec`; `viewReferenceAt` also matches a route's `view:`, so an extension-side scan mistakes a binding for an embedding. |
| EN-04 | the contract still does not carry views | LIVE | low, **worth no** | #642 (the scan), #1356 decision 1 | The scan mirrors `ManifestLoader.loadViews` on every shipped app. New fact: a BOM-prefixed `id:` line diverges (the scan derives the file name, the loader reads the id) — one-line fix in `viewIdInfoOf`. Counter-fact: `SymbolsCommand` goes dark on a duplicate view id (TQL-VIEW-3315 empties routes/workflows/jobs) while the scan survives the mid-edit state — **a contract array retires nothing; the scan must remain.** |
| EN-05 | a flow-form `sources: { main: … }` yields no line | NARROWED | low, worth no | #303 (pre-window), #1357 | `routes()` can fall back to the `sources:` line; the multi-line flow map already resolves by accident of indentation, so the one-line spelling is the only red case. |
| F100 | the filter dialog has no accessible name | NARROWED | low | none | AX name is `""` after `showModal()`, `"Filters"` with the two attributes. Narrowed: the kit's dialog page accepts "title before the first focusable" as the alternative, which the dialog satisfies (axe: best-practice). A class-wide test must exempt `hc-command-dialog` (the kit's own recipe). |
| F101 | six standalone pages hand-roll centering | NARROWED | low | #1342/#1338 strings only | Either `hc-container` on all six or one line in `console-ux-refresh.md` recording that the standalone pages keep the inline frame — a decision, not a fix. |
| F102 | an hc-internal override and three bootstrap behaviours have no brief | WIDENED | low | #1341 adjacent; #1094 the widening | `data-tql-open-dialog` is compiler markup contract (`list.html:43,54`), not Studio glue. Fix: briefs 13-15 in `hc-briefs.md` with a "stand-in to retire" pointer each; guard: every `data-tql-*` selector in `tesseraql.js` is named in a doc. |
| F103 | `tesseraql.css` copies `.hc-fill` "because table.html cannot carry it" | REFRAMED | low | #1097 severed the chain the rule belongs to | The audit's class swap regresses phones (`hc-fill` is unconditional; the 70vh cap below 60rem disappears). The comment is wrong; the constraint is real and its chain is already broken (unfiled 33). Rewrite the comment; a class swap needs a `list-surface.md` decision first. |
| XH-08 | 2855's message quotes a value fragment | LIVE | none | #1311 wrote the filing | The class is BigDecimal (`toString()` through `writeUTF`), not long text: `1E+70000` spools in 8 chars, `10^70000` does not. A spool-format change under a shared `db`/`blob` temp store has a rolling-upgrade consequence — marginal. |
| XH-12 | 2831's absolute app-home path in a recorded reason | REFRAMED | low | #1312-#1317 | Spell the template route-relative in both codecs; 2837 should relativize against `spec.resources()`, not `getFileName()`. |
| XH-26 | `rowCount` 0 on every failed export | WIDENED | low | #1317 (filed) | The live half is RUNNING: every export write of `row_count` is inside the extraction's transaction, so the card prints `0 rows` for the whole run (100 s, polled every 2 s) and the card's backoff never engages. Fix M: a progress flush on the import's cadence through its own connection. |
| XH-04 | case-insensitive-filesystem collision in a split bundle | LIVE | low | #1310 decision 15 | A decision on the split-export line first: refuse with 2857 (flips the pinned test) or suffix the later entry. The NFC/NFD "collision" is not one (the mark is folded before keying) — that is unfiled 31. |
| XH-21 | the hard-coded excel hint in TQL-LD-2801 | DEAD | none | #1346 (codec-discovery S2) | Three assertions already pin the absent text. |
| DN-03b | `FileCodecs.discover` last-put-wins | NOT A DEFECT | none | #1316, #1345, #1346 | Decided twice with the run that proved a refusal fails every app carrying the module; "silently" died with #1316's WARN. An optional lint for a built-in format resolving outside `io.tesseraql.operations`. |
| DN-06c | the two BOM sniffers | LIVE | none | #1289 created the duplication | Touch only when one of the two files is open. |
| F107 | wrapped exceptions drop their cause | WIDENED | medium | #1291, #1312 | 26 `SQLException` catch blocks wrap without the cause (`JdbcFileTransferService` ×15). Decisive: `tesseraql schema` on a refused connection answers exit 2 with a coded line while `identity-schema`/`migrate` answer the operator message at exit 1 — `SchemaGenerator:44-50` drops the cause `CliExceptionHandler`'s walk needs, contradicting `cli-surface.md` decision 10. Medium is that one site; the sweep is low. |
| F109 | `OpenTelemetryMeter` builds an instrument per call | LIVE | low | none | Two `computeIfAbsent` maps mirroring `AggregatingMeter`; `counter(x) == counter(x)` is the wrong guard (fresh lambda each call) — spy the OTel `Meter`. |
| XD-07h | TQL-YAML-1409 defined twice | NARROWED | low | #924's ANTICIPATED entry | Delete `RouteRules:38`, raise `PolicyCodes.TEMPLATE_UNRESOLVABLE`, remove the ANTICIPATED entry the existing test demands. |
| DN-06b | `ErrorIndex` indexes a literal code in a comment | WIDENED | medium | #1243/#1292 touched `ErrorIndex` elsewhere | `collect` adds provenance unconditionally while only `meaningAt` checks comments/prose; the published reference lists `ErrorIndex.java` itself as a raise site of TQL-LD-2810 and TQL-ROUTE-3100, and seven real raise sites sit behind "+N more". Two censuses: 97 codes, 110 false published links. Fix S; regenerate. |
| XH-11 | the transfer span is invisible to the ops traces API | LIVE | medium | mechanism #692; `OpsScope` untouched since 2026-08-22 | `compose` = `app != null && …`, so a null-app root fails every caller; `OpsDashboard.java:412-418`'s wildcard promise was true before #692. `traceMetrics()` is unscoped and feeds the overview and TQL-OPS-9001, so the overview counts an errored trace no scope can list. `OpsDashboardTest:145-147` asserts the wildcard with a hand-built `app -> true` — green on the defect. Fix S plus one policy decision on unattributed roots. |
| F121 | `matches()` caches compiled regexes by runtime value, unbounded | LIVE | low | #857 cache, unchanged | 20,000 distinct masks → 20,000 cached Patterns; a bad literal is a raw `PatternSyntaxException`; `(.*a){14}` on 29 chars is 2 s on JDK 25 (the textbook exponential shapes are defused). Compile a literal once at parse time, never cache a non-literal. |
| DN-01b | `StackRelay:509`'s raw header write | DEAD | none | #1329 decision 3 | `uriLiteral(rootTarget)` with a guard; the client-supplied query residue is unfiled 40. |
| DN-02a | asset/SSE bypass checks and the MCP transport write header values unrefused | WIDENED | medium | #1323 (E3) covers values | Value half dead; the MCP third was wrong at base (0 hits for `responseHeaders` in `tesseraql-mcp`). Survives wider: **no check anywhere reads a header NAME** — `"X Space": typo` in `security.responseHeaders` lints clean, boots, and hangs every asset, SSE and download response (Vert.x validates the name inside `runOnContext`). Content-Length is refused in YAML by lint, not at boot. Fix S: E3's shape extended to the name. |
| DN-02c | `Content-Disposition` under `headers:` mangled | LIVE | low | #1303, #1323 | One lint row keyed on `filename=` with a placeholder — a blanket rule would flag the only `inline` spelling. |
| DN-02d | the `HX-Trigger` toast escape | DEAD | none | #1323 | `constrainedAscii()`; guarded. |
| DN-02e | an IDN host in an absolute `location:`; `[`/`]` in a path | NARROWED | low, worth no | #1303 | Only when the trigger fires; `PercentEncodingTest` pins `[` unchanged by decision 7 — keep the IPv6 row beside any punycode row. |
| XD-07j | the header-fed input (`RequestBinder.rawValue:348`) | **WIDENED** | **high** | mechanism #966 (pre-base); untouched | Every declared input on every route kind falls back to a same-named request header: `host` silently satisfies `required: true`, `cookie` binds the caller's whole `Cookie` header into SQL, a `required: true` boolean named `accept` is never refused, and **`helpdesk-app/web/tickets/get.yml:9`'s `priority: {enum: [low, normal, high]}` answers 400 to every Chrome/Firefox navigation over h2** (`Priority: u=0, i`; h2 is what the documented Cloudflare → kamal-proxy deployment puts in front — `dev` on HTTP/1.1 never reproduces it). And **109 shipped routes depend on the fallback**: the ops-console (12) and Studio (97) shells declare `input: Cookie:` and forward `params.Cookie` on delegated calls; `OpsShellIntegrationTest` Order 3/4 go red on the naive fix. `vertx-native.md:305-307` says the read was removed. Fix M, design-shaped: a declared header source (`header.Cookie`) for the shells first, then `return null` at `:348`, recorded as a breaking change. |
| XD-07l | `body.*` on a GET | NARROWED | low | #1322 (E2) killed the runtime half | A lint arm for `body.` sources on a GET; a companion row for `body.<name>` naming an undeclared input (unfiled 43). |
| XD-09b | HTTP/1.0 mid-body close reads as a complete file | NARROWED | medium | records #1305/#1320; #1335 beside it | Vert.x ignores `setChunked` on an HTTP/1.0 response, so a mid-body failure is EOF-delimited and indistinguishable from completion. Not a deployment error: nginx's `proxy_http_version` defaults to 1.0, and `base-path.md` decision 1 documents a standalone runtime behind nginx. And the gateway's own HTTP/1.0 arm **buffers the entire streamed body in heap, unbounded** (unfiled 39). Fix S-M: a sized body (the spool knows its length). |
| DN-01c | `BasePaths.relative` on a wire-spelled `_return` | NARROWED | low | #1329 killed the upper-case-hex spelling | The residue is the bare base with a query string (unfiled 42), a different mechanism. |
| XD-07k | `EvaluationContext`'s reflective reach echoes claims into the ERROR log | NARROWED | low | #1307 killed the log consequence | A rider on XD-07a's `principal.` lint; the reflective reach itself is G15. |
| TS-01 | `locale:` on a read declaration parses in the root locale | DEAD | none | #1332 (decision 23), #1333 (25) | `1.234,50` under `de-DE` → 1234.50; the ledger bullet at `:479-481` is stale. |
| TS-03 | `result:` on a chunk reader/writer is a lint error and no boot refusal | WIDENED | medium | #1331 filed it; #1334 created the note | `requireJob` has no chunk arm: lint exit 1, `job run` COMPLETED exit 0, and a writer navigating `row.note.sku` **writes NULL** — while `CHANGELOG.md:85-87` (released 0.17.0) claims "a lint error and a boot refusal". Nothing runs `AppLinter` at boot. Fix S: lift `chunkBinding` into `DeclaredKinds.chunkViolations` and call it from `requireJob`. |
| TS-04 | `result:` does not reach a `lookup:`, a Studio browse, the suite runner | REFRAMED | low | #1331 filed the mounts; #1327 the seam | M: `runQuery` applies the declaration after `readRows` (domains resolved — the preview re-parses the route text without `withResultDomains`); or S: one documented clause. |
| XD-12 | add `time` to the `type:` vocabulary? | NOT A DEFECT | low | #1305 filed, #1332 closed the pointer without deciding it | `temporal-semantics.md:623-625` points at a locale question that closed without `time` — the pointer dangles. A "Filed, not fixed" bullet of its own, or the decision (`type: time` reaches the `LocalTime` arm with no code change). |
| XH-13 | the `RouteReloader` fingerprint hole | WIDENED | medium | #1313 (TQL-LD-2837 self-heals a plain delete) | Reproduced with no manual reload: a `get.yml` save while `tpl/report.xlsx` is away installs a stub; restore is "no route changes" and the stub stays. Wider: editing `web/orders/order.sql` read only by `detail/get.yml` (`file: ../order.sql`, the layout `LiveViewIntegrationTest:326` uses) bounces `orders.list`, which never reads it, while `orders.detail` serves the OLD SQL until a yml save. Decision #315 ("fingerprint = source directory") did not consider that lint, the compiler and the framework's own tests accept non-colocated layouts. Fix M: fingerprint per route over the files the compiler resolves; a referenced file outside the watcher's roots still produces no event. |
| XH-14 | the Excel codec accepts `..` in `template:` | WIDENED | medium | #1306 (the 1006 existence predicate accepts `..`) | Four ways wider: outside the app home (not just `..`); **every** `FileCodec` inherits the unconfined path; `sql.file` outside the app home executes; pdf's confinement is request-time only — lint 0, `tesseraql admission` passed, boot green, GET 200 with the outside workbook's marker cell. Medium on contract grounds, not security. Fix S: one `RouteFiles.resolve(appHome, dir, declared)` in `tesseraql-yaml` shared by lint and the compiler. |
| XH-20 | `InboxNotifier`'s bare `Context`, `MailNotifier`'s ROOT subject | LIVE | low | none | `Locale.ENGLISH` at the three sites; document the locale-less list. A `ja_JP` workstation does not reproduce the inbox half (use `de-DE`); the subject half is red on every JVM. |
| DN-06d | the mail leg's JVM-default RFC 2231 charset | LIVE | low | none | Two explicit headers on the part (the codec's type verbatim + `ContentDisposition.attachment`); `mail.mime.*` properties are read at class initialisation, so a `setProperty` after the first `MimeBodyPart` does nothing. |
| XD-08a | a refused hot deploy advances `catalog.json` | **WIDENED** | **high** | none in the window; #911 added the sweep | Reproduced by all three lenses: `deploy --wait` of a route-compile-refusing package → exit 2, old version serving, `catalog.json` naming the refused version; **the next cold host start fails the whole stack** (`MultiAppHost.java:268-280`), gateway never bound. Wider: the 15 s sweep re-attempts the refused replace indefinitely (four WARNs in 50 s, each a Hikari pool + three Flyway rounds + a route compile) — `runtime-replace.md:472-474`'s "No retry loop" has been false since #911; `--url` and the console deploy page report success for a deploy the host refuses. Structural decision 2 makes the catalogue the CLI's intent file and never states what a refused catalogued intent means at boot. Fix M: the host writes the catalogue on an applied replace; addendum first. |
| XD-08b | `deploy rollback` targets the refused version, `previous` null | WIDENED | medium | none | First run of a claim filed from a read: rollback prints success while the host refuses, `previous` is null, and the sweep loop resumes. Absorbed by XD-08a's M; independently `rollback --wait` (S). |
| XD-04a | TQL-FIELD-4622 guards nothing on any export surface | LIVE | medium | #742 origin; #1305/#1306 | Both export chains publish declared source names only; csv bytes are identical with and without `locale:`; the rule draws an ERROR that fails the default lint gate on every csv/pdf export in an app with a `language:` catalog (no example app declares one). Decision 25 deferred the removal to `lookups.md`, which never received it. Fix S: delete the rule, its four cases, its docs rows; regenerate. |
| XD-04b | the dead `CatalogBinder(fixedLocale)` step | LIVE | low | #742, #1260 (ledger comment) | Delete the two `.process(new CatalogBinder(…))` lines, `formatDeclaration` and the constructor in XD-04a's PR; do not "fix" the collapse by passing a declaration — that preserves a dead step. |
| F93 | the devcontainer installs Maven 3.8.7 | DEAD | none | #1225, #1232 | The Dockerfile has no `maven`; the enforcer refuses 3.8.7 at `validate`; `PluginVersionLedgerTest` guards it. This container's image predates #1225, so `mvn` on PATH is still 3.8.7 — always `./mvnw`. |
| F94 | `wrangler.jsonc`'s `$schema` points into an uninstalled `node_modules` | LIVE | low | none | Light: `npx wrangler@4 deploy` in `DEPLOYMENT.md`, annotate the `$schema` line. The deploy command lives in a dashboard, so no test is red on the real defect; `workerd` is a hard dependency of wrangler 4 — do not add it to `docs-site/`. |
| F95 | two developer-setup comments explain themselves in Camel terms | WIDENED | low | none | `.vscode/settings.json:11-12`'s rationale was wrong at birth (JUnit 6.1.0 already carried `org.jspecify`; Jackson never did); `.devcontainer/devcontainer.json:39-51` duplicates every key without the comment — fix both. |
| EH-03 | the application half of the transfer scope is proven by a unit test only | LIVE | low | none | The "no harness can boot two members against one table" reason is false (`SharedSessionIntegrationTest:44-49`, `dev --stack`); the property holds end to end (re-run). An IT, S. |
| EH-07 | a HEAD of a download is not run by the E4 guard | WIDENED | medium | #1335 beside it | A HEAD of `…/file` **spends the first-download claim and fires `after: timing: download` with zero bytes delivered** (re-run over HTTP/1.1 via the gateway and h2c at the member); the claim is spent at `openInput`, of which HEAD is the zero-byte member. And F57 — the audit's confirmed medium "claim before `after:` on separate connections" — **is still open at HEAD**: #1309 shipped only the open-before-claim half. Fix S+S in one PR. |
| EH-08 | Vert.x 5.1.7 sends a HEAD's body over HTTP/2 | LIVE | none | — | Re-run on a bare server: 5.1.7 AND 5.1.8 send the DATA frames; nghttp2 clients get `PROTOCOL_ERROR`; unfixed on master, unfiled upstream. The edge is independent. An upstream report, S. |

**Ten dead, two not a defect, 66 live**: 4 high, 23 medium, 36 low, 3 none. The four highs are
new since the filings' own severities — a schema-poisoning CLI verb the filing already called HIGH,
a Studio row editor that commits a multi-row overwrite while reporting a rejection, a deploy
ledger that takes a stack down at the next cold start, and a header fallback that 400s a shipped
example under the documented deployment.

## The 23 atoms filed as fixed — confirmation

| Atom | Filed as fixed by | Status | Note |
|---|---|---|---|
| XH-02 | export-hygiene P3/P8 | **NOT CONFIRMED** | P3/P8 changed the export's failure reason; a COMPLETED export whose spool is gone (a node-local file spool on another node, an external cleaner, a deleted `tql_temp_spool` row) still answers 500 `TQL-ROUTE-5000` with no code on the route or the console. Low; the export-errors slice. |
| XH-18 | temporal-semantics decision 13 | CONFIRMED | `JdbcValues.read` on the reader. |
| XH-23 | codec-discovery S1-S4 | CONFIRMED | |
| XD-01 | temporal-semantics decisions 1-8, 13 (T0-T3) | **PARTIAL** | Residue R1 (low): a `bytea`/BLOB column renders as the JVM identity string `[B@…` on csv, the Excel grid and the PDF grid — non-deterministic bytes; one arm in `ColumnValues.format`. R2 (low, a design question): a MySQL/MariaDB TIME outside a day is refused with driver text (MySQL) or silently wrapped (MariaDB). |
| XD-02 | export-hygiene P1-P8 | **PARTIAL** | R5 (medium, docs mislead): `groupBy:` on a pdf template exposes no `groups`, lint- and boot-silent. R7 (low-medium): a jxls report writes a time-of-day column as today's date + time — date-dependent bytes; grid/placement write a day fraction. R3 (low): the Excel grid's default cell format for `type: date`. |
| XD-05 | export-hygiene P8 | CONFIRMED | |
| DN-03a | export-hygiene P1 | CONFIRMED | |
| DN-03c | export-hygiene P4/P5/P7, codec-discovery S2 | CONFIRMED | |
| DN-03e | export-hygiene P2/P3/P4/P6/P7 | CONFIRMED | residue is XH-* and XD-04a |
| DN-04a | export-declarations 5a | CONFIRMED | |
| DN-04b | export-declarations 5c / export-hygiene P1 | CONFIRMED | |
| XH-24 | edge-hygiene E0 | CONFIRMED | |
| EH-05 | edge-hygiene E4 #1335 | CONFIRMED | |
| DN-01a | router-unicode-names #1329 | CONFIRMED | |
| DN-02f | edge-hygiene E1 | CONFIRMED | |
| DN-05 | download-name 4b #1303 | CONFIRMED | |
| XD-09a | edge-hygiene E2 | CONFIRMED | |
| XD-03 | codec-discovery S1-S4 | CONFIRMED | |
| DN-07 | codec-discovery S1 | CONFIRMED | |
| CD-01 | codec-discovery S5 | CONFIRMED | |
| CD-02 | codec-discovery S5 / module-channel #1350 | CONFIRMED | |
| CD-03 | codec-discovery decision 3 | CONFIRMED | |
| XD-06 | audit-medium-leads 8a #1337 | CONFIRMED | |

## What the campaigns killed

- **XD-09c** — #1349 computes stack members inside the `TqlException` catch; #1351 (cli-surface 10a)
  shapes the two remaining bare sites. Probed on an install root, the one shape where the filed
  line is the first parse: sentence, exit 2. No guard needed; a guard added later must use the
  install-root shape or it pins #1349, not #1351.
- **XD-07e** — #1316 (P7) shipped TQL-YAML-1042 as an ERROR, stronger than the filed WARNING.
- **TS-01, TS-02** — #1332/#1333 (decisions 23-25): the locale parse and the unread-key refusal,
  both altitudes, one code. The T3 ledger lines at `temporal-semantics.md:479-485` were never
  struck.
- **XD-10** — #1315, the same day as the filing.
- **XH-21** — #1346 (codec-discovery S2 decision 2); three assertions pin the absent hint.
- **DN-01b, DN-02d** — #1329 decision 3 and #1323, each with an IT.
- **DN-01e** — #1329 (R3); the record already annotated it.
- **F93** — #1225 removed `maven` from the Dockerfile; the enforcer and `PluginVersionLedgerTest`
  hold it. The container image in use predates #1225, so `mvn` on PATH is still 3.8.7 — a direct
  goal (`mvn spotless:apply`) never reaches the enforcer.
- **DN-03b** (not a defect) — last-put-wins is decided twice, with the run that proved a refusal
  fails every app carrying the module; "silently" died with #1316's WARN.
- **XD-12** (not a defect) — a design question the records stopped pointing at.

## Where measurement changed the lead

- **XH-01 is eleven scripts, not one.** Fixing V3 moves the failure to V4. V5 and V12 open with
  `if not exists` and carry bare statements lower down; V10 passes only because the CLI never
  bootstraps the idempotency store. The reverse order (runtime first) is safe; `migrate apply` is
  not a pre-flight (app history only). A guard that counts `tql_schema_history__operations` rows
  AFTER the command is green on a migrate-after-bootstrap fix that still fails — assert the boot,
  or the history before any `ensureSchema` ran.
- **DN-06f's trigger does not need a PK-less table.** pgjdbc's `getPrimaryKeys` with a null schema
  returns both schemas' rows and `primaryKey` keeps the last per `KEY_SEQ`; the default constraint
  name makes two properly keyed tables collide. `uiDataBrowserExportOfAnUnreadableTableIsANote`
  depends on the schema-blind listing and turns red on the fix — rewrite it.
- **XD-07j: "nothing depends on the fallback" was wrong twice.** The measurer and the archaeology
  both wrote that no writer or test feeds an input through a header; 109 routes do, and
  `OpsShellIntegrationTest` Order 3/4 only pass because `params.Cookie` carried the operator's
  session. The `Priority` header is h2/h3-only (RFC 9218; `curl_cffi#785` captured real Chrome
  149 HTTP/1.1 traffic without it) — so the shipped example works in `dev` and 400s behind the
  documented TLS edge.
- **XH-22's ledger sentence and its origin measurement disagree.** The sentence says "crashes
  with a stack trace" (killed by #1351); the measurement it cites says "the whole lint aborts
  instead of reporting one finding" (live, and `--format json` prints nothing). A lens that read
  the sentence called it dead.
- **XH-10 is the seam's arguments, not the dispatcher.** Routing the preview through `ExportWrite`
  would need a `TempStore`, turn a `splitBy:` preview into a ZIP and buy the PDF codec nothing;
  the throw on the documented template is the missing `values`.
- **XD-08b was filed from a read; its first run** shows the rollback printing success, doing
  nothing, and re-arming the sweep loop. The one-move recovery (`deploy rollback` immediately after
  the refusal) works today and nothing tells the operator; after the next deploy it is gone.
- **F103's audit fix regresses phones**; **F108's audit fix reverses a recorded decision**; **F100 is
  a best-practice, not a WCAG failure**, by the kit's own dialog page.
- **EN-04's premise inverted**: the contract cannot replace the scan, because the CLI goes dark on
  a duplicate view id where the scan survives. The four editor atoms the user weighed as the
  alternative slice are one M (EN-01+EN-02) and two marginal S — see slice 21.
- **EH-07 re-opened F57**, an audit-confirmed medium whose one-transaction half never shipped.
- **XD-09b's "deployment error" narrowing was a misreading**: `hosting.md:346-351` is the mTLS
  trust contract; `authentication.md:329-331` names nginx as the edge, and nginx speaks HTTP/1.0 to
  its upstream by default.

## Defects surfaced that no atom carries

Merged from the eighteen adjudications and the two hand-read verdict files, deduplicated (a
query-json route without `response:` was found by three groups; the `rows` template key by two;
the Studio double execution by two; the dead export fixture by two). Each carries quoted evidence
in its group's `verdict.md`. Severity on the audit's scale.

**High**

1. **A route without the response arm its recipe reads lints clean and NPEs the whole stack at
   boot, naming no route** — `RouteCompiler.java:742-743` (a query-json route with no
   `response:`); found by lint-judgement, lint-crash-boot and reload-confinement independently. The
   hot reloader isolates it per route; the boot does not.
2. **The Studio row editor's "exactly one row" guarantee is post-hoc on an auto-committed
   connection** — `StudioDataService.java:520`; the DN-06f probe committed a three-row overwrite
   while reporting a rejection.

**Medium**

3. A declared input `default:` is never coerced or validated — `InputBinder.java:141` (the class
   behind XD-07b).
4. A view document with its envelope and no `recipe:` is an uncoded NPE at lint and at boot —
   `ViewSpec.java:293`; it escapes from `AppDirectory.applications:240`, BEFORE the runtime's
   "Failed to start" wrapper.
5. A route step whose `sql:` names neither `file:` nor `contract:` is a message-less NPE at boot on
   every recipe; lint silent — `RouteCompiler.java:2296`.
6. A missing 2-way SQL file lints red (TQL-SQL-2103) but boots green and answers a raw
   `NoSuchFileException` as TQL-ROUTE-5000 — `FileSqlSource.java:48`; a failed read leaves
   `nodes == null` and heals on the next request, so no stub is ever installed.
7. The reloader charges a shared file's change to the routes of its directory, bouncing routes
   that never read it and naming them in the log — `RouteReloader.java:343`.
8. A running export's Cancel is a no-op — the mount, the card and the flag promise a stop that
   never comes — `JdbcFileTransferService.java:1280`; only the terminal STOPPED and a 409 on
   `/file` are red (`cancelRequested: true` is set today).
9. `SplitExport.safe()` folds every combining mark to `_` — abugida, NFD-Latin and NFD-Japanese
   keys are mangled and ordinary words collide — `SplitExport.java:228`.
10. `tesseraql schema` answers a refused database with exit 2 and a coded line, contradicting
    cli-surface decision 10 — `SchemaGenerator.java:47` (F107's decisive site).
11. A `validate:`/`requiredWhen` rule whose `matches()` literal does not compile passes lint and
    boot and answers 500 on every request — `Expr.java:187`.
12. `ErrorCodeUniquenessTest` cannot see a fully-qualified `TqlErrorCode` declaration nor an inline
    anonymous construction — `ErrorCodeUniquenessTest.java:123`; 51+ sites are outside its sight.
13. `<source>.first` is documented for every route source and bound by none: `main.first` is null
    on a query route (`unified-sources.md:120`, `app-mcp.md:250`, `connectors.md:56`) —
    `SqlStep.java:498`. A fixture interpolating `{main.first.<col>}` silently yields `""`.
14. The `result:` sweep judges the binding; export-recipe sources and command/transactional-tool
    `http:` sources accept the key at lint and drop it at run time — `RouteCompiler.java:1474`.
15. A grouped number pattern parses a mis-grouped number as a 100× value with no error, in every
    locale (`'1234,50'` under `#,##0.00` is 123450) — `ColumnValues.java:109`; `setStrict(true)`
    also refuses the ungrouped form, so strictness must be gated on the separator's presence.
16. The reconcile sweep re-attempts every refused candidate every 15 s; a refused canary stage
    loops the same way — `StackReconciler.java:151` (XD-08a's widening, its own row).
17. A staged canary candidate that refuses takes the whole stack down at the next cold start,
    catalogue and `previous` both consumed — `MultiAppHost.java:282`.
18. The remote deploy endpoint and the console deploy page report success for a deploy the host
    refuses, with no surface that says otherwise — `DeployRoutes.java:141`.
19. A never-loaded `table:` catalog fails EVERY route of the app with 404 TQL-APP-4206 and retries
    the failing load per request — `JdbcCatalogStore.java:333`; 404 comes from the domain default,
    not a case, and nothing is logged at the default level.
20. The gateway buffers an entire streamed response in heap for an HTTP/1.0 client, unbounded —
    `vertx-http-proxy` `ProxiedResponse.java:236-250`, `MultiAppGateway.java:49-52`; nginx is
    HTTP/1.0 to its upstream by default; 300 MB export → +560 MB RSS. `hosting.md:336-340` did not
    consider the library's HTTP/1.0 arm. Fix: XD-09b's sized body (nothing left to buffer).
21. Lint is silent where the compiler refuses TQL-VIEW-3308 for an embedded view's and a
    `views:`-bound view's sources — `ViewRules.java:127`; the obvious fix (call `ViewBinding`) is a
    module-boundary violation `ModuleBoundaryGuards` catches — the lint must reimplement the loop.
22. A bound view's document-level `source:` is judged by nothing; a typo renders an empty page —
    `ViewBinding.java:1697`.
23. Site pages still spell the primary source `source: sql`, which the lint and build refuse —
    `declarative-views.md:64` and siblings.
24. `printable-documents.md:58-59` names the pdf template's model key `rows`; the key is `main` — a
    template written to it renders an empty document, COMPLETED, no log line (found twice).
25. The list page's fill chain is severed at the bulk-action `<form>` since #1097: desktop chrome
    scrolls away, the grid is uncapped, and every existing test stayed green — `list.html:69`
    (F103's real constraint).
26. **F57 is still open at HEAD**: `claimFirstDownload` commits on its own auto-commit connection
    before `runAfterSql` opens a second; a failing statement answers 500 and leaves `downloaded_at`
    set, so the statement never runs again — `JdbcFileTransferService.java:1686-1698`; #1309
    shipped the open-before-claim half only.

**Low** (27-72; one line each; the file:line is in the group's `verdict.md`)

27. `tesseraql test` against an unreachable database prints N failed tests and exit 1 instead of
    the operator message (`TestRunner.java:137`). 28. `FrameworkMigrations` Javadoc asserts an
    idempotency the scripts lost at #484. 29. The naive hand repair of an XH-01-poisoned database
    makes it unbootable a second way (V3 on a missing relation). 30. Two stale "why" comments name
    the pdf/export model key `rows`/`sql` (`PdfFileCodec.java:31`). 31. `documentation-ia.md:76`
    says "seven sections" over eight rows on a ten-section site. 32. `CodeCatalogIntegrationTest:547`
    writes an export fixture it never requests, under a comment false for csv. 33. `Principal.java:10`
    documents `principal.sub`, a path that resolves to nothing. 34. The `push.as:` placeholder
    grammar is wider at lint than at run time (`PushStepRules.java:64`). 35. The extension attributes
    a manifest that does not load to an old CLI and keeps a stale Problems panel
    (`diagnostics.ts:113`). 36. `RouteReloader.reload`'s comment under-describes the strict half of
    the tolerant load. 37. `cli-surface.md` decision 10a records lint as shaped without saying
    `--format json` then prints nothing. 38. `StudioTestService.liveRows` runs the main binding
    twice, once under the retired key `sql` (`:280-284`). 39. `StudioDataService.row` shows the
    first of several matches as "the row" (`:458`). 40. On H2 the schema-blind listing shows every
    `INFORMATION_SCHEMA` table (test fixtures only). 41. A job step's `enrich: source: steps.<id>`
    is lint-silent for a step that does not exist (`StepRules.java:29`); the editor record
    describes a route form the lint refuses (`editor-named-sources.md:58`). 42. `viewIdInfoOf`
    misses a BOM-prefixed line-1 `id:` (`views.ts:40`). 43. The catalog's sentence for TQL-LD-2856
    describes a code that moved (`en.yml:147`). 44. An `Error` escaping `runExport` skips the
    writer-spool discard P2 added (`:1356`). 45. A runtime stopped while an export runs leaves the
    execution RUNNING until the reaper (`TesseraqlRuntime.java:2504`). 46. `FileCodecs.put`'s
    Javadoc points at a duplicate-format lint that does not exist. 47. The gateway root redirect
    echoes the request query into `Location` raw: DEL/C0 → 502 blamed on the member with a stack
    trace (`StackRelay.java:550`). 48. The MCP `WWW-Authenticate` challenge and default resource
    fold to `?` under a Japanese app name (`TesseraqlRuntime.java:1505`). 49. `edge-hygiene.md:48`
    names the MCP transport as a `security.responseHeaders` writer; it never was. 50. An app-wide
    default `Cache-Control` overwrites every asset's caching policy and the SSE stream's `no-store`
    (`AssetRoutes.java:365`). 51. `temporal-semantics.md:478`'s "Filed, not fixed" list and its
    scope-out pointer are stale. 52. Lint confines no route-relative path except the mail
    template; the html `template:` is refused only at boot (`TemplateResolution.java:40`). 53. A mail
    attachment's name reaches the MIME part headers unfolded — CR LF splits the header
    (`MailNotifier.java:238`). 54. `[(#{key})]` never resolves in a mail `subject:` or an inbox
    `title:`/`body:` while it resolves in the mail body (`:269`). 55. `lookups.md:338` "Mail — the
    recipient's language" states an unimplemented rule. 56. The inbox title's 500-unit truncation
    meets Oracle's byte-semantics `varchar2(500)`: a long non-ASCII title dead-letters
    (`InboxNotifier.java:46`); the inbox schema is `create table if not exists`, outside Flyway.
    57. A refusal's status record carries no action or version (`StackReconciler.java:342`).
    58. `deploy --wait` on an intent that changes nothing times out with exit 1 although converged
    (`DeployCommand.java:300`). 59. `deploy rollback` has no `--wait` (`:406`). 60. `deploy status`
    renders a stale refusal against a newer intent no host has judged (`:486`). 61. Three published
    sentences say a refused deploy is harmless to the ledger (`hosting.md:250`). 62. 4622's finding
    names the route file by absolute path (`CatalogLocaleRules.java:62`). 63. `CatalogBinder`'s
    Javadoc and `lookups.md` claim export and mail templates read `codes`; none does. 64.
    `lookups.md:334` says an export's `locale:` may be a request source the route binds; the binder
    never walks it. 65. `CONTRIBUTING.md:13` still says `mvn verify` in prose. 66. Two records
    promise "green CI implies a clean deploy"; CI never runs wrangler (`docs-site.md:75`). 67. Input
    precedence is path → body → query; `Request.param` and `vertx-native.md:301-303` say path →
    query → form (`RequestBinder.rawValue:339-347`). 68. Lint is silent when a `body.<name>` /
    `query.<name>` source names an input the route does not declare. 69. `BasePaths.relative` does
    not strip the bare base when a query string follows it (`/shop?page=2` → `/shop/shop?page=2`
    on a `location: back`). 70. Stack members bind `0.0.0.0` on random ports while the gateway
    reaches them over loopback (`TesseraqlRuntime.java:1377`). 71. `file-transfers.md:341-342`
    overstates the `download` timing for GET (the predicate is "the spool opened"), and E4's "What
    this breaks" names cost only. 72. `RouteEdge.mountRoute`'s comment (`:217-220`) says the
    transport withholds a HEAD's body — the reading E4 itself corrected; it contradicts
    `HeadRequests.java:13-18` twenty lines away. `TransferScopeTest.java:13-15` states the
    falsehood EH-03 rests on. 73. *Surfaced by slice 9a's verify, fixed there:* the canary
    discard that follows a refused replace wrote "discard applied" over the refusal
    (`StackReconciler.java`, the `canary != null` arm; one file, last outcome), so the next
    pass attempted the refused candidate once more and `deploy status` read applied against
    a refused intent. Slice 7's `aRefusedDirectDeployIsSurvivedByTheNextColdStart` was green
    only when its status snapshot beat the discard's write by the 200 ms debounce — its
    "the first pass left it alone" was never exercised. A standing refusal now stays the
    record through the discard; `StackReconcilerTest.aCanaryDiscardAfterARefusalKeepsTheRefusalOnRecord`
    is red on the old arm (two attempts across three passes), and the IT is deterministic.
    74. *Surfaced by slice 10's Japanese-member row, fixed there:* the stack surface mounted
    every member's RFC 9728 document on one pipeline id (`OAuthRoutes`,
    `system.oauth.resourceMetadata`), and the edge keys a mount by method and pipeline, so a
    stack of two or more members served the last member's document only — the first member's
    `/.well-known/oauth-protected-resource/<member>/_tesseraql/mcp` answered the router's 404
    since the document shipped. `OAuthIssuerUnificationIntegrationTest` had one member and
    never saw it; with the second member installed its `shop` row was the red. One pipeline
    per member now.

## The completeness critic — first run

The 2026-09-04 workflow stopped twice on the session limit and never reached its critic stage.
It ran here as written: the critic read the 17 finders' coverage notes verbatim, the 129 known
findings, the 209 commits since the base and `AGENTS.md`, checked every top-level directory and
reactor module against what the finders said they had read, struck what a campaign since has
measured end to end with its own record (the export pipeline, the HTTP edge, base paths, the
2-way SQL parser, deterministic output, release/CI, the YAML surface, module boundaries, temporal
semantics, the contract seam, the CLI exit codes, Unicode names at the router, the editor's named
sources, the bundled apps' i18n, the high and medium leads, the JDBC close-out, test hygiene) and
what this pool is measuring, and scouted each surviving area in the code until it produced a
concrete starting probe or turned out to be a recorded decision. Six areas, ranked by defect
likelihood × user visibility; six demoted alternates (the extension↔CLI contract, DuckDB,
outbound egress, deploy lifecycle, config secrets, the bundled apps as a whole) are named in
`critic/critic.md` with the reason each lost.

| Area | Why the critic named it |
|---|---|
| `mcp-surface` | A shipped developer surface with write tools bound on loopback without authentication, no `Origin` validation, an optional session and an unchecked content type — the drive-by-localhost class the MCP spec guards against; no finder read the transport or the dev tools, and no record mentions `Origin`. |
| `workflow-engine` | The approval workflow's engine, sweeper, bulk/dispatch/delegate processors, 649-line lint and suite target were all "not read" by four finders; one scouting probe (a scoped escalation command rendered without a resolver) already contradicted two design records. |
| `tenancy-scoping` | Every class on the tenant/scope path was left unread by security-auth, performance and core-sql alike, and the docs make a fail-closed promise at every SQL surface the temporal campaign had shown is not uniform for a sibling declaration. |
| `suite-runner-coverage` | A false green in the suite runner voids the guarantee the gallery apps, the admission profile and every user's CI rely on; test-core, coverage-core and report were "not covered" by two finders. |
| `expressions-decisions-validation` | The expression language sits under every workflow guard, validation rule, decision table, `when:` and 2-way SQL directive; its evaluator contradicts its own Javadoc at line 116, and core-sql wrote that the parser body was not covered. |
| `identity-lifecycle` | security-auth verified the token and protocol primitives sound and explicitly did not reach mTLS matching, TOTP enrolment and recovery, session-token minting, the issue-token page or the 76-route IAM admin app — the credential flows an operator meets daily. |

Six finders, 43 findings (one duplicate dropped), **43 confirmed** — every one by the three
lenses; six were also adjudicated a second time with a probe because a lens was uncertain (G3,
G15, G24, G33, G42) or because the first single-adjudicator pass had refuted it (G26, overturned:
the sentence is real, the behaviour behind it is a recorded deferral). Severity after the worth
lens: **8 high, 29 medium, 6 low**. The rate is not leniency — the areas were chosen because
nobody had read them, and every finder ran probes the 2026-09-04 finders could not.

| Id | Area | Now | Finding | Where |
|---|---|---|---|---|
| G1 | mcp | medium / security | The dev MCP HTTP transport never validates `Origin` (a spec MUST) nor `Content-Type`, and the session is optional: on a no-JWT loopback server a web page's no-cors `text/plain` POST runs the write tools — probed: `draft_apply` promoted a route into the source tree. Opt-in state (HTTP transport + a jwt-less app or `--insecure`), so medium. | `HttpTransport.java:59` |
| G2 | mcp | medium | `McpServer` prepends the code to a `TqlException` message that already carries it, on all three primitives. | `McpServer.java:196` |
| G3 | mcp | low | Negotiates 2025-03-26 but refuses the JSON-RPC batches that revision makes MUST-receive, and never validates `MCP-Protocol-Version` (a bad one answers 200, the spec says 400). Narrowing `SUPPORTED` would break SDK 1.12 clients that work today — the finder's fix is wrong; the two legs are one small PR. | `McpServer.java:81` |
| G4 | mcp | low / dx | `McpInputSchema` omits `pattern`, `minLength` and the email/uuid/uri formats `InputBinder` enforces and the OpenAPI generator emits. | `McpInputSchema.java:22` |
| G5 | mcp | medium / docs | `app-mcp.md` promises an interactive fragment that presents the tool's result; the served artefact is a bare hc-* snapshot of its own query. | `docs/app-mcp.md:194` |
| G6 | mcp | medium | Under the stack issuer a declared `tesseraql.mcp.resource` can never be granted for — the RFC 9728 document, the challenge and the grant all name the derived resource. | `OAuthRoutes.java:109` |
| G7 | mcp | low | Three live MCP HTTP refusals (400/404/500) still ship the flat `{"error":"…"}` shape decision 6 retired; the ledger guard cannot see them. | `McpHttpHandler.java:156` |
| G8 | expr | **high** | A `between` cell written `> n` / `< n` compiles to an inclusive bound shifted by the literal's scale — every decimal input in `(n, n+scale)` is misrouted on every decision table with a strict bound. | `DecisionTables.java:417` |
| G9 | expr | medium / build-ci | A 2-way SQL file that does not parse passes lint and admission and 500s on the first request of a query route; `LintContext.sqlNodes` swallows the parse failure. | `LintContext.java:204` |
| G10 | expr | medium / build-ci | Three manifest expression positions (`response.headersWhen`, a step `when:`, `notify.recipient:`) are parsed only at boot: lint and admission pass a broken one. | `InputRules.java:151` |
| G11 | expr | medium | A relational comparison with a null or mismatched operand is an uncoded 500, though #234 and the docs record null-propagation for optional inputs. | `Expr.java:265` |
| G12 | expr | medium | Virtual `size`/`length`/`empty` shadow a same-named map key on every nested scope, so an input or column named `size` is unreachable. | `EvaluationContext.java:61` |
| G13 | expr | medium | The lexer silently drops the backslash of every escape other than `\'` `\"` `\\`, so `matches(x, '\d{3}-\d{4}')` is the regex `d{3}-d{4}`. | `ExpressionParser.java:323` |
| G14 | expr | medium | Numeric `==` and `<` compare through `double`, so `Long`/`BigDecimal` operands past 2^53 compare equal when they differ. | `Expr.java:251` |
| G15 | expr | low / docs | `EvaluationContext` invokes any public zero-arg method by bare name (`s.strip`, `list.stream`, `URL.openStream` ran); a public static match crashes uncaught; the Javadoc and two doc pages promise no method invocation. | `EvaluationContext.java:116` |
| G16 | suite | medium | The SQL coverage gate and the regression aggregate count only the files a case rendered — deleting a test raises coverage. | `CoverageGate.java:29` |
| G17 | suite | medium | A `sql` case whose file carries a top-level `commit;` persists its write and poisons later cases; the "never commits anything" guarantee is false. | `SqlCases.java:48` |
| G18 | suite | medium / robustness | `test --report --fail-on-regression` fails open and overwrites the baseline on a corrupt `history.json` — #652's `isCorrupt` guard never reaches this path. | `TestCommand.java:185` |
| G19 | suite | medium / robustness | Duplicate case names across suites are accepted (the portal shows the failing twin as passed) and a `--case` matching nothing exits 0. | `AppTestRunner.java:93` |
| G20 | suite | medium | The suite's validate/notify/transition kinds compile against the process default, dropping the registry `SuiteContext` holds — a module function fails the suite and passes the route. | `ValidationCases.java:62` |
| G21 | suite | medium | The Studio recorder writes query-string values untyped, so a recorded case with an integer bind fails at the database on its first replay. | `StudioService.java:671` |
| G22 | suite | medium / docs | `coverage.thresholds.queue-consume` and `.decision` are documented gates the resolver's hand-kept `KINDS` list never reads; an unknown threshold key is silent. | `CoverageThresholdResolver.java:17` |
| G23 | suite | medium / maintainability | `RouteTestRunner`/`RouteSuite` and the plan-guard package have no caller, while the README, three design records and `vertx-native.md:458` present them as the mechanism. | `RouteTestRunner.java:71` |
| G24 | tenancy | **high** / security | file-export and file-import routes run on the main pool in database/schema-per-tenant modes — probed three times: an unknown tenant refused on reads gets 202 and the main pool's file; an import lands in `public`. The slice-6 deferral in `route-governance-parity.md` assumed "no caller", which is false for route-triggered transfers. | `FileExportStartProcessor.java:79` |
| G25 | tenancy | **high** / security | A typo in `tenancy.mode` or `tenancy.resolver.type` is accepted silently: no lint, no boot refusal, every tenant reads the shared pool. | `TenantDataSources.java:48` |
| G26 | tenancy | low / docs+UI | "It expires by itself" — `account.md:210`, `iam-admin.md:159` and the product's own account-card hint (`tql.account.eligibilityHint`, en and ja) — while the elevating session keeps the role until End now, sign-out or the 12 h session TTL. The behaviour is `application-roles.md` decision 2's deferral; the sentence is not. | `docs/account.md:210` |
| G27 | tenancy | medium | Matching scope arms that share a bind name all render against the last arm's value (`Resolved` carries one bind map for every OR-combined fragment). | `CompiledScopeResolver.java:144` |
| G28 | tenancy | medium | A non-main export source's own `params:` are never resolved — its SQL renders with main's map (`ExportQuery` has no params slot); `export-pipeline.md` decision 2 promises "exactly as a read route does". | `RouteCompiler.java:2565` |
| G29 | tenancy | medium / security | TQL-TENANT-3001 lost every command write in #753 (`sql()` → `main()`); `steps:` and non-main `sources:` are never inspected, while `multi-tenancy.md` and `threat-model.md` name the lint as the control. | `DocumentRules.java:407` |
| G30 | tenancy | medium | A `/*%scope*/` in a query-export route's non-main source passes lint and answers 500 TQL-SQL-2106 (`composedValues` uses the resolver-less render overload). | `SqlStep.java:404` |
| G31 | tenancy | medium / security | A transfer's subtree is scoped to app + route, not tenant, and the `/file` leg resolves no tenant at all: any tenant id (or none) can fetch and cancel another tenant's export. | `RouteCompiler.java:1670` |
| G32 | workflow | **high** / security | `assign:` SQL never receives the document key — a `/* key */` resolver binds null, no task opens, and the framework-enforced task-authority gate silently disappears; three procurement gallery resolvers are affected. | `TransactionalCommandProcessor.java:823` |
| G33 | workflow | **high** | `onBreach.escalate` renders the transition's own scoped command resolver-less: TQL-SQL-2106 every sweep, the whole overdue batch rolled back, no lint — the same file is legal request-fired. | `WorkflowSweeper.java:204` |
| G34 | workflow | **high** | Workflow reminders drop `recipient:` and tenant on both enqueue paths and are never linted — an inbox reminder dead-letters every time. | `TransactionalCommandProcessor.java:880` |
| G35 | workflow | medium / robustness | One failing breach rolls back and blocks every other overdue task's escalation on every sweep, and the WARNING never names the task. | `WorkflowSweeper.java:114` |
| G36 | workflow | medium / docs | `onBreach.reassign` binds undocumented `docId`/`state`, drops declared `params:` at boot, and no-ops silently every sweep when the resolver answers nothing. | `WorkflowSweeper.java:238` |
| G37 | workflow | medium | Under tenancy, delegation rules are honoured only by `delegate/{to}`: the assign funnel resolves under `TenantContext`'s `toString`, and sweeper escalation passes a null tenant. | `WorkflowSweeper.java:146` |
| G38 | identity | **high** / security | `POST totp/begin` on a confirmed enrollment removes (and lets a session holder replace) the second factor with no password re-check. | `AccountViews.java:384` |
| G39 | identity | **high** / security | An invite token outlives Disable: the public accept leg re-activates a DISABLED account and nothing revokes the token; the console offers no withdraw. | `RecoveryRoutes.java:131` |
| G40 | identity | medium | mTLS `clockSkew` narrows the certificate validity window to `[notBefore+skew, notAfter−skew]` instead of widening it; PKIX path validation also runs at the strict now. | `MtlsAuthenticator.java:137` |
| G41 | identity | medium / docs | "Issuance is recorded in the audit trail" is false: the Java-mounted system routes (token, login, logout, elevate, recovery, bulk disable) carry no `RouteAudit` step. | `docs/session-token-exchange.md:85` |
| G42 | identity | medium / robustness | TOTP confirm commits `confirmed_at` before activating the recovery codes; on SQL Server the common `totp/V2` script makes `created_at` a `rowversion` (no vendor V2 exists), so every confirm 500s after enabling the factor — measured three times on SQL Server 2022. | `AccountViews.java:438` |
| G43 | identity | low / robustness | IAM Admin lets an administrator disable their own account (per-user and bulk) — the last `tql.iam.admin.write` holder included — and signs them out in the same request. | `OpsAccountProviders.java:405` |

What the finders could not reach is recorded in each `critic/find-<area>.md`: the runtime's
`/_tesseraql/mcp` under a booted app and a real MCP Apps host; the ambient-params seeding
paths, table-backed decision SQL on a live database and a Unicode-class `pattern:` on NFD input;
the plan inspectors and the Allure/HTML reporters; MCP tools and the workflow sweeper's pool
under tenancy (the sweeper runs application escalate/reassign SQL on main with no `tenant.*`
bind, and `tql_workflow_instance`'s unique key is `(doc_type, doc_id)` without `tenant_id` —
noted by the workflow finder, unprobed); the inbox stores and the remaining ~450 lines of
`WorkflowRules`; `OpsShellRoutes` beyond the nav provider and the delegation route's delegate
lookup by login id with no tenant filter (a possible cross-tenant task hand-off no lens had a
tenanted stack to confirm). A second critic round would start there.

## Verdicts not to trust without more work

- **XD-07j's fix shape.** The declared-header source for the 109 shell routes was designed by
  the refuter and endorsed by the adjudicator from reading; no one built it. The naive fix is
  proven harmful by two shipped ITs; the shape that keeps them green is not yet proven. *Built
  in slice 9a: the naive fix turned three rows red in each shell IT, and the count was 158
  routes — the 49 `X-CSRF-Token` inputs rode the same fallback and no lens's header list
  named them.*
- **XH-13's half 2** (the stub that stays after a restore) was probed with an export template;
  a 2-way SQL file never installs a stub (it reads lazily and heals) — the guard must use a
  compile-time-judged input or it is green on HEAD.
- **F100/F101/F103's layout claims** rest on a Playwright probe borrowed from another session's
  scratchpad; the `probe*.out` files are the record. No browser harness exists in the repo.
- **TS-04's Studio half**: no lens ran the live preview end to end (it needs a dev stack with
  Studio, `tesseraql.studio.testRunner.enabled` and a session); the mechanism rests on the absence
  of any `result()` read in `tesseraql-studio-runtime`.
- **XH-08's severity** is "none" on the filing's own terms and the class (BigDecimal through
  `writeUTF`) is a spool-format change under a shared temp store; leave it filed.
- **F121's ReDoS demonstration** holds on JDK 25 only for the polynomial `(.*a){n}` shape; the
  textbook exponential shapes answer in milliseconds there.
- **G26's severity.** Three lenses and a second adjudicator call the sentence real (the first
  adjudicator had refuted it as covered by decision 2); the worth lens says medium, the
  adjudicator low. It is a sentence on the published site and in the product's own hint —
  fix it with slice 4 and do not argue the number.
- **G3's fix.** The finder proposed narrowing `SUPPORTED`; that breaks SDK 1.12 clients that
  work today. The two legs that survived (batches refused, `MCP-Protocol-Version` unchecked)
  are the PR; the finder's fix is not.

## The campaign — slices in severity order

Chosen order: severity first, then reachability; the cheap sweeps batched at the end — *decided
2026-09-15: the order below stands as written.* Every slice branches from a fresh `origin/main`; every guard is proven red on HEAD and against a broken
variant before it ships (four guards in the HIGH pass were green on their own defect; here the
adjudicators resolved 131 lens disagreements, and in 36 of them a lens's evidence was wrong at
first reading). Sizes are the adjudicators'. Nothing here is
scheduled anywhere else; F57 (row 26) was scheduled once and did not ship whole.

| # | Slice | Atoms / findings | Size | Note |
|---|---|---|---|---|
| 1 | `job run` migrates before it bootstraps | XH-01, unfiled 28-29 | S | **SHIPPED.** `JobCommand.wire` → `FrameworkMigrations.migrateOperations(main)` (now public, the class too) before the first `ensureSchema()`; H2/DuckDB skip inside `migrateComponent`; the Javadoc states the order and why the scripts cannot be made re-runnable; CHANGELOG names the hand repair. Guard `JobCommandIntegrationTest.aDatabaseFirstTouchedByJobRunBootsARuntimeAfterwards`: its own database in the class's container, `job run demo.touch` exit 0, then `TesseraqlRuntime.start` against it — red with the call removed by exactly `V3__job_execution_actor.sql` 42701 at the boot, green on the fix. One fact the guard added: on a fresh schema Flyway writes no baseline row, so the history starts at `framework operations` — a `<< Flyway Baseline >>` row at 0 is the poisoned state's signature, and the guard asserts its absence. The scripts were not edited. |
| 2 | The workflow engine resolves for the document it is told about | G32, G33, G34, G35, G36, G37 | L (2 PRs) | **2a SHIPPED.** `applyTasks` seeds `key` for the assign resolver as the guard and command are seeded (a declared `params:` key wins); both reminder enqueues build the addressed envelope — `recipient:` resolved against the reminder scope, the tenant (the principal's on the route, the task's on the sweeper: `Overdue` carries it) — and honour the opt-out; `WorkflowRules` runs reminders through `lintNotifySpec` (1034/1102/2101) and assign/reassign `params:` keys through 2120. Guards, each red on the reverted engine: `WorkflowTransitionIntegrationTest` (a keyed resolver opens the task and a non-holder gets 403; assigned and escalated reminders land in `tql_user_notification`; an opted-out assignee gets none), `ProcurementRequisitionTaskIntegrationTest` (the shipped gallery over HTTP: REQ-1002 opens a task for `kishi`, an intruder MANAGER gets 3203, `kishi` settles it), `WorkflowSweeperBoundTest` (envelope), `AppLinterWorkflowTest`. The route reminder takes the principal's tenant as `notify:` does. **2b SHIPPED.** The sweeper renders through `CompiledScopeResolver.asSystem()` — every declared scope `(1=1)`, an undeclared one still 2107 (`data-scoping.md` names the exception; `route-governance-parity.md` decision 4 closes its sweeper line) — behind a per-task savepoint with a WARNING that names the task; `resolveAssignee` binds `key`/`docId`/`state`/`audit.*` and the declared `params:` against the loaded document (`Rule` carries `Reassign(nodes, params)` + `Document`), a no-row answer clears the deadline (`WorkflowTaskStore.clearDeadline`), records history and warns; `TransitionExecutor.begin` takes `TenantContext.id()`, the sweeper passes `task.tenantId()` to `Delegations.resolve`; an escalated command that matched no row is 3204 and rolls the advance back (finder 7); lint `TQL-WORKFLOW-3121` refuses a reassign `params:` entry outside the sweep context. Guards, each red on the reverted engine (nine, nothing else red): `WorkflowTransitionIntegrationTest` (+4: scoped escalate fires; keyed+params reassign and the no-row answer once; the poison task skipped while the next escalates; the hollow command rolled back), NEW `TenantedDelegationIntegrationTest` (shared-schema tenancy: the rule found at assignment, `tenant_id` = the id, the sweeper's fallback under the task's tenant), `WorkflowSweeperBoundTest` (+2), `AppLinterWorkflowTest` (+1). The savepoint ledger took its due: `DialectRuntimeChecks.workflowSweepRoundTrip` (a poison task met first, the healthy one still reassigned) runs on PostgreSQL per PR (`WorkflowSweepFenceIntegrationTest`) and on the three gated vendors; 3204 is a SHARED code (route + sweeper, one rule). The check's first gated run found a defect no atom carried: `workflow-task/V2__delegated_from.sql` (`add column`, 0.5.0) had no Oracle/SQL Server variant, so `JdbcWorkflowTaskStore.ensureSchema` — every task-assigning workflow app's boot — failed on both vendors since 0.5.0; fixed in the follow-up PR with the two vendor scripts. Planned as: 2a the transactional side: `assign:` told its document (`/* key */`), reminders keep `recipient:` and tenant, both linted (G32, G34). 2b the sweeper: a scope resolver on `escalate` (G33), one failing breach does not roll back the others and the WARNING names the task (G35), `reassign` binds declared `params:` and a no-row answer is loud (G36), delegation rules honoured under tenancy (G37). Three procurement gallery resolvers are affected — the gallery ITs are the guard. |
| 3 | Tenancy reaches every executor, and refuses what it cannot route | G24, G25, G27, G28, G29, G30, G31 | L (2 PRs) | **3a SHIPPED.** `TransferPools.of(exchange)` resolves the pool through `TenantRouting` (4031 before any transfer row) and both requests carry a `TransferPool`; `JdbcFileTransferService` runs the row statement, the extraction and the `after:` statement on it, records the tenant (operations V15, three vendors) and resolves it again for the after-download statement (`tenantPools`); in a per-tenant mode the rows and the record are two connections (`Bookkeeping`: rows first, then the verdict, the compare-and-set still before either commit). `TenantDataSources.load` and `TenancySettings.from` refuse the vocabulary (4032), `TenancyConfigRules` lints it (3002). Guards, nine red on the reverted engine: `TenantDataSourceRoutingIntegrationTest` (+3: the export serves the tenant's rows and its after-download mark lands in the tenant's schema; the import lands there; an unknown tenant's export and import are 403/4031 with no row anywhere), `TenantDataSourcesTest` (+3), NEW `TenancySettingsTest`, NEW `AppLinterTenancyConfigTest`. `route-governance-parity.md` Matrix 2 cell and the slice-6 paragraph answered; the audit binds stay open. **3b SHIPPED.** `ScopeResolver.Resolved` carries one `Fragment` per matching arm and `SqlRenderer.renderScope` layers each fragment's binds around it (G27); `ExportQuery(name, sqlFile, paramSources, params)` — the compiler carries the binding's `params:`, `FileExportStartProcessor` resolves them at request time, `SqlStep.composedValues` resolves and renders through `statement.scopes()` + CONTEXT, `JdbcFileTransferService` layers `query.bindsOver(params)` (G28, G30); `DocumentRules.lintTenantPredicate` walks `main` + `steps` + non-main `sources`, one warning per binding (G29); `TransferStatus.tenantId` from the V15 column, `TransferScope.own(…, exchange)` compares it, the `/file` leg takes `applyCommonGovernance` (G31). Guards, seven red on the reverted engine: `CompiledScopeResolverTest` (the shared-name arms, rendered through the directive), `ExportDataSourcesIntegrationTest` (+3: the header source's own `params:` on the inline and the transfer path — binding a name `main` does not declare, or `main`'s map hides the defect — and a scoped header source under a buyer's claim), `AppLinterTest` (a second command step and a second source), `TransferScopeTest` (+1) and `TenantDataSourceRoutingIntegrationTest` (+1: another tenant's status/file/cancel are 404, the file leg refuses a tenant-less request with 4001, the owner still downloads). Planned as: 3a file-export/import on the tenant's pool and 4031 on an unknown tenant (G24; the slice-6 deferral's "no caller" premise is false for route-triggered transfers), an unknown `tenancy.mode`/`resolver.type` refused at boot (G25). 3b scope arms sharing a bind name (G27), a non-main export source's own `params:` (G28), TQL-TENANT-3001 over `steps:` and non-main sources again (G29 — lost in #753), `/*%scope*/` in a non-main export source (G30), the transfer subtree scoped by tenant (G31, medium — EH-01's product decision, now with a security row behind it). |
| 4 | An account's second factor and its invite survive what the operator did | G38, G39, G42, G40, G43, G41, G26 | M | **SHIPPED.** `totp/begin` on a confirmed enrollment is refused (`TQL-ACCOUNT-4807`, 409) rather than asked for the password: the page never sends it, so the enforced secret leaves only through `disable`, which does — the adjudicator's "least surprising" of the two shapes (G38). `TotpStore` reshaped: `beginEnrollment` takes the plain codes, `confirmEnrollment` takes the hashes and confirms + activates + clears pending in one `Transactions` bracket, `remove` deletes the codes with the enrollment (the G38 secondary), `Enrollment` carries `pendingRecovery`; `totp-sqlserver/V2` and `totp-oracle/V2` exist, `VendorMigrationSetTest.KNOWN_GAPS` is gone (G42). `CredentialTokenStore.revoke(loginId)`; `IdentityDisables.disable` (one method for the per-user provider and the bulk route) flips, invalidates and revokes; `acceptInvite` activates only an account still `INVITED`; the detail page offers Withdraw on an invited row (the same action, `?withdrawn=1`) and neither Enable nor Disable; a withdrawn login is re-invitable through the new `reinvite-user` contract (no credential = no takeover) — the mis-addressed invitation's recovery, which the finding's own scenario needed and the plan had not named (G39). `MtlsAuthenticator` refuses only outside `[notBefore-skew, notAfter+skew]` and validates the chain at the window's nearer edge (G40). Self-disable refused whole with `TQL-IAM-4037` (409) before anything in a selection is touched; the list and detail pages render no checkbox and no Disable for the caller's row; the general last-holder invariant stays a design item, as the adjudicator said (G43). Four sentences amended, not the seven `Pipelines.of` installers (G41); five sentences and the ja hint amended (G26). Guards, each red on the reverted engine and nothing else red: `TotpIntegrationTest` (+begin on a confirmed enrollment 409/secret untouched/password-only login still refused, eight hashes after confirm, none after disable — red on the check removed and on `remove` leaving the codes), `InviteIntegrationTest` (+2: withdraw → re-invite mails a second link, the first dead, the second works, then 409 once usable — red on revoke removed, by the cooldown's silence; an account disabled in the store with its token live is not activated — red on the status gate removed), `IamAdminIntegrationTest` (+1, signed in as the store row `u1`: no Disable, no checkbox, per-user and bulk 409, nothing disabled — red on the self checks removed), `MtlsAuthenticatorTest` (+4 on the committed fixtures with a twenty-year skew: expired-within-skew and issued-within-skew accepted, expired-beyond refused, the PKIX path at the same leeway — three red on HEAD, the PKIX one alone red on a window-only fix), `DialectRuntimeChecks.totpEnrollmentRoundTrip` on all four vendors (SQL Server red with error 273 on the jar built without the vendor script — a plain `install` had left the moved file in `target/classes` and the first bracket lied green; `clean install` for a resource variant). The confirm's atomicity is by construction (one bracket), not by a red test. **Three defects no atom carried, found by the guards:** (1) the common `totp/V2` `create table` was bare, and MySQL 1050 is not a tolerated code — the second boot of every password-login app on MySQL failed at `JdbcTotpStore.ensureSchema` from 0.6.0 (the check applies the schema twice; red before `if not exists`); (2) `SqlScripts` read H2's duplicate-column/-index codes 42121/42111 as SQLStates — H2 reports them through `getErrorCode()` — so every `ensureSchema` column add failed a second boot on H2; fixed, `SqlScriptsTest` on a live H2 (core gains H2 at test scope); (3) a bundled app file absent from `.app-index` is on the classpath and served nowhere — the withdraw route answered 404 for one run; `BundledAppIndexLedgerTest` pins every index to its directory. **Filed, not fixed:** the untenanted sentinel `""` is NULL on Oracle, so `tql_user_totp.tenant_id not null` refuses `beginEnrollment` — the same idiom in `JdbcShortcutStore`, `JdbcInboxStore`, `JdbcPreferenceStore`, `JdbcDelegationStore`; the dialect check names its tenant. |
| 5 | A `between` cell means what it says | G8 | S | **SHIPPED.** `Condition.Range(min, minInclusive, max, maxInclusive)`; `matches`, `intersects` and `containedIn` take the flags (an open end against a closed one at the same point does not meet; two closed ones do); `exclusive()` and its Javadoc deleted; the schema description, `decision-tables.md`'s row and its "identical semantics" sentence (a table row has no open end — promote `> n` at the column's scale, not the literal's) corrected. Guards, red on HEAD's `DecisionTables`: `DecisionTablesTest` (+2: the archetype at 100000 / 100000.01 / 100000.50 / 100001, `< 3` at 2.99 and 3, a `> 100000.00` literal against 100000.001; the `<= n` / `> n` unique partition answers at 100000.50 where HEAD raised 4721, two closed ends meeting at a point are 4714, `< 100001` / `> 100000` are 4714), and the purchase-request gallery suite (+1 case: 100000.01 → `cfo-1`; HEAD answered `approver-1` through `GalleryAppsIntegrationTest`). The table-backed source needed nothing: it never had exclusive ends. |
| 6 | The Studio data browser reads one schema and writes one row | DN-06f, unfiled 2, DN-06g, F108, unfiled 39-40 | S | **SHIPPED.** `listTables` scopes `getTables` to `connection.getSchema()` (null on MySQL, the catalog scoping as before) and `TableRef` carries the schema with a `qualified` flag — a server ref keeps the bare name in SQL, its schema for the metadata reads; `getColumns` takes the schema and table as exact patterns (`getSearchStringEscape`: `hd_2` no longer reads `hdx2`); `updateRow` runs its UPDATE through `Transactions.call` and rolls back on any count but one; `row` refuses a second match (defence in depth — unreachable once the key is this schema's, so it has no red of its own); the note is folded (`replaceAll("\\R+", " ")`) and both catches log at WARN, a refused table name at DEBUG (DN-06g, F108); on H2 the fifteen `INFORMATION_SCHEMA` tables stop listing (unfiled 40). Guards: `uiDataBrowserReadsTheConnectionsSchemaAndWritesOneRow` — `probe_other.tql_users` beside the identity pack's, `probe_editable` in both schemas with the default `_pkey` name and the keys swapped; on HEAD the listing shows `tql_users` twice (deterministic) and, with the listing assertions skipped for the measurement, the update keyed on `ref` committed `["CLOBBERED", "CLOBBERED", "CLOBBERED", "other"]` behind a 400; on the transaction-only variant the rows stay intact and the id-keyed update is refused (the metadata still lies); on the scoping-only variant everything is green — the transaction is defence in depth, not separately provable. `uiDataBrowserExportOfAnUnreadableTableIsANote` rewritten: its `probe_other.ghost` trigger depended on the schema-blind listing; the arm now fires through an ordering filter on a `uuid` column with a non-uuid value (`operator does not exist: uuid > character varying`, three lines from pgjdbc) and asserts one record — red on HEAD and on the fold removed. The API's 400 body is generic, so the guard asserts the code and the data, not the sentence. |
| 7 | The host writes the catalogue; a refusal is loud and does not loop | XD-08a, XD-08b, unfiled 16-18, 57-61 | M | **SHIPPED.** The addendum landed first (its own commit): `UpgradeState` gains `mode` (`canary`/`replace`; absent = canary), `upgrade` writes a replace candidate and leaves the catalogue, `promote` rewrites the canary as one, `rollback` discards an unapplied candidate (the refused deploy's shape) or writes `previous` as one, `pending()` beside `canary()`. The reconciler's rules: the catalogue moved (another node) → promote/replace as before; a replace candidate not yet serving → promote if the canary slot runs it, else replace, and `catalog.replace` runs at the swap through a `swapped` hook on `HostOperations` — before the retiring runtime drains, because the first cut wrote it after the drain and the full verify caught the window: Order 3's promote answered live, Order 4's `rollback` read a catalogue the drain had not let the host move yet, and judged the promote unapplied; the canary rules as before; a refusal is recorded with action and version, a follow-up pass is requested so the rest converges, and `refusedOnRecord` (same action, same version, the intent file no newer than the record's `at`) keeps every later pass off the attempt. Boot admits a staged canary through `admitAndStart` (the guards it skipped before) and isolates a refusal; a pending replace candidate is left to the first pass. `DeployPen.status`, `MemberOrigins.lastVerdict`, `GET /_tesseraql/deploy/{name}` behind the deploy grant, the deploy page's verdict column, `deploy --url --wait`, `rollback --wait`, `deploy status` version-matched. Docs: `hosting.md` (five paragraphs), `stack-shells.md` ×2, `ops-console.md`, `DeployRoutes` Javadoc (unfiled 61). Guards — red on HEAD's `AppUpgrader`: a HEAD-compatible probe of `AppUpgraderTest.rollbackOfACandidateNeverApplied…` answered `1.0.1` (the never-applied version) where the fix answers `1.0.0`; red on HEAD's reconciler (6 of 18): `anAppliedReplaceCandidateMovesTheCatalogue`, `…PromotesAndMovesTheCatalogue`, `aRefusedReplaceCandidateLeavesTheCatalogueOnTheServingVersion`, `aRefusedCandidateIsAttemptedOnceUntilTheOperatorWritesAgain` (three passes, one attempt; a newer intent earns the second), `aRefusedCanaryStageIsAttemptedOnce`, the action/version in the record. End to end on the fix: `StackReconcilerIntegrationTest` Order 8 (a refused direct deploy, the catalogue on `2.0.0`, `gateway.close()` + start → serving, the record untouched by the first pass) and Order 9 (a refusing canary at a cold start → up, no canary, `refused stage v6.0.0`); `StackDeployIntegrationTest` Order 10 (the endpoint's 200, then `refused replace v5.0.0` through the GET and on the page) — HEAD's behaviour for these is the verdict's own measurement (probe 2(a), archaeology step 6, refuter B2/C), not re-run here: HEAD's three files against the new orders fail from Order 1 on, which proves nothing about Orders 8-10. `DeployCommandTest` (+4). Not done: U-3 (`deploy --wait` timing out on a no-change intent) — its trigger, re-deploying the served version to escape the refused state, no longer exists: the preflight refuses it and there is no state to escape. Trap: boot's canary path never ran `ModulesGuard` — the first Order 9 cut, a modules-refusing canary, started fine at boot and only the live path refused it; `admitAndStart` at boot is what made boot and live one function. |
| 8 | The compiler refuses what lint should have, with a route's name on it | unfiled 1, 4, 5, XD-07c, XD-07f, XH-22, XD-07g, unfiled 6, 35, 37 | M | **SHIPPED.** One predicate, `RecipeShape` (yaml/app), judges the pieces a recipe reads — the response arm (`TQL-YAML-1066`: json/redirect on the JSON recipes, html/file on the page recipes; `response: {session:}` alone and `html:` on a JSON recipe are the same refusal), each source's and step's arm (`TQL-YAML-1067`, an `http:` without `url:` included; a `sequence:` is a step's arm only), a file-import's `import:` block, row step and file (`TQL-YAML-1041`, `missingImportBlock`/`missingRowStep` beside `missingBlock`) and an export recipe's `main` file (`TQL-YAML-1041`, `missingMain` — `buildQueryExport`/`buildFileExport` dereferenced `main().file()` too; not in the atoms, same class). `RouteRules`/`ToolRules`/`ConsumerRules` report from it, `buildRoute`/`buildMcpTool`/`buildQueueConsume` refuse from it before any builder runs. `ViewSpec.parse` refuses a null `recipe:` as 3301. A missing SQL file (unfiled 6) is refused at both resolve sites — `execution()` after the dialect variant, `commandProcessor()` before the transactional processor reads — with the lint's `TQL-SQL-2103` (ANTICIPATED entry); slice 14's `RouteFiles.resolve` inherits the check. XH-22/XD-07g, the M shape: `ManifestLoader.BrokenDocument(source, code, error)` replaces `BrokenRoute`, the sink covers routes, jobs, consumers, workflows and mcp (`perDocument`), `AppLinter` loads through it and files each as one finding (the parser's `line: n, column: m` lifted), a whole-load refusal (config, shared definitions) is one finding at its file, `symbols` names the file, `RouteReloader` holds a broken workflow's transitions in a `held` ledger (recompiled in place on the fix, un-mounted on a delete) and prints the code on its failure line (35 closed by the CLI, 36's comment corrected here, 37 as 10a's addendum). XD-07f: `ConditionZone.of/problem` over `ExportDeclarations.zoneProblem` (`TQL-SEC-4147`, read as written like the files zone — decision 14's rule, the runtime's `trim` dropped), `ConditionZoneRules` after `SecurityDefaultRules`; the cron by decision 10's A: `CronExpressions.problem` over Quartz's `CronExpression` in `tesseraql-yaml` (`TQL-YAML-1068`), `JobRules.lintSchedule` (+ `fixedDelay:` as `TQL-YAML-1054`, the poll delay's) and `JobSchedules.schedule` before `Schedules.cron`, which F60 may still edit. Guards, each red on the neutered variant (every fix stubbed, the new types kept; the reloader's ledger proven on its own second variant): `AppLinterRecipeShapeTest` 7, `RecipeShapeCompileTest` 11 (the four message-less NPEs and the six named ones reproduced), `ViewSpecTest`/`AppLinterViewTest` +1 each, `AppLinterBrokenDocumentTest` 2, `ManifestLoaderTest` +1, `AppLinterHttpSourceTest` rewritten (the duplicate key is a finding now), `AppLinterScheduleTest` 3, `AppLinterConditionZoneTest` 2, `BootRefusalShapesIntegrationTest` 2 (Testcontainers — the pools open before either key is read), `RouteWatchIntegrationTest` +1 ("1 failed" naming `gone.sql`; HEAD served `TQL-ROUTE-5000`), `ReloadContentDiffIntegrationTest` +1 (`removed` empty, then `reloaded` not `added`), `AppLifecycleCommandsTest` +2 (`lint --format json` prints a document, exit 1; `symbols` names the job). Fixtures the rule caught: six lint fixtures without `response:` (datasource, duckdb tests) and the route-export fixture without `main` — written for other rules, never compiled. |
| 9 | An input is fed by what the route declares; a response is framed | XD-07j, XD-07l, unfiled 67-68, DN-02a, DN-02c, XD-09b, unfiled 20, 47, 50, 13 | M (2 PRs) | **9a SHIPPED.** `RequestSources` (yaml/app, one predicate): `header.<Name>` is a source on a `service:` binding's `params:` only — `TQL-YAML-1069` anywhere else (a statement, a rule, an enrichment, an export's `after:`) or naming no header — reported by `RequestSourceRules` on routes, tools and consumers and refused by `requireRequestSources` beside the recipe-shape check. `NamedQueryBinder` reads it from the wire (`Request.header`, first value, case-insensitive) for a service binding and from nothing else, so `?Cookie=x` no longer replaces the forwarded session on the delegated hop. The shells adopted it on **158** routes, not 109: the 109 `Cookie` inputs and 49 `X-CSRF-Token` inputs the measurement's header list did not carry (`shellCsrfHeader: params.X-CSRF-Token`) — both blocks deleted from `input:`, `header.<Name>` on the `params:`. Then `RequestBinder.rawValue` lost the header read and took `Request.param`'s order (path, query, form) with a JSON or programmatic body after it (unfiled 67 — one mechanism, the record's). XD-07l is `TQL-YAML-1070` (`body.<name>` on a GET, `export.timezone`/`locale` included; a snapshot-paginated page answers the pager's POST and is exempt) and unfiled 68 is `TQL-YAML-1071` (`body.<name>` the route does not declare while it rejects unknown fields — the guard refuses the request first). Guards, each red on its variant: `RequestBinderHeaderTest` 5 (3 red on HEAD's binder: `Priority: u=0, i` → `TQL-FIELD-2001`, `Host` satisfying `required`, the body outranking the query; the service row red on the naive fix), `HttpEdgeIntegrationTest` +1 (the helpdesk declaration under an explicit `Priority` header: 400 on HEAD, 200 now, and `?priority=urgent` still 400), `OpsShellIntegrationTest` (3 of 5 red on the naive fix — Orders 1, 3, 4, not only 3/4) and `StackStudioIntegrationTest` (3 of 7 red) as the bracket that proves the declared source load-bearing, `AppLinterRequestSourcesTest` 11 (7 red with the predicates stubbed) and `RequestSourcesCompileTest` 2. One fixture the rule caught: `AppLinterTest.quietOnAWellFormedValidateBlock` bound `body.name` on a route declaring no input. The schema's service-arm `params` description names the source; the scaffolder's `excludeId: params.id` on a create route (a bind wired to nothing, by `validation-rule-sets.md`) is `params.`, which the lint does not judge. Rider: the full verify surfaced unfiled 73 (a canary discard clobbering a standing refusal; slice 7's cold-start IT green by a 200 ms window) — fixed in the same PR with a deterministic unit row. **9b SHIPPED.** DN-02a: `ReservedHeaders.notAToken` (core) is the one name predicate — `ResponseHeaderDefaults.from` refuses a non-token name (`TQL-SEC-4135`) and a transport-owned one (`TQL-SEC-4139`, now a `TqlErrorCode` the lint reports through `ex.code()`, the config loop deleted), `ResponseHeaderRules.lintReserved` refuses a non-token route key (`TQL-SEC-4152`), `RouteEdge.wireHeaders` throws on one written by code (500 on the route's thread). DN-02c: `TQL-SEC-4153` WARNING keyed on `filename` + a `{placeholder}` in a declared `Content-Disposition`, `inline` untouched, JSON routes included. XD-09b/unfiled 20: `SizedBody` (core/http, a `FilterInputStream` carrying `length()`) produced by `FileTempStore`, `JdbcTempStore` (the staged copy's size, outside the cleaner's wrapper), `FileBlobStore`, `S3BlobStore` (the GET's `contentLength`) and `AttachmentDownloadProcessor` (the upload's `byteSize`), forwarded by `OpsShellRoutes.download` from the member's header; `RouteEdge.stream` writes `Content-Length` for a sized body instead of `setChunked`, and the HEAD carries it; `hosting.md` gains the `proxy_http_version 1.1` paragraph. Unfiled 47: `uriLiteral(rootTarget + query)`. Unfiled 50: the defaults first in `AssetRoutes.headers` and the SSE opening frame. Unfiled 13: the record's choice, documented — `unified-sources.md`, `glossary.md`, `app-mcp.md` (the recipe reads `rows[0]`); binding `first` at the four acquisition sites and the two rewriters is the alternative, not taken. Guards, each red on its variant (every fix stubbed with the new types kept): `ResponseHeaderDefaultsTest` +2, `AppLinterResponseHeadersTest` +5 (the existing reserved-default row now rests on `from`), `RedirectLocationIntegrationTest` +1 (a `"X Typo"` key: 500 in 10 s, a timeout on HEAD), `DownloadFilenameIntegrationTest.aDownloadDeclaresItsLength` (the inline export, the attachment, the transfer file, a raw HTTP/1.0 exchange, a HEAD; the attachment leg red alone on a variant sizing the temp store only), `StackRelayTest.theRootRedirectEncodesItsQuery` (raw socket: DEL → 307 `%7F`, a triplet kept; 502 on HEAD), `RouteAuditAndErrorPagesIntegrationTest.anAppWideCacheControlDoesNotReplaceTheAssetsOwn`. The MCP transport (unfiled 49) is corrected in `edge-hygiene.md` on the way. One shipped test flipped by design: `MultiAppGatewayDifferentialTest.aChunkedResponseWithNoDeclaredLengthKeepsItsBody` asserted an export answers without a length; it is `aSizedExportDeclaresItsLengthThroughTheGateway` now, and the chunked relay stays guarded at the relay with a stub origin. |
| 10 | The dev MCP server validates `Origin` | G1, G2, G6, G7, G5, G4, G3, unfiled 48 | M | **SHIPPED.** G1: `McpHttpHandler` judges the caller before the message, on both transports — a present `Origin` must be a loopback origin or one the embedder allowed (`TQL-MCP-4265`, 403; the runtime allows its external origin, the dev server what `--allow-origin` names; `null` and the unparseable refused), a `POST` must declare `application/json` (`TQL-MCP-4266`, 415 — the one type a browser cannot send without a preflight), a request after `initialize` carries its `Mcp-Session-Id` (`TQL-MCP-4268`, 400), and `HttpTransport` reads the body to 10 MiB and no further (`TQL-MCP-4270`, 413; a declared length past it refused unread). G2: `ex.getMessage()` alone at the three catch sites — the sentence begins with the code once. G3, the two legs that survived: `McpServer.handle` dispatches a JSON-RPC batch element by element (an array answer, nothing for a batch of notifications, `-32600` for an empty one or one past 100, `initialize` refused per element) on both transports, and a present `MCP-Protocol-Version` outside `SUPPORTED` is `TQL-MCP-4267` (400) — `SUPPORTED` untouched, as decided. G7: the parse failure is the JSON-RPC `-32700` envelope both transports now build in one place (`McpServer.parseError`; an empty body is the same failure, not the 200 the dead null branch let through), an unknown session is `TQL-MCP-4269` (404), a serialisation failure is `-32603`; `errorBody` deleted; `ErrorEnvelopeLedgerTest` gains the mapper-built predicate (a fresh object whose first key is `error`), red on the deleted method. G6: `StackIssuer.apply` refuses a declared `tesseraql.mcp.resource` under the stack issuer (`TQL-OAUTH-3005`, the key-source refusal's shape), the "override included" audience arm deleted, `oauth.md`'s `urn:` example gone. Unfiled 48: `McpRoutes.resource`/`metadataUrl` spell the resource and the document address through `PercentEncoding.uriLiteral`; `StackIssuer.audiences`, `OAuthRoutes.resourceMetadata` and `AuthorizeFlow.memberOf` (both sides) agree on that spelling, and the mint stores the resource as the wire spells it. G4: one `describe` in `McpInputSchema` for the top level and the object element, carrying `pattern`, `minLength` and the `email`/`uuid`/`url` (as `uri`) formats. G5: `app-mcp.md` says what the fragment is in a host (a static snapshot of its own query, unstyled and inert unless the template links the kit, never party to the handshake), `roadmap.md` no longer says "interactive", the `'self'` example and both fixtures are an origin. Guards, each red on its variant: `McpHttpHandlerTest` +10 (origin, loopback, allow-list spelling, `null`, content types, revision, session, parse/empty, batch), `McpServerTest` +8 (code once ×3, batches ×5), `StdioTransportTest` +1, `HttpTransportTest` +2 (the headers over a real socket; a declared and a chunked body past the ceiling), `AppMcpToolIntegrationTest` +1 (the runtime bridge hands the headers through), `StackIssuerTest` +2 (the refusal; the Japanese member's audience), `AuthorizeFlowTest` +1, `McpInputSchemaTest` +1, `OAuthIssuerUnificationIntegrationTest` +1 with a second member `受注` (the wire-spelled document, a challenge without `?`, a grant for that resource opening the surface and running the tool) — which surfaced unfiled 74, fixed here. |
| 11 | The expression language fails as coded, and once | G11, G12, G13, G14, G15, F121, unfiled 11, G9, G10 | M | **SHIPPED.** G11: `Expr.UNEVALUABLE_OPERAND` (`TQL-SQL-2122`) is the one coded refusal for every evaluation-time failure — a relational operator on a `null` (the sentence names the side and the guard `x != null &&`) or on two values of unrelated kinds (the kinds named, never a value: an operand may be a claim), arithmetic on a non-number, `/ 0` and `% 0` (the finder's aside, same class), a non-literal `matches()` pattern that does not compile. **Not the 400 this row planned:** two-way-sql-parser.md decision 10 had already decided the shape for exactly this class — the request is ordinary (an optional input left out), the template is what is defective, so `2118` answers 500 and says so in the switch — and `2122` follows it with its own explicit `case` and reason. Not `false` either: a `validate:` rule reading a false reports an optional field the caller omitted as a violation; `==`/`!=` stay null-safe. G14: two numbers compare through `Arithmetic.decimal(...).compareTo` (a non-finite double keeps the IEEE ordering; `10 == 10.0 == 10.00` still holds). G12: `property()` reads a present map key before the virtual answer; the absent-key and #1190 answers are unchanged. G13: the lexer knows `\'`, `\"`, `\\` and refuses any other backslash as `TQL-SQL-2101` naming the doubled spelling; `ExpressionDepthTest:63` corrected (it asserted `\d` and tested `d`). F121/unfiled 11: `Expr.Call` carries a `regex` component the parser fills from a literal second operand (a bad literal is the parser's 2101 — lint and boot see it); a non-literal compiles per evaluation and is held nowhere; `PATTERNS` deleted. G15: `getterValue` tries the bare name only on a record and only for a method the record itself declares (`Principal.claim()` kept), `get`/`is` elsewhere, never a static, never `getClass`/`toString`/`hashCode`/`wait`/`notify`; `IllegalArgumentException` caught beside `ReflectiveOperationException` (the static-match crash); `fieldValue` skips a static; the Javadoc's phantom "ch. 20.6" and the two doc sentences replaced by what is true; enums keep no bare-name arm (`color.name` — no doc or app wrote one). G9: `LintContext.sqlNodes` reports the parse failure once per file with the parser's code and line; rider: `Sql2WayParser.parseExpression` rebuilds a directive expression's 2101 with the directive's line and text, closing two-way-sql-parser.md's open item for the SQL surface; the five "its own lint's concern" comments retired; `LintContextTest`'s SQL half flipped. G10: `headersWhen:` (both arms, `InputRules`), a step `when:` (`DocumentRules.lintStepGuards` from Route/Tool/ConsumerRules) and `recipient:` (`MessagingRules`) each a positioned `TQL-SQL-2101`; `DecisionRules`' comment now names who reports. Guards, each red on the neutered variant (every mechanism back to HEAD's logic, the new types kept): NEW `EvaluationContextTest` (4 of 6 red; the two controls green), `ExpressionParserTest` +6 (all red), `ValidationRulesTest` +3 (the rule path: coded refusal, the documented guard, the bad literal at compile), `Sql2WayParserTest` +1, `LintContextTest` (flipped +1), NEW `AppLinterExpressionTest` 8 (7 red; the well-formed control green; the injection lint's fail-open reproduced then closed). Not done, filed: a lint for `<`/`>` on an input that is not `required` (the 2119 twin — the refusal is loud now, so the lint is DX), and parsing a query route's SQL at boot (camel-removal decision 2's choice; the lint is the gate). |
| 12 | The suite runner says what it proved | G16, G17, G18, G19, G20, G21, G22, G23, F115, unfiled 27, 10 | M | **SHIPPED.** G16: `ManifestSqlFiles.of(manifest)` (test-core) enumerates every bound SQL file — route/consumer/tool/resource sources, steps, enrichments, validation files, an export's `after:`; job steps, chunk reader and writer; workflow commands and guard files — and `AppTestRunner.run` declares each to `SqlCoverage.declare(id, coverable lines, branch lines)` before the first case (`SqlCoverableLines.branchLines` is the static twin of the renderer's `recordBranch` keys, every `if`/`elseif`/`else` arm); `SuiteContext.sqlId` normalizes both sides so a declared id and a recorded id are one entry; `test`/`coverage` print a stderr line when no suite file exists. Leg C of the finding (a failing case still covers its lines; `coverage` does not fail on failed cases) is the documented split and was not touched. G17: `TransactionControl` (core/sql) scans the raw file for `COMMIT`/`ROLLBACK`/`START TRANSACTION`/`SET TRANSACTION` and a bare `BEGIN`/`BEGIN WORK|TRANSACTION|TRAN` at a statement start, outside `--`/`/* */` comments, literals, quoted identifiers and `$tag$` bodies; `Sql2WayParser.parse` refuses the file as **`TQL-SQL-2123`** with the line, so lint, the runner, the compiler and the sandbox answer with one code and no surface needed its own check. G18: `TestCommand.writeOverlay` mirrors `ReportMojo` — `ReportHistory.isCorrupt` → `ReportHistory.corrupt(file)` (`TQL-REPORT-2006`, shared sentence) thrown under `--fail-on-regression` (exit 2, file untouched), printed as a warning without; the `@JsonIgnoreProperties` half was not done (rule 10 — the guard makes an extended entry fail closed with the file named). F115/decision 1: `return regressed ? 3 : 0`, `ExitCodes.SKIPPED` widened to "the command ran and a policy gate said no" (cli-surface **10b** written); `--bogus` and a non-app `--app` keep their 2. G19: `AppTestRunner.run` refuses a case name declared twice across suites (**`TQL-YAML-1410`**, both files named) and a `--case` set naming any unknown case (**`TQL-YAML-1411`**) before anything runs or is written. G20: `ValidationCases`/`NotifyCases`/`WorkflowCases` compile through the registry-taking overloads with `context.functions()` (the decide kind parses no expression — the reproduce lens's correction); `StudioTestService` builds the 6-arg `TestRunner` with the runtime's registry; `ReportMojo` gains the install line. G21: `StudioService.typedQuery` types each query value by the route's `input:` (`integer`→long, `number`→double, `boolean`→boolean, else the string; an unreadable value stays a string) for both the recorded case and the sandbox capture's context. G22: `CoverageThresholdResolver` reads every `coverage.thresholds.*` key as written (the `KINDS` allow-list deleted); `CoverageGate.check` names a threshold whose kind the run did not measure, with the measured kinds — a typo gates nothing no more. G23 (delete, the adjudicator's default): `RouteTestRunner`, `RouteSuite`, `RouteTestRunnerTest`, the whole `io.tesseraql.coverage.plan` package with its seven tests, `TqlDomain.PLAN`, coverage-core's seven test-scoped vendor/Testcontainers dependencies, the dialects.yml "Plan guards" step, the build.md/release.md commands; the three ledger tests dropped their entries; vertx-native/http-edge/camel-removal name the runtime's JUnit integration tests as the acceptance, README/roadmap/procurement-demo/temporal-semantics/cli-surface/SarifReporter reworded. Unfiled 27: `TestCommand`/`CoverageCommand` open the first connection before the suites (the handler's class-08 shape at 1); unfiled 10: `SchemaGenerator` carries the `SQLException` as the cause. Guards, each red on the neutered variant (every mechanism back to HEAD's logic, the new types kept; scratchpad `variant12.py`): NEW `TransactionControlTest` (the parser-wiring row red; the three detector rows are the detector's own), `SqlCoverageTest` +2 (declare red; `branchLines` is the renderer's keys), `CoverageGateTest` +2, `CoverageThresholdResolverTest` +1, NEW `AppTestRunnerTest` 3/3 (population, 1410, 1411 — no database reached), NEW `SuiteGuaranteesIntegrationTest` 2/2 (the commit file fails with 2123 and leaks nothing where HEAD passed it and leaked; the MCP shape's registry), `AppLifecycleDbCommandsIntegrationTest` +5 methods all red (3 not 2 with `--bogus`/non-app at 2; corrupt history 2 + untouched, warning without the gate; `--case` 2 + the no-suite line; unreachable `test` at 1 without FAIL lines; unreachable `schema` at 1 without 2007), `StudioServiceTest` +1 row, `StudioIntegrationTest.tryConsoleRecords…` extended (`limit: 10` written as an integer and the recorded case's badge is pass). Not done, filed: a lint for a `coverage.thresholds` key in `config/` (the gate's refusal is the honest place — it knows what was measured); eager parsing of a query route's SQL at boot (slice 11's note stands). |
| 13 | A declaration is judged once, on both altitudes | XD-07a, XD-07k, XD-07b, unfiled 3, XD-07i, EH-06, XD-07d, TS-03, unfiled 14, 34, 33 | M | The `principal.` arm requires `claim.<name>` (and retires the undocumented `claims.`); a `default:` is coerced and validated at `InputBinder` construction with a lint twin; one `audiences()` predicate and one two-key policy refusal both sides call; the charset and OWS lints; the query-export `after:` arm; the chunk `result:` arm in `requireJob` and the released CHANGELOG note corrected; the `result:` sweep over export and `http:` sources; the `push.as:` grammar aligned; `principal.sub` struck. Guard hazard: a lint↔boot differential is green on a shared defect — these are ONE predicate on both altitudes, so each guard feeds the broken variant too. |
| 14 | A route's files are the reloader's fingerprint and the app home their fence | XH-13, XH-14, unfiled 6, 7, 36, 52 | M | `fingerprintsOf` digests per route the files the compiler resolves (main/sources/steps/template/`after.sql`/rowStep/lookup SQL); a referenced file outside the watcher's roots is documented; one `RouteFiles.resolve(appHome, dir, declared)` in `tesseraql-yaml` for every codec's template, `sql.file` and the html template, shared by lint, admission and the compiler (the missing-file boot refusal shipped in slice 8 at both resolve sites; the resolver inherits it). The existing reload guards write every file beside its yml and are green on the hole. |
| 15 | An export says how far it got, and stops when told | XH-26, unfiled 8, XH-02 residue, unfiled 44-45, XH-12, XH-08, XH-04, unfiled 9, 43 | M | A progress flush on the import's cadence through its own connection (RUNNING and FAILED carry the rows reached); Cancel stops the run (STOPPED, 409 on `/file`); a COMPLETED export whose spool is gone is coded; an `Error` releases the writer spool; a stop marks the execution; the template spelled route-relative in both codecs; then the split-export line's decisions (case fold, combining marks, emoji — XH-04, XH-05, unfiled 9) taken once and the 2856 sentence corrected. XH-08 stays filed. |
| 16 | An error carries its cause, its code its raise site | F107, DN-06b, XD-07h, XH-11, F109, unfiled 12 | S+S+S | The 26 `SQLException` wrap sites carry the cause (the `schema` verb first); `ErrorIndex` skips comments and prose (regenerate; 110 links change); 1409's string twin deleted; the transfer span carries `app` and the unattributed-root policy is decided once (`OpsScope.compose` is one predicate for five tables); `OpenTelemetryMeter` memoises; `ErrorCodeUniquenessTest` sees FQN and anonymous declarations. |
| 17 | A catalog is what its readers read | XD-04a, XD-04b, XD-04c, unfiled 19, 62-64, 32 | S | Delete TQL-FIELD-4622, its four cases and the dead `CatalogBinder(fixedLocale)` step; rewrite `lookups.md` decision 12's row, 13a and `code-catalogs.md:122-130`; a never-loaded `table:` catalog is a coded, logged, non-retried refusal that does not take every route down; the dead export fixture requested or removed. |
| 18 | Temporal residue | TS-04, XD-12, unfiled 15, 38, 51, XD-01 R1-R2, XD-02 R3/R5/R7 | S (docs) or M | The user chooses TS-04's shape (declarations applied after `readRows` with domains, or one documented clause); strict grouped parsing gated on the separator; `bytea` on the three grids; `groupBy:` on a pdf template linted; the jxls time-of-day column; `type: time` decided or filed on its own bullet; the stale T3 ledger struck; the Studio double execution and the retired `sql` key. |
| 19 | The Studio preview renders what the route renders | XH-10, XH-19 | M | `PdfRender.render` carries the render context minus `main` and the declared sources (the documented header-and-lines template previews); `FileDefaults` threaded, `literal()` first. |
| 20 | A notification renders in a named locale | XH-20, DN-06d, unfiled 53-56 | S | `Locale.ENGLISH` at the three sites and the locale-less list documented; two explicit MIME headers on the attachment; the attachment name folded; `[(#{key})]` in a subject/title; the inbox title's truncation in bytes on Oracle; `lookups.md:338` struck. |
| 21 | The editor follows a bindable path | EN-01, EN-02, EN-03, unfiled 21-23, 41, 42 | M | `pathReferenceAt` in `symbols.ts` on the value shape `<root>(.segment)*` (model/body/payload/params/location), a root → the route's own `sources[]` line or a job's `- id:` line; `routes[].embeds` in the contract for embedded views; the two view lints (3308 over embedded/`views:`-bound sources, the document-level `source:` judged), the site's `source: sql` spellings, the BOM line in `viewIdInfoOf`. EN-04 and EN-05 are recorded as not worth doing (the scan stays). |
| 22 | HEAD and the first download | EH-07, unfiled 26 (F57), EH-03, EH-08, unfiled 71-72 | S+S | A HEAD does not spend the claim; the claim and `after:` in one transaction (F57's other half); the E4 IT adds the HEAD-of-download row; an IT for the application half of `TransferScope`; the `mountRoute` comment and the record sentences; the Vert.x HEAD-over-h2 report filed upstream. |
| 23 | Sweeps: docs, frontend, dx | F83, F84, F85, F116, F122, DN-03d, F94, F95, F100, F101, F102, F103, DN-01c, unfiled 24, 25, 30, 31, 46, 49, 65, 66, 69, 70 | S each, one or two PRs | The stale distribution sentences; the package-manager upgrade bullets; the sidebar pointer; the memoization claim struck; the goal → verb parity sentence (reuse `ReadmeLedgerTest`'s `@Mojo` scan); the schema description split per arm and `route-v1:531/577` corrected (regenerate; keep the `.vscode` copy); `wrangler@4` in `DEPLOYMENT.md`; both setup comments; the dialog's `aria-labelledby`; F101 decided; briefs 13-15 with the `data-tql-*` guard; F103's comment and the fill chain's guard; the `rows` template key and its two stale comments; the bare base + query and the wire-spelled `_return` in `BasePaths.relative` (DN-01c, unfiled 69); member bind address in `host`/`dev`. |

The in-slice calls the measurement made stand as written (decided 2026-09-15): G3 keeps
`SUPPORTED`; F108 gets the log line only; F103 gets no class swap.

**Not scheduled, on the measurement's own verdict:** DN-02e (the IDN trigger has not fired;
`PercentEncodingTest` pins `[` by decision 7), DN-06c (touch when one of the two files is open),
EN-04 and EN-05 (worth no — the scan stays), XH-08 (none; a spool-format change), EH-08 beyond
the upstream report, and the eighteen note-only atoms of `POOL.md`.

### Decisions this campaign needs before its slices run

1. **The exit code of a coverage regression (slice 12).** Decision 10 published 2 = "nothing ran";
   `test --fail-on-regression` ran and found a regression. Recommendation: 3, widening
   `ExitCodes.SKIPPED`'s sentence to "the command ran and a policy gate said no" (decision 10b);
   1 is the acceptable alternative; 2 is the one answer the published vocabulary rules out.
   *Decided 2026-09-15: as recommended — 3, and `SKIPPED`'s sentence widened (decision 10b).
   Shipped in slice 12; `docs/cli-surface.md` 10b records it.*
2. **Who writes `catalog.json` (slice 7).** Structural decision 2 makes the catalogue the CLI's
   intent file; the boot reads it as the served version. Recommendation: the CLI writes
   candidates, the host writes the catalogue and the status on an applied replace; N hosts on a
   shared root each write the same bytes (benign, stated). Without this the slice cannot be built.
   *Decided 2026-09-15: as recommended — the host writes the catalogue and the status; the CLI
   writes candidates; the addendum to structural decision 2 lands first, in the same PR.*
3. **A declared header source (slice 9a).** Which names may be header-sourced, and whether a
   declared header input is overridden by query/body (today `?Cookie=x` outranks the header on
   the delegated hop). Recommendation: `header.<name>` in service `params:` only, never overridden,
   the fallback deleted, recorded as a breaking change.
   *Decided 2026-09-15: as recommended — `header.<name>` is legal in service `params:` only, never
   overridden by query or body; the order is declare → adopt on the 109 routes → delete the read.*
4. **The unattributed root in the ops traces (slice 16).** `OpsScope.compose` admits `app == null`
   for the wildcard grant (restoring `c047b6c60`) or the Javadoc changes; the same line scopes
   five tables. Recommendation: admit it for traces only, as a separate predicate.
   *Decided 2026-09-15: as recommended — traces only, its own predicate; the table scope keeps
   #692's fence; the guard goes through `OpsScope.view(...)`, never a hand-built predicate.*
5. **The split-export fold (slice 15).** Case-insensitive collisions refused (flips the pinned
   2857 test) or suffixed; combining marks kept; emoji. Recommendation: refuse, once, with 2857's
   sentence naming both keys.
   *Decided 2026-09-15: as recommended — refuse with 2857, the sentence saying the two keys are one
   file on a case-insensitive filesystem; combining marks kept under NFC; reserved DOS stems
   prefixed; the pinned `SplitExportTest` row flips.*
6. **The transfer subtree under tenancy (slice 3b / G31).** EH-01's product decision, now with a
   security finding behind it. Recommendation: scope by tenant where the app is tenanted.
   *Decided 2026-09-15: as recommended — a `tenant_id` column (V15, three dialects), the `/file`
   leg under `applyCommonGovernance`, `TransferScope` with a tenant term; subject scoping stays a
   separate product decision.*
7. **`type: time` (slice 18)** — add it to the vocabulary or file it on its own bullet.
   Recommendation: file it; the export side has no time-only column in any shipped example.
   *Decided 2026-09-15: as recommended — filed on its own bullet in `temporal-semantics.md`.*
8. **TS-04's shape (slice 18)** — apply `result:` declarations after `readRows` in the Studio
   browse (M) or one documented clause (S). Recommendation: the clause now, the M when the preview
   is next touched.
   *Decided 2026-09-15: as recommended — the documented clause now; the M rides the next change
   to the preview seam (slice 19).*
9. **F101** — `hc-container` on the six standalone pages, or one sentence recording the inline
   frame. Recommendation: the sentence.
   *Decided 2026-09-15: as recommended — one sentence in `console-ux-refresh.md`, as a rider.*
10. **The cron lint's dependency (slice 8)** — Quartz is `tesseraql-runtime`-only; a cron lint in
    `tesseraql-yaml` needs the parser or a recorded exception. Recommendation: shape the boot
    refusal now; sequence the lint with F60.
    *Decided 2026-09-15: as recommended — the boot refusals shaped and the zone lint in slice 8;
    the cron lint waits for the dependency decision, sequenced with F60.*
    *Decided 2026-09-16, before slice 8 ran: A — Quartz becomes a compile dependency of
    `tesseraql-yaml`, declared as the runtime declares it (`CronExpression` only, c3p0 excluded);
    the lint and the boot then share the one grammar the scheduler fires by, on the Maven `lint`
    goal (which has no runtime) and the CLI alike. Rejected: an SPI the runtime supplies (the same
    document lints differently per surface), `cron-utils` (a second grammar, drift at the edges),
    a hand-rolled grammar (prefer the library), vendoring the class (a fork). The refusal sits in
    `JobSchedules` before `Schedules.cron`, so F60's zone parameter does not collide.*

### Rules carried into every slice

- Build the broken variant and run the guard against it. Four guards in the HIGH pass and one in
  the medium campaign were green on their own defect, and this measurement found seven shipped
  tests green on the defect beside them (`OpsDashboardTest:145-147`, `StackReconcilerTest:266-278`,
  `uiDataBrowserExportOfAnUnreadableTableIsANote`, `AppLinterDeclaredKindsTest:182`, the reload
  guards, `TransferScopeTest`, `headAnswersIdenticallyThroughTheGateway`); re-reading never caught
  one. The table names each slice's variant.
- A lint↔boot differential is green on a shared defect when both altitudes call one predicate
  (slice 13); feed the broken variant to both.
- `-am` builds dependencies, not dependents: add `tesseraql-docs-reference` to the verify set.
- Any `TQL-<DOMAIN>-<n>` literal in a source file becomes a row in the generated error reference
  (until slice 16 lands); name codes by constant.
- Two `dev` processes on one port answer as one, and `pkill -f` on the port string kills the
  calling shell — kill by PID file; a published Docker port is `host.docker.internal` from the
  devcontainer, not `localhost`.
- A probe source compiled by the Java launcher reads `file.encoding`: keep probe sources ASCII
  with `\uXXXX` escapes.
