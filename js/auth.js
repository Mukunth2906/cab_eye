/* ═══════════════════════════════════════════════════════════
   auth.js — two roles, two completely different front doors.

   A typed login form is the worst possible screen for a blind user:
   a sequence of fields, each needing traverse → focus → type → verify.
   It is where blind users abandon apps. So the rider does it ONCE —
   set up by a helper, or by speaking their name — and the device keeps
   a token from then on. Every later launch opens straight into the mic.

   The driver is sighted and gets a conventional email + password.

   Sessions are stored per role, so one machine can run the rider app in
   one window and the driver app in another — which is exactly what the
   test rig does.

   Nothing here is real security. Accounts are hardcoded and the session
   lives in localStorage. Production is a device-bound token on the rider
   side and normal credentials on the driver side, both from the backend.
   ═══════════════════════════════════════════════════════════ */

window.CE = window.CE || {};

CE.auth = (function(){
  const KEY = role => "cabeye.session." + role;

  // Demo driver accounts. Replace with the backend later.
  const DRIVERS = [
    {email:"ravi@cabeye.test",   pass:"driver123", name:"Ravi",
     car:"white Swift", plate:"TN 07 CA 1234",
     phon:"Tango-November Zero-Seven, Charlie-Alpha, One-Two-Three-Four"},
    {email:"suresh@cabeye.test", pass:"driver123", name:"Suresh",
     car:"grey Wagon R", plate:"TN 09 BK 4471",
     phon:"Tango-November Zero-Nine, Bravo-Kilo, Four-Four-Seven-One"}
  ];

  // localStorage throws on file:// in some browsers, so keep a memory copy too.
  const mem = {};

  function read(role){
    try{
      const v = localStorage.getItem(KEY(role));
      if(v) return JSON.parse(v);
    }catch(e){}
    return mem[role] || null;
  }
  function write(role, s){
    mem[role] = s;
    try{ localStorage.setItem(KEY(role), JSON.stringify(s)); }catch(e){}
    return s;
  }

  /** The rig opens its frames with ?demo=1 so they boot without a login. */
  function demoWanted(){
    return /[?&]demo=1/.test(location.search);
  }

  return {
    DRIVERS,

    session(role){ return read(role); },

    /** Has this device already been set up for a rider? */
    riderReady(){
      const s = read("rider");
      return !!(s && s.token);
    },

    driverReady(){ return !!read("driver"); },

    /** One-time rider setup. After this the app never asks again. */
    setupRider({name, guardian}){
      return write("rider", {
        role:"rider",
        name:(name || "Rider").trim(),
        guardian:(guardian || "").trim(),
        token:"dev-" + Math.random().toString(36).slice(2) + Date.now().toString(36),
        since:Date.now()
      });
    },

    /** Conventional driver login. Returns the session, or null. */
    loginDriver(email, pass){
      const d = DRIVERS.find(x =>
        x.email.toLowerCase() === String(email).trim().toLowerCase() && x.pass === pass);
      if(!d) return null;
      return write("driver", {role:"driver", name:d.name, email:d.email,
                              car:d.car, plate:d.plate, phon:d.phon, since:Date.now()});
    },

    /** Used by the test rig so both apps can boot without a login round-trip. */
    ensureDemo(){
      if(!read("rider"))  this.setupRider({name:"Harshini", guardian:"98xxxxxxxx"});
      if(!read("driver")) this.loginDriver("ravi@cabeye.test", "driver123");
    },

    /** Guard a page. Sends the user to the front door if they don't belong. */
    require(role){
      let s = read(role);
      if(!s && demoWanted()){
        s = (role === "rider")
          ? this.setupRider({name:"Harshini", guardian:"98xxxxxxxx"})
          : this.loginDriver("ravi@cabeye.test", "driver123");
      }
      if(!s){ location.href = "index.html"; return null; }
      return s;
    },

    signOut(role){
      try{ localStorage.removeItem(KEY(role)); }catch(e){}
      location.href = "index.html";
    }
  };
})();
