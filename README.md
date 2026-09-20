# CoralVPN Client (Android)

«Одно приложение, одна кнопка» — Android-клиент для сервиса CoralVPN. Открыл →
вставил ссылку из бота (или deeplink) → нажал **Connect** → интернет идёт через VPN.

- **Ядро:** [sing-box](https://github.com/SagerNet/sing-box) через `libbox` (Go-биндинги).
- **База:** форк [sing-box-for-android (SFA)](https://github.com/SagerNet/sing-box-for-android) с урезанным UI (GPL-3.0).
- **Конфиг:** приходит готовым с нашего sub-сервера (`?fmt=singbox`) — клиент его не парсит, отдаёт в libbox как есть.
- **Дистрибуция:** APK на GitHub Releases (вне сторов). Сборка — GitHub Actions.
- **Протокол:** только Hysteria2 (весь прод CoralVPN — hy2-only).

## Документы

- [`docs/PRODUCT-CLIENT-ANDROID.md`](docs/PRODUCT-CLIENT-ANDROID.md) — описание продукта для команды.
- [`docs/SPEC-CLIENT-ANDROID.md`](docs/SPEC-CLIENT-ANDROID.md) — техническая спека (контракт с сервером, сценарии, acceptance).

## Контракт с сервером (кратко)

```
GET https://sub-ru.ai-boost.tech/sub/{sub_id}?fmt=singbox
  → 200 application/json  — валидный sing-box config
  → 404                   — подписка не активна
```
Заголовки ответа: `Profile-Title`, `Profile-Update-Interval`, `Subscription-Userinfo`
(`upload/download/total/expire`), `Support-Url`. Fallback-хост: `miniapp.ai-boost.tech`.

## Статус

🚧 В разработке. Этапы — см. `docs/SPEC-CLIENT-ANDROID.md` §8.

## Лицензия

GPL-3.0 (наследуется от SFA). Исходники открываем к первому публичному релизу.
