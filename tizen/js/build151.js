/*
 * Build 151 (Samsung TV en, via deze map, LG webOS): pure logica, gelijk aan features/Build151Logic.kt op Android en
 * getest in test/build151_test.js met dezelfde gevallen (tools/test/build151_cases.json).
 *
 * - X.channels: de rij "Zenders" op Home: zenderlogo's van de eigen server (zoals een rij streamingdiensten). Eerst de
 *   recente zenders en favorieten, aangevuld met zenders uit de grootste zichtbare live-categorieën (met logo eerst),
 *   hooguit MAX. Dezelfde zender in HD/FHD/4K of met "NL |" ervoor telt één keer. Kinderprofiel: alleen zenders die het
 *   kinderfilter toelaat.
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
})();
