@echo off
REM Build signage-admin.exe (the web admin server) as a standalone Windows exe.
REM Double-click this file, or run it from a terminal. All args pass through,
REM e.g.  build-exe.bat --no-smoke
REM
REM Python is looked up in this order:
REM   1. the PY environment variable, if it is already set
REM   2. local-env.bat in the project root (not in the repo -- copy the .example)
REM   3. whatever "python" resolves to on PATH
REM
REM The build needs PyInstaller installed (and boto3, if you use object storage).
setlocal
cd /d "%~dp0"

if not defined PY if exist "%~dp0..\local-env.bat" call "%~dp0..\local-env.bat"
if not defined PY where python >nul 2>nul && set "PY=python"
if not defined PY (
  echo [!] Python not found. Any of these will fix it:
  echo     - put python on PATH, or
  echo     - set the PY environment variable to the full path, or
  echo     - copy local-env.example.bat to local-env.bat and edit it
  pause
  exit /b 1
)

set PYTHONIOENCODING=utf-8
"%PY%" build-exe.py %*
echo.
pause
