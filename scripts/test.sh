#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash scripts/build.sh
compiler=(javac)
if ! command -v javac >/dev/null 2>&1; then compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
find src/test/java -name '*.java' | sort > build/test-sources.txt
"${compiler[@]}" --release 17 --add-modules jdk.httpserver -Xlint:all -cp build/classes -d build/test-classes @build/test-sources.txt
java --add-modules jdk.httpserver -ea -cp build/classes:build/test-classes dev.switchyard.AllTests
