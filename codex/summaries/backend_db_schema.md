# backend/db/schema.py Summary

`backend/db/schema.py` owns SQLite schema initialization for the Flask backend.

## Responsibilities

- Create core tables for users, sessions, chats, members, messages, direct chats and push tokens.
- Create push token index.
- Apply additive compatibility columns with `ensure_column`.
- Create upload and update artifact directories passed by `server.py`.

## Compatibility Notes

- `backend/server.py` still exposes and calls `init_db()`.
- Schema SQL and additive column definitions were moved without changing values.
- Route URLs and JSON contracts are unaffected.
