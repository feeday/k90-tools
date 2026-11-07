@echo off
chcp 65001 >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0一键构建APK.ps1"
if errorlevel 1 (
  echo.
  echo 构建失败，请查看上面的错误信息。
  pause
  exit /b 1
)
echo.
echo 构建完成。
pause
