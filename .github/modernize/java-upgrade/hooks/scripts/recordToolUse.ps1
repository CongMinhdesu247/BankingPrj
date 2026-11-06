# Records run_in_terminal and appmod-* tool calls as JSONL without arguments or secrets.
$raw = [Console]::In.ReadToEnd()
if ([string]::IsNullOrWhiteSpace($raw)) { exit 0 }
try { $payload = $raw | ConvertFrom-Json -ErrorAction Stop } catch { exit 0 }
$toolName = [string]$payload.tool_name
if ($toolName -ne 'run_in_terminal' -and $toolName -notlike 'appmod-*') { exit 0 }
$sessionId = [string]$payload.session_id
if ($sessionId -notmatch '^[A-Za-z0-9_-]{1,100}$') { exit 0 }
$hooksDir = '.github\modernize\java-upgrade\hooks'
if (-not (Test-Path -LiteralPath $hooksDir)) { New-Item -ItemType Directory -Path $hooksDir -Force | Out-Null }
$safe = [ordered]@{ tool_name = $toolName; session_id = $sessionId } | ConvertTo-Json -Compress
[System.IO.File]::AppendAllText((Join-Path $hooksDir "$sessionId.json"), "$safe`n", [System.Text.UTF8Encoding]::new($false))
