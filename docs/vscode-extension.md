# The VS Code extension — the editor as a first-class authoring surface

Status: design accepted 2026-07-07 (roadmap Phase 54, a cross-cutting developer-experience
track like Phase 38). **All four slices are delivered — Phase 54 is complete and
milestone M19 is met** (`vscode-extension/`, `tesseraql lint --format json`, the
`vscode-extension` CI job, and the scaffolder recommendation). The marketplace publish
remains the recorded operator step; until then the CI-built `.vsix` installs from file.

TesseraQL already has three authoring surfaces, each riding the same engines: **Studio**
in the browser (Track J), the **MCP dev-tools** for coding agents (Phase 24), and the
**CLI** for scripts and CI. The plain editor is the fourth — and today it is only
half-served. Phase 43 wired schema-driven completion into every scaffolded app
(`.vscode/settings.json` associates the committed `tesseraql-v1.schema.json` with the
route trees through `redhat.vscode-yaml`), and the linter learned to position its
findings (`LintFinding.line`/`column`, the `source:line:column` clickable form). But a
JSON Schema can only check *shape*. Everything the real linter knows — SQL file
references, security policies, connector names, i18n keys, the whole `TQL-*` taxonomy —
never reaches the editor; the developer round-trips through a terminal or Studio to see
it. Phase 54 closes that loop with `vscode-extension/`: a thin editor shell over the
engines that already exist.

## The no-duplication stance first

- **The extension holds no validation logic.** `AppLinter` is the single lint engine;
  the extension runs the project-selected CLI and renders what it reports. A rule added
  to the framework reaches the editor with no extension release, and the two can never
  disagree — the drift risk that killed every "editor plugin with its own parser" design
  is structurally absent.
- **The contract already exists.** The MCP dev-tools' `lint` tool has emitted
  `{errors, warnings, findings: [{code, severity, source, message, line, column}]}`
  since Phase 24. Slice 2 teaches `tesseraql lint` to print exactly that shape with
  `--format json`, making it the one cross-surface findings contract (CLI, MCP, editor)
  rather than a new one.
- **Zero runtime dependencies.** The extension is TypeScript compiled by `tsc`, no
  bundler, no runtime npm packages — the JDK-only instinct applied to the npm world, and
  the docs-site precedent for toolchain (pnpm, Node 22). All logic that does not need
  the `vscode` API (contract parsing, app-home discovery, the explorer tree model) lives
  in editor-free core modules unit-tested with `node:test`; the `vscode` glue stays
  thin. That factoring is deliberate: a future language server grows *out of* the core,
  not beside it.
- **The CLI is the app's, not the extension's.** The extension resolves the `tesseraql`
  binary from the `tesseraql.cliPath` setting (default: `tesseraql` on `PATH`), so the
  findings always come from the version the project actually builds and serves with.
  The extension version is independent of the framework version; the JSON shape above is
  the only coupling.

## What ships

**Slice 1 — this design** and the roadmap entry.

**Slice 2 — the CLI contract.** `tesseraql lint --app <dir> --format json` prints the
MCP-shape findings document on stdout (one JSON object, nothing else on stdout; exit
semantics unchanged, `--fail-on-warning` included). `--format text` is the explicit
name for today's output and stays the default.

**Slice 3 — the extension MVP**, under `vscode-extension/`:

- **App discovery.** A workspace folder — or a direct child directory — holding
  `config/tesseraql.yml` is an app home. Multi-root and multi-app workspaces resolve
  through a quick-pick; a single app resolves silently.
- **Diagnostics.** On save of any file under an app home (debounced), on activation,
  and on an explicit *TesseraQL: Lint* command, the extension runs the JSON lint and
  publishes every finding to the Problems panel at `source:line:column`
  (position-less findings anchor at the top of their file). Findings clear when the
  next run no longer reports them. If the CLI is missing or predates `--format json`,
  one actionable warning points at the `tesseraql.cliPath` setting — never a modal, never
  a crash.
- **Commands.** *Serve*, *Test*, *Migrate*, *Admission*, and *Package* run the
  corresponding CLI verb in the integrated terminal (visible, cancellable, credentials
  prompted by the CLI itself); *Lint* runs headless into the Problems panel.
