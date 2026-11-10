# Hosts + Xray — единый комбинированный VPN

Версия реализации: **SiteScope Pro 0.32.0-alpha30**  
Дата: **4 августа 2026 года**

## Назначение

Режим решает ограничение Android: одновременно может быть активен только один `VpnService`. Поэтому локальная hosts-подмена и удалённый Xray-профиль объединены в одной службе `HostsVpnService`.

Доступны два режима:

- **BASIC** — локальная DNS-подмена по активному hosts-профилю без удалённого туннеля;
- **XRAY** — полный IPv4/IPv6 TUN, в котором hosts-подмена и весь пользовательский трафик проходят через один выбранный Xray outbound.

## Схема XRAY

```text
Android applications
        │
        ▼
VpnService TUN (IPv4 + IPv6)
        │
        ▼
hev-socks5-tunnel
        │
        ▼
127.0.0.1:10808 / private SOCKS inbound
        │
        ├── DNS port 53 ──► Xray DNS module + dns.hosts
        │
        └── all other traffic ──► selected Xray outbound
```

UID самого SiteScope Pro исключается из TUN через `addDisallowedApplication()`. Это предотвращает повторное попадание исходящих соединений Xray core в собственный VPN.

## Поддерживаемые профили

- VLESS;
- VMess;
- Trojan;
- Shadowsocks без внешнего plugin;
- полный Xray JSON с хотя бы одним реальным прокси-outbound.

Для ссылок поддерживаются основные транспортные параметры:

- TCP;
- WebSocket;
- gRPC;
- KCP;
- XHTTP/SplitHTTP;
- HTTPUpgrade;
- TLS;
- Reality.

Direct-only JSON, содержащий только `freedom`, `blackhole` или `dns`, отклоняется, потому что он не выполняет задачу удалённого туннеля.

## Hosts внутри Xray

Активные записи Hosts Manager преобразуются в `dns.hosts`. Существующие записи из полного JSON сохраняются, после чего добавляются правила активного профиля SiteScope.

Raw DNS-трафик из private SOCKS inbound с портом 53 первым правилом направляется в Xray DNS outbound. Следующим правилом весь оставшийся трафик private SOCKS inbound принудительно направляется в выбранный proxy outbound.

## Хранение профиля

Сохраняется один активный Xray-профиль.

- raw-ссылка или JSON шифруются через `SecureStore`;
- используется AES/GCM и Android Keystore;
- в обычных SharedPreferences остаются только имя, тип и endpoint;
- raw-профиль не включается в переносную резервную копию;
- экран после сохранения не показывает UUID, пароль, private key или полный JSON.

## Встроенные компоненты

В APK включены:

- `libv2ray.jar`;
- `lib/arm64-v8a/libgojni.so`;
- `lib/arm64-v8a/libhev-socks5-tunnel.so`;
- `assets/geoip.dat`;
- `assets/geosite.dat`;
- `assets/geoip-only-cn-private.dat`.

Java/JNI bridge оставлен с обязательным именем класса:

`com.v2ray.ang.service.TProxyService`

Это имя соответствует JNI-регистрации библиотеки из предоставленного v2rayNG Unified ARM64.

## Отказоустойчивость

- watchdog проверяет Xray core каждые четыре секунды;
- неожиданное завершение core вызывает остановку полного TUN и возврат в BASIC;
- ошибка первичного запуска XRAY также переводит приложение в BASIC;
- если BASIC восстановить невозможно, служба останавливается без sticky restart-loop;
- остановка закрывает HEV, Xray core и файловый дескриптор TUN;
- изменение hosts-профиля в BASIC выполняет reload;
- изменение hosts-профиля в XRAY приводит к безопасному перезапуску конфигурации.

## Диагностика

Экран Hosts Manager показывает:

- активность VPN;
- BASIC/XRAY;
- профиль;
- endpoint;
- версию core;
- состояние запуска или fallback;
- redacted lifecycle-журнал.

Фильтр журнала удаляет UUID, password/token/private key, VLESS/Trojan/Shadowsocks userinfo и VMess payload. Секрет профиля в диагностике не выводится.

## ABI и ограничения

Эта сборка содержит native Xray только для **arm64-v8a**. На устройстве другой архитектуры потребуется отдельная сборка с соответствующими native-библиотеками либо BASIC-only APK.

Статические и JVM-проверки не заменяют испытание на физическом Android-устройстве. Требуется проверить:

- реальный VLESS/VMess/Trojan/SS сервер;
- смену внешнего IP;
- hosts override через тот же туннель;
- DNS leak;
- Android Private DNS;
- браузерный Secure DNS/DoH;
- Wi-Fi и мобильную сеть;
- смену сети, сон и battery optimization;
- stop/restart и аварийный fallback.
