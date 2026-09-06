# =====================================================================================
#  Cab Eye - build and install the app on every connected phone
#
#  USAGE
#      .\install-app.ps1                  install to EVERY connected device
#      .\install-app.ps1 -Serial ABC123   install to one device
#      .\install-app.ps1 -List            list devices and exit
#      .\install-app.ps1 -Release         build the shareable release APK instead of debug
#      .\install-app.ps1 -NoLogs          skip the log stream at the end
#
#  Or from a terminal:
#      powershell -ExecutionPolicy Bypass -File D:\pw\install-app.ps1
#
#  EXIT CODES
#      0   every targeted device installed successfully
#      1   setup problem (no adb, no JDK, build failed) or NO DEVICES ATTACHED
#      2   at least one device failed to install
#
#  The multi-device support is not a convenience. This app has two roles - rider and
#  driver - and the whole point of the boarding code is that two DIFFERENT people, on two
#  DIFFERENT phones, verify each other. It cannot be meaningfully tested on one device.
# =====================================================================================

[CmdletBinding()]
param(
    # Install to this serial only. Get serials from -List.
    [string] $Serial,

    # Print the device table and exit without building.
    [switch] $List,

    # Build the release APK (signed with the debug key) instead of the debug one.
    [switch] $Release,

    # Skip the logcat stream at the end.
    [switch] $NoLogs
)

$ErrorActionPreference = 'Stop'

$adb        = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$androidDir = Join-Path $PSScriptRoot 'android'

Write-Host ''
Write-Host '  Cab Eye - install to phones' -ForegroundColor Cyan
Write-Host '  ---------------------------' -ForegroundColor Cyan

# =====================================================================================
#  1. adb
# =====================================================================================

if (-not (Test-Path $adb)) {
    Write-Host ''
    Write-Host "  ERROR: adb not found at $adb" -ForegroundColor Red
    Write-Host '  Install Android Studio, or fix the path at the top of this script.' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}

# =====================================================================================
#  2. Enumerate devices
#
#  `adb devices -l` prints one line per device:
#      R58N12ABCDE   device product:a52q model:SM_A525F device:a52q transport_id:3
#      emulator-5554 device product:sdk_gphone64 ...
#      4C1B22XYZ     unauthorized
#      9A2F00PQR     offline
#
#  Anything not in state exactly `device` is unusable: `unauthorized` means the phone has
#  not accepted the USB debugging prompt, `offline` means the daemon lost it. Installing to
#  either fails with a confusing message several steps later, so they are filtered here and
#  reported by name - a skipped phone the user does not know about is worse than an error.
# =====================================================================================

function Get-AttachedDevices {
    $raw = & $adb devices -l 2>&1

    $devices = @()
    foreach ($line in $raw) {
        $text = "$line".Trim()

        # Skip the header, blank lines, and the daemon's startup chatter.
        if ($text -eq '')                              { continue }
        if ($text -like 'List of devices attached*')   { continue }
        if ($text -like '*daemon*')                    { continue }
        if ($text -like '*adb server*')                { continue }

        # serial<whitespace>state<whitespace>key:value key:value ...
        if ($text -notmatch '^(\S+)\s+(\S+)(\s+(.*))?$') { continue }

        $serialValue = $Matches[1]
        $state       = $Matches[2]
        $rest        = if ($Matches[4]) { $Matches[4] } else { '' }

        $model = ''
        if ($rest -match 'model:(\S+)') { $model = $Matches[1].Replace('_', ' ') }

        $devices += [PSCustomObject]@{
            Serial = $serialValue
            State  = $state
            Model  = $model
            Usable = ($state -eq 'device')
        }
    }
    return $devices
}

$allDevices = @(Get-AttachedDevices)
$usable     = @($allDevices | Where-Object { $_.Usable })
$unusable   = @($allDevices | Where-Object { -not $_.Usable })

# --- Report what was found, including what is being skipped and why -------------------
Write-Host ''
if ($allDevices.Count -eq 0) {
    Write-Host '  Devices: none' -ForegroundColor Red
} else {
    Write-Host "  Devices: $($allDevices.Count) attached, $($usable.Count) usable" -ForegroundColor Gray
    foreach ($d in $allDevices) {
        if ($d.Usable) {
            $label = if ($d.Model) { "$($d.Serial)  ($($d.Model))" } else { $d.Serial }
            Write-Host "    [ok]   $label" -ForegroundColor Green
        } else {
            Write-Host "    [skip] $($d.Serial)  - state '$($d.State)'" -ForegroundColor Yellow
            if ($d.State -eq 'unauthorized') {
                Write-Host "           Unlock the phone and tap Allow on the USB debugging prompt." -ForegroundColor DarkYellow
            } elseif ($d.State -eq 'offline') {
                Write-Host "           Unplug and replug, or run: adb kill-server" -ForegroundColor DarkYellow
            }
        }
    }
}

