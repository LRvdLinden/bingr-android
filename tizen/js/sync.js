/*
 * Gegevens van het account: profielen (account_data), servers (get_sources), instellingen (settings),
 * per profiel Mijn lijst, favorieten, voortgang, bekeken, recente zenders en geschiedenis (profile.<id>),
 * collecties (version 2, per profiel) en pincodes. Zelfde formaat en samenvoegregels als StateSync.kt:
 * per item wint de nieuwste tijd, verwijderingen komen door via `removed`, onbekende velden blijven staan.
 * Alles wordt lokaal bewaard (localStorage), zodat de app na een update of zonder internet direct werkt.
 */
(function () {
  'use strict';
  var B = window.B;
  var A = B.account;
  var D = B.data = {};

  var SET_FIELDS = ['myList', 'favorites', 'watched', 'recentChannels'];
  var REMOVED_DAYS = 90, MAX_RECENT = 20, MAX_PROGRESS = 300, MAX_HISTORY = 500;
  var lower = function (s) { return String(s || '').toLowerCase(); };

  // MARK: lokale kopieën

  D.profiles = B.store.get('profiles', []) || [];        // alleen actieve (geen tombstones), op volgorde
  D.sources = B.store.get('sources', []) || [];          // alleen actieve bronnen
  D.mainSourceID = B.store.get('mainSourceID', null);
  D.settings = B.store.get('settings', null);            // {data, updatedAt} zoals de server het gaf
  D.collections = B.store.get('collections', null);      // ruwe section-data
  D.pins = B.store.get('pins', {}) || {};                // kleine letters profiel-id → pincode

  function save() {
    B.store.set('profiles', D.profiles);
    B.store.set('sources', D.sources);
    B.store.set('mainSourceID', D.mainSourceID);
    B.store.set('settings', D.settings);
    B.store.set('collections', D.collections);
    B.store.set('pins', D.pins);
  }

  /** Ander account op deze tv: eerst alles van het vorige account wissen (zoals SourceSync.checkAccountSwitch). */
  D.checkAccountSwitch = function () {
    var uid = A.userId();
    if (!uid) return;
    var last = B.store.get('owner', null);
    if (last && lower(last) !== lower(uid)) D.wipe();
    B.store.set('owner', uid);
  };
  D.wipe = function () {
    D.profiles = []; D.sources = []; D.mainSourceID = null; D.settings = null; D.collections = null; D.pins = {};
    save();
    B.store.keys('pstate.').forEach(function (k) { B.store.del(k); });
    B.store.keys('lastChannel.').forEach(function (k) { B.store.del(k); });
    B.store.del('lastProfile'); B.store.del('activeSource');
    B.idb.clear();
    // Trakt, OpenSubtitles en herinneringen van het vorige account
    B.emit('wipe');
  };

  // MARK: instellingen (settings.shared, gelijk op alle platforms)

  D.shared = function (key, def) {
    var s = D.settings && D.settings.data && D.settings.data.shared;
    return s && B.has(s, key) && s[key] != null ? s[key] : def;
  };
  /** Gedeelde instelling wijzigen: nieuwste serverversie lezen, alleen dit veld aanpassen, het geheel uploaden. */
  D.setShared = function (key, value) {
    var local = D.settings ? B.copy(D.settings) : { data: {} };
    local.data.shared = local.data.shared || {};
    local.data.shared[key] = value;
    local.data.updatedAt = B.iso();
    D.settings = local; save();
    B.emit('settings');
    return A.getState(['settings']).then(function (all) {
      var cur = all && all.settings && all.settings.data;
      var data = cur && typeof cur === 'object' ? B.copy(cur) : {};
      data.shared = data.shared || {};
      data.shared[key] = value;
      data.updatedAt = B.iso();
      return A.putState('settings', data).then(function (at) {
        if (at != null) { D.settings = { data: data, updatedAt: at || data.updatedAt }; save(); }
      });
    }).catch(B.noop);
  };
  /** Lokale instellingen van deze tv (niet gesynchroniseerd). */
  D.local = function (key, def) { var v = B.store.get('local.' + key, null); return v == null ? def : v; };
  D.setLocal = function (key, v) { B.store.set('local.' + key, v); };

  // MARK: profielen

  var ACCENTS = { red: '#E50914', blue: '#2F7BF5', green: '#1DB954', purple: '#9B51E0', orange: '#F5821F', pink: '#E6448C' };
  D.accents = ACCENTS;
  D.accentKey = function (p) { var k = p && lower(p.accentColor); return ACCENTS[k] ? k : 'red'; };

  // iOS-symboolnaam → emoji (= AvatarMap.kt)
  var SF = {
    'tv': '📺', 'globe': '🌐', 'sportscourt': '⚽', 'crown': '👑', 'flame': '🔥', 'film': '🎬', 'popcorn': '🍿', 'star': '⭐',
    'gamecontroller': '🎮', 'moon.stars': '🌙', 'bolt': '⚡', 'heart': '❤️', 'waveform.path.ecg': '🎧', 'hare': '🐰',
    'forward': '🚀', 'sparkle': '🌈', 'link': '🔗', 'bubble.left': '💬', 'wifi': '📶', 'umbrella': '☂️', 'play': '▶️',
    'book.closed': '📕', 'person': '🧸', 'paintbrush': '🎭'
  };
  D.palettes = [['#E50914', '#7A0008'], ['#2563EB', '#1E1B4B'], ['#10B981', '#064E3B'], ['#F59E0B', '#9A3412'],
    ['#A855F7', '#3B0764'], ['#EC4899', '#831843'], ['#14B8A6', '#0C4A6E'], ['#64748B', '#0F172A']];
  /** Avatar uit het gedeelde formaat: {kind: 'kid'|'emoji', value, colorIndex}. */
  D.avatar = function (p) {
    var a = p && p.avatar, n = D.palettes.length;
    var color = 0, sym = null, emoji = null;
    if (a && typeof a === 'object') {
      sym = a.symbol || a.figure || null; emoji = a.emoji || null;
      color = B.int(a.colorIndex != null ? a.colorIndex : a.color) || 0;
    } else if (typeof a === 'string') sym = a;
    color = ((color % n) + n) % n;
    if (sym && /^kid\d\d$/.test(sym)) return { kind: 'kid', value: sym, colorIndex: color };
    var e = emoji || (sym && SF[sym]) || sym || (p && p.kids ? null : '📺');
    if (!e) return { kind: 'kid', value: 'kid01', colorIndex: color };
    return { kind: 'emoji', value: e, colorIndex: color };
  };
  D.isMain = function (p) { return !!(p && p.isMain); };
  D.mainProfile = function () {
    for (var i = 0; i < D.profiles.length; i++) if (D.profiles[i].isMain) return D.profiles[i];
    return D.profiles[0] || null;
  };
  D.profileById = function (id) {
    for (var i = 0; i < D.profiles.length; i++) if (lower(D.profiles[i].id) === lower(id)) return D.profiles[i];
    return null;
  };
  /** Leeftijdsgrens die echt geldt (kinderprofiel zonder grens = t/m 12). */
  D.ageLimit = function (p) {
    if (!p) return null;
    var a = B.int(p.ageLimit);
    return a != null ? a : (p.kids ? 12 : null);
  };
  D.strict = function (p) { var a = D.ageLimit(p); return !!(p && p.kids) || (a != null && a <= 12); };
  D.pinFor = function (p) { return p ? (D.pins[lower(p.id)] || null) : null; };

  function applyProfiles(list) {
    var active = (list || []).filter(function (p) { return p && p.id && p.deleted !== true; });
    active.sort(function (a, b) { return (B.int(a.order) || 0) - (B.int(b.order) || 0); });
    D.profiles = active;
    save();
    B.emit('profiles');
  }

  /** Profielen lezen; leeg account → één hoofdprofiel aanmaken en uploaden (zoals de andere apps doen). */
  D.syncProfiles = function () {
    return A.readProfiles().then(function (list) {
      if (list == null) return false;
      var alive = list.filter(function (p) { return p && p.id && p.deleted !== true; });
      if (!alive.length) {
        var email = A.email() || '';
        var name = email ? email.split('@')[0] : 'Bingr';
        name = name.charAt(0).toUpperCase() + name.slice(1);
        var main = { id: B.uuid().toUpperCase(), name: name, avatar: { symbol: 'tv', colorIndex: 0, emoji: '📺' }, kids: false,
          ageLimit: null, isMain: true, order: 0, photo: null, updatedAt: B.iso() };
        list = list.concat([main]);
        A.writeProfiles(list);
      }
      applyProfiles(list);
      return true;
    }, function () { return false; });
  };

  /** Accentkleur van een profiel wijzigen (build 54: per profiel in account_data, met updatedAt = nu). */
  D.setAccent = function (profileId, key) {
    if (!ACCENTS[key]) return Promise.resolve();
    var p = D.profileById(profileId);
    if (p) { p.accentColor = key; p.updatedAt = B.iso(); save(); B.emit('profiles'); }
    return A.readProfiles().then(function (list) {
      if (!list) return;
      var changed = false;
      list.forEach(function (o) {
        if (o && lower(o.id) === lower(profileId) && o.deleted !== true) { o.accentColor = key; o.updatedAt = B.iso(); changed = true; }
      });
      if (changed) return A.writeProfiles(list).then(function () { applyProfiles(list); });
    }).catch(B.noop);
  };

  // MARK: servers

  D.kinds = ['M3U / M3U8', 'Xtream Codes', 'Stalker / MAC-portal', 'Plex', 'Jellyfin', 'Emby', 'Stremio'];
  D.supportedKinds = D.kinds;
  D.syncSources = function () {
    return A.getSources().then(function (r) {
      if (!r) return false;
      applySources(r);
      return true;
    });
  };
  function applySources(r) {
    D.sources = r.sources.filter(function (s) { return s.deleted !== true; });
    D.mainSourceID = r.mainSourceID;
    save();
    B.emit('sources');
  }

  /**
   * Bron toevoegen of wijzigen (alleen hoofdprofiel, zoals de andere apps). Eerst de nieuwste lijst van de server
   * lezen (met tombstones), dan alleen deze bron vervangen, updatedAt = nu, en de hele lijst terug (put_sources).
   * Onbekende velden, het apple/android-blok, catchupOffsets en ongewijzigde secrets blijven staan.
   * opts: {makeMain: true|false}
   */
  D.saveSource = function (src, opts) {
    opts = opts || {};
    return A.getSources().then(function (r) {
      if (!r) throw new Error(L('No internet connection.'));
      var list = r.sources.slice();
      var idx = -1;
      for (var i = 0; i < list.length; i++) if (lower(list[i].id) === lower(src.id)) { idx = i; break; }
      var base = idx >= 0 && list[idx].deleted !== true ? list[idx] : {};
      var o = B.assign({}, base, src);
      o.secrets = B.assign({}, (base && base.secrets) || {}, src.secrets || {});
      Object.keys(o.secrets).forEach(function (k) { if (o.secrets[k] == null) delete o.secrets[k]; });
      ['m3uURL', 'epgURL', 'serverURL', 'username', 'macAddress'].forEach(function (k) { if (o[k] == null) o[k] = ''; });
      o.preferHLS = !!o.preferHLS;
      delete o.deleted; delete o.deletedAt;
      o.updatedAt = B.iso();
      if (idx >= 0) list[idx] = o; else list.push(o);
      var main = r.mainSourceID;
      var alive = list.filter(function (s) { return s.deleted !== true; });
      var mainValid = !!main && alive.some(function (s) { return lower(s.id) === lower(main); });
      if (opts.makeMain || !mainValid) main = o.id;
      return A.putSources(list, main).then(function (at) {
        applySources({ sources: list, mainSourceID: main, updatedAt: at });
        return o;
      });
    });
  };
  /** Bron verwijderen: tombstone {id, deleted, deletedAt, updatedAt}; hoofdserver schuift door naar de eerste die overblijft. */
  D.deleteSource = function (id) {
    return A.getSources().then(function (r) {
      if (!r) throw new Error(L('No internet connection.'));
      var now = B.iso();
      var list = r.sources.map(function (s) { return lower(s.id) === lower(id) ? { id: s.id, deleted: true, deletedAt: now, updatedAt: now } : s; });
      var main = r.mainSourceID;
      if (lower(main) === lower(id)) {
        var first = list.filter(function (s) { return s.deleted !== true; })[0];
        main = first ? first.id : null;
      }
      return A.putSources(list, main).then(function (at) {
        applySources({ sources: list, mainSourceID: main, updatedAt: at });
        if (lower(B.store.get('activeSource', '')) === lower(id)) B.store.del('activeSource');
        B.idb.del('lib:' + id); B.idb.del('epg:' + id); B.idb.del('chan:' + id);
      });
    });
  };
  /** Hoofdserver van het account wijzigen. */
  D.setMainSource = function (id) {
    return A.getSources().then(function (r) {
      if (!r) throw new Error(L('No internet connection.'));
      return A.putSources(r.sources, id).then(function (at) { applySources({ sources: r.sources, mainSourceID: id, updatedAt: at }); });
    });
  };
  /** Terugkijk-correctie per zender in minuten (−120…120, build 51), gedeeld veld catchupOffsets van de bron. */
  D.catchupOffset = function (src, channelID) {
    var o = src && src.catchupOffsets;
    var v = o && B.has(o, channelID) ? B.int(o[channelID]) : 0;
    return v || 0;
  };
  D.setCatchupOffset = function (src, channelID, minutes) {
    var m = B.clamp(Math.round(minutes || 0), -120, 120);
    var offsets = B.assign({}, (src && src.catchupOffsets) || {});
    if (m === 0) delete offsets[channelID]; else offsets[channelID] = m;
    var local = D.sourceById(src.id);
    if (local) { local.catchupOffsets = offsets; save(); }
    return A.getSources().then(function (r) {
      if (!r) return;
      var list = r.sources.map(function (s) {
        if (lower(s.id) !== lower(src.id) || s.deleted === true) return s;
        var c = B.assign({}, s);
        var cur = B.assign({}, s.catchupOffsets || {});
        if (m === 0) delete cur[channelID]; else cur[channelID] = m;
        c.catchupOffsets = cur;
        c.updatedAt = B.iso();
        return c;
      });
      return A.putSources(list, r.mainSourceID).then(function (at) { applySources({ sources: list, mainSourceID: r.mainSourceID, updatedAt: at }); });
    }).catch(B.noop);
  };
  D.sourceById = function (id) {
    for (var i = 0; i < D.sources.length; i++) if (lower(D.sources[i].id) === lower(id)) return D.sources[i];
    return null;
  };
  /** Actieve server: eigen keuze op deze tv, anders de hoofdserver van het account, anders de eerste die werkt. */
  D.activeSource = function () {
    var own = D.sourceById(B.store.get('activeSource', null));
    if (own) return own;
    var main = D.sourceById(D.mainSourceID);
    if (main && D.supportedKinds.indexOf(main.kind) >= 0) return main;
    for (var i = 0; i < D.sources.length; i++) if (D.supportedKinds.indexOf(D.sources[i].kind) >= 0) return D.sources[i];
    return main || D.sources[0] || null;
  };
  D.setActiveSource = function (id) { B.store.set('activeSource', id); };
  D.secret = function (src, name) { return (src && src.secrets && src.secrets[name]) || ''; };
  /** Eigen blok van Android (aanpassingen en vergrendelde groepen); wordt hier alleen gelezen. */
  D.customization = function (src) {
    var a = src && src.android;
    var c = (a && a.customization) || {};
    return {
      renamed: c.renamed || {}, hidden: c.hidden || [], groupOrder: c.groupOrder || [],
      channelOrder: c.channelOrder || {}, merged: c.merged || {}
    };
  };
  D.lockedGroups = function (src) { var a = src && src.android; return (a && a.lockedGroups) || []; };

  // MARK: per profiel (profile.<id>)

  function time(v) {
    if (v == null) return null;
    if (typeof v === 'number') return v > 1e12 ? v : v * 1000;
    return B.parseISO(v);
  }
  function putNewer(map, k, item) { var c = map[k]; if (!c || item.at > c.at) map[k] = item; }

  function parseSetField(e, removed) {
    var out = {};
    function parseList(a) {
      a.forEach(function (x) {
        if (typeof x === 'string' || typeof x === 'number') { if (String(x)) putNewer(out, String(x), { at: 0, on: true }); }
        else if (x && typeof x === 'object') {
          var k = x.id || x.key;
          if (!k) return;
          var at = time(x.at) || time(x.updatedAt) || 0;
          putNewer(out, String(k), { at: at, on: !(x.removed === true || x.deleted === true) });
        }
      });
    }
    if (Array.isArray(e)) parseList(e);
    else if (e && typeof e === 'object') {
      var list = Array.isArray(e.items) ? e.items : (Array.isArray(e.ids) ? e.ids : null);
      if (list) {
        parseList(list);
        if (e.removed && typeof e.removed === 'object') Object.keys(e.removed).forEach(function (k) { putNewer(out, k, { at: time(e.removed[k]) || 0, on: false }); });
      } else {
        Object.keys(e).forEach(function (k) {
          var v = e[k];
          if (v && typeof v === 'object') putNewer(out, k, { at: time(v.at) || time(v.updatedAt) || 0, on: !(v.removed === true || v.deleted === true) });
          else if (v === true || v === false) putNewer(out, k, { at: 0, on: v });
          else putNewer(out, k, { at: time(v) || 0, on: true });
        });
      }
    }
    if (removed && typeof removed === 'object') Object.keys(removed).forEach(function (k) { putNewer(out, k, { at: time(removed[k]) || 0, on: false }); });
    return out;
  }
  function parseProgress(o) {
    var pos = B.num(o.position) || 0, dur = B.num(o.duration);
    var frac = B.num(o.fraction);
    if (frac == null) { if (dur && dur > 0) frac = pos / dur; else return null; }
    var e = { fraction: frac, position: pos, updated: time(o.updatedAt) || time(o.updated) || 0, title: o.title || '' };
    if (o.artwork) e.artwork = o.artwork;
    if (o.playlistID) e.playlistID = o.playlistID;
    return e;
  }
  function parseHistory(o) {
    if (!o || !o.itemID) return null;
    var d = time(o.date) || time(o.at);
    if (!d) return null;
    var e = { itemID: String(o.itemID), title: o.title || '', type: String(o.type || 'movie').toLowerCase(), date: d };
    if (o.playlistID) e.playlistID = o.playlistID;
    if (o.subtitle) e.subtitle = o.subtitle;
    if (o.artwork) e.artwork = o.artwork;
    if (o.channelID) e.channelID = o.channelID;
    return e;
  }
  function historyId(e) { return (e.playlistID || '-') + '|' + e.itemID; }

  /** Serverobject → model {sets, progress, history}. */
  function parseState(o) {
    var removed = (o && o.removed) || {};
    var st = { sets: {}, progress: {}, history: {} };
    SET_FIELDS.forEach(function (f) { st.sets[f] = parseSetField(o[f], removed[f]); });
    var pr = o.progress || {};
    Object.keys(pr).forEach(function (k) {
      var p = pr[k];
      if (!p || typeof p !== 'object') return;
      if (p.removed === true || p.deleted === true) putNewer(st.progress, k, { at: time(p.updatedAt) || 0, v: null });
      else { var e = parseProgress(p); if (e) putNewer(st.progress, k, { at: e.updated, v: e }); }
    });
    if (removed.progress) Object.keys(removed.progress).forEach(function (k) { putNewer(st.progress, k, { at: time(removed.progress[k]) || 0, v: null }); });
    (Array.isArray(o.history) ? o.history : []).forEach(function (h) {
      var e = parseHistory(h);
      if (e) putNewer(st.history, historyId(e), { at: e.date, v: e });
    });
    if (removed.history) Object.keys(removed.history).forEach(function (k) {
      var at = time(removed.history[k]) || 0, c = st.history[k];
      if (!c || at >= c.at) st.history[k] = { at: at, v: null };
    });
    return st;
  }
  function mergeMaps(a, b) {
    var out = {};
    Object.keys(a || {}).forEach(function (k) { out[k] = a[k]; });
    Object.keys(b || {}).forEach(function (k) { var l = out[k]; if (!l || b[k].at > l.at) out[k] = b[k]; });
    return out;
  }
  function mergeState(a, b) {
    var st = { sets: {}, progress: mergeMaps(a.progress, b.progress), history: mergeMaps(a.history, b.history) };
    SET_FIELDS.forEach(function (f) { st.sets[f] = mergeMaps(a.sets[f], b.sets[f]); });
    return st;
  }
  function alive(item) { return item.on === undefined ? item.v != null : item.on; }
  /** Oude verwijderingen weg, maximale lengtes (20 recente zenders, 300 voortgang, 500 geschiedenis). */
  function normalize(st) {
    var cutoff = Date.now() - REMOVED_DAYS * 86400000;
    function prune(m, max) {
      var keys = Object.keys(m).filter(function (k) { return alive(m[k]) || m[k].at >= cutoff; });
      var live = keys.filter(function (k) { return alive(m[k]); }).sort(function (x, y) { return m[y].at - m[x].at; });
      var keep = {};
      (max ? live.slice(0, max) : live).forEach(function (k) { keep[k] = true; });
      var out = {};
      keys.forEach(function (k) { if (!alive(m[k]) || keep[k]) out[k] = m[k]; });
      return out;
    }
    var o = { sets: {}, progress: prune(st.progress, MAX_PROGRESS), history: prune(st.history, MAX_HISTORY) };
    SET_FIELDS.forEach(function (f) { o.sets[f] = prune(st.sets[f] || {}, f === 'recentChannels' ? MAX_RECENT : 0); });
    return o;
  }
  function sortedKeys(m, pred) { return Object.keys(m).filter(pred).sort(); }

  /** Model → serverobject; onbekende velden (prefs, pin, homeLayout, …) blijven zoals de server ze had. */
  function toJSON(base, st) {
    var out = B.copy(base || {});
    var removed = {};
    SET_FIELDS.forEach(function (f) {
      var m = st.sets[f] || {};
      out[f] = Object.keys(m).filter(function (k) { return m[k].on; })
        .sort(function (x, y) { return (m[y].at - m[x].at) || (x < y ? -1 : x > y ? 1 : 0); })
        .map(function (k) { return { id: k, at: B.iso(m[k].at) }; });
      removed[f] = {};
      sortedKeys(m, function (k) { return !m[k].on; }).forEach(function (k) { removed[f][k] = B.iso(m[k].at); });
    });
    out.progress = {};
    removed.progress = {};
    sortedKeys(st.progress, function () { return true; }).forEach(function (k) {
      var it = st.progress[k];
      if (it.v == null) { removed.progress[k] = B.iso(it.at); return; }
      var e = it.v;
      var p = { position: e.position, duration: e.fraction > 0 ? e.position / e.fraction : 0, fraction: e.fraction, updatedAt: B.iso(e.updated), title: e.title };
      if (e.artwork) p.artwork = e.artwork;
      if (e.playlistID) p.playlistID = e.playlistID;
      out.progress[k] = p;
    });
    var hist = Object.keys(st.history).map(function (k) { return st.history[k]; }).filter(function (x) { return x.v; })
      .map(function (x) { return x.v; }).sort(function (a, b) { return b.date - a.date; });
    out.history = hist.map(function (e) {
      var h = { id: historyId(e), itemID: e.itemID };
      if (e.playlistID) h.playlistID = e.playlistID;
      h.title = e.title;
      if (e.subtitle) h.subtitle = e.subtitle;
      if (e.artwork) h.artwork = e.artwork;
      h.type = e.type;
      if (e.channelID) h.channelID = e.channelID;
      h.date = B.iso(e.date);
      return h;
    });
    removed.history = {};
    sortedKeys(st.history, function (k) { return !st.history[k].v; }).forEach(function (k) { removed.history[k] = B.iso(st.history[k].at); });
    var baseRemoved = (base && base.removed && typeof base.removed === 'object') ? B.copy(base.removed) : {};
    out.removed = B.assign(baseRemoved, removed);
    out.updatedAt = B.iso();
    return out;
  }
  function stateKey(st) {
    // vergelijkingssleutel zonder updatedAt
    var j = toJSON({}, st); delete j.updatedAt; return JSON.stringify(j);
  }

  // Actief profiel: model + basis van de server
  var P = { id: null, st: null, base: null, names: [], dirty: false };
  function pkey(id) { return 'pstate.' + lower(id); }
  function persistP() {
    if (!P.id) return;
    B.store.set(pkey(P.id), { st: P.st, base: P.base, names: P.names, dirty: P.dirty });
  }
  D.loadProfileState = function (id) {
    var saved = B.store.get(pkey(id), null);
    P.id = id;
    P.st = (saved && saved.st) || { sets: { myList: {}, favorites: {}, watched: {}, recentChannels: {} }, progress: {}, history: {} };
    SET_FIELDS.forEach(function (f) { P.st.sets[f] = P.st.sets[f] || {}; });
    P.base = (saved && saved.base) || null;
    P.names = (saved && saved.names) || [];
    P.dirty = !!(saved && saved.dirty);
    B.emit('profileData');
  };

  /** Pincodes van alle profielen uit de state (pin staat in profile.<id>). */
  function readPins(sections) {
    var pins = {};
    Object.keys(sections).forEach(function (name) {
      if (lower(name).indexOf('profile.') !== 0) return;
      var d = sections[name] && sections[name].data;
      if (!d || !B.has(d, 'pin')) return;
      var v = d.pin;
      if (v && typeof v === 'object') v = v.pin || v.value || v.code;
      var id = lower(name.slice(8));
      var at = time(d.pinUpdatedAt) || 0;
      var cur = pins[id];
      if (!cur || at >= cur.at) pins[id] = { at: at, pin: v ? String(v) : null };
    });
    var out = {};
    Object.keys(pins).forEach(function (k) { if (pins[k].pin) out[k] = pins[k].pin; });
    return out;
  }

  var syncing = null, lastProgressUpload = 0, timer = null;

  /**
   * Alles van de server lezen en samenvoegen: instellingen, collecties, pincodes en het actieve profiel.
   * Upload als lokaal iets nieuwer is. Fouten zijn stil.
   */
  D.syncState = function () {
    if (syncing) return syncing;
    syncing = A.getState().then(function (all) {
      if (!all) return false;
      // instellingen: geheel, nieuwste wint (deze tv wijzigt alleen via setShared)
      if (all.settings) D.settings = { data: all.settings.data || {}, updatedAt: all.settings.updatedAt };
      if (all.collections) D.collections = all.collections.data || null;
      D.pins = readPins(all);
      save();
      B.emit('settings');
      // connections (Trakt/OpenSubtitles) en herinneringen voegen zelf samen
      B.emit('state', all);
      if (!P.id) return true;
      return mergeActive(all);
    }).catch(function (e) { B.log('sync', e); return false; }).then(function (r) { syncing = null; return r; });
    return syncing;
  };

  function mergeActive(all) {
    var id = lower(P.id);
    var names = Object.keys(all).filter(function (n) { return lower(n) === 'profile.' + id; });
    var server = null, base = null;
    names.forEach(function (n) {
      var d = all[n] && all[n].data;
      if (!d || typeof d !== 'object') return;
      if (!base || n === 'profile.' + P.id) base = d;
      var s = parseState(d);
      server = server ? mergeState(server, s) : s;
    });
    if (base) P.base = base;
    if (names.length) P.names = names;
    var srv = server ? normalize(server) : null;
    var merged = normalize(srv ? mergeState(P.st, srv) : P.st);
    var changed = stateKey(merged) !== stateKey(P.st);
    P.st = merged;
    var needUpload = !srv ? hasAny(merged) : stateKey(merged) !== stateKey(srv);
    P.dirty = false;
    persistP();
    if (changed) B.emit('profileData');
    if (!needUpload) return true;
    var json = toJSON(P.base, merged);
    var targets = P.names.length ? P.names : ['profile.' + P.id];
    return Promise.all(targets.map(function (n) { return A.putState(n, json); })).then(function (res) {
      if (res.some(function (x) { return x == null; })) { P.dirty = true; persistP(); return false; }
      P.base = json; persistP();
      return true;
    });
  }
  function hasAny(st) {
    var any = Object.keys(st.progress).length || Object.keys(st.history).length;
    SET_FIELDS.forEach(function (f) { if (Object.keys(st.sets[f]).length) any = true; });
    return !!any;
  }

  /** Lokale wijziging: na ~3 s naar de server (voortgang tijdens afspelen hooguit eens per 30 s). */
  function changed(progressOnly) {
    P.dirty = true; persistP();
    B.emit('profileData');
    var wait = 3000;
    if (progressOnly) {
      var since = Date.now() - lastProgressUpload;
      if (since < 30000) wait = Math.max(3000, 30000 - since);
    }
    clearTimeout(timer);
    timer = setTimeout(function () { lastProgressUpload = Date.now(); D.syncState(); }, wait);
  }
  /** Meteen versturen (stoppen met afspelen, app naar de achtergrond). */
  D.flush = function () {
    if (!P.dirty) return Promise.resolve();
    clearTimeout(timer);
    lastProgressUpload = Date.now();
    return D.syncState();
  };

  // lezen
  function liveKeys(m) {
    return Object.keys(m || {}).filter(function (k) { return m[k].on; }).sort(function (a, b) { return m[b].at - m[a].at; });
  }
  D.key = function (playlist, item) { return playlist + '|' + item; };
  D.episodeKey = function (series, season, ep) { return 'ep:' + series + '|' + season + '-' + ep; };
  D.inSet = function (field, k) { var it = P.st && P.st.sets[field][k]; return !!(it && it.on); };
  D.setList = function (field) { return P.st ? liveKeys(P.st.sets[field]) : []; };
  D.progress = function (k) { var it = P.st && P.st.progress[k]; return it && it.v ? it.v : null; };
  D.progressList = function () {
    if (!P.st) return [];
    var m = P.st.progress;
    return Object.keys(m).filter(function (k) { return m[k].v; }).map(function (k) { return { key: k, e: m[k].v }; })
      .sort(function (a, b) { return b.e.updated - a.e.updated; });
  };
  D.history = function () {
    if (!P.st) return [];
    var m = P.st.history;
    return Object.keys(m).map(function (k) { return m[k].v; }).filter(Boolean).sort(function (a, b) { return b.date - a.date; });
  };
  D.isWatched = function (k) { return D.inSet('watched', k); };

  // schrijven
  D.toggleSet = function (field, k, on) {
    if (!P.st) return;
    var cur = D.inSet(field, k);
    if (on == null) on = !cur;
    if (on === cur) return;
    P.st.sets[field][k] = { at: Date.now(), on: on };
    changed(false);
  };
  D.pushRecentChannel = function (playlist, channelID) {
    if (!P.st) return;
    P.st.sets.recentChannels[D.key(playlist, channelID)] = { at: Date.now(), on: true };
    P.st = normalize(P.st);
    changed(false);
  };
  D.setProgress = function (k, e) {
    if (!P.st) return;
    e.updated = Date.now();
    P.st.progress[k] = { at: e.updated, v: e };
    changed(true);
  };
  D.clearProgress = function (k) {
    if (!P.st || !P.st.progress[k] || !P.st.progress[k].v) return;
    P.st.progress[k] = { at: Date.now(), v: null };
    changed(false);
  };
  D.setWatched = function (k, on) { D.toggleSet('watched', k, on); };
  D.addHistory = function (e) {
    if (!P.st) return;
    var id = historyId(e);
    P.st.history[id] = { at: e.date, v: e };
    changed(false);
  };

  // MARK: collecties (section `collections`, version 2: per profiel)

  /** Collecties van een profiel (zonder tombstones), op volgorde. Ouder formaat: de oude lijst voor iedereen. */
  D.collectionsFor = function (profileId) {
    var c = D.collections;
    if (!c || typeof c !== 'object') return [];
    var list = null;
    if ((B.int(c.version) || 0) >= 2 && c.profiles && typeof c.profiles === 'object') {
      var keys = Object.keys(c.profiles).filter(function (k) { return lower(k) === lower(profileId); });
      list = [];
      keys.forEach(function (k) { list = list.concat((c.profiles[k] && c.profiles[k].items) || []); });
    } else list = Array.isArray(c.items) ? c.items : [];
    var byId = {};
    list.forEach(function (x) {
      if (!x || !x.id) return;
      var cur = byId[lower(x.id)];
      if (!cur || (time(x.updatedAt) || 0) > (time(cur.updatedAt) || 0)) byId[lower(x.id)] = x;
    });
    return Object.keys(byId).map(function (k) { return byId[k]; }).filter(function (x) { return x.deleted !== true; })
      .sort(function (a, b) { return (B.int(a.order) || 0) - (B.int(b.order) || 0); });
  };

  // MARK: intro-markeringen (settings.shared.introMarkers, sleutel "<serverID>:<serieID>", hoofdletterongevoelig)

  D.introMarker = function (sourceId, seriesId) {
    var all = D.shared('introMarkers', {}) || {};
    var want = lower(sourceId + ':' + seriesId);
    for (var k in all) {
      if (B.has(all, k) && lower(k) === want) {
        var m = all[k] || {};
        var o = { introStart: B.num(m.introStart), introEnd: B.num(m.introEnd), creditsStart: B.num(m.creditsStart) };
        if (o.introStart == null && o.introEnd == null && o.creditsStart == null) return null;
        return o;
      }
    }
    return null;
  };

  /**
   * Intro-markering zetten of wissen (alle velden null = gewist; blijft staan zodat het wissen doorkomt).
   * Zoals setShared: nieuwste settings van de server, alleen deze serie aanpassen, het geheel uploaden.
   */
  D.setIntroMarker = function (sourceId, seriesId, marker) {
    var key = sourceId + ':' + seriesId;
    function r1(v) { return v == null ? null : Math.round(v * 10) / 10; }
    var val = { introStart: r1(marker.introStart), introEnd: r1(marker.introEnd), creditsStart: r1(marker.creditsStart) };
    function put(map) {
      var out = B.assign({}, map || {});
      Object.keys(out).forEach(function (k) { if (lower(k) === lower(key) && k !== key) { key = k; } });
      out[key] = val;
      return out;
    }
    var local = D.settings ? B.copy(D.settings) : { data: {} };
    local.data = local.data || {};
    local.data.shared = local.data.shared || {};
    local.data.shared.introMarkers = put(local.data.shared.introMarkers);
    local.data.updatedAt = B.iso();
    D.settings = local; save();
    return A.getState(['settings']).then(function (all) {
      var cur = all && all.settings && all.settings.data;
      var data = cur && typeof cur === 'object' ? B.copy(cur) : B.copy(local.data);
      data.shared = data.shared || {};
      data.shared.introMarkers = put(data.shared.introMarkers);
      data.updatedAt = B.iso();
      return A.putState('settings', data).then(function (at) {
        if (at != null) { D.settings = { data: data, updatedAt: at || data.updatedAt }; save(); }
      });
    }).catch(B.noop);
  };

  /** Startscherm-indeling van het actieve profiel (homeLayout in profile.<id>), of null. */
  D.homeLayout = function () {
    var h = P.base && P.base.homeLayout;
    return h && Array.isArray(h.rows) ? h.rows : null;
  };

  // test- en debug-hulp
  D._parseState = parseState; D._toJSON = toJSON; D._merge = mergeState; D._normalize = normalize;
})();
