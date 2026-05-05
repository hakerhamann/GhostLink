# MessageReplyPreviewBinder.kt Summary

`MessageReplyPreviewBinder` renders reply previews inside incoming and outgoing message rows.

## Responsibilities

- Show or hide the reply preview container.
- Bind reply sender and text.
- Bind optional reply thumbnail via `MessagePhotoBinder`.
- Hide fallback photo text when the reply preview already has an image.
- Install or clear reply-preview tap callbacks.

## Compatibility Notes

- `MessageAdapter` still chooses the fallback sender label: incoming uses the sender name, outgoing uses `Reply`.
- Reply preview navigation callback is unchanged.