if ($List) {
    Write-Host ''
    exit 0
}

# =====================================================================================
#  3. Zero usable devices -> fail loudly, non-zero exit
#
#  Explicitly NOT a silent success. A script that prints nothing and returns 0 when it
#  installed nothing is how you end up demoing a build from three days ago.
# =====================================================================================

if ($usable.Count -eq 0) {
    Write-Host ''
    Write-Host '  ERROR: no usable devices attached. Nothing was installed.' -ForegroundColor Red
    Write-Host ''
    Write-Host '  Checklist:' -ForegroundColor Yellow
    Write-Host '    1. USB cable plugged in (must be a DATA cable, not charge-only)' -ForegroundColor Yellow
    Write-Host '    2. Settings > About phone > tap "Build number" 7 times' -ForegroundColor Yellow
    Write-Host '    3. Settings > Developer options > USB debugging ON' -ForegroundColor Yellow
    Write-Host '    4. Accept the "Allow USB debugging?" prompt on the phone' -ForegroundColor Yellow
    Write-Host '    5. Notification shade > USB notification > File Transfer' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}

# --- Narrow to one device if -Serial was given ----------------------------------------
$targets = $usable
if ($Serial) {
    $targets = @($usable | Where-Object { $_.Serial -eq $Serial })
    if ($targets.Count -eq 0) {
        Write-Host ''
        Write-Host "  ERROR: no usable device with serial '$Serial'." -ForegroundColor Red
        Write-Host '  Run with -List to see what is attached.' -ForegroundColor Yellow
        Write-Host ''
        exit 1
    }
}

Write-Host ''
Write-Host "  Installing to $($targets.Count) device(s)." -ForegroundColor Cyan

# =====================================================================================
#  4. Check every target can actually run the app before spending time on a build
# =====================================================================================

$tooOld = @()
foreach ($d in $targets) {
    $sdk = (& $adb -s $d.Serial shell getprop ro.build.version.sdk 2>$null)
    $sdk = "$sdk".Trim()
    if ($sdk -match '^\d+$' -and [int]$sdk -lt 26) {
        $tooOld += "$($d.Serial) (API $sdk)"
    }
}
if ($tooOld.Count -gt 0) {
    Write-Host ''
    Write-Host '  ERROR: these devices are below Android 8.0 (API 26), which this app requires:' -ForegroundColor Red
    $tooOld | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
    Write-Host ''
    exit 1
}

# =====================================================================================
#  5. Build
# =====================================================================================

$jdk = "$env:ProgramFiles\Android\Android Studio\jbr"
if (-not (Test-Path (Join-Path $jdk 'bin\java.exe'))) {
    Write-Host ''
    Write-Host "  ERROR: Android Studio's Java not found at $jdk" -ForegroundColor Red
    Write-Host '  This machine''s default java is too old for the Android Gradle plugin.' -ForegroundColor Yellow
    Write-Host ''
    exit 1
}
$env:JAVA_HOME = $jdk
$env:Path      = "$jdk\bin;$env:Path"

$gradleTask = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
$apkPath    = if ($Release) {
    Join-Path $androidDir 'app\build\outputs\apk\release\app-release.apk'
} else {
    Join-Path $androidDir 'app\build\outputs\apk\debug\app-debug.apk'
}

Write-Host ''
Write-Host "  Building ($gradleTask)..." -ForegroundColor DarkGray
Push-Location $androidDir
try {
    & .\gradlew.bat $gradleTask --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host '  BUILD FAILED - see the errors above.' -ForegroundColor Red
        Write-Host ''
        exit 1
    }
} finally { Pop-Location }

if (-not (Test-Path $apkPath)) {
    Write-Host ''
    Write-Host "  ERROR: the build reported success but no APK exists at:" -ForegroundColor Red
    Write-Host "    $apkPath" -ForegroundColor Red
    Write-Host ''
    exit 1
}

$apkMb = [math]::Round((Get-Item $apkPath).Length / 1MB, 1)
Write-Host "  APK    : $apkPath  ($apkMb MB)" -ForegroundColor Green

# =====================================================================================
#  6. Install to each device, recording the outcome per device
# =====================================================================================

$results = @()

