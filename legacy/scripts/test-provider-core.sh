#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/sitescope-provider-test-$$"
mkdir -p "$TMP/src/pro/sitescope/app/ipintel" "$TMP/classes"
trap 'rm -rf "$TMP"' EXIT
cp "$PROJ/android-direct/src/pro/sitescope/app/ipintel/ProviderCore.java" "$TMP/src/pro/sitescope/app/ipintel/ProviderCore.java"
cp "$PROJ/tests/provider-jvm/ProviderCoreHarness.java" "$TMP/src/pro/sitescope/app/ipintel/ProviderCoreHarness.java"
javac -encoding UTF-8 -source 8 -target 8 -d "$TMP/classes" $(find "$TMP/src" -name '*.java')
java -cp "$TMP/classes" pro.sitescope.app.ipintel.ProviderCoreHarness
