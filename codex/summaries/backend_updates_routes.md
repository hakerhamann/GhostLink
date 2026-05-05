# backend/routes/updates.py Summary

Update API route blueprint factory used by `backend/server.py`.

## Responsibilities

- Register `/api/updates`.
- Resolve request login from bearer token or `login` query.
- Resolve request IP.
- Apply update feed visibility checks and build response payload.

## Compatibility Notes

- Route URL, method and JSON response fields are unchanged.
- Static APK serving `/updates/<filename>` stays in `backend/server.py`.
- Feed parsing and APK metadata stay in `backend/services/updates.py`.