- **The explorer.** A *TesseraQL* tree view over the app layout — routes grouped by
  kind (`web/`, `consume/`, `batch/`, `mcp/`), views, `db/**/migration` trees, and
  `tests/` suites — built from the documented directory contract
  (`docs/app-layout.md`), refreshed on file events, one click to the source. No CLI
  call needed to navigate.
- **Error-code hovers.** Hovering a `TQL-<DOMAIN>-<n>` literal in an app file links to
  its entry in the published error-code reference on the docs site — the generated
  index from the documentation portal, now one hover away from the finding that cited
  it.
- **Snippets** for the blessed route shapes, kept few and exactly aligned with
  `docs/reference-yaml-surface.md`.
- **Schema completion stays Phase 43's.** The extension declares
  `redhat.vscode-yaml` as a recommendation and leaves the scaffolded schema wiring
  alone — it complements the JSON Schema, it does not replace it.
- **CI.** A `vscode-extension` job mirroring the docs-site job (pnpm, Node 22):
  typecheck, unit tests, and a `vsce package` smoke so a broken manifest can never
  merge.

**Slice 4 — the adoption wiring.** The scaffolder's `.vscode/extensions.json` (and the
committed `examples/scaffold-demo-app` copy) recommends the extension alongside
`redhat.vscode-yaml`; the cookbook documents install and use. Publishing to the
Visual Studio Marketplace / Open VSX is recorded as an **operator step** (the
Cloudflare-dashboard precedent from the docs site): until the publisher account
exists, the CI-built `.vsix` installs from file.

## Deliberately out of scope (the ladder above the MVP)

Recorded so they are chosen, not implied: a full language server (cross-file
go-to-definition, project-aware completion beyond the JSON Schema, embedded-SQL
analysis against the introspected catalog), Test Explorer integration over
`tesseraql test`, deep links into a running Studio, auto-registering the Phase 24 MCP
server with editor AI clients, and marketplace publish automation in `release.yml`.
Each has an obvious seam in the MVP (the editor-free core, the findings contract, the
explorer) and none blocks it.

**Milestone M19** — in a fresh `tesseraql new` app opened in VS Code with the
extension installed: an edit that breaks a route surfaces the real `TQL-*` finding in
the Problems panel at its line and column within seconds of save, and clears on fix;
serve, test, and migrate run from the command palette; the explorer navigates to every
route, view, migration, and test suite — all without opening Studio or a terminal by
hand.

## Phase 55 — editor authoring depth

Status: design accepted 2026-07-08 (roadmap Phase 55, the first rungs of the ladder
above). **All five slices are delivered — Phase 55 is complete and milestone M20 is
met** (reference links, `tesseraql test --format json`, Test Explorer, SQL coverage,
the serve status bar; extension 0.2.0). Four capabilities, in the order they pay off, under the same Phase 54 stance:
**the extension computes nothing the framework already knows.** Reference resolution
follows the documented app layout, test results and coverage come from run contracts
the framework already writes, health comes from the Phase 45 probe.

**Slice 1 — this design.**

**Slice 2 — reference navigation (the pre-LSP rung).** A document-link provider makes
`file:`, `view:`, and `template:` values in app YAML clickable, resolved against the
document's directory exactly as the runtime resolves them (a `frags.html::fragment`
suffix links to the file). Links appear only when the target exists — a broken
reference stays a lint finding, not a dead link. No YAML semantics enter the
extension: the provider matches the documented key shapes line-by-line, in the
editor-free core, under `node:test`. The full go-to-definition ladder (message keys,
policies, named queries) stays with the language server rung.

**Slice 3 — the test-run contract.** `tesseraql test --app <dir> --format json`
prints one JSON object on stdout:
`{passed, failed, results: [{name, passed, message}], sql: [{file, lineRatio,
branchRatio, coveredLines, coverableLines}]}` — the complete per-case results
(`report.json` only carries cases joined to a route) plus per-file SQL line/branch
coverage with the 1-based line lists the portal already renders. `--format text`
names today's output and stays the default; exit semantics (1 on failure, 2 on the
opt-in regression gate) are identical in both formats.

