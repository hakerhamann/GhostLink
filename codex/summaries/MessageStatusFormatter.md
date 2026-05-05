# MessageStatusFormatter.kt Summary

`MessageStatusFormatter` formats outgoing message status UI.

## Responsibilities

- Map `SENDING` and `FAILED` send states to status text/color.
- Count delivered/read recipients excluding the current user.
- Format direct chat status glyphs.
- Format group chat status as glyph plus read count over recipients.

## Compatibility Notes

- Existing glyphs, colors and group count behavior were moved without changing values.
- `MessageAdapter` still owns when and where the status is displayed.
