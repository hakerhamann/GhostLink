# backend/routes/push.py Summary

Push route blueprint factory used by `backend/server.py`.

## Responsibilities

- Register `/api/push/register`.
- Register `/api/push/unregister`.
- Preserve push token validation, upsert/delete SQL and JSON response fields.
- Delegate auth, DB access, timestamps and token trimming through injected dependencies.

## Compatibility Notes

- Route URLs and methods are unchanged.
- Does not import `server.py`; avoids circular app imports.
- FCM fanout remains in `backend/services/push.py`.
