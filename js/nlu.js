/* ═══════════════════════════════════════════════════════════
   nlu.js — turning one spoken sentence into a booking.

   Two ideas live here, and both are research claims:

   1. FAST PATH — rules match most real utterances in microseconds.
      Report the fraction that hit the fast path; that is a result.

   2. AMBIGUITY BUDGET — ask the user to clarify only when being wrong
      would actually cost them something. Two candidates that tie on
      score but sit 400 m apart are not worth a spoken turn; two that
      sit 12 km apart are.
   ═══════════════════════════════════════════════════════════ */

window.CE = window.CE || {};

CE.nlu = (function(){

  const PLACES = [
    {n:"Anna Nagar East", lat:13.0878, lng:80.2183},
    {n:"Anna Nagar West", lat:13.0850, lng:80.1998},
    {n:"T Nagar",         lat:13.0418, lng:80.2341},
    {n:"Besant Nagar",    lat:13.0002, lng:80.2668},
    {n:"Adyar",           lat:13.0067, lng:80.2570},
    {n:"Velachery",       lat:12.9750, lng:80.2210},
    {n:"Guindy",          lat:13.0067, lng:80.2206},
    {n:"Nungambakkam",    lat:13.0569, lng:80.2425},
    {n:"Mylapore",        lat:13.0339, lng:80.2698},
    {n:"Egmore",          lat:13.0732, lng:80.2609},
    {n:"Chennai Central", lat:13.0827, lng:80.2755},
    {n:"Chennai Airport", lat:12.9941, lng:80.1709},
    {n:"Tambaram",        lat:12.9229, lng:80.1275},
    {n:"Porur",           lat:13.0374, lng:80.1575},
    {n:"Sholinganallur",  lat:12.9010, lng:80.2279}
  ];

  const SAMPLES = [
    "take me to Anna Nagar",
    "take me to Anna Nagar East",
    "I want to go to the airport",
    "book an auto to T Nagar",
    "drop me at Besant Nagar",
    "Velachery-ku poganum"
  ];

  const PATTERNS = [
    /(?:take|drop|bring)\s+me\s+(?:to|at)\s+(.+)/i,
    /(?:i\s+(?:want|need)\s+to\s+)?go\s+to\s+(.+)/i,
    /book\s+(?:an?\s+)?(?:auto|car|cab|bike|taxi)?\s*(?:to|for)\s+(.+)/i,
    /(?:^|\s)to\s+(.+)/i,
    /(.+?)\s*-?ku\s+poganum/i          // Tamil–English code-mix
  ];

  const DELTA   = 0.16;   // scores closer than this count as tied
  const DIVERGE = 1.5;    // km — below this, being wrong is cheap, so don't ask

  function parseIntent(text){
    const rideType = /\bbike\b/i.test(text) ? "bike"
                   : /\b(car|cab|taxi)\b/i.test(text) ? "car" : "auto";
    let q = null, fast = false;
    for(const p of PATTERNS){
      const m = text.match(p);
      if(m && m[1] && m[1].trim().length > 1){ q = m[1]; fast = true; break; }
    }
    if(!q) q = text;
    q = q.replace(/\b(please|now|the|a|an|auto|car|cab|taxi|bike|ride)\b/gi," ")
         .replace(/[^\w\s]/g," ").replace(/\s+/g," ").trim();
    return {q, rideType, fast};
  }

  function score(q, name){
    const a = q.toLowerCase().split(" ").filter(Boolean);
    const b = name.toLowerCase().split(" ").filter(Boolean);
    if(!a.length) return 0;
    let hit = 0;
    for(const w of a) if(b.some(x => x.startsWith(w) || w.startsWith(x))) hit++;
    return hit / Math.max(a.length, b.length);
  }

  function km(a, b){
    const R = 6371;
    const dLat = (b.lat-a.lat)*Math.PI/180, dLng = (b.lng-a.lng)*Math.PI/180;
    const s = Math.sin(dLat/2)**2 +
              Math.cos(a.lat*Math.PI/180)*Math.cos(b.lat*Math.PI/180)*Math.sin(dLng/2)**2;
    return 2*R*Math.asin(Math.sqrt(s));
  }

  /**
   * Resolve a spoken sentence.
   * Returns one of:
   *   {ok:false}                                  nothing recognised
   *   {ok:true, ask:false, place, ...}            book it
   *   {ok:true, ask:true,  candidates:[a,b], ...} ask which one
   */
  function resolve(text){
    const {q, rideType, fast} = parseIntent(text);
    const ranked = PLACES.map(p => ({p, s: score(q, p.n)})).sort((a,b) => b.s - a.s);

    if(!ranked.length || ranked[0].s < 0.28) return {ok:false, q, rideType, fast};

    const gap = ranked[0].s - (ranked[1] ? ranked[1].s : 0);
    const div = ranked[1] ? km(ranked[0].p, ranked[1].p) : 99;
    const tied = gap < DELTA;

    if(tied && div > DIVERGE){
      return {ok:true, ask:true, candidates:[ranked[0].p, ranked[1].p],
              q, rideType, fast, gap, div};
    }
    return {ok:true, ask:false, place:ranked[0].p, q, rideType, fast, gap, div, tied};
  }

  return {PLACES, SAMPLES, PATTERNS, DELTA, DIVERGE, parseIntent, score, km, resolve};
})();
