param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path ".venv")) {
    python -m venv .venv
}

$python = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"
& $python -m pip install -r requirements.txt | Out-Null

$env:RESERV_PORT = "$Port"
Start-Process -FilePath $python -ArgumentList "server.py" -WorkingDirectory $PSScriptRoot
Write-Output "Backend started on port $Port"
