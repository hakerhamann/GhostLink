@echo off
setlocal

cd /d "%~dp0"

echo.
echo GhostLink: pull latest changes from GitHub
echo Project: %CD%
echo.

git rev-parse --is-inside-work-tree >nul 2>&1
if errorlevel 1 (
    echo ERROR: This folder is not a Git repository.
    pause
    exit /b 1
)

git remote get-url origin >nul 2>&1
if errorlevel 1 (
    echo ERROR: Git remote "origin" is not configured.
    pause
    exit /b 1
)

for /f "delims=" %%B in ('git branch --show-current') do set "CURRENT_BRANCH=%%B"
if "%CURRENT_BRANCH%"=="" (
    echo ERROR: Could not detect current branch.
    pause
    exit /b 1
)

echo Current branch: %CURRENT_BRANCH%
echo Remote: origin
echo.

git status --short
if errorlevel 1 (
    echo ERROR: Could not read git status.
    pause
    exit /b 1
)

echo.
echo Fetching from GitHub...
git fetch origin
if errorlevel 1 (
    echo ERROR: git fetch failed.
    pause
    exit /b 1
)

echo.
echo Pulling origin/%CURRENT_BRANCH% with fast-forward only...
git pull --ff-only origin %CURRENT_BRANCH%
if errorlevel 1 (
    echo.
    echo ERROR: Pull failed.
    echo Local changes or divergent commits may need manual handling.
    pause
    exit /b 1
)

echo.
echo Done. Local project is up to date with origin/%CURRENT_BRANCH%.
pause
