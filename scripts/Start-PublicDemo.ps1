# Start after adding your tunnel setup locally. Never pass secrets or tokens on the command line.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $false)]
    [string]$PublicUrl = '',
    [ValidateSet('ngrok', 'cloudflared')]
    [string]$Provider = 'ngrok'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$envPath = Join-Path $projectRoot '.env'

if ([string]::IsNullOrWhiteSpace($PublicUrl) -and (Test-Path -LiteralPath $envPath)) {
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        if ($line -match '^DOCKER_ALLOWED_ORIGINS=(.*)$') {
            $saved = $matches[1].Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ -like 'https://*' } | Select-Object -First 1
            if ($saved) { $PublicUrl = $saved }
        }
    }
}
if ([string]::IsNullOrWhiteSpace($PublicUrl)) {
    throw 'Pass -PublicUrl https://your-domain.ngrok-free.dev (or add it to DOCKER_ALLOWED_ORIGINS in .env).'
}
if ($PublicUrl -notmatch '^https?://') {
    $PublicUrl = "https://$PublicUrl"
}

$address = $null
if (-not [Uri]::TryCreate($PublicUrl, [UriKind]::Absolute, [ref]$address) -or
    $address.Scheme -ne 'https' -or -not $address.IsDefaultPort -or
    $address.UserInfo -or $address.Query -or $address.Fragment -or
    $address.AbsolutePath -ne '/' -or
    $address.DnsSafeHost -notmatch '^[a-z0-9.-]+\.[a-z]{2,}$') {
    throw 'Enter a valid HTTPS origin URL (for example: https://your-subdomain.ngrok-free.app).'
}
if ($Provider -eq 'ngrok' -and $address.DnsSafeHost -notmatch '^[a-z0-9-]+\.ngrok(-free)?\.(app|dev)$') {
    throw 'Enter your assigned HTTPS dev domain from the ngrok dashboard (no path).'
}
$origin = $address.GetLeftPart([UriPartial]::Authority)
if (-not (Get-Command $Provider -ErrorAction SilentlyContinue)) {
    throw "Install $Provider first, then reopen PowerShell."
}

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'Initialize-Docker.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Failed to initialize .env.' }

$envPath = Join-Path $projectRoot '.env'
$lines = [IO.File]::ReadAllLines($envPath)
$originsSetting = "DOCKER_ALLOWED_ORIGINS=http://localhost:8090,http://127.0.0.1:8090,$origin"
$proxySetting = 'DOCKER_TRUST_PROXY_HEADERS=true'
$foundOrigins = $false
$foundProxy = $false

$updated = foreach ($line in $lines) {
    if ($line -match '^DOCKER_ALLOWED_ORIGINS=') {
        $originsSetting
        $foundOrigins = $true
    } elseif ($line -match '^DOCKER_TRUST_PROXY_HEADERS=') {
        $proxySetting
        $foundProxy = $true
    } else {
        $line
    }
}
if (-not $foundOrigins) { $updated = @($updated) + $originsSetting }
if (-not $foundProxy) { $updated = @($updated) + $proxySetting }
[IO.File]::WriteAllLines($envPath, [string[]]$updated, [Text.UTF8Encoding]::new($false))

Push-Location $projectRoot
try {
    & docker compose up -d --wait --wait-timeout 300
    if ($LASTEXITCODE -ne 0) { throw 'Docker services are not ready. Tunnel was not started.' }
    Write-Output "Publishing application at $origin with per-visitor proxy IP rate-limiting enabled."
    Write-Output 'Keep this terminal open. Press Ctrl+C to stop sharing.'
    if ($Provider -eq 'ngrok') {
        & ngrok http --url $origin http://127.0.0.1:8090
    } else {
        & cloudflared tunnel --url http://127.0.0.1:8090
    }
    if ($LASTEXITCODE -ne 0) { throw "$Provider exited with an error. Check your local account setup." }
} finally {
    Pop-Location
}
