@echo off
setlocal enabledelayedexpansion

pushd "%~dp0\.."
if errorlevel 1 goto :fail

for /f "delims=" %%B in ('git branch --show-current') do set "BRANCH=%%B"
if not defined BRANCH (
  echo Cannot detect current branch.
  goto :fail
)

if "%~1"=="" (
  set "MESSAGE=update current branch"
) else (
  set "MESSAGE=%~1"
)

git add -A
if errorlevel 1 goto :fail

git diff --cached --quiet
if not errorlevel 1 (
  echo No staged changes. Nothing to commit.
  goto :push
)

git commit -m "%MESSAGE%"
if errorlevel 1 goto :fail

:push
git push origin HEAD
if errorlevel 1 goto :fail

echo Done. Branch: %BRANCH%
popd
exit /b 0

:fail
echo Failed.
popd
exit /b 1
