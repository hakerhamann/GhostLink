# MessageVideoBinder.kt Summary

`MessageVideoBinder` renders the video-message row controls shared by incoming and outgoing message layouts.

## Responsibilities

- Hide video rows with blank `videoUrl`.
- Bind video duration using the shared media duration formatter.
- Install or clear the video preview click handler.

## Compatibility Notes

- Incoming rows still pass a click handler only for sent messages.
- Outgoing rows keep the previous behavior and always pass the preview callback.
