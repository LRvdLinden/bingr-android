/*
 * Build 148 (versie 1.0.1), gelijk aan Build148Logic.kt op Android (zelfde gevallen in tizen/test/build148_test.js en
 * tools/test/Build148LogicTest.kt). Diagnose-schema 3 (docs/ACCOUNT.md in iptv-ios-appletv, "Build 148").
 * Alleen pure logica, zonder tizen.*-API's (ook voor een andere tv-app op dezelfde basis, zoals LG webOS):
 * - X.codes: mijlpalen, taken en fasen als vaste codes; oude namen worden omgezet; label = Engelse sleutel voor L().
 * - X.updates: bijwerken van een server in fasen (ophalen → inlezen → indexeren → toepassen → bewaren), de laatste 5.
 * - X.hidden: "geleverd" naast "zichtbaar", met de reden van het verschil.
 * - X.stalls: haperingen tijdens het afspelen (playback_stall).
 * - X.mergeSortChunked: sorteren in kleine stappen (merge sort), geen enkele stap langer dan het budget.
 * Geschreven voor Chromium M56 (ES2017, geen ?. ?? of spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var X = B.b148 = {};

  // MARK: codes

  var C = X.codes = {
    FIXED: ['started', 'storage', 'account', 'first_frame', 'intro_done', 'profile_picker', 'profile_picked', 'pin_screen',
      'pin_ok', 'profile', 'servers', 'home', 'catalog', 'ready', 'fetch', 'parse', 'index', 'apply', 'save',
      'sync_apply', 'catalog_filter', 'catalog_sort', 'catalog_meta', 'pin_migrate', 'rows_build', 'guide_parse'],
    NEW: ['categories_migrate', 'pin_hash', 'pin_check', 'track_prefs', 'playback_stall']
  };
  C.ALL = C.FIXED.concat(C.NEW);
  var RE = /^[a-z][a-z0-9_]{0,39}$/;
  C.valid = function (c) { return typeof c === 'string' && RE.test(c); };
  var OLD = {
    'start': 'started', 'launch': 'started', 'app': 'storage', 'activity': 'first_frame', 'splash': 'first_frame',
    'auth': 'account', 'session': 'account', 'profiles': 'profile_picker', 'pin': 'pin_screen', 'servers_loaded': 'servers',
    'sources': 'servers', 'epg': 'guide_parse',
    'categorieën migreren': 'categories_migrate', 'sync toepassen': 'sync_apply', 'pin hashen': 'pin_hash',
    'pin controleren': 'pin_check', 'pin migreren': 'pin_migrate', 'catalogus verwerken': 'parse',
    'catalogus filteren': 'catalog_filter', 'catalogus sorteren': 'catalog_sort', 'indexeren': 'index',
    'bibliotheek toepassen': 'apply', 'rijen opbouwen': 'rows_build', 'gids inlezen': 'guide_parse'
  };
  /** Naam (oud of al een code) → code; null als er geen geldige code van te maken is. */
  C.of = function (name) {
    if (name == null) return null;
    if (Object.prototype.hasOwnProperty.call(OLD, name)) return OLD[name];
    return C.valid(name) ? name : null;
  };
  var LABELS = {
    started: 'Started', storage: 'Storage loaded', account: 'Account', first_frame: 'First screen', intro_done: 'Intro done',
    profile_picker: 'Profile picker', profile_picked: 'Profile chosen', pin_screen: 'PIN screen', pin_ok: 'PIN correct',
    profile: 'Profile opened', servers: 'Servers loaded', home: 'Home', catalog: 'Catalog', ready: 'Ready', fetch: 'Fetching',
    parse: 'Reading', index: 'Indexing', apply: 'Applying', save: 'Saving', sync_apply: 'Applying sync',
    catalog_filter: 'Filtering catalog', catalog_sort: 'Sorting catalog', catalog_meta: 'Catalog details',
    pin_migrate: 'Migrating PIN', rows_build: 'Building rows', guide_parse: 'Reading TV guide',
    categories_migrate: 'Migrating categories', pin_hash: 'Securing PIN', pin_check: 'Checking PIN',
    track_prefs: 'Choosing audio and subtitles', playback_stall: 'Playback stall'
  };
  /** Engelse sleutel (strings.js) voor het scherm; een onbekende code wordt als code getoond. */
  C.label = function (code) { return Object.prototype.hasOwnProperty.call(LABELS, code) ? LABELS[code] : (code || ''); };
  C.LABEL_KEYS = Object.keys(LABELS).map(function (k) { return LABELS[k]; });
  /** Mijlpalen als codes, elke code één keer (de eerste telt): [{code, m}]. */
  C.dedupe = function (marks, nameOf) {
    var seen = {}, out = [];
    (marks || []).forEach(function (m) {
      var c = C.of(nameOf ? nameOf(m) : m.name);
      if (!c || seen[c]) return;
      seen[c] = true;
      out.push({ code: c, m: m });
    });
    return out;
  };

  // MARK: bijwerken per server

  var U = X.updates = { PHASES: ['fetch', 'parse', 'index', 'apply', 'save'], MAX: 5 };
  U.Recorder = function (server, at, start, startMB) {
    this.server = server; this.at = at; this.start = start; this.last = start;
    this.ms = {}; this.mem = {}; this.peakMB = startMB || 0;
  };
  U.Recorder.prototype.sample = function (mb) { if (mb != null && mb > this.peakMB) this.peakMB = mb; };
  /** Sluit de lopende fase af: de tijd sinds de vorige mark telt bij code (twee keer dezelfde code telt op). */
  U.Recorder.prototype.phase = function (code, now, mb) {
    if (U.PHASES.indexOf(code) < 0) return;
    this.ms[code] = (this.ms[code] || 0) + Math.max(0, now - this.last);
    this.mem[code] = mb || 0;
    this.last = now;
    this.sample(mb);
  };
  U.Recorder.prototype.finish = function (result, now) {
    var self = this;
    function clamp(v, hi) { return Math.max(0, Math.min(hi, Math.round(v || 0))); }
    var phases = [];
    U.PHASES.forEach(function (c) {
      if (Object.prototype.hasOwnProperty.call(self.ms, c)) phases.push({ code: c, ms: clamp(self.ms[c], 3600000), memMB: clamp(self.mem[c], 100000) });
    });
    var r = result === 'ok' || result === 'failed' || result === 'cancelled' ? result : 'failed';
    return { server: this.server, at: this.at, phases: phases, totalMs: clamp(now - this.start, 3600000), peakMB: clamp(this.peakMB, 100000), result: r };
  };
  /** Nieuwste eerst, hooguit MAX. */
  U.push = function (list, u) { return [u].concat(list || []).slice(0, U.MAX); };

  // MARK: geleverd en zichtbaar

  var H = X.hidden = {};
  /** Rest van het verschil = overig (leeftijd, kinderslot, zelf verborgen). Nooit negatief. */
  H.of = function (delivered, visible, categories, kids, duplicates) {
    var d = Math.max(0, delivered || 0);
    var v = Math.max(0, Math.min(d, visible || 0));
    var gap = d - v;
    var c = Math.max(0, Math.min(gap, categories || 0)); gap -= c;
    var k = Math.max(0, Math.min(gap, kids || 0)); gap -= k;
    var du = Math.max(0, Math.min(gap, duplicates || 0)); gap -= du;
    return { delivered: d, visible: v, categories: c, kids: k, duplicates: du, other: gap };
  };
  /** Per item de reden: eerst verborgen categorieën, dan het kinderfilter (per groep één keer uitgerekend). */
  H.count = function (items, visible, groupOf, hiddenCategory, notKids) {
    var memo = {}, cats = 0, kids = 0;
    for (var i = 0; i < items.length; i++) {
      var g = groupOf(items[i]);
      var r = memo[g];
      if (r === undefined) r = memo[g] = hiddenCategory(g) ? 1 : notKids(g) ? 2 : 0;
      if (r === 1) cats++; else if (r === 2) kids++;
    }
    return H.of(items.length, visible, cats, kids, 0);
  };

  // MARK: haperingen tijdens het afspelen

  var S = X.stalls = { MIN_MS: 500, MAX: 10 };
  S.Tracker = function () { this.playedAt = -1; this.since = -1; };
  /** Elke tik (of bij een buffer-gebeurtenis); geeft een afgeronde hapering {ms, atS, live} of null. */
  S.Tracker.prototype.update = function (now, playing, buffering, live) {
    if (playing && !buffering) {
      if (this.playedAt < 0) this.playedAt = now;
      var s = this.since;
      this.since = -1;
      if (s >= 0 && now - s >= S.MIN_MS) return { ms: now - s, atS: Math.max(0, Math.floor((s - this.playedAt) / 1000)), live: !!live };
      return null;
    }
    if (buffering && this.playedAt >= 0 && this.since < 0) this.since = now;
    return null;
  };
  S.Tracker.prototype.reset = function () { this.playedAt = -1; this.since = -1; };
  S.push = function (list, s) { return [s].concat(list || []).slice(0, S.MAX); };

  // MARK: sorteren in stukken

  /**
   * Merge sort in kleine stappen (stabiel). step(budget, clock) doet werk tot het budget op is en geeft true als het klaar
   * is; result() = de gesorteerde lijst. Een tv zonder threads blijft zo bedienbaar (elke stap ≤ budget, ook bij 37k).
   */
  X.mergeSortChunked = function (list, cmp) {
    var n = list.length;
    var a = list.slice(), b = new Array(n);
    var width = 1, lo = 0, i = 0, j = 0, k = 0, mid = 0, hi = 0, inRun = false;
    // eerst kleine stukjes van 16 met insertion sort
    var RUN = 16, ins = 0;
    var self = {
      done: n <= 1,
      step: function (budget, clock) {
        if (self.done) return true;
        var t0 = clock(), ops = 0;
        while (ins < n) {
          var end = Math.min(n, ins + RUN);
          for (var x = ins + 1; x < end; x++) {
            var v = a[x], y = x - 1;
            while (y >= ins && cmp(a[y], v) > 0) { a[y + 1] = a[y]; y--; }
            a[y + 1] = v;
          }
          ins = end;
          if (clock() - t0 >= budget) return false;
        }
        if (width === 1) width = RUN;
        while (width < n) {
          while (lo < n) {
            if (!inRun) { mid = Math.min(n, lo + width); hi = Math.min(n, lo + 2 * width); i = lo; j = mid; k = lo; inRun = true; }
            while (k < hi) {
              if (i < mid && (j >= hi || cmp(a[i], a[j]) <= 0)) b[k++] = a[i++]; else b[k++] = a[j++];
              if ((++ops & 255) === 0 && clock() - t0 >= budget) return false;
            }
            inRun = false;
            lo = hi;
          }
          var t = a; a = b; b = t;
          width *= 2; lo = 0;
        }
        self.done = true;
        return true;
      },
      result: function () { return a; }
    };
    return self;
  };
  /** Promise-variant op de tv: stappen van ~budget ms met een pauze ertussen (B.chunked-stijl). */
  X.sortChunked = function (list, cmp, budget) {
    budget = budget || 12;
    var job = X.mergeSortChunked(list, cmp), cancelled = false;
    var p = new Promise(function (resolve, reject) {
      function go() {
        if (cancelled) { reject(new Error('cancelled')); return; }
        var fin;
        try { fin = job.step(budget, Date.now); } catch (e) { reject(e); return; }
        if (fin) resolve(job.result()); else setTimeout(go, 0);
      }
      go();
    });
    p.cancel = function () { cancelled = true; };
    return p;
  };
})();
