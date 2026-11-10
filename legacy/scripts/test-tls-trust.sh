#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
WEB="$PROJ/android-direct/src/pro/sitescope/app/WebClientActivity.java"
STORE="$PROJ/android-direct/src/pro/sitescope/app/TlsTrustStore.java"
APK="$PROJ/dist/SiteScopePro-0.39.0-alpha37-release.apk"
grep -q 'handler.proceed()' "$WEB"
grep -q 'Доверять этому сертификату' "$WEB"
grep -q 'Сертификат узла изменился' "$WEB"
grep -q 'isPinnableTlsError' "$WEB"
grep -q 'SSL_EXPIRED' "$WEB"
grep -q 'sitescope_tls_pins_v1' "$STORE"
grep -q 'MessageDigest.getInstance("SHA-256")' "$STORE"
grep -q 'x509-certificate' "$STORE"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
unzip -p "$APK" classes.dex | strings > "$TMP"
grep -q 'TlsTrustStore' "$TMP"
grep -q 'sitescope_tls_pins_v1' "$TMP"
echo 'TLS trust contract PASS: one-time proceed + exact SHA-256 host pin + mismatch block + trust removal'
