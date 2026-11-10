#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
ENVROOT="${SITESCOPE_OFFLINE_ENV:-/mnt/data/offline-build-runtime/APKForge-Offline-Dev-Linux-x86_64}"
source "$ENVROOT/env.sh"
TMP="$(mktemp -d /tmp/sitescope-discovery-test-XXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/src/pro/sitescope/app" "$TMP/src/android/content" "$TMP/src/android/net/wifi" "$TMP/classes"
cp "$PROJ/android-direct/src/pro/sitescope/app/NetworkDiscoveryEngine.java" "$TMP/src/pro/sitescope/app/"
cp "$PROJ/android-direct/src/pro/sitescope/app/MdnsPacketParser.java" "$TMP/src/pro/sitescope/app/"
cp -a "$PROJ/tests/discovery-jvm/." "$TMP/src/"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -d "$TMP/classes" $(find "$TMP/src" -name '*.java' -print)
"$JAVA_HOME/bin/java" -cp "$TMP/classes" pro.sitescope.app.DiscoveryHarness
