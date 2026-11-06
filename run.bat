@echo off
setlocal
cd /d "%~dp0"

where docker >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please install Docker Desktop with Docker Compose first.
  goto :failed
)

docker compose version >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Docker Compose v2 is required.
  goto :failed
)

docker info >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Please start Docker Desktop and wait until it is ready.
  goto :failed
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Initialize-Docker.ps1"
if errorlevel 1 goto :failed

docker compose config --quiet
if errorlevel 1 goto :failed

echo.
echo [1/2] Building and starting BankingPrj containers (first run may take a few minutes)...
docker compose up -d --build --wait --wait-timeout 600
if errorlevel 1 goto :failed

echo.
echo [2/2] Preparing sample accounts and demo transactions...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Seed-DockerDemo.ps1"
if errorlevel 1 (
  echo [WARN] Demo seed was skipped or partially applied. You can still use docker.admin or register a new account.
)

echo.
echo ============================================================================
echo [DONE] BankingPrj is running at: http://localhost:8090
echo ----------------------------------------------------------------------------
echo  - Sample accounts : mock.an, mock.linh, mock.minh, mock.staff, mock.risk
echo    Password file   : backend\config\docker-demo.credentials.txt
echo  - Admin account   : docker.admin
echo    Password file   : .env (DOCKER_ADMIN_PASSWORD)
echo ============================================================================
start "" "http://localhost:8090"
pause
exit /b 0

:failed
echo.
echo [ERROR] Startup failed. Check the message above.
echo For service logs, run: docker compose logs --tail 100
pause
exit /b 1
