# VS Code extension

The TesseraQL VS Code extension, `ingcreators.tesseraql-vscode`, turns the plain
editor into a full authoring surface for TesseraQL apps: real linter findings in the
Problems panel, the CLI verbs on the command palette, a project explorer, test runs
with inline SQL coverage, and navigation that knows what the framework knows. It is a
thin shell over the same engines behind the other three surfaces — **Studio** in the
browser, the [MCP dev-tools](ai-mcp.md) for coding agents, and the **CLI** for
scripts and CI (its source lives at `vscode-extension/` in the repository).

A JSON Schema can only check *shape*. Every scaffolded app
([scaffolding](scaffolding.md)) already associates the committed
the per-kind schemas with their document trees through `redhat.vscode-yaml`
(`.vscode/settings.json`), and the linter positions its findings
(`LintFinding.line`/`column`, the `source:line:column` clickable form). But
everything the real linter knows — SQL file references, security policies, connector
names, i18n keys, the whole `TQL-*` taxonomy — needs the engine itself. The extension
closes that loop by running the project's own CLI and rendering what it reports.

## Installation and setup

The scaffolder's `.vscode/extensions.json` (and the committed
`examples/scaffold-demo-app` copy) recommends the extension alongside
`redhat.vscode-yaml`, so a fresh `tesseraql new` app opened in VS Code prompts for
both. The extension is published to the Visual Studio Marketplace as
`ingcreators.tesseraql-vscode` by the `ext-v*` release workflow (see Publishing
below); it can also be built from the repository (`pnpm run package` under
`vscode-extension/`) and installed from the resulting `.vsix` (Extensions view →
*Install from VSIX…*).

The extension resolves the `tesseraql` binary from the `tesseraql.cliPath` setting
(default: `tesseraql` on `PATH`), so findings always come from the CLI version the
project actually builds and serves with. The extension version is independent of the
framework version; the JSON contracts below are the only coupling.

| Setting | Default | Purpose |
| --- | --- | --- |
| `tesseraql.cliPath` | `tesseraql` (on `PATH`) | The CLI binary the extension runs for lint, tests, symbols, and terminal commands. |
| `tesseraql.serverUrl` | `http://localhost:8080` | Base URL of the running app, used by the serve status bar and Studio deep links. |

## App discovery

A workspace folder — or a direct child directory — holding `config/tesseraql.yml` is
an app home. Multi-root and multi-app workspaces resolve through a quick-pick; a
single app resolves silently.

## Diagnostics — the linter in the Problems panel

On save of any file under an app home (debounced), on activation, and on an explicit
*TesseraQL: Lint* command, the extension runs `tesseraql lint --format json` and
publishes every finding to the Problems panel at `source:line:column`
(position-less findings anchor at the top of their file). Findings clear when the
next run no longer reports them. If the CLI is missing or predates `--format json`,
one actionable warning points at the `tesseraql.cliPath` setting — never a modal,
never a crash.

The extension holds no validation logic of its own. `AppLinter` is the single lint
engine; the extension renders what it reports. A rule added to the framework reaches
the editor with no extension release, and the two can never disagree.

## Commands

*Serve*, *Test*, *Migrate*, *Admission*, and *Package* run the corresponding CLI verb
in the integrated terminal (visible, cancellable, credentials prompted by the CLI
itself); *Lint* runs headless into the Problems panel.

## The explorer

A *TesseraQL* tree view over the app layout, built from the documented directory contract
([app layout](app-layout.md)), refreshed on file events, one click to the source. It covers:

