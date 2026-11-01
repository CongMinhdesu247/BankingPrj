function Start-BankingKafka {
    param([int]$Port,[int]$ControllerPort,[string]$Distribution='Ubuntu',[string]$RuntimeId)
    if ($Port -lt 1024 -or $Port -gt 65535 -or $ControllerPort -lt 1024 -or $ControllerPort -gt 65535 -or $Port -eq $ControllerPort -or
        $Distribution -notmatch '^[A-Za-z0-9._-]+$' -or $RuntimeId -notmatch '^[a-f0-9]{32}$') { throw 'Invalid Kafka runtime arguments.' }
    foreach ($candidate in @($Port,$ControllerPort)) {
        if (Get-NetTCPConnection -LocalPort $candidate -State Listen -ErrorAction SilentlyContinue) { throw "Kafka port $candidate is occupied." }
    }
    $scriptPath=Join-Path $PSScriptRoot 'KafkaRuntime.sh'
    $linuxScript=(& wsl.exe -d $Distribution -- wslpath -u $scriptPath.Replace('\','/')).Trim()
    $runtime=@{port=$Port;controllerPort=$ControllerPort;distribution=$Distribution;directory="/tmp/banking-kafka-$RuntimeId";script=$linuxScript}
    & wsl.exe -d $Distribution -- bash $linuxScript start $runtime.directory $Port $ControllerPort
    if ($LASTEXITCODE -ne 0) { throw 'Kafka start failed. Install Java 21 and run scripts/Install-Kafka.sh in WSL first.' }
    $deadline=[DateTime]::UtcNow.AddSeconds(45); $ready=$false
    while ([DateTime]::UtcNow -lt $deadline) {
        try { $client=[Net.Sockets.TcpClient]::new(); $client.Connect('127.0.0.1',$Port); $client.Dispose(); $ready=$true; break }
        catch { Start-Sleep -Milliseconds 200 }
    }
    if (-not $ready) { Stop-BankingKafka $runtime; throw 'Kafka listener did not become ready.' }
    & wsl.exe -d $Distribution -- bash $linuxScript ready $runtime.directory $Port $ControllerPort | Out-Null
    if ($LASTEXITCODE -ne 0) { Stop-BankingKafka $runtime; throw 'Kafka metadata is not ready.' }
    return $runtime
}
function Stop-BankingKafka {
    param($Runtime)
    if ($Runtime.directory -notmatch '^/tmp/banking-kafka-[a-f0-9]{32}$' -or $Runtime.distribution -notmatch '^[A-Za-z0-9._-]+$' -or
        $Runtime.script -notmatch '^/mnt/[a-z]/.+/backend/scripts/KafkaRuntime\.sh$') { throw 'Invalid Kafka runtime state.' }
    & wsl.exe -d $Runtime.distribution -- bash $Runtime.script stop $Runtime.directory $Runtime.port $Runtime.controllerPort
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop the verified Kafka process.' }
}
