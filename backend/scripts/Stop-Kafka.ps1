$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'KafkaRuntime.ps1')
$statePath=Join-Path (Split-Path $PSScriptRoot -Parent) 'target/kafka-local-runtime.json'
if (Test-Path -LiteralPath $statePath) { Stop-BankingKafka -Runtime (Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json) }
Write-Output 'Verified local Kafka stopped; retained broker data/logs.'
