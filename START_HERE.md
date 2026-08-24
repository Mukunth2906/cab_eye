# Start here

## Rule 1

Never double-click the HTML files. Always go through `localhost`.
That is the whole reason the mic kept asking for permission.

---

## Step 1 — start the server

Double-click `serve.bat`

Or in PowerShell:

```
cd C:\Users\ELCOT\Desktop\cab_booking
python -m http.server 8000
```

Leave that window open. Closing it stops everything.

---

## Step 2 — check it works

Open **Chrome** and go to:

```
http://localhost:8000/selftest.html
```

Click these two buttons, in order:

1. **Test sound and speech** → you hear beeps, then a voice
2. **Start 25-second microphone test** → Chrome asks for the mic → click **Allow**

While it counts down, say this two or three times:

> **"Hey Cab, take me to Anna Nagar"**

---

## Step 3 — read one number

Look at the big number under **permission prompts**.

| It says | Meaning |
|---|---|
| **1** | Fixed. This is what you want. |
| **0** | Also fine — Chrome remembered from last time. |
| **2 or more** | Bug is back. Tell me. |

Everything else on that page is extra detail. This one number is the test.

---

## Step 4 — the demo

```
http://localhost:8000/rig.html
```

1. Headphones on
2. Right side (driver): press **Go online**, then **Auto: on**
3. Click once on the left side (rider)
4. Say **"Hey Cab, take me to Anna Nagar"**
5. It asks East or West → say **"east"**
6. Don't touch anything. Just listen.

`Rider taps` should stay at **0**.

---

## If something breaks

| Problem | Fix |
|---|---|
| Mic keeps asking again and again | You opened the file directly. Use `localhost:8000` |
| "Microphone blocked" | Padlock icon in address bar → Site settings → Microphone → **Allow** → reload |
| No sound at all | Click once anywhere on the page first, then try again |
| "This browser cannot hear you" | Use Chrome, not Firefox or Safari |
| Nothing heard when you speak | Padlock → Site settings → Microphone → choose the correct mic |
| Port 8000 already in use | A server is already running. Just open `localhost:8000` |

---

## Backend — skip it for now

Not needed. The app works fully without it.
Only if you have **JDK 21** installed:

```
cd backend
mvn spring-boot:run
```

Details are in `RUN.md`.
