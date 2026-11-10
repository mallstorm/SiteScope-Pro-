#!/usr/bin/env bash
set -euo pipefail

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
ENVROOT="${SITESCOPE_OFFLINE_ENV:-/mnt/data/offline-build-runtime/APKForge-Offline-Dev-Linux-x86_64}"
source "$ENVROOT/env.sh"

SRC="$PROJ/android-direct"
BUILD="$PROJ/build-direct-release"
DIST="$PROJ/dist"
VERSION="0.39.0-alpha37"
APK_UNALIGNED="$BUILD/SiteScopePro-$VERSION-unsigned-unaligned.apk"
APK_WITH_DEX="$BUILD/SiteScopePro-$VERSION-with-dex.apk"
APK_UNSIGNED="$DIST/SiteScopePro-$VERSION-unsigned-aligned.apk"
APK_SIGNED="$DIST/SiteScopePro-$VERSION-release.apk"

rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/classes" "$BUILD/dex" "$DIST"
rm -f "$APK_UNSIGNED" "$APK_SIGNED"

AAPT_ARGS=(
  -o "$APK_UNALIGNED"
  -I "$ANDROID_HOME/platforms/android-36/android.jar"
  --manifest "$SRC/AndroidManifest.xml"
)
if [[ -d "$SRC/assets" ]]; then AAPT_ARGS+=( -A "$SRC/assets" ); fi

"$ANDROID_HOME/build-tools/36.0.0/aapt2" compile --dir "$SRC/res" -o "$BUILD/resources.zip"
"$ANDROID_HOME/build-tools/36.0.0/aapt2" link "${AAPT_ARGS[@]}" "$BUILD/resources.zip"

mapfile -t LIBS < <(find "$SRC/libs" -maxdepth 1 -type f -name '*.jar' -print | sort)
CLASSPATH="$ANDROID_HOME/platforms/android-36/android.jar"
for lib in "${LIBS[@]}"; do CLASSPATH+="${CLASSPATH:+:}$lib"; done

mapfile -t SOURCES < <(find "$SRC/src" -name '*.java' -print | sort)
"$JAVA_HOME/bin/javac" -encoding UTF-8 -classpath "$CLASSPATH" -source 8 -target 8 -d "$BUILD/classes" "${SOURCES[@]}"

mapfile -t CLASSES < <(find "$BUILD/classes" -name '*.class' -print | sort)
"$ANDROID_HOME/build-tools/36.0.0/d8" --min-api 28 --lib "$ANDROID_HOME/platforms/android-36/android.jar" --output "$BUILD/dex" "${CLASSES[@]}" "${LIBS[@]}"

cp "$APK_UNALIGNED" "$APK_WITH_DEX"
(cd "$BUILD/dex" && zip -q -j "$APK_WITH_DEX" classes*.dex)
if [[ -d "$SRC/lib" ]]; then (cd "$SRC" && zip -q -r "$APK_WITH_DEX" lib); fi
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -p -f 4 "$APK_WITH_DEX" "$APK_UNSIGNED"

SIGNED=0
if [[ -n "${SITESCOPE_SIGNING_P12:-}" && -f "${SITESCOPE_SIGNING_P12}" ]]; then
  : "${SITESCOPE_KEYSTORE_PASSWORD:?SITESCOPE_KEYSTORE_PASSWORD is required}"
  ALIAS="${SITESCOPE_KEY_ALIAS:-unifiedcore}"
  KEYPASS="${SITESCOPE_KEY_PASSWORD:-$SITESCOPE_KEYSTORE_PASSWORD}"
  "$ANDROID_HOME/build-tools/36.0.0/apksigner" sign \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --ks "$SITESCOPE_SIGNING_P12" --ks-type PKCS12 --ks-key-alias "$ALIAS" \
    --ks-pass "pass:$SITESCOPE_KEYSTORE_PASSWORD" --key-pass "pass:$KEYPASS" \
    --out "$APK_SIGNED" "$APK_UNSIGNED"
  SIGNED=1
else
  echo "INFO: explicit signing key was not supplied; producing unsigned aligned APK only" >&2
fi
if [[ "$SIGNED" == 1 ]]; then
  "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs "$APK_SIGNED" | tee "$DIST/APK_SIGNATURE.txt"
  "$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -p 4 "$APK_SIGNED"
  "$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging "$APK_SIGNED" > "$DIST/APK_BADGING.txt"
  sha256sum "$APK_SIGNED" "$APK_UNSIGNED" > "$DIST/SHA256SUMS.txt"
  echo "APK=$APK_SIGNED"
else
  "$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging "$APK_UNSIGNED" > "$DIST/APK_BADGING.txt"
  sha256sum "$APK_UNSIGNED" > "$DIST/SHA256SUMS.txt"
  echo "WARNING: no signing key available; produced unsigned aligned APK" >&2
  echo "APK=$APK_UNSIGNED"
fi
