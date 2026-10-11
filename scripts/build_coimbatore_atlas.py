"""
Builds the offline Coimbatore atlas: real places and area names from OpenStreetMap, bundled in
the app so it needs no paid service, no API key and no network for them.

Run once (and again whenever you want fresher data):

    python scripts/build_coimbatore_atlas.py

It asks the public Overpass API for the Coimbatore region in small tiles, politely (one request
at a time, a pause between, retries on busy servers), and writes:

    android/app/src/main/assets/atlas/coimbatore.json

Data (c) OpenStreetMap contributors, available under the Open Database Licence (ODbL).
The app credits OpenStreetMap whenever it reads place details aloud.
"""
import json
import math
import os
import sys
import time
import urllib.parse
import urllib.request

# Coimbatore city and around: Thudiyalur and Saravanampatti in the north, Madukkarai and
# Kinathukadavu road in the south, Vadavalli and Perur in the west, Sulur and the airport in the east.
SOUTH, WEST, NORTH, EAST = 10.86, 76.82, 11.13, 77.12
TILES = 4  # 4 x 4 tiles keeps each request small enough for a busy public server

SERVERS = [
    "https://overpass-api.de/api/interpreter",
    "https://z.overpass-api.de/api/interpreter",
]
USER_AGENT = "CabEye/0.1 atlas builder (accessible ride-hailing for blind riders; https://github.com/Mukunth2906/cab_eye)"

OUT = os.path.join(os.path.dirname(__file__), "..", "android", "app", "src", "main", "assets", "atlas", "coimbatore.json")

QUERY = """[out:json][timeout:90][bbox:{s},{w},{n},{e}];
(
  node["place"~"^(suburb|neighbourhood|quarter|village|hamlet|town|locality)$"]["name"];
  nwr["name"]["tourism"~"^(attraction|museum|viewpoint|zoo|theme_park|gallery|hotel)$"];
  nwr["name"]["historic"];
  nwr["name"]["railway"~"^(station|halt)$"];
  nwr["name"]["aeroway"="aerodrome"];
  nwr["name"]["amenity"~"^(bus_station|place_of_worship|hospital|clinic|college|university|school|marketplace|cinema|library|townhall|courthouse|police|post_office|bank)$"];
  nwr["name"]["shop"~"^(mall|supermarket|department_store)$"];
  nwr["name"]["leisure"~"^(park|stadium|sports_centre)$"];
  nwr["name"]["office"="government"];
);
out center tags;"""

AREA_PLACES = {"suburb", "neighbourhood", "quarter", "village", "hamlet", "town", "locality"}

# What people in Coimbatore actually call places the map knows by a formal name. Only names are
# added — each entry must match a place really in the OpenStreetMap data (optionally near a
# point, for names used more than once); nothing is placed on the map by hand.
ALIASES = [
    ("Arulmigu Subramaniyaswami Thirukovil", (11.046, 76.854), ["Marudhamalai temple", "Maruthamalai temple", "Marudhamalai Murugan temple"]),
    ("Perur Temple", None, ["Perur Patteeswarar temple", "Patteeswarar temple"]),
    ("Eachanari Vinayagar Temple", None, ["Eachanari temple", "Eachanari Pillaiyar temple"]),
    ("Koniamman Temple", None, ["Koniamman kovil", "Koniyamman temple"]),
    ("Coimbatore International Airport", None, ["airport", "Coimbatore airport", "Kovai airport"]),
    ("Coimbatore Junction", None, ["railway station", "Coimbatore railway station", "Kovai junction", "central station"]),
    ("Gandhipuram Central Bus Stand", None, ["central bus stand", "Gandhipuram bus stand"]),
    ("Gandhipuram Town Bus Stand", None, ["town bus stand"]),
    ("Ukkadam Bus Stand", None, ["Ukkadam bus stop"]),
    ("Singanallur Bus Stand", None, ["Singanallur bus stop"]),
    ("Kovai Medical Centre and Hospital", None, ["KMCH", "K M C H", "Kovai Medical Center"]),
    ("Coimbatore Medical College Hospital", None, ["government hospital", "GH", "CMCH", "Coimbatore GH"]),
    ("PSG College of Technology", None, ["PSG Tech", "PSG college"]),
    ("PSG College of Arts and Science", None, ["PSG Arts", "PSG arts college"]),
    ("Tamil Nadu Agricultural University", None, ["TNAU", "Agri university", "agricultural university"]),
    ("VOC Park", None, ["V O C park", "VOC park and zoo"]),
    ("Brookefields Mall", None, ["Brookefields", "Brookfields mall"]),
]


def apply_aliases(out):
    """Adds ALIASES to the places they name; reports any that match nothing."""
    for name, near, extra in ALIASES:
        hits = [p for p in out["places"] if p["n"] == name and
                (near is None or abs(p["la"] - near[0]) < 0.03 and abs(p["lo"] - near[1]) < 0.03)]
        if not hits:
            print(f"  alias target not in data, skipped: {name}")
        for p in hits:
            p["a"] = sorted(set(p.get("a", [])) | set(extra))
    return out


