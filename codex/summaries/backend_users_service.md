# backend/services/users.py Summary

Small user formatting helpers used by `backend/server.py`.

## Responsibilities

- Convert SQLite user rows to client JSON.
- List searchable user items excluding current user.
- Parse `u123` style user ids.
- Update display name and return refreshed user row.
- Update avatar filename and return refreshed user row plus old avatar.

## Compatibility Notes

- User JSON field names are unchanged.
- `server.py` keeps validation, avatar cleanup, JSON response and wrapper helpers.
