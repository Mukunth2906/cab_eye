# Setup, running and troubleshooting

[← Back to the README](../README.md)

Everything needed to get the backend running on a PC and the app onto one or more Android
phones, plus how to share a build with someone who is not in the room.

All paths below are relative to the project folder (the one holding `README.md`,
`run-backend.ps1` and `install-app.ps1`).

---

## 1. One-time setup

### Software

| Need | Notes |
|---|---|
| **Android Studio** | Installed at `C:\Program Files\Android\Android Studio`. To install from scratch: <https://developer.android.com/studio>, accept every default, choose **Standard**. |
| **Java 17 or newer** | The system `java` on this PC is **11**, too old for Spring Boot 3 and the Android build. Android Studio ships its own (JBR, Java 21) at `C:\Program Files\Android\Android Studio\jbr`. `run-backend.ps1` and `install-app.ps1` find it by themselves. When you run `gradlew` by hand, point `JAVA_HOME` at it first (see below). |
| **adb** | `C:\Users\Prahalya\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| **Python 3** (optional) | Only for the demo-data scripts in `scripts/`. |
| **ngrok** (optional) | Only for sharing the app with phones that are not on your USB cable. |

Running `gradlew` by hand:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"      # PowerShell
```
```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"       # Git Bash
```

Without it, the build fails with *"Dependency requires at least JVM runtime version 17. This
build uses a Java 11 JVM."*

### Keys and secrets

None are required to run the app. Each one switches on an extra feature:

| File | Key | Turns on |
|---|---|---|
| `backend/cabeye-secrets.properties` | `cabeye.gemini.api-keys` | Gemini scene descriptions for the camera (explore and the ride camera), and the mind map's sentence reader. Without it the phone uses its on-device detector. |
| | `cabeye.grok.api-key` | Grok as the mind map's sentence reader (preferred over Gemini when both are set). |
| | `cabeye.admin.email` / `cabeye.admin.password` | The admin website login. |
| | `cabeye.telegram.bot-token`, `spring.mail.*` | SOS and urgent-feedback alerts by Telegram and email. |
| | `cabeye.razorpay.key-id` / `key-secret` | Real Razorpay payments. Blank = the built-in mock gateway. |
| | `cabeye.sms.twilio.*` or `cabeye.sms.fast2sms.api-key` | Real SMS sign-in codes. Blank = the code appears in the app as a pretend message. |
| `android/local.properties` | `GOOGLE_MAPS_API_KEY` | Google Places destination search and the map preview. Without it the app uses its offline Coimbatore atlas. See [GOOGLE-PLACES.md](GOOGLE-PLACES.md). |

Start from the templates: copy `backend/cabeye-secrets.example.properties` to
`backend/cabeye-secrets.properties`, and `android/local.properties.example` to
`android/local.properties`. Never commit or share the filled-in files. Restart the backend
after changing its secrets.

**Gemini keys**: get them free at <https://aistudio.google.com/apikey>. Put several,
comma-separated, to spread the free-tier rate limit. On start-up the backend logs
`GEMINI keys=N models=[…]`, and every answer logs `GEMINI ok model=… key#N`. A failure logs
Google's own reason, for example `HTTP 404 This model … is no longer available`. If models are
retired, set `cabeye.gemini.models` to ones your keys can use (the default is
`gemini-3.5-flash-lite,gemini-3.5-flash,gemini-2.5-flash`).

### Prepare each Android phone (once per phone)

1. **Developer options:** Settings → About phone → tap **Build number** 7 times (on Samsung:
   About phone → Software information). Enter your PIN.
2. **USB debugging:** Settings → System → Developer options → turn on **USB debugging**.
3. **Plug in and authorise:** connect with a USB cable that carries data (many charging-only
   cables don't, which is the most common reason a phone "won't connect"). On the phone's
   *"Allow USB debugging?"* dialog, tick **Always allow from this computer** → **Allow**. Set the
   USB mode to **File transfer** if your phone needs it.
4. **Check:** `adb devices` should list the phone as `device`. `unauthorized` means the dialog
   was not accepted: unplug, replug, tap Allow.

---

## 2. Run the backend

**Right-click `run-backend.ps1` → Run with PowerShell.** If Windows refuses ("running scripts is
disabled"):

```powershell
powershell -ExecutionPolicy Bypass -File .\run-backend.ps1
```

The first run downloads Gradle and the dependencies (2–5 minutes). Wait for
`Started CabEyeBackendApplication in … seconds` and leave the window open; `Ctrl+C` stops it.

Check it from the PC:

- <http://localhost:8080/health>: `{"status":"UP", …}`
- <http://localhost:8080/>: a test console (two tabs, connect as rider and as driver, and messages
  pass between them)
- <http://localhost:8080/admin>: the admin website (log in with the admin email and password
  from the secrets file)

