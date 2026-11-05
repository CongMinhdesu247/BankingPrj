$ErrorActionPreference='Stop'
$registrationRun=[guid]::NewGuid().ToString('N')
$registrationPg='banking-reg-test-pg-'+$registrationRun
$registrationRedis='banking-reg-test-redis-'+$registrationRun
$backendRoot = Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Force -Path (Join-Path $backendRoot 'target') | Out-Null
Push-Location -LiteralPath $backendRoot
try {
  docker run --rm -d --name $registrationPg --memory 192m -e POSTGRES_USER=schema_test -e POSTGRES_PASSWORD=isolated-registration-test -e POSTGRES_DB=banking_test -p 127.0.0.1::5432 postgres:18 | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Temporary PostgreSQL failed'}
  docker run --rm -d --name $registrationRedis --memory 64m -p 127.0.0.1::6379 redis:7-alpine | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Temporary Redis failed'}
  $registrationPgPort=((docker port $registrationPg 5432/tcp).Trim() -split ':')[-1]
  $registrationRedisPort=((docker port $registrationRedis 6379/tcp).Trim() -split ':')[-1]
  $registrationReady=$false
  for($i=0;$i -lt 30;$i++){docker exec $registrationPg pg_isready -U schema_test -d banking_test *> $null; if($LASTEXITCODE -eq 0){$registrationReady=$true;break};Start-Sleep -Milliseconds 300}
  if(-not $registrationReady){throw 'Temporary PostgreSQL not ready'}
  $env:TEST_DB_URL="jdbc:postgresql://127.0.0.1:$registrationPgPort/banking_test"
  $env:TEST_DB_USERNAME='schema_test';$env:TEST_DB_PASSWORD='isolated-registration-test'
  $env:REDIS_HOST='127.0.0.1';$env:REDIS_PORT=$registrationRedisPort;$env:REDIS_NAMESPACE="banking:registration-test:$registrationRun"
  $env:JWT_SECRET=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
  $env:BANKING_KAFKA_ENABLED='false';$env:BANKING_FRAUD_RECOVERY_ENABLED='false'
  $env:BANKING_REDIS_LOGIN_RATE_LIMIT='10000'
  $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4';$env:SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0'
  $env:MAVEN_OPTS='-Xms32m -Xmx256m'
  $registrationAgent=Get-ChildItem "$env:USERPROFILE/.m2/repository/org/mockito/mockito-core" -Recurse -Filter 'mockito-core-*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
  .\mvnw.cmd -q '-Dtest=RegistrationIT' "-DargLine=-Xms32m -Xmx256m -Duser.timezone=UTC -javaagent:$registrationAgent" test *> target/registration-it.log
  $registrationExit=$LASTEXITCODE
  Get-Content target/registration-it.log -Tail 30
  if($registrationExit -ne 0){throw 'Registration integration tests failed'}
} finally {
  try {
    docker stop $registrationPg $registrationRedis 2>$null | Out-Null
  } finally {
    Pop-Location
  }
}
