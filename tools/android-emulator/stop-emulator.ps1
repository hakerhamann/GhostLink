$ErrorActionPreference = "Stop"

function Get-SdkRoot {
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
        return $env:ANDROID_SDK_ROOT
    }

    $default = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $default) {
        return $default
    }

    throw "Android SDK not found. Set ANDROID_SDK_ROOT or install SDK in %LOCALAPPDATA%\Android\Sdk."
}

$sdkRoot = Get-SdkRoot
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb.exe not found: $adb"
}

$devices = (& $adb devices) | Select-String "^emulator-\d+\s+device$"
if (-not $devices) {
    Write-Output "No running emulators found."
    exit 0
}

foreach ($line in $devices) {
    $serial = ($line.Line -split "\s+")[0]
    & $adb -s $serial emu kill | Out-Null
    Write-Output "Stopped: $serial"
}
