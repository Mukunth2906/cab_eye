# =====================================================================================
#  Cab Eye - build an APK your friends can install, pointing at YOUR laptop
#
#  WHAT THIS DOES, IN ORDER
#    1. starts your backend (if it is not already running)
#    2. starts an ngrok tunnel, giving your laptop a public https address
#    3. reads that address out of ngrok's local API
#    4. BAKES IT INTO THE APK at build time
#    5. copies the finished APK to your Desktop, ready to share
#
#  Step 4 is the whole point. Your friend installs the APK and it already knows where
#  your laptop is - they never open settings, never paste a URL, and never have to be
#  told one. A rider who cannot see the screen could not do any of that anyway.
#
#  USAGE
#      powershell -ExecutionPolicy Bypass -File D:\pw\share-with-friends.ps1
#
#  KEEP THIS WINDOW OPEN. The tunnel dies with it, and so does the app for everyone
#  holding that APK.
#
#  ONE-TIME SETUP
#      1. Download ngrok:   https://ngrok.com/download
#      2. Unzip ngrok.exe somewhere on your PATH, or next to this script
#      3. Free account -> copy your authtoken from the ngrok dashboard
#      4. Run once:  ngrok config add-authtoken YOUR_TOKEN_HERE
# =====================================================================================

[CmdletBinding()]
param(
    # Port the backend listens on. Must match server.port in application.properties.
    [int] $Port = 8080,

    # Build the release APK. Recommended for sharing: it is the one that behaves like a
    # real install, and it is the one where /debug/broadcast has to be proven absent.
    [switch] $DebugBuild,

    # Assume the backend is already running in another window; do not start one.
    [switch] $BackendAlreadyRunning
)

$ErrorActionPreference = 'Stop'

$androidDir = Join-Path $PSScriptRoot 'android'
$backendDir = Join-Path $PSScriptRoot 'backend'

Write-Host ''
Write-Host '  Cab Eye - build a shareable APK' -ForegroundColor Cyan
Write-Host '  -------------------------------' -ForegroundColor Cyan

# =====================================================================================
#  0. Find the tools
# =====================================================================================

$jdk = "$env:ProgramFiles\Android\Android Studio\jbr"
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    Write-Host ''
    Write-Host "  ERROR: Android Studio's Java not found at $jdk" -ForegroundColor Red
    Write-Host '  Install Android Studio, or edit the path at the top of this script.' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}
$env:JAVA_HOME = $jdk
$env:Path      = "$jdk\bin;$env:Path"

# ngrok: on PATH, or sitting next to this script.
$ngrok = (Get-Command ngrok.exe -ErrorAction SilentlyContinue).Source
if (-not $ngrok) {
    $local = Join-Path $PSScriptRoot 'ngrok.exe'
    if (Test-Path $local) { $ngrok = $local }
}
if (-not $ngrok) {
    Write-Host ''
    Write-Host '  ERROR: ngrok.exe not found.' -ForegroundColor Red
    Write-Host ''
    Write-Host '  One-time setup:' -ForegroundColor Yellow
    Write-Host '    1. Download from https://ngrok.com/download' -ForegroundColor Yellow
    Write-Host "    2. Put ngrok.exe on your PATH, or next to this script:" -ForegroundColor Yellow
    Write-Host "       $PSScriptRoot\ngrok.exe" -ForegroundColor Yellow
    Write-Host '    3. Sign up (free) and run:  ngrok config add-authtoken YOUR_TOKEN' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}
Write-Host "  ngrok  : $ngrok" -ForegroundColor Gray

# =====================================================================================
#  1. Backend
# =====================================================================================

