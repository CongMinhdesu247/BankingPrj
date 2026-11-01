# WSL Redis helper for disposable test/demo instances. Never attaches to an occupied port.
function Start-BankingRedis {
    param([int]$Port, [string]$Distribution = 'Ubuntu', [string]$RuntimeId)
    if ($Port -lt 1024 -or $Port -gt 65535 -or $Distribution -notmatch '^[A-Za-z0-9._-]+$' -or $RuntimeId -notmatch '^[a-f0-9]{32}$') { throw 'Invalid isolated Redis arguments.' }
    $runtimeDir = "/tmp/banking-redis-$RuntimeId"
    # An unused port is expected. Native stderr under Windows PowerShell with
    # ErrorActionPreference=Stop must not abort the test runner on connection refused.
    $occupied = $false
    $probe = [Net.Sockets.TcpClient]::new()
    try { $probe.Connect('127.0.0.1', $Port); $occupied = $true }
    catch [Net.Sockets.SocketException] { }
    finally { $probe.Dispose() }
    if ($occupied) { throw "Redis port $Port already belongs to another runtime." }
    $command = "mkdir -p $runtimeDir && redis-server --bind 127.0.0.1 --protected-mode yes --port $Port --save '' --appendonly no --daemonize yes --dir $runtimeDir --pidfile $runtimeDir/redis.pid --logfile $runtimeDir/redis.log"
    & wsl.exe -d $Distribution -- sh -lc $command
    if ($LASTEXITCODE -ne 0) { throw 'Redis could not start; install redis-server and redis-tools inside the WSL distribution.' }
    $ready = $false
    for ($i=0; $i -lt 40; $i++) {
        try {
            $client=[Net.Sockets.TcpClient]::new(); $client.Connect('127.0.0.1',$Port); $client.Dispose(); $ready=$true; break
        } catch { Start-Sleep -Milliseconds 100 }
    }
    if (-not $ready) { throw 'WSL Redis is not reachable through Windows localhost forwarding.' }
    return @{port=$Port;distribution=$Distribution;directory=$runtimeDir}
}

function Stop-BankingRedis {
    param($Runtime)
    if ($Runtime.directory -notmatch '^/tmp/banking-redis-[a-f0-9]{32}$' -or $Runtime.distribution -notmatch '^[A-Za-z0-9._-]+$' -or $Runtime.port -lt 1024 -or $Runtime.port -gt 65535) { throw 'Invalid isolated Redis state.' }
    $reply = & wsl.exe -d $Runtime.distribution -- redis-cli -h 127.0.0.1 -p $Runtime.port --raw CONFIG GET dir 2>$null
    if ($LASTEXITCODE -ne 0) { return }
    if (($reply | Select-Object -Last 1) -ne $Runtime.directory) { throw 'Refusing to stop Redis belonging to another runtime.' }
    & wsl.exe -d $Runtime.distribution -- redis-cli -h 127.0.0.1 -p $Runtime.port shutdown nosave
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop isolated Redis.' }
}
