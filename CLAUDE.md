# CoralVPN Client — заметки для Claude

Android-клиент CoralVPN. Форк SFA (sing-box-for-android) с урезанным UI до «одной
кнопки». Ядро — sing-box через `libbox`. Полное описание: `docs/PRODUCT-CLIENT-ANDROID.md`
и `docs/SPEC-CLIENT-ANDROID.md` — **это источник правды, читать перед работой.**

## Ключевые факты окружения

- **Локально Android НЕ собирается**: на машине нет Java/Go/Gradle/Android SDK.
  Единственный способ собрать/проверить APK — **GitHub Actions**. Любое изменение
  проверяется зелёной сборкой CI, не локально.
- Репозиторий: `solesurvivor1981-max/coralvpn-android` (приватный на старте,
  публичный к первому релизу — GPL-3.0 обязывает).
- Ведётся из основной Telegram-сессии (`/home/alex/Documents/Claude`), отдельного
  бота у проекта пока нет.
- **Ядро: sing-box 1.14.1** (release `libbox-v1.14.1`, arm64). Именно эту версию
  валидирует сервер — не переключать на alpha без причины.
- Релизы подписываются `release.yml` (ключ в GitHub Secrets); сборка/подпись/дистрибуция
  описаны в `docs/BUILD.md`. Дистрибуция APK — с сайта (репо приватный).
- `DefaultNetworkMonitor.getInterfaces()` ОБЯЗАН срезать IPv6-зону (`%wlan0`) из адресов —
  иначе `netip.ParsePrefix` роняет ядро нативно при старте.

## Архитектурные инварианты (не нарушать)

- **Клиент НЕ парсит подписку и НЕ мутирует конфиг.** Скачал JSON с `?fmt=singbox`
  → отдал в libbox как есть. Вся «умная» логика (выбор серверов, обход блокировок)
  живёт на сервере.
- Один протокол — Hysteria2. Не тащим поддержку остальных протоколов из SFA.
- Смена/добавление серверов — правкой на сервере, без релиза приложения.
- Секреты (sub_url/UUID) — в EncryptedSharedPreferences; не логировать в systemd/logcat.

## Контракт с сервером

`GET https://sub-ru.ai-boost.tech/sub/{sub_id}?fmt=singbox` → 200 sing-box JSON / 404 неактивна.
Fallback-хост: `miniapp.ai-boost.tech`. Заголовки: `Profile-Title`,
`Profile-Update-Interval` (часы), `Subscription-Userinfo` (upload/download/total/expire),
`Support-Url`. Deeplink: `coralvpn://add?url=<urlencoded>`.

## Рабочий процесс

- Крупные изменения — с одобрения Alex; мелкие зелёные — можно самому (см. общую память).
- CI пишем под этот проект (см. память `ci-waits-for-first-project`).
- Коммиты по-английски, тело по делу.
