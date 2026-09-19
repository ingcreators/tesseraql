# A download's name is a template on both altitudes: `{dotted.path}` in a route's `filename:` resolves against the request

> **Status: designed 2026-09-19 (#1399); S1 shipped the same day.** The direction was decided
> by the user — docs/audit-low-leads.md DN-03d left "the lint companion" as a design call
> between refusing a placeholder on a route's `filename:` (A) and resolving it (B); **B was
> chosen.** One implementation slice.
>
> **S1** — one resolver in core for the three route sites and the job step; the fold every
> value goes through; the roots each site resolves, judged by one predicate at lint and at
> build; the Studio data export names its table: **shipped, #S1_PR** (every decision as
> recommended; the nine variants of `scratchpad/variant_s1.py` each red on exactly the guard
> the table below names; `StepContextInterpolateTest`'s empty-value row flipped to `_` by
> decision 4).

A route declares the name a download is offered under in three places: `export.filename` on a
`query-export` or `file-export` route, `response.stream.filename` on the streaming spelling of
the same, and `response.file.filename` on a template-rendered file. A job's export step
declares the same key. On the job the value is a template — `{batch.businessDate}` resolves
against the step context (`StepContext.interpolate`) — and on the route it is a literal: the
compiler hands it to the `Content-Disposition` writer as written, and only `{key}`, the split
export's own placeholder, means anything. Slice 23a of docs/audit-low-leads.md made the schema
say so ("On a route it is a literal name except `{key}`") and left the question of whether the
route should learn the job's grammar or refuse it. This record answers it: it learns it.

Registered in `docs-site/nav.mjs` `EXCLUDED` and `ErrorIndex.INTERNAL_DOCS` by this record.
Site-excluded, so the prose lint does not read it; `sync-content.mjs` and `InternalDocsSyncTest`
do.

## What was measured

Measured 2026-09-19 on main `6e3cb93a8` (0.18.0-SNAPSHOT). Rows 1-3 are RUNs from the installed
jars and the compiled extension; rows 4-7 are readings of the code.

