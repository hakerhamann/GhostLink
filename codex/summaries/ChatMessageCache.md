# ChatMessageCache.kt Summary

`ChatMessageCache` owns chat message cache state for `ChatRepository`.

## Responsibilities

- Lazily load persisted snapshots from `SessionStore`.
- Keep in-memory message lists per chat.
- Save fresh network snapshots back to `SessionStore`.
- Replace one cached message after edit confirmation.

## Compatibility Notes

- `ChatRepository` still exposes public cache methods.
- `ChatRepository` still owns polling and prefetch coroutine flow.
- Snapshot persistence behavior remains unchanged.
