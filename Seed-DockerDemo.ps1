#requires -Version 7.0
# Explicit fixtures for the isolated Docker demo only; safe to rerun.
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$backendRoot = Join-Path $projectRoot 'backend'
Set-Location -LiteralPath $projectRoot
$settings = @{}
Get-Content (Join-Path $projectRoot '.env') | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1].Trim()] = $matches[2].Trim() }
}
function Sql([string]$query) {
    $result = $query | & docker compose exec -T postgres psql -X -U banking_demo -d banking_docker_demo -v ON_ERROR_STOP=1 -A -t
    if ($LASTEXITCODE -ne 0) { throw 'Docker demo SQL failed.' }
    return ($result -join "`n").Trim()
}
function Api([string]$method, [string]$path, $body, [string]$token = '', [string]$key = '') {
    $headers = @{'X-Request-Id'='local-mockdata-v1'}
    if ($token) { $headers.Authorization = "Bearer $token" }
    if ($key) { $headers['Idempotency-Key'] = $key }
    $params = @{Uri="http://127.0.0.1:8090/api/v1$path"; Method=$method; Headers=$headers; TimeoutSec=30}
    if ($null -ne $body) {
        $params.ContentType = 'application/json; charset=utf-8'
        $params.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress -Depth 5))
    }
    Invoke-RestMethod @params
}
try {
    $admin = (Api POST '/auth/login' @{username='docker.admin';password=$settings['DOCKER_ADMIN_PASSWORD']}).accessToken
    New-Item -ItemType Directory -Force -Path (Join-Path $backendRoot 'config') | Out-Null
    $credentialPath = Join-Path $backendRoot 'config/docker-demo.credentials.txt'
    if (Test-Path $credentialPath) {
        $password = ((Get-Content $credentialPath | Where-Object { $_ -like 'password=*' }) -replace '^password=','')
    } else {
        $password = 'Mock-' + [guid]::NewGuid().ToString('N').Substring(0,16) + '!a1'
        "password=$password`nusers=mock.an,mock.linh,mock.minh,mock.staff,mock.risk" | Set-Content $credentialPath -Encoding utf8
    }
    $people = @(
        @{code='MOCK-AN'; name='Nguyễn Minh An'; username='mock.an'; amount=200000000; phone='0900000101'},
        @{code='MOCK-LINH'; name='Trần Gia Linh'; username='mock.linh'; amount=150000000; phone='0900000102'},
        @{code='MOCK-MINH'; name='Lê Hoàng Minh'; username='mock.minh'; amount=100000000; phone='0900000103'},
        @{code='MOCK-RESERVE'; name='Quỹ vốn mô phỏng local'; username=''; amount=0; phone='0900000199'}
    )
    foreach ($person in $people) {
        $customerId = Sql "SELECT id FROM customers WHERE customer_code='$($person.code)'"
        if (-not $customerId) {
            $customerId = (Api POST '/customers' @{customerCode=$person.code;fullName=$person.name;email=($person.code.ToLower()+'@mock.example');phone=$person.phone} $admin).id
        }
        $person.customerId = $customerId
        $accountId = Sql "SELECT id FROM accounts WHERE customer_id='$customerId' ORDER BY created_at,id LIMIT 1"
        if (-not $accountId) { $accountId = (Api POST "/customers/$customerId/accounts" @{currency='VND'} $admin).id }
        $person.accountId = $accountId
        if ($person.username -and -not (Sql "SELECT id FROM users WHERE username='$($person.username)'")) {
            $null = Api POST '/users' @{username=$person.username;password=$password;customerId=$customerId;roles=@('CUSTOMER')} $admin
        }
    }
    foreach ($internal in @(@{username='mock.staff';role='BANK_STAFF'},@{username='mock.risk';role='RISK_OFFICER'})) {
        if (-not (Sql "SELECT id FROM users WHERE username='$($internal.username)'")) {
            $null = Api POST '/users' @{username=$internal.username;password=$password;roles=@($internal.role)} $admin
        }
    }
    # One atomic opening fixture. A durable audit marker prevents top-ups on rerun.
    # Documented opening book capital: 500,000,000 VND in MOCK-RESERVE.
    $reserveId = $people[3].accountId
    $ids = ($people | ForEach-Object { "'$($_.accountId)'::uuid" }) -join ','
    $seed = @"
BEGIN;
SELECT pg_advisory_xact_lock(734920261030);
DO `$fixture`$
BEGIN
  IF current_database() <> 'banking_docker_demo' OR current_user <> 'banking_demo' THEN RAISE EXCEPTION 'Wrong database'; END IF;
  IF NOT EXISTS (SELECT 1 FROM audit_logs WHERE action='LOCAL_MOCK_OPENING_V1' AND target_id='$reserveId') THEN
    PERFORM id FROM accounts WHERE id IN ($ids) ORDER BY id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM accounts WHERE id IN ($ids) AND (balance<>0 OR status<>'ACTIVE'))
       OR EXISTS (SELECT 1 FROM transaction_entries WHERE account_id IN ($ids)) THEN
      RAISE EXCEPTION 'Opening fixture requires unused zero-balance mock accounts';
    END IF;
    UPDATE accounts SET balance=500000000,version=version+1,updated_at=now() WHERE id='$reserveId';
