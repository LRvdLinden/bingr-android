/*
 * Bronnen lezen: M3U/M3U8, Xtream Codes en Stalker (MAC-portal), plus de XMLTV-gids.
 * Zelfde id's als Android en iOS (x123, xv456, xs789, xe…, st…, "<8 tekens bron-id>-<hash>"),
 * zodat voortgang, lijsten en collecties tussen de apps gelijk blijven.
 */
(function () {
  'use strict';
  var B = window.B;
  var L = function () { return B.L.apply(null, arguments); };
  var P = B.providers = {};

  function urlValue(s) { s = s == null ? '' : String(s).trim(); return s ? s : null; }
  P.urlValue = urlValue;

  // MARK: M3U (= M3UParser.kt)

  var attrRe = /([\w-]+)="([^"]*)"/g;
  function lastTopLevelComma(s) {
    var q = false;
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      if (c === '"') q = !q; else if (c === ',' && !q) return i;
    }
    return -1;
  }
  P.m3uEntries = function (text) {
    var out = [];
    var pendingName = null, pendingAttrs = {}, groupOverride = null;
    var lines = text.split(/\r?\n/);
    for (var li = 0; li < lines.length; li++) {
      var line = lines[li].trim();
      if (!line) continue;
      if (line.indexOf('#EXTINF') === 0) {
        var ci = lastTopLevelComma(line);
        var header = ci >= 0 ? line.substring(0, ci) : line;
        var name = ci >= 0 ? line.substring(ci + 1).trim() : '';
        var attrs = {}, m;
        attrRe.lastIndex = 0;
        while ((m = attrRe.exec(header)) !== null) attrs[m[1].toLowerCase()] = m[2];
        pendingName = name || attrs['tvg-name'] || '?';
        pendingAttrs = attrs;
      } else if (line.indexOf('#EXTGRP:') === 0) {
        groupOverride = line.substring(8);
      } else if (line.charAt(0) === '#') {
        // overslaan
      } else if (pendingName != null) {
        if (pendingAttrs['group-title'] == null && groupOverride != null) pendingAttrs['group-title'] = groupOverride;
        out.push({ name: pendingName, attrs: pendingAttrs, url: line });
        pendingName = null; groupOverride = null;
      }
    }
    return out;
  };

  /** build 97: opts.channelsOnly → alleen de zenders (voor de reservebron), zonder films en series op te bouwen. */
  P.m3uLibrary = function (text, playlistID, opts) {
    var only = !!(opts && opts.channelsOnly);
    var channels = [], movies = [], seriesMap = {}, seriesEps = {}, seriesOrder = [];
    var number = 0;
    var other = L('Other');
    var prefix = String(playlistID).slice(0, 8);
    var entries = P.m3uEntries(text);
    for (var i = 0; i < entries.length; i++) {
      var e = entries[i];
      var group = e.attrs['group-title'] || other;
      var logo = urlValue(e.attrs['tvg-logo']);
      var lower = e.url.toLowerCase();
      var parsed = B.parseTitle(e.name);
      var id = prefix + '-' + B.stableHash(e.url);
      var looksSeries = lower.indexOf('/series/') >= 0 || (parsed.season != null && parsed.episode != null);
      var looksMovie = !looksSeries && (lower.indexOf('/movie/') >= 0 || /\.(mkv|mp4|avi)$/.test(lower));
      if (only && (looksSeries || looksMovie)) continue;
      if (looksSeries) {
        var sKey = parsed.title.toLowerCase();
        if (!seriesMap[sKey]) {
          seriesMap[sKey] = { id: 's-' + B.stableHash(group + sKey), title: parsed.title, year: parsed.year, poster: logo, group: group, episodes: [] };
          var scl = B.clang.parse(e.name);
          if (scl) seriesMap[sKey].cl = scl;
          seriesEps[sKey] = [];
          seriesOrder.push(sKey);
        }
        var eps = seriesEps[sKey];
        eps.push({
          id: id, seriesID: seriesMap[sKey].id, season: parsed.season || 1,
          number: parsed.episode || (eps.length + 1), title: e.name, streamURL: urlValue(e.url), still: logo
        });
      } else if (looksMovie) {
        var mo = { id: id, title: parsed.title, year: parsed.year, poster: logo, group: group, streamURL: urlValue(e.url) };
        // taal-tag uit de naam bewaren voordat het opschonen hem weghaalt ("Animal (IT)")
        var mcl = B.clang.parse(e.name);
        if (mcl) mo.cl = mcl;
        movies.push(mo);
      } else {
        number++;
        var catchup = parseInt(e.attrs['catchup-days'] || e.attrs.timeshift || '', 10) || 0;
        channels.push({
          id: id, name: e.name, logo: logo, group: group, streamURL: urlValue(e.url),
          epgID: e.attrs['tvg-id'] || null, number: parseInt(e.attrs['tvg-chno'] || '', 10) || number, catchupDays: catchup
        });
      }
    }
    var series = seriesOrder.map(function (k) {
      var s = seriesMap[k];
      s.episodes = seriesEps[k].sort(function (a, b) { return a.season - b.season || a.number - b.number; });
      return s;
    });
    return { channels: channels, movies: movies, series: series, epg: {} };
  };

  P.m3uEpgURL = function (text) {
    var first = (text.split(/\r?\n/)[0] || '').replace(/^﻿/, '').trim();
    if (first.indexOf('#EXTM3U') !== 0) return null;
    var keys = ['url-tvg', 'x-tvg-url', 'tvg-url'];
    for (var i = 0; i < keys.length; i++) {
      var start = first.indexOf(keys[i] + '="');
      if (start >= 0) {
        var rest = first.substring(start + keys[i].length + 2);
        var end = rest.indexOf('"');
        if (end >= 0) return urlValue(rest.substring(0, end).split(',')[0]);
      }
    }
    return null;
  };

  // MARK: Xtream Codes (= XtreamClient.kt)

  function Xtream(source, password) {
    this.server = B.normalizedServer(source.serverURL);
    this.username = source.username || '';
    this.password = password || '';
    this.preferHLS = !!source.preferHLS;
  }
  Xtream.prototype.api = function (action, extra) {
    var q = [['username', this.username], ['password', this.password]];
    if (action) q.push(['action', action]);
    if (extra) q = q.concat(extra);
    return B.query(this.server + '/player_api.php', q);
  };
  Xtream.prototype.authenticate = function () {
    var self = this;
    return B.getJSON(this.api(), null, 30000).then(function (r) {
      if (!r || !r.user_info || B.int(r.user_info.auth) !== 1) throw new Error(L("Couldn't sign in to the server. Check the server, username and password."));
      var c = providerConnections(r.user_info);
      if (c) P.providerLimit.store(self.server, self.username, c);
      return r;
    });
  };
  /**
   * Verbindingslimiet van de provider (user_info.max_connections / active_cons), of null als de server die niet
   * meestuurt. Korte time-out: alleen om bij een mislukte stream de juiste foutmelding te kiezen.
   */
  Xtream.prototype.connections = function () {
    var self = this;
    return B.getJSON(this.api(), null, 6000).then(function (r) {
      var c = providerConnections(r && r.user_info);
      if (c) P.providerLimit.store(self.server, self.username, c);
      return c;
    }, function () { return null; });
  };

  /*
   * Limiet van de IPTV-provider (Xtream). Staat los van de streamlimiet van Bingr (eigen telling op de server);
   * die wordt hier nooit uit afgeleid. Alleen om bij een mislukte stream te zeggen dat de provider vol zit, in plaats
   * van een algemene fout. Niet vooraf blokkeren: active_cons loopt bij providers achter.
   */
  function providerConnections(u) {
    if (!u) return null;
    var max = B.int(u.max_connections);
    if (max == null) return null;
    return { max: max, active: B.int(u.active_cons) || 0 };
  }
  function providerAtLimit(c) { return !!c && c.max > 0 && c.active >= c.max; }
  function providerMessage(c) {
    return c.max === 1
      ? L('Your IPTV subscription allows 1 connection at a time and it is in use. This is a limit of your provider, not of Bingr.')
      : L('Your IPTV subscription allows up to %d connections at the same time and they are all in use. This is a limit of your provider, not of Bingr.', c.max);
  }
  P.providerLimit = {
    cache: {},
    store: function (server, username, c) { this.cache[server + '|' + username] = { c: c, at: Date.now() }; },
    client: function (src) {
      return src && src.kind === 'Xtream Codes' ? new Xtream(src, B.data.secret(src, 'password')) : null;
    },
    /** Vóór het afspelen: de limiet van de provider alvast ophalen (alleen Xtream; blokkeert niets). */
    prime: function (src) {
      var x = this.client(src);
      if (!x) return;
      var e = this.cache[x.server + '|' + x.username];
      if (e && Date.now() - e.at < 60000) return;
      x.connections().then(B.noop, B.noop);
    },
    /** Na een mislukte stream: melding als alle verbindingen van de provider in gebruik zijn, anders null (M3U: null). */
    failureMessage: function (src) {
      var self = this, x = this.client(src);
      if (!x) return Promise.resolve(null);
      return x.connections().then(function (c) {
        var e = self.cache[x.server + '|' + x.username];
        c = c || (e && e.c);
        // met de tip eronder: een net gesloten verbinding telt bij de provider vaak nog even mee
        return providerAtLimit(c)
          ? providerMessage(c) + '\n\n' + L('Stop the stream on your other device, or wait a moment: your provider sometimes only releases a connection you just closed after a minute.')
          : null;
      }, function () { return null; });
    }
  };
  function items(j) {
    if (Array.isArray(j)) return j;
    if (j && typeof j === 'object') return Object.keys(j).map(function (k) { return j[k]; });
    return [];
  }
  Xtream.prototype.list = function (action, keys) {
    // build 147 (diagnose): een mislukte lijst blijft leeg (raakt de andere lijsten niet), met een korte foutcode
    // build 148: met [keys] een grote lijst in een Worker inlezen, met alleen die velden (B.getJSONList)
    var self = this;
    self.errors = self.errors || {};
    var get = keys ? B.getJSONList(this.api(action), keys, 120000) : B.getJSON(this.api(action), null, 120000);
    return get.then(function (r) { delete self.errors[action]; return items(r); }, function (e) {
      var X7 = B.b147 && B.b147.fetch;
      self.errors[action] = X7 ? X7.errorCode(e && e.name, e && e.message) : 'error';
      return [];
    });
  };
  /** Build 147: foutcode per soort (live, movie, series) van de laatste library(). */
  Xtream.prototype.kindErrors = function () {
    var e = this.errors || {}, out = {};
    if (e.get_live_streams) out.live = e.get_live_streams;
    if (e.get_vod_streams) out.movie = e.get_vod_streams;
    if (e.get_series) out.series = e.get_series;
    return out;
  };
  function catMap(cats, other) {
    var m = {};
    cats.forEach(function (c) { var id = B.str(c && c.category_id); if (id != null && !B.has(m, id)) m[id] = c.category_name || other; });
    return m;
  }
  // build 148: alleen deze velden blijven per item over (lichte objecten, js/jsonworker.js)
  var KEYS_LIVE = ['stream_id', 'name', 'stream_icon', 'category_id', 'epg_channel_id', 'num', 'tv_archive', 'tv_archive_duration'];
  var KEYS_VOD = ['stream_id', 'name', 'stream_icon', 'category_id', 'container_extension', 'rating', 'added', 'year'];
  var KEYS_SERIES = ['series_id', 'name', 'year', 'releaseDate', 'cover', 'category_id', 'plot', 'rating', 'last_modified'];
  Xtream.KEYS = { live: KEYS_LIVE, vod: KEYS_VOD, series: KEYS_SERIES };
  Xtream.prototype.movieOf = function (v, vc, other) {
    var sid = B.str(v && v.stream_id); if (sid == null) return null;
    var pt = B.parseTitle(v.name || '');
    var added = B.num(v.added);
    var mv = {
      id: 'xv' + sid, title: pt.title, year: B.int(v.year) || pt.year, poster: urlValue(v.stream_icon),
      group: vc[B.str(v.category_id) || ''] || other,
      streamURL: this.server + '/movie/' + this.username + '/' + this.password + '/' + sid + '.' + (v.container_extension || 'mp4'),
      rating: B.num(v.rating), addedAt: added ? added * 1000 : null, xtreamVodID: B.int(sid)
    };
    var vcl = B.clang.parse(v.name);
    if (vcl) mv.cl = vcl;
    return mv;
  };
  Xtream.prototype.seriesOf = function (s, sc, other) {
    var sid = B.int(s && s.series_id); if (sid == null) return null;
    var pt = B.parseTitle(s.name || '');
    var y = B.int(s.year) || (s.releaseDate ? B.int(String(s.releaseDate).slice(0, 4)) : null) || pt.year;
    var lm = B.num(s.last_modified);   // build 111: laatst bijgewerkt, voor "Nieuw toegevoegd"
    var se = {
      id: 'xs' + sid, title: pt.title, year: y, poster: urlValue(s.cover), group: sc[B.str(s.category_id) || ''] || other,
      plot: s.plot || null, rating: B.num(s.rating), xtreamSeriesID: sid, episodes: [], addedAt: lm > 0 ? lm * 1000 : null
    };
    var scl = B.clang.parse(s.name);
    if (scl) se.cl = scl;
    return se;
  };
  Xtream.prototype.channelOf = function (l, lc, other) {
    var sid = B.str(l && l.stream_id); if (sid == null) return null;
    return {
      id: 'x' + sid, name: l.name || 'Channel', logo: urlValue(l.stream_icon), group: lc[B.str(l.category_id) || ''] || other,
      streamURL: this.server + '/live/' + this.username + '/' + this.password + '/' + sid + '.' + (this.preferHLS ? 'm3u8' : 'ts'),
      epgID: l.epg_channel_id || null,
      number: B.int(l.num), catchupDays: B.int(l.tv_archive) === 1 ? (B.int(l.tv_archive_duration) || 0) : 0
    };
  };
  /**
   * Live, films en series. Build 148: elke lijst wordt ingelezen zodra hij binnen is (in een Worker, lichte objecten) en in
   * stukjes van ~12 ms omgezet (B.chunked); de ruwe lijst gaat daarna weg. hooks.fetched() = alle lijsten zijn binnen.
   */
  Xtream.prototype.library = function (hooks) {
    var self = this;
    hooks = hooks || {};
    var other = L('Other');
    var cats = Promise.all(['get_live_categories', 'get_vod_categories', 'get_series_categories'].map(function (a) { return self.list(a); }))
      .then(function (r) { return [catMap(r[0], other), catMap(r[1], other), catMap(r[2], other)]; });
    var arrived = 0;
    function got(l) { if (++arrived === 3 && hooks.fetched) { try { hooks.fetched(); } catch (e) { /* stil */ } } return l; }
    function kind(action, keys, ci, fn) {
      return Promise.all([self.list(action, keys).then(got), cats]).then(function (r) {
        var list = r[0], map = r[1][ci], out = [];
        return B.chunked(list, function (x) { var o = fn.call(self, x, map, other); if (o) out.push(o); }).then(function () {
          list.length = 0;
          return out;
        });
      });
    }
    return Promise.all([
      kind('get_live_streams', KEYS_LIVE, 0, self.channelOf),
      kind('get_vod_streams', KEYS_VOD, 1, self.movieOf),
      kind('get_series', KEYS_SERIES, 2, self.seriesOf)
    ]).then(function (r) { return { channels: r[0], movies: r[1], series: r[2], epg: {} }; });
  };
  /** Live-zenders uit get_live_streams (build 97: ook los gebruikt voor de reservebron). */
  Xtream.prototype.mapLive = function (list, lc, other) {
    var self = this, channels = [];
    (list || []).forEach(function (l) { var c = self.channelOf(l, lc, other); if (c) channels.push(c); });
    return channels;
  };
  /** build 97: alleen get_live_streams (geen films, series of categorieën), voor de reservebron. */
  Xtream.prototype.liveChannels = function () {
    var self = this;
    return this.list('get_live_streams').then(function (list) { return self.mapLive(list, {}, L('Other')); });
  };
  /** Afleveringen per seizoen: {"1": [...]} of een lijst met lijsten (zoals episodeGroups op Android). */
  function episodeGroups(info) {
    var eps = info && info.episodes;
    if (!eps) return [];
    if (Array.isArray(eps)) {
      return eps.map(function (v, i) {
        if (Array.isArray(v)) return [String(i + 1), v];
        return [String((v && v.season) || i + 1), [v]];
      });
    }
    return Object.keys(eps).map(function (k) {
      var v = eps[k];
      if (Array.isArray(v)) return [k, v];
      if (v && typeof v === 'object') {
        return [k, Object.keys(v).sort(function (a, b) { return (parseInt(a, 10) || 0) - (parseInt(b, 10) || 0); }).map(function (x) { return v[x]; })];
      }
      return [k, []];
    });
  }
  Xtream.prototype.seriesInfo = function (series) {
    if (series.xtreamSeriesID == null) return Promise.resolve(null);
    return B.getJSON(this.api('get_series_info', [['series_id', String(series.xtreamSeriesID)]]), null, 45000);
  };
  Xtream.prototype.episodes = function (series) {
    var self = this;
    return this.seriesInfo(series).then(function (info) {
      if (!info) return { episodes: [], info: null };
      var out = [], seen = {};
      episodeGroups(info).forEach(function (g) {
        g[1].forEach(function (e) {
          if (!e) return;
          var eid = B.str(e.id != null ? e.id : e.stream_id);
          if (eid == null || seen[eid]) return;
          seen[eid] = true;
          var i = e.info || {};
          out.push({
            id: 'xe' + eid, seriesID: series.id, season: B.int(e.season) || B.int(g[0]) || 1, number: B.int(e.episode_num) || 0,
            title: e.title || e.name || L('Episode'),
            streamURL: self.server + '/series/' + self.username + '/' + self.password + '/' + eid + '.' + (e.container_extension || 'mp4'),
            still: urlValue(i.movie_image), plot: i.plot || null, durationSeconds: B.int(i.duration_secs)
          });
        });
      });
      out.sort(function (a, b) { return a.season - b.season || a.number - b.number; });
      return { episodes: out, info: info.info || null };
    });
  };
  Xtream.prototype.vodInfo = function (vodID) {
    return B.getJSON(this.api('get_vod_info', [['vod_id', String(vodID)]]), null, 30000).then(function (j) { return (j && j.info) || null; });
  };
  /** Terugkijken (tv_archive): /timeshift/<user>/<pw>/<minuten>/<jjjj-mm-dd:uu-mm lokale tijd>/<id>.ts (= XtreamClient.catchupURL). */
  Xtream.prototype.catchupURL = function (ch, start, end) {
    if (!/^x\d/.test(String(ch.id))) return null;
    var sid = String(ch.id).slice(1);
    var d = new Date(start);
    var stamp = d.getFullYear() + '-' + B.pad2(d.getMonth() + 1) + '-' + B.pad2(d.getDate()) + ':' + B.pad2(d.getHours()) + '-' + B.pad2(d.getMinutes());
    var minutes = Math.max(1, Math.round((end - start) / 60000));
    return this.server + '/timeshift/' + this.username + '/' + this.password + '/' + minutes + '/' + stamp + '/' + sid + '.ts';
  };
  Xtream.prototype.xmltvURL = function () {
    return B.query(this.server + '/xmltv.php', [['username', this.username], ['password', this.password]]);
  };
  Xtream.prototype.shortEPG = function (streamID) {
    return B.getJSON(this.api('get_short_epg', [['stream_id', String(streamID)], ['limit', '4']]), null, 15000).then(function (j) {
      var list = (j && j.epg_listings) || [];
      return list.map(function (p) {
        var s = B.num(p.start_timestamp), e = B.num(p.stop_timestamp);
        if (!s || !e) return null;
        return { title: decodeB64(p.title), description: decodeB64(p.description), start: s * 1000, end: e * 1000 };
      }).filter(Boolean);
    });
  };
  function decodeB64(s) {
    if (!s) return '';
    try { return decodeURIComponent(escape(atob(s))); } catch (e) { return String(s); }
  }
  P.Xtream = Xtream;

  // MARK: Stalker / MAC-portal (= StalkerClient.kt; op de tv kan de browser geen Cookie-header zetten,
  // daarom gaat het MAC-adres ook als parameter mee — niet elk portal accepteert dat)

  function Stalker(source) {
    var p = B.normalizedServer(source.serverURL);
    if (/\/c$/.test(p)) p = p.slice(0, -2);
    this.portal = p;
    this.mac = String(source.macAddress || '').toUpperCase();
    this.endpoint = null;
    this.token = null;
  }
  Stalker.prototype.headers = function () {
    var h = {
      'X-User-Agent': 'Model: MAG250; Link: WiFi',
      'Cookie': 'mac=' + this.mac + '; stb_lang=en; timezone=Europe/Amsterdam'
    };
    if (this.token) h.Authorization = 'Bearer ' + this.token;
    return h;
  };
  Stalker.prototype.url = function (q, base) {
    var b = base || this.endpoint;
    return B.query(b, q.concat([['JsHttpRequest', '1-xml'], ['mac', this.mac]]));
  };
  Stalker.prototype.js = function (u) {
    return B.getJSON(u, this.headers(), 45000).then(function (j) { return j ? j.js : null; });
  };
  Stalker.prototype.connect = function () {
    var self = this;
    var cands = [this.portal + '/portal.php', this.portal + '/server/load.php', this.portal + '/stalker_portal/server/load.php', this.portal + '/c/portal.php'];
    var i = 0;
    function next() {
      if (i >= cands.length) throw new Error(L("Couldn't connect to the portal. Check the portal URL and MAC address."));
      var base = cands[i++];
      self.token = null;
      return self.js(self.url([['type', 'stb'], ['action', 'handshake'], ['token', '']], base)).then(function (js) {
        var t = js && js.token;
        if (!t) return next();
        self.endpoint = base; self.token = t;
        return self.js(self.url([['type', 'stb'], ['action', 'get_profile'], ['hd', '1']])).catch(B.noop);
      }, function () { return next(); });
    }
    return next();
  };
  function directURL(cmd) {
    if (!cmd) return null;
    var c = String(cmd);
    ['ffmpeg ', 'ffrt ', 'auto '].forEach(function (p) { if (c.indexOf(p) === 0) c = c.slice(p.length); });
    c = c.trim();
    if (!/^http/i.test(c) || c.indexOf('localhost') >= 0) return null;
    return c;
  }
  /** Zenders met hun genres (build 97: los, zodat de reservebron niet de hele bibliotheek met films hoeft te laden). */
  Stalker.prototype.liveChannels = function () {
    var self = this;
    var other = L('Other');
    return (this.token ? Promise.resolve() : this.connect()).then(function () {
      return Promise.all([
        self.js(self.url([['type', 'itv'], ['action', 'get_genres']])).catch(function () { return []; }),
        self.js(self.url([['type', 'itv'], ['action', 'get_all_channels']])).catch(function () { return null; })
      ]);
    }).then(function (r) {
      var gMap = {};
      items(r[0]).forEach(function (g) { if (g && g.id != null && !B.has(gMap, String(g.id))) gMap[String(g.id)] = g.title || other; });
      var chans = (r[1] && r[1].data) || [];
      return chans.filter(function (c) { return c && c.id != null; }).map(function (c) {
        return {
          id: 'st' + c.id, name: c.name || 'Channel', logo: urlValue(c.logo), group: gMap[String(c.tv_genre_id || '')] || other,
          streamURL: directURL(c.cmd), epgID: c.xmltv_id || null, number: B.int(c.number),
          catchupDays: Math.floor((B.int(c.tv_archive_duration) || 0) / 24), stalkerCmd: c.cmd || null
        };
      });
    });
  };
  Stalker.prototype.library = function () {
    var self = this;
    var other = L('Other');
    var lib = { channels: [], movies: [], series: [], epg: {} };
    return this.liveChannels().then(function (chs) {
      lib.channels = chs;
      return self.js(self.url([['type', 'vod'], ['action', 'get_categories']])).catch(function () { return []; });
    }).then(function (cats) {
      cats = items(cats).filter(function (c) { return c && c.id != null && String(c.id) !== '*'; }).slice(0, 40);
      var chain = Promise.resolve();
      cats.forEach(function (cat) {
        chain = chain.then(function () {
          return self.js(self.url([['type', 'vod'], ['action', 'get_ordered_list'], ['category', String(cat.id)], ['p', '1'], ['sortby', 'added']]))
            .then(function (js) {
              ((js && js.data) || []).forEach(function (v) {
                if (!v || v.id == null) return;
                var pt = B.parseTitle(v.name || '');
                if (B.int(v.is_series) === 1) {
                  lib.series.push({ id: 'sts' + v.id, title: pt.title, year: B.int(v.year) || pt.year, poster: urlValue(v.screenshot_uri),
                    group: cat.title || L('Series'), plot: v.description || null, rating: B.num(v.rating_imdb), episodes: [], cl: B.clang.parse(v.name) || undefined });
                } else {
                  lib.movies.push({ id: 'stv' + v.id, title: pt.title, year: B.int(v.year) || pt.year, poster: urlValue(v.screenshot_uri),
                    group: cat.title || L('Movies'), rating: B.num(v.rating_imdb), plot: v.description || null, stalkerCmd: v.cmd || null, cl: B.clang.parse(v.name) || undefined });
                }
              });
            }, B.noop);
        });
      });
      return chain;
    }).then(function () { return lib; });
  };
  Stalker.prototype.createLink = function (cmd, live) {
    var self = this;
    return (this.token ? Promise.resolve() : this.connect()).then(function () {
      return self.js(self.url([['type', live ? 'itv' : 'vod'], ['action', 'create_link'], ['cmd', cmd], ['forced_storage', 'undefined'], ['disable_ad', '0']]));
    }).then(function (js) { return directURL(js && js.cmd); });
  };
  /**
   * Terugkijken op een MAC-portal: het programma in het archief zoeken (epg get_simple_data_table) en daarvoor
   * een tv_archive-link maken. Lukt dat niet, dan de live-link met ?utc=…&lutc=… (Flussonic-stijl, veel portals).
   */
  Stalker.prototype.archiveLink = function (ch, start, end) {
    var self = this;
    var cid = String(ch.id).replace(/^st/, '');
    var d = new Date(start);
    var day = d.getFullYear() + '-' + B.pad2(d.getMonth() + 1) + '-' + B.pad2(d.getDate());
    function fallback() {
      if (!ch.stalkerCmd) return null;
      return self.createLink(ch.stalkerCmd, true).then(function (u) {
        if (!u) return null;
        return u + (u.indexOf('?') >= 0 ? '&' : '?') + 'utc=' + Math.floor(start / 1000) + '&lutc=' + Math.floor(Date.now() / 1000);
      }, function () { return null; });
    }
    return (this.token ? Promise.resolve() : this.connect()).then(function () {
      var found = null, page = 1;
      function next() {
        return self.js(self.url([['type', 'epg'], ['action', 'get_simple_data_table'], ['ch_id', cid], ['date', day], ['p', String(page)]])).then(function (js) {
          var list = (js && js.data) || [];
          for (var i = 0; i < list.length; i++) {
            var s = B.num(list[i].start_timestamp) * 1000;
            if (Math.abs(s - start) <= 5 * 60000 && (B.int(list[i].mark_archive) === 1 || list[i].mark_archive === true)) { found = list[i]; break; }
          }
          var total = B.int(js && js.total_items) || 0, per = B.int(js && js.max_page_items) || list.length || 1;
          if (!found && list.length && page * per < total && page < 6) { page++; return next(); }
          return found;
        }, function () { return null; });
      }
      return next();
    }).then(function (prog) {
      if (!prog || prog.id == null) return fallback();
      return self.js(self.url([['type', 'tv_archive'], ['action', 'create_link'], ['cmd', 'auto /media/' + prog.id + '.mpg'], ['forced_storage', 'undefined'], ['disable_ad', '0']]))
        .then(function (js) { return directURL(js && js.cmd) || fallback(); }, fallback);
    }, fallback);
  };
  P.Stalker = Stalker;

  // MARK: XMLTV (tekst doorlopen zonder DOM: grote gidsen passen anders niet in het geheugen van de tv)

  function xmlDate(s) {
    if (!s) return null;
    var m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4})?/.exec(String(s).trim());
    if (!m) return null;
    var t = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
    if (m[7]) {
      var sign = m[7].charAt(0) === '-' ? -1 : 1;
      var off = (parseInt(m[7].slice(1, 3), 10) * 60 + parseInt(m[7].slice(3, 5), 10)) * 60000;
      t -= sign * off;
    }
    return t;
  }
  function unxml(s) {
    return String(s || '').replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'")
      .replace(/&#(\d+);/g, function (m, d) { return String.fromCharCode(parseInt(d, 10)); })
      .replace(/&amp;/g, '&').trim();
  }
  var attrRes = {};
  function attr(tag, name) {
    var re = attrRes[name] || (attrRes[name] = new RegExp('\\s' + name + '="([^"]*)"'));
    var m = re.exec(tag);
    return m ? unxml(m[1]) : null;
  }
  function inner(block, tag) {
    var i = block.indexOf('<' + tag);
    if (i < 0) return null;
    var s = block.indexOf('>', i);
    var e = block.indexOf('</' + tag + '>', s);
    if (s < 0 || e < 0) return null;
    return unxml(block.substring(s + 1, e));
  }
  /**
   * Doorlopende XMLTV-lezer: stukken tekst erin (push), complete <programme>-blokken eruit.
   * wanted: {kanaal-id in kleine letters: ondergrens in ms} (leeg = alles, 3 uur terug); bovengrens 36 uur vooruit.
   * Zo hoeft een grote (uitgepakte) gids nooit in z'n geheel als één string in het geheugen.
   */
  function XMLTVReader(wanted) {
    this.now = Date.now();
    this.lower = this.now - 3 * 3600000;
    this.upper = this.now + 36 * 3600000;
    this.want = wanted && Object.keys(wanted).length ? wanted : null;
    this.out = {};
    this.buf = '';
    this.count = 0;
  }
  XMLTVReader.prototype.push = function (text) {
    var buf = this.buf ? this.buf + text : text;
    var pos = 0, keep = -1;
    while (true) {
      var i = buf.indexOf('<programme', pos);
      if (i < 0) break;
      var tagEnd = buf.indexOf('>', i);
      if (tagEnd < 0) { keep = i; break; }
      var selfClosing = buf.charAt(tagEnd - 1) === '/';
      var end = selfClosing ? tagEnd : buf.indexOf('</programme>', tagEnd);
      if (end < 0) { keep = i; break; }
      var tag = buf.substring(i, tagEnd);
      pos = selfClosing ? tagEnd + 1 : end + 12;
      var ch = attr(tag, 'channel');
      if (!ch) continue;
      var key = ch.toLowerCase();
      var low = this.lower;
      if (this.want) {
        if (!B.has(this.want, key)) continue;
        if (typeof this.want[key] === 'number') low = Math.min(low, this.want[key]);
      }
      var s = xmlDate(attr(tag, 'start')), e = xmlDate(attr(tag, 'stop'));
      if (s == null || e == null || e < low || s > this.upper) continue;
      var block = selfClosing ? '' : buf.substring(tagEnd + 1, end);
      (this.out[key] = this.out[key] || []).push({ title: inner(block, 'title') || '', description: inner(block, 'desc') || '', start: s, end: e });
      this.count++;
    }
    // onvolledig laatste blok bewaren; anders alleen een staartje (een '<programme' kan over de grens vallen)
    if (keep >= 0) this.buf = buf.substring(keep);
    else this.buf = buf.length - pos > 12 ? buf.substring(buf.length - 12) : buf.substring(pos);
  };
  XMLTVReader.prototype.finish = function () {
    var out = this.out;
    for (var k in out) if (B.has(out, k)) out[k].sort(function (a, b) { return a.start - b.start; });
    this.buf = '';
    return out;
  };
  P.XMLTVReader = XMLTVReader;

  /** XMLTV-tekst → {kanaal-id: [programma's]} in één keer (tests en kleine gidsen). */
  P.parseXMLTV = function (text, wanted) {
    var r = new XMLTVReader(wanted);
    r.push(text);
    return r.finish();
  };

  /**
   * Gids laden: binair ophalen; gzip (.xml.gz, 1f 8b) in stukken uitpakken met pako, de tekst in stukken
   * decoderen (TextDecoder) en meteen doorlezen. Tussen de stukken krijgt de afstandsbediening voorrang.
   */
  P.loadXMLTV = function (url, wanted) {
    return B.http(url, { timeout: 180000, binary: true }).then(function (r) {
      if (r.status < 200 || r.status >= 300 || !r.buffer || !r.buffer.byteLength) return {};
      return P.readXMLTVBuffer(r.buffer, wanted);
    }, function () { return {}; });
  };
  P.readXMLTVBuffer = function (buffer, wanted) {
    var bytes = new Uint8Array(buffer);
    var reader = new XMLTVReader(wanted);
    var gz = bytes.length > 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
    var dec = makeDecoder();
    var CHUNK = 256 * 1024;
    return new Promise(function (resolve) {
      var inflator = null, failed = false;
      if (gz) {
        if (!window.pako || !window.pako.Inflate) { resolve({}); return; }
        inflator = new window.pako.Inflate();
        inflator.onData = function (chunk) { reader.push(dec.decode(chunk, true)); };
        inflator.onEnd = function (status) { if (status !== 0) failed = true; };
      }
      var off = 0;
      function step() {
        var t0 = Date.now();
        try {
          while (off < bytes.length && Date.now() - t0 < 14) {
            var part = bytes.subarray(off, Math.min(bytes.length, off + CHUNK));
            off += part.length;
            if (inflator) {
              inflator.push(part, off >= bytes.length);
              if (inflator.err) { failed = true; break; }
            } else reader.push(dec.decode(part, true));
          }
        } catch (e) { failed = true; }
        if (failed || off >= bytes.length) {
          try { reader.push(dec.decode(new Uint8Array(0), false)); } catch (e) { /* stil */ }
          bytes = null;
          resolve(reader.finish());
          return;
        }
        setTimeout(step, Date.now() - B.lastKeyAt < 250 ? 24 : 0);
      }
      step();
    });
  };
  /** UTF-8-decoder; TextDecoder als die er is, anders een eenvoudige eigen versie. */
  function makeDecoder() {
    if (window.TextDecoder) {
      var td = new window.TextDecoder('utf-8');
      return { decode: function (u8, more) { return td.decode(u8, { stream: !!more }); } };
    }
    var rest = [];
    return {
      decode: function (u8) {
        var s = '';
        var all = rest.length ? rest.concat(Array.prototype.slice.call(u8)) : u8;
        var n = all.length, i = 0;
        // onvolledig teken aan het eind bewaren
        var cut = n;
        for (var b = Math.max(0, n - 3); b < n; b++) {
          var c = all[b];
          var need = c >= 0xf0 ? 4 : c >= 0xe0 ? 3 : c >= 0xc0 ? 2 : 1;
          if (need > 1 && b + need > n) { cut = b; break; }
        }
        rest = Array.prototype.slice.call(all, cut);
        var chunk = [];
        for (i = 0; i < cut; i += 8192) chunk.push(String.fromCharCode.apply(null, Array.prototype.slice.call(all, i, Math.min(cut, i + 8192))));
        try { s = decodeURIComponent(escape(chunk.join(''))); } catch (e) { s = chunk.join(''); }
        return s;
      }
    };
  }
})();