Data is kept in `backend/data/` (an H2 database plus JSON tables). Delete the folder for a
clean start.

Useful switches (environment variables, set before starting):

| Variable | Effect |
|---|---|
| `CABEYE_OTP_EXPOSE=true` | Sign-in codes are returned to the app and printed in the log (the default when no SMS provider is set). |
| `CABEYE_MEMORY_SIMULATOR=true` | Enables `/me/memory/simulate`, used by `scripts/seed_demo.py` and `seed_history.py`. Local only. |
| `CABEYE_GEMINI_API_KEYS=k1,k2` | Same as `cabeye.gemini.api-keys`. |
| `CABEYE_FARE_AUTO_BASE`, `CABEYE_FARE_AUTO_PER_KM`, … | Fare rates (see [FARE-AND-FEEDBACK.md](FARE-AND-FEEDBACK.md)). |
| `CABEYE_DB_URL` | A PostgreSQL URL instead of the local H2 file. |

For Docker (PostgreSQL, Redis, several backend instances behind Nginx) see
[../DOCKER_SETUP.md](../DOCKER_SETUP.md). For AWS see [../aws-docker-setup.md](../aws-docker-setup.md).

---

## 3. Put the app on phones

### Fastest: `install-app.ps1`

With the backend running and phones plugged in: **right-click `install-app.ps1` → Run with
PowerShell.** It builds, installs to every connected phone, sets up the `localhost` tunnel on
each (see below), launches the app and prints a per-phone summary.

```
.\install-app.ps1                  install to EVERY connected device
.\install-app.ps1 -Serial ABC123   install to one device
.\install-app.ps1 -List            list devices and exit
.\install-app.ps1 -Release         build the shareable release APK instead of debug
.\install-app.ps1 -NoLogs          skip the log stream at the end
```

Exit codes: `0` all good, `1` setup problem or no devices, `2` at least one device failed.
Phones that are `unauthorized` or `offline` are skipped with a reason, never silently.

Two phones matter: the boarding code is two people on two phones checking each other, so the
full ride can't be tested on one device.

### From Android Studio

1. **Open** the `android` folder (not the project root).
2. Wait for Gradle sync. If it complains about Java: Settings → Build Tools → Gradle → **Gradle
   JDK = jbr-21**.
3. Pick the phone in the device menu and press **▶ Run**.

### From the command line

```powershell
cd android
.\gradlew.bat installDebug           # with JAVA_HOME set as above
```

### Reaching the backend: `adb reverse`

`localhost` on the phone is the phone itself. One command tunnels the phone's port 8080 down
the USB cable to the PC:

```powershell
adb reverse tcp:8080 tcp:8080
adb reverse --list                   # check it is active
```

Re-run it after unplugging the phone or rebooting either device (`install-app.ps1` does it for
you). No Wi-Fi, IP address or firewall rule needed.

### First launch

Allow the **microphone** (needed), **location** (pickup position, walks, the ride guide) and
**notifications**. Camera and contacts are asked for later, at the moment they are needed, with
a spoken explanation first.

---

## 4. Share with someone else (ngrok)

**Right-click `share-with-friends.ps1` → Run with PowerShell.** It:

1. starts the backend if it is not running;
2. opens an ngrok tunnel, giving the PC a public `https://` address;
3. **bakes that address into the APK** at build time;
4. copies the APK to your Desktop as `CabEye-MMDD-HHMM.apk`.

Whoever installs it never has to type a URL. Tell them: allow installing from an unknown source,
allow the microphone, then hold the screen and say where to go. For the driver side, on a
second phone: **Settings** (top right) → **DRIVER** → sign in → **GO ONLINE**.

One-time ngrok setup: download from <https://ngrok.com/download>, sign up (free), then
`ngrok config add-authtoken YOUR_TOKEN`.

> **Keep the windows open.** The tunnel dies with the window that started it.
> **The free ngrok address changes every restart.** Re-run the script and re-send the APK, or
> have people paste the new address into Settings → Backend address → **Test connection**
> (the result is spoken aloud) → Save. The app works out `wss://` from `https://` itself.

