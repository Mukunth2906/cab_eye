/* ═══════════════════════════════════════════════════════════
   voice.js — the ear.

   The rule this file enforces:
   IF THE APP ASKS A QUESTION OUT LOUD, IT MUST LISTEN FOR THE ANSWER.

   Three ways of listening, one microphone:

     dictate  — the opening sentence, "take me to Anna Nagar"
     answer   — a one-shot reply to a question the narrator just asked
     command  — a standing loop, so the wake word works at any moment

   ── ONE PERMISSION PROMPT, EVER ────────────────────────────

   The old build asked for the microphone roughly every three seconds,
   which is the single most destructive thing a voice app can do: it
   puts a modal dialog between a blind user and the only control they
   have. Three separate causes, and all three had to go.

   1. AN ORIGIN CHROME WILL NOT REMEMBER. A grant is persisted against
      an origin. `file://` has no usable origin, and a plain
      `http://192.168.x.x` is not a secure context, so Chrome treats
      every grant as one-shot and asks again the next time anything
      touches the microphone. With a listener restarting twice a
      second, that is a prompt as fast as you can dismiss one. There is
      no code fix for this — only localhost or HTTPS will do — so the
      app now refuses to start recognition on such an origin and says
      why, instead of hammering the user with dialogs.

   2. NOTHING DEDUPED THE REQUEST. Several code paths could ask for the
      microphone at once, and each `SpeechRecognition.start()` is its
      own acquisition. `ensureMic()` now collapses every caller onto a
      single in-flight promise: N callers, one prompt.

   3. THE GRANT WAS HANDED STRAIGHT BACK. The permission check opened a
      stream and immediately stopped its tracks, releasing the device.
      We now HOLD the stream for as long as the app is listening.
      Chrome shows the microphone as in use and never re-acquires, so
      nothing can prompt again. It also makes the browser's recording
      indicator tell the truth: this app really is listening.

   And the safety net: recognition never starts until permission is
   confirmed granted, and a circuit breaker stops everything if
   denials or restarts start piling up. A loop that can prompt must
   never be able to run twice.

   ── THE OTHER FOUR FAULTS THAT MADE IT GO DEAF ─────────────

   4. THE DOUBLE-LOOP RACE. open() aborted the live recogniser, but
      that recogniser's onend still fired, read the shared `loop` flag,
      and queued its own restart. Two recognisers raced, the second
      start() threw InvalidStateError, and the ear died silently.
      Fixed with a generation token: every open() bumps `gen`, and a
      callback from an older generation returns immediately.

   5. RESTART GAPS. continuous=false plus restart-on-onend means a
      fresh handshake after every utterance — 300–800 ms during which
      the microphone is not listening. The standing loop is now
      continuous=true and detects end-of-utterance itself.

   6. SELF-HEARING. The recogniser hears the phone's own narrator and
      answers itself. The old guard polled a flag audio.js could leave
      stale; noteSpeechEnded() now stamps the moment narration stops.

   7. SILENT ERRORS. onerror handled only "no-speech". Everything else
      vanished. Every fault is now named, and fatal ones are spoken.
   ═══════════════════════════════════════════════════════════ */

window.CE = window.CE || {};

