# SessionVisibilityStore.kt Summary

`SessionVisibilityStore` owns pinned and hidden chat/message state for the `SessionStore` facade.

## Responsibilities

- Persist pinned chat ids and pinned order.
- Resolve pinned chat order while keeping only currently pinned ids.
- Persist hidden chats with current-user prefixes.
- Persist hidden message ids with current-user and chat prefixes.
- Remove hidden message ids.

## Compatibility Notes

- `SessionStore` still exposes the public pinned/hidden methods.
- SharedPreferences key strings remain declared in `SessionStore`.
- Per-user key prefix format is unchanged.
