# backend/services/auth.py Summary

Auth service primitives used by `backend/server.py`.

## Responsibilities

- Normalize login values.
- Hash and verify passwords using the existing salt/digest format.
- Create session tokens and insert rows into `sessions`.

## Compatibility Notes

- Does not own Flask routes or request state.
- Keeps existing `sessions(token, user_id, created_at)` insert contract.
- Keeps the existing password hash wire/storage format stable.
