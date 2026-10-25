/*
 * Categorieën (build 85), Netflix-stijl, = Android, bingr.online en de Apple-apps.
 * Overzicht "Categorieën" (sinds build 86 via het profielmenu en de rij op Home, eerst een menu-item): tegels (naam, aantal, collage van posters op een kleurverloop per genre) in twee secties,
 * "Genres" (TMDB-genres, films en series samen) en "Van je server" (de VOD-/seriecategorieën van de bron, zoals ze na
 * verbergen en kinderslot/leeftijdsgrens van dit profiel in Lib.lib staan). OK op een tegel opent de categoriepagina:
 * kop met aantal, Alles/Films/Series, sorteren (Populair, Nieuwste, A–Z, Beoordeling) en een virtueel raster.
 * Genres per titel komen uit de TMDB-cache van B.ageRatings (dezelfde als het kinderfilter); die wordt bij het openen
 * op de achtergrond aangevuld. De index wordt één keer per bibliotheekversie + genre-update uitgerekend.
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui, D = B.data, Lib = B.lib, S = B.screens;
  var h = U.h;
  var L = function () { return B.L.apply(null, arguments); };

  // zelfde volgorde, id's en kleuren als Genres.all op Android
  var GENRES = [
    { slug: 'action', name: 'Action', m: [28], t: [10759], c: ['#B3121D', '#3A0A0E'] },
    { slug: 'adventure', name: 'Adventure', m: [12], t: [10759], c: ['#D9822B', '#4A2308'] },
    { slug: 'comedy', name: 'Comedy', m: [35], t: [35], c: ['#F2B705', '#5C3A00'] },
    { slug: 'drama', name: 'Drama', m: [18], t: [18], c: ['#6A4C93', '#1E1233'] },
    { slug: 'thriller', name: 'Thriller', m: [53], t: [], c: ['#37474F', '#0B1114'] },
    { slug: 'crime', name: 'Crime', m: [80], t: [80], c: ['#8D2F23', '#1C0907'] },
    { slug: 'scifi', name: 'Science Fiction', m: [878], t: [10765], c: ['#1E88E5', '#07203F'] },
    { slug: 'fantasy', name: 'Fantasy', m: [14], t: [10765], c: ['#7B1FA2', '#220833'] },
    { slug: 'horror', name: 'Horror', m: [27], t: [], c: ['#4A0D0D', '#000000'] },
    { slug: 'mystery', name: 'Mystery', m: [9648], t: [9648], c: ['#283593', '#0A0E2E'] },
    { slug: 'romance', name: 'Romance', m: [10749], t: [], c: ['#D81B60', '#3F0A1F'] },
    { slug: 'animation', name: 'Animation', m: [16], t: [16], c: ['#00ACC1', '#06323A'] },
    { slug: 'family', name: 'Family', m: [10751], t: [10751], c: ['#43A047', '#0F2E12'] },
    { slug: 'kids', name: 'Kids', m: [], t: [10762], c: ['#FFB300', '#0277BD'] },
    { slug: 'documentary', name: 'Documentary', m: [99], t: [99], c: ['#5D7B6F', '#16211C'] },
    { slug: 'history', name: 'History', m: [36], t: [], c: ['#8D6E63', '#2A1D18'] },
    { slug: 'war', name: 'War', m: [10752], t: [10768], c: ['#556B2F', '#171E0B'] },
    { slug: 'western', name: 'Western', m: [37], t: [37], c: ['#BF6B2C', '#3A1C07'] },
    { slug: 'music', name: 'Music', m: [10402], t: [], c: ['#EC407A', '#311B92'] },
    { slug: 'reality', name: 'Reality', m: [], t: [10764], c: ['#FF7043', '#3E1408'] },
    { slug: 'soap', name: 'Soap', m: [], t: [10766], c: ['#AB47BC', '#2D0F33'] }
  ];
  var BY_SLUG = {}, BY_MOVIE = {}, BY_TV = {};
  GENRES.forEach(function (g) {
    BY_SLUG[g.slug] = g;
    g.m.forEach(function (id) { (BY_MOVIE[id] = BY_MOVIE[id] || []).push(g.slug); });
    g.t.forEach(function (id) { (BY_TV[id] = BY_TV[id] || []).push(g.slug); });
  });
  S.genreList = GENRES; // build 102: zoeken op genre (js/parity.js)
  var SERVER_PALETTE = GENRES.filter(function (g) { return g.slug !== 'horror' && g.slug !== 'thriller'; });
  function hash(s) { var x = 0; s = String(s || '').toLowerCase(); for (var i = 0; i < s.length; i++) x = (x * 31 + s.charCodeAt(i)) | 0; return x; }
  function serverColors(name) { var n = SERVER_PALETTE.length; return SERVER_PALETTE[((hash(name) % n) + n) % n].c; }
  /** "#RRGGBB" + doorzichtigheid → rgba() (8-cijferige hex kent Chromium M56 nog niet). */
  function rgba(hex, a) {
    var n = parseInt(String(hex).slice(1), 16);
    return 'rgba(' + ((n >> 16) & 255) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')';
  }
  function gradient(c) { return 'linear-gradient(135deg, ' + c[0] + ' 0%, ' + c[1] + ' 100%)'; }

  // MARK: index

  function Members() { this.movies = []; this.series = []; this._p = {}; }
  Members.prototype.count = function (k) { return k === 'movie' ? this.movies.length : k === 'series' ? this.series.length : this.movies.length + this.series.length; };
  /** Posters voor de collage: de best beoordeelde titels met een poster. */
  Members.prototype.posters = function (k, n) {
    var key = k + n;
    if (this._p[key]) return this._p[key];
    var list = [];
    if (k !== 'series') this.movies.forEach(function (m) { if (m.poster) list.push(m); });
    if (k !== 'movie') this.series.forEach(function (s) { if (s.poster) list.push(s); });
    list.sort(function (a, b) { return (b.rating || 0) - (a.rating || 0); });
    var seen = {}, out = [];
    for (var i = 0; i < list.length && out.length < n; i++) if (!seen[list[i].poster]) { seen[list[i].poster] = true; out.push(list[i].poster); }
    this._p[key] = out;
    return out;
  };
  /** [{type, item}] voor de soort. */
  Members.prototype.items = function (k) {
    var out = [];
    if (k !== 'series') this.movies.forEach(function (m) { out.push({ type: 'movie', item: m }); });
    if (k !== 'movie') this.series.forEach(function (s) { out.push({ type: 'series', item: s }); });
    return out;
  };

  var genreVersion = 0, memo = null;
  B.on('ageRatings', function () { genreVersion++; });
  function index() {
    var key = Lib.version + '|' + genreVersion;
    if (memo && memo.key === key) return memo.idx;
    var lib = Lib.lib, AR = B.ageRatings;
    var genres = {}, server = [], byName = {};
    function addGenres(x, isShow) {
      var info = AR.cached(x.title, x.year, isShow);
      if (!info || !info.genres) return;
      var map = isShow ? BY_TV : BY_MOVIE, done = {};
      for (var i = 0; i < info.genres.length; i++) {
        var slugs = map[info.genres[i]];
        if (!slugs) continue;
        for (var j = 0; j < slugs.length; j++) {
          var s = slugs[j];
          if (done[s]) continue;
          done[s] = true;
          var m = genres[s] || (genres[s] = new Members());
          (isShow ? m.series : m.movies).push(x);
        }
      }
    }
    function addServer(x, isShow) {
      if (!x.group) return;
      var m = byName[x.group];
      if (!m) { m = byName[x.group] = new Members(); server.push({ name: x.group, m: m }); }
      (isShow ? m.series : m.movies).push(x);
    }
    lib.movies.forEach(function (x) { addGenres(x, false); addServer(x, false); });
    lib.series.forEach(function (x) { addGenres(x, true); addServer(x, true); });
    var idx = { genres: genres, server: server, byName: byName };
    memo = { key: key, idx: idx };
    return idx;
  }
  function membersOf(ref) {
    var idx = index();
    return ref.genre ? idx.genres[ref.genre] || null : idx.byName[ref.server] || null;
  }
  function refTitle(ref) { return ref.genre ? L(BY_SLUG[ref.genre].name) : ref.server; }
  function countText(n) { return n === 1 ? L('1 title') : L('%d titles', n); }

  /** Genres van de hele bibliotheek bij TMDB aanvullen (bij een leeftijdsgrens loopt dat al). */
  var warmedFor = null;
  function warm() {
    if (warmedFor === Lib.raw) return;
    warmedFor = Lib.raw;
    try { B.ageRatings.warm(Lib.raw); } catch (e) { B.log('categories warm', e); }
  }

  // MARK: onderdelen

  function pill(label, active, fn) {
    var p = h('div.cat-pill.f' + (active ? '.active' : ''), label);
    p.onEnter = fn;
    return p;
  }
  var KINDS = [['all', 'All'], ['movie', 'Movies'], ['series', 'Series']];
  function kindPills(kind, available, onPick) {
    var row = h('div.cat-pills.fm');
    KINDS.forEach(function (k) {
      if (available && !available[k[0]]) return;
      row.appendChild(pill(L(k[1]), k[0] === kind, function () { onPick(k[0]); }));
    });
    return row;
  }
  function tile(title, count, posters, colors, onEnter) {
    var fan = h('div.cat-fan');
    // achterste eerst; de eerste (best beoordeelde) staat vooraan rechts
    for (var i = posters.length - 1; i >= 0; i--) {
      var img = U.img(posters[i], 'cat-fan-img');
      img.className += ' p' + i;
      fan.appendChild(img);
    }
    var t = h('div.cat-tile.f', { style: { background: gradient(colors) } }, [
      fan,
      h('div.cat-shade', { style: { background: 'linear-gradient(90deg, ' + rgba(colors[0], 0.95) + ' 0%, ' + rgba(colors[0], 0.7) + ' 45%, rgba(0,0,0,0) 80%)' } }),
      h('div.cat-text', [h('div.cat-name', title), h('div.cat-count', countText(count))])
    ]);
    t.onEnter = onEnter;
    t._key = 'cat:' + title;
    return t;
  }

  // MARK: overzicht (pagina in het hoofdmenu)

  S.pages.categories = function () {
    var scroller = S.vScroller('.page.cats', { 'data-align': 'top', 'data-pad': '60' });
    var track = scroller.track;
    var pg = { el: scroller };
    var kind = B.store.get('categories.kind', 'all');
    if (kind !== 'all' && kind !== 'movie' && kind !== 'series') kind = 'all';
    function render() {
      U.clear(track);
      U.scrollTo(scroller, 0);
      track.appendChild(h('div.cats-head', [h('h1', L('Categories'))]));
      var st = S.stateBox();
      if (st) { track.appendChild(st); return; }
      warm();
      track.appendChild(kindPills(kind, null, function (k) {
        kind = k; B.store.set('categories.kind', k);
        render();
        var a = track.querySelector('.cat-pill.active');
        if (a) U.focus(a, true);
      }));
      var idx = index();
      var gTiles = [];
      GENRES.forEach(function (g) {
        var m = idx.genres[g.slug];
        if (!m || !m.count(kind)) return;
        gTiles.push(tile(L(g.name), m.count(kind), m.posters(kind, 4), g.c, function () { U.push(S.categoryPage({ genre: g.slug }, kind)); }));
      });
      if (gTiles.length) track.appendChild(h('div.cats-sec', [h('div.row-title', L('Genres')), h('div.cats-grid.fm', gTiles)]));
      var sTiles = [];
      idx.server.forEach(function (s) {
        if (!s.m.count(kind)) return;
        sTiles.push(tile(s.name, s.m.count(kind), s.m.posters(kind, 3), serverColors(s.name), function () { U.push(S.categoryPage({ server: s.name }, kind)); }));
      });
      if (sTiles.length) track.appendChild(h('div.cats-sec', [h('div.row-title', L('From your server')), h('div.cats-grid.fm', sTiles)]));
      if (!gTiles.length && !sTiles.length) track.appendChild(h('div.state.dim', L('No titles in this category yet.')));
      track.appendChild(h('div.page-end'));
    }
    // nieuwe genres binnen: opnieuw tekenen als de afstandsbediening stil is, focus op dezelfde tegel
    var pending = false;
    var off = B.on('ageRatings', function () {
      if (pending || !document.body.contains(scroller) || scroller.style.display === 'none') return;
      pending = true;
      B.whenQuiet(function () {
        pending = false;
        if (!document.body.contains(scroller)) return;
        var key = U.focused && scroller.contains(U.focused) ? U.focused._key : null;
        render();
        if (key) {
          var all = track.querySelectorAll('.f');
          for (var i = 0; i < all.length; i++) if (all[i]._key === key) { U.focus(all[i], true); break; }
        }
      }, 2500);
    });
    pg.refresh = render;
    pg.focusIn = function () {
      var t = track.querySelector('.cat-tile') || track.querySelector('.cat-pill.active');
      if (t) { U.focus(t); return true; }
      return false;
    };
    pg.destroy = function () { off(); };
    render();
    return pg;
  };

  // MARK: rij op Home (build 86)

  /** Best gevulde categorieën voor de rij op Home: genres eerst, aangevuld met de grootste van de server. */
  function topTiles(idx, limit) {
    var out = [];
    GENRES.map(function (g) { return { g: g, m: idx.genres[g.slug] }; })
      .filter(function (x) { return x.m && x.m.count('all'); })
      .sort(function (a, b) { return b.m.count('all') - a.m.count('all'); })
      .slice(0, limit)
      .forEach(function (x) { out.push({ ref: { genre: x.g.slug }, title: L(x.g.name), m: x.m, c: x.g.c }); });
    if (out.length < limit) {
      idx.server.filter(function (x) { return x.m.count('all'); })
        .sort(function (a, b) { return b.m.count('all') - a.m.count('all'); })
        .slice(0, limit - out.length)
        .forEach(function (x) { out.push({ ref: { server: x.name }, title: x.name, m: x.m, c: serverColors(x.name) }); });
    }
    return out;
  }
  function visibleCount(idx) {
    var n = 0;
    Object.keys(idx.genres).forEach(function (k) { if (idx.genres[k].count('all')) n++; });
    idx.server.forEach(function (x) { if (x.m.count('all')) n++; });
    return n;
  }

  /**
   * Rij "Categorieën" op Home (build 86): kleinere tegels voor de ~10 best gevulde categorieën, als laatste
   * "Alle categorieën" (naar het overzicht). De plek (vijf rijen onder "Nu live") regelt Home; null als er niets is.
   */
  S.categoriesRow = function () {
    var lib = Lib.lib;
    if (!lib.movies.length && !lib.series.length) return null;
    warm();
    var idx = index();
    var tiles = topTiles(idx, 10).map(function (t) {
      return tile(t.title, t.m.count('all'), t.m.posters('all', 3), t.c, function () { U.push(S.categoryPage(t.ref, 'all')); });
    });
    if (!tiles.length) return null;
    var n = visibleCount(idx);
    var all = h('div.cat-tile.cat-all.f', [
      h('div.cat-all-in', [U.icon('grid', 'cat-all-icon'), h('div.cat-name', L('All categories')),
        n ? h('div.cat-count', n === 1 ? L('1 category') : L('%d categories', n)) : null])
    ]);
    all.onEnter = function () { S.openCategories(); };
    all._key = 'cat:*all';
    tiles.push(all);
    return S.rowBlock(L('Categories'), tiles, 'cat-row');
  };

  /**
   * Build 149: rij "Ontdek categorieën" op Home: grote tegels per categorie van de server, bovenin een collage van 3–4
   * posters, eronder een gekleurde balk met de naam of het logo van de streamingdienst (TMDB). Hooguit 10, de grootste
   * eerst; Enter opent de categorie. Niet bij een kinderprofiel, behalve als alle categorieën kindercategorieën zijn.
   */
  S.discoverCategoriesRow = function () {
    var X9 = B.b149, lib = Lib.lib;
    if (!X9 || (!lib.movies.length && !lib.series.length)) return null;
    var idx = index();
    var p = B.app && B.app.profile;
    var cats = idx.server.map(function (x) { return { name: x.name, count: x.m.count('all'), posters: x.m.posters('all', 4), m: x.m }; });
    var pick = X9.discover.pick(cats, !!(p && p.kids), function (n) { return B.b140.isKidsCategory(n); });
    if (!pick.length) return null;
    var logos = X9.discover.logos();
    var tiles = pick.map(function (c) {
      var title = B.parseTitle(c.name).title || c.name;
      var color = X9.discover.colorFor(title);
      var pk = X9.discover.providerFor(title, logos), logo = pk ? logos[pk] : null;
      var collage = h('div.dc-collage', c.posters.slice(0, 4).map(function (u) { return U.img(u, 'dc-img'); }));
      var bar = h('div.dc-bar', { style: { background: 'linear-gradient(180deg, ' + color + ' 0%, ' + rgba(color, 0.8) + ' 100%)' } },
        logo ? [U.img(logo, 'dc-logo'), h('div.dc-name.dc-small', title)] : [h('div.dc-name', title)]);
      var t = h('div.dc-tile.f', { style: { background: color } }, [collage, bar]);
      t.onEnter = function () { U.push(S.categoryPage({ server: c.name }, 'all')); };
      t._key = 'dc:' + c.name;
      return t;
    });
    // logo's eens per week ophalen; zijn ze er voor het eerst, dan Home de rij laten vernieuwen
    if (Object.keys(logos).length <= 1) X9.discover.loadLogos().then(function (m) { if (Object.keys(m).length > 1) B.emit('discoverLogos'); });
    return S.rowBlock(L('Discover categories'), tiles, 'dc-row');
  };

  /** Overzicht openen (pagina in het hoofdscherm, sinds build 86 via het profielmenu en de rij op Home). */
  S.openCategories = function () {
    if (S.goPage) S.goPage('categories');
  };

  // MARK: categoriepagina

  var SORTS = [['popular', 'Popular'], ['newest', 'Newest'], ['az', 'A–Z'], ['rating', 'Rating']];
  function sortItems(list, sort) {
    var out = list.slice();
    // stabiel sorteren (oude Chromium sorteert niet stabiel): bij gelijke waarde de volgorde van de bron
    out.forEach(function (e, i) { e._i = i; });
    function num(v) { return v == null || isNaN(v) ? -1 : +v; }
    var cmp;
    if (sort === 'az') {
      var coll = null;
      try { coll = new Intl.Collator(B.lang || undefined, { sensitivity: 'base' }); } catch (e) { coll = null; }
      cmp = function (a, b) {
        var ta = String(a.item.title || ''), tb = String(b.item.title || '');
        return coll ? coll.compare(ta, tb) : ta.localeCompare(tb);
      };
    } else if (sort === 'newest') {
      cmp = function (a, b) { return num(b.item.addedAt) - num(a.item.addedAt) || num(b.item.year) - num(a.item.year); };
    } else {
      // populair: score uit de bron (TMDB/IMDb), dan de volgorde van de bron; beoordeling: alleen de score
      cmp = function (a, b) { return num(b.item.rating) - num(a.item.rating); };
    }
    out.sort(function (a, b) { return cmp(a, b) || a._i - b._i; });
    return out;
  }

  S.categoryPage = function (ref, startKind) {
    var members = membersOf(ref);
    var available = { all: true, movie: !!(members && members.movies.length), series: !!(members && members.series.length) };
    var kind = available[startKind] ? startKind : 'all';
    var sort = B.store.get('categories.sort', 'popular');
    var colors = ref.genre ? BY_SLUG[ref.genre].c : serverColors(ref.server);
    var title = h('h1.cp-title', refTitle(ref));
    var count = h('div.cp-count.dim');
    var controls = h('div.cp-controls');
    var gridBox = h('div.cp-gridbox');
    var el = h('div.layer.catpage', [
      h('div.cp-glow', { style: { background: 'linear-gradient(180deg, ' + rgba(colors[0], 0.65) + ' 0%, rgba(0,0,0,0) 100%)' } }),
      h('div.cp-head', [title, count, controls]), gridBox
    ]);
    var list = [], grid = null;
    function drawControls() {
      U.clear(controls);
      var both = available.movie && available.series;
      if (both) controls.appendChild(kindPills(kind, available, function (k) { kind = k; draw(); focusActive('.cp-kinds'); }));
      if (both) controls.lastChild.classList.add('cp-kinds');
      var row = h('div.cat-pills.cp-sorts.fm');
      SORTS.forEach(function (s) {
        row.appendChild(pill(L(s[1]), s[0] === sort, function () { sort = s[0]; B.store.set('categories.sort', sort); draw(); focusActive('.cp-sorts'); }));
      });
      controls.appendChild(row);
    }
    function focusActive(sel) { var a = controls.querySelector(sel + ' .cat-pill.active'); if (a) U.focus(a, true); }
    function draw() {
      members = membersOf(ref);
      list = sortItems(members ? members.items(kind) : [], sort);
      count.textContent = countText(list.length);
      drawControls();
      var empty = gridBox.querySelector('.state');
      if (empty) gridBox.removeChild(empty);
      if (!grid) {
        grid = U.vgrid({ count: list.length, cols: 7, cellW: 238, cellH: 345, height: 700, render: function (i) {
          var e = list[i];
          var p = e.type === 'movie' ? D.progress(e.item.id) : null;
          return S.posterCard(e.item, e.type, { progress: p ? p.fraction : null });
        } });
        gridBox.appendChild(grid.el);
      } else grid.reset(list.length);
      if (!list.length) gridBox.appendChild(h('div.state.dim', L('No titles in this category yet.')));
    }
    var s = {
      el: el,
      onShow: function (back) {
        if (back) { if (grid) grid.redrawVisible(); return; }
        if (list.length) grid.focusIndex(0); else U.focusFirst(el);
      },
      onBack: function () { U.pop(); return true; }
    };
    draw();
    return s;
  };
})();
