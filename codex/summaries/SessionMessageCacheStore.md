# SessionMessageCacheStore.kt Summary

`SessionMessageCacheStore` owns persisted chat message snapshots for the `SessionStore` facade.

## Responsibilities

- Save per-user/per-chat message snapshot JSON payloads.
- Load cached snapshots into `ChatMessage` models.
- Maintain and trim the chat-message cache index.
- Clear snapshots for the current user during session logout.
- Preserve legacy single-photo fallback parsing and album metadata parsing.

## Compatibility Notes

- `SessionStore` still exposes the public snapshot methods.
- SharedPreferences key strings are still declared in `SessionStore` and passed into this collaborator.
- Cached messages are restored with `MessageSendState.SENT`, matching previous behavior.
