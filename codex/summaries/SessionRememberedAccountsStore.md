# SessionRememberedAccountsStore.kt Summary

`SessionRememberedAccountsStore` owns remembered login accounts for the `SessionStore` facade.

## Responsibilities

- Parse remembered account JSON from SharedPreferences.
- Save remembered account JSON after login or removal.
- Filter remembered accounts by normalized server URL.
- Find an account for restore by login/server.
- Remove remembered accounts.

## Compatibility Notes

- `SessionStore` still exposes the public remembered-account methods.
- The SharedPreferences key remains declared in `SessionStore`.
- Server URL normalization is injected from `SessionStore`, preserving existing comparison behavior.
