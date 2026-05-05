# MessagePhotoBinder.kt Summary

`MessagePhotoBinder` renders photo message rows for incoming and outgoing message layouts.

## Responsibilities

- Resolve message photo URLs from album and legacy single-photo fields.
- Bind caption visibility while hiding the photo fallback text.
- Render single photos with bounded dimensions.
- Render multi-photo albums using the existing mosaic layout rules.
- Load thumbnails and preserve original photo indexes for preview callbacks.

## Compatibility Notes

- Preview callbacks still receive the original photo index and URL.
- Album layout constants and sizing were moved without changing values.
- Reply preview images use `bindThumbnail`, but reply preview state remains in `MessageAdapter`.
