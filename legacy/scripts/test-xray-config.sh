#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
ENVROOT="${SITESCOPE_OFFLINE_ENV:-/mnt/data/offline-build-runtime/APKForge-Offline-Dev-Linux-x86_64}"
source "$ENVROOT/env.sh"
OUT="$PROJ/build-xray-config-test"
JSONSRC="$ANDROID_HOME/sources/android-36/org/json"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/src/org/json"
# Android's JSON implementation is pure Java; strip platform-only source annotations for the JVM harness.
for file in JSON.java JSONArray.java JSONException.java JSONObject.java JSONStringer.java JSONTokener.java; do
  sed -E \
    -e '/^import android\./d' \
    -e '/^import libcore\./d' \
    -e '/^import static android\./d' \
    -e 's/@UnsupportedAppUsage(\([^)]*\))?[[:space:]]*//g' \
    -e 's/@SystemApi(\([^)]*\))?[[:space:]]*//g' \
    -e 's/@NonNull[[:space:]]*//g' \
    -e 's/@Nullable[[:space:]]*//g' \
    "$JSONSRC/$file" > "$OUT/src/org/json/$file"
done
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -d "$OUT/classes" \
  "$OUT/src/org/json/JSON.java" "$OUT/src/org/json/JSONArray.java" "$OUT/src/org/json/JSONException.java" \
  "$OUT/src/org/json/JSONObject.java" "$OUT/src/org/json/JSONStringer.java" "$OUT/src/org/json/JSONTokener.java" \
  "$PROJ/tests/xray-jvm/android/util/Base64.java" \
  "$PROJ/android-direct/src/pro/sitescope/app/XrayProfileParser.java" \
  "$PROJ/android-direct/src/pro/sitescope/app/XrayConfigBuilder.java" \
  "$PROJ/tests/xray-jvm/pro/sitescope/app/XrayConfigHarness.java"
"$JAVA_HOME/bin/java" -cp "$OUT/classes" pro.sitescope.app.XrayConfigHarness
