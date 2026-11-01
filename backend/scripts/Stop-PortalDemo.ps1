param([string]$PostgreSqlBin = 'C:\Program Files\PostgreSQL\18\bin')
$ErrorActionPreference='Stop'
$backendRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'RedisRuntime.ps1')
. (Join-Path $PSScriptRoot 'KafkaRuntime.ps1')
$stateRoot=[IO.Path]::GetFullPath((Join-Path $backendRoot 'target/portal-demo'))
$statePath=Join-Path $stateRoot 'server-state.json'
if (-not (Test-Path -LiteralPath $statePath)) { Write-Output 'No portal demo state exists.'; exit 0 }
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$dataPath=[IO.Path]::GetFullPath($state.dataPath)
if (-not $dataPath.StartsWith($stateRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $state.database -ne 'banking_test' -or $state.databaseUser -ne 'portal_demo') { throw 'Refusing to stop a PostgreSQL cluster outside the isolated portal demo workspace.' }
foreach ($entry in @(@{id=$state.frontendPid;name='node.exe';pattern='node_modules/vite/bin/vite.js';portArgument="--port $($state.uiPort)"},@{id=$state.backendPid;name='java.exe';pattern='target\banking-0.0.1-SNAPSHOT.jar';portArgument="--server.port=$($state.apiPort)"})) {
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.id)"
    if ($process) {
        if ($process.Name -ne $entry.name -or -not $process.CommandLine.Contains($entry.pattern) -or -not $process.CommandLine.Contains($entry.portArgument)) { throw 'PID was reused by another process; inspect it manually.' }
        Stop-Process -Id $entry.id
    }
}
& (Join-Path $PostgreSqlBin 'pg_ctl.exe') -D $dataPath status | Out-Null
if ($LASTEXITCODE -eq 0) { & (Join-Path $PostgreSqlBin 'pg_ctl.exe') -D $dataPath -m fast -w stop; if ($LASTEXITCODE -ne 0) { throw 'Could not stop the demo cluster.' } }
if ($state.redis) { Stop-BankingRedis -Runtime $state.redis }
if ($state.kafka) { Stop-BankingKafka -Runtime $state.kafka }
Write-Output 'Portal demo stopped. Its data remains in backend/target/portal-demo; personal PostgreSQL was not stopped.'
exit 0
