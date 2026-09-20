# Сборка CoralVPN Client

Локально Android **не собирается** (на dev-машине нет Java/Go/Gradle/Android SDK).
Вся сборка и проверка идут в **GitHub Actions**.

## Три workflow

| Workflow | Когда | Что делает |
|---|---|---|
| `.github/workflows/android.yml` | push в main / PR | качает `libbox.aar` из релиза → юнит-тесты → debug APK (артефакт) |
| `.github/workflows/release.yml` | тег `v*` / вручную | **подписанный** release APK → прикладывает к GitHub Release |
| `.github/workflows/gen-keystore.yml` | вручную (однократно) | генерит постоянный keystore подписи (см. «Подпись») |
| `.github/workflows/build-libbox.yml` | вручную | полное ядро (все протоколы) через `build_libbox`, 4 ABI |
| `.github/workflows/build-libbox-slim.yml` | вручную | **slim hy2-only** ядро (только нужные теги), arm64, stripped |

Текущий релиз ядра, который качает CI: **`libbox-v1.14.1`** (стабильная sing-box 1.14.1 —
совпадает с версией, под которую сервер валидирует конфиг). Не alpha.

## Подпись (release)

Ключ подписи — постоянный (менять нельзя, иначе обновления не встанут поверх). Хранится в
GitHub Secrets: `ANDROID_KEYSTORE_B64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_PASSWORD`,
`ANDROID_KEY_ALIAS`. `release.yml` декодит keystore и собирает подписанный APK. Gradle берёт
ключ из env (`KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD`).
**Важно (PKCS12):** store- и key-пароль ДОЛЖНЫ совпадать.

Выпуск: `gh release create vX.Y.Z ...` (создаёт тег) → `release.yml` собирает и прикладывает APK.

## Дистрибуция

Репозиторий приватный → GitHub Release не скачать без логина. APK раздаётся с нашего сайта:
кладём подписанный `coralvpn-latest.apk` на сервер (Content-Type
`application/vnd.android.package-archive`), кнопка в боте ведёт на постоянный URL.

## Как устроено ядро (libbox)

sing-box собирается в нативную `libbox.aar` (Go → gomobile → `.so`). Это дорого
(~4–9 мин) и не меняется от правок приложения, поэтому:

1. Ядро собирается **отдельным workflow** и один раз кладётся в **GitHub Release**
   `libbox-v1.15.0-alpha.6` (asset `libbox.aar`).
2. `android.yml` скачивает этот asset в `app/libs/` перед Gradle — обычная сборка
   приложения занимает ~1.5 мин и не трогает Go.

### Slim-сборка (текущая, hy2-only)

По спеке прод — только Hysteria2, поэтому ядро собирается с минимумом тегов:

```
gomobile bind -target android/arm64 -androidapi 24 \
  -javapkg=io.nekohasekai -libname=box -trimpath -buildvcs=false \
  -ldflags "-s -w -checklinkname=0" \
  -tags "with_quic,with_gvisor,with_utls,badlinkname,tfogo_checklinkname0" \
  -o libbox.aar ./experimental/libbox
```

- `with_quic` — Hysteria2 (QUIC). `with_gvisor` — tun-стек. `with_utls` — TLS-фингерпринт.
- `badlinkname,tfogo_checklinkname0` **+** `-ldflags -checklinkname=0` — обязательны,
  иначе Go 1.26 падает на `invalid reference to os.checkPidfdOnce`.
- `-s -w` — strip символов.

Результат: `.so` ~50 МБ (было 77), aar ~17 МБ. В APK нативные либы **сжимаются**
(`packaging { jniLibs { useLegacyPackaging = true } }`), итоговый debug APK ~25 МБ.

## Обновить ядро

1. Запустить `build-libbox-slim.yml` (Actions → Run workflow), при необходимости
   сменив `singbox_ref`/`tags`.
2. Скачать артефакт `libbox-aar-slim` и залить в релиз:
   `gh release upload libbox-v1.15.0-alpha.6 libbox.aar --clobber`.
3. Перезапустить `android.yml` (или сделать push).

## Версии

Go 1.26.8 · sagernet gomobile/gobind v0.1.13 · NDK r28 · JDK 17 · AGP 8.5.2 ·
Kotlin 1.9.24 · Gradle 8.7 · compileSdk/targetSdk 34 · minSdk 24 · **arm64-only**.

## TODO дистрибуции

- Универсальная сборка под все ABI (armeabi-v7a для старых ТВ-приставок).
- Подписанный release APK (keystore в secrets) + автопубликация в GitHub Releases по тегу.
