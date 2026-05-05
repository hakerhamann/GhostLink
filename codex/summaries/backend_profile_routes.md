# backend/routes/profile.py Summary

Users/profile route blueprint factory used by `backend/server.py`.

## Responsibilities

- Bind `/api/users`.
- Bind `/api/profile`.
- Bind `/api/profile/avatar`.
- Apply injected `auth_required` to all handlers.

## Compatibility Notes

- Route URLs and methods are unchanged.
- Handler bodies stay in `backend/server.py` for now.
