# Cab Eye — next phase: what's new and how to test it

Covers: sign-in (rider voice + fingerprint, driver form + profile), the destination dialogue
"go back" fixes, the Preference-Memory Agent (visited places, time-of-travel suggestions,
misheard-place repair, learned aliases, confidence recalibration), optional voice feedback,
and the next journey (book now / schedule / done).

## 0. Build first

```powershell
cd backend
.\gradlew.bat test          # AuthEndpointTest, MemoryEndpointTest, FeedbackEndpointTest + existing
cd ..\android
.\gradlew.bat testDebugUnitTest   # AuthLogicTest, DialogueFlowTest, MemoryAgentTest, PostRideTest + existing
.\gradlew.bat assembleDebug
```

The pure logic tests (99 of them) were run while writing this. The Compose screens
(`RiderSignInSurface`, `DriverAccountScreen`, the new `RiderSurface` branches) and
`MainActivity` could not be compiled there — if the build reports an error, it will be in one
of those four files.

Backend data now lives in `backend/data/` (accounts, sessions, visited places, trips,
feedback). Delete that folder for a clean slate. It is git-ignored.

Mock OTP: while `CABEYE_OTP_EXPOSE` is unset/true, the code is returned to the app and read
automatically; it is also printed in the backend log (`OTP role=… code=…`).

## 1. Rider sign-in (voice)

| Do | Expect |
|---|---|
| Fresh install, open app | "Welcome to Cab Eye… Say your ten digit number now. Or say skip…" |
| Say "nine eight seven six five, four three two one zero" | Read back in two groups: "9 8 7 6 5. 4 3 2 1 0. Is that right?" |
| Say "no" | "Okay. Say your ten digit number again." |
| Say the number, then "yes" | "Sending a code… Code received. Checking it." → "What should I call you?" |
| Say "my name is Harshini" → "yes" | "You're all set, Harshini. Hold anywhere and say where you want to go." |
| Close and reopen app | "Welcome back, Harshini. Touch the fingerprint sensor…" → fingerprint → "Hello Harshini…" |
| Cancel the fingerprint prompt | "Not unlocked. Hold the screen to try again, or say different number…" |
| Say "skip" at the number question | Guest mode: "…I won't remember your places…" |
| On the ride screen, say "sign out" | Back to the sign-in screen |
| Settings → Account → SIGN OUT | Same, for either role |
| Helper panel: type the number / code / name and press OK | Same flow as speaking it |

## 2. Driver sign-in and profile

| Do | Expect |
|---|---|
| Settings → Driver role | Driver sign-in: phone → SEND CODE → code auto-filled (test mode) → VERIFY |
| First sign-in | Profile form. SAVE is disabled until name, vehicle model and plate are filled |
| Plate "tn38ab1234" | Saved as "TN 38 AB 1234" |
| Accept a ride | Rider hears *your* name and "Yellow Bajaj RE" (colour + model) |
| Tap your name at the top of the driver screen | Edit profile; shows trip count and rating |

## 3. Destination dialogue — going back

| Say | Expect |
|---|---|
| "anna" → (A or B?) "neither" / "none" / "no" / "go back" | "Okay, neither. Say the place again…" and the mic opens |
| "anna" → "the second one" | Books option B |
| near-miss "Did you mean X?" → "no" | "Okay, not X. Say the place again, or a nearby landmark." (not the failure ladder) |
| During the 5-second cancel window, "go back" | "Okay, not booking that. Where would you like to go?" — keeps auto/cab |
| Three misses in a row | Rung 3 offers "help" or "call support" and keeps listening |

## 4. Memory agent (sign in first; needs completed rides)

Complete two or three rides to the same place at roughly the same time of day (driver phone:
accept → arrived → seated → start → complete). Memory is written when a ride COMPLETES.

| Do | Expect |
|---|---|
| "my places" | "Your usual places are …" (most visited first) |
| Reopen the app at the usual time, from near the usual pickup | "It's 8 40 AM. Going to PSG College, like most weekday mornings? Say yes, or tell me another place." |
| → "yes" | Normal booking announcement + 5-second cancel window |
| → "no" | "Okay. Where would you like to go?" (previous step). Two "no"s = no more suggestions this session |
| Mumble a visited place ("brook fields") | "Did you mean Brookefields Mall, where you've been 2 times? Say yes, or no." |
| → "yes", then complete the ride | "brook fields" is learned; next time it books straight away: "…one of your usual places…" |
| Say only "college" at the usual time | Suggests *your* college (time of travel breaks the tie); at an unusual time it does not guess |
| Recogniser fails (NO_MATCH) at the usual time | "I didn't catch that clearly. Did you mean PSG College, like most weekday mornings?" (once per booking) |
| At the usual place already | No suggestion to go there |
| "forget my history" | "Done. I've forgotten your places and past trips." |

Recalibration: every yes/no is sent to `/me/memory/outcome`. More "no"s raise the confidence the
agent needs before speaking (up to 0.85); more "yes"es lower it (down to 0.40). A place
rejected 3 times and never accepted is not suggested unprompted again.

Logs: `adb logcat -s CabEye.Dialogue CabEye.Memory` — look for `MEMORY proactive`, `MEMORY rescue`,
`MEMORY direct`, `MEMORY accepted/rejected`.

## 5. After payment: feedback and the next journey

| Do | Expect |
|---|---|
| Pay the fare | Receipt, then "How was your ride with Karthik? Say a number from one to five, say report a problem, or say skip." |
| "five" | "Thank you. Would you like to book another ride now, schedule one for later, or are you done?" |
| "two" | "Sorry it wasn't good. What went wrong?" → your words → "I'll report this as a pickup problem: … Shall I send it?" |
| Report mentioning "unsafe" / "too fast" / "touched" | Filed as SAFETY, marked urgent (backend log `FEEDBACK_URGENT`), told to press SOS if in danger |
| "skip", or say nothing for 8 s | Straight to the next-journey question |
| Next journey → "book now" | "Where would you like to go?" |
| → "take me to Gandhipuram" | Books it directly |
| → "schedule for later" → "tomorrow at 8 30" → "college" → "yes" | "Scheduled for tomorrow at 8 30 AM. When it's time I'll tell you and book your ride…" |
| → "done", or silence | "Okay. Hold anywhere when you need a ride." |
| At the scheduled time | Notification "Time for your ride"; with the app open it says "It's time for your scheduled ride to …" and starts the normal booking with its cancel window |
| "my scheduled rides" / "cancel my scheduled ride" | Lists / clears them (never cancels a ride in progress) |
| "give feedback" later from idle | Feedback for the last finished ride |

Quick scheduling test: say "schedule a ride" → "in 2 minutes" → a place → "yes", then wait.
