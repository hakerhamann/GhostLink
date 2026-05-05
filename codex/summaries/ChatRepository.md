# ChatRepository.kt Summary

`ChatRepository` is the Android chat data facade over `ApiClient` and `SessionStore`.

## Main Responsibilities

- Polling observers for chat list and chat messages.
- Direct/group chat creation and group membership operations.
- Message send, edit, delete and read marker calls.
- Delegation to `ChatMediaRepository` for voice/photo/video upload and send helpers.
- Delegation to `ChatMessageCache` for in-memory message cache and `SessionStore` snapshot bridge.
- Delegation to `ChatJsonParsers` for chat previews, group info and message JSON parsing.

## High-Value Split Targets

- Network endpoint methods.
- Network endpoint methods.

## Compatibility Notes

- Keep method signatures stable for Activity callers while extracting internals.
- Preserve parsed field names and fallback behavior.
- JSON parsing moved to `ChatJsonParsers`; repository still owns network calls, cache updates and public API surface.
- Message cache internals moved to `ChatMessageCache`; repository still owns polling/prefetch coroutine orchestration and public cache methods.
- Media upload/send internals moved to `ChatMediaRepository`; public methods and default fallback text constants remain in `ChatRepository`.
