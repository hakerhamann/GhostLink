# RoundVideoMessageBinder.kt Summary

`RoundVideoMessageBinder` binds Telegram-like circular video message UI for incoming and outgoing rows.

## Responsibilities

- Show/hide round video row content.
- Bind circular thumbnail/TextureView media, duration, progress ring and play/pause/preparing overlay.
- Disable playback for pending optimistic video messages.
- Call attach/detach playback hooks so recycled rows do not keep playing in the wrong cell.

## Compatibility Notes

- Voice, photo, text, reply and status row binding remains in their existing binders/adapter paths.
