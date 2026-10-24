/*
 * Build 148: grote JSON-lijsten (catalogus van een Xtream-server) inlezen buiten de hoofdthread. De app stuurt de tekst en
 * de velden die hij nodig heeft; dit script leest de JSON in, houdt per item alleen die velden over (lichte objecten) en
 * stuurt ze in pakketjes terug, zodat de hoofdthread nooit een hele catalogus in één keer hoeft over te nemen.
 * Bericht in: {id, text, keys}. Uit: {id, batch:[…]} … {id, done:true, total} of {id, error:'parse'}.
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
    for (var i = 0; i < list.length; i += BATCH) {
      var end = Math.min(list.length, i + BATCH), out = new Array(end - i);
      for (var n = i; n < end; n++) {
        var x = list[n];
        if (!keys || !x || typeof x !== 'object') { out[n - i] = x; continue; }
        var o = {};
        for (var k = 0; k < keys.length; k++) { var v = x[keys[k]]; if (v != null && v !== '') o[keys[k]] = v; }
        out[n - i] = o;
        list[n] = null;
      }
      self.postMessage({ id: d.id, batch: out });
    }
    self.postMessage({ id: d.id, done: true, total: list.length });
  };
})();
