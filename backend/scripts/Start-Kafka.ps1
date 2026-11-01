param([int]$Port=9092,[int]$ControllerPort=9093,[string]$Distribution='Ubuntu')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'KafkaRuntime.ps1')
$backendRoot=Split-Path $PSScriptRoot -Parent
$statePath=Join-Path $backendRoot 'target/kafka-local-runtime.json'
if (Test-Path -LiteralPath $statePath) {
    $existing=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    if ($existing.port -eq $Port -and (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
        & wsl.exe -d $existing.distribution -- bash $existing.script ready $existing.directory $existing.port $existing.controllerPort | Out-Null
        if ($LASTEXITCODE -eq 0) { Write-Output "Kafka local already ready on 127.0.0.1:$Port"; exit 0 }
    }
}
$runtime=Start-BankingKafka -Port $Port -ControllerPort $ControllerPort -Distribution $Distribution -RuntimeId ([guid]::NewGuid().ToString('N'))
$runtime | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding utf8
Write-Output "Kafka local ready on 127.0.0.1:$Port; state: $statePath"
