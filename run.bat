@echo off
setlocal
cd /d "%~dp0"
where docker >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Install Docker Desktop with Docker Compose first.
  goto :failed
)
docker compose version >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Docker Compose v2 is required.
  goto :failed
)
docker info >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Start Docker Desktop and wait until it is ready.
  goto :failed
)
if not exist ".env" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Initialize-Docker.ps1"
  if errorlevel 1 goto :failed
)
docker compose config --quiet
if errorlevel 1 goto :failed
echo Building and starting BankingPrj. The first build may take several minutes...
docker compose up -d --build --wait --wait-timeout 300
if errorlevel 1 goto :failed
echo.
echo [DONE] Application is ready: http://localhost:8090
echo Admin: docker.admin - password: DOCKER_ADMIN_PASSWORD in .env
echo Keep .env for future starts. Data is stored in Docker volumes.
pause
exit /b 0

:failed
echo.
echo [ERROR] Startup failed. Check the error above.
echo For service logs: docker compose logs --tail 100
pause
exit /b 1
