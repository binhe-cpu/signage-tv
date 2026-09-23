@echo off
setlocal

rem ============================================================
rem  Build script for the SignageTV Android app (Windows)
rem  Double-click this file to build the debug APK
rem  The APK file name carries the version, e.g. signage-0.3.0-3-debug.apk
rem ============================================================

set "JAVA_HOME=D:\jdk17\jdk-17.0.20.1+1"
set "ANDROID_HOME=D:\android-sdk"
set "ANDROID_SDK_ROOT=D:\android-sdk"
set "GRADLE_USER_HOME=D:\gradle-home"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0"

echo.
echo [1/2] Java version:
"%JAVA_HOME%\bin\java.exe" -version
echo.
echo [2/2] Building debug APK...
echo.

call "D:\gradle-8.7\bin\gradle.bat" --console=plain :app:assembleDebug %*
if errorlevel 1 goto fail

echo.
echo ============================================================
echo  BUILD OK
echo.
echo  APK (file name carries the version):
dir /b "app\build\outputs\apk\debug\*.apk"
echo.
echo  Folder: app\build\outputs\apk\debug\
echo ============================================================
echo.
pause
exit /b 0

:fail
echo.
echo ============================================================
echo  BUILD FAILED - send the error text above for help
echo ============================================================
echo.
pause
exit /b 1
