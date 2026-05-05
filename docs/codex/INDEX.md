# Codex Index

This directory keeps compact navigation notes for GhostLink. Treat source code and build files as the source of truth; these notes are only an entry point for lower-context changes.

## Start Here

- `AGENTS.md`: stable repo rules and risk boundaries.
- `docs/codex/ARCHITECTURE.md`: compact system map.
- `docs/codex/TESTS.md`: baseline and validation commands.
- `codex/manifest.yaml`: machine-readable project map.
- `codex/index.jsonl`: symbol and hot-file index.
- `codex/summaries/`: focused summaries for files that are expensive to load.

## Hot Files

- `app/src/main/java/com/rezerv/app/chat/ChatActivity.kt`: chat screen orchestration, composer, media recording, reply/edit flow, viewport logic.
- `app/src/main/java/com/rezerv/app/ui/adapters/MessageAdapter.kt`: message row rendering and voice playback.
- `app/src/main/java/com/rezerv/app/storage/SessionStore.kt`: SharedPreferences-backed session, cache, update, visibility, pinned and account state.
- `app/src/main/java/com/rezerv/app/data/repository/ChatRepository.kt`: chat API facade, polling subscriptions, cache bridge and JSON parsing.
- `backend/server.py`: Flask backend, SQLite schema, auth, chat, upload, push and update endpoints.

## Current Baseline

- Git HEAD when this index was created: `2b8ec5c`.
- Local baseline tag: `baseline/codex-20260501`.
- Existing release tag on same commit: `v1.9.58`.
- Debug APK baseline installed and launched on `emulator-5554`.
