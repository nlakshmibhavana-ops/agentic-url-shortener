#!/usr/bin/env bash
# Warms the local Maven repository (~/.m2) with everything the gates use, so runs can build offline
# (fast and reproducible): it runs the full lifecycle once, online, on each product the scenarios build.
set -euo pipefail
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for entry in "playbooks/shortener/files:CodeGeneratorTest" "fixtures/linkly:LinkApiTest"; do
  src=${entry%%:*}
  one_test=${entry##*:}
  echo "prefetching dependencies for $src ..."
  cp -r "$src" "$tmp/p"
  # Real test runs: Surefire only resolves its JUnit provider when there are tests to execute.
  (cd "$tmp/p" \
      && mvn -q -B clean test-compile org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check \
      && mvn -q -B package \
      && mvn -q -B surefire:test -Dtest="$one_test" -Djacoco.skip=true) >/dev/null
  rm -rf "$tmp/p"
done
echo "local Maven repository is warm; runs can build offline"
