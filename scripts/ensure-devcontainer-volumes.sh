#!/usr/bin/env bash
set -euo pipefail

# Ensure persistent named volumes mounted under /home/vscode are writable by the dev user.
# This is needed because Docker named volumes may be created as root-owned on first use.

USER_NAME="$(id -un)"
USER_GROUP="$(id -gn)"
USER_HOME="${HOME:-/home/${USER_NAME}}"

ensure_writable_dir() {
  local dir="$1"

  if [ ! -d "$dir" ]; then
    sudo mkdir -p "$dir"
  fi

  if [ ! -w "$dir" ]; then
    sudo chown -R "${USER_NAME}:${USER_GROUP}" "$dir"
  fi

  chmod -R u+rwX "$dir" 2>/dev/null || true
}

ensure_writable_dir "${USER_HOME}/.m2"
ensure_writable_dir "${USER_HOME}/.m2/repository"
ensure_writable_dir "${USER_HOME}/.claude"
ensure_writable_dir "${USER_HOME}/.codex"
ensure_writable_dir "${USER_HOME}/.config/gh"
ensure_writable_dir "${USER_HOME}/.npm"

echo "Persistent tool volumes are writable."
