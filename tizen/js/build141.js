/*
 * Build 141 (versie 1.0.1): beveiliging, gelijk aan Build141Logic.kt op Android (zelfde gevallen in
 * tizen/test/build141_test.js en tools/test/Build141LogicTest.kt), en de feedback van gebruikers (Home-indeling,
 * Mijn lijst, Verder kijken; gelijk aan Build141Feedback.kt, tools/test/Build141FeedbackTest.kt).
 * - lockout: oplopende wachttijd na 5 foute pincodes op rij (30 s, 1 min, 5 min, 15 min, 1 uur)
 * - traktOAuthBase / osHost: vaste lijst voor Trakt (OAuth) en OpenSubtitles
 * - B.vault: gevoelige waarden (sessie van het account, servers met wachtwoorden, pincodes, Trakt/OpenSubtitles-tokens,
 *   app-sleutels) niet meer in localStorage maar in IndexedDB, versleuteld met AES-GCM (Web Crypto) met een sleutel die
 *   niet uit te lezen is (non-extractable) en per installatie wordt gemaakt.
 *
 *   Beperking (Tizen heeft geen keystore voor web-apps): de sleutel staat in dezelfde app-opslag als de gegevens. Dit
 *   beschermt tegen het kopiëren of uitlezen van localStorage (bijv. via de web-inspector van een ontwikkelaars-tv of
 *   een gekopieerd opslagbestand), niet tegen iemand met volledige toegang tot het bestandssysteem van de tv. Kent de tv
 *   Web Crypto niet, dan staan de waarden onversleuteld in IndexedDB; kent hij ook IndexedDB niet (of reageert die
 *   niet), dan blijft alles in localStorage zoals vóór build 141.
 * Geschreven voor Chromium M56 (ES2017, geen ?. ?? of spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var X = B.b141 = {};

  // MARK: pincode-limiet

  var FREE = X.FREE_ATTEMPTS = 5;
  var WAITS = [30000, 60000, 300000, 900000, 3600000];
  X.lockout = {
    /** Wachttijd na f fouten op rij (0 = meteen opnieuw). */
    waitAfter: function (f) { return f < FREE ? 0 : WAITS[Math.min(f - FREE, WAITS.length - 1)]; },
    /** Resterende wachttijd; klok teruggezet (nu vóór de laatste fout) = de hele wachttijd opnieuw. */
    remaining: function (s, now) {
      s = s || {};
      var w = X.lockout.waitAfter(s.f || 0);
      if (!w) return 0;
      var t = s.t || 0;
      if (now < t) return w;
      return Math.max(0, t + w - now);
    },
    failed: function (s, now) { return { f: ((s && s.f) || 0) + 1, t: now }; },
    /** "30 s", "2 min", "1 h" (naar boven afgerond). */
    shortText: function (ms) {
      var s = Math.floor((ms + 999) / 1000);
      if (s < 60) return s + ' s';
      if (s < 3600) return Math.floor((s + 59) / 60) + ' min';
      return Math.floor((s + 3599) / 3600) + ' h';
    }
  };
  // stand op deze tv (geen geheim, wel blijvend: een herstart wist hem niet)
  var monoUntil = 0;
  function nowMono() { try { return window.performance && performance.now ? performance.now() : Date.now(); } catch (e) { return Date.now(); } }
  X.pinRemaining = function () {
    var wall = X.lockout.remaining(B.store.get('pinLockout', null), Date.now());
    return Math.max(wall, Math.max(0, monoUntil - nowMono()));
  };
  X.pinFailed = function () {
    var s = X.lockout.failed(B.store.get('pinLockout', null), Date.now());
    B.store.set('pinLockout', s);
    var w = X.lockout.waitAfter(s.f);
    monoUntil = w ? nowMono() + w : 0;
  };
  X.pinSucceeded = function () { B.store.del('pinLockout'); monoUntil = 0; };

  // MARK: vaste hosts

  var TRAKT = X.TRAKT_OAUTH = ['https://api.trakt.tv', 'https://auth.trakt.tv'];
  X.traktOAuthBase = function (stored) {
    var s = String(stored == null ? '' : stored).trim().replace(/\/+$/, '').toLowerCase();
    return TRAKT.indexOf(s) >= 0 ? s : TRAKT[0];
  };
  var OS_DEFAULT = X.OS_DEFAULT = 'api.opensubtitles.com';
  /** OpenSubtitles-host zonder https:// (zoals `base_url`): alleen opensubtitles.com of een subdomein daarvan. */
  X.osHost = function (raw) {
    var s = String(raw == null ? '' : raw).trim().toLowerCase();
    if (!s) return OS_DEFAULT;
    if (s.indexOf('://') >= 0) {
      if (s.indexOf('https://') !== 0) return OS_DEFAULT;
      s = s.slice(8);
    }
    s = s.split('/')[0].replace(/\.+$/, '');
    if (!s || !/^[a-z0-9.-]+$/.test(s)) return OS_DEFAULT;
    return (s === 'opensubtitles.com' || /\.opensubtitles\.com$/.test(s)) ? s : OS_DEFAULT;
  };

  // MARK: feedback (zelfde regels als Build141Feedback.kt op Android, tools/test/Build141FeedbackTest.kt)

  /**
   * Home: de rij "Nieuw toegevoegd" (recent) is weg; "Nieuw op Bingr" (streaming) staat op zijn plek, met zijn eigen
   * aan/uit. rows = [{key, visible}] (de opgeslagen indeling verandert niet; andere apps kennen beide rijen nog).
   */
  X.homeRows = {
    displayed: function (rows) {
      var iR = -1, iN = -1, i;
      for (i = 0; i < rows.length; i++) { if (rows[i].key === 'recent' && iR < 0) iR = i; if (rows[i].key === 'streaming' && iN < 0) iN = i; }
      if (iR < 0) return rows.slice();
      var out = [];
      for (i = 0; i < rows.length; i++) {
        if (i === iR) { if (iN >= 0) out.push(rows[iN]); }
        else if (i !== iN) out.push(rows[i]);
      }
      return out;
    }
  };

  /** Mijn lijst: TMDB-titels ("tmdb-movie-123") horen bij geen server; ook onder een andere server-id tellen ze mee. */
  X.myList = {
    itemOf: function (k) { var bar = String(k).indexOf('|'); return bar < 0 ? String(k) : String(k).slice(bar + 1); },
    isExternal: function (id) { return String(id || '').indexOf('tmdb-') === 0; },
    externalID: function (k) { var id = X.myList.itemOf(k); return X.myList.isExternal(id) ? id : null; },
    /** Sleutel voor een titel: een TMDB-titel met een bestaande sleutel (andere server) houdt die; anders src|id. */
    keyFor: function (existing, src, id) {
      if (X.myList.isExternal(id)) for (var i = 0; i < existing.length; i++) if (X.myList.itemOf(existing[i]) === id) return existing[i];
      return src + '|' + id;
    }
  };

  /** Verder kijken: de poster van de film/serie gaat voor; de bewaarde afbeelding (vaak een still) alleen als terugval. */
  X.continueArt = function (titlePoster, metaPoster, stored) {
    function ok(v) { return v && String(v).trim() ? v : null; }
    return ok(titlePoster) || ok(metaPoster) || ok(stored) || null;
  };

  /** Label op de tegels van Nieuw op Bingr en Voor jou: FILM of SERIE. */
  X.typeBadge = function (type) { return type === 'series' ? B.L('SERIES') : B.L('FILM'); };

  // MARK: kluis (gevoelige waarden)

  var VAULT_KEYS = X.VAULT_KEYS = ['auth', 'pins', 'sources', 'connections', 'serverKeys'];
  var PREFIX = 'bingr.';
  var isVault = {};
  VAULT_KEYS.forEach(function (k) { isVault[k] = true; });

  var V = B.vault = {};
  var mode = 'pending';   // pending → idb (kluis) of ls (terugval: localStorage zoals vroeger)
  var mem = {};           // sleutel → JSON-tekst
  var dirty = {};         // vóór het laden gewijzigd: niet overschrijven met de opgeslagen waarde
  var hooks = [];
  var vdb = null, vkey = null;
  var chain = {};         // per sleutel achter elkaar wegschrijven

  V.mode = function () { return mode; };
  V.encrypted = function () { return mode === 'idb' && !!vkey; };
  /** fn wordt aangeroepen zodra de kluis geladen is (modules lezen dan hun gevoelige waarden opnieuw). */
  V.onLoad = function (fn) { hooks.push(fn); };

  var base = { get: B.store.get, set: B.store.set, del: B.store.del };
  function ls() { try { return window.localStorage; } catch (e) { return null; } }
  function lsGet(k) { var s = ls(); if (!s) return null; try { return s.getItem(PREFIX + k); } catch (e) { return null; } }
  function lsDel(k) { var s = ls(); if (!s) return; try { s.removeItem(PREFIX + k); } catch (e) { /* stil */ } }

  B.store.get = function (k, def) {
    if (!isVault[k] || mode === 'ls') return base.get(k, def);
    var raw = B.has(mem, k) ? mem[k] : null;
    if (raw == null) return def === undefined ? null : def;
    try { return JSON.parse(raw); } catch (e) { return def === undefined ? null : def; }
  };
  B.store.set = function (k, v) {
    if (!isVault[k] || mode === 'ls') return base.set(k, v);
    var raw = JSON.stringify(v);
    mem[k] = raw;
    if (mode === 'pending') { dirty[k] = true; return true; }
    persist(k, raw);
    return true;
  };
  B.store.del = function (k) {
    if (!isVault[k] || mode === 'ls') return base.del(k);
    delete mem[k];
    if (mode === 'pending') { dirty[k] = true; return; }
    persist(k, null);
  };

  function req(r) {
    return new Promise(function (resolve, reject) {
      r.onsuccess = function () { resolve(r.result); };
      r.onerror = function () { reject(r.error || new Error('idb')); };
    });
  }
  function store(name, rw) { return vdb.transaction(name, rw ? 'readwrite' : 'readonly').objectStore(name); }
  function subtle() { try { return window.crypto && window.crypto.subtle ? window.crypto.subtle : null; } catch (e) { return null; } }
  function utf8(s) { return new TextEncoder().encode(s); }
  function fromUtf8(b) { return new TextDecoder().decode(b); }

  function seal(raw) {
    var c = subtle();
    if (!c || !vkey || !window.TextEncoder) return Promise.resolve({ v: 0, p: raw });
    var iv = new Uint8Array(12);
    window.crypto.getRandomValues(iv);
    return c.encrypt({ name: 'AES-GCM', iv: iv }, vkey, utf8(raw)).then(function (ct) { return { v: 1, iv: iv, c: ct }; });
  }
  function open(rec) {
    if (!rec) return Promise.resolve(null);
    if (rec.v === 0) return Promise.resolve(typeof rec.p === 'string' ? rec.p : null);
    var c = subtle();
    if (rec.v !== 1 || !c || !vkey) return Promise.resolve(null);
    return c.decrypt({ name: 'AES-GCM', iv: rec.iv }, vkey, rec.c).then(fromUtf8, function () { return null; });
  }
  function persist(k, raw) {
    var prev = chain[k] || Promise.resolve();
    var p = prev.then(function () {
      if (!vdb) return;
      if (raw == null) return req(store('kv', true).delete(k));
      return seal(raw).then(function (rec) { return req(store('kv', true).put(rec, k)); });
    }).then(function () { return true; }, function (e) { B.log('kluis: opslaan mislukt', k, String(e && e.message || e)); return false; });
    chain[k] = p;
    return p;
  }

  function loadKey() {
    var c = subtle();
    if (!c || !window.TextEncoder) return Promise.resolve(null);
    return req(store('keys', false).get('k1')).then(function (k) {
      if (k) return k;
      return c.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']).then(function (nk) {
        return req(store('keys', true).put(nk, 'k1')).then(function () { return nk; });
      });
    }).catch(function () { return null; });
  }

  function finish(m) {
    if (mode !== 'pending') return;
    mode = m;
    if (m === 'ls') {
      // terugval: wat vóór het laden gezet is, alsnog in localStorage
      Object.keys(dirty).forEach(function (k) { if (B.has(mem, k)) base.set(k, JSON.parse(mem[k])); else base.del(k); });
      mem = {};
    } else {
      Object.keys(dirty).forEach(function (k) { persist(k, B.has(mem, k) ? mem[k] : null); });
    }
    dirty = {};
    hooks.forEach(function (fn) { try { fn(); } catch (e) { B.log('kluis: onLoad', String(e && e.message || e)); } });
  }

  /** Laden (en eenmalig overzetten uit localStorage). Altijd binnen ~4 s klaar; daarna gaat de app verder. */
  V.ready = new Promise(function (resolve) {
    var done = false;
    function end(m) { if (done) return; done = true; finish(m); resolve(m); }
    setTimeout(function () { end('ls'); }, 4000);
    var idb = null;
    try { idb = window.indexedDB; } catch (e) { idb = null; }
    if (!idb) { end('ls'); return; }
    var r;
    try { r = idb.open('bingr-vault', 1); } catch (e) { end('ls'); return; }
    r.onupgradeneeded = function () { var d = r.result; d.createObjectStore('kv'); d.createObjectStore('keys'); };
    r.onerror = function () { end('ls'); };
    r.onsuccess = function () {
      vdb = r.result;
      var migrated = [];
      loadKey().then(function (k) {
        vkey = k;
        return Promise.all(VAULT_KEYS.map(function (key) {
          var legacy = lsGet(key);
          // vóór het laden al gewijzigd: die waarde wint (finish schrijft hem weg)
          if (dirty[key]) { if (legacy != null) migrated.push(key); return null; }
          if (legacy != null) {
            // localStorage is nieuwer (oude versie, of een keer de terugval): overnemen
            mem[key] = legacy;
            return persist(key, legacy).then(function (ok) { if (ok) migrated.push(key); });
          }
          return req(store('kv', false).get(key)).then(open).then(function (raw) {
            if (raw != null && !dirty[key]) mem[key] = raw;
          }, function () { /* onleesbaar: weg */ });
        }));
      }).then(function () {
        // pas uit localStorage halen als de kluis echt gebruikt wordt (niet na de terugval door de tijdslimiet)
        if (done) return;
        migrated.forEach(lsDel);
        end('idb');
      }, function () { end('ls'); });
    };
  });
})();