function Test-BackendUp {
    try {
        $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/health" `
                                      -TimeoutSec 3 -UseBasicParsing
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

if (Test-BackendUp) {
    Write-Host "  Backend: already running on port $Port" -ForegroundColor Green
} elseif ($BackendAlreadyRunning) {
    Write-Host ''
    Write-Host "  ERROR: -BackendAlreadyRunning was passed but nothing answers on port $Port." -ForegroundColor Red
    Write-Host ''
    exit 1
} else {
    Write-Host ''
    Write-Host '  Starting the backend in a new window...' -ForegroundColor DarkGray

    # A separate window on purpose. The backend logs every WebSocket join, every ride
    # transition and every rejected debug call - during a demo that window is the single
    # most useful thing on the screen, and burying it in this script's output would lose it.
    #
    # CABEYE_DEBUG_TOKEN is generated fresh per run and passed down. The debug endpoint
    # fails CLOSED without it, and a tunnel is open to the internet here - this is exactly
    # the situation the token exists for.
    $debugToken = [guid]::NewGuid().ToString('N')
    $env:CABEYE_DEBUG_TOKEN = $debugToken

    $startCommand = @"
`$env:JAVA_HOME = '$jdk'
`$env:Path      = '$jdk\bin;' + `$env:Path
`$env:CABEYE_DEBUG_TOKEN = '$debugToken'
Set-Location '$backendDir'
Write-Host 'Cab Eye backend - keep this window open' -ForegroundColor Cyan
.\gradlew.bat bootRun --console=plain
"@
    Start-Process powershell -ArgumentList '-NoExit', '-Command', $startCommand | Out-Null

    Write-Host '  Waiting for it to come up (first run downloads Gradle, be patient)...' -ForegroundColor DarkGray
    $waited = 0
    while (-not (Test-BackendUp)) {
        Start-Sleep -Seconds 3
        $waited += 3
        if ($waited % 15 -eq 0) { Write-Host "    still waiting... ${waited}s" -ForegroundColor DarkGray }
        if ($waited -ge 300) {
            Write-Host ''
            Write-Host '  ERROR: the backend did not start within 5 minutes.' -ForegroundColor Red
            Write-Host '  Look at the other PowerShell window for the actual error.' -ForegroundColor Yellow
            Write-Host ''
            exit 1
        }
    }
    Write-Host "  Backend: up on port $Port" -ForegroundColor Green
    Write-Host "  Debug token: $debugToken" -ForegroundColor DarkGray
}

# =====================================================================================
#  2. ngrok
# =====================================================================================

# Kill any tunnel left over from a previous run. Two ngrok processes fight over the local
# API port (4040) and the second one's URL becomes unreadable - which would produce an APK
# baked with the WRONG address, and that failure is invisible until a friend installs it.
Get-Process ngrok -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 700

Write-Host ''
Write-Host '  Starting the ngrok tunnel...' -ForegroundColor DarkGray
Start-Process -FilePath $ngrok `
              -ArgumentList "http $Port --log=stdout" `
              -WindowStyle Minimized | Out-Null

# ngrok exposes its own state on http://127.0.0.1:4040/api/tunnels once it is ready.
$publicUrl = $null
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 800
    try {
        $tunnels = Invoke-RestMethod -Uri 'http://127.0.0.1:4040/api/tunnels' `
                                     -TimeoutSec 3 -ErrorAction Stop
        $https = $tunnels.tunnels | Where-Object { $_.public_url -like 'https://*' } |
                 Select-Object -First 1
        if ($https) { $publicUrl = $https.public_url; break }
    } catch {
        # Not up yet. Keep waiting.
    }
}

if (-not $publicUrl) {
    Write-Host ''
    Write-Host '  ERROR: could not read the ngrok tunnel URL.' -ForegroundColor Red
    Write-Host ''
    Write-Host '  Most likely cause: no authtoken configured. Run this once:' -ForegroundColor Yellow
    Write-Host '      ngrok config add-authtoken YOUR_TOKEN_HERE' -ForegroundColor Yellow
    Write-Host '  Get the token from https://dashboard.ngrok.com after signing up (free).' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '  You can also check http://127.0.0.1:4040 in a browser.' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}

# Trim any trailing slash before it reaches the build. The app normalises this too - see
# BackendUrl.normaliseBase - but an address that is already clean is one fewer thing to be
# wrong about when something does not work.
$publicUrl = $publicUrl.TrimEnd('/')

Write-Host ''
Write-Host "  PUBLIC URL: $publicUrl" -ForegroundColor Green
Write-Host "  Socket    : $($publicUrl -replace '^https://', 'wss://')/ws/ride" -ForegroundColor DarkGray
Write-Host ''

# Prove it end to end before spending three minutes on a build. If /health does not answer
# through the tunnel, nothing the app does later will either.
try {
    $check = Invoke-RestMethod -Uri "$publicUrl/health" -TimeoutSec 10 `
                               -Headers @{ 'ngrok-skip-browser-warning' = 'true' }
    Write-Host "  Tunnel test: OK - backend says '$($check.status)'" -ForegroundColor Green
} catch {
    Write-Host '  WARNING: the tunnel is up but /health did not answer through it.' -ForegroundColor Yellow
    Write-Host "  $($_.Exception.Message)" -ForegroundColor DarkYellow
    Write-Host '  Continuing anyway - it sometimes needs a few more seconds.' -ForegroundColor DarkYellow
}

# =====================================================================================
#  3. Build the APK with that URL baked in
# =====================================================================================

$task = if ($DebugBuild) { 'assembleDebug' } else { 'assembleRelease' }
$builtApk = if ($DebugBuild) {
    Join-Path $androidDir 'app\build\outputs\apk\debug\app-debug.apk'
} else {
    Join-Path $androidDir 'app\build\outputs\apk\release\app-release.apk'
}

Write-Host ''
Write-Host "  Building the APK with backendUrl=$publicUrl ..." -ForegroundColor DarkGray
Write-Host '  (this takes a couple of minutes the first time)' -ForegroundColor DarkGray
Write-Host ''

Push-Location $androidDir
try {
    # -PbackendUrl is read in app/build.gradle and written into BuildConfig.DEFAULT_BACKEND_URL.
    & .\gradlew.bat $task "-PbackendUrl=$publicUrl" --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host '  BUILD FAILED - see the errors above.' -ForegroundColor Red
        Write-Host ''
        exit 1
    }
} finally { Pop-Location }

if (-not (Test-Path $builtApk)) {
    Write-Host ''
    Write-Host "  ERROR: build succeeded but no APK at $builtApk" -ForegroundColor Red
    Write-Host ''
    exit 1
}

# =====================================================================================
#  4. Put it somewhere findable
# =====================================================================================

$stamp    = Get-Date -Format 'MMdd-HHmm'
$desktop  = [Environment]::GetFolderPath('Desktop')
$shareApk = Join-Path $desktop "CabEye-$stamp.apk"
Copy-Item $builtApk $shareApk -Force

$sizeMb = [math]::Round((Get-Item $shareApk).Length / 1MB, 1)

Write-Host ''
Write-Host '  =====================================================================' -ForegroundColor Green
Write-Host '   READY TO SHARE' -ForegroundColor Green
Write-Host '  =====================================================================' -ForegroundColor Green
Write-Host ''
Write-Host "   APK      : $shareApk" -ForegroundColor Green
Write-Host "   Size     : $sizeMb MB" -ForegroundColor Gray
Write-Host "   Points at: $publicUrl" -ForegroundColor Gray
Write-Host ''
Write-Host '   Send that file however you like - WhatsApp, Drive, email.' -ForegroundColor White
Write-Host ''
Write-Host '   Tell whoever installs it:' -ForegroundColor White
Write-Host '     - Android will warn about installing from an unknown source. Allow it.' -ForegroundColor Gray
Write-Host '     - Accept the microphone permission when the app opens.' -ForegroundColor Gray
Write-Host '     - Press and HOLD anywhere, then say where you want to go.' -ForegroundColor Gray
Write-Host ''
Write-Host '   For the driver side, on a second phone:' -ForegroundColor White
Write-Host '     - Tap the small dot at the TOP RIGHT five times' -ForegroundColor Gray
Write-Host '     - Settings -> DRIVER -> Done -> GO ONLINE' -ForegroundColor Gray
Write-Host ''
Write-Host '  ---------------------------------------------------------------------' -ForegroundColor Yellow
Write-Host '   KEEP THIS WINDOW AND THE TWO IT OPENED RUNNING.' -ForegroundColor Yellow
Write-Host '   Closing them kills the tunnel, and the app stops working for everyone.' -ForegroundColor Yellow
Write-Host ''
Write-Host '   The free ngrok URL CHANGES every restart. When it does, re-run this' -ForegroundColor Yellow
Write-Host '   script and re-send the new APK - or have people paste the new address' -ForegroundColor Yellow
Write-Host '   into Settings themselves.' -ForegroundColor Yellow
Write-Host '  ---------------------------------------------------------------------' -ForegroundColor Yellow
Write-Host ''

# Held open deliberately: this window owns the ngrok process it started.
Read-Host '  Press Enter to STOP the tunnel and exit'

Write-Host '  Stopping ngrok...' -ForegroundColor DarkGray
Get-Process ngrok -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Write-Host '  Tunnel closed. The backend window is still running; close it separately.' -ForegroundColor Cyan
Write-Host ''
