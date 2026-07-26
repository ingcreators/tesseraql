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
  (`domains/`), shared validation rules (`rules/`), migrations, and test suites —
  one click to the source.
- **Declared-symbol intelligence.** Completion and go-to-definition for `policy:`,
  `message:`, `domain:`, and `use:` values over the `tesseraql symbols` contract —
  the editor offers exactly what the framework declares, nothing more.
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

Install from the [Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=ingcreators.tesseraql-vscode)
(published by the `ext-v*` release workflow, which also attaches the identical
`.vsix` to a GitHub release), or install a locally built `.vsix` with
*Extensions: Install from VSIX…*.
