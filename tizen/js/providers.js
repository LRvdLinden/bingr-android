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

  P.m3uLibrary = function (text, playlistID) {
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
      if (looksSeries) {
        var sKey = parsed.title.toLowerCase();
        if (!seriesMap[sKey]) {
          seriesMap[sKey] = { id: 's-' + B.stableHash(group + sKey), title: parsed.title, year: parsed.year, poster: logo, group: group, episodes: [] };
          seriesEps[sKey] = [];
          seriesOrder.push(sKey);
        }
        var eps = seriesEps[sKey];
        eps.push({
          id: id, seriesID: seriesMap[sKey].id, season: parsed.season || 1,
          number: parsed.episode || (eps.length + 1), title: e.name, streamURL: urlValue(e.url), still: logo
        });
      } else if (looksMovie) {
        movies.push({ id: id, title: parsed.title, year: parsed.year, poster: logo, group: group, streamURL: urlValue(e.url) });
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
    return B.getJSON(this.api(), null, 30000).then(function (r) {
      if (!r || !r.user_info || B.int(r.user_info.auth) !== 1) throw new Error(L("Couldn't sign in to the server. Check the server, username and password."));
      return r;
    });
  };
  function items(j) {
    if (Array.isArray(j)) return j;
    if (j && typeof j === 'object') return Object.keys(j).map(function (k) { return j[k]; });
    return [];
  }
  Xtream.prototype.list = function (action) {
    return B.getJSON(this.api(action), null, 120000).then(items, function () { return []; });
  };
  function catMap(cats, other) {
    var m = {};
    cats.forEach(function (c) { var id = B.str(c && c.category_id); if (id != null && !B.has(m, id)) m[id] = c.category_name || other; });
    return m;
  }
  Xtream.prototype.library = function () {
    var self = this;
    var acts = ['get_live_categories', 'get_live_streams', 'get_vod_categories', 'get_vod_streams', 'get_series_categories', 'get_series'];
    return Promise.all(acts.map(function (a) { return self.list(a); })).then(function (r) {
      var other = L('Other');
      var lc = catMap(r[0], other), vc = catMap(r[2], other), sc = catMap(r[4], other);
      var ext = self.preferHLS ? 'm3u8' : 'ts';
      var base = self.server, u = self.username, p = self.password;
      var channels = [], movies = [], series = [];
      r[1].forEach(function (l) {
        var sid = B.str(l.stream_id); if (sid == null) return;
        channels.push({
          id: 'x' + sid, name: l.name || 'Channel', logo: urlValue(l.stream_icon), group: lc[B.str(l.category_id) || ''] || other,
          streamURL: base + '/live/' + u + '/' + p + '/' + sid + '.' + ext, epgID: l.epg_channel_id || null,
          number: B.int(l.num), catchupDays: B.int(l.tv_archive) === 1 ? (B.int(l.tv_archive_duration) || 0) : 0
        });
      });
      r[3].forEach(function (v) {
        var sid = B.str(v.stream_id); if (sid == null) return;
        var pt = B.parseTitle(v.name || '');
        var added = B.num(v.added);
        movies.push({
          id: 'xv' + sid, title: pt.title, year: B.int(v.year) || pt.year, poster: urlValue(v.stream_icon),
          group: vc[B.str(v.category_id) || ''] || other,
          streamURL: base + '/movie/' + u + '/' + p + '/' + sid + '.' + (v.container_extension || 'mp4'),
          rating: B.num(v.rating), addedAt: added ? added * 1000 : null, xtreamVodID: B.int(sid)
        });
      });
      r[5].forEach(function (s) {
        var sid = B.int(s.series_id); if (sid == null) return;
        var pt = B.parseTitle(s.name || '');
        var y = B.int(s.year) || (s.releaseDate ? B.int(String(s.releaseDate).slice(0, 4)) : null) || pt.year;
        series.push({
          id: 'xs' + sid, title: pt.title, year: y, poster: urlValue(s.cover), group: sc[B.str(s.category_id) || ''] || other,
          plot: s.plot || null, rating: B.num(s.rating), xtreamSeriesID: sid, episodes: []
        });
      });
      return { channels: channels, movies: movies, series: series, epg: {} };
    });
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
  Stalker.prototype.library = function () {
    var self = this;
    var other = L('Other');
    var lib = { channels: [], movies: [], series: [], epg: {} };
    return (this.token ? Promise.resolve() : this.connect()).then(function () {
      return Promise.all([
        self.js(self.url([['type', 'itv'], ['action', 'get_genres']])).catch(function () { return []; }),
        self.js(self.url([['type', 'itv'], ['action', 'get_all_channels']])).catch(function () { return null; })
      ]);
    }).then(function (r) {
      var gMap = {};
      items(r[0]).forEach(function (g) { if (g && g.id != null && !B.has(gMap, String(g.id))) gMap[String(g.id)] = g.title || other; });
      var chans = (r[1] && r[1].data) || [];
      lib.channels = chans.filter(function (c) { return c && c.id != null; }).map(function (c) {
        return {
          id: 'st' + c.id, name: c.name || 'Channel', logo: urlValue(c.logo), group: gMap[String(c.tv_genre_id || '')] || other,
          streamURL: directURL(c.cmd), epgID: c.xmltv_id || null, number: B.int(c.number),
          catchupDays: Math.floor((B.int(c.tv_archive_duration) || 0) / 24), stalkerCmd: c.cmd || null
        };
      });
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
                    group: cat.title || L('Series'), plot: v.description || null, rating: B.num(v.rating_imdb), episodes: [] });
                } else {
                  lib.movies.push({ id: 'stv' + v.id, title: pt.title, year: B.int(v.year) || pt.year, poster: urlValue(v.screenshot_uri),
                    group: cat.title || L('Movies'), rating: B.num(v.rating_imdb), plot: v.description || null, stalkerCmd: v.cmd || null });
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
  function attr(tag, name) {
    var m = new RegExp('\\s' + name + '="([^"]*)"').exec(tag);
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
   * XMLTV-tekst → {kanaal-id: [programma's]}. Alleen kanalen uit [wanted] (kleine letters; leeg = alles)
   * en programma's van 3 uur terug tot 36 uur vooruit.
   */
  P.parseXMLTV = function (text, wanted) {
    var now = Date.now(), lower = now - 3 * 3600000, upper = now + 36 * 3600000;
    var out = {};
    var pos = 0;
    var want = wanted && Object.keys(wanted).length ? wanted : null;
    while (true) {
      var i = text.indexOf('<programme', pos);
      if (i < 0) break;
      var tagEnd = text.indexOf('>', i);
      var end = text.indexOf('</programme>', tagEnd);
      if (tagEnd < 0) break;
      var selfClosing = text.charAt(tagEnd - 1) === '/';
      if (!selfClosing && end < 0) break;
      var tag = text.substring(i, tagEnd);
      pos = selfClosing ? tagEnd + 1 : end + 12;
      var ch = attr(tag, 'channel');
      if (!ch) continue;
      var key = ch.toLowerCase();
      if (want && !want[key]) continue;
      var s = xmlDate(attr(tag, 'start')), e = xmlDate(attr(tag, 'stop'));
      if (s == null || e == null || e < lower || s > upper) continue;
      var block = selfClosing ? '' : text.substring(tagEnd + 1, end);
      (out[key] = out[key] || []).push({ title: inner(block, 'title') || '', description: inner(block, 'desc') || '', start: s, end: e });
    }
    for (var k in out) if (B.has(out, k)) out[k].sort(function (a, b) { return a.start - b.start; });
    return out;
  };
  P.loadXMLTV = function (url, wanted) {
    return B.http(url, { timeout: 120000 }).then(function (r) {
      if (r.status < 200 || r.status >= 300 || !r.text) return {};
      if (r.text.charCodeAt(0) === 0x1f) return {}; // gzip-bestand: de tv kan dit niet zelf uitpakken
      return P.parseXMLTV(r.text, wanted);
    }, function () { return {}; });
  };
})();
