/* ═══════════════════════════════════════════════════════════
   wake.js — "Hey Cab". The trigger, instead of a tap.

   WHY A WAKE WORD AND NOT A BUTTON

   A tap-to-speak button asks the user to do the one thing this app
   exists to avoid: find a target on a flat sheet of glass. Every
   other trigger we considered has the same flaw or worse —

     hardware key      the browser cannot see volume or power keys;
                       only a native Android AccessibilityService can
     shake             works, but fires in a moving auto-rickshaw
     long-press screen still a touch, and TalkBack claims the gesture
     spacebar          fine on a laptop, absent on a phone

   A wake word is the only trigger that costs nothing to reach. The
   phone can be in a pocket, the screen can be off, and the user's
   hands can be on a cane.

   HOW IT WORKS HERE

   One microphone, two moods. Asleep, the standing recogniser is
   running but every transcript is discarded unless it opens with the
   wake phrase. Awake, transcripts go to the app. There is no second
   recogniser and no second permission prompt — sleeping is a
   filtering decision, not a different audio path.

   TWO SHAPES OF UTTERANCE, BOTH ACCEPTED

     "Hey Cab."                      → wakes, asks "Yes?", then listens
     "Hey Cab, take me to Adyar."    → wakes and books, one breath,
                                        no round trip

   The second form is the fast one and the one to report: a complete
   booking from a single utterance with zero taps and zero turns.

   MATCHING A PHRASE THE RECOGNISER GETS WRONG

   Speech recognisers hear "hey cab" as "hey cap", "a cab", "hey gab",
   "hey cub", "hakab". A literal string compare rejects all of those
   and the app appears deaf for no visible reason. So the match is
   edit-distance based over a small set of canonical forms, applied
   only near the start of the utterance, where a wake word belongs.
   ═══════════════════════════════════════════════════════════ */

window.CE = window.CE || {};

