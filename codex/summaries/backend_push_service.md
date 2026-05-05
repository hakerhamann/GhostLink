# backend/services/push.py Summary

Push notification service used by `backend/server.py`.

## Responsibilities

- Initialize Firebase Admin/FCM from environment JSON or credential file.
- Trim stored push tokens per user.
- Build data-only message notification payloads.
- Send FCM multicast notifications and remove stale tokens.
- Dispatch message push work asynchronously with a fresh SQLite connection.

## Compatibility Notes

- Does not own Flask routes or request state.
- Keeps `/api/push/register` and `/api/push/unregister` response contracts in `server.py`.
- Uses caller-provided fallback texts, DB path, logger and chat-title resolver to avoid importing the Flask app module.
