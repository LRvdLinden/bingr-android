/*
 * Bibliotheek van de actieve server: laden (M3U, Xtream, Stalker), cachen in IndexedDB, tv-gids,
 * en filteren zoals Playlists.apply op Android: aanpassingen (hernoemd, verborgen, samengevoegd, volgorde),
 * kinderslot (vergrendelde groepen, 18+ automatisch) en de leeftijdsgrens / het kinderprofiel.
 */
(function () {
  'use strict';
  var B = window.B;
  var D = B.data;
  var P = B.providers;
  var L = function () { return B.L.apply(null, arguments); };
  var Lib = B.lib = {};

  Lib.source = null;
  Lib.raw = { channels: [], movies: [], series: [], epg: {} };
  Lib.lib = Lib.raw;
  Lib.loading = false;
  Lib.error = null;
  Lib.episodeCache = {};
  var gen = 0;

  // MARK: kinderslot en leeftijd (= ParentalControl.kt)

  var PC = B.parental = {};
  var adultWords = ['adult', 'xxx', '18+', '+18', 'porn', 'erotic', 'erotiek', 'for adults'];
  var kidsWords = ['kid', 'kinder', 'child', 'junior', 'jeugd', 'cartoon', 'animat', 'disney', 'nick', 'pixar', 'dreamworks',
    'family', 'familie', 'enfant', 'jeunesse', 'niños', 'infantil', 'bambini', 'ragazzi', 'çocuk', 'cocuk', 'dzieci', 'bajki',
    'kika', 'zapp', 'baby', 'boomerang', 'cbeebies', 'pbs kids', 'toon', 'أطفال', '儿童', '动画'];
  var notKidsWords = ['anime', 'hentai', 'horror', 'thriller', 'family guy'];
  var kidsChannelWords = ['kids', 'kinder', 'junior', 'cartoon', 'disney', 'nick', 'boomerang', 'cbeebies', 'baby', 'zapp', 'kika',
    'toon', 'jeugd', 'jeunesse', 'pbs kids', 'duck tv', 'jimjam', 'minimax', 'pop kids'];
  function anyIn(s, words) { for (var i = 0; i < words.length; i++) if (s.indexOf(words[i]) >= 0) return true; return false; }
  PC.isAdultGroup = function (g) { return anyIn(String(g || '').toLowerCase(), adultWords); };
  PC.isKidsGroup = function (g) {
    var l = String(g || '').toLowerCase();
    return !PC.isAdultGroup(g) && !anyIn(l, notKidsWords) && anyIn(l, kidsWords);
  };
  PC.channelAllowedFor = function (name, group, limit, strict) {
    if (limit == null) return true;
    if (PC.isAdultGroup(group) || PC.isAdultGroup(name)) return false;
    if (!strict) return true;
    if (PC.isKidsGroup(group)) return true;
    var n = String(name || '').toLowerCase();
    return !anyIn(n, notKidsWords) && anyIn(n, kidsChannelWords);
  };
  var ageTable = {
    'G': 0, 'TV-Y': 0, 'TV-G': 0, 'AL': 0, 'U': 0, 'ALL': 0, 'APPROVED': 0, 'PASSED': 0, 'TV-Y7': 7, 'TV-Y7-FV': 7, 'PG': 9,
    'TV-PG': 10, 'UC': 0, 'PG-13': 13, 'TV-14': 14, '12A': 12, 'M': 15, 'MA15+': 15, 'MA 15+': 15, 'R': 17, 'TV-MA': 17,
    'NC-17': 18, 'X': 18, 'R18+': 18, 'R 18+': 18, '18': 18, 'BTL': 0, 'TP': 0, 'L': 0, 'ATP': 0, 'KT': 0
  };
  PC.minimumAge = function (rated) {
    if (rated == null) return null;
    var r = String(rated).toUpperCase().trim();
    if (!r || r === 'N/A' || r === 'NR' || r === 'NOT RATED' || r === 'UNRATED' || r === 'NOT_RATED') return null;
    if (B.has(ageTable, r)) return ageTable[r];
    var m = /\d{1,2}/.exec(r);
    if (!m) return null;
    var n = parseInt(m[0], 10);
    return n <= 21 ? n : null;
  };
  PC.isAdultGenre = function (genre) {
    if (!genre) return false;
    var parts = String(genre).toLowerCase().split(',').map(function (x) { return x.trim(); });
    return ['horror', 'thriller', 'crime', 'war'].some(function (w) { return parts.indexOf(w) >= 0; });
  };
  PC.isKidsGenre = function (genre) {
    var g = String(genre || '').toLowerCase();
    return g.indexOf('animation') >= 0 || g.indexOf('family') >= 0;
  };
  /**
   * Mag dit profiel dit zien? (= allowedFor op Android)
   * geen grens → alles; volwassenengroep → nooit; bekende leeftijd → alleen als ≤ grens;
   * onbekend en gewoon profiel → ja; onbekend en streng (kinderprofiel / t/m 12) → alleen kinderinhoud.
   */
  PC.allowedFor = function (rated, group, limit, genre, strict) {
    if (limit == null) return true;
    if (PC.isAdultGroup(group)) return false;
    var age = PC.minimumAge(rated);
    if (age != null) return age <= limit;
    if (!strict) return true;
    if (PC.isAdultGenre(genre)) return false;
    if (PC.isKidsGenre(genre)) return true;
    return PC.isKidsGroup(group);
  };
  /** Ontgrendeld met de pincode van het hoofdprofiel: 30 minuten alles zichtbaar. */
  PC.unlockedUntil = 0;
  PC.isUnlocked = function () { return PC.unlockedUntil > Date.now(); };
  PC.unlock = function () {
    PC.unlockedUntil = Date.now() + 30 * 60000;
    Lib.applyFilters();
    setTimeout(function () { if (!PC.isUnlocked()) Lib.applyFilters(); }, 30 * 60000 + 500);
  };
  PC.lock = function () { PC.unlockedUntil = 0; Lib.applyFilters(); };

  // MARK: filteren (= Playlists.apply)

  function context() {
    var p = B.app && B.app.profile;
    var src = Lib.source;
    return {
      custom: D.customization(src), playlist: src ? src.id : '',
      ageLimit: D.ageLimit(p), strict: D.strict(p),
      parentalEnabled: !!B.bool(D.shared('parentalControl', false)),
      autoLockAdult: B.bool(D.shared('parentalAutoLockAdult', true)) !== false,
      lockedGroups: D.lockedGroups(src), unlocked: PC.isUnlocked()
    };
  }
  Lib.apply = function (raw, ctx) {
    var c = ctx.custom;
    var hidden = {}; (c.hidden || []).forEach(function (h) { hidden[h] = true; });
    var locked = {}; (ctx.lockedGroups || []).forEach(function (g) { locked[g] = true; });
    var ageLimit = ctx.unlocked ? null : ctx.ageLimit;
    function isLocked(g) {
      if (!ctx.parentalEnabled || ctx.unlocked) return false;
      if (locked[ctx.playlist + '|' + g]) return true;
      return ctx.autoLockAdult && PC.isAdultGroup(g);
    }
    function group(g) {
      var m = c.merged[g] || g;
      return c.renamed['group:' + m] || m;
    }
    var groupOK = {};
    function visible(id, g) {
      if (hidden[id]) return false;
      if (B.has(groupOK, g)) return groupOK[g];
      var m = c.merged[g] || g;
      var ok = !hidden['group:' + g] && !hidden['group:' + m] && !isLocked(g) && !isLocked(m);
      groupOK[g] = ok;
      return ok;
    }
    function ageOK(x) { return ageLimit == null || PC.allowedFor(x.rated, x.group, ageLimit, x.genre, ctx.strict); }
    var channels = raw.channels.filter(function (ch) { return visible(ch.id, ch.group); }).map(function (ch) {
      return B.assign({}, ch, { group: group(ch.group), name: c.renamed[ch.id] || ch.name, rawGroup: ch.group });
    });
    if (ageLimit != null) channels = channels.filter(function (ch) { return PC.channelAllowedFor(ch.name, ch.rawGroup, ageLimit, ctx.strict); });
    var movies = raw.movies.filter(function (m) { return visible(m.id, m.group) && ageOK(m); }).map(function (m) {
      return B.assign({}, m, { group: group(m.group), title: c.renamed[m.id] || m.title });
    });
    var series = raw.series.filter(function (s) { return visible(s.id, s.group) && ageOK(s); }).map(function (s) {
      return B.assign({}, s, { group: group(s.group), title: c.renamed[s.id] || s.title });
    });
    if ((c.groupOrder && c.groupOrder.length) || Object.keys(c.channelOrder || {}).length) {
      var gIndex = {}; c.groupOrder.forEach(function (g, i) { if (!B.has(gIndex, g)) gIndex[g] = i; });
      var natural = {}, n = 0;
      channels.forEach(function (ch) { if (!B.has(natural, ch.group)) natural[ch.group] = 10000 + n++; });
      var cIndex = {};
      Object.keys(c.channelOrder).forEach(function (g) { (c.channelOrder[g] || []).forEach(function (id, i) { cIndex[id] = i; }); });
      var original = {};
      channels.forEach(function (ch, i) { if (!B.has(original, ch.id)) original[ch.id] = 100000 + i; });
      channels.sort(function (a, b) {
        var ga = B.has(gIndex, a.group) ? gIndex[a.group] : natural[a.group];
        var gb = B.has(gIndex, b.group) ? gIndex[b.group] : natural[b.group];
        if (ga !== gb) return ga - gb;
        var ia = B.has(cIndex, a.id) ? cIndex[a.id] : original[a.id];
        var ib = B.has(cIndex, b.id) ? cIndex[b.id] : original[b.id];
        return ia - ib;
      });
    }
    return { channels: channels, movies: movies, series: series, epg: raw.epg || {} };
  };
  Lib.applyFilters = function () {
    Lib.lib = Lib.apply(Lib.raw, context());
    buildIndexes();
    B.emit('library');
  };

  function groupsOf(list) {
    var seen = {}, out = [];
    list.forEach(function (x) { if (!seen[x.group]) { seen[x.group] = true; out.push(x.group); } });
    return out;
  }
  function buildIndexes() {
    var l = Lib.lib;
    l.channelGroups = groupsOf(l.channels);
    l.movieGroups = groupsOf(l.movies);
    l.seriesGroups = groupsOf(l.series);
    l.byId = {};
    l.channels.forEach(function (x) { l.byId[x.id] = { type: 'channel', item: x }; });
    l.movies.forEach(function (x) { l.byId[x.id] = { type: 'movie', item: x }; });
    l.series.forEach(function (x) { l.byId[x.id] = { type: 'series', item: x }; });
  }

  // MARK: tv-gids

  Lib.epgFor = function (ch) {
    var e = Lib.raw.epg || {};
    var k = String(ch.epgID || ch.id).toLowerCase();
    return e[k] || [];
  };
  Lib.nowNext = function (ch) {
    var list = Lib.epgFor(ch), now = Date.now();
    for (var i = 0; i < list.length; i++) {
      if (list[i].start <= now && now < list[i].end) return { now: list[i], next: list[i + 1] || null };
      if (list[i].start > now) return { now: null, next: list[i] };
    }
    return { now: null, next: null };
  };

  // MARK: laden en cachen

  var LIB_TTL = 12 * 3600000, EPG_TTL = 4 * 3600000;
  function wantedEpg(lib) {
    var w = {};
    lib.channels.forEach(function (c) { if (c.epgID) w[String(c.epgID).toLowerCase()] = true; });
    return w;
  }
  function fetchLibrary(src) {
    var kind = src.kind;
    var pw = D.secret(src, 'password');
    if (kind === 'M3U / M3U8') {
      var u = P.urlValue(src.m3uURL);
      if (!u) return Promise.reject(new Error(L('Invalid URL')));
      return B.getText(u, null, 180000).then(function (text) {
        var lib = P.m3uLibrary(text, src.id);
        lib.epgURL = P.urlValue(src.epgURL) || P.m3uEpgURL(text);
        return lib;
      });
    }
    if (kind === 'Xtream Codes') {
      var x = new P.Xtream(src, pw);
      return x.authenticate().then(function () { return x.library(); }).then(function (lib) {
        lib.epgURL = P.urlValue(src.epgURL) || x.xmltvURL();
        return lib;
      });
    }
    if (kind === 'Stalker / MAC-portal') {
      var st = new P.Stalker(src);
      Lib.stalker = st;
      return st.connect().then(function () { return st.library(); }).then(function (lib) {
        lib.epgURL = P.urlValue(src.epgURL);
        return lib;
      });
    }
    return Promise.reject(new Error(L('This kind of server is not supported on Samsung TV yet.')));
  }
  function loadEpg(src, lib, force) {
    if (!lib.epgURL) return Promise.resolve();
    return B.idb.get('epg:' + src.id).then(function (c) {
      if (c && !force && Date.now() - c.at < EPG_TTL) return c.epg;
      return P.loadXMLTV(lib.epgURL, wantedEpg(lib)).then(function (epg) {
        if (Object.keys(epg).length) B.idb.set('epg:' + src.id, { at: Date.now(), epg: epg });
        return Object.keys(epg).length ? epg : (c ? c.epg : {});
      });
    }).then(function (epg) {
      if (Lib.source && Lib.source.id === src.id && epg) { Lib.raw.epg = epg; Lib.lib.epg = epg; B.emit('epg'); }
    }).catch(B.noop);
  }

  /** Server kiezen en laden: eerst de cache (meteen zichtbaar), daarna verversen als hij oud is. */
  Lib.select = function (src, force) {
    var my = ++gen;
    Lib.source = src;
    Lib.error = null;
    Lib.episodeCache = {};
    Lib.stalker = null;
    if (!src) { Lib.raw = { channels: [], movies: [], series: [], epg: {} }; Lib.applyFilters(); return Promise.resolve(); }
    Lib.loading = true;
    B.emit('loading');
    return B.idb.get('lib:' + src.id).then(function (cached) {
      if (my !== gen) return;
      if (cached && cached.lib && !force) {
        Lib.raw = cached.lib; Lib.raw.epg = Lib.raw.epg || {};
        Lib.loading = false;
        Lib.applyFilters();
        loadEpg(src, Lib.raw, false);
        if (Date.now() - cached.at > LIB_TTL) Lib.refresh(src, my);
        return;
      }
      return Lib.refresh(src, my);
    });
  };
  Lib.refresh = function (src, my) {
    my = my || gen;
    Lib.loading = true;
    B.emit('loading');
    return fetchLibrary(src).then(function (lib) {
      lib.epg = {};
      B.idb.set('lib:' + src.id, { at: Date.now(), lib: lib });
      // alleen de zenders apart, voor de reservebron op andere servers
      B.idb.set('chan:' + src.id, lib.channels.map(function (c) { return { name: c.name, epgID: c.epgID, streamURL: c.streamURL, stalkerCmd: c.stalkerCmd || null }; }));
      if (my !== gen) return;
      var epg = Lib.raw && Lib.source && Lib.source.id === src.id ? Lib.raw.epg : {};
      lib.epg = epg || {};
      Lib.raw = lib;
      Lib.loading = false;
      Lib.applyFilters();
      return loadEpg(src, lib, true);
    }, function (e) {
      if (my !== gen) return;
      Lib.loading = false;
      if (!Lib.raw.channels.length && !Lib.raw.movies.length && !Lib.raw.series.length) Lib.error = (e && e.message) || L('Something went wrong. Please try again.');
      B.emit('library');
    });
  };

  /** Afleveringen van een serie (M3U: al in de lijst; Xtream: get_series_info). */
  Lib.episodes = function (s) {
    if (s.episodes && s.episodes.length) return Promise.resolve({ episodes: s.episodes, info: null });
    if (Lib.episodeCache[s.id]) return Promise.resolve(Lib.episodeCache[s.id]);
    var src = Lib.source;
    if (!src || src.kind !== 'Xtream Codes') return Promise.resolve({ episodes: [], info: null });
    var x = new P.Xtream(src, D.secret(src, 'password'));
    return x.episodes(s).then(function (r) {
      if (r.episodes.length) Lib.episodeCache[s.id] = r;
      return r;
    }, function () { return { episodes: [], info: null }; });
  };
  Lib.movieInfo = function (m) {
    var src = Lib.source;
    if (!src || src.kind !== 'Xtream Codes' || m.xtreamVodID == null) return Promise.resolve(null);
    return new P.Xtream(src, D.secret(src, 'password')).vodInfo(m.xtreamVodID).catch(function () { return null; });
  };

  /** Afspeel-URL (MAC-portal: eerst een link aanmaken). */
  Lib.resolveURL = function (item, live) {
    if (item.stalkerCmd && Lib.source && Lib.source.kind === 'Stalker / MAC-portal') {
      var st = Lib.stalker || new P.Stalker(Lib.source);
      Lib.stalker = st;
      return st.createLink(item.stalkerCmd, live).then(function (u) { return u || item.streamURL; }, function () { return item.streamURL; });
    }
    return Promise.resolve(item.streamURL);
  };

  // MARK: reservebron (= BackupSources.kt): dezelfde zender op een andere server

  var bPrefix = /^\s*[a-z]{2,3}\s*[:|]\s*|^\s*[\[(|]\s*[a-z]{2,3}\s*[\])|]\s*|^\s*[a-z]{2}\s+-\s+/;
  var bQuality = /\b(?:hd|fhd|uhd|4k|sd|hevc|h\.?265|h\.?264|1080[pi]?|720p|2160p|50fps|60fps|backup|raw)\b/g;
  Lib.normalizeName = function (name) {
    var s = B.fold(name).trim();
    for (var i = 0; i < 2; i++) s = s.replace(bPrefix, '');
    s = s.replace(bQuality, ' ').replace(/ᴴᴰ/g, ' ').replace(/ʰᵈ/g, ' ');
    return s.replace(/[^\w+]|_/g, '');
  };
  Lib.backupChannels = function (ch) {
    var cur = Lib.source ? Lib.source.id : null;
    var others = D.sources.filter(function (s) { return s.id !== cur && D.supportedKinds.indexOf(s.kind) >= 0; });
    var epg = ch.epgID ? String(ch.epgID).trim().toLowerCase() : null;
    var norm = Lib.normalizeName(ch.name);
    if (!epg && norm.length < 2) return Promise.resolve([]);
    return Promise.all(others.map(function (src) {
      return B.idb.get('chan:' + src.id).then(function (list) {
        if (!list) return null;
        var hit = null, byEpg = false;
        if (epg) for (var i = 0; i < list.length; i++) if (list[i].epgID && String(list[i].epgID).trim().toLowerCase() === epg) { hit = list[i]; byEpg = true; break; }
        if (!hit) for (var j = 0; j < list.length; j++) if (Lib.normalizeName(list[j].name) === norm) { hit = list[j]; break; }
        if (!hit || (!hit.streamURL && !hit.stalkerCmd)) return null;
        return { source: src, name: hit.name, url: hit.streamURL, stalkerCmd: hit.stalkerCmd, byEpg: byEpg };
      });
    })).then(function (r) { return r.filter(Boolean).sort(function (a, b) { return (b.byEpg ? 1 : 0) - (a.byEpg ? 1 : 0); }); });
  };
  Lib.resolveBackup = function (h) {
    if (h.stalkerCmd && h.source.kind === 'Stalker / MAC-portal') {
      return new P.Stalker(h.source).createLink(h.stalkerCmd, true).then(function (u) { return u || h.url; }, function () { return h.url; });
    }
    return Promise.resolve(h.url);
  };

  /** Andere servers op de achtergrond laden (voor de reservebron), één tegelijk. */
  Lib.warmOthers = function () {
    var cur = Lib.source ? Lib.source.id : null;
    var list = D.sources.filter(function (s) { return s.id !== cur && D.supportedKinds.indexOf(s.kind) >= 0; });
    var chain = Promise.resolve();
    list.forEach(function (src) {
      chain = chain.then(function () {
        return B.idb.get('chan:' + src.id).then(function (c) {
          if (c) return;
          return fetchLibrary(src).then(function (lib) {
            B.idb.set('chan:' + src.id, lib.channels.map(function (x) { return { name: x.name, epgID: x.epgID, streamURL: x.streamURL, stalkerCmd: x.stalkerCmd || null }; }));
          }, B.noop);
        });
      });
    });
    return chain;
  };

  // MARK: zoeken en collecties

  Lib.search = function (q, limit) {
    var f = B.fold(q).trim();
    if (!f) return { channels: [], movies: [], series: [] };
    limit = limit || 40;
    function hit(list, field) {
      var starts = [], contains = [];
      for (var i = 0; i < list.length && starts.length < limit; i++) {
        var t = B.fold(list[i][field]);
        var idx = t.indexOf(f);
        if (idx === 0) starts.push(list[i]); else if (idx > 0 && contains.length < limit) contains.push(list[i]);
      }
      return starts.concat(contains).slice(0, limit);
    }
    return { channels: hit(Lib.lib.channels, 'name'), movies: hit(Lib.lib.movies, 'title'), series: hit(Lib.lib.series, 'title') };
  };

  /** Inhoud van een collectie (handmatig: lijst met id's; slim: zoekwoorden, soorten, groep, server). */
  Lib.collectionItems = function (c, limit) {
    limit = limit || 60;
    var lib = Lib.lib, out = [];
    var src = Lib.source ? String(Lib.source.id).toLowerCase() : '';
    if (c.sourceID && String(c.sourceID).toLowerCase() !== src) return [];
    if (c.kind === 'smart') {
      var words = String(c.query || '').split(',').map(function (w) { return B.fold(w).trim(); }).filter(Boolean);
      if (!words.length) return [];
      var types = Array.isArray(c.types) && c.types.length ? c.types : ['channel', 'movie', 'series'];
      var pools = [['channel', lib.channels, 'name'], ['movie', lib.movies, 'title'], ['series', lib.series, 'title']];
      pools.forEach(function (p) {
        if (types.indexOf(p[0]) < 0) return;
        for (var i = 0; i < p[1].length && out.length < limit; i++) {
          var x = p[1][i];
          if (c.group && x.group !== c.group) continue;
          var t = B.fold(x[p[2]]);
          for (var w = 0; w < words.length; w++) if (t.indexOf(words[w]) >= 0) { out.push({ type: p[0], item: x }); break; }
        }
      });
      return out;
    }
    (c.items || []).forEach(function (it) {
      var hit = it && lib.byId[it.id];
      if (hit && out.length < limit) out.push(hit);
    });
    return out;
  };

  // MARK: Top 10 (zoals localTopMovies op Android: best beoordeeld, anders nieuwste)

  Lib.top10 = function (items) {
    var withPoster = items.filter(function (x) { return x.poster; });
    var pool = withPoster.length ? withPoster : items;
    var rated = pool.filter(function (x) { return (x.rating || 0) > 0; });
    if (rated.length >= 10) return rated.slice().sort(function (a, b) { return (b.rating || 0) - (a.rating || 0); }).slice(0, 10);
    var recent = pool.filter(function (x) { return x.addedAt; });
    if (recent.length >= 10) return recent.slice().sort(function (a, b) { return b.addedAt - a.addedAt; }).slice(0, 10);
    return pool.slice(0, 10);
  };
  Lib.recent = function (items, n) {
    return items.filter(function (x) { return x.addedAt; }).sort(function (a, b) { return b.addedAt - a.addedAt; }).slice(0, n || 30);
  };
})();
