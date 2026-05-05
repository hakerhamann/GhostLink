# ChatOptimisticMessageStore.kt Summary

`ChatOptimisticMessageStore` owns local optimistic chat-message state used by `ChatActivity`.

## Main Responsibilities

- Hold local outgoing overlay messages.
- Map local message ids to confirmed server message ids.
- Merge local overlays with server messages for display.
- Sync and prune overlays when server messages arrive.
- Track pending optimistic edits and clear them when server state matches.
- Build optimistic text, voice, image, video and edited messages.
- Resolve server ids for local optimistic ids.

## Compatibility Notes

- `ChatActivity` still owns UI submission and scroll decisions after store mutations.
- Reply preview data is supplied by Activity callbacks through `ChatReplyController`.
- Backend API calls and parsed message fields are unchanged.
