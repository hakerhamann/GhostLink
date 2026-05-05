# backend/services/chat_creation.py Summary

`backend/services/chat_creation.py` owns chat creation/reuse DB writes.

## Symbols

- `ensure_direct_chat(db, current_user_id, target_user_id, now_ms)`: returns existing direct chat id for a user pair or creates `chats`, `direct_chats` and two `chat_members` rows.
- `create_group_chat_record(db, title, description, current_user_id, member_ids, now_ms)`: creates a group chat row and member rows for a de-duplicated member id set.
- `update_chat_avatar_record(db, chat_id, file_name, updated_at)`: updates a group chat avatar filename and timestamp.

## Compatibility Notes

- No route URL or JSON contract changes.
- Routes keep request validation, target/member lookup and response shaping.
- SQLite schema and inserted columns are unchanged.
