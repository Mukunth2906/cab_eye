@echo off
REM Cab Eye needs a real origin so the rider and driver apps can talk
REM over BroadcastChannel. Double-clicking the HTML files will not work.

echo.
echo   Cab Eye is starting...
echo   Open  http://localhost:8000  in Google Chrome
echo   Press Ctrl+C in this window to stop.
echo.

start "" http://localhost:8000

python -m http.server 8000 2>nul
if errorlevel 1 (
  echo Python not found. Trying Node instead...
  npx --yes serve -l 8000
)
pause
