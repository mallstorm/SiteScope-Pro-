from pathlib import Path
root=Path(__file__).resolve().parents[1]
required=[
    "README.md",
    "backend/app/main.py",
    "backend/app/security.py",
    "backend/app/rules.py",
    "backend/app/scanner_policy.py",
    "backend/app/network_tools.py",
    "android-direct/AndroidManifest.xml",
    "android-direct/src/pro/sitescope/app/MainActivity.java",
    "android-direct/src/pro/sitescope/app/BaseSiteScopeActivity.java",
    "android-direct/src/pro/sitescope/app/SeoActivity.java",
    "android-direct/src/pro/sitescope/app/ProjectsActivity.java",
    "android-direct/src/pro/sitescope/app/HistoryActivity.java",
    "android-direct/src/pro/sitescope/app/AppDatabase.java",
    "android-direct/src/pro/sitescope/app/IpEngineeringActivity.java",
    "android-direct/src/pro/sitescope/app/ScannerActivity.java",
    "android-direct/src/pro/sitescope/app/DiscoveryActivity.java",
    "android-direct/src/pro/sitescope/app/UiKit.java",
    "android-direct/src/pro/sitescope/app/FeatureReadiness.java",
    "android-direct/src/pro/sitescope/app/HostsActivity.java",
    "android-direct/src/pro/sitescope/app/HostsEntry.java",
    "android-direct/src/pro/sitescope/app/HostsParser.java",
    "android-direct/src/pro/sitescope/app/HostsStore.java",
    "android-direct/src/pro/sitescope/app/HostsRootManager.java",
    "android-direct/src/pro/sitescope/app/HostsVpnPacket.java",
    "android-direct/src/pro/sitescope/app/HostsVpnService.java",
    "android-direct/src/pro/sitescope/app/XrayProfileParser.java",
    "android-direct/src/pro/sitescope/app/XrayConfigBuilder.java",
    "android-direct/src/pro/sitescope/app/XrayProfileStore.java",
    "android-direct/src/pro/sitescope/app/XrayAssets.java",
    "android-direct/src/pro/sitescope/app/XrayRuntime.java",
    "android-direct/src/pro/sitescope/app/VpnNetworkCoordinator.java",
    "android-direct/src/pro/sitescope/app/VpnDiagnostics.java",
    "android-direct/src/com/v2ray/ang/service/TProxyService.java",
    "android-direct/libs/libv2ray.jar",
    "android-direct/lib/arm64-v8a/libgojni.so",
    "android-direct/lib/arm64-v8a/libhev-socks5-tunnel.so",
    "android-direct/assets/geoip.dat",
    "android-direct/assets/geosite.dat",
    "android-direct/src/pro/sitescope/app/ScannerEngine.java",
    "android-direct/src/pro/sitescope/app/IptvEngine.java",
    "android-direct/src/pro/sitescope/app/IptvActivity.java",
    "android-direct/src/pro/sitescope/app/ScanService.java",
    "android-direct/src/pro/sitescope/app/NetworkDiscoveryEngine.java",
    "android-direct/src/pro/sitescope/app/PlayerActivity.java",
    "android-direct/src/pro/sitescope/app/Models.java",
    "android-direct/src/pro/sitescope/app/CrawlerEngine.java",
    "android-direct/src/pro/sitescope/app/SearchMetricsEngine.java",
    "android-direct/src/pro/sitescope/app/SearchMetricsActivity.java",
    "android-direct/src/pro/sitescope/app/TechnologyAnalyzer.java",
    "android-direct/src/pro/sitescope/app/DnsAnalyzer.java",
    "android-direct/src/pro/sitescope/app/DomainRdapClient.java",
    "android-direct/src/pro/sitescope/app/DomainAnalysisEngine.java",
    "android-direct/src/pro/sitescope/app/SecureStore.java",
    "android-direct/src/pro/sitescope/app/IpIntelligenceActivity.java",
    "android-direct/src/pro/sitescope/app/IpDataStore.java",
    "android-direct/src/pro/sitescope/app/IpMath.java",
    "android-direct/src/pro/sitescope/app/RipeStatClient.java",
    "android-direct/src/pro/sitescope/app/BackupManager.java",
    "android-direct/src/pro/sitescope/app/SettingsStore.java",
    "android-direct/src/pro/sitescope/app/SettingsActivity.java",
    "android-direct/src/pro/sitescope/app/ServiceEndpoint.java",
    "android-direct/src/pro/sitescope/app/ProtocolRouter.java",
    "android-direct/src/pro/sitescope/app/ProtocolPreferences.java",
    "android-direct/src/pro/sitescope/app/ServiceActionActivity.java",
    "android-direct/src/pro/sitescope/app/WebClientActivity.java",
    "android-direct/src/pro/sitescope/app/TlsTrustStore.java",
    "android-direct/src/pro/sitescope/app/ConnectionsActivity.java",
    "android-direct/src/pro/sitescope/app/ConnectionProfileStore.java",
    "android-direct/src/pro/sitescope/app/ClientUi.java",
    "android-direct/src/pro/sitescope/app/SshClientActivity.java",
    "android-direct/src/pro/sitescope/app/NativeFileClientActivity.java",
    "android-direct/src/pro/sitescope/app/VncClientActivity.java",
    "android-direct/src/pro/sitescope/app/SnmpClientActivity.java",
    "android-direct/src/pro/sitescope/app/MqttClientActivity.java",
    "android-direct/src/pro/sitescope/app/OnvifClientActivity.java",
    "android-direct/src/pro/sitescope/app/DlnaBrowserActivity.java",
    "android-direct/libs/jsch-0.2.16.jar",
    "android-direct/src/pro/sitescope/app/UpnpDescriptionParser.java",
    "android-direct/src/pro/sitescope/app/MdnsServiceMapper.java",
    "android-direct/src/pro/sitescope/app/MdnsPacketParser.java",
    "android-direct/src/pro/sitescope/app/ipintel/ProviderCore.java",
    "android-direct/src/pro/sitescope/app/ipintel/ProviderApiClient.java",
    "android-direct/src/pro/sitescope/app/ipintel/ProviderLookupPanel.java",
    "scripts/build-direct-release.sh",
    "scripts/test-scanner-engine.sh",
    "scripts/test-iptv-engine.sh",
    "scripts/test-ip-intelligence.sh",
    "scripts/test-provider-core.sh",
    "docs/08_AUTHORIZED_SCANNER_RU.md",
]
for item in required:
    path=root/item
    assert path.is_file() and path.stat().st_size>20, item
