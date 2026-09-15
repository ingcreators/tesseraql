#!/usr/bin/env bash
# Write the release notes for an ext-v* tag: the pull requests that touched vscode-extension/
# since the previous ext-v* tag, and nothing else.
#
#   extension-release-notes.sh <ext-v tag>   ->  markdown on stdout
#
# Why not `gh release create --generate-notes`: the extension shares the repository, the commit
# line and the release list with the framework, and GitHub's automatic notes pick their "previous
# release" from that shared list. ext-v0.3.13 was ranged from v0.14.0 and ext-v0.3.16 from
# v0.16.0, which reproduced the framework release's seventy-two lines under the extension's
# title; ext-v0.3.15 happened to be ranged from ext-v0.3.13. Ranging from the previous ext-v* tag
# is not enough either: ext-v0.3.15..ext-v0.3.16 holds 206 pull requests, five of which touched
# the extension. The path is the filter, and git already knows it.
#
# Needs the tags and the history, so the checkout that runs this is `fetch-depth: 0`. A squash
# merge's subject ends in the pull request number, `(#1341)`, which is what the link is built
# from; a subject without one is listed as it is.
set -euo pipefail

TAG="${1:?usage: extension-release-notes.sh <ext-v tag>}"
REPO="${GITHUB_REPOSITORY:-ingcreators/tesseraql}"
EXTENSION_DIR="vscode-extension"

case "$TAG" in
  ext-v*) ;;
  *) echo "extension-release-notes: '$TAG' is not an ext-v* tag" >&2; exit 1 ;;
esac

if ! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "extension-release-notes: tag $TAG is not in this checkout (a shallow fetch?)" >&2
  exit 1
fi

# The nearest ext-v* tag reachable from this one, itself excluded -- so a previous tag on the
# same commit still counts. The first release has none and ranges over the whole history.
previous=$(git describe --tags --match 'ext-v*' --exclude "$TAG" --abbrev=0 "$TAG" 2>/dev/null || true)
if [ -n "$previous" ]; then
  range="$previous..$TAG"
else
  range="$TAG"
fi

echo "## What's Changed"
count=0
while IFS= read -r subject; do
  [ -n "$subject" ] || continue
  count=$((count + 1))
  if [[ "$subject" =~ ^(.*)\ \(#([0-9]+)\)$ ]]; then
    echo "* ${BASH_REMATCH[1]} in https://github.com/$REPO/pull/${BASH_REMATCH[2]}"
  else
    echo "* $subject"
  fi
done < <(git log --reverse --format='%s' "$range" -- "$EXTENSION_DIR")

if [ "$count" -eq 0 ]; then
  echo "* No change under \`$EXTENSION_DIR/\` since ${previous:-the first commit}."
fi

echo
if [ -n "$previous" ]; then
  echo "Changes under \`$EXTENSION_DIR/\` since $previous."
fi
echo "**Full Changelog**: https://github.com/$REPO/commits/$TAG/$EXTENSION_DIR"