---

## 5. Logs

```powershell
adb logcat -s CABEYE_METRICS                         # timings, state changes, every decision
adb logcat -s CABEYE_METRICS:V CabEye.Stt:V CabEye.Tts:V CabEye.Audio:V
adb logcat -s CabEye.Explore CabEye.Vision CabEye.Camera  # explore and the camera
adb logcat -s CabEye.Dialogue CabEye.Memory          # the memory agents
adb logcat -s AndroidRuntime:E                       # crashes
```

A booking in `CABEYE_METRICS` looks like:

```
STATE Idle -> Listening
stage=MIC_RELEASED t=+2104ms
stage=SPEECH_FINAL t=+2530ms
CLARIFY_DECISION a="PSG College of Technology" b="PSG Hospitals" gap=0.410 divergenceKm=0.9 asked=false
STATE Resolving -> Confirming
RIDE_SUMMARY … taps=1 clarifications=0 | asr=426ms intent=2ms resolve=3ms tts=…ms total=…ms
```

Budgets (breaches are logged at WARN): ASR ≤ 800 ms, intent ≤ 300 ms, resolve ≤ 400 ms, first
TTS audio ≤ 300 ms, total ≤ 2000 ms.

---

## 6. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `adb devices` shows nothing | Charge-only cable, or wrong USB mode | Try another cable first; then USB notification → **File transfer** |
| `adb devices` shows `unauthorized` | RSA prompt not accepted | Unplug, replug, tap **Allow** + "Always allow". Still nothing: Developer options → **Revoke USB debugging authorizations**, replug |
| Gradle: *"requires at least JVM runtime version 17"* | System Java 11 | Set `JAVA_HOME` to Android Studio's `jbr` (section 1) |
| Backend: `Port 8080 was already in use` | Another backend still running | `Get-NetTCPConnection -LocalPort 8080 -State Listen`, close it, or change `server.port` |
| Backend log: `GEMINI all keys/models failed` | Bad key, retired model, or rate limit | Read the reason after each `HTTP …` in that line. `404 … no longer available`: set `cabeye.gemini.models`. `429`: add keys or wait a minute. `400 API key not valid`: replace the key |
| Explore says only coarse names ("an object"), no distances | Gemini is off or failed, so the on-device detector answered | Check `GET /me/vision` returns `{"enabled":true}` and the backend log above |
| App says "I need microphone permission" | Permission denied | Settings → Apps → Cab Eye → Permissions → Microphone → Allow |
| Explore never opens | Camera permission denied | Same place → Camera → Allow, then double-tap again |
| "Speech recognition isn't available" | No recogniser | Install/enable the **Google** app, or download on-device recognition for **English (India)** |
| Earcons but **no speech** | TTS voice data missing | Settings → Accessibility → Text-to-speech → install voice data; `adb logcat -s CabEye.Tts` |
| No sound at all | Media volume zero or audio focus lost | Turn media volume up; `adb logcat -s CabEye.Audio` |
| Volume keys only change the volume | They do: a **short** press is a volume step. **Hold** to talk; press **volume down three times quickly** for the mind map | |
| Double tap opens nothing | TalkBack is on (it keeps double taps for itself), or a ride is under way | With TalkBack, use the **📷 Explore** button or the screen's actions menu (swipe up-then-right) |
| "cancel" doesn't cancel | Said too early | The cancel window starts after the sentence ends |
| App installs then closes at once | A crash | `adb logcat -s AndroidRuntime:E`, read the top exception |
| Studio: "SDK location not found" | `local.properties` path wrong | Delete `android/local.properties` (keep your Maps key somewhere) and re-sync |

---

## 7. Versions

Pinned as a tested-together set.

| Android | | Backend | |
|---|---|---|---|
| Gradle | 8.11.1 | Gradle | 8.11.1 |
| Android Gradle Plugin | 8.7.3 | Spring Boot | 3.3.5 |
| Kotlin + Compose compiler plugin | 2.3.0 | Java | 17 (run by JBR 21) |
| Compose BOM | 2024.10.01 | Database | H2 (local) / PostgreSQL (Docker) |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 | Optional | Redis (multi-instance events) |
| CameraX | 1.4.2 | | |
| ML Kit | object detection 17.0.2, labeling 17.0.9, text 16.0.1 | | |
| Places SDK / Maps SDK | 5.3.0 / 20.0.0 | | |
