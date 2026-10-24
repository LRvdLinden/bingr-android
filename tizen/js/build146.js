/*
 * Build 146 (versie 1.0.1), gelijk aan Build146Logic.kt op Android (zelfde gevallen in tizen/test/build146_test.js en
 * tools/test/Build146LogicTest.kt). Contract: docs/ACCOUNT.md in iptv-ios-appletv, "Build 146".
 * - X.pin: pincode als hash (`pinHash`): PBKDF2-HMAC-SHA256, 100.000 iteraties, salt 16 bytes, hash 32 bytes. Met
 *   WebCrypto als die er is (veilige context), anders de pure-JavaScript-PBKDF2 (pbkdf2Js uit
 *   supabase/functions/_shared/pin.ts, naar ES5), in stukjes zodat de tv blijft reageren ("Controleren…").
 * - X.pinChoice: welke pincode geldt en wanneer een oude leesbare `pin` meteen gehasht en geüpload wordt.
 * - X.profiles: de profiellijst van get_profiles samenvoegen met de profielen op deze tv.
 * - X.support: groepen (Open / In behandeling / Gesloten), labels, de deeplink en de controles van het formulier.
 * - X.diag: apparaattype, opschonen (geen persoonsgegevens), mijlpalen en de startdiagnose (vorige start niet klaar,
 *   fout via window.onerror / unhandledrejection).
 * - X.epg: is de tv-gids verlopen?
 * Geschreven voor Chromium M56 (ES2017, geen ?. ?? of spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var X = B.b146 = {};

  // MARK: base64 (standaard, met opvulling)

  var b64 = X.b64 = {};
  b64.encode = function (u8) {
    var s = '';
    for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
    return btoa(s);
  };
  b64.decode = function (s) {
    if (typeof s !== 'string' || !/^[A-Za-z0-9+/]*={0,2}$/.test(s) || s.length % 4 !== 0) return null;
    try {
      var bin = atob(s), out = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
      return out;
    } catch (e) { return null; }
  };

  // MARK: pincode als hash

  var P = X.pin = { ALG: 'pbkdf2-sha256', ITER: 100000, SALT_BYTES: 16, HASH_BYTES: 32, ITER_MIN: 10000, ITER_MAX: 2000000 };
  P.valid = function (pin) { return typeof pin === 'string' && /^[0-9]{4,8}$/.test(pin); };
  /** Oude, leesbare pincode (alleen cijfers)? */
  P.isLegacy = function (v) { return P.valid(v); };
  /** Geldig pinHash-object → {alg, iter, salt, hash} (vaste volgorde), anders null. */
  P.parse = function (v) {
    if (!v || typeof v !== 'object' || Array.isArray(v)) return null;
    if (v.alg !== P.ALG) return null;
    var iter = Number(v.iter);
    if (!isFinite(iter) || Math.floor(iter) !== iter || iter < P.ITER_MIN || iter > P.ITER_MAX) return null;
    var salt = b64.decode(String(v.salt == null ? '' : v.salt)), hash = b64.decode(String(v.hash == null ? '' : v.hash));
    if (!salt || salt.length < 8 || salt.length > 64 || !hash || hash.length !== P.HASH_BYTES) return null;
    return { alg: P.ALG, iter: iter, salt: String(v.salt), hash: String(v.hash) };
  };
  function ascii(pin) {
    var k = new Uint8Array(pin.length);
    for (var i = 0; i < pin.length; i++) k[i] = pin.charCodeAt(i) & 255;
    return k;
  }

  // SHA-256 / HMAC / PBKDF2 in pure JavaScript (= pbkdf2Js in supabase/functions/_shared/pin.ts)
  var K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ]);
  var IV = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  var W = new Uint32Array(64);
  function compress(state, m) {
    var i;
    for (i = 0; i < 16; i++) W[i] = m[i];
    for (i = 16; i < 64; i++) {
      var x = W[i - 15], y = W[i - 2];
      var s0 = ((x >>> 7) | (x << 25)) ^ ((x >>> 18) | (x << 14)) ^ (x >>> 3);
      var s1 = ((y >>> 17) | (y << 15)) ^ ((y >>> 19) | (y << 13)) ^ (y >>> 10);
      W[i] = (W[i - 16] + s0 + W[i - 7] + s1) | 0;
    }
    var a = state[0], b = state[1], c = state[2], d = state[3], e = state[4], f = state[5], g = state[6], h = state[7];
    for (i = 0; i < 64; i++) {
      var S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
      var t1 = (h + S1 + ((e & f) ^ (~e & g)) + K[i] + W[i]) | 0;
      var S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
      var t2 = (S0 + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    state[0] += a; state[1] += b; state[2] += c; state[3] += d; state[4] += e; state[5] += f; state[6] += g; state[7] += h;
  }
  function words(bytes) {
    var w = new Uint32Array(Math.ceil(bytes.length / 4));
    for (var i = 0; i < bytes.length; i++) w[i >> 2] |= bytes[i] << (24 - (i & 3) * 8);
    return w;
  }
  function sha256Js(msg, state0, prefixLen) {
    var st = new Uint32Array(state0 || IV);
    var total = (prefixLen || 0) + msg.length;
    var padLen = ((msg.length + 9 + 63) >> 6) << 6;
    var buf = new Uint8Array(padLen);
    buf.set(msg); buf[msg.length] = 0x80;
    var bits = total * 8;
    buf[padLen - 4] = (bits >>> 24) & 255; buf[padLen - 3] = (bits >>> 16) & 255; buf[padLen - 2] = (bits >>> 8) & 255; buf[padLen - 1] = bits & 255;
    buf[padLen - 5] = Math.floor(bits / 4294967296) & 255;
    var w = words(buf);
    for (var i = 0; i < w.length; i += 16) compress(st, w.subarray(i, i + 16));
    return st;
  }
  /** PBKDF2 als stappen: run(n) doet hooguit n iteraties; done() = klaar, result() = de hash (Uint8Array). */
  function pbkdf2Steps(pin, salt, iter, dkLen) {
    dkLen = dkLen || P.HASH_BYTES;
    if (dkLen > 32) throw new Error('dkLen > 32');
    var key = ascii(pin), i;
    if (key.length > 64) {
      var hk = sha256Js(key), kb = new Uint8Array(32);
      for (i = 0; i < 32; i++) kb[i] = (hk[i >> 2] >>> (24 - (i & 3) * 8)) & 255;
      key = kb;
    }
    var ipad = new Uint8Array(64), opad = new Uint8Array(64);
    for (i = 0; i < 64; i++) { ipad[i] = 0x36; opad[i] = 0x5c; }
    for (i = 0; i < key.length; i++) { ipad[i] ^= key[i]; opad[i] ^= key[i]; }
    var inner = new Uint32Array(IV), outer = new Uint32Array(IV);
    compress(inner, words(ipad)); compress(outer, words(opad));
    var first = new Uint8Array(salt.length + 4); first.set(salt); first[salt.length + 3] = 1;
    var ih = sha256Js(first, inner, 64);
    var blk = new Uint32Array(16); blk[8] = 0x80000000; blk[15] = 768;
    var st = new Uint32Array(8), u = new Uint32Array(8), t = new Uint32Array(8);
    for (i = 0; i < 8; i++) blk[i] = ih[i];
    u.set(outer); compress(u, blk);
    t.set(u);
    var n = 1;
    return {
      run: function (count) {
        var end = Math.min(iter, n + count);
        for (; n < end; n++) {
          var j;
          for (j = 0; j < 8; j++) blk[j] = u[j];
          st.set(inner); compress(st, blk);
          for (j = 0; j < 8; j++) blk[j] = st[j];
          u.set(outer); compress(u, blk);
          for (j = 0; j < 8; j++) t[j] ^= u[j];
        }
      },
      done: function () { return n >= iter; },
      result: function () {
        var out = new Uint8Array(dkLen);
        for (var k = 0; k < dkLen; k++) out[k] = (t[k >> 2] >>> (24 - (k & 3) * 8)) & 255;
        return out;
      }
    };
  }
  /** PBKDF2-HMAC-SHA256 in één keer (synchroon; voor tests en kleine aantallen). */
  P.pbkdf2Js = function (pin, salt, iter, dkLen) {
    var s = pbkdf2Steps(pin, salt, iter, dkLen);
    s.run(iter);
    return s.result();
  };
  /** Zelfde, in stukjes van [chunk] iteraties met een pauze ertussen (de tv blijft reageren). */
  P.pbkdf2Chunked = function (pin, salt, iter, chunk) {
    return new Promise(function (resolve, reject) {
      var s;
      try { s = pbkdf2Steps(pin, salt, iter); } catch (e) { reject(e); return; }
      function go() {
        try {
          s.run(chunk || 4000);
          if (s.done()) resolve(s.result()); else setTimeout(go, 0);
        } catch (e) { reject(e); }
      }
      go();
    });
  };
  /** WebCrypto als die er is (veilige context, Tizen 5.5+); anders (of bij een fout) de pure-JS-versie in stukjes. */
  P.subtle = function () {
    try { var c = window.crypto; return c && c.subtle && typeof c.subtle.deriveBits === 'function' ? c.subtle : null; } catch (e) { return null; }
  };
  P.derive = function (pin, salt, iter) {
    var subtle = P.subtle();
    if (!subtle) return P.pbkdf2Chunked(pin, salt, iter);
    return Promise.resolve().then(function () {
      return subtle.importKey('raw', ascii(pin), 'PBKDF2', false, ['deriveBits']);
    }).then(function (k) {
      return subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: salt, iterations: iter }, k, P.HASH_BYTES * 8);
    }).then(function (bits) { return new Uint8Array(bits); }, function () { return P.pbkdf2Chunked(pin, salt, iter); });
  };
  function randomSalt() {
    var s = new Uint8Array(P.SALT_BYTES);
    try { window.crypto.getRandomValues(s); return s; } catch (e) { /* zonder crypto: hieronder */ }
    for (var i = 0; i < s.length; i++) s[i] = Math.floor(Math.random() * 256);
    return s;
  }
  /** Nieuwe hash (nieuwe salt, tenzij meegegeven voor een test) → Promise<{alg, iter, salt, hash}>. */
  P.make = function (pin, salt) {
    if (!P.valid(pin)) return Promise.reject(new Error('bad_pin'));
    var s = salt || randomSalt();
    return P.derive(pin, s, P.ITER).then(function (h) { return { alg: P.ALG, iter: P.ITER, salt: b64.encode(s), hash: b64.encode(h) }; });
  };
  P.equal = function (a, b) {
    var d = a.length ^ b.length;
    for (var i = 0; i < Math.min(a.length, b.length); i++) d |= a[i] ^ b[i];
    return d === 0;
  };
  /** Klopt de pincode? Vergelijkt in vaste tijd; een ongeldige hash = false. */
  P.verify = function (pin, h) {
    var p = P.parse(h);
    if (!p || !P.valid(pin)) return Promise.resolve(false);
    return P.derive(pin, b64.decode(p.salt), p.iter).then(function (got) { return P.equal(got, b64.decode(p.hash)); }, function () { return false; });
  };
  /** Controle bij het invoeren: [stored] = een hash (object) of een oude leesbare pincode → Promise<boolean>. */
  P.check = function (entered, stored) {
    if (stored && typeof stored === 'object') return P.verify(entered, stored);
    if (P.isLegacy(stored)) {
      var a = ascii(String(entered)), b = ascii(String(stored));
      return Promise.resolve(P.equal(a, b));
    }
    return Promise.resolve(false);
  };

  // MARK: welke pincode geldt

  var C = X.pinChoice = {};
  /**
   * Wat een sectie `profile.<id>` over de pincode zegt: {kind: 'absent' | 'cleared' | 'hashed' | 'legacy' | 'invalid',
   * hash?, pin?}. Een geldige `pinHash` gaat voor; een leesbare `pin` zonder hash is van een oude app.
   */
  C.read = function (d) {
    d = d || {};
    var hasHash = B.has(d, 'pinHash'), hasPin = B.has(d, 'pin');
    if (hasHash && d.pinHash !== null) {
      var h = P.parse(d.pinHash);
      return h ? { kind: 'hashed', hash: h } : { kind: 'invalid' };
    }
    var v = d.pin;
    if (v && typeof v === 'object') v = v.pin || v.value || v.code;
    if (hasPin && P.valid(v == null ? null : String(v))) return { kind: 'legacy', pin: String(v) };
    if (hasHash || hasPin) return { kind: 'cleared' };
    return { kind: 'absent' };
  };
  function isHashValue(v) { return !!(v && typeof v === 'object' && P.parse(v)); }
  function sameHash(a, b) { return isHashValue(a) && isHashValue(b) && a.salt === b.salt && a.hash === b.hash && a.iter === b.iter; }
  /**
   * 'local' of 'server'. Nieuwste tijd wint; bij gelijke tijd lokaal, behalve als allebei een andere hash hebben (dan de
   * server: twee apparaten die dezelfde oude pincode elk met een eigen salt hashten, uploaden niet om en om) of als lokaal
   * nog een oude leesbare pincode staat en de server een hash heeft.
   */
  C.pick = function (localAt, localValue, serverAt, serverValue) {
    if (serverAt > localAt) return 'server';
    if (serverAt < localAt) return 'local';
    if (localValue != null && serverValue != null && !sameHash(localValue, serverValue) && isHashValue(serverValue) &&
      (isHashValue(localValue) || P.isLegacy(localValue))) return 'server';
    return 'local';
  };

  // MARK: profielen samenvoegen (= Build146Logic.ProfileMerge.plan op Android)

  var M = X.profiles = { EPOCH: '1970-01-01T00:00:00.000Z' };
  /** ISO-8601 → ms, met de fractie van de seconde. */
  M.ms = function (s) {
    var t = Date.parse(String(s || ''));
    return isFinite(t) ? t : 0;
  };
  M.newer = function (a, b) { return M.ms(a) > M.ms(b); };
  /**
   * local = [{key, updatedAt}], rows = [{key, updatedAt, deleted, isMain, order}], tombs = {key: true}, mainKey →
   * {entries: [{kind: 'keep'|'take'|'add', local: index|null, row: key|null}], removed: [index], stamps: {key: tijd},
   * needUpload, adoptMain}. Zelfde regels en volgorde als Android.
   */
  M.plan = function (local, rows, tombs, mainKey) {
    tombs = tombs || {};
    var byKey = {};
    rows.forEach(function (r) { byKey[r.key] = r; });
    var need = false, serverMain = null, i;
    for (i = 0; i < rows.length; i++) if (!rows[i].deleted && rows[i].isMain) { serverMain = rows[i].key; break; }
    function localAt(key) { for (var j = 0; j < local.length; j++) if (local[j].key === key) return local[j].updatedAt || M.EPOCH; return M.EPOCH; }
    function rowNewer(key) {
      var r = key != null ? byKey[key] : null;
      if (!r || r.deleted) return false;
      return M.newer(r.updatedAt || M.EPOCH, localAt(key));
    }
    var adopt = serverMain != null && serverMain !== mainKey && !tombs[serverMain] && (rowNewer(serverMain) || rowNewer(mainKey));
    var stamps = {}, removed = [], merged = [];
    local.forEach(function (p, idx) {
      var o = byKey[p.key];
      if (!o) { need = true; merged.push([{ kind: 'keep', local: idx, row: null }, idx]); return; }
      if (o.deleted) {
        if (p.key === mainKey) { need = true; merged.push([{ kind: 'keep', local: idx, row: null }, idx]); }
        else removed.push(idx);
        return;
      }
      var su = o.updatedAt || M.EPOCH, lu = p.updatedAt || M.EPOCH;
      if (M.newer(su, lu)) {
        merged.push([{ kind: 'take', local: idx, row: o.key }, o.order != null ? o.order : idx]);
        stamps[p.key] = su;
      } else {
        if (M.newer(lu, su)) need = true;
        merged.push([{ kind: 'keep', local: idx, row: o.key }, idx]);
      }
    });
    var present = {};
    local.forEach(function (p) { present[p.key] = true; });
    rows.forEach(function (o) {
      if (o.deleted || present[o.key]) return;
      if (tombs[o.key]) { need = true; return; }
      merged.push([{ kind: 'add', local: null, row: o.key }, o.order != null ? o.order : Number.MAX_VALUE]);
      stamps[o.key] = o.updatedAt || M.EPOCH;
    });
    Object.keys(tombs).forEach(function (k) { if (!byKey[k] || !byKey[k].deleted) need = true; });
    var sorted = merged.map(function (m, idx) { return { e: m[0], o: m[1], i: idx }; })
      .sort(function (a, b) { return (a.o - b.o) || (a.i - b.i); }).map(function (x) { return x.e; });
    var keyOf = function (e) { return e.row != null ? e.row : local[e.local].key; };
    var newMain = adopt && sorted.some(function (e) { return keyOf(e) === serverMain; }) ? serverMain : null;
    return { entries: sorted, removed: removed, stamps: stamps, needUpload: need, adoptMain: newMain };
  };

  // MARK: support

  var S = X.support = { CATEGORIES: ['account', 'afspelen', 'apps', 'website', 'overig'], GROUPS: ['open', 'in_progress', 'closed'] };
  S.group = function (status, group) {
    if (S.GROUPS.indexOf(group) >= 0) return group;
    var s = String(status || '').toLowerCase();
    if (s === 'open' || s === 'pending') return 'in_progress';
    if (s === 'resolved' || s === 'closed') return 'closed';
    return 'open';
  };
  S.statusKey = function (status) {
    return { 'new': 'New', open: 'In progress', pending: 'Awaiting your reply', resolved: 'Resolved', closed: 'Closed' }[String(status || '').toLowerCase()] || 'Open';
  };
  S.categoryKey = function (c) {
    return { account: 'Account and sign-in', afspelen: 'Playback and streams', apps: 'Apps and installing', website: 'Website and web player' }[c] || 'Other';
  };
  S.groupKey = function (g) { return { open: 'Open', in_progress: 'In progress', closed: 'Closed' }[g] || 'Open'; };
  S.badge = function (n) {
    n = Number(n) || 0;
    if (n <= 0) return null;
    return n > 99 ? '99+' : String(n);
  };
  /** 'bingr://support' → {ticket: null}; 'bingr://support/ticket?n=12' → {ticket: 12}; anders null. */
  S.parseLink = function (url) {
    var m = /^bingr:\/\/support(\/[^?#]*)?(\?[^#]*)?/i.exec(String(url || '').trim());
    if (!m) return null;
    var path = String(m[1] || '').replace(/\/+$/, '').toLowerCase();
    if (path !== '/ticket') return { ticket: null };
    var q = String(m[2] || '').replace(/^\?/, '').split('&'), n = null;
    for (var i = 0; i < q.length; i++) {
      var kv = q[i].split('=');
      if (kv[0] === 'n' && /^\s*\d+\s*$/.test(kv[1] || '')) { var v = parseInt(kv[1], 10); if (v > 0) { n = v; break; } }
    }
    return { ticket: n };
  };
  S.links = function (s) { var m = String(s || '').match(/(https?:\/\/|www\.)/gi); return m ? m.length : 0; };
  S.checkNew = function (subject, message) {
    var s = String(subject || '').trim(), m = String(message || '').trim();
    if (s.length < 3) return 'subject_missing';
    if (s.length > 120) return 'subject_too_long';
    if (!m) return 'message_missing';
    if (m.length < 10) return 'message_too_short';
    if (m.length > 5000) return 'message_too_long';
    if (S.links(m) > 6) return 'too_many_links';
    return null;
  };
  S.checkReply = function (message) {
    var m = String(message || '').trim();
    if (!m) return 'message_missing';
    if (m.length > 5000) return 'message_too_long';
    if (S.links(m) > 6) return 'too_many_links';
    return null;
  };
  S.errorKey = function (code) {
    return {
      subject_missing: 'Enter a subject (at least 3 characters).',
      subject_too_long: 'The subject is too long (max. 120 characters).',
      message_missing: 'Describe your question in at least 10 characters.',
      message_too_short: 'Describe your question in at least 10 characters.',
      message_too_long: 'Your message is too long (max. 5000 characters).',
      too_many_links: 'Your message contains too many links (max. 6).',
      rate_limited: 'Too many requests. Wait a moment and try again.',
      closed: 'This ticket is closed. Create a new ticket.',
      not_found: "This ticket wasn't found.",
      diagnostics_too_large: 'The diagnostics are too large to send.',
      too_large: 'The diagnostics are too large to send.'
    }[code] || 'Something went wrong. Please try again.';
  };

  // MARK: diagnose (pure delen)

  var G = X.diag = { FINAL: 'ready', FIRST: 'start', HANG_MS: 10000, MAX_MARKS: 40 };
  G.validMilestone = function (n) { return /^[a-z][a-z0-9_.-]{0,39}$/.test(String(n)); };
  var SCRUB = [
    [/[A-Za-z][A-Za-z0-9+.-]*:\/\/\S+/g, '<url>'],
    [/[^\s"'<>]+@[^\s"'<>]+\.[A-Za-z]{2,}/g, '<e>'],
    [/\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b/g, '<mac>'],
    [/\b(\d{1,3}\.){3}\d{1,3}\b/g, '<ip>'],
    [/\/(Users|home|opt\/usr\/apps|opt\/usr\/home)\/[^/\s]+/g, '/~'],
    [/(username|user|password|pass|pwd|token|key|mac)=[^&\s]*/gi, '$1=…'],
    [/"[^"]{24,}"/g, '"…"']
  ];
  G.scrub = function (s, max) {
    var t = String(s == null ? '' : s);
    SCRUB.forEach(function (r) { t = t.replace(r[0], r[1]); });
    return t.slice(0, max || 300);
  };
  G.stack = function (lines) {
    return (lines || []).map(function (l) { return G.scrub(String(l).trim(), 200); }).filter(function (l) { return !!l.trim(); }).slice(0, 50);
  };
  /** Mijlpaal toevoegen aan record r (alleen de eerste keer per naam, max. 40, niet na "klaar"). */
  G.mark = function (r, name, ms, mb) {
    if (r.finished || !G.validMilestone(name) || r.marks.length >= G.MAX_MARKS) return r;
    for (var i = 0; i < r.marks.length; i++) if (r.marks[i].name === name) return r;
    var t = Math.max(0, Math.min(600000, Math.round(ms)));
    var n = B.assign({}, r);
    n.marks = r.marks.concat([{ name: name, ms: t, mb: mb == null ? null : mb }]);
    if (mb != null) n.peakMB = Math.max(r.peakMB || 0, mb);
    if (name === G.FINAL) n.finished = true;
    return n;
  };
  /** Wat er mis was met de vorige start: {kind: 'crash'|'hang'|'notFinished', …} of null. */
  G.analyze = function (r) {
    if (!r || !r.marks) return null;
    var last = r.marks.length ? r.marks[r.marks.length - 1] : null;
    var base = { build: r.build, os: r.os, at: r.started, lastMark: last ? last.name : G.FIRST, ms: last ? last.ms : 0, marks: r.marks,
      crash: null, peakMB: r.peakMB == null ? null : r.peakMB };
    if (r.crash) return B.assign(base, { kind: 'crash', crash: r.crash });
    if (r.hangMs != null && r.hangMs >= G.HANG_MS) return B.assign(base, { kind: 'hang', lastMark: r.hangAt || base.lastMark, ms: r.hangMs });
    if (!r.finished) return B.assign(base, { kind: 'notFinished' });
    return null;
  };
  function sec(ms) { return (ms / 1000).toFixed(1); }
  G.text = function (p) {
    var out = [];
    if (p.kind === 'crash') out.push('Vorige start: gestopt (fout) na ' + sec(p.ms) + ' s, bij "' + p.lastMark + '"');
    else if (p.kind === 'hang') out.push('Vorige start: vastgelopen (' + sec(p.ms) + ' s) bij "' + p.lastMark + '"');
    else out.push('Vorige start: niet klaar, laatste mijlpaal "' + p.lastMark + '" na ' + sec(p.ms) + ' s (gestopt, bijv. geheugen of de tv)');
    if (p.peakMB != null) out.push('Geheugen: hoogste ' + p.peakMB + ' MB');
    out.push('Build ' + p.build + ' · ' + p.os);
    out.push('Mijlpalen: ' + p.marks.map(function (m) { return m.name + ' ' + sec(m.ms) + 's' + (m.mb != null ? ' ' + m.mb + 'MB' : ''); }).join(' · '));
    if (p.crash) {
      out.push('Fout: ' + p.crash.type + ': ' + p.crash.reason);
      if (p.crash.stack && p.crash.stack.length) { out.push('Stack:'); out = out.concat(p.crash.stack); }
    }
    return out.join('\n');
  };
  G.speed = function (ms, ok) { return !ok || ms == null ? 'fail' : (ms < 1500 ? 'ok' : 'slow'); };

  // MARK: tv-gids

  X.epg = { HORIZON_MS: 3 * 3600000 };
  /** programs = [[start, eind], …] (ms) van een steekproef; leeg = geen gids (niet verlopen). */
  X.epg.expired = function (now, programs) {
    if (!programs || !programs.length) return false;
    for (var i = 0; i < programs.length; i++) if (programs[i][1] > now && programs[i][0] < now + X.epg.HORIZON_MS) return false;
    return true;
  };
})();