| # | Run or reading | Result |
| --- | --- | --- |
| 1 | `tesseraql lint` on a copy of `examples/user-admin-app` whose export route declares `input: status` and `filename: users-{params.status}-{now}-{batch.business-date}.csv`, and whose print route declares `filename: users-{path.id}-{main.rowCount}.pdf` (no `{id}` in its path) | **One finding, unrelated** (`TQL-LD-5310`, the pdf `maxRows:` warning). A declared parameter, a root no request carries, a spelling the grammar does not resolve, a path parameter the route does not have and a source member all pass lint silently. |
| 2 | `ContentDisposition.attachment("users-{params.status}.csv")` and `attachment("受注-{path.id}.pdf")` from the installed core jar | `attachment; filename="users-{params.status}.csv"` and `attachment; filename="__-{path.id}.pdf"; filename*=UTF-8''%E5%8F%97%E6%B3%A8-%7Bpath.id%7D.pdf`. **The braces reach the wire**; the sanitizer folds quotes, controls and format characters, not braces (correctly — it is the last writer, not a resolver). |
| 3 | The compiled extension's `pathReferenceAt` on `  filename: users-{params.status}.csv` (cursor in the placeholder) and `  filename: {main.rowCount}.pdf` | `{"root":"params"}` and `{"root":"main"}` — **the editor already reads a `{…}` in a filename as a bindable path** (slice 21's value-shape detector; the `filename` entry of `NOT_A_PATH_KEY` governs only the whole-scalar spelling, `filename: params.status`, which stays a literal name). `pathCompletionAt` after `filename: users-{pa` → `undefined`: the request roots are offered nowhere, by slice 21's decision. |
| 4 | Where the three route names go (`RouteCompiler.exportFilename`, `:1890`) | `query-export` and `response.stream.filename` → `SqlStep`'s `filename` field (`:68`), written at `:375` as `ContentDisposition.attachment(split ? zipName(filename) : filename)` and passed to `ExportWrite.write` for the split entries; `file-export` → `FileExportStartProcessor.filename` → `ExportRequest.filename` → the transfer row's `filename` column (`insertTransfer`, `varchar(500)`), read back by the status JSON, the HEAD, the GET and `download()`; `response.file.filename` → `FileResponseRenderer:48`, beside the `model:` resolution that already builds an `EvaluationContext` over `TesseraqlProperties.CONTEXT`. Every site has the exchange in hand when it fixes the name. |
| 5 | What the request context carries when a route fixes the name (`RequestBinder:158-205`) | `query`, `params` (declared inputs only — `InputBinder` whitelists), `body`, `path` (the URL's `{name}` parameters), `principal`, `tenant`, `flags`, `request.locale`, `preference`. A `response.file` renders after the route's sources ran, so their names are present too (`<source>.rows`, `rowCount`, `truncated`); `EvaluationContext` walks maps and virtual `size`/`empty` — **not list indexes**, so no row value is addressable from a route. The job's roots (`PushStepRules.CONTEXT_ROOTS`): `params`, `steps`, `batch`, `tenant`. |
| 6 | The two grammars in the tree | `io.tesseraql.yaml.app.FilenamePlaceholders.RESOLVED` = `\{([\p{L}\p{N}_.]+)}` — the job's (`StepContext:440`) and `push as:`'s (`PushStepRules:35-39`); `Interpolation.PLACEHOLDER` = `\{([^}]+)}` — the header/redirect/view-link grammar (`compiler.binding`, package-private). The filename grammar is the narrower one on purpose: a filename placeholder is a path, never an expression. **`SplitExport.safe(key)`** (`:288`) is the one fold a value takes to become a filename component: NFC, `[^\p{L}\p{M}\p{N}._-]` → `_`, a leading or trailing run of dots → `_`, blank → `_`, cut at 100 graphemes; `entryName` then guards the Win32 device stems. It runs for `{key}` only. |
| 7 | Census of `filename:` in the tree and the docs | 12 route documents (4 examples, 8 Studio), every one a literal (`users.csv`, `data.csv`, `tesseraql-oidc.yml`, …); 1 job (`daily-price-report.yml`, `price-summary-{batch.businessDate}.csv`); 6 docs pages show the job spelling. **No route filename in the tree or the docs carries a brace**, so resolving one breaks no shipped name. The Studio data export (`tql.studio.data.export`, `input: table` required) names every table's download `data.csv`. |

## The mechanism

A filename is a template of the job's grammar, resolved **once, where the name is fixed,
against the context that site already holds**, each resolved value **folded** by the rule a
split key already obeys, and the whole name then written by the one `Content-Disposition`
writer as today. The three route sites and the job step call one static resolver; the lint and
the build judge the template by one predicate whose root set is the site's.

```
filename: orders-{params.month}-{key}.csv        (route, splitBy: region)
          │       │              │
          │       │              └ {key}: passes through; the split writer replaces it per group
          │       └ resolved against the request context → "2026-09" → folded → "2026-09"
          └ literal text, as written
→ per group: orders-2026-09-east.csv …   bundle: orders-2026-09.zip (zipName of the resolved name)
```

The resolver lives in core, in the package that already owns the fold and the split, so the
pipeline (`SqlStep`), the compiler (`FileExportStartProcessor`, `FileResponseRenderer`) and the
operations module (`StepContext`) reach it without a new dependency; `tesseraql-pipeline` does
not import `io.tesseraql.yaml` today and does not start to.

## The decisions

Each is recommended; the user decided the direction (B) on 2026-09-19 and the rest are the
shape that direction takes.

### 1 — A route's `filename:` resolves `{dotted.path}`, on all three sites (B over A)

**Recommended: B, as chosen.** Refusing (A) would have enforced the sentence slice 23a wrote;
resolving makes the sentence unnecessary and gives the route what the job has had since the
export pipeline campaign. The three sites resolve the same way: `export.filename` and
`response.stream.filename` at `SqlStep` (per request, at the disposition — the same resolved
name feeds `ExportWrite` so the split entries carry the value too), `export.filename` on a
`file-export` route at `FileExportStartProcessor` before the `ExportRequest` is built (the
transfer row records the resolved name, so the status JSON, the HEAD, the GET and `download()`
all say the name the request asked for, with no second resolution), `response.file.filename`
at `FileResponseRenderer` with the `EvaluationContext` it already builds for `model:`. No site
resolves twice; no name is resolved after it is recorded.

### 2 — One resolver, in core, and the yaml class moves to it

**Recommended.** `io.tesseraql.yaml.app.FilenamePlaceholders` becomes
`io.tesseraql.core.files.FilenamePlaceholders` — the grammar (`RESOLVED`, `WRITTEN`,
`resolves`) unchanged, plus `resolve(String template, EvaluationContext evaluation)`: every
`RESOLVED` match is looked up as a dotted path (the split on `.` that `StepContext` and
`Interpolation` both perform), `{key}` (`SplitExport.KEY`) is appended as itself, every other
value is folded (decision 4) and appended; a spelling the grammar does not match stays literal,
braces on, which is what the lint refuses (decision 5). `StepContext.interpolate` becomes one
call to it; `PushStepRules` imports the moved class. The move is a rename of a class no
application code names (its package is `yaml.app`, not a published contract); the record notes
it and nothing else.

### 3 — The roots a site resolves are the roots its context carries — no more, no fewer

**Recommended.** A **route** resolves the request roots of row 5: `params`, `query`, `path`,
`body`, `tenant`, `request`, `flags`, `preference`, and `principal` when the route is
authenticated (`Site.principal`, the fact `ExportDeclarations` already computes for the
`export:` source arms); a `response.file` additionally resolves the route's `sources:` names.
A **job step** resolves `params`, `steps`, `batch`, `tenant` — `PushStepRules.CONTEXT_ROOTS`,
which moves to the shared judgement so `push as:` and the export step's `filename:` agree. The
runtime resolves whatever the context holds (an `EvaluationContext` has no root list); the
root set is the **lint's** knowledge of what the context will hold, and it is stated in one
place so the two altitudes cannot disagree about it.

### 4 — Every resolved value is folded by `SplitExport.safe`; a missing value is `_`

**Recommended.** The fold that makes a group key a filename component (row 6) is the fold a
request value takes: NFC, anything outside letters, marks, digits, `.`, `_`, `-` becomes `_`,
a leading or trailing run of dots becomes `_`, the value is cut at 100 graphemes, and a blank
or absent value is `_`. So `{params.month}` with `2026/09` names the file `orders-2026_09.csv`;
with `../../etc/passwd`, `orders-__.._etc_passwd.csv` — a name, not a path; with `a"b\c`,
`orders-a_b_c.csv`; with nothing, `orders-_.csv` (all measured on the installed jar). The Win32
device-stem guard (`CON` → `_CON`) stays `entryName`'s — an extracted zip entry is a file the
recipient did not choose; a download is placed by a browser that names its own saves. The
`Content-Disposition` writer still runs on the whole name (row 2), so the literal text of the
template keeps today's protection and the value gains the split key's.

A **job's** `{batch.businessDate}` is unchanged by the fold (`2026-09-19` is all safe
characters); two things do change on the job, both recorded under "What this breaks": an
absent value renders `_` where it rendered empty (`delivered-.zip` was the silent shape
`PushStepRules` exists to catch), and a value carrying a separator is folded where
`FilePushService.push` used to refuse the whole delivery ("must be a plain file name"). The
fold is preferred over the refusal because the same value in a route's name must reach the
wire as a name, and one value must mean one name on both altitudes.

