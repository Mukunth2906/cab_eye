#!/usr/bin/env python3
"""
Demo accounts and data for trying every feature of Cab Eye on a real phone.

Creates (or refreshes) two accounts on a LOCAL backend:

    RIDER   Meena     90000 11111   emergency contact Priya
    DRIVER  Karthik   90000 22222   Bajaj auto, TN 37 BX 4412

and gives them a history:

  * four weeks of Meena's rides (fed through the dev-only history simulator), so the memory
    agent has habits to suggest:  weekday mornings to PSG College, Monday's pharmacy-then-
    college route, Saturday check-ups at KG Hospital, Wednesday's "pick up Amma at Race Course",
    Sunday at Brookefields Mall — and spoken names for them ("college", "hospital", "mall",
    "medical shop", "amma's place"); plus one habit at THIS weekday and time, so a suggestion
    is offered as soon as the app opens;
  * two saved routes: "Monday errands" and "Fetch Amma";
  * three REAL rides between Meena and Karthik, driven through the public API exactly as the
    two apps drive them — GPS-measured distance, a server-priced fare, a paid fare, ratings
    both ways, and one complaint ("driving too fast") that opens a case on the admin website.

The phone half — saved walks, personal notes (Ravi's tea shop, the temple bell, where Meena
meets Priya), ride cues, areas and past ride paths — lives on the phone only. Load it from the
app: Settings -> "Load demo walks & mind map" (debug builds). It uses the same place ids as
this script ("demo-kgh", "demo-psg" ...) so everything joins into one mind map.

Needs the LOCAL backend started with the history simulator on:
    PowerShell:  $env:CABEYE_MEMORY_SIMULATOR = "true"; .\\run-backend.ps1
and no SMS provider configured (the code is read from the reply).

Usage
    python scripts/seed_demo.py
    python scripts/seed_demo.py --base http://localhost:8080 --weeks 6

Standard library only.
"""

import argparse
import math
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from seed_history import Api, ZONE, sign_in  # noqa: E402

RIDER_PHONE, RIDER_NAME = "9000011111", "Meena"
DRIVER_PHONE, DRIVER_NAME = "9000022222", "Karthik"

# Real Coimbatore coordinates (the app's offline atlas). Ids shared with DemoData.kt.
PLACES = {
    "home":   {"name": "Home",                      "placeId": "demo-home",   "latitude": 11.0080, "longitude": 76.9502, "spokenAs": "home"},
    "psg":    {"name": "PSG College of Technology", "placeId": "demo-psg",    "latitude": 11.02481, "longitude": 77.00292, "spokenAs": "college"},
    "kgh":    {"name": "KG Hospital",               "placeId": "demo-kgh",    "latitude": 11.0005, "longitude": 76.97164, "spokenAs": "hospital"},
    "mall":   {"name": "Brookefields Mall",         "placeId": "demo-mall",   "latitude": 11.00887, "longitude": 76.9594, "spokenAs": "mall"},
    "race":   {"name": "Race Course",               "placeId": "demo-race",   "latitude": 11.0017, "longitude": 76.9713, "spokenAs": "amma's place"},
    "pharma": {"name": "Murugan Medicals",          "placeId": "demo-pharma", "latitude": 11.0062, "longitude": 76.9528, "spokenAs": "medical shop"},
}
HOME = (PLACES["home"]["latitude"], PLACES["home"]["longitude"])


def place(key, **extra):
    return dict(PLACES[key], **extra)


# ------------------------------------------------------------------------------------
#  Profiles
# ------------------------------------------------------------------------------------

def set_up_profiles(api, rider_token, driver_token):
    api.ok("PATCH", "/me/profile", {
        "name": RIDER_NAME,
        "preferredRideType": "AUTO",
        "speechRate": 1.0,
        "emergencyContactName": "Priya",
        "emergencyContactPhone": "9000033333",
        "memoryEnabled": True,
    }, token=rider_token, what="rider profile")
    api.ok("PATCH", "/me/profile", {
        "name": DRIVER_NAME,
        "vehicleType": "AUTO",
        "vehicleModel": "Bajaj RE auto",
        "vehiclePlate": "TN 37 BX 4412",
        "vehicleColour": "yellow and green",
        "licenceNumber": "TN37 20190012345",
        "languages": "Tamil, English",
    }, token=driver_token, what="driver profile")
    print(f"Profiles: rider {RIDER_NAME} (emergency contact Priya), driver {DRIVER_NAME} (Bajaj RE auto, TN 37 BX 4412)")


# ------------------------------------------------------------------------------------
#  Four weeks of history, through the dev simulator
# ------------------------------------------------------------------------------------

