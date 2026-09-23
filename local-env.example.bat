@echo off
REM ------------------------------------------------------------------
REM Copy this file to "local-env.bat" (same folder) and set PY to the
REM python.exe you want the scripts to use.
REM
REM local-env.bat is listed in .gitignore, so your machine-specific
REM paths never end up in the repository.
REM
REM Why this exists: these scripts need a Python that has PyInstaller
REM installed (and boto3, for object storage). That is usually a
REM virtualenv, and its path is different on every machine.
REM
REM build-exe.bat / start-server.bat / publish.bat all read PY from here.
REM ------------------------------------------------------------------

set "PY=C:\path\to\your\venv\Scripts\python.exe"
