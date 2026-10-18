/*
 * Build 128: "Eén lijst over alle servers" (docs/ACCOUNT.md van de iOS-repo, sectie Build 128; = AllServers.kt op Android).
 * - Verder kijken, Mijn lijst, favorieten, recente zenders, geschiedenis en handmatige collecties tonen items van ALLE
 *   servers. Items van een andere bestaande server spelen/openen daar (item._src = server-id), zonder te wisselen.
 * - Items van een verwijderde server (of die niet meer op hun server staan) worden op de andere servers gezocht (actieve
 *   eerst): gevonden → remap-regel in `catalogmeta` en alle lijsten/collecties van alle profielen verhuizen; niet gevonden
 *   → het item blijft, bij aanklikken een melding met Verwijderen / Zoeken / Annuleren.
 * - State-section `catalogmeta` (meta per item + remap-regels) lezen, samenvoegen (nieuwste `at` wint), begrenzen, uploaden.
 * - Zuinig met geheugen (oude tv's): catalogi van andere servers één tegelijk uit IndexedDB, alleen de items waarnaar
 *   verwezen wordt blijven bewaard; zoeken loopt de lijsten door zonder ze te kopiëren; niet tijdens het afspelen.
 * Geschreven voor Chromium M56 (ES2017, geen ?. ?? of object-spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var D = B.data, Lib = B.lib;
  var L = function () { return B.L.apply(null, arguments); };
  var AS = B.allservers = {};
  function lower(s) { return String(s == null ? '' : s).toLowerCase(); }
  var DAY = 86400000, META_DAYS = 90, REMAP_DAYS = 180, MAX_META = 6000;

  // MARK: sleutels (pure functies, gelijk aan AllServersLogic.kt)

  AS.serverOf = function (k) { var i = String(k).indexOf('|'); return i > 0 ? String(k).slice(0, i) : null; };
  AS.itemOf = function (k) { var i = String(k).indexOf('|'); return i < 0 ? String(k) : String(k).slice(i + 1); };
  AS.metaKey = function (k) { var i = String(k).indexOf('|'); return i <= 0 ? String(k) : lower(String(k).slice(0, i)) + String(k).slice(i); };
  AS.remapKey = function (k) { return lower(k); };
  AS.sameServer = function (a, b) { return a != null && b != null && lower(a) === lower(b); };

  // MARK: normaliseren

  var langPrefix = /^\s*(?:[a-z]{2,3}\s*[:|]\s*|[\[(|]\s*[a-z]{2,3}\s*[\])|]\s*|[a-z]{2}\s+-\s+)/;
  var yearParen = /[(\[]\s*((?:19|20)\d{2})\s*[)\]]/;
  var yearParenG = /[(\[]\s*((?:19|20)\d{2})\s*[)\]]/g;
  var trailingYear = /\s((?:19|20)\d{2})\s*$/;
  var tags = /[(\[][^)\]]{0,12}[)\]]/g;
  var episodeMark = /\bs\d{1,2}\s*e\d{1,3}\b.*$/;
  var titleNoise = /\b(?:4k|uhd|fhd|hd|sd|hevc|h\.?26[45]|x26[45]|1080p|720p|2160p|multi|dual|nl subs?|subs?|dolby|atmos|hdr|dubbed|vost|vose)\b/g;
  var channelNoise = /\b(?:hd|fhd|uhd|4k|8k|sd|hevc|h\.?26[45]|1080[pi]?|720p|2160p|50fps|60fps|25fps|hdr|backup|raw)\b/g;
  var trailingCountry = /\s(?:nl|be|de|uk|us|fr|es|it|pl|tr|pt|en)\s*$/;

  AS.yearIn = function (t) {
    var m = yearParen.exec(String(t || '')) || trailingYear.exec(String(t || '').trim());
    return m ? parseInt(m[1], 10) : null;
  };
  AS.normTitle = function (raw) {
    var s = B.fold(raw).trim();
    for (var i = 0; i < 2; i++) s = s.replace(langPrefix, '');
    s = s.replace(episodeMark, ' ').replace(yearParenG, ' ').replace(tags, ' ').replace(trailingYear, ' ').replace(titleNoise, ' ');
    s = s.replace(/&/g, ' ').replace(/[^a-z0-9À-￿]+/g, ' ');
    return s.split(' ').filter(Boolean).join(' ');
  };
  AS.normChannel = function (raw) {
    var s = B.fold(raw).trim();
    for (var i = 0; i < 2; i++) s = s.replace(langPrefix, '');
    s = s.replace(channelNoise, ' ').replace(/ᴴᴰ|ʰᵈ/g, ' ');
    s = s.trim().replace(trailingCountry, '');
    return s.replace(/[^a-z0-9+À-￿]/g, '');
  };
  /** Seizoen/aflevering uit "S1:E2 · Titel" of "S01E02". */
  AS.seasonEpisode = function (s) {
    var m = /\bS(\d{1,2})\s*:?\s*E(\d{1,3})\b/i.exec(String(s || ''));
    return m ? [parseInt(m[1], 10), parseInt(m[2], 10)] : null;
  };

  // MARK: matchen

  function yearOK(a, b) { return a == null || b == null || Math.abs(a - b) <= 1; }
  /** w = {kind, title, year, tmdb, epg}; c = {sid, id, kind, title, year, tmdb, epg}. */
  AS.matches = function (w, c) {
    var kind = w.kind === 'episode' ? 'series' : w.kind;
    if (c.kind !== kind) return false;
    if (kind === 'channel') {
      var e1 = lower(w.epg).trim(), e2 = lower(c.epg).trim();
      if (e1 && e1 === e2) return true;
      var n = AS.normChannel(w.title);
      return n.length >= 2 && n === AS.normChannel(c.title);
    }
    if (w.tmdb != null && c.tmdb != null) return String(w.tmdb) === String(c.tmdb);
    var t = AS.normTitle(w.title);
    if (!t || t !== AS.normTitle(c.title)) return false;
    return yearOK(w.year != null ? w.year : AS.yearIn(w.title), c.year != null ? c.year : AS.yearIn(c.title));
  };
  /** Beste kandidaat: servers in volgorde (actieve eerst), TMDB-match vóór titel, exact jaar vóór ±1. */
  AS.best = function (w, cands, order, exclude) {
    var ex = {}; (exclude || []).forEach(function (s) { ex[lower(s)] = true; });
    var rank = {}; (order || []).forEach(function (s, i) { rank[lower(s)] = i; });
    var wy = w.year != null ? w.year : AS.yearIn(w.title);
    var hits = cands.filter(function (c) { return !ex[lower(c.sid)] && AS.matches(w, c); });
    function r(c) { return B.has(rank, lower(c.sid)) ? rank[lower(c.sid)] : 1e9; }
    hits.sort(function (a, b) {
      var d = r(a) - r(b); if (d) return d;
      d = (w.tmdb != null && String(a.tmdb) === String(w.tmdb) ? 0 : 1) - (w.tmdb != null && String(b.tmdb) === String(w.tmdb) ? 0 : 1); if (d) return d;
      var ya = a.year != null ? a.year : AS.yearIn(a.title), yb = b.year != null ? b.year : AS.yearIn(b.title);
      return (wy != null && ya === wy ? 0 : 1) - (wy != null && yb === wy ? 0 : 1);
    });
    return hits[0] || null;
  };
  AS.dedupKey = function (kind, title, year, tmdb) {
    if (kind === 'channel') return 'c:' + AS.normChannel(title);
    var p = kind === 'movie' ? 'm:' : 's:';
    if (tmdb != null) return p + 'tmdb:' + tmdb;
    var y = year != null ? year : AS.yearIn(title);
    return p + AS.normTitle(title) + ':' + (y == null ? '' : y);
  };

  // MARK: build 129: zoeken en slimme collecties over alle servers (= AllServersLogic.crossKey/mergeAcross/smartHit)

  /** Hetzelfde werk op twee servers: {base, year}; base = soort + genormaliseerde titel/zender, jaar ±1; null = niet samenvoegen. */
  AS.crossKey = function (kind, title, year, tmdb) {
    if (kind === 'channel') { var n = AS.normChannel(title); return n.length >= 2 ? { base: 'c:' + n, year: null } : null; }
    var p = kind === 'movie' ? 'm:' : 's:';
    if (tmdb != null) return { base: p + 'tmdb:' + tmdb, year: null };
    var t = AS.normTitle(title);
    if (!t) return null;
    return { base: p + t, year: year != null ? year : AS.yearIn(title) };
  };
  /** Sleutel van een {type, item}-treffer. */
  AS.entryKey = function (e) {
    if (!e || !e.item) return null;
    return e.type === 'channel' ? AS.crossKey('channel', e.item.name) : AS.crossKey(e.type, e.item.rawTitle || e.item.title, e.item.year);
  };
  /**
   * Treffers van meerdere servers tot één lijst: groups op volgorde (actieve server eerst). De eerste groep blijft
   * helemaal; van de andere servers valt een treffer weg als hetzelfde werk er al in staat. Volgorde blijft.
   */
  AS.mergeAcross = function (groups, keyOf, limit) {
    var seen = {}, out = [];
    limit = limit == null ? 1e9 : limit;
    for (var g = 0; g < groups.length; g++) {
      var list = groups[g] || [];
      for (var i = 0; i < list.length; i++) {
        if (out.length >= limit) return out;
        var k = keyOf(list[i]);
        if (k && g > 0 && (seen[k.base] || []).some(function (y) { return yearOK(y, k.year); })) continue;
        if (k) (seen[k.base] = seen[k.base] || []).push(k.year);
        out.push(list[i]);
      }
    }
    return out;
  };
  /** Slimme collectie: past een gevouwen naam bij de zoekwoorden (één is genoeg) en de categorie? */
  AS.smartHit = function (folded, group, words, wantGroup) {
    if (wantGroup && group !== wantGroup) return false;
    for (var i = 0; i < words.length; i++) if (words[i] && folded.indexOf(words[i]) >= 0) return true;
    return false;
  };

  // MARK: remap-regels

  /** Eindsleutel via de regels (ketens, max. 10 stappen, nooit een lus); null = geen regel. */
  AS.resolve = function (rules, key) {
    var cur = lower(key), out = null, seen = {}, n = 0;
    while (!seen[cur] && n < 10) {
      seen[cur] = true; n++;
      var r = rules[cur];
      if (!r) break;
      if (lower(r.to) === lower(key)) return null;
      out = { to: r.to, at: Math.max(out ? out.at : 0, r.at || 0) };
      cur = lower(r.to);
    }
    return out;
  };
  AS.addRule = function (rules, from, to, at) {
    if (lower(from) === lower(to)) return rules;
    var end = AS.resolve(rules, to);
    if (lower(end ? end.to : to) === lower(from)) return rules;
    var out = B.assign({}, rules);
    out[lower(from)] = { to: to, at: at };
    return out;
  };
  /** Remap op een set {sleutel: {at, on}}; null = niets veranderd. keyOf/newKeyOf zoals in Kotlin. */
  AS.remapSet = function (m, rules, keyOf, newKeyOf) {
    if (!rules || !Object.keys(rules).length) return null;
    var out = null;
    Object.keys(m || {}).forEach(function (k) {
      var e = m[k];
      if (!e || !e.on) return;
      var rk = keyOf ? keyOf(k) : k;
      if (rk == null) return;
      var t = AS.resolve(rules, rk);
      if (!t) return;
      var nk = newKeyOf ? newKeyOf(t.to) : t.to;
      if (nk === k) return;
      out = out || B.assign({}, m);
      out[k] = { at: Math.max(t.at, e.at + 1), on: false };
      var cur = null;
      Object.keys(out).forEach(function (x) { if (x === nk || AS.metaKey(x) === AS.metaKey(nk)) cur = cur || x; });
      if (!cur || (!out[cur].on && out[cur].at < e.at)) out[nk] = { at: e.at, on: true };
    });
    return out;
  };

  /**
   * Remap op een profielmodel van sync.js ({sets, progress, history}): Mijn lijst, favorieten, recente zenders,
   * voortgang (item-id + playlistID), geschiedenis en bekeken (nieuwe id erbij). Geeft een nieuw model of hetzelfde.
   */
  AS.applyRemapState = function (st, rules) {
    rules = rules || CM.remap;
    if (!st || !Object.keys(rules).length) return st;
    var changed = false, moved = [];
    var sets = B.assign({}, st.sets);
    ['myList', 'favorites', 'recentChannels'].forEach(function (f) {
      var r = AS.remapSet(st.sets[f] || {}, rules);
      if (!r) return;
      Object.keys(st.sets[f]).forEach(function (k) {
        if (st.sets[f][k].on && r[k] && !r[k].on) { var t = AS.resolve(rules, k); if (t) moved.push([AS.itemOf(k), AS.itemOf(t.to)]); }
      });
      sets[f] = r; changed = true;
    });
    var progress = st.progress;
    Object.keys(st.progress || {}).forEach(function (k) {
      var it = st.progress[k], e = it && it.v;
      if (!e || !e.playlistID) return;
      var t = AS.resolve(rules, e.playlistID + '|' + k);
      if (!t) return;
      var sid = AS.serverOf(t.to), nk = AS.itemOf(t.to);
      if (!sid) return;
      if (progress === st.progress) progress = B.assign({}, st.progress);
      changed = true;
      var ne = B.assign({}, e, { playlistID: sid });
      if (nk === k) { if (!AS.sameServer(e.playlistID, sid)) progress[k] = { at: it.at, v: ne }; return; }
      progress[k] = { at: Math.max(t.at, it.at + 1), v: null };
      var cur = progress[nk];
      if (!cur || cur.at < it.at) progress[nk] = { at: it.at, v: ne };
      moved.push([k, nk]);
    });
    var history = st.history;
    Object.keys(st.history || {}).forEach(function (k) {
      var it = st.history[k], e = it && it.v;
      if (!e || !e.playlistID) return;
      var t = AS.resolve(rules, e.playlistID + '|' + e.itemID);
      if (!t) return;
      var sid = AS.serverOf(t.to), item = AS.itemOf(t.to);
      var ne = B.assign({}, e, { playlistID: sid, itemID: item });
      if (e.channelID) ne.channelID = item;
      var nid = sid + '|' + item;
      if (nid === k) return;
      if (history === st.history) history = B.assign({}, st.history);
      changed = true;
      history[k] = { at: Math.max(t.at, it.at + 1), v: null };
      var cur = history[nid];
      if (!cur || cur.at < it.at) history[nid] = { at: it.at, v: ne };
    });
    var w = st.sets.watched;
    if (w && moved.length) {
      var nw = B.assign({}, w), wc = false;
      moved.forEach(function (p) {
        var o = p[0], n = p[1];
        if (o === n) return;
        Object.keys(w).forEach(function (wk) {
          if (!w[wk].on) return;
          var nk = wk === o ? n : (wk.indexOf('ep:' + o + '|') === 0 ? 'ep:' + n + '|' + wk.slice(('ep:' + o + '|').length) : null);
          if (!nk) return;
          var cur = nw[nk];
          if (!cur || (!cur.on && cur.at < w[wk].at)) { nw[nk] = { at: w[wk].at, on: true }; wc = true; }
        });
      });
      if (wc) { sets.watched = nw; changed = true; }
    }
    if (!changed) return st;
    return { sets: sets, progress: progress, history: history };
  };

  /** Remap op de handmatige collecties van alle profielen (section `collections`); true = gewijzigd (data aangepast). */
  AS.applyRemapCollections = function (data, rules) {
    rules = rules || CM.remap;
    if (!data || !data.profiles || !Object.keys(rules).length) return false;
    var changed = false, now = B.iso();
    Object.keys(data.profiles).forEach(function (pk) {
      var bucket = data.profiles[pk];
      (bucket && Array.isArray(bucket.items) ? bucket.items : []).forEach(function (c) {
        if (!c || c.deleted === true || c.kind === 'smart' || !Array.isArray(c.items)) return;
        var any = false, out = [];
        c.items.forEach(function (r) {
          var sid = r.sid || c.sourceID, n = r;
          if (sid) {
            var t = AS.resolve(rules, sid + '|' + r.id);
            if (t) { n = { type: r.type, id: AS.itemOf(t.to), sid: AS.serverOf(t.to) }; any = true; }
          }
          if (!out.some(function (x) { return x.type === n.type && x.id === n.id && (!x.sid || !n.sid || lower(x.sid) === lower(n.sid)); })) out.push(n);
        });
        if (any) { c.items = out; c.updatedAt = now; changed = true; }
      });
    });
    if (changed) data.updatedAt = now;
    return changed;
  };

  // MARK: catalogmeta (state-section, lokaal in IndexedDB)

  var CM = { items: {}, remap: {} };
  var cmLoaded = false;
  var cmIndex = {};   // kleine letters → sleutel
  function time(v) { if (v == null) return 0; if (typeof v === 'number') return v > 1e12 ? v : v * 1000; return B.parseISO(v) || 0; }
  function reindex() { cmIndex = {}; Object.keys(CM.items).forEach(function (k) { cmIndex[lower(k)] = k; }); }
  AS.load = function () {
    if (cmLoaded) return Promise.resolve();
    return B.idb.get('catalogmeta').then(function (o) {
      if (o && typeof o === 'object') setFrom(o);
      cmLoaded = true;
    });
  };
  function parseRules(o) {
    var out = {};
    Object.keys(o || {}).forEach(function (k) {
      var r = o[k];
      if (r && r.to && String(r.to).indexOf('|') > 0) out[lower(k)] = { to: String(r.to), at: time(r.at) };
    });
    return out;
  }
  function parseItems(o) {
    var out = {};
    Object.keys(o || {}).forEach(function (k) { if (o[k] && typeof o[k] === 'object') out[AS.metaKey(k)] = o[k]; });
    return out;
  }
  function setFrom(o) { CM = { items: parseItems(o.items), remap: parseRules(o.remap) }; reindex(); }
  var saveTimer = null;
  function save() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(function () { B.idb.set('catalogmeta', toJSON()); }, 1500);
  }
  function toJSON() {
    var rm = {};
    Object.keys(CM.remap).sort().forEach(function (k) { rm[k] = { to: CM.remap[k].to, at: B.iso(CM.remap[k].at) }; });
    var items = {};
    Object.keys(CM.items).sort().forEach(function (k) { items[k] = CM.items[k]; });
    return { items: items, remap: rm, updatedAt: B.iso() };
  }
  AS.wipe = function () { CM = { items: {}, remap: {} }; reindex(); found = {}; absent = {}; epgs = {}; tried = {}; smart = {}; smartTried = {}; scans = {}; smartDone = null; B.idb.del('catalogmeta'); B.store.del('allservers.migrated128'); };
  AS.meta = function (key) { var k = AS.metaKey(key); return CM.items[k] || CM.items[cmIndex[lower(k)]] || null; };
  AS.rules = function () { return CM.remap; };
  AS.target = function (key) { var t = AS.resolve(CM.remap, key); return t ? t.to : null; };

  var dirty = false;
  /** Meta van een item vastleggen (alleen als er iets anders is; dan at = nu). */
  AS.record = function (key, o) {
    if (!o || !o.t || String(key).indexOf('|') <= 0) return false;
    var k = AS.metaKey(key), cur = AS.meta(k) || {};
    var n = B.assign({}, cur);
    n.t = o.t; n.k = o.k; n.sid = o.sid;
    if (o.y != null) n.y = o.y; else if (!B.has(n, 'y')) n.y = null;
    if (o.tmdb != null) n.tmdb = o.tmdb; else if (!B.has(n, 'tmdb')) n.tmdb = null;
    if (o.img) n.img = o.img; else if (!B.has(n, 'img')) n.img = null;
    if (o.sname) n.sname = o.sname;
    if (o.ep) n.ep = o.ep; else if (!B.has(n, 'ep')) n.ep = null;
    if (o.k === 'channel') n.ch = { name: (o.ch && o.ch.name) || o.t, epg: (o.ch && o.ch.epg) || (cur.ch && cur.ch.epg) || null };
    else if (!B.has(n, 'ch')) n.ch = null;
    var a = B.assign({}, n), b = B.assign({}, cur); delete a.at; delete b.at;
    if (B.has(cur, 't') && JSON.stringify(a) === JSON.stringify(b)) return false;
    n.at = B.iso();
    CM.items[k] = n; cmIndex[lower(k)] = k;
    dirty = true; save(); scheduleUpload();
    return true;
  };
  AS.addRemap = function (from, to) {
    var n = AS.addRule(CM.remap, from, to, Date.now());
    if (n === CM.remap) return !!AS.target(from);
    CM.remap = n; dirty = true; save(); scheduleUpload();
    return true;
  };
  var upTimer = null;
  function scheduleUpload() {
    clearTimeout(upTimer);
    upTimer = setTimeout(function () { if (dirty && B.account.isSignedIn()) D.syncState(); }, 4000);
  }

  /** Sleutels waarnaar het actieve profiel en de collecties verwijzen (voor het opruimen). */
  function referenced() {
    var out = {};
    ['myList', 'favorites', 'recentChannels'].forEach(function (f) { D.setList(f).forEach(function (k) { out[AS.metaKey(k)] = true; }); });
    D.progressList().forEach(function (pe) { if (pe.e.playlistID) out[AS.metaKey(pe.e.playlistID + '|' + pe.key)] = true; });
    D.history().forEach(function (e) { if (e.playlistID) out[AS.metaKey(e.playlistID + '|' + e.itemID)] = true; });
    var c = D.collections;
    if (c && c.profiles) Object.keys(c.profiles).forEach(function (pk) {
      ((c.profiles[pk] && c.profiles[pk].items) || []).forEach(function (col) {
        (col && col.items || []).forEach(function (r) { var sid = r.sid || col.sourceID; if (sid) out[AS.metaKey(sid + '|' + r.id)] = true; });
      });
    });
    return out;
  }
  function prune(items, refs, now) {
    var cutoff = now - META_DAYS * DAY;
    var keys = Object.keys(items).filter(function (k) { return refs[k] || time(items[k].at) >= cutoff; });
    if (keys.length > MAX_META) {
      keys.sort(function (a, b) { return ((refs[a] ? 1 : 0) - (refs[b] ? 1 : 0)) || (time(items[a].at) - time(items[b].at)); });
      keys = keys.slice(keys.length - MAX_META);
    }
    var out = {}; keys.forEach(function (k) { out[k] = items[k]; });
    return out;
  }

  /**
   * Bij elke sync (sync.js): serverversie samenvoegen (per sleutel nieuwste `at`), begrenzen, lokaal bewaren en
   * uploaden als het afwijkt. Geeft een promise.
   */
  AS.syncMeta = function (all) {
    return AS.load().then(function () {
      var sec = all && all.catalogmeta, data = sec && sec.data && typeof sec.data === 'object' ? sec.data : null;
      var sItems = parseItems(data && data.items), sRules = parseRules(data && data.remap);
      var items = B.assign({}, CM.items);
      Object.keys(sItems).forEach(function (k) { var c = items[k]; if (!c || time(sItems[k].at) >= time(c.at)) items[k] = sItems[k]; });
      var rules = B.assign({}, CM.remap);
      Object.keys(sRules).forEach(function (k) { var c = rules[k]; if (!c || sRules[k].at >= c.at) rules[k] = sRules[k]; });
      var now = Date.now();
      items = prune(items, referenced(), now);
      Object.keys(rules).forEach(function (k) { if (rules[k].at < now - REMAP_DAYS * DAY) delete rules[k]; });
      var changedLocal = JSON.stringify(items) !== JSON.stringify(CM.items) || JSON.stringify(rules) !== JSON.stringify(CM.remap);
      CM = { items: items, remap: rules }; reindex();
      if (changedLocal) { save(); B.emit('allservers'); }
      var differs = !data || JSON.stringify(items) !== JSON.stringify(sItems) || JSON.stringify(rules) !== JSON.stringify(sRules);
      if (!differs) { dirty = false; return; }
      if (!data && !Object.keys(items).length && !Object.keys(rules).length) { dirty = false; return; }
      var json = B.assign({}, data || {}, toJSON());
      return B.account.putState('catalogmeta', json).then(function (at) { if (at != null) dirty = false; });
    }).catch(function (e) { B.log('catalogmeta', e); });
  };

  // MARK: items van andere servers

  var found = {};    // metaKey → {type, item (met _src)}
  var epgs = {};     // metaKey → programma's van een zender van een andere server (gids en terugkijken)
  var absent = {};   // metaKey → true: server bestaat, item staat er niet (meer) in
  var tried = {};    // metaKey → tijd van de laatste zoekpoging
  var lastSig = '';
  var RETRY = 6 * 3600000;
  AS.version = 0;

  // MARK: build 130: achtergrondwerk voor de andere servers (= AllServersLogic.kt op Android)
  // Na het openen eerst de actieve server; de andere stil op de achtergrond, één tegelijk, alleen opnieuw inlezen als hun
  // cache, de filters of de verwijzingen veranderd zijn, en een server zonder cache hooguit eens per 6 uur ophalen.
  AS.FETCH_EVERY = 6 * 3600000;
  AS.FETCH_TIMEOUT = 90000;
  AS.START_GRACE = 10000;
  var startedAt = Date.now();
  /** Mag een catalogus weer van het netwerk? Hooguit eens per `every` sinds `last` (0/null = nooit; klok terug = mag). */
  AS.mayFetch = function (last, now, every) {
    every = every == null ? AS.FETCH_EVERY : every;
    return !last || last <= 0 || last > now || now - last >= every;
  };
  /** Mag de achtergrond werken? Niet tijdens afspelen, niet zolang de actieve server laadt, niet vlak na het openen. */
  AS.idleOK = function (playing, loading, sinceStart) { return !playing && !loading && sinceStart >= AS.START_GRACE; };
  /** "Alles vernieuwen": de actieve eerst, dan de rest in lijstvolgorde, elke server één keer. */
  AS.refreshOrder = function (ids, active) {
    var out = [], seen = {};
    ids.forEach(function (id) { if (AS.sameServer(id, active) && !seen[lower(id)]) { seen[lower(id)] = true; out.push(id); } });
    ids.forEach(function (id) { if (!seen[lower(id)]) { seen[lower(id)] = true; out.push(id); } });
    return out;
  };
  /** Handtekening van één server-scan: zelfde verwijzingen (volgorde maakt niet uit), cache en filters = zelfde uitkomst. */
  AS.scanSig = function (refs, stamp, filters) { return refs.slice().sort().join('\u0001') + '\u0002' + stamp + '\u0002' + filters; };
  function fetchKey(id) { return 'allservers.fetch.' + lower(id); }
  /** Met een time-out: na `ms` null (de belofte zelf loopt stil door). */
  AS.withTimeout = function (p, ms) {
    return new Promise(function (resolve) {
      var done = false;
      var t = setTimeout(function () { if (!done) { done = true; resolve(null); } }, ms);
      p.then(function (v) { if (!done) { done = true; clearTimeout(t); resolve(v); } },
        function () { if (!done) { done = true; clearTimeout(t); resolve(null); } });
    });
  };
  var scans = {};   // server-id (kleine letters) → {sig, at, found, absent, epg, needs}
  var smartDone = null;
  /** Na "alles vernieuwen": alles opnieuw proberen (ook items die niet gevonden werden). */
  AS.refreshed = function () { tried = {}; smartTried = {}; scans = {}; smartDone = null; return AS.refresh(); };

  function activeId() { return Lib.source ? Lib.source.id : null; }
  AS.isActive = function (sid) { return AS.sameServer(sid, activeId()); };
  function server(sid) { return sid ? D.sourceById(sid) : null; }
  function order() {
    var a = activeId();
    return D.sources.slice().sort(function (x, y) { return (AS.sameServer(x.id, a) ? 0 : 1) - (AS.sameServer(y.id, a) ? 0 : 1); });
  }
  AS.isOrphan = function (key) {
    var sid = AS.serverOf(key);
    if (!sid || AS.isActive(sid)) return false;
    return !server(sid) || !!absent[AS.metaKey(key)];
  };
  AS.item = function (key) { return found[AS.metaKey(key)] || null; };
  /** Gids van een zender van een andere server. */
  AS.programs = function (ch) { return (ch && ch._src && epgs[AS.metaKey(ch._src + '|' + ch.id)]) || []; };

  /** Tegel voor een item van een andere server: het echte item, anders uit de meta ("Onbekende titel"). */
  AS.tile = function (key, wantType) {
    var f = found[AS.metaKey(key)];
    if (f) return (!wantType || f.type === wantType || (wantType === 'vod' && f.type !== 'channel')) ? f : null;
    if (!AS.isOrphan(key)) return null;
    var m = AS.meta(key), sid = AS.serverOf(key), id = AS.itemOf(key);
    var k = m ? m.k : null;
    var type = k === 'channel' ? 'channel' : (k === 'series' || k === 'episode') ? 'series' : 'movie';
    if (wantType === 'channel' && type !== 'channel') { if (k) return null; type = 'channel'; }
    if (wantType && wantType !== 'channel' && wantType !== 'vod' && type !== wantType) return null;
    if (wantType === 'vod' && type === 'channel') return null;
    var title = (m && m.t) || L('Unknown title');
    var item = type === 'channel' ? { id: id, name: title, logo: (m && m.img) || null, group: '', _src: sid, _orphan: true }
      : { id: id, title: title, poster: (m && m.img) || null, group: '', year: m && m.y, _src: sid, _orphan: true };
    return { type: type, item: item };
  };

  /** Rij Mijn lijst over alle servers: entries [{type, item}], laatst toegevoegd eerst, dubbele één keer (actieve gaat voor). */
  AS.myList = function () {
    var keys = D.setList('myList');
    var a = activeId(), seen = {}, picked = {};
    keys.slice().sort(function (x, y) { return (AS.sameServer(AS.serverOf(x), a) ? 0 : 1) - (AS.sameServer(AS.serverOf(y), a) ? 0 : 1); }).forEach(function (k) {
      var hit = AS.sameServer(AS.serverOf(k), a) ? Lib.lib.byId[AS.itemOf(k)] : AS.tile(k, 'vod');
      if (!hit || hit.type === 'channel') return;
      var d = AS.dedupKey(hit.type, hit.item.title, hit.item.year, null);
      if (seen[d]) return;
      seen[d] = true; picked[k] = hit;
    });
    return keys.map(function (k) { return picked[k]; }).filter(Boolean);
  };
  /** Zenders (favorieten/recent) over alle servers. */
  AS.channels = function (keys) {
    var a = activeId(), seen = {}, picked = {};
    keys.slice().sort(function (x, y) { return (AS.sameServer(AS.serverOf(x), a) ? 0 : 1) - (AS.sameServer(AS.serverOf(y), a) ? 0 : 1); }).forEach(function (k) {
      var hit = AS.sameServer(AS.serverOf(k), a) ? Lib.lib.byId[AS.itemOf(k)] : AS.tile(k, 'channel');
      if (!hit || hit.type !== 'channel') return;
      var d = AS.normChannel(hit.item.name) || k;
      if (seen[d]) return;
      seen[d] = true; picked[k] = hit.item;
    });
    return keys.map(function (k) { return picked[k]; }).filter(Boolean);
  };
  /** Verder kijken over alle servers: [{key, e, type, item}] (nieuwste eerst; zelfde werk op twee servers = één). */
  AS.continueList = function () {
    var lib = Lib.lib, a = activeId(), out = [], seen = {};
    var byTitle = Lib.memo('seriesByTitle', function () { var m = {}; lib.series.forEach(function (s) { if (!m[s.title]) m[s.title] = s; }); return m; });
    D.progressList().forEach(function (pe) {
      var pid = pe.e.playlistID;
      var other = pid && !AS.sameServer(pid, a);
      var hit = null;
      if (!other) {
        hit = lib.byId[pe.key];
        if (!hit || hit.type !== 'movie') { var s = byTitle[pe.e.title]; hit = s ? { type: 'series', item: s } : null; }
      } else {
        var full = pid + '|' + pe.key;
        hit = AS.item(full);
        if (!hit) {
          var m = AS.meta(full);
          var t = m && m.k === 'movie' ? 'movie' : 'series';
          hit = { type: t, item: { id: t === 'movie' ? pe.key : (m && m.ep && m.ep.series ? AS.itemOf(m.ep.series) : pe.key), title: pe.e.title || (m && m.t) || L('Unknown title'),
            poster: pe.e.artwork || (m && m.img) || null, group: '', _src: pid, _orphan: AS.isOrphan(full) } };
        }
      }
      if (!hit) return;
      var d = (hit.type === 'movie' ? 'm:' : 's:') + AS.normTitle(hit.item.title || pe.e.title);
      if (B.has(seen, d) && !AS.sameServer(seen[d], pid || a)) return;
      if (hit.type === 'series' && out.some(function (x) { return x.type === 'series' && x.item.id === hit.item.id && AS.sameServer(x.item._src || a, hit.item._src || a); })) return;
      seen[d] = pid || a;
      out.push({ key: pe.key, e: pe.e, type: hit.type, item: hit.item, src: other ? pid : null });
    });
    return out.slice(0, 20);
  };

  // MARK: achtergrond: andere servers, meta aanvullen, verhuizen

  function refs() {
    var out = [];
    D.setList('myList').forEach(function (k) { out.push({ key: k, field: 'mylist', kind: null }); });
    D.setList('favorites').forEach(function (k) { out.push({ key: k, field: 'favorites', kind: 'channel' }); });
    D.setList('recentChannels').forEach(function (k) { out.push({ key: k, field: 'recent', kind: 'channel' }); });
    D.progressList().forEach(function (pe) {
      var pid = pe.e.playlistID || activeId();
      if (pid) out.push({ key: pid + '|' + pe.key, field: 'continue', kind: null, title: pe.e.title, img: pe.e.artwork });
    });
    D.history().forEach(function (e) {
      if (!e.playlistID) return;
      out.push({ key: e.playlistID + '|' + e.itemID, field: 'history', kind: e.type === 'live' ? 'channel' : e.type === 'episode' ? 'episode' : e.type === 'movie' ? 'movie' : null,
        title: e.title, img: e.artwork, se: AS.seasonEpisode(e.subtitle) });
    });
    var pid = B.app && B.app.profile ? B.app.profile.id : null;
    (pid ? D.collectionsFor(pid) : []).forEach(function (c) {
      if (c.kind === 'smart') return;
      (c.items || []).forEach(function (r) { var sid = r.sid || c.sourceID || activeId(); if (sid) out.push({ key: sid + '|' + r.id, field: 'collection', kind: r.type, coll: c.id }); });
    });
    return out;
  }

  function recordFrom(key, sid, sname, hit, r) {
    if (hit) {
      var x = hit.item;
      if (hit.type === 'channel') AS.record(key, { t: x.name, k: 'channel', img: x.logo, sid: sid, sname: sname, ch: { name: x.name, epg: x.epgID || null } });
      else AS.record(key, { t: x.rawTitle || x.title, k: hit.type, y: x.year || AS.yearIn(x.title), img: x.poster, sid: sid, sname: sname });
      return;
    }
    if (r && (r.field === 'continue' || r.field === 'history') && r.title && !AS.meta(key)) {
      var kind = r.kind || (r.se || r.field === 'continue' ? 'episode' : 'movie');
      if (kind === 'channel') return;
      AS.record(key, { t: r.title, k: kind, y: kind === 'movie' ? AS.yearIn(r.title) : null, img: r.img, sid: sid, sname: sname,
        ep: r.se ? { series: null, st: r.title, s: r.se[0], e: r.se[1] } : null });
    }
  }

  /** Ruwe catalogus van een server: de actieve uit het geheugen, anders IndexedDB, anders (mag) van het netwerk. */
  function catalog(src, network) {
    if (AS.isActive(src.id) && Lib.raw && (Lib.raw.movies.length || Lib.raw.channels.length || Lib.raw.series.length)) return Promise.resolve(Lib.raw);
    return B.idb.get('lib:' + src.id).then(function (c) {
      if (c && c.lib) { if (Lib.stamps) Lib.stamps[src.id] = c.at || 0; return c.lib; }
      if (!network) return null;
      // build 130: op de achtergrond (network === 'bg') hooguit eens per 6 uur per server (ook na een herstart); altijd
      // met een time-out. Aangeklikt door de gebruiker = meteen proberen.
      if (network === 'bg' && !AS.mayFetch(B.store.get(fetchKey(src.id), 0), Date.now())) return null;
      B.store.set(fetchKey(src.id), Date.now());
      return AS.withTimeout(Lib.fetchOther(src), AS.FETCH_TIMEOUT);
    });
  }
  AS.markFetched = function (id) { B.store.set(fetchKey(id), Date.now()); };
  /** Tijd van de cache van een andere server (bekend nadat hij één keer gelezen of geschreven is), anders null. */
  function stampOf(src) {
    if (AS.isActive(src.id)) return 'active:' + (Lib.version || 0);
    return Lib.stamps && B.has(Lib.stamps, src.id) ? Lib.stamps[src.id] : null;
  }
  function idle() {
    return new Promise(function (resolve) {
      (function wait() {
        var playing = B.player.current || (B.multiview && B.multiview.current);
        if (!AS.idleOK(!!playing, !!(Lib.loading || Lib.refreshingAll && Lib.refreshingAll.active), Date.now() - startedAt)) setTimeout(wait, 3000);
        else resolve();
      })();
    });
  }
  function findIn(raw, filter, id, kind) {
    function look(list, f, type) {
      for (var i = 0; i < list.length; i++) if (list[i].id === id) { var o = filter[f](list[i]); return o ? { type: type, item: o } : null; }
      return null;
    }
    if (kind === 'channel') return look(raw.channels || [], 'channel', 'channel');
    if (kind === 'movie') return look(raw.movies || [], 'movie', 'movie');
    if (kind === 'series') return look(raw.series || [], 'series', 'series');
    return look(raw.movies || [], 'movie', 'movie') || look(raw.series || [], 'series', 'series') || look(raw.channels || [], 'channel', 'channel');
  }

  // MARK: build 129: slimme collecties over alle servers

  var smart = {};        // collectie-id (kleine letters) → {sig, items: [{type, item (met _src)}]}
  var smartTried = {};   // server-id → tijd van de laatste poging om een catalogus zonder cache op te halen
  var SMART_MAX = 60;
  function smartSig(c) { return [c.query, (c.types || []).join(','), c.group || '', c.sourceID || '', c.updatedAt || ''].join('|'); }
  function smartWords(c) { return String(c.query || '').split(',').map(function (w) { return B.fold(w).trim(); }).filter(Boolean); }
  /** Treffers van een slimme collectie op de andere servers (leeg tot ze op de achtergrond doorzocht zijn). */
  AS.smartFor = function (c) {
    var x = c && smart[lower(c.id)];
    return x && x.sig === smartSig(c) ? x.items : [];
  };
  /** Slimme collecties van het actieve profiel op de andere servers doorzoeken: één catalogus tegelijk, niet tijdens het afspelen. */
  function smartRound() {
    var pid = B.app && B.app.profile ? B.app.profile.id : null;
    var cols = (pid ? D.collectionsFor(pid) : []).filter(function (c) { return c.kind === 'smart' && smartWords(c).length; });
    var out = {};
    cols.forEach(function (c) { out[lower(c.id)] = { sig: smartSig(c), items: [] }; });
    // build 130: zelfde collecties, servers, caches en filters als de vorige keer → niets te doen
    var others = order().filter(function (src) { return !AS.isActive(src.id); });
    var roundSig = cols.map(function (c) { return c.id + '=' + smartSig(c); }).join(';') + '#' +
      others.map(function (src) { return src.id + '@' + stampOf(src) + '@' + Lib.filterSig(src); }).join(';');
    var known = others.every(function (src) { return stampOf(src) != null; });
    if (cols.length && known && roundSig === smartDone) return Promise.resolve();
    var complete = true;
    var chain = cols.length ? idle() : Promise.resolve();
    if (cols.length) order().forEach(function (src) {
      if (AS.isActive(src.id)) return;
      var mine = cols.filter(function (c) { return !c.sourceID || AS.sameServer(c.sourceID, src.id); });
      if (!mine.length) return;
      chain = chain.then(function () {
        return catalog(src, false).then(function (raw) {
          if (raw) return raw;
          // nog geen cache van die server: hooguit eens per 6 uur stil ophalen
          var k = lower(src.id);
          if (Date.now() - (smartTried[k] || 0) < RETRY) return null;
          smartTried[k] = Date.now();
          return idle().then(function () { return catalog(src, 'bg'); });
        }).then(function (raw) {
          if (!raw) { complete = false; return; }
          var filter = Lib.filterOne(src);
          var spec = mine.map(function (c) {
            var types = Array.isArray(c.types) && c.types.length ? c.types : ['channel', 'movie', 'series'];
            return { c: c, words: smartWords(c), group: c.group || null, types: types, list: out[lower(c.id)].items };
          });
          [['channel', raw.channels || [], 'name'], ['movie', raw.movies || [], 'title'], ['series', raw.series || [], 'title']].forEach(function (pool) {
            var want = spec.filter(function (x) { return x.types.indexOf(pool[0]) >= 0; });
            if (!want.length) return;
            for (var i = 0; i < pool[1].length; i++) {
              var it = pool[1][i], f = null;
              for (var w = 0; w < want.length; w++) {
                var sp = want[w];
                if (sp.list.length >= SMART_MAX) continue;
                if (f == null) f = B.fold(it[pool[2]] || '');
                if (!AS.smartHit(f, it.group, sp.words, sp.group)) continue;
                var fx = filter[pool[0]](it);   // kinderslot, verborgen of vergrendeld op die server
                if (!fx) continue;
                fx._src = src.id;
                sp.list.push({ type: pool[0], item: fx });
              }
            }
          });
        });
      });
    });
    return chain.then(function () {
      if (complete && cols.length) {
        smartDone = cols.map(function (c) { return c.id + '=' + smartSig(c); }).join(';') + '#' +
          others.map(function (src) { return src.id + '@' + stampOf(src) + '@' + Lib.filterSig(src); }).join(';');
      }
      var sig = JSON.stringify(Object.keys(out).sort().map(function (k) { return [k, out[k].sig, out[k].items.map(function (e) { return e.type + ':' + e.item._src + '|' + e.item.id; })]; }));
      var before = JSON.stringify(Object.keys(smart).sort().map(function (k) { return [k, smart[k].sig, smart[k].items.map(function (e) { return e.type + ':' + e.item._src + '|' + e.item.id; })]; }));
      smart = out;
      if (sig !== before) { AS.version++; B.emit('allservers'); }
    });
  }
  AS._smartRound = smartRound;

  var running = null, again = false;
  /** Eén ronde (rustig, achter elkaar). */
  AS.refresh = function () {
    if (running) { again = true; return running; }
    running = AS.load().then(round).then(smartRound).catch(function (e) { B.log('allservers', e); }).then(function () {
      running = null;
      if (again) { again = false; setTimeout(AS.refresh, 3000); }
    });
    return running;
  };
  function refSig(r) { return [r.key, r.field, r.kind || '', r.title || ''].join('\u0003'); }
  function round() {
    // build 130: eerst de actieve server (snel klaar na het openen), daarna pas de andere
    return idle().then(roundNow);
  }
  function roundNow() {
    var list = refs();
    var bySrc = {};
    list.forEach(function (r) { var s = lower(AS.serverOf(r.key)); (bySrc[s] = bySrc[s] || []).push(r); });
    var newFound = {}, newAbsent = {}, newEpg = {}, needs = [];
    var keep = {};
    D.sources.forEach(function (src) { if (bySrc[lower(src.id)]) keep[lower(src.id)] = true; });
    Object.keys(scans).forEach(function (k) { if (!keep[k]) delete scans[k]; });
    var chain = Promise.resolve();
    D.sources.forEach(function (src) {
      var mine = bySrc[lower(src.id)];
      if (!mine) return;
      chain = chain.then(function () {
        // build 130: niets veranderd voor deze server (cache, filters, verwijzingen) → vorige uitkomst, niets inlezen
        var pk = lower(src.id), st = stampOf(src);
        var sig = st == null ? null : AS.scanSig(mine.map(refSig), st, Lib.filterSig(src));
        var prev = scans[pk];
        if (sig && prev && prev.sig === sig) {
          B.assign(newFound, prev.found); B.assign(newAbsent, prev.absent); B.assign(newEpg, prev.epg); needs = needs.concat(prev.needs);
          return;
        }
        var sFound = {}, sAbsent = {}, sEpg = {}, sNeeds = [];
        function save() {
          var st2 = stampOf(src);
          if (st2 != null) scans[pk] = { sig: AS.scanSig(mine.map(refSig), st2, Lib.filterSig(src)), found: sFound, absent: sAbsent, epg: sEpg, needs: sNeeds };
          B.assign(newFound, sFound); B.assign(newAbsent, sAbsent); B.assign(newEpg, sEpg); needs = needs.concat(sNeeds);
        }
        return catalog(src, false).then(function (raw) { return raw || idle().then(function () { return catalog(src, 'bg'); }); }).then(function (raw) {
          if (!raw) return;
          var filter = Lib.filterOne(src);
          var titles = null;
          mine.forEach(function (r) {
            var id = AS.itemOf(r.key), mk = AS.metaKey(r.key);
            var hit = findIn(raw, filter, id, r.kind === 'episode' ? 'movie' : r.kind);
            recordFrom(r.key, src.id, src.name, hit, r);
            if (hit) { if (!AS.isActive(src.id)) { hit.item._src = src.id; sFound[mk] = hit; } return; }
            if (r.field === 'continue' && r.title) {
              // aflevering: de serie (op titel) bestaat nog → niet "weg"
              if (!titles) { titles = {}; (raw.series || []).forEach(function (s) { var n = AS.normTitle(s.title); if (!titles[n]) titles[n] = s; }); }
              var s = titles[AS.normTitle(r.title)];
              var fs = s ? filter.series(s) : null;
              if (fs) { if (!AS.isActive(src.id)) { fs._src = src.id; sFound[mk] = { type: 'series', item: fs }; } return; }
            }
            if ((r.field === 'continue' || r.field === 'history') && !wanted(r)) return;
            sAbsent[mk] = true; sNeeds.push(r);
          });
          // gids van de gevonden zenders van deze server (alleen die zenders), voor gids en terugkijken
          if (AS.isActive(src.id)) return save();
          var chans = [];
          mine.forEach(function (r) { var f = sFound[AS.metaKey(r.key)]; if (f && f.type === 'channel' && chans.indexOf(f.item) < 0) chans.push(f.item); });
          if (!chans.length) return save();
          return idle().then(function () { return AS.withTimeout(Lib.epgOther(src, raw, chans), AS.FETCH_TIMEOUT); }).then(function (byId) {
            Object.keys(byId || {}).forEach(function (id) { sEpg[AS.metaKey(src.id + '|' + id)] = byId[id]; });
            save();
          });
        });
      });
    });
    return chain.then(function () {
      Object.keys(bySrc).forEach(function (s) { if (s && !server(s)) needs = needs.concat(bySrc[s]); });
      // alleen opnieuw tekenen als er echt iets anders is (anders tekent elke ronde de rijen opnieuw)
      var sig = Object.keys(newFound).sort().join(',') + '#' + Object.keys(newAbsent).sort().join(',') + '#' + Object.keys(newEpg).length;
      found = newFound; absent = newAbsent; epgs = newEpg;
      if (sig !== lastSig) { lastSig = sig; AS.version++; B.emit('allservers'); }
      var now = Date.now(), seen = {};
      var todo = needs.filter(function (r) {
        var mk = AS.metaKey(r.key);
        if (seen[mk] || now - (tried[mk] || 0) < RETRY) return false;
        seen[mk] = true; return true;
      });
      if (!todo.length || !D.sources.length) return;
      return idle().then(function () { return match(todo, 'bg'); }).then(function (moved) {
        todo.forEach(function (r) { tried[AS.metaKey(r.key)] = now; });
        if (Object.keys(moved).length) D.remapNow();
      });
    });
  }

  function wanted(r) {
    var m = AS.meta(r.key);
    if (m && m.t && m.k) return { kind: m.k, title: m.t, year: m.y, tmdb: m.tmdb, epg: m.ch && m.ch.epg, ep: m.ep };
    if (!r.title) return null;
    if (r.field === 'continue' || r.field === 'history') {
      var k = r.kind || (r.se ? 'episode' : 'movie');
      if (k === 'episode' && !r.se) return null;
      return { kind: k, title: r.title, year: AS.yearIn(r.title), ep: r.se ? { st: r.title, s: r.se[0], e: r.se[1] } : null };
    }
    return null;
  }

  /** Items zoeken op de servers (actieve eerst, de oude niet). Gevonden → remap + meta. Geeft {oud: nieuw}. */
  function match(list, network) {
    var pending = list.map(function (r) { return { r: r, w: wanted(r) }; }).filter(function (x) { return x.w; });
    var moved = {};
    if (!pending.length) return Promise.resolve(moved);
    var chain = Promise.resolve();
    order().forEach(function (src) {
      chain = chain.then(function () {
        var mine = pending.filter(function (x) { return !moved[x.r.key] && !AS.sameServer(AS.serverOf(x.r.key), src.id); });
        if (!mine.length) return;
        return catalog(src, network).then(function (raw) {
          if (!raw) return;
          var filter = Lib.filterOne(src);
          // per gezochte genormaliseerde titel/zender de kandidaten (zonder de lijsten te kopiëren)
          var byNorm = {};
          mine.forEach(function (x) { var n = x.w.kind === 'channel' ? 'c:' + AS.normChannel(x.w.title) : 't:' + AS.normTitle(x.w.ep && x.w.ep.st ? x.w.ep.st : x.w.title); byNorm[n] = []; });
          var epgs = {}; mine.forEach(function (x) { if (x.w.epg) epgs[lower(x.w.epg)] = []; });
          function add(type, it, title) {
            var n = (type === 'channel' ? 'c:' + AS.normChannel(title) : 't:' + AS.normTitle(title));
            if (byNorm[n]) byNorm[n].push({ type: type, item: it });
            if (type === 'channel' && it.epgID && epgs[lower(it.epgID)]) epgs[lower(it.epgID)].push({ type: type, item: it });
          }
          (raw.movies || []).forEach(function (m) { add('movie', m, m.title); });
          (raw.series || []).forEach(function (s) { add('series', s, s.title); });
          (raw.channels || []).forEach(function (c) { add('channel', c, c.name); });
          var sub = Promise.resolve();
          mine.forEach(function (x) {
            sub = sub.then(function () {
              var w = x.w;
              var n = w.kind === 'channel' ? 'c:' + AS.normChannel(w.title) : 't:' + AS.normTitle(w.ep && w.ep.st ? w.ep.st : w.title);
              var pool = (byNorm[n] || []).concat(w.epg ? (epgs[lower(w.epg)] || []) : []);
              var cands = [];
              pool.forEach(function (p) {
                var f = filter[p.type](p.item);
                if (!f) return;   // kinderslot, verborgen of vergrendeld op die server
                cands.push({ sid: src.id, id: p.item.id, kind: p.type, title: p.type === 'channel' ? p.item.name : p.item.title, year: p.item.year, epg: p.item.epgID, item: p.item });
              });
              var ww = w.kind === 'episode' ? { kind: 'series', title: w.ep && w.ep.st ? w.ep.st : w.title, year: null } : w;
              var hit = AS.best(ww, cands, [src.id]);
              if (!hit) return;
              if (w.kind === 'episode') {
                var ep = w.ep;
                if (!ep || ep.s == null || ep.e == null) return;
                var s = B.assign({}, hit.item, { _src: src.id });
                return Lib.episodes(s).then(function (r) {
                  var e = (r.episodes || []).filter(function (x2) { return x2.season === ep.s && x2.number === ep.e; })[0];
                  if (!e) return;
                  var nk = src.id + '|' + e.id;
                  AS.record(nk, { t: s.title, k: 'episode', y: s.year, img: e.still || s.poster, sid: src.id, sname: src.name,
                    ep: { series: src.id + '|' + s.id, st: s.title, s: ep.s, e: ep.e } });
                  if (AS.addRemap(x.r.key, nk)) moved[x.r.key] = nk;
                }, B.noop);
              }
              var nk = src.id + '|' + hit.id;
              recordFrom(nk, src.id, src.name, { type: hit.kind, item: hit.item }, null);
              if (AS.addRemap(x.r.key, nk)) moved[x.r.key] = nk;
            });
          });
          return sub;
        });
      });
    });
    return chain.then(function () { return moved; });
  }

  // MARK: aanklikken

  function fieldOf(key) {
    var sid = AS.serverOf(key), id = AS.itemOf(key);
    if (D.inSet('myList', key)) return 'mylist';
    if (D.inSet('favorites', key)) return 'favorites';
    var pid = B.app && B.app.profile ? B.app.profile.id : null;
    if ((pid ? D.collectionsFor(pid) : []).some(function (c) { return c.kind !== 'smart' && (c.items || []).some(function (r) { return r.id === id && AS.sameServer(r.sid || c.sourceID, sid); }); })) return 'collection';
    var p = D.progress(id);
    if (p && AS.sameServer(p.playlistID, sid)) return 'continue';
    if (D.inSet('recentChannels', key)) return 'recent';
    return null;
  }

  /** Item openen na een verhuizing (zonder van server te wisselen). */
  function openKey(key, play) {
    var sid = AS.serverOf(key), id = AS.itemOf(key), src = server(sid);
    if (!src) return;
    catalog(src, true).then(function (raw) {
      if (!raw) return;
      var hit = findIn(raw, Lib.filterOne(src), id, null);
      if (!hit) return;
      if (!AS.isActive(sid)) hit.item._src = src.id;
      if (hit.type === 'channel') return B.player.playChannel(hit.item, [hit.item]);
      if (play && hit.type === 'movie') return B.player.playVOD(B.player.movieItem(hit.item));
      B.ui.push(B.screens.detail(hit.type, hit.item));
    });
  }
  AS.openKey = openKey;

  var busy = false;
  /** Niet (meer) beschikbaar: nu zoeken; gevonden → verhuizen en then(nieuweSleutel), anders de melding. */
  AS.rescue = function (key, kind, title, then) {
    var t = AS.target(key);
    if (t && server(AS.serverOf(t))) { then(t); return; }
    if (busy) return;
    busy = true;
    var field = fieldOf(key);
    var toast = B.ui.toast ? B.ui.toast(L('Looking for this title on your other servers…')) : null;
    match([{ key: key, field: field || 'mylist', kind: kind, title: title }], true).then(function (moved) {
      busy = false;
      if (moved[key]) { D.remapNow(); then(moved[key]); return; }
      tried[AS.metaKey(key)] = Date.now();
      notice(key, field, title);
    }, function () { busy = false; notice(key, field, title); });
    return toast;
  };

  function notice(key, field, title) {
    var sid = AS.serverOf(key) || '', id = AS.itemOf(key), src = server(sid);
    var m = AS.meta(key);
    var name = src ? src.name : ((m && m.sname) || nameFromMeta(sid) || L('a removed server'));
    var others = D.sources.filter(function (s) { return !AS.sameServer(s.id, sid); }).map(function (s) { return s.name; }).join(', ');
    var msg = !src && others ? L('“%@” was on “%@”, but that server has been removed. This title wasn\'t found on your other servers (%@).', title, name, others)
      : !src ? L('“%@” was on “%@”, but that server has been removed, and you have no other server to search.', title, name)
      : others ? L('“%@” is no longer on “%@” and wasn\'t found on %@ either.', title, name, others)
      : L('“%@” is no longer on “%@”, and you have no other server to search.', title, name);
    var buttons = [];
    var remove = {
      mylist: [L('Remove from My List'), function () { D.toggleSet('myList', key, false); }],
      favorites: [L('Remove from favorites'), function () { D.toggleSet('favorites', key, false); }],
      'continue': [L('Remove from Continue watching'), function () { D.clearProgress(id); }],
      recent: [L('Remove'), function () { D.toggleSet('recentChannels', key, false); }],
      collection: [L('Remove from collection'), function () {
        var pid = B.app && B.app.profile ? B.app.profile.id : null;
        (pid ? D.collectionsFor(pid) : []).forEach(function (c) {
          if (c.kind === 'smart') return;
          var items = (c.items || []).filter(function (r) { return !(r.id === id && AS.sameServer(r.sid || c.sourceID, sid)); });
          if (items.length !== (c.items || []).length && B.parity) B.parity.saveCollection(B.assign({}, c, { items: items }));
        });
      }]
    }[field];
    if (remove) buttons.push({ label: remove[0], fn: function () { remove[1](); B.emit('profileData'); } });
    buttons.push({ label: L('Search'), fn: function () { if (B.screens.searchFor) B.screens.searchFor(B.parseTitle(title).title || title); } });
    buttons.push({ label: L('Cancel') });
    B.ui.modal({ title: title, text: msg, buttons: buttons });
  }
  function nameFromMeta(sid) {
    var k = Object.keys(CM.items).filter(function (x) { return AS.sameServer(CM.items[x].sid, sid) && CM.items[x].sname; })[0];
    return k ? CM.items[k].sname : null;
  }

  /**
   * Kaart van een andere server aangeklikt. true = afgehandeld (zoeken/melding of spelen op die server).
   * entry = {type, item}; item._src = server-id.
   */
  AS.open = function (entry) {
    var x = entry.item, sid = x._src;
    if (!sid || AS.isActive(sid)) return false;
    var key = sid + '|' + x.id;
    if (!AS.isOrphan(key)) {
      if (entry.type === 'channel') { D.pushRecentChannel(sid, x.id); B.player.playChannel(x, [x]); return true; }
      return false;   // gewone detailpagina, met item._src
    }
    var title = (AS.meta(key) && AS.meta(key).t) || x.title || x.name;
    AS.rescue(key, entry.type, title, function (nk) { openKey(nk, entry.type === 'channel'); });
    return true;
  };

  /** Verder kijken van een andere (of verwijderde) server: op hetzelfde punt verder. */
  AS.resume = function (c) {
    var full = c.src + '|' + c.key;
    if (AS.isOrphan(full)) {
      AS.rescue(full, (AS.meta(full) && AS.meta(full).k) || c.type, c.e.title, function (nk) { AS.resume({ src: AS.serverOf(nk), key: AS.itemOf(nk), e: c.e, type: c.type }); });
      return;
    }
    var src = server(c.src);
    if (!src) return;
    catalog(src, true).then(function (raw) {
      if (!raw) return;
      var filter = Lib.filterOne(src);
      var hit = findIn(raw, filter, c.key, 'movie');
      if (hit) { hit.item._src = src.id; return B.player.playVOD(B.player.movieItem(hit.item)); }
      var n = AS.normTitle(c.e.title);
      var s = (raw.series || []).filter(function (x) { return AS.normTitle(x.title) === n || (x.episodes || []).some(function (e) { return e.id === c.key; }); })[0];
      var fs = s ? filter.series(s) : null;
      if (!fs) return;
      fs._src = src.id;
      return Lib.episodes(fs).then(function (r) {
        var e = (r.episodes || []).filter(function (x) { return x.id === c.key; })[0];
        if (e) B.player.playVOD(B.player.episodeItem(e, fs));
        else B.ui.push(B.screens.detail('series', fs));
      });
    });
  };

  /** Bij afspelen: meta van dit item (met serie + seizoen/aflevering). */
  AS.recordPlayed = function (item) {
    var sid = item.src || activeId(), src = server(sid);
    if (!src) return;
    if (item.type === 'live' && item.channel) {
      AS.record(sid + '|' + item.channel.id, { t: item.channel.name, k: 'channel', img: item.channel.logo, sid: src.id, sname: src.name, ch: { name: item.channel.name, epg: item.channel.epgID || null } });
    } else if (item.type === 'movie') {
      AS.record(sid + '|' + item.id, { t: item.title, k: 'movie', y: item.year || null, img: item.artwork, sid: src.id, sname: src.name });
    } else if (item.type === 'episode') {
      AS.record(sid + '|' + item.id, { t: item.title, k: 'episode', img: item.artwork, sid: src.id, sname: src.name,
        ep: item.season != null && item.episode != null ? { series: item.seriesID ? sid + '|' + item.seriesID : null, st: item.title, s: item.season, e: item.episode } : null });
      if (item.seriesID && !AS.meta(sid + '|' + item.seriesID)) AS.record(sid + '|' + item.seriesID, { t: item.title, k: 'series', y: item.year || null, img: item.artwork, sid: src.id, sname: src.name });
    }
  };

  // rustig verversen bij wijzigingen (niet bij elke toets)
  var refreshTimer = null;
  function later() { clearTimeout(refreshTimer); refreshTimer = setTimeout(function () { B.whenQuiet ? B.whenQuiet(AS.refresh, 1500) : AS.refresh(); }, 2500); }
  ['profileData', 'sources', 'library'].forEach(function (ev) { B.on(ev, later); });
  B.on('wipe', function () { AS.wipe(); });
})();
