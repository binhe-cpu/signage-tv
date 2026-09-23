@echo off
chcp 65001 >nul
rem ============================================================
rem  SignageTV - publish media + playlist (Windows launcher)
rem ------------------------------------------------------------
rem  Usage (run in cmd or PowerShell, NOT Git Bash):
rem
rem    cd signage-tv\tools
rem    publish.bat --src "D:\signage\media" --target "s3://bucket-1250000000/signage"
rem
rem  For object storage (COS/OSS), set these in the SAME window first:
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

"%PY%" "%~dp0publish.py" %*
