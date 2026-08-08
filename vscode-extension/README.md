# TesseraQL for Visual Studio Code

TesseraQL application authoring in the editor — the real linter's findings in the
Problems panel, the CLI verbs as commands, and an app explorer. The design is
[docs/vscode-extension.md](https://github.com/ingcreators/tesseraql/blob/main/docs/vscode-extension.md);
the one stance to know: **the extension holds no validation logic**. It runs the
project-selected `tesseraql` CLI and renders what it reports, so editor findings can
never disagree with the build.

## Features

- **Lint on save.** Saving any file inside an app home (a folder holding
  `config/tesseraql.yml`) runs `tesseraql lint --format json` and publishes every
  finding to the Problems panel at its source, line, and column. Finding codes link
  to the published error-code reference.
- **Commands.** *TesseraQL: Serve / Test / Migrate / Admission / Package* run the
  CLI verb in the integrated terminal; *TesseraQL: Lint* runs headless into the
  Problems panel.
- **The explorer.** A *TesseraQL* view in the Explorer sidebar: routes grouped by
  kind (`web/`, `consume/`, `batch/`, `mcp/`), views, shared field domains
  (`domains/`), shared validation rules (`rules/`), shared decisions
  (`decisions/`), approval workflows (`workflow/`), migrations, and test suites —
  one click to the source. Route files show their served identity
  (`GET /api/users · query-json`) once the symbols index has answered.
- **Declared-symbol intelligence.** Completion and go-to-definition for `policy:`,
  `message:`, `domain:`, `use:`, `decision:`, and `workflow:` values over the
  `tesseraql symbols` contract — the editor offers exactly what the framework
  declares, nothing more. Flow-map fields
  (`salary: { domain: salary, policy: hr.write }`) complete too.
- **View-composition intelligence.** View ids complete at every reference position —
  `response.html.view:`, `views: [..]` on template routes, and `view:` on dashboard
  panels and detail children — from the app's `*.view.yml` registry (`web/` +
  `templates/`); `shell:` offers the negotiation vocabulary (`auto`/`always`/`never`)
  and `widget:` the field-widget enum.
- **Test Explorer with SQL coverage.** App test suites run in the native Test
  Explorer (`tesseraql test --format json`), single cases included; a coverage run
  paints per-file SQL line coverage in the editor.
- **Reference navigation.** `file:` and `template:` values in app YAML are
  clickable links when the target exists (`view:` is an id into the view registry,
  not a path, and completes instead).
- **Serve status.** A status-bar item polls the dev server's readiness endpoint;
  *TesseraQL: Open Server* jumps to it.
- **Open in Studio.** Any app file opens at its source view in the running Studio.
- **Register MCP Server.** Writes the app's MCP endpoint into `.vscode/mcp.json` /
  `.mcp.json` for MCP-capable editors and agents.
- **Error-code hovers.** Hover any `TQL-<DOMAIN>-<n>` literal for a link into the
  error-code reference.
- **Snippets** for the blessed shapes (`tql-query-json`, `tql-query-html`,
  `tql-command`, `tql-view-list`, `tql-view-dashboard`, `tql-view-embed`,
  `tql-views`, `tql-chart-panel`, `tql-workflow`, `tql-test`,
  `tql-test-transition`, `tql-test-dispatch`, `tql-calendar`, `tql-job-schedule`,
  `tql-chunk-step`, `tql-export-step`).

Schema-driven completion stays with the scaffolded wiring: `tesseraql new` associates
the committed JSON Schema through the recommended
[`redhat.vscode-yaml`](https://marketplace.visualstudio.com/items?itemName=redhat.vscode-yaml)
extension. This extension complements it; it does not replace it.

## Requirements

- The `tesseraql` CLI, 0.5.0 or later (`lint --format json`). Shared-definition
  completion (`domain:`/`use:`) and the explorer's route annotations need the 0.8+
  `symbols` document; on an older CLI they simply stay absent. Set
  **`tesseraql.cliPath`** if it is not on `PATH` — point it at the project's own CLI
  so editor findings always match the build.

## Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `tesseraql.cliPath` | `tesseraql` | The CLI the extension runs for lint, tests, symbols, and commands. |
| `tesseraql.serverUrl` | `http://localhost:8080` | The dev server the status bar polls and Studio/MCP links target. |

## Building from source

```bash
pnpm install
pnpm run test      # tsc + node:test over the editor-free core
pnpm run package   # produces tesseraql-vscode-<version>.vsix
```

Install from the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=ingcreators.tesseraql-vscode)
(published by the `ext-v*` release workflow, which also attaches the identical
`.vsix` to a GitHub release), or install a locally built `.vsix` with
*Extensions: Install from VSIX…*.
