@echo off
setlocal enabledelayedexpansion
REM ═══════════════════════════════════════════════════════════
REM  serve.bat — start Cab Eye on http://localhost:8000
REM
REM  THIS IS NOT OPTIONAL.
REM
REM  Chrome will not remember a microphone permission for a page
REM  loaded from file://, because file:// has no origin to remember
REM  it against. The grant becomes one-shot, so every time the
REM  listener restarts — twice a second — you get another dialog.
REM  That is the "asking every 3 seconds" bug, and no amount of
REM  JavaScript can fix it.
REM
REM  localhost IS a secure context. Serving the folder is the whole
REM  fix. No certificate, no install.
REM ═══════════════════════════════════════════════════════════

cd /d "%~dp0"
set PORT=8000

echo.
echo   Cab Eye
echo   =======
echo.

REM ── Is the port already taken? Starting a second server on a busy
REM    port fails in a way that looks like the app is broken.
netstat -ano | findstr /r /c:"LISTENING.*:%PORT% " >nul 2>&1
if not errorlevel 1 (
  echo   Port %PORT% is already in use.
  echo.
  echo   Either a server is already running - just open
  echo       http://localhost:%PORT%/rig.html
  echo   or something else has the port. To find it:
  echo       netstat -ano ^| findstr :%PORT%
  echo.
  pause
  exit /b 1
)

REM ── Find something that can serve a folder. Checked in order of
REM    how likely it is to already be on a Windows machine.
set RUNNER=
where python >nul 2>&1 && set RUNNER=python -m http.server %PORT%
if "!RUNNER!"=="" ( where py     >nul 2>&1 && set RUNNER=py -m http.server %PORT% )
if "!RUNNER!"=="" ( where npx    >nul 2>&1 && set RUNNER=npx --yes serve -l %PORT% )
if "!RUNNER!"=="" ( where php    >nul 2>&1 && set RUNNER=php -S localhost:%PORT% )

if "!RUNNER!"=="" (
  echo   Could not find anything to serve the folder with.
  echo.
  echo   Tried: python, py, npx, php - none are on your PATH.
  echo.
  echo   Easiest fix: install Python from https://python.org
  echo   and tick "Add Python to PATH" during setup.
  echo.
  echo   If you have VS Code, the "Live Server" extension also works:
  echo   right-click rig.html and choose "Open with Live Server".
  echo.
  pause
  exit /b 1
)

echo   Serving this folder with: !RUNNER!
echo.
echo     Self test    http://localhost:%PORT%/selftest.html   ^<- start here
echo     Test rig     http://localhost:%PORT%/rig.html
echo     Rider only   http://localhost:%PORT%/rider.html
echo     Driver only  http://localhost:%PORT%/driver.html
echo.
echo   Use Google Chrome. Allow the microphone when it asks - once.
echo   Press Ctrl+C in this window to stop the server.
echo.

REM Open the self test rather than the rig: it says in plain words
REM whether the build works, instead of leaving you to infer it.
start "" "http://localhost:%PORT%/selftest.html"

!RUNNER!
