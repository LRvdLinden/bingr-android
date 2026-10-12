/*
 * Koppelingen: Trakt en OpenSubtitles, gedeeld via state-section `connections` (build 44):
 *   {trakt: {accessToken, refreshToken, expiresAt, username} | null,
 *    opensubtitles: {token, username, baseURL} | null, updatedAt}
 * Het geheel met de nieuwste updatedAt wint. Trakt-refresh-tokens zijn eenmalig: vóór het verversen eerst de
 * server lezen; na het verversen meteen uploaden (= StateSync.kt / Trakt.kt / OpenSubtitles.kt op Android).
 * Trakt hoort bij het hoofdprofiel: andere profielen scrobblen niet naar dat account.
 */
(function () {
  'use strict';
  var B = window.B;
  var A = B.account;
  var L = function () { return B.L.apply(null, arguments); };
  var C = B.connections = {};

  // MARK: lokale kopie en samenvoegen

  var local = B.store.get('connections', null);   // {data, at}
  function save() { B.store.set('connections', local); }
  function at(v) { return B.parseISO(v) || 0; }
  C.data = function () { return (local && local.data) || {}; };
  C.trakt = function () { var t = C.data().trakt; return t && t.accessToken && t.refreshToken ? t : null; };
  C.opensubtitles = function () { var o = C.data().opensubtitles; return o && o.token ? o : null; };

  /** Lokale wijziging: tijd = nu, meteen uploaden (refresh-tokens zijn eenmalig). */
  function changed(mut) {
    var d = B.copy(C.data()) || {};
    mut(d);
    var now = B.iso();
    d.updatedAt = now;
    local = { data: d, at: Date.parse(now) };
    save();
    B.emit('connections');
    return upload();
  }
  function upload() {
    if (!local) return Promise.resolve(false);
    return A.putState('connections', local.data).then(function (r) { return r != null; }).catch(function () { return false; });
  }
  /** Section van de server toepassen (bij elke sync). */
  function merge(sec) {
    var data = sec && sec.data;
    if (!data || typeof data !== 'object') {
      if (local && (C.trakt() || C.opensubtitles())) upload();
      return;
    }
    var srvAt = at(data.updatedAt) || at(sec.updatedAt);
    var locAt = local ? local.at || 0 : 0;
    if (!local || srvAt > locAt) {
      var before = JSON.stringify(C.data());
      local = { data: data, at: srvAt };
      save();
      if (before !== JSON.stringify(data)) { B.emit('connections'); Trakt.onTokens(); }
    } else if (locAt > srvAt) upload();
  }
  B.on('state', function (all) { if (all && B.has(all, 'connections')) merge(all.connections); else if (all && local) merge(null); });
  B.on('wipe', function () { local = null; B.store.del('connections'); B.store.del('trakt.cache'); Trakt.reset(); });
  C.sync = function () { return A.getState(['connections']).then(function (all) { if (all) merge(all.connections || null); }); };

  // MARK: SHA-256 (PKCE voor Trakt; crypto.subtle bestaat niet altijd op de tv)

  function sha256(ascii) {
    function rr(v, n) { return (v >>> n) | (v << (32 - n)); }
    var K = [], H = [], i, j, prime = 2, n = 0;
    function frac(x) { return ((x - Math.floor(x)) * 4294967296) | 0; }
    while (n < 64) {
      var isP = true;
      for (j = 2; j * j <= prime; j++) if (prime % j === 0) { isP = false; break; }
      if (isP) { if (n < 8) H[n] = frac(Math.pow(prime, 1 / 2)); K[n++] = frac(Math.pow(prime, 1 / 3)); }
      prime++;
    }
    var bytes = [];
    for (i = 0; i < ascii.length; i++) bytes.push(ascii.charCodeAt(i) & 0xff);
    var bitLen = bytes.length * 8;
    bytes.push(0x80);
    while (bytes.length % 64 !== 56) bytes.push(0);
    for (i = 7; i >= 0; i--) bytes.push(i >= 4 ? 0 : (bitLen >>> (i * 8)) & 0xff);
    var w = new Array(64);
    for (i = 0; i < bytes.length; i += 64) {
      for (j = 0; j < 16; j++) w[j] = (bytes[i + j * 4] << 24) | (bytes[i + j * 4 + 1] << 16) | (bytes[i + j * 4 + 2] << 8) | bytes[i + j * 4 + 3];
      for (j = 16; j < 64; j++) {
        var s0 = rr(w[j - 15], 7) ^ rr(w[j - 15], 18) ^ (w[j - 15] >>> 3);
        var s1 = rr(w[j - 2], 17) ^ rr(w[j - 2], 19) ^ (w[j - 2] >>> 10);
        w[j] = (w[j - 16] + s0 + w[j - 7] + s1) | 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (j = 0; j < 64; j++) {
        var t1 = (h + (rr(e, 6) ^ rr(e, 11) ^ rr(e, 25)) + ((e & f) ^ (~e & g)) + K[j] + w[j]) | 0;
        var t2 = ((rr(a, 2) ^ rr(a, 13) ^ rr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }
    var out = [];
    for (i = 0; i < 8; i++) for (j = 3; j >= 0; j--) out.push((H[i] >>> (j * 8)) & 0xff);
    return out;
  }
  function b64url(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
  C._sha256 = sha256; C._b64url = b64url;

  // MARK: Trakt

  var BASE = 'https://api.trakt.tv';
  var OAUTH_HOSTS = ['https://api.trakt.tv', 'https://auth.trakt.tv'];
  var REDIRECTS = ['https://github.com/LRvdLinden/iptv-ios-appletv', 'urn:ietf:wg:oauth:2.0:oob'];
  var Trakt = B.trakt = {};
  var cacheT = B.store.get('trakt.cache', null) || { movies: [], shows: {}, index: {}, activity: null };
  var watchedMovies = {}, watchedShows = {};
  function loadCache() {
    watchedMovies = {}; watchedShows = {};
    (cacheT.movies || []).forEach(function (i) { watchedMovies[i] = true; });
    Object.keys(cacheT.shows || {}).forEach(function (k) { var set = {}; (cacheT.shows[k] || []).forEach(function (e) { set[e] = true; }); watchedShows[k] = set; });
  }
  loadCache();
  function saveCache() { B.store.set('trakt.cache', cacheT); }
  Trakt.reset = function () { cacheT = { movies: [], shows: {}, index: {}, activity: null }; loadCache(); };

  Trakt.clientID = function () { return A.serverKey('traktClientID') || ''; };
  Trakt.ready = function () { return !!Trakt.clientID(); };
  /** Alleen het hoofdprofiel (of het enige profiel) gebruikt Trakt. */
  Trakt.allowed = function () {
    var p = B.app && B.app.profile;
    return !!p && (B.data.isMain(p) || B.data.profiles.length <= 1);
  };
  Trakt.isLoggedIn = function () { return !!C.trakt() && Trakt.allowed(); };
  Trakt.username = function () { var t = C.trakt(); return t ? t.username || '' : ''; };
  Trakt.scrobbleEnabled = function () { return B.bool(B.data.shared('traktScrobble', true)) !== false; };
  Trakt.lastScrobble = null;

  function apiHeaders(token) {
    var h = { 'Content-Type': 'application/json', 'trakt-api-version': '2', 'trakt-api-key': Trakt.clientID() };
    if (token) h.Authorization = 'Bearer ' + token;
    return h;
  }
  function oauthBody(extra) {
    var o = B.assign({}, extra, { client_id: Trakt.clientID() });
    var sec = A.serverKey('traktClientSecret');
    if (sec) o.client_secret = sec;
    return o;
  }
  var oauthBase = B.store.get('trakt.oauthHost', BASE) || BASE;
  function oauthPost(path, body, host) {
    return B.http((host || oauthBase) + path, { method: 'POST', headers: apiHeaders(null), body: body, timeout: 20000 });
  }
  function parseTokens(j) {
    if (!j || !j.access_token || !j.refresh_token) return null;
    var created = B.num(j.created_at) || Math.floor(Date.now() / 1000);
    var exp = B.num(j.expires_in) || 0;
    return { accessToken: j.access_token, refreshToken: j.refresh_token, expiresAt: B.iso((created + exp) * 1000) };
  }

  var verifier = '';
  function randomVerifier() {
    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~', out = '';
    var b = new Uint8Array(64);
    window.crypto.getRandomValues(b);
    for (var i = 0; i < 64; i++) out += chars.charAt(b[i] % chars.length);
    return out;
  }
  /** Koppelcode aanvragen (trakt.tv/activate): {deviceCode, userCode, url, expiresIn, interval}. */
  Trakt.startLogin = function () {
    verifier = randomVerifier();
    var body = { client_id: Trakt.clientID(), code_challenge: b64url(sha256(verifier)), code_challenge_method: 'S256' };
    var i = 0, lastErr = null;
    function next() {
      if (i >= OAUTH_HOSTS.length) throw lastErr || new Error(L('Something went wrong. Please try again.'));
      var host = OAUTH_HOSTS[i++];
      return oauthPost('/oauth/device/code', body, host).then(function (r) {
        var j = r.json || {};
        if (r.status >= 200 && r.status < 300 && j.device_code && j.user_code) {
          oauthBase = host; B.store.set('trakt.oauthHost', host);
          return { deviceCode: j.device_code, userCode: j.user_code, url: j.verification_url || 'https://trakt.tv/activate',
            expiresIn: B.int(j.expires_in) || 600, interval: Math.max(5, B.int(j.interval) || 5) };
        }
        lastErr = new Error('Trakt (' + r.status + ')');
        return next();
      }, function (e) { lastErr = e; return next(); });
    }
    return next();
  };
  /** Pollen tot de code bevestigd is; daarna tokens in `connections` en meteen uploaden. cancel() via het object. */
  Trakt.finishLogin = function (code) {
    var cancelled = false, interval = code.interval;
    var deadline = Date.now() + code.expiresIn * 1000;
    var p = new Promise(function (resolve, reject) {
      function poll() {
        if (cancelled) return;
        if (Date.now() > deadline) { reject(new Error(L('The code has expired. Please try again.'))); return; }
        oauthPost('/oauth/device/token', oauthBody({ code: code.deviceCode, code_verifier: verifier })).then(function (r) {
          if (cancelled) return;
          if (r.status === 200) {
            var t = parseTokens(r.json);
            if (!t) { reject(new Error(L('Something went wrong. Please try again.'))); return; }
            B.http(BASE + '/users/settings', { headers: apiHeaders(t.accessToken), timeout: 15000 }).then(function (u) {
              t.username = (u.json && u.json.user && u.json.user.username) || null;
            }, B.noop).then(function () {
              return changed(function (d) { d.trakt = t; });
            }).then(function () { Trakt.refresh(true); resolve(t); });
            return;
          }
          if (r.status === 429) interval += 1;
          else if ([404, 409, 410, 418].indexOf(r.status) >= 0) { reject(new Error(L('Trakt refused the connection (%d).', r.status))); return; }
          setTimeout(poll, interval * 1000);
        }, function () { if (!cancelled) setTimeout(poll, interval * 1000); });
      }
      setTimeout(poll, interval * 1000);
    });
    p.cancel = function () { cancelled = true; };
    return p;
  };
  Trakt.logout = function () {
    var t = C.trakt();
    if (t) oauthPost('/oauth/revoke', oauthBody({ token: t.accessToken })).catch(B.noop);
    Trakt.reset(); saveCache();
    return changed(function (d) { d.trakt = null; });
  };
  Trakt.onTokens = function () { if (Trakt.isLoggedIn()) Trakt.refresh(false); };

  var refreshing = null;
  /** Geldig access-token; ververst als het binnen 24 uur verloopt (eerst kijken of een ander apparaat dat al deed). */
  function validToken() {
    var t = C.trakt();
    if (!t) return Promise.reject(new Error(L('Not signed in to Trakt.')));
    if (at(t.expiresAt) - Date.now() > 24 * 3600000) return Promise.resolve(t.accessToken);
    if (refreshing) return refreshing;
    refreshing = C.sync().catch(B.noop).then(function () {
      var cur = C.trakt();
      if (!cur) throw new Error(L('Not signed in to Trakt.'));
      if (at(cur.expiresAt) - Date.now() > 24 * 3600000) return cur.accessToken;
      var i = 0, unauthorized = 0;
      function next() {
        if (i >= REDIRECTS.length) {
          if (unauthorized === REDIRECTS.length && at(cur.expiresAt) < Date.now()) { changed(function (d) { d.trakt = null; }); throw new Error(L('Not signed in to Trakt.')); }
          return cur.accessToken;
        }
        var red = REDIRECTS[i++];
        return oauthPost('/oauth/token', oauthBody({ refresh_token: cur.refreshToken, redirect_uri: red, grant_type: 'refresh_token' })).then(function (r) {
          var n = r.status === 200 ? parseTokens(r.json) : null;
          if (n) {
            n.username = cur.username || null;
            return changed(function (d) { d.trakt = n; }).then(function () { return n.accessToken; });
          }
          if (r.status === 400 || r.status === 401) unauthorized++;
          return next();
        }, function () { return cur.accessToken; });
      }
      return next();
    });
    var p = refreshing;
    p.then(function () { refreshing = null; }, function () { refreshing = null; });
    return p;
  }
  function get(path, params, auth) {
    var u = params && params.length ? B.query(BASE + path, params) : BASE + path;
    var tp = auth === false ? Promise.resolve(null) : validToken();
    return tp.then(function (token) {
      return B.http(u, { headers: apiHeaders(token), timeout: 30000 }).then(function (r) {
        if (r.status < 200 || r.status >= 300) throw new Error('Trakt ' + r.status);
        return r.json;
      });
    });
  }
  function post(path, body) {
    return validToken().then(function (token) {
      return B.http(BASE + path, { method: 'POST', headers: apiHeaders(token), body: body, timeout: 20000 }).then(function (r) {
        if ((r.status < 200 || r.status >= 300) && r.status !== 409) throw new Error('Trakt ' + r.status);
        return r.json;
      });
    });
  }
  Trakt._get = get;

  // titel → IMDb-index (Trakt-titels en eerder opgezochte IPTV-titels)
  function idxKey(title, year, show) { return (show ? 's' : 'm') + '|' + B.fold(title).replace(/[^a-z0-9]+/g, ' ').trim() + '|' + (year || ''); }
  function indexAdd(title, year, imdb, show) {
    if (!title || !imdb) return;
    cacheT.index[idxKey(title, year, show)] = imdb;
    cacheT.index[idxKey(title, null, show)] = cacheT.index[idxKey(title, null, show)] || imdb;
  }
  function indexed(title, year, show) {
    var ix = cacheT.index || {};
    var p = B.parseTitle(title);
    return ix[idxKey(title, year, show)] || ix[idxKey(p.title, year || p.year, show)] || ix[idxKey(p.title, null, show)] || null;
  }
  Trakt.imdbFor = function (x, show) { return (x && x.imdbID) || (x ? indexed(x.rawTitle || x.title, x.year, show) : null); };
  Trakt.isMovieWatched = function (m) { var i = Trakt.isLoggedIn() ? Trakt.imdbFor(m, false) : null; return !!(i && watchedMovies[i]); };
  Trakt.isEpisodeWatched = function (series, season, ep) {
    var i = Trakt.isLoggedIn() ? Trakt.imdbFor(series, true) : null;
    return !!(i && watchedShows[i] && watchedShows[i][season + '-' + ep]);
  };

  var syncing = false;
  /** Bekeken films/afleveringen ophalen (alleen als /sync/last_activities iets nieuws meldt, of force). */
  Trakt.refresh = function (force) {
    if (!Trakt.isLoggedIn() || syncing) return Promise.resolve();
    syncing = true;
    return get('/sync/last_activities').then(function (act) {
      var all = act && act.all;
      if (!force && all && all === cacheT.activity && (cacheT.movies.length || Object.keys(cacheT.shows).length)) return;
      return Promise.all([get('/sync/watched/movies'), get('/sync/watched/shows')]).then(function (r) {
        var movies = [], shows = {};
        (r[0] || []).forEach(function (x) {
          var mv = x && x.movie, i = mv && mv.ids && mv.ids.imdb;
          if (!i) return;
          movies.push(i); indexAdd(mv.title, mv.year, i, false);
        });
        (r[1] || []).forEach(function (x) {
          var sh = x && x.show, i = sh && sh.ids && sh.ids.imdb;
          if (!i) return;
          indexAdd(sh.title, sh.year, i, true);
          var eps = [];
          (x.seasons || []).forEach(function (s) { (s.episodes || []).forEach(function (e) { if (s.number != null && e.number != null) eps.push(s.number + '-' + e.number); }); });
          shows[i] = eps;
        });
        cacheT.movies = movies; cacheT.shows = shows; cacheT.activity = all || null;
        saveCache(); loadCache();
        B.emit('trakt');
      });
    }).catch(function (e) { B.log('trakt', e && e.message); }).then(function () { syncing = false; });
  };

  /** IMDb-id van een item: eigen id, de index, anders zoeken bij Trakt (zonder login). */
  function resolveImdb(item) {
    if (item.imdbID) return Promise.resolve(item.imdbID);
    var show = item.type === 'episode';
    var hit = indexed(item.title, item.year || null, show);
    if (hit) return Promise.resolve(hit);
    var p = B.parseTitle(item.title);
    var year = show ? p.year : (p.year || B.int(item.subtitle));
    var q = [['query', p.title], ['extended', 'full'], ['limit', '5']];
    if (year) q.push(['years', String(year)]);
    function look(params) {
      return get('/search/' + (show ? 'show' : 'movie'), params, false).then(function (list) {
        var h = list && list[0];
        var m = h && (h.movie || h.show);
        return m && m.ids && m.ids.imdb ? { imdb: m.ids.imdb, title: m.title, year: m.year } : null;
      }, function () { return null; });
    }
    return look(q).then(function (r) {
      if (r || !year) return r;
      return look([['query', p.title], ['extended', 'full'], ['limit', '5']]);
    }).then(function (r) {
      if (!r) return null;
      indexAdd(item.title, null, r.imdb, show); indexAdd(r.title, r.year, r.imdb, show); saveCache();
      return r.imdb;
    });
  }
  /**
   * Scrobble start/pause/stop (progress 0…1). Bij stop ≥ 80% telt het als gezien; mislukt de scrobble dan,
   * dan via /sync/history. Live en terugkijken worden niet gescrobbled.
   */
  Trakt.scrobble = function (action, item, progress) {
    if (!Trakt.isLoggedIn() || !Trakt.scrobbleEnabled() || !item || (item.type !== 'movie' && item.type !== 'episode')) return Promise.resolve('skipped');
    return resolveImdb(item).then(function (imdb) {
      if (!imdb) { Trakt.lastScrobble = L('Not found on Trakt: %@', item.title); return 'notfound'; }
      var isEp = item.type === 'episode' && item.season != null && item.episode != null;
      var body = { progress: B.clamp(progress * 100, 0, 100), app_version: '1.0.0' };
      if (isEp) { body.show = { ids: { imdb: imdb } }; body.episode = { season: item.season, number: item.episode }; }
      else body.movie = { ids: { imdb: imdb } };
      var watched = action === 'stop' && progress >= 0.8;
      return post('/scrobble/' + action, body).then(function () { return 'ok'; }, function () { return 'failed'; }).then(function (res) {
        if (!watched) return res;
        var hist = isEp ? { shows: [{ ids: { imdb: imdb }, seasons: [{ number: item.season, episodes: [{ number: item.episode }] }] }] }
          : { movies: [{ ids: { imdb: imdb } }] };
        var p = res === 'failed' ? post('/sync/history', hist).then(function () { return 'ok'; }, function () { return 'failed'; }) : Promise.resolve(res);
        return p.then(function (r2) {
          if (r2 === 'ok') {
            if (isEp) { (watchedShows[imdb] = watchedShows[imdb] || {})[item.season + '-' + item.episode] = true; (cacheT.shows[imdb] = cacheT.shows[imdb] || []).push(item.season + '-' + item.episode); }
            else { watchedMovies[imdb] = true; cacheT.movies.push(imdb); }
            saveCache();
            Trakt.lastScrobble = L('Marked as watched on Trakt: %@', item.title);
          }
          setTimeout(function () { Trakt.refresh(true); }, 3000);
          return r2;
        });
      });
    }).catch(function () { return 'failed'; });
  };

  // MARK: OpenSubtitles (REST v1, eigen account; API-sleutel van de server)

  var OS_HOST = 'api.opensubtitles.com';
  var OS = B.opensubtitles = {};
  OS.apiKey = function () { return A.serverKey('openSubtitlesAPIKey') || ''; };
  OS.ready = function () { return !!OS.apiKey(); };
  OS.isLoggedIn = function () { return !!C.opensubtitles(); };
  OS.username = function () { var o = C.opensubtitles(); return o ? o.username || '' : ''; };
  OS.languages = [['nl', 'Nederlands'], ['en', 'English'], ['de', 'Deutsch'], ['fr', 'Français'], ['es', 'Español'], ['it', 'Italiano'],
    ['pt-PT', 'Português'], ['tr', 'Türkçe'], ['ar', 'العربية'], ['pl', 'Polski'], ['zh-CN', '简体中文']];
  /** Standaardtaal: settings.shared.openSubtitlesLanguage, anders de ondertiteltaal, anders de app-taal. */
  OS.defaultLanguage = function () {
    var D = B.data;
    var v = D.shared('openSubtitlesLanguage', null);
    if (v != null) return String(v);
    var t = String(D.shared('subtitleLanguage', '') || '');
    return t.length === 2 ? t : String(B.lang || 'en').slice(0, 2);
  };
  function apiCodes(c) { c = String(c || '').toLowerCase(); if (c === 'pt' || c === 'pt-pt') return ['pt-br', 'pt-pt']; if (c === 'zh' || c === 'zh-cn') return ['zh-cn', 'zh-tw']; return c ? [c] : []; }
  function osRequest(path, method, params, body, host) {
    var o = C.opensubtitles();
    var h = host || (o && o.baseURL) || OS_HOST;
    h = String(h).replace(/^https?:\/\//, '').replace(/\/+$/, '');
    var u = 'https://' + h + '/api/v1' + path;
    if (params) {
      var list = [];
      Object.keys(params).sort().forEach(function (k) { list.push([k, params[k]]); });
      if (list.length) u = B.query(u, list);
    }
    var hd = { 'Api-Key': OS.apiKey(), 'Content-Type': 'application/json', 'Accept': 'application/json' };
    if (o && o.token && path !== '/login') hd.Authorization = 'Bearer ' + o.token;
    return B.http(u, { method: method || 'GET', headers: hd, body: body, timeout: 30000 }).then(function (r) {
      if (r.status === 401 && o && path !== '/login') changed(function (d) { d.opensubtitles = null; });
      if (r.status < 200 || r.status >= 300) throw new Error((r.json && r.json.message) || ('OpenSubtitles ' + r.status));
      return r.json || {};
    });
  }
  OS.login = function (user, pass) {
    return osRequest('/login', 'POST', null, { username: user, password: pass }, OS_HOST).then(function (j) {
      if (!j.token) throw new Error(L('Incorrect email or password.'));
      return changed(function (d) { d.opensubtitles = { token: j.token, username: user, baseURL: j.base_url || OS_HOST }; });
    });
  };
  OS.logout = function () {
    osRequest('/logout', 'DELETE').catch(B.noop);
    return changed(function (d) { d.opensubtitles = null; });
  };
  /** Zoeken op IMDb-id (afleveringen: parent_imdb_id + seizoen/aflevering) of anders op titel. */
  OS.search = function (opts, languages) {
    var q = {};
    var langs = [];
    (languages || []).forEach(function (l) { apiCodes(l).forEach(function (c) { if (langs.indexOf(c) < 0) langs.push(c); }); });
    langs.sort();
    if (langs.length) q.languages = langs.join(',');
    var numeric = B.int(String(opts.imdb || '').replace('tt', ''));
    var isEp = opts.season != null && opts.episode != null;
    if (numeric && numeric > 0) {
      if (isEp) { q.parent_imdb_id = String(numeric); q.season_number = String(opts.season); q.episode_number = String(opts.episode); }
      else q.imdb_id = String(numeric);
    } else if (opts.title) {
      var p = B.parseTitle(opts.title);
      q.query = p.title.toLowerCase();
      if (isEp) { q.type = 'episode'; q.season_number = String(opts.season); q.episode_number = String(opts.episode); }
      else { q.type = 'movie'; if (opts.year || p.year) q.year = String(opts.year || p.year); }
    } else return Promise.resolve([]);
    return osRequest('/subtitles', 'GET', q).then(function (j) {
      var subs = (j.data || []).map(function (i) {
        var a = i.attributes || {}, f = (a.files || [])[0];
        if (!f || f.file_id == null) return null;
        return { id: f.file_id, language: a.language || '?', release: a.release || f.file_name || 'Subtitle', downloads: B.int(a.download_count) || 0,
          hearingImpaired: !!a.hearing_impaired, trusted: !!a.from_trusted };
      }).filter(Boolean);
      var order = (languages || []).map(function (l) { return String(l).toLowerCase().slice(0, 2); });
      function rank(s) { var r = order.indexOf(String(s.language).toLowerCase().slice(0, 2)); return r < 0 ? 99 : r; }
      return subs.sort(function (a, b) { return rank(a) - rank(b) || (b.trusted ? 1 : 0) - (a.trusted ? 1 : 0) || b.downloads - a.downloads; });
    });
  };
  /** Downloaden → tekst (UTF-8, anders Windows-1252) → cues [{start, end, text}] in seconden. */
  OS.download = function (sub) {
    if (!OS.isLoggedIn()) return Promise.reject(new Error(L('Sign in to OpenSubtitles first (My Bingr).')));
    return osRequest('/download', 'POST', null, { file_id: sub.id, sub_format: 'srt' }).then(function (j) {
      if (!j.link) throw new Error(L('Something went wrong. Please try again.'));
      return B.http(j.link, { binary: true, timeout: 30000 });
    }).then(function (r) {
      if (r.status < 200 || r.status >= 300 || !r.buffer) throw new Error(L('Something went wrong. Please try again.'));
      return B.subs.parse(B.subs.decode(r.buffer));
    });
  };

  // MARK: ondertitels lezen (SRT en WebVTT) voor de eigen weergave in de speler

  var Sub = B.subs = {};
  Sub.decode = function (buf) {
    var u8 = new Uint8Array(buf);
    if (u8.length >= 2 && u8[0] === 0xff && u8[1] === 0xfe && window.TextDecoder) return new TextDecoder('utf-16le').decode(u8.subarray(2));
    if (u8.length >= 2 && u8[0] === 0xfe && u8[1] === 0xff && window.TextDecoder) return new TextDecoder('utf-16be').decode(u8.subarray(2));
    if (window.TextDecoder) {
      try { return new TextDecoder('utf-8', { fatal: true }).decode(u8); } catch (e) {
        try { return new TextDecoder('windows-1252').decode(u8); } catch (e2) { /* verder */ }
      }
    }
    var s = '';
    for (var i = 0; i < u8.length; i += 8192) s += String.fromCharCode.apply(null, Array.prototype.slice.call(u8, i, i + 8192));
    try { return decodeURIComponent(escape(s)); } catch (e3) { return s; }
  };
  function ts(t) {
    var m = /(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})/.exec(t);
    if (!m) return null;
    return (parseInt(m[1] || '0', 10) * 3600) + parseInt(m[2], 10) * 60 + parseInt(m[3], 10) + parseInt((m[4] + '00').slice(0, 3), 10) / 1000;
  }
  Sub.parse = function (text) {
    var out = [];
    var blocks = String(text || '').replace(/^﻿/, '').replace(/\r\n?/g, '\n').split(/\n{2,}/);
    blocks.forEach(function (b) {
      var lines = b.split('\n').filter(function (l) { return l.trim() !== ''; });
      for (var i = 0; i < lines.length; i++) {
        if (lines[i].indexOf('-->') >= 0) {
          var parts = lines[i].split('-->');
          var s = ts(parts[0]), e = ts(parts[1]);
          if (s == null || e == null) return;
          var txt = lines.slice(i + 1).join('\n').replace(/<[^>]+>/g, '').replace(/\{\\[^}]*\}/g, '');
          if (txt.trim()) out.push({ start: s, end: e, text: txt });
          return;
        }
      }
    });
    out.sort(function (a, b) { return a.start - b.start; });
    return out;
  };
  /** Cue op tijdstip t (binair zoeken). */
  Sub.at = function (cues, t) {
    var lo = 0, hi = cues.length - 1, ans = -1;
    while (lo <= hi) { var mid = (lo + hi) >> 1; if (cues[mid].start <= t) { ans = mid; lo = mid + 1; } else hi = mid - 1; }
    var lines = [];
    for (var i = ans; i >= 0 && i > ans - 4; i--) if (cues[i].start <= t && t < cues[i].end) lines.unshift(cues[i].text);
    return lines.join('\n');
  };
})();
