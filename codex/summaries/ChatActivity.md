# ChatActivity.kt Summary

`ChatActivity` is the main chat conversation screen and currently mixes several domains in one Activity.

## Main Responsibilities

- Session initialization and current-user setup.
- Message observation through `ChatRepository`.
- Cached message display, loading indicator timing and display list submission.
- Delegation to `ChatOptimisticMessageStore` for optimistic outgoing and edited message overlays.
- Viewport capture, scroll restoration, unread positioning and read markers.
- Text send, photo drafts and media upload/send completion.
- Delegation to `ChatRecordingController` for voice/video recording state and UI.
- Delegation to `ChatEmojiKeyboardController` for emoji panel and keyboard inset choreography.
- Delegation to `ChatViewportController` for RecyclerView viewport anchor, scroll and visible-unread calculations.
- Swipe-to-reply, reply preview navigation and inline edit.
- Delegation to `ChatInlineEditController` for inline edit state and edit strip UI.
- Delegation to `ChatReplyController` for selected reply target and reply preview UI.
- Message actions: edit, delete for me, delete for everyone, hide incoming.
- Group chat menu and member management dialogs.
- Chat header avatar/profile/photo/video preview navigation.

## High-Value Split Targets

- Group chat menu and member management dialogs.
- Chat header avatar/profile/photo/video preview navigation.

## Compatibility Notes

- Preserve Activity extras used by callers.
- Preserve repository calls and optimistic UI behavior while extracting helpers.
- Keep message identity handling stable because local optimistic IDs and server IDs are reconciled here.
- `setupEmojiPanel`, `hideEmojiPanel`, `showKeyboard` and related helpers currently remain as thin delegates for existing Activity call sites.
- Viewport orchestration flags and read-marker network calls still live in `ChatActivity`; low-level RecyclerView math now lives in `ChatViewportController`.
- Reply target state moved to `ChatReplyController`.
- Inline edit state moved to `ChatInlineEditController`; network save flow still lives in `ChatActivity`.
- Voice/video recording state moved to `ChatRecordingController`; upload/send after recording still lives in `ChatActivity`.
- Optimistic overlay/edit state moved to `ChatOptimisticMessageStore`; Activity still decides when to submit visible messages and force scroll.
