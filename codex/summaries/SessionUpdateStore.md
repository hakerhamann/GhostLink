# SessionUpdateStore.kt Summary

`SessionUpdateStore` owns update-related SharedPreferences state for the `SessionStore` facade.

## Responsibilities

- Persist and clear available update version code/name.
- Persist last seen update version code.
- Persist and clear downloaded update version code/path.
- Persist, read and clear raw updates info cache JSON.

## Compatibility Notes

- `SessionStore` still exposes the public update methods.
- SharedPreferences key strings are still declared in `SessionStore` and passed into this collaborator.
- APK update behavior and cache contents are unchanged.
