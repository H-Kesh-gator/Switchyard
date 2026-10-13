#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build.sh
exec java --add-modules jdk.httpserver -cp build/classes dev.switchyard.DemoMain "${1:-config/local.properties}"
