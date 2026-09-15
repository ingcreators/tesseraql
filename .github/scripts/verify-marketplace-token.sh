#!/usr/bin/env bash
# Verify the Marketplace publisher token, retrying a request that timed out.
#
#   verify-marketplace-token.sh <publisher>   ->  vsce verify-pat's output; exit 0 when the token holds
#
# `vsce verify-pat` asks Azure DevOps whether the token holds a role on the publisher. On
# ext-v0.3.16 that request timed out twice -- `Request timeout: /_apis/securityroles`, three
# minutes each, seven minutes apart -- and answered in two seconds on the third re-run, each
# re-run a person pressing "Re-run failed jobs" on a tag. Other publishers hit the identical
# signature; it is the service, not the token. A token that is expired or wrong is refused at
# once (`TF400813: The user '...' is not authorized`), so only the timeout is worth retrying, and
# only the timeout is: any other failure is final on its first attempt and printed as vsce wrote
# it. Three attempts, fifteen seconds apart -- the attempts are what take the time, not the pause.
#
# A retried attempt's output is summarised on stderr rather than repeated: under GITHUB_ACTIONS
# vsce prints its failure as a `::error::` workflow command, and repeating one would annotate a
# run that went on to pass. The final attempt's output is printed as it was, annotation and all.
#
# `vsce` is resolved from PATH. The workflow runs this under `pnpm exec`, which puts
# node_modules/.bin there; the test puts a scripted vsce there instead. vsce reads the token
# from VSCE_PAT.
set -euo pipefail

PUBLISHER="${1:?usage: verify-marketplace-token.sh <publisher>}"
ATTEMPTS=3
PAUSE_SECONDS=15

for attempt in $(seq 1 "$ATTEMPTS"); do
  if output=$(vsce verify-pat "$PUBLISHER" 2>&1); then
    printf '%s\n' "$output"
    exit 0
  fi
  if ! grep -q 'Request timeout' <<<"$output"; then
    printf '%s\n' "$output"
    exit 1
  fi
  if [ "$attempt" -lt "$ATTEMPTS" ]; then
    timed_out=$(grep -o -m1 'Request timeout[^%]*' <<<"$output")
    echo "verify-marketplace-token: attempt $attempt/$ATTEMPTS: $timed_out;" \
         "retrying in ${PAUSE_SECONDS}s" >&2
    sleep "$PAUSE_SECONDS"
  fi
done

printf '%s\n' "$output"
echo "verify-marketplace-token: the Marketplace request timed out on all $ATTEMPTS attempts." \
     "Nothing has been published: re-run this job from the same tag." >&2
exit 1
