param(
    [string]$AvdName = "GhostLink_API_35",
    [string]$SearchDir = "C:\Projekt\APK",
    [string]$Pattern = "GhostLink-release-*.apk",
    [string]$PackageName = "com.rezerv.app"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

& (Join-Path $scriptDir "start-emulator.ps1") -AvdName $AvdName
& (Join-Path $scriptDir "install-latest-apk.ps1") -SearchDir $SearchDir -Pattern $Pattern -PackageName $PackageName
