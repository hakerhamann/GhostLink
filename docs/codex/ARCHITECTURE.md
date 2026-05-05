# Architecture

GhostLink is an Android client with a Flask/SQLite backend.

## Android

- `ReservApp` owns application startup.
- `AppContainer` wires `SessionStore`, `ApiClient`, repositories and notification helpers.
- `auth`, `chat`, `profile`, `settings`, `updates` contain Activity-level flows.
- `data/model` contains API-facing models used by repositories and UI.
- `data/repository` contains network/cache facades. Keep API URLs and JSON field names backward compatible.
- `network` contains the HTTP client and API exception handling.
- `storage/SessionStore.kt` owns SharedPreferences keys and local snapshots; changing keys is a compatibility risk.
- `ui/adapters` and `ui/widget` contain RecyclerView and view helpers.
- `notifications` and update-related code must be kept even when README wording is stale.

## Backend

- `backend/server.py` is currently monolithic.
- It initializes Flask, SQLite schema, FCM/push helpers, auth helpers, chat serializers, update-feed helpers, routes and upload handlers.
- Future split should move code by domain while preserving the app object, database path, route URLs and JSON contracts.

## Risk Boundaries

- Do not change backend endpoint paths or response fields without a compatibility layer.
- Do not rename `SessionStore` SharedPreferences keys.
- Do not change Activity intent extras or SQLite schema casually.
- Keep login, chat list, open chat and text send behavior stable during structural splits.

## Suggested Split Order

1. Extract narrow helpers from `ChatActivity.kt`, starting with keyboard/emoji or viewport logic.
2. Extract `MessageAdapter` binders for voice, photo, video and reply preview.
3. Split `SessionStore` behind facade methods so public callers keep the same API.
4. Split `ChatRepository` behind the same facade surface.
5. Split `backend/server.py` into app/db/services/routes after route inventory is stable.
