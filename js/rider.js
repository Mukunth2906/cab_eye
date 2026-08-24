/* ═══════════════════════════════════════════════════════════
   rider.js — the blind user's app.

   ONE surface. Eleven states. No router, no screen stack, no back
   button, because a blind user never navigates.

   THE RULE: if the app asks a question out loud, it listens for the
   answer out loud. Every spoken prompt here is answerable by speaking.
   The buttons remain for low-vision users and sighted helpers, but a
   whole ride can be completed without touching the screen once.
   ═══════════════════════════════════════════════════════════ */

(function(){
const A = CE.audio, N = CE.nlu, B = CE.bus, V = CE.voice, W = CE.wake, T = CE.bus.T;
const $ = id => document.getElementById(id);

const session = CE.auth.require("rider");
if(!session) return;
$("hello").textContent = "Hello, " + session.name;

let S, lastSpoken = "", handsFree = true, primed = false, micProblem = null;

function fresh(keep){
  S = {
    state:"idle", transcript:"", dest:null, rideType:"auto", candidates:[],
    drv:null, bearing:0, fare:0, eta:0, sos:false,
    undoTimer:null, undoLeft:0, approachTimer:null,
    t0:0, marks:[null,null,null,null,null],
    taps: keep ? keep.taps : 0,
    turns: keep ? keep.turns : 0,
    ttcb: null
  };
}
fresh();

/* ── plumbing ──────────────────────────────────────────── */
function log(kind, key, msg){ B.publish(T.LOG, {kind, key, msg}); }
function pushState(){ B.publish(T.STATE, {rider:S.state}); }
function pushMarks(){ B.publish(T.MARKS, {marks:S.marks}); }
function pushMetrics(){ B.publish(T.METRICS, {ttcb:S.ttcb, taps:S.taps, turns:S.turns, dwell:A.dwell}); }
function tap(){ S.taps++; pushMetrics(); }
function mark(i){ if(!S.t0) return; S.marks[i] = performance.now() - S.t0; pushMarks(); }
A.onFirstAudio = ()=>{ if(S.marks[4] === null) mark(4); pushMetrics(); };

/** say(text, tier, then) — `then` runs when the sentence finishes. */
function say(text, tier, then){
  lastSpoken = text;
  log("sys","Narrator",text);
  A.speak(text, tier === undefined ? 1 : tier, then);
}

function go(state){ S.state = state; render(); pushState(); listenForState(); }

/* ═══ THE EAR ═══════════════════════════════════════════

   ONE RULE, TWO HALVES:

     The app asked a question   → answer it bare. "East." "Cancel."
     You are starting something → say "Hey Cab" first.

   That asymmetry is deliberate. An open turn the app itself opened is
   a conversation, and making the user re-announce themselves inside
   one would be absurd. But an unprompted utterance in a moving
   auto-rickshaw is probably the user talking to the driver, not to
   the phone — and "no, not that way" must never cancel a ride. The
   wake word is what separates speech aimed at the app from speech
   that merely happens near it.

   States with a dedicated listener (clarify, confirming) are open
   turns and take bare answers. Everything else sleeps on the wake
   word.                                                            */
function listenForState(){
  if(!V.supported() || !handsFree) return;
  const s = S.state;

  // These states have their own dedicated listener, set up where the
  // question is asked. Don't stomp on them.
  if(s === "listening" || s === "resolving" || s === "clarify" || s === "confirming") return;

  armWake();
}

/* ── the sleeping listener ─────────────────────────────── */
function armWake(){
  if(!V.supported() || !handsFree) return;

  const d = V.diagnose();
  if(!d.ok){ showMicProblem(d); return; }
  micProblem = null;

  W.arm({
    onWake: onWakeUtterance,
    onHeard: (text, hit)=>{
      if(hit) log("user","Wake",`&ldquo;${text}&rdquo;`);
      else    log("note","Asleep",`heard &ldquo;<em>${text}</em>&rdquo; — no wake word, ignored`);
    },
    onArmChange: ()=> paintEar()
  });
}

/**
 * Something got past the wake filter.
 * `payload` is whatever followed "Hey Cab", or null if nothing did.
 */
function onWakeUtterance(payload){
  W.keepAwake();

  if(!payload){
    // Bare "Hey Cab" — acknowledge and open a proper dictation window.
    // This is the two-breath path; the one-breath path skips it.
    log("note","Awake","wake word only — opening dictation");
    const q = S.state === "idle" || S.state === "done" ? "Yes. Where to?" : "Yes?";
    say(q, 0, ()=>{
      S.transcript = "";
      if(S.state === "idle") { S.state = "listening"; render(); pushState(); }
      V.onInterim = t=>{ if(S.state !== "listening") return;   // the sleeping
                                            // loop emits interims too
        S.transcript = t; render(); };
      V.open("dictate", text=>{ handleAwake(text); return true; }, {timeout:9000});
    });
    return;
  }

  log("note","Awake",`one breath — wake word and request together`);
  handleAwake(payload);
}

/** Route an utterance the user aimed at the app. */
function handleAwake(text){
  W.keepAwake();
  log("user","Heard",text);

  if(handleCommand(text)) return;

  // Not a command. In idle or after a finished trip, it's a destination.
  if(S.state === "idle" || S.state === "listening" || S.state === "done"){
    if(S.state === "done") { newRideSilently(); }
    startFrom(text);
    return;
  }

  A.earcon.error();
  say("Sorry, I didn't understand that. You can say status, cancel, or repeat.", 0, ()=>W.sleep());
}

/* ── the global command grammar ────────────────────────── */
/** Returns true if `text` was a recognised command and was acted on. */
function handleCommand(text){
  if(V.G.repeat.test(text)){ say(lastSpoken || "Nothing to repeat yet.", 1, ()=>W.sleep()); return true; }
  if(V.G.help.test(text)){ sayHelp(); return true; }
  if(V.G.status.test(text)){ sayStatus(); return true; }
  if(V.G.call.test(text) && S.drv){
    log("note","Call","masked call placed by voice");
    say("Calling " + S.drv.name + ".", 1, ()=>W.sleep());
    return true;
  }
  if(V.G.again.test(text) && S.state === "done"){ newRide(); return true; }
  if(V.G.cancel.test(text) && ["finding","assigned","approaching","arrived"].includes(S.state)){
    B.publish(T.RIDE_CANCEL, {});
    log("warn","Cancelled","ride cancelled by voice");
    say("Ride cancelled.", 0);
    newRide();
    return true;
  }
  if(S.sos && (V.G.cancel.test(text) || V.G.yes.test(text))){
    S.sos = false; render();
    log("note","Dismissed","false alarm, by voice");
    say("Alright. Cancelling the alert.", 0, ()=>W.sleep());
    return true;
  }
  return false;
}

/* ── when the microphone cannot work at all ────────────── */
/* A blind user cannot see a dead indicator, and the old build failed
   exactly here in silence. Every fatal microphone fault is now named
   on screen, written to the log, and — once — read out loud. */
function showMicProblem(d){
  if(micProblem && micProblem.code === d.code) { render(); return; }
  micProblem = d;
  log("warn","Mic blocked", d.msg);
  render();
  A.earcon.error();
  say(d.msg, 0);
}

function sayStatus(){
  const done = ()=>W.sleep();
  if(S.state === "finding")  return say("Still looking for a driver.", 1, done);
  if(S.state === "assigned" || S.state === "approaching")
    return say(`${S.drv.name} is on the way, about ${S.eta} minutes.`, 1, done);
  if(S.state === "arrived")  return say(`${S.drv.name} has arrived. ${S.drv.car}, ${S.drv.plate}.`, 1, done);
  if(S.state === "intrip")   return say(`On the way to ${S.dest.n}. About ${S.eta} minutes left.`, 1, done);
  if(S.state === "done")     return say(`Trip finished. Fare ${S.fare} rupees.`, 1, done);
  return say("Nothing booked yet. Say Hey Cab, then where you want to go.", 1, done);
}

function sayHelp(){
  const done = ()=>W.sleep();
  if(S.state === "idle")
    return say("Say Hey Cab to wake me, then tell me where to go. " +
               "Or say it all at once — Hey Cab, take me to Anna Nagar.", 1, done);
  say("Say Hey Cab, then: status, call driver, repeat, cancel, or book again.", 1, done);
}

/* ── dictation ─────────────────────────────────────────── */
function startFrom(text){
  S.t0 = 0; S.marks = [null,null,null,null,null]; pushMarks();
  S.transcript = text;
  S.state = "listening"; render(); pushState();
  A.earcon.listen();
  setTimeout(()=>heard(text), 260);
}

/* The button still works — for low-vision users, sighted helpers, and
   for anyone whose room is too loud for a wake word. It is no longer
   the way in, only a way in. */
function startListening(){
  A.unlock(); prime(); tap();
  S.t0 = 0; S.marks = [null,null,null,null,null]; pushMarks();
  S.transcript = "";
  S.state = "listening"; render(); pushState();
  A.earcon.listen();
  A.heartbeat(false);
  log("user","Mic","listening… (button, not wake word)");

  const d = V.diagnose();
  if(!d.ok){ showMicProblem(d); S.state = "idle"; render(); pushState(); return; }
  if(!V.supported()){
    log("note","No ASR","this browser has no speech recognition — use a sample phrase");
    return;
  }
  V.onInterim = t=>{ if(S.state !== "listening") return;   // the sleeping
                                            // loop emits interims too
        S.transcript = t; render(); };
  V.open("dictate", text=>{
    S.transcript = text;
    heard(text);
    return true;
  }, {timeout:10000});
}

function stopListening(){ V.stop(); if(S.transcript) heard(S.transcript); else go("idle"); }

function useSample(text){
  A.unlock(); prime(); tap();
  V.stop();
  startFrom(text);
}

/* ── understanding ─────────────────────────────────────── */
function heard(text){
  V.stop();
  S.t0 = performance.now() - 40;
  mark(0); mark(1);
  log("user","Heard",text);
  A.earcon.captured();
  S.state = "resolving"; render(); pushState();
  A.earcon.thinking();

  N.resolveAsync(text, session.name).then(r=>{
    S.rideType = r.rideType;
    mark(2);
    log("note", r.fast ? "Fast path" : "Fallback",
        (r.fast ? `rule matched → "<em>${r.q}</em>" · ${r.rideType}`
                : `no rule matched, whole utterance used → "<em>${r.q}</em>"`) +
        ` · <em>${r.via === "server" ? "resolved on the server" : "resolved in the browser"}</em>`);
    mark(3);

    if(!r.ok){
      A.earcon.error();
      log("warn","No match","could not resolve a destination");
      say("Sorry, I didn't catch a place I know. Please say it again.", 0, ()=>{
        go("idle");
      });
      return;
    }

    if(r.ask){ askWhich(r); return; }

    if(r.tied){
      log("note","Ambiguity",
        `gap ${r.gap.toFixed(2)} but only ${r.div.toFixed(1)} km apart &rarr; <b>don't ask</b>, book it`);
    }
    commit(r.place);
  });
}

/* ── the question that used to need a finger ───────────── */
function askWhich(r, retry){
  S.candidates = r.candidates;
  if(!retry){
    S.turns++;
    log("note","Ambiguity",
      `gap ${r.gap.toFixed(2)} &lt; ${N.DELTA} <em>and</em> ${r.div.toFixed(1)} km apart &rarr; <b>ask</b>`);
  }
  S.state = "clarify"; render(); pushState(); pushMetrics();

  const q = retry
    ? `Sorry. ${r.candidates[0].n}, or ${r.candidates[1].n}?`
    : `Did you mean ${r.candidates[0].n}, or ${r.candidates[1].n}?`;

  // Ask, then open the mic the moment the question finishes.
  say(q, 1, ()=>{
    if(S.state !== "clarify") return;
    log("note","Listening","waiting for a spoken answer");
    V.open("answer", text=>{
      if(S.state !== "clarify") return true;
      log("user","Heard",text);
      if(V.G.cancel.test(text)){
        say("Cancelled.", 0, ()=>go("idle"));
        return true;
      }
      const i = V.pickCandidate(text, r.candidates);
      if(i < 0){ askWhich(r, true); return true; }
      log("note","Answered",`voice → ${r.candidates[i].n}`);
      commit(r.candidates[i]);
      return true;
    }, {timeout:9000});
  });
}

/* ── booking: act first, allow undo — by voice ─────────── */
const UNDO_SECONDS = 5;

function commit(place){
  V.stop();
  S.dest = place; S.undoLeft = UNDO_SECONDS;
  S.state = "confirming"; render(); pushState();
  A.earcon.booked();

  say(`Booking ${S.rideType} to ${place.n}. Say cancel to stop.`, 1, ()=>{
    // The promise is now kept: the mic really is open for "cancel".
    if(S.state !== "confirming") return;
    V.open("answer", text=>{
      if(S.state !== "confirming") return true;
      log("user","Heard",text);
      if(V.G.cancel.test(text)){ cancelUndo(true); return true; }
      return true;
    }, {timeout:UNDO_SECONDS * 1000});
  });

  clearInterval(S.undoTimer);
  S.undoTimer = setInterval(()=>{
    S.undoLeft -= 0.1;
    if(S.undoLeft <= 0){ clearInterval(S.undoTimer); finding(); }
    else render();
  }, 100);
}

function cancelUndo(byVoice){
  if(!byVoice) tap();
  clearInterval(S.undoTimer);
  V.stop();
  A.earcon.error();
  log("warn","Cancelled", byVoice ? "cancelled by voice, inside the undo window"
                                  : "user cancelled inside the undo window");
  say("Cancelled.", 0, ()=>go("idle"));
  S.dest = null;
  S.state = "idle"; render(); pushState();
}

function finding(){
  V.stop();
  S.ttcb = (performance.now() - S.t0)/1000;
  S.fare = 120 + Math.round(Math.random()*140);
  S.eta  = 4 + Math.round(Math.random()*7);
  go("finding");
  A.heartbeat(true);
  log("note","Booked",`request sent — time to confirmed booking <b>${S.ttcb.toFixed(2)}s</b>`);
  pushMetrics();
  B.publish(T.RIDE_REQUEST, {dest:S.dest.n, rideType:S.rideType, fare:S.fare, eta:S.eta});
}

/* ── what the driver's taps sound like ─────────────────── */
B.on(T.ACCEPT, p=>{
  S.drv = p.driver; S.bearing = p.bearing;
  go("assigned");
  A.heartbeat(false);
  A.earcon.assigned();
  say(`Driver assigned. ${p.driver.name}, ${p.driver.car}. ${p.driver.phon}. About ${S.eta} minutes.`);
  startApproach();
});

function startApproach(){
  clearInterval(S.approachTimer);
  let near = 0;
  log("ear","Earcon","approach tone — tempo rises, no speech spent");
  S.approachTimer = setInterval(()=>{
    if(S.state !== "assigned" && S.state !== "approaching"){ clearInterval(S.approachTimer); return; }
    near = Math.min(1, near + 0.14);
    S.state = "approaching"; render(); pushState();
    A.earcon.approach(Math.sin(S.bearing*Math.PI/180), near);
  }, 1500);
}

B.on(T.ARRIVED, ()=>{
  clearInterval(S.approachTimer);
  go("arrived");
  A.earcon.arrived();
  say("Your car has arrived.", 0);          // tier 0 — interrupts
});

B.on(T.SAYS, p=>{ say(`${S.drv ? S.drv.name : "Your driver"} says: ${p.text}`); });

B.on(T.BEACON, p=>{
  const pan = Math.sin((p.bearing || S.bearing)*Math.PI/180);
  log("ear","Beacon",`panned ${pan > 0 ? "right" : "left"} · bearing ${p.bearing || S.bearing}°`);
  A.earcon.beacon(pan);
});

B.on(T.ONBOARD, ()=>{
  go("intrip");
  A.heartbeat(true);
  say(`Trip started. About ${S.eta + 8} minutes to ${S.dest ? S.dest.n : "your destination"}.`);
});

B.on(T.DEVIATION, ()=>{
  S.sos = true; render();
  log("warn","Deviation","route deviation detected → guardian notified");
  A.earcon.alarm();
  say("The route has changed. I have alerted your guardian. Say cancel if this is fine.", 0);
});

B.on(T.COMPLETE, p=>{
  clearInterval(S.approachTimer);
  A.heartbeat(false);
  S.fare = p.fare || S.fare;
  go("done");
  say(`You have arrived at ${S.dest ? S.dest.n : "your destination"}. Fare is ${S.fare} rupees. Please pay ${S.drv ? S.drv.name : "the driver"} in cash. Say book again for another ride.`);
});

/* ── render: one function, one branch per state ────────── */
function ear(label){
  return `<div class="ear ${V.live ? "on" : ""}"><span class="dot"></span>${label}</div>`;
}

/* The sleeping indicator. Distinct from `ear`, because "I am running
   and filtering" and "I am listening to you right now" are different
   states and conflating them is what made the old build feel broken. */
function earSleep(){
  if(!handsFree || !V.supported()) return "";
  if(micProblem) return `<div class="ear bad"><span class="dot"></span>Microphone blocked</div>`;
  return `<div class="ear sleep ${V.live ? "on" : ""}"><span class="dot"></span>Say &ldquo;Hey&nbsp;Cab&rdquo;</div>`;
}

/* Post-booking states sleep on the wake word too, so the hint has to
   carry the wake word with it. "Say cancel" was a lie the moment the
   filter went in. */
function earWake(hint){
  if(!handsFree || !V.supported()) return "";
  if(micProblem) return `<div class="ear bad"><span class="dot"></span>Microphone blocked</div>`;
  return `<div class="ear sleep ${V.live ? "on" : ""}"><span class="dot"></span>${hint}</div>`;
}

/* A denied microphone cannot be un-denied from JavaScript. Once we are
   here the only useful thing left is to say precisely which clicks fix
   it — on screen for the sighted helper, and aloud for the rider. */
const MIC_FIX = {
  "not-allowed": [
    "Click the padlock (or sliders) icon in the address bar",
    "Choose <b>Site settings</b>",
    "Set <b>Microphone</b> to <b>Allow</b>",
    "Reload this page"
  ],
  "service-not-allowed": [
    "Click the padlock icon in the address bar",
    "Choose <b>Site settings</b> → <b>Microphone</b> → <b>Allow</b>",
    "Reload this page"
  ],
  "storm": [
    "Click the padlock icon in the address bar",
    "Set <b>Microphone</b> to <b>Allow</b> — not <b>Ask</b>",
    "Reload this page"
  ],
  "insecure": [
    "Close this tab",
    "Run <code>serve.bat</code> in the project folder",
    "Open <code>http://localhost:8000/rig.html</code>"
  ],
  "unsupported": [
    "Open this page in <b>Google Chrome</b>"
  ]
};

function micBanner(){
  if(!micProblem) return "";
  const steps = MIC_FIX[micProblem.code];
  const how = steps
    ? `<ol class="fix">${steps.map(s=>`<li>${s}</li>`).join("")}</ol>`
    : "";
  return `<div class="micwarn"><b>Microphone blocked.</b> ${micProblem.msg}${how}</div>`;
}

function render(){
  const s = S.state;
  let h = "";

  if(s === "idle") h = `
    <div class="orb ${V.live && !micProblem ? "sleep" : ""}" id="orb">👂</div>
    <div class="display">Say &ldquo;Hey Cab&rdquo;</div>
    <div class="say">Then tell me where to go — or say it all in one breath:
      <em>&ldquo;Hey Cab, take me to Anna Nagar.&rdquo;</em></div>
    ${earSleep()}
    ${micBanner()}
    <div class="chips">${N.SAMPLES.map((x,i)=>`<button class="chip" data-s="${i}">${x}</button>`).join("")}</div>
    <div class="tiny">Samples let you demo without a microphone.
      <button class="linkbtn" id="mic">Or tap to talk</button></div>`;

  if(s === "listening") h = `
    <button class="orb live talk" id="mic">🎙</button>
    <div class="big amber">${S.transcript || "Listening…"}</div>
    <button class="act ghost maxw" id="stop">Stop</button>`;

  if(s === "resolving") h = `
    <div class="orb think">◌</div>
    <div class="big">Working on it</div>
    <div class="say">${S.transcript}</div>`;

  if(s === "clarify") h = `
    <div class="orb ok">?</div>
    <div class="big">Which one?</div>
    ${ear("Say it out loud")}
    <div class="stack maxw">
      ${S.candidates.map((c,i)=>`<button class="act" data-c="${i}">${c.n}</button>`).join("")}
    </div>`;

  if(s === "confirming") h = `
    <div class="orb ok">✓</div>
    <div class="display">${S.dest.n}</div>
    <div class="say">${S.rideType} · booking in ${Math.max(0,S.undoLeft).toFixed(1)}s</div>
    <div class="undo"><i style="width:${Math.max(0,S.undoLeft)/UNDO_SECONDS*100}%"></i></div>
    ${ear("Say “cancel” to stop")}
    <button class="act danger maxw" id="undo">Cancel</button>`;

  if(s === "finding") h = `
    <div class="orb">◍</div>
    <div class="big">Finding a driver</div>
    <div class="say">You'll hear a heartbeat while I work. Silence would mean something is wrong.</div>
    ${earWake("Hey Cab, cancel &middot; Hey Cab, status")}`;

  if(s === "assigned" || s === "approaching") h = `
    <div class="orb ok">🚗</div>
    <div class="display">${S.drv.name}</div>
    <div class="mid">${S.drv.car}</div>
    <div class="plate teal">${S.drv.plate}</div>
    <div class="say">${s === "approaching" ? "Getting closer — listen to the tone" : "About " + S.eta + " minutes away"}</div>
    ${earWake("Hey Cab, status &middot; Hey Cab, call driver")}
    <button class="act maxw" id="call">Call driver</button>`;

  if(s === "arrived") h = `
    <div class="compass"><span style="transform:rotate(${S.bearing}deg);display:block">↑</span></div>
    <div class="display teal">Car is here</div>
    <div class="plate">${S.drv.plate}</div>
    <div class="say">Bearing ${S.bearing}° — the tone comes from that side</div>
    ${earWake("Hey Cab, call driver")}
    <button class="act maxw" id="call">Call driver</button>`;

  if(s === "intrip") h = `
    <div class="orb ok">➔</div>
    <div class="display">On the way</div>
    <div class="mid">${S.dest.n}</div>
    ${earWake("Hey Cab, where are we?")}
    <div class="stack maxw">
      <button class="act" id="status">Status now</button>
      <button class="act ghost" id="call">Call driver</button>
    </div>`;

  if(s === "done") h = `
    <div class="orb ok">✓</div>
    <div class="display">₹${S.fare}</div>
    <div class="mid">Pay ${S.drv ? S.drv.name : "the driver"} in cash</div>
    ${earWake("Hey Cab, book again")}
    <button class="act pri maxw" id="again">Book again</button>`;

  $("surface").innerHTML = h;

  const old = document.querySelector(".sos-full");
  if(old) old.remove();
  if(S.sos){
    const o = document.createElement("div");
    o.className = "sos-full";
    o.innerHTML = `
      <div class="display red">Route changed</div>
      <div class="say">Your guardian has been notified.</div>
      ${earWake("Hey Cab, cancel &mdash; if this is fine")}
      <div class="stack">
        <button class="act danger" id="sos-call">Call emergency</button>
        <button class="act ghost" id="sos-ok">This is fine</button>
      </div>`;
    document.body.appendChild(o);
  }
}

/* Repaint the indicator whenever the mic opens or closes.
   Three distinguishable states, not two: blocked, sleeping on the
   wake word, and listening to you right now. */
function paintEar(){
  // Recovered? Clear the warning. A stale "microphone blocked" banner on
  // a working mic is its own bug — and the hold-conflict fallback is
  // designed to recover, so this path really does get taken.
  if(micProblem && !V.fatal && V.live){
    micProblem = null;
    log("note","Mic recovered", "listening again" +
        (V.holding ? " — stream held" : " — using the saved permission"));
    render();
  }

  document.querySelectorAll(".ear").forEach(e => e.classList.toggle("on", V.live));
  const orb = $("orb");
  if(orb) orb.classList.toggle("sleep", V.live && !micProblem);
  // "Mic held" is worth showing: it is the state in which no further
  // permission prompt is possible, and the old build never reached it.
  const label = micProblem     ? "Mic blocked"
              : W.awake        ? "Listening to you"
              : V.live         ? "Awake for “Hey Cab”"
              : V.holding      ? "Mic held — no more prompts"
              :                  "Mic closed";
  $("mic-state").textContent = label;
}
V.onStateChange = paintEar;

/* A fatal microphone fault must reach the user, not just the console. */
V.onError = e=>{
  if(e.permanent){ showMicProblem({ok:false, code:e.code, msg:e.msg}); return; }
  // Recoverable. Notably "hold-conflict": we let go of the device and
  // are about to try again, which is a note, not a failure.
  log("note","Mic", e.msg);
};

/* ── events ────────────────────────────────────────────── */
document.addEventListener("click", e=>{
  const t = e.target.closest("button");
  if(!t) return;

  if(t.id === "mic"){ S.state === "listening" ? stopListening() : startListening(); return; }
  if(t.id === "stop"){ tap(); stopListening(); return; }
  if(t.dataset.s !== undefined){ useSample(N.SAMPLES[+t.dataset.s]); return; }
  if(t.dataset.c !== undefined){ tap(); V.stop(); commit(S.candidates[+t.dataset.c]); return; }
  if(t.id === "undo"){ cancelUndo(false); return; }
  if(t.id === "call"){ tap(); log("note","Call","masked call placed"); say("Calling your driver."); return; }
  if(t.id === "status"){ tap(); sayStatus(); return; }
  if(t.id === "again"){ tap(); newRide(); return; }
  if(t.id === "sos-call"){ tap(); log("warn","Emergency","emergency call placed"); A.earcon.alarm(); return; }
  if(t.id === "sos-ok"){ tap(); S.sos = false; render(); log("note","Dismissed","user marked it a false alarm"); return; }

  if(t.id === "b-audio"){
    A.enabled = !A.enabled;
    t.textContent = A.enabled ? "Audio on" : "Audio off";
    return;
  }
  if(t.id === "b-hands"){
    handsFree = !handsFree;
    t.textContent = "Hands-free: " + (handsFree ? "on" : "off");
    log("note","Hands-free", handsFree ? "on — the mic reopens after every question"
                                       : "off — buttons only");
    // Hands-free off gives the microphone back: the browser's recording
    // indicator must never claim we are listening when we are not.
    if(handsFree){ armWake(); } else { W.disarm(); V.releaseMic(); }
    render();
    return;
  }
  if(t.id === "b-out"){ CE.auth.signOut("rider"); return; }
});

// Spacebar stands in for a hardware talk key — works with the screen off.
document.addEventListener("keydown", e=>{
  if(e.code === "Space" && !e.repeat && S.state === "idle" &&
     !/INPUT|TEXTAREA/.test(document.activeElement.tagName)){
    e.preventDefault(); startListening();
  }
});
document.addEventListener("keyup", e=>{
  if(e.code === "Space" && S.state === "listening"){ e.preventDefault(); stopListening(); }
});

$("sos-edge").addEventListener("click", ()=>{
  A.unlock(); prime(); tap();
  S.sos = true; render();
  A.earcon.alarm();
  log("warn","SOS","user triggered SOS from the edge");
  say("Emergency. Calling your guardian.", 0);
});

function newRide(){
  clearInterval(S.undoTimer); clearInterval(S.approachTimer);
  A.heartbeat(false); V.stop();
  A.shutUp();
  fresh({taps:S.taps, turns:S.turns});
  render(); pushState(); pushMarks(); pushMetrics();
  log("note","Ready","new booking — say Hey Cab, then where you want to go");
  say("Ready. Say Hey Cab whenever you want another ride.", 0, listenForState);
}

/** Reset for a new booking without the spoken preamble — used when
    the user has already said where they want to go. */
function newRideSilently(){
  clearInterval(S.undoTimer); clearInterval(S.approachTimer);
  A.heartbeat(false);
  fresh({taps:S.taps, turns:S.turns});
  pushState(); pushMarks(); pushMetrics();
}

/* ── first sound needs a user gesture in every browser ──
   And so, in practice, does the microphone: SpeechRecognition raises
   its own permission prompt mid-utterance, which eats the first
   sentence. Asking for the grant here, during the priming gesture,
   means the wake word is live before the user has said anything. */
function prime(){
  if(primed) return;
  primed = true;
  A.unlock();

  const d = V.diagnose();
  if(!d.ok){
    say("Ready, but I cannot hear you. " + d.msg, 0);
    showMicProblem(d);
    return;
  }

  V.requestPermission().then(res=>{
    if(!res.ok){
      showMicProblem(res);
      return;
    }
    log("note","Mic granted",
      V.holding
        ? "stream <b>held open</b> — Chrome cannot prompt again for this page"
        : "permission granted (this browser will not let us hold the stream)");
    say("Ready. Say Hey Cab, then tell me where to go.", 0, listenForState);
  });
}
document.addEventListener("pointerdown", prime, {once:true});
document.addEventListener("keydown", prime, {once:true});

render(); pushState(); pushMetrics();

(function boot(){
  const d = V.diagnose();
  if(d.ok){
    log("note","Rider ready",
      `wake word <b>&ldquo;Hey Cab&rdquo;</b> — touch the screen once to grant the microphone, then never again`);

    // Say out loud, in the log, what Chrome already thinks. A returning
    // user on a remembered origin sees "granted" here and will get no
    // prompt at all; "prompt" means exactly one is coming.
    V.queryPermission().then(state=>{
      if(state === "granted")
        log("note","Microphone","already allowed for this origin — no prompt will appear");
      else if(state === "denied")
        log("warn","Microphone","blocked for this origin. " + V.message("not-allowed"));
      else if(state === "prompt")
        log("note","Microphone","Chrome will ask once, on your first touch, and remember it");
    });
  }else{
    log("warn","Rider ready", d.msg);
    micProblem = d; render();
  }

  // Ask the backend whether it is there — once, without blocking
  // anything. A "no" costs the app nothing; nlu.js resolves locally.
  if(CE.api){
    CE.api.probe().then(p=>{
      if(p.up){
        log("note","Backend",
          `Spring Boot at <em>${CE.api.base}</em> — intent resolved server-side` +
          (p.cloudSpeech ? " · Google cloud speech available" : " · browser speech only"));
      }else{
        log("note","Backend",
          `not running at <em>${CE.api.base}</em> — resolving in the browser instead. ` +
          `Nothing is lost; the gazetteer and the ambiguity rule are the same.`);
      }
    });
  }
})();
})();