def diary(weeks):
    now = datetime.now(ZONE)
    trips = []

    def add(when, dest, stops=(), pickup=HOME):
        if when < now:
            trips.append({
                "at": int(when.timestamp() * 1000),
                "destination": place(dest),
                "stops": list(stops),
                "rideType": "AUTO",
                "pickupLatitude": pickup[0],
                "pickupLongitude": pickup[1],
            })

    start = (now - timedelta(days=7 * weeks)).replace(hour=0, minute=0, second=0, microsecond=0)
    psg = (PLACES["psg"]["latitude"], PLACES["psg"]["longitude"])
    for d in range(7 * weeks + 1):
        day = start + timedelta(days=d)
        dow = day.isoweekday()
        if dow == 1:
            add(day.replace(hour=8, minute=30), "psg", [place("pharma", kind="WAIT")])
        elif dow in (2, 3, 4, 5):
            add(day.replace(hour=8, minute=35 + d % 6), "psg")
        if dow == 3:
            add(day.replace(hour=18, minute=0), "home", [place("race", kind="PICKUP")], psg)
        if dow == 6:
            add(day.replace(hour=10, minute=0), "kgh")
        if dow == 7:
            add(day.replace(hour=16, minute=0), "mall")

    # A habit at this weekday and time, so the app offers it as soon as it opens.
    for w in range(1, weeks + 1):
        add(now - timedelta(days=7 * w, minutes=4 * (w % 3)), "kgh")

    trips.sort(key=lambda t: t["at"])
    return trips


def seed_history(api, rider_token, weeks):
    api.ok("DELETE", "/me/memory", token=rider_token, what="clear old history")
    status, out = api.call("POST", "/me/memory/simulate", {"trips": diary(weeks)}, rider_token)
    if status == 404:
        sys.exit("The history simulator is off. Restart the LOCAL backend with CABEYE_MEMORY_SIMULATOR=true:\n"
                 "    $env:CABEYE_MEMORY_SIMULATOR = \"true\"; .\\run-backend.ps1")
    if status >= 300:
        sys.exit(f"Simulate failed ({status}): {out.get('error', out)}")
    print(f"History: {out['recorded']} past rides over {weeks} weeks")

    for route in (
        {"name": "Monday errands", "rideType": "AUTO",
         "stops": [place("pharma", kind="WAIT")], "destination": place("psg")},
        {"name": "Fetch Amma", "rideType": "AUTO",
         "stops": [place("race", kind="PICKUP", note="Amma")], "destination": place("home")},
    ):
        api.ok("POST", "/me/memory/routes", route, token=rider_token, what=f"saved route {route['name']}")
    print("Saved routes: \"Monday errands\", \"Fetch Amma\"")


# ------------------------------------------------------------------------------------
#  Real rides between the two accounts
# ------------------------------------------------------------------------------------

def gps_path(a, b, fixes=40):
    """Points from a to b along a gentle curve, like a road; times spaced at a driving pace."""
    (la1, lo1), (la2, lo2) = a, b
    mid = ((la1 + la2) / 2, (lo1 + lo2) / 2)
    ctrl = (mid[0] - (lo2 - lo1) * 0.15, mid[1] + (la2 - la1) * 0.15)
    pts = []
    for i in range(fixes + 1):
        s = i / fixes
        lat = (1 - s) ** 2 * la1 + 2 * (1 - s) * s * ctrl[0] + s * s * la2
        lng = (1 - s) ** 2 * lo1 + 2 * (1 - s) * s * ctrl[1] + s * s * lo2
        pts.append((lat, lng))
    return pts


