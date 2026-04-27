param(
    [string]$SearchDir = (Get-Location).Path,
    [string]$Pattern = "GhostLink-release-*.apk",
    [string]$PackageName
)

$ErrorActionPreference = "Stop"

$apk = Get-ChildItem -Path $SearchDir -Filter $Pattern -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $apk) {
    throw "No APK found in $SearchDir with pattern $Pattern"
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$installerScript = Join-Path $scriptDir "install-apk.ps1"

if (-not (Test-Path $installerScript)) {
    throw "install-apk.ps1 not found: $installerScript"
}

if ($PackageName) {
    & $installerScript -ApkPath $apk.FullName -PackageName $PackageName
}
else {
    & $installerScript -ApkPath $apk.FullName
}
