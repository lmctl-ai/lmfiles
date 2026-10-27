#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for name in frontier revisions claims snapshots journal_resume publish_ttl; do
  timeout 180 lmscript "examples/$name.lms"
  cp "images/$name.png" "docs/images/$name.png"
done
