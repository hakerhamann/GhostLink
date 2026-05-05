# backend/routes/uploads.py Summary

Upload route blueprint factory used by `backend/server.py`.

## Responsibilities

- Bind uploaded media static routes under `/uploads/*`.
- Bind chat media upload routes for voice, photo and video.
- Apply injected `auth_required` to chat media upload handlers.

## Compatibility Notes

- Route URLs and methods are unchanged.
- Handler bodies stay in `backend/server.py` for now.
- Shared media file validation/storage stays in `backend/services/uploads.py`.
