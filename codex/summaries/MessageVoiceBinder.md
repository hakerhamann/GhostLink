# MessageVoiceBinder.kt Summary

`MessageVoiceBinder` renders the voice-message row controls shared by incoming and outgoing message layouts.

## Responsibilities

- Show preparing, playing and idle play glyphs.
- Bind remaining duration and progress bar state.
- Disable/clear click handlers for voice rows that cannot be played yet.
- Provide shared `VoicePlaybackState` and `formatMessageDuration` for media row display.

## Compatibility Notes

- `MediaPlayer` lifecycle remains in `MessageAdapter`.
- Incoming rows still block playback until the message is sent and has a non-blank voice URL.
- Outgoing rows keep the previous always-clickable behavior by passing `canPlay = true`.
