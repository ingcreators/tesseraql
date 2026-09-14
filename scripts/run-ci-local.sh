#!/usr/bin/env bash
# The three checks CI gates on: the Maven reactor, the docs site (navigation manifest, prose
# lint, link validation) and the VS Code extension (tests and the .vsix smoke package). The
# last two run under pnpm, not Maven — a docs-only or extension-only change passes `verify`
# and fails CI without them.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
./mvnw -B -ntp verify
(cd docs-site && pnpm install --frozen-lockfile && pnpm run build)
(cd vscode-extension && pnpm install --frozen-lockfile && pnpm run test && pnpm run package)
