#!/usr/bin/env bash
# Does a shipped fat jar reach for a JDK module the jlinked images are not linked from?
#
#   check-image-modules.sh <fat-jar>
#
# The companion to JlinkModuleLedgerTest, which scans FIRST-PARTY bytecode only and so cannot see
# what a third-party jar needs. It adds one root to what is checked -- jdk.unsupported, reached by
# Netty and by nothing of ours -- plus forward cover for whatever a future dependency reaches. It
# does NOT "close the dependency half": measured by dropping each of the twelve roots in turn, a
# one-way diff reddens for four of them and is green for eight, because the rest are reached by
# name -- a locale, a cipher suite, a keystore type -- and leave nothing to analyse.
#
# It is NOT part of `mvn verify` and cannot be: every -Pdist invocation passes -DskipTests, so a
# JUnit test would never see a fat jar on a real run. It runs in the jobs that already build one.
set -euo pipefail

JAR="${1:?usage: check-image-modules.sh <fat-jar>}"
WORKFLOW="${WORKFLOW_FILE:-.github/workflows/jpackage.yml}"

# jdeps EXITS 0 ON A PATH THAT DOES NOT EXIST, printing its warning to stdout and then a plausible
# module list. A guard that only diffs would go silently green the day this path moves, which is
# the one failure mode it exists to prevent. So the jar is checked first, and the parsed sets are
# checked for emptiness after.
[ -f "$JAR" ] || { echo "check-image-modules: $JAR does not exist" >&2; exit 1; }

# The roots the images are linked from, by token rather than by line: the two --add-modules lines
# have moved twice already.
roots=$(grep -o -- '--add-modules[= ][^ \\]*' "$WORKFLOW" | head -1 | sed 's/^--add-modules[= ]//')
[ -n "$roots" ] || { echo "check-image-modules: no --add-modules in $WORKFLOW" >&2; exit 1; }

# What those roots actually resolve to, asked of the JDK rather than assumed.
closure=$(java -e "
    java.lang.module.Configuration.empty()
        .resolve(java.lang.module.ModuleFinder.ofSystem(), java.lang.module.ModuleFinder.of(),
                 java.util.Set.of(\"${roots//,/\",\"}\"))
        .modules().forEach(m -> System.out.println(m.reference().descriptor().name()));
  " 2>/dev/null | sort -u) || closure=""
if [ -z "$closure" ]; then
  # No --source-file mode on this JDK: fall back to the launcher's own resolver.
  closure=$(java --limit-modules "$roots" --list-modules | sed 's/@.*//' | sort -u)
fi
[ -n "$closure" ] || { echo "check-image-modules: could not resolve the module closure" >&2; exit 1; }

needed=$(jdeps --multi-release 25 --ignore-missing-deps --list-deps "$JAR" \
  | tr -d ' ' | grep -E '^[a-z]' | sort -u)
[ -n "$needed" ] || { echo "check-image-modules: jdeps named no modules for $JAR" >&2; exit 1; }

# Ignored by REACHING CLASS, never by module name. javassist's hot-swap helpers are the only
# thing in the jar that reaches jdk.attach and jdk.jdi, and nothing in 27,000 classes calls them.
# Keyed on the module instead, this list would also hide a real dependency that reached them.
IGNORED_REACHERS='^javassist\.util\.HotSwap'

missing=""
for module in $needed; do
  if ! printf '%s\n' "$closure" | grep -qx "$module"; then
    reachers=$(jdeps --multi-release 25 --ignore-missing-deps -verbose:class "$JAR" \
      | awk -v m="$module" '$NF == m && $1 !~ /[.]jar$/ { print $1 }' | sort -u)
    if [ -n "$reachers" ] && ! printf '%s\n' "$reachers" | grep -qvE "$IGNORED_REACHERS"; then
      echo "note: $module is reached only by $(printf '%s' "$reachers" | tr '\n' ' ')— ignored"
      continue
    fi
    missing="$missing $module (reached by: $(printf '%s' "$reachers" | tr '\n' ' '))"
  fi
done

if [ -n "$missing" ]; then
  echo "A dependency reaches a JDK module the images are not linked from:$missing" >&2
  echo "Add it to BOTH --add-modules lists in $WORKFLOW, or record why it is unreachable." >&2
  exit 1
fi
echo "check-image-modules: $(basename "$JAR") needs nothing outside the images' module closure"
