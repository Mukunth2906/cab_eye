/* ═══════════════════════════════════════════════════════════
   audio.js — the non-visual interface.

   One primitive, tone(), builds every sound. Earcons carry the
   high-frequency low-information events so the narrator doesn't have
   to spend speech on them, and speech time is task time.
   ═══════════════════════════════════════════════════════════ */

window.CE = window.CE || {};

CE.audio = (function(){
  let AC = null, on = true, heartTimer = null, dwell = 0, dwellStart = 0;

  function ctx(){
    if(!AC) AC = new (window.AudioContext || window.webkitAudioContext)();
    if(AC.state === "suspended") AC.resume();
    return AC;
  }

  /** freq Hz, dur seconds. pan −1 left … +1 right. glide = end frequency. */
  function tone(freq, dur, o){
    if(!on) return;
    o = o || {};
    const c = ctx(), t = c.currentTime + (o.delay || 0);
    const osc = c.createOscillator(), gain = c.createGain(), pan = c.createStereoPanner();
    osc.type = o.type || "sine";
    osc.frequency.setValueAtTime(freq, t);
    if(o.glide) osc.frequency.exponentialRampToValueAtTime(o.glide, t + dur);
    pan.pan.setValueAtTime(Math.max(-1, Math.min(1, o.pan || 0)), t);
    const g = o.gain || 0.13;
    gain.gain.setValueAtTime(0.0001, t);
    gain.gain.exponentialRampToValueAtTime(g, t + 0.012);
    gain.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    osc.connect(gain); gain.connect(pan); pan.connect(c.destination);
    osc.start(t); osc.stop(t + dur + 0.05);
  }

  const earcon = {
    listen  : ()=>{ tone(520,.10,{type:"triangle"}); tone(780,.13,{type:"triangle",delay:.09}); },
    captured: ()=>  tone(400,.07,{type:"square",gain:.07}),
    thinking: ()=>{ tone(300,.09,{gain:.05}); tone(300,.09,{gain:.05,delay:.28}); },
    booked  : ()=>{ tone(620,.11,{type:"triangle"}); tone(930,.20,{type:"triangle",delay:.10}); },
    assigned: ()=>{ tone(500,.09); tone(660,.09,{delay:.09}); tone(820,.16,{delay:.18}); },
    arrived : ()=>{ tone(980,.16,{type:"triangle",gain:.18}); tone(1310,.26,{type:"triangle",gain:.18,delay:.14}); },
    error   : ()=>  tone(220,.26,{type:"sawtooth",gain:.09,glide:140}),
    alarm   : ()=>{ for(let i=0;i<4;i++){ tone(880,.16,{type:"square",gain:.12,delay:i*.34});
                                          tone(660,.16,{type:"square",gain:.12,delay:i*.34+.17}); } },
    /** panned to the driver's real bearing — the last-50-metres cue */
    beacon  : (p)=>{ for(let i=0;i<3;i++) tone(1180,.13,{type:"triangle",gain:.17,pan:p,delay:i*.22}); },
    /** pitch rises as the car nears. No speech is spent on approach. */
    approach: (p,near)=> tone(700 + near*260,.09,{type:"triangle",gain:.11,pan:p})
  };

  /** The system-alive floor. While this pulses, silence is not ambiguity. */
  function heartbeat(run){
    clearInterval(heartTimer); heartTimer = null;
    if(!run) return;
    heartTimer = setInterval(()=> tone(58,.16,{gain:.032}), 2600);
  }

  /* ── the Ambient Status Narrator ──────────────────────
     tier 0 interrupts whatever is speaking (arrival, deviation)
     tier 1 queues normally (assignment, confirmation)
     tier 2 never reaches here — those events fire an earcon only     */
  let onFirstAudio = null, speaking = false;

  /** speak(text, tier, onDone) — onDone fires when the sentence finishes,
      which is how the app knows when to open the mic for your answer. */
  function speak(text, tier, onDone){
    if(!on || !("speechSynthesis" in window)){
      if(onFirstAudio) onFirstAudio();
      if(onDone) setTimeout(onDone, 250);
      return;
    }
    if(tier === 0) speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text);
    u.rate = 1.06; u.lang = "en-IN";
    // Chrome's speechSynthesis sometimes never fires onend. If the app is
    // waiting on that to open the mic, a dropped event means the user is
    // asked a question nobody listens to — so there is always a watchdog.
    let done = false, watchdog = null;
    const finish = ()=>{
      if(done) return;
      done = true;
      clearTimeout(watchdog);
      if(dwellStart){ dwell += (performance.now()-dwellStart)/1000; dwellStart = 0; }
      speaking = false;
      // Stamp the moment narration stopped. voice.js refuses to trust
      // any transcript for a short guard window after this, so the
      // recogniser cannot transcribe the phone's own speaker.
      if(CE.voice && CE.voice.noteSpeechEnded) CE.voice.noteSpeechEnded();
      if(onDone) onDone();
    };
    u.onstart = ()=>{ speaking = true; dwellStart = performance.now(); if(onFirstAudio) onFirstAudio(); };
    u.onend   = finish;
    u.onerror = finish;
    speaking = true;
    speechSynthesis.speak(u);
    watchdog = setTimeout(finish, Math.max(1400, text.split(/\s+/).length * 420));
  }

  /** Barge-in: stop talking the moment the user starts. */
  function shutUp(){
    try{ speechSynthesis.cancel(); }catch(e){}
    speaking = false;
    if(CE.voice && CE.voice.noteSpeechEnded) CE.voice.noteSpeechEnded();
  }

  return {
    unlock : ctx,
    tone, earcon, heartbeat, speak, shutUp,
    get speaking(){ return speaking; },
    set enabled(v){ on = v; if(!v){ try{ speechSynthesis.cancel(); }catch(e){} heartbeat(false); } },
    get enabled(){ return on; },
    get dwell(){ return dwell; },
    resetDwell(){ dwell = 0; },
    /** called the instant the first sound is audible — stamps the T4 latency mark */
    set onFirstAudio(fn){ onFirstAudio = fn; }
  };
})();
