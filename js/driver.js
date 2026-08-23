/* ═══════════════════════════════════════════════════════════
   driver.js — the sighted driver's app.

   Conventional UI on purpose: this user can see, and is driving.
   No speech recognition, no text-to-speech. What makes it different
   from an ordinary driver app is four accessibility affordances:

     1. a non-dismissable "passenger is visually impaired" badge
     2. preset position phrases — the driver taps, the RIDER HEARS
     3. an audio beacon the passenger can home in on
     4. an explicit "passenger is seated" confirmation before starting

   (2) is the modality bridge: each side uses the channel that suits
   their situation, and the system translates between them.
   ═══════════════════════════════════════════════════════════ */

(function(){
const B = CE.bus, T = CE.bus.T;
const $ = id => document.getElementById(id);

const me = CE.auth.require("driver");
if(!me) return;
$("hello").textContent = me.name;
$("vehicle").textContent = me.car + " · " + me.plate;

const POSITIONS = [
  "I'm right in front of you, near the gate",
  "I'm about twenty metres to your left",
  "I'm behind the blue bus, please wait there",
  "I'm across the road, I'll come to you"
];

let S = {screen:"offline", ride:null, bearing:0, code:"", auto:false};

function log(kind, key, msg){ B.publish(T.LOG, {kind, key, msg}); }
function go(s){ S.screen = s; render(); B.publish(T.STATE, {driver:s}); }

/* ── incoming from the rider app ───────────────────────── */
B.on(T.RIDE_REQUEST, p=>{
  if(S.screen !== "waiting") return;
  S.ride = p;
  S.bearing = Math.round(Math.random()*360);
  S.code = String(1000 + Math.floor(Math.random()*8999));
  go("request");
  // Auto mode drives the whole driver side so one person can demo alone.
  if(S.auto){
    setTimeout(()=>{ if(S.screen === "request") click("d-accept"); }, 2200);
    setTimeout(()=>{ if(S.screen === "topickup") click("d-arrived"); }, 9000);
  }
});

function click(id){ const el = $(id); if(el) el.click(); }

B.on(T.RIDE_CANCEL, ()=>{
  if(["request","topickup","arrived"].includes(S.screen)){
    log("drv","Cancelled","the rider cancelled");
    go("waiting");
  }
});

/* ── render ────────────────────────────────────────────── */
function render(){
  const s = S.screen, r = S.ride;
  let h = "";

  if(s === "offline") h = `
    <div class="fill mid-center">
      <div class="display">Offline</div>
      <div class="say">Go online to start receiving ride requests.</div>
    </div>
    <button class="act pri" id="d-online">Go online</button>`;

  if(s === "waiting") h = `
    <div class="fill mid-center">
      <div class="display teal">Online</div>
      <div class="say">Waiting for a request. Book a ride on the rider app and it will appear here.</div>
    </div>
    <button class="act ghost" id="d-offline">Go offline</button>`;

  if(s === "request") h = `
    <div class="badge"><b>⚠ PASSENGER IS VISUALLY IMPAIRED</b><br>
      You must help them find your car</div>
    <div class="fill">
      <div class="row"><span class="k">Pickup</span><span class="v">Kilpauk</span></div>
      <div class="row"><span class="k">Drop</span><span class="v">${r.dest}</span></div>
      <div class="row"><span class="k">Ride</span><span class="v">${r.rideType}</span></div>
      <div class="row"><span class="k">Fare</span><span class="v">₹${r.fare}</span></div>
    </div>
    <div class="stack">
      <button class="act pri" id="d-accept">Accept</button>
      <button class="act ghost" id="d-decline">Decline</button>
    </div>`;

  if(s === "topickup") h = `
    <div class="badge"><b>⚠ VISUALLY IMPAIRED PASSENGER</b></div>
    <div class="fill">
      <div class="row"><span class="k">Passenger</span><span class="v">Waiting at Kilpauk</span></div>
      <div class="row"><span class="k">Drop</span><span class="v">${r.dest}</span></div>
      <div class="row"><span class="k">Your ETA</span><span class="v">${r.eta} min</span></div>
    </div>
    <div class="stack">
      <button class="act pri" id="d-arrived">I've arrived</button>
      <button class="act ghost" id="d-call">Call passenger</button>
    </div>`;

  if(s === "arrived") h = `
    <div class="badge"><b>TELL THEM WHERE YOU ARE</b><br>
      They cannot see your car — tap a phrase and they will hear it</div>
    <div class="fill presets" style="justify-content:flex-start;padding-top:.3rem">
      ${POSITIONS.map((p,i)=>`<button class="preset" data-p="${i}">“${p}”</button>`).join("")}
    </div>
    <div class="stack">
      <button class="act" id="d-beacon">Play audio beacon</button>
      <button class="act pri" id="d-onboard">Passenger is seated</button>
    </div>`;

  if(s === "intrip") h = `
    <div class="fill">
      <div class="row"><span class="k">Drop</span><span class="v">${r.dest}</span></div>
      <div class="row"><span class="k">Fare</span><span class="v">₹${r.fare}</span></div>
      <div class="row"><span class="k">Verification code</span><span class="v"></span></div>
      <div class="code">${S.code}</div>
    </div>
    <div class="stack">
      <button class="act pri" id="d-complete">Complete trip</button>
      <button class="act ghost" id="d-deviate">Simulate route deviation</button>
    </div>`;

  if(s === "complete") h = `
    <div class="fill mid-center">
      <div class="display">₹${r.fare}</div>
      <div class="say">Collect in cash from the passenger.</div>
    </div>
    <button class="act pri" id="d-next">Next ride</button>`;

  $("screen").innerHTML = h;
}

/* ── events: every tap becomes something the rider hears ─ */
document.addEventListener("click", e=>{
  const t = e.target.closest("button");
  if(!t) return;

  if(t.id === "d-online"){
    go("waiting");
    B.publish(T.DRIVER_ONLINE, {name:me.name});
    log("drv","Online", me.name + " went online");
    return;
  }
  if(t.id === "d-offline"){ go("offline"); return; }

  if(t.id === "d-accept"){
    go("topickup");
    log("drv","Accepted",`${me.name} · ${me.car} · ${me.plate}`);
    B.publish(T.ACCEPT, {
      driver:{name:me.name, car:me.car, plate:me.plate, phon:me.phon},
      bearing:S.bearing
    });
    return;
  }
  if(t.id === "d-decline"){ log("drv","Declined","looking for another driver"); go("waiting"); return; }

  if(t.id === "d-arrived"){
    go("arrived");
    log("drv","Arrived","driver tapped I've arrived");
    B.publish(T.ARRIVED, {});
    return;
  }
  if(t.id === "d-call"){ log("drv","Call","driver called the passenger"); return; }

  if(t.dataset.p !== undefined){
    const text = POSITIONS[+t.dataset.p];
    log("drv","Says",text);
    B.publish(T.SAYS, {text});          // driver taps → rider hears
    return;
  }

  if(t.id === "d-beacon"){
    log("drv","Beacon",`played · bearing ${S.bearing}°`);
    B.publish(T.BEACON, {bearing:S.bearing});
    return;
  }

  if(t.id === "d-onboard"){
    go("intrip");
    log("drv","Started","passenger confirmed seated");
    B.publish(T.ONBOARD, {});
    return;
  }

  if(t.id === "d-deviate"){
    log("warn","Deviation","driver left the expected route");
    B.publish(T.DEVIATION, {});
    return;
  }

  if(t.id === "d-complete"){
    go("complete");
    log("drv","Complete",`fare ₹${S.ride.fare}`);
    B.publish(T.COMPLETE, {fare:S.ride.fare});
    return;
  }

  if(t.id === "d-next"){ S.ride = null; go("waiting"); return; }

  if(t.id === "b-auto"){
    S.auto = !S.auto;
    t.textContent = "Auto: " + (S.auto ? "on" : "off");
    log("note","Auto driver", S.auto ? "on — the driver side plays itself" : "off");
    return;
  }
  if(t.id === "b-out"){ CE.auth.signOut("driver"); return; }
});

render();
log("note","Driver ready", me.name + " opened the driver app");
})();
