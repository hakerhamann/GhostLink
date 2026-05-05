# ChatMediaRepository.kt Summary

`ChatMediaRepository` owns media upload and media-message send internals for `ChatRepository`.

## Responsibilities

- Upload voice/photo/video bytes through `ApiClient`.
- Preserve upload URL fallback to returned `fileName`.
- Build voice message payloads.
- Build photo message payloads including album URLs and dimensions.
- Build video message payloads.

## Compatibility Notes

- `ChatRepository` still exposes the public media methods and default fallback texts.
- API paths and JSON field names are unchanged.
- Send responses are still parsed through `ChatJsonParsers`.