CE.wake = (function(){

  /* Canonical wake phrases, spaces removed. Everything is compared
     against these with a small edit-distance budget. */
  const PHRASES = [
    "heycab", "hicab", "okcab", "okaycab", "hellocab",
    "heycabby", "cabeye", "heycabeye", "heycabi"
  ];

  /* How many characters may be wrong. Longer phrases can absorb more
     damage before the match becomes a coincidence. */
  function budget(s){ return s.length >= 8 ? 2 : 1; }

  /* A wake word sits at the front of a sentence. Scanning the whole
     utterance would let "call me a cab" trigger a wake mid-ride. */
  const SCAN_TOKENS = 4;

  /* Sleep again after this long with nothing said, so an accidental
     wake does not leave the app listening for a booking forever. */
  const AWAKE_MS = 12000;

  let armed = false;          // the sleeping listener is running
  let awake = false;          // a voice session is open
  let onWake = null;          // (payload|null) => void
  let onArmChange = null;
  let onHeard = null;         // every discarded transcript, for the log
  let sleepTimer = null;
  let lastWake = 0;

  /* ── fuzzy matching ─────────────────────────────────────── */

  function norm(s){
    return (s || "").toLowerCase()
      .replace(/[^a-z0-9\s]/g, " ")
      .replace(/\s+/g, " ")
      .trim();
  }

  /** Levenshtein, capped — we never care how far past the budget. */
  function dist(a, b, cap){
    if(Math.abs(a.length - b.length) > cap) return cap + 1;
    let prev = Array.from({length: b.length + 1}, (_, j) => j);
    for(let i = 1; i <= a.length; i++){
      const cur = [i];
      let best = i;
      for(let j = 1; j <= b.length; j++){
        const c = a[i-1] === b[j-1] ? 0 : 1;
        cur[j] = Math.min(prev[j] + 1, cur[j-1] + 1, prev[j-1] + c);
        if(cur[j] < best) best = cur[j];
      }
      if(best > cap) return cap + 1;   // this row is already too far
      prev = cur;
    }
    return prev[b.length];
  }

  /**
   * Look for the wake phrase at the front of `text`.
   * Returns null, or {matched, payload, at, phrase} where `payload`
   * is whatever the user said after it — possibly an empty string.
   */
  function detect(text){
    const words = norm(text).split(" ").filter(Boolean);
    if(!words.length) return null;

    const limit = Math.min(SCAN_TOKENS, words.length);

    // Try every window of 1..3 tokens starting inside the first few
    // words. One token catches "heycab" run together by the recogniser;
    // three catches "hey cab eye".
    for(let start = 0; start < limit; start++){
      for(let len = 1; len <= 3 && start + len <= words.length; len++){
        const window = words.slice(start, start + len);
        const joined = window.join("");
        if(joined.length < 4) continue;         // too short to be safe

        for(const p of PHRASES){
          if(dist(joined, p, budget(p)) <= budget(p)){
            const payload = words.slice(start + len).join(" ").trim();
            return {matched: window.join(" "), phrase: p, payload, at: start};
          }
        }
      }
    }
    return null;
  }

  /* ── the sleeping listener ──────────────────────────────── */

  const V = () => CE.voice;

  /**
   * arm({onWake, onHeard, onArmChange})
   * Starts the standing recogniser in its sleeping mood.
   */
  function arm(opts){
    opts = opts || {};
    if(opts.onWake)      onWake = opts.onWake;
    if(opts.onHeard)     onHeard = opts.onHeard;
    if(opts.onArmChange) onArmChange = opts.onArmChange;

    const d = V().diagnose();
    if(!d.ok){ armed = false; change(); return d; }

    // Already sleeping on a live command loop? Leave it alone. The
    // app re-arms on every state change, and tearing the recogniser
    // down and back up each time is exactly the churn that used to
    // drop the first word of an utterance.
    if(armed && !awake && !opts.force &&
       V().live && V().purpose === "command"){
      change();
      return {ok:true};
    }

    armed = true; awake = false;
    change();

    V().open("command", text => {
      if(!armed) return true;

      const hit = detect(text);
      if(!hit){
        // Discarded — but log it, because "the wake word never fires"
        // is impossible to debug without seeing what was heard.
        if(onHeard) onHeard(text, false);
        return true;
      }

      // Debounce: the recogniser sometimes re-delivers the tail of an
      // utterance, which would wake us twice for one "Hey Cab".
      if(performance.now() - lastWake < 900) return true;
      lastWake = performance.now();

      if(onHeard) onHeard(text, true);
      wake(hit.payload);
      return true;
    });

    return {ok:true};
  }

  /** Stop listening entirely — the Hands-free off switch. */
  function disarm(){
    armed = false; awake = false;
    clearTimeout(sleepTimer);
    V().stop();
    change();
  }

  function change(){ if(onArmChange) onArmChange({armed, awake}); }

  /** Enter the awake state and hand whatever followed to the app. */
  function wake(payload){
    awake = true;
    clearTimeout(sleepTimer);
    change();
    if(CE.audio) CE.audio.earcon.listen();
    if(onWake) onWake(payload && payload.length > 1 ? payload : null);
  }

  /**
   * The app calls this when a voice session finishes — booking done,
   * cancelled, or timed out — to go back to sleep and start filtering
   * on the wake phrase again.
   */
  function sleep(){
    if(!armed) return;
    awake = false;
    change();
    // Return to the sleeping listener. If the standing loop is still
    // live this costs nothing — arm() sees it and leaves it alone. If
    // a dictation window took the microphone, this reclaims it.
    arm({});
  }

  /** Slide the auto-sleep deadline; call it on every user turn. */
  function keepAwake(){
    clearTimeout(sleepTimer);
    if(!awake) return;
    sleepTimer = setTimeout(()=>{ if(awake) sleep(); }, AWAKE_MS);
  }

  return {
    arm, disarm, sleep, wake, keepAwake, detect, norm,
    PHRASES, AWAKE_MS,
    get armed(){ return armed; },
    get awake(){ return awake; },
    /** The phrase to print on screen and read out in help. */
    get label(){ return "Hey Cab"; }
  };
})();
