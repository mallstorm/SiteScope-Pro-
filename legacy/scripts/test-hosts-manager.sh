#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
ENVROOT="${SITESCOPE_OFFLINE_ENV:-/mnt/data/offline-build-runtime/APKForge-Offline-Dev-Linux-x86_64}"
source "$ENVROOT/env.sh"
OUT="$PROJ/build-hosts-test"
rm -rf "$OUT" && mkdir -p "$OUT"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -d "$OUT" \
 "$PROJ/tests/hosts-jvm/org/json/JSONObject.java" "$PROJ/tests/hosts-jvm/org/json/JSONArray.java" \
 "$PROJ/android-direct/src/pro/sitescope/app/HostsEntry.java" \
 "$PROJ/android-direct/src/pro/sitescope/app/HostsParser.java" \
 "$PROJ/android-direct/src/pro/sitescope/app/HostsVpnPacket.java" \
 "$PROJ/tests/hosts-jvm/HostsHarness.java"
"$JAVA_HOME/bin/java" -cp "$OUT" HostsHarness
python3 - <<'PY' "$PROJ"
import pathlib,sys
root=pathlib.Path(sys.argv[1])
manifest=(root/'android-direct/AndroidManifest.xml').read_text()
activity=(root/'android-direct/src/pro/sitescope/app/HostsActivity.java').read_text()
root_manager=(root/'android-direct/src/pro/sitescope/app/HostsRootManager.java').read_text()
service=(root/'android-direct/src/pro/sitescope/app/HostsVpnService.java').read_text()
for token in ['.HostsActivity','.HostsVpnService','android.permission.BIND_VPN_SERVICE','foregroundServiceType="specialUse"']:
 assert token in manifest,token
for token in ['Вставить блок','Сырой текст','Предпросмотр','Резервные копии','Systemless через Magisk','BASIC · только Hosts через локальный DNS-VPN']:
 assert token in activity,token
for token in ['MODULE_DIR','mount --bind','module.prop','backup','sha256']:
 assert token in root_manager,token
for token in ['VpnService','addDnsServer','protect(socket)','HostsVpnPacket']:
 assert token in service,token
print('Hosts Manager Android contract PASS')
PY
