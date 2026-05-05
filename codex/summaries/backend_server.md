# backend/server.py Summary

`backend/server.py` is a monolithic Flask backend file.

## Main Responsibilities

- Flask app setup and teardown.
- SQLite connection and delegation to `backend/db/schema.py` for schema initialization.
- Blueprint registration for push routes in `backend/routes/push.py`.
- Blueprint registration for auth routes in `backend/routes/auth.py`.
- Blueprint registration for users/profile routes in `backend/routes/profile.py`.
- Blueprint registration for update API route in `backend/routes/updates.py`.
- Blueprint registration for uploaded media static routes and chat media upload routes in `backend/routes/uploads.py`.
- Blueprint registration for chat, group, message and read-marker routes in `backend/routes/chat.py`.
- Chat preview/message JSON serialization delegates to `backend/services/chat_serialization.py`.
- Direct/group chat create/reuse DB writes delegate to `backend/services/chat_creation.py`.
- Chat membership, group info, chat-title, member-add, member-remove and leave DB helpers delegate to `backend/services/chat_groups.py`.
- Chat list preview query/sorting delegates to `backend/services/chat_lists.py`.
- Chat message reply preview, create/list/edit/delete query/update and local media cleanup delegate to `backend/services/chat_messages.py`.
- Chat read-marker state update delegates to `backend/services/chat_read.py`.
- Media URL/list parsing, image dimensions and reply preview formatting delegate to `backend/services/media_formatting.py`.
- User list/search JSON, UID parsing and profile DB writes delegate to `backend/services/users.py`.
- Auth handlers and protected-route decorator; auth URL binding moved to `backend/routes/auth.py`, password/session primitives delegate to `backend/services/auth.py`.
- User/group serialization plus thin chat/message serialization wrappers.
- Static APK update serving; uploaded media static URL binding moved to `backend/routes/uploads.py`.
- Voice/photo/video upload handlers delegate shared file validation and storage to `backend/services/uploads.py`; URL binding moved to `backend/routes/uploads.py`.
- Profile avatar handler delegates file validation/storage to `backend/services/uploads.py`.
- Profile/group avatar handlers delegate local old-file cleanup to `backend/services/uploads.py`; group avatar file storage delegates there too.
- `/api/updates` handled by `backend/routes/updates.py` with feed logic in `backend/services/updates.py`.
- Auth, profile, users, push and upload handlers; chat/message/read URL binding moved to `backend/routes/chat.py`.

## Route Areas

- Health/static: `/health`, `/updates/*`; uploaded media static routes in `backend/routes/uploads.py`.
- Updates: `/api/updates` in `backend/routes/updates.py`.
- Auth: `/api/auth/register`, `/api/auth/login`, `/api/auth/me` in `backend/routes/auth.py`.
- Push: `/api/push/register`, `/api/push/unregister` in `backend/routes/push.py`.
- Users/profile: `/api/users`, `/api/profile`, `/api/profile/avatar` in `backend/routes/profile.py`.
- Chats/groups: `/api/chats`, direct/group creation, avatar, group info, members, leave in `backend/routes/chat.py`.
- Messages/media: list/send/edit/delete messages and read markers in `backend/routes/chat.py`; voice/photo/video upload URL binding in `backend/routes/uploads.py`.

## High-Value Split Targets

- `services/chat.py`.
- `services/chat.py`.

## Compatibility Notes

- Preserve route URLs, HTTP methods and JSON contracts.
- Keep SQLite schema compatible and additive.
- Keep update artifact behavior stable unless the task explicitly targets updates.
- Schema creation, compatibility columns and upload/update directory creation moved to `backend/db/schema.py`; `server.py` keeps a thin `init_db()` wrapper.
- Password hashing, login normalization and session row creation moved to `backend/services/auth.py`; `server.py` keeps auth routes and `auth_required`.
- Auth URL binding moved to `backend/routes/auth.py`; handler bodies stay in `server.py` for this low-risk step.
- Users/profile URL binding moved to `backend/routes/profile.py`; handler bodies stay in `server.py`.
- Push token routes moved to `backend/routes/push.py`; FCM initialization, push token trimming and message notification fanout live in `backend/services/push.py`.
- Shared multipart media upload validation, suffix selection and file writes moved to `backend/services/uploads.py`; routes keep membership checks and JSON field names.
- Profile avatar file validation/write moved to `backend/services/uploads.py`; route keeps DB update, old-file cleanup and JSON response.
- Local old-avatar cleanup moved to `backend/services/uploads.py`; routes keep DB update and JSON response.
- Uploaded media static routes and chat media upload URL binding moved to `backend/routes/uploads.py`; handler bodies stay in `server.py`.
- Update API route moved to `backend/routes/updates.py`; update feed loading, targeting, APK URL/file metadata and SHA-256 derivation live in `backend/services/updates.py`.
- Chat, group, message and read-marker URL binding moved to `backend/routes/chat.py`; handler bodies stay in `server.py`.
- Chat preview and message JSON builder bodies moved to `backend/services/chat_serialization.py`; wrapper names stay in `server.py`.
- Direct/group chat create/reuse DB writes moved to `backend/services/chat_creation.py`; routes keep request validation and response shape.
- Group chat avatar DB update moved to `backend/services/chat_creation.py`; route keeps validation and preview response.
- Chat group/member/leave helper bodies moved to `backend/services/chat_groups.py`; routes keep validation and responses.
- Chat list preview query/sorting moved to `backend/services/chat_lists.py`; route keeps auth context and response shape.
- Chat message reply preview, create/list/edit/delete DB updates and local media cleanup moved to `backend/services/chat_messages.py`; routes keep membership/sender checks and push dispatch.
- Chat read-marker update body moved to `backend/services/chat_read.py`; route keeps auth/membership check.
- Media URL/list parser and image dimension bodies moved to `backend/services/media_formatting.py`; wrapper names stay in `server.py`.
- User list/helper/profile DB write bodies moved to `backend/services/users.py`; wrapper names and route validation stay in `server.py`.
