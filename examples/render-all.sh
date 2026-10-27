#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/lmfiles-gallery.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
for name in frontier revisions claims snapshots journal_resume publish_ttl download_lmcurl pipeline_lifecycle sftp_registry; do
  timeout 180 lmscript "$ROOT/examples/$name.lms"
  cp "images/$name.png" "$ROOT/docs/images/$name.png"
done
