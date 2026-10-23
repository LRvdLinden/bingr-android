/*
 * Build 144 (versie 1.0.1), gelijk aan Build144Logic.kt en ContinueCovers.kt op Android (zelfde gevallen in
 * tizen/test/build144_test.js en tools/test/Build144LogicTest.kt) en aan de Apple-app (build 143):
 * - cover: de afbeelding van een tegel in Verder kijken. Eerst de poster uit de bibliotheek of de catalogmeta, anders de
 *   TMDB-poster (bewaard op de tv), pas daarna het bewaarde beeld van de voortgang (bij een aflevering vaak de scène).
 *   Tot build 143 viel de tv terug op dat beeld (of op niets) als de bibliotheek geen poster had, ook bij oude voortgang en
 *   bij voortgang van andere apparaten (account-sync).
 * - covers: het opzoeken bij TMDB (via B.ageRatings.tmdb, de bestaande TMDB-route) en bewaren in IndexedDB.
 * - auto: wanneer het automatisch verversen na het openen mag beginnen: pas na ±45 s, alleen met een gekozen profiel (niet
 *   in "Wie kijkt er?" of het pincodescherm) en nooit tijdens het afspelen.
 * Geschreven voor Chromium M56 (ES2017, geen ?. ?? of spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var X = B.b144 = {};
  /** Start van de app (dit script laadt bij het starten). */
  X.t0 = Date.now();

  function ok(v) { return v && String(v).trim() ? v : null; }

  // MARK: Verder kijken: cover

  var C = X.cover = { MAX_LOOKUPS: 12 };
  /** "m|<schone titel>|<jaar>" / "s|…" (jaar 0 = onbekend), gelijk aan Android en Apple. */
  C.key = function (title, year, isShow) {
    var p = B.parseTitle(title);
    return (isShow ? 's|' : 'm|') + String(p.title).toLowerCase() + '|' + String(year || p.year || 0);
  };
  /** Catalogmeta die alleen het bewaarde beeld herhaalt is geen poster. */
  function realMeta(meta, stored) { var m = ok(meta); return m && m !== ok(stored) ? m : null; }
  /** 1. bibliotheek of catalogmeta, 2. TMDB, 3. het bewaarde beeld. */
  C.pick = function (library, meta, tmdb, stored) {
    return ok(library) || realMeta(meta, stored) || ok(tmdb) || ok(stored) || null;
  };
  /** Alleen opzoeken als de bibliotheek en de catalogmeta geen poster hebben. */
  C.needsLookup = function (library, meta, stored) { return !ok(library) && !realMeta(meta, stored); };
  /** Nog op te zoeken: niet bekend, niet onderweg, elke sleutel één keer, hooguit max. wants = [{title, year, isShow}]. */
  C.pending = function (wants, known, inFlight, max) {
    max = max == null ? C.MAX_LOOKUPS : max;
    var out = [], seen = {};
    for (var i = 0; i < wants.length && out.length < max; i++) {
      var w = wants[i];
      if (!w || !String(w.title || '').trim()) continue;
      var k = C.key(w.title, w.year, w.isShow);
      if (known(k) || B.has(inFlight, k) || B.has(seen, k)) continue;
      seen[k] = true;
      out.push({ key: k, want: w });
    }
    return out;
  };
  C.query = function (w) { var p = B.parseTitle(w.title); return { q: p.title, year: w.year || p.year || null }; };

  // MARK: Verder kijken: TMDB-posters, bewaard op de tv

  var posters = null, inFlight = {}, loading = null, saveTimer = null;
  var DB_KEY = 'continueCovers.v144';
  function load() {
    if (loading) return loading;
    loading = (B.idb ? B.idb.get(DB_KEY) : Promise.resolve(null)).then(function (m) {
      posters = posters || {};
      if (m && typeof m === 'object') Object.keys(m).forEach(function (k) { if (!B.has(posters, k)) posters[k] = m[k]; });
    }, function () { posters = posters || {}; });
    return loading;
  }
  function save() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(function () { if (B.idb && posters) B.idb.set(DB_KEY, posters); }, 1000);
  }
  X.covers = {
    /** Bekende TMDB-poster, anders null (ook als het bewaren nog niet gelezen is). */
    cover: function (title, year, isShow) {
      if (!posters) { load(); return null; }
      var v = posters[C.key(title, year, isShow)];
      return v ? v : null;
    },
    /** Ontbrekende posters opzoeken; daarna 'covers' (Home tekent Verder kijken opnieuw). */
    resolve: function (wants) {
      if (!wants || !wants.length) return Promise.resolve(false);
      return load().then(function () {
        var AR = B.ageRatings;
        if (!AR || !AR.tmdb || !AR.hasKey || !AR.hasKey()) return false;
        var todo = C.pending(wants, function (k) { return B.has(posters, k); }, inFlight);
        if (!todo.length) return false;
        todo.forEach(function (t) { inFlight[t.key] = true; });
        var changed = false, chain = Promise.resolve();
        todo.forEach(function (t) {
          chain = chain.then(function () {
            var q = C.query(t.want), show = !!t.want.isShow;
            var params = [['query', q.q], ['language', AR.tmdbLang()], ['include_adult', 'false']];
            if (q.year > 0) params.push([show ? 'first_air_date_year' : 'year', String(q.year)]);
            return AR.tmdb(show ? '/search/tv' : '/search/movie', params).then(function (r) {
              delete inFlight[t.key];
              if (!r) return;   // netwerkfout of geen sleutel: later opnieuw
              var hit = (r.results || []).filter(function (x) { return x && x.poster_path; })[0];
              posters[t.key] = hit ? 'https://image.tmdb.org/t/p/w342' + hit.poster_path : '';
              changed = true;
            }, function () { delete inFlight[t.key]; });
          });
        });
        return chain.then(function () {
          if (changed) { save(); B.emit('covers'); }
          return changed;
        });
      });
    }
  };
  B.on('wipe', function () { posters = {}; inFlight = {}; if (B.idb) B.idb.del(DB_KEY); });

  // MARK: automatisch verversen na het openen

  var A = X.auto = { SETTLE: 45000, OTHERS_NETWORK_AFTER: 60000 };
  /** Mag het automatisch verversen nu beginnen? */
  A.mayStart = function (uptime, onMain, playing) { return uptime >= A.SETTLE && !!onMain && !playing; };
  /** Wachttijd tot de volgende controle (minstens 5 s). */
  A.waitMs = function (uptime) { return Math.max(5000, A.SETTLE - uptime); };
  /** Milliseconden sinds de start van de app of het kiezen van een profiel (de laatste van de twee). */
  var openedAt = X.t0;
  A.profileOpened = function () { openedAt = Date.now(); };
  A.sinceOpen = function () { return Date.now() - openedAt; };
  A.uptime = function () { return Date.now() - X.t0; };
  /** Nu op het hoofdscherm (profiel gekozen, app in beeld) en speelt er niets? */
  A.onMain = function () { return !!(B.app && B.app.profile) && !(typeof document !== 'undefined' && document.hidden); };
  A.playing = function () { return !!((B.player && B.player.current) || (B.multiview && B.multiview.current)); };
  A.allowed = function () { return A.mayStart(A.sinceOpen(), A.onMain(), A.playing()); };
  /** fn() zodra het mag; stale() = true → niet meer (andere server of ander profiel). */
  A.whenAllowed = function (fn, stale) {
    (function wait() {
      if (stale && stale()) return;
      if (!A.allowed()) { setTimeout(wait, A.waitMs(A.sinceOpen())); return; }
      fn();
    })();
  };
  /** Andere servers niet van het netwerk in de eerste minuut na de start (Promise). */
  A.othersNetworkGrace = function () {
    var w = A.OTHERS_NETWORK_AFTER - A.uptime();
    return w > 0 ? new Promise(function (resolve) { setTimeout(resolve, w); }) : Promise.resolve();
  };
})();
