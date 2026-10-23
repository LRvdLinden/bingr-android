/*
 * Build 111: rijen op Home (= Discover.swift / Discover.kt in de apps en js/discover.js van de webspeler)
 *  - "Nieuw toegevoegd": films én series uit je eigen bron, de laatste 30 dagen (Xtream `added` / `last_modified`),
 *    nieuwste eerst, max. 30; zonder datum (M3U) blijft de rij weg.
 *  - "Binnenkort op Bingr" en "Nieuw op Bingr" (namen sinds build 112): titels uit TMDB (sleutel van de server), regio van de app-taal.
 *    Nooit namen of logo's van streamingdiensten (we vragen alleen "flatrate" op, zonder aanbieders te tonen).
 *    Staat een titel in je bibliotheek, dan opent die; anders een detailpagina met Mijn lijst. Build 112: "Nieuw op
 *    Bingr" alleen met titels uit je bibliotheek (meteen afspeelbaar; niets = geen rij). De bronvermelding (TMDB,
 *    JustWatch) staat niet meer onder de rijen en op de detailpagina, maar één keer in Instellingen → Over.
 *    12 uur bewaard; koppelen aan je bibliotheek via de titelindex van js/details.js.
 * Sleutels in de Home-indeling: "upcoming" en "streaming". Geschreven voor Chromium M56 (ES2017, geen ?. ?? of spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui, D = B.data, Lib = B.lib, S = B.screens, AR = B.ageRatings;
  var h = U.h;
  var L = function () { return B.L.apply(null, arguments); };
  var X = B.discover = {};

  X.KINDS = ['upcoming', 'streaming'];
  /** Getoonde naam (build 112: "Binnenkort op Bingr" / "Nieuw op Bingr"); de sleutels blijven upcoming/streaming. */
  X.title = function (kind) { return kind === 'upcoming' ? L('Coming soon to Bingr') : L('New on Bingr'); };
  /** Build 112: "Nieuw op Bingr" alleen met titels uit de eigen bibliotheek (meteen afspeelbaar). */
  X.onlyLibrary = function (kind) { return kind === 'streaming'; };
  /** Build 112: bronvermelding van TMDB en JustWatch, één keer in Instellingen → Over (niet meer onder de rijen). */
  X.attribution = function () {
    return L('Movie and series data: TMDB. This app uses the TMDB API but is not endorsed or certified by TMDB. Availability data: JustWatch.');
  };

  // MARK: id's ("tmdb-movie-123" / "tmdb-tv-123", zelfde als de apps; ook in Mijn lijst)

  var PREFIX = 'tmdb-';
  X.isExternal = function (id) { return String(id || '').indexOf(PREFIX) === 0; };
  X.externalID = function (tmdbID, isShow) { return PREFIX + (isShow ? 'tv' : 'movie') + '-' + tmdbID; };
  X.parse = function (id) {
    var m = /^tmdb-(movie|tv)-(\d+)$/.exec(String(id || ''));
    return m && +m[2] > 0 ? { id: +m[2], isShow: m[1] === 'tv' } : null;
  };

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function day(offsetDays) {
    var d = new Date(Date.now() + (offsetDays || 0) * 86400000);
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  }
  function img(path, size) { return path ? 'https://image.tmdb.org/t/p/' + size + path : null; }
  function locale() { return B.lang === 'zh-Hans' ? 'zh-CN' : (B.lang || 'en'); }

  /** Regio: die van de app-taal (nl → NL, …); bij Engels of een andere taal de regio van de tv, anders NL. */
  X.region = function () {
    var byLang = { nl: 'NL', de: 'DE', fr: 'FR', es: 'ES', it: 'IT', pt: 'PT', pl: 'PL', tr: 'TR' };
    if (B.has(byLang, B.lang)) return byLang[B.lang];
    var m = /[-_]([A-Za-z]{2})\b/.exec((navigator.languages && navigator.languages[0]) || navigator.language || '');
    return m ? m[1].toUpperCase() : 'NL';
  };

  function profile() { return B.app && B.app.profile; }
  function kidsProfile() { var p = profile(); return !!(p && p.kids); }
  function ageLimited() { var p = profile(); return !!(p && D.ageLimit(p) != null); }

  /** Titel uit TMDB als kaart-item (zoals een film/serie uit de bibliotheek, maar zonder stream). */
  X.asItem = function (t) {
    return { id: X.externalID(t.id, t.isShow), title: t.title, year: t.date ? B.int(t.date.slice(0, 4)) : null, poster: img(t.poster, 'w342'),
      plot: t.overview || null, tmdb: t.id, external: true, date: t.date || null, kind: t.kind, backdrop: img(t.backdrop, 'w1280') };
  };

  // MARK: bekende TMDB-titels (voor Mijn lijst; alleen op deze tv, max. 400)

  var KNOWN_KEY = 'discover.known';
  var known = null;
  function knownMap() { if (!known) known = B.store.get(KNOWN_KEY, {}) || {}; return known; }
  X.known = function (id) { var k = knownMap()[id]; return k ? k.t : null; };
  function remember(list) {
    if (!list || !list.length) return;
    var m = knownMap(), now = Date.now();
    list.forEach(function (t) { m[X.externalID(t.id, t.isShow)] = { t: t, at: now }; });
    var keys = Object.keys(m);
    if (keys.length > 400) {
      keys.sort(function (a, b) { return m[b].at - m[a].at; }).slice(400).forEach(function (k) { delete m[k]; });
    }
    B.store.set(KNOWN_KEY, m);
  }

  // MARK: ophalen (12 uur bewaard per soort, taal, regio en kinderprofiel)

  function cacheKey(kind, kids) { return 'discover.' + kind + '.' + (kids ? 'kids.' : '') + (B.lang || 'en') + '.' + X.region(); }
  var mem = {};
  function saved(kind, kids) {
    var k = cacheKey(kind, kids);
    if (!B.has(mem, k)) mem[k] = B.store.get(k, null);
    return mem[k];
  }
  /** Uit de cache (ook verlopen), zonder netwerk; Binnenkort zonder wat intussen uit is. */
  X.cached = function (kind, kids) {
    var c = saved(kind, kids);
    var items = c && c.items ? c.items : [];
    if (kind !== 'upcoming') return items;
    var today = day(0);
    return items.filter(function (t) { return (t.date || '') > today; });
  };
  function page(path, q, isShow, kind) {
    return AR.tmdb(path, q).then(function (r) {
      return ((r && r.results) || []).map(function (i) {
        var t = (isShow ? i.name : i.title) || i.title || i.name;
        if (!t || !i.poster_path || i.adult) return null;
        var o = isShow ? i.original_name : i.original_title;
        var date = (isShow ? i.first_air_date : i.release_date) || null;
        return { id: i.id, isShow: isShow, title: t, original: o && o !== t ? o : null, date: date, poster: i.poster_path,
          backdrop: i.backdrop_path || null, overview: i.overview || null, kind: kind };
      }).filter(Boolean);
    }, function () { return []; });
  }
  function fetchKind(kind, kids) {
    if (!AR.hasKey()) return Promise.resolve([]);
    var lang = AR.tmdbLang(), region = X.region(), today = day(0);
    var mq = [['language', lang], ['region', region], ['include_adult', 'false'], ['sort_by', 'popularity.desc'], ['page', '1']];
    var tq = [['language', lang], ['include_adult', 'false'], ['sort_by', 'popularity.desc'], ['page', '1']];
    if (kids) {
      mq.push(['with_genres', '16|10751'], ['certification_country', 'US'], ['certification.lte', 'PG']);
      tq.push(['with_genres', '10762|16|10751']);
    }
    var moviePath = '/discover/movie';
    if (kind === 'upcoming') {
      if (kids) mq.push(['with_release_type', '2|3'], ['release_date.gte', day(1)], ['release_date.lte', day(120)]);
      else moviePath = '/movie/upcoming';
      tq.push(['first_air_date.gte', day(1)], ['first_air_date.lte', day(120)]);
    } else {
      mq.push(['watch_region', region], ['with_watch_monetization_types', 'flatrate'], ['release_date.gte', day(-90)], ['release_date.lte', today]);
      tq.push(['watch_region', region], ['with_watch_monetization_types', 'flatrate'], ['first_air_date.gte', day(-120)], ['first_air_date.lte', today]);
    }
    return Promise.all([page(moviePath, mq, false, kind), page('/discover/tv', tq, true, kind)]).then(function (r) {
      var up = kind === 'upcoming';
      function ok(t) { return up ? (t.date || '') > today : (t.date || '') <= today; }
      var mv = r[0].filter(ok), tv = r[1].filter(ok), out = [], seen = {};
      for (var i = 0; i < Math.max(mv.length, tv.length); i++) {
        [mv[i], tv[i]].forEach(function (t) {
          if (!t) return;
          var k = X.externalID(t.id, t.isShow);
          if (!seen[k]) { seen[k] = true; out.push(t); }
        });
      }
      out = out.slice(0, 40);
      // Binnenkort: de 40 populairste, op datum (eerst wat het eerst uitkomt)
      if (up) out.sort(function (a, b) { return (a.date || '') < (b.date || '') ? -1 : (a.date || '') > (b.date || '') ? 1 : (a.title < b.title ? -1 : 1); });
      return out;
    });
  }
  var inFlight = {};
  X.load = function (kind, kids) {
    var c = saved(kind, kids);
    if (c && c.items && c.items.length && Date.now() - c.at < 12 * 3600000) return Promise.resolve(X.cached(kind, kids));
    var k = cacheKey(kind, kids);
    if (inFlight[k]) return inFlight[k];
    var p = inFlight[k] = fetchKind(kind, kids).then(function (items) {
      delete inFlight[k];
      if (!items.length) return X.cached(kind, kids);
      var v = { at: Date.now(), items: items };
      mem[k] = v;
      B.store.set(k, v);
      remember(items);
      return items;
    }, function () { delete inFlight[k]; return X.cached(kind, kids); });
    return p;
  };

  // MARK: koppelen aan je bibliotheek

  /** Rijen die in de Home-indeling aan staan (geen indeling of de sleutel ontbreekt = aan). */
  X.enabledKinds = function () {
    var layout = D.homeLayout && D.homeLayout();
    return X.KINDS.filter(function (k) {
      if (!layout) return true;
      for (var i = 0; i < layout.length; i++) if (layout[i] && layout[i].key === k) return layout[i].visible !== false;
      return true;
    });
  };
  function idxReady() { return !!(B.details && B.details.indexReadyNow && B.details.indexReadyNow()); }
  var stamp = 0;
  /**
   * Kaarten van een rij: [{type, item, external, t}] — item uit je bibliotheek als hij daar staat, anders de TMDB-titel.
   * Profielen met een leeftijdsgrens zien alleen titels uit hun (gefilterde) bibliotheek, en Nieuw op Bingr (build 112)
   * altijd. Max. 30, geen dubbele.
   */
  X.entries = function (kind) {
    var kids = kidsProfile(), only = ageLimited() || X.onlyLibrary(kind), ready = idxReady();
    var c = saved(kind, kids);
    var key = 'disc:' + kind + ':' + (kids ? 'k' : '') + (only ? 'a' : '') + ':' + (c ? c.at : 0) + ':' + ready + ':' + stamp;
    return Lib.memo(key, function () {
      var out = [], seen = {};
      X.cached(kind, kids).forEach(function (t) {
        if (out.length >= 30) return;
        var hit = ready ? B.details.match({ title: t.title, original: t.original, year: t.date ? B.int(t.date.slice(0, 4)) : null, isShow: t.isShow }) : null;
        if (only && !hit) return;
        var type = t.isShow ? 'series' : 'movie';
        var item = hit || X.asItem(t);
        var k = type + ':' + item.id;
        if (seen[k]) return;
        seen[k] = true;
        out.push({ type: type, item: item, external: !hit, t: t });
      });
      return out;
    });
  };

  /** Ophalen (alleen de rijen die aan staan) en de titelindex; true als er iets nieuws is. */
  X.loadAll = function () {
    var kids = kidsProfile();
    // build 140: Nieuw op Bingr ("streaming") komt uit je eigen bibliotheek (js/newonbingr.js), niet meer uit TMDB
    var kinds = X.enabledKinds().filter(function (k) { return k !== 'streaming'; });
    if (!kinds.length) return Promise.resolve(false);
    var before = kinds.map(function (k) { var c = saved(k, kids); return c ? c.at : 0; }).join() + idxReady();
    var idx = B.details && B.details.indexReady ? B.details.indexReady().catch(B.noop) : Promise.resolve();
    return Promise.all(kinds.map(function (k) { return X.load(k, kids); }).concat([idx])).then(function () {
      var after = kinds.map(function (k) { var c = saved(k, kids); return c ? c.at : 0; }).join() + idxReady();
      return after !== before;
    }, function () { return false; });
  };

  // MARK: Nieuw toegevoegd (films én series, laatste 30 dagen)

  X.recentTitles = function () {
    return Lib.memo('recentTitles30', function () {
      var lib = Lib.lib, cutoff = Date.now() - 30 * 86400000, out = [], seen = {};
      function add(list, type) {
        (list || []).forEach(function (x) {
          if (!x.addedAt || x.addedAt < cutoff || seen[type + x.id]) return;
          seen[type + x.id] = true;
          out.push({ type: type, item: x });
        });
      }
      add(lib.movies, 'movie');
      add(lib.series, 'series');
      return out.sort(function (a, b) { return b.item.addedAt - a.item.addedAt; }).slice(0, 30);
    });
  };

  // MARK: Mijn lijst

  /** TMDB-titels uit Mijn lijst van deze bron ([{type, item}]); niet bij een leeftijdsgrens. */
  X.myListHits = function () {
    if (ageLimited()) return [];
    var out = [], seen = {};
    // build 141: ook onder een andere server-id (op een ander apparaat of met een andere actieve server toegevoegd)
    D.setList('myList').forEach(function (k) {
      var id = B.b141.myList.externalID(k);
      if (!id || seen[id]) return;
      seen[id] = true;
      var t = X.known(id);
      if (t) out.push({ type: t.isShow ? 'series' : 'movie', item: X.asItem(t) });
    });
    return out;
  };
  function fetchOne(p) {
    return AR.tmdb((p.isShow ? '/tv/' : '/movie/') + p.id, [['language', AR.tmdbLang()]]).then(function (f) {
      var t = f && ((p.isShow ? f.name : f.title) || f.title || f.name);
      if (!t) return null;
      var o = p.isShow ? f.original_name : f.original_title;
      return { id: p.id, isShow: p.isShow, title: t, original: o && o !== t ? o : null, date: (p.isShow ? f.first_air_date : f.release_date) || null,
        poster: f.poster_path || null, backdrop: f.backdrop_path || null, overview: f.overview || null, kind: 'streaming' };
    }, function () { return null; });
  }
  var resolving = false;
  /** TMDB-titels in Mijn lijst die deze tv nog niet kent (op een ander apparaat toegevoegd) opzoeken. */
  X.resolveMissing = function () {
    if (resolving || !AR.hasKey()) return;
    // build 141: van alle servers (een TMDB-titel hoort bij geen server)
    var ids = D.setList('myList').map(function (k) { return B.b141.myList.externalID(k); })
      .filter(function (id, i, a) { return id && a.indexOf(id) === i && !X.known(id); }).slice(0, 20);
    if (!ids.length) return;
    resolving = true;
    Promise.all(ids.map(function (id) { var p = X.parse(id); return p ? fetchOne(p) : null; })).then(function (list) {
      resolving = false;
      list = list.filter(Boolean);
      if (!list.length) return;
      remember(list);
      stamp++;
      B.emit('discover');
    }, function () { resolving = false; });
  };

  // MARK: rijen op Home

  /** Korte datum ("17 okt", "Oct 17"; in de app-taal), alleen bij Binnenkort op Bingr; zonder punt achter de maand. */
  function dateLabel(t) {
    if (t.kind !== 'upcoming' || !t.date) return null;
    try { return new Date(t.date + 'T12:00:00').toLocaleDateString(locale(), { day: 'numeric', month: 'short' }).replace(/\.$/, ''); } catch (e) { return t.date; }
  }
  /** Rij, of null als hij leeg is (build 112: zonder bronvermelding eronder; die staat in Instellingen → Over). */
  X.row = function (kind) {
    var list = X.entries(kind);
    var cards = list.map(function (e) { return S.posterCard(e.item, e.type, { badge: e.external ? dateLabel(e.t) : null }); });
    return S.rowBlock(X.title(kind), cards, 'discover-row');
  };

  /** Regels op de detailpagina van een TMDB-titel: niet in je afspeellijst en de datum (build 112: zonder bronvermelding). */
  X.note = function (id) {
    var t = X.known(id);
    var first = L('Not in your playlists');
    if (t && t.date) {
      var d = t.date;
      try { d = new Date(t.date + 'T12:00:00').toLocaleDateString(locale(), { day: 'numeric', month: 'long', year: 'numeric' }); } catch (e) { /* stil */ }
      first += ' · ' + L('Release date: %@', d).replace('%@', d);
    }
    return [first];
  };

  // MARK: detailpagina van een TMDB-titel (vanuit S.detail)

  function trailerKey(p) {
    var lang = AR.tmdbLang(), base = lang.slice(0, 2);
    return AR.tmdb((p.isShow ? '/tv/' : '/movie/') + p.id + '/videos', [['language', lang], ['include_video_language', base + ',en,null']]).then(function (r) {
      var v = ((r && r.results) || []).filter(function (x) { return x.site === 'YouTube' && (x.type === 'Trailer' || x.type === 'Teaser'); });
      v.sort(function (a, b) { return (a.type === 'Trailer' ? 0 : 1) - (b.type === 'Trailer' ? 0 : 1); });
      return v[0] ? v[0].key : null;
    }, function () { return null; });
  }
  X.detail = function (type, x) {
    var isShow = type === 'series';
    var p = X.parse(x.id) || { id: x.tmdb, isShow: isShow };
    var P = B.parity;
    var alive = true, trailer = null;
    // build 141: een bestaande sleutel van een andere server-id telt ook (zie B.parity.listKey)
    var listKey = B.parity && B.parity.listKey ? B.parity.listKey(x) : D.key(Lib.source ? Lib.source.id : '', x.id);
    var meta = h('div.dt-meta', [x.year ? String(x.year) : '', isShow ? L('Series') : L('Movie')].filter(Boolean).join('  ·  '));
    var plot = h('p.dt-plot', x.plot || '');
    var creditsEl = h('div.dt-credits'), extraEl = h('div.dt-extra'), altEl = h('div.dt-alt-slot');
    var note = h('div.dt-note.dim', X.note(x.id).map(function (line) { return h('div', line); }));
    var buttons = h('div.dt-buttons.fm');
    var body = S.vScroller('.dt-body', { 'data-pad': '60' });
    body.track.appendChild(h('div.dt-poster', [U.img(x.poster, 'poster-img', x.title, true)]));
    body.track.appendChild(h('div.dt-info', [h('div.dt-kind', isShow ? L('SERIES') : L('FILM')), h('h1.dt-title', x.title), meta, plot, creditsEl, buttons, note]));
    body.track.appendChild(altEl);
    body.track.appendChild(extraEl);
    body.track.appendChild(h('div.dt-end'));
    var bg = h('div.dt-bg', [h('div.dt-kb', [U.img(x.backdrop || x.poster, 'hero-img', null, true)])]);
    var el = h('div.layer.detail.discover-detail', [bg, h('div.dt-shade'), body]);
    function drawButtons() {
      var f = U.focused && buttons.contains(U.focused) ? U.focused._bk : null;
      U.clear(buttons);
      if (Lib.source) {
        var on = D.inSet('myList', listKey);
        // plus / vinkje: de knop blijft staan (met focus), alleen het icoon en de tekst wisselen
        var b = U.plusCheckButton('mylist', on ? L('Remove from My List') : L('Add to My List'), on, function () {
          var now = P ? P.toggleMyList(type, x) : (D.toggleSet('myList', listKey), D.inSet('myList', listKey));
          U.toast(now ? L('Added to My List') : L('Removed from My List'));
          U.setPlusCheck(b, now);
          var lbl = b.querySelector('.lbl');
          if (lbl) lbl.textContent = now ? L('Remove from My List') : L('Add to My List');
        }, { primary: true, autofocus: true });
        b._bk = 'list';
        buttons.appendChild(b);
      }
      if (trailer && P && P.playTrailer) {
        var tb = U.button(L('Trailer'), function () { P.playTrailer(trailer); }, { icon: 'trailer' });
        tb._bk = 'trailer';
        buttons.appendChild(tb);
      }
      if (f) { var c = buttons.querySelectorAll('.f'); for (var i = 0; i < c.length; i++) if (c[i]._bk === f) { U.focus(c[i], true); break; } }
    }
    drawButtons();
    trailerKey(p).then(function (k) { if (!alive || !k) return; trailer = k; drawButtons(); });
    // cast, genres en aanbevelingen (js/details.js, met het TMDB-id als hint) en Andere bronnen (andere afspeellijsten)
    if (B.details) B.details.load(x, isShow, { tmdb: p.id, tmdb_id: p.id }).then(function (d) {
      if (!alive || !d) return;
      if (d.overview) plot.textContent = d.overview;
      if (d.runtime) meta.textContent += '  ·  ' + d.runtime;
      U.clear(creditsEl);
      U.append(creditsEl, B.details.credits(d));
      U.clear(extraEl);
      B.details.sections(d, extraEl);
    }, B.noop);
    if (P && P.alternatives) P.alternatives(type, x).then(function (list) {
      if (!alive || !list || !list.length) return;
      U.clear(altEl);
      var r = S.rowBlock(L('Other sources'), list.map(P.otherCard), 'dt-alts');
      if (r) altEl.appendChild(r);
    }, B.noop);
    return {
      el: el,
      onShow: function (back) { if (back) drawButtons(); },
      onBack: function () { U.pop(); return true; },
      destroy: function () { alive = false; U.forgetImgs(el); }
    };
  };
})();
