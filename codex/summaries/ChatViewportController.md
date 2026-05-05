# ChatViewportController.kt Summary

`ChatViewportController` owns RecyclerView viewport operations for the chat screen.

## Main Responsibilities

- Capture the first visible message as a stable viewport anchor.
- Restore scroll position by message id with fallback index and pixel offset.
- Find the first unread incoming message index for initial chat open.
- Compute the latest visible unread incoming timestamp for read markers.
- Scroll to a message, scroll to bottom and update the scroll-to-bottom button.
- Persist current scroll state through `SessionStore`.

## Compatibility Notes

- `ChatActivity` still owns orchestration state such as `keepViewportStableOnUpdates`, `lockedViewportAnchor`, `pendingOpenAtFirstUnread` and read-marker in-flight timestamps.
- `SessionStore.saveChatScrollState` arguments and keys are unchanged.
- No backend or API contract behavior is touched.