CE.voice = (function(){
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;

  /* How long a run of silence ends an utterance, per purpose. */
  const SILENCE = { dictate: 1100, answer: 1200, command: 900 };

  /* After the narrator stops, wait this long before trusting audio. */
  const ECHO_GUARD = 420;

  /* Circuit breaker. If the microphone is being denied, or the loop is
     thrashing, stop — do not keep asking. */
  const DENIAL_WINDOW = 12000, DENIAL_LIMIT = 3;
  const START_WINDOW  = 10000, START_LIMIT  = 14;

  let rec = null;
  let gen = 0;                // generation token — see fault 4 above
  let purpose = null;         // "dictate" | "answer" | "command" | null
  let handler = null;
  let live = false;           // the mic is actually open right now
  let loop = false;           // keep re-opening for commands
  let onInterim = null;
  let onStateChange = null;
  let onError = null;
  let timer = null;
  let silenceTimer = null;
  let retryTimer = null;
  let retries = 0;
  let lastSpeechEnd = 0;
  let fatal = null;           // a permanent failure, e.g. permission denied

  /* ── the permission state, held in one place ───────────── */
  let heldStream = null;      // kept OPEN on purpose — see cause 3
  let micPromise = null;      // the single in-flight request — cause 2
  let permission = "unknown"; // "unknown" | "prompt" | "granted" | "denied"
  let denials = [];           // timestamps, for the circuit breaker
  let starts  = [];

  /* Holding the stream is an optimisation, not a requirement, and on
     some Windows audio drivers it is actively harmful: the device goes
     exclusive and SpeechRecognition then cannot open it, reporting
     "audio-capture". So the hold self-disables the first time it looks
     like the cause, and we fall back to relying on Chrome's persisted
     grant — which on a secure origin is also exactly one prompt, ever.
     Better a fix that steps aside when it is wrong than one that
     insists and takes the microphone down with it. */
  let holdDisabled = false;
  let captureFails = 0;

  function supported(){ return !!SR; }

  /** True while we are holding the device open — no prompt possible. */
  function holding(){ return !!(heldStream && heldStream.active); }

  /* ── is this page even allowed to keep a microphone grant? ──
     Not "can it open a mic once" — "will Chrome remember". An
     insecure origin can sometimes open one and will still re-prompt
     every single time, which is the behaviour we are here to kill. */
  function secureContext(){
    return window.isSecureContext ||
           location.protocol === "https:" ||
           location.hostname === "localhost" ||
           location.hostname === "127.0.0.1";
  }

  function diagnose(){
    if(!SR) return {ok:false, code:"unsupported", msg: message("unsupported")};
    if(!secureContext()) return {ok:false, code:"insecure", msg: message("insecure")};
    if(fatal) return {ok:false, code:fatal.code, msg:fatal.msg};
    return {ok:true};
  }

  /* Plain-language text for every failure. These are read aloud, so
     they are sentences, and the recoverable ones say how to recover —
     a denied microphone cannot be un-denied from JavaScript, so the
     only useful thing left is to explain the click. */
  function message(code){
    switch(code){
      case "not-allowed":
      case "service-not-allowed":
        return "I don't have permission to use the microphone. " +
               "Click the padlock in the address bar, choose Site settings, " +
               "set Microphone to Allow, then reload the page.";
      case "audio-capture":
        return "I can't find a microphone on this device.";
      case "insecure":
        return "The microphone is blocked because this page was not opened over " +
               "localhost or HTTPS. Chrome will not remember permission for this " +
               "address, so it would ask again every few seconds. " +
               "Run serve dot bat and open localhost port 8000.";
      case "storm":
        return "The microphone permission keeps being refused, so I have stopped " +
               "asking. Allow the microphone for this page and reload.";
      case "thrash":
        return "The microphone kept restarting, so I have stopped it. Reload the page.";
      case "network":
        return "Speech recognition needs the internet and I can't reach it.";
      case "unsupported":
        return "This browser cannot hear you. Use Google Chrome.";
      default:
        return "The microphone stopped working.";
    }
  }

  function notify(){ if(onStateChange) onStateChange({live, purpose, permission, fatal}); }

  function report(code, permanent){
    const msg = message(code);
    if(permanent){
      fatal = {code, msg};
      loop = false;
    }
    if(onError) onError({code, msg, permanent: !!permanent});
    notify();
  }

  /** Keep only the events still inside a rolling window. */
  function recent(list, window){
    const now = performance.now();
    const kept = list.filter(t => now - t < window);
    kept.push(now);
    return kept;
  }

  /* ── the Permissions API: know, rather than guess ──────────
     Chrome can tell us whether a grant already exists. When it says
     "granted" we can skip getUserMedia entirely, which means a
     returning user sees no prompt at all — not even a suppressed one. */
  async function queryPermission(){
    if(!navigator.permissions || !navigator.permissions.query) return "unknown";
    try{
      const status = await navigator.permissions.query({name: "microphone"});
      permission = status.state;

      // Revocation mid-session must not look like the app going deaf.
      if(!status.__ceWatched){
        status.__ceWatched = true;
        status.onchange = ()=>{
          permission = status.state;
          if(status.state !== "granted"){
            releaseMic();
            stop();
            report("not-allowed", true);
          }
          notify();
        };
      }
      return status.state;
    }catch(e){
      return "unknown";      // Firefox and older Chrome land here
    }
  }

  /**
   * The one and only place a microphone is ever requested.
   *
   * Every caller shares a single in-flight promise, so N simultaneous
   * callers produce exactly one browser prompt. Once granted, the
   * stream is HELD rather than released — a live stream is what stops
   * anything later from acquiring the device again and re-prompting.
   */
  function ensureMic(){
    if(heldStream && heldStream.active && permission === "granted"){
      return Promise.resolve({ok:true});
    }
    // Hold disabled and the grant already on record: nothing to do, and
    // crucially nothing that could prompt.
    if(holdDisabled && permission === "granted"){
      return Promise.resolve({ok:true, held:false});
    }
    if(micPromise) return micPromise;          // ← the dedupe

    micPromise = (async ()=>{
      const d = diagnose();
      if(!d.ok) return {ok:false, code:d.code, msg:d.msg};

      // Too many refusals already? Stop. Never ask into a wall.
      if(denials.length >= DENIAL_LIMIT){
        fatal = {code:"storm", msg: message("storm")};
        return {ok:false, code:"storm", msg: fatal.msg};
      }

      await queryPermission();
      if(permission === "denied"){
        fatal = {code:"not-allowed", msg: message("not-allowed")};
        return {ok:false, code:"not-allowed", msg: fatal.msg};
      }

      if(!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia){
        // Old browser. Let SpeechRecognition ask for itself; we cannot
        // hold a stream, but there is nothing better available.
        permission = "granted";
        return {ok:true, held:false};
      }

      try{
        heldStream = await navigator.mediaDevices.getUserMedia({
          audio: {
            // The recogniser wants speech, not music. These are the
            // settings that make a wake word survive a noisy room.
            echoCancellation: true,
            noiseSuppression: true,
            autoGainControl: true
          }
        });
        permission = "granted";
        fatal = null;
        denials = [];

        if(holdDisabled){
          // We only wanted the grant on record. Chrome remembers it for
          // this origin, so releasing costs us nothing and keeps the
          // device free for whoever could not share it.
          heldStream.getTracks().forEach(t => t.stop());
          heldStream = null;
          notify();
          return {ok:true, held:false};
        }

        // If the device disappears — headset unplugged, tab moved —
        // drop the handle so the next open() can legitimately re-ask.
        heldStream.getTracks().forEach(t=>{
          t.onended = ()=>{ heldStream = null; notify(); };
        });

        notify();
        return {ok:true, held:true};

      }catch(err){
        denials = recent(denials, DENIAL_WINDOW);
        permission = "denied";
        const code = err && err.name === "NotAllowedError" ? "not-allowed"
                   : err && err.name === "NotFoundError"   ? "audio-capture"
                   : "mic-failed";
        fatal = {code, msg: message(code)};
        return {ok:false, code, msg: fatal.msg};
      } finally {
        micPromise = null;
      }
    })();

    return micPromise;
  }

  /** Public wrapper — call it from the priming gesture. */
  async function requestPermission(){
    const res = await ensureMic();
    notify();
    return res;
  }

  /** Give the microphone back. Hands-free off, or signing out. */
  function releaseMic(){
    if(heldStream){
      try{ heldStream.getTracks().forEach(t=>{ t.onended = null; t.stop(); }); }catch(e){}
    }
    heldStream = null;
    notify();
  }

  function clearTimers(){
    clearTimeout(timer);
    clearTimeout(silenceTimer);
    clearTimeout(retryTimer);
    timer = silenceTimer = retryTimer = null;
  }

  /** Detach a recogniser so its late callbacks cannot reach us. */
  function detach(r){
    if(!r) return;
    r.onstart = r.onresult = r.onerror = r.onend = r.onaudiostart =
      r.onspeechstart = r.onspeechend = null;
  }

  function stop(){
    gen++;                     // everything in flight is now stale
    loop = false;
    clearTimers();
    const old = rec;
    rec = null;
    detach(old);
    if(old){ try{ old.abort(); }catch(e){} }
    live = false; purpose = null; retries = 0;
    notify();
  }

  /** Called by audio.js the moment the narrator finishes a sentence. */
  function noteSpeechEnded(){ lastSpeechEnd = performance.now(); }

  /** True while the narrator's own audio could still be in the room. */
  function echoing(){
    if(CE.audio && CE.audio.speaking) return true;
    return (performance.now() - lastSpeechEnd) < ECHO_GUARD;
  }

  /**
   * open("answer", text => {...}, {timeout: 9000})
   *
   * Returns immediately; the recogniser starts once permission is
   * confirmed. Nothing here can produce a prompt of its own — that is
   * the entire point of routing through ensureMic().
   */
  function open(kind, fn, opts){
    opts = opts || {};

    const d = diagnose();
    if(!d.ok){ report(d.code, true); return false; }

    // Never listen into the narrator's own voice.
    if(echoing()){
      const my = gen;
      clearTimeout(retryTimer);
      retryTimer = setTimeout(()=>{ if(my === gen) open(kind, fn, opts); }, 180);
      return true;
    }

    // Thrash guard: a recogniser that will not stay open must not be
    // allowed to spin forever burning battery and audio focus.
    starts = recent(starts, START_WINDOW);
    if(starts.length > START_LIMIT){
      report("thrash", true);
      stop();
      return false;
    }

    // Retire whatever is running. Bumping gen first means the old
    // recogniser's onend cannot queue a competing restart.
    gen++;
    const my = gen;
    clearTimers();
    const old = rec;
    rec = null;
    detach(old);
    if(old){ try{ old.abort(); }catch(e){} }
    live = false;

    purpose = kind; handler = fn; loop = (kind === "command");

    // The gate. One prompt at most, shared by every caller, and the
    // recogniser is not constructed until the answer comes back.
    ensureMic().then(res=>{
      if(my !== gen) return;                 // a newer open() took over
      if(!res.ok){ report(res.code, true); return; }
      launch(my, kind, fn, opts);
    });

    return true;
  }

  /** Build and start the recogniser. Permission is already granted. */
  function launch(my, kind, fn, opts){
    const r = new SR();
    rec = r;
    r.lang = opts.lang || "en-IN";
    r.interimResults = true;                 // needed for silence detection
    r.continuous = (kind === "command");     // the standing loop never closes
    r.maxAlternatives = 3;

    let finalText = "";
    let interimText = "";
    let delivered = false;

    /* End-of-utterance. With continuous=true the service will not tell
       us a phrase is over, so we decide: a run of silence after
       something was heard means the person has finished talking. */
    function armSilence(){
      clearTimeout(silenceTimer);
      silenceTimer = setTimeout(()=>{
        if(my !== gen) return;
        const txt = (finalText + " " + interimText).trim();
        if(txt) deliver(txt);
      }, opts.silence || SILENCE[kind] || 1000);
    }

    function deliver(txt){
      if(my !== gen || delivered) return;
      // A transcript that arrived while the narrator was speaking is
      // almost certainly the phone hearing itself. Throw it away.
      if(echoing()) return;
      delivered = true;
      clearTimeout(silenceTimer);
      finalText = ""; interimText = "";

      const keep = handler ? handler(txt) : false;

      if(kind === "command"){
        delivered = false;      // the loop lives on
        return;
      }
      if(kind === "answer" && keep === false){
        retryTimer = setTimeout(()=>{ if(my === gen) open("answer", fn, opts); }, 260);
      }
    }

    r.onstart = ()=>{
      if(my !== gen) return;
      // Note what onstart does NOT reset: captureFails. A recogniser
      // that cannot reach the microphone still fires onstart happily and
      // only then raises "audio-capture", so resetting the counter here
      // would let it retry for ever. Only real audio clears it.
      live = true; retries = 0; permission = "granted"; fatal = null;
      notify();
    };

    r.onresult = e=>{
      if(my !== gen) return;

      captureFails = 0;        // audio is genuinely reaching us
      let fin = "", intr = "";
      for(let i = e.resultIndex; i < e.results.length; i++){
        const res = e.results[i];
        if(res.isFinal) fin += res[0].transcript;
        else            intr += res[0].transcript;
      }
      if(fin) finalText += fin;
      interimText = intr;

      const shown = (finalText + " " + interimText).trim();

      // Barge-in: the moment the user speaks, the narrator stops. But
      // only for real user speech, never for the narrator's own echo.
      if(shown && !echoing() && CE.audio && CE.audio.speaking) CE.audio.shutUp();

      if(shown && onInterim) onInterim(shown);

      if(fin && !intr && kind !== "command"){ deliver(finalText.trim()); return; }
      armSilence();
    };

    r.onerror = ev=>{
      if(my !== gen) return;
      live = false;
      const code = ev.error;

      if(code === "not-allowed" || code === "service-not-allowed"){
        // Permission was pulled out from under a running recogniser.
        // Count it — three of these and we stop asking for good.
        denials = recent(denials, DENIAL_WINDOW);
        permission = "denied";
        releaseMic();
        report(denials.length >= DENIAL_LIMIT ? "storm" : code, true);
        return;
      }
      if(code === "audio-capture"){
        // Most likely cause on Windows: our own held stream took the
        // device exclusively and the recogniser cannot open it. Step
        // aside once and try again before calling this fatal.
        captureFails++;
        if(holding() && !holdDisabled){
          holdDisabled = true;
          releaseMic();
          if(onError) onError({code:"hold-conflict", permanent:false,
            msg:"The microphone would not open while I was holding it, so I let go. " +
                "Permission is already granted, so you still will not be asked again."});
          retryTimer = setTimeout(()=>{ if(my === gen) open(kind, fn, opts); }, 350);
          return;
        }
        if(captureFails >= 3){ report(code, true); }
        else if(loop){
          retryTimer = setTimeout(()=>{ if(my === gen) open(kind, fn, opts); }, 600);
        } else {
          report(code, true);
        }
        return;
      }
      // "no-speech" and "aborted" are ordinary in a standing loop.
      if(code === "network") report(code, false);
      notify();
    };

    r.onend = ()=>{
      if(my !== gen) return;      // a newer open() already took over
      live = false; notify();
      if(!loop || fatal) return;

      // Chrome ends the session after a stretch of silence even with
      // continuous=true. Reopen — and because the stream is held and
      // permission is cached, this cannot produce a prompt.
      retries = Math.min(retries + 1, 6);
      const wait = retries > 3 ? 400 * retries : 250;
      retryTimer = setTimeout(()=>{ if(my === gen) open(kind, fn, opts); }, wait);
    };

    try{
      r.start();
    }catch(e){
      // InvalidStateError: an older recogniser is still winding down.
      live = false;
      retryTimer = setTimeout(()=>{ if(my === gen) open(kind, fn, opts); }, 300);
      return;
    }

    if(opts.timeout){
      timer = setTimeout(()=>{
        if(my !== gen) return;
        if(live && purpose === kind) stop();
      }, opts.timeout);
    }
  }

  /* ── what the rider is allowed to say ────────────────── */
  const G = {
    cancel : /\b(cancel|stop|abort|no|nope|don'?t|wait)\b/i,
    yes    : /\b(yes|yeah|yep|correct|right|okay|ok|sure|confirm)\b/i,
    status : /\b(status|where|how (long|far)|eta|update|are we)\b/i,
    call   : /\b(call|phone|ring|contact)\b/i,
    again  : /\b(again|another|new ride|book)\b/i,
    repeat : /\b(repeat|say (that )?again|pardon|what)\b/i,
    help   : /\b(help|what can i say|options)\b/i,
    first  : /\b(first|one|1st|former)\b/i,
    second : /\b(second|two|2nd|latter|other)\b/i
  };

  /** Pick which of two places the rider meant. Returns 0, 1, or -1. */
  function pickCandidate(text, candidates){
    // Order matters, and getting it wrong is silent. "the second one"
    // contains the word "one", so testing `first` first sends the rider
    // to the wrong place while looking like it understood perfectly.
    if(G.second.test(text)) return 1;
    if(G.first.test(text))  return 0;
    const scored = candidates.map((c,i)=>({i, s: CE.nlu.score(text, c.n)}));
    scored.sort((a,b)=>b.s-a.s);
    if(scored[0].s >= 0.3 && scored[0].s - scored[1].s > 0.05) return scored[0].i;
    // "east" / "west" style single-word answers
    for(let i=0;i<candidates.length;i++){
      const tail = candidates[i].n.split(" ").pop().toLowerCase();
      const other = candidates[1-i].n.split(" ").pop().toLowerCase();
      if(tail !== other && new RegExp("\\b"+tail+"\\b","i").test(text)) return i;
    }
    return -1;
  }

  return {
    supported, open, stop, G, pickCandidate,
    diagnose, requestPermission, queryPermission, releaseMic,
    noteSpeechEnded, message,
    get live(){ return live; },
    get purpose(){ return purpose; },
    get permission(){ return permission; },
    /** True while we are holding the device open — no prompt possible. */
    get holding(){ return holding(); },
    get fatal(){ return fatal; },
    clearFatal(){ fatal = null; denials = []; starts = []; captureFails = 0; },
    set onInterim(fn){ onInterim = fn; },
    set onStateChange(fn){ onStateChange = fn; },
    set onError(fn){ onError = fn; }
  };
})();
