# backend/services/chat_serialization.py Summary

`ChatSerializationService` builds chat preview and message JSON payloads for `backend/server.py`.

## Responsibilities

- Build chat list preview payloads, including peer/group metadata.
- Compute unread counts and outgoing delivered/read state.
- Build message payloads with media URLs, dimensions, status lists and reply preview data.
- Use injected URL normalizers and fallback text constants from `server.py`.

## Compatibility Notes

- JSON field names are unchanged.
- SQL reads are unchanged from the old `server.py` helpers.
- `server.py` keeps thin `build_chat_preview` and `build_message_json` wrappers.
