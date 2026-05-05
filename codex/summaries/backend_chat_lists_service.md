# backend/services/chat_lists.py Summary

`backend/services/chat_lists.py` owns chat-list preview selection.

## Symbols

- `list_chat_previews(db, user_id, build_chat_preview)`: loads chat ids for a member, builds existing preview payloads through the injected serializer, sorts by client timestamp descending and returns `{"items": ...}`.

## Compatibility Notes

- No route URL or JSON contract changes.
- SQL still reads `chats` joined through `chat_members`.
- Serialization remains delegated to the existing `build_chat_preview` wrapper.