### 5 — One judgement, `TQL-YAML-1076`, on both altitudes, at every site

**Recommended.** A new `io.tesseraql.yaml.app.FilenameTemplates` (yaml, beside
`ExportDeclarations` and `ResponseLiterals`) holds `UNRESOLVABLE_PLACEHOLDER =
TQL-YAML-1076` and `violations(key, template, Site)` with four arms, each an ERROR because the
delivered name would be wrong:

1. **malformed** — `WRITTEN` but not `RESOLVED` (`{batch.business-date}`, `{now()}`): "not a
   dotted path of letters, digits, `_` and `.` — the runtime resolves no other spelling and
   delivers it literally" (the `push as:` wording, shared);
2. **root** — the first segment is not one the site resolves (decision 3): "names no
   {request|job context} root — it would render `_`";
3. **input** — `params.<name>` or `query.<name>` names an input the route (or job) does not
   declare under `input:` — `InputBinder` whitelists, so it would render `_`;
4. **path** — `path.<name>` names no `{name}` of the route's URL path. The `Site` learns the
   route's path parameters (`RouteFile.urlPath()` is in both callers' hands) as one more
   component, computed once like `inputs`.

`{key}` is not this rule's: a `{key}` without `splitBy:` stays `TQL-YAML-1041`, a `splitBy:`
without `{key}` stays `TQL-LD-2858`. The rule runs where the templates are already judged:
`ExportDeclarations.violations(site, spec, …)` gains the arm for `export.filename` (so the
route's lint and build, the job's lint, `requireJob` at boot and `tesseraql job` all carry it
with no new call site); `ResponseLiterals.violations` gains it for `response.file.filename` and
`response.stream.filename` (lint and the three compiler `requireResponseLiterals` sites);
`PushStepRules.lintDeliveredName` keeps `TQL-YAML-1042` and its own wording but takes its
classification from the same class, so the three surfaces cannot drift on what a root is.

