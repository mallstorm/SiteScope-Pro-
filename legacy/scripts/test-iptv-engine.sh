#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/sitescope-iptv-test-$$"
mkdir -p "$TMP/src/pro/sitescope/app" "$TMP/src/android/util" "$TMP/src/org/xmlpull/v1" "$TMP/classes"
trap '[[ -n "${SERVER_PID:-}" ]] && kill "$SERVER_PID" 2>/dev/null || true; rm -rf "$TMP"' EXIT
cp "$PROJ/android-direct/src/pro/sitescope/app/IptvEngine.java" "$TMP/src/pro/sitescope/app/"
cp "$PROJ/android-direct/src/pro/sitescope/app/MulticastSupport.java" "$TMP/src/pro/sitescope/app/"
cp "$PROJ/tests/iptv-jvm/Models.java" "$TMP/src/pro/sitescope/app/"
cp "$PROJ/tests/iptv-jvm/IptvHarness.java" "$TMP/src/pro/sitescope/app/"
cp "$PROJ/tests/iptv-jvm/Xml.java" "$TMP/src/android/util/"
cp "$PROJ/tests/iptv-jvm/XmlPullParser.java" "$TMP/src/org/xmlpull/v1/"
python3 "$PROJ/tests/iptv-jvm/mock_iptv.py" > "$TMP/server.log" 2>&1 & SERVER_PID=$!
for _ in $(seq 1 30); do grep -q READY "$TMP/server.log" && break; sleep .1; done
javac -encoding UTF-8 -source 8 -target 8 -d "$TMP/classes" $(find "$TMP/src" -name '*.java')
java -cp "$TMP/classes" pro.sitescope.app.IptvHarness
