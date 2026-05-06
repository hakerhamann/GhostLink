# ChatRecordingController.kt Summary

`ChatRecordingController` owns the chat composer recording domain for `ChatActivity`.

## Responsibilities

- Voice/video mode toggle and composer recording button labels.
- Long-press gesture state for starting, sending and cancelling recordings.
- Runtime permission continuation for microphone and camera+microphone flows.
- `MediaRecorder` setup/finalize for voice message temp files.
- CameraX preview/capture setup, camera switching and video finalize handling.
- Recording ticker, max video duration cutoff and recording status UI.
- Circular CameraX video preview setup with recording progress ring.

## Compatibility Notes

- Upload/send remains in `ChatActivity` through `sendVoice` and `sendVideo` callbacks.
- Temp files are deleted on cancel, failed finalize or missing current user in the Activity callback.
- Existing button visibility/enabled behavior is preserved through `updateComposerActionState`.
