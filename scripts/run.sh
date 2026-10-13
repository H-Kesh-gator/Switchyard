#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build.sh
exec java --add-modules jdk.httpserver -jar build/switchyard.jar "${1:-config/local.properties}"
