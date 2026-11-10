# Современные поисковые метрики — SiteScope Pro 0.18.0-alpha16

## Цель изменения

В приложении больше не предполагается наличие публичного «Google Rank» и не используется устаревший тИЦ. Вместо них реализована работа с современными официальными источниками данных:

- Google Search Console;
- Google PageSpeed Insights и Lighthouse;
- Core Web Vitals: LCP, INP и CLS;
- Яндекс Вебмастер;
- индекс качества сайта — ИКС / SQI.

## Google

Google не публикует единый числовой рейтинг сайта. В интерфейсе показываются:

- клики;
- показы;
- CTR;
- средняя позиция;
- период выборки;
- выбранное Search Console property;
- Lighthouse Performance;
- Lighthouse SEO;
- Accessibility;
- Best Practices;
- полевые LCP, INP, CLS, FCP и TTFB при наличии данных CrUX;
- лабораторные LCP, CLS и Total Blocking Time.

Средняя позиция подписывается именно как средняя позиция выборки, а не как рейтинг домена.

## Яндекс

Вместо тИЦ приложение показывает:

- актуальный ИКС;
- дату значения ИКС;
- подтверждённость прав на сайт;
- количество страниц в поиске, если поле доступно в API;
- критические и фатальные проблемы из сводки Вебмастера.

ИКС получается через официальный ресурс `sqi-history` API Яндекс Вебмастера.

## Search Readiness

В приложении присутствует собственный показатель `Search Readiness` от 0 до 100. Он рассчитывается из:

- технической оценки SiteScope Pro;
- индексируемости;
- HTTPS и безопасности;
- Lighthouse Performance;
- Lighthouse SEO.

Показатель явно обозначен как расчётная метрика SiteScope Pro. Он не является фактором Google, PageRank, ИКС или гарантией позиции.

## Авторизация и хранение секретов

В alpha-версии пользователь самостоятельно вставляет OAuth access token для принадлежащего ему подтверждённого ресурса.

При включённой настройке сохранения:

- токены шифруются AES-GCM;
- ключ создаётся в Android Keystore;
- токены не записываются в APK;
- токены не отправляются на сервер SiteScope Pro;
- запрос выполняется непосредственно к выбранному официальному API.

Следующий production-этап — полноценный OAuth Authorization Code + PKCE без ручной вставки токена.

## Ограничения текущей версии

- live-запросы зависят от доступности API и квот;
- PageSpeed может работать без API key, но ключ рекомендуется для регулярного использования;
- Search Console и Яндекс Вебмастер возвращают данные только для ресурсов, к которым пользователь имеет права;
- данные CrUX могут отсутствовать для сайтов с недостаточным объёмом полевых данных;
- отдельной официальной метрики «AI Overview Rank» или «AI Mode Rank» нет;
- хороший Core Web Vitals сам по себе не гарантирует высокую позицию.

## Официальные источники

- https://developers.google.com/search/docs/appearance/ranking-systems-guide
- https://developers.google.com/search/docs/appearance/core-web-vitals
- https://developers.google.com/search/docs/appearance/page-experience
- https://developers.google.com/webmaster-tools
- https://developers.google.com/webmaster-tools/v1/searchanalytics/query
- https://developers.google.com/speed/docs/insights/v5/about
- https://developers.google.com/search/docs/fundamentals/ai-optimization-guide
- https://yandex.ru/support/webmaster/ru/site-quality-index
- https://yandex.ru/dev/webmaster/doc/ru/reference/sqi-history
- https://yandex.ru/dev/webmaster/doc/ru/concepts/getting-started