"@
    foreach ($person in $people[0..2]) {
        $txId = [guid]::NewGuid().ToString()
        $seed += @"

    INSERT INTO transactions(id,reference,source_account_id,destination_account_id,amount,currency,type,status,description)
      VALUES ('$txId','OPN-$txId','$reserveId','$($person.accountId)',$($person.amount),'VND','OPENING','COMPLETED','Vốn mở sổ mô phỏng local');
    UPDATE accounts SET balance=balance-$($person.amount),version=version+1,updated_at=now() WHERE id='$reserveId';
    UPDATE accounts SET balance=balance+$($person.amount),version=version+1,updated_at=now() WHERE id='$($person.accountId)';
    INSERT INTO transaction_entries(transaction_id,account_id,direction,amount,currency,balance_after,created_at)
      SELECT '$txId',id,CASE WHEN id='$reserveId'::uuid THEN 'DEBIT' ELSE 'CREDIT' END,$($person.amount),'VND',balance,
        (SELECT created_at FROM transactions WHERE id='$txId')
      FROM accounts WHERE id IN ('$reserveId','$($person.accountId)');
"@
    }
    $seed += @"

    INSERT INTO audit_logs(actor_id,action,target_type,target_id,outcome,request_id,details)
      VALUES ((SELECT id FROM users WHERE username='docker.admin'),'LOCAL_MOCK_OPENING_V1','ACCOUNT','$reserveId','SUCCESS','local-mockdata-v1',
      '{"fixture":"local-mock-v1","openingReserveVnd":"500000000","purpose":"User-requested fictional local test data"}');
  END IF;
END;
`$fixture`$;
COMMIT;
"@
    $null = Sql $seed
    foreach ($person in $people[0..2]) {
        $person.token = (Api POST '/auth/login' @{username=$person.username;password=$password}).accessToken
    }
    # Real API transfers exercise audit, idempotency, outbox, fraud and notifications.
    $transfers = @(@{from=0;to=1;amount='1250000'},@{from=1;to=2;amount='750000'},@{from=2;to=0;amount='250000'},@{from=0;to=1;amount='100000000'})
    for ($i=0; $i -lt $transfers.Count; $i++) {
        $t=$transfers[$i]; $source=$people[$t.from]; $destination=$people[$t.to]
        if (-not (Sql "SELECT id FROM beneficiaries WHERE owner_customer_id='$($source.customerId)' AND destination_account_id='$($destination.accountId)' AND deleted_at IS NULL")) {
            $null = Api POST '/me/beneficiaries' @{destinationAccountId=$destination.accountId;nickname=$destination.name} $source.token
        }
        # Persistent transaction description guards reruns even after idempotency expires.
        $description = "MOCK-V1-$i - Chuyển tiền mẫu"
        if (-not (Sql "SELECT id FROM transactions WHERE source_account_id='$($source.accountId)' AND description='$description'")) {
            $null = Api POST '/transfers' @{sourceAccountId=$source.accountId;destinationAccountId=$destination.accountId;amount=$t.amount;currency='VND';description=$description} $source.token "local-mock-v1-$i"
        }
    }
    foreach ($person in $people[0..2]) {
        $accounts = Api GET '/me/accounts' $null $person.token
        if ($accounts.content.Count -ne 1) { throw 'Expected one mock customer account.' }
        [pscustomobject]@{username=$person.username;name=$person.name;accountNumber=$accounts.content[0].accountNumber;balance=$accounts.content[0].balance}
    }
    Write-Output "Credentials: $credentialPath"
} catch { throw }
