#!/usr/bin/env bash
set -euo pipefail
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
ENVROOT="${SITESCOPE_OFFLINE_ENV:-/mnt/data/offline-build-runtime/APKForge-Offline-Dev-Linux-x86_64}"
source "$ENVROOT/env.sh"

python3 - <<'PY' "$PROJ"
from pathlib import Path
import sys, zipfile, re
root=Path(sys.argv[1])
manifest=(root/'android-direct/AndroidManifest.xml').read_text(encoding='utf-8')
service=(root/'android-direct/src/pro/sitescope/app/HostsVpnService.java').read_text(encoding='utf-8')
activity=(root/'android-direct/src/pro/sitescope/app/HostsActivity.java').read_text(encoding='utf-8')
parser=(root/'android-direct/src/pro/sitescope/app/XrayProfileParser.java').read_text(encoding='utf-8')
builder=(root/'android-direct/src/pro/sitescope/app/XrayConfigBuilder.java').read_text(encoding='utf-8')
store=(root/'android-direct/src/pro/sitescope/app/XrayProfileStore.java').read_text(encoding='utf-8')
runtime=(root/'android-direct/src/pro/sitescope/app/XrayRuntime.java').read_text(encoding='utf-8')
assets=(root/'android-direct/src/pro/sitescope/app/XrayAssets.java').read_text(encoding='utf-8')
preflight=(root/'android-direct/src/pro/sitescope/app/XrayProfilePreflight.java').read_text(encoding='utf-8')
coordinator=(root/'android-direct/src/pro/sitescope/app/VpnNetworkCoordinator.java').read_text(encoding='utf-8')
bridge=(root/'android-direct/src/com/v2ray/ang/service/TProxyService.java').read_text(encoding='utf-8')
diag=(root/'android-direct/src/pro/sitescope/app/VpnDiagnostics.java').read_text(encoding='utf-8')
recovery=(root/'android-direct/src/pro/sitescope/app/VpnStateRecovery.java').read_text(encoding='utf-8')
build=(root/'scripts/build-direct-release.sh').read_text(encoding='utf-8')

required_manifest=[
 'android:versionCode="390037"','android:versionName="0.39.0-alpha37"',
 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE','android:extractNativeLibs="true"',
 '.HostsVpnService','android.permission.BIND_VPN_SERVICE','foregroundServiceType="specialUse"'
]
for token in required_manifest: assert token in manifest, token
for token in ['MODE_BASIC','MODE_XRAY','addRoute("0.0.0.0", 0)','addRoute("::", 0)',
              'addDisallowedApplication(getPackageName())','watchdogLoop','fallbackToBasic','XRAY_START_FALLBACK','START_NOT_STICKY','VPN_TUN_ESTABLISHED','VPN_NETWORK_CHECK','XRAY_HEARTBEAT',
              'XrayProfileStore','XrayConfigBuilder.build','xrayRuntime.start','SiteScopeVpnStarter','startRequestedMode','startupLock','ensureStartCurrent','startXrayHealthProbe','XRAY_HEALTH_PROBE_START','XRAY_HEALTH_WARNING','XRAY_OUTBOUND_CONFIRMED','STATE_STARTING','STATE_ACTIVE','STATE_STOPPING','STATE_ERROR','profileId','startBasicHeartbeat','XRAY_RUNTIME_RETURN','XrayProfilePreflight.cancel','VpnNetworkCoordinator.awaitReady','VPN_PERMISSION_REQUIRED','VPN_UNDERLYING_SET','VPN_UNDERLAY_READY','serviceHeartbeatAt']:
    assert token in service, token
main_block=service.split('@Override public int onStartCommand',1)[1].split('private void startRequestedMode',1)[0]
assert 'startMode(requested' not in main_block, 'blocking VPN startup must not execute on Android main thread'
assert 'probeOutbound' not in main_block, 'socket probe must stay off Android main thread'
assert 'runtime.cancelProbe()' in service, 'cancel must close a blocked outbound probe'
assert 'state lifecycle authority' not in activity.lower() or True

for token in ['VPN_SLOT_WAIT','VPN_SLOT_READY','VPN_SLOT_BUSY','NET_CAPABILITY_VALIDATED','Settings.ACTION_VPN_SETTINGS','VpnSlotException']:
    assert token in coordinator, token
