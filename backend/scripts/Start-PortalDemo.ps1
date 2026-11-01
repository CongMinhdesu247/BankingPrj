param(
    [string]$PostgreSqlBin = 'C:\Program Files\PostgreSQL\18\bin',
    [int]$ApiPort = 8081,
    [int]$UiPort = 5174,
    [int]$RedisPort = 6381,
    [int]$KafkaPort = 19096,
    [int]$KafkaControllerPort = 19097,
    [string]$RedisDistribution = 'Ubuntu'
)
$ErrorActionPreference = 'Stop'
$backendRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'RedisRuntime.ps1')
. (Join-Path $PSScriptRoot 'KafkaRuntime.ps1')
$projectRoot = (Resolve-Path (Join-Path $backendRoot '..')).Path
$frontendRoot = Join-Path $projectRoot 'frontend'
$stateRoot = Join-Path $backendRoot 'target/portal-demo'
$statePath = Join-Path $stateRoot 'server-state.json'
foreach ($port in @($ApiPort, $UiPort)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $port is occupied. Stop the existing demo with Stop-PortalDemo.ps1 first."
    }
}
if (-not (Test-Path (Join-Path $backendRoot 'target/banking-0.0.1-SNAPSHOT.jar'))) { throw 'Build and verify the backend JAR before starting the portal demo.' }
$demoRoot = Join-Path $stateRoot ([guid]::NewGuid().ToString('N'))
$dataPath = Join-Path $demoRoot 'data'
New-Item -ItemType Directory -Force -Path $demoRoot | Out-Null
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
$probe.Start(); $pgPort = $probe.LocalEndpoint.Port; $probe.Stop()
$savedEnvironment = @{}
foreach ($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','JWT_SECRET','BOOTSTRAP_ADMIN_ENABLED','BOOTSTRAP_ADMIN_USERNAME','BOOTSTRAP_ADMIN_PASSWORD','API_PROXY_TARGET','REDIS_HOST','REDIS_PORT','REDIS_NAMESPACE','KAFKA_BOOTSTRAP_SERVERS','KAFKA_TOPIC_PREFIX','BANKING_KAFKA_ENABLED')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$pgStarted = $false; $backendProcess = $null; $frontendProcess = $null
$redisRuntime = $null
$kafkaRuntime = $null
function New-DemoPassword { return 'Demo-' + [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(16)) + '!a1' }
function Invoke-DemoApi([string]$method, [string]$path, $body, [string]$token = '') {
    $headers = @{'X-Request-Id'='portal-demo-setup'}
    if ($token) { $headers.Authorization = "Bearer $token" }
    if ($path -eq '/transfers') { $headers['Idempotency-Key'] = [guid]::NewGuid().ToString() }
    $parameters = @{Uri="http://127.0.0.1:$ApiPort/api/v1$path"; Method=$method; Headers=$headers; TimeoutSec=15}
    if ($null -ne $body) { $parameters.ContentType='application/json'; $parameters.Body=($body | ConvertTo-Json -Compress -Depth 5) }
    return Invoke-RestMethod @parameters
}
try {
    $runtimeId=[guid]::NewGuid().ToString('N')
    $redisRuntime=Start-BankingRedis -Port $RedisPort -Distribution $RedisDistribution -RuntimeId $runtimeId
    $env:REDIS_HOST='127.0.0.1'; $env:REDIS_PORT=[string]$RedisPort; $env:REDIS_NAMESPACE="banking:demo:$runtimeId"
    $kafkaRuntime=Start-BankingKafka -Port $KafkaPort -ControllerPort $KafkaControllerPort -Distribution $RedisDistribution -RuntimeId $runtimeId
    $env:KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$KafkaPort"; $env:KAFKA_TOPIC_PREFIX="banking.demo.$runtimeId"; $env:BANKING_KAFKA_ENABLED='true'
    & (Join-Path $PostgreSqlBin 'initdb.exe') -D $dataPath -A trust -U portal_demo --no-locale --encoding=UTF8 -c shared_buffers=32MB -c max_connections=30 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Demo PostgreSQL initialization failed.' }
    & (Join-Path $PostgreSqlBin 'pg_ctl.exe') -D $dataPath -l (Join-Path $demoRoot 'postgres.log') -o "-h 127.0.0.1 -p $pgPort" -w start
    if ($LASTEXITCODE -ne 0) { throw 'Demo PostgreSQL startup failed.' }; $pgStarted=$true
    & (Join-Path $PostgreSqlBin 'createdb.exe') -h 127.0.0.1 -p $pgPort -U portal_demo banking_test
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated banking_test demo database.' }
    $env:SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:$pgPort/banking_test"
    $env:SPRING_DATASOURCE_USERNAME='portal_demo'; $env:SPRING_DATASOURCE_PASSWORD=''
    $env:JWT_SECRET=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
    $adminPassword=New-DemoPassword; $customerPassword=New-DemoPassword; $otherPassword=New-DemoPassword; $riskPassword=New-DemoPassword; $secondRiskPassword=New-DemoPassword; $staffPassword=New-DemoPassword
    $env:BOOTSTRAP_ADMIN_ENABLED='true'; $env:BOOTSTRAP_ADMIN_USERNAME='portal.admin'; $env:BOOTSTRAP_ADMIN_PASSWORD=$adminPassword
    $backendProcess=Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList @('-Xms64m','-Xmx384m','-jar','target\banking-0.0.1-SNAPSHOT.jar','--spring.profiles.active=dev','--spring.config.import=','--banking.redis.login-max-failures=6','--banking.redis.login-rate-limit=120',"--server.port=$ApiPort","--banking.security.allowed-origins=http://127.0.0.1:$UiPort,http://localhost:$UiPort") -WorkingDirectory $backendRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $demoRoot 'backend.stdout.log') -RedirectStandardError (Join-Path $demoRoot 'backend.stderr.log') -PassThru
    $deadline=[DateTime]::UtcNow.AddSeconds(60); $ready=$false
    while ([DateTime]::UtcNow -lt $deadline) {
        try { $response=Invoke-WebRequest "http://127.0.0.1:$ApiPort/v3/api-docs" -TimeoutSec 2; if ($response.StatusCode -eq 200) { $ready=$true; break } } catch { }
        if ($backendProcess.HasExited) { throw "Demo backend exited. Inspect logs in $demoRoot" }
        Start-Sleep -Milliseconds 250
    }
    if (-not $ready) { throw "Demo backend did not become ready. Inspect $demoRoot" }
    $admin=(Invoke-DemoApi 'POST' '/auth/login' @{username='portal.admin';password=$adminPassword}).accessToken
    $first=Invoke-DemoApi 'POST' '/customers' @{customerCode='PORTAL-AN';fullName='Nguyễn Minh An';email='an@portal.example';phone='0900000001'} $admin
    $second=Invoke-DemoApi 'POST' '/customers' @{customerCode='PORTAL-LINH';fullName='Trần Gia Linh';email='linh@portal.example';phone='0900000002'} $admin
    $reserve=Invoke-DemoApi 'POST' '/customers' @{customerCode='PORTAL-RESERVE';fullName='Quỹ mô phỏng Portal';email='reserve@portal.example';phone=$null} $admin
    $source=Invoke-DemoApi 'POST' "/customers/$($first.id)/accounts" @{currency='VND'} $admin
    $savings=Invoke-DemoApi 'POST' "/customers/$($first.id)/accounts" @{currency='VND'} $admin
    $destination=Invoke-DemoApi 'POST' "/customers/$($second.id)/accounts" @{currency='VND'} $admin
    $treasury=Invoke-DemoApi 'POST' "/customers/$($reserve.id)/accounts" @{currency='VND'} $admin
    $null=Invoke-DemoApi 'POST' '/users' @{username='demo.an';password=$customerPassword;customerId=$first.id;roles=@('CUSTOMER')} $admin
    $null=Invoke-DemoApi 'POST' '/users' @{username='demo.linh';password=$otherPassword;customerId=$second.id;roles=@('CUSTOMER')} $admin
    $null=Invoke-DemoApi 'POST' '/users' @{username='demo.risk';password=$riskPassword;roles=@('RISK_OFFICER')} $admin
    $null=Invoke-DemoApi 'POST' '/users' @{username='demo.risk2';password=$secondRiskPassword;roles=@('RISK_OFFICER')} $admin
    $null=Invoke-DemoApi 'POST' '/users' @{username='demo.staff';password=$staffPassword;roles=@('BANK_STAFF')} $admin
    # Funding exists ONLY in this brand-new isolated native test cluster. No funding endpoint is added.
    # Lock all fixture accounts in canonical UUID order; reserve + OPENING/debit/credit are atomic.
    $seed = @"
BEGIN;
DO `$`$ BEGIN IF current_database()<>'banking_test' OR current_user<>'portal_demo' THEN RAISE EXCEPTION 'Not the isolated portal demo database'; END IF; END `$`$;
SELECT id FROM accounts ORDER BY id FOR UPDATE;
UPDATE accounts SET balance=500000000,version=version+1,updated_at=now() WHERE id='$($treasury.id)';
"@
    $reserveBalance=500000000L
    foreach ($allocation in @(@{account=$source.id;amount=240000000L},@{account=$savings.id;amount=80000000L},@{account=$destination.id;amount=50000000L})) {
        $transactionId=[guid]::NewGuid().ToString(); $reserveBalance-=$allocation.amount
        $seed += @"

INSERT INTO transactions(id,reference,source_account_id,destination_account_id,amount,currency,type,status,description,created_at) VALUES ('$transactionId','OPN-$transactionId','$($treasury.id)','$($allocation.account)',$($allocation.amount),'VND','OPENING','COMPLETED','Phân bổ vốn mô phỏng Portal',clock_timestamp());
UPDATE accounts SET balance=$reserveBalance,version=version+1,updated_at=now() WHERE id='$($treasury.id)';
UPDATE accounts SET balance=$($allocation.amount),version=version+1,updated_at=now() WHERE id='$($allocation.account)';
INSERT INTO transaction_entries(transaction_id,account_id,direction,amount,currency,balance_after,created_at) VALUES ('$transactionId','$($treasury.id)','DEBIT',$($allocation.amount),'VND',$reserveBalance,(SELECT created_at FROM transactions WHERE id='$transactionId')),('$transactionId','$($allocation.account)','CREDIT',$($allocation.amount),'VND',$($allocation.amount),(SELECT created_at FROM transactions WHERE id='$transactionId'));
"@
    }
    $seed += "`nCOMMIT;"
    $seedPath=Join-Path $demoRoot 'seed-opening.sql'; Set-Content -LiteralPath $seedPath -Value $seed -Encoding utf8
    & (Join-Path $PostgreSqlBin 'psql.exe') -X -h 127.0.0.1 -p $pgPort -U portal_demo -d banking_test -v ON_ERROR_STOP=1 -f $seedPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Isolated opening fixture failed.' }
    $firstToken=(Invoke-DemoApi 'POST' '/auth/login' @{username='demo.an';password=$customerPassword}).accessToken
    $secondToken=(Invoke-DemoApi 'POST' '/auth/login' @{username='demo.linh';password=$otherPassword}).accessToken
    $null=Invoke-DemoApi 'POST' '/me/beneficiaries' @{destinationAccountId=$destination.id;nickname='Gia Linh'} $firstToken
    $null=Invoke-DemoApi 'POST' '/me/beneficiaries' @{destinationAccountId=$savings.id;nickname='Tài khoản dự phòng'} $firstToken
    # HIGH: amount + newly saved beneficiary, before the velocity threshold.
    $null=Invoke-DemoApi 'POST' '/transfers' @{sourceAccountId=$source.id;destinationAccountId=$destination.id;amount='100000000';currency='VND';description='Giao dịch mô phỏng cảnh báo HIGH'} $firstToken
    $descriptions=@('Chi phí sinh hoạt','Bữa trưa cuối tuần','Gửi bạn một chút niềm vui','Mua sách tháng này','Cà phê cùng bạn','Đóng góp chuyến đi','Thanh toán bữa tối','Quà tặng cuối tháng','Chia sẻ chi phí','Chuyển khoản gia đình','Hẹn một ngày đẹp trời')
    for ($i=0;$i -lt $descriptions.Count;$i++) {
        $null=Invoke-DemoApi 'POST' '/transfers' @{sourceAccountId=$source.id;destinationAccountId=$destination.id;amount=[string](100000+($i*25000));currency='VND';description=$descriptions[$i]} $firstToken
    }
    $null=Invoke-DemoApi 'POST' '/transfers' @{sourceAccountId=$destination.id;destinationAccountId=$source.id;amount='250000';currency='VND';description='Gửi lại bạn nhé'} $secondToken
    # Actual failed HTTP logins create durable evidence; demo guard=6 lets a sixth, correct login reset it.
    foreach ($person in @(@{username='demo.an';password=$customerPassword},@{username='demo.linh';password=$otherPassword})) {
        for ($attempt=0;$attempt -lt 5;$attempt++) {
            $failed=Invoke-WebRequest -Uri "http://127.0.0.1:$ApiPort/api/v1/auth/login" -Method POST -ContentType 'application/json' -Body (@{username=$person.username;password='deliberately-wrong-demo-password'} | ConvertTo-Json -Compress) -SkipHttpErrorCheck
            if ($failed.StatusCode -ne 401) { throw 'Fraud demo failed-login fixture did not return 401.' }
        }
        $login=Invoke-DemoApi 'POST' '/auth/login' @{username=$person.username;password=$person.password}
        if ($person.username -eq 'demo.an') { $firstToken=$login.accessToken } else { $secondToken=$login.accessToken }
    }
    # CRITICAL: all four rule matches cap at 100; LOW: only failed-login context for the other actor.
    $null=Invoke-DemoApi 'POST' '/transfers' @{sourceAccountId=$source.id;destinationAccountId=$destination.id;amount='100000000';currency='VND';description='Giao dịch mô phỏng cảnh báo CRITICAL'} $firstToken
    $null=Invoke-DemoApi 'POST' '/transfers' @{sourceAccountId=$destination.id;destinationAccountId=$source.id;amount='1000';currency='VND';description='Giao dịch mô phỏng cảnh báo LOW'} $secondToken
    # Do not call the demo ready until the real Kafka consumers have caught up.
    $riskToken=(Invoke-DemoApi 'POST' '/auth/login' @{username='demo.risk';password=$riskPassword}).accessToken
    $deadline=[DateTime]::UtcNow.AddSeconds(60); $caughtUp=$false
    while ([DateTime]::UtcNow -lt $deadline) {
        $summary=Invoke-DemoApi 'GET' '/fraud/summary' $null $riskToken
        $inbox=Invoke-DemoApi 'GET' '/me/notifications?size=1' $null $riskToken
        if ($summary.analyzedTransfers -ge 15 -and $summary.pendingTransfers -eq 0 -and $inbox.totalElements -ge 11) { $caughtUp=$true; break }
        Start-Sleep -Milliseconds 250
    }
    if (-not $caughtUp) { throw 'Kafka fraud/notification consumers did not finish demo setup; inspect backend logs.' }
    $env:API_PROXY_TARGET="http://127.0.0.1:$ApiPort"
    $frontendProcess=Start-Process -FilePath (Get-Command node.exe).Source -ArgumentList @('node_modules/vite/bin/vite.js','--host','127.0.0.1','--port',[string]$UiPort) -WorkingDirectory $frontendRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $demoRoot 'frontend.stdout.log') -RedirectStandardError (Join-Path $demoRoot 'frontend.stderr.log') -PassThru
    $state=@{demoRoot=$demoRoot;dataPath=$dataPath;pgPort=$pgPort;apiPort=$ApiPort;uiPort=$UiPort;backendPid=$backendProcess.Id;frontendPid=$frontendProcess.Id;database='banking_test';databaseUser='portal_demo';treasuryId=$treasury.id;sourceId=$source.id;savingsId=$savings.id;destinationId=$destination.id;sourceNumber=$source.accountNumber;destinationNumber=$destination.accountNumber}
    $state.redis=$redisRuntime
    $state.kafka=$kafkaRuntime
    $state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statePath -Encoding utf8
    $credentialPath=Join-Path $backendRoot 'config/portal-demo.credentials.txt'
    "username=demo.an`npassword=$customerPassword`notherUsername=demo.linh`notherPassword=$otherPassword`nadminUsername=portal.admin`nadminPassword=$adminPassword`nriskUsername=demo.risk`nriskPassword=$riskPassword`nsecondRiskUsername=demo.risk2`nsecondRiskPassword=$secondRiskPassword`nstaffUsername=demo.staff`nstaffPassword=$staffPassword" | Set-Content -LiteralPath $credentialPath -Encoding utf8
    New-Item -ItemType Directory -Force -Path (Join-Path $frontendRoot '.local') | Out-Null
    (@{baseUrl="http://127.0.0.1:$UiPort";apiUrl="http://127.0.0.1:$ApiPort";username='demo.an';password=$customerPassword;otherUsername='demo.linh';otherPassword=$otherPassword;riskUsername='demo.risk';riskPassword=$riskPassword;secondRiskUsername='demo.risk2';secondRiskPassword=$secondRiskPassword;staffUsername='demo.staff';staffPassword=$staffPassword;adminUsername='portal.admin';adminPassword=$adminPassword;sourceId=$source.id;sourceNumber=$source.accountNumber;destinationId=$destination.id;destinationNumber=$destination.accountNumber} | ConvertTo-Json) | Set-Content -LiteralPath (Join-Path $frontendRoot '.local/portal-demo.json') -Encoding utf8
    Write-Output "Isolated portal demo ready: http://127.0.0.1:$UiPort"
    Write-Output "Demo credentials saved in $credentialPath (Git ignored)."
    Write-Output 'Personal banking_db was not used or seeded. Stop with backend/scripts/Stop-PortalDemo.ps1.'
} catch {
    if ($kafkaRuntime) { Stop-BankingKafka -Runtime $kafkaRuntime }
    if ($redisRuntime) { Stop-BankingRedis -Runtime $redisRuntime }
    if ($frontendProcess -and -not $frontendProcess.HasExited) { Stop-Process -Id $frontendProcess.Id }
    if ($backendProcess -and -not $backendProcess.HasExited) { Stop-Process -Id $backendProcess.Id }
    if ($pgStarted) { & (Join-Path $PostgreSqlBin 'pg_ctl.exe') -D $dataPath -m fast -w stop | Out-Null }
    throw
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process') }
}
