# =====================================================================================
#  Cab Eye - start the backend
#
#  Right-click this file -> "Run with PowerShell", or from a terminal:
#      powershell -ExecutionPolicy Bypass -File D:\pw\run-backend.ps1
#
#  Why this script exists: this machine's default `java` is version 11, which is too old
#  for Spring Boot 3 (needs 17+). Android Studio ships its own Java 21, so the script
#  finds that one and uses it for THIS PROCESS ONLY. Nothing about your system Java or
#  your JAVA_HOME is permanently changed.
# =====================================================================================

$ErrorActionPreference = 'Stop'

Write-Host ''
Write-Host '  Cab Eye backend' -ForegroundColor Cyan
Write-Host '  ---------------' -ForegroundColor Cyan

# --- 1. Find a JDK 17 or newer -------------------------------------------------------
# Checked in order of preference. The first two are Android Studio's bundled runtime,
# which is the intended one on this machine.
$candidates = @(
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
    "${env:ProgramFiles(x86)}\Android\Android Studio\jbr",
    "$env:ProgramFiles\Eclipse Adoptium\jdk-21",
    "$env:ProgramFiles\Eclipse Adoptium\jdk-17",
    "$env:ProgramFiles\Java\jdk-21",
    "$env:ProgramFiles\Java\jdk-17"
)

$jdk = $null
foreach ($c in $candidates) {
    if (Test-Path (Join-Path $c 'bin\java.exe')) { $jdk = $c; break }
}

# Widen the search if none of the usual spots matched.
if (-not $jdk) {
    $found = Get-ChildItem "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium" `
                -Directory -ErrorAction SilentlyContinue |
             Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
             Sort-Object Name -Descending | Select-Object -First 1
    if ($found) { $jdk = $found.FullName }
}

if (-not $jdk) {
    Write-Host ''
    Write-Host '  ERROR: No Java 17+ found.' -ForegroundColor Red
    Write-Host '  Expected Android Studio at:' -ForegroundColor Yellow
    Write-Host "      $env:ProgramFiles\Android\Android Studio\jbr" -ForegroundColor Yellow
    Write-Host '  Either install Android Studio, or install Temurin JDK 17 from' -ForegroundColor Yellow
    Write-Host '      https://adoptium.net/temurin/releases/?version=17' -ForegroundColor Yellow
    Write-Host ''
    Read-Host '  Press Enter to close'
    exit 1
}

$env:JAVA_HOME = $jdk
$env:Path      = "$jdk\bin;$env:Path"

# `java -version` writes to STDERR, not stdout — it always has. With
# $ErrorActionPreference = 'Stop' set at the top of this script, PowerShell turns that
# perfectly normal output into a terminating NativeCommandError and the script dies before
# it ever reaches bootRun. So the preference is relaxed for this one call and restored
# immediately, and the result is coerced to a plain string rather than left as an
# ErrorRecord.
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$verLine = (& "$jdk\bin\java.exe" -version 2>&1 | Select-Object -First 1 | Out-String).Trim()
$ErrorActionPreference = $previousErrorAction

Write-Host "  Java   : $verLine"
Write-Host "  From   : $jdk"

# --- 2. Sanity-check the Gradle wrapper ----------------------------------------------
$backend = Join-Path $PSScriptRoot 'backend'
if (-not (Test-Path (Join-Path $backend 'gradle\wrapper\gradle-wrapper.jar'))) {
    Write-Host ''
    Write-Host '  ERROR: gradle-wrapper.jar is missing from backend\gradle\wrapper\.' -ForegroundColor Red
    Write-Host '  See the "Gradle wrapper missing" row in README.md.' -ForegroundColor Yellow
    Read-Host '  Press Enter to close'
    exit 1
}

# --- 3. Warn early if port 8080 is already taken --------------------------------------
$busy = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    $procName = (Get-Process -Id $busy[0].OwningProcess -ErrorAction SilentlyContinue).ProcessName
    Write-Host ''
    Write-Host "  WARNING: port 8080 is already in use by '$procName' (PID $($busy[0].OwningProcess))." -ForegroundColor Yellow
    Write-Host '  The backend will fail to start. Close that program, or change server.port' -ForegroundColor Yellow
    Write-Host '  in backend\src\main\resources\application.properties.' -ForegroundColor Yellow
    Write-Host ''
}

# --- 4. Run ---------------------------------------------------------------------------
Write-Host ''
Write-Host '  Starting... first run downloads Gradle + dependencies (a few minutes).' -ForegroundColor DarkGray
Write-Host '  When you see "Started CabEyeBackendApplication", open:' -ForegroundColor DarkGray
Write-Host '      http://localhost:8080/health   <- should show JSON' -ForegroundColor Green
Write-Host '      http://localhost:8080/         <- WebSocket test console' -ForegroundColor Green
Write-Host '  Press Ctrl+C in this window to stop the server.' -ForegroundColor DarkGray
Write-Host ''

Push-Location $backend
try {
    & (Join-Path $backend 'gradlew.bat') bootRun --console=plain
} finally {
    Pop-Location
}

Write-Host ''
Write-Host '  Backend stopped.' -ForegroundColor Cyan
Read-Host '  Press Enter to close'
