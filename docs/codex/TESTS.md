# Tests And Validation

Use these commands after behavior-affecting changes.

## Android

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Current baseline notes:

- `testDebugUnitTest` succeeds, but there are no unit test sources for the debug variant.
- `assembleDebug` succeeds.
- Kotlin currently emits deprecation warnings for `overridePendingTransition` and notification category usage.
- Native libraries `libimage_processing_util_jni.so` and `libsurface_util_jni.so` are packaged unstripped in debug builds.

## Emulator Smoke

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell monkey -p com.rezerv.app -c android.intent.category.LAUNCHER 1
```

Baseline smoke used `emulator-5554` and launched `com.rezerv.app`.

## Backend

Prefer excluding local virtualenv and generated caches:

```powershell
python -m compileall -q -x "backend[\\/]\.venv|__pycache__" backend
```

The broad command `python -m compileall backend` succeeds, but it walks `backend/.venv` and creates ignored cache files, so avoid it unless specifically needed.
