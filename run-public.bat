@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-PublicDemo.ps1"
if errorlevel 1 (
  echo.
  echo [ERROR] Could not start public demo tunnel.
  pause
  exit /b 1
)

