/*
 * Leeftijdsclassificaties uit TMDB voor het kinderfilter (= AgeRatings.kt, build 53).
 * Per titel één keer TMDB opzoeken (IMDb-id of titel + jaar), dan de certificering per land:
 * Kijkwijzer (NL) eerst, dan VS, VK, Duitsland, …; anders de strengste. Plus de TMDB-genres (animatie/familie/kids
 * = kinderinhoud, horror/thriller/misdaad/oorlog = niet voor kinderen).
 * Gecachet in IndexedDB (sleutel "ageRatings"), en op de achtergrond voor de hele bibliotheek bijgewerkt zodra een
 * profiel met leeftijdsgrens actief is: eerst titels uit kindergroepen, daarna best beoordeeld, in kleine batches
 * en alleen als de afstandsbediening even stil is.
 */
(function () {
  'use strict';
  var B = window.B;
  var AR = B.ageRatings = {};
  var cache = {};            // sleutel → {id, age, genres, found, checked}
  var loaded = false, loading = null, saveTimer = null, running = false;
  var RETRY_MS = 30 * 86400000;
  var MAX_PER_RUN = 1500;
  var KIDS = [16, 10751, 10762], ADULT = [27, 53, 80, 10752, 10768];
  var CERT_COUNTRIES = ['NL', 'US', 'GB', 'DE', 'BE', 'FR', 'IE', 'AU', 'CA'];

  /** Zelfde sleutel als Metadata.key op Android: "m|titel in kleine letters|jaar" of "s|…". */
  AR.key = function (title, year, isShow) { return (isShow ? 's' : 'm') + '|' + String(title || '').toLowerCase() + '|' + (year != null ? year : ''); };
  AR.cached = function (title, year, isShow) {
    var r = cache[AR.key(title, year, isShow)];
    return r && r.found !== false ? r : null;
  };
  function any(list, set) { if (!list) return false; for (var i = 0; i < list.length; i++) if (set.indexOf(list[i]) >= 0) return true; return false; }
  AR.isKidsInfo = function (info) { return !!(info && any(info.genres, KIDS)); };
  AR.isAdultInfo = function (info) { return !!(info && any(info.genres, ADULT)); };

  /** Minimumleeftijd uit certificeringen [[land, code]]: per land de hoogste, eerste voorkeursland wint, anders de strengste. */
  AR.ageFromCertifications = function (certs) {
    var by = {};
    certs.forEach(function (c) {
      var a = B.parental.minimumAge(c[1]);
      if (a == null) return;
      var cc = String(c[0]).toUpperCase();
      by[cc] = Math.max(by[cc] || 0, a);
    });
    for (var i = 0; i < CERT_COUNTRIES.length; i++) if (B.has(by, CERT_COUNTRIES[i])) return by[CERT_COUNTRIES[i]];
    var vals = Object.keys(by).map(function (k) { return by[k]; });
    return vals.length ? Math.max.apply(null, vals) : null;
  };

  AR.preload = function () {
    if (loaded) return Promise.resolve();
    if (loading) return loading;
    loading = B.idb.get('ageRatings').then(function (m) {
      if (m && typeof m === 'object') Object.keys(m).forEach(function (k) { if (!B.has(cache, k)) cache[k] = m[k]; });
      loaded = true;
      if (m && Object.keys(m).length) B.emit('ageRatings');
    }, function () { loaded = true; });
    return loading;
  };
  function scheduleSave() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(function () { B.idb.set('ageRatings', cache); }, 4000);
  }
  B.on('wipe', function () { cache = {}; });

  function apiKey() { return B.account.serverKey('tmdbAPIKey') || ''; }
  function tmdb(path, params) {
    var key = apiKey();
    if (!key) return Promise.resolve(null);
    var q = (params || []).slice(), h = { 'Accept': 'application/json' };
    // v4-token (lang, begint met eyJ) als Bearer, anders een v3-sleutel als query
    if (key.length > 40) h.Authorization = 'Bearer ' + key; else q.push(['api_key', key]);
    var u = q.length ? B.query('https://api.themoviedb.org/3' + path, q) : 'https://api.themoviedb.org/3' + path;
    return B.http(u, { headers: h, timeout: 15000 }).then(function (r) {
      if (r.status === 429) throw new Error('rate');
      return r.status >= 200 && r.status < 300 ? r.json : null;
    });
  }
  /** = TMDB.ageInfo op Android. null = netwerkfout (niets opslaan, later opnieuw). */
  AR.ageInfo = function (imdb, title, year, isShow) {
    var now = Date.now();
    var hitP;
    if (imdb) hitP = tmdb('/find/' + imdb, [['external_source', 'imdb_id']]).then(function (f) {
      if (!f) return undefined;
      return (f[isShow ? 'tv_results' : 'movie_results'] || [])[0] || null;
    });
    else hitP = Promise.resolve(null);
    return hitP.then(function (hit) {
      if (hit === undefined) return undefined;
      if (hit) return hit;
      var path = isShow ? '/search/tv' : '/search/movie';
      var q = [['query', title], ['include_adult', 'true']];
      if (year != null) q.push([isShow ? 'first_air_date_year' : 'year', String(year)]);
      return tmdb(path, q).then(function (r) {
        if (!r) return undefined;
        var h = (r.results || [])[0];
        if (h || year == null) return h || null;
        // jaar uit de lijst klopt niet altijd: zonder jaar nog een keer
        return tmdb(path, [['query', title], ['include_adult', 'true']]).then(function (r2) { return r2 ? ((r2.results || [])[0] || null) : undefined; });
      });
    }).then(function (h) {
      if (h === undefined) return null;
      if (!h || h.id == null) return { found: false, checked: now };
      var genres = (h.genre_ids || []).map(function (g) { return B.int(g); }).filter(function (g) { return g != null; });
      var p = isShow ? tmdb('/tv/' + h.id + '/content_ratings') : tmdb('/movie/' + h.id + '/release_dates');
      return p.then(function (r) {
        if (!r) return null;
        var certs = [];
        (r.results || []).forEach(function (c) {
          if (!c || !c.iso_3166_1) return;
          if (isShow) { if (c.rating) certs.push([c.iso_3166_1, c.rating]); }
          else (c.release_dates || []).forEach(function (d) { if (d && d.certification) certs.push([c.iso_3166_1, d.certification]); });
        });
        var age = h.adult === true ? 18 : AR.ageFromCertifications(certs);
        return { id: h.id, age: age, genres: genres, found: true, checked: now };
      });
    }).catch(function () { return null; });
  };
  function fresh(r) { return !!r && (r.found !== false || Date.now() - (r.checked || 0) < RETRY_MS); }
  function fetchOne(t) {
    var k = AR.key(t.title, t.year, t.isShow);
    if (fresh(cache[k])) return Promise.resolve(cache[k]);
    var p = B.parseTitle(t.title);
    var clean = p.title || t.title;
    return AR.ageInfo(t.imdb, clean, t.year != null ? t.year : p.year, t.isShow).then(function (r) {
      if (!r) return cache[k] || null;
      cache[k] = r;
      scheduleSave();
      return r;
    });
  }
  AR.lookup = function (x, isShow) { return AR.preload().then(function () { return fetchOne({ title: x.rawTitle || x.title, year: x.year, isShow: isShow, imdb: x.imdbID || null }); }); };

  var bump = B.debounce(function () { B.emit('ageRatings'); }, 2500);
  /** Hele bibliotheek bijwerken (alleen bij een profiel met leeftijdsgrens). Kindergroepen eerst. */
  AR.warm = function (raw) {
    if (!apiKey() || running || !raw) return Promise.resolve();
    running = true;
    return AR.preload().then(function () {
      var seen = {}, todo = [];
      function add(x, isShow) {
        var k = AR.key(x.title, x.year, isShow);
        if (seen[k] || fresh(cache[k])) return;
        seen[k] = true;
        todo.push({ title: x.title, year: x.year, isShow: isShow, imdb: x.imdbID || null, kids: B.parental.isKidsGroup(x.group), rating: x.rating || 0 });
      }
      raw.movies.forEach(function (m) { add(m, false); });
      raw.series.forEach(function (s) { add(s, true); });
      todo.sort(function (a, b) { return (b.kids ? 1 : 0) - (a.kids ? 1 : 0) || b.rating - a.rating; });
      todo = todo.slice(0, MAX_PER_RUN);
      var i = 0, since = 0;
      return new Promise(function (resolve) {
        function batch() {
          if (i >= todo.length || !running) { resolve(); return; }
          // pas verder als er even geen toets is ingedrukt: scrollen gaat voor
          B.whenQuiet(function () {
            var part = todo.slice(i, i + 4);
            i += part.length;
            Promise.all(part.map(fetchOne)).then(function () {
              since += part.length;
              if (since >= 40) { since = 0; bump(); }
              setTimeout(batch, 80);
            });
          }, 500);
        }
        batch();
      }).then(function () { bump(); });
    }).then(function () { running = false; }, function () { running = false; });
  };
  AR.stop = function () { running = false; };
  AR._cache = function () { return cache; };
})();
