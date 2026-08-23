/* ═══════════════════════════════════════════════════════════
   voice.js — the ear.

   The rule this file enforces:
   IF THE APP ASKS A QUESTION OUT LOUD, IT MUST LISTEN FOR THE ANSWER.

   Without it the app talks like an assistant and listens like a form:
   it says "East or West?" and then expects a finger. A blind user
   cannot answer a spoken question by finding a button.

   Three ways of listening, one microphone:

     dictate  — the opening sentence, "take me to Anna Nagar"
     answer   — a one-shot reply to a question the narrator just asked
     command  — a standing loop, so "status" or "cancel" work any time

   The mic is closed while the narrator speaks, otherwise the recogniser
   hears the phone's own voice and answers itself.
   ═══════════════════════════════════════════════════════════ */

window.CE = window.CE || {};

CE.voice = (function(){
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;

  let rec = null;
  let purpose = null;         // "dictate" | "answer" | "command" | null
  let handler = null;
  let live = false;           // the mic is actually open right now
  let loop = false;           // keep re-opening for commands
  let onInterim = null;
  let onStateChange = null;
  let timer = null;

  function supported(){ return !!SR; }

  function stop(){
    loop = false;
    clearTimeout(timer);
    if(rec && live){ try{ rec.abort(); }catch(e){} }
    live = false; purpose = null;
    notify();
  }

  function notify(){ if(onStateChange) onStateChange({live, purpose}); }

  /**
   * open("answer", text => {...}, {timeout: 9000})
   * The callback gets the final transcript. Return true from an "answer"
   * handler to say "understood"; false re-opens the mic and asks again.
   */
  function open(kind, fn, opts){
    opts = opts || {};
    if(!SR) return false;

    // Never listen while the phone is talking — it would hear itself.
    if(CE.audio && CE.audio.speaking){
      setTimeout(()=>open(kind, fn, opts), 260);
      return true;
    }

    if(live){ try{ rec.abort(); }catch(e){} live = false; }

    purpose = kind; handler = fn; loop = (kind === "command");

    rec = new SR();
    rec.lang = "en-IN";
    rec.interimResults = (kind === "dictate");
    rec.continuous = false;

    rec.onstart = ()=>{ live = true; notify(); };

    rec.onresult = e=>{
      let txt = "";
      for(let i=0;i<e.results.length;i++) txt += e.results[i][0].transcript;
      txt = txt.trim();

      const final = e.results[e.results.length-1].isFinal;

      // Barge-in: the moment the user speaks, the narrator stops.
      if(txt && CE.audio && CE.audio.speaking) CE.audio.shutUp();

      if(!final){ if(onInterim) onInterim(txt); return; }

      const keep = handler ? handler(txt) : false;
      if(kind === "answer" && keep === false){
        // Not understood — ask the mic to stay open one more time.
        setTimeout(()=>open("answer", fn, opts), 300);
      }
    };

    rec.onerror = ev=>{
      live = false; notify();
      if(ev.error === "no-speech" && kind === "command" && loop){
        timer = setTimeout(()=>open("command", fn, opts), 700);
      }
    };

    rec.onend = ()=>{
      live = false; notify();
      if(loop && kind === "command"){
        timer = setTimeout(()=>open("command", fn, opts), 600);
      }
    };

    try{ rec.start(); }catch(e){ live = false; }

    if(opts.timeout){
      clearTimeout(timer);
      timer = setTimeout(()=>{ if(live && purpose === kind) stop(); }, opts.timeout);
    }
    return true;
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
    if(G.first.test(text))  return 0;
    if(G.second.test(text)) return 1;
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
    get live(){ return live; },
    get purpose(){ return purpose; },
    set onInterim(fn){ onInterim = fn; },
    set onStateChange(fn){ onStateChange = fn; }
  };
})();
