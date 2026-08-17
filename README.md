# SiteScope Pro

**SiteScope Pro** — Android-приложение для SEO-аудита, IP-инжиниринга, IPTV-инжиниринга, Hosts Manager и Xray/Hosts VPN.

## Текущий канонический baseline

**`0.39.0-alpha37`** (`versionCode 390037`)

- package: `pro.sitescope.app`
- minSdk: `28`
- compileSdk / targetSdk: `36`
- Android ABI для Xray runtime: `arm64-v8a`
- статус исходной версии: **validated / OVERALL=PASS**

17 августа 2026 года выполнена повторная сверка сохранённых исходников SiteScope Pro с GitHub. Самый новый доступный source snapshot — `SiteScopePro-0.39.0-alpha37-Sources.zip`; более новой версии SiteScope Pro в доступной истории после `alpha37` не обнаружено.

## Что закреплено в alpha37

### Hosts + Xray VPN

- TUN + Xray-core + HEV + подтверждение собственной Android VPN-сети переводят lifecycle в `ACTIVE` без ожидания внешнего probe.
- Health probe выполняется отдельно и не уничтожает уже работающий VPN при диагностическом timeout/ошибке.
- Удалено process-wide network binding, способное ломать маршрутизацию процесса.
- Проверка собственной VPN-сети использует owner UID / LinkProperties вместо ненадёжного внешнего connectivity probe.
- Добавлены heartbeat и stale-service recovery; зависшее состояние может быть восстановлено без перезапуска приложения.
- После восстановления интерфейс возвращается в Hosts Manager.

### SEO

- SEO-аудит и техническая диагностика сайта.
- Определение CMS/технологий.
- DNS, RDAP и доменная инфраструктура.
- Локальная SQLite-база и атомарное обновление DB-IP данных.

### IP engineering

- IP intelligence и provider lookup.
- CIDR/host/port workflows.
- LAN/network engineering функции.

### IPTV engineering

- IPTV provider/client workflows.
- Multicast/UDP tooling.
- Инженерные проверки IPTV endpoints.

## Каноническая структура исходного пакета

```text
scripts/
tests/
backend/
android/
android-direct/
docs/
contracts/
third_party/
```

Основная Android-реализация находится в `android-direct/src` и `android-direct/res`.

## Runtime/build inputs

Исходный пакет также содержит крупные runtime-артефакты, которые не являются редактируемым исходным кодом:

- `android-direct/assets/geoip.dat`
- `android-direct/assets/geosite.dat`
- `android-direct/lib/arm64-v8a/libgojni.so`
- `android-direct/lib/arm64-v8a/libhev-socks5-tunnel.so`
- `android-direct/libs/jsch-0.2.16.jar`

Эти файлы нужны для полностью автономной APK-сборки соответствующей версии, но должны рассматриваться как build/runtime inputs, а не как основной исходный код проекта.

## Проверка alpha37

Исторический validation report для `0.39.0-alpha37` подтверждает:

- `OVERALL=PASS`;
- Hosts Manager contract — PASS;
- Hosts + Xray combined VPN contract — PASS;
- Android/Java compile contracts — PASS;
- IP intelligence/provider core — PASS;
- IPTV — PASS;
- multicast — PASS;
- D8 — PASS;
- zipalign — PASS;
- APK Signature Scheme v3 — PASS.

## Правило дальнейшей разработки

Каноническая версия для продолжения разработки — **`0.39.0-alpha37`**. Следующие изменения не должны откатывать уже работающие Hosts/Xray lifecycle, SEO/IP/IPTV модули и утверждённую структуру интерфейса.

> Примечание по синхронизации: повреждённые промежуточные транспортные chunks, не прошедшие контроль целостности при переносе через GitHub connector, намеренно удалены. Они не являются частью исходников SiteScope Pro.
