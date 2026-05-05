# backend/services/chat_groups.py Summary

`ChatGroupService` holds chat membership, group info and chat-title helpers used by `backend/server.py`.

## Responsibilities

- Read chat member ids.
- Check chat membership.
- Build group info payloads with member/user JSON.
- Resolve display title for direct and group chats.
- Add a group member row and update chat timestamp.
- Leave a group, deleting last-member chats and reassigning creator when needed.
- Remove a group member and update chat timestamp.

## Compatibility Notes

- SQL reads and JSON field names are unchanged.
- Route validation and JSON responses stay in `server.py`.
