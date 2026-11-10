# Threat Model

## Основные угрозы
- SSRF через URL и redirects;
- DNS rebinding;
- oversized response/decompression bomb;
- crawler loops;
- утечка API keys;
- злоупотребление сканированием;
- HTML/script injection в отчётах.

## Меры 0.1.0
- TargetGuard и блок private/reserved addresses;
- лимиты страницы, глубины, размера и timeout;
- экранирование пользовательских данных UI-фреймворком;
- отсутствие исполнения JavaScript в backend baseline;
- провайдерные секреты только через environment;
- audit evidence хранится как данные, не исполняемый HTML.
