# Спецификация 37 — матрица достоверности тестов

## Реальные JVM/локальные проверки
- ScannerEngine подключается к локальным mock-серверам и определяет HTTP, SSH, SMTP, SIP, RTSP, MQTT, AMQP, RDP, Memcached и udpxy.
- NetworkDiscoveryEngine проверяет парсинг SSDP, ONVIF и mDNS.
- IPTV harness проверяет M3U/XMLTV, MPEG-TS, SDT и PMT.
- IP Intelligence harness проверяет CIDR, IPv6, CSV и версии DB-IP.

## Контрактные проверки
- наличие Android Activity в DEX;
- наличие требуемых методов и защитных параметров;
- backup schema;
- разделение экранов;
- staging DB-IP;
- SQLite-таблицы.

Контрактный PASS означает, что код присутствует и компилируется. Он не означает успешное подключение к любому реальному серверу.

## Требуется физический smoke-test
- полное обновление с 0.26;
- миграция проектов и истории;
- DB-IP update при обрыве;
- 1000+ результатов сканера;
- SSH/SFTP, VNC, SNMP, MQTT, ONVIF и DLNA на реальном оборудовании.
