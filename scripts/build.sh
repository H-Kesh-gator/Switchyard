#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes build/test-classes
compiler=(javac)
if ! command -v javac >/dev/null 2>&1; then compiler=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
find src/main/java -name '*.java' | sort > build/main-sources.txt
"${compiler[@]}" --release 17 --add-modules jdk.httpserver -Xlint:all -d build/classes @build/main-sources.txt
cp src/main/resources/* build/classes/
archiver=(jar)
if ! command -v jar >/dev/null 2>&1; then archiver=(java -m jdk.jartool/sun.tools.jar.Main); fi
"${archiver[@]}" --create --file build/switchyard.jar --main-class dev.switchyard.Main -C build/classes .
echo 'Built build/switchyard.jar'
