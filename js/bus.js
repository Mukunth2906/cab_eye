/* ═══════════════════════════════════════════════════════════
   bus.js — the event bus between the rider app and the driver app.

   Uses BroadcastChannel: real message passing between two browser
   windows (or two iframes) on the same origin, with no server.

   This is deliberately the same publish/subscribe shape as the
   WebSocket the production Ktor backend will use, so swapping it out
   later means changing this one file and nothing else.

   NOTE: needs a real origin. Serve the folder — `python -m http.server 8000`
   — rather than double-clicking the HTML files.
   ═══════════════════════════════════════════════════════════ */

window.CE = window.CE || {};

CE.bus = (function(){
  const NAME = "cab-eye";
  const subs = [];          // {type, fn}
  const seen = new Set();   // message ids already handled, so nothing loops
  let ch = null, ready = false;

  function setup(){
    if(ready) return;
    ready = true;

    // Path 1: BroadcastChannel — separate windows, when the folder is served.
    if("BroadcastChannel" in window){
      try{
        ch = new BroadcastChannel(NAME);
        ch.onmessage = e => take(e.data);
      }catch(e){ ch = null; }
    }

    // Path 2: postMessage — works between a page and its iframes even from
    // file://, so the test rig runs by double-clicking, with no server.
    window.addEventListener("message", e=>{
      const m = e.data;
      if(!m || !m.__ce) return;
      if(take(m)) relay(m);
    });
  }

  function id(){ return Math.random().toString(36).slice(2) + Date.now().toString(36); }

  /** Handle a message once. Returns false if it was already seen. */
  function take(msg){
    if(!msg || !msg.type || seen.has(msg.id)) return false;
    seen.add(msg.id);
    if(seen.size > 400) seen.delete(seen.values().next().value);
    subs.forEach(s=>{ if(s.type === msg.type || s.type === "*") s.fn(msg.payload || {}, msg); });
    return true;
  }

  /** Pass a message up to the host page and down into any embedded frames. */
  function relay(msg){
    try{
      if(window.parent && window.parent !== window) window.parent.postMessage(msg, "*");
    }catch(e){}
    document.querySelectorAll("iframe").forEach(f=>{
      try{ f.contentWindow.postMessage(msg, "*"); }catch(e){}
    });
  }

  return {
    /** Send a message to every other window and frame listening. */
    publish(type, payload){
      setup();
      const msg = {__ce:true, id:id(), type, payload: payload || {}, ts: Date.now()};
      seen.add(msg.id);
      if(ch){ try{ ch.postMessage(msg); }catch(e){} }
      relay(msg);
    },
    /** Listen for one message type. Use "*" for everything. */
    on(type, fn){ setup(); subs.push({type, fn}); },
    /** Message names, in one place so the two apps can't drift apart. */
    T: {
      RIDE_REQUEST : "ride:request",
      RIDE_CANCEL  : "ride:cancel",
      DRIVER_ONLINE: "driver:online",
      ACCEPT       : "ride:accept",
      ARRIVED      : "ride:arrived",
      SAYS         : "ride:says",
      BEACON       : "ride:beacon",
      ONBOARD      : "ride:onboard",
      DEVIATION    : "ride:deviation",
      COMPLETE     : "ride:complete",
      LOG          : "rig:log",
      STATE        : "rig:state",
      MARKS        : "rig:marks",
      METRICS      : "rig:metrics"
    }
  };
})();
