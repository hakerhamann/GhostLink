param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [string]$PackageName,
    [switch]$NoLaunch
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

function Get-AaptPath {
    param([string]$SdkRoot)
    $buildTools = Join-Path $SdkRoot "build-tools"
    if (-not (Test-Path $buildTools)) {
        return $null
    }
    return Get-ChildItem -Path $buildTools -Recurse -Filter "aapt.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

function Get-BadgingLine {
    param(
        [string]$AaptPath,
        [string]$ResolvedApkPath,
        [string]$Pattern
    )
    if (-not $AaptPath) {
        return $null
    }
    return (& $AaptPath dump badging $ResolvedApkPath) | Select-String $Pattern | Select-Object -First 1
}

$resolvedApk = (Resolve-Path $ApkPath).Path
if (-not (Test-Path $resolvedApk)) {
    throw "APK file not found: $ApkPath"
}

$sdkRoot = Get-SdkRoot
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    throw "adb.exe not found: $adb"
}

$aapt = Get-AaptPath -SdkRoot $sdkRoot
$packageFromApk = $null
$activityFromApk = $null

if (-not $PackageName -or -not $NoLaunch) {
    $pkgLine = Get-BadgingLine -AaptPath $aapt -ResolvedApkPath $resolvedApk -Pattern "package: name='"
    if ($pkgLine) {
        $packageFromApk = [regex]::Match($pkgLine.Line, "package: name='([^']+)'").Groups[1].Value
    }
}

if (-not $NoLaunch) {
    $actLine = Get-BadgingLine -AaptPath $aapt -ResolvedApkPath $resolvedApk -Pattern "launchable-activity: name='"
    if ($actLine) {
        $activityFromApk = [regex]::Match($actLine.Line, "launchable-activity: name='([^']+)'").Groups[1].Value
    }
}

if (-not $PackageName) {
    $PackageName = $packageFromApk
}

& $adb install -r $resolvedApk

if (-not $NoLaunch) {
    if ($PackageName -and $activityFromApk) {
        & $adb shell am start -n "$PackageName/$activityFromApk" | Out-Null
    }
    elseif ($PackageName) {
        & $adb shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 | Out-Null
    }
}

Write-Output "Installed: $resolvedApk"
if ($PackageName) {
    Write-Output "Package: $PackageName"
}
