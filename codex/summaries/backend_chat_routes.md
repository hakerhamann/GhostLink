# backend/routes/chat.py Summary

Chat route blueprint factory used by `backend/server.py`.

## Responsibilities

- Bind chat list/direct/group routes.
- Bind group avatar/info/member/leave routes.
- Bind message list/send/edit/delete routes.
- Bind read-marker route.
- Apply injected `auth_required` to all handlers.

## Compatibility Notes

- Route URLs and methods are unchanged.
- Handler bodies stay in `backend/server.py` for now.
