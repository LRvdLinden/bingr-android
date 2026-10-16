/*
 * build 102: gelijk met de Apple TV (tvOS) — wat de schermen in screens.js hier gebruiken:
 *  - contextmenu op kaarten (lang OK of de Tools-toets): Mijn lijst, collecties, bekeken, Verder kijken, zender-acties;
 *  - collecties maken en vullen (state-section `collections`, version 2, emmer per profiel; ACCOUNT.md);
 *  - bekeken/niet bekeken voor films, afleveringen en hele seizoenen (ook naar Trakt);
 *  - "Bekijk allemaal": rasterpagina met sorteren (Standaard, A–Z, Z–A, Nieuwste, Hoogst gewaardeerd);
 *  - Home: "Voor jou" (Trakt: lijkt op wat je keek + aanbevelingen, aangevuld uit je groepen) en Top 10 uit
 *    TMDB/Trakt trending (kinderprofiel: populaire animatie/familie), gekoppeld aan je bibliotheek;
 *  - evenementzenders (PPV/EVENT-groepen, begintijd uit de naam) voor Live TV;
 *  - andere afspeellijsten (zoeken, "Andere bronnen") en wisselen van bron;
 *  - zoeken naar personen, programma's in de tv-gids en categorieën;
 *  - trailer beeldvullend met geluid en achtergronden die rustig in elkaar overlopen (Ken Burns).
 * Geschreven voor Chromium M56 (ES2017, geen ?. ?? of object-spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui, D = B.data, A = B.account, Lib = B.lib, S = B.screens, T = B.trakt, AR = B.ageRatings;
  var h = U.h;
  var L = function () { return B.L.apply(null, arguments); };
  var P = B.parity = {};
  function lower(s) { return String(s == null ? '' : s).toLowerCase(); }
  function src() { return Lib.source ? Lib.source.id : ''; }
  function isShowType(type) { return type === 'series'; }

  // MARK: collecties schrijven (ACCOUNT.md: version 2, per profiel een emmer; per collectie wint de nieuwste updatedAt)

  function profileId() { return B.app && B.app.profile ? B.app.profile.id : null; }
  /** Wijziging op een section-object toepassen: collectie `coll` (volledig, met updatedAt) in de emmer van `pid`. */
  function applyCollection(data, pid, coll) {
    data = data && typeof data === 'object' ? B.copy(data) : {};
    var migrated = (B.int(data.version) || 0) >= 2 || (data.profiles && typeof data.profiles === 'object' && !Array.isArray(data.profiles));
    if (!migrated) {
      // nog het oude formaat: oude collecties alleen naar de emmer van het hoofdprofiel (build 74), daarna version 2
      data.profiles = {};
      var main = D.mainProfile();
      if (main && Array.isArray(data.items) && data.items.length) data.profiles[main.id] = { items: B.copy(data.items), updatedAt: data.updatedAt || B.iso() };
      data.migratedAt = B.iso();
    }
    data.version = Math.max(2, B.int(data.version) || 0);
    data.profiles = data.profiles && typeof data.profiles === 'object' ? data.profiles : {};
    var key = null;
    Object.keys(data.profiles).forEach(function (k) { if (lower(k) === lower(pid)) key = key || k; });
    key = key || pid;
    var bucket = data.profiles[key] && typeof data.profiles[key] === 'object' ? data.profiles[key] : { items: [] };
    var items = (Array.isArray(bucket.items) ? bucket.items : []).filter(function (x) { return !x || lower(x.id) !== lower(coll.id); });
    items.push(coll);
    bucket.items = items;
    bucket.updatedAt = coll.updatedAt;
    data.profiles[key] = bucket;
    data.updatedAt = B.iso();
    return data;
  }
  var collChain = Promise.resolve();
  /** Collectie bewaren: meteen lokaal, daarna in de nieuwste serverversie (alleen deze collectie aangepast). */
  P.saveCollection = function (coll) {
    var pid = profileId();
    if (!pid) return Promise.resolve(false);
    coll.updatedAt = B.iso();
    D.collections = applyCollection(D.collections, pid, coll);
    B.store.set('collections', D.collections);
    B.emit('profileData');
    collChain = collChain.then(function () {
      return A.getState(['collections']).then(function (all) {
        if (!all) return false;
        var cur = all.collections && all.collections.data;
        var data = applyCollection(cur, pid, coll);
        return A.putState('collections', data).then(function (at) {
          if (at == null) return false;
          D.collections = data;
          B.store.set('collections', D.collections);
          return true;
        });
      });
    }).catch(function (e) { B.log('collecties', e && e.message); return false; });
    return collChain;
  };
  P.manualCollections = function () {
    var pid = profileId();
    return pid ? D.collectionsFor(pid).filter(function (c) { return c.kind !== 'smart'; }) : [];
  };
  function inCollection(c, type, id) {
    return (c.items || []).some(function (it) { return it && it.id === id && (!it.type || it.type === type); });
  }
  /** Item toevoegen aan (of weghalen uit) een handmatige collectie. */
  P.toggleInCollection = function (c, type, item) {
    var copy = B.copy(c);
    var has = inCollection(copy, type, item.id);
    copy.items = (copy.items || []).filter(function (it) { return !(it && it.id === item.id && (!it.type || it.type === type)); });
    if (!has) copy.items.push({ type: type, id: item.id });
    P.saveCollection(copy);
    U.toast(has ? L('Removed from %@', c.name) : L('Added to %@', c.name));
    return !has;
  };
  /** Nieuwe handmatige collectie met dit item. */
  P.newCollection = function (name, type, item) {
    name = String(name || '').trim();
    if (!name) return null;
    var order = 0;
    D.collectionsFor(profileId()).forEach(function (c) { order = Math.max(order, (B.int(c.order) || 0) + 1); });
    var coll = { id: B.uuid().toUpperCase(), name: name, image: { symbol: 'popcorn', colorIndex: Math.floor(Math.random() * D.palettes.length) },
      kind: 'manual', items: item ? [{ type: type, id: item.id }] : [], order: order };
    P.saveCollection(coll);
    U.toast(L('Added to %@', name));
    return coll;
  };
  /** "Aan collectie toevoegen…": bestaande handmatige collecties (● = staat erin) en "Nieuwe collectie". */
  P.chooseCollection = function (type, item) {
    var list = P.manualCollections();
    var opts = list.map(function (c) { return { value: c.id, label: c.name }; });
    opts.push({ value: '+', label: '+  ' + L('New collection') });
    var current = null;
    list.forEach(function (c) { if (!current && inCollection(c, type, item.id)) current = c.id; });
    U.choose(L('Add to collection'), opts, current, function (v) {
      if (v === '+') {
        U.editText({ title: L('New collection'), value: '', layout: 'text', placeholder: L('Name') }, function (name) { P.newCollection(name, type, item); });
        return;
      }
      var c = null;
      list.forEach(function (x) { if (x.id === v) c = x; });
      if (c) P.toggleInCollection(c, type, item);
    });
  };

  // MARK: bekeken (lokaal per profiel + Trakt)

  function traktItem(x, type, ep) {
    if (type === 'episode') return { title: x.rawTitle || x.title, year: x.year || null, type: 'episode', season: ep.season, episode: ep.number, imdbID: x.imdbID || null };
    return { title: x.rawTitle || x.title, year: x.year || null, type: 'movie', imdbID: x.imdbID || null };
  }
  P.movieWatched = function (m) { return D.isWatched(m.id) || T.isMovieWatched(m); };
  P.setMovieWatched = function (m, on) {
    D.setWatched(m.id, on);
    if (on) D.clearProgress(m.id);
    T.setWatched(traktItem(m, 'movie'), on);
  };
  P.episodeWatched = function (s, e) {
    return D.isWatched(e.id) || D.isWatched(D.episodeKey(e.seriesID || s.id, e.season, e.number)) || T.isEpisodeWatched(s, e.season, e.number);
  };
  /** Afleveringen (één, een seizoen of de hele serie) als (niet) bekeken; zelfde sleutels als iOS (id en ep:serie|s-e). */
  P.setEpisodesWatched = function (s, eps, on) {
    eps.forEach(function (e) {
      D.setWatched(e.id, on);
      D.setWatched(D.episodeKey(e.seriesID || s.id, e.season, e.number), on);
      if (on) D.clearProgress(e.id);
    });
    if (eps.length === 1) T.setWatched(traktItem(s, 'episode', eps[0]), on);
    else T.setWatchedEpisodes(s, eps, on);
  };
  /** Hele serie (vanaf een kaart): afleveringen laden; alles bekeken → alles terug, anders alles bekeken. */
  P.toggleSeriesWatched = function (s) {
    U.toast(L('Loading…'));
    return Lib.episodes(s).then(function (r) {
      var eps = (r && r.episodes) || [];
      if (!eps.length) { U.toast(L('No episodes found')); return; }
      var all = eps.every(function (e) { return P.episodeWatched(s, e); });
      P.setEpisodesWatched(s, eps, !all);
      U.toast(all ? L('Marked as unwatched') : L('Marked as watched'));
    });
  };
  /** Mijn lijst aan/uit; voor het hoofdprofiel ook op de Trakt-watchlist (= TraktSync.setWatchlist). */
  P.toggleMyList = function (type, x) {
    var k = D.key(src(), x.id);
    D.toggleSet('myList', k);
    var on = D.inSet('myList', k);
    if (type === 'movie' || type === 'series') T.setWatchlist(x, type === 'series', on);
    return on;
  };

  // MARK: contextmenu (lang OK of Tools)

  /** Menu als modal met pictogrammen (focus blijft erin; Terug sluit). items = [{icon, label, fn}] */
  P.menu = function (title, items, sub) {
    var list = h('div.pm-list.sy.fm', { 'data-pad': '40' });
    items.forEach(function (it) {
      if (!it) return;
      var el = h('div.choice.pm-item.f' + (it.danger ? '.danger' : ''), [U.icon(it.icon || 'list'), h('span.pm-label', it.label)]);
      el.onEnter = function () { U.pop(); it.fn(); };
      el._mk = it.key || it.label;
      list.appendChild(el);
    });
    var box = h('div.modal-box.ctx-box', [h('div.ctx-title', title), sub ? h('div.ctx-sub.dim', sub) : null, list]);
    var s = { modal: true, el: h('div.layer.modal.ctx-layer', [box]) };
    s.onBack = function () { U.pop(); };
    U.push(s);
    return s;
  };
  /**
   * Contextmenu van een kaart. ctx = {type: 'movie'|'series'|'channel', item, progressKey?, collection?, list?}
   * (= contextMenu van de kaarten op tvOS: HomeView/LiveTVView).
   */
  P.cardMenu = function (ctx) {
    var x = ctx.item, type = ctx.type, items = [];
    if (type === 'channel') {
      var fk = D.key(src(), x.id), fav = D.inSet('favorites', fk);
      items.push({ key: 'play', icon: 'play', label: L('Watch live'), fn: function () { B.player.playChannel(x, ctx.list || [x]); } });
      items.push({ key: 'fav', icon: 'star', label: fav ? L('Remove from favorites') : L('Add to favorites'), fn: function () {
        D.toggleSet('favorites', fk);
        U.toast(D.inSet('favorites', fk) ? L('Added to favorites') : L('Removed from favorites'));
      } });
      items.push({ key: 'guide', icon: 'guide', label: L('TV guide'), fn: function () {
        var list = ctx.list && ctx.list.length ? ctx.list : [x];
        U.push(S.guide(list, Math.max(0, list.indexOf(x))));
      } });
      items.push({ key: 'coll', icon: 'grid', label: L('Add to collection…'), fn: function () { P.chooseCollection('channel', x); } });
      if (ctx.collection && ctx.collection.kind !== 'smart') items.push({ key: 'uncoll', icon: 'back', label: L('Remove from collection'), fn: function () { P.toggleInCollection(ctx.collection, 'channel', x); } });
      if (x.catchupDays > 0 && Lib.source && D.isMain(B.app.profile)) items.push({ key: 'offset', icon: 'replay', label: L('Catch-up offset…'), fn: function () { S.catchupOffset(x); } });
      items.push({ key: 'mv', icon: 'grid', label: L('Open in Multiview'), fn: function () {
        var list2 = ctx.list && ctx.list.length > 1 ? ctx.list : Lib.lib.channels;
        B.multiview.open(list2, Math.max(0, list2.indexOf(x)));
      } });
      return P.menu(x.name, items);
    }
    var lk = D.key(src(), x.id), inList = D.inSet('myList', lk);
    items.push({ key: 'open', icon: 'info', label: L('More info'), fn: function () { U.push(S.detail(type, x)); } });
    items.push({ key: 'mylist', icon: inList ? 'check' : 'list', label: inList ? L('Remove from My List') : L('Add to My List'), fn: function () {
      var on = P.toggleMyList(type, x);
      U.toast(on ? L('Added to My List') : L('Removed from My List'));
    } });
    // build 111: titel uit TMDB (niet in je bibliotheek): alleen Meer info en Mijn lijst
    if (x.external) return P.menu(x.title, items, x.year ? String(x.year) : null);
    items.push({ key: 'coll', icon: 'grid', label: L('Add to collection…'), fn: function () { P.chooseCollection(type, x); } });
    if (ctx.collection && ctx.collection.kind !== 'smart') items.push({ key: 'uncoll', icon: 'back', label: L('Remove from collection'), fn: function () { P.toggleInCollection(ctx.collection, type, x); } });
    if (type === 'movie') {
      var w = P.movieWatched(x);
      items.push({ key: 'watched', icon: 'check', label: w ? L('Mark as unwatched') : L('Mark as watched'), fn: function () {
        P.setMovieWatched(x, !w);
        U.toast(w ? L('Marked as unwatched') : L('Marked as watched'));
      } });
    } else {
      var n = S.watchedEpisodeCount ? S.watchedEpisodeCount(x) : 0;
      items.push({ key: 'watched', icon: 'check', label: n ? L('Mark as unwatched') : L('Mark as watched'), fn: function () { P.toggleSeriesWatched(x); } });
    }
    if (ctx.progressKey) items.push({ key: 'continue', icon: 'back', label: L('Remove from Continue watching'), fn: function () {
      D.clearProgress(ctx.progressKey);
      U.toast(L('Removed from Continue watching'));
    } });
    return P.menu(x.title, items, x.year ? String(x.year) : null);
  };
  /** Kaart een contextmenu geven (lang OK / Tools). */
  P.attachMenu = function (card, ctx) {
    card._ctx = ctx;
    card._menu = function () { P.cardMenu(card._ctx); };
    return card;
  };

  // MARK: sorteren en "Bekijk allemaal"

  P.SORTS = [['default', 'Default'], ['az', 'A–Z'], ['za', 'Z–A'], ['newest', 'Newest'], ['rating', 'Highest rated']];
  var collator = null;
  function coll() {
    if (collator === null) { try { collator = new Intl.Collator(B.lang || undefined, { sensitivity: 'base' }); } catch (e) { collator = false; } }
    return collator;
  }
  function titleOf(x) { return String(x.title || x.name || ''); }
  /** Stabiel sorteren (oude Chromium sorteert niet stabiel). get(e) geeft het item van een entry. */
  P.sort = function (list, sort, get) {
    get = get || function (e) { return e; };
    if (!sort || sort === 'default') return list;
    var out = list.map(function (e, i) { return { e: e, i: i }; });
    function num(v) { return v == null || isNaN(v) ? -1 : +v; }
    var c = coll(), cmp;
    if (sort === 'az' || sort === 'za') {
      var dir = sort === 'az' ? 1 : -1;
      cmp = function (a, b) { var ta = titleOf(get(a.e)), tb = titleOf(get(b.e)); return dir * (c ? c.compare(ta, tb) : ta.localeCompare(tb)); };
    } else if (sort === 'newest') {
      cmp = function (a, b) { var x = get(a.e), y = get(b.e); return num(y.addedAt) - num(x.addedAt) || num(y.year) - num(x.year); };
    } else cmp = function (a, b) { return num(get(b.e).rating) - num(get(a.e).rating); };
    out.sort(function (a, b) { return cmp(a, b) || a.i - b.i; });
    return out.map(function (x) { return x.e; });
  };
  function pill(label, active, fn) {
    var p = h('div.cat-pill.f' + (active ? '.active' : ''), label);
    p.onEnter = fn;
    return p;
  }
  /** Rij met sorteerknoppen; onPick(sleutel). */
  P.sortPills = function (cur, onPick, cls) {
    var row = h('div.cat-pills.sort-pills.fm' + (cls ? '.' + cls : ''));
    P.SORTS.forEach(function (s) {
      var p = pill(L(s[1]), s[0] === cur, function () { onPick(s[0]); });
      p._sort = s[0];
      row.appendChild(p);
    });
    return row;
  };
  /**
   * "Bekijk allemaal" (= CategoryView op tvOS): kop met aantal en sorteren, raster met alle titels.
   * entries = [{type, item}] (films/series; zenders worden zenderkaarten).
   */
  S.gridPage = function (title, entries) {
    var sort = 'default';
    var count = h('div.cp-count.dim');
    var controls = h('div.cp-controls');
    var gridBox = h('div.cp-gridbox');
    var el = h('div.layer.catpage.gridpage', [h('div.cp-head', [h('h1.cp-title', title), count, controls]), gridBox]);
    var list = [], grid = null;
    function draw() {
      list = P.sort(entries, sort, function (e) { return e.item; });
      count.textContent = list.length === 1 ? L('1 title') : L('%d titles', list.length);
      U.clear(controls);
      controls.appendChild(P.sortPills(sort, function (k) {
        sort = k;
        draw();
        var a = controls.querySelector('.cat-pill.active');
        if (a) U.focus(a, true);
      }));
      if (!grid) {
        grid = U.vgrid({ count: list.length, cols: 7, cellW: 238, cellH: 345, height: 700, render: function (i) {
          var e = list[i];
          if (e.type === 'channel') return S.channelCard(e.item, entries.filter(function (x) { return x.type === 'channel'; }).map(function (x) { return x.item; }));
          var p = e.type === 'movie' ? D.progress(e.item.id) : null;
          return S.posterCard(e.item, e.type, { progress: p ? p.fraction : null });
        } });
        gridBox.appendChild(grid.el);
      } else grid.reset(list.length);
    }
    draw();
    return {
      el: el,
      onShow: function (back) {
        if (back) { if (grid) grid.redrawVisible(); return; }
        if (list.length) grid.focusIndex(0); else U.focusFirst(el);
      },
      onBack: function () { U.pop(); return true; },
      destroy: function () { U.forgetImgs(el); },
      order: function () { return list.map(function (e) { return e.item.id; }); }
    };
  };
  P.openGrid = function (title, entries) { if (entries && entries.length) U.push(S.gridPage(title, entries)); };
  /** Kaart "Bekijk allemaal" aan het eind van een rij. */
  P.seeAllCard = function (fn) {
    var c = h('div.card.poster.see-all.f', [h('div.see-all-in', [U.icon('grid'), h('div.see-all-lbl', L('See all'))])]);
    c.onEnter = fn;
    c._key = 'seeall';
    return c;
  };

  // MARK: trending (Top 10) en "Voor jou"

  function tmdbTitles(list) {
    return ((list && list.results) || []).map(function (i) {
      var date = i.release_date || i.first_air_date || '';
      return { title: i.title || i.name || '', original: i.original_title || i.original_name || null, year: B.int(date.slice(0, 4)) || null };
    }).filter(function (t) { return t.title; });
  }
  function tmdbPages(path, params) {
    if (!AR.hasKey()) return Promise.resolve([]);
    return Promise.all(['1', '2'].map(function (pg) {
      return AR.tmdb(path, params.concat([['page', pg]])).then(tmdbTitles, function () { return []; });
    })).then(function (r) { return r[0].concat(r[1]); });
  }
  /** Trending vandaag (TMDB in de app-taal + Trakt), of populaire animatie/familie voor kinderprofielen; 6 uur bewaard. */
  function trendKey(shows, kids) { return 'trending.' + (kids ? 'kids.' : '') + (shows ? 'tv' : 'movie') + '.' + B.lang; }
  var trendMem = {};
  P.trendingCached = function (shows, kids) {
    var k = trendKey(shows, kids);
    if (!trendMem[k]) trendMem[k] = B.store.get(k, null);
    return trendMem[k];
  };
  P.trending = function (shows, kids) {
    var c = P.trendingCached(shows, kids);
    if (c && c.items && c.items.length && Date.now() - c.at < 6 * 3600000) return Promise.resolve(c.items);
    var lang = AR.tmdbLang(), p;
    if (kids) {
      var q = [['language', lang], ['sort_by', 'popularity.desc'], ['include_adult', 'false'], ['with_genres', shows ? '10762|16|10751' : '16|10751']];
      if (!shows) q.push(['certification_country', 'US'], ['certification.lte', 'PG']);
      p = tmdbPages(shows ? '/discover/tv' : '/discover/movie', q);
    } else {
      p = Promise.all([tmdbPages(shows ? '/trending/tv/day' : '/trending/movie/day', [['language', lang]]),
        T.trending(shows).catch(function () { return []; })]).then(function (r) { return r[0].concat(r[1]); });
    }
    return p.then(function (items) {
      if (items.length) {
        var v = { at: Date.now(), items: items.slice(0, 120) };
        trendMem[trendKey(shows, kids)] = v;
        B.store.set(trendKey(shows, kids), v);
        return v.items;
      }
      return c && c.items ? c.items : [];
    }, function () { return c && c.items ? c.items : []; });
  };
  function matchAll(list, isShow, limit) {
    var out = [], seen = {};
    for (var i = 0; i < list.length && out.length < limit; i++) {
      var t = list[i];
      var hit = B.details.match({ title: t.title, original: t.original || null, year: t.year || null, isShow: isShow });
      if (hit && !seen[hit.id]) { seen[hit.id] = true; out.push(hit); }
    }
    return out;
  }
  /** Top 10 van deze soort: trending gekoppeld aan je lijst; minder dan 5 treffers → aangevuld met je eigen Top 10. */
  P.top10 = function (type, full) {
    var shows = type === 'series', kids = !!(B.app.profile && B.app.profile.kids);
    var items = shows ? Lib.lib.series : Lib.lib.movies;
    var c = P.trendingCached(shows, kids);
    var key = 'ptop:' + type + ':' + (kids ? 'k' : '') + ':' + (c ? c.at : 0) + ':' + !!B.details.indexReadyNow();
    return Lib.memo(key, function () {
      var found = c && c.items ? matchAll(c.items, shows, 40) : [];
      var local = Lib.top10(items);
      if (found.length >= 5) return found;
      return found.concat(local.filter(function (x) { return found.indexOf(x) < 0; }));
    }).slice(0, full ? 40 : 10);
  };
  /** Trending ophalen en de titelindex bouwen; true als er iets nieuws is (Home tekent dan Top 10 / Voor jou opnieuw). */
  P.loadTrending = function () {
    var kids = !!(B.app.profile && B.app.profile.kids);
    var before = [P.trendingCached(false, kids), P.trendingCached(true, kids)].map(function (c) { return c ? c.at : 0; }).join();
    var ready = B.details.indexReadyNow();
    return Promise.all([P.trending(false, kids), P.trending(true, kids), B.details.indexReady().catch(B.noop)]).then(function () {
      var after = [P.trendingCached(false, kids), P.trendingCached(true, kids)].map(function (c) { return c ? c.at : 0; }).join();
      return after !== before || (!ready && B.details.indexReadyNow());
    }, function () { return false; });
  };

  function shuffle(a) {
    a = a.slice();
    for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)); var t = a[i]; a[i] = a[j]; a[j] = t; }
    return a;
  }
  var forYou = { key: null, list: [] };
  function fyKey() { return (profileId() || '') + '|' + src() + '|' + Lib.version; }
  /** "Voor jou" uit de cache van dit profiel en deze bibliotheek ([{type, item}]), alfabetisch zoals op tvOS. */
  P.forYou = function () { return forYou.key === fyKey() ? forYou.list : []; };
  /**
   * "Voor jou" opbouwen (= buildForYou op tvOS): lijkt op wat je (half) keek (Trakt related), persoonlijke Trakt-
   * aanbevelingen, onbekeken titels uit de groepen waar je het meest kijkt, aangevuld met trending. Zonder titels met
   * voortgang of die al bekeken zijn. Promise → true als de lijst veranderd is.
   */
  var fyBusy = null;
  P.loadForYou = function () {
    if (fyBusy) return fyBusy;
    var key = fyKey();
    var lib = Lib.lib;
    if (!lib.movies.length && !lib.series.length) return Promise.resolve(false);
    var out = [], seen = {};
    function add(type, x) {
      if (!x || seen[type + x.id]) return;
      if (D.progress(x.id)) return;
      if (type === 'movie' && P.movieWatched(x)) return;
      if (type === 'series' && S.watchedEpisodeCount && S.watchedEpisodeCount(x) > 0) return;
      seen[type + x.id] = true;
      out.push({ type: type, item: x });
    }
    var byTitle = {};
    lib.series.forEach(function (s) { if (!byTitle[s.title]) byTitle[s.title] = s; });
    fyBusy = B.details.indexReady().catch(B.noop).then(function () {
      // 1. vergelijkbaar met wat je (half) keek
      var seeds = [];
      D.progressList().slice(0, 12).forEach(function (pe) {
        if (seeds.length >= 4) return;
        var hit = lib.byId[pe.key];
        if (hit && hit.type === 'movie') seeds.push({ x: hit.item, show: false });
        else if (!hit && byTitle[pe.e.title]) seeds.push({ x: byTitle[pe.e.title], show: true });
      });
      var w = T.watchedSeeds();
      var imdbSeeds = shuffle(w.shows).slice(0, 2).map(function (i) { return { imdb: i, show: true }; })
        .concat(shuffle(w.movies).slice(0, 2).map(function (i) { return { imdb: i, show: false }; }));
      return Promise.all(seeds.map(function (s) {
        return T.imdbOf(s.x, s.show).then(function (i) { return i ? { imdb: i, show: s.show } : null; }, function () { return null; });
      })).then(function (r) {
        var all = r.filter(Boolean).concat(imdbSeeds), uniq = {}, list = [];
        all.forEach(function (s) { if (!uniq[s.imdb]) { uniq[s.imdb] = true; list.push(s); } });
        return Promise.all(list.slice(0, 6).map(function (s) {
          return T.related(s.imdb, s.show).then(function (rel) { return { show: s.show, rel: rel }; }, function () { return { show: s.show, rel: [] }; });
        }));
      }).then(function (rels) {
        rels.forEach(function (r) { matchAll(r.rel, r.show, 6).forEach(function (x) { add(r.show ? 'series' : 'movie', x); }); });
        // 2. persoonlijke Trakt-aanbevelingen
        return Promise.all([T.recommendations(false), T.recommendations(true)]);
      }).then(function (rec) {
        var rm = matchAll(rec[0] || [], false, 40), rs = matchAll(rec[1] || [], true, 40);
        for (var i = 0; i < 15; i++) { if (rm[i]) add('movie', rm[i]); if (rs[i]) add('series', rs[i]); }
        rm.slice(15, 20).forEach(function (x) { add('movie', x); });
        rs.slice(15, 20).forEach(function (x) { add('series', x); });
        // 3. groepen waar je in kijkt (voortgang, bekeken, Mijn lijst)
        var score = {};
        D.progressList().forEach(function (pe) { var hit = lib.byId[pe.key]; if (hit && hit.type === 'movie') score[hit.item.group] = (score[hit.item.group] || 0) + 1; });
        D.setList('watched').forEach(function (k) { var hit = lib.byId[k]; if (hit && hit.type === 'movie') score[hit.item.group] = (score[hit.item.group] || 0) + 1; });
        D.setList('myList').forEach(function (k) {
          var p2 = k.split('|');
          if (p2[0] !== src()) return;
          var hit = lib.byId[p2.slice(1).join('|')];
          if (hit && hit.type !== 'channel') score[hit.item.group] = (score[hit.item.group] || 0) + (hit.type === 'series' ? 2 : 1);
        });
        var groups = Object.keys(score).sort(function (a, b) { return score[b] - score[a]; }).slice(0, 4);
        var mg = Lib.byGroup('movie'), sg = Lib.byGroup('series');
        groups.forEach(function (g) {
          shuffle(mg[g] || []).slice(0, 5).forEach(function (x) { add('movie', x); });
          shuffle(sg[g] || []).slice(0, 5).forEach(function (x) { add('series', x); });
        });
        // 4. trending
        var tm = P.top10('movie'), ts = P.top10('series');
        for (var j = 0; j < 8; j++) { if (tm[j]) add('movie', tm[j]); if (ts[j]) add('series', ts[j]); }
        if (out.length < 8) {
          shuffle(lib.movies.filter(function (m) { return m.poster; })).slice(0, 6).forEach(function (x) { add('movie', x); });
          shuffle(lib.series.filter(function (s) { return s.poster; })).slice(0, 6).forEach(function (x) { add('series', x); });
        }
        var c = coll();
        var list2 = out.slice(0, 30).sort(function (a, b) {
          var ta = B.fold(a.item.title), tb = B.fold(b.item.title);
          return c ? c.compare(ta, tb) : (ta < tb ? -1 : ta > tb ? 1 : 0);
        });
        if (key !== fyKey()) return false;
        var changed = JSON.stringify(list2.map(function (e) { return e.type + e.item.id; })) !== JSON.stringify(forYou.list.map(function (e) { return e.type + e.item.id; })) || forYou.key !== key;
        forYou = { key: key, list: list2 };
        return changed;
      });
    }).catch(function (e) { B.log('voor jou', e && e.message); return false; }).then(function (r) { fyBusy = null; return r; });
    return fyBusy;
  };

  // MARK: billboard (IMDb-badge, genre en speelduur uit de metadata, = HeroBanner op tvOS)

  P.heroMeta = function (x, type, metaEl) {
    var isShow = type === 'series';
    var info = type === 'movie' ? Lib.movieInfo(x).catch(function () { return null; }) : Promise.resolve(null);
    info.then(function (i) { return B.details.load(x, isShow, i); }).then(function (d) {
      return B.details.omdb(d.imdbID, x, isShow).then(function (o) { return { d: d, o: o }; });
    }).then(function (r) {
      if (!document.body.contains(metaEl)) return;
      var d = r.d, o = r.o;
      var parts = [];
      if (x.year) parts.push(String(x.year));
      var genres = d.genres && d.genres.length ? d.genres : (o && o.genre ? o.genre.split(', ') : []);
      if (genres.length) parts.push(genres.slice(0, 2).join(' • '));
      var rt = d.runtime || (o && o.runtime) || null;
      if (rt) parts.push(rt);
      U.clear(metaEl);
      if (o && o.imdbRating) metaEl.appendChild(h('span.imdb-badge.big', [h('b', 'IMDb'), ' ' + o.imdbRating]));
      else if (d.tmdbRating) metaEl.appendChild(h('span.tmdb-badge', '★ ' + d.tmdbRating.toFixed(1)));
      if (parts.length) metaEl.appendChild(h('span.hero-tags', parts.join('  ·  ')));
      metaEl.classList.add('rich');
    }).catch(B.noop);
  };

  // MARK: evenementzenders (alleen lokaal; = EventChannels op tvOS)

  var GROUP_WORDS = ['PPV', 'EVENT', 'EVENTS', 'LIVE EVENT', 'SPORTS EVENT', 'UFC/WWE/BOXING EVENTS'];
  P.isEventGroup = function (g) {
    var u = ' ' + String(g || '').toUpperCase().replace(/_/g, ' ') + ' ';
    return GROUP_WORDS.some(function (w) {
      var i = u.indexOf(w);
      while (i >= 0) {
        var before = u.charAt(i - 1), after = u.charAt(i + w.length);
        if (!/[A-Z]/.test(before) && !/[A-Z]/.test(after || ' ')) return true;
        i = u.indexOf(w, i + 1);
      }
      return false;
    });
  };
  var MONTHS = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, oct: 10, nov: 11, dec: 12, mrt: 3, mei: 5, okt: 10, mai: 5, dez: 12 };
  var ZONE = '(?:\\s*\\(?\\s*(?:(UTC|GMT|BST|CEST|CET|EEST|EET|WEST|WET|EDT|EST|ET|CDT|CST|CT|MDT|MST|MT|PDT|PST|PT|UK|AEDT|AEST|IST)\\b)?\\s*([+-]\\d{1,2}(?::?\\d{2})?)?\\s*\\)?)';
  var MW = '(jan|feb|mar|mrt|apr|may|mei|mai|jun|jul|aug|sep|oct|okt|nov|dec|dez)[a-z]*\\.?';
  var SEP = '(?:\\s*[@\\-,|]\\s*|\\s+)';
  var TP = '(\\d{1,2})(?:[:.h](\\d{2}))?\\s*(am|pm)?';
  var PATTERNS = [
    [new RegExp('(\\d{4})-(\\d{1,2})-(\\d{1,2})[ T]+(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?' + ZONE, 'i'), 0],
    [new RegExp('\\b(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?' + SEP + '(\\d{1,2})[:.h](\\d{2})\\s*(am|pm)?' + ZONE, 'i'), 1],
    [new RegExp('\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+' + MW + '(?:\\s+(\\d{4}))?' + SEP + TP + ZONE, 'i'), 3],
    [new RegExp('\\b' + MW + '\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?' + SEP + TP + ZONE, 'i'), 2],
    [new RegExp('\\b(\\d{1,2})[:.](\\d{2})\\s*(am|pm)?' + ZONE, 'i'), 4]
  ];
  // vaste verschuiving in minuten t.o.v. UTC; zomertijd (ongeveer: april t/m oktober) voor de zones die dat hebben
  var FIXED = { UTC: 0, GMT: 0, EST: -300, EDT: -240, CST: -360, CDT: -300, MST: -420, MDT: -360, PST: -480, PDT: -420, CET: 60, CEST: 120,
    EET: 120, EEST: 180, WET: 0, WEST: 60, BST: 60, IST: 330, AEST: 600, AEDT: 660 };
  var DST = { UK: [0, 60], ET: [-300, -240], CT: [-360, -300], MT: [-420, -360], PT: [-480, -420] };
  function zoneOffset(abbr, off, month) {
    if (off) {
      var sign = off.charAt(0) === '-' ? -1 : 1, dg = off.slice(1).replace(':', ''), hh, mm;
      if (dg.length <= 2) { hh = +dg; mm = 0; } else { hh = +dg.slice(0, dg.length - 2); mm = +dg.slice(-2); }
      if (hh <= 14 && mm < 60) return sign * (hh * 60 + mm);
    }
    if (!abbr) return null;
    var a = abbr.toUpperCase();
    if (B.has(FIXED, a)) return FIXED[a];
    if (B.has(DST, a)) return DST[a][month >= 4 && month <= 10 ? 1 : 0];
    return null;
  }
  function findDate(name, now) {
    for (var pi = 0; pi < PATTERNS.length; pi++) {
      var re = PATTERNS[pi][0], kind = PATTERNS[pi][1];
      var m = re.exec(name);
      if (!m) continue;
      var n = function (i) { return m[i] != null && m[i] !== '' ? parseInt(m[i], 10) : null; };
      var year = null, month = null, day = null, hour = null, minute = 0, ampm = null, zi;
      if (kind === 0) { year = n(1); month = n(2); day = n(3); hour = n(4); minute = n(5) || 0; ampm = m[6]; zi = 7; }
      else if (kind === 1) {
        var a = n(1), b = n(2);
        if (a != null && b != null && b > 12 && a <= 12) { var t = a; a = b; b = t; }
        day = a; month = b;
        if (n(3) != null) year = n(3) < 100 ? 2000 + n(3) : n(3);
        hour = n(4); minute = n(5) || 0; ampm = m[6]; zi = 7;
      } else if (kind === 2) {
        month = MONTHS[String(m[1]).toLowerCase().slice(0, 3)] || null; day = n(2); year = n(3); hour = n(4); minute = n(5) || 0; ampm = m[6]; zi = 7;
      } else if (kind === 3) {
        day = n(1); month = MONTHS[String(m[2]).toLowerCase().slice(0, 3)] || null; year = n(3); hour = n(4); minute = n(5) || 0; ampm = m[6]; zi = 7;
      } else { hour = n(1); minute = n(2) || 0; ampm = m[3]; zi = 4; }
      if (hour == null || hour > 24 || minute >= 60) continue;
      if (ampm) { ampm = ampm.toLowerCase(); if (ampm === 'pm' && hour < 12) hour += 12; if (ampm === 'am' && hour === 12) hour = 0; }
      if ((kind === 2 || kind === 3) && m[5] == null && !ampm) continue;
      if (kind === 4 && m[2] == null) continue;
      var nd = new Date(now);
      var y = year || nd.getFullYear(), mo = month || (nd.getMonth() + 1), d = day || nd.getDate();
      if (mo < 1 || mo > 12 || d < 1 || d > 31) continue;
      var off = zoneOffset(m[zi], m[zi + 1], mo);
      var ms = off == null ? new Date(y, mo - 1, d, hour === 24 ? 0 : hour, minute).getTime()
        : Date.UTC(y, mo - 1, d, hour === 24 ? 0 : hour, minute) - off * 60000;
      if (hour === 24) ms += 86400000;
      if (!year && month) {
        if (ms < now - 180 * 86400000) ms = new Date(ms).setFullYear(new Date(ms).getFullYear() + 1);
        else if (ms > now + 180 * 86400000) ms = new Date(ms).setFullYear(new Date(ms).getFullYear() - 1);
      }
      return { start: ms, index: m.index, length: m[0].length };
    }
    return null;
  }
  P.cleanEventTitle = function (raw) {
    var s = String(raw || '');
    s = s.replace(/^\s*(?:PPV|EVENTS?|LIVE\s+EVENTS?|SPORTS?\s+EVENTS?)\s*\d*\s*[:|\-–]?\s*/i, '');
    s = s.replace(/^\s*[A-Z]{2,3}\s*[:|]\s*/, '');
    s = s.replace(/\s+(?:vs\.?|v\.?)\s+/ig, ' – ');
    s = s.replace(/\s+-\s+/g, ' – ');
    s = s.replace(/[(\[]\s*[)\]]/g, '');
    s = s.replace(/\s{2,}/g, ' ');
    s = s.replace(/^[\s\-–|:@,/()[\]•·]+|[\s\-–|:@,/()[\]•·]+$/g, '');
    return s || String(raw || '').trim();
  };
  function hasVersus(name) { return /\S\s+(?:vs\.?|v\.?|-|–)\s+\S/i.test(name); }
  /** Evenement van een zender ({title, start}) of null. */
  P.eventInfo = function (ch, now) {
    now = now || Date.now();
    var inGroup = P.isEventGroup(ch.rawGroup || ch.group);
    // buiten een evenementgroep alleen namen met "A vs B" (goedkoop eerst; de datumpatronen zijn duurder)
    if (!inGroup && !hasVersus(ch.name)) return null;
    var f = findDate(ch.name, now);
    if (f) {
      var rest = ch.name.slice(0, f.index) + ch.name.slice(f.index + f.length);
      if (inGroup || hasVersus(ch.name)) return { title: P.cleanEventTitle(rest), start: f.start };
      return null;
    }
    return inGroup ? { title: P.cleanEventTitle(ch.name), start: null } : null;
  };
  P.eventLive = function (ev, now) { now = now || Date.now(); return ev.start != null && ev.start <= now && now - ev.start <= 6 * 3600000; };
  P.eventExpired = function (ev, now) { now = now || Date.now(); return ev.start != null && now - ev.start > 6 * 3600000; };
  /** "Vandaag 20:45", "Morgen 20:45" of "za 14 okt. 20:45". */
  P.eventWhen = function (ev) {
    if (ev.start == null) return '';
    var d = new Date(ev.start), t = B.fmtTime(ev.start);
    var today = new Date(); today.setHours(0, 0, 0, 0);
    var day = new Date(ev.start); day.setHours(0, 0, 0, 0);
    var diff = Math.round((day.getTime() - today.getTime()) / 86400000);
    if (diff === 0) return L('Today %@', t);
    if (diff === 1) return L('Tomorrow %@', t);
    if (diff === -1) return L('Yesterday %@', t);
    try { return d.toLocaleDateString(B.lang, { weekday: 'short', day: 'numeric', month: 'short' }) + ' ' + t; } catch (e) { return t; }
  };
  /** Evenementen in de bibliotheek, gesorteerd: komend en bezig op begintijd, zonder tijd daarna, verlopen onderaan. */
  P.events = function () {
    return Lib.memo('events:' + Math.floor(Date.now() / 300000), function () {
      var now = Date.now(), out = [];
      Lib.lib.channels.forEach(function (c, i) { var ev = P.eventInfo(c, now); if (ev) out.push({ ch: c, ev: ev, i: i }); });
      function rank(e) { return e.ev.start == null ? 1 : (P.eventExpired(e.ev, now) ? 2 : 0); }
      out.sort(function (a, b) {
        var ra = rank(a), rb = rank(b);
        if (ra !== rb) return ra - rb;
        if (a.ev.start != null && b.ev.start != null && a.ev.start !== b.ev.start) return ra === 2 ? b.ev.start - a.ev.start : a.ev.start - b.ev.start;
        return a.i - b.i;
      });
      return out;
    });
  };

  // MARK: andere afspeellijsten (bibliotheken uit de cache; ontbrekende één keer op de achtergrond laden)

  var others = {};   // bron-id → {at, lib}
  var othersBusy = null, othersFetched = {};
  B.on('wipe', function () { others = {}; othersFetched = {}; });
  /** Kleur van een bron (avatar.colorIndex, zelfde paletten als de avatars). */
  P.sourceColor = function (s) {
    var i = s && s.avatar ? B.int(s.avatar.colorIndex) || 0 : 0;
    var pal = D.palettes[((i % D.palettes.length) + D.palettes.length) % D.palettes.length];
    return pal[0];
  };
  /** Mag dit profiel andere afspeellijsten gebruiken? (zoals tvOS: alleen het hoofdprofiel) */
  P.othersAllowed = function () { return !!B.app.profile && (D.isMain(B.app.profile) || D.profiles.length <= 1) && D.sources.length > 1; };
  /**
   * Gefilterde bibliotheken van de andere bronnen: [{source, lib}]. Uit IndexedDB; fetch = ontbrekende ophalen
   * (één keer per sessie, één tegelijk). Alleen titels/zenders (geen gids).
   */
  P.otherLibraries = function (fetch) {
    if (!P.othersAllowed()) return Promise.resolve([]);
    if (othersBusy) return othersBusy;
    var cur = src();
    var list = D.sources.filter(function (s) { return s.id !== cur; });
    var chain = Promise.resolve();
    list.forEach(function (s) {
      chain = chain.then(function () {
        return B.idb.get('lib:' + s.id).then(function (c) {
          if (c && c.lib) {
            if (others[s.id] && others[s.id].at === c.at) return;
            others[s.id] = { at: c.at, lib: Lib.filterFor(s, c.lib) };
            return;
          }
          // fetch: true = alles ophalen (zoeken); 'media' = alleen Plex/Jellyfin/Emby/Stremio (klein, via hun API);
          // grote IPTV-lijsten alleen uit de cache (die er is als je de bron eerder gebruikte)
          if (!fetch || othersFetched[s.id]) return;
          if (fetch === 'media' && !(B.media.isMediaServer(s.kind) || s.kind === 'Stremio')) return;
          othersFetched[s.id] = true;
          return Lib.fetchOther(s).then(function (lib) { if (lib) others[s.id] = { at: Date.now(), lib: Lib.filterFor(s, lib) }; }, B.noop);
        }, B.noop);
      });
    });
    othersBusy = chain.then(function () {
      othersBusy = null;
      return list.filter(function (s) { return others[s.id]; }).map(function (s) { return { source: s, lib: others[s.id].lib }; });
    }, function () { othersBusy = null; return []; });
    return othersBusy;
  };
  function norm(t) { var p = B.parseTitle(t); return B.fold((p && p.title) || t).replace(/[^a-z0-9]/g, ''); }
  /** Zelfde titel elders: andere groepen in deze lijst (ander id) en andere afspeellijsten. */
  P.alternatives = function (type, x) {
    var n = norm(x.rawTitle || x.title), y = x.year;
    function same(o) {
      if (!o || norm(o.rawTitle || o.title) !== n) return false;
      if (type === 'movie' && y && o.year && Math.abs(o.year - y) > 1) return false;
      return true;
    }
    var mine = (type === 'movie' ? Lib.lib.movies : Lib.lib.series).filter(function (o) { return o.id !== x.id && same(o); })
      .map(function (o) { return { source: Lib.source, item: o, type: type, current: true }; });
    if (!n) return Promise.resolve(mine);
    return P.otherLibraries('media').then(function (libs) {
      var out = mine.slice();
      libs.forEach(function (l) {
        (type === 'movie' ? l.lib.movies : l.lib.series).forEach(function (o) { if (same(o)) out.push({ source: l.source, item: o, type: type, current: false }); });
      });
      return out.slice(0, 20);
    });
  };
  /** Naar een andere bron wisselen en daar het item openen (= app.open(playlist, thenShow:) op tvOS). */
  P.openFromSource = function (source, type, id) {
    if (!source) return;
    if (Lib.source && Lib.source.id === source.id) {
      var hit = Lib.lib.byId[id];
      if (hit) { if (type === 'channel') B.player.playChannel(hit.item, [hit.item]); else U.push(S.detail(hit.type, hit.item)); }
      return;
    }
    U.toast(L('Switching to %@…', source.name));
    D.setActiveSource(source.id);
    var done = false;
    var off = B.on('library', function () { tryOpen(); });
    var timer = setTimeout(function () { finish(); }, 30000);
    function finish() { if (done) return; done = true; off(); clearTimeout(timer); }
    function tryOpen() {
      if (done || !Lib.source || Lib.source.id !== source.id || !Lib.lib.byId) return;
      var hit2 = Lib.lib.byId[id];
      if (!hit2) return;
      finish();
      // even wachten tot het hoofdscherm de nieuwe bibliotheek getekend heeft
      setTimeout(function () {
        if (type === 'channel') B.player.playChannel(hit2.item, [hit2.item]);
        else U.push(S.detail(hit2.type, hit2.item));
      }, 400);
    }
    Lib.select(source).then(tryOpen, finish);
  };
  /** Kaart van een titel uit een andere bron: gekleurde streep en de naam van de bron. */
  P.otherCard = function (o) {
    var card = h('div.card.poster.other-src.f', [U.img(o.item.poster || o.item.logo, 'poster-img', o.item.title || o.item.name),
      h('div.src-badge', { style: { background: P.sourceColor(o.source) } }, o.source.name)]);
    card.onEnter = function () { P.openFromSource(o.source, o.type, o.item.id); };
    card._key = 'other:' + o.source.id + ':' + o.item.id;
    return card;
  };

  // MARK: zoeken: personen, tv-gids, categorieën en andere afspeellijsten (= SearchView op tvOS)

  function initials(n) {
    return String(n || '?').split(/\s+/).filter(Boolean).slice(0, 2).map(function (w) { return w.charAt(0).toUpperCase(); }).join('') || '?';
  }
  function personCard(p) {
    var photo = p.profile_path ? 'https://image.tmdb.org/t/p/w185' + p.profile_path : null;
    var card = h('div.cast-card.person-hit.f', [h('div.round-pic.cast-pic', [U.img(photo, 'round-img', initials(p.name))]), h('div.cast-name', p.name),
      p.known_for_department ? h('div.cast-role', p.known_for_department === 'Acting' ? L('Acting') : p.known_for_department) : null]);
    card.onEnter = function () { U.push(S.person({ name: p.name, personID: p.id, photo: photo })); };
    card._key = 'person:' + p.id;
    return card;
  }
  P.searchPeople = function (q) {
    if (!AR.hasKey() || B.fold(q).trim().length < 2 || B.details.ageLimited()) return Promise.resolve([]);
    return AR.tmdb('/search/person', [['query', q], ['include_adult', 'false'], ['language', AR.tmdbLang()]]).then(function (r) {
      return ((r && r.results) || []).filter(function (p) { return p && p.name; }).slice(0, 12);
    }, function () { return []; });
  };
  /** Programma's in de gids (nu en de komende 24 uur) waarvan de titel past; in stukjes. Promise → [{ch, p}]. */
  P.searchPrograms = function (q, limit) {
    var f = B.fold(q).trim();
    limit = limit || 30;
    if (f.length < 2) return Promise.resolve([]);
    var now = Date.now(), until = now + 24 * 3600000, out = [];
    var job = B.chunked(Lib.lib.channels, function (ch) {
      if (out.length >= limit) return;
      var list = Lib.epgFor(ch);
      if (!list.length) return;
      var i = Math.max(0, B.bsearchStart(list, now));
      for (; i < list.length && list[i].start < until && out.length < limit; i++) {
        var p = list[i];
        if (p.end <= now) continue;
        var t = p._ft != null ? p._ft : (p._ft = B.fold(p.title));
        if (t.indexOf(f) >= 0) out.push({ ch: ch, p: p });
      }
    });
    var pr = job.then(function () { return out.sort(function (a, b) { return a.p.start - b.p.start; }); }, function () { return []; });
    pr.cancel = job.cancel;
    return pr;
  };
  P.programCard = function (hit, list) {
    var ch = hit.ch, p = hit.p, now = Date.now(), live = p.start <= now && now < p.end;
    var card = h('div.card.prog.f', [h('div.prog-logo', [U.img(ch.logo, 'logo-img', ch.name)]),
      h('div.prog-meta', [h('div.prog-title', p.title), h('div.prog-sub.dim', (live ? L('LIVE') + '  ·  ' : '') + B.fmtTime(p.start) + ' – ' + B.fmtTime(p.end) + '  ·  ' + ch.name)])]);
    if (live) card.classList.add('live');
    card.onEnter = function () { S.programSheet(ch, p, list || [ch]); };
    card._key = 'prog:' + ch.id + ':' + p.start;
    return card;
  };
  /** Categorieën (groepen van films/series) en genres waarvan de naam past. */
  P.searchCategories = function (q) {
    var f = B.fold(q).trim(), out = [];
    if (f.length < 2) return out;
    var lib = Lib.lib, mg = Lib.byGroup('movie'), sg = Lib.byGroup('series');
    lib.movieGroups.forEach(function (g) { if (B.fold(g).indexOf(f) >= 0) out.push({ title: g, entries: (mg[g] || []).map(function (x) { return { type: 'movie', item: x }; }) }); });
    lib.seriesGroups.forEach(function (g) { if (B.fold(g).indexOf(f) >= 0) out.push({ title: g, entries: (sg[g] || []).map(function (x) { return { type: 'series', item: x }; }) }); });
    (S.genreList || []).forEach(function (g) {
      if (B.fold(L(g.name)).indexOf(f) >= 0 || B.fold(g.name).indexOf(f) >= 0) out.push({ title: L(g.name), genre: g.slug });
    });
    return out.slice(0, 30);
  };
  function categoryPill(c) {
    var p = h('div.cat-pill.search-cat.f', [h('span', c.title), c.entries ? h('span.cat-n', ' ' + c.entries.length) : null]);
    p.onEnter = function () { if (c.genre) U.push(S.categoryPage({ genre: c.genre }, 'all')); else P.openGrid(c.title, c.entries); };
    p._key = 'cat:' + c.title;
    return p;
  }
  /** Treffers in de andere afspeellijsten (alleen hoofdprofiel). Promise → [{source, item, type}] */
  P.searchOthers = function (q) {
    var f = B.fold(q).trim();
    if (f.length < 2) return Promise.resolve([]);
    return P.otherLibraries(true).then(function (libs) {
      var out = [];
      libs.forEach(function (l) {
        [['movie', l.lib.movies, 'title'], ['series', l.lib.series, 'title'], ['channel', l.lib.channels, 'name']].forEach(function (pool) {
          for (var i = 0, n = 0; i < pool[1].length && n < 12; i++) {
            if (B.fold(pool[1][i][pool[2]]).indexOf(f) >= 0) { out.push({ source: l.source, item: pool[1][i], type: pool[0] }); n++; }
          }
        });
      });
      return out.slice(0, 40);
    });
  };
  /**
   * Extra rijen onder de zoekresultaten: Personen, Categorieën, Tv-gids, In je andere afspeellijsten.
   * track = de .sy-track; alive() = nog dezelfde zoekopdracht. Geeft een functie die lopend werk stopt.
   */
  P.searchExtras = function (q, track, alive, empty) {
    var slots = {};
    ['people', 'cats', 'progs', 'others'].forEach(function (k) { slots[k] = h('div.search-slot'); });
    // personen na de zenders, de rest onderaan (volgorde van tvOS)
    var firstRow = track.querySelector('.row-block');
    track.insertBefore(slots.people, firstRow ? firstRow.nextSibling : null);
    track.appendChild(slots.cats);
    track.appendChild(slots.progs);
    track.appendChild(slots.others);
    var any = false;
    function put(slot, row) {
      if (!row || !alive()) return;
      slot.appendChild(row);
      any = true;
      if (empty && empty.parentNode) empty.parentNode.removeChild(empty);
    }
    var cats = P.searchCategories(q);
    if (cats.length) put(slots.cats, h('div.row-block.search-cats', [h('div.row-title', L('Categories')),
      h('div.row-scroll.sx.fm', { 'data-pad': '70' }, [h('div.row-track', cats.map(categoryPill))])]));
    P.searchPeople(q).then(function (list) {
      if (list.length) put(slots.people, S.rowBlock(L('People'), list.map(personCard), 'search-people'));
    });
    var pj = P.searchPrograms(q, 30);
    pj.then(function (hits) {
      if (!hits.length) return;
      var chans = [];
      hits.forEach(function (x) { if (chans.indexOf(x.ch) < 0) chans.push(x.ch); });
      put(slots.progs, S.rowBlock(L('TV guide'), hits.map(function (x) { return P.programCard(x, chans); }), 'search-progs'));
    });
    if (P.othersAllowed()) {
      P.searchOthers(q).then(function (list) {
        if (list.length) put(slots.others, S.rowBlock(L('In your other playlists'), list.map(P.otherCard), 'search-others'));
      });
    }
    return function () { if (pj.cancel) pj.cancel(); };
  };

  /** Live TV: "Zoek zender of programma" (= zoekveld in LiveTVView). */
  S.channelSearch = function () {
    var query = h('div.search-query.placeholder', L('Search channel or program'));
    var results = S.vScroller('.search-results', { 'data-align': 'top', 'data-pad': '20' });
    var job = null, timer = null, gen = 0;
    function run(v) {
      var my = ++gen;
      if (job) { job(); job = null; }
      U.clear(results.track);
      U.scrollTo(results, 0);
      var f = B.fold(v || '').trim();
      if (!f) return;
      var chans = [];
      for (var i = 0; i < Lib.lib.channels.length && chans.length < 40; i++) if (B.fold(Lib.lib.channels[i].name).indexOf(f) >= 0) chans.push(Lib.lib.channels[i]);
      var r = S.rowBlock(L('Channels'), chans.map(function (c) { return S.channelCard(c, chans); }), 'live');
      if (r) results.track.appendChild(r);
      var slot = h('div.search-slot');
      results.track.appendChild(slot);
      var empty = r ? null : h('div.state.dim', L('No results for “%@”', v));
      if (empty) results.track.appendChild(empty);
      var pj = P.searchPrograms(v, 40);
      job = pj.cancel;
      pj.then(function (hits) {
        if (my !== gen || !hits.length) return;
        var cl = [];
        hits.forEach(function (x) { if (cl.indexOf(x.ch) < 0) cl.push(x.ch); });
        slot.appendChild(S.rowBlock(L('TV guide'), hits.map(function (x) { return P.programCard(x, cl); }), 'search-progs'));
        if (empty && empty.parentNode) empty.parentNode.removeChild(empty);
      });
    }
    var kb = U.keyboard({ layout: 'search', onChange: function (v) {
      query.textContent = v || L('Search channel or program');
      query.classList.toggle('placeholder', !v);
      clearTimeout(timer);
      timer = setTimeout(function () { B.whenQuiet(function () { run(v); }, 250); }, 300);
    } });
    var el = h('div.layer.search-layer.chan-search', [h('div.page.search', [h('div.search-left', [query, kb]), results])]);
    var s = {
      el: el,
      onBack: function () { U.pop(); return true; },
      destroy: function () { clearTimeout(timer); if (job) job(); U.forgetImgs(el); },
      setQuery: function (v) { kb.setValue(v); query.textContent = v || L('Search channel or program'); query.classList.toggle('placeholder', !v); run(v); }
    };
    U.push(s);
    var k = kb.querySelector('.key');
    if (k) U.focus(k);
    return s;
  };

  // MARK: trailer beeldvullend (met geluid), via dezelfde embed/doorgeefpagina als de trailer in de kop

  var TRAILER_RELAY = 'https://bingr.online/trailer/';
  P.playTrailer = function (key) {
    key = String(key || '').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32);
    if (!key) return null;
    var attempt = 0, frame = null, playing = false, timeout = null, closed = false;
    var spin = h('div.tf-spin', [U.spinner()]);
    var hint = h('div.tf-hint', L('Back: close'));
    var el = h('div.layer.trailer-full', [spin, hint]);
    function send(func, args) {
      try { frame.contentWindow.postMessage(JSON.stringify({ event: 'command', func: func, args: args || [], id: 1, channel: 'widget' }), '*'); } catch (e) { /* stil */ }
    }
    function srcFor() {
      if (attempt === 0) {
        return 'https://www.youtube-nocookie.com/embed/' + key + '?autoplay=1&mute=1&controls=0&playsinline=1&enablejsapi=1&rel=0' +
          '&iv_load_policy=3&disablekb=1&fs=0&modestbranding=1&widget_referrer=' + encodeURIComponent('https://bingr.online/tizen');
      }
      return TRAILER_RELAY + '?v=' + encodeURIComponent(key) + '&app=tizen';
    }
    function onMsg(ev) {
      if (!frame || ev.source !== frame.contentWindow) return;
      var m = null;
      try { m = typeof ev.data === 'string' ? JSON.parse(ev.data) : ev.data; } catch (e) { return; }
      if (!m) return;
      if (m.event === 'onError') { failed(); return; }
      var state = m.event === 'onStateChange' ? m.info : (m.event === 'infoDelivery' && m.info ? m.info.playerState : null);
      if (m.event === 'onReady') { send('unMute'); send('setVolume', [100]); send('playVideo'); }
      if (state === 1 && !playing) { playing = true; clearTimeout(timeout); frame.className = 'tf-frame on'; spin.style.display = 'none'; send('unMute'); send('setVolume', [100]); }
      if (state === 0) close();
    }
    function mount() {
      if (frame && frame.parentNode) frame.parentNode.removeChild(frame);
      var f = frame = document.createElement('iframe');
      f.className = 'tf-frame';
      f.setAttribute('tabindex', '-1');
      f.setAttribute('allow', 'autoplay; encrypted-media');
      f.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
      f.setAttribute('frameborder', '0');
      f.src = srcFor();
      f.onload = function () {
        if (frame !== f) return;
        try { f.contentWindow.postMessage(JSON.stringify({ event: 'listening', id: 1, channel: 'widget' }), '*'); } catch (e) { /* stil */ }
        send('playVideo');
      };
      el.insertBefore(f, spin);
      clearTimeout(timeout);
      timeout = setTimeout(function () { if (!playing) failed(); }, 12000);
    }
    function failed() {
      clearTimeout(timeout);
      if (closed) return;
      if (attempt === 0) { attempt = 1; mount(); return; }
      U.toast(L("The trailer can't be played right now."));
      close();
    }
    function close() {
      if (closed) return;
      if (U.top() === s) U.pop(); else cleanup();
    }
    function cleanup() {
      closed = true;
      clearTimeout(timeout);
      window.removeEventListener('message', onMsg);
      if (frame) { send('stopVideo'); try { frame.src = 'about:blank'; } catch (e) { /* stil */ } }
    }
    var s = { el: el, isTrailer: true, onBack: function () { U.pop(); return true; }, destroy: cleanup,
      onKey: function (name) { if (name === 'enter' || name === 'stop') { U.pop(); return true; } return name !== 'back'; } };
    window.addEventListener('message', onMsg);
    U.push(s);
    U.blur();
    mount();
    return s;
  };

  // MARK: achtergronden die rustig in elkaar overlopen (Ken Burns), als er geen trailer speelt

  P.kenBurns = function (bg, urls) {
    urls = (urls || []).filter(Boolean).slice(0, 6);
    if (!urls.length) return { stop: B.noop };
    var stack = h('div.kb-stack');
    var layers = urls.map(function (u) { return h('div.kb-layer', [U.img(u, 'kb-img', null, true)]); });
    layers.forEach(function (l) { stack.appendChild(l); });
    var first = bg.querySelector('.dt-trailer');
    bg.insertBefore(stack, first || null);
    var i = 0, timer = null;
    layers[0].classList.add('on');
    if (layers.length > 1) {
      timer = setInterval(function () {
        if (!document.body.contains(stack)) { clearInterval(timer); return; }
        layers[i].classList.remove('on');
        i = (i + 1) % layers.length;
        // animatie opnieuw starten op de nieuwe laag
        var l = layers[i];
        l.classList.remove('on');
        void l.offsetWidth;
        l.classList.add('on');
      }, 9000);
    }
    return { stop: function () { clearInterval(timer); if (stack.parentNode) stack.parentNode.removeChild(stack); }, el: stack };
  };

  // MARK: voortgang van een serie (= watchedFraction op tvOS)

  P.fraction = function (s, eps) {
    if (!eps || !eps.length) return 0;
    var n = 0;
    eps.forEach(function (e) {
      if (P.episodeWatched(s, e)) { n += 1; return; }
      var p = D.progress(e.id);
      if (p && p.fraction > 0) n += Math.min(1, p.fraction);
    });
    return n / eps.length;
  };
  P.progressLine = function (text, f) {
    return h('div.dt-sprogress', [h('span', text), h('div.card-progress.inline', [h('div', { style: { width: Math.round(B.clamp(f, 0, 1) * 100) + '%' } })])]);
  };
})();
