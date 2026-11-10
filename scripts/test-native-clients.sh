#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
ENVROOT="${SITESCOPE_OFFLINE_ENV:-/mnt/data/offline-build-runtime/APKForge-Offline-Dev-Linux-x86_64}"
source "$ENVROOT/env.sh"
APK="$PROJ/dist/SiteScopePro-0.39.0-alpha37-release.apk"
[[ -f "$APK" ]]
PACKAGES="$($ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer dex packages "$APK")"
for cls in \
  pro.sitescope.app.SshClientActivity \
  pro.sitescope.app.NativeFileClientActivity \
  pro.sitescope.app.VncClientActivity \
  pro.sitescope.app.SnmpClientActivity \
  pro.sitescope.app.MqttClientActivity \
  pro.sitescope.app.OnvifClientActivity \
  pro.sitescope.app.DlnaBrowserActivity \
  com.jcraft.jsch.JSch; do
  grep -q "$cls" <<<"$PACKAGES" || { echo "MISSING $cls"; exit 1; }
done
grep -q 'StrictHostKeyChecking' "$PROJ/android-direct/src/pro/sitescope/app/SshClientActivity.java"
grep -q 'AUTH TLS' "$PROJ/android-direct/src/pro/sitescope/app/NativeFileClientActivity.java"
grep -q 'RFB 003.008' "$PROJ/android-direct/src/pro/sitescope/app/VncClientActivity.java"
grep -q '0xA1' "$PROJ/android-direct/src/pro/sitescope/app/SnmpClientActivity.java"
grep -q 'CONNACK' "$PROJ/android-direct/src/pro/sitescope/app/MqttClientActivity.java"
grep -q 'GetStreamUri' "$PROJ/android-direct/src/pro/sitescope/app/OnvifClientActivity.java"
grep -q 'BrowseDirectChildren' "$PROJ/android-direct/src/pro/sitescope/app/DlnaBrowserActivity.java"
echo "Native protocol packaging contract PASS: classes and declared protocol primitives are present; real-device compatibility is not asserted"
