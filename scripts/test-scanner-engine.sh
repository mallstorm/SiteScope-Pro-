#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/sitescope-scanner-test-$$"
mkdir -p "$TMP/src/pro/sitescope/app" "$TMP/classes"
trap '[[ -n "${SERVER_PID:-}" ]] && kill "$SERVER_PID" 2>/dev/null || true; rm -rf "$TMP"' EXIT
cp "$PROJ/android-direct/src/pro/sitescope/app/ScannerEngine.java" "$TMP/src/pro/sitescope/app/ScannerEngine.java"
cp "$PROJ/tests/scanner-jvm/Models.java" "$TMP/src/pro/sitescope/app/Models.java"
cp "$PROJ/tests/scanner-jvm/ScannerHarness.java" "$TMP/src/pro/sitescope/app/ScannerHarness.java"
python3 "$PROJ/tests/scanner-jvm/mock_servers.py" > "$TMP/server.log" 2>&1 & SERVER_PID=$!
for _ in $(seq 1 30); do grep -q READY "$TMP/server.log" && break; sleep .1; done
javac -encoding UTF-8 -source 8 -target 8 -d "$TMP/classes" $(find "$TMP/src" -name '*.java')
java -cp "$TMP/classes" pro.sitescope.app.ScannerHarness
