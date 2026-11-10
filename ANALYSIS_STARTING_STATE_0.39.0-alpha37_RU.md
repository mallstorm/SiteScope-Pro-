# Анализ вечного STARTING и самопроизвольного закрытия VPN

Дата: 2026-08-05  
Исходная версия: SiteScope Pro 0.38.0-alpha36

## Что подтверждено журналом

Последние попытки стабильно проходят:

- `VPN_SLOT_READY` — прежний VPN исчез, мобильная сеть подтверждена;
- `XRAY_ENDPOINT_READY` — endpoint доступен по TCP;
- `VPN_UNDERLYING_SET | success=true`;
- `VPN_TUN_ESTABLISHED`;
- `XRAY_CORE_READY`;
- `HEV_READY`;
- `XRAY_RUNTIME_READY`;
- `XRAY_RUNTIME_RETURN`;
- `XRAY_PROBE_ENTER`.

Следовательно, другой VPN уже не удерживает системный слот. Android создаёт TUN,
Xray-core запускается, HEV стартует, endpoint доступен. Проблема возникает после
запуска локального SOCKS health probe.

## Основная ошибка 0.38

Версия 0.38 считала соединение активным только после успешного доступа через
Xray к одной из двух внешних целей (`www.gstatic.com` или `one.one.one.one`).
До завершения этого вспомогательного теста интерфейс оставался в `STARTING`.

Это неверная модель жизненного цикла:

1. VPN-сеть уже создана и Xray/HEV уже работают.
2. Тестовая цель может отвечать медленно, блокироваться сервером, DNS или сетью.
3. Ошибка тестовой цели не означает, что сам VPN должен быть уничтожен.
4. При ошибке probe 0.38 переходила в startup failure и закрывала уже созданный TUN.

Именно поэтому VPN-индикатор мог кратковременно появляться, после чего исчезать,
а кнопки оставались в состоянии запуска.

## Дополнительная проблема process-wide binding

0.38 временно применяла `ConnectivityManager.bindProcessToNetwork()` ко всему
процессу SiteScope. Это затрагивало не только Xray, но и Activity, UI-потоки и
локальное соединение к `127.0.0.1`. В журнале появлялись повторные
`VPN_PROCESS_UNBOUND`, потому что снятие binding выполнялось из нескольких путей.

По журналу нельзя доказать, что именно binding вызывал возврат на главный экран,
но он был ненужным глобальным воздействием на процесс и удалён.

## Почему интерфейс мог оставаться заблокированным

Если Android или нативный core прерывал процесс до записи `VPN_START_FAILED`, в
SharedPreferences сохранялось старое `STARTING`. После повторного открытия
приложение доверяло этому флагу и показывало:

- «Применить» — неактивно;
- «Отключить VPN» — активно;
- ожидание без ограничения.

## Исправление 0.39

- TUN + Xray-core + HEV + собственная Android VPN-сеть теперь сразу дают `ACTIVE`.
- Health probe запускается отдельно и не блокирует startup.
- Ошибка/timeout health probe не закрывает VPN.
- Удалён process-wide `bindProcessToNetwork()`.
- Сохраняются `setUnderlyingNetworks()` и исключение пакета SiteScope из TUN,
  необходимые для защиты от VPN-loop.
- Добавлена проверка, что Android видит именно VPN текущего UID/его TUN-адреса.
- Добавлен heartbeat самой службы каждые 1,5 секунды.
- При stale `STARTING/ACTIVE/STOPPING` старше 9 секунд состояние восстанавливается
  в ERROR и кнопки разблокируются.
- Если процесс был перезапущен и Android вернул пользователя на главную SiteScope,
  приложение автоматически снова открывает Hosts Manager.

## Новая последовательность

```text
VPN_SLOT_READY
VPN_UNDERLAY_READY
XRAY_ENDPOINT_READY
VPN_UNDERLYING_SET
VPN_TUN_ESTABLISHED
XRAY_CORE_READY
HEV_READY
XRAY_RUNTIME_READY
XRAY_RUNTIME_RETURN
VPN_NETWORK_CHECK | visible=true, own=true
XRAY_READY | bootstrap confirmed, health=pending
XRAY_HEALTH_PROBE_START
```

Затем без остановки туннеля появляется один из результатов:

```text
XRAY_OUTBOUND_CONFIRMED
```

или:

```text
XRAY_HEALTH_WARNING
```

Во втором случае VPN остаётся активным, а интерфейс сообщает, что Интернет пока
не подтверждён.
