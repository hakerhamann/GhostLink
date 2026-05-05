# ChatReplyController.kt Summary

`ChatReplyController` owns reply-target state and reply preview UI for `ChatActivity`.

## Main Responsibilities

- Store the currently selected `ChatMessage` reply target.
- Bind and clear the reply composer strip.
- Format reply preview text for text, photo, video and voice messages.
- Resolve reply preview image URLs through the Activity-provided photo resolver.
- Jump to and highlight a source message when a reply preview is tapped.
- Clear the reply target when the matching message is deleted locally.

## Compatibility Notes

- `ChatActivity` still exposes thin delegate methods for existing call sites.
- Sending logic still reads the current reply target through `currentReplyTarget()`.
- Inline edit state and optimistic edit behavior remain in `ChatActivity`.
