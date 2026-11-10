# Спецификация 30 — Protocol Router и клиенты сетевых сервисов

## Назначение
Каждый обнаруженный endpoint должен быть не только строкой отчёта, но и запускать подходящий клиент.

## Реестр протоколов

| Протокол | Обработчик 0.21 |
|---|---|
| HTTP / HTTPS | встроенный Web Client |
| RTSP | встроенный IPTV Player |
| SSDP / UPnP | встроенный Device Center |
| mDNS / DNS-SD | встроенный Service Center |
| ONVIF | встроенный Camera Center + RTSP |
| SSH / SFTP | Android external client |
| RDP / VNC | Android external client |
| FTP / FTPS | Android external client |
| SMB / WebDAV | Android external client |
| MQTT / SNMP / SIP / Telnet | Android external client / inspector |
| Unknown TCP | Service Action Center |

## Правила безопасности
- Клиент запускается только после нажатия пользователя.
- Пароли и приватные ключи не сохраняются в обычных preferences.
- Ошибки TLS не игнорируются.
- Неизвестный SSH fingerprint не принимается автоматически.
- UPnP и ONVIF discovery работают read-only.
- Приложение не выполняет подбор паролей или эксплуатацию сервисов.

## Назначение обработчика
Для протокола можно выбрать:
- встроенный клиент;
- внешнее Android-приложение;
- панель действий.

Настройки включены в резервную копию, но учётные данные в неё не входят.

## mDNS
Обязательные DNS record types:
- PTR;
- SRV;
- TXT;
- A;
- AAAA.

SRV является источником фактического порта. Порт 5353 — это только транспорт discovery и не должен использоваться как порт найденного HTTP/SSH/SMB-сервиса.
