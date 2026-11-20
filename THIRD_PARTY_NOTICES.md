# Сторонние компоненты

| Компонент | Использование | Лицензия |
|---|---|---|
| [byedpi](https://github.com/hufrea/byedpi) | Версия 0.17.3 с локальным патчем, `libbyedpi.so` | [MIT](native/third_party/byedpi/LICENSE) |
| [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) | Версия 2.19.3, `libhev-socks5-tunnel.so` и обработка SOCKS5 | [MIT](native/third_party/hev-socks5-tunnel/LICENSE), [MIT для SOCKS5 Core](native/third_party/hev-socks5-tunnel/src/core/LICENSE) |
| [lwIP](https://savannah.nongnu.org/projects/lwip/) | Статически связан с `libhev-socks5-tunnel.so` | [BSD-3-Clause](native/third_party/hev-socks5-tunnel/third-part/lwip/LICENSE) |
| [LibYAML](https://github.com/yaml/libyaml) | Форк проекта hev, статически связан с `libhev-socks5-tunnel.so` | [MIT](native/third_party/hev-socks5-tunnel/third-part/yaml/LICENSE) |
| [hev-task-system](https://github.com/heiher/hev-task-system) | Статически связан с `libhev-socks5-tunnel.so` | [MIT](native/third_party/hev-socks5-tunnel/third-part/hev-task-system/LICENSE) |
| [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) | Схема Telegram-прокси, которую использует `TgBridge.kt` | [MIT](https://github.com/Flowseal/tg-ws-proxy/blob/HEAD/LICENSE) |
| [Inter](https://github.com/rsms/inter), [Unbounded](https://github.com/googlefonts/unbounded) | Шрифты Android-приложения | [SIL Open Font License 1.1](licenses/OFL-1.1.txt) |
| [Gradle Wrapper](https://github.com/gradle/gradle), [Android Open Source Project](https://source.android.com/) | Wrapper и правила сборки Android NDK | [Apache License 2.0](licenses/Apache-2.0.txt) |
