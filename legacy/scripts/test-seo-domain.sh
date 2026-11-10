#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
ENVROOT="${SITESCOPE_OFFLINE_ENV:-/mnt/data/offline-build-runtime/APKForge-Offline-Dev-Linux-x86_64}"
source "$ENVROOT/env.sh"
OUT="$PROJ/build-seo-domain-test"
rm -rf "$OUT"
mkdir -p "$OUT"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_HOME/platforms/android-36/android.jar" \
  -d "$OUT" \
  "$PROJ/android-direct/src/pro/sitescope/app/Models.java" \
  "$PROJ/android-direct/src/pro/sitescope/app/TechnologyAnalyzer.java" \
  "$PROJ/android-direct/src/pro/sitescope/app/DnsAnalyzer.java" \
  "$PROJ/android-direct/src/pro/sitescope/app/DomainRdapClient.java" \
  "$PROJ/tests/SeoDomainHarness.java"
"$JAVA_HOME/bin/java" -cp "$OUT:$ANDROID_HOME/platforms/android-36/android.jar" pro.sitescope.app.SeoDomainHarness