foreach ($d in $targets) {
    $label = if ($d.Model) { "$($d.Serial) ($($d.Model))" } else { $d.Serial }
    Write-Host ''
    Write-Host "  --> $label" -ForegroundColor Cyan

    $output   = & $adb -s $d.Serial install -r $apkPath 2>&1
    $exitCode = $LASTEXITCODE
    $text     = ($output | Out-String).Trim()

    # adb install is not reliable about its exit code across versions: some builds return 0
    # while printing "Failure [INSTALL_FAILED_...]". Both signals are checked, so a failure
    # cannot be reported as a success.
    $ok = ($exitCode -eq 0) -and ($text -notmatch 'Failure')

    if ($ok) {
        Write-Host '      installed' -ForegroundColor Green

        # The localhost tunnel, per device. Harmless when the app points at ngrok instead,
        # and essential when it points at this PC over USB.
        & $adb -s $d.Serial reverse tcp:8080 tcp:8080 2>&1 | Out-Null
        Write-Host '      tunnel   : phone localhost:8080 -> this PC :8080' -ForegroundColor DarkGray

        & $adb -s $d.Serial shell am start -n com.cabeye.rider/.MainActivity 2>&1 | Out-Null
        Write-Host '      launched' -ForegroundColor DarkGray

        $results += [PSCustomObject]@{ Device = $label; Result = 'OK'; Detail = '' }
    } else {
        Write-Host '      FAILED' -ForegroundColor Red
        Write-Host "      $text" -ForegroundColor DarkRed

        $hint = ''
        if ($text -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
            $hint = 'signature mismatch - run: adb -s ' + $d.Serial + ' uninstall com.cabeye.rider'
            Write-Host "      Fix: $hint" -ForegroundColor Yellow
        } elseif ($text -match 'INSTALL_FAILED_INSUFFICIENT_STORAGE') {
            $hint = 'not enough free space on the device'
            Write-Host "      Fix: $hint" -ForegroundColor Yellow
        } elseif ($text -match 'INSTALL_FAILED_USER_RESTRICTED') {
            $hint = 'enable "Install via USB" in Developer options'
            Write-Host "      Fix: $hint" -ForegroundColor Yellow
        }

        $results += [PSCustomObject]@{ Device = $label; Result = 'FAILED'; Detail = $hint }
    }
}

# =====================================================================================
#  7. Per-device summary
# =====================================================================================

Write-Host ''
Write-Host '  Summary' -ForegroundColor Cyan
Write-Host '  -------' -ForegroundColor Cyan

foreach ($r in $results) {
    $colour = if ($r.Result -eq 'OK') { 'Green' } else { 'Red' }
    $line   = "    {0,-8} {1}" -f $r.Result, $r.Device
    Write-Host $line -ForegroundColor $colour
    if ($r.Detail) { Write-Host "             $($r.Detail)" -ForegroundColor DarkYellow }
}

$failed = @($results | Where-Object { $_.Result -ne 'OK' })

Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host "  $($failed.Count) of $($results.Count) device(s) failed." -ForegroundColor Red
    Write-Host ''
    exit 2
}

Write-Host "  All $($results.Count) device(s) installed." -ForegroundColor Green
Write-Host ''
Write-Host '  On each phone: accept the microphone permission prompt.' -ForegroundColor Yellow
Write-Host ''
Write-Host '  RIDER phone : press and HOLD anywhere, say "take me to Adyar"' -ForegroundColor Yellow
Write-Host '  DRIVER phone: tap the small dot at the top right 5 times ->' -ForegroundColor Yellow
Write-Host '                Settings -> DRIVER -> Done -> GO ONLINE' -ForegroundColor Yellow
Write-Host ''

if ($NoLogs) { exit 0 }

# =====================================================================================
#  8. Logs
#
#  Single-device only. `adb logcat` cannot merge two devices into one stream, and picking
#  one arbitrarily would silently hide the other phone - which during a two-phone test is
#  exactly the half you are trying to watch.
# =====================================================================================

if ($targets.Count -gt 1) {
    Write-Host '  Multiple devices installed, so logs are not streamed automatically.' -ForegroundColor DarkGray
    Write-Host '  Open a window per phone:' -ForegroundColor DarkGray
    foreach ($d in $targets) {
        Write-Host "      adb -s $($d.Serial) logcat -s CABEYE_METRICS:V CabEye.Socket:V CabEye.Api:V" -ForegroundColor DarkGray
    }
    Write-Host ''
    exit 0
}

$only = $targets[0].Serial
Write-Host '  Streaming logs (Ctrl+C to stop)...' -ForegroundColor DarkGray
Write-Host ''
& $adb -s $only logcat -c
& $adb -s $only logcat -s CABEYE_METRICS:V CabEye.Socket:V CabEye.Api:V CabEye.Dialogue:V `
                        CabEye.Stt:V CabEye.Tts:V CabEye.Audio:V CabEye.Driver:V AndroidRuntime:E
