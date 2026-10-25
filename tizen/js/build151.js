/*
 * Build 151 (Samsung TV en, via deze map, LG webOS): pure logica, gelijk aan features/Build151Logic.kt op Android en
 * getest in test/build151_test.js met dezelfde gevallen (tools/test/build151_cases.json).
 *
 * - X.channels: de rij "Zenders" op Home: zenderlogo's van de eigen server (zoals een rij streamingdiensten). Eerst de
 *   recente zenders en favorieten, aangevuld met zenders uit de grootste zichtbare live-categorieën (met logo eerst),
 *   hooguit MAX. Dezelfde zender in HD/FHD/4K of met "NL |" ervoor telt één keer. Kinderprofiel: alleen zenders die het
 *   kinderfilter toelaat.
 * - X.rows: elke rij op Home ontdubbelen over servers heen: dezelfde titel (TMDB/IMDb-id, of anders genormaliseerde
 *   titel + jaar; zenders op de opgeschoonde naam) maar één tegel, op de plek waar hij het eerst staat. Welke bron
 *   blijft: in de audio- of app-taal, dan de actieve server, dan de rest; bij Verder kijken de nieuwste voortgang. De
 *   andere servers blijven als "Andere bronnen" op de detailpagina.
 * ES5 (oude tv's).
 */
(function () {
  'use strict';
  var B = window.B = window.B || {};
  var X = B.b151 = {};

  var CH = X.channels = { MAX: 15, PER_GROUP: 3, MIN: 3 };

  /** Zendernaam zonder taal-, land- en kwaliteitsaanduiding ("NL | NPO 1 HD" → "NPO 1"); hoofdletters blijven. */
  CH.name = function (raw) {
    var s = String(raw == null ? '' : raw);
    var n = B.b150 ? B.b150.cat.strip(s).name : s.trim();
    return n || s.trim();
  };
  /** Sleutel om dezelfde zender in een andere variant te herkennen ("NPO 1 HD", "NL: NPO 1 FHD" → "npo1"). */
  CH.sameKey = function (raw) {
    var n = CH.name(raw);
    return (B.b150 ? B.b150.merge.key(n) : n.toLowerCase()) || String(raw == null ? '' : raw).trim().toLowerCase();
  };

  /**
   * Welke zenders in de rij komen, in volgorde. preferred = recente zenders en dan favorieten, groups = [[naam, zenders]]
   * (zichtbare live-categorieën). fn = { key, name, hasLogo, allowed }.
   */
  CH.pick = function (preferred, groups, fn, max) {
    max = max || CH.MAX;
    var out = [], keys = {}, names = {};
    function add(t) {
      if (out.length >= max || !fn.allowed(t)) return false;
      var k = '#' + fn.key(t), n = '#' + CH.sameKey(fn.name(t));
      if (keys[k] || names[n]) return false;
      keys[k] = true; names[n] = true; out.push(t);
      return true;
    }
    var i, j;
    for (i = 0; i < (preferred || []).length && out.length < max; i++) add(preferred[i]);
    // grootste categorie eerst; bij gelijke grootte de volgorde van de server
    var sorted = (groups || []).map(function (g, ix) { return { g: g, ix: ix }; });
    sorted.sort(function (a, b) { return (b.g[1].length - a.g[1].length) || (a.ix - b.ix); });
    for (i = 0; i < sorted.length && out.length < max; i++) {
      var list = sorted[i].g[1], n = 0;
      // zenders met een logo eerst (de rij is een rij logo's), verder de volgorde van de server
      for (var pass = 0; pass < 2; pass++) {
        for (j = 0; j < list.length; j++) {
          if (n >= CH.PER_GROUP || out.length >= max) break;
          if (!!fn.hasLogo(list[j]) !== (pass === 0)) continue;
          if (add(list[j])) n++;
        }
      }
    }
    return out;
  };

  var R = X.rows = {};
  function skey(t) { return (B.b149 && B.b149.search) ? B.b149.search.key(t) : String(t || '').toLowerCase(); }
  /** Titelsleutel: genormaliseerde titel (taal/kwaliteit eraf, zoals zoeken) + jaar. */
  R.titleKey = function (title, year) { return skey(title).replace(/ /g, '') + '|' + (year == null ? '' : year); };
  /** Rangorde van een bron (lager = beter): taal uit preferred, dan meertalig, dan de rest; daarbinnen de actieve server. */
  R.rank = function (tags, active, preferred) {
    var up = (tags || []).map(function (x) { return String(x).toUpperCase(); }), p = (preferred || []).map(function (x) { return String(x).toUpperCase(); });
    var lang = -1;
    for (var i = 0; i < p.length && lang < 0; i++) if (up.indexOf(p[i]) >= 0 || (p[i] === 'NL' && up.indexOf('BE') >= 0)) lang = i;
    if (lang < 0) lang = p.length + (up.indexOf('MULTI') >= 0 ? 0 : 1);
    return lang * 2 + (active ? 0 : 1);
  };
  /** Labels van een bron: uit de categorie en uit de titel. */
  R.tags = function (group, title) {
    var C = B.b150 && B.b150.cat, out = [];
    if (!C) return out;
    C.cached(String(group || '')).tags.concat(C.strip(String(title || '')).tags).forEach(function (t) { if (out.indexOf(t) < 0) out.push(t); });
    return out;
  };
  /**
   * Ontdubbelen: zelfde id (fn.id) of zelfde titelsleutel = één groep (ook als het ene item een id heeft en het andere
   * niet); per groep het item met de laagste fn.rank (gelijk: het eerste), op de plek van het eerste. fn = {id, title, year, rank}.
   */
  R.dedupe = function (items, fn) {
    if (!items || items.length < 2) return items || [];
    var byId = {}, byT = {}, best = [], bestRank = [];
    for (var ix = 0; ix < items.length; ix++) {
      var x = items[ix], d = fn.id(x), ttl = fn.title(x);
      d = d ? '#' + String(d).trim().toLowerCase() : null;
      if (d === '#') d = null;
      var t = skey(ttl).trim() ? '#' + R.titleKey(ttl, fn.year(x)) : null;
      var g = d && has(byId, d) ? byId[d] : (t && has(byT, t) ? byT[t] : -1);
      if (g < 0) { best.push(ix); bestRank.push(fn.rank(x)); g = best.length - 1; }
      else if (best[g] !== ix) { var r = fn.rank(x); if (r < bestRank[g]) { best[g] = ix; bestRank[g] = r; } }
      if (d && !has(byId, d)) byId[d] = g;
      if (t && !has(byT, t)) byT[t] = g;
    }
    if (best.length === items.length) return items;
    return best.map(function (i) { return items[i]; });
  };
  function has(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }
})();
