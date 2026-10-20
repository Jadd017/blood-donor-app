# Local testing only. Resets test data in the LOCAL blooddonor database:
# clears every user's last donation date, cancels open requests, and deletes
# all request notifications and pledges. Refuses to run against a non-local host.
#
# Usage: powershell -ExecutionPolicy Bypass -File dev\reset-test-data.ps1

$ErrorActionPreference = 'Stop'

$psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
$envFile = Join-Path $PSScriptRoot '..\server\.env'

if (-not (Test-Path $psql)) { throw "psql not found at $psql" }
if (-not (Test-Path $envFile)) { throw "server/.env not found at $envFile" }

# Read KEY=VALUE pairs from server/.env (ignores comments and blank lines).
$settings = @{}
foreach ($line in Get-Content $envFile) {
    $line = $line.Trim()
    if ($line -eq '' -or $line.StartsWith('#')) { continue }
    $eq = $line.IndexOf('=')
    if ($eq -lt 1) { continue }
    $settings[$line.Substring(0, $eq).Trim()] = $line.Substring($eq + 1).Trim().Trim('"', "'")
}

foreach ($key in 'DB_URL', 'DB_USER', 'DB_PASSWORD') {
    if (-not $settings[$key]) { throw "$key is missing from server/.env" }
}

# DB_URL looks like jdbc:postgresql://localhost:5432/blooddonor
if ($settings['DB_URL'] -notmatch '^jdbc:postgresql://(\[[^\]]+\]|[^:/?]+)(?::(\d+))?/([^?]+)') {
    throw "Can't parse DB_URL: $($settings['DB_URL'])"
}
$dbHost = $Matches[1].Trim('[', ']')
$dbPort = if ($Matches[2]) { $Matches[2] } else { '5432' }
$dbName = $Matches[3]

if (@('localhost', '127.0.0.1', '::1') -notcontains $dbHost.ToLower()) {
    Write-Host "REFUSING TO RUN: database host is '$dbHost', not localhost." -ForegroundColor Red
    Write-Host 'This script is for the local test database only.' -ForegroundColor Red
    exit 1
}

Write-Host "Resetting test data in $dbName on ${dbHost}:$dbPort as $($settings['DB_USER'])..."

$sql = @"
WITH changed AS (UPDATE users SET last_donation_date = NULL WHERE last_donation_date IS NOT NULL RETURNING 1)
SELECT 'Users with last_donation_date cleared: ' || count(*) FROM changed;
WITH changed AS (UPDATE blood_requests SET status = 'cancelled', closed_at = now() WHERE status = 'open' RETURNING 1)
SELECT 'Open blood requests cancelled:          ' || count(*) FROM changed;
WITH changed AS (DELETE FROM request_notifications RETURNING 1)
SELECT 'Request notifications deleted:          ' || count(*) FROM changed;
WITH changed AS (DELETE FROM pledges RETURNING 1)
SELECT 'Pledges deleted:                        ' || count(*) FROM changed;
"@

$sqlFile = [System.IO.Path]::GetTempFileName()
try {
    [System.IO.File]::WriteAllText($sqlFile, $sql)
    $env:PGPASSWORD = $settings['DB_PASSWORD']
    # One transaction: either everything is reset or nothing is.
    & $psql -X -q -t -A -v ON_ERROR_STOP=1 --single-transaction `
        -h $dbHost -p $dbPort -U $settings['DB_USER'] -d $dbName -f $sqlFile
    if ($LASTEXITCODE -ne 0) { throw "psql failed (exit code $LASTEXITCODE); nothing was changed." }
} finally {
    Remove-Item $sqlFile -ErrorAction SilentlyContinue
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
}

Write-Host 'Done.' -ForegroundColor Green
