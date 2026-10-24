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
  var L = function () { return B.L.apply(null, arguments); };
  var D = B.data = {};

  // build 128: hiddenCategories = categorieën aan/uit per profiel ("<server-id>|<naam>", zoals myList)
  var SET_FIELDS = ['myList', 'favorites', 'watched', 'recentChannels', 'hiddenCategories'];
  var REMOVED_DAYS = 90, MAX_RECENT = 20, MAX_PROGRESS = 300, MAX_HISTORY = 500;
  var lower = function (s) { return String(s || '').toLowerCase(); };

  // MARK: lokale kopieën

  D.profiles = B.store.get('profiles', []) || [];        // alleen actieve (geen tombstones), op volgorde
  D.sources = B.store.get('sources', []) || [];          // alleen actieve bronnen
  D.mainSourceID = B.store.get('mainSourceID', null);
  D.settings = B.store.get('settings', null);            // {data, updatedAt} zoals de server het gaf
  D.collections = B.store.get('collections', null);      // ruwe section-data
  // build 146: kleine letters profiel-id → pinHash-object ({alg, iter, salt, hash}); een oude leesbare pincode (tekst)
  // alleen tot hij gehasht is (B.b146.pin, migratePins)
  D.pins = B.store.get('pins', {}) || {};
  // instellingen die op deze tv gewijzigd zijn maar nog niet geüpload (offline): {shared: {sleutel: waarde}, intro: {sleutel: markering}}
  var pendingSettings = B.store.get('settingsPending', null);
  // build 141: servers (met wachtwoorden) en pincodes staan in de kluis; na het laden opnieuw lezen
  if (B.vault) B.vault.onLoad(function () {
    D.sources = B.store.get('sources', []) || [];
    D.pins = B.store.get('pins', {}) || {};
  });

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
    pendingSettings = null; B.store.del('settingsPending');
    save();
    B.store.keys('pstate.').forEach(function (k) { B.store.del(k); });
    B.store.keys('lastChannel.').forEach(function (k) { B.store.del(k); });
    B.store.del('lastProfile'); B.store.del('activeSource'); B.store.del('accentMigrated'); B.store.del('cats.migrated128');
    // build 140: categorieën per account
    B.store.del('accountCats'); B.store.del('cats.migrated140');
    ACC = { marks: {}, base: null, dirty: false }; accIdx = null;
    B.idb.clear();
    // Trakt, OpenSubtitles en herinneringen van het vorige account
    B.emit('wipe');
  };

  // MARK: instellingen (settings.shared, gelijk op alle platforms)

  D.shared = function (key, def) {
    var s = D.settings && D.settings.data && D.settings.data.shared;
    return s && B.has(s, key) && s[key] != null ? s[key] : def;
  };
  /** Wijzigingen van deze tv op een settings-object toepassen (gedeelde sleutels en intro-markeringen). */
  function applyPending(data, pend) {
    data.shared = data.shared || {};
    Object.keys(pend.shared || {}).forEach(function (k) { data.shared[k] = pend.shared[k]; });
    var intro = pend.intro || {};
    var keys = Object.keys(intro);
    if (keys.length) {
      var out = B.assign({}, data.shared.introMarkers || {});
      keys.forEach(function (key) {
        // bestaande sleutel met andere hoofdletters hergebruiken (sleutels zijn hoofdletterongevoelig)
        var use = key;
        Object.keys(out).forEach(function (k) { if (lower(k) === lower(key)) use = k; });
        out[use] = intro[key];
      });
      data.shared.introMarkers = out;
    }
    return data;
  }
  /**
   * Openstaande wijzigingen uploaden: nieuwste settings van de server lezen, alleen onze sleutels aanpassen, het geheel
   * terugzetten (het geheel met de nieuwste updatedAt wint). Lukt het niet, dan blijven ze staan voor de volgende sync.
   */
  var settingsBusy = null;
  function uploadSettings(serverSection) {
    if (!pendingSettings) return Promise.resolve();
    if (settingsBusy) return settingsBusy;
    var pend = pendingSettings;
    var read = serverSection !== undefined ? Promise.resolve({ settings: serverSection }) : A.getState(['settings']);
    settingsBusy = read.then(function (all) {
      if (!all) return;
      var cur = all.settings && all.settings.data;
      var data = applyPending(cur && typeof cur === 'object' ? B.copy(cur) : {}, pend);
      data.updatedAt = B.iso();
      return A.putState('settings', data).then(function (at) {
        if (at == null) return;
        D.settings = { data: data, updatedAt: at || data.updatedAt };
        // alleen wissen wat we net verstuurd hebben (er kan intussen iets bij zijn gekomen)
        if (pendingSettings === pend) { pendingSettings = null; B.store.del('settingsPending'); }
        save();
      });
    }).catch(B.noop).then(function () {
      settingsBusy = null;
      // tijdens het versturen nog iets gewijzigd: dat ook versturen
      if (pendingSettings && pendingSettings !== pend) return uploadSettings();
    });
    return settingsBusy;
  }
  function addPending(kind, key, value) {
    var p = pendingSettings ? B.copy(pendingSettings) : { shared: {}, intro: {} };
    p[kind] = p[kind] || {};
    p[kind][key] = value;
    pendingSettings = p;
    B.store.set('settingsPending', p);
    var local = D.settings ? B.copy(D.settings) : { data: {} };
    local.data = applyPending(local.data || {}, p);
    local.data.updatedAt = B.iso();
    D.settings = local; save();
  }
  /** Gedeelde instelling wijzigen: lokaal meteen, daarna alleen dit veld in de nieuwste serverversie (uploadSettings). */
  D.setShared = function (key, value) {
    addPending('shared', key, value);
    B.emit('settings');
    return uploadSettings();
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
    // build 97: lokale kijkstatus (pstate.<id>) van verwijderde profielen opruimen
    if (active.length) {
      var ids = {};
      active.forEach(function (p) { ids[lower(p.id)] = true; });
      B.store.keys('pstate.').forEach(function (k) { if (!ids[k.slice(7)] && !(P.id && k.slice(7) === lower(P.id))) B.store.del(k); });
    }
    B.emit('profiles');
  }

  /**
   * Profielen lezen (build 146: device-auth get_profiles); leeg account → één hoofdprofiel aanmaken en uploaden (zoals de
   * andere apps doen). Een wijziging op deze tv die nog niet op de server staat (bijv. een accentkleur, offline gekozen) en
   * nieuwer is dan de server, blijft staan en gaat mee met put_profiles (B.b146.profiles.plan, = Android).
   */
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
        return A.writeProfiles(list).then(function (merged) { applyProfiles(merged || list); return true; });
      }
      var merged = mergeLocalProfiles(list);
      if (merged.upload) {
        return A.writeProfiles(merged.list).then(function (back) { applyProfiles(back || merged.list); return true; });
      }
      applyProfiles(list);
      return true;
    }, function () { return false; });
  };
  /** Lokale profielen die nieuwer zijn dan de server houden (alleen velden die deze tv wijzigt); de server is verder de bron. */
  function mergeLocalProfiles(list) {
    var X = B.b146 && B.b146.profiles;
    if (!X || !D.profiles.length) return { list: list, upload: false };
    var mainP = D.mainProfile();
    var plan = X.plan(D.profiles.map(function (p) { return { key: lower(p.id), updatedAt: p.updatedAt || null }; }),
      list.map(function (o) { return { key: lower(o.id), updatedAt: o.updatedAt || null, deleted: o.deleted === true, isMain: o.isMain === true, order: B.int(o.order) }; }),
      {}, mainP ? lower(mainP.id) : null);
    var upload = false;
    var out = list.map(function (o) {
      var e = null;
      plan.entries.forEach(function (x) { if (x.kind === 'keep' && x.row === lower(o.id)) e = x; });
      if (!e) return o;
      var p = D.profiles[e.local];
      if (!X.newer(p.updatedAt || X.EPOCH, o.updatedAt || X.EPOCH)) return o;
      // alleen de accentkleur wordt op de tv gewijzigd
      upload = true;
      var c = B.copy(o);
      c.accentColor = p.accentColor; c.updatedAt = p.updatedAt;
      return c;
    });
    return { list: out, upload: upload };
  }

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
      if (changed) return A.writeProfiles(list).then(function (back) { if (back) applyProfiles(back); });
    }).catch(B.noop);
  };

  /**
   * Eenmalige migratie (build 54): oude settings.shared.accentColor naar alle profielen zonder eigen kleur.
   * Pas na het toepassen van de profielen van de server; daarna wordt settings.shared.accentColor niet meer gebruikt.
   */
  D.migrateAccent = function () {
    if (B.store.get('accentMigrated', false)) return Promise.resolve();
    var old = lower(D.shared('accentColor', ''));
    if (!ACCENTS[old]) { if (D.settings) B.store.set('accentMigrated', true); return Promise.resolve(); }
    return A.readProfiles().then(function (list) {
      if (!list) return;
      var changed = false, now = B.iso();
      list.forEach(function (o) {
        if (o && o.id && o.deleted !== true && !ACCENTS[lower(o.accentColor)]) { o.accentColor = old; o.updatedAt = now; changed = true; }
      });
      if (!changed) { B.store.set('accentMigrated', true); return; }
      return A.writeProfiles(list).then(function (back) { if (back) { B.store.set('accentMigrated', true); applyProfiles(back); } });
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
    var removedObj = (o && o.removed && typeof o.removed === 'object') ? o.removed : {};
    // verwijderingen per veld: `removed.<veld>` plus de oudere iOS-velden `<veld>Removed` (`progressRemoved`,
    // `historyRemoved`, …) van Apple-builds vóór 60; per sleutel wint de nieuwste tijd
    function removedFor(f) {
      var a = removedObj[f], b = o ? o[f + 'Removed'] : null;
      a = (a && typeof a === 'object') ? a : null; b = (b && typeof b === 'object' && !Array.isArray(b)) ? b : null;
      if (!a || !b) return a || b;
      var out = B.copy(a);
      Object.keys(b).forEach(function (k) { if (!B.has(out, k) || (time(b[k]) || 0) > (time(out[k]) || 0)) out[k] = b[k]; });
      return out;
    }
    var removed = { progress: removedFor('progress'), history: removedFor('history') };
    var st = { sets: {}, progress: {}, history: {} };
    SET_FIELDS.forEach(function (f) { st.sets[f] = parseSetField(o[f], removedFor(f)); });
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
      // `at` en `date` zijn dezelfde tijd (iOS las `at`, Android/Tizen `date`): schrijf allebei
      h.at = B.iso(e.date);
      h.date = h.at;
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

  // Actief profiel: model + basis van de server.
  // build 97: bij een profielwissel komt er een nieuw object; een lopende sync/upload houdt zo het object (en de id)
  // van het profiel waarvoor hij begon, en schrijft nooit de voortgang van het oude profiel bij het nieuwe.
  var P = { id: null, st: null, base: null, names: [], dirty: false };
  function pkey(id) { return 'pstate.' + lower(id); }
  function persistP(Q) {
    Q = Q || P;
    if (!Q.id) return;
    B.store.set(pkey(Q.id), { st: Q.st, base: Q.base, names: Q.names, dirty: Q.dirty });
  }
  D.loadProfileState = function (id) {
    var saved = B.store.get(pkey(id), null);
    var st = (saved && saved.st) || { sets: { myList: {}, favorites: {}, watched: {}, recentChannels: {}, hiddenCategories: {} }, progress: {}, history: {} };
    SET_FIELDS.forEach(function (f) { st.sets[f] = st.sets[f] || {}; });
    P = { id: id, st: st, base: (saved && saved.base) || null, names: (saved && saved.names) || [], dirty: !!(saved && saved.dirty) };
    B.emit('profileData');
  };
  /** build 97: id van het profiel waarvan de kijkstatus nu geladen is (de speler schrijft alleen naar zijn eigen profiel). */
  D.activeProfileId = function () { return P.id; };

  /**
   * Pincodes van alle profielen uit de state (profile.<id>). Build 146: `pinHash` gaat voor; een leesbare `pin` zonder
   * hash is van een oude app: die wordt meteen gehasht en geüpload ([migratePins], pinUpdatedAt blijft gelijk). Lokaal
   * staat daarna alleen de hash.
   */
  function readPins(sections, prev) {
    var C = B.b146 && B.b146.pinChoice;
    if (!C) return { pins: {}, legacy: [] };
    var pins = {};
    Object.keys(sections).forEach(function (name) {
      if (lower(name).indexOf('profile.') !== 0) return;
      var d = sections[name] && sections[name].data;
      if (!d || typeof d !== 'object') return;
      var r = C.read(d);
      if (r.kind === 'absent') return;
      var id = lower(name.slice(8));
      var at = time(d.pinUpdatedAt) || 0;
      var v = r.kind === 'hashed' ? r.hash : r.kind === 'legacy' ? r.pin : null;
      var cur = pins[id];
      if (!cur || at > cur.at || (at === cur.at && C.pick(cur.at, cur.v, at, v) === 'server')) pins[id] = { at: at, v: v, legacy: r.kind === 'legacy', name: name, data: d };
    });
    var out = {}, legacy = [];
    Object.keys(pins).forEach(function (k) {
      var e = pins[k];
      if (e.v == null) return;
      if (e.legacy) {
        // al gehasht op deze tv: die hash houden tot migratePins hem gecontroleerd heeft (dan geen nieuwe salt nodig)
        var known = prev && prev[k];
        out[k] = known && typeof known === 'object' ? known : e.v;
        legacy.push({ id: k, name: e.name, data: e.data, pin: e.v, known: known && typeof known === 'object' ? known : null });
      } else out[k] = e.v;
    });
    return { pins: out, legacy: legacy };
  }

  /**
   * Build 146: oude leesbare pincodes van de server hashen (één voor één, op de achtergrond: zonder WebCrypto kan dat op de
   * tv een paar seconden duren) en uploaden met pinHash, pin: null en dezelfde pinUpdatedAt.
   */
  var migrating = false;
  function migratePins(legacy) {
    if (migrating || !legacy.length || !B.b146) return;
    migrating = true;
    var X = B.b146.pin;
    var chain = Promise.resolve();
    legacy.forEach(function (e) {
      chain = chain.then(function () {
        var known = e.known;
        var reuse = known ? X.verify(e.pin, known).then(function (okv) { return okv ? known : null; }) : Promise.resolve(null);
        return reuse.then(function (h) { return h || X.make(e.pin); }).then(function (h) {
          D.pins[e.id] = h;
          save();
          var data = B.copy(e.data);
          data.pinHash = h; data.pin = null;
          return A.putState(e.name, data);
        });
      }).catch(function (err) { B.log('pincode hashen', err && err.message); });
    });
    chain.then(function () { migrating = false; B.emit('pins'); }, function () { migrating = false; });
  }
  /** Zorgt dat een upload van een profielsectie nooit een leesbare pincode bevat als deze tv de hash kent. */
  function fixPin(json, id) {
    var h = D.pins[lower(id)];
    if (json && typeof json.pin === 'string' && h && typeof h === 'object' && !B.has(json, 'pinHash')) { json.pinHash = h; json.pin = null; }
    return json;
  }

  var syncing = null, lastProgressUpload = 0, timer = null;

  /**
   * Alles van de server lezen en samenvoegen: instellingen, collecties, pincodes en het actieve profiel.
   * Upload als lokaal iets nieuwer is. Fouten zijn stil.
   */
  D.syncState = function () {
    if (syncing) return syncing;
    return startSync(P);
  };
  /** build 97: sync voor een vast profielobject Q (zie P hierboven). */
  function startSync(Q) {
    syncing = A.getState().then(function (all) {
      // build 146 (diagnose): laatste poging om met het account te synchroniseren en of die lukte
      D.lastSyncAt = Date.now(); D.lastSyncOk = !!all;
      if (!all) return false;
      // instellingen: het geheel van de server; wijzigingen van deze tv die nog niet geüpload zijn gaan erover en worden verstuurd
      if (all.settings) {
        D.settings = { data: all.settings.data || {}, updatedAt: all.settings.updatedAt };
        if (pendingSettings) { D.settings.data = applyPending(B.copy(D.settings.data), pendingSettings); uploadSettings(all.settings); }
      } else if (pendingSettings) uploadSettings(null);
      if (all.collections) D.collections = all.collections.data || null;
      var rp = readPins(all, D.pins);
      D.pins = rp.pins;
      save();
      migratePins(rp.legacy);
      B.emit('settings');
      // connections (Trakt/OpenSubtitles) en herinneringen voegen zelf samen
      B.emit('state', all);
      // build 140: categorieën per account (section `account`); eenmalig de per-profiel-sets erin
      migrateAccount(all);
      mergeAccount(all.account).catch(B.noop);
      if (!Q.id) return true;
      // build 128: eerst catalogmeta (remap-regels), dan het actieve profiel, daarna de andere profielen en de collecties
      var AS = B.allservers;
      return (AS ? AS.syncMeta(all) : Promise.resolve()).then(function () {
        return mergeActive(all, Q);
      }).then(function (r) {
        return (AS ? otherProfiles(all, Q) : Promise.resolve()).then(function () { return r; });
      });
    }).catch(function (e) { B.log('sync', e); return false; }).then(function (r) { syncing = null; return r; });
    return syncing;
  }
  /**
   * build 97: wijzigingen van profiel Q versturen. Loopt er al een sync, dan daarna opnieuw als Q dan nog
   * wijzigingen heeft (die sync kan zijn gegevens al vóór de laatste wijziging hebben samengevoegd).
   */
  function flushQ(Q) {
    if (syncing) return syncing.then(function () { return Q.dirty ? flushQ(Q) : true; });
    if (!Q.dirty) return Promise.resolve(true);
    return startSync(Q);
  }

  function mergeActive(all, Q) {
    var P = Q;
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
    // kijkvoorkeuren (prefs): het geheel met de nieuwste updatedAt wint; een nieuwere lokale versie blijft en gaat mee
    var prefsNewer = false, localPrefs = P.base && P.base.prefs;
    if (base) {
      var sp = base.prefs;
      var lAt = localPrefs ? time(localPrefs.updatedAt) || 0 : 0;
      var sAt = sp && typeof sp === 'object' ? time(sp.updatedAt) || time(base.prefsUpdatedAt) || 0 : 0;
      if (localPrefs && lAt > sAt) { base = B.copy(base); base.prefs = localPrefs; prefsNewer = true; }
      P.base = base;
    } else if (localPrefs) prefsNewer = true;
    if (names.length) P.names = names;
    var srv = server ? normalize(server) : null;
    var merged = normalize(srv ? mergeState(P.st, srv) : P.st);
    // build 128: verhuisde items (remap) en categoriekeuzes van verwijderde servers opruimen / migreren
    merged = normalize(fixState(merged, true));
    var changed = stateKey(merged) !== stateKey(P.st);
    P.st = merged;
    var needUpload = prefsNewer || (!srv ? hasAny(merged) : stateKey(merged) !== stateKey(srv));
    P.dirty = false;
    persistP(P);
    if (changed && P === activeP()) B.emit('profileData');
    if (!needUpload) return true;
    var json = fixPin(toJSON(P.base, merged), P.id);
    var targets = P.names.length ? P.names : ['profile.' + P.id];
    return Promise.all(targets.map(function (n) { return A.putState(n, json); })).then(function (res) {
      if (res.some(function (x) { return x == null; })) { P.dirty = true; persistP(P); return false; }
      P.base = json; persistP(P);
      return true;
    });
  }
  function activeP() { return P; }

  // MARK: build 128: remap, categorieën per profiel en de andere profielen

  /** Sleutels van servers die niet (meer) in de sources staan (tombstone): alleen als de servers bekend zijn. */
  function goneServer(k) {
    if (!D.sources.length) return false;
    var bar = k.indexOf('|');
    return bar > 0 && !D.sourceById(k.slice(0, bar));
  }
  /** Oude keuzes (per server, "group:<naam>" in het android-blok) als "<server-id>|<naam>" met een oude tijd. */
  function legacyCategories() {
    var out = [];
    D.sources.forEach(function (src) {
      (D.customization(src).hidden || []).forEach(function (h) { if (String(h).indexOf('group:') === 0) out.push(src.id + '|' + String(h).slice(6)); });
    });
    return out;
  }
  /**
   * Remap-regels toepassen, categoriekeuzes van verwijderde servers als verwijdering markeren en (eenmalig per apparaat)
   * de oude categoriekeuzes migreren (oude tijd, zodat een nieuwere keuze per profiel voorgaat).
   */
  function fixState(st, migrate) {
    var AS = B.allservers;
    var out = AS ? AS.applyRemapState(st) : st;
    var hc = out.sets.hiddenCategories || {};
    var nh = null, now = Date.now();
    Object.keys(hc).forEach(function (k) {
      if (hc[k].on && goneServer(k)) { nh = nh || B.assign({}, hc); nh[k] = { at: Math.max(now, hc[k].at + 1), on: false }; }
    });
    if (migrate && !B.store.get('cats.migrated128', false) && D.sources.length) {
      legacyCategories().forEach(function (k) {
        var cur = (nh || hc)[k];
        var known = cur || Object.keys(nh || hc).some(function (x) { return lower(x) === lower(k); });
        if (!known) { nh = nh || B.assign({}, hc); nh[k] = { at: 0, on: true }; }
      });
    }
    if (!nh) return out;
    var sets = B.assign({}, out.sets); sets.hiddenCategories = nh;
    return { sets: sets, progress: out.progress, history: out.history };
  }
  /**
   * De andere profielen (alleen op de server, deze tv heeft hun gegevens niet lokaal) en de collecties: remap-regels
   * toepassen, categoriekeuzes van verwijderde servers opruimen en de oude keuzes migreren; uploaden wat verandert.
   */
  function otherProfiles(all, Q) {
    var jobs = [];
    var mig = !B.store.get('cats.migrated128', false) && D.sources.length > 0;
    var seen = {};
    Object.keys(all).forEach(function (name) {
      if (lower(name).indexOf('profile.') !== 0) return;
      var id = lower(name.slice(8));
      if (Q.id && id === lower(Q.id)) return;
      if (!D.profiles.some(function (p) { return lower(p.id) === id; })) return;
      var d = all[name] && all[name].data;
      if (!d || typeof d !== 'object') return;
      var st = normalize(parseState(d));
      var fixed = normalize(fixState(st, mig));
      // ook profielen die nog geen hiddenCategories hebben, krijgen de oude keuzes (één keer per profiel-id)
      if (stateKey(fixed) === stateKey(st)) return;
      if (seen[id]) return;
      seen[id] = true;
      jobs.push(A.putState(name, fixPin(toJSON(d, fixed), id)));
    });
    var AS = B.allservers;
    if (AS && D.collections && AS.applyRemapCollections(D.collections)) {
      save();
      jobs.push(A.putState('collections', D.collections));
    }
    return Promise.all(jobs).then(function (res) {
      if (mig && !res.some(function (x) { return x == null; })) B.store.set('cats.migrated128', true);
    }, B.noop);
  }

  /** Build 128: er is een remap-regel bij gekomen: meteen toepassen op dit profiel en daarna alles synchroniseren. */
  D.remapNow = function () {
    if (!P.st) return;
    var n = normalize(fixState(P.st, false));
    if (n !== P.st && stateKey(n) !== stateKey(P.st)) { P.st = n; changed(false); }
    else { clearTimeout(timer); setTimeout(function () { D.syncState(); }, 500); }
  };

  // MARK: build 140: categorieën per account (section `account`, docs/ACCOUNT.md → "Build 140")

  var X140 = B.b140;
  /** {marks: {"<server-id kleine letters>|<naam>": {at, on}}, base: laatst gelezen serverobject, dirty} */
  var ACC = B.store.get('accountCats', null) || { marks: {}, base: null, dirty: false };
  if (!ACC.marks || typeof ACC.marks !== 'object') ACC.marks = {};
  var accIdx = null, accTimer = null, accBusy = null;
  function persistAcc() { B.store.set('accountCats', ACC); accIdx = null; }
  function accIndex() { if (!accIdx) accIdx = X140.marks.index(X140.marks.alive(ACC.marks)); return accIdx; }
  function activeKids() { var p = B.app && B.app.profile; return !!(p && p.kids); }

  /** Verborgen categorienamen van server sid in de account-set ({naam: true}), ook als er een kinderprofiel actief is. */
  D.accountHiddenNames = function (sid) { return B.assign({}, accIndex()[lower(sid)] || {}); };
  /** Zelfde, als index (kleine letters server-id → {naam: true}); niet wijzigen. */
  D.accountHiddenIndex = function () { return accIndex(); };
  /** Voor het filteren: de account-set; een kinderprofiel: geen (daar bepaalt het kinderfilter alles). */
  D.hiddenCategoryNames = function (sid) { return activeKids() ? {} : D.accountHiddenNames(sid); };
  /** Categorieën ("<server-id>|<naam>") aan/uit voor het hele account. */
  D.setCategoriesHidden = function (keys, hidden) {
    var n = X140.marks.set(ACC.marks, keys, hidden, Date.now());
    if (X140.marks.equal(n, ACC.marks)) return;
    ACC.marks = n; ACC.dirty = true; persistAcc();
    B.emit('accountCats');
    clearTimeout(accTimer);
    accTimer = setTimeout(function () { uploadAccount(); }, 3000);
  };
  D.accountMarks = function () { return ACC.marks; };
  function uploadAccount() {
    if (!A.isSignedIn()) return Promise.resolve(false);
    if (accBusy) return accBusy.then(function () { return ACC.dirty ? uploadAccount() : true; });
    accBusy = A.getState(['account']).then(function (all) { return all ? mergeAccount(all.account) : false; })
      .catch(function () { return false; }).then(function (r) { accBusy = null; return r; });
    return accBusy;
  }
  /** Categoriekeuzes van verwijderde servers (tombstone) als verwijdering. */
  function dropGoneServers(m) {
    var out = m, now = Date.now();
    Object.keys(m).forEach(function (k) {
      if (m[k].on && goneServer(k)) { if (out === m) out = B.assign({}, m); out[k] = { at: X140.stampAfter(now, m[k].at), on: false }; }
    });
    return out;
  }
  /**
   * Samenvoegen zoals myList (per sleutel wint de nieuwste `at`), lokaal toepassen en uploaden als het resultaat afwijkt.
   * Onbekende velden van de server blijven staan.
   */
  function mergeAccount(sec) {
    var data = sec && sec.data && typeof sec.data === 'object' && !Array.isArray(sec.data) ? sec.data : null;
    var removed = data && data.removed && typeof data.removed === 'object' ? data.removed.hiddenCategories : null;
    var srv = data ? X140.marks.normalizeAll(parseSetField(data.hiddenCategories, removed)) : {};
    var now = Date.now();
    var merged = dropGoneServers(X140.marks.prune(X140.marks.merge(ACC.marks, srv), now));
    var changed = !X140.marks.equal(merged, ACC.marks);
    ACC.marks = merged; ACC.dirty = false;
    if (data) ACC.base = data;
    persistAcc();
    if (changed) B.emit('accountCats');
    if (!data && !Object.keys(merged).length) return Promise.resolve(true);
    if (data && X140.marks.equal(merged, X140.marks.prune(srv, now))) return Promise.resolve(true);
    var json = accountJSON(data || ACC.base, merged);
    return A.putState('account', json).then(function (at) {
      if (at == null) { ACC.dirty = true; persistAcc(); return false; }
      ACC.base = json; persistAcc();
      return true;
    }, function () { ACC.dirty = true; persistAcc(); return false; });
  }
  function accountJSON(base, m) {
    var out = B.copy(base || {});
    out.hiddenCategories = Object.keys(m).filter(function (k) { return m[k].on; })
      .sort(function (x, y) { return (m[y].at - m[x].at) || (x < y ? -1 : x > y ? 1 : 0); })
      .map(function (k) { return { id: k, at: B.iso(m[k].at) }; });
    var rem = (base && base.removed && typeof base.removed === 'object') ? B.copy(base.removed) : {};
    rem.hiddenCategories = {};
    Object.keys(m).filter(function (k) { return !m[k].on; }).sort().forEach(function (k) { rem.hiddenCategories[k] = B.iso(m[k].at); });
    out.removed = rem;
    out.updatedAt = B.iso();
    return out;
  }
  /**
   * Migratie (eenmalig per apparaat): unie van `hiddenCategories` uit profile.<id> van alle niet-kinderprofielen (plus de
   * oude keuzes per server van vóór build 128) met at = 0, zodat een keuze in `account` altijd wint. Per-profiel-data blijft.
   */
  function migrateAccount(all) {
    if (B.store.get('cats.migrated140', false) || !D.profiles.length) return;
    var keys = [];
    Object.keys(all || {}).forEach(function (name) {
      if (lower(name).indexOf('profile.') !== 0) return;
      var prof = D.profileById(name.slice(8));
      if (!prof || prof.kids) return;
      var d = all[name] && all[name].data;
      if (!d || typeof d !== 'object') return;
      var hc = parseSetField(d.hiddenCategories, d.removed && d.removed.hiddenCategories);
      Object.keys(hc).forEach(function (k) { if (hc[k].on) keys.push(k); });
    });
    var mine = P.st && P.st.sets.hiddenCategories, me = P.id && D.profileById(P.id);
    if (mine && me && !me.kids) Object.keys(mine).forEach(function (k) { if (mine[k].on) keys.push(k); });
    if (!B.store.get('cats.migrated128', false) && D.sources.length) keys = keys.concat(legacyCategories());
    var n = X140.marks.migrate(ACC.marks, keys);
    if (!X140.marks.equal(n, ACC.marks)) { ACC.marks = n; persistAcc(); B.emit('accountCats'); }
    B.store.set('cats.migrated140', true);
  }
  D._mergeAccount = mergeAccount; D._migrateAccount = migrateAccount; D._accountJSON = accountJSON;
  // server verwijderd (tombstone): zijn categorieën meteen weg uit de account-set (als verwijdering, dus gesynct)
  B.on('sources', function () {
    var n = dropGoneServers(ACC.marks);
    if (n === ACC.marks) return;
    ACC.marks = n; ACC.dirty = true; persistAcc();
    B.emit('accountCats');
    clearTimeout(accTimer);
    accTimer = setTimeout(function () { uploadAccount(); }, 3000);
  });

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
    var Q = P;
    timer = setTimeout(function () { lastProgressUpload = Date.now(); flushQ(Q); }, wait);
  }
  /**
   * Meteen versturen (stoppen met afspelen, app naar de achtergrond, profiel wisselen).
   * build 97: altijd voor het profiel dat nu geladen is, ook als er al een sync loopt (dan daarna nog een keer).
   */
  D.flush = function () {
    var Q = P;
    if (!Q.dirty && !syncing) return Promise.resolve();
    if (Q.dirty) { clearTimeout(timer); lastProgressUpload = Date.now(); }
    return flushQ(Q);
  };

  // lezen
  function liveKeys(m) {
    return Object.keys(m || {}).filter(function (k) { return m[k].on; }).sort(function (a, b) { return m[b].at - m[a].at; });
  }
  D.key = function (playlist, item) { return playlist + '|' + item; };
  D.episodeKey = function (series, season, ep) { return 'ep:' + series + '|' + season + '-' + ep; };
  // Server-id in sleutels ("<server>|<item>") hoofdletterongevoelig: iOS schrijft hem met hoofdletters, web/Android/
  // Samsung TV soms met kleine letters (server daar aangemaakt). Opgeslagen sleutels blijven zoals ze zijn (oudere apps);
  // bij het lezen krijgt de server-id de schrijfwijze van de bekende bron, zodat `k.split('|')[0] === src` overal klopt.
  var UUIDISH = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  function knownSource(id) {
    var l = lower(id);
    for (var i = 0; i < D.sources.length; i++) if (lower(D.sources[i].id) === l) return D.sources[i].id;
    return null;
  }
  /** Sleutel met de server-id zoals de bron hem heeft (anders ongewijzigd). */
  function canonKey(k) {
    var bar = k.indexOf('|');
    if (bar <= 0) return k;
    var s = knownSource(k.slice(0, bar));
    return s == null ? k : s + k.slice(bar);
  }
  /** Vergelijkingssleutel: server-id (bekende bron of UUID) in kleine letters, item-id exact. */
  function normKey(k) {
    var bar = k.indexOf('|');
    if (bar <= 0) return k;
    var p = k.slice(0, bar);
    return (knownSource(p) != null || UUIDISH.test(p)) ? lower(p) + k.slice(bar) : k;
  }
  /** Alle opgeslagen schrijfwijzen van sleutel k in m. */
  function variants(m, k) {
    var n = normKey(k);
    return Object.keys(m || {}).filter(function (x) { return x === k || normKey(x) === n; });
  }
  D.inSet = function (field, k) {
    var m = P.st && P.st.sets[field];
    if (!m) return false;
    if (m[k] && m[k].on) return true;
    return variants(m, k).some(function (x) { return m[x].on; });
  };
  D.setList = function (field) {
    if (!P.st) return [];
    var seen = {};
    return liveKeys(P.st.sets[field]).map(canonKey).filter(function (k) { var n = normKey(k); if (seen[n]) return false; seen[n] = true; return true; });
  };
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
    var seen = {};
    // dezelfde titel uit iOS (hoofdletters) én web/Samsung TV (kleine letters): één keer, de nieuwste
    return Object.keys(m).map(function (k) { return m[k].v; }).filter(Boolean).sort(function (a, b) { return b.date - a.date; })
      .filter(function (e) { var n = normKey(historyId(e)); if (seen[n]) return false; seen[n] = true; return true; });
  };
  D.isWatched = function (k) { return D.inSet('watched', k); };

  // schrijven
  D.toggleSet = function (field, k, on) {
    if (!P.st) return;
    var cur = D.inSet(field, k);
    if (on == null) on = !cur;
    if (on === cur) return;
    var now = Date.now(), m = P.st.sets[field];
    // build 140: altijd na de laatst bekende tijd van die sleutel (ook een verwijdering van een ander apparaat met een
    // voorlopende klok); anders won die verwijdering bij het samenvoegen en verdween een net toegevoegd item weer
    var prev = null;
    variants(m, k).concat([k]).forEach(function (x) { if (m[x] && (prev == null || m[x].at > prev)) prev = m[x].at; });
    if (on) m[k] = { at: B.b140.stampAfter(now, prev), on: true };
    // uit: alle schrijfwijzen (anders komt het via de andere terug)
    else variants(m, k).concat([k]).forEach(function (x) { if (x === k || (m[x] && m[x].on)) m[x] = { at: B.b140.stampAfter(now, m[x] ? m[x].at : null), on: false }; });
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

  /**
   * Kijkvoorkeuren van het actieve profiel (profile.<id>.prefs, zoals Android): autoSubtitles, audioLanguage,
   * streamSubtitleLanguage (taal van ondertitels in de stream) en subtitleLanguage (OpenSubtitles). null = niet ingesteld.
   */
  D.profilePref = function (key) {
    var pr = P.base && P.base.prefs;
    if (!pr || typeof pr !== 'object' || !B.has(pr, key) || pr[key] == null) return null;
    return String(pr[key]);
  };
  D.setProfilePref = function (key, value) {
    if (!P.id) return;
    P.base = P.base ? B.copy(P.base) : {};
    var pr = B.assign({}, (P.base.prefs && typeof P.base.prefs === 'object') ? P.base.prefs : {});
    pr[key] = value;
    pr.updatedAt = B.iso();
    P.base.prefs = pr;
    changed(false);
  };
  D.addHistory = function (e) {
    if (!P.st) return;
    var id = historyId(e);
    P.st.history[id] = { at: e.date, v: e };
    changed(false);
  };
  /**
   * Build 129 (scherm Geschiedenis): één item uit de geschiedenis van het actieve profiel, met alle schrijfwijzen van
   * de sleutel (iOS hoofdletters, web/Samsung TV kleine letters); als verwijdering, zodat het ook elders weggaat.
   */
  D.removeHistory = function (e) {
    if (!P.st || !e) return;
    var n = normKey(historyId(e)), now = Date.now(), any = false;
    Object.keys(P.st.history).forEach(function (k) {
      if (normKey(k) !== n || !P.st.history[k].v) return;
      P.st.history[k] = { at: Math.max(now, (P.st.history[k].at || 0) + 1), v: null };
      any = true;
    });
    if (any) changed(false);
  };
  /** Build 129: hele geschiedenis van het actieve profiel wissen (over alle servers). */
  D.clearHistory = function () {
    if (!P.st) return;
    var now = Date.now(), any = false;
    Object.keys(P.st.history).forEach(function (k) {
      if (!P.st.history[k].v) return;
      P.st.history[k] = { at: Math.max(now, (P.st.history[k].at || 0) + 1), v: null };
      any = true;
    });
    if (any) changed(false);
  };

  // MARK: collecties (section `collections`, version 2: per profiel)

  /**
   * Collecties van een profiel (zonder tombstones), op volgorde. Elk profiel ziet alleen zijn eigen collecties (build 74):
   * - ouder formaat (nog niet gemigreerd): de oude lijst (voor het hele account) alleen voor het hoofdprofiel;
   * - een subprofiel ziet geen collectie waarvan de id ook bij een ander profiel staat: dat is een kopie die de
   *   migratie tot build 73 naar elk profiel zette (nieuwe collecties krijgen altijd een eigen id).
   */
  D.collectionsFor = function (profileId) {
    var c = D.collections;
    if (!c || typeof c !== 'object' || !profileId) return [];
    var main = D.mainProfile();
    var isMain = !!main && lower(main.id) === lower(profileId);
    var list = null, hide = {};
    // gemigreerd (contract): `version >= 2`, of `migrated: true` (vroege build 54), of er is al een object `profiles`
    var migrated = (B.int(c.version) || 0) >= 2 || c.migrated === true || (c.profiles && typeof c.profiles === 'object' && !Array.isArray(c.profiles));
    if (migrated && c.profiles && typeof c.profiles === 'object') {
      list = [];
      Object.keys(c.profiles).forEach(function (k) {
        var items = (c.profiles[k] && c.profiles[k].items) || [];
        if (lower(k) === lower(profileId)) { list = list.concat(items); return; }
        if (!isMain && Array.isArray(items)) items.forEach(function (x) { if (x && x.id) hide[lower(x.id)] = true; });
      });
    } else list = isMain && Array.isArray(c.items) ? c.items : [];
    var byId = {};
    list.forEach(function (x) {
      if (!x || !x.id || hide[lower(x.id)]) return;
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
    function r1(v) { return v == null ? null : Math.round(v * 10) / 10; }
    addPending('intro', sourceId + ':' + seriesId, { introStart: r1(marker.introStart), introEnd: r1(marker.introEnd), creditsStart: r1(marker.creditsStart) });
    return uploadSettings();
  };

  /** Startscherm-indeling van het actieve profiel (homeLayout in profile.<id>), of null. */
  D.homeLayout = function () {
    var h = P.base && P.base.homeLayout;
    return h && Array.isArray(h.rows) ? h.rows : null;
  };

  // test- en debug-hulp
  D._parseState = parseState; D._toJSON = toJSON; D._merge = mergeState; D._normalize = normalize;
})();
