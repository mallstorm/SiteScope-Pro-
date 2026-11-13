# Встроенные Tor и I2P (beta31)

| Компонент | Файл | Версия | SHA-256 | Лицензия |
|---|---|---|---|---|
| Tor (исполняемый файл для Android arm64) | `app/src/main/jniLibs/arm64-v8a/libtor.so` | 0.4.9.5 | `f7db794857e10a9a6a4a267ffd68e495f043be22384e2de9ef5b645e2bf4a201` | BSD-3-Clause (Tor Project) |
| i2pd (JNI-библиотека PurpleI2P для Android arm64) | `app/src/main/jniLibs/arm64-v8a/libi2pd.so` | 2.60.0 | `b17b41807e9f6464af6b399dfbbfcf7b3d649d15acc0bf16cd627a5649092a56` | BSD-3-Clause (PurpleI2P) |
| JNI-обёртка | `app/src/main/java/org/purplei2p/i2pd/I2PD_JNI.java` | — | — | BSD-3-Clause (PurpleI2P); имя пакета и класса определяется символами `Java_org_purplei2p_i2pd_I2PD_1JNI_*` в библиотеке |
| Сертификаты reseed | `app/src/main/assets/i2pd/certificates/` | комплект i2pd | — | из дистрибутива i2pd |

**Происхождение.** Готовые бинарные файлы взяты из архивов, предоставленных владельцем приложения (RouteMatrix PRO 1.0.0-pro.4.2 и L2Ray 2.5.2, в обоих файлы совпадают побайтно). В этом репозитории они **не собираются из исходного кода**, воспроизводимость сборки не подтверждена. Перед публичным выпуском их нужно пересобрать из закреплённых исходников официальных проектов (torproject.org, github.com/PurpleI2P/i2pd) и обновить хеши.

Tor запускается как отдельный процесс из `nativeLibraryDir`, i2pd загружается через `System.loadLibrary("i2pd")` в отдельном процессе `:i2pcore`. Для Tor нет клиента подключаемых транспортов (obfs4, snowflake, webtunnel); поддерживаются только простые мосты IP:порт. Библиотеки выровнены по 4 КБ: на устройствах со страницей 16 КБ (Android 15+, режим разработчика) загрузка этих двух файлов может не удаться, пока они не будут пересобраны с `-Wl,-z,max-page-size=16384`.
