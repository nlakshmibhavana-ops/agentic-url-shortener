#!/usr/bin/env bash
# Regenerates docs/sample-runs/ from fresh deterministic runs of all three scenarios, so reviewers
# can read real outputs (report, audit trail, approvals, diffs, generated code) without running anything.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf runs/demo-*-sample
for s in greenfield brownfield ambiguous; do rm -rf "docs/sample-runs/$s"; done
STAMP=sample ./scripts/demo.sh > /dev/null
for s in greenfield brownfield ambiguous; do
  src="runs/demo-$s-sample"
  dst="docs/sample-runs/$s"
  mkdir -p "$dst"
  cp "$src"/{report.md,audit.jsonl,metrics.json,approvals.json} "$dst"/
  cp -r "$src/changes" "$dst/changes"
  cp -r "$src/workspace" "$dst/workspace"
  rm -rf "$dst/workspace/target" "$dst/workspace/data"
  sed -i "s#$(pwd)/##g" "$dst/report.md"
done
echo "samples written to docs/sample-runs/"
