# Backend API

## Методы
- `GET /health`
- `POST /api/v1/analyze/quick`
- `POST /api/v1/crawl`
- `POST /api/v1/audit`
- `GET /api/v1/providers`

## Ограничения
- quick analysis: один URL;
- crawler: максимум 100 страниц в текущей версии;
- глубина: максимум 5;
- размер HTML: максимум 2 МБ;
- только HTTP/HTTPS;
- private/internal targets блокируются.

OpenAPI автоматически доступен по `/docs` после запуска.
