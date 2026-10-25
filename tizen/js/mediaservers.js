/*
 * Mediaservers: Jellyfin/Emby, Plex en Stremio (= MediaServerClients.kt op Android, MediaServerClients.swift op iOS).
 * Zelfde id's als de andere apps (jf…, jfs…, jfe…, jfc…, px…, pxs…, pxe…, sm<tt>-<catalogus>, ss<tt>-<catalogus>, se…),
 * zodat voortgang, lijsten en collecties tussen de apparaten gelijk blijven.
 * Inloggegevens komen uit de gesynchroniseerde `secrets` van de bron:
 *   session  = JSON {token, userID, serverURI} (Plex, Jellyfin, Emby)
 *   password = Plex-gebruikerstoken (om de server opnieuw te zoeken) of Stremio-authKey
 */
(function () {
  'use strict';
  var B = window.B;
  var L = function () { return B.L.apply(null, arguments); };
  var M = B.media = B.media || {};
  var P = B.providers;

  function urlValue(s) { return P.urlValue(s); }
  function q(base, params) {
    var list = [];
    Object.keys(params).sort().forEach(function (k) { if (params[k] != null) list.push([k, String(params[k])]); });
    return list.length ? B.query(base, list) : base;
  }
  function fail(msg) { return new Error(msg); }
  M.notSignedIn = function () { return fail(L('Not signed in. Edit the playlist and sign in again.')); };

  /** Sessie uit de secrets van een bron; null als er geen (geldige) is. */
  M.session = function (src) {
    var raw = src && src.secrets && src.secrets.session;
    if (!raw) return null;
    var s = raw;
    if (typeof raw === 'string') { try { s = JSON.parse(raw); } catch (e) { return null; } }
    if (!s || !s.token) return null;
    return { token: String(s.token), userID: s.userID != null ? String(s.userID) : null, serverURI: String(s.serverURI || '') };
  };
  /** Sessie als tekst, precies zoals iOS en Android hem in de sleutelhanger zetten. */
  M.sessionString = function (s) {
    return JSON.stringify({ token: s.token, userID: s.userID == null ? null : s.userID, serverURI: s.serverURI });
  };

  function getJSON(url, headers, timeout) {
    var h = B.assign({ 'Accept': 'application/json' }, headers || {});
    return B.http(url, { headers: h, timeout: timeout || 60000 }).then(function (r) {
      if (r.status === 401 || r.status === 403) throw M.notSignedIn();
      if (r.status < 200 || r.status >= 300) throw fail('HTTP ' + r.status);
      if (r.json == null) throw fail(L('Something went wrong. Please try again.'));
      return r.json;
    });
  }
  function postJSON(url, body, headers, timeout) {
    var h = B.assign({ 'Accept': 'application/json', 'Content-Type': 'application/json' }, headers || {});
    return B.http(url, { method: 'POST', headers: h, body: body || {}, timeout: timeout || 30000 });
  }
  function list(o, k) { var v = o && o[k]; return Array.isArray(v) ? v : []; }
  function num(v) { return B.num(v); }

  // MARK: Jellyfin / Emby (zelfde API)

  function Jellyfin(src, session) {
    this.server = B.normalizedServer(src.serverURL);
    this.isEmby = src.kind === 'Emby';
    this.token = session ? session.token : null;
    this.userID = session ? session.userID : null;
  }
  Jellyfin.prototype.headers = function () {
    var v = 'MediaBrowser Client="Bingr", Device="' + B.platform.name + '", DeviceId="' + B.account.deviceId() + '", Version="1.1.0"';
    if (this.token) v += ', Token="' + this.token + '"';
    return { 'X-Emby-Authorization': v, 'Authorization': v };
  };
  Jellyfin.prototype.url = function (path, params) { return q(this.server + path, params || {}); };
  Jellyfin.prototype.login = function (username, password) {
    var self = this;
    return postJSON(this.url('/Users/AuthenticateByName'), { Username: username, Pw: password, Password: password }, this.headers()).then(function (r) {
      var j = r.json || {};
      if (r.status === 401 || r.status === 403) throw fail(L("Couldn't sign in to the server. Check the server, username and password."));
      if (r.status < 200 || r.status >= 300 || !j.AccessToken || !j.User || !j.User.Id) throw fail(L("Couldn't sign in to the server. Check the server, username and password."));
      return { token: j.AccessToken, userID: String(j.User.Id), serverURI: self.server };
    }, function () { throw fail(L('No internet connection.')); });
  };
  Jellyfin.prototype.image = function (id) { return this.url('/Items/' + id + '/Images/Primary', { maxHeight: '600', quality: '90' }); };
  Jellyfin.prototype.stream = function (id) { return this.url('/Videos/' + id + '/stream', { 'static': 'true', api_key: this.token || '' }); };
  Jellyfin.prototype.items = function (u) { return getJSON(u, this.headers(), 90000).then(function (j) { return list(j, 'Items'); }); };
  Jellyfin.prototype.library = function () {
    var self = this, uid = this.userID;
    if (!uid || !this.token) return Promise.reject(M.notSignedIn());
    function items(type) {
      return self.items(self.url('/Users/' + uid + '/Items', { IncludeItemTypes: type, Recursive: 'true',
        Fields: 'ProviderIds,Overview,Genres,DateCreated,OfficialRating', SortBy: 'DateCreated', SortOrder: 'Descending', Limit: '3000' }));
    }
    var moviesL = L('Movies'), seriesL = L('Series');
    return Promise.all([items('Movie'), items('Series'),
      self.items(self.url('/LiveTv/Channels', { UserId: uid, EnableImages: 'true' })).catch(function () { return []; })
    ]).then(function (r) {
      var movies = r[0].filter(function (i) { return i && i.Id; }).map(function (i) {
        return { id: 'jf' + i.Id, title: i.Name || '?', year: B.int(i.ProductionYear), poster: self.image(i.Id),
          group: (list(i, 'Genres')[0]) || moviesL, streamURL: self.stream(i.Id), imdbID: (i.ProviderIds && i.ProviderIds.Imdb) || null,
          rating: num(i.CommunityRating), plot: i.Overview || null, addedAt: B.parseISO(i.DateCreated), rated: i.OfficialRating || null };
      });
      var series = r[1].filter(function (i) { return i && i.Id; }).map(function (i) {
        return { id: 'jfs' + i.Id, title: i.Name || '?', year: B.int(i.ProductionYear), poster: self.image(i.Id),
          group: (list(i, 'Genres')[0]) || seriesL, imdbID: (i.ProviderIds && i.ProviderIds.Imdb) || null,
          plot: i.Overview || null, rating: num(i.CommunityRating), remoteID: String(i.Id), rated: i.OfficialRating || null, episodes: [] };
      });
      var channels = r[2].filter(function (c) { return c && c.Id; }).map(function (c) {
        return { id: 'jfc' + c.Id, name: c.Name || '?', logo: self.image(c.Id), group: 'Live TV', streamURL: self.stream(c.Id),
          epgID: String(c.Id), number: B.int(c.Number), catchupDays: 0 };
      });
      var lib = { channels: channels, movies: movies, series: series, epg: {} };
      if (!channels.length) return lib;
      var now = Date.now();
      return self.items(self.url('/LiveTv/Programs', { UserId: uid, MinEndDate: new Date(now).toISOString(), MaxStartDate: new Date(now + 2 * 86400000).toISOString() }))
        .then(function (progs) {
          progs.forEach(function (p) {
            var cid = p.ChannelId, s = B.parseISO(p.StartDate), e = B.parseISO(p.EndDate);
            if (!cid || s == null || e == null) return;
            var k = String(cid).toLowerCase();
            (lib.epg[k] = lib.epg[k] || []).push({ title: p.Name || '', description: p.Overview || '', start: s, end: e });
          });
          Object.keys(lib.epg).forEach(function (k) { lib.epg[k].sort(function (a, b) { return a.start - b.start; }); });
          return lib;
        }, function () { return lib; });
    });
  };
  Jellyfin.prototype.episodes = function (series) {
    var self = this;
    if (!series.remoteID || !this.userID) return Promise.resolve([]);
    return this.items(this.url('/Shows/' + series.remoteID + '/Episodes', { UserId: this.userID, Fields: 'Overview' })).then(function (list) {
      return list.filter(function (e) { return e && e.Id; }).map(function (e) {
        return { id: 'jfe' + e.Id, seriesID: series.id, season: B.int(e.ParentIndexNumber) || 1, number: B.int(e.IndexNumber) || 0,
          title: e.Name || '', streamURL: self.stream(e.Id), still: self.image(e.Id), plot: e.Overview || null,
          durationSeconds: e.RunTimeTicks ? Math.floor(e.RunTimeTicks / 10000000) : null };
      }).sort(function (a, b) { return a.season - b.season || a.number - b.number; });
    });
  };
  M.Jellyfin = Jellyfin;

  // MARK: Plex (inloggen met een koppelcode op plex.tv/link)

  function plexHeaders() {
    return { 'X-Plex-Product': 'Bingr', 'X-Plex-Client-Identifier': B.account.deviceId(), 'X-Plex-Version': '1.1.0',
      'X-Plex-Device-Name': B.platform.name, 'X-Plex-Platform': B.platform.os, 'Accept': 'application/json' };
  }
  var Plex = M.Plex = function (session) { this.s = session; };
  Plex.createPin = function () {
    return B.http('https://plex.tv/api/v2/pins?strong=false', { method: 'POST', headers: plexHeaders(), timeout: 20000 }).then(function (r) {
      var j = r.json || {};
      if (!j.id || !j.code) throw fail(L('Something went wrong. Please try again.'));
      return { id: j.id, code: String(j.code) };
    });
  };
  /** Eén keer kijken of de koppelcode bevestigd is: token of null. */
  Plex.checkPin = function (pin) {
    return B.http('https://plex.tv/api/v2/pins/' + pin.id, { headers: plexHeaders(), timeout: 15000 }).then(function (r) {
      var t = r.json && r.json.authToken;
      return t ? String(t) : null;
    }, function () { return null; });
  };
  /** Eerste bereikbare Plex Media Server van dit account (lokaal eerst, relay als laatste). */
  Plex.discoverServer = function (userToken) {
    var h = B.assign(plexHeaders(), { 'X-Plex-Token': userToken });
    return B.http('https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1', { headers: h, timeout: 20000 }).then(function (r) {
      var all = Array.isArray(r.json) ? r.json : [];
      var servers = all.filter(function (s) { return String(s.provides || '').indexOf('server') >= 0; });
      var cands = [];
      servers.forEach(function (s) {
        (s.connections || []).slice().sort(function (a, b) { return rank(a) - rank(b); }).forEach(function (c) {
          if (c && c.uri) cands.push({ uri: String(c.uri), token: s.accessToken || userToken, name: s.name || null });
        });
      });
      function rank(c) { return c.local ? 0 : c.relay ? 2 : 1; }
      var i = 0;
      function next() {
        if (i >= cands.length) throw fail(L('No reachable server found on this account.'));
        var c = cands[i++];
        return B.http(c.uri + '/identity', { timeout: 4000, headers: { 'Accept': 'application/json' } }).then(function (x) {
          if (x.status === 200) return { token: c.token, userID: c.name, serverURI: c.uri };
          return next();
        }, function () { return next(); });
      }
      return next();
    });
  };
  Plex.prototype.url = function (path, params) {
    var p = B.assign({}, params || {});
    p['X-Plex-Token'] = this.s.token;
    return q(this.s.serverURI + path, p);
  };
  Plex.prototype.get = function (path, params) {
    return getJSON(this.url(path, params), plexHeaders(), 90000).then(function (j) { return j.MediaContainer || {}; });
  };
  Plex.prototype.image = function (path) { return path ? this.url(path) : null; };
  Plex.prototype.stream = function (m) {
    var media = list(m, 'Media')[0], part = media && list(media, 'Part')[0];
    return part && part.key ? this.url(part.key) : null;
  };
  function plexImdb(m) {
    var g = list(m, 'Guid');
    for (var i = 0; i < g.length; i++) { var id = String(g[i].id || ''); if (id.indexOf('imdb://') === 0) return id.slice(7); }
    return null;
  }
  Plex.prototype.library = function () {
    var self = this;
    var movies = [], series = [];
    return this.get('/library/sections').then(function (mc) {
      var dirs = list(mc, 'Directory').filter(function (d) { return d.type === 'movie' || d.type === 'show'; });
      var chain = Promise.resolve();
      dirs.forEach(function (dir) {
        chain = chain.then(function () {
          return self.get('/library/sections/' + dir.key + '/all', { includeGuids: '1' }).then(function (c) {
            list(c, 'Metadata').forEach(function (m) {
              if (m.ratingKey == null) return;
              var rk = String(m.ratingKey);
              var g0 = list(m, 'Genre')[0];
              var group = (g0 && g0.tag) || dir.title || 'Plex';
              var rating = num(m.audienceRating) != null ? num(m.audienceRating) : num(m.rating);
              if (dir.type === 'movie') {
                movies.push({ id: 'px' + rk, title: m.title || '?', year: B.int(m.year), poster: self.image(m.thumb), group: group,
                  streamURL: self.stream(m), imdbID: plexImdb(m), rating: rating, plot: m.summary || null,
                  addedAt: m.addedAt ? m.addedAt * 1000 : null, rated: m.contentRating || null });
              } else {
                series.push({ id: 'pxs' + rk, title: m.title || '?', year: B.int(m.year), poster: self.image(m.thumb), group: group,
                  imdbID: plexImdb(m), plot: m.summary || null, rating: rating, remoteID: rk, rated: m.contentRating || null, episodes: [] });
              }
            });
          });
        });
      });
      return chain;
    }).then(function () {
      movies.sort(function (a, b) { return (b.addedAt || 0) - (a.addedAt || 0); });
      return { channels: [], movies: movies, series: series, epg: {} };
    });
  };
  Plex.prototype.episodes = function (series) {
    var self = this;
    if (!series.remoteID) return Promise.resolve([]);
    return this.get('/library/metadata/' + series.remoteID + '/allLeaves').then(function (mc) {
      return list(mc, 'Metadata').filter(function (e) { return e.ratingKey != null; }).map(function (e) {
        return { id: 'pxe' + e.ratingKey, seriesID: series.id, season: B.int(e.parentIndex) || 1, number: B.int(e.index) || 0,
          title: e.title || '', streamURL: self.stream(e), still: self.image(e.thumb), plot: e.summary || null,
          durationSeconds: e.duration ? Math.floor(e.duration / 1000) : null };
      });
    });
  };

  // MARK: Stremio (add-ons: catalogi en directe http(s)-streams)

  var CINEMETA = 'https://v3-cinemeta.strem.io/manifest.json';
  var Stremio = M.Stremio = function (addons) { this.addons = addons || []; };
  Stremio.login = function (email, password) {
    return postJSON('https://api.strem.io/api/login', { type: 'Login', email: email, password: password, facebook: false }).then(function (r) {
      var k = r.json && r.json.result && r.json.result.authKey;
      if (!k) throw fail(L("Couldn't sign in to the server. Check the server, username and password."));
      return String(k);
    });
  };
  Stremio.accountAddons = function (authKey) {
    return postJSON('https://api.strem.io/api/addonCollectionGet', { type: 'AddonCollectionGet', authKey: authKey, update: true }).then(function (r) {
      var res = r.json && r.json.result;
      return list(res, 'addons').map(function (a) { return a && a.transportUrl; }).filter(Boolean);
    });
  };
  Stremio.loadAddon = function (manifestURL) {
    var m = String(manifestURL || '').trim();
    if (m.indexOf('stremio://') === 0) m = 'https://' + m.slice(10);
    if (!/manifest\.json/.test(m)) m = m.replace(/\/+$/, '') + '/manifest.json';
    return getJSON(m, null, 20000).then(function (o) {
      var resources = list(o, 'resources').map(function (r) { return typeof r === 'string' ? r : r && r.name; }).filter(Boolean);
      // build 150: ook de zoekcatalogi (extra "search", eventueel verplicht) apart, voor zoeken via de add-ons
      var catalogs = [], searchCatalogs = [];
      list(o, 'catalogs').forEach(function (c) {
        if (!c || !c.type || !c.id) return;
        var extra = list(c, 'extra');
        var req = extra.filter(function (e) { return e && e.isRequired === true; }).map(function (e) { return e.name; }).concat(list(c, 'extraRequired'));
        var canSearch = extra.some(function (e) { return e && e.name === 'search'; }) || list(c, 'extraSupported').indexOf('search') >= 0;
        var entry = { type: c.type, id: c.id, name: c.name || c.id };
        if (canSearch && req.every(function (n) { return n === 'search'; })) searchCatalogs.push(entry);
        if (!req.length) catalogs.push(entry);
      });
      return { base: m.replace('/manifest.json', ''), name: o.name || 'Addon', resources: resources,
        types: list(o, 'types').map(String), catalogs: catalogs, searchCatalogs: searchCatalogs };
    }, function () { return null; });
  };
  /** Add-ons van een Stremio-bron: account-add-ons (authKey in secrets.password) + eigen URL's (m3uURL), altijd Cinemeta. */
  Stremio.addonsFor = function (src) {
    var urls = String(src.m3uURL || '').split(/[\n, ]+/).map(function (s) { return s.trim(); }).filter(Boolean);
    var key = src.secrets && src.secrets.password;
    var acc = key ? Stremio.accountAddons(key).catch(function () { return []; }) : Promise.resolve([]);
    return acc.then(function (a) {
      urls = a.concat(urls);
      if (!urls.some(function (u) { return u.indexOf('cinemeta') >= 0; })) urls.unshift(CINEMETA);
      var seen = {};
      urls = urls.filter(function (u) { if (seen[u]) return false; seen[u] = true; return true; });
      return Promise.all(urls.map(Stremio.loadAddon));
    }).then(function (r) { return r.filter(Boolean); });
  };
  Stremio.prototype.library = function () {
    var jobs = [];
    this.addons.forEach(function (a) {
      if (a.resources.indexOf('catalog') < 0) return;
      a.catalogs.slice(0, 10).filter(function (c) { return c.type === 'movie' || c.type === 'series'; }).forEach(function (c) {
        jobs.push(getJSON(a.base + '/catalog/' + c.type + '/' + c.id + '.json', null, 30000).then(function (j) { return { cat: c, metas: list(j, 'metas') }; },
          function () { return { cat: c, metas: [] }; }));
      });
    });
    var moviesL = L('Movies'), seriesL = L('Series');
    return Promise.all(jobs).then(function (res) {
      var movies = [], series = [];
      res.forEach(function (r) {
        var group = r.cat.name + ' · ' + (r.cat.type === 'movie' ? moviesL : seriesL);
        r.metas.forEach(function (m) {
          var id = m && m.id ? String(m.id) : '';
          if (id.indexOf('tt') !== 0) return;
          var year = m.releaseInfo ? B.int(String(m.releaseInfo).slice(0, 4)) : null;
          if (r.cat.type === 'movie') {
            movies.push({ id: 'sm' + id + '-' + r.cat.id, title: m.name || '?', year: year, poster: urlValue(m.poster), group: group,
              imdbID: id, rating: num(m.imdbRating), plot: m.description || null, stremio: 'movie|' + id });
          } else {
            series.push({ id: 'ss' + id + '-' + r.cat.id, title: m.name || '?', year: year, poster: urlValue(m.poster), group: group,
              imdbID: id, plot: m.description || null, rating: num(m.imdbRating), remoteID: id, episodes: [] });
          }
        });
      });
      return { channels: [], movies: movies, series: series, epg: {} };
    });
  };
  /**
   * Build 150: zoeken via de zoekcatalogi van de add-ons (= StremioClient.search op Android): hooguit 6 catalogi, elk 6 s.
   * Zelfde items als library() (alleen IMDb-id's), per soort één keer.
   */
  Stremio.prototype.search = function (q) {
    var enc = encodeURIComponent(String(q || '').trim()), jobs = [];
    if (!enc) return Promise.resolve({ movies: [], series: [] });
    this.addons.forEach(function (a) {
      if (a.resources.indexOf('catalog') < 0) return;
      (a.searchCatalogs || []).filter(function (c) { return c.type === 'movie' || c.type === 'series'; }).forEach(function (c) { jobs.push({ a: a, c: c }); });
    });
    var moviesL = L('Movies'), seriesL = L('Series');
    return Promise.all(jobs.slice(0, 6).map(function (j) {
      return getJSON(j.a.base + '/catalog/' + j.c.type + '/' + j.c.id + '/search=' + enc + '.json', null, 6000)
        .then(function (r) { return { cat: j.c, metas: list(r, 'metas') }; }, function () { return { cat: j.c, metas: [] }; });
    })).then(function (res) {
      var movies = [], series = [], seen = {};
      res.forEach(function (r) {
        var group = r.cat.name + ' · ' + (r.cat.type === 'movie' ? moviesL : seriesL);
        r.metas.forEach(function (m) {
          var id = m && m.id ? String(m.id) : '';
          if (id.indexOf('tt') !== 0 || seen[r.cat.type + id]) return;
          seen[r.cat.type + id] = true;
          var year = m.releaseInfo ? B.int(String(m.releaseInfo).slice(0, 4)) : null;
          if (r.cat.type === 'movie') {
            movies.push({ id: 'sm' + id + '-' + r.cat.id, title: m.name || '?', year: year, poster: urlValue(m.poster), group: group,
              imdbID: id, rating: num(m.imdbRating), plot: m.description || null, stremio: 'movie|' + id });
          } else {
            series.push({ id: 'ss' + id + '-' + r.cat.id, title: m.name || '?', year: year, poster: urlValue(m.poster), group: group,
              imdbID: id, plot: m.description || null, rating: num(m.imdbRating), remoteID: id, episodes: [] });
          }
        });
      });
      return { movies: movies, series: series };
    });
  };
  Stremio.prototype.episodes = function (series) {
    if (!series.remoteID) return Promise.resolve([]);
    var meta = null;
    for (var i = 0; i < this.addons.length; i++) {
      var a = this.addons[i];
      if (a.resources.indexOf('meta') >= 0 && a.types.indexOf('series') >= 0) { meta = a; break; }
    }
    var base = meta ? meta.base : CINEMETA.replace('/manifest.json', '');
    return getJSON(base + '/meta/series/' + series.remoteID + '.json', null, 30000).then(function (j) {
      var vids = list(j && j.meta, 'videos').filter(function (v) { return (B.int(v.season) || 0) > 0 && v.id; });
      return vids.map(function (v) {
        return { id: 'se' + v.id, seriesID: series.id, season: B.int(v.season) || 1, number: B.int(v.episode) || B.int(v.number) || 0,
          title: v.name || v.title || '', streamURL: null, still: urlValue(v.thumbnail), plot: v.overview || null, stremio: 'series|' + v.id };
      }).sort(function (a, b) { return a.season - b.season || a.number - b.number; });
    });
  };
  /** Alle directe streams voor "movie|tt…" of "series|tt…:s:e". */
  Stremio.prototype.streams = function (ref) {
    var parts = String(ref || '').split('|');
    if (parts.length !== 2) return Promise.resolve([]);
    return Promise.all(this.addons.filter(function (a) { return a.resources.indexOf('stream') >= 0; }).map(function (a) {
      return getJSON(a.base + '/stream/' + parts[0] + '/' + parts[1] + '.json', null, 30000).then(function (j) {
        return list(j, 'streams').filter(function (s) { return s && s.url && /^https?:/i.test(String(s.url)); }).map(function (s) {
          var t = [s.name, s.title || s.description].filter(Boolean).join(' · ');
          return { title: t || a.name, addon: a.name, url: String(s.url) };
        });
      }, function () { return []; });
    })).then(function (all) { return [].concat.apply([], all); });
  };

  // MARK: per bron

  /** Bibliotheek van een Plex/Jellyfin/Emby/Stremio-bron. ctx: {onSession(session)} als Plex een nieuw adres vond. */
  M.library = function (src, ctx) {
    var kind = src.kind;
    if (kind === 'Jellyfin' || kind === 'Emby') return new Jellyfin(src, M.session(src)).library();
    if (kind === 'Plex') {
      var s = M.session(src);
      if (!s) return Promise.reject(M.notSignedIn());
      return new Plex(s).library().catch(function (e) {
        // adres van de server kan veranderen: met het gebruikerstoken opnieuw zoeken
        var user = src.secrets && src.secrets.password;
        if (!user) throw e;
        return Plex.discoverServer(user).then(function (ns) {
          if (ctx && ctx.onSession) ctx.onSession(ns);
          return new Plex(ns).library();
        });
      });
    }
    if (kind === 'Stremio') {
      return Stremio.addonsFor(src).then(function (addons) {
        M.stremioAddons[src.id] = addons;
        return new Stremio(addons).library();
      });
    }
    return Promise.reject(fail(L(B.platform.texts.unsupportedServer)));
  };
  M.stremioAddons = {};
  M.stremioFor = function (src) {
    var a = M.stremioAddons[src.id];
    if (a && a.length) return Promise.resolve(new Stremio(a));
    return Stremio.addonsFor(src).then(function (addons) { M.stremioAddons[src.id] = addons; return new Stremio(addons); });
  };
  M.episodes = function (src, series) {
    var kind = src.kind;
    if (kind === 'Jellyfin' || kind === 'Emby') return new Jellyfin(src, M.session(src)).episodes(series);
    if (kind === 'Plex') { var s = M.session(src); return s ? new Plex(s).episodes(series) : Promise.resolve([]); }
    if (kind === 'Stremio') return M.stremioFor(src).then(function (st) { return st.episodes(series); });
    return Promise.resolve([]);
  };
  M.isMediaServer = function (kind) { return kind === 'Plex' || kind === 'Jellyfin' || kind === 'Emby' || kind === 'Stremio'; };
})();
