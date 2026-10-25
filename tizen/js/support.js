/*
 * Build 146: support en diagnose op de Samsung TV (= SupportCenter.kt, Diagnostics.kt en StartDiagnostics.kt op Android).
 * Contract: docs/ACCOUNT.md in iptv-ios-appletv → "Support vanuit de app", "Diagnose-bijlage" en "Pushmelding bij een
 * antwoord". De tv heeft geen push: de badge (ongelezen antwoorden, counts.unread van app_list) wordt bijgewerkt bij het
 * openen van de tab, bij terugkeer naar de voorgrond en bij elke heartbeat (elke 5 minuten).
 * - B.support: lijst, ticket, reageren, nieuw ticket, sluiten (functie `support`, acties app_*).
 * - B.diag: startdiagnose (mijlpalen met tijd en geheugen, vorige start niet klaar, fout via window.onerror /
 *   unhandledrejection) en de app-diagnose (model via productinfo of tizen.systeminfo, netwerk, opslag, geheugen).
 * Geen persoonsgegevens: geen e-mail, namen, gebruikersnamen, wachtwoorden, URL's of servernamen.
 * Build 147 (diagnose-schema 2, B.b147): detector voor lange taken (gat in een setInterval-tik van 250 ms; 2 s = hang) met
 * voorgrond/achtergrond, de actieve taak (DG.task) en de laatste taken als "stack" (JavaScript kan de stack van een andere
 * taak niet lezen); soort verbinding vanaf de start (tizen.systeminfo NETWORK, anders "unknown"); per server en soort wat
 * er geleverd is (DG.fetchLog); opslag met één totaal; en elke diagnose heeft de startdiagnose (deze start en de vorige).
 * Geschreven voor Chromium M56 (ES2017, geen ?. ?? of spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var A = B.account, X = B.b146;
  var L = function () { return B.L.apply(null, arguments); };

  // MARK: startdiagnose (vroeg, zodat fouten bij het starten gevangen worden)

  var G = X.diag;
  var DG = B.diag = {};
  var KEY_CUR = 'diag.start.current.v146', KEY_PROB = 'diag.start.problem.v146';
  var t0 = Date.now();
  try { if (window.performance && performance.timing && performance.timing.navigationStart) t0 = performance.timing.navigationStart; } catch (e) { /* stil */ }
  var record = null;
  DG.lastProblem = null;
  DG.lastError = null;

  function memMB() {
    try { if (window.performance && performance.memory) return Math.round(performance.memory.usedJSHeapSize / 1048576); } catch (e) { /* stil */ }
    return null;
  }
  function osLabel() { return A && A.osVersion ? A.osVersion() : B.platform.os; }
  function buildNo() { var b = parseInt(B.config.build, 10); return isFinite(b) ? b : 0; }
  function save() { try { B.store.set(KEY_CUR, record); } catch (e) { /* stil */ } }

  DG.start = function () {
    if (record) return;
    var prev = B.store.get(KEY_CUR, null);
    DG.previous = prev && prev.marks ? prev : null;
    var p = G.analyze(prev);
    if (p) { DG.lastProblem = p; B.store.set(KEY_PROB, p); }
    else DG.lastProblem = B.store.get(KEY_PROB, null);
    record = { build: buildNo(), os: osLabel(), started: Date.now(), marks: [], finished: false, peakMB: null };
    DG.mark(G.FIRST);
    // een JavaScript-fout tijdens het starten telt als "gestopt"; daarna alleen als laatste fout in de app-diagnose
    function note(type, reason, stack) {
      var c = { at: new Date().toISOString(), type: String(type || 'Error').slice(0, 80), reason: G.scrub(reason || ''),
        stack: G.stack(String(stack || '').split('\n')), build: buildNo() };
      DG.lastError = c;
      if (record && !record.finished) { record = B.assign({}, record, { crash: c }); save(); }
    }
    window.addEventListener('error', function (ev) {
      var e = ev && ev.error;
      note(e && e.name || 'Error', (e && e.message) || (ev && ev.message) || '', (e && e.stack) || ((ev && ev.filename ? String(ev.filename).split('/').pop() : '') + ':' + (ev && ev.lineno)));
    });
    window.addEventListener('unhandledrejection', function (ev) {
      var r = ev && ev.reason;
      note('UnhandledRejection', (r && r.message) || String(r || ''), r && r.stack);
    });
  };
  /** Mijlpaal (alleen de eerste keer per naam), meteen bewaard. */
  DG.mark = function (name) {
    if (!record) return;
    var n = G.mark(record, name, Date.now() - t0, memMB());
    if (n === record) return;
    record = n;
    save();
  };
  DG.marks = function () { return record ? record.marks : []; };
  DG.uptimeS = function () { return Math.round((Date.now() - t0) / 1000); };
  DG.clearProblem = function () { DG.lastProblem = null; B.store.del(KEY_PROB); B.emit('diag'); };

  // MARK: build 147: taken en lange taken

  var X7 = B.b147;
  DG.taskName = null;
  var trail = [];
  function noteTask(name) { trail.push('taak ' + name); if (trail.length > 12) trail.shift(); }
  /** Synchrone taak met een naam (een hang noemt dan deze taak). */
  DG.task = function (name, fn) {
    var before = DG.taskName;
    DG.taskName = name; noteTask(name);
    try { return fn(); } finally { DG.taskName = before; }
  };
  var detector = X7 ? new X7.hang.Detector() : null;
  DG.maxHangMs = function () { return detector ? detector.maxForegroundMs : 0; };
  /** Build 148: langste hang van deze start (voorgrond gaat voor): {ms, task, milestone, foreground, stack} of null. */
  DG.longestHang = function () { return detector ? detector.worst : null; };
  function visible() { try { return !document.hidden; } catch (e) { return true; } }
  function startDetector() {
    if (!detector) return;
    try {
      document.addEventListener('visibilitychange', function () { detector.reset(Date.now()); });
    } catch (e) { /* stil */ }
    setInterval(function () {
      var now = Date.now();
      var last = record && record.marks.length ? record.marks[record.marks.length - 1].name : null;
      var e = detector.tick(now, { foreground: visible(), task: DG.taskName || (trail.length ? trail[trail.length - 1].slice(5) : null),
        afterReady: !!(record && record.finished), milestone: last, trail: trail.slice() });
      if (!e || !record) return;
      var cur = record.hangMs;
      var curFg = record.hangFg !== false;
      var better = cur == null || (e.foreground && !curFg) || (e.foreground === curFg && e.ms > cur);
      if (!better) return;
      record = B.assign({}, record, { hangMs: e.ms, hangAt: e.milestone, hangFg: e.foreground, hangTask: e.task, hangStack: e.stack,
        hangAfterReady: e.afterReady });
      save();
    }, X7.hang.TICK_MS);
  }

  // soort verbinding vanaf de start (tizen.systeminfo NETWORK; buiten de tv of zonder rechten: "unknown")
  DG.netType = 'unknown';
  function trackNetwork() {
    try {
      if (!(window.tizen && tizen.systeminfo && tizen.systeminfo.getPropertyValue)) return;
      var set = function (n) { if (X7) DG.netType = X7.netType(n && n.networkType); };
      tizen.systeminfo.getPropertyValue('NETWORK', set, function () { /* niet beschikbaar */ });
      if (tizen.systeminfo.addPropertyValueChangeListener) tizen.systeminfo.addPropertyValueChangeListener('NETWORK', set);
    } catch (e) { /* buiten de tv */ }
  }

  DG.start();
  startDetector();
  trackNetwork();

  // MARK: apparaat

  var sysModel = B.store.get('diag.model', '') || '';
  try {
    if (window.tizen && tizen.systeminfo && tizen.systeminfo.getPropertyValue) {
      tizen.systeminfo.getPropertyValue('BUILD', function (b) {
        if (b && b.model) { sysModel = String(b.model).slice(0, 80); B.store.set('diag.model', sysModel); }
      }, function () { /* niet beschikbaar */ });
    }
  } catch (e) { /* buiten de tv */ }
  /** Samsung-modelcode ("QE55Q80C"): productinfo, anders tizen.systeminfo BUILD.model. */
  DG.model = function () { return String((A.model && A.model()) || sysModel || '').slice(0, 80); };
  DG.osVersion = function () { return B.platform.osVersion() || ''; };
  DG.language = function () {
    var lang = B.lang || 'en';
    var m = /^[A-Za-z]{2,3}[-_]([A-Za-z]{2})\b/.exec(String((navigator && navigator.language) || ''));
    return lang.indexOf('-') < 0 && m ? lang + '-' + m[1].toUpperCase() : lang;
  };
  DG.screen = function () { try { return screen.width + 'x' + screen.height; } catch (e) { return ''; } };
  DG.memory = function () {
    var out = { usedMB: 0, totalMB: 0, appMB: memMB() || 0, lowMemory: false };
    try {
      if (window.tizen && tizen.systeminfo) {
        var tot = tizen.systeminfo.getTotalMemory ? tizen.systeminfo.getTotalMemory() : 0;
        var av = tizen.systeminfo.getAvailableMemory ? tizen.systeminfo.getAvailableMemory() : 0;
        if (tot) { out.totalMB = Math.round(tot / 1048576); out.usedMB = Math.max(0, Math.round((tot - av) / 1048576)); out.lowMemory = av > 0 && av / tot < 0.1; }
      }
    } catch (e) { /* stil */ }
    return out;
  };
  function storageMB() {
    var n = 0;
    try { for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); n += k.length + String(localStorage.getItem(k) || '').length; } } catch (e) { /* stil */ }
    return Math.round(n * 2 / 1048576 * 10) / 10;
  }
  function networkType() {
    return new Promise(function (res) {
      try {
        if (!(window.tizen && tizen.systeminfo && tizen.systeminfo.getPropertyValue)) { res(navigator.onLine === false ? 'none' : 'unknown'); return; }
        tizen.systeminfo.getPropertyValue('NETWORK', function (n) {
          var t = String((n && n.networkType) || '').toUpperCase();
          if (X7) DG.netType = X7.netType(t);
          res(t === 'ETHERNET' ? 'ethernet' : t === 'WIFI' ? 'wifi' : t === 'NONE' ? 'none' : (X7 && X7.netType(t) === 'cellular') ? 'cellular' : t ? 'other' : 'unknown');
        }, function () { res(DG.netType || 'unknown'); });
      } catch (e) { res('unknown'); }
    });
  }
  function engine() { return window.webapis && webapis.avplay ? 'avplay' : 'html5'; }
  function appJson(build) { return { platform: B.platform.id, version: String(B.config.version || '').slice(0, 20), build: build || buildNo(), engine: engine(), lang: DG.appLang() }; }
  /** Build 148: de app-taal (`app.lang`), niet die van het systeem. */
  DG.appLang = function () {
    var l = '';
    try { l = String(B.lang || ''); } catch (e) { l = ''; }
    return /^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$/.test(l) && l.length <= 12 ? l : 'en';
  };
  function deviceJson() {
    var d = { type: 'tv', manufacturer: B.platform.maker, os: B.platform.os, osVersion: DG.osVersion(), language: DG.language() };
    var m = DG.model();
    if (m) d.model = m;
    var sc = DG.screen();
    if (sc) d.screen = sc;
    return d;
  }
  /** Build 148 (schema 3): mijlpalen als code (elke code één keer), de oude naam mag mee. */
  function marksJson(marks) {
    var C8 = B.b148 && B.b148.codes;
    var l = (marks || []).slice(0, G.MAX_MARKS);
    if (!C8) return l.map(function (m) { return { name: m.name, ms: m.ms, ok: true }; });
    return C8.dedupe(l).map(function (x) { return { code: x.code, name: x.m.name, ms: x.m.ms, ok: true }; });
  }

  /** Per server (zonder naam of URL): soort en wanneer de lijst het laatst is bijgewerkt (0 = nooit). */
  DG.serverUpdates = function () {
    var D = B.data, Lib = B.lib;
    return (D.sources || []).map(function (s) {
      var at = Lib && Lib.stamps ? (Lib.stamps[String(s.id).toLowerCase()] || Lib.stamps[s.id] || 0) : 0;
      return { kind: s.kind, at: typeof at === 'number' ? at : (B.parseISO(at) || 0) };
    });
  };

  /** Korte netwerkcheck: Bingr-server (ping) en de eigen IPTV-server (build 149: kleine GET, bij Xtream de login; niets bewaard). */
  DG.network = function () {
    var src = B.lib && B.lib.source;
    var base = src ? String(src.serverURL || src.url || src.m3uURL || '').trim() : '';
    var origin = (/^(https?:\/\/[^/?#]+)/i.exec(base) || [])[1] || null;
    var p1 = A.ping();
    var t1 = Date.now();
    // build 149: kleine GET in plaats van HEAD (veel IPTV-servers weigeren HEAD); bij Xtream de login van player_api.php
    var probe = origin + '/';
    if (origin && src.kind === 'Xtream Codes') {
      var D0 = B.data;
      probe = B.query(B.normalizedServer(src.serverURL || origin) + '/player_api.php',
        [['username', src.username || ''], ['password', (D0 && D0.secret ? D0.secret(src, 'password') : '') || '']]);
    }
    var p2 = origin ? B.http(probe, { timeout: 6000, rawText: true }).then(function () { return Date.now() - t1; }, function () { return null; }) : Promise.resolve(undefined);
    return Promise.all([p1, p2, networkType()]).then(function (r) {
      var online = navigator.onLine !== false && r[2] !== 'none';
      return { online: online, type: r[2], backend: G.speed(r[0], r[0] != null), backendMs: r[0], provider: r[1] === undefined ? 'skipped' : G.speed(r[1], r[1] != null),
        providerMs: r[1] || null, vpn: false };
    });
  };

  // MARK: build 147: startdiagnose, ophalen per soort, opslag

  /** Deze start: {summary, timing, maxHangMs, previous}. */
  DG.startInfo = function () {
    var t = X7.start.timing(DG.marks());
    var hang = DG.maxHangMs();
    return { summary: X7.start.summary(t.appMs, hang, false), timing: t, maxHangMs: hang, previous: X7.previous(DG.previous) };
  };
  function startBlock() {
    var i = DG.startInfo();
    var o = { summary: i.summary, appMs: i.timing.appMs, userWaitMs: i.timing.userWaitMs, finished: i.timing.finished };
    if (i.maxHangMs > 0) { o.hangMs = i.maxHangMs; o.maxHangMs = i.maxHangMs; }
    var C8 = B.b148 && B.b148.codes;
    // build 148: langste hang van deze start, met taak en mijlpaal als code
    var h = DG.longestHang();
    if (h && C8) {
      var lh = { ms: Math.max(0, Math.min(3600000, Math.round(h.ms))), foreground: !!h.foreground, stack: (h.stack || []).slice(0, 30).map(function (x) { return String(x).slice(0, 200); }) };
      var tc = C8.of(h.task), mc = C8.of(h.milestone);
      if (tc) lh.task = tc;
      if (mc) lh.milestone = mc;
      o.longestHang = lh;
    }
    if (i.previous) {
      var p = i.previous, pv = { problem: p.problem, seconds: p.seconds, foreground: p.foreground, stack: (p.stack || []).slice(0, 50) };
      var pm = C8 ? C8.of(p.milestone) : p.milestone, pt = C8 ? C8.of(p.task) : p.task;
      if (pm) pv.milestone = pm;
      if (pt) pv.task = String(pt).slice(0, 60);
      o.previous = pv;
    }
    return o;
  }

  // MARK: build 148: bijwerken per server (fasen) en haperingen tijdens het afspelen

  var X8 = B.b148;
  var KEY_UPD = 'diag.updates.v148';
  DG.updates = {
    all: function () { var l = B.store.get(KEY_UPD, []); return Array.isArray(l) ? l : []; },
    begin: function (src) {
      if (!X8) return null;
      var i = 0;
      (B.data && B.data.sources || []).forEach(function (s, n) { if (src && String(s.id).toLowerCase() === String(src.id).toLowerCase()) i = n; });
      return new X8.updates.Recorder(X7.fetch.serverName(i), Date.now(), Date.now(), memMB() || 0);
    },
    phase: function (rec, code) { if (rec) rec.phase(code, Date.now(), memMB() || 0); },
    finish: function (rec, result) {
      if (!rec || rec.finished) return;
      rec.finished = true;
      rec.sample(memMB() || 0);
      var u = rec.finish(result, Date.now());
      try { B.store.set(KEY_UPD, X8.updates.push(DG.updates.all(), u)); } catch (e) { /* stil */ }
    },
    attachment: function () {
      return DG.updates.all().slice(0, 5).map(function (u) {
        return { server: u.server, at: new Date(u.at || Date.now()).toISOString(), phases: (u.phases || []).slice(0, 5),
          totalMs: u.totalMs || 0, peakMB: u.peakMB || 0, result: u.result || 'ok' };
      });
    }
  };
  DG.stalls = [];
  DG.addStall = function (st) { if (X8 && st) DG.stalls = X8.stalls.push(DG.stalls, st); };
  function playbackBlock() {
    var l = DG.stalls, longest = 0;
    l.forEach(function (x) { if (x.ms > longest) longest = x.ms; });
    return { code: 'playback_stall', count: l.length, longestMs: longest, stalls: l.map(function (x) { return { ms: x.ms, atS: x.atS, live: x.live }; }) };
  }

  var KEY_FETCH = 'diag.fetch.v147';
  DG.fetchLog = {
    all: function () { return B.store.get(KEY_FETCH, {}) || {}; },
    delivered: function (id, lib, errs) {
      var m = DG.fetchLog.all(), e = errs || {};
      function ent(n, k) { var o = { n: n }; if (!n && e[k]) o.err = e[k]; return o; }
      // build 149: geleverd = ingelezen + niet ingelezen (verborgen categorieën)
      var X9 = B.b149, hn = function (k) { return X9 ? X9.hiddenCount(lib, k) : 0; };
      m[String(id).toLowerCase()] = { live: ent((lib.channels || []).length + hn('live'), 'live'), movie: ent((lib.movies || []).length + hn('movie'), 'movie'),
        series: ent((lib.series || []).length + hn('series'), 'series'), at: Date.now() };
      DG.fetchLog.prune(m);
      B.store.set(KEY_FETCH, m);
    },
    failed: function (id, code) {
      var m = DG.fetchLog.all();
      m[String(id).toLowerCase()] = { live: { n: 0, err: code }, movie: { n: 0, err: code }, series: { n: 0, err: code }, at: Date.now() };
      B.store.set(KEY_FETCH, m);
    },
    prune: function (m) {
      var ids = {};
      (B.data && B.data.sources || []).forEach(function (s) { ids[String(s.id).toLowerCase()] = true; });
      if (!Object.keys(ids).length) return;
      Object.keys(m).forEach(function (k) { if (!ids[k]) delete m[k]; });
    }
  };
  /** Build 148: fetchRows met geleverd, zichtbaar en waarom verborgen (open server). */
  DG.fetchRows3 = function () {
    var rows = DG.fetchRows();
    var vis = null;
    try { vis = B.lib && B.lib.visibility ? B.lib.visibility() : null; } catch (e) { vis = null; }
    var act = -1;
    (B.data && B.data.sources || []).forEach(function (s, n) { if (B.lib && B.lib.source && String(s.id).toLowerCase() === String(B.lib.source.id).toLowerCase()) act = n; });
    var an = act >= 0 ? X7.fetch.serverName(act) : null;
    rows.forEach(function (r) {
      var v = vis && r.server === an ? vis[r.kind] : null;
      if (v) {
        r.delivered = v.delivered; r.visible = v.visible;
        r.hidden = { categories: v.categories, kids: v.kids, duplicates: v.duplicates, other: v.other };
      } else if (r.status !== 'failed') r.delivered = r.count;
    });
    return rows;
  };
  /** [{server: 's1', kind, status, count, error}] voor alle servers (zonder namen of URL's). */
  DG.fetchRows = function () {
    var D = B.data, Lib = B.lib, log = DG.fetchLog.all(), out = [];
    var act = Lib && Lib.source ? String(Lib.source.id).toLowerCase() : null;
    var raw = Lib && Lib.raw, lib = Lib && Lib.lib;
    (D.sources || []).forEach(function (s, i) {
      var id = String(s.id).toLowerCase(), e = log[id] || null;
      var isActive = id === act && raw && (raw.channels.length || raw.movies.length || raw.series.length);
      X7.fetch.KINDS.forEach(function (k) {
        var key = k === 'live' ? 'channels' : k === 'movie' ? 'movies' : 'series';
        var r = isActive ? raw[key].length : (e && e[k] ? e[k].n : null);
        var v = isActive && lib && lib[key] ? lib[key].length : null;
        var err = e && e[k] ? e[k].err || null : null;
        var st = X7.fetch.status(r, v, err);
        var row = { server: X7.fetch.serverName(i), kind: k, status: st, count: r || 0 };
        if (st === 'failed' && err) row.error = err;
        out.push(row);
      });
    });
    return out;
  };

  function localBytes() {
    var n = 0;
    try { for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); n += k.length + String(localStorage.getItem(k) || '').length; } } catch (e) { /* stil */ }
    return n * 2;
  }
  /** Opslag: catalogus = IndexedDB + localStorage (navigator.storage.estimate als die er is), vrij via tizen.systeminfo STORAGE. */
  DG.storage = function () {
    var est = new Promise(function (res) {
      try {
        if (navigator.storage && navigator.storage.estimate) { navigator.storage.estimate().then(function (e) { res(e && e.usage || 0); }, function () { res(0); }); return; }
      } catch (e) { /* stil */ }
      res(0);
    });
    var free = new Promise(function (res) {
      try {
        if (!(window.tizen && tizen.systeminfo && tizen.systeminfo.getPropertyValue)) { res(null); return; }
        tizen.systeminfo.getPropertyValue('STORAGE', function (st) {
          var u = st && st.units && st.units[0];
          res(u && typeof u.availableCapacity === 'number' ? u.availableCapacity : null);
        }, function () { res(null); });
      } catch (e) { res(null); }
    });
    return Promise.all([est, free]).then(function (r) {
      var ls = localBytes();
      return X7.storage(0, Math.max(r[0], ls), 0, r[1]);
    });
  };
  /** Blokken van schema 2 en (build 148) schema 3 die in elke diagnose zitten. */
  function schema2(out) {
    function three() {
      out.schema = 3;
      out.updates = DG.updates.attachment();
      if (DG.stalls.length) out.playback = playbackBlock();
    }
    return DG.storage().then(function (st) {
      out.schema = 2;
      out.start = startBlock();
      out.fetch = DG.fetchRows3();
      three();
      out.network = { type: DG.netType === 'wifi' || DG.netType === 'ethernet' || DG.netType === 'cellular' ? DG.netType : 'unknown' };
      var so = { totalMB: st.totalMB, cacheMB: st.cacheMB, catalogMB: st.catalogMB, downloadsMB: st.downloadsMB };
      if (st.freeMB != null) so.freeMB = st.freeMB;
      out.storage = so;
      return out;
    }, function () { out.start = startBlock(); out.fetch = DG.fetchRows3(); out.network = { type: 'unknown' }; three(); return out; });
  }

  /** App-diagnose (schema 2, kind app). */
  DG.app = function () {
    var D = B.data, Lib = B.lib;
    return DG.network().then(function (net) {
      var lib = (Lib && Lib.lib) || { channels: [], movies: [], series: [] };
      var ent = A.entitlements ? A.entitlements() : null;
      var nw = { online: net.online, type: net.type, backend: net.backend, provider: net.provider, vpn: false };
      if (net.backendMs != null) nw.backendMs = net.backendMs;
      if (net.providerMs != null) nw.providerMs = net.providerMs;
      var kinds = [];
      (D.sources || []).forEach(function (s) { if (kinds.indexOf(s.kind) < 0) kinds.push(s.kind); });
      var st = { signedIn: A.isSignedIn(), profiles: (D.profiles || []).length, servers: (D.sources || []).length, serverKinds: kinds,
        pushEnabled: false, channels: (lib.channels || []).length, movies: (lib.movies || []).length, series: (lib.series || []).length,
        uptimeS: DG.uptimeS(), network: nw, storage: { dbMB: storageMB() } };
      if (D.lastSyncAt) { st.lastSync = new Date(D.lastSyncAt).toISOString(); st.lastSyncOk = D.lastSyncOk !== false; }
      if (ent) st.plan = ent.free ? 'free' : 'lifetime';
      var out = { schema: 1, kind: 'app', createdAt: new Date().toISOString(), app: appJson(), device: deviceJson(),
        milestones: marksJson(DG.marks()), memory: DG.memory(), state: st };
      if (DG.lastError) out.crash = DG.lastError;
      return schema2(out);
    });
  };
  /** Startdiagnose van de vorige start. */
  DG.startJson = function (p) {
    var out = { schema: 1, kind: 'start', createdAt: new Date(p.at || Date.now()).toISOString(), app: appJson(p.build), device: deviceJson(),
      milestones: marksJson(p.marks), memory: B.assign(DG.memory(), { appMB: p.peakMB || 0 }),
      state: { signedIn: A.isSignedIn(), profiles: (B.data.profiles || []).length, servers: (B.data.sources || []).length, uptimeS: Math.round((p.ms || 0) / 1000) } };
    if (p.crash) out.crash = p.crash;
    return out;
  };
  /** Build 147: startdiagnose van de vorige start, met deze start, servers, verbinding en opslag (schema 2). */
  DG.startFull = function (p) { return schema2(DG.startJson(p)); };
  /**
   * Wat er bij "Diagnose meesturen" meegaat: na een mislukte vorige start de startdiagnose, anders de app-diagnose. Build 147:
   * allebei met de startdiagnose (deze start en de vorige).
   */
  DG.forSupport = function () { return DG.lastProblem ? DG.startFull(DG.lastProblem) : DG.app(); };

  // MARK: support (functie `support`, acties app_*)

  var SP = X.support;
  var SC = B.support = { items: [], counts: { open: 0, in_progress: 0, closed: 0, unread: 0 }, loaded: false, loading: false, error: null };
  /** Wat er geopend moet worden: null = niets, 0 = de lijst, anders het ticketnummer. */
  SC.openRequest = null;
  var lastRefresh = 0, busy = null;

  SC.errorText = function (r) {
    if (!r) return L('No internet connection.');
    var e = r.json && r.json.error;
    if (r.status === 401 && e === 'user_banned') return A.bannedMessage();
    return L(SP.errorKey(e));
  };
  function ticket(t) {
    var status = t.status || 'new';
    return { number: t.number, subject: t.subject || '', status: status, group: SP.group(status, t.group), category: t.category || null,
      createdAt: B.parseISO(t.created_at) || 0, lastReplyAt: B.parseISO(t.last_reply_at) || 0, canReply: t.can_reply !== false && status !== 'closed',
      unread: t.unread === true, unreadCount: t.unread_count || 0 };
  }
  SC.unread = function () { return SC.counts.unread || 0; };
  /** Lijst en badge vernieuwen (stil; hoogstens elke 10 s, tenzij force). */
  SC.refresh = function (force) {
    if (!A.isSignedIn()) { SC.reset(); return Promise.resolve(false); }
    if (busy) return busy;
    if (!force && Date.now() - lastRefresh < 10000) return Promise.resolve(true);
    lastRefresh = Date.now();
    SC.loading = true;
    busy = A.support({ action: 'app_list', status: 'all' }).then(function (r) {
      if (!r || !r.ok || !r.json) { SC.error = SC.errorText(r); return false; }
      SC.items = (r.json.items || []).filter(function (t) { return t && t.number; }).map(ticket);
      SC.counts = B.assign({ open: 0, in_progress: 0, closed: 0, unread: 0 }, r.json.counts || {});
      SC.error = null; SC.loaded = true;
      return true;
    }).catch(function () { return false; }).then(function (okv) { SC.loading = false; busy = null; B.emit('support'); return okv; });
    return busy;
  };
  SC.reset = function () {
    SC.items = []; SC.counts = { open: 0, in_progress: 0, closed: 0, unread: 0 }; SC.loaded = false; SC.error = null; lastRefresh = 0;
    B.emit('support');
  };
  /** {ticket, messages} | 'not_found' | {error}. Zet het ticket op gelezen. */
  SC.get = function (n) {
    return A.support({ action: 'app_get', number: n }).then(function (r) {
      if (r && r.status === 404) return 'not_found';
      if (!r || !r.ok || !r.json || !r.json.ticket) return { error: SC.errorText(r) };
      var t = ticket(r.json.ticket);
      var msgs = (r.json.messages || []).map(function (m) {
        return { author: m.author || '', body: m.body || '', createdAt: B.parseISO(m.created_at) || 0, diagnostics: m.diagnostics && m.diagnostics.kind || null };
      });
      var had = false;
      SC.items = SC.items.map(function (x) { if (x.number === n && x.unread) { had = true; return B.assign({}, x, { unread: false, unreadCount: 0 }); } return x; });
      if (had) { SC.counts.unread = Math.max(0, (SC.counts.unread || 0) - 1); B.emit('support'); }
      return { ticket: t, messages: msgs };
    });
  };
  /** null = gelukt, anders de fouttekst. */
  SC.reply = function (n, message, diagnostics) {
    var c = SP.checkReply(message);
    if (c) return Promise.resolve(L(SP.errorKey(c)));
    var body = { action: 'app_reply', number: n, message: String(message).trim() };
    if (diagnostics) body.diagnostics = diagnostics;
    return A.support(body).then(function (r) {
      if (!r || !r.ok) return SC.errorText(r);
      SC.refresh(true);
      return null;
    });
  };
  /** {number} of {error}. */
  SC.create = function (subject, category, message, diagnostics) {
    var c = SP.checkNew(subject, message);
    if (c) return Promise.resolve({ error: L(SP.errorKey(c)) });
    var body = { action: 'app_create', subject: String(subject).trim(), message: String(message).trim(), category: category,
      device_id: A.deviceId(), platform: B.platform.id, app_version: A.appVersion(), lang: B.lang === 'nl' ? 'nl' : 'en' };
    if (diagnostics) body.diagnostics = diagnostics;
    return A.support(body).then(function (r) {
      if (!r || !r.ok || !r.json) return { error: SC.errorText(r) };
      SC.refresh(true);
      return { number: r.json.number };
    });
  };
  SC.close = function (n) {
    return A.support({ action: 'app_close', number: n }).then(function (r) {
      if (!r || !r.ok) return SC.errorText(r);
      SC.refresh(true);
      return null;
    });
  };
  B.on('signedOut', function () { SC.reset(); });
})();
