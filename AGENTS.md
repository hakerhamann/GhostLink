# AGENTS.md

## Цель

Ты работаешь в репозитории GhostLink.

Главная цель:
- уменьшить объём контекста, который нужен для типовых задач;
- ускорить навигацию и правки для Codex;
- сохранить рабочий Android-клиент и совместимость с текущим backend.

Если между "сделать красиво" и "не сломать клиент" есть конфликт, всегда выбирай второе.

## Источники истины

Используй такой приоритет:
1. Код и сборка:
   - `app/src/main/java/**`
   - `backend/**`
   - `app/src/main/AndroidManifest.xml`
   - `app/build.gradle.kts`
   - `gradle.properties`
2. Этот файл `AGENTS.md`
3. `docs/codex/*.md`
4. README

Важно: README может отставать от кода. Не удаляй push / updates / Firebase-related код только потому, что README это формулирует иначе.

## Что нельзя ломать

Нельзя без явного отдельного задания менять:
- URL и JSON-контракты backend API;
- имена полей, которые клиент парсит из backend;
- ключи SharedPreferences в `SessionStore`;
- Intent extras, которые используются между Activity;
- схему SQLite и имена колонок;
- механизм обновлений APK;
- поведение входа, списка чатов, открытия чата и отправки текста.

Если нужно изменить контракт, сначала создай совместимую промежуточную прослойку.

## Как работать по умолчанию

Всегда работай в таком порядке:
1. Найди минимально достаточный контекст.
2. Не читай большие файлы целиком, если можно читать по подсистемам.
3. Сначала делай структурные изменения без смены поведения.
4. После каждого атомарного шага прогони проверки.
5. Держи изменения маленькими и коммить их отдельно.

## Контекстная дисциплина

По умолчанию НЕ загружай целиком:
- `backend/update_feed.json`
- `backend/updates/**`
- `**/*.apk`
- `app/build/**`
- `backend/.venv/**`

Загружай их только если задача прямо относится к updates / release artifacts.

Если задача касается одной подсистемы, сначала ищи:
- конкретный symbol;
- конкретный chunk;
- summary;
- и только потом весь файл.

## Горячие зоны

Сначала предполагай, что самые рискованные и самые дорогие по контексту файлы:
- `app/src/main/java/com/rezerv/app/chat/ChatActivity.kt`
- `app/src/main/java/com/rezerv/app/ui/adapters/MessageAdapter.kt`
- `app/src/main/java/com/rezerv/app/storage/SessionStore.kt`
- `app/src/main/java/com/rezerv/app/data/repository/ChatRepository.kt`
- `backend/server.py`

Если задача касается одной из этих зон, сначала проверь:
- есть ли summary в `codex/summaries/`;
- есть ли symbol record в `codex/index.jsonl`;
- можно ли выделить минимальный поддомен.

## Правила разбиения файлов

Новый код разрезай так:

### Kotlin
- 1 основной public class / controller / binder на файл.
- Extension functions выноси только если они реально переиспользуются.
- Не держи в одном файле одновременно UI orchestration, persistence, parsing и media logic.
- Activity должна координировать, а не владеть всей логикой.

### Python
- Route handlers отдельно от db/helpers/services.
- Один файл routes = один домен (`auth`, `chat`, `uploads`, `updates`).
- Никаких новых монолитов.

### Порог для split
Если файл:
- больше примерно 300 LOC, или
- содержит 3+ разных ответственности, или
- по задаче требует постоянной полной загрузки,

то сначала предложи или выполни безопасный split без смены поведения.

## Целевые извлечения

При работе с `ChatActivity.kt` старайся выносить:
- keyboard / emoji logic;
- viewport / scroll-anchor logic;
- reply / inline-edit logic;
- optimistic overlay / merge logic;
- voice recording;
- video recording.

При работе с `MessageAdapter.kt` старайся выносить:
- voice binder;
- photo binder;
- video binder;
- reply preview binder;
- album layout calculator;
- status formatter.

При работе с `SessionStore.kt` старайся выносить:
- message cache store;
- update state store;
- remembered accounts store;
- visibility / pinned state store.

При работе с `backend/server.py` старайся выносить:
- db/schema;
- auth routes;
- chat routes;
- upload routes;
- push logic;
- update feed / apk logic.

## Индекс и документы, которые должны существовать

Если их ещё нет, создай и поддерживай:
- `docs/codex/INDEX.md`
- `docs/codex/ARCHITECTURE.md`
- `docs/codex/TESTS.md`
- `codex/manifest.yaml`
- `codex/index.jsonl`
- `codex/summaries/`

## Что должно быть в index.jsonl

Для каждого hot file и для каждого важного symbol храни:
- `path`
- `symbol`
- `kind`
- `area`
- `summary`
- `imports`
- `priority`
- `stateful`
- `load_by_default`

## Prompt / token rules

- Этот файл должен оставаться коротким и стабильным.
- Глубокие детали держи в `docs/codex/*.md`.
- Повторяемые инструкции не переписывай каждый раз по-разному.
- Стабильный префикс промпта очень важен.

## Проверки после изменений

Минимальные проверки:
```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
python -m compileall backend

Данные VPS
130.49.128.205
root
94a19JgzG7lnjZWB3Y

Заливать билд на сервер только если я об этом попросил! В остальных случаях только собирать и заливать на эмулятор.
В описании обновления никогда не упоминать телеграмм!