#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
timeout 1800 mvn -q -Pnative -DskipTests package