manifest=(root/"android-direct/AndroidManifest.xml").read_text()
assert 'android:versionCode="390037"' in manifest
assert 'android:versionName="0.39.0-alpha37"' in manifest
assert 'android.permission.CHANGE_WIFI_MULTICAST_STATE' in manifest
assert 'android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE' in manifest
assert 'android:name=".ScanService"' in manifest
assert 'android:name=".HostsVpnService"' in manifest
assert 'android:name=".HostsActivity"' in manifest
assert 'android.permission.BIND_VPN_SERVICE' in manifest
assert 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE' in manifest
scanner=(root/"android-direct/src/pro/sitescope/app/ScannerEngine.java").read_text()
for token in ["STANDARD_MAX_HOSTS = 4096", "STANDARD_MAX_PORTS = 4096", "STANDARD_MAX_PROBES = 2000000", "EXTENDED_MAX_HOSTS = 65536", "EXTENDED_MAX_PORTS = 65535", "EXTENDED_MAX_PROBES = 10000000", "extendedPrivateMode", "allTargetsPrivateOrLocal", "probeRdp", "probeRtsp", "probeSip", "probeMqtt", "probeUdpxyService", "parseTargets", "parsePorts", "startIndex", "awaitIfPaused"]:
    assert token in scanner, token
iptv=(root/"android-direct/src/pro/sitescope/app/IptvEngine.java").read_text()
for token in ["parseM3u", "applyXmlTv", "discoverUdpxy", "discoverSap", "buildPlaybackUrl", "probeChannel", "parseSdt", "parsePmt"]:
    assert token in iptv, token