- routes grouped by kind (`web/`, `consume/`, `batch/`, `mcp/`), and views;
- the shared definitions — field domains (`domains/`), validation rules with their SQL
  (`rules/`), [decision tables](declarative-validation.md#decision-tables)
  (`decisions/`), and [code catalogs](code-catalogs.md) with the SQL a `file:` catalog
  reads (`catalogs/`);
- `db/**/migration` trees and `tests/` suites.

No CLI call is needed to navigate. When the symbols index has answered (see below), each
route file additionally shows its served identity — `GET /api/users · query-json` —
as the tree item's description; the tree itself never waits on the CLI.

## Reference navigation

A document-link provider makes `file:`, `view:`, and `template:` values in app YAML
clickable, resolved against the document's directory exactly as the runtime resolves
them (a `frags.html::fragment` suffix links to the file). Links appear only when the
target exists — a broken reference stays a lint finding, not a dead link. No YAML
semantics enter the extension: the provider matches the documented key shapes
line-by-line.

## Declared-symbol intelligence

The extension reads what the framework declares through the `tesseraql symbols`
contract (see below), per app home and refreshed on save, and adds:

**Completion**, for the values the app itself declares:

| Key | Completes to |
| --- | --- |
| `policy:` | the app's declared policies |
| `message:` | catalog keys |
| `domain:` | the shared field domains under `domains/` |
| `use:` | the shared validation rules under `rules/`; inside a `decide:` block, the shared decisions under `decisions/` (the providers answer from both namespaces) |
| `decision:` | a test suite's `decide:` target |
| `workflow:` | a suite's `transition:`/`dispatch:` targets — the declared workflows, each carrying its transition and dispatch ids |
| `calendar:` | the business-day calendars under `calendars/` |
| `codes:` | the code catalogs under `catalogs/` |
| `after:` | the declared batch jobs, each completion carrying its one-line trigger story |

A mistyped `calendar:` fails open at fire time, so the editor is where it gets caught.

**Go-to-definition**, from a value to the line that declares it:

- `policy:` → its declaration in `config/tesseraql.yml`.
- `message:` — or a view `title:`/`label:` naming an existing key → the default-locale
  catalog.
- `domain:`, `use:`, `decision:` → the shared definition's line in its `domains/*.yml`,
  `rules/*.yml`, or `decisions/*.yml` document.
- `calendar:` → its `calendars/*.yml` declaration.
- `codes:` → the catalog's `catalogs/*.yml` declaration. A mistyped name resolves nothing,
  and every value of that field is then refused at runtime.
- `after:` → the chained job's document.

Unknown references stay lint findings — the providers navigate, they do not judge.
A pre-shared-definitions CLI simply omits the `domains`/`rules`/`decisions` arrays
(a pre-0.10 CLI the `workflows`/`calendars`/`jobs` arrays), and the extension
degrades those features to empty rather than rejecting the document.

## Test Explorer and SQL coverage

The extension discovers suites from `tests/**/*.yml` (case names and lines only —
presentation, not semantics) into the native Test Explorer; a run executes
`tesseraql test --format json` against the app's datasource and maps results back by
case name. When a run request names specific cases, the extension passes the CLI's
repeatable `--case <name>` filter (exact case names), so one failing case re-runs
alone rather than the whole app.

The same run feeds VS Code's test coverage API: `coverableLines` minus `coveredLines`
renders covered and uncovered SQL lines directly in the editor — the SQL coverage
story from the documentation portal, visible where the SQL is written.

## Serve status

A status-bar item polls the readiness probe (`/_tesseraql/health/ready`, see
[deployment](deployment.md)) on `tesseraql.serverUrl` while an app home is open:
up, DOWN (503), or unreachable, one click to open the app. Nothing extension-specific
server-side — the probe is the contract. Pair it with `tesseraql serve --app . --watch`
in the terminal and every save under `web/` hot-reloads its route — the editor-first
instant loop, no Studio **Apply** needed.

## Error-code hovers

Hovering a `TQL-<DOMAIN>-<n>` literal in an app file links to its entry in the
published error-code reference on the docs site — the generated index from the
[documentation portal](documentation-portal.md), one hover away from the finding that
cited it.

## Snippets and schema completion

The extension ships snippets for the blessed route shapes, kept few and exactly
aligned with the [YAML surface reference](reference-yaml-surface.md) — including the
batch authoring surfaces whose contracts live partly in SQL where only a template can
teach them: `tql-chunk-step` (the keyset reader contract with the `chunk.after`
guard), `tql-calendar`, and `tql-job-schedule` (the daily-consider qualifiers).
The analytics surfaces follow the same rule: `tql-view-dashboard` and
`tql-chart-panel` carry the kit chart vocabulary (the `series:` list, the kinds,
the passthrough attributes — [declarative views](declarative-views.md)), and
`tql-export-step` pairs the export step with the follow-up `notify:` that carries
its published `steps.<id>.transferId` ([jobs](jobs.md)).

HTML mail templates ([notifications](notifications.md#html-mail)) get their own
html-language surface: `tql-mail` scaffolds the single-file wrapper + content
fragment, `tql-mail-block`/`-button`/`-kvtable`/`-alert` insert fragment blocks, and
completion offers the `tql/email` fragment palette after
`~{tql/email/hc-email :: ` (signatures as placeholders) plus the mail model —
`payload`/`event` roots and the `event.` members — inside `${...}` expressions.
Lines without the mail markers complete nothing, so page templates are untouched.

Schema-driven completion stays with `redhat.vscode-yaml`: the extension declares it
as a recommendation and leaves the scaffolded schema wiring alone — it complements
the JSON Schema, it does not replace it.

## The Studio round trip

Studio owns the live runtime side (data, state, audited draft/apply writes under
runtime governance); the editor owns the source-of-truth loop (files, git, tests,
findings). Both are thin renderers over the same engines, meeting through deep links
— neither re-implements the other's trust model.

- ***TesseraQL: Open in Studio*** on an app file (editor context, explorer context,
  and the command palette) opens
  `/_tesseraql/studio/ui/source?path=<app-relative path>` on `tesseraql.serverUrl` —
  the same source view every Studio surface links to. The file under the cursor is
  one click from its live, hot-reloading counterpart.
- ***Open in editor*** in the Studio source view (`vscode://file/<absolute path>`,
  next to *Edit as form*) is the reverse link: see a finding or a failing test in
  Studio, land in the editor on the same file. Best-effort by design: the protocol
  link assumes the browser and the files share a machine (the normal dev loop); a
  remote or containerized Studio simply leaves the button inert.

The two surfaces also draw their option lists from the same source. Studio's route
form derives its dropdowns from the framework — `AppLinter.knownRouteRecipes()`,
`knownAuthModes()`, and `knownInputTypes()` feed the recipe, auth, and input-type
selects — and the same surfaces land as real `enum`s in the shipped JSON Schema, so
the editor gains completion for `security.auth` too. `SchemaSyncTest` keeps the
schema, the linter, and the form in sync: they can never drift apart.

## Registering the MCP server

*TesseraQL: Register MCP Server* writes the [dev-tools MCP server](ai-mcp.md)
(`<cliPath> mcp --app <home>`, stdio transport) into the chosen client
configuration: `.vscode/mcp.json` (VS Code MCP clients) and/or the repo-root
`.mcp.json` (Claude Code), merging with any existing servers and never overwriting a
foreign `tesseraql` entry without confirmation. One command, and any connected agent
sees manifest, lint, tests, and scaffolding.

A file that is not valid JSON — `mcp.json` is JSONC in practice, and VS Code itself writes
comments into it — cannot be merged, so the command **refuses to write it** and offers to open
it for a hand edit. It never rewrites a file it could not read: the other servers declared there
are exactly what a rewrite would destroy.

## CLI contracts

The extension computes nothing the framework already knows; these CLI contracts carry
everything it renders. They are usable by any tool, not only the extension.

### `tesseraql lint --app <dir> --format json`

Prints the findings document on stdout (one JSON object, nothing else on stdout):

```json
{"errors": 0, "warnings": 0, "findings": [{"code": "...", "severity": "...", "source": "...", "message": "...", "line": 1, "column": 1}]}
```

This is exactly the shape the MCP dev-tools' `lint` tool emits — one cross-surface
findings contract for CLI, MCP, and editor. Exit semantics are unchanged,
`--fail-on-warning` included. `--format text` is the explicit name for the default
human-readable output.

### `tesseraql test --app <dir> --format json [--case <name>]`

Prints one JSON object on stdout:

```json
{"passed": 0, "failed": 0, "results": [{"name": "...", "passed": true, "message": "..."}], "sql": [{"file": "...", "lineRatio": 0.0, "branchRatio": 0.0, "coveredLines": [], "coverableLines": []}]}
```

The complete per-case results (the packaged `report.json` only carries cases joined
to a route) plus per-file SQL line/branch coverage with 1-based line lists — the same
numbers the documentation portal renders. The repeatable `--case <name>` filter
(exact case names) runs only the matching cases, and the JSON document reports only
them. `--format text` names the default output; exit semantics (1 on failure, 2 on
the opt-in regression gate) are identical in both formats.

### `tesseraql symbols --app <dir>`

Prints what the framework declares:

```json
{"policies": [{"name": "...", "source": "...", "line": 1}], "messages": [{"key": "...", "source": "...", "line": 1}], "domains": [{"name": "...", "source": "...", "line": 1}], "rules": [{"name": "...", "source": "...", "line": 1}], "decisions": [{"name": "...", "source": "...", "line": 1}], "calendars": [{"name": "...", "source": "...", "line": 1}], "catalogs": [{"name": "...", "source": "...", "line": 1}], "routes": [{"id": "...", "source": "...", "path": "...", "recipe": "..."}], "workflows": [{"id": "...", "source": "...", "line": 1, "transitions": ["..."], "dispatches": ["..."]}], "jobs": [{"id": "...", "source": "...", "line": 1, "trigger": "..."}], "broken": [{"source": "...", "error": "..."}]}
```

Everything in it is sorted and deterministic. Policies come from the app config, and message
keys from the default-locale catalog, as flattened dotted keys with their source lines.
Domains, rules, calendars, and catalogs come from the shared-definition documents under
`domains/`, `rules/`, `calendars/`, and `catalogs/`, each name paired with the file
declaring it. Routes, workflows
(each with its transition and dispatch ids), and jobs (each with its one-line trigger story)
come from the manifest.

A document that does not parse is **skipped, not fatal**: it is listed in `broken`
(with the parser's message) and on stderr, and everything else still prints. Editor
intelligence exists to help while an app is mid-edit, so the one moment a document is
broken must not be the moment every completion in the app goes quiet. A failure
outside the route tree — a broken shared definition, job, or MCP document — still
aborts the manifest load, and the command then degrades one step further: the
config-derived arrays (`policies`, `messages`) and the shared-definition walks still
answer, `routes`/`workflows`/`jobs` come back empty rather than absent, and `broken`
carries an `(app manifest)` entry. The extension names the skipped files once per set
rather than leaving the missing completions unexplained.

## Publishing

The extension versions independently of the framework, so its releases live in their own tag
namespace. Pushing an `ext-v<version>` tag runs the *Extension release* workflow
(`.github/workflows/extension-release.yml`): tests, a manifest-version-matches-tag gate, one
`vsce package`, `vsce verify-pat`, then `vsce publish` of that exact vsix to the Visual
Studio Marketplace (publisher `ingcreators`), and a GitHub release with the same vsix
attached. The archived artifact is therefore byte-identical to what the Marketplace serves.
The workflow
authenticates with the `VSCE_PAT` repository secret (an Azure DevOps PAT with the
Marketplace *Manage* scope — it expires, so re-issuing it is a recurring operator
task); running the workflow manually is a dry run that proves the token is still
valid without publishing anything. `.vsix` files are gitignored — the release
asset is the distribution channel, not the repository.

## Not currently supported

- **An LSP wire protocol.** The definition/completion providers over the `symbols`
  contract are functionally identical to an LSP client inside VS Code and cost one
  process less; a separate language-server process becomes worthwhile only when a
  second editor is targeted. The editor-free core keeps that seam open.
- **Open VSX publication.** The `ext-v*` workflow publishes to the Visual Studio
  Marketplace only; mirroring to Open VSX (for VS Code forks) needs its own token
  and is not wired up.
- **Embedded-SQL analysis** against the introspected catalog, and go-to-definition
  for named queries.

## Design notes

- **No duplicated validation.** The extension holds no lint or parse logic;
  `AppLinter` is the single engine and the extension renders its JSON output. The
  drift risk inherent in every "editor plugin with its own parser" design is
  structurally absent.
- **One findings contract.** `tesseraql lint --format json` prints the same shape the
  MCP dev-tools emit, so CLI, MCP, and editor share one contract rather than growing
  three.
- **Zero runtime dependencies.** The extension is TypeScript compiled by `tsc`, no
  bundler, no runtime npm packages (toolchain: pnpm, Node 22, matching the docs
  site). All logic that does not need the `vscode` API — contract parsing, app-home
  discovery, the explorer tree model, the document-link matching — lives in
  editor-free core modules unit-tested with `node:test`; the `vscode` glue stays
  thin. A future language server grows *out of* that core, not beside it.
- **The CLI is the app's, not the extension's.** Findings, test results, and symbols
  always come from the version the project builds with, resolved via
  `tesseraql.cliPath`.
- **The intelligence lives in the framework.** Everything an editor would want to
  know about a TesseraQL app — declared policies, message keys, route ids — only the
  framework can resolve, so it is exposed as a CLI contract (`symbols`) and the
  editor stays a thin renderer.
- **The Studio–editor boundary.** Studio owns the live runtime side; the editor owns
  the source-of-truth loop; they meet through deep links in both directions. Full
  generation of Studio's route form from the JSON Schema was deliberately not chosen:
  the form is a curated subset — id, recipe, security, inputs — with the text editor
  as the escape hatch. The option *lists* are framework-derived and drift-tested
  because the choices, not the layout, are where drift lives.

## Next

- [studio.md](studio.md) — the browser console the extension complements.
- [testing.md](testing.md) — the suites the extension runs.
