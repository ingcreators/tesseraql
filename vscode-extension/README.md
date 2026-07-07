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
  kind (`web/`, `consume/`, `batch/`, `mcp/`), views, migrations, and test suites —
  one click to the source.
- **Error-code hovers.** Hover any `TQL-<DOMAIN>-<n>` literal for a link into the
  error-code reference.
- **Snippets** for the blessed route shapes (`tql-query-json`, `tql-query-html`,
  `tql-command`, `tql-view-list`, `tql-test`).

Schema-driven completion stays with the scaffolded wiring: `tesseraql new` associates
the committed JSON Schema through the recommended
[`redhat.vscode-yaml`](https://marketplace.visualstudio.com/items?itemName=redhat.vscode-yaml)
extension. This extension complements it; it does not replace it.

## Requirements

- The `tesseraql` CLI, 0.5.0 or later (`lint --format json`). Set
  **`tesseraql.cliPath`** if it is not on `PATH` — point it at the project's own CLI
  so editor findings always match the build.

## Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `tesseraql.cliPath` | `tesseraql` | The CLI the extension runs for lint and commands. |

## Building from source

```bash
pnpm install
pnpm run test      # tsc + node:test over the editor-free core
pnpm run package   # produces tesseraql-vscode-<version>.vsix
```

Install the `.vsix` with *Extensions: Install from VSIX…* — marketplace publishing is
tracked as an operator step in the design document.
