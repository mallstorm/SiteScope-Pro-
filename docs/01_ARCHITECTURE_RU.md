# Архитектура

## Компоненты

### Android
Compose UI → ViewModel → Repository → REST API / Room.

### Backend
FastAPI → TargetGuard → Fetcher → Parser → RuleEngine → Result DTO.

### Долгие задания
В следующих версиях: PostgreSQL + Redis queue + workers. В 0.1.0 используется синхронное выполнение с жёсткими лимитами.

## Безопасность URL
До сетевого запроса выполняется:
- разрешение DNS;
- запрет localhost, loopback, link-local, multicast, reserved и private IP;
- повторная проверка каждого redirect;
- ограничение redirect count, response size и timeout.

## Расширяемость
Провайдеры реализуют единый интерфейс и не смешиваются с rule engine.
