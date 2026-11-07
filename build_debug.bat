@echo off
setlocal
where gradle >nul 2>nul
if errorlevel 1 (
  echo [ERROR] gradle not found. Open the project with Android Studio, or install Gradle 8.x.
  pause
  exit /b 1
)
gradle assembleDebug
if errorlevel 1 exit /b 1
echo APK: app\build\outputs\apk\debug\app-debug.apk
pause
