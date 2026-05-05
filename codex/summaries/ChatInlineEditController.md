# ChatInlineEditController.kt Summary

`ChatInlineEditController` owns inline-edit state and edit UI for `ChatActivity`.

## Main Responsibilities

- Store the currently edited message and resolved server message id.
- Track existing edited photo URLs removed from an image message.
- Bind and clear the edit strip UI.
- Start, cancel and finish inline edit mode.
- Calculate existing photo count for max-photo enforcement.
- Produce existing photo previews and final edited photo source lists.

## Compatibility Notes

- `ChatActivity` still owns selected photo picking target and network save flow.
- The edit API call path and optimistic edit preview behavior are unchanged.
- `PhotoDraftPreview` is now package-level so both Activity and controller can use it.
