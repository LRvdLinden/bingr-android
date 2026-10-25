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
  /** Build 130: tijd van de cache per server-id (lib:<id>.at), zodat de achtergrond weet of hij opnieuw moet inlezen. */
  Lib.stamps = {};
  /** Build 130: bezig met "alles vernieuwen" ({active: true zolang de actieve server aan de beurt is}), anders null. */
  Lib.refreshingAll = null;
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
  function lower(s) { return String(s == null ? '' : s).toLowerCase(); }
  // build 128: categoriekeuzes per profiel kunnen via de sync veranderen (applyFilters doet niets als er niets anders is)
  B.on('profileData', function () { if (Lib.raw) Lib.applyFilters(); });
  // build 140: categorieën aan/uit (account-set): pas filteren als er even niet getikt wordt (niet bij elke tik)
  var catTimer = null;
  B.on('accountCats', function () {
    clearTimeout(catTimer);
    catTimer = setTimeout(function () { if (Lib.raw) Lib.applyFilters(); reloadUnhidden(); }, 450);
  });
  // build 149: categorieën die bij het ophalen verborgen waren, zijn niet ingelezen (raw.hiddenGroups). Staat er daarvan
  // weer één aan (of geldt het verbergen niet meer, kinderprofiel), dan de open server op de achtergrond opnieuw ophalen.
  // Andere servers krijgen die categorie bij hun volgende keer bijwerken.
  var reloading = false;
  function reloadUnhidden() {
    var src = Lib.source, X9 = B.b149;
    if (!X9 || !src || !Lib.raw || reloading || Lib.isUpdating()) return;
    if (!X9.needsReload(Lib.raw, Lib.hiddenPredicate(src))) return;
    reloading = true;
    Promise.resolve(Lib.refresh(src)).then(function () { reloading = false; }, function () { reloading = false; });
  }
  B.on('profileData', function () { setTimeout(reloadUnhidden, 500); });
  /** Build 149: wat bij het ophalen van [src] niet ingelezen hoeft te worden (verborgen categorie, ook via samenvoegen). */
  Lib.hiddenPredicate = function (src) {
    var hc = D.hiddenCategoryNames(src.id) || {}, merged = (D.customization(src) || {}).merged || {};
    if (!Object.keys(hc).length) return function () { return false; };
    return function (g) { return !!(hc[g] || (merged[g] && hc[merged[g]])); };
  };
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
  PC.allowedFor = function (rated, group, limit, genre, strict, info) {
    if (limit == null) return true;
    if (PC.isAdultGroup(group)) return false;
    // leeftijd: TMDB (Kijkwijzer eerst, B.ageRatings) en anders de classificatie van de bron
    var age = info && info.age != null ? info.age : PC.minimumAge(rated);
    if (age != null) return age <= limit;
    if (!strict) return true;
    var AR = B.ageRatings;
    if (PC.isAdultGenre(genre) || (AR && AR.isAdultInfo(info))) return false;
    if (PC.isKidsGenre(genre) || (AR && AR.isKidsInfo(info))) return true;
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

  function context(other) {
    var p = B.app && B.app.profile;
    var src = other || Lib.source;
    return {
      custom: D.customization(src), playlist: src ? src.id : '',
      ageLimit: D.ageLimit(p), strict: D.strict(p),
      parentalEnabled: !!B.bool(D.shared('parentalControl', false)),
      autoLockAdult: B.bool(D.shared('parentalAutoLockAdult', true)) !== false,
      lockedGroups: D.lockedGroups(src), unlocked: PC.isUnlocked(),
      // build 128: categorieën aan/uit (build 140: voor het hele account, section `account`; kinderprofiel: geen)
      hiddenCats: src ? D.hiddenCategoryNames(src.id) : {},
      // build 140: kinderprofiel: alleen inhoud uit kindercategorieën
      kids: !!(p && p.kids)
    };
  }
  /**
   * Build 148 (diagnose): per soort van de open server geleverd, zichtbaar en waarom verborgen (categorieën, kinderfilter,
   * overig). Telt per groep één keer; null als er nog gefilterd wordt of niets open is.
   */
  Lib.visibility = function () {
    var raw = Lib.raw, lib = Lib.lib, X8 = B.b148;
    if (!X8 || !Lib.source || !raw || !lib || applyJob) return null;
    var ctx = context(), c = ctx.custom, hc = ctx.hiddenCats || {};
    var kids = !!ctx.kids && !ctx.unlocked;
    function hiddenCat(g) { return !!(hc[g] || hc[c.merged[g] || g]); }
    function notKids(g) { return kids && !B.b140.isKidsCategory(g); }
    function g(x) { return x.group; }
    // build 149: plus wat niet is ingelezen omdat de categorie verborgen was (geleverd én verborgen)
    function plus(c, kind) {
      var n = B.b149 ? B.b149.hiddenCount(raw, kind) : 0;
      return n > 0 ? X8.hidden.of(c.delivered + n, c.visible, c.categories + n, c.kids, c.duplicates) : c;
    }
    return {
      live: plus(X8.hidden.count(raw.channels, lib.channels.length, g, hiddenCat, notKids), 'live'),
      movie: plus(X8.hidden.count(raw.movies, lib.movies.length, g, hiddenCat, notKids), 'movie'),
      series: plus(X8.hidden.count(raw.series, lib.series.length, g, hiddenCat, notKids), 'series')
    };
  };
  /** Filter per item (= Playlists.apply): geeft per soort een functie item → gefilterde kopie of null. */
  function makeFilter(ctx) {
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
    var kids = !!ctx.kids && !ctx.unlocked;
    function visible(id, g) {
      if (hidden[id]) return false;
      if (B.has(groupOK, g)) return groupOK[g];
      var m = c.merged[g] || g;
      var hc = ctx.hiddenCats || {};
      // build 140: kinderprofiel: alleen kindercategorieën (naam zoals de server hem levert)
      var ok = (!kids || B.b140.isKidsCategory(g)) && !hc[g] && !hc[m] && !isLocked(g) && !isLocked(m);
      groupOK[g] = ok;
      return ok;
    }
    var AR = B.ageRatings;
    var CLs = B.clang ? B.clang.strip : function (t) { return t; };
    function ageOK(x, isShow) {
      if (ageLimit == null) return true;
      var info = AR ? AR.cached(x.title, x.year, isShow) : null;
      // build 140: kinderprofiel: bekende classificatie boven de grens niet, zonder bekende classificatie wel
      if (kids) return B.b140.kidsAgeAllowed(info && info.age != null ? info.age : PC.minimumAge(x.rated), ageLimit);
      return PC.allowedFor(x.rated, x.group, ageLimit, x.genre, ctx.strict, info);
    }
    return {
      channel: function (ch) {
        if (!visible(ch.id, ch.group)) return null;
        var o = B.assign({}, ch, { group: group(ch.group), name: c.renamed[ch.id] || ch.name, rawGroup: ch.group });
        // build 140: een kinderprofiel volgt alleen de categorie (de zendernaam telt niet mee)
        if (ageLimit != null && !kids && !PC.channelAllowedFor(o.name, o.rawGroup, ageLimit, ctx.strict)) return null;
        return o;
      },
      movie: function (m) {
        if (!visible(m.id, m.group) || !ageOK(m, false)) return null;
        // taal-tag ("Animal (IT)", "NL | …") niet in de getoonde titel; rawTitle blijft voor TMDB, Trakt en sleutels
        return B.assign({}, m, { group: group(m.group), title: c.renamed[m.id] || CLs(m.title), rawTitle: m.title, rawGroup: m.group });
      },
      series: function (x) {
        if (!visible(x.id, x.group) || !ageOK(x, true)) return null;
        return B.assign({}, x, { group: group(x.group), title: c.renamed[x.id] || CLs(x.title), rawTitle: x.title, rawGroup: x.group });
      },
      sortChannels: function (channels) {
        if (!((c.groupOrder && c.groupOrder.length) || Object.keys(c.channelOrder || {}).length)) return channels;
        var gIndex = {}; c.groupOrder.forEach(function (g, i) { if (!B.has(gIndex, g)) gIndex[g] = i; });
        var natural = {}, n = 0;
        channels.forEach(function (ch) { if (!B.has(natural, ch.group)) natural[ch.group] = 10000 + n++; });
        var cIndex = {};
        Object.keys(c.channelOrder).forEach(function (g) { (c.channelOrder[g] || []).forEach(function (id, i) { cIndex[id] = i; }); });
        var original = {};
        channels.forEach(function (ch, i) { if (!B.has(original, ch.id)) original[ch.id] = 100000 + i; });
        return channels.sort(function (a, b) {
          var ga = B.has(gIndex, a.group) ? gIndex[a.group] : natural[a.group];
          var gb = B.has(gIndex, b.group) ? gIndex[b.group] : natural[b.group];
          if (ga !== gb) return ga - gb;
          var ia = B.has(cIndex, a.id) ? cIndex[a.id] : original[a.id];
          var ib = B.has(cIndex, b.id) ? cIndex[b.id] : original[b.id];
          return ia - ib;
        });
      }
    };
  }
  /** In één keer filteren (tests en kleine bibliotheken). */
  Lib.apply = function (raw, ctx) {
    var f = makeFilter(ctx);
    function run(list, fn) { var out = []; for (var i = 0; i < list.length; i++) { var o = fn(list[i]); if (o) out.push(o); } return out; }
    return { channels: f.sortChannels(run(raw.channels, f.channel)), movies: run(raw.movies, f.movie), series: run(raw.series, f.series), epg: raw.epg || {} };
  };
  /** In stukjes filteren (grote bibliotheken): de afstandsbediening blijft reageren. */
  Lib.applyChunked = function (raw, ctx) {
    var f = makeFilter(ctx);
    var out = { channels: [], movies: [], series: [], epg: raw.epg || {} };
    var job = null, cancelled = false;
    function run(list, fn, target) {
      if (cancelled) return Promise.reject(new Error('cancelled'));
      job = B.chunked(list, function (x) { var o = fn(x); if (o) target.push(o); });
      return job;
    }
    var p = run(raw.channels, f.channel, out.channels)
      .then(function () { return run(raw.movies, f.movie, out.movies); })
      .then(function () { return run(raw.series, f.series, out.series); })
      .then(function () { out.channels = f.sortChannels(out.channels); return out; });
    p.cancel = function () { cancelled = true; if (job) job.cancel(); };
    return p;
  };

  /** Telt op bij elke nieuwe gefilterde bibliotheek; afgeleide lijsten (collecties, top 10, groepen) worden per versie gecachet. */
  Lib.version = 0;
  var memo = {};
  Lib.memo = function (key, fn) {
    var m = memo[key];
    if (m && m.v === Lib.version) return m.r;
    var r = fn();
    memo[key] = { v: Lib.version, r: r };
    return r;
  };
  var applyJob = null, lastRaw = null, lastCtx = null, ageVersion = 0;
  B.on('ageRatings', function () { ageVersion++; });
  /**
   * Opnieuw filteren; grote bibliotheken in stukjes, kleine meteen. Daarna het event 'library'.
   * Niets veranderd (zelfde bibliotheek, zelfde instellingen, geen nieuwe leeftijden)? Dan niets doen:
   * zo tekent de heartbeat niet elke 5 minuten alle schermen opnieuw.
   */
  Lib.applyFilters = function (force) {
    var raw = Lib.raw, ctx = context();
    var key = JSON.stringify(ctx) + '|' + ageVersion;
    if (!force && raw === lastRaw && key === lastCtx && !applyJob) return Promise.resolve();
    lastRaw = raw; lastCtx = key;
    if (applyJob) { applyJob.cancel(); applyJob = null; }
    var size = raw.channels.length + raw.movies.length + raw.series.length;
    if (size < 6000) { task('catalog_filter', function () { finishApply(Lib.apply(raw, ctx)); }); return Promise.resolve(); }
    var job = applyJob = Lib.applyChunked(raw, ctx);
    var ijob = null;
    var p = job.then(function (lib) {
      if (applyJob !== job) return;
      // build 148: ook de indexen in stukjes (37k films, 12k zenders, 9k series), pas daarna in één keer toepassen
      ijob = indexChunked(lib);
      return ijob.then(function () {
        if (applyJob !== job) return;
        applyJob = null;
        finishApply(lib, true);
      });
    }).then(B.noop, B.noop);
    var c = job.cancel;
    job.cancel = function () { c(); if (ijob) ijob.cancel(); };
    return p;
  };
  /** Build 147: synchrone taak met een naam (detector van lange taken, js/support.js). */
  function task(name, fn) { return B.diag && B.diag.task ? B.diag.task(name, fn) : fn(); }
  function finishApply(lib, indexed) {
    if (!indexed) task('index', function () { buildIndexes(lib); });
    Lib.lib = lib;
    Lib.version++;
    memo = {};
    task('apply', function () { B.emit('library'); });
  }
  /** Build 148: indexen van een (grote) gefilterde bibliotheek in stukjes, zonder Lib.lib al te vervangen. */
  function indexChunked(l) {
    var seen = { c: {}, m: {}, s: {} };
    l.channelGroups = []; l.movieGroups = []; l.seriesGroups = [];
    l.byId = {};
    var job = null, cancelled = false;
    function run(list, type, gs, groups) {
      if (cancelled) return Promise.reject(new Error('cancelled'));
      job = B.chunked(list, function (x) {
        if (!gs[x.group]) { gs[x.group] = true; groups.push(x.group); }
        l.byId[x.id] = { type: type, item: x };
      });
      return job;
    }
    var p = run(l.channels, 'channel', seen.c, l.channelGroups)
      .then(function () { return run(l.movies, 'movie', seen.m, l.movieGroups); })
      .then(function () { return run(l.series, 'series', seen.s, l.seriesGroups); });
    p.cancel = function () { cancelled = true; if (job) job.cancel(); };
    return p;
  }

  function groupsOf(list) {
    var seen = {}, out = [];
    list.forEach(function (x) { if (!seen[x.group]) { seen[x.group] = true; out.push(x.group); } });
    return out;
  }
  /** Items per groep (één keer per versie, in plaats van de hele lijst te filteren per categorierij). */
  Lib.byGroup = function (type) {
    return Lib.memo('byGroup:' + type, function () {
      var list = type === 'movie' ? Lib.lib.movies : type === 'series' ? Lib.lib.series : Lib.lib.channels;
      var m = {};
      for (var i = 0; i < list.length; i++) (m[list[i].group] = m[list[i].group] || []).push(list[i]);
      return m;
    });
  };
  function buildIndexes(lib) {
    var l = lib || Lib.lib;
    l.channelGroups = groupsOf(l.channels);
    l.movieGroups = groupsOf(l.movies);
    l.seriesGroups = groupsOf(l.series);
    l.byId = {};
    l.channels.forEach(function (x) { l.byId[x.id] = { type: 'channel', item: x }; });
    l.movies.forEach(function (x) { l.byId[x.id] = { type: 'movie', item: x }; });    l.series.forEach(function (x) { l.byId[x.id] = { type: 'series', item: x }; });
  }

  // MARK: tv-gids

  /** Programma's van een zender (gesorteerd op start). De sleutel wordt per zender één keer bepaald. */
  Lib.epgFor = function (ch) {
    // build 128: zender van een andere server: zijn eigen gids (js/allservers.js)
    if (ch && ch._src) return B.allservers ? B.allservers.programs(ch) : [];
    var e = Lib.raw.epg || {};
    var k = ch._ek || (ch._ek = String(ch.epgID || ch.id).toLowerCase());
    return e[k] || e[String(ch.id).toLowerCase()] || [];
  };
  /** Nu en straks via binair zoeken (geen lineaire lus per rij tijdens het scrollen). */
  Lib.nowNext = function (ch, at) {
    var list = Lib.epgFor(ch), now = at || Date.now();
    if (!list.length) return { now: null, next: null };
    var i = B.bsearchStart(list, now);
    if (i >= 0 && now < list[i].end) return { now: list[i], next: list[i + 1] || null };
    return { now: null, next: list[i + 1] || null };
  };
  /** Programma's die het venster [from, to) raken (binair zoeken + korte lus). */
  Lib.programsBetween = function (ch, from, to) {
    var list = Lib.epgFor(ch);
    if (!list.length) return [];
    var i = Math.max(0, B.bsearchStart(list, from));
    var out = [];
    for (; i < list.length && list[i].start < to; i++) if (list[i].end > from) out.push(list[i]);
    return out;
  };

  // MARK: terugkijken (catch-up / timeshift)

  /** Kan dit programma teruggekeken worden? (afgelopen of bezig, binnen catchupDays, Xtream of MAC-portal) */
  /** build 128: server van een zender (ch._src = andere server, ook na een verhuizing). */
  function srcOf(ch) { return ch && ch._src ? D.sourceById(ch._src) : Lib.source; }
  Lib.canCatchup = function (ch, p) {
    var s = srcOf(ch);
    if (!ch || !p || !(ch.catchupDays > 0) || !s) return false;
    var kind = s.kind;
    if (kind !== 'Xtream Codes' && kind !== 'Stalker / MAC-portal') return false;
    var now = Date.now();
    return p.start < now && p.start > now - ch.catchupDays * 86400000;
  };
  /** Afspeel-URL voor terugkijken, met de terugkijk-correctie van de zender (catchupOffsets, minuten). */
  Lib.catchupURL = function (ch, p) {
    var src = srcOf(ch);
    if (!Lib.canCatchup(ch, p)) return Promise.resolve(null);
    var raw = null;
    // build 128: zender van een andere server: zijn eigen gegevens (inlog, terugkijk-correctie, MAC-portal)
    if (!ch._src) for (var i = 0; i < Lib.raw.channels.length; i++) if (Lib.raw.channels[i].id === ch.id) { raw = Lib.raw.channels[i]; break; }
    raw = raw || ch;
    var shift = D.catchupOffset(D.sourceById(src.id) || src, ch.id) * 60000;
    var start = p.start + shift, end = Math.min(p.end, Date.now()) + shift;
    if (p.end > Date.now()) end = p.end + shift;
    if (src.kind === 'Xtream Codes') return Promise.resolve(new P.Xtream(src, D.secret(src, 'password')).catchupURL(raw, start, end));
    var st = ch._src ? new P.Stalker(src) : (Lib.stalker || new P.Stalker(src));
    if (!ch._src) Lib.stalker = st;
    return st.archiveLink(raw, start, end).catch(function () { return null; });
  };

  // MARK: laden en cachen

  // build 146: geen eigen verversen van de gids op tijd meer (was 4 uur): de gids volgt "Servers laden bij openen"
  // (Nu bijwerken, Altijd, of handmatig met Servers vernieuwen). Bij het openen met een bewaarde lijst geldt de bewaarde gids.
  /** Gewenste gidskanalen met per kanaal de ondergrens: zenders met terugkijken tot 3 dagen terug, de rest 3 uur. */
  function wantedEpg(lib) {
    var w = {}, now = Date.now();
    lib.channels.forEach(function (c) {
      if (!c.epgID) return;
      var k = String(c.epgID).toLowerCase();
      var low = c.catchupDays > 0 ? now - Math.min(3, c.catchupDays) * 86400000 : now - 3 * 3600000;
      w[k] = B.has(w, k) ? Math.min(w[k], low) : low;
    });
    return w;
  }
  /** Build 147 (diagnose): per soort vastleggen wat de server leverde of waarom niet (zonder URL of namen). */
  function fetchLibrary(src, rec) {
    var errs = {};
    var DG = B.diag;
    return fetchLibraryFrom(src, errs, rec).then(function (lib) {
      phase(rec, 'parse');
      if (DG && DG.fetchLog) DG.fetchLog.delivered(src.id, lib, errs);
      return lib;
    }, function (e) {
      var X7 = B.b147 && B.b147.fetch;
      if (DG && DG.fetchLog && X7) DG.fetchLog.failed(src.id, X7.errorCode(e && e.name, e && e.message));
      throw e;
    });
  }
  /** Build 148: fase van het bijwerken afsluiten (B.diag.updates, js/support.js). */
  function phase(rec, code) { if (rec && B.diag && B.diag.updates) B.diag.updates.phase(rec, code); }
  function fetchLibraryFrom(src, errs, rec) {
    var kind = src.kind;
    var pw = D.secret(src, 'password');
    if (kind === 'M3U / M3U8') {
      var u = P.urlValue(src.m3uURL);
      if (!u) return Promise.reject(new Error(L('Invalid URL')));
      return B.getText(u, null, 180000).then(function (text) {
        phase(rec, 'fetch');
        var lib = P.m3uLibrary(text, src.id);
        // build 97: gids-URL uit de kop lezen vóórdat de tekst wordt vrijgegeven (anders faalde een M3U zonder eigen gids-URL)
        lib.epgURL = P.urlValue(src.epgURL) || P.m3uEpgURL(text || '');
        text = null;
        return lib;
      });
    }
    if (kind === 'Xtream Codes') {
      var x = new P.Xtream(src, pw);
      return x.authenticate().then(function () { return x.library({ fetched: function () { phase(rec, 'fetch'); }, hidden: Lib.hiddenPredicate(src) }); }).then(function (lib) {
        B.assign(errs, x.kindErrors());
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
    if (B.media.isMediaServer(kind)) {
      return B.media.library(src, {
        // Plex vond een nieuw serveradres: bewaren in het account (secrets.session), zoals de andere apps
        onSession: function (ns) {
          var sec = { session: B.media.sessionString(ns) };
          D.saveSource({ id: src.id, serverURL: ns.serverURI, secrets: sec }).catch(B.noop);
        }
      }).then(function (lib) { lib.epgURL = P.urlValue(src.epgURL); return lib; });
    }
    return Promise.reject(new Error(L(B.platform.texts.unsupportedServer)));
  }
  function loadEpg(src, lib, force) {
    if (!lib.epgURL) return Promise.resolve();
    if (B.media.isMediaServer(src.kind)) return Promise.resolve();
    return B.idb.get('epg:' + src.id).then(function (c) {
      if (c && !force) return c.epg;
      return P.loadXMLTV(lib.epgURL, wantedEpg(lib)).then(function (epg) {
        if (Object.keys(epg).length) B.idb.set('epg:' + src.id, { at: Date.now(), epg: epg });
        return Object.keys(epg).length ? epg : (c ? c.epg : {});
      });
    }).then(function (epg) {
      if (Lib.source && Lib.source.id === src.id && epg) { Lib.raw.epg = epg; Lib.lib.epg = epg; B.emit('epg'); }
    }).catch(B.noop);
  }

  /**
   * Server kiezen en laden: eerst de cache (meteen zichtbaar). Build 145: met een bewaarde lijst wordt niets vanzelf
   * opgehaald (geen vernieuwtermijn meer, ook niet na 45 s); na het kiezen van een profiel volgt de keuze van "Servers
   * laden bij openen" (afterOpen). Zonder bewaarde lijst: meteen laden.
   */
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
      if (cached && cached.lib) Lib.stamps[src.id] = cached.at || 0;
      if (cached && cached.lib && !force) {
        Lib.raw = cached.lib; Lib.raw.epg = Lib.raw.epg || {};
        Lib.loading = false;
        Lib.applyFilters();
        loadEpg(src, Lib.raw, false);
        afterOpen(my, true);
        return;
      }
      refreshedThisLaunch[lower(src.id)] = true;
      return Lib.refresh(src, my).then(function (ok) {
        // geen bewaarde lijst: geen vraag; bij "Altijd" daarna de andere servers
        if (my === gen) afterOpen(my, false);
        return ok;
      });
    });
  };

  // MARK: build 145: servers laden bij openen (Vragen / Altijd / Nooit, alleen handmatig; B.b145.load)

  var refreshedThisLaunch = {}, decisionPending = false, askedThisLaunch = false, updating = null, updGen = 0;
  /** Profiel gekozen (na de pincode): de keuze volgt zodra de open server er is (Lib.select → afterOpen). */
  Lib.profileOpened = function () {
    decisionPending = true;
    Lib.askUpdate = false;
  };
  /** Build 145: de vraag "Servers bijwerken?" staat open (app.js toont hem, niet tijdens het afspelen). */
  Lib.askUpdate = false;
  function afterOpen(my, savedList) {
    // zenders van de andere servers (reservebron) die nog niet op de tv staan: pas na de wachttijd van build 144
    var A = B.b144 && B.b144.auto;
    if (A) A.whenAllowed(function () { Lib.warmOthers(); }, function () { return my !== gen; });
    if (!decisionPending) return;
    decisionPending = false;
    var P = B.b145.load;
    var kids = !!(B.app && B.app.profile && B.app.profile.kids);
    var act = P.decide(P.mode(), savedList, kids, askedThisLaunch);
    if (act === 'ask') { askedThisLaunch = true; Lib.askUpdate = true; B.emit('askUpdate'); }
    else if (act === 'updateAll') Lib.updateAll(true);
  }
  /** Antwoord op de vraag: true = Nu bijwerken, false = Later (niets ophalen). */
  Lib.answerUpdate = function (now) {
    Lib.askUpdate = false;
    if (now) Lib.updateAll(false);
  };
  function playingNow() { return !!((B.player && B.player.current) || (B.multiview && B.multiview.current)); }
  function onMain() { return !!(B.app && B.app.profile); }
  /**
   * Build 145: alle servers van het account bijwerken, één voor één, de open server eerst, op de achtergrond (de bewaarde
   * lijst blijft staan; melding "Bijwerken…" / "%@ vernieuwen (1 van 3)…"). Nooit tijdens het afspelen of in "Wie kijkt
   * er?"/het pincodescherm (dan wachten), elk met een time-out. Een andere server of een ander profiel (nieuwe
   * Lib.select) stopt het. onlyNew: servers die bij deze keer openen al bijgewerkt zijn overslaan ("Altijd" na een
   * profielwissel).
   */
  Lib.updateAll = function (onlyNew) {
    if (updating || Lib.refreshingAll || !D.sources.length) return updating || Promise.resolve(false);
    var my = gen, mine = ++updGen, P = B.b145.load, AS = B.allservers, any = false;
    function stale() { return !P.stillCurrent(my, gen) || mine !== updGen; }
    function idle(first) {
      return new Promise(function (resolve) {
        (function wait() {
          if (stale()) { resolve(false); return; }
          if (Lib.loading || Lib.refreshingAll || !P.mayRun(onMain(), playingNow())) { setTimeout(wait, first ? 1000 : 2000); return; }
          resolve(true);
        })();
      });
    }
    var state = updating = idle(true).then(function (go) {
      if (!go) return false;
      var order = P.order(D.sources.map(function (s) { return s.id; }), Lib.source ? Lib.source.id : null, onlyNew ? refreshedThisLaunch : {});
      var n = order.length, chain = Promise.resolve();
      if (n && B.ui) B.ui.toast(L('Updating…'));
      order.forEach(function (id, i) {
        chain = chain.then(function () { return idle(false); }).then(function (go2) {
          var src = D.sourceById(id);
          if (!go2 || !src) return;
          if (n > 1 && B.ui) B.ui.toast(L('Refreshing %@ (%d of %d)…', src.name, i + 1, n), 4000);
          refreshedThisLaunch[lower(src.id)] = true;
          if (Lib.source && src.id === Lib.source.id) {
            B.log('servers bijwerken: open server');
            return Lib.refresh(src).then(function (ok) { if (ok) any = true; });
          }
          if (AS && AS.markFetched) AS.markFetched(src.id);
          var job = Lib.fetchOther(src);
          return (AS && AS.withTimeout ? AS.withTimeout(job, 90000) : job).then(function (lib) { if (lib) any = true; }, B.noop);
        });
      });
      return chain;
    }).catch(B.noop).then(function () {
      if (updating === state) updating = null;
      if (any && AS && AS.refreshed) AS.refreshed();
      return any;
    });
    return state;
  };
  Lib.isUpdating = function () { return !!updating; };
  Lib.refresh = function (src, my) {
    my = my || gen;
    Lib.loading = true;
    B.emit('loading');
    var UP = B.diag && B.diag.updates;
    var rec = UP ? UP.begin(src) : null;
    return fetchLibrary(src, rec).then(function (lib) {
      // Jellyfin/Emby leveren hun gids zelf mee; bij de andere komt hij later uit XMLTV
      var ownEpg = B.media.isMediaServer(src.kind) ? (lib.epg || {}) : null;
      lib.epg = ownEpg || {};
      var at = Date.now();
      var saved = B.idb.set('lib:' + src.id, { at: at, lib: lib });
      Lib.stamps[src.id] = at;
      // alleen de zenders apart, voor de reservebron op andere servers
      saveChannels(src, lib.channels);
      if (my !== gen) { if (UP && rec) UP.finish(rec, 'cancelled'); return; }
      var epg = Lib.raw && Lib.source && Lib.source.id === src.id ? Lib.raw.epg : {};
      lib.epg = ownEpg || epg || {};
      Lib.raw = lib;
      Lib.loading = false;
      // build 148: toepassen (filteren en indexeren in stukjes) en bewaren als fasen voor de diagnose
      Promise.resolve(Lib.applyFilters()).then(function () { phase(rec, 'apply'); return saved; })
        .then(function () { phase(rec, 'save'); if (UP) UP.finish(rec, 'ok'); }, function () { if (UP) UP.finish(rec, 'ok'); });
      return Promise.resolve(loadEpg(src, lib, true)).then(function () { return true; }, function () { return true; });
    }, function (e) {
      if (UP && rec) UP.finish(rec, 'failed');
      if (my !== gen) return false;
      Lib.loading = false;
      if (!Lib.raw.channels.length && !Lib.raw.movies.length && !Lib.raw.series.length) Lib.error = (e && e.message) || L('Something went wrong. Please try again.');
      B.emit('library');
      return false;
    });
  };

  /**
   * Build 130: alle servers vernieuwen ("Nu vernieuwen"): eerst de actieve (zodat die snel klaar is), daarna de andere
   * één voor één op de achtergrond, elk met een time-out en niet tijdens het afspelen. Een fout bij één server houdt de
   * rest niet tegen; daarna een korte melding met de servers die niet lukten (alleen de naam, niets gelogd). Daarna
   * werkt allservers.js catalogmeta, remap en slimme collecties bij.
   */
  Lib.refreshAll = function (status) {
    if (Lib.refreshingAll) return Lib.refreshingAll.promise;
    var AS = B.allservers;
    var active = Lib.source;
    var ids = D.sources.map(function (s) { return s.id; });
    var order = (AS && AS.refreshOrder ? AS.refreshOrder(ids, active ? active.id : null) : ids).map(function (id) { return D.sourceById(id); }).filter(Boolean);
    var failed = [], n = order.length;
    var state = Lib.refreshingAll = { active: false, promise: null };
    status = status || B.noop;
    function idle() {
      return new Promise(function (resolve) {
        (function wait() { if ((B.player && B.player.current) || Lib.loading) setTimeout(wait, 2000); else resolve(); })();
      });
    }
    function timeout(p, ms) { return AS && AS.withTimeout ? AS.withTimeout(p, ms) : p; }
    // build 145: Nu vernieuwen neemt een lopend bijwerken over (dat stopt na de server waar het mee bezig is)
    updGen++;
    var chain = updating ? updating.then(B.noop, B.noop) : Promise.resolve();
    order.forEach(function (src, i) {
      chain = chain.then(function () {
        if (n > 1) status(L('Refreshing %@ (%d of %d)…', src.name, i + 1, n));
        if (active && src.id === active.id) {
          state.active = true;
          return timeout(Lib.select(src, true), 180000).then(function (ok) {
            state.active = false;
            if (ok === false || ok === null || Lib.error) failed.push(src.name);
          });
        }
        return idle().then(function () {
          if (AS && AS.markFetched) AS.markFetched(src.id);
          return timeout(Lib.fetchOther(src), 90000);
        }).then(function (lib) { if (!lib) failed.push(src.name); }, function () { failed.push(src.name); });
      });
    });
    state.promise = chain.then(function () {
      Lib.refreshingAll = null;
      status(failed.length ? L('Couldn\'t refresh: %@', failed.join(', ')) : null, true);
      if (AS && AS.refreshed) AS.refreshed();
      return failed;
    }, function () { Lib.refreshingAll = null; return failed; });
    return state.promise;
  };

  /** Afleveringen van een serie (M3U: al in de lijst; Xtream: get_series_info; Plex/Jellyfin/Emby/Stremio: hun API). */
  Lib.episodes = function (s) {
    if (s.episodes && s.episodes.length) return Promise.resolve({ episodes: s.episodes, info: null });
    // build 128: serie van een andere server (s._src), zonder te wisselen; eigen cachesleutel
    var ck = (s._src ? lower(s._src) + '|' : '') + s.id;
    if (Lib.episodeCache[ck]) return Promise.resolve(Lib.episodeCache[ck]);
    var src = s._src ? (D.sourceById(s._src) || Lib.source) : Lib.source;
    if (src && B.media.isMediaServer(src.kind)) {
      return B.media.episodes(src, s).then(function (eps) {
        var r = { episodes: eps || [], info: null };
        if (r.episodes.length) Lib.episodeCache[ck] = r;
        return r;
      }, function () { return { episodes: [], info: null }; });
    }
    if (!src || src.kind !== 'Xtream Codes') return Promise.resolve({ episodes: [], info: null });
    var x = new P.Xtream(src, D.secret(src, 'password'));
    return x.episodes(s).then(function (r) {
      if (r.episodes.length) Lib.episodeCache[ck] = r;
      return r;
    }, function () { return { episodes: [], info: null }; });
  };
  Lib.movieInfo = function (m) {
    var src = m && m._src ? (D.sourceById(m._src) || Lib.source) : Lib.source;
    if (!src || src.kind !== 'Xtream Codes' || m.xtreamVodID == null) return Promise.resolve(null);
    return new P.Xtream(src, D.secret(src, 'password')).vodInfo(m.xtreamVodID).catch(function () { return null; });
  };

  /** Stremio: alle directe streams van een titel ("movie|tt…" of "series|tt…:s:e"). */
  Lib.stremioStreams = function (ref, srcId) {
    var src = srcId ? (D.sourceById(srcId) || Lib.source) : Lib.source;
    if (!src || src.kind !== 'Stremio' || !ref) return Promise.resolve([]);
    return B.media.stremioFor(src).then(function (st) { return st.streams(ref); }, function () { return []; });
  };
  /** Afspeel-URL (MAC-portal: eerst een link aanmaken; Stremio: gekozen of eerste directe stream). */
  Lib.resolveURL = function (item, live) {
    if (item.stremio && !item.streamURL) {
      return Lib.stremioStreams(item.stremio, item.src).then(function (list) { return list.length ? list[0].url : null; });
    }
    // build 128: item van een andere server: een eigen MAC-portalverbinding
    var other = item.src && !(Lib.source && lower(Lib.source.id) === lower(item.src)) ? D.sourceById(item.src) : null;
    if (other) {
      if (item.stalkerCmd && other.kind === 'Stalker / MAC-portal') {
        return new P.Stalker(other).createLink(item.stalkerCmd, live).then(function (u) { return u || item.streamURL; }, function () { return item.streamURL; });
      }
      return Promise.resolve(item.streamURL);
    }
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
  var IPTV = ['M3U / M3U8', 'Xtream Codes', 'Stalker / MAC-portal'];
  Lib.backupChannels = function (ch) {
    var cur = Lib.source ? Lib.source.id : null;
    var others = D.sources.filter(function (s) { return s.id !== cur && IPTV.indexOf(s.kind) >= 0; });
    var epg = ch.epgID ? String(ch.epgID).trim().toLowerCase() : null;
    var norm = Lib.normalizeName(ch.name);
    if (!epg && norm.length < 2) return Promise.resolve([]);
    return Promise.all(others.map(function (src) {
      return B.idb.get('chan:' + src.id).then(function (c) {
        var list = chanList(c);
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

  // build 97: zenderlijst voor de reservebron met een vervaldatum ({at, list}); oude caches waren een kale lijst
  function saveChannels(src, channels) {
    return B.idb.set('chan:' + src.id, { at: Date.now(), list: channels.map(function (c) { return { name: c.name, epgID: c.epgID, streamURL: c.streamURL, stalkerCmd: c.stalkerCmd || null }; }) });
  }
  function chanList(c) { return !c ? null : Array.isArray(c) ? c : (Array.isArray(c.list) ? c.list : null); }
  /** build 97: alleen de live-zenders van een IPTV-server (geen films of series), voor de reservebron. */
  function fetchChannels(src) {
    if (src.kind === 'M3U / M3U8') {
      var u = P.urlValue(src.m3uURL);
      if (!u) return Promise.reject(new Error(L('Invalid URL')));
      return B.getText(u, null, 180000).then(function (text) { return P.m3uLibrary(text, src.id, { channelsOnly: true }).channels; });
    }
    if (src.kind === 'Xtream Codes') {
      var x = new P.Xtream(src, D.secret(src, 'password'));
      return x.authenticate().then(function () { return x.liveChannels(); });
    }
    if (src.kind === 'Stalker / MAC-portal') return new P.Stalker(src).liveChannels();
    return Promise.resolve([]);
  }
  /** build 102: bibliotheek van een andere bron gefilterd voor dit profiel (zoeken in andere afspeellijsten, "Andere bronnen"). */
  Lib.filterFor = function (src, raw) {
    var lib = Lib.apply({ channels: raw.channels || [], movies: raw.movies || [], series: raw.series || [], epg: {} }, context(src));
    lib.epg = {};
    return lib;
  };
  /** build 129: handtekening van de filters van het actieve profiel voor een bron (gefilterde kopieën hergebruiken). */
  Lib.filterSig = function (src) { try { return JSON.stringify(context(src)); } catch (e) { return String(Date.now()); } };
  /** build 128: filter per item (kinderslot, verborgen, leeftijd) voor een andere bron, zonder de hele lijst te kopiëren. */
  Lib.filterOne = function (src) { return makeFilter(context(src)); };
  /** build 128: gids van een paar zenders van een andere server (cache van die server, anders alleen deze zenders uit XMLTV). */
  Lib.epgOther = function (src, lib, chans) {
    if (!chans.length || B.media.isMediaServer(src.kind)) return Promise.resolve({});
    return B.idb.get('epg:' + src.id).then(function (c) {
      if (c && c.epg && Date.now() - c.at < 24 * 3600000) return c.epg;
      if (!lib || !lib.epgURL) return c && c.epg ? c.epg : {};
      var w = {}, now = Date.now();
      chans.forEach(function (ch) {
        if (!ch.epgID) return;
        w[String(ch.epgID).toLowerCase()] = ch.catchupDays > 0 ? now - Math.min(3, ch.catchupDays) * 86400000 : now - 3 * 3600000;
      });
      if (!Object.keys(w).length) return {};
      return P.loadXMLTV(lib.epgURL, w).catch(function () { return {}; });
    }).then(function (epg) {
      var out = {};
      chans.forEach(function (ch) {
        var k = String(ch.epgID || ch.id).toLowerCase();
        var l = (epg && (epg[k] || epg[String(ch.id).toLowerCase()])) || null;
        if (l && l.length) out[ch.id] = l;
      });
      return out;
    }, function () { return {}; });
  };
  /** build 102: bibliotheek van een andere bron ophalen en cachen (zoals Lib.refresh, maar zonder te wisselen). */
  Lib.fetchOther = function (src) {
    var UP = B.diag && B.diag.updates;
    var rec = UP ? UP.begin(src) : null;
    return fetchLibrary(src, rec).then(function (lib) {
      lib.epg = B.media.isMediaServer(src.kind) ? (lib.epg || {}) : {};
      var at = Date.now();
      Promise.resolve(B.idb.set('lib:' + src.id, { at: at, lib: lib })).then(function () { phase(rec, 'save'); if (UP) UP.finish(rec, 'ok'); },
        function () { if (UP) UP.finish(rec, 'failed'); });
      Lib.stamps[src.id] = at;
      // build 145: ook de zenders apart (reservebron), zodat warmOthers ze niet meer van het netwerk haalt
      if (IPTV.indexOf(src.kind) >= 0 && lib.channels && lib.channels.length) saveChannels(src, lib.channels);
      return lib;
    }, function (e) { if (UP && rec) UP.finish(rec, 'failed'); throw e; });
  };
  /**
   * Andere servers op de achtergrond laden (voor de reservebron), één tegelijk; build 97: alleen hun zenders. Build 145:
   * alleen als ze nog niet op de tv staan (niet meer opnieuw na 6 uur: bijwerken doet de gebruiker, zie Lib.updateAll).
   */
  Lib.warmOthers = function () {
    var cur = Lib.source ? Lib.source.id : null;
    var list = D.sources.filter(function (s) { return s.id !== cur && IPTV.indexOf(s.kind) >= 0; });
    var chain = Promise.resolve();
    list.forEach(function (src) {
      chain = chain.then(function () {
        return B.idb.get('chan:' + src.id).then(function (c) {
          if (chanList(c)) return;
          return fetchChannels(src).then(function (chs) {
            if (chs && chs.length) return saveChannels(src, chs);
          }, B.noop);
        });
      });
    });
    return chain;
  };

  // MARK: zoeken en collecties

  /** Opgevouwen titel (kleine letters, zonder accenten), één keer per item berekend. */
  function folded(x, field) {
    var k = field === 'name' ? '_fn' : '_ft';
    return x[k] != null ? x[k] : (x[k] = B.fold(x[field]));
  }
  Lib.search = function (q, limit) {
    var f = B.fold(q).trim();
    if (!f) return { channels: [], movies: [], series: [] };
    limit = limit || 40;
    // build 149: op woorden (volgorde maakt niet uit), genormaliseerd, met ranking en één typefout (B.b149.search)
    var SE = B.b149 && B.b149.search;
    if (SE) return { channels: SE.find(Lib.lib.channels, q, limit, 'name'), movies: SE.find(Lib.lib.movies, q, limit, 'title'), series: SE.find(Lib.lib.series, q, limit, 'title') };
    function hit(list, field) {
      var starts = [], contains = [];
      for (var i = 0; i < list.length && starts.length < limit; i++) {
        var idx = folded(list[i], field).indexOf(f);
        if (idx === 0) starts.push(list[i]); else if (idx > 0 && contains.length < limit) contains.push(list[i]);
      }
      return starts.concat(contains).slice(0, limit);
    }
    return { channels: hit(Lib.lib.channels, 'name'), movies: hit(Lib.lib.movies, 'title'), series: hit(Lib.lib.series, 'title') };
  };

  /**
   * Inhoud van een collectie (handmatig: lijst met id's; slim: zoekwoorden, soorten, groep, server).
   * Per bibliotheekversie en collectieversie gecachet: bij het scrollen wordt niets opnieuw doorzocht.
   */
  Lib.collectionItems = function (c, limit) {
    limit = limit || 60;
    var prof = B.app && B.app.profile ? B.app.profile.id : '';
    var key = 'coll:' + prof + '|' + c.id + '|' + (c.updatedAt || '') + '|' + limit + '|' + (Lib.source ? Lib.source.id : '') + '|' + (B.allservers ? B.allservers.version : 0);
    return Lib.memo(key, function () { return collectionItems(c, limit); });
  };
  function collectionItems(c, limit) {
    var lib = Lib.lib, out = [];
    var src = Lib.source ? String(Lib.source.id).toLowerCase() : '';
    if (c.kind === 'smart') {
      // build 129: ook de treffers op de andere servers van het profiel (js/allservers.js), dubbele titels één keer
      var AS = B.allservers;
      var other = AS ? AS.smartFor(c) : [];
      if (c.sourceID && String(c.sourceID).toLowerCase() !== src) return other.slice(0, limit);
      var words = String(c.query || '').split(',').map(function (w) { return B.fold(w).trim(); }).filter(Boolean);
      if (!words.length) return [];
      var types = Array.isArray(c.types) && c.types.length ? c.types : ['channel', 'movie', 'series'];
      var pools = [['channel', lib.channels, 'name'], ['movie', lib.movies, 'title'], ['series', lib.series, 'title']];
      pools.forEach(function (p) {
        if (types.indexOf(p[0]) < 0) return;
        var pool = c.group ? (Lib.byGroup(p[0])[c.group] || []) : p[1];
        for (var i = 0; i < pool.length && out.length < limit; i++) {
          var x = pool[i];
          var t = folded(x, p[2]);
          for (var w = 0; w < words.length; w++) if (t.indexOf(words[w]) >= 0) { out.push({ type: p[0], item: x }); break; }
        }
      });
      return other.length ? AS.mergeAcross([out, other], AS.entryKey, limit) : out;
    }
    (c.items || []).forEach(function (it) {
      if (!it || out.length >= limit) return;
      // build 128: item van een andere (of verwijderde) server (`sid`)
      var sid = it.sid || c.sourceID;
      if (sid && src && String(sid).toLowerCase() !== src && B.allservers) {
        var t = B.allservers.tile(sid + '|' + it.id, it.type);
        if (t) out.push(t);
        return;
      }
      var hit = lib.byId[it.id];
      if (hit) out.push(hit);
    });
    return out;
  }

  // MARK: Top 10 (zoals localTopMovies op Android: best beoordeeld, anders nieuwste)

  Lib.top10 = function (items) {
    var key = items === Lib.lib.movies ? 'top10:m' : items === Lib.lib.series ? 'top10:s' : null;
    return key ? Lib.memo(key, function () { return top10(items); }) : top10(items);
  };
  function top10(items) {
    var withPoster = items.filter(function (x) { return x.poster; });
    var pool = withPoster.length ? withPoster : items;
    var rated = pool.filter(function (x) { return (x.rating || 0) > 0; });
    if (rated.length >= 10) return rated.slice().sort(function (a, b) { return (b.rating || 0) - (a.rating || 0); }).slice(0, 10);
    var recent = pool.filter(function (x) { return x.addedAt; });
    if (recent.length >= 10) return recent.slice().sort(function (a, b) { return b.addedAt - a.addedAt; }).slice(0, 10);
    return pool.slice(0, 10);
  }
  Lib.recent = function (items, n) {
    var key = items === Lib.lib.movies ? 'recent:m:' + (n || 30) : items === Lib.lib.series ? 'recent:s:' + (n || 30) : null;
    function run() { return items.filter(function (x) { return x.addedAt; }).sort(function (a, b) { return b.addedAt - a.addedAt; }).slice(0, n || 30); }
    return key ? Lib.memo(key, run) : run();
  };
})();
