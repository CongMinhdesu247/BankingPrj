param(
    [string]$PostgreSqlBin = (Join-Path $env:ProgramFiles 'PostgreSQL\18\bin'),
    [string]$RedisDistribution = 'Ubuntu',
    [string]$IntegrationTest = ''
)

# Creates an isolated, short-lived PostgreSQL cluster. Never uses banking_db.
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'RedisRuntime.ps1')
. (Join-Path $PSScriptRoot 'KafkaRuntime.ps1')
$testRoot = Join-Path $backendRoot ('target\postgres-test\' + [guid]::NewGuid().ToString('N'))
$testData = Join-Path $testRoot 'data'
foreach ($executable in @('initdb.exe', 'pg_ctl.exe', 'createdb.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $PostgreSqlBin $executable))) {
        throw "Missing $executable. Supply -PostgreSqlBin with the PostgreSQL bin directory."
    }
}

$portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$portProbe.Start()
$testPort = $portProbe.LocalEndpoint.Port
$portProbe.Stop()

$savedEnvironment = @{}
foreach ($key in @('TEST_DB_URL', 'TEST_DB_USERNAME', 'TEST_DB_PASSWORD', 'JWT_SECRET',
    'REDIS_HOST','REDIS_PORT','REDIS_NAMESPACE','TEST_REDIS_DIRECTORY','TEST_REDIS_DISTRIBUTION',
    'BANKING_REDIS_LOGIN_RATE_LIMIT','BANKING_REDIS_TRANSFER_RATE_LIMIT','BANKING_FRAUD_RECOVERY_ENABLED','BANKING_KAFKA_ENABLED',
    'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE','SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE',
    'KAFKA_BOOTSTRAP_SERVERS','KAFKA_TOPIC_PREFIX','TEST_KAFKA_DIRECTORY','TEST_KAFKA_DISTRIBUTION','TEST_KAFKA_SCRIPT','TEST_KAFKA_PORT','TEST_KAFKA_CONTROLLER_PORT')) {
    $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
}
$started = $false
$redisRuntime = $null
$kafkaRuntime = $null
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
Push-Location $backendRoot
try {
    $portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $portProbe.Start(); $redisPort=$portProbe.LocalEndpoint.Port; $portProbe.Stop()
    $runtimeId=[guid]::NewGuid().ToString('N')
    $redisRuntime=Start-BankingRedis -Port $redisPort -Distribution $RedisDistribution -RuntimeId $runtimeId
    $env:REDIS_HOST='127.0.0.1'; $env:REDIS_PORT=[string]$redisPort; $env:REDIS_NAMESPACE="banking:test:$runtimeId"
    $env:TEST_REDIS_DIRECTORY=$redisRuntime.directory; $env:TEST_REDIS_DISTRIBUTION=$RedisDistribution
    # Regression suite uses generous demo thresholds; PhaseEightRedisIT overrides real policy explicitly.
    $env:BANKING_REDIS_LOGIN_RATE_LIMIT='10000'; $env:BANKING_REDIS_TRANSFER_RATE_LIMIT='10000'
    $env:BANKING_FRAUD_RECOVERY_ENABLED='false'
    $env:BANKING_KAFKA_ENABLED='false'
    # Many Spring test contexts coexist; bound their pools so security regression
    # contexts cannot exhaust the disposable server's 100 connection slots.
    $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4'; $env:SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0'
    $portProbe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    $portProbe.Start(); $kafkaPort=$portProbe.LocalEndpoint.Port; $portProbe.Stop()
    do { $portProbe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0); $portProbe.Start(); $controllerPort=$portProbe.LocalEndpoint.Port; $portProbe.Stop() } while ($controllerPort -eq $kafkaPort)
    $kafkaRuntime=Start-BankingKafka -Port $kafkaPort -ControllerPort $controllerPort -Distribution $RedisDistribution -RuntimeId $runtimeId
    $env:KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$kafkaPort"; $env:KAFKA_TOPIC_PREFIX="banking.test.$runtimeId"
    $env:TEST_KAFKA_DIRECTORY=$kafkaRuntime.directory; $env:TEST_KAFKA_DISTRIBUTION=$RedisDistribution; $env:TEST_KAFKA_SCRIPT=$kafkaRuntime.script
    $env:TEST_KAFKA_PORT=[string]$kafkaPort; $env:TEST_KAFKA_CONTROLLER_PORT=[string]$controllerPort
    & (Join-Path $PostgreSqlBin 'initdb.exe') -D $testData -A trust -U schema_test --no-locale --encoding=UTF8
    if ($LASTEXITCODE -ne 0) { throw 'initdb failed' }
    & (Join-Path $PostgreSqlBin 'pg_ctl.exe') -D $testData -l (Join-Path $testRoot 'postgres.log') -o "-h 127.0.0.1 -p $testPort" -w start
    if ($LASTEXITCODE -ne 0) { throw 'Temporary PostgreSQL could not start' }
    $started = $true
    & (Join-Path $PostgreSqlBin 'createdb.exe') -h 127.0.0.1 -p $testPort -U schema_test banking_test
    if ($LASTEXITCODE -ne 0) { throw 'Could not create banking_test' }
    $env:TEST_DB_URL = "jdbc:postgresql://127.0.0.1:$testPort/banking_test"
    $env:TEST_DB_USERNAME = 'schema_test'
    # Windows PowerShell removes environment variables assigned an empty string.
    # The disposable loopback/trust cluster ignores this synthetic password.
    $env:TEST_DB_PASSWORD = 'isolated-test-only'
    $testSigningBytes = New-Object byte[] 64
    $testSigningRng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $testSigningRng.GetBytes($testSigningBytes) }
    finally { $testSigningRng.Dispose() }
    $env:JWT_SECRET = [Convert]::ToBase64String($testSigningBytes)
    $mavenArguments=@('-B','-ntp','-P','integration','verify')
    if ($IntegrationTest) { $mavenArguments += "-Dit.test=$IntegrationTest" }
    & .\mvnw.cmd @mavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed; inspect target/surefire-reports and target/failsafe-reports' }
} finally {
    try { if ($kafkaRuntime) { Stop-BankingKafka -Runtime $kafkaRuntime } }
    finally {
        try { if ($redisRuntime) { Stop-BankingRedis -Runtime $redisRuntime } }
        finally {
            try { if ($started) { & (Join-Path $PostgreSqlBin 'pg_ctl.exe') -D $testData -m fast -w stop } }
            finally {
                foreach ($key in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key,$savedEnvironment[$key],'Process') }
                Pop-Location
            }
        }
    }
}
