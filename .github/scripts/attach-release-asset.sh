#!/usr/bin/env bash
# Attach one built asset to a GitHub release, waiting for the release to appear first.
#
#   attach-release-asset.sh <tag> <asset-path>
#
# release.yml publishes the release near the END of its run (verify, then publish), while the
# per-OS image jobs usually finish earlier, so this waits rather than failing. The budget is
# stated in minutes because the observed margin is what matters: recomputed from the real runs,
# the release job finished 4m51s, 1m48s and 8m04s before the old 20-minute deadline at v0.13.0,
# v0.14.0 and v0.15.0. That moves with the release job's own duration (19m42s to 13m44s), so it is
# variance rather than decay -- but 1m48s is too little margin to keep, and 40 minutes puts the
# worst observed case at 21m48s.
#
# A timeout here is recoverable: the release exists, only the asset is missing, and re-running the
# image job re-attaches it. Say which asset, so the operator does not have to read the log to find
# out what to re-run.
set -euo pipefail

TAG="${1:?usage: attach-release-asset.sh <tag> <asset-path>}"
ASSET="${2:?usage: attach-release-asset.sh <tag> <asset-path>}"

TIMEOUT_MINUTES="${ATTACH_TIMEOUT_MINUTES:-40}"
INTERVAL_SECONDS="${ATTACH_INTERVAL_SECONDS:-20}"
attempts=$(( TIMEOUT_MINUTES * 60 / INTERVAL_SECONDS ))

if [ ! -f "$ASSET" ]; then
  echo "attach-release-asset: $ASSET does not exist; nothing to attach to $TAG" >&2
  exit 1
fi

for attempt in $(seq 1 "$attempts"); do
  if gh release view "$TAG" >/dev/null 2>&1; then
    gh release upload "$TAG" "$ASSET" --clobber
    echo "Attached $(basename "$ASSET") to $TAG"
    exit 0
  fi
  echo "Waiting for release $TAG to be published ($attempt/$attempts)..."
  sleep "$INTERVAL_SECONDS"
done

echo "Release $TAG did not appear within ${TIMEOUT_MINUTES}m, so $(basename "$ASSET") is not" \
     "attached. The release itself is unaffected: re-run this job to attach it." >&2
exit 1
