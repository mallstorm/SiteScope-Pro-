# Текущий статус этапов SiteScope Pro — 0.4.0-alpha2

| Этап | Статус | Фактическое состояние |
|---|---|---|
| 1. Основа | Alpha | Нативная навигация, проекты, локальная история, офлайн-сборка; production persistence/auth ещё не закрыты |
| 2. Быстрый анализ | Alpha работает | HTTP, DNS, TLS, headers, metadata и базовый экран результата |
| 3. SEO crawler | Backend baseline | Ограниченный crawler есть в backend, Android UI ещё не подключён |
| 4. Полный аудит | Первая версия | Rule engine и оценка главной страницы; полный каталог ТЗ не закрыт |
| 5. Производительность | Контракты | PageSpeed/Lighthouse worker и полевые CWV ещё не подключены |
| 6. Интеграции | Архитектура | OAuth Google/Яндекс и внешние коммерческие API не завершены |
| 7. Сетевые инструменты | Alpha | DNS/IP, reverse DNS, HTTP/TLS; WHOIS/RDAP/ASN/mail/reputation ещё впереди |
| 8. Авторизованный сканер | Рабочая alpha | CIDR/ranges/1-800, concurrency, cancel, fingerprinting, карты, история и share; background/CMDB/diff ещё не закрыты |
| 9. Мониторинг | Backend-модели | Нет полноценного Android scheduler и уведомлений |
| 10. Отчёты и AI | Baseline | Markdown/HTML/CSV и evidence-bound plan; PDF/XLSX/UI providers ещё не закрыты |
| 11. Безопасность | Базовые guardrails | Ограничения scanner/SSRF; production pentest и OAuth hardening не завершены |
| 12. Релиз | Тестовый release | Подписанный APK и offline pipeline; device matrix, beta и Play Store не завершены |

## Зафиксированная контрольная точка

`0.4.0-alpha2` можно закрепить как рабочую alpha-реализацию инженерного TCP scanner и продолжающийся технический фундамент проекта.

Нельзя закреплять все этапы 1–12 как полностью выполненные по критериям исходного ТЗ.
