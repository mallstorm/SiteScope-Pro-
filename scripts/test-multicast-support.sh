#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/sitescope-multicast-test-$$"
mkdir -p "$TMP/classes"
trap 'rm -rf "$TMP"' EXIT
javac -encoding UTF-8 -source 8 -target 8 -d "$TMP/classes" \
  "$PROJ/android-direct/src/pro/sitescope/app/MulticastSupport.java" \
  "$PROJ/tests/multicast-jvm/MulticastSupportHarness.java"
java -cp "$TMP/classes" pro.sitescope.app.MulticastSupportHarness
