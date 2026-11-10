# SiteScope Pro 0.39.0-alpha37 — отчёт реализации

## Неблокирующая активация XRAY

Жизненный цикл разделён на две независимые части:

### Обязательная активация

- Android TUN создан;
- Xray-core работает;
- HEV работает;
- Android видит собственную VPN-сеть SiteScope.

После этих проверок служба сразу устанавливает:

- lifecycle `ACTIVE`;
- `active=true`;
- активную кнопку «Отключить VPN»;
- неактивную кнопку «VPN активен».

### Необязательная health-проверка

`XrayRuntime.probeOutbound()` запускается в `SiteScopeXrayHealthProbe` после
активации сессии.

Успех:

- `XRAY_OUTBOUND_CONFIRMED`;
- статус «Интернет подтверждён»;
- отображение задержки.

Ошибка или timeout:

- `XRAY_HEALTH_WARNING`;
- статус «туннель активен, Интернет не подтверждён»;
- TUN, Xray и HEV не останавливаются;
- BASIC fallback не запускается.

## Удаление глобального network binding

Из службы удалены:

- `bindProcessForStartup()`;
- `clearProcessNetworkBinding()`;
- `ConnectivityManager.bindProcessToNetwork()`.

Сохраняются:

- `Builder.setUnderlyingNetworks()`;
- `VpnService.setUnderlyingNetworks()`;
- `addDisallowedApplication(getPackageName())`.

Это сохраняет корректную underlay и защиту от loop без глобального изменения
маршрутизации Activity и локального SOCKS-соединения.

## Собственная VPN-сеть

`VPN_NETWORK_CHECK` теперь проверяет:

- owner UID на Android 10+;
- либо наличие TUN-адресов SiteScope в LinkProperties.

Чужой оставшийся VPN больше не может ошибочно считаться VPN SiteScope.

## Восстановление после прерывания процесса

Добавлен `VpnStateRecovery` и service pulse:

- `serviceHeartbeatAt` обновляется каждые 1,5 секунды;
- stale lifecycle восстанавливается через 9 секунд;
- событие `VPN_STALE_RECOVERED` записывается в журнал;
- кнопки возвращаются в рабочее состояние;
- после возврата на MainActivity автоматически открывается Hosts Manager.

## Диагностика probe

Добавлены этапы:

- `XRAY_PROBE_LOCAL_CONNECT`;
- `XRAY_PROBE_LOCAL_CONNECTED`;
- `XRAY_PROBE_SOCKS_READY`;
- `XRAY_PROBE_REMOTE_REQUEST`.

Они показывают, где именно остановилась фоновая проверка, не влияя на активную
VPN-сессию.

## Версия

- package: `pro.sitescope.app`
- versionName: `0.39.0-alpha37`
- versionCode: `390037`
- minSdk: 28
- target/compile SDK: 36
- Xray ABI: arm64-v8a

## Проверки

Пройдены все 20 наборов:

- static validation;
- Hosts Manager;
- Hosts + Xray и полная Java-компиляция;
- Xray config harness;
- scanner;
- discovery;
- IP Intelligence;
- provider core;
- IPTV;
- multicast;
- mDNS;
- Protocol Router;
- native clients;
- connection inventory;
- backup;
- TLS trust;
- stabilization;
- UI cleanup;
- SEO/domain;
- 15 backend pytest.

Дополнительно:

- D8: PASS;
- zipalign: PASS;
- APK Signature Scheme v3: PASS;
- certificate SHA-256:
  `0b3e3e9524ed599ce37824d8f020482f441e1db0b16a14be2d287a36cc802e15`.