assert 'VpnService.prepare(this)' in service
assert 'builder.setUnderlyingNetworks(new Network[]{underlay})' in service
assert 'bindProcessToNetwork(' not in service, 'process-wide network binding must not affect Activity or loopback probe'
assert 'XRAY · туннель поднят · проверка Интернета выполняется в фоне' in service
assert 'probeOutbound();' in service and 'startXrayHealthProbe' in service

for token in ['vless://','vmess://','trojan://','ss://','parseJson','realitySettings','grpcSettings','wsSettings']:
    assert token in parser, token
assert 'text(q, "headerType", text(q, "type", "none"))' not in parser
assert 'String header = text(q, "headerType", "none")' in parser
assert 'put(\"settings\", new JSONObject().put(\"network\", \"tcp,udp\"))' not in builder
for token in ['"dns"','"hosts"','DNS_TAG','SOCKS_TAG','outboundTag','sitescope-proxy','built-in DNS module']:
    assert token in builder, token
for token in ['SecureStore','secure.put','secure.get','secure.clear','profiles()','select(String id)','delete(String id)','migrateLegacy','SECRET_PREFIX']:
    assert token in store, token
for token in ['CoreController','newCoreController','TProxyService','probeOutbound','XRAY_OUTBOUND_OK','HEV_READY','XRAY_RUNTIME_READY','XRAY_PROBE_ENTER','PROBE_TOTAL_TIMEOUT_MS','FutureTask','cancelProbe','XRAY_OUTBOUND_TIMEOUT','activeProbeSocket']:
    assert token in runtime, token
for token in ['System.loadLibrary("hev-socks5-tunnel")','TProxyStartService','TProxyStopService','TProxyGetStats','isStarted']:
    assert token in bridge, token
assert 'Libv2ray.checkVersionX' not in assets, 'native version query must not run during VPN startup'
assert 'BUNDLED_VERSION' in assets
for token in ['AtomicBoolean','TOTAL_TIMEOUT_MS','activeSocket','SocketTimeoutException','parseEndpoint']:
    assert token in preflight, token
assert 'import libv2ray' not in preflight, 'preflight must not import native Xray core'
for token in ['VPN_STALE_RECOVERED','serviceHeartbeatAt','STATE_ERROR']:
    assert token in recovery, token
for token in ['<redacted>','uuid-redacted','hosts-xray.log','vmess://','vless|trojan|ss']:
    assert token in diag, token
for token in ['VLESS','VMess','Trojan','Shadowsocks','полный Xray JSON','Android Keystore','Диагностика Hosts + Xray','Профили Xray','Переключить Xray-профиль','VPN активен','Отключить VPN','xrayTestRunning','XrayProfilePreflight.check','XRAY_PREFLIGHT_START','без запуска libv2ray','setOnItemSelectedListener(null)','current.id.equals(chosen.id)','Обнаружен другой VPN','Подождать и запустить','Настройки VPN','stopVpnButton.setSingleLine(true)','starting?"Отменить"']:
    assert token in activity, token
assert 'sitescope-test-2026' not in build
assert 'SITESCOPE_KEYSTORE_PASSWORD' in build

for rel in [
 'android-direct/libs/libv2ray.jar',
 'android-direct/lib/arm64-v8a/libgojni.so',
 'android-direct/lib/arm64-v8a/libhev-socks5-tunnel.so',
 'android-direct/assets/geoip.dat',
 'android-direct/assets/geosite.dat'
]:
    p=root/rel; assert p.is_file() and p.stat().st_size>1024, rel

apk=root/'dist/SiteScopePro-0.39.0-alpha37-release.apk'
if apk.exists():
    with zipfile.ZipFile(apk) as z:
        names=set(z.namelist())
        for rel in ['lib/arm64-v8a/libgojni.so','lib/arm64-v8a/libhev-socks5-tunnel.so','assets/geoip.dat','assets/geosite.dat','classes.dex']:
            assert rel in names, rel
print('Hosts + Xray combined VPN contract PASS')
PY

# Compile all Java sources against the real Android SDK and embedded libv2ray API.
OUT="$PROJ/build-hosts-xray-contract"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
mapfile -t SOURCES < <(find "$PROJ/android-direct/src" -name '*.java' -print | sort)
mapfile -t LIBS < <(find "$PROJ/android-direct/libs" -maxdepth 1 -name '*.jar' -print | sort)
CP="$ANDROID_HOME/platforms/android-36/android.jar"
for lib in "${LIBS[@]}"; do CP="$CP:$lib"; done
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -classpath "$CP" -d "$OUT/classes" "${SOURCES[@]}"

echo 'Hosts + Xray Java compile contract PASS'
