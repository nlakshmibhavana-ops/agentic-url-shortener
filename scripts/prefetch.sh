#!/usr/bin/env bash
# Warms the local Maven repository (~/.m2) with everything the gates use, so runs can build offline
# (fast and reproducible): it runs the full lifecycle once, online, on each product the scenarios build.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for src in playbooks/shortener/files fixtures/linkly; do
  name=$(basename "$(dirname "$src")")/$(basename "$src")
  echo "prefetching dependencies for $name ..."
  cp -r "$src" "$tmp/p"
  (cd "$tmp/p" && mvn -q -B clean test-compile org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check \
      && mvn -q -B package -DskipTests && mvn -q -B test -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
      && mvn -q -B surefire:test -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false) >/dev/null
  rm -rf "$tmp/p"
done
echo "local Maven repository is warm; runs can build offline"