def km(a, b):
    r = 6371.0
    dlat, dlng = math.radians(b[0] - a[0]), math.radians(b[1] - a[1])
    h = math.sin(dlat / 2) ** 2 + math.cos(math.radians(a[0])) * math.cos(math.radians(b[0])) * math.sin(dlng / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def drive(api, rider, driver, dest, stops=(), method="UPI", rider_rating=5, rider_note=None, category=None,
          driver_rating=5, driver_note=None):
    body = {
        "destination": PLACES[dest]["name"],
        "destinationPlaceId": PLACES[dest]["placeId"],
        "destinationLatitude": PLACES[dest]["latitude"],
        "destinationLongitude": PLACES[dest]["longitude"],
        "spokenAs": PLACES[dest]["spokenAs"],
        "rideType": "AUTO",
        "pickupLatitude": HOME[0], "pickupLongitude": HOME[1],
        "stops": [{"name": PLACES[k]["name"], "placeId": PLACES[k]["placeId"],
                   "latitude": PLACES[k]["latitude"], "longitude": PLACES[k]["longitude"], "kind": kind}
                  for k, kind in stops],
    }
    ride = api.ok("POST", "/rides", body, token=rider, what="booking")
    rid = ride["rideId"]
    api.ok("POST", f"/rides/{rid}/accept", {"etaMinutes": 4}, token=driver, what="accept")
    api.ok("POST", f"/rides/{rid}/enroute", token=driver, what="enroute")
    for metres in (600, 250, 60):
        api.ok("POST", f"/rides/{rid}/location", {"distanceMeters": metres, "bearingDeg": 40}, token=driver, what="approach")
    api.ok("POST", f"/rides/{rid}/arrived", token=driver, what="arrived")
    api.ok("POST", f"/rides/{rid}/code", {"matched": True}, token=rider, what="boarding code")
    api.ok("POST", f"/rides/{rid}/seated", token=driver, what="seated")
    api.ok("POST", f"/rides/{rid}/start", {"etaMinutes": 15}, token=driver, what="start")

    # The meter: GPS fixes along the way, timed at a driving pace within the server's clock window.
    legs = [HOME] + [(PLACES[k]["latitude"], PLACES[k]["longitude"]) for k, _ in stops] + \
           [(PLACES[dest]["latitude"], PLACES[dest]["longitude"])]
    path = []
    for a, b in zip(legs, legs[1:]):
        path += gps_path(a, b, fixes=max(8, int(km(a, b) * 9)))
    t0 = int(time.time() * 1000) - 9 * 60_000
    step = (9 * 60_000) // max(1, len(path))
    stop_ids = [s["stopId"] for s in ride.get("stops", [])]
    for i, (lat, lng) in enumerate(path):
        api.ok("POST", f"/rides/{rid}/trip-location", {"lat": lat, "lng": lng, "at": t0 + i * step},
               token=driver, what="trip location")
    for sid, (_, kind) in zip(stop_ids, stops):
        api.ok("POST", f"/rides/{rid}/stops/{sid}/arrived", token=driver, what="stop arrived")
        if kind == "WAIT":
            api.ok("POST", f"/rides/{rid}/code", {"matched": True}, token=rider, what="code at stop")
        api.ok("POST", f"/rides/{rid}/stops/{sid}/done", token=driver, what="stop done")

    done = api.ok("POST", f"/rides/{rid}/complete", {}, token=driver, what="complete")
    order = api.ok("POST", f"/rides/{rid}/payment/order", token=rider, what="payment order")
    paid = api.ok("POST", f"/payments/{order['orderId']}/simulate", {"method": method}, token=rider, what="payment")

    fb = {"rating": rider_rating}
    if rider_note:
        fb["text"] = rider_note
    if category:
        fb["category"] = category
    api.ok("POST", f"/rides/{rid}/feedback", fb, token=rider, what="rider feedback")
    dfb = {"rating": driver_rating}
    if driver_note:
        dfb["text"] = driver_note
    api.ok("POST", f"/rides/{rid}/rider-feedback", dfb, token=driver, what="driver feedback")

    fare = done.get("fareRupees") or order.get("amountRupees")
    dist = done.get("tripDistanceMeters") or 0
    print(f"  {rid}: Home -> {PLACES[dest]['name']}{' via ' + ', '.join(PLACES[k]['name'] for k, _ in stops) if stops else ''}"
          f"  {dist / 1000:.1f} km, Rs {fare}, paid by {method} ({paid.get('status')}), rated {rider_rating}/5")


def real_rides(api, rider, driver):
    print("Real rides between Meena and Karthik:")
    drive(api, rider, driver, "kgh", method="UPI", rider_rating=5,
          rider_note="Karthik waited and walked me to the gate. Very kind.", driver_note="Ready on time.")
    drive(api, rider, driver, "mall", method="CARD", rider_rating=2, category="SAFETY",
          rider_note="The driver was going too fast near the flyover.", driver_rating=4)
    drive(api, rider, driver, "psg", stops=[("pharma", "WAIT")], method="UPI", rider_rating=4,
          rider_note="Waited at the medical shop without fuss.")


# ------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="http://localhost:8080", help="backend URL (local only)")
    ap.add_argument("--weeks", type=int, default=4, help="weeks of history (2-12)")
    ap.add_argument("--no-rides", action="store_true", help="skip the real rides between the two accounts")
    a = ap.parse_args()

    api = Api(a.base)
    rider, rider_id = sign_in(api, RIDER_PHONE, "RIDER", RIDER_NAME)
    driver, driver_id = sign_in(api, DRIVER_PHONE, "DRIVER", DRIVER_NAME)
    print(f"Accounts: rider {RIDER_NAME} {RIDER_PHONE} ({rider_id}), driver {DRIVER_NAME} {DRIVER_PHONE} ({driver_id})")

    set_up_profiles(api, rider, driver)
    seed_history(api, rider, max(2, min(12, a.weeks)))
    if not a.no_rides:
        real_rides(api, rider, driver)

    mem = api.ok("GET", "/me/memory?trips=300", token=rider, what="read memory")
    me = api.ok("GET", "/me", token=driver, what="driver account")
    stats = (me.get("account") or {}).get("driver") or {}
    print("\nWhat the server now knows:")
    for p in mem.get("places", []):
        print(f"  {p['name']:<28} visits={p['visitCount']:<3} says: {', '.join(p.get('aliases') or []) or '-'}")
    print(f"  driver {DRIVER_NAME}: {stats.get('completedTrips', 0)} trips, rating {stats.get('ratingAverage', 0):.1f}")

    print(f"""
On the phone
  Rider:   Settings -> Account -> Sign out, then sign in with {RIDER_PHONE} (the code fills in by itself).
           Settings -> "Load demo walks & mind map".
  Driver:  on a second phone, Settings -> DRIVER, sign in with {DRIVER_PHONE}.
  Admin:   http://localhost:8080/admin shows the rides, ratings and the "driving too fast" case.
""")


if __name__ == "__main__":
    main()
