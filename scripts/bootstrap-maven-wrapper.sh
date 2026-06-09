#!/usr/bin/env bash
set -euo pipefail

MAVEN_VERSION="${MAVEN_VERSION:-3.9.16}"

mvn -N wrapper:wrapper -Dmaven="${MAVEN_VERSION}"

echo "Maven Wrapper generated for Maven ${MAVEN_VERSION}."
echo "Run ./mvnw -B -ntp verify"