**Slice 4 — Test Explorer and SQL coverage.** The extension discovers suites from
`tests/**/*.yml` (case names and lines only — presentation, not semantics) into the
native Test Explorer; a run executes the slice-3 contract against the app's
datasource and maps results back by case name. The same run feeds VS Code's test
coverage API: `coverableLines` minus `coveredLines` renders covered/uncovered SQL
lines in the editor — the 2-way SQL branch story, visible where the SQL is written.
Single-case filtering server-side is deferred (the CLI runs the whole app; runs are
seconds on a scaffold); it joins the ladder.

**Slice 5 — serve status.** A status-bar item polls the Phase 45 readiness probe
(`/_tesseraql/health/ready`) on the configured base URL (`tesseraql.serverUrl`,
default `http://localhost:8080`) while an app home is open: up, DOWN (503), or
unreachable, one click to open the app. Nothing new server-side — the probe is the
contract.

**Milestone M20** — in a scaffolded app: Ctrl+click on a `file:`/`view:` value jumps
to the SQL or view source; the Test Explorer lists every suite case, a run marks
pass/fail inline and paints covered/uncovered lines in the route's SQL files; the
status bar tracks `tesseraql serve` up and down — still no Studio, still no
hand-typed terminal.

## Phase 56 — editor intelligence: the remaining ladder

Status: design accepted 2026-07-08 (roadmap Phase 56). The four remaining ladder
items, in the order they pay off: single-case runs, Studio deep links, MCP
registration, and the language layer. One design decision up front:

**The "full LSP" ships as in-extension providers over a CLI symbols contract, not as
a language-server process.** Everything a language server would know about a
TesseraQL app — declared policies, message keys, route ids — only the framework can
resolve, so the intelligence lives in a new CLI contract and the editor stays a thin
renderer (the Phase 54 stance). Inside VS Code, definition/completion providers over
that contract are functionally identical to an LSP client and cost one process less.
The LSP wire protocol becomes worthwhile only when a second editor is targeted; that
rung stays on the ladder, and the editor-free core keeps the seam open.

**Slice 1 — this design.**

**Slice 2 — single-case runs.** `tesseraql test` gains a repeatable `--case <name>`
filter (exact case names): the runner executes only the matching cases, and the
`--format json` document reports only them. The Test Explorer passes the filter when
a run request names specific cases, so one failing case re-runs alone — the
seconds-long whole-app run stops being the only granularity.

**Slice 3 — Studio deep links.** *TesseraQL: Open in Studio* on an app file (editor
context, explorer context, and the command palette) opens
`/_tesseraql/studio/ui/source?path=<app-relative path>` on `tesseraql.serverUrl` —
the same source view every Studio surface links to. The editor and Studio stop being
parallel worlds: the file under the cursor is one click from its live, hot-reloading
counterpart.

**Slice 4 — MCP registration.** *TesseraQL: Register MCP Server* writes the Phase 24
dev-tools server (`<cliPath> mcp --app <home>`, stdio transport) into the chosen
client configuration: `.vscode/mcp.json` (VS Code MCP clients) and/or the repo-root
`.mcp.json` (Claude Code), merging with any existing servers and never overwriting a
foreign `tesseraql` entry without confirmation. One command, and any connected agent
sees manifest, lint, tests, and scaffolding.

**Slice 5 — the language layer.** A new CLI contract, `tesseraql symbols --app <dir>
--format json`, prints what the framework declares:
`{policies: [{name, source, line}], messages: [{key, source, line}], routes: [{id,
source, path, recipe}]}` — policies from the app config, message keys from the
default-locale catalog (flattened dotted keys with their source lines), routes from
the manifest; sorted, deterministic. Over it the extension adds, per app home and
refreshed on save: **completion** for `policy:` values (declared policies) and
`message:` values (catalog keys), and **go-to-definition** from a `policy:` value to
its declaration in `config/tesseraql.yml` and from a `message:` value (or a view
`title:`/`label:` that names an existing key) to its line in the catalog. Unknown
references stay lint findings — the providers navigate, they do not judge.

**Milestone M21** — in a scaffolded app: a single failing case re-runs alone from
the Test Explorer; right-click on a route file opens that source in Studio; one
command registers the MCP server and a connected agent lists the dev tools; typing
`policy:` offers the app's declared policies and Ctrl+click on one lands on its
declaration, same for message keys — the editor knows what the framework knows.
