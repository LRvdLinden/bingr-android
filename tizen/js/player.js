/*
 * Speler: Samsung AVPlay (webapis.avplay) voor HLS, TS en MP4, met HTML5 <video> als terugval.
 * Regelt hervatten, voortgang (gesynchroniseerd zoals de andere apps), audio- en ondertitelsporen,
 * zappen met vertraging, intro overslaan (introMarkers), volgende aflevering met aftellen,
 * stream_start/ping/stop (max. 6 tegelijk), kijkgeschiedenis, reservebron bij een live-fout en een foutscherm.
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui, D = B.data, A = B.account, Lib = B.lib;
  var h = U.h;
  var L = function () { return B.L.apply(null, arguments); };

  // MARK: engines

  function hasAVPlay() { try { return !!(window.webapis && webapis.avplay && webapis.avplay.open); } catch (e) { return false; } }

  function AVEngine(ev) { this.ev = ev; this.opened = false; this.playing = false; this.dur = 0; }
  AVEngine.prototype.name = 'avplay';
  AVEngine.prototype.open = function (url, opts) {
    var self = this, av = webapis.avplay;
    return new Promise(function (resolve, reject) {
      try {
        try { av.stop(); av.close(); } catch (e) { /* niets open */ }
        av.open(url);
        self.opened = true;
        av.setDisplayRect(0, 0, 1920, 1080);
        try { av.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX'); } catch (e) { /* oudere tv */ }
        av.setListener({
          onbufferingstart: function () { self.ev.buffering(true); },
          onbufferingprogress: B.noop,
          onbufferingcomplete: function () { self.ev.buffering(false); },
          oncurrentplaytime: B.noop,
          onstreamcompleted: function () { self.playing = false; self.ev.ended(); },
          onerror: function (t) { self.playing = false; self.ev.error(String(t || 'PLAYER_ERROR')); },
          onevent: B.noop,
          onsubtitlechange: function (duration, text) { self.ev.subtitle(text || ''); },
          ondrmevent: B.noop
        });
        if (opts.live) { try { av.setTimeoutForBuffering(15); } catch (e) { /* stil */ } }
        av.prepareAsync(function () {
          try { self.dur = av.getDuration() / 1000; } catch (e) { self.dur = 0; }
          var go = function () { av.play(); self.playing = true; resolve(); };
          if (opts.start && opts.start > 5 && !opts.live) {
            av.seekTo(Math.floor(opts.start * 1000), go, go);
          } else go();
        }, function (e) { reject(new Error(String(e && e.name || e || 'prepare'))); });
      } catch (e) { reject(e); }
    });
  };
  AVEngine.prototype.play = function () { try { webapis.avplay.play(); this.playing = true; } catch (e) { /* stil */ } };
  AVEngine.prototype.pause = function () { try { webapis.avplay.pause(); this.playing = false; } catch (e) { /* stil */ } };
  AVEngine.prototype.stop = function () {
    try { webapis.avplay.stop(); } catch (e) { /* stil */ }
    try { webapis.avplay.close(); } catch (e) { /* stil */ }
    this.playing = false; this.opened = false;
  };
  AVEngine.prototype.time = function () { try { return webapis.avplay.getCurrentTime() / 1000; } catch (e) { return 0; } };
  AVEngine.prototype.duration = function () {
    try { var d = webapis.avplay.getDuration() / 1000; if (d > 0) this.dur = d; } catch (e) { /* stil */ }
    return this.dur || 0;
  };
  AVEngine.prototype.seek = function (sec) {
    try { webapis.avplay.seekTo(Math.max(0, Math.floor(sec * 1000)), B.noop, B.noop); } catch (e) { /* stil */ }
  };
  AVEngine.prototype.tracks = function () {
    var out = { audio: [], text: [] };
    try {
      var all = webapis.avplay.getTotalTrackInfo() || [];
      for (var i = 0; i < all.length; i++) {
        var t = all[i], extra = {};
        try { extra = JSON.parse(t.extra_info || '{}'); } catch (e) { extra = {}; }
        var lang = extra.language || extra.track_lang || '';
        if (t.type === 'AUDIO') out.audio.push({ index: t.index, lang: lang, label: langName(lang) || (L('Audio') + ' ' + (out.audio.length + 1)), extra: extra });
        else if (t.type === 'TEXT') out.text.push({ index: t.index, lang: lang, label: langName(lang) || (L('Subtitles') + ' ' + (out.text.length + 1)) });
      }
    } catch (e) { /* geen sporen */ }
    try {
      var cur = webapis.avplay.getCurrentStreamInfo() || [];
      cur.forEach(function (c) { if (c.type === 'AUDIO') out.currentAudio = c.index; if (c.type === 'TEXT') out.currentText = c.index; });
    } catch (e) { /* stil */ }
    return out;
  };
  AVEngine.prototype.selectAudio = function (i) { try { webapis.avplay.setSelectTrack('AUDIO', i); } catch (e) { /* stil */ } };
  AVEngine.prototype.selectText = function (i) {
    try {
      if (i == null) { webapis.avplay.setSilentSubtitle(true); this.ev.subtitle(''); return; }
      webapis.avplay.setSilentSubtitle(false);
      webapis.avplay.setSelectTrack('TEXT', i);
    } catch (e) { /* stil */ }
  };

  function HTMLEngine(ev) {
    this.ev = ev;
    this.v = document.getElementById('html-video');
    var self = this;
    this.v.onwaiting = function () { self.ev.buffering(true); };
    this.v.onplaying = function () { self.ev.buffering(false); };
    this.v.onended = function () { self.ev.ended(); };
    this.v.onerror = function () { self.ev.error('MEDIA_ERR_' + ((self.v.error && self.v.error.code) || 0)); };
  }
  HTMLEngine.prototype.name = 'html5';
  HTMLEngine.prototype.open = function (url, opts) {
    var v = this.v;
    v.style.display = 'block';
    return new Promise(function (resolve, reject) {
      var done = false;
      v.oncanplay = function () {
        if (done) return; done = true;
        if (opts.start && opts.start > 5 && !opts.live) { try { v.currentTime = opts.start; } catch (e) { /* stil */ } }
        var p = v.play();
        if (p && p.then) p.then(resolve, function () { resolve(); }); else resolve();
      };
      v.onerror = function () { if (!done) { done = true; reject(new Error('MEDIA_ERR')); } };
      v.src = url;
      v.load();
    });
  };
  HTMLEngine.prototype.play = function () { var p = this.v.play(); if (p && p.catch) p.catch(B.noop); };
  HTMLEngine.prototype.pause = function () { this.v.pause(); };
  HTMLEngine.prototype.stop = function () { try { this.v.pause(); this.v.removeAttribute('src'); this.v.load(); } catch (e) { /* stil */ } this.v.style.display = 'none'; };
  HTMLEngine.prototype.time = function () { return this.v.currentTime || 0; };
  HTMLEngine.prototype.duration = function () { var d = this.v.duration; return isFinite(d) ? d : 0; };
  HTMLEngine.prototype.seek = function (s) { try { this.v.currentTime = Math.max(0, s); } catch (e) { /* stil */ } };
  Object.defineProperty(HTMLEngine.prototype, 'playing', { get: function () { return !this.v.paused && !this.v.ended; } });
  HTMLEngine.prototype.tracks = function () {
    var out = { audio: [], text: [] };
    var at = this.v.audioTracks;
    if (at) for (var i = 0; i < at.length; i++) { out.audio.push({ index: i, lang: at[i].language, label: langName(at[i].language) || at[i].label || ('Audio ' + (i + 1)) }); if (at[i].enabled) out.currentAudio = i; }
    var tt = this.v.textTracks;
    if (tt) for (var j = 0; j < tt.length; j++) { out.text.push({ index: j, lang: tt[j].language, label: langName(tt[j].language) || tt[j].label || ('Sub ' + (j + 1)) }); if (tt[j].mode === 'showing') out.currentText = j; }
    return out;
  };
  HTMLEngine.prototype.selectAudio = function (i) { var at = this.v.audioTracks; if (at) for (var k = 0; k < at.length; k++) at[k].enabled = k === i; };
  HTMLEngine.prototype.selectText = function (i) { var tt = this.v.textTracks; if (tt) for (var k = 0; k < tt.length; k++) tt[k].mode = k === i ? 'showing' : 'disabled'; };

  var LANGS = { nl: 'Nederlands', nld: 'Nederlands', dut: 'Nederlands', en: 'English', eng: 'English', de: 'Deutsch', deu: 'Deutsch', ger: 'Deutsch',
    fr: 'Français', fra: 'Français', fre: 'Français', es: 'Español', spa: 'Español', it: 'Italiano', ita: 'Italiano', tr: 'Türkçe', tur: 'Türkçe',
    ar: 'العربية', ara: 'العربية', pl: 'Polski', pol: 'Polski', pt: 'Português', por: 'Português', und: '' };
  function langName(code) { if (!code) return ''; var c = String(code).toLowerCase(); return B.has(LANGS, c) ? LANGS[c] : String(code).toUpperCase(); }

  // MARK: spelerscherm

  /**
   * B.player.open(item, ctx)
   * item: {type: 'live'|'movie'|'episode', id, title, subtitle, streamURL, stalkerCmd, artwork, seriesID, season, episode, channel}
   * ctx: {channels: [...], index, episodes: [...], series, resume: seconden|null, sourceId}
   */
  B.player = {};
  B.player.open = function (item, ctx) {
    ctx = ctx || {};
    var profile = B.app.profile;
    var engine = null;
    var closed = false, overlayTimer = null, tickTimer = null, pingTimer = null;
    var watchedSeconds = 0, recorded = false, lastSave = 0, attempts = 0, triedBackup = false;
    var nextShown = false, nextDismissed = false, countdownTimer = null;
    var zapTimer = null, zapIndex = null, digitBuffer = '', digitTimer = null;
    var marker = null;

    var video = h('div.pl-stage');
    var subtitle = h('div.pl-sub');
    var spinner = h('div.pl-spin', [U.spinner()]);
    var title = h('div.pl-title');
    var sub = h('div.pl-subtitle');
    var epg = h('div.pl-epg');
    var bar = h('div.pl-bar', [h('div.pl-bar-fill')]);
    var tCur = h('span.pl-time'), tDur = h('span.pl-time.right');
    var btnRow = h('div.pl-buttons.fm');
    var overlay = h('div.pl-overlay', [
      h('div.pl-top', [title, sub, epg]),
      h('div.pl-bottom', [h('div.pl-progress', [tCur, bar, tDur]), btnRow])
    ]);
    var skipBtn = U.button(L('Skip intro'), function () { skipIntro(); }, { cls: 'pl-skip' });
    skipBtn.style.display = 'none';
    var nextCard = h('div.pl-next');
    nextCard.style.display = 'none';
    var zapBanner = h('div.pl-zap');
    zapBanner.style.display = 'none';
    var errorBox = h('div.pl-error');
    errorBox.style.display = 'none';
    var el = h('div.layer.player', [video, subtitle, spinner, overlay, skipBtn, nextCard, zapBanner, errorBox]);
    var screen = { el: el, isPlayer: true };

    function isLive() { return item.type === 'live'; }

    // MARK: overlay

    function showOverlay(focusPlay) {
      overlay.classList.add('show');
      clearTimeout(overlayTimer);
      overlayTimer = setTimeout(hideOverlay, 6000);
      if (focusPlay || !btnRow.contains(U.focused)) { var b = btnRow.querySelector('.f'); if (b) U.focus(b); }
    }
    function hideOverlay() {
      if (errorBox.style.display !== 'none') return;
      overlay.classList.remove('show');
      if (btnRow.contains(U.focused)) { U.focused.classList.remove('focused'); U.focused = null; }
    }
    function overlayVisible() { return overlay.classList.contains('show'); }

    function renderInfo() {
      title.textContent = isLive() && item.channel ? ((item.channel.number ? item.channel.number + '  ' : '') + item.channel.name) : item.title;
      sub.textContent = item.subtitle || '';
      U.clear(epg);
      if (isLive() && item.channel) {
        var nn = Lib.nowNext(item.channel);
        if (nn.now) epg.appendChild(h('div', [h('b', L('Now') + '  '), B.fmtTime(nn.now.start) + '–' + B.fmtTime(nn.now.end) + '  ' + nn.now.title]));
        if (nn.next) epg.appendChild(h('div.dim', [h('b', L('Next') + '  '), B.fmtTime(nn.next.start) + '  ' + nn.next.title]));
      }
      U.clear(btnRow);
      btnRow.appendChild(U.button('', togglePlay, { icon: engine && !engine.playing ? 'play' : 'pause', cls: 'round pl-play' }));
      if (!isLive()) {
        btnRow.appendChild(U.button('', function () { jump(-10); }, { icon: 'replay', cls: 'round' }));
      }
      btnRow.appendChild(U.button(L('Audio'), chooseAudio, { icon: 'audio' }));
      btnRow.appendChild(U.button(L('Subtitles'), chooseText, { icon: 'subs' }));
      if (item.type === 'episode' && nextEpisode()) btnRow.appendChild(U.button(L('Next episode'), function () { playNext(); }, { icon: 'next' }));
      if (isLive()) btnRow.appendChild(U.button(L('Other source'), function () { chooseBackup(); }, { icon: 'swap' }));
    }

    function updateTime() {
      if (!engine) return;
      var t = engine.time(), d = engine.duration();
      if (isLive()) {
        var nn = item.channel ? Lib.nowNext(item.channel) : {};
        if (nn.now) {
          var frac = (Date.now() - nn.now.start) / (nn.now.end - nn.now.start);
          bar.firstChild.style.width = (B.clamp(frac, 0, 1) * 100) + '%';
          tCur.textContent = B.fmtTime(nn.now.start); tDur.textContent = B.fmtTime(nn.now.end);
        } else { bar.firstChild.style.width = '100%'; tCur.textContent = 'LIVE'; tDur.textContent = ''; }
        return;
      }
      bar.firstChild.style.width = (d > 0 ? B.clamp(t / d, 0, 1) * 100 : 0) + '%';
      tCur.textContent = B.fmtDur(t);
      tDur.textContent = d > 0 ? '-' + B.fmtDur(d - t) : '';
    }

    // MARK: afspelen

    function start(resumeAt) {
      errorBox.style.display = 'none';
      spinner.style.display = 'flex';
      nextShown = false; nextDismissed = false; nextCard.style.display = 'none';
      clearInterval(countdownTimer);
      subtitle.textContent = '';
      marker = item.type === 'episode' && item.seriesID && Lib.source ? D.introMarker(Lib.source.id, item.seriesID) : null;
      renderInfo();
      showOverlay(true);
      streamStart().then(function (ok) {
        if (!ok || closed) return;
        return Lib.resolveURL(item, isLive()).then(function (url) {
          if (closed) return;
          if (!url) throw new Error('no_url');
          return openURL(url, resumeAt);
        });
      }).catch(function (e) { fail(e); });
      if (isLive() && item.channel && Lib.source) {
        D.pushRecentChannel(Lib.source.id, item.channel.id);
        B.store.set('lastChannel.' + String(profile.id).toLowerCase(), { sourceId: Lib.source.id, channelId: item.channel.id });
      }
    }
    function openURL(url, resumeAt) {
      if (engine) engine.stop();
      var events = {
        buffering: function (b) { spinner.style.display = b ? 'flex' : 'none'; },
        ended: function () { onEnded(); },
        error: function (msg) { fail(new Error(msg)); },
        subtitle: function (t) { subtitle.innerHTML = B.esc(String(t || '').replace(/<[^>]+>/g, '')).replace(/\n/g, '<br>'); }
      };
      var useAV = hasAVPlay() && !item.forceHTML;
      engine = useAV ? new AVEngine(events) : new HTMLEngine(events);
      return engine.open(url, { live: isLive(), start: resumeAt || 0 }).then(function () {
        if (closed) { engine.stop(); return; }
        attempts = 0;
        spinner.style.display = 'none';
        applyTrackPrefs();
        renderInfo();
        showOverlay(true);
      }, function (e) {
        // AVPlay lukt niet: één keer met HTML5 <video> proberen (vooral MP4/HLS)
        if (useAV && !item.forceHTML) { item.forceHTML = true; return openURL(url, resumeAt); }
        throw e;
      });
    }
    function applyTrackPrefs() {
      var pref = String(D.shared('audioLanguage', '') || '').toLowerCase();
      if (!pref || !engine) return;
      var tr = engine.tracks();
      for (var i = 0; i < tr.audio.length; i++) {
        var lg = String(tr.audio[i].lang || '').toLowerCase();
        if (lg && (lg === pref || lg.indexOf(pref) === 0 || langName(lg) === langName(pref))) { engine.selectAudio(tr.audio[i].index); break; }
      }
    }

    function fail(e) {
      if (closed) return;
      B.log('player error', e && e.message);
      spinner.style.display = 'none';
      // één stille nieuwe poging
      if (attempts < 1) {
        attempts++;
        var at = engine && !isLive() ? engine.time() : 0;
        setTimeout(function () { if (!closed) Lib.resolveURL(item, isLive()).then(function (u) { return openURL(u, at); }).catch(fail); }, 1500);
        return;
      }
      // live: één keer automatisch een reservebron
      if (isLive() && item.channel && !triedBackup) {
        triedBackup = true;
        Lib.backupChannels(item.channel).then(function (hits) {
          if (!hits.length || closed) { showError(); return; }
          U.toast(L('Trying another source…'));
          Lib.resolveBackup(hits[0]).then(function (u) { attempts = 1; return openURL(u, 0); }).catch(function () { showError(); });
        });
        return;
      }
      showError();
    }
    function showError() {
      if (closed) return;
      if (engine) engine.stop();
      U.clear(errorBox);
      errorBox.appendChild(h('h2', L("Can't play this")));
      errorBox.appendChild(h('p', isLive() ? L('This channel is not available right now.') : L('This title could not be loaded.')));
      var row = h('div.modal-buttons');
      row.appendChild(U.button(L('Try again'), function () { attempts = 0; triedBackup = false; start(engine && !isLive() ? engine.time() : (ctx.resume || 0)); }, { primary: true }));
      if (isLive()) row.appendChild(U.button(L('Other source'), chooseBackup));
      row.appendChild(U.button(L('Close'), close));
      errorBox.appendChild(row);
      errorBox.style.display = 'flex';
      overlay.classList.remove('show');
      U.focus(row.firstChild);
    }

    function togglePlay() {
      if (!engine) return;
      if (engine.playing) engine.pause(); else engine.play();
      renderInfo();
      var pb = btnRow.querySelector('.pl-play');
      if (pb) U.focus(pb);
      showOverlay();
    }
    function jump(s) {
      if (!engine || isLive()) return;
      var d = engine.duration();
      var t = B.clamp(engine.time() + s, 0, d > 0 ? d - 2 : engine.time() + s);
      engine.seek(t);
      updateTime();
      showOverlay();
    }

    // MARK: sporen

    function chooseAudio() {
      if (!engine) return;
      var tr = engine.tracks();
      if (!tr.audio.length) { U.toast(L('No other audio tracks')); return; }
      U.choose(L('Audio'), tr.audio.map(function (t) { return { value: t.index, label: t.label }; }), tr.currentAudio, function (v) { engine.selectAudio(v); });
    }
    function chooseText() {
      if (!engine) return;
      var tr = engine.tracks();
      var opts = [{ value: -1, label: L('Off') }].concat(tr.text.map(function (t) { return { value: t.index, label: t.label }; }));
      U.choose(L('Subtitles'), opts, tr.currentText == null ? -1 : tr.currentText, function (v) { engine.selectText(v < 0 ? null : v); });
    }
    function chooseBackup() {
      if (!item.channel) return;
      Lib.backupChannels(item.channel).then(function (hits) {
        if (!hits.length) { U.toast(L('No other source found for this channel.')); return; }
        U.choose(L('Other source'), hits.map(function (x, i) { return { value: i, label: x.source.name + ' · ' + x.name }; }), null, function (i) {
          spinner.style.display = 'flex';
          errorBox.style.display = 'none';
          Lib.resolveBackup(hits[i]).then(function (u) { attempts = 1; return openURL(u, 0); }).catch(function () { showError(); });
        });
      });
    }

    // MARK: intro en volgende aflevering

    function inIntro(t) {
      if (!marker || marker.introEnd == null) return false;
      var s = marker.introStart || 0;
      return marker.introEnd > s + 1 && t >= s && t < marker.introEnd - 1;
    }
    function skipIntro() { if (engine && marker) { engine.seek(marker.introEnd); skipBtn.style.display = 'none'; } }
    function nextEpisode() {
      var eps = ctx.episodes || [];
      for (var i = 0; i < eps.length; i++) if (eps[i].id === item.id) return eps[i + 1] || null;
      return null;
    }
    function showNextCard() {
      var nx = nextEpisode();
      if (!nx || nextShown || nextDismissed) return;
      nextShown = true;
      var left = 10;
      U.clear(nextCard);
      var count = h('span.pl-count', String(left));
      var playBtn = U.button(L('Next episode'), function () { playNext(); }, { primary: true, icon: 'play' });
      nextCard.appendChild(h('div.pl-next-info', [h('div.dim', L('Next episode')), h('div.pl-next-title', 'S' + nx.season + ':E' + nx.number + ' ' + nx.title), h('div.dim', [L('Starts in'), ' ', count])]));
      nextCard.appendChild(playBtn);
      nextCard.style.display = 'flex';
      U.focus(playBtn);
      countdownTimer = setInterval(function () {
        left--;
        count.textContent = String(left);
        if (left <= 0) { clearInterval(countdownTimer); playNext(); }
      }, 1000);
    }
    function playNext() {
      var nx = nextEpisode();
      clearInterval(countdownTimer);
      if (!nx) { close(); return; }
      saveProgress(true);
      stopEngine(false);
      item = episodeItem(nx, ctx.series);
      watchedSeconds = 0; recorded = false; attempts = 0;
      var p = D.progress(nx.id);
      start(p ? p.position : 0);
    }
    function onEnded() {
      saveProgress(true, true);
      if (item.type === 'episode' && nextEpisode()) { nextDismissed = false; showNextCard(); return; }
      close();
    }

    // MARK: voortgang en geschiedenis (= saveProgress/recordHistory op Android)

    function saveProgress(force, ended) {
      if (!engine || isLive()) return;
      var now = Date.now();
      if (!force && now - lastSave < 10000) return;
      lastSave = now;
      var d = engine.duration(), t = engine.time();
      if (d <= 60) return;
      var pos = ended ? 1 : t / d;
      var left = d - t;
      var inCredits = marker && marker.creditsStart != null && marker.creditsStart > 60 && t >= marker.creditsStart;
      if (pos >= 0.85 || (d > 600 && left < 180 && pos > 0.7) || inCredits) {
        D.clearProgress(item.id);
        D.setWatched(item.id, true);
        if (item.type === 'episode' && item.seriesID && item.season != null && item.episode != null) {
          D.setWatched(D.episodeKey(item.seriesID, item.season, item.episode), true);
        }
      } else if (pos > 0.02) {
        D.setProgress(item.id, { fraction: pos, position: t, title: item.title, artwork: item.artwork || null, playlistID: Lib.source ? Lib.source.id : null });
      }
    }
    function recordHistory() {
      if (recorded || watchedSeconds < 60) return;
      recorded = true;
      D.addHistory({
        itemID: item.channel ? item.channel.id : item.id, playlistID: Lib.source ? Lib.source.id : null, title: item.title,
        subtitle: item.subtitle || null, artwork: item.artwork || null, type: item.type, channelID: item.channel ? item.channel.id : null, date: Date.now()
      });
    }

    // MARK: tegelijk kijken (stream_start / ping / stop)

    function streamTitle() {
      if (isLive() && item.channel) { var nn = Lib.nowNext(item.channel); return item.channel.name + (nn.now ? ' · ' + nn.now.title : ''); }
      return item.subtitle ? item.title + ' · ' + item.subtitle : item.title;
    }
    function streamStart() {
      return A.streamStart(profile.id, profile.name, streamTitle(), item.type, item.id).then(function (r) {
        if (r.limit) { showLimit(r); return false; }
        clearInterval(pingTimer);
        pingTimer = setInterval(function () {
          A.streamPing(streamTitle()).then(function (alive) {
            if (alive === false && !closed) A.streamStart(profile.id, profile.name, streamTitle(), item.type, item.id).then(function (r2) { if (r2.limit) { stopEngine(false); showLimit(r2); } });
          });
        }, 30000);
        return true;
      });
    }
    function showLimit(r) {
      spinner.style.display = 'none';
      if (engine) engine.stop();
      U.clear(errorBox);
      errorBox.appendChild(h('h2', L('Too many people are watching')));
      errorBox.appendChild(h('p', L('Your account can be used on up to %d screens at the same time.', r.limit)));
      var list = h('div.limit-list');
      r.streams.forEach(function (s) {
        list.appendChild(h('div.limit-row', [U.icon(s.platform.indexOf('TV') >= 0 || s.platform === 'tizen' ? 'tv' : 'phone'),
          h('div', [h('div', s.deviceName + ' · ' + s.profileName), h('div.dim', s.title)])]));
      });
      errorBox.appendChild(list);
      var row = h('div.modal-buttons');
      row.appendChild(U.button(L('Try again'), function () { errorBox.style.display = 'none'; start(ctx.resume || 0); }, { primary: true }));
      row.appendChild(U.button(L('Close'), close));
      errorBox.appendChild(row);
      errorBox.style.display = 'flex';
      overlay.classList.remove('show');
      U.focus(row.firstChild);
    }

    // MARK: zappen

    function zap(delta) {
      var list = ctx.channels || [];
      if (!list.length) return;
      var base = zapIndex == null ? (ctx.index || 0) : zapIndex;
      zapIndex = (base + delta + list.length) % list.length;
      showZap(list[zapIndex]);
      clearTimeout(zapTimer);
      zapTimer = setTimeout(commitZap, D.local('zapDelay', 600));
    }
    function showZap(ch) {
      var nn = Lib.nowNext(ch);
      U.clear(zapBanner);
      zapBanner.appendChild(h('div.pl-zap-num', ch.number != null ? String(ch.number) : ''));
      zapBanner.appendChild(h('div', [h('div.pl-zap-name', ch.name), h('div.dim', nn.now ? nn.now.title : '')]));
      zapBanner.style.display = 'flex';
    }
    function commitZap() {
      if (zapIndex == null) return;
      var ch = ctx.channels[zapIndex];
      ctx.index = zapIndex; zapIndex = null;
      setTimeout(function () { zapBanner.style.display = 'none'; }, 2500);
      switchChannel(ch);
    }
    function switchChannel(ch) {
      stopEngine(false);
      item = channelItem(ch);
      watchedSeconds = 0; recorded = false; attempts = 0; triedBackup = false;
      start(0);
    }
    function digit(n) {
      if (!isLive()) return;
      digitBuffer += n;
      zapBanner.style.display = 'flex';
      U.clear(zapBanner);
      zapBanner.appendChild(h('div.pl-zap-num', digitBuffer));
      clearTimeout(digitTimer);
      digitTimer = setTimeout(function () {
        var num = parseInt(digitBuffer, 10); digitBuffer = '';
        var list = ctx.channels || [];
        for (var i = 0; i < list.length; i++) if (list[i].number === num) { ctx.index = i; showZap(list[i]); switchChannel(list[i]); setTimeout(function () { zapBanner.style.display = 'none'; }, 2500); return; }
        zapBanner.style.display = 'none';
      }, 1500);
    }

    // MARK: toetsen

    screen.onKey = function (name) {
      if (errorBox.style.display !== 'none') return false;
      if (nextCard.style.display !== 'none' && name === 'back') {
        nextDismissed = true; clearInterval(countdownTimer); nextCard.style.display = 'none'; return true;
      }
      if (name.charAt(0) === 'd' && name.length === 2) { digit(name.charAt(1)); return true; }
      switch (name) {
        case 'play': if (engine && !engine.playing) togglePlay(); return true;
        case 'pause': if (engine && engine.playing) togglePlay(); return true;
        case 'playpause': togglePlay(); return true;
        case 'stop': close(); return true;
        case 'ff': jump(30); return true;
        case 'rw': jump(-30); return true;
        case 'chup': if (isLive()) zap(-1); return true;
        case 'chdown': if (isLive()) zap(1); return true;
        case 'info': if (overlayVisible()) hideOverlay(); else showOverlay(true); return true;
      }
      var skipVisible = skipBtn.style.display !== 'none';
      if (!overlayVisible() && !skipVisible && nextCard.style.display === 'none') {
        if (name === 'enter') { showOverlay(true); return true; }
        if (name === 'left' || name === 'right') { if (isLive()) showOverlay(true); else jump(name === 'left' ? -10 : 10); return true; }
        if (name === 'up' || name === 'down') { if (isLive()) zap(name === 'up' ? -1 : 1); else showOverlay(true); return true; }
      }
      if (overlayVisible()) {
        clearTimeout(overlayTimer);
        overlayTimer = setTimeout(hideOverlay, 6000);
        if (name === 'up' && !skipVisible) { hideOverlay(); return true; }
      }
      return false;
    };
    screen.onBack = function () {
      if (overlayVisible()) { hideOverlay(); return true; }
      close();
      return true;
    };

    // MARK: klok

    function tick() {
      if (!engine || closed) return;
      updateTime();
      if (engine.playing) {
        watchedSeconds += 1;
        recordHistory();
        saveProgress(false);
      }
      if (!isLive()) {
        var t = engine.time(), d = engine.duration();
        var intro = inIntro(t);
        if (intro && skipBtn.style.display === 'none') {
          skipBtn.style.display = 'block';
          if (!overlayVisible()) U.focus(skipBtn);
        } else if (!intro && skipBtn.style.display !== 'none') {
          skipBtn.style.display = 'none';
          if (U.focused === skipBtn) { U.focused = null; }
        }
        if (item.type === 'episode' && d > 120) {
          var credits = marker && marker.creditsStart != null ? marker.creditsStart : d - 25;
          if (t >= credits && !nextShown) showNextCard();
        }
      }
    }

    function stopEngine(sendStop) {
      saveProgress(true);
      if (engine) engine.stop();
      if (sendStop) { clearInterval(pingTimer); A.streamStop(); }
    }
    function close() {
      if (closed) return;
      closed = true;
      clearInterval(tickTimer); clearInterval(pingTimer); clearInterval(countdownTimer);
      clearTimeout(overlayTimer); clearTimeout(zapTimer); clearTimeout(digitTimer);
      stopEngine(true);
      document.documentElement.classList.remove('playing');
      D.flush();
      if (U.top() === screen) U.pop();
      B.player.current = null;
      if (ctx.onClose) ctx.onClose();
    }
    screen.close = close;
    screen.pauseForBackground = function () {
      saveProgress(true);
      if (engine && engine.playing) engine.pause();
      clearInterval(pingTimer);
      A.streamStop();
      renderInfo();
      showOverlay(true);
    };
    screen.resumeFromBackground = function () { if (!closed) streamStart(); };
    screen.destroy = function () { if (!closed) { closed = true; stopEngine(true); document.documentElement.classList.remove('playing'); } };

    document.documentElement.classList.add('playing');
    B.player.current = screen;
    U.push(screen);
    tickTimer = setInterval(tick, 1000);
    start(ctx.resume || 0);
    return screen;
  };

  // MARK: items maken

  function channelItem(ch) {
    return { type: 'live', id: ch.id, title: ch.name, subtitle: ch.group, streamURL: ch.streamURL, stalkerCmd: ch.stalkerCmd || null, artwork: ch.logo || null, channel: ch };
  }
  function episodeItem(ep, series) {
    return {
      type: 'episode', id: ep.id, title: series ? series.title : ep.title, subtitle: 'S' + ep.season + ':E' + ep.number + ' · ' + ep.title,
      streamURL: ep.streamURL, artwork: (series && series.poster) || ep.still || null, seriesID: ep.seriesID, season: ep.season, episode: ep.number
    };
  }
  function movieItem(m) {
    return { type: 'movie', id: m.id, title: m.title, subtitle: m.year ? String(m.year) : '', streamURL: m.streamURL, stalkerCmd: m.stalkerCmd || null, artwork: m.poster || null };
  }
  B.player.channelItem = channelItem;
  B.player.episodeItem = episodeItem;
  B.player.movieItem = movieItem;

  /** Zender afspelen met zappen binnen de lijst. */
  B.player.playChannel = function (ch, list) {
    list = list || [ch];
    var idx = 0;
    for (var i = 0; i < list.length; i++) if (list[i].id === ch.id) { idx = i; break; }
    return B.player.open(channelItem(ch), { channels: list, index: idx });
  };
  /** Film of aflevering afspelen; vraagt eerst "Hervatten" als er voortgang is. */
  B.player.playVOD = function (item, ctx) {
    ctx = ctx || {};
    var p = D.progress(item.id);
    if (p && p.position > 30 && p.fraction < 0.95 && ctx.resume == null) {
      U.modal({
        title: item.title,
        text: item.subtitle || '',
        buttons: [
          { label: L('Resume') + ' (' + B.fmtDur(p.position) + ')', fn: function () { ctx.resume = p.position; B.player.open(item, ctx); } },
          { label: L('Watch from the beginning'), fn: function () { ctx.resume = 0; B.player.open(item, ctx); } }
        ]
      });
      return;
    }
    B.player.open(item, ctx);
  };
})();
