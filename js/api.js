/* ═══════════════════════════════════════════════════════════
   api.js — the thin client for the Spring Boot service.

   THE RULE THIS FILE ENFORCES:
   THE BACKEND MAY NEVER BE THE REASON A BOOKING FAILS.

   The rider app already has a complete gazetteer and a complete
   ambiguity rule in js/nlu.js. The server has the same ones, ported to
   Java. So the server is an upgrade — better logging, one place to tune
   the thresholds, and eventually a real geocoder — and never a
   dependency. If it is slow, down, or not running at all, the app
   resolves locally and the user notices nothing.

   That is why every call here has a hard deadline measured in
   milliseconds rather than seconds. A blind user waiting on a hung
   fetch has no spinner to look at; the silence is the whole failure.
   Better a local answer in 40 ms than a perfect one in four seconds.

   Point it somewhere else with ?api=http://192.168.1.9:8080 — useful
   when the phone and the laptop are different machines.
   ═══════════════════════════════════════════════════════════ */

window.CE = window.CE || {};

CE.api = (function(){

  /* Hard deadlines. Past these we stop waiting and resolve locally. */
  const INTERPRET_MS = 900;
  const PROBE_MS     = 1200;

  function baseUrl(){
    const fromQuery = new URLSearchParams(location.search).get("api");
    if(fromQuery) return fromQuery.replace(/\/$/, "");

    let stored = null;
    try{ stored = localStorage.getItem("cabeye.api"); }catch(e){}
    if(stored) return stored.replace(/\/$/, "");

    // Same host the page came from, on the service's port. Works for
    // localhost and for a phone hitting the laptop over wifi.
    if(location.protocol === "file:") return "http://localhost:8080";
    return location.protocol + "//" + location.hostname + ":8080";
  }

  const BASE = baseUrl();

  let reachable = null;     // null = not probed yet
  let cloudSpeech = false;
  let onStatus = null;

  function setStatus(up, why){
    const changed = reachable !== up;
    reachable = up;
    if(changed && onStatus) onStatus({up, why, base: BASE});
  }

  /** fetch with a deadline. Rejects rather than hanging. */
  async function call(path, opts, ms){
    const ctl = new AbortController();
    const t = setTimeout(()=>ctl.abort(), ms);
    try{
      const res = await fetch(BASE + path, Object.assign({signal: ctl.signal}, opts || {}));
      if(!res.ok) throw new Error("HTTP " + res.status);
      return res;
    } finally {
      clearTimeout(t);
    }
  }

  /** Is the service there? Called once at start-up, never blocking. */
  async function probe(){
    try{
      const res = await call("/api/v1/speech/capabilities", {}, PROBE_MS);
      const caps = await res.json();
      cloudSpeech = !!caps.cloudSpeech;
      setStatus(true, caps.note);
      return {up:true, cloudSpeech, note: caps.note};
    }catch(e){
      setStatus(false, e.name === "AbortError" ? "no answer in time" : e.message);
      return {up:false, cloudSpeech:false};
    }
  }

  /**
   * Resolve one utterance on the server.
   * Returns the same object shape js/nlu.js produces locally, or null
   * if the server could not answer in time — the caller then falls back.
   */
  async function interpret(text, sessionId){
    try{
      const res = await call("/api/v1/voice/interpret", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({text, lang: "en-IN", sessionId: sessionId || null})
      }, INTERPRET_MS);
      const r = await res.json();
      setStatus(true);
      return r;
    }catch(e){
      setStatus(false, e.name === "AbortError" ? "slower than " + INTERPRET_MS + "ms" : e.message);
      return null;
    }
  }

  /** Which of two candidates the spoken answer picked. */
  async function clarify(answer, candidates, sessionId){
    try{
      const res = await call("/api/v1/voice/clarify", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({answer, candidates, sessionId: sessionId || null})
      }, INTERPRET_MS);
      return await res.json();
    }catch(e){
      return null;
    }
  }

  /**
   * Send recorded audio to Google via the backend.
   * Only worth doing when the browser has no recogniser of its own, or
   * when the utterance is Tamil — the browser cannot switch languages
   * mid-sentence and Google can.
   */
  async function transcribe(blob, lang){
    if(!cloudSpeech) return null;
    const form = new FormData();
    form.append("audio", blob, "utterance.webm");
    if(lang) form.append("lang", lang);
    try{
      const res = await call("/api/v1/speech/transcribe", {method:"POST", body:form}, 8000);
      return await res.json();
    }catch(e){
      return null;
    }
  }

  return {
    probe, interpret, clarify, transcribe,
    get base(){ return BASE; },
    get reachable(){ return reachable; },
    get cloudSpeech(){ return cloudSpeech; },
    set onStatus(fn){ onStatus = fn; }
  };
})();
