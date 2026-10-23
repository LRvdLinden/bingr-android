/*
 * Build 140: "Nieuw op Bingr" (docs/ACCOUNT.md → "Build 140"), gelijk aan NewOnBingr.kt op Android: de titels die het laatst
 * aan de bibliotheek van het account (alle servers) zijn toegevoegd, nieuwste eerst. Datum: die van de provider (Xtream
 * `added`, Plex/Jellyfin `addedAt`/`DateCreated`, in `addedAt`), anders het moment waarop deze tv de titel voor het eerst
 * zag (lokaal in IndexedDB, `firstseen:<server-id>`; de eerste keer dat een server gezien wordt telt alles als 0).
 * Tot build 139 kwam de rij uit TMDB (nieuw op streamingdiensten in de regio, gekoppeld aan je bibliotheek op titel).
 * Geschreven voor Chromium M56 (ES2017, geen ?. ?? of spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var D = B.data, Lib = B.lib;
  var N = B.newOnBingr = {};
  var MAX = 30;
  function lower(s) { return String(s == null ? '' : s).toLowerCase(); }

  var seenMem = {};   // kleine letters server-id → {ids: {…}, n: aantal titels toen bijgewerkt}
  /** Eerst gezien bijwerken voor deze ruwe catalogus; Promise → {"m:<id>"|"s:<id>": tijd}. */
  function firstSeen(sid, raw) {
    var k = lower(sid);
    var n = (raw.movies || []).length + (raw.series || []).length;
    if (!n) return Promise.resolve(seenMem[k] ? seenMem[k].ids : {});
    var mem = seenMem[k];
    if (mem && mem.raw === raw) return Promise.resolve(mem.ids);
    var read = mem ? Promise.resolve(mem.ids) : B.idb.get('firstseen:' + k).then(function (o) { return o || null; }, function () { return null; });
    return read.then(function (known) {
      var ids = [];
      (raw.movies || []).forEach(function (x) { ids.push('m:' + x.id); });
      (raw.series || []).forEach(function (x) { ids.push('s:' + x.id); });
      var next = B.b140.picks.firstSeen(known, ids, Date.now());
      var changed = !known || Object.keys(next).length !== Object.keys(known).length ||
        ids.some(function (id) { return known[id] !== next[id]; });
      seenMem[k] = { ids: next, raw: raw };
      if (changed) B.idb.set('firstseen:' + k, next);
      return next;
    });
  }

  function collect(lib, seen, sid, out) {
    var P = B.b140.picks;
    [['movie', lib.movies || []], ['series', lib.series || []]].forEach(function (pair) {
      var pre = pair[0] === 'movie' ? 'm:' : 's:';
      pair[1].forEach(function (x) {
        var d = P.addedDate(x.addedAt, seen[pre + x.id]);
        if (d > 0) out.push({ type: pair[0], item: sid ? B.assign({}, x, { _src: sid }) : x, at: d });
      });
    });
  }

  var cache = { key: null, list: [] }, busy = null;
  function key() {
    var p = B.app && B.app.profile;
    return (p ? p.id : '') + '|' + (Lib.source ? Lib.source.id : '') + '|' + Lib.version + '|' + D.sources.map(function (s) { return s.id + ':' + (Lib.stamps[s.id] || 0); }).join(',');
  }
  /** De rij uit de cache ([{type, item}]), of leeg zolang hij nog berekend wordt. */
  N.list = function () { return cache.list; };
  /** Opnieuw berekenen (actieve server uit het geheugen, de andere uit IndexedDB met hun eigen filters); true als hij veranderde. */
  N.load = function () {
    if (busy) return busy;
    var k = key();
    if (cache.key === k) return Promise.resolve(false);
    var out = [], active = Lib.source;
    var AS = B.allservers;
    var chain = active ? firstSeen(active.id, Lib.raw).then(function (seen) { collect(Lib.lib, seen, null, out); }) : Promise.resolve();
    D.sources.forEach(function (src) {
      if (active && lower(src.id) === lower(active.id)) return;
      chain = chain.then(function () {
        return B.idb.get('lib:' + src.id).then(function (c) {
          if (!c || !c.lib) return;
          if (Lib.stamps) Lib.stamps[src.id] = c.at || 0;
          return firstSeen(src.id, c.lib).then(function (seen) {
            var f = Lib.filterOne(src);
            var lib = {
              movies: (c.lib.movies || []).map(function (x) { return f.movie(x); }).filter(Boolean),
              series: (c.lib.series || []).map(function (x) { return f.series(x); }).filter(Boolean)
            };
            collect(lib, seen, src.id, out);
          });
        }, B.noop);
      });
    });
    busy = chain.then(function () {
      var dedup = {};
      var list = out.sort(function (a, b) { return (b.at - a.at) || (a.item.title < b.item.title ? -1 : a.item.title > b.item.title ? 1 : 0); })
        .filter(function (e) {
          var d = AS && AS.dedupKey ? AS.dedupKey(e.type, e.item.rawTitle || e.item.title, e.item.year, null) : e.type + ':' + e.item.id;
          if (dedup[d]) return false;
          dedup[d] = true;
          return true;
        }).slice(0, MAX).map(function (e) { return { type: e.type, item: e.item }; });
      var sig = function (l) { return l.map(function (e) { return e.type + (e.item._src || '') + e.item.id; }).join(','); };
      var changed = sig(list) !== sig(cache.list);
      cache = { key: k, list: list };
      return changed;
    }).catch(function (e) { B.log('nieuw op bingr', e && e.message); return false; }).then(function (r) { busy = null; return r; });
    return busy;
  };
  N.wipe = function () { seenMem = {}; cache = { key: null, list: [] }; };
  B.on('wipe', function () {
    N.wipe();
    D.sources.forEach(function (s) { B.idb.del('firstseen:' + lower(s.id)); });
  });
})();
