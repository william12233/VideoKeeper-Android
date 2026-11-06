@echo off
chcp 65001 >nul
echo Building VideoKeeper Android debug APK...
call "%~dp0gradlew.bat" assembleDebug
if errorlevel 1 (
    echo Build failed. Open this project in Android Studio to inspect the error.
    pause
    exit /b 1
)
echo.
echo APK: %~dp0app\build\outputs\apk\debug\app-debug.apk
pause
