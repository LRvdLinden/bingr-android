/*
 * Build 140 (versie 1.0.1, docs/ACCOUNT.md van de iOS-repo → "Build 140"): pure logica, gelijk aan Build140Logic.kt op
 * Android (zelfde gevallen in tizen/test/build140_test.js en tools/test/Build140LogicTest.kt).
 * - K: is een categorie een kindercategorie?
 * - marks: de account-brede set verborgen categorieën (`account.hiddenCategories`): sleutels, samenvoegen, migratie
 * - stamp: tijd van een lokale wijziging, altijd na de vorige van die sleutel (ook van een ander apparaat)
 * - gate: de rekensom van de ouderpoort
 * - picks: Voor jou (genres), Nieuw op Bingr (eerst gezien), uitgelicht (vaste roulerende selectie)
 * - refresh: wanneer een server volgens "Afspeellijsten bijwerken" ververst moet worden
 * Geschreven voor Chromium M56 (ES2017, geen ?. ?? of spread, geen \p{…} in regexen).
 */
(function () {
  'use strict';
  var B = window.B;
  var X = B.b140 = {};
  var DAY = X.DAY = 86400000;
  function lower(s) { return String(s == null ? '' : s).toLowerCase(); }

  // MARK: kindercategorieën

  var NEVER = ['adult', 'xxx', '18+', '+18', 'porn', 'erotic', 'erotiek'];
  var STARTS = ['kinder', 'kids', 'children', 'familie', 'family', 'jeugd', 'cartoon', 'animatie', 'animation', 'disney', 'pixar',
    'nickelodeon', 'peuter', 'kleuter', 'ketnet', 'zappelin', 'jetix', 'boomerang'];
  var WHOLE = ['kid', 'child', 'baby', 'junior', 'nick jr', 'z@pp'];
  var PREFIX = /^\s*(?:\|\s*[a-z]{2,3}\s*\||\[\s*[a-z]{2,3}\s*\]|\(\s*[a-z]{2,3}\s*\)|[a-z]{2,3}\s*(?:\||:|-|–))\s*/;
  var ALNUM = /[0-9a-zÀ-ɏͰ-ϿЀ-ӿ]/;

  X.fold = function (s) {
    var t = String(s == null ? '' : s);
    try { t = t.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) { /* oude engine */ }
    return t.toLowerCase();
  };
  /** Kleine letters, accenten weg en het landvoorvoegsel vooraan weg (ook herhaald): "NL | Kinderfilms" → "kinderfilms". */
  X.normCategory = function (name) {
    var s = X.fold(name).trim();
    for (;;) {
      var n = s.replace(PREFIX, '');
      if (n === s || !n) break;
      s = n;
    }
    return s.trim();
  };
  function before(s, i) { return i === 0 || !ALNUM.test(s.charAt(i - 1)); }
  function hasStart(s, w) {
    for (var i = s.indexOf(w); i >= 0; i = s.indexOf(w, i + 1)) if (before(s, i)) return true;
    return false;
  }
  function hasWhole(s, w) {
    for (var i = s.indexOf(w); i >= 0; i = s.indexOf(w, i + 1)) {
      var end = i + w.length;
      if (before(s, i) && (end === s.length || !ALNUM.test(s.charAt(end)))) return true;
    }
    return false;
  }
  /** Kindercategorie? Naam zoals de server hem levert (vóór hernoemen/samenvoegen). */
  X.isKidsCategory = function (name) {
    var n = X.normCategory(name);
    if (!n) return false;
    for (var i = 0; i < NEVER.length; i++) if (n.indexOf(NEVER[i]) >= 0) return false;
    for (var j = 0; j < STARTS.length; j++) if (hasStart(n, STARTS[j])) return true;
    for (var k = 0; k < WHOLE.length; k++) if (hasWhole(n, WHOLE[k])) return true;
    return false;
  };
  /** Binnen een kindercategorie: bekende classificatie boven de grens niet; zonder bekende classificatie wel. */
  X.kidsAgeAllowed = function (age, limit) { return age == null || limit == null || age <= limit; };

  // MARK: tijden

  /** Nu, maar altijd na `previous` (de laatst bekende tijd van die sleutel). */
  X.stampAfter = function (now, previous) { return previous == null ? now : Math.max(now, previous + 1); };

  // MARK: account-brede verborgen categorieën ({sleutel: {at, on}}; on = categorie uit)

  var M = X.marks = {};
  M.norm = function (k) {
    k = String(k);
    var bar = k.indexOf('|');
    return bar <= 0 ? k : lower(k.slice(0, bar)) + k.slice(bar);
  };
  M.key = function (sid, name) { return lower(sid) + '|' + name; };
  M.normalizeAll = function (m) {
    var out = {};
    Object.keys(m || {}).forEach(function (k) {
      var n = M.norm(k), v = m[k], c = out[n];
      if (!c || v.at > c.at) out[n] = { at: v.at, on: !!v.on };
    });
    return out;
  };
  /** Per sleutel wint de nieuwste `at` (bij gelijke tijd blijft a). */
  M.merge = function (a, b) {
    var out = M.normalizeAll(a), nb = M.normalizeAll(b);
    Object.keys(nb).forEach(function (k) { var c = out[k]; if (!c || nb[k].at > c.at) out[k] = nb[k]; });
    return out;
  };
  M.prune = function (m, now, days) {
    var cutoff = now - (days || 90) * DAY, out = {};
    Object.keys(m).forEach(function (k) { if (m[k].on || m[k].at >= cutoff) out[k] = m[k]; });
    return out;
  };
  M.alive = function (m) { return Object.keys(m).filter(function (k) { return m[k].on; }).sort(); };
  /** Per server-id (kleine letters) {naam: true}. */
  M.index = function (alive) {
    var out = {};
    alive.forEach(function (k) {
      var bar = k.indexOf('|');
      if (bar <= 0) return;
      var s = lower(k.slice(0, bar));
      (out[s] = out[s] || {})[k.slice(bar + 1)] = true;
    });
    return out;
  };
  M.set = function (m, keys, hidden, now) {
    var out = M.normalizeAll(m);
    keys.forEach(function (raw) {
      var k = M.norm(raw), cur = out[k];
      if (cur && cur.on === hidden) return;
      if (!cur && !hidden) return;
      out[k] = { at: X.stampAfter(now, cur ? cur.at : null), on: hidden };
    });
    return out;
  };
  M.removeServer = function (m, sid, now) {
    var pre = lower(sid) + '|', out = M.normalizeAll(m), changed = false;
    Object.keys(out).forEach(function (k) {
      if (out[k].on && k.indexOf(pre) === 0) { out[k] = { at: X.stampAfter(now, out[k].at), on: false }; changed = true; }
    });
    return changed ? out : m;
  };
  /** Migratie: unie van de per-profiel-sets met at = 0, alleen voor sleutels die de account-set nog niet kent. */
  M.migrate = function (m, keys) {
    var out = M.normalizeAll(m);
    keys.forEach(function (raw) { var k = M.norm(raw); if (!B.has(out, k) && k.indexOf('|') > 0) out[k] = { at: 0, on: true }; });
    return out;
  };
  M.equal = function (a, b) {
    var ka = Object.keys(a), kb = Object.keys(b);
    if (ka.length !== kb.length) return false;
    for (var i = 0; i < ka.length; i++) { var x = a[ka[i]], y = b[ka[i]]; if (!y || x.at !== y.at || !!x.on !== !!y.on) return false; }
    return true;
  };

  // MARK: ouderpoort

  var G = X.gate = { MAX_DIGITS: 3, MAX_TRIES: 3 };
  function rnd(rand, from, until) { return from + Math.floor(rand() * (until - from)); }
  /** Helft vermenigvuldigen (6…12 × 3…9), helft optellen (11…49 + 11…49). */
  G.newSum = function (rand) {
    rand = rand || Math.random;
    var mul = rand() < 0.5;
    var s = mul ? { a: rnd(rand, 6, 13), b: rnd(rand, 3, 10), multiply: true } : { a: rnd(rand, 11, 50), b: rnd(rand, 11, 50), multiply: false };
    s.answer = s.multiply ? s.a * s.b : s.a + s.b;
    s.text = s.a + (s.multiply ? ' × ' : ' + ') + s.b + ' = ?';
    return s;
  };
  /** Toestand van de som: check(input, force) → 'ok' | 'wrong' | 'new' (na 3 keer fout een nieuwe som) | 'wait'. */
  G.state = function (sum, rand) {
    var st = { sum: sum || G.newSum(rand), tries: 0 };
    st.check = function (input, force) {
      var want = String(st.sum.answer);
      if (!force && input.length < want.length) return 'wait';
      if (input === want) return 'ok';
      st.tries++;
      if (st.tries >= G.MAX_TRIES) { st.sum = G.newSum(rand); st.tries = 0; return 'new'; }
      return 'wrong';
    };
    return st;
  };

  // MARK: Voor jou, Nieuw op Bingr en uitgelicht

  var P = X.picks = { HERO_INTERVAL: 8000 };
  P.genres = function (s) {
    if (!s) return [];
    return String(s).split(/[,/|&]/).map(function (x) { return x.trim().toLowerCase(); }).filter(function (x) { return x && x !== 'n/a'; });
  };
  P.genreWeights = function (seeds) {
    var out = {};
    seeds.forEach(function (gs, i) {
      var w = Math.max(0.3, 1 - 0.15 * i);
      gs.forEach(function (g) { out[g] = (out[g] || 0) + w; });
    });
    return out;
  };
  /** cands: [{key, genres, rating, addedAt}] → sleutels, meeste overlap eerst, dan beoordeling, dan nieuwste. */
  P.byGenres = function (cands, weights, exclude, limit) {
    if (!Object.keys(weights).length) return [];
    return cands.filter(function (c) { return !exclude[c.key]; }).map(function (c) {
      var s = 0; c.genres.forEach(function (g) { s += weights[g] || 0; });
      return { c: c, s: s };
    }).filter(function (x) { return x.s > 0; }).sort(function (a, b) {
      return (b.s - a.s) || ((b.c.rating || 0) - (a.c.rating || 0)) || ((b.c.addedAt || 0) - (a.c.addedAt || 0)) ||
        (a.c.key < b.c.key ? -1 : a.c.key > b.c.key ? 1 : 0);
    }).slice(0, limit).map(function (x) { return x.c.key; });
  };
  /** Eerst gezien: een server die nog nooit gezien is → alles 0; daarna nieuwe ids = now; ids die weg zijn gaan weg. */
  P.firstSeen = function (known, ids, now) {
    var out = {};
    ids.forEach(function (id) { out[id] = known ? (B.has(known, id) ? known[id] : now) : 0; });
    return out;
  };
  P.addedDate = function (provider, seen) { return provider > 0 ? provider : (seen == null ? 0 : seen); };
  /** Uitgelicht: om en om uit Voor jou en Nieuw op Bingr, dubbele weg, max. 8. */
  P.heroPool = function (forYou, fresh, key, max) {
    max = max || 8;
    var out = [], seen = {};
    for (var i = 0; out.length < max && (i < forYou.length || i < fresh.length); i++) {
      [forYou[i], fresh[i]].forEach(function (x) {
        if (x == null || out.length >= max) return;
        var k = key(x);
        if (seen[k]) return;
        seen[k] = true; out.push(x);
      });
    }
    return out;
  };
  P.dayNumber = function (now, offsetMs) { return Math.floor((now + offsetMs) / DAY); };
  P.heroStart = function (day, size) { return size > 0 ? ((day % size) + size) % size : 0; };
  /** Dagnummer in de tijdzone van de tv. */
  P.today = function () { var n = Date.now(); return P.dayNumber(n, -new Date(n).getTimezoneOffset() * 60000); };

  // MARK: automatisch vernieuwen

  var R = X.refresh = { SIX_HOURS: 6 * 3600000, CHECK_EVERY: 15 * 60000 };
  R.due = function (policy, ageMs, doneThisLaunch) {
    switch (policy) {
      case 'manual': return false;
      case 'launch': return !doneThisLaunch;
      case 'sixHours': return ageMs == null || ageMs > R.SIX_HOURS;
      case 'weekly': return ageMs == null || ageMs > 7 * DAY;
      default: return ageMs == null || ageMs > DAY;
    }
  };
  /** Servers die nu aan de beurt zijn, de actieve eerst (ids). */
  R.dueServers = function (ids, active, policy, ageOf, done) {
    var order = ids.slice().sort(function (a, b) { return (lower(a) === lower(active) ? 0 : 1) - (lower(b) === lower(active) ? 0 : 1); });
    return order.filter(function (id) { return R.due(policy, ageOf(id), !!done[lower(id)]); });
  };
})();
