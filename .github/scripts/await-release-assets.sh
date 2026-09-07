#!/usr/bin/env bash
# Wait until every named asset is on a release, then print its name and SHA-256 digest.
#
#   await-release-assets.sh <tag> <asset-name>...   ->  "<name> <sha256>" per line, in order
#
# The mirror of attach-release-asset.sh: that one waits for the release, this one waits for the
# assets the image jobs attach to it asynchronously. Same budget, and stated in minutes for the
# same reason.
#
# A timeout names every asset it is still missing. The old loop reported "Timed out waiting for
# $DIST and/or $WIN", which does not say which -- and the two are produced by different jobs on
# different runners, so which one is missing is the whole diagnosis.
set -euo pipefail

TAG="${1:?usage: await-release-assets.sh <tag> <asset-name>...}"
shift
[ "$#" -gt 0 ] || { echo "await-release-assets: name at least one asset" >&2; exit 1; }

TIMEOUT_MINUTES="${ATTACH_TIMEOUT_MINUTES:-40}"
INTERVAL_SECONDS="${ATTACH_INTERVAL_SECONDS:-20}"
attempts=$(( TIMEOUT_MINUTES * 60 / INTERVAL_SECONDS ))

for attempt in $(seq 1 "$attempts"); do
  json=$(gh release view "$TAG" -R "$GITHUB_REPOSITORY" --json assets)
  missing=""
  found=""
  for name in "$@"; do
    digest=$(printf '%s' "$json" | jq -r --arg n "$name" \
      '.assets[] | select(.name == $n) | .digest // empty' | sed 's/^sha256://')
    if [ -z "$digest" ]; then
      missing="$missing $name"
    else
      found="$found$name $digest"$'\n'
    fi
  done

  if [ -z "$missing" ]; then
    printf '%s' "$found"
    exit 0
  fi
  echo "Waiting for release assets ($attempt/$attempts), still missing:$missing" >&2
  sleep "$INTERVAL_SECONDS"
done

echo "Timed out after ${TIMEOUT_MINUTES}m waiting on release $TAG for:$missing" >&2
exit 1
