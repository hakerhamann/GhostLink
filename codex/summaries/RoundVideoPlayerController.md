# RoundVideoPlayerController.kt Summary

`RoundVideoPlayerController` owns inline playback for round video messages.

## Responsibilities

- Keep only one round video active at a time.
- Manage `MediaPlayer`, `Surface`, and active `TextureView` binding.
- Center-crop video into the circular TextureView.
- Toggle play/pause, handle preparing/completion/error, and emit smooth progress ticks.
- Release playback when rows recycle or Activity lifecycle asks the adapter to release playback.

## Compatibility Notes

- Backend fields stay unchanged: `type="video"`, `videoUrl`, `videoDurationSec`.
- Pending optimistic `pending://video/...` messages are displayed but not played.