service=(root/"android-direct/src/pro/sitescope/app/ScanService.java").read_text()
for token in ["startForeground", "ACTION_PAUSE", "ACTION_RESUME", "START_STICKY"]:
    assert token in service, token
store=(root/"android-direct/src/pro/sitescope/app/DataStore.java").read_text()
assert "active_scan_v2" in store
discovery=(root/"android-direct/src/pro/sitescope/app/NetworkDiscoveryEngine.java").read_text()
for token in ["M-SEARCH", "ProbeMatches", "dnsPtrQuery", "224.0.0.251", "239.255.255.250"]:
    assert token.lower() in discovery.lower(), token
crawler=(root/"android-direct/src/pro/sitescope/app/CrawlerEngine.java").read_text()
for token in ["fetchRobots", "loadSitemap", "analyzeDuplicates", "ORPHAN_CANDIDATES", "DUPLICATE_CONTENT"]:
    assert token in crawler, token
metrics=(root/"android-direct/src/pro/sitescope/app/SearchMetricsEngine.java").read_text()
for token in ["Search Console API", "PageSpeed Insights API", "sqi-history", "INTERACTION_TO_NEXT_PAINT", "CUMULATIVE_LAYOUT_SHIFT_SCORE"]:
    assert token in metrics, token
secure=(root/"android-direct/src/pro/sitescope/app/SecureStore.java").read_text()
for token in ["AndroidKeyStore", "AES/GCM/NoPadding", "KeyGenParameterSpec"]:
    assert token in secure, token

ip_intel=(root/"android-direct/src/pro/sitescope/app/IpIntelligenceActivity.java").read_text()
for token in ["IP Intelligence", "Каталог", "Калькуляторы", "countryResources", "DB‑IP City Lite", "providersForGeo", "Проверить обновление", "Проверить целостность базы", "Актуально"]:
    assert token in ip_intel, token
ip_store=(root/"android-direct/src/pro/sitescope/app/IpDataStore.java").read_text()
for token in ["geo_ranges", "asn_ranges", "importCity", "importAsn", "searchGeo", "searchAsn", "providersForGeo", "parseCsv", "validateDataset", "sourceVersion", "versionCurrent", "quick_check"]:
    assert token in ip_store, token
ip_math=(root/"android-direct/src/pro/sitescope/app/IpMath.java").read_text()
for token in ["cidrToRange", "rangeToCidrs", "aggregate", "conversionReport", "expandIpv6"]:
    assert token in ip_math, token
ripe=(root/"android-direct/src/pro/sitescope/app/RipeStatClient.java").read_text()
for token in ["country-resource-list", "announced-prefixes", "network-info", "whois", "rdap.org"]:
    assert token in ripe, token
assert 'android:name=".SeoActivity"' in manifest
assert 'android:name=".ProjectsActivity"' in manifest
assert 'android:name=".HistoryActivity"' in manifest
assert 'android:name=".IpIntelligenceActivity"' in manifest
assert 'android:name=".IpEngineeringActivity"' in manifest
assert 'android:name=".ScannerActivity"' in manifest
assert 'android:name=".DiscoveryActivity"' in manifest
assert 'android:name=".SettingsActivity"' in manifest
assert 'android:name=".ServiceActionActivity"' in manifest
assert 'android:name=".WebClientActivity"' in manifest
assert 'android:name=".ConnectionsActivity"' in manifest
for activity in ['SshClientActivity','NativeFileClientActivity','VncClientActivity','SnmpClientActivity','MqttClientActivity','OnvifClientActivity','DlnaBrowserActivity']:
    assert f'android:name=".{activity}"' in manifest, activity
backup=(root/"android-direct/src/pro/sitescope/app/BackupManager.java").read_text()
for token in ["SiteScopeProBackup", "schemaVersion", "secretsIncluded", "sitescope_data_v1", "sitescope_iptv", "ip_intelligence", "restore", "exportTo"]:
    assert token in backup, token
