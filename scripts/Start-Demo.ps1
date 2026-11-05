# Run from PowerShell 7: pwsh -File ./scripts/Start-Demo.ps1
[CmdletBinding()]
param(
    [string]$PostgreSqlBin = 'C:\Program Files\PostgreSQL\18\bin',
    [string]$Distribution = 'Ubuntu',
    [int]$ApiPort = 8081,
    [int]$UiPort = 5174
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'Use PowerShell 7: pwsh -File ./scripts/Start-Demo.ps1' }
foreach ($command in @('node.exe', 'npm.cmd', 'java.exe', 'wsl.exe')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Missing $command. See README.md prerequisites." }
}
$nodeVersion = [version]((& node.exe -p 'process.versions.node').Trim())
if ($nodeVersion -lt [version]'22.12.0') { throw 'Node.js 22.12+ is required.' }
foreach ($binary in @('initdb.exe','pg_ctl.exe','createdb.exe','psql.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $PostgreSqlBin $binary))) { throw "Missing PostgreSQL binary $binary. Set -PostgreSqlBin to your installation bin folder." }
}
& wsl.exe -d $Distribution -- sh -lc 'command -v redis-server >/dev/null && command -v redis-cli >/dev/null && command -v java >/dev/null && test -d /opt/kafka_2.13-4.1.2'
if ($LASTEXITCODE -ne 0) { throw 'WSL prerequisites missing. Follow the one-time setup in README.md.' }
Push-Location (Join-Path $projectRoot 'frontend')
try {
    & npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    & npm.cmd run build
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
} finally { Pop-Location }
Push-Location (Join-Path $projectRoot 'backend')
try {
    & ./mvnw.cmd package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
} finally { Pop-Location }
& (Join-Path $projectRoot 'backend/scripts/Start-PortalDemo.ps1') -PostgreSqlBin $PostgreSqlBin -RedisDistribution $Distribution -ApiPort $ApiPort -UiPort $UiPort
