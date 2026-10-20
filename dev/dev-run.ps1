# Local testing only. Builds and installs the debug app on every connected
# device, forwards device port 8080 to the local server (adb reverse), and
# launches the app.
#
# Usage: powershell -ExecutionPolicy Bypass -File dev\dev-run.ps1

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$activity = 'com.jadbarakat.blooddonor/.MainActivity'
$healthUrl = 'http://localhost:8080/health'

function Test-Server {
    try {
        $response = Invoke-WebRequest -Uri $healthUrl -UseBasicParsing -TimeoutSec 3
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Write-ServerWarning {
    Write-Host ''
    Write-Host '!!! WARNING: the server is NOT running (no answer from ' -ForegroundColor Yellow -NoNewline
    Write-Host $healthUrl -ForegroundColor Yellow -NoNewline
    Write-Host ').' -ForegroundColor Yellow
    Write-Host '!!! The app will not be able to reach the backend. Start it with: .\gradlew :server:run' -ForegroundColor Yellow
    Write-Host ''
}

# --- Java and adb ---------------------------------------------------------

$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
if (-not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { throw "No JDK found at $env:JAVA_HOME" }
Write-Host "JAVA_HOME = $env:JAVA_HOME"

# Find the SDK: local.properties, then ANDROID_HOME, then the default location.
$sdkDir = $null
$localProps = Join-Path $repoRoot 'local.properties'
if (Test-Path $localProps) {
    $sdkLine = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($sdkLine) { $sdkDir = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\' }
}
if (-not $sdkDir) { $sdkDir = $env:ANDROID_HOME }
if (-not $sdkDir) { $sdkDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdkDir 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

$serverUp = Test-Server
if (-not $serverUp) { Write-ServerWarning } else { Write-Host "Server is running ($healthUrl)." -ForegroundColor Green }

$stuck = Get-Process -Name adb -ErrorAction SilentlyContinue
if ($stuck) {
    Write-Host "Killing $(@($stuck).Count) adb.exe process(es)..."
    $stuck | Stop-Process -Force
    Start-Sleep -Seconds 1
}

Write-Host 'Starting adb...'
& $adb start-server | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'adb start-server failed' }

# --- Devices --------------------------------------------------------------

$deviceLines = & $adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne '' }
Write-Host 'Devices:'
$deviceLines | ForEach-Object { Write-Host "  $_" }

$serials = @()
foreach ($line in $deviceLines) {
    $parts = $line -split '\s+'
    if ($parts[1] -eq 'device') {
        $serials += $parts[0]
    } else {
        Write-Warning "Skipping $($parts[0]): state is '$($parts[1])' (unlock the phone and accept the USB debugging prompt)."
    }
}
if ($serials.Count -eq 0) { throw 'No usable devices connected.' }

# --- Build and install ----------------------------------------------------

Write-Host 'Running gradlew :app:installDebug...'
Push-Location $repoRoot
try {
    & .\gradlew.bat :app:installDebug
    if ($LASTEXITCODE -ne 0) { throw "installDebug failed (exit code $LASTEXITCODE)" }
} finally {
    Pop-Location
}

# --- Port forwarding and launch -------------------------------------------

$failed = @()
foreach ($serial in $serials) {
    Write-Host ''
    Write-Host "[$serial]" -ForegroundColor Cyan
    & $adb -s $serial reverse tcp:8080 tcp:8080 | Out-Host
    $reverseList = & $adb -s $serial reverse --list
    if ($reverseList -match 'tcp:8080\s+tcp:8080') {
        Write-Host '  adb reverse tcp:8080 -> tcp:8080 OK' -ForegroundColor Green
    } else {
        Write-Warning "  adb reverse is NOT set on $serial. reverse --list returned: $reverseList"
        $failed += $serial
    }
    & $adb -s $serial shell am start -n $activity | Out-Host
}

Write-Host ''
if ($failed.Count -gt 0) { Write-Warning "Port forwarding failed on: $($failed -join ', ')" }
if (-not $serverUp) { Write-ServerWarning }
Write-Host "Done: installed and launched on $($serials.Count) device(s)." -ForegroundColor Green