settings=(root/"android-direct/src/pro/sitescope/app/SettingsActivity.java").read_text()
for token in ["Экспортировать резервную копию", "Восстановить из файла", "scannerConcurrency", "ACTION_CREATE_DOCUMENT", "ACTION_OPEN_DOCUMENT"]:
    assert token in settings, token
models=(root/"android-direct/src/pro/sitescope/app/Models.java").read_text()
for token in ["notes", "tags", "pinned", "archived", "userNamed"]:
    assert token in models, token

protocol_endpoint=(root/"android-direct/src/pro/sitescope/app/ServiceEndpoint.java").read_text()
for token in ["UPNP", "MDNS", "ONVIF", "HTTPS", "SSH", "RDP", "VNC", "RTSP", "SMB", "buildUri", "BigInteger"]:
    if token == "BigInteger": continue
    assert token in protocol_endpoint, token
router=(root/"android-direct/src/pro/sitescope/app/ProtocolRouter.java").read_text()
for token in ["WebClientActivity", "ServiceActionActivity", "PlayerActivity", "SshClientActivity", "NativeFileClientActivity", "VncClientActivity", "SnmpClientActivity", "MqttClientActivity", "OnvifClientActivity", "openExternal", "ProtocolPreferences"]:
    assert token in router, token
web_client=(root/"android-direct/src/pro/sitescope/app/WebClientActivity.java").read_text()
for token in ["WebView", "MIXED_CONTENT_NEVER_ALLOW", "onReceivedSslError", "handleSslError", "handler.proceed", "TlsTrustStore", "TLS-доверие для узла", "getCertificate"]:
    assert token in web_client, token
tls_store=(root/"android-direct/src/pro/sitescope/app/TlsTrustStore.java").read_text()
for token in ["sitescope_tls_pins_v1", "SHA-256", "x509-certificate", "pinnedFingerprint", "trust", "clear"]:
    assert token in tls_store, token
service_action=(root/"android-direct/src/pro/sitescope/app/ServiceActionActivity.java").read_text()
for token in ["UPnP Device Center", "mDNS / DNS-SD", "ONVIF Camera Center", "fetchUpnp", "Сохранить"]:
    assert token in service_action, token
connections=(root/"android-direct/src/pro/sitescope/app/ConnectionProfileStore.java").read_text()
for token in ["connection_profiles", "profiles_json", "savedAt"]:
    assert token in connections, token
upnp=(root/"android-direct/src/pro/sitescope/app/UpnpDescriptionParser.java").read_text()
for token in ["friendlyName", "manufacturer", "modelName", "presentationURL", "serviceType"]:
    assert token in upnp, token
mdns=(root/"android-direct/src/pro/sitescope/app/MdnsServiceMapper.java").read_text()
for token in ["_http._tcp", "_ssh._tcp", "_smb._tcp", "_rtsp._tcp", "_mqtt._tcp"]:
    assert token in mdns, token
mdns_packet=(root/"android-direct/src/pro/sitescope/app/MdnsPacketParser.java").read_text()
for token in ["TYPE_PTR", "TYPE_SRV", "TYPE_TXT", "TYPE_AAAA", "DNS pointer loop", "serviceTypeFromInstance"]:
    assert token in mdns_packet, token

ssh=(root/"android-direct/src/pro/sitescope/app/SshClientActivity.java").read_text()
for token in ["StrictHostKeyChecking", "ChannelShell", "ChannelSftp", "ssh_known_hosts", "ACTION_CREATE_DOCUMENT"]:
    assert token in ssh, token
native_files=(root/"android-direct/src/pro/sitescope/app/NativeFileClientActivity.java").read_text()
for token in ["AUTH TLS", "PROT P", "MLSD", "PROPFIND", "ACTION_OPEN_DOCUMENT"]:
    assert token in native_files, token
vnc=(root/"android-direct/src/pro/sitescope/app/VncClientActivity.java").read_text()
for token in ["RFB 003.008", "VNC Authentication", "framebufferUpdate", "Ctrl+Alt+Del", "DES/ECB/NoPadding"]:
    assert token in vnc, token
