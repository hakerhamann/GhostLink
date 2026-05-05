# backend/services/media_formatting.py Summary

Shared formatting/parsing helpers used by `backend/server.py`.

## Responsibilities

- Build uploaded-media URLs from an injected base URL and upload segment.
- Normalize JSON/string/list media URL arrays.
- Normalize JSON/string/list integer arrays.
- Build bounded image width/height JSON payloads for albums and single images.
- Parse ints with defaults.
- Normalize reply preview text length and newlines.

## Compatibility Notes

- `server.py` keeps wrapper names used by existing handlers and serializers.
- URL paths and JSON field values are unchanged.
