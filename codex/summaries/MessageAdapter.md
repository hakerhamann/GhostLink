# MessageAdapter.kt Summary

`MessageAdapter` renders chat messages, owns voice playback state for visible rows, and delegates inline round-video playback to `RoundVideoPlayerController`.

## Main Responsibilities

- Diff/list adapter behavior for `ChatMessage`.
- Row binding for own and incoming message types.
- Text message presentation.
- Delegation to `MessageVoiceBinder` for voice row presentation.
- Delegation to `RoundVideoMessageBinder` for circular video row presentation.
- Delegation to `MessagePhotoBinder` for photo row and album presentation.
- Delegation to `MessageReplyPreviewBinder` for reply preview presentation.
- Message highlight/entrance animation state.
- Delegation to `MessageStatusFormatter` for outgoing status text/color.
- Voice playback lifecycle through `MediaPlayer`.
- Round video playback lifecycle through `RoundVideoPlayerController`.

## High-Value Split Targets

- Voice playback controller.

## Compatibility Notes

- Keep stable adapter callbacks for message actions, replies, photos, videos, avatars and profile taps.
- Release voice playback from lifecycle owners before adapter disposal.
- Voice row rendering moved to `MessageVoiceBinder`; `MediaPlayer` ownership remains in the adapter.
- Video row rendering moved to `RoundVideoMessageBinder`; video taps now toggle inline circular playback instead of opening preview.
- Photo row rendering and album layout moved to `MessagePhotoBinder`; image preview callbacks remain unchanged.
- Reply preview rendering moved to `MessageReplyPreviewBinder`; incoming/outgoing fallback sender labels remain selected by the adapter.
- Outgoing status formatting moved to `MessageStatusFormatter`; `MessageAdapter` only applies text/color to the row.
