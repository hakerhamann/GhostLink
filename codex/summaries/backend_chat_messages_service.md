# backend/services/chat_messages.py Summary

`backend/services/chat_messages.py` owns chat message DB writes, listing side effects and media cleanup.

## Symbols

- `list_chat_messages(db, chat_id, user_id, delivered_at, build_message_json)`: marks the caller's chat membership as delivered, loads up to 500 messages ascending by creation time/id, delegates serialization and returns `{"items": ...}`.
- `resolve_reply_preview(db, chat_id, reply_to_message_id, ...)`: loads reply target metadata and formats stable sender/text preview fields for send payloads.
- `create_chat_message(...)`: inserts a message, updates chat/member read-delivery timestamps, reloads the joined message row and returns payload plus push metadata.
- `update_chat_message(db, chat_id, message_id, text, media_url, media_urls, media_width, media_height, media_widths, media_heights, updated_at, build_message_json)`: updates editable message fields, updates chat timestamp, reloads the joined message row and returns the message API payload.
- `delete_chat_message(db, chat_id, message_id, message_row, voice_dir, photo_dir, video_dir, now_ms, normalize_media_urls)`: deletes a message, updates chat timestamp, removes local media files and returns `{"ok": true}`.

## Compatibility Notes

- No route URL or JSON contract changes.
- Route keeps membership authorization before calling this service.
- Send route keeps media/reply validation and push dispatch before/after calling this service.
- Edit route keeps sender/type validation and media parsing before calling this service.
- Delete route keeps sender authorization before calling this service.
- Message JSON remains delegated to existing `build_message_json`.
