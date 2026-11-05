# Start after adding your ngrok authtoken locally. Never pass the token to this script.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$PublicUrl
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$address = $null
if (-not [Uri]::TryCreate($PublicUrl, [UriKind]::Absolute, [ref]$address) -or
    $address.Scheme -ne 'https' -or -not $address.IsDefaultPort -or
    $address.UserInfo -or $address.Query -or $address.Fragment -or
    $address.AbsolutePath -ne '/' -or
    $address.DnsSafeHost -notmatch '^[a-z0-9-]+\.ngrok-free\.(app|dev)$') {
    throw 'Enter your assigned HTTPS dev domain from the ngrok dashboard (no path).'
}
$origin = $address.GetLeftPart([UriPartial]::Authority)
if (-not (Get-Command ngrok -ErrorAction SilentlyContinue)) {
    throw 'Install ngrok first, then reopen PowerShell.'
}
$envPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envPath)) { throw 'Run ./scripts/Initialize-Docker.ps1 first.' }
$lines = [IO.File]::ReadAllLines($envPath)
$setting = "DOCKER_ALLOWED_ORIGINS=http://localhost:8090,http://127.0.0.1:8090,$origin"
$found = $false
$updated = foreach ($line in $lines) {
    if ($line -match '^DOCKER_ALLOWED_ORIGINS=') { $setting; $found = $true } else { $line }
}
if (-not $found) { $updated = @($updated) + $setting }
[IO.File]::WriteAllLines($envPath, [string[]]$updated, [Text.UTF8Encoding]::new($false))
Push-Location $projectRoot
try {
    & docker compose up -d --wait --wait-timeout 300
    if ($LASTEXITCODE -ne 0) { throw 'Docker services are not ready. Tunnel was not started.' }
    Write-Output "Publishing demo at $origin. Keep this terminal open. Press Ctrl+C to stop sharing."
    & ngrok http --url $origin http://127.0.0.1:8090
    if ($LASTEXITCODE -ne 0) { throw 'ngrok exited with an error. Check your local account setup.' }
} finally { Pop-Location }
