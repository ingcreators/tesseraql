#!/usr/bin/env bash
set -euo pipefail

echo "== Java =="
java -version

echo "== Maven =="
mvn -version

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
