param(
    [string]$AvdName = "GhostLink_API_35",
    [int]$BootTimeoutSec = 360,
    [switch]$ColdBoot
)

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
$emulator = Join-Path $sdkRoot "emulator\emulator.exe"

if (-not (Test-Path $adb)) {
    throw "adb.exe not found: $adb"
}
if (-not (Test-Path $emulator)) {
    throw "emulator.exe not found: $emulator"
}

& $adb start-server | Out-Null

$runningEmulators = (& $adb devices) | Select-String "^emulator-\d+\s+device$"
if (-not $runningEmulators) {
    $args = @("-avd", $AvdName, "-netdelay", "none", "-netspeed", "full", "-no-snapshot-save")
    if ($ColdBoot) {
        $args += "-no-snapshot-load"
    }
    Start-Process -FilePath $emulator -ArgumentList $args | Out-Null
}

& $adb wait-for-device | Out-Null

$deadline = (Get-Date).AddSeconds($BootTimeoutSec)
do {
    $bootCompleted = (& $adb shell getprop sys.boot_completed 2>$null).Trim()
    if ($bootCompleted -eq "1") {
        break
    }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)

if ($bootCompleted -ne "1") {
    throw "Emulator did not finish booting within $BootTimeoutSec seconds."
}

Write-Output "Emulator is ready."
