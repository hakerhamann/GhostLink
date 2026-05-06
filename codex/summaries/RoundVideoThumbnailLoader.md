# RoundVideoThumbnailLoader.kt Summary

`RoundVideoThumbnailLoader` loads first-frame thumbnails for round video messages.

## Responsibilities

- Use `MediaMetadataRetriever` off the main thread.
- Cache small thumbnail bitmaps by video URL.
- Bind or clear thumbnail `ImageView` instances safely during RecyclerView reuse.
