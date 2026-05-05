# backend/services/updates.py Summary

Update-feed service helpers used by `backend/server.py`.

## Responsibilities

- Load and normalize `update_feed.json` history records.
- Normalize target/excluded logins and IP allow/block lists.
- Decide whether an update is visible for a request login/IP.
- Build client-facing update entries with APK URL, file size and SHA-256.

## Compatibility Notes

- Does not own Flask request state or route URLs.
- Keeps APK artifact lookup under the caller-provided update directory.
- Keeps JSON response field names stable for `/api/updates`.
