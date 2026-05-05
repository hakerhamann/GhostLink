# ChatEmojiKeyboardController.kt Summary

`ChatEmojiKeyboardController` owns the chat screen keyboard and emoji panel state that used to live directly in `ChatActivity`.

## Main Responsibilities

- Initialize the emoji RecyclerView, tabs and emoji adapter.
- Install root window inset handling for top bar padding, IME height and input bar bottom padding.
- Coordinate keyboard-to-emoji and emoji-to-keyboard transitions.
- Animate input bar inset and emoji panel height.
- Persist and restore last known keyboard height in `chat_ui_state`.
- Hide/show the soft keyboard and reset keyboard state for swipe-back gestures.

## Compatibility Notes

- Public behavior is still reached through thin `ChatActivity` delegate methods.
- SharedPreferences key `pref_last_keyboard_height_px` and prefs file `chat_ui_state` were preserved.
- Route/API/backend behavior is untouched.
