# GhostLink: Android Emulator Testing (Ready Setup)

This PC is already prepared for APK testing.

- Android SDK: `C:\Users\haker\AppData\Local\Android\Sdk`
- Emulator AVD name: `GhostLink_API_35`
- Current app package: `com.rezerv.app`

## 1) Fast start (boot emulator + install latest APK + launch app)

Run in PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Projekt\APK\tools\android-emulator\run-latest.ps1"
```

## 2) Install a specific APK version

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Projekt\APK\tools\android-emulator\install-apk.ps1" -ApkPath "C:\Projekt\APK\GhostLink-release-v1.7.6.apk"
```

## 3) Boot emulator only

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Projekt\APK\tools\android-emulator\start-emulator.ps1"
```

## 4) Stop emulator

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Projekt\APK\tools\android-emulator\stop-emulator.ps1"
```

## 5) Workflow for every new GhostLink release

1. Put the new APK file into `C:\Projekt\APK\`.
2. Keep release naming format `GhostLink-release-vX.Y.Z.apk`.
3. Run:

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Projekt\APK\tools\android-emulator\run-latest.ps1"
```

The script will:
- start emulator if needed,
- find the newest `GhostLink-release-*.apk`,
- install over existing version (`-r`),
- launch the app automatically.

## 6) Optional: install latest APK from another folder

```powershell
powershell -ExecutionPolicy Bypass -File "C:\Projekt\APK\tools\android-emulator\install-latest-apk.ps1" -SearchDir "D:\Builds"
```