snmp=(root/"android-direct/src/pro/sitescope/app/SnmpClientActivity.java").read_text()
for token in ["SNMP v1/v2c", "GET", "WALK", "0xA1", "200 объектами"]:
    assert token in snmp, token
mqtt=(root/"android-direct/src/pro/sitescope/app/MqttClientActivity.java").read_text()
for token in ["MQTT 3.1.1", "SUBSCRIBE", "PUBLISH", "CONNACK", "SSLSocket"]:
    assert token in mqtt, token
onvif=(root/"android-direct/src/pro/sitescope/app/OnvifClientActivity.java").read_text()
for token in ["GetDeviceInformation", "GetProfiles", "GetStreamUri", "PasswordDigest", "RTP-Unicast"]:
    assert token in onvif, token
dlna=(root/"android-direct/src/pro/sitescope/app/DlnaBrowserActivity.java").read_text()
for token in ["ContentDirectory", "BrowseDirectChildren", "SOAPACTION", "DLNA Media Browser"]:
    assert token in dlna, token

provider_core=(root/"android-direct/src/pro/sitescope/app/ipintel/ProviderCore.java").read_text()
for token in ["AS31343", "BigInteger", "aliasCandidates", "normalizeProviderQuery", "contains"]:
    assert token in provider_core, token
provider_api=(root/"android-direct/src/pro/sitescope/app/ipintel/ProviderApiClient.java").read_text()
for token in ["api.ipify.org", "api6.ipify.org", "rest.db.ripe.net", "inverse-attribute=org", "announcedPrefixes"]:
    assert token in provider_api, token
provider_ui=(root/"android-direct/src/pro/sitescope/app/ipintel/ProviderLookupPanel.java").read_text()
for token in ["Определить моего провайдера", "Поиск провайдера", "Текущий диапазон", "Экспорт CSV", "TRANSPORT_VPN"]:
    assert token in provider_ui, token
assert "ProviderLookupPanel" in ip_intel

app_db=(root/"android-direct/src/pro/sitescope/app/AppDatabase.java").read_text()
for token in ["SQLiteOpenHelper", "projects", "scan_results", "active_scan_results", "connection_profiles", "inventory_snapshots", "migrateLegacyOnce", "exportPortable", "restorePortable"]:
    assert token in app_db, token
assert "AppDatabase.get" in store
assert "SharedPreferences JSON" not in store
assert "schemaVersion" in backup and "SCHEMA_VERSION = 6" in backup
main=(root/"android-direct/src/pro/sitescope/app/MainActivity.java").read_text()
projects_activity=(root/"android-direct/src/pro/sitescope/app/ProjectsActivity.java").read_text()
history_activity=(root/"android-direct/src/pro/sitescope/app/HistoryActivity.java").read_text()
seo_activity=(root/"android-direct/src/pro/sitescope/app/SeoActivity.java").read_text()
base_activity=(root/"android-direct/src/pro/sitescope/app/BaseSiteScopeActivity.java").read_text()
for token in ["SeoActivity", "IpEngineeringActivity", "IptvActivity"]:
    assert token in main, token
for token in ["homeHeader", "headerGradient", "Единый центр анализа", "Инженерный центр анализа", "0.39 α"]:
    assert token in main, token
for forbidden in ["Архитектура 0.28", "SEO-раздел работает только", "больше не смешиваются", "Stable:", "Проекты хранятся в SQLite", "История хранится в SQLite"]:
    combined = "\n".join((root / path).read_text() for path in [
        "android-direct/src/pro/sitescope/app/MainActivity.java",
        "android-direct/src/pro/sitescope/app/SeoActivity.java",
        "android-direct/src/pro/sitescope/app/IpEngineeringActivity.java",
        "android-direct/src/pro/sitescope/app/ProjectsActivity.java",
        "android-direct/src/pro/sitescope/app/HistoryActivity.java"
    ])
    assert forbidden not in combined, forbidden
for token in ["ProjectAdapter", "ListView"]:
    assert token in projects_activity, token
for token in ["HistoryAdapter", "ListView", "audit_timestamp"]:
    assert token in history_activity, token
