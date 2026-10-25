/*
 * Build 148: grote JSON-lijsten (catalogus van een Xtream-server) inlezen buiten de hoofdthread. De app stuurt de tekst en
 * de velden die hij nodig heeft; dit script leest de JSON in, houdt per item alleen die velden over (lichte objecten) en
 * stuurt ze in pakketjes terug, zodat de hoofdthread nooit een hele catalogus in één keer hoeft over te nemen.
 * Bericht in: {id, text, keys}. Uit: {id, batch:[…]} … {id, done:true, total} of {id, error:'parse'}.
 * Build 149: optioneel {skipField, skip: {waarde: true}}: items met zo'n waarde (verborgen categorie) worden niet
 * teruggestuurd maar alleen geteld; done krijgt dan ook skipped: {waarde: aantal}.
 * Build 150: optioneel ook {skipName: veld}: van die overgeslagen items alleen de naam en de waarde (compacte zoekindex
 * voor zoeken in verborgen categorieën); done krijgt dan skippedNames: [naam] en skippedCats: [waarde].
 * Geen netwerk, geen opslag; ES5 (oude tv's).
 */
(function () {
  'use strict';
  var BATCH = 1500;
  function items(j) {
    if (Array.isArray(j)) return j;
    if (j && typeof j === 'object') return Object.keys(j).map(function (k) { return j[k]; });
    return [];
  }
  self.onmessage = function (ev) {
    var d = ev.data || {};
    var list;
    try { list = items(JSON.parse(d.text)); } catch (e) { self.postMessage({ id: d.id, error: 'parse' }); return; }
    d.text = null;
    var keys = d.keys || null;
    var sf = d.skipField || null, skip = d.skip || null, skipped = {}, any = false;
    var sn = d.skipName || null, names = sn ? [] : null, ncats = sn ? [] : null;
    for (var i = 0; i < list.length; i += BATCH) {
      var end = Math.min(list.length, i + BATCH), out = [];
      for (var n = i; n < end; n++) {
        var x = list[n];
        if (sf && skip && x && typeof x === 'object') {
          var sv = x[sf] == null ? '' : String(x[sf]);
          if (skip[sv] === true) {
            skipped[sv] = (skipped[sv] || 0) + 1; any = true;
            if (names && x[sn] != null) { names.push(String(x[sn])); ncats.push(sv); }
            list[n] = null; continue;
          }
        }
        if (!keys || !x || typeof x !== 'object') { out.push(x); continue; }
        var o = {};
        for (var k = 0; k < keys.length; k++) { var v = x[keys[k]]; if (v != null && v !== '') o[keys[k]] = v; }
        out.push(o);
        list[n] = null;
      }
      self.postMessage({ id: d.id, batch: out });
    }
    self.postMessage({ id: d.id, done: true, total: list.length, skipped: any ? skipped : null,
      skippedNames: any && names ? names : null, skippedCats: any && names ? ncats : null });
  };
})();
