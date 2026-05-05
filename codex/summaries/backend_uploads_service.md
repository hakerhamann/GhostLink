# backend/services/uploads.py Summary

Shared upload storage helpers used by chat media and profile avatar routes in `backend/server.py`.

## Responsibilities

- Validate required multipart file presence.
- Reject empty uploads and files above caller-provided byte limits.
- Preserve allowed file suffixes with safe defaults per media type.
- Generate chat media filenames using caller-provided timestamp/token values.
- Generate profile avatar filenames as caller-token `.jpg` values.
- Generate group chat avatar filenames as `chat_{chat_id}_{token}.jpg` values.
- Create the target directory and write uploaded bytes.
- Remove local uploaded files by name while ignoring remote URLs and missing files.

## Compatibility Notes

- Does not own Flask routes, membership checks or JSON responses.
- Existing voice/photo/video route URLs and response fields stay in `server.py`.
- Profile avatar DB update and JSON response stay route-local in `server.py`.
- Group avatar DB update delegates to `backend/services/chat_creation.py`; JSON response stays route-local.
