@echo off
chcp 65001 >nul
rem ============================================================
rem  Signage Admin - start the web console on this machine
rem ------------------------------------------------------------
rem  Edit the two values below, then double-click this file.
rem
rem    SIGNAGE_STORAGE   where the media lives, one of:
rem                        s3://<bucket>-<appid>/signage
rem                        local:D:\signage-online
rem    SIGNAGE_ADMIN_CODE  the passcode you type in the browser
rem
rem  For object storage (COS/OSS) also set these in the SAME
rem  window before running, or edit them in here:
rem
rem    set SIGNAGE_S3_ENDPOINT=https://cos.ap-shanghai.myqcloud.com
rem    set SIGNAGE_S3_REGION=ap-shanghai
rem    set AWS_ACCESS_KEY_ID=<your SecretId>
rem    set AWS_SECRET_ACCESS_KEY=<your SecretKey>
rem
rem  Python comes from local-env.bat in the project root (copy
rem  local-env.example.bat and edit it). Falls back to "python" on PATH.
rem ============================================================

if not defined PY if exist "%~dp0..\local-env.bat" call "%~dp0..\local-env.bat"
if not defined PY set "PY=python"

if not defined SIGNAGE_STORAGE set "SIGNAGE_STORAGE=local:D:\signage-online"
if not defined SIGNAGE_ADMIN_CODE set "SIGNAGE_ADMIN_CODE=change-me"

set "PYTHONIOENCODING=utf-8"
set "PYTHONUTF8=1"

echo.
echo   Storage : %SIGNAGE_STORAGE%
echo   Passcode: %SIGNAGE_ADMIN_CODE%
echo   Open    : http://127.0.0.1:8600/
echo.
echo   Press Ctrl+C to stop.
echo.

"%PY%" "%~dp0signage_admin.py" --port 8600 --host 0.0.0.0 %*
pause
