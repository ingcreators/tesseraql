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
