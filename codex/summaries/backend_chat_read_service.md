# backend/services/chat_read.py Summary

Read-marker helper used by `backend/server.py`.

## Responsibilities

- Resolve requested read timestamp with fallback to injected clock.
- Clamp negative read timestamps.
- Preserve monotonic `last_read_at` and `last_delivered_at` values.
- Update `chat_members` read/delivered state and return API payload fields.

## Compatibility Notes

- JSON field names are unchanged.
- Route auth/membership checks stay in `server.py`.
