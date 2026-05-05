# ChatJsonParsers.kt Summary

`ChatJsonParsers` owns JSON parsing for the Android chat repository.

## Responsibilities

- Parse chat previews from `/api/chats`.
- Parse group info and member lists.
- Parse chat messages from fetch and send responses.
- Preserve media field fallbacks for single photo and album metadata.
- Preserve reply preview parsing from nested `replyTo`.

## Compatibility Notes

- Parsed JSON field names and fallback behavior were moved without changing values.
- `ChatRepository` still owns all network endpoints and public method signatures.
