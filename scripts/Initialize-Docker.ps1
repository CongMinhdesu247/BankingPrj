# Generate local-only secrets once. Existing non-empty secrets are never overwritten.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $projectRoot '.env'

function New-RandomBase64 {
    param([int]$Count = 48)
    $bytes = New-Object byte[] $Count
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [Convert]::ToBase64String($bytes)
}

$existing = @{}
$lines = New-Object System.Collections.Generic.List[string]
if (Test-Path -LiteralPath $envFile) {
    foreach ($line in [IO.File]::ReadAllLines($envFile)) {
        $lines.Add($line)
        if ($line -match '^\s*([^#=]+?)=(.*)$') {
            $existing[$matches[1].Trim()] = $matches[2].Trim()
        }
    }
}

$required = [ordered]@{
    'DOCKER_DB_PASSWORD'     = { New-RandomBase64 48 }
    'DOCKER_JWT_SECRET'      = { New-RandomBase64 64 }
    'DOCKER_ADMIN_PASSWORD'  = { New-RandomBase64 48 }
    'DOCKER_ALLOWED_ORIGINS' = { 'http://localhost:8090,http://127.0.0.1:8090' }
}

if (-not (Test-Path -LiteralPath $envFile)) {
    $values = @(
        '# Local Docker demo secrets. Do not commit or share this file.'
        "DOCKER_DB_PASSWORD=$(& $required['DOCKER_DB_PASSWORD'])"
        "DOCKER_JWT_SECRET=$(& $required['DOCKER_JWT_SECRET'])"
        "DOCKER_ADMIN_PASSWORD=$(& $required['DOCKER_ADMIN_PASSWORD'])"
        "DOCKER_ALLOWED_ORIGINS=$(& $required['DOCKER_ALLOWED_ORIGINS'])"
        'GEMINI_API_KEY='
        'GEMINI_MODEL=gemini-2.5-flash-lite'
    )
    [IO.File]::WriteAllLines($envFile, $values, [Text.UTF8Encoding]::new($false))
    Write-Output 'Created .env. Admin username: docker.admin; password is DOCKER_ADMIN_PASSWORD in .env.'
} else {
    $updated = $false
    foreach ($key in $required.Keys) {
        if (-not $existing.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($existing[$key])) {
            $newVal = & $required[$key]
            $replaced = $false
            for ($i = 0; $i -lt $lines.Count; $i++) {
                if ($lines[$i] -match "^\s*$([regex]::Escape($key))\s*=") {
                    $lines[$i] = "$key=$newVal"
                    $replaced = $true
                    break
                }
            }
            if (-not $replaced) {
                $lines.Add("$key=$newVal")
            }
            $updated = $true
        }
    }
    if ($updated) {
        [IO.File]::WriteAllLines($envFile, $lines, [Text.UTF8Encoding]::new($false))
        Write-Output 'Updated missing Docker secrets in .env while preserving existing values.'
    } else {
        Write-Output 'Using existing .env configuration.'
    }
}
