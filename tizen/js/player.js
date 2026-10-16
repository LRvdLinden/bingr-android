/*
 * Speler: Samsung AVPlay (webapis.avplay) voor HLS, TS en MP4, met HTML5 <video> als terugval.
 * Regelt hervatten, voortgang (gesynchroniseerd zoals de andere apps), audio- en ondertitelsporen,
 * zappen met vertraging, intro overslaan (introMarkers), volgende aflevering met aftellen,
 * stream_start/ping/stop (limiet van het abonnement), kijkgeschiedenis, reservebron bij een live-fout en een foutscherm.
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
  // build 97: een tv heeft één AVPlay-speler; alleen de engine die hem als laatste opende mag hem stoppen of bedienen
  AVEngine.owner = null;
  AVEngine.prototype.open = function (url, opts) {
    var self = this, av = webapis.avplay;
    AVEngine.owner = self;
    return new Promise(function (resolve, reject) {
      // build 97: de belofte precies één keer afronden; een fout vóór het eerste beeld gaat alleen via reject
      // (dan kiest de speler de HTML5-terugval), pas daarna via ev.error (nieuwe poging / reservebron)
      var settled = false, started = false;
      function ok() { if (settled) return; settled = true; started = true; resolve(); }
      function bad(e) { if (settled) return; settled = true; reject(e instanceof Error ? e : new Error(String(e || 'PLAYER_ERROR'))); }
      try {
        try { av.stop(); av.close(); } catch (e) { /* niets open */ }
        av.open(url);
        self.opened = true;
        var r = opts.rect || { x: 0, y: 0, w: 1920, h: 1080 };
        av.setDisplayRect(Math.round(r.x), Math.round(r.y), Math.round(r.w), Math.round(r.h));
        try { av.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX'); } catch (e) { /* oudere tv */ }
        av.setListener({
          onbufferingstart: function () { self.ev.buffering(true); },
          onbufferingprogress: B.noop,
          onbufferingcomplete: function () { self.ev.buffering(false); },
          oncurrentplaytime: B.noop,
          onstreamcompleted: function () { if (AVEngine.owner !== self) return; self.playing = false; self.ev.ended(); },
          onerror: function (t) {
            if (AVEngine.owner !== self) return;
            self.playing = false;
            if (!started) bad(new Error(String(t || 'PLAYER_ERROR')));
            else self.ev.error(String(t || 'PLAYER_ERROR'));
          },
          onevent: B.noop,
          onsubtitlechange: function (duration, text) { self.ev.subtitle(text || ''); },
          ondrmevent: B.noop
        });
        if (opts.live) { try { av.setTimeoutForBuffering(15); } catch (e) { /* stil */ } }
        // sneller starten: kleine startbuffer (~2 s) in plaats van de standaard; oudere tv's kennen dit niet
        try {
          av.setBufferingParam('PLAYER_BUFFER_FOR_PLAY', 'PLAYER_BUFFER_SIZE_IN_SECOND', 2);
          av.setBufferingParam('PLAYER_BUFFER_FOR_RESUME', 'PLAYER_BUFFER_SIZE_IN_SECOND', opts.live ? 3 : 2);
        } catch (e) { /* niet ondersteund */ }
        av.prepareAsync(function () {
          if (settled || AVEngine.owner !== self) { bad(new Error('stale')); return; }
          try { self.dur = av.getDuration() / 1000; } catch (e) { self.dur = 0; }
          if (opts.mark) opts.mark('prepared');
          // sporen kiezen vóór play(): geen hoorbare wissel van audiotaal
          if (opts.onReady) { try { opts.onReady(); } catch (e) { B.log('onReady', e); } }
          // build 97: een fout in play() of seekTo() rondt de belofte af (geen eindeloze spinner)
          var go = function () {
            if (settled) return;
            try { av.play(); self.playing = true; ok(); } catch (e) { bad(e); }
          };
          if (opts.start && opts.start > 5 && !opts.live) {
            try { av.seekTo(Math.floor(opts.start * 1000), go, go); } catch (e) { B.log('seekTo', e && e.message); go(); }
          } else go();
        }, function (e) { bad(new Error(String(e && e.name || e || 'prepare'))); });
      } catch (e) { bad(e); }
    });
  };
  AVEngine.prototype.play = function () { try { webapis.avplay.play(); this.playing = true; } catch (e) { /* stil */ } };
  AVEngine.prototype.pause = function () { try { webapis.avplay.pause(); this.playing = false; } catch (e) { /* stil */ } };
  AVEngine.prototype.stop = function () {
    // build 97: een oude engine (tegel, voorbeeld) sluit niet de speler die een andere engine net geopend heeft
    if (AVEngine.owner === this || AVEngine.owner == null) {
      try { webapis.avplay.stop(); } catch (e) { /* stil */ }
      try { webapis.avplay.close(); } catch (e) { /* stil */ }
      AVEngine.owner = null;
    }
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
      var logged = this._tracksLogged;
      for (var i = 0; i < all.length; i++) {
        var t = all[i], info = trackInfo(t.extra_info);
        var extra = info.extra, lang = info.lang;
        if (!logged && (t.type === 'TEXT' || t.type === 'AUDIO')) B.log('spoor ' + t.type + ' ' + t.index + ': ' + String(typeof t.extra_info === 'string' ? t.extra_info : JSON.stringify(t.extra_info)).slice(0, 200));
        if (t.type === 'AUDIO') out.audio.push({ index: t.index, lang: lang, label: langName(lang) || (L('Audio') + ' ' + (out.audio.length + 1)), extra: extra });
        else if (t.type === 'TEXT') {
          var n = out.text.length + 1, kind = info.kind;
          out.text.push({ index: t.index, lang: lang,
            label: langName(lang) || (L('Subtitles') + ' ' + n + (kind ? ' · ' + kind : '')) });
        }
      }
      this._tracksLogged = true;
    } catch (e) { /* geen sporen */ }
    try {
      var cur = webapis.avplay.getCurrentStreamInfo() || [];
      cur.forEach(function (c) { if (c.type === 'AUDIO') out.currentAudio = c.index; if (c.type === 'TEXT') out.currentText = c.index; });
    } catch (e) { /* stil */ }
    return out;
  };
  // build 102: snelheid (setSpeed; niet elke tv of stream kan het, dan false), beeldverhouding en live-venster
  AVEngine.prototype.setRate = function (r) {
    if (AVEngine.owner !== this) return false;
    try { webapis.avplay.setSpeed(r); return true; } catch (e) { B.log('setSpeed', e && (e.name || e.message)); return false; }
  };
  AVEngine.prototype.setAspect = function (a) {
    if (AVEngine.owner !== this) return;
    var av = webapis.avplay;
    // fit = hele beeld met zwarte randen; fill = scherm vullen (bijsnijden); 16:9 en 4:3 = uitrekken naar die verhouding
    var r = a === 'r43' ? { x: 240, y: 0, w: 1440, h: 1080 } : { x: 0, y: 0, w: 1920, h: 1080 };
    try { av.setDisplayRect(r.x, r.y, r.w, r.h); } catch (e) { /* stil */ }
    var mode = a === 'fit' ? 'PLAYER_DISPLAY_MODE_LETTER_BOX' : (a === 'fill' ? 'PLAYER_DISPLAY_MODE_CROPPED_FULL' : 'PLAYER_DISPLAY_MODE_FULL_SCREEN');
    try { av.setDisplayMethod(mode); } catch (e) {
      // oudere tv zonder CROPPED_FULL: automatisch
      try { av.setDisplayMethod(a === 'fill' ? 'PLAYER_DISPLAY_MODE_AUTO_ASPECT_RATIO' : 'PLAYER_DISPLAY_MODE_LETTER_BOX'); } catch (e2) { /* stil */ }
    }
  };
  /** build 102: terugspoelbaar venster van een live-stream (HLS met DVR), {start, end} in seconden; anders null. */
  AVEngine.prototype.liveWindow = function () {
    try {
      var p = String(webapis.avplay.getStreamingProperty('GET_LIVE_DURATION') || '').split('|');
      var a = parseFloat(p[0]), b = parseFloat(p[1]);
      if (p.length === 2 && b > a) return { start: a / 1000, end: b / 1000 };
    } catch (e) { /* niet ondersteund */ }
    return null;
  };
  AVEngine.prototype.setRect = function (r) { try { webapis.avplay.setDisplayRect(Math.round(r.x), Math.round(r.y), Math.round(r.w), Math.round(r.h)); } catch (e) { /* stil */ } };
  AVEngine.prototype.selectAudio = function (i) { try { webapis.avplay.setSelectTrack('AUDIO', i); } catch (e) { /* stil */ } };
  AVEngine.prototype.selectText = function (i) {
    try {
      if (i == null) { webapis.avplay.setSilentSubtitle(true); this.ev.subtitle(''); return; }
      webapis.avplay.setSilentSubtitle(false);
      webapis.avplay.setSelectTrack('TEXT', i);
    } catch (e) { /* stil */ }
  };

  function HTMLEngine(ev, video) {
    this.ev = ev;
    this.v = video || document.getElementById('html-video');
    this.own = !!video;
    var self = this;
    this.v.onwaiting = function () { self.ev.buffering(true); };
    this.v.onplaying = function () { self.ev.buffering(false); };
    this.v.onended = function () { self.ev.ended(); };
    this.v.onerror = function () { self.ev.error('MEDIA_ERR_' + ((self.v.error && self.v.error.code) || 0)); };
  }
  /** build 97: dit element speelt nog voor deze engine (een voorbeeld- of tegelvideo kan intussen een nieuwe engine hebben). */
  HTMLEngine.prototype.mine = function () { return this.v._engine === this; };
  HTMLEngine.prototype.name = 'html5';
  HTMLEngine.prototype.open = function (url, opts) {
    var v = this.v, self = this;
    v._engine = self;
    v.style.display = 'block';
    return new Promise(function (resolve, reject) {
      var done = false;
      v.oncanplay = function () {
        if (done || !self.mine()) return; done = true;
        if (opts.mark) opts.mark('prepared');
        if (opts.onReady) { try { opts.onReady(); } catch (e) { B.log('onReady', e); } }
        if (opts.start && opts.start > 5 && !opts.live) { try { v.currentTime = opts.start; } catch (e) { /* stil */ } }
        var p = v.play();
        if (p && p.then) p.then(resolve, function () { resolve(); }); else resolve();
      };
      // build 97: open() overschreef de foutafhandeling van de engine; fouten ná de start gaan nu ook naar ev.error
      // (nieuwe poging of reservebron in de speler, ook in het voorbeeld en de multiview-tegels)
      v.onerror = function () {
        if (!self.mine()) return;
        if (!done) { done = true; reject(new Error('MEDIA_ERR')); return; }
        if (!self.stopped) self.ev.error('MEDIA_ERR_' + ((v.error && v.error.code) || 0));
      };
      self.stopped = false;
      v.src = url;
      v.load();
    });
  };
  HTMLEngine.prototype.play = function () { var p = this.v.play(); if (p && p.catch) p.catch(B.noop); };
  HTMLEngine.prototype.pause = function () { this.v.pause(); };
  HTMLEngine.prototype.stop = function () {
    // build 97: removeAttribute('src') + load() geeft soms zelf een fout; die telt niet meer
    this.stopped = true;
    if (this.v._engine && this.v._engine !== this) return;
    try { this.v.pause(); this.v.removeAttribute('src'); this.v.load(); } catch (e) { /* stil */ }
    if (!this.own) { this.v.style.display = 'none'; this.setAspect('fit'); }
  };
  // build 102: snelheid, beeldverhouding (object-fit), live-venster (seekable) en ondertiteltiming (cues verschuiven)
  HTMLEngine.prototype.setRate = function (r) { try { this.v.playbackRate = r; return true; } catch (e) { return false; } };
  HTMLEngine.prototype.setAspect = function (a) {
    var st = this.v.style;
    st.objectFit = a === 'fill' ? 'cover' : (a === 'r169' || a === 'r43' ? 'fill' : '');
    st.left = a === 'r43' ? '240px' : '';
    st.width = a === 'r43' ? '1440px' : '';
  };
  HTMLEngine.prototype.liveWindow = function () {
    var v = this.v;
    if (isFinite(v.duration) || !v.seekable || !v.seekable.length) return null;
    var a = v.seekable.start(0), b = v.seekable.end(v.seekable.length - 1);
    return b > a ? { start: a, end: b } : null;
  };
  HTMLEngine.prototype.shiftText = function (delta) {
    var tt = this.v.textTracks;
    if (!tt || !delta) return;
    for (var k = 0; k < tt.length; k++) {
      var cues = tt[k].cues;
      if (!cues) continue;
      for (var c = 0; c < cues.length; c++) { try { cues[c].startTime += delta; cues[c].endTime += delta; } catch (e) { /* stil */ } }
    }
  };
  HTMLEngine.prototype.setRect = B.noop;
  HTMLEngine.prototype.time = function () { return this.v.currentTime || 0; };
  HTMLEngine.prototype.duration = function () { var d = this.v.duration; return isFinite(d) ? d : 0; };
  HTMLEngine.prototype.seek = function (s) { try { this.v.currentTime = Math.max(0, s); } catch (e) { /* stil */ } };
  Object.defineProperty(HTMLEngine.prototype, 'playing', { get: function () { return !this.v.paused && !this.v.ended; } });
  HTMLEngine.prototype.tracks = function () {
    var out = { audio: [], text: [] };
    var at = this.v.audioTracks;
    if (at) for (var i = 0; i < at.length; i++) { out.audio.push({ index: i, lang: at[i].language, label: langName(at[i].language) || at[i].label || (L('Audio') + ' ' + (i + 1)) }); if (at[i].enabled) out.currentAudio = i; }
    var tt = this.v.textTracks;
    if (tt) for (var j = 0; j < tt.length; j++) { out.text.push({ index: j, lang: tt[j].language, label: langName(tt[j].language) || tt[j].label || (L('Subtitles') + ' ' + (j + 1)) }); if (tt[j].mode === 'showing') out.currentText = j; }
    return out;
  };
  HTMLEngine.prototype.selectAudio = function (i) { var at = this.v.audioTracks; if (at) for (var k = 0; k < at.length; k++) at[k].enabled = k === i; };
  HTMLEngine.prototype.selectText = function (i) { var tt = this.v.textTracks; if (tt) for (var k = 0; k < tt.length; k++) tt[k].mode = k === i ? 'showing' : 'disabled'; };

  B.engines = { AV: AVEngine, HTML: HTMLEngine, hasAVPlay: hasAVPlay };

  /** Naam van een taalcode van een spoor (nl, nld, dut, eng, …) in de taal van de app; '' als onbekend. */
  function langName(code) { return B.clang.name(code); }

  /**
   * build 101: extra_info van AVPlay lezen. Per tv-model/firmware verschillend: een JSON-tekst, al een object, of tekst
   * die geen geldige JSON is (sleutels zonder aanhalingstekens). Taal staat in track_lang (ondertitels), language
   * (audio) of soms lang/Language. kind = soort ondertitel (SRT, TTML, WebVTT…) voor als de taal onbekend is.
   */
  var KINDS = { srt: 'SRT', subrip: 'SRT', ttml: 'TTML', webvtt: 'WebVTT', vtt: 'WebVTT', ass: 'ASS', ssa: 'SSA',
    dvbsub: 'DVB', dvb: 'DVB', pgs: 'PGS', vobsub: 'VobSub', smi: 'SAMI', sami: 'SAMI', cea608: 'CC', cea708: 'CC', tx3g: 'Text' };
  function trackInfo(raw) {
    var extra = {};
    if (raw && typeof raw === 'object') extra = raw;
    else if (raw) { try { extra = JSON.parse(raw); } catch (e) { extra = {}; } }
    var lang = '';
    ['track_lang', 'language', 'lang', 'Language', 'TrackLang', 'track_language'].some(function (k) {
      if (extra && typeof extra[k] === 'string' && extra[k].trim()) { lang = extra[k].trim(); return true; }
      return false;
    });
    var text = typeof raw === 'string' ? raw : '';
    if (!lang && text) {
      var m = /(?:track_lang|language|lang)["']?\s*[:=]\s*["']?([A-Za-z]{2,3}(?:[-_][A-Za-z]{2,4})?)/i.exec(text);
      if (m) lang = m[1];
    }
    var fourcc = String((extra && (extra.fourCC || extra.fourcc || extra.subtitle_type || extra.codec)) || '');
    if (!fourcc && text) { var f = /(?:fourCC|codec)["']?\s*[:=]\s*["']?([A-Za-z0-9_-]{2,12})/i.exec(text); if (f) fourcc = f[1]; }
    var kind = KINDS[fourcc.toLowerCase().replace(/[^a-z0-9]/g, '')] || '';
    return { extra: extra || {}, lang: lang, kind: kind };
  }

  // MARK: spelerscherm

  /**
   * B.player.open(item, ctx)
   * item: {type: 'live'|'movie'|'episode', id, title, subtitle, streamURL, stalkerCmd, artwork, seriesID, season, episode, channel}
   * ctx: {channels: [...], index, episodes: [...], series, resume: seconden|null, sourceId}
   */
  B.player = {};
  /**
   * build 102: ondertitelgrootte (settings.shared.subtitleSize: small / medium / large, = iOS en Android; oud: 0/1/2).
   * Zonder instelling medium (de grootte van vóór build 102).
   */
  B.player.SIZES = ['small', 'medium', 'large'];
  var SUB_PX = { small: 38, medium: 46, large: 58 };
  B.player.subtitleSize = function () {
    var v = String(D.shared('subtitleSize', 'medium')).toLowerCase();
    if (/^[0-2]$/.test(v)) v = B.player.SIZES[+v];
    return SUB_PX[v] ? v : 'medium';
  };
  B.player.subtitlePx = function () { return SUB_PX[B.player.subtitleSize()]; };
  B.player.sizeLabel = function (z) { return z === 'small' ? L('Small') : z === 'large' ? L('Large') : L('Medium'); };
  B.player.open = function (item, ctx) {
    ctx = ctx || {};
    if (B.preview) B.preview.stop();
    if (B.multiview && B.multiview.current) B.multiview.current.close();
    var profile = B.app.profile;
    var engine = null;
    var closed = false, overlayTimer = null, tickTimer = null, pingTimer = null;
    var watchedSeconds = 0, recorded = false, lastSave = 0, attempts = 0, triedBackup = false;
    var nextShown = false, nextDismissed = false, countdownTimer = null;
    var zapTimer = null, zapIndex = null, digitBuffer = '', digitTimer = null;
    var marker = null;
    var extCues = null, extOffset = 0, extTimer = null, scrobbled = false, stopSent = false;
    var startGen = 0, limited = false, t0 = 0, marks = {}, audioChosen = false, autoSubsDone = false, autoSubsRetried = false;
    // app naar de achtergrond: AVPlay is vrijgegeven; bij terugkomen opnieuw openen op deze plek
    var suspended = false, bgPos = 0, bgPaused = false, pauseAfterOpen = false;
    // build 97: laatst bekende afspeelpositie (voor "Opnieuw proberen" na een fout; engine.time() is dan vaak 0)
    var lastPos = 0, markerTimer = null, markerScreen = null;
    // build 102: opties (snelheid, beeld, slaaptimer) gelden voor deze spelersessie, ook na zappen of de volgende aflevering
    var rate = 1, aspect = 'fit', sleepAt = 0, sleepEnd = false, sleepWarned = false;
    // build 102: timing van ondertitels uit de stream (AVPlay: alleen later tonen, met een wachtrij op mediatijd)
    var subDelay = 0, subQueue = [], subTimer = null;
    // build 102: live terugspoelen (= canTimeshift op tvOS): shiftStart = kloktijd (ms) waar de terugkijkstream begint
    var shiftStart = null, shiftTarget = null, shiftTimer = null, livePausedAt = 0, liveBtn = null;

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
    /** Terugkijken: een opname van een programma (doorspoelen kan, voortgang wordt niet bewaard). */
    function isCatchup() { return item.type === 'catchup'; }

    // MARK: overlay

    function showOverlay(focusPlay) {
      overlay.classList.add('show');
      el.classList.add('ov');
      clearTimeout(overlayTimer);
      overlayTimer = setTimeout(hideOverlay, 6000);
      if (focusPlay || !btnRow.contains(U.focused)) { var b = btnRow.querySelector('.f'); if (b) U.focus(b); }
    }
    function hideOverlay() {
      if (errorBox.style.display !== 'none') return;
      overlay.classList.remove('show');
      el.classList.remove('ov');
      if (btnRow.contains(U.focused)) { U.focused.classList.remove('focused'); U.focused = null; }
    }
    function overlayVisible() { return overlay.classList.contains('show'); }

    function renderInfo() {
      title.textContent = isLive() && item.channel ? ((item.channel.number ? item.channel.number + '  ' : '') + item.channel.name) : item.title;
      if (isCatchup()) title.textContent = '⟲ ' + item.title;
      sub.textContent = item.subtitle || '';
      U.clear(epg);
      if (isLive() && item.channel) {
        var nn = Lib.nowNext(item.channel);
        if (nn.now) epg.appendChild(h('div', [h('b', L('Now') + '  '), B.fmtTime(nn.now.start) + '–' + B.fmtTime(nn.now.end) + '  ' + nn.now.title]));
        if (nn.next) epg.appendChild(h('div.dim', [h('b', L('Next') + '  '), B.fmtTime(nn.next.start) + '  ' + nn.next.title]));
      }
      var keep = U.focused && btnRow.contains(U.focused) ? U.focused._k : null;
      U.clear(btnRow);
      btnRow._shift = 0; btnRow.style.transform = '';
      // build 102: meer knoppen dan er passen (live met terugspoelen): de rij schuift mee met de focus
      function add(b, k) { b._k = k; b.onFocus = function () { fitRow(b); }; btnRow.appendChild(b); }
      add(U.button('', togglePlay, { icon: engine && !engine.playing ? 'play' : 'pause', cls: 'round pl-play' }), 'play');
      if (!isLive()) add(U.button('', function () { jump(-10); }, { icon: 'replay', cls: 'round' }), 'back10');
      // build 102: live pauzeren en terugspoelen als de zender terugkijken heeft of de stream een live-venster
      liveBtn = null;
      if (isLive() && canTimeshift()) {
        add(U.button('\u221210', function () { liveJump(-10); }, { icon: 'replay' }), 'lback');
        add(U.button('+30', function () { liveJump(30); }, { icon: 'next' }), 'lfwd');
        liveBtn = U.button(L('LIVE'), function () { goLive(); }, { icon: 'live', cls: 'pl-golive' });
        add(liveBtn, 'golive');
        updateLive();
      }
      add(U.button(L('Audio'), chooseAudio, { icon: 'audio' }), 'audio');
      add(U.button(L('Subtitles'), chooseText, { icon: 'subs' }), 'subs');
      add(U.button(L('Options'), function () { optionsPanel(); }, { icon: 'gear' }), 'opts');
      if (item.type === 'episode' && nextEpisode()) add(U.button(L('Next episode'), function () { playNext(); }, { icon: 'next' }), 'next');
      if (item.type === 'episode' && item.seriesID && Lib.source) add(U.button(L('Intro & credits'), function () { markerPanel(); }, { icon: 'flag' }), 'intro');
      if (isLive() && item.channel) {
        var nn = Lib.nowNext(item.channel);
        if (nn.now && Lib.canCatchup(item.channel, nn.now)) add(U.button(L('From the start'), function () { B.player.playCatchup(item.channel, nn.now, ctx.channels); }, { icon: 'replay' }), 'cu');
        add(U.button(L('Other source'), function () { chooseBackup(); }, { icon: 'layers' }), 'backup');
        if ((ctx.channels || []).length > 1) add(U.button(L('Multiview'), function () { var list = ctx.channels, idx = ctx.index || 0; close(); B.multiview.open(list, idx); }, { icon: 'grid' }), 'mv');
      }
      if (keep) { var again = null; Array.prototype.forEach.call(btnRow.children, function (b) { if (b._k === keep) again = b; }); if (again) { U.focus(again, true); fitRow(again); } }
    }
    function fitRow(b) {
      var w = btnRow.parentNode ? btnRow.parentNode.clientWidth : 0;
      if (!w) return;
      var cur = btnRow._shift || 0, left = b.offsetLeft, right = left + b.offsetWidth;
      if (right - cur > w) cur = right - w;
      if (left < cur) cur = left;
      if (cur !== (btnRow._shift || 0)) { btnRow._shift = cur; btnRow.style.transform = cur ? 'translateX(' + (-cur) + 'px)' : ''; }
    }

    function updateTime() {
      if (!engine) return;
      var t = engine.time(), d = engine.duration();
      if (isLive()) {
        var nn = item.channel ? Lib.nowNext(item.channel) : {};
        // build 102: achter live (terugspoelen of pauze): de balk staat op de plek die nu speelt, rechts −mm:ss
        var behind = behindLive();
        var wall = Date.now() - behind * 1000;
        if (nn.now) {
          var frac = (wall - nn.now.start) / (nn.now.end - nn.now.start);
          bar.firstChild.style.width = (B.clamp(frac, 0, 1) * 100) + '%';
          tCur.textContent = B.fmtTime(nn.now.start); tDur.textContent = behind > 5 ? '\u2212' + B.fmtDur(behind) : B.fmtTime(nn.now.end);
        } else { bar.firstChild.style.width = '100%'; tCur.textContent = L('LIVE'); tDur.textContent = behind > 5 ? '\u2212' + B.fmtDur(behind) : ''; }
        updateLive();
        return;
      }
      bar.firstChild.style.width = (d > 0 ? B.clamp(t / d, 0, 1) * 100 : 0) + '%';
      tCur.textContent = B.fmtDur(t);
      tDur.textContent = d > 0 ? '-' + B.fmtDur(d - t) : '';
    }

    // MARK: afspelen

    /** Tijden van Afspelen tot eerste beeld bijhouden en loggen (B.player.timings voor tests). */
    function mark(name) {
      if (marks[name] != null) return;
      marks[name] = Date.now() - t0;
      B.log('start ' + name + ' ' + marks[name] + ' ms');
      B.player.timings = marks;
    }
    /**
     * Afspelen. Alles wat niet nodig is voor het eerste beeld loopt ernaast: stream_start (tegelijk kijken),
     * Trakt, ondertitels en de taalkeuze wachten niet; de URL gaat meteen naar de speler (prepareAsync).
     */
    function start(resumeAt) {
      var gen = ++startGen;
      lastPos = !isLive() && resumeAt > 0 ? resumeAt : 0;
      t0 = Date.now(); marks = {}; limited = false; audioChosen = false; autoSubsDone = false; autoSubsRetried = false;
      errorBox.style.display = 'none';
      spinner.style.display = 'flex';
      nextShown = false; nextDismissed = false; nextCard.style.display = 'none';
      clearInterval(countdownTimer);
      subtitle.textContent = '';
      marker = item.type === 'episode' && item.seriesID && Lib.source ? D.introMarker(Lib.source.id, item.seriesID) : null;
      renderInfo();
      showOverlay(true);
      clearExtSubs();
      scrobbled = false; stopSent = false;
      // build 102: niets van de vorige titel of zender meenemen (timeshift, pauze, ondertiteltiming)
      shiftStart = null; shiftTarget = null; livePausedAt = 0; clearTimeout(shiftTimer);
      subQueue = []; subDelay = 0; sleepWarned = false;
      applySubSize();
      // limiet van de IPTV-provider (alleen Xtream) alvast ophalen; blokkeert niets
      B.providers.providerLimit.prime(Lib.source);
      // tegelijk kijken: op de achtergrond; bij "te veel schermen" stopt de speler alsnog
      streamStart().then(function (ok) {
        mark('stream_start');
        if (ok === false && gen === startGen) limited = true;
      }, B.noop);
      var resolve = isCatchup() ? (item.streamURL ? Promise.resolve(item.streamURL) : Lib.catchupURL(item.channel, item.program).then(function (u) { item.streamURL = u; return u; }))
        : Lib.resolveURL(item, isLive());
      resolve.then(function (url) {
        if (closed || gen !== startGen || limited) return;
        if (!url) throw new Error('no_url');
        mark('url');
        return openURL(url, resumeAt);
      }).catch(function (e) { if (gen === startGen) fail(e); });
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
        // build 97: alleen fouten van de engine die nu speelt (een oude poging telt niet meer)
        error: function (msg) { if (engine === my && !closed) fail(new Error(msg)); },
        // build 102: via de wachtrij (ondertiteltiming); zonder vertraging meteen
        subtitle: function (t) { if (engine === my) embeddedSub(t); }
      };
      var useAV = hasAVPlay() && !item.forceHTML;
      engine = useAV ? new AVEngine(events) : new HTMLEngine(events);
      var my = engine;
      return engine.open(url, { live: isLive(), start: resumeAt || 0, mark: mark, onReady: function () { if (engine === my) applyTrackPrefs(); } }).then(function () {
        if (closed || limited || suspended) { my.stop(); return; }
        if (engine !== my) return;
        mark('playing');
        attempts = 0;
        spinner.style.display = 'none';
        if (pauseAfterOpen) { pauseAfterOpen = false; my.pause(); }
        // build 102: gekozen snelheid en beeldverhouding ook op de nieuwe engine (zappen, volgende aflevering)
        if (aspect !== 'fit') my.setAspect(aspect);
        if (rate !== 1) applyRate(true);
        renderInfo();
        showOverlay(true);
        // na het eerste beeld, niets hiervan houdt de start op
        setTimeout(function () {
          if (closed || engine !== my) return;
          if (!audioChosen) applyTrackPrefs();
          if (!scrobbled) { scrobbled = true; scrobble('start'); }
          autoSubtitles();
        }, 1200);
        // HLS: sommige audiosporen verschijnen pas na een paar segmenten
        setTimeout(function () { if (!closed && engine === my && !audioChosen) applyTrackPrefs(); }, 5000);
      }, function (e) {
        // AVPlay lukt niet: één keer met HTML5 <video> proberen (vooral MP4/HLS)
        if (closed || suspended || engine !== my) return;
        if (useAV && !item.forceHTML) { item.forceHTML = true; return openURL(url, resumeAt); }
        throw e;
      });
    }
    // MARK: taal (gewenste audiotaal, taal van de titel, automatische ondertitels)

    /** Taal van wat er speelt volgens de naam/categorie/TMDB: film uit de bibliotheek, of de serie. */
    function contentLang() {
      if (item.type === 'movie') { var hit = Lib.lib && Lib.lib.byId ? Lib.lib.byId[item.id] : null; return B.clang.forItem(hit ? hit.item : item, false); }
      if (item.type === 'episode' && ctx.series) return B.clang.forItem(ctx.series, true);
      return null;
    }
    /** Gewenste audiotaal kiezen (settings.shared.audioLanguage, standaard de app-taal; "stream" = niets kiezen). */
    function applyTrackPrefs() {
      if (!engine || audioChosen) return;
      var pref = B.clang.preferredAudio();
      if (!pref) return;
      var tr = engine.tracks();
      if (tr.audio.length < 2) return;
      for (var i = 0; i < tr.audio.length; i++) {
        if (B.clang.norm(tr.audio[i].lang) === pref) {
          if (tr.audio[i].index !== tr.currentAudio) { engine.selectAudio(tr.audio[i].index); B.log('audio → ' + pref); }
          audioChosen = true;
          return;
        }
      }
    }
    /** Taal van het audiospoor dat nu speelt; anders de taal van de titel. */
    function currentAudioLang() {
      var tr = engine ? engine.tracks() : { audio: [] };
      var cur = null;
      tr.audio.forEach(function (t) { if (t.index === tr.currentAudio) cur = t; });
      if (!cur && tr.audio.length === 1) cur = tr.audio[0];
      var lg = cur ? B.clang.norm(cur.lang) : '';
      if (lg) return lg;
      var cl = contentLang();
      return cl && !cl.m ? (cl.a || '') : '';
    }
    /**
     * Automatische ondertitels (settings.shared.autoSubtitles: off | whenNeeded | always).
     * whenNeeded: alleen als de audio niet in de gewenste taal is. Eerst een ingebouwd spoor in die taal, anders
     * OpenSubtitles op de achtergrond; aanzetten met een melding in beeld. Houdt de start nooit op.
     */
    function autoSubtitles() {
      if (autoSubsDone || !engine || isLive() || isCatchup() || extCues) return;
      var tr = engine.tracks();
      // build 91: HLS meldt ondertitelsporen soms pas later: dan één keer opnieuw kijken (het afspelen wacht nergens op)
      if (!tr.text.length && !autoSubsRetried) {
        autoSubsRetried = true;
        var my0 = engine;
        setTimeout(function () { if (!closed && engine === my0) autoSubtitles(); }, 4000);
        return;
      }
      autoSubsDone = true;
      var mode = B.clang.autoSubtitles();
      var want = B.clang.preferredSubtitle();
      var audio = currentAudioLang();
      var cl = contentLang();
      var needed = mode === 'always' || (mode === 'whenNeeded' && !!audio && audio !== want);
      // ondertitels in de gewenste taal zitten al in het beeld ("NL SUBS")
      if (cl && cl.s === want) needed = false;
      // build 91: geen automatische ondertitels nodig: de gewone standaard-ondertiteltaal voor sporen in de stream (zoals Android)
      if (!needed) { applyStreamSubtitleDefault(tr); return; }
      if (tr.currentText != null) { var on = null; tr.text.forEach(function (t) { if (t.index === tr.currentText) on = t; }); if (on && B.clang.norm(on.lang) === want) return; }
      for (var i = 0; i < tr.text.length; i++) {
        if (B.clang.norm(tr.text[i].lang) === want) {
          engine.selectText(tr.text[i].index);
          U.toast(L('Subtitles turned on automatically: %@', langName(want)), 4000);
          return;
        }
      }
      var OS = B.opensubtitles;
      if (!OS.ready() || !OS.isLoggedIn()) return;
      var my = engine, id = item.id;
      osIds().then(function (ids) {
        if (closed || engine !== my || item.id !== id || extCues) return [];
        return OS.search(osQuery(ids), [want]);
      }).then(function (list) {
        list = list.filter(function (x) { return B.clang.norm(x.language) === want; });
        if (!list.length || closed || engine !== my || item.id !== id || extCues) return null;
        return OS.download(list[0]).then(function (cues) {
          if (!cues.length || closed || engine !== my || item.id !== id || extCues) return;
          showExtCues(cues);
          U.toast(L('Subtitles turned on automatically: %@', langName(want)), 4000);
        });
      }).catch(function (e) { B.log('auto-ondertitels', e && e.message); });
    }
    /**
     * build 91: standaard-ondertiteltaal in de stream (prefs.streamSubtitleLanguage / settings.shared.subtitleLanguage)
     * als er geen automatische ondertitels nodig zijn: oud "off" = uit, '' = niets kiezen, anders dat spoor.
     */
    function applyStreamSubtitleDefault(tr) {
      var raw = B.clang.streamSubtitleSetting();
      if (raw === 'off') { if (tr.currentText != null) engine.selectText(null); return; }
      var code = B.clang.norm(raw);
      if (!code) return;
      for (var i = 0; i < tr.text.length; i++) {
        if (B.clang.norm(tr.text[i].lang) === code) {
          if (tr.text[i].index !== tr.currentText) engine.selectText(tr.text[i].index);
          return;
        }
      }
    }
    /**
     * build 91: ids voor OpenSubtitles: IMDb (eigen id, de Trakt-index, anders zoeken bij Trakt), en zonder IMDb-id
     * het TMDB-id uit de cache van de leeftijdskeuring (agerating.js; zo nodig één keer opgezocht). Promise {imdb, tmdb}.
     */
    function osIds() {
      var isEp = item.type === 'episode';
      var series = ctx.series || null;
      var quick = item.imdbID || (isEp ? B.trakt.imdbFor(series || { title: item.title }, true) : B.trakt.imdbFor({ title: item.title, year: B.int(item.subtitle) }, false));
      var pImdb = quick ? Promise.resolve(quick) : (B.trakt.resolveImdb ? B.trakt.resolveImdb(item) : Promise.resolve(null));
      return pImdb.then(function (imdb) {
        var AR = B.ageRatings;
        if (imdb || !AR || !AR.lookup) return { imdb: imdb || null, tmdb: null };
        var hit = !isEp && Lib.lib && Lib.lib.byId ? Lib.lib.byId[item.id] : null;
        var x = isEp ? (series || { title: item.title, year: item.year || null }) : (hit ? hit.item : { title: item.title, year: item.year || B.int(item.subtitle) });
        return AR.lookup(x, isEp).then(function (r) {
          return { imdb: null, tmdb: r && r.found !== false && r.id != null ? r.id : null };
        }, function () { return { imdb: null, tmdb: null }; });
      }, function () { return { imdb: null, tmdb: null }; });
    }
    /** build 91: zoekvraag voor OS.search met deze ids. */
    function osQuery(ids) {
      var isEp = item.type === 'episode';
      return { imdb: ids.imdb, tmdb: ids.tmdb, title: item.title, year: item.type === 'movie' ? B.int(item.subtitle) : null,
        season: isEp ? item.season : null, episode: isEp ? item.episode : null };
    }
    function showExtCues(cues) {
      try { engine.selectText(null); } catch (e) { /* stil */ }
      extCues = cues; extOffset = 0;
      clearInterval(extTimer);
      extTimer = setInterval(renderExtSub, 250);
      renderExtSub();
    }

    function fail(e) {
      if (closed || limited || suspended) return;
      B.log('player error', e && e.message);
      spinner.style.display = 'none';
      // build 102: terugkijkstream (live terugspoelen) lukt niet: de nieuwe poging is gewoon live
      if (shiftStart != null) { shiftStart = null; livePausedAt = 0; }
      // één stille nieuwe poging
      if (attempts < 1) {
        attempts++;
        var at = isLive() ? 0 : currentPos();
        var g = startGen;
        setTimeout(function () {
          if (closed || suspended || g !== startGen) return;
          // terugkijken heeft al een eigen URL; resolveURL kent alleen live, films en afleveringen
          var again = isCatchup() && item.streamURL ? Promise.resolve(item.streamURL) : Lib.resolveURL(item, isLive());
          again.then(function (u) { if (!closed && !suspended && g === startGen) return openURL(u, at); }).catch(fail);
        }, 1500);
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
    /** build 97: positie om te hervatten: de engine als die nog iets zinnigs meldt, anders de laatst bekende. */
    function currentPos() {
      var t = 0;
      try { t = engine ? engine.time() : 0; } catch (e) { t = 0; }
      if (t > 0) lastPos = t;
      return lastPos || 0;
    }
    function showError() {
      if (closed) return;
      // build 97: de plek onthouden vóór het stoppen, zodat "Opnieuw proberen" daar verder gaat (niet bij 0)
      var resumePos = isLive() ? 0 : (currentPos() || ctx.resume || 0);
      if (engine) engine.stop();
      U.clear(errorBox);
      errorBox.appendChild(h('h2', L("Can't play this")));
      var msg = h('p', isLive() ? L('This channel is not available right now.') : L('This title could not be loaded.'));
      errorBox.appendChild(msg);
      // provider vol (alle verbindingen van het Xtream-abonnement in gebruik)? Dan die melding i.p.v. de algemene
      var g = startGen;
      B.providers.providerLimit.failureMessage(Lib.source).then(function (m) {
        if (m && !closed && g === startGen && errorBox.style.display !== 'none' && msg.parentNode === errorBox) msg.textContent = m;
      }, B.noop);
      var row = h('div.modal-buttons');
      row.appendChild(U.button(L('Try again'), function () { attempts = 0; triedBackup = false; start(resumePos); }, { primary: true }));
      if (isLive()) row.appendChild(U.button(L('Other source'), chooseBackup, { icon: 'layers' }));
      row.appendChild(U.button(L('Close'), close));
      errorBox.appendChild(row);
      errorBox.style.display = 'flex';
      overlay.classList.remove('show');
      U.focus(row.firstChild);
    }

    function togglePlay() {
      if (!engine) return;
      if (engine.playing) {
        engine.pause(); scrobble('pause');
        // build 102: live gepauzeerd: onthouden wanneer (verder kijken via terugkijken, = tvOS pausedAt)
        if (isLive() && shiftStart == null && !livePausedAt) livePausedAt = Date.now();
      } else if (isLive() && livePausedAt && shiftStart == null && Date.now() - livePausedAt > 10000 && !hasLiveWindow() && canShiftCatchup()) {
        openShift(livePausedAt);
      } else {
        engine.play(); scrobble('start');
        if (rate !== 1) applyRate(false);
        // zonder terugkijken en zonder live-venster gaat de stream gewoon verder (live)
        if (isLive() && shiftStart == null && !hasLiveWindow()) livePausedAt = 0;
      }
      renderInfo();
      var pb = btnRow.querySelector('.pl-play');
      if (pb) U.focus(pb);
      showOverlay();
    }
    function jump(s) {
      if (!engine || isLive()) return;
      subQueue = [];
      if (extCues) renderExtSub();
      var d = engine.duration();
      var t = B.clamp(engine.time() + s, 0, d > 0 ? d - 2 : engine.time() + s);
      engine.seek(t);
      updateTime();
      showOverlay();
    }

    // MARK: sporen

    /** Andere taalversies van wat er speelt (film, of de serie bij een aflevering). */
    function versions() {
      if (item.type === 'movie') { var hit = Lib.lib && Lib.lib.byId ? Lib.lib.byId[item.id] : null; return hit ? B.clang.versions(hit.item, 'movie') : []; }
      if (item.type === 'episode' && ctx.series) return B.clang.versions(ctx.series, 'series');
      return [];
    }
    /**
     * Audio: meerdere sporen → kiezen (plus andere taalversies). Eén spoor → de taal ervan, "Deze stream heeft één
     * audiotaal" en de andere taalversies om op dezelfde plek verder te kijken.
     */
    function chooseAudio() {
      if (!engine) return;
      var tr = engine.tracks();
      var vs = versions();
      if (tr.audio.length > 1) {
        var opts = tr.audio.map(function (t) { return { value: t.index, label: t.label }; });
        vs.slice(0, 6).forEach(function (v, i) { opts.push({ value: 'v' + i, label: L('Other language') + ': ' + v.label }); });
        U.choose(L('Audio'), opts, tr.currentAudio, function (v) {
          if (typeof v === 'string') { switchVersion(vs[B.int(v.slice(1))]); return; }
          audioChosen = true;
          engine.selectAudio(v);
        });
        return;
      }
      var lg = currentAudioLang();
      var name = lg ? langName(lg) : (tr.audio[0] ? tr.audio[0].label : L('Audio'));
      var buttons = vs.slice(0, 4).map(function (v) { return { label: v.label, icon: 'globe', fn: function () { switchVersion(v); } }; });
      buttons.push({ label: L('Close') });
      U.modal({
        title: L('Audio') + ': ' + name,
        text: L('This stream has one audio language') + (vs.length ? '. ' + L('Watch another language version from the same moment:') : '.'),
        buttons: buttons
      });
    }
    /** Andere taalversie kiezen en op dezelfde plek verder kijken. */
    function switchVersion(v) {
      if (!v || !engine) return;
      var pos = isLive() ? 0 : engine.time();
      var target = v.item;
      U.toast(L('Switching to %@…', v.label));
      if (item.type === 'movie') {
        saveProgress(true);
        if (scrobbled) scrobble('stop');
        stopEngine(false);
        item = movieItem(target);
        watchedSeconds = 0; recorded = false; attempts = 0;
        start(pos);
        return;
      }
      // aflevering: dezelfde aflevering (seizoen + nummer) in de andere serie zoeken
      var season = item.season, epn = item.episode;
      Lib.episodes(target).then(function (r) {
        var eps = (r && r.episodes) || [];
        var hit = null;
        eps.forEach(function (e) { if (e.season === season && e.number === epn) hit = e; });
        if (!hit || closed) { U.toast(L('No other language version found.')); return; }
        saveProgress(true);
        if (scrobbled) scrobble('stop');
        stopEngine(false);
        ctx.series = target; ctx.episodes = eps;
        item = episodeItem(hit, target);
        watchedSeconds = 0; recorded = false; attempts = 0;
        start(pos);
      }, function () { U.toast(L('Something went wrong. Please try again.')); });
    }
    function chooseText() {
      if (!engine) return;
      var tr = engine.tracks();
      var opts = [{ value: -1, label: L('Off') }].concat(tr.text.map(function (t) { return { value: t.index, label: t.label }; }));
      if (!isLive()) opts.push({ value: -2, label: 'OpenSubtitles…' });
      // build 102: timing ook voor ondertitels uit de stream (niet alleen OpenSubtitles)
      if (extCues || tr.currentText != null) opts.push({ value: -3, label: L('Subtitle timing') + ' (' + fmtOffset(extCues ? extOffset : subDelay) + ')' });
      var cur = extCues ? -2 : (tr.currentText == null ? -1 : tr.currentText);
      U.choose(L('Subtitles'), opts, cur, function (v) {
        if (v === -2) { openSubtitles(); return; }
        if (v === -3) { chooseOffset(); return; }
        autoSubsDone = true;
        clearExtSubs();
        engine.selectText(v < 0 ? null : v);
      });
    }
    function fmtOffset(x) { return x === 0 ? L('In sync') : (x > 0 ? '+' : '\u2212') + Math.abs(x) + ' s'; }
    /**
     * build 102: ondertiteltiming −10…+10 s (= tvOS subtitleDelay; + = later). OpenSubtitles en HTML5-sporen kunnen
     * beide kanten op; ondertitels die AVPlay uit de stream levert alleen later (eerder dan de tv ze geeft kan niet).
     */
    function chooseOffset() {
      if (!engine) return;
      var ext = !!extCues, onlyLater = !ext && !engine.shiftText;
      var cur = ext ? extOffset : subDelay;
      var o = [];
      for (var x = onlyLater ? 0 : -10; x <= 10; x += 0.5) o.push({ value: x, label: fmtOffset(x) });
      U.choose(L('Subtitle timing'), o, cur, function (v) {
        if (ext) { extOffset = v; renderExtSub(); return; }
        setSubDelay(v);
      });
      U.toast(onlyLater ? L('Subtitles from the stream can only be shown later, not earlier.') : L('Subtitles too early or too late? Shift them in steps of half a second.'), 5000);
    }
    function showSubText(t) { subtitle.innerHTML = B.esc(String(t || '').replace(/<[^>]+>/g, '')).replace(/\n/g, '<br>'); }
    /** build 102: tekst van AVPlay (onsubtitlechange): meteen, of na subDelay seconden mediatijd. */
    function embeddedSub(t) {
      if (!(subDelay > 0) || !engine) { showSubText(t); return; }
      subQueue.push({ at: engine.time() + subDelay, text: t });
      if (subQueue.length > 300) subQueue.shift();
      if (!subTimer) subTimer = setInterval(flushSubs, 200);
    }
    function flushSubs() {
      if (closed || !engine) return;
      var now = engine.time();
      while (subQueue.length && subQueue[0].at <= now) showSubText(subQueue.shift().text);
      if (!subQueue.length && !(subDelay > 0)) { clearInterval(subTimer); subTimer = null; }
    }
    function setSubDelay(v) {
      var delta = v - subDelay;
      subDelay = v;
      if (engine && engine.shiftText) engine.shiftText(delta);
      subQueue.forEach(function (c) { c.at += delta; });
      if (subQueue.length && !subTimer) subTimer = setInterval(flushSubs, 200);
    }

    // MARK: opties (build 102: snelheid, ondertitelgrootte, beeld, slaaptimer; = OptionsPanel op tvOS)

    var RATES = [0.75, 1, 1.25, 1.5, 2];
    var ASPECTS = ['fit', 'fill', 'r169', 'r43'];
    function rateLabel(r) { return r === 1 ? L('Normal') : String(r) + '\u00d7'; }
    function aspectLabel(a) { return a === 'fit' ? L('Fit') : a === 'fill' ? L('Fill') : a === 'r169' ? '16:9' : '4:3'; }
    function sleepLabel() {
      if (sleepEnd) return L('End of episode');
      if (sleepAt) return L('In %d minutes', Math.max(1, Math.ceil((sleepAt - Date.now()) / 60000)));
      return L('Off');
    }
    function applySubSize() {
      var px = B.player.subtitlePx();
      subtitle.style.fontSize = px + 'px';
      // HTML5-sporen tekent de browser zelf (::cue)
      var st = document.getElementById('bingr-cue-size');
      if (!st) { st = document.createElement('style'); st.id = 'bingr-cue-size'; document.head.appendChild(st); }
      st.textContent = '#html-video::cue { font-size: ' + Math.round(px * 0.8) + 'px; }';
    }
    function applyRate(quiet) {
      if (!engine || isLive()) return;
      if (!engine.setRate(rate) && rate !== 1) {
        rate = 1;
        if (!quiet) U.toast(L('This stream does not support another speed.'));
      }
    }
    function optionsPanel(focusKey) {
      if (!engine) return;
      var opts = [];
      if (!isLive()) opts.push({ value: 'speed', label: L('Speed') + ':  ' + rateLabel(rate) });
      opts.push({ value: 'size', label: L('Subtitle size') + ':  ' + B.player.sizeLabel(B.player.subtitleSize()) });
      opts.push({ value: 'aspect', label: L('Picture') + ':  ' + aspectLabel(aspect) });
      opts.push({ value: 'sleep', label: L('Sleep timer') + ':  ' + sleepLabel() });
      var s = U.choose(L('Options'), opts, null, function (v) {
        if (v === 'speed') {
          U.choose(L('Speed'), RATES.map(function (r) { return { value: r, label: rateLabel(r) }; }), rate, function (r) {
            rate = r; applyRate(false); optionsPanel('speed');
          });
        } else if (v === 'size') {
          var cur = B.player.subtitleSize();
          U.choose(L('Subtitle size'), B.player.SIZES.map(function (z) { return { value: z, label: B.player.sizeLabel(z) }; }), cur, function (z) {
            if (z !== cur) D.setShared('subtitleSize', z);
            applySubSize(); optionsPanel('size');
          });
        } else if (v === 'aspect') {
          U.choose(L('Picture'), ASPECTS.map(function (a) { return { value: a, label: aspectLabel(a) }; }), aspect, function (a) {
            aspect = a; if (engine) engine.setAspect(a); optionsPanel('aspect');
          });
        } else if (v === 'sleep') {
          var so = [0, 15, 30, 45, 60, 90].map(function (m) { return { value: m, label: m ? L('In %d minutes', m) : L('Off') }; });
          if (item.type === 'episode' || item.type === 'movie') so.push({ value: -1, label: L('End of episode') });
          var now0 = sleepEnd ? -1 : (sleepAt ? null : 0);
          U.choose(L('Sleep timer'), so, now0, function (m) { setSleep(m); optionsPanel('sleep'); });
        }
      });
      if (focusKey) {
        var idx = 0;
        opts.forEach(function (o, i) { if (o.value === focusKey) idx = i; });
        var rows = s.el.querySelectorAll('.choice');
        if (rows[idx]) U.focus(rows[idx], true);
      }
    }
    /** Slaaptimer: m minuten (0 = uit, −1 = einde van de aflevering/film). */
    function setSleep(m) {
      sleepWarned = false;
      sleepEnd = m === -1;
      sleepAt = m > 0 ? Date.now() + m * 60000 : 0;
      B.log('slaaptimer ' + m);
    }
    /** Elke seconde: 30 s vooraf een melding, daarna pauzeren en de speler sluiten. */
    function checkSleep() {
      if (!engine || (!sleepAt && !sleepEnd)) return;
      var left = null;
      if (sleepAt) left = (sleepAt - Date.now()) / 1000;
      else if (!isLive()) { var d = engine.duration(); if (d > 60) left = d - engine.time(); }
      if (left == null) return;
      if (left <= 30 && !sleepWarned) { sleepWarned = true; U.toast(L('Sleep timer: playback stops in 30 seconds.'), 6000); }
      if (sleepAt && left <= 0) {
        sleepAt = 0;
        try { engine.pause(); } catch (e) { /* stil */ }
        close();
      }
    }

    // MARK: live terugspoelen (build 102, = canTimeshift / seek(by:) / goLive op tvOS)

    function canShiftCatchup() {
      var now = Date.now();
      return !!(item.channel && Lib.canCatchup(item.channel, { start: now - 60000, end: now + 3600000 }));
    }
    function liveWin() { return isLive() && shiftStart == null && engine && engine.liveWindow ? engine.liveWindow() : null; }
    function hasLiveWindow() { var w = liveWin(); return !!(w && w.end - w.start > 30); }
    function canTimeshift() { return isLive() && !!engine && (shiftStart != null || hasLiveWindow() || canShiftCatchup()); }
    /** Seconden achter live. */
    function behindLive() {
      if (!isLive() || !engine) return 0;
      if (shiftTarget != null) return Math.max(0, (Date.now() - shiftTarget) / 1000);
      if (shiftStart != null) return Math.max(0, (Date.now() - (shiftStart + engine.time() * 1000)) / 1000);
      var w = liveWin();
      if (w && w.end - w.start > 30) { var b = w.end - engine.time(); return b > 8 ? b : 0; }
      return livePausedAt ? (Date.now() - livePausedAt) / 1000 : 0;
    }
    function updateLive() {
      if (!liveBtn) return;
      var b = behindLive();
      var txt = b > 5 ? '\u2212' + B.fmtDur(b) + '  \u00b7  ' + L('Back to live') : L('LIVE');
      var lab = liveBtn.querySelector('.lbl') || liveBtn.lastChild;
      if (lab && lab.nodeType === 3) { if (lab.nodeValue !== txt) lab.nodeValue = txt; }
      else if (lab && lab.textContent !== txt) lab.textContent = txt;
      liveBtn.classList.toggle('behind', b > 5);
    }
    function liveJump(s) {
      if (!canTimeshift()) return;
      var w = liveWin();
      if (w && w.end - w.start > 30) {
        var t = B.clamp(engine.time() + s, w.start, w.end);
        engine.seek(w.end - t < 10 ? w.end : t);
        subQueue = []; updateTime(); showOverlay(); return;
      }
      var now = Date.now();
      var cur = shiftTarget != null ? shiftTarget : (shiftStart != null ? shiftStart + engine.time() * 1000 : (livePausedAt || now));
      var target = Math.min(cur + s * 1000, now);
      if (now - target < 10000) { goLive(); return; }
      // binnen de terugkijkstream die al speelt: gewoon spoelen
      if (shiftStart != null && shiftTarget == null) {
        var d = engine.duration(), off = (target - shiftStart) / 1000;
        if (off >= 0 && d > 0 && off < d - 5) { engine.seek(off); subQueue = []; updateTime(); showOverlay(); return; }
      }
      // anders na een korte pauze in de toetsen één nieuwe terugkijkstream (niet per druk)
      shiftTarget = target;
      clearTimeout(shiftTimer);
      shiftTimer = setTimeout(function () { var t2 = shiftTarget; shiftTarget = null; openShift(t2); }, 700);
      updateTime(); showOverlay();
    }
    function openShift(from) {
      if (closed || suspended || !item.channel || from == null) return;
      var gen = startGen, ch = item.channel;
      // Xtream: de terugkijk-URL begint op een hele minuut; daarna naar de juiste seconde spoelen
      var begin = Lib.source && Lib.source.kind === 'Xtream Codes' ? Math.floor(from / 60000) * 60000 : from;
      spinner.style.display = 'flex';
      Lib.catchupURL(ch, { start: begin, end: Date.now() + 3 * 3600000 }).then(function (u) {
        if (closed || suspended || gen !== startGen || item.channel !== ch) return;
        if (!u) { spinner.style.display = 'none'; U.toast(L('Something went wrong. Please try again.')); return; }
        shiftStart = begin; livePausedAt = 0; subQueue = [];
        B.log('timeshift ' + Math.round((Date.now() - from) / 1000) + ' s');
        return openURL(u, 0).then(function () {
          if (!closed && engine && shiftStart === begin && from - begin > 2000) engine.seek((from - begin) / 1000);
          updateTime();
        });
      }).catch(function (e) { if (gen === startGen) fail(e); });
    }
    function goLive() {
      clearTimeout(shiftTimer); shiftTarget = null;
      if (!engine || !isLive()) return;
      var w = liveWin();
      if (w && w.end - w.start > 30) { engine.seek(w.end); livePausedAt = 0; if (!engine.playing) engine.play(); renderInfo(); updateTime(); return; }
      if (shiftStart == null && !livePausedAt) { updateTime(); return; }
      shiftStart = null; livePausedAt = 0; subQueue = [];
      var gen = startGen;
      spinner.style.display = 'flex';
      Lib.resolveURL(item, true).then(function (u) {
        if (closed || gen !== startGen) return;
        if (!u) throw new Error('no_url');
        return openURL(u, 0);
      }).catch(function (e) { if (gen === startGen) fail(e); });
    }
    /** OpenSubtitles: zoeken (IMDb-id of titel), kiezen, downloaden en zelf tonen boven de video. */
    function openSubtitles() {
      var OS = B.opensubtitles;
      if (!OS.ready()) {
        // build 91: app-sleutels van de server nog niet binnen: eerst ophalen en dan opnieuw
        U.toast(L('Loading…'));
        B.account.fetchConfig().then(function (ok) {
          if (closed) return;
          if (ok && OS.ready()) openSubtitles(); else U.toast(L('Something went wrong. Please try again.'));
        }, function () { if (!closed) U.toast(L('Something went wrong. Please try again.')); });
        return;
      }
      if (!OS.isLoggedIn()) { U.toast(L('Sign in to OpenSubtitles under My Bingr → OpenSubtitles to download subtitles.'), 5000); return; }
      var lang = OS.defaultLanguage();
      U.toast(L('Searching…'));
      osIds().then(function (ids) { return OS.search(osQuery(ids), lang ? [lang] : []); }).then(function (list) {
        if (closed) return;
        if (!list.length) { U.toast(lang ? L('No subtitles found in %@.', langName(lang) || lang) : L('No subtitles found for this title.')); return; }
        U.choose('OpenSubtitles', list.slice(0, 30).map(function (x, i) {
          return { value: i, label: (langName(x.language) || x.language) + '  ·  ' + x.release + (x.hearingImpaired ? '  ·  SDH' : '') };
        }), null, function (i) {
          U.toast(L('Loading…'));
          OS.download(list[i]).then(function (cues) {
            if (closed) return;
            if (!cues.length) { U.toast(L('No subtitles found for this title.')); return; }
            showExtCues(cues);
            U.toast(L('Subtitles: %@', langName(list[i].language) || list[i].language));
          }, function (e) { U.toast(e.message || L('Something went wrong. Please try again.')); });
        });
      }, function (e) { U.toast(e.message || L('Something went wrong. Please try again.')); });
    }
    function renderExtSub() {
      if (!extCues || !engine) return;
      var t = B.subs.at(extCues, engine.time() - extOffset);
      var html = B.esc(t).replace(/\n/g, '<br>');
      if (subtitle._last !== html) { subtitle._last = html; subtitle.innerHTML = html; }
    }
    function clearExtSubs() {
      clearInterval(extTimer); extTimer = null;
      if (extCues) { extCues = null; subtitle.innerHTML = ''; subtitle._last = ''; }
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

    // MARK: Trakt

    function scrobble(action) {
      if (!engine || isLive() || isCatchup()) return;
      if (action === 'stop') { if (stopSent) return; stopSent = true; }
      var d = engine.duration(), t = engine.time();
      var prog = d > 0 ? t / d : 0;
      if (action === 'start' && prog <= 0) prog = 0.001;
      var it = item;
      B.trakt.scrobble(action, it, prog).then(function (r) {
        if (action === 'stop' && r === 'ok' && prog >= 0.8 && B.trakt.lastScrobble) U.toast(B.trakt.lastScrobble);
      });
    }

    // MARK: intro en aftiteling markeren (settings.shared.introMarkers, = IntroMarkers.kt)

    function markerPanel() {
      if (!engine || !Lib.source || !item.seriesID) return;
      var srcId = Lib.source.id, sid = item.seriesID;
      var m = D.introMarker(srcId, sid) || { introStart: null, introEnd: null, creditsStart: null };
      m = { introStart: m.introStart, introEnd: m.introEnd, creditsStart: m.creditsStart };
      if (engine.playing) engine.pause();
      var rows = h('div.mk-rows');
      var fields = [['introStart', L('Intro starts here')], ['introEnd', L('Intro ends here')], ['creditsStart', L('Credits start here')]];
      function fmt(v) { return v == null ? '—' : B.fmtDur(v); }
      function persist() {
        marker = (m.introStart == null && m.introEnd == null && m.creditsStart == null) ? null : { introStart: m.introStart, introEnd: m.introEnd, creditsStart: m.creditsStart };
        D.setIntroMarker(srcId, sid, m);
      }
      function draw(focusKey) {
        U.clear(rows);
        var target = null;
        fields.forEach(function (f) {
          var k = f[0];
          var val = h('div.mk-val', fmt(m[k]));
          var setB = U.button(L('Set'), function () { if (!engine) return; m[k] = Math.max(0, Math.round(engine.time() * 10) / 10); persist(); draw(k + ':set'); }, { cls: 'mk-set' });
          var minus = U.button('−5 s', function () { if (engine && m[k] != null) { m[k] = Math.max(0, m[k] - 5); engine.seek(m[k]); persist(); draw(k + ':minus'); } }, { cls: 'mk-adj' });
          var plus = U.button('+5 s', function () { if (engine && m[k] != null) { m[k] = m[k] + 5; engine.seek(m[k]); persist(); draw(k + ':plus'); } }, { cls: 'mk-adj' });
          var go = U.button('', function () { if (engine && m[k] != null) engine.seek(m[k]); }, { icon: 'play', cls: 'round mk-go' });
          setB._fk = k + ':set'; minus._fk = k + ':minus'; plus._fk = k + ':plus'; go._fk = k + ':go';
          [setB, minus, plus, go].forEach(function (b) { if (b._fk === focusKey) target = b; });
          rows.appendChild(h('div.mk-row.fm', [h('div.mk-label', f[1]), val, minus, setB, plus, go]));
        });
        if (target) U.focus(target, true);
      }
      var timeEl = h('div.mk-time');
      // build 97: stopt bij sluiten, bij de achtergrond (pauseForBackground) en als de engine weg is
      clearInterval(markerTimer);
      var tt = markerTimer = setInterval(function () {
        if (closed || suspended || !engine) return;
        try { timeEl.textContent = B.fmtDur(engine.time()) + (engine.duration() > 0 ? ' / ' + B.fmtDur(engine.duration()) : ''); } catch (e) { /* stil */ }
      }, 300);
      var clear = U.button(L('Clear'), function () { m = { introStart: null, introEnd: null, creditsStart: null }; persist(); draw(null); U.focus(rows.querySelector('.f')); });
      var done = U.button(L('Done'), function () { closePanel(); }, { primary: true });
      var box = h('div.mk-box', [h('h2', L('Intro & credits')), h('p.modal-text', L('Mark the intro and credits once; Bingr uses them for every episode of this series.')),
        timeEl, rows, h('div.modal-buttons.fm', [done, clear]), h('div.mk-hint.dim', L('⏪ / ⏩ on the remote: −10 s / +10 s'))]);
      var s = { modal: true, el: h('div.layer.modal.mk-layer', [box]) };
      function closePanel() { clearInterval(tt); if (markerTimer === tt) markerTimer = null; markerScreen = null; if (U.top() === s) U.pop(); if (engine && !engine.playing) engine.play(); renderInfo(); showOverlay(true); }
      s.onBack = closePanel;
      s.onKey = function (name) {
        if (!engine) return name === 'ff' || name === 'rw' || name === 'playpause' || name === 'play' || name === 'pause';
        if (name === 'ff' || name === 'rw') { engine.seek(Math.max(0, engine.time() + (name === 'ff' ? 10 : -10))); return true; }
        if (name === 'playpause' || name === 'play' || name === 'pause') { if (engine.playing) engine.pause(); else engine.play(); return true; }
        return false;
      };
      draw(null);
      markerScreen = s;
      U.push(s);
      U.focus(rows.querySelector('.mk-set'));
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
      if (scrobbled) scrobble('stop');
      stopEngine(false);
      item = episodeItem(nx, ctx.series);
      watchedSeconds = 0; recorded = false; attempts = 0;
      var p = D.progress(nx.id);
      start(p ? p.position : 0);
    }
    function onEnded() {
      saveProgress(true, true);
      if (!isLive() && !isCatchup() && engine && !stopSent) { stopSent = true; B.trakt.scrobble('stop', item, 1); }
      // build 102: slaaptimer "einde van de aflevering": niet door naar de volgende
      if (sleepEnd) { sleepEnd = false; close(); return; }
      if (item.type === 'episode' && nextEpisode()) { nextDismissed = false; showNextCard(); return; }
      close();
    }

    // MARK: voortgang en geschiedenis (= saveProgress/recordHistory op Android)

    /** build 97: voortgang alleen naar het profiel waarmee het afspelen begon (niet naar een intussen gekozen profiel). */
    function ownProfile() {
      var cur = D.activeProfileId ? D.activeProfileId() : null;
      return !cur || !profile || String(cur).toLowerCase() === String(profile.id).toLowerCase();
    }
    function saveProgress(force, ended) {
      if (!engine || isLive() || isCatchup() || !ownProfile()) return;
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
      if (recorded || watchedSeconds < 60 || isCatchup() || !ownProfile()) return;
      recorded = true;
      D.addHistory({
        itemID: item.channel ? item.channel.id : item.id, playlistID: Lib.source ? Lib.source.id : null, title: item.title,
        subtitle: item.subtitle || null, artwork: item.artwork || null, type: item.type, channelID: item.channel ? item.channel.id : null, date: Date.now()
      });
    }

    // MARK: tegelijk kijken (stream_start / ping / stop)

    function streamTitle() {
      if (isLive() && item.channel) { var nn = Lib.nowNext(item.channel); return item.channel.name + (nn.now ? ' · ' + nn.now.title : ''); }
      if (isCatchup()) return item.subtitle + ' · ' + item.title;
      return item.subtitle ? item.title + ' · ' + item.subtitle : item.title;
    }
    /** Soort, id en serie voor stream_start (build 68): terugkijken = 'catchup' bij de zender, aflevering met series_id. */
    function streamKind() { return isCatchup() ? 'catchup' : item.type; }
    function streamContentId() { return isCatchup() && item.channel ? item.channel.id : item.id; }
    function streamExtra() { return item.type === 'episode' ? { seriesId: item.seriesID || null, seriesTitle: item.title || null } : null; }
    function streamStart() {
      return A.streamStart(profile.id, profile.name, streamTitle(), streamKind(), streamContentId(), streamExtra()).then(function (r) {
        // speler intussen dicht of naar de achtergrond: geen ping-timer starten (die zou blijven lopen)
        if (closed || suspended) return true;
        if (r.limit) { showLimit(r); return false; }
        clearInterval(pingTimer);
        pingTimer = setInterval(function () {
          if (closed || suspended) { clearInterval(pingTimer); return; }
          A.streamPing(streamTitle()).then(function (alive) {
            if (alive === false && !closed && !suspended) A.streamStart(profile.id, profile.name, streamTitle(), streamKind(), streamContentId(), streamExtra()).then(function (r2) { if (r2.limit && !closed) { stopEngine(false); showLimit(r2); } });
          });
        }, 30000);
        return true;
      });
    }
    function showLimit(r) {
      if (closed) return;
      limited = true;
      spinner.style.display = 'none';
      var resumePos = isLive() ? 0 : (currentPos() || ctx.resume || 0);
      if (engine) engine.stop();
      U.clear(errorBox);
      errorBox.appendChild(h('h2', L('Too many people are watching')));
      errorBox.appendChild(h('p', r.limit === 1 ? L('Your account can be used on 1 screen at a time.')
        : L('Your account can be used on up to %d screens at the same time.', r.limit)));
      // build 88: Gratis → met Lifetime meer schermen tegelijk
      var free = r.plan ? String(r.plan).toLowerCase() === 'free' : A.isFree();
      if (free) errorBox.appendChild(h('p', L('Upgrade to Lifetime to watch on up to %d screens at the same time.', A.LIFETIME.streams)));
      var list = h('div.limit-list');
      r.streams.forEach(function (s) {
        list.appendChild(h('div.limit-row', [U.icon(U.platformIcon(s.platform)),
          h('div', [h('div', s.deviceName + ' · ' + s.profileName), h('div.dim', s.title)])]));
      });
      errorBox.appendChild(list);
      var row = h('div.modal-buttons');
      row.appendChild(U.button(L('Try again'), function () { errorBox.style.display = 'none'; start(resumePos); }, { primary: true }));
      if (free) row.appendChild(U.button(L('Upgrade to Lifetime'), function () { B.screens.upgrade('streams'); }, { icon: 'lifetime' }));
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
        // build 102: live met terugkijken of live-venster: −10 / +30 (zoals tvOS)
        case 'ff': if (isLive()) liveJump(30); else jump(30); return true;
        case 'rw': if (isLive()) liveJump(-10); else jump(-30); return true;
        case 'blue': optionsPanel(); return true;
        case 'chup': if (isLive()) zap(-1); return true;
        case 'chdown': if (isLive()) zap(1); return true;
        case 'red': if (item.type === 'episode') markerPanel(); return true;
        case 'info': if (overlayVisible()) hideOverlay(); else showOverlay(true); return true;
      }
      var skipVisible = skipBtn.style.display !== 'none';
      // build 97: kaart "Intro overslaan" / "Volgende aflevering" zonder overlay: OK gaat naar de kaart, nooit naar een
      // verborgen knop van de overlay
      var cardBtn = nextCard.style.display !== 'none' ? nextCard.querySelector('.f') : (skipVisible ? skipBtn : null);
      if (!overlayVisible() && cardBtn && name === 'enter') {
        var onCard = U.focused && (U.focused === skipBtn || nextCard.contains(U.focused));
        if (!onCard) { U.focus(cardBtn); if (cardBtn.onEnter) cardBtn.onEnter(); return true; }
      }
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
      checkSleep();
      if (!isLive()) { var tp = engine.time(); if (tp > 0) lastPos = tp; }
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
          // build 97: ook de focus-markering weghalen
          if (U.focused === skipBtn) { skipBtn.classList.remove('focused'); U.focused = null; }
        }
        if (item.type === 'episode' && d > 120 && !sleepEnd) {
          var credits = marker && marker.creditsStart != null ? marker.creditsStart : d - 25;
          if (t >= credits && !nextShown) showNextCard();
        }
      }
    }

    function stopEngine(sendStop) {
      if (!suspended) saveProgress(true);
      if (engine) engine.stop();
      if (sendStop) { clearInterval(pingTimer); A.streamStop(); }
    }
    function clearTimers() {
      clearInterval(tickTimer); clearInterval(pingTimer); clearInterval(countdownTimer);
      clearTimeout(overlayTimer); clearTimeout(zapTimer); clearTimeout(digitTimer);
      clearInterval(extTimer); clearInterval(markerTimer); markerTimer = null;
      clearInterval(subTimer); subTimer = null; clearTimeout(shiftTimer);
    }
    function close() {
      if (closed) return;
      closed = true;
      clearTimers();
      if (scrobbled && !stopSent) scrobble('stop');
      stopEngine(true);
      document.documentElement.classList.remove('playing');
      D.flush();
      if (U.top() === screen) U.pop();
      if (B.player.current === screen) B.player.current = null;
      if (ctx.onClose) ctx.onClose();
    }
    screen.close = close;
    // build 102: stand van de opties en live terugspoelen (voor tests en het logboek)
    screen.state = function () {
      return { rate: rate, aspect: aspect, sleepAt: sleepAt, sleepEnd: sleepEnd, subDelay: subDelay, shiftStart: shiftStart,
        behind: Math.round(behindLive()), canTimeshift: canTimeshift(), engine: engine ? engine.name : null };
    };
    screen.sleepIn = function (sec) { sleepWarned = false; sleepEnd = false; sleepAt = Date.now() + sec * 1000; };
    /**
     * App naar de achtergrond (Home-toets, andere app, bron wisselen): Tizen verwacht dat AVPlay dan wordt vrijgegeven.
     * Voortgang bewaren, stream_stop, de speler sluiten en onthouden waar we waren.
     */
    screen.pauseForBackground = function () {
      if (closed || suspended) return;
      saveProgress(true);
      if (scrobbled && engine && engine.playing && !isLive()) scrobble('pause');
      suspended = true;
      bgPos = engine && !isLive() ? engine.time() : 0;
      bgPaused = !!(engine && !engine.playing);
      startGen++; // lopende start (URL ophalen, prepare) negeren
      clearInterval(pingTimer); clearInterval(countdownTimer); clearTimeout(zapTimer); clearTimeout(digitTimer);
      clearInterval(markerTimer); markerTimer = null;
      clearTimeout(shiftTimer); shiftTarget = null;
      // build 97: paneel "Intro & aftiteling" sluiten (de engine is zo weg; na terugkomen start de speler opnieuw)
      if (markerScreen) { if (U.top() === markerScreen) U.pop(); markerScreen = null; }
      zapIndex = null; digitBuffer = ''; zapBanner.style.display = 'none';
      if (nextShown && nextCard.style.display !== 'none') { nextShown = false; nextCard.style.display = 'none'; }
      if (engine) { engine.stop(); engine = null; }
      A.streamStop();
    };
    /** Terug op de voorgrond: opnieuw openen (live: nu; film/aflevering: dezelfde plek, gepauzeerd als hij gepauzeerd was). */
    screen.resumeFromBackground = function () {
      if (closed || !suspended) return;
      suspended = false;
      if (limited || errorBox.style.display !== 'none') { renderInfo(); return; }
      pauseAfterOpen = bgPaused && !isLive();
      attempts = 0;
      start(isLive() ? 0 : bgPos);
    };
    // weggehaald door een schermwissel (uitloggen, profiel wisselen): alles opruimen zoals bij sluiten
    screen.destroy = function () {
      if (closed) return;
      closed = true;
      clearTimers();
      if (scrobbled && !stopSent) scrobble('stop');
      stopEngine(true);
      document.documentElement.classList.remove('playing');
      if (B.player.current === screen) B.player.current = null;
    };

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
      streamURL: ep.streamURL, artwork: (series && series.poster) || ep.still || null, seriesID: ep.seriesID, season: ep.season, episode: ep.number,
      imdbID: (series && series.imdbID) || null, stremio: ep.stremio || null, year: series ? series.year : null
    };
  }
  function movieItem(m) {
    return { type: 'movie', id: m.id, title: m.title, subtitle: m.year ? String(m.year) : '', streamURL: m.streamURL, stalkerCmd: m.stalkerCmd || null,
      artwork: m.poster || null, imdbID: m.imdbID || null, stremio: m.stremio || null, year: m.year || null };
  }
  /** Terugkijken: id "<zender>-cu-<start in s>" zoals op Android. */
  function catchupItem(ch, p) {
    return { type: 'catchup', id: ch.id + '-cu-' + Math.floor(p.start / 1000), title: p.title || ch.name, subtitle: ch.name, streamURL: null,
      artwork: ch.logo || null, channel: ch, program: p };
  }
  B.player.catchupItem = catchupItem;
  B.player.playCatchup = function (ch, p, list) {
    if (B.player.current) B.player.current.close();
    return B.player.open(catchupItem(ch, p), { channels: list || null });
  };
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
          { label: L('Resume') + ' (' + B.fmtDur(p.position) + ')', play: true, fn: function () { ctx.resume = p.position; B.player.open(item, ctx); } },
          { label: L('Watch from the beginning'), fn: function () { ctx.resume = 0; B.player.open(item, ctx); } }
        ]
      });
      return;
    }
    B.player.open(item, ctx);
  };

  // MARK: voorbeeld in Live TV (één speler, pas na ~1,2 s stilstand, nooit tijdens ingedrukt houden)

  var PV = B.preview = {};
  var pvTimer = null, pvPending = null, pvEngine = null, pvBox = null, pvId = null, pvVideo = null, pvGen = 0;
  /** HLS en MP4 kan de tv ook zonder AVPlay (gedempt in de pagina); de rest via AVPlay in een "gat" in de pagina. */
  function htmlFriendly(url) { return /\.m3u8(\?|$)|\.mp4(\?|$)/i.test(url) || !hasAVPlay(); }
  PV.focus = function (ch, box) {
    if (D.local('livePreview', true) === false || B.player.current || (B.multiview && B.multiview.current)) return;
    if (pvId === ch.id && pvBox === box) return;
    pvPending = ch.id;
    clearTimeout(pvTimer);
    if (pvId && pvId !== ch.id) PV.stop(true);
    pvTimer = setTimeout(function () {
      B.whenQuiet(function () {
        if (pvPending !== ch.id || B.player.current || !document.body.contains(box)) return;
        start(ch, box);
      }, 900);
    }, 300);
  };
  function start(ch, box) {
    var my = ++pvGen;
    pvId = ch.id; pvBox = box;
    box.classList.add('loading');
    Lib.resolveURL(channelItem(ch), true).then(function (url) {
      if (my !== pvGen || !url) { if (my === pvGen) fail(); return; }
      var events = { buffering: B.noop, ended: B.noop, subtitle: B.noop, error: function () { if (my === pvGen) fail(); } };
      var p;
      if (htmlFriendly(url)) {
        if (!pvVideo) { pvVideo = document.createElement('video'); pvVideo.muted = true; pvVideo.setAttribute('muted', ''); pvVideo.setAttribute('playsinline', ''); pvVideo.className = 'pv-video'; }
        if (pvVideo.parentNode !== box) box.appendChild(pvVideo);
        pvEngine = new HTMLEngine(events, pvVideo);
        p = pvEngine.open(url, { live: true });
      } else {
        var r = box.getBoundingClientRect();
        box.classList.add('hole');
        document.documentElement.classList.add('pv-hole');
        pvEngine = new AVEngine(events);
        p = pvEngine.open(url, { live: true, rect: { x: r.left, y: r.top, w: r.width, h: r.height } });
      }
      return p.then(function () {
        if (my !== pvGen) return;
        box.classList.remove('loading');
        box.classList.add('playing');
      });
    }).catch(function () { if (my === pvGen) fail(); });
  }
  function fail() {
    var box = pvBox;
    PV.stop(true);
    if (box) box.classList.add('failed');
  }
  /** Voorbeeld stoppen (andere zender, pagina weg, speler open). keepPending: alleen de video, niet de wachtende zender. */
  PV.stop = function (keepPending) {
    pvGen++;
    if (!keepPending) { clearTimeout(pvTimer); pvPending = null; }
    if (pvEngine) { try { pvEngine.stop(); } catch (e) { /* stil */ } pvEngine = null; }
    if (pvVideo && pvVideo.parentNode) pvVideo.parentNode.removeChild(pvVideo);
    if (pvBox) { pvBox.classList.remove('hole'); pvBox.classList.remove('playing'); pvBox.classList.remove('loading'); pvBox.classList.remove('failed'); }
    document.documentElement.classList.remove('pv-hole');
    pvId = null; pvBox = null;
  };
})();
