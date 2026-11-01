param([string]$Distribution='Ubuntu')
$ErrorActionPreference='Stop'
if ($Distribution -notmatch '^[A-Za-z0-9._-]+$') { throw 'Invalid WSL distribution name.' }
& wsl.exe -d $Distribution -u root -- apt-get update -qq
if ($LASTEXITCODE -ne 0) { throw 'Could not refresh WSL package index.' }
& wsl.exe -d $Distribution -u root -- apt-get install -y openjdk-21-jre-headless curl
if ($LASTEXITCODE -ne 0) { throw 'Could not install Java 21/curl inside WSL.' }
$path=(Join-Path $PSScriptRoot 'Install-Kafka.sh').Replace('\','/')
$linux=(& wsl.exe -d $Distribution -- wslpath -u $path).Trim()
& wsl.exe -d $Distribution -u root -- bash $linux
if ($LASTEXITCODE -ne 0) { throw 'Kafka archive installation or SHA-512 verification failed.' }
