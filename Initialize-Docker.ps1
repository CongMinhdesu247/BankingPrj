# Generate local-only secrets once. Existing secrets are never overwritten.
$ErrorActionPreference = 'Stop'
$envFile = Join-Path $PSScriptRoot '.env'
if (Test-Path -LiteralPath $envFile) {
    throw '.env already exists. Keep it: it contains the passwords for your Docker database.'
}
function New-RandomBase64 {
    param([int]$Count = 48)
    $bytes = New-Object byte[] $Count
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [Convert]::ToBase64String($bytes)
}
$values = @(
    '# Local Docker demo secrets. Do not commit or share this file.'
    "DOCKER_DB_PASSWORD=$(New-RandomBase64)"
    "DOCKER_JWT_SECRET=$(New-RandomBase64 64)"
    "DOCKER_ADMIN_PASSWORD=$(New-RandomBase64)"
    'DOCKER_ALLOWED_ORIGINS=http://localhost:8090,http://127.0.0.1:8090'
)
[IO.File]::WriteAllLines($envFile, $values, [Text.UTF8Encoding]::new($false))
Write-Output 'Created .env. Admin username: docker.admin; password is DOCKER_ADMIN_PASSWORD in .env.'
Write-Output 'Start: docker compose up -d --build --wait --wait-timeout 300'
