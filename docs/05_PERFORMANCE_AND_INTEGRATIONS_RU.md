# Производительность и интеграции

## Производительность
Подготовлен интерфейс `PerformanceProvider` и адаптер PageSpeed. При отсутствии ключа или разрешения возвращается контролируемый статус, а не фиктивные значения.

## Интеграции
- Google Search Console — OAuth и подтверждённые ресурсы.
- Google PageSpeed Insights — API key.
- Яндекс Вебмастер — OAuth.
- внешние backlink/authority providers — отдельные адаптеры.

## Секреты
Ключи не включаются в APK и не коммитятся. Backend читает их из environment/secret manager.

---

## Реализация 0.18.0-alpha16

Актуальная модель метрик реализована в Android и backend:

- Google Search Console: клики, показы, CTR и средняя позиция;
- PageSpeed Insights: mobile/desktop;
- Core Web Vitals: LCP, INP, CLS;
- Lighthouse: Performance, SEO, Accessibility, Best Practices;
- Яндекс Вебмастер: ИКС/SQI и сводка состояния сайта;
- Android Keystore для пользовательских OAuth-токенов.

Публичный Google Rank не используется. тИЦ отмечен как устаревший. Подробности: `docs/17_MODERN_SEARCH_METRICS_RU.md`.