### 6 — The Studio data export names its table

**Recommended.** `tql.studio.data.export` (`ui/data/export/get.yml`, `input: table` required)
declares `filename: {params.table}.csv`, so a browse of `orders` downloads `orders.csv` instead
of `data.csv`. It is the one shipped route whose name wants a value (row 7), it exercises the
`response.file` site, and `StudioIntegrationTest` asserts the disposition — the feature is
dogfooded on the day it lands, not documented and unused.

### 7 — The extension changes nothing

**Recommended.** Row 3: the detector already reads a `{…}` in a filename as a path and resolves
a source root; the request roots are deliberately offered nowhere (slice 21: "declared
nowhere"). `filename` stays in `NOT_A_PATH_KEY` for the whole-scalar spelling. No `symbols`
contract change, no `package.json` bump, no extension release rides this slice.

### 8 — The docs say one thing about both altitudes

**Recommended.** The `$ref`-shared `export.filename` description (`defs-v1`) and the two
`route-v1` descriptions (`response.file.filename` :531, `response.stream.filename` :577) all
say: "`{dotted.path}` placeholders resolve against the request (`{params.month}`, `{path.id}`)
— on a job's export step against the step context (`{batch.businessDate}`) — each value folded
to a filename component; `{key}` is the split export's"; the `.vscode` byte copies and
`reference-yaml-surface.md` regenerate. `file-transfers.md`'s `filename` bullet and
`printable-documents.md` gain the route sentence with one example each; `troubleshooting.md`
gains the 1076 entry; the DN-03d row of docs/audit-low-leads.md records "the lint companion =
this record, B". `CHANGELOG.md`: one `### Added` entry (route filenames resolve) and one
`### Changed` (the job fold and `_`), both under Unreleased.

## What this breaks

- **A route filename with a brace resolves.** Measured: none exists in the tree or the docs
  (row 7). An application that wanted a literal `{` in a download name has no spelling for it
  after this slice — the job never had one either.
- **A job's absent value renders `_`, not empty**, and **a job's separator-carrying value is
  folded, not refused** (decision 4). `daily-price-report.yml` and the six docs examples are
  unaffected (`{batch.businessDate}` folds to itself).
- **`io.tesseraql.yaml.app.FilenamePlaceholders` is `io.tesseraql.core.files.FilenamePlaceholders`.**
  Two in-tree consumers move with it; no published contract names the class.
- The `export.filename` description slice 23a wrote is replaced, not extended: the sentence "On
  a route it is a literal name except `{key}`" was true for four days.

## Filed, not fixed

- **A row value in a route's download name** (`invoice-{main.first.invoice_no}.pdf`): a route
  source publishes `rows`/`rowCount`/`truncated` and `EvaluationContext` walks no list index
  (row 5). A `first` member on a route source is the unified-source model's decision, not this
  record's; until then the pdf route's natural name is `{path.id}`.
- **Completion of the request roots in the editor** after `{` in a filename (row 3). Slice 21
  chose not to offer them anywhere; a filename is not the place to start.
- **The transfer row's `filename varchar(500)`** bounds the recorded name; five maxed
  placeholders reach it. Not guarded: the insert fails loudly, and a template that long has no
  honest use. Recorded so the next reader does not measure it again.
- **`Interpolation.PLACEHOLDER`** (headers, redirects, view links) stays the wider grammar;
  the two grammars serve different sites and neither is changed here.

## The slice

**S1 — the resolver, the fold, the judgement, the consumer (M, one PR).**

Code: `FilenamePlaceholders` moves to `core.files` and gains `resolve`; `StepContext.interpolate`
delegates; `SqlStep`, `FileExportStartProcessor`, `FileResponseRenderer` resolve at their sites;
`FilenameTemplates` (yaml) with `TQL-YAML-1076`; the arm in `ExportDeclarations.violations` and
`ResponseLiterals.violations`; `Site` learns `pathParams`; `PushStepRules` shares the
classification; the Studio route's filename.

Guards, each proven red on a built variant before the PR opens:

| Guard | Asserts | Variant that reddens exactly it |
| --- | --- | --- |
| core `FilenamePlaceholdersTest` (NEW) | `{params.month}` → the folded value; `2026/09` → `2026_09`; `../x` → `__x`; null → `_`; 101 graphemes → 100; `{key}` passes; `{batch.business-date}` stays literal | `no-fold` (value appended unfolded), `empty-null` (null → `""`) |
| yaml `FilenameTemplatesTest` + `AppLinterTest` rows | 1076 on each of the four arms for a route (`{now}`, `{params.undeclared}`, `{path.id}` on a path-less route, `{principal.subject}` on a public route) and a job (`{query.x}`, `{steps.a.b-c}`); clean: `{params.status}` declared, `{path.id}` on `…/{id}/…`, `{principal.subject}` authenticated, `{main.rowCount}` on `response.file`, `{batch.businessDate}` on a step | `lint-noroot` (root arm deleted), `lint-nopath` (path arm deleted) |
| compiler `JudgedOnceCompileTest` +2 rows | a route with `{now}` in `response.file.filename` fails the build with 1076 and the linter's sentence; the same template in `export.filename` fails at `requireExportDeclarations` | `compiler-nojudge` (the arm skipped at build) |
| compiler `FileResponseRendererTest` (NEW) | `user-{path.id}.pdf` → `attachment; filename="user-42.pdf"` | `no-resolve-file` |
| runtime `FileTransferIntegrationTest` (+2 routes) | query-export `orders-{params.month}.csv` with `month=2026/09` → disposition `orders-2026_09.csv` on GET and HEAD; a split twin bundles as `orders-2026_09.zip` with entries `orders-2026_09-east.csv`; file-export with `{params.month}` → status JSON `filename` resolved, HEAD and GET dispositions equal to it, `download()` after the claim unchanged | `no-resolve-stream`, `no-resolve-start` |
| operations `StepContextInterpolateTest` | `anUnresolvedPlaceholderStillRendersEmpty` **flips** to `_` (`orders-_.csv`) — the test asserted the shape decision 4 retires; `{batch.businessDate}` and `{key}` rows unchanged | `empty-null` |
| runtime `BatchJobIntegrationTest` row | a job with an optional `input: region` and `filename: report-{params.region}.csv` run without the parameter records `report-_.csv`; with `east`, `report-east.csv` (`batch.businessDate` is never absent — `JobExecutor:353` defaults it) | `empty-null` |
| studio-runtime `StudioIntegrationTest` | the data export of table `orders` is offered as `orders.csv` | `studio-literal` (the route's filename back to `data.csv`) |
| docs-reference `ErrorCodeUniquenessTest`, reference regeneration | 1076 held once (the yaml constant; the compiler raises the same constant, so no `ANTICIPATED` entry), on the generated `reference-error-codes.md` with its Javadoc sentence; `troubleshooting.md` gains the entry by hand in the 1073/1074 shape | by construction |

Docs as decision 8. CHANGELOG as decision 8. The record's status block flips to shipped with the
PR number, and docs/audit-low-leads.md DN-03d's row names this record.

## Docs and CHANGELOG

`CHANGELOG.md`, Unreleased:

- `### Added` — **A route's download name resolves `{dotted.path}` placeholders.**
  `export.filename`, `response.stream.filename` and `response.file.filename` resolve against
  the request (`orders-{params.month}.csv`, `user-{path.id}.pdf`) the way a job's export step
  has resolved `{batch.businessDate}`; each value is folded to a filename component (NFC,
  separators and controls to `_`, 100 graphemes) and `{key}` stays the split export's. A
  placeholder the site cannot resolve — a spelling outside the grammar, a root the request does
  not carry, an undeclared input or path parameter — is `TQL-YAML-1076` at lint and at build.
  The Studio data export downloads as `<table>.csv`.
- `### Changed` — **A job's filename value is folded.** An export step's or push's placeholder
  value takes the split key's fold before it names a file: an absent value renders `_` (it
  rendered empty), a value carrying a separator is folded (the push refused it).
  `io.tesseraql.yaml.app.FilenamePlaceholders` is `io.tesseraql.core.files.FilenamePlaceholders`.
