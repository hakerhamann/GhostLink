# SessionStore.kt Summary

`SessionStore` is the SharedPreferences facade. It is stateful and compatibility-sensitive because persisted keys are used across app versions.

## Main Responsibilities

- Server URL normalization and persistence.
- Auth token and current user profile storage.
- Delegation to `SessionMessageCacheStore` for chat message snapshots.
- Chat scroll state cache.
- Delegation to `SessionUpdateStore` for update availability, seen/downloaded state and update info cache.
- Delegation to `SessionVisibilityStore` for pinned chats/order and hidden chats/messages.
- Delegation to `SessionRememberedAccountsStore` for remembered accounts per server.
- FCM local and synced token storage.

## High-Value Split Targets

- Chat scroll state store.

## Compatibility Notes

- Keep all SharedPreferences key strings stable.
- Prefer facade extraction: public `SessionStore` methods should remain while backing logic moves to focused collaborators.
- Message snapshot public methods remain on `SessionStore`; storage/parsing moved to `SessionMessageCacheStore` with the same key strings passed from the facade.
- Update public methods remain on `SessionStore`; update state moved to `SessionUpdateStore` with the same key strings passed from the facade.
- Remembered account public methods remain on `SessionStore`; JSON parsing/saving moved to `SessionRememberedAccountsStore` with the same key string and server normalization.
- Pinned/hidden public methods remain on `SessionStore`; storage moved to `SessionVisibilityStore` with the same key strings and per-user prefixes.
