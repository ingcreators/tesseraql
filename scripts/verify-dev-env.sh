#!/usr/bin/env bash
# What this machine can build with, and what it is missing (docs/development-environment.md).
# Answers on the host and, until it retires, inside the Dev Container.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "${SCRIPT_DIR}/.." && pwd)"
missing=0

if [ -f /.dockerenv ] && [ -d /home/vscode ]; then
  # The Dev Container's named volumes may be created root-owned on first use.
  bash "${SCRIPT_DIR}/ensure-devcontainer-volumes.sh"
fi

echo "== mise =="
if command -v mise > /dev/null; then
  mise --version
  (cd "${REPO}" && mise bootstrap packages status) || missing=1
else
  echo "mise is not installed; the toolchain is pinned in mise.toml." >&2
  [ -f /.dockerenv ] || missing=1
fi

echo "== Java =="
java -version

echo "== Maven (the wrapper; the build refuses any other) =="
"${REPO}/mvnw" -version

echo "== unzip (the wrapper unpacks its distribution with it) =="
if command -v unzip; then
  :
else
  echo "unzip is missing: mise bootstrap packages apply" >&2
  missing=1
fi

echo "== Maven local repository =="
maven_args=$(cd "${REPO}" && { [ -f "${HOME}/.mavenrc" ] && . "${HOME}/.mavenrc"; echo "${MAVEN_ARGS:-}"; })
case "${maven_args}" in
  *maven.repo.local.tail*) echo "one per checkout, over ~/.m2/repository as a read-only tail" ;;
  *) echo "shared ~/.m2: source scripts/mavenrc from ~/.mavenrc when sessions run in parallel" >&2 ;;
esac

echo "== Git =="
git --version

echo "== Docker =="
docker version || {
  echo "Docker is unavailable. Install it on the host (Docker Desktop's WSL integration, Docker Engine, or its snap)." >&2
  exit 1
}

echo "== Node / pnpm =="
node --version
(cd "${REPO}/docs-site" && pnpm --version)

if [ "${missing}" -ne 0 ]; then
  echo "The development environment is missing something above." >&2
  exit 1
fi
echo "Development environment looks ready."
