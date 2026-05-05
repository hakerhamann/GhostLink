# backend/routes/auth.py Summary

Auth route blueprint factory used by `backend/server.py`.

## Responsibilities

- Bind `/api/auth/register`.
- Bind `/api/auth/login`.
- Bind `/api/auth/me` with injected `auth_required`.

## Compatibility Notes

- Route URLs and methods are unchanged.
- Handler bodies stay in `backend/server.py` for now.
