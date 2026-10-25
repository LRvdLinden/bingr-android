/*
 * Build 149 (Samsung TV en, via deze map, LG webOS): pure logica, gelijk aan features/Build149Logic.kt op Android en
 * getest in test/build149_test.js.
 *
 * - X.dates: nergens meer per item een datumopmaker. Eigen ISO-8601- en XMLTV-parser (geen Date.parse/new Date(tekst) in
 *   lussen) en Intl.DateTimeFormat één keer per taal en opties (X.dates.fmt).
 * - X.skip: items uit verborgen categorieën bij het inlezen alleen tellen (niet in het geheugen of de cache).
 * - X.memory: geheugengrens tijdens het bijwerken (netjes stoppen met een melding in plaats van wegvallen).
 * - X.discover: de rij "Ontdek categorieën" op Home (welke categorieën, naam, kleur, logo van een streamingdienst).
 * ES5 (oude tv's).
 */
(function () {
  'use strict';
  var B = window.B = window.B || {};
  var X = B.b149 = {};

  // MARK: datums

  var D = X.dates = {};
  function num(s, from, len) {
    if (from + len > s.length) return -1;
    var v = 0;
    for (var i = from; i < from + len; i++) { var c = s.charCodeAt(i) - 48; if (c < 0 || c > 9) return -1; v = v * 10 + c; }
    return v;
  }
  D.num = num;
  /**
   * ISO-8601 → epoch-ms: yyyy-MM-dd, yyyy-MM-ddTHH:mm[:ss[.fractie]] met Z, ±HH:mm, ±HHmm of zonder zone (= UTC); een
   * spatie mag in plaats van T. null = geen geldige datum. Zelfde regels als Build149Logic.Dates.parseIso.
   */
  D.parseIso = function (input) {
    if (input == null) return null;
    var s = String(input).trim();
    if (s.length < 10 || s.charAt(4) !== '-' || s.charAt(7) !== '-') return null;
    var y = num(s, 0, 4), mo = num(s, 5, 2), d = num(s, 8, 2);
    if (y < 0 || mo < 1 || mo > 12 || d < 1 || d > 31) return null;
    var ms = Date.UTC(y, mo - 1, d);
    if (s.length === 10) return ms;
    var sep = s.charAt(10);
    if (sep !== 'T' && sep !== 't' && sep !== ' ') return null;
    var h = num(s, 11, 2), mi = num(s, 14, 2);
    if (h < 0 || h > 23 || mi < 0 || mi > 59 || s.length < 16 || s.charAt(13) !== ':') return null;
    ms += h * 3600000 + mi * 60000;
    var i = 16;
    if (i < s.length && s.charAt(i) === ':') {
      var sec = num(s, 17, 2); if (sec < 0 || sec > 60) return null;
      ms += sec * 1000; i = 19;
      if (i < s.length && (s.charAt(i) === '.' || s.charAt(i) === ',')) {
        i++;
        var frac = 0, digits = 0;
        while (i < s.length) { var c = s.charCodeAt(i) - 48; if (c < 0 || c > 9) break; if (digits < 3) { frac = frac * 10 + c; digits++; } i++; }
        while (digits > 0 && digits < 3) { frac *= 10; digits++; }
        ms += frac;
      }
    }
    if (i >= s.length) return ms;
    var z = s.charAt(i);
    if ((z === 'Z' || z === 'z') && i === s.length - 1) return ms;
    if (z !== '+' && z !== '-') return null;
    var oh = num(s, i + 1, 2); if (oh < 0) return null;
    var om = s.length === i + 3 ? 0 : (s.length === i + 6 && s.charAt(i + 3) === ':') ? num(s, i + 4, 2) : s.length === i + 5 ? num(s, i + 3, 2) : -1;
    if (om < 0 || oh > 18) return null;
    var off = (oh * 60 + om) * 60000;
    return z === '+' ? ms - off : ms + off;
  };
  /** XMLTV-tijd yyyyMMddHHmmss[ ±HHmm] → epoch-ms (zonder zone = UTC), zonder regex. */
  D.parseXmltv = function (input) {
    if (input == null) return null;
    var s = String(input).trim();
    if (s.length < 12) return null;
    var y = num(s, 0, 4), mo = num(s, 4, 2), d = num(s, 6, 2), h = num(s, 8, 2), mi = num(s, 10, 2);
    if (y < 0 || mo < 1 || mo > 12 || d < 1 || d > 31 || h < 0 || h > 23 || mi < 0 || mi > 59) return null;
    var sec = 0;
    if (s.length >= 14) { sec = num(s, 12, 2); if (sec < 0 || sec > 60) return null; }
    var ms = Date.UTC(y, mo - 1, d, h, mi, sec);
    var i = 14;
    while (i < s.length && s.charAt(i) === ' ') i++;
    var z = s.charAt(i);
    if (z === '+' || z === '-') {
      var oh = num(s, i + 1, 2), om = s.charAt(i + 3) === ':' ? num(s, i + 4, 2) : num(s, i + 3, 2);
      if (oh >= 0 && om >= 0) { var off = (oh * 60 + om) * 60000; ms = z === '+' ? ms - off : ms + off; }
    }
    return ms;
  };
  /** Intl.DateTimeFormat één keer per taal en opties (aanmaken is duur; toLocaleDateString maakt er elke keer een). */
  var fmts = {};
  D.created = 0;
  D.fmt = function (lang, opts) {
    var k = (lang || '') + '|' + (opts ? JSON.stringify(opts) : '');
    var f = fmts[k];
    if (f !== undefined) return f;
    try { f = (typeof Intl !== 'undefined' && Intl.DateTimeFormat) ? new Intl.DateTimeFormat(lang || undefined, opts || undefined) : null; D.created++; } catch (e) { f = null; }
    fmts[k] = f;
    return f;
  };
  /** Datum (ms) in de taal van de app met [opts]; terugval zoals vroeger. */
  D.format = function (ms, lang, opts) {
    var f = D.fmt(lang, opts);
    var d = new Date(ms);
    if (f) { try { return f.format(d); } catch (e) { /* hieronder */ } }
    try { return d.toLocaleDateString(lang || undefined, opts); } catch (e2) { return d.toDateString(); }
  };

  // MARK: verborgen categorieën bij het inlezen overslaan

  /** hidden(naam) → true = niet inlezen, alleen tellen (per categorienaam één keer gevraagd). */
  X.skip = function (hidden) {
    var memo = {}, counts = {}, total = 0;
    return {
      skip: function (group) {
        var h = memo[group];
        if (h === undefined) h = memo[group] = !!hidden(group);
        if (h) { counts[group] = (counts[group] || 0) + 1; total++; }
        return h;
      },
      counts: function () { return counts; },
      total: function () { return total; }
    };
  };
  /** Telling van de niet ingelezen items van een soort ("live", "movie", "series") uit lib.hiddenGroups. */
  X.hiddenCount = function (lib, kind) {
    var hg = (lib && lib.hiddenGroups) || {}, p = kind + '|', n = 0;
    for (var k in hg) if (Object.prototype.hasOwnProperty.call(hg, k) && k.indexOf(p) === 0) n += hg[k] || 0;
    return n;
  };
  X.hiddenNames = function (lib, kind) {
    var hg = (lib && lib.hiddenGroups) || {}, p = kind + '|', out = [];
    for (var k in hg) if (Object.prototype.hasOwnProperty.call(hg, k) && k.indexOf(p) === 0) out.push(k.slice(p.length));
    return out;
  };
  /** Staat er een niet ingelezen categorie weer aan? Dan moet de server opnieuw worden opgehaald. */
  X.needsReload = function (lib, hidden) {
    var hg = (lib && lib.hiddenGroups) || {};
    for (var k in hg) if (Object.prototype.hasOwnProperty.call(hg, k) && !hidden(k.slice(k.indexOf('|') + 1))) return true;
    return false;
  };

  // MARK: geheugengrens

  X.memory = {
    FRACTION: 0.85,
    /** {used, limit} in bytes als de engine dat meldt (Chromium: performance.memory), anders null. */
    info: function () {
      var m = window.performance && window.performance.memory;
      if (!m || !m.jsHeapSizeLimit) return null;
      return { used: m.usedJSHeapSize, limit: m.jsHeapSizeLimit };
    },
    tooHigh: function (info) {
      info = info === undefined ? X.memory.info() : info;
      return !!(info && info.limit > 0 && info.used > info.limit * X.memory.FRACTION);
    },
    message: function () { return B.L ? B.L("This server is very large. Hide categories you don't use and it will load faster.") : 'too_large'; }
  };

  // MARK: Ontdek categorieën

  var DC = X.discover = { MAX: 10, MIN_ITEMS: 4 };
  DC.PALETTE = ['#7A2E3A', '#2E4A7A', '#2F6B4F', '#6B4A2F', '#4F2E6B', '#2E6B6B', '#7A5A2E', '#3A3A5C', '#6B2F55', '#355E3B'];
  /** Vergelijksleutel: kleine letters, alleen letters en cijfers, + → "plus" ("Disney+" → "disneyplus"). */
  DC.key = function (name) {
    var s = String(name || '').toLowerCase(), out = '';
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      if (c === '+') out += 'plus';
      else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) out += c;
      else if (c.toUpperCase() !== c.toLowerCase()) out += c;   // letters buiten ASCII
    }
    return out;
  };
  /** FNV-1a 32 (zelfde als Build149Logic.Discover.hash). */
  DC.hash = function (s) {
    var h = 0x811C9DC5;
    for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul ? Math.imul(h, 0x01000193) : (h * 0x01000193) | 0; }
    return h | 0;
  };
  DC.colorFor = function (name) {
    var n = DC.PALETTE.length, h = DC.hash(DC.key(name));
    return DC.PALETTE[((h % n) + n) % n];
  };
  DC.ALIASES = {
    netflix: 'netflix', disney: 'disneyplus', disneyplus: 'disneyplus', hbo: 'hbomax', hbomax: 'hbomax', max: 'hbomax',
    prime: 'amazonprimevideo', primevideo: 'amazonprimevideo', amazon: 'amazonprimevideo', amazonprime: 'amazonprimevideo',
    amazonprimevideo: 'amazonprimevideo', skyshowtime: 'skyshowtime', viaplay: 'viaplay', videoland: 'videoland',
    appletv: 'appletvplus', appletvplus: 'appletvplus', apple: 'appletvplus', paramount: 'paramountplus',
    paramountplus: 'paramountplus', npoplus: 'npoplus', npo: 'npoplus'
  };
  var TAIL = /(films?|movies?|series|shows?|tv|kids|nl|en|4k|uhd|fhd|hd|originals?|vod|new|nieuw)+$/;
  /** Sleutel van [providers] (key(provider_name) → logo-URL) bij een categorie, of null. */
  DC.providerFor = function (category, providers) {
    providers = providers || {};
    var k = DC.key(category);
    if (!k) return null;
    var cands = [k], t = k;
    for (var r = 0; r < 3; r++) { t = t.replace(TAIL, ''); if (t && cands.indexOf(t) < 0) cands.push(t); }
    for (var i = 0; i < cands.length; i++) {
      var c = cands[i];
      if (Object.prototype.hasOwnProperty.call(providers, c)) return c;
      var a = DC.ALIASES[c];
      if (a) {
        if (Object.prototype.hasOwnProperty.call(providers, a)) return a;
        for (var p in providers) if (Object.prototype.hasOwnProperty.call(providers, p) && p.indexOf(a) === 0) return p;
      }
    }
    return null;
  };
  /**
   * Welke categorieën: [cats] = [{name, count, posters}], minstens MIN_ITEMS titels en 3 posters, de grootste eerst,
   * hooguit MAX. Bij een kinderprofiel alleen als alle categorieën kindercategorieën zijn.
   */
  DC.pick = function (cats, kidsProfile, isKids, max) {
    var ok = (cats || []).filter(function (c) { return c.count >= DC.MIN_ITEMS && (c.posters || []).length >= 3; });
    if (kidsProfile && ok.some(function (c) { return !isKids(c.name); })) return [];
    return ok.sort(function (a, b) { return b.count - a.count; }).slice(0, max || DC.MAX);
  };

  /** Logo's van streamingdiensten uit TMDB (watch providers, regio NL), een week bewaard. Geen logo-bestanden in de app. */
  var LOGOS_KEY = 'b149.providerLogos', WEEK = 7 * 86400000, logoMem = null, logoLoad = null;
  DC.logos = function () {
    if (logoMem) return logoMem;
    try { var j = JSON.parse(localStorage.getItem(LOGOS_KEY) || 'null'); logoMem = j && j.map ? j.map : {}; logoMem._at = j && j.at || 0; } catch (e) { logoMem = {}; }
    return logoMem;
  };
  DC.loadLogos = function () {
    var cur = DC.logos();
    if (Object.keys(cur).length > 1 && Date.now() - (cur._at || 0) < WEEK) return Promise.resolve(cur);
    var AR = B.ageRatings;
    if (!AR || !AR.tmdb || !AR.hasKey || !AR.hasKey()) return Promise.resolve(cur);
    if (logoLoad) return logoLoad;
    logoLoad = Promise.all(['movie', 'tv'].map(function (k) {
      return AR.tmdb('/watch/providers/' + k, [['watch_region', 'NL']]).catch(function () { return null; });
    })).then(function (r) {
      var map = {};
      r.forEach(function (x) {
        ((x && x.results) || []).forEach(function (p) {
          var k = DC.key(p && p.provider_name);
          if (k && p.logo_path && !map[k]) map[k] = 'https://image.tmdb.org/t/p/w300' + p.logo_path;
        });
      });
      logoLoad = null;
      if (!Object.keys(map).length) return cur;
      var at = Date.now();
      try { localStorage.setItem(LOGOS_KEY, JSON.stringify({ at: at, map: map })); } catch (e) { /* vol */ }
      map._at = at;
      logoMem = map;
      return map;
    }, function () { logoLoad = null; return cur; });
    return logoLoad;
  };

  // MARK: zoeken (= Build149Logic.Search)

  var SE = X.search = { EXACT: 4000, PREFIX: 3000, WORDS: 2000, CONTAINS: 1000, TYPO: 500 };
  var sePrefix = /^\s*(?:\|[^|]{1,12}\||\[[^\]]{1,12}\]|\([^)]{1,6}\)|[A-Z0-9+]{2,4}\s*[-:|•]\s+)/;
  var seMarks = /[̀-ͯ]/g;
  var seSpecial = { 'ß': 'ss', 'æ': 'ae', 'ø': 'o', 'ł': 'l', 'đ': 'd' };
  function isWord(c) { return c.toUpperCase() !== c.toLowerCase() || (c >= '0' && c <= '9'); }
  /** "NL | Amélie & Co." → "amelie and co". */
  SE.key = function (raw) {
    var s = String(raw == null ? '' : raw);
    for (var r = 0; r < 3; r++) { var n = s.replace(sePrefix, ''); if (n === s) break; s = n; }
    // build 150: dezelfde opschoning als de categorienamen (js/build150.js), als die geladen is
    if (B.b150) s = B.b150.cat.strip(s).name || s;
    if (!/^[\x00-\x7f]*$/.test(s)) { try { s = s.normalize('NFD').replace(seMarks, ''); } catch (e) { /* oude engine */ } }
    s = s.toLowerCase();
    var out = '', space = true;
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      if (c === '&') { if (!space) out += ' '; out += 'and '; space = true; }
      else if (c === "'" || c === '’') { /* "grey's" → "greys" */ }
      else if (isWord(c)) { out += seSpecial[c] || c; space = false; }
      else if (!space) { out += ' '; space = true; }
    }
    return out.trim();
  };
  SE.words = function (q) { return SE.key(q).split(' ').filter(function (w) { return w.length > 0; }); };
  SE.oneEdit = function (a, b) {
    if (a === b) return true;
    var la = a.length, lb = b.length;
    if (Math.abs(la - lb) > 1) return false;
    var i = 0;
    while (i < la && i < lb && a.charAt(i) === b.charAt(i)) i++;
    if (la === lb) {
      if (a.slice(i + 1) === b.slice(i + 1)) return true;
      return i + 1 < la && a.charAt(i) === b.charAt(i + 1) && a.charAt(i + 1) === b.charAt(i) && a.slice(i + 2) === b.slice(i + 2);
    }
    return la > lb ? a.slice(i + 1) === b.slice(i) : a.slice(i) === b.slice(i + 1);
  };
  SE.score = function (key, q, phrase) {
    if (!q.length || !key) return 0;
    phrase = phrase || q.join(' ');
    var tie = Math.max(0, Math.min(99, 100 - key.length)), i;
    if (key === phrase) return SE.EXACT + tie;
    if (key.indexOf(phrase) === 0) return SE.PREFIX + tie;
    var all = true;
    for (i = 0; i < q.length; i++) if (key.indexOf(q[i]) < 0) { all = false; break; }
    if (all) {
      var begin = true;
      for (i = 0; i < q.length; i++) if (!(key.indexOf(q[i]) === 0 || key.indexOf(' ' + q[i]) >= 0)) { begin = false; break; }
      return (begin ? SE.WORDS : SE.CONTAINS) + tie;
    }
    var kw = key.split(' ');
    for (i = 0; i < q.length; i++) {
      var w = q[i];
      if (key.indexOf(w) >= 0) continue;
      if (w.length < 5) return 0;
      var found = false;
      for (var j = 0; j < kw.length && !found; j++) {
        var k = kw[j];
        found = SE.oneEdit(w, k) || (k.length > w.length && SE.oneEdit(w, k.slice(0, w.length)));
      }
      if (!found) return 0;
    }
    return SE.TYPO + tie;
  };
  /** Zoeksleutel van een item, één keer berekend en bij het item bewaard (x._sk). */
  SE.itemKey = function (x, field) { return x._sk != null ? x._sk : (x._sk = SE.key(x[field])); };
  /** Treffers uit [list] (beste eerst), hooguit [limit]. */
  SE.find = function (list, q, limit, field) {
    var w = SE.words(q);
    if (!w.length) return [];
    var phrase = w.join(' '), hits = [];
    for (var i = 0; i < list.length; i++) { var sc = SE.score(SE.itemKey(list[i], field), w, phrase); if (sc > 0) hits.push([i, sc]); }
    hits.sort(function (a, b) { return b[1] - a[1] || a[0] - b[0]; });
    return hits.slice(0, limit).map(function (hh) { return list[hh[0]]; });
  };
})();