def fetch(s, w, n, e):
    data = urllib.parse.urlencode({"data": QUERY.format(s=s, w=w, n=n, e=e)}).encode()
    for attempt in range(6):
        server = SERVERS[attempt % len(SERVERS)]
        try:
            req = urllib.request.Request(server, data=data, headers={"User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=120) as r:
                body = r.read().decode("utf-8")
            if body.lstrip().startswith("{"):
                return json.loads(body)["elements"]
            print(f"    busy page from {server}, retrying", flush=True)
        except Exception as ex:  # 429 / 504 / timeouts are normal on public servers
            print(f"    {server}: {ex}, retrying", flush=True)
        time.sleep(10 * (attempt + 1))
    raise SystemExit("Overpass did not answer; try again later.")


def types_of(t):
    """OSM tags -> the Google-style type names the app already says ("a temple", "a railway station")."""
    out = []
    amenity = t.get("amenity", "")
    if amenity == "place_of_worship":
        out += {"hindu": ["hindu_temple"], "christian": ["church"], "muslim": ["mosque"]}.get(t.get("religion", ""), [])
        out.append("place_of_worship")
    out += {
        "hospital": ["hospital"], "clinic": ["clinic"], "marketplace": ["market"], "bus_station": ["bus_station"],
        "university": ["university"], "college": ["university"], "school": ["school"], "cinema": ["cinema"],
        "library": ["library"], "townhall": ["government"], "courthouse": ["government"], "police": ["police"],
        "post_office": ["post_office"], "bank": ["bank"],
    }.get(amenity, [])
    if t.get("railway") in ("station", "halt"):
        out.append("train_station")
    if t.get("aeroway") == "aerodrome":
        out.append("airport")
    out += {"park": ["park"], "stadium": ["stadium"], "sports_centre": ["stadium"]}.get(t.get("leisure", ""), [])
    if t.get("shop") in ("mall", "department_store"):
        out.append("shopping_mall")
    if t.get("shop") == "supermarket":
        out.append("supermarket")
    if t.get("tourism") == "museum":
        out.append("museum")
    if t.get("tourism") == "hotel":
        out.append("hotel")
    elif "tourism" in t or "historic" in t:
        out.append("tourist_attraction")
    if t.get("office") == "government":
        out.append("government")
    return out


def score(t):
    """How well known a place is, read from the map itself (OpenStreetMap has no ratings).
    Same scale as the backend's OsmService, extended for places used only in search."""
    s = 100 if ("wikidata" in t or "wikipedia" in t) else 0
    tourism, amenity = t.get("tourism", ""), t.get("amenity", "")
    if t.get("aeroway") == "aerodrome":
        s += 70
    elif tourism and tourism != "hotel":
        s += 60
    elif "historic" in t:
        s += 55
    elif t.get("railway") == "station":
        s += 50
    elif t.get("shop") in ("mall", "department_store") or t.get("leisure") == "stadium":
        s += 40
    elif t.get("leisure") == "park" or amenity in ("university", "college"):
        s += 35
    elif amenity == "bus_station":
        s += 30
    elif amenity == "marketplace":
        s += 25
    elif amenity == "place_of_worship":
        s += 20
    elif amenity == "hospital":
        s += 15
    else:
        s += 5
    return s


def main():
    # "--aliases-only": re-apply ALIASES to the existing file without downloading again.
    if "--aliases-only" in sys.argv:
        with open(OUT, encoding="utf-8") as f:
            out = apply_aliases(json.load(f))
        with open(OUT, "w", encoding="utf-8") as f:
            json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
        print(f"aliases applied to {OUT}")
        return
    areas, places, seen = {}, {}, set()
    dlat, dlng = (NORTH - SOUTH) / TILES, (EAST - WEST) / TILES
    for i in range(TILES):
        for j in range(TILES):
            s, w = SOUTH + i * dlat, WEST + j * dlng
            n, e = s + dlat, w + dlng
            print(f"tile {i * TILES + j + 1}/{TILES * TILES}  {s:.3f},{w:.3f} - {n:.3f},{e:.3f}", flush=True)
            for el in fetch(round(s, 4), round(w, 4), round(n, 4), round(e, 4)):
                key = (el["type"], el["id"])
                if key in seen:
                    continue
                seen.add(key)
                t = el.get("tags", {})
                name = (t.get("name:en") or t.get("name") or "").strip()
                c = el.get("center", el)
                # The app speaks English: a name with no Latin letters can't be said or matched.
                if not name or "lat" not in c or not any("a" <= ch.lower() <= "z" for ch in name):
                    continue
                lat, lng = round(c["lat"], 5), round(c["lon"], 5)
                if t.get("place") in AREA_PLACES:
                    areas[(name.lower(), round(lat, 2), round(lng, 2))] = {"n": name, "k": t["place"], "la": lat, "lo": lng}
                    continue
                aliases = sorted({a.strip() for k in ("alt_name", "old_name", "short_name", "official_name")
                                  for a in t.get(k, "").split(";") if a.strip() and a.strip() != name})
                p = {"n": name, "la": lat, "lo": lng, "ty": types_of(t), "s": score(t)}
                if aliases:
                    p["a"] = aliases
                if t.get("wikidata"):
                    p["w"] = t["wikidata"]
                places[(name.lower(), round(lat, 3), round(lng, 3))] = p
            time.sleep(3)  # be polite to a shared public server

    out = {
        "region": "Coimbatore",
        "bbox": [SOUTH, WEST, NORTH, EAST],
        "built": time.strftime("%Y-%m-%d"),
        "attribution": "Data (c) OpenStreetMap contributors, ODbL",
        "areas": sorted(areas.values(), key=lambda a: a["n"]),
        "places": sorted(places.values(), key=lambda p: (-p["s"], p["n"])),
    }
    apply_aliases(out)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print(f"wrote {OUT}: {len(out['areas'])} areas, {len(out['places'])} places, "
          f"{os.path.getsize(OUT) // 1024} KB")


if __name__ == "__main__":
    sys.exit(main())