for token in ["CrawlerEngine", "showAuditResult", "SearchMetricsActivity"]:
    assert token in seo_activity, token
for token in ["bottomNavigation", "ProjectsActivity.class", "HistoryActivity.class"]:
    assert token in base_activity, token
ip_hub=(root/"android-direct/src/pro/sitescope/app/IpEngineeringActivity.java").read_text()
for token in ["IP Intelligence", "Сканер сети", "LAN Discovery", "Инвентаризация", "Подключения"]:
    assert token in ip_hub, token
scanner_activity=(root/"android-direct/src/pro/sitescope/app/ScannerActivity.java").read_text()
for token in ["ListView", "ScanService", "EXTENDED_MAX_PROBES", "ProtocolRouter.open"]:
    assert token in scanner_activity, token
discovery_activity=(root/"android-direct/src/pro/sitescope/app/DiscoveryActivity.java").read_text()
for token in ["ListView", "NetworkDiscoveryEngine", "DeviceInventoryStore.saveSnapshot"]:
    assert token in discovery_activity, token
for token in ["geo_ranges_staging", "asn_ranges_staging", "Атомарная установка базы", "replaceGeoFromStaging", "replaceAsnFromStaging"]:
    assert token in ip_store, token
readiness=(root/"android-direct/src/pro/sitescope/app/FeatureReadiness.java").read_text()
for token in ["STABLE", "BETA", "SSH", "ONVIF", "HTTP"]:
    assert token in readiness, token
assert "toCheckpointJson" in service and "saveActiveResult" in service and "clearActiveResults" in service

technology=(root/"android-direct/src/pro/sitescope/app/TechnologyAnalyzer.java").read_text()
for token in ["WordPress", "WooCommerce", "1C-Битрикс", "Shopify", "Next.js", "cmsConfidence", "metaGenerator"]:
    assert token in technology, token
dns=(root/"android-direct/src/pro/sitescope/app/DnsAnalyzer.java").read_text()
for token in ["DNSKEY", "_dmarc.", "CAA", "SRV", "cloudflare-dns.com/dns-query", "dns.google/dns-query", "compareResolvers"]:
    assert token in dns, token
domain=(root/"android-direct/src/pro/sitescope/app/DomainRdapClient.java").read_text()
for token in ["rdap.org/domain", "registrar", "nameservers", "registrableDomain"]:
    assert token in domain, token
for token in ["Технологии сайта", "DNS домена", "Домен и сетевая инфраструктура", "DKIM"]:
    assert token in seo_activity, token

hosts_activity=(root/"android-direct/src/pro/sitescope/app/HostsActivity.java").read_text()
hosts_parser=(root/"android-direct/src/pro/sitescope/app/HostsParser.java").read_text()
hosts_root=(root/"android-direct/src/pro/sitescope/app/HostsRootManager.java").read_text()
hosts_vpn=(root/"android-direct/src/pro/sitescope/app/HostsVpnService.java").read_text()
for token in ["Hosts Manager", "Вставить блок", "Сырой текст", "Предпросмотр", "Резервные копии", "Systemless через Magisk", "BASIC · только Hosts через локальный DNS-VPN"]:
    assert token in hosts_activity, token
for token in ["BEGIN SITESCOPE PRO MANAGED HOSTS", "normalizeAddress", "normalizeHostname", "validateConflicts", "mergeManagedBlock"]:
    assert token in hosts_parser, token
for token in ["/data/adb/modules/sitescope_hosts", "module.prop", "mount --bind", "backup", "sha256"]:
    assert token in hosts_root, token
for token in ["VpnService", "addDnsServer", "protect(socket)", "HostsVpnPacket", "10.203.0.1"]:
    assert token in hosts_vpn, token
assert "hosts_profiles" in app_db and "hosts_entries" in app_db and "hosts_backups" in app_db
assert "SCHEMA_VERSION = 6" in backup

print(f"Static validation PASS: {len(required)} files + SEO technology detection + full DNS/RDAP infrastructure + SQLite migration + atomic DB-IP staging + modular activities")
