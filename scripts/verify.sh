#!/usr/bin/env bash
# The pre-push full verify, one at a time per machine (docs/host-development.md decision 6).
# Several agent sessions share a machine, and two full verifies at once turn the Testcontainers
# suites' timeouts into false reds — the most expensive kind of red to rule out. A second run
# waits for the first rather than failing. Module runs (./mvnw -pl <module> -am test) do not
# queue. Arguments pass through to Maven.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
lock="${XDG_RUNTIME_DIR:-/tmp}/tesseraql-verify.lock"
exec 9> "$lock"
if ! flock -n 9; then
  echo "Another full verify on this machine holds $lock; waiting for it to finish." >&2
  flock 9
fi
./mvnw -B -ntp clean verify "$@"
