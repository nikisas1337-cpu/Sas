# Інструкції для Claude

Спілкуйся з користувачем українською.

## Android-програми та доставка APK у Telegram

- Кожна Android-програма — окремий Gradle-модуль у корені (`app`, `boost`, …), підключений у `settings.gradle.kts`.
- Android SDK у хмарній сесії недоступний, тому APK збирає GitHub Actions (`.github/workflows/build.yml`) на кожен пуш у будь-яку гілку.
- Після збірки workflow сам надсилає APK у Telegram-бота користувача (секрети `TELEGRAM_BOT_TOKEN` і `TELEGRAM_CHAT_ID` уже налаштовані). Надсилаються лише APK модулів, код яких змінився в пуші.

### Коли створюєш нову програму

1. Створи новий модуль і додай `include(":назва")` у `settings.gradle.kts`. Задай `app_name` у `назва/src/main/res/values/strings.xml`: це назва в підписі в Telegram.
2. Закоміть і запуш. Workflow сам знайде APK нового модуля й надішле його в Telegram, правити `build.yml` не треба.
3. Дочекайся завершення workflow і перевір у лозі кроку Telegram рядок «Надіслано …». Якщо збірка впала, виправ і запуш знову.

### Якщо користувач просить «відправ APK у бота»

Запусти workflow `build.yml` вручну (workflow_dispatch) на потрібній гілці: при ручному запуску надсилаються APK усіх програм.
