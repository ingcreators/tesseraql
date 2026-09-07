#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "${SCRIPT_DIR}/ensure-devcontainer-volumes.sh"

echo "== Java =="
java -version

echo "== Maven (the wrapper; the build refuses any other) =="
"$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/mvnw" -version

echo "== Git =="
git --version

echo "== Docker =="
docker version || {
  echo "Docker is unavailable. Enable Docker Desktop WSL integration or devcontainer Docker access." >&2
  exit 1
}

echo "== Node / pnpm =="
node --version
pnpm --version

echo "Development environment looks ready."
