#!/usr/bin/env python3
"""
"Be the user": gives a rider account a month of ride history so the memory agent's
suggestions can be shown today, on a real phone, without weeks of real rides.

What it does
  1. Signs the rider in (the same phone number you use in the app) with the dev OTP.
  2. Feeds a four-week diary into that rider's memory through POST /me/memory/simulate —
     the same trip records, visits, hour histograms and aliases a completed ride produces.
  3. Optionally (--live-ride) books and drives one REAL multi-stop ride end to end through
     the public API — WAIT stop, boarding code re-checked, DROP stop, completion — so you can
     see the live path record memory too.
  4. Prints what the server now remembers and when to open the app to hear a suggestion.

Why a simulator: real rides are timestamped by the server when they happen and cannot be
backdated, and a habit ("most Monday mornings") only exists across weeks. The simulator is
dev-only and off by default; it never runs on a public server.

Needs a LOCAL backend started with:
    CABEYE_OTP_EXPOSE=true          (default locally: the OTP is returned as devCode)
    CABEYE_MEMORY_SIMULATOR=true    (turns on /me/memory/simulate)

Usage
    python scripts/seed_history.py --phone 9876543210
    python scripts/seed_history.py --phone 9876543210 --demo-now      # a habit at THIS weekday and time
    python scripts/seed_history.py --phone 9876543210 --live-ride     # plus one real multi-stop ride
    python scripts/seed_history.py --phone 9876543210 --forget        # "forget my history" first

The diary (Coimbatore, home in RS Puram), each week for --weeks weeks:
    Monday     08:30  Apollo Pharmacy (driver waits), then PSG College     <- a route habit
    Tue-Fri    08:35  PSG College                                          <- a place habit
    Wednesday  18:00  pick up Amma at Race Course, then Home               <- an evening route
    Sunday     16:00  Brookefields Mall
Spoken aliases are included ("medical shop", "college"), so misheard-name repair and
"take me to the medical shop" work too.

Standard library only.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

ZONE = ZoneInfo("Asia/Kolkata")

PLACES = {
    "psg":    {"name": "PSG College of Technology", "placeId": "seed-psg",    "latitude": 11.0247, "longitude": 77.0028, "spokenAs": "college"},
    "apollo": {"name": "Apollo Pharmacy",           "placeId": "seed-apollo", "latitude": 11.0120, "longitude": 76.9550, "spokenAs": "medical shop"},
    "race":   {"name": "Race Course",               "placeId": "seed-race",   "latitude": 11.0005, "longitude": 76.9760, "spokenAs": "race course"},
    "home":   {"name": "Home",                      "placeId": "seed-home",   "latitude": 11.0050, "longitude": 76.9500, "spokenAs": "home"},
    "mall":   {"name": "Brookefields Mall",         "placeId": "seed-mall",   "latitude": 11.0089, "longitude": 76.9606, "spokenAs": "brookefields"},
}
HOME = (11.0050, 76.9500)


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")

    def call(self, method, path, body=None, token=None, user=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if user:
            headers["X-User-Id"] = user
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=15) as r:
                raw = r.read().decode() or "{}"
                return r.status, json.loads(raw)
        except urllib.error.HTTPError as e:
            raw = e.read().decode() or "{}"
            try:
                return e.code, json.loads(raw)
            except ValueError:
                return e.code, {"error": raw[:200]}
        except urllib.error.URLError as e:
            sys.exit(f"Can't reach the backend at {self.base}: {e.reason}. Is it running?")

    def ok(self, method, path, body=None, token=None, what=""):
        status, out = self.call(method, path, body, token)
        if status >= 300:
            sys.exit(f"{what or path} failed ({status}): {out.get('error', out)}")
        return out


def sign_in(api, phone, role, name):
    status, out = api.call("POST", "/auth/otp", {"phone": phone, "role": role})
    if status == 429:
        sys.exit(f"{role}: {out.get('error')} Run again in a minute.")
    if status >= 300:
        sys.exit(f"{role} OTP failed ({status}): {out.get('error', out)}")
    code = out.get("devCode")
    if not code:
        sys.exit("The server did not return a devCode. Start the LOCAL backend with CABEYE_OTP_EXPOSE=true.")
    out = api.ok("POST", "/auth/verify", {"phone": phone, "role": role, "code": code, "name": name},
                 what=f"{role} sign-in")
    return out["token"], out["account"]["id"]


def stop(key, kind):
    return dict(PLACES[key], kind=kind)


def diary(weeks, demo_now):
    """Past trips, oldest first. Times are local (Asia/Kolkata)."""
    now = datetime.now(ZONE)
    trips = []

    def add(when, dest, stops=(), pickup=HOME):
        if when >= now:
            return
        trips.append({
            "at": int(when.timestamp() * 1000),
            "destination": dict(PLACES[dest]),
            "stops": list(stops),
            "rideType": "AUTO",
            "pickupLatitude": pickup[0] if pickup else None,
            "pickupLongitude": pickup[1] if pickup else None,
        })

    def clashes_with_now(when):
        # --demo-now owns this weekday around this time; ordinary diary trips there would
        # dilute the habit being demonstrated.
        if not demo_now or when.isoweekday() != now.isoweekday():
            return False
        a = when.hour * 60 + when.minute
        b = now.hour * 60 + now.minute
        return abs(a - b) <= 90

    start = (now - timedelta(days=7 * weeks)).replace(hour=0, minute=0, second=0, microsecond=0)
    for d in range(7 * weeks + 1):
        day = start + timedelta(days=d)
        dow = day.isoweekday()
        plan = []
        if dow == 1:
            plan.append((day.replace(hour=8, minute=30 + (d // 7) % 5), "psg", [stop("apollo", "WAIT")], HOME))
        elif dow in (2, 3, 4, 5):
            plan.append((day.replace(hour=8, minute=35), "psg", [], HOME))
        elif dow == 7:
            plan.append((day.replace(hour=16, minute=0), "mall", [], HOME))
        if dow == 3:
            psg = (PLACES["psg"]["latitude"], PLACES["psg"]["longitude"])
            plan.append((day.replace(hour=18, minute=0), "home", [stop("race", "PICKUP")], psg))
        for when, dest, stops, pickup in plan:
            if not clashes_with_now(when):
                add(when, dest, stops, pickup)

    if demo_now:
        # The same route on this weekday at this time, for each past week.
        for w in range(1, weeks + 1):
            add(now - timedelta(days=7 * w, minutes=3 * (w % 3)), "psg", [stop("apollo", "WAIT")], HOME)

    trips.sort(key=lambda t: t["at"])
    return trips


def live_ride(api, rider_token, driver_token):
    """One real multi-stop ride through the public API, exactly as the two apps drive it."""
    print("\nLive multi-stop ride:")
    ride = api.ok("POST", "/rides", {
        "destination": PLACES["psg"]["name"],
        "destinationPlaceId": PLACES["psg"]["placeId"],
        "destinationLatitude": PLACES["psg"]["latitude"],
        "destinationLongitude": PLACES["psg"]["longitude"],
        "spokenAs": "college",
        "rideType": "AUTO",
        "pickupLatitude": HOME[0], "pickupLongitude": HOME[1],
        "stops": [
            {"name": "Apollo Pharmacy", "placeId": "seed-apollo", "latitude": 11.0120, "longitude": 76.9550,
             "kind": "WAIT", "spokenAs": "medical shop"},
            {"name": "Gandhipuram", "kind": "DROP", "note": "my friend", "latitude": 11.0168, "longitude": 76.9665},
        ],
    }, token=rider_token, what="booking")
    rid = ride["rideId"]
    pharmacy, friend = (s["stopId"] for s in ride["stops"])
    print(f"  booked {rid} with {len(ride['stops'])} stops")

    api.ok("POST", f"/rides/{rid}/accept", {"etaMinutes": 3}, token=driver_token, what="accept")
    api.ok("POST", f"/rides/{rid}/arrived", token=driver_token, what="arrived")
    api.ok("POST", f"/rides/{rid}/code", {"matched": True}, token=rider_token, what="boarding code")
    api.ok("POST", f"/rides/{rid}/seated", token=driver_token, what="seated")
    api.ok("POST", f"/rides/{rid}/start", {"etaMinutes": 20}, token=driver_token, what="start")
    print("  picked up, code confirmed, trip started")

    api.ok("POST", f"/rides/{rid}/stops/{pharmacy}/arrived", token=driver_token, what="stop 1 arrived")
    status, out = api.call("POST", f"/rides/{rid}/stops/{pharmacy}/done", token=driver_token)
    assert status == 409, f"expected the car to be held at the WAIT stop, got {status}"
    print(f"  stop 1 (WAIT): driver can't leave yet -> \"{out.get('error')}\"")
    api.ok("POST", f"/rides/{rid}/code", {"matched": True}, token=rider_token, what="code again")
    api.ok("POST", f"/rides/{rid}/stops/{pharmacy}/done", token=driver_token, what="stop 1 done")
    print("  rider back, code heard again, stop 1 done")

    api.ok("POST", f"/rides/{rid}/stops/{friend}/arrived", token=driver_token, what="stop 2 arrived")
    api.ok("POST", f"/rides/{rid}/stops/{friend}/done", token=driver_token, what="stop 2 done")
    done = api.ok("POST", f"/rides/{rid}/complete", {"fareRupees": 180, "durationMinutes": 34},
                  token=driver_token, what="complete")
    print(f"  stop 2 (DROP) done; ride {done.get('phase')}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="http://localhost:8080", help="backend URL (local only)")
    ap.add_argument("--phone", required=True, help="the rider's 10-digit number, as used in the app")
    ap.add_argument("--name", default="Demo Rider")
    ap.add_argument("--weeks", type=int, default=4, help="weeks of history (2-12)")
    ap.add_argument("--demo-now", action="store_true", help="add a route habit at this weekday and time")
    ap.add_argument("--live-ride", action="store_true", help="also drive one real multi-stop ride")
    ap.add_argument("--driver-phone", default="9000000001", help="driver number for --live-ride")
    ap.add_argument("--forget", action="store_true", help="clear this rider's memory first")
    a = ap.parse_args()
    weeks = max(2, min(12, a.weeks))

    api = Api(a.base)
    token, rider_id = sign_in(api, a.phone, "RIDER", a.name)
    print(f"Signed in rider {rider_id}")

    if a.forget:
        api.ok("DELETE", "/me/memory", token=token, what="forget")
        print("Forgot previous history")

    trips = diary(weeks, a.demo_now)
    status, out = api.call("POST", "/me/memory/simulate", {"trips": trips}, token)
    if status == 404:
        sys.exit("The history simulator is off. Restart the LOCAL backend with CABEYE_MEMORY_SIMULATOR=true.")
    if status >= 300:
        sys.exit(f"Simulate failed ({status}): {out.get('error', out)}")
    print(f"Fed {out['recorded']} past trips ({weeks} weeks)")

    if a.live_ride:
        driver_token, _ = sign_in(api, a.driver_phone, "DRIVER", "Ravi")
        live_ride(api, token, driver_token)

    mem = api.ok("GET", "/me/memory?trips=300", token=token, what="read memory")
    print("\nWhat the server remembers:")
    for p in mem.get("places", []):
        aliases = ", ".join(p.get("aliases") or []) or "-"
        print(f"  {p['name']:<28} visits={p['visitCount']:<3} weekday={p.get('weekdayVisits', 0):<3} "
              f"weekend={p.get('weekendVisits', 0):<3} aliases: {aliases}")
    with_stops = sum(1 for t in mem.get("trips", []) if t.get("stops"))
    print(f"  trips={len(mem.get('trips', []))} (with stops: {with_stops})")

    now = datetime.now(ZONE)
    print("\nOn the phone (signed in as this rider, at home, app idle):")
    if a.demo_now:
        print(f"  now ({now:%A %H:%M}) -> \"Your usual route, like most {now:%A} "
              f"{'mornings' if 5 <= now.hour <= 11 else 'afternoons' if now.hour <= 16 else 'evenings' if now.hour <= 20 else 'nights'}: "
              f"Apollo Pharmacy, then PSG College of Technology. Shall I plan it?\"")
    print("  Monday 7:30-9:30   -> the pharmacy-then-college route")
    print("  Tue-Fri 7:35-9:35  -> \"Going to PSG College of Technology, like most weekday mornings?\"")
    print("  Wednesday ~18:00, from college -> pick up at Race Course, then Home")
    print("  Any time: \"take me to the medical shop\" books Apollo Pharmacy from the learned alias;")
    print("            \"my places\" lists them; \"forget my history\" clears everything.")
    print("  The phone refreshes memory on sign-in and after each ride; reopen the app to pick this up.")


if __name__ == "__main__":
    main()
