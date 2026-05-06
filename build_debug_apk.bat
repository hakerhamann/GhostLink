@echo off
setlocal

cd /d "%~dp0"

echo Building GhostLink debug APK...
call gradlew.bat assembleDebug
if errorlevel 1 (
    echo.
    echo Build failed.
    exit /b %errorlevel%
)

echo.
echo Debug APK ready:
echo %CD%\app\build\outputs\apk\debug\app-debug.apk

endlocal
