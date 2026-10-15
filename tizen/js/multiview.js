/*
 * Multiview: 2 tot 4 live-zenders tegelijk in een raster (= MultiviewScreen.kt).
 * Een Samsung-tv heeft maar één AVPlay-speler. Daarom: de tegel met geluid (de "hoofdtegel") speelt via AVPlay
 * (alle formaten, ook .ts), de andere tegels via HTML5-video, gedempt (HLS/MP4; hoeveel tegelijk lukt, hangt af van
 * de tv). Kan een tegel niet spelen, dan staat er nu/straks; OK op die tegel maakt hem de hoofdtegel.
 * Telt als één stream (stream_start/ping/stop).
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui, D = B.data, A = B.account, Lib = B.lib;
  var h = U.h;
  var L = function () { return B.L.apply(null, arguments); };
  var MV = B.multiview = { current: null };
  var MAX = 4;

  MV.open = function (channels, index) {
    channels = (channels && channels.length ? channels : Lib.lib.channels).slice();
    if (!channels.length) { U.toast(L('No channels')); return; }
    if (B.preview) B.preview.stop();
    if (MV.current) MV.current.close();
    var profile = B.app.profile;
    var first = channels[B.clamp(index || 0, 0, channels.length - 1)];
    var second = null;
    for (var i = 1; i < channels.length && !second; i++) { var c = channels[((index || 0) + i) % channels.length]; if (c.id !== first.id) second = c; }
    var tiles = [];          // {ch, el, video, engine, main}
    var mainIndex = 0, closed = false, pingTimer = null, suspended = false;
    var grid = h('div.mv-grid');
    var hint = h('div.mv-hint.dim', L('OK: sound on this tile  ·  Yellow: remove  ·  Play: full screen'));
    var el = h('div.layer.multiview', [grid, hint]);
    var screen = { el: el, isMultiview: true };

    function rects(n) {
      // 2: naast elkaar; 3–4: 2×2
      var W = 1920, H = 1080, gap = 16;
      if (n <= 2) {
        var w = (W - gap * 3) / 2, hh = w * 9 / 16, y = (H - hh) / 2;
        return [{ x: gap, y: y, w: w, h: hh }, { x: gap * 2 + w, y: y, w: w, h: hh }];
      }
      // onderaan ruimte voor de uitleg, zodat die niet over de tegels valt
      var w2 = (W - gap * 3) / 2, h2 = (H - 56 - gap * 3) / 2;
      return [{ x: gap, y: gap, w: w2, h: h2 }, { x: gap * 2 + w2, y: gap, w: w2, h: h2 }, { x: gap, y: gap * 2 + h2, w: w2, h: h2 }, { x: gap * 2 + w2, y: gap * 2 + h2, w: w2, h: h2 }];
    }
    function label(t) {
      var nn = Lib.nowNext(t.ch);
      return h('div.mv-label', [h('div.mv-name', [t.main ? U.icon('audio', 'mv-audio') : null, (t.ch.number != null ? t.ch.number + '  ' : '') + t.ch.name]),
        h('div.mv-now.dim', nn.now ? nn.now.title : ''), h('div.mv-fail', L("This tile can't play on this TV. Press OK to give it the sound."))]);
    }
    function layout() {
      var f = U.focused, fi = f && f._ti;
      U.clear(grid);
      var n = tiles.length + (tiles.length < MAX ? 1 : 0);
      var rs = rects(n);
      tiles.forEach(function (t, i) {
        var r = rs[i];
        t.rect = r;
        if (!t.el) {
          t.el = h('div.mv-tile.f');
          t.el.onEnter = function () { makeMain(tiles.indexOf(t)); };
          t.el.onKey = function (name) {
            if (name === 'yellow' || name === 'red') { removeTile(tiles.indexOf(t)); return true; }
            if (name === 'play' || name === 'info') { fullscreen(t); return true; }
            return false;
          };
        }
        t.el._ti = i;
        t.el.style.left = r.x + 'px'; t.el.style.top = r.y + 'px'; t.el.style.width = r.w + 'px'; t.el.style.height = r.h + 'px';
        t.el.classList.toggle('main', !!t.main);
        t.el.classList.toggle('hole', !!(t.main && t.engine && t.engine.name === 'avplay'));
        // video-element blijft staan; alleen het label opnieuw
        var old = t.el.querySelector('.mv-label');
        if (old) t.el.removeChild(old);
        t.el.appendChild(label(t));
        grid.appendChild(t.el);
        if (t.main && t.engine && t.engine.setRect) t.engine.setRect(r);
      });
      if (tiles.length < MAX) {
        var r = rs[tiles.length];
        var add = h('div.mv-tile.add.f', [U.icon('list'), h('div', L('Add channel'))]);
        add.style.left = r.x + 'px'; add.style.top = r.y + 'px'; add.style.width = r.w + 'px'; add.style.height = r.h + 'px';
        add._ti = tiles.length;
        add.onEnter = chooseChannel;
        grid.appendChild(add);
      }
      var all = grid.querySelectorAll('.f');
      var target = null;
      for (var k = 0; k < all.length; k++) if (all[k]._ti === fi) target = all[k];
      U.focus(target || all[0]);
    }
    /**
     * build 97: elke start van een tegel krijgt een generatie (t.gen). Een oudere start (URL nog onderweg, prepare,
     * late fout) doet niets meer: geen tweede engine, en hij maakt de nieuwe start niet "mislukt".
     * Alleen de hoofdtegel gebruikt AVPlay; een andere tegel die nog een AVPlay-engine had, wordt eerst gestopt.
     */
    function play(t) {
      stopTile(t);
      var g = t.gen = (t.gen || 0) + 1;
      function stale() { return closed || suspended || tiles.indexOf(t) < 0 || t.gen !== g; }
      t.failed = false;
      t.el.classList.remove('failed');
      Lib.resolveURL(B.player.channelItem(t.ch), true).then(function (url) {
        if (stale()) return;
        if (!url) { failTile(t, g); return; }
        var events = { buffering: B.noop, ended: B.noop, subtitle: B.noop, error: function () { failTile(t, g); } };
        if (t.main && B.engines.hasAVPlay()) {
          tiles.forEach(function (o) { if (o !== t && o.engine && o.engine.name === 'avplay') stopTile(o); });
          t.engine = new B.engines.AV(events);
          t.el.classList.add('hole');
          document.documentElement.classList.add('mv-hole');
          return t.engine.open(url, { live: true, rect: t.rect });
        }
        if (!t.video) {
          t.video = document.createElement('video');
          t.video.className = 'mv-video';
          t.video.setAttribute('playsinline', '');
        }
        t.video.muted = !t.main;
        if (t.video.parentNode !== t.el) t.el.insertBefore(t.video, t.el.firstChild);
        t.engine = new B.engines.HTML(events, t.video);
        return t.engine.open(url, { live: true });
      }).then(function () {
        if (t.gen !== g) return;
        // intussen dicht, naar de achtergrond of tegel weg: de speler meteen weer vrijgeven
        if (closed || suspended || tiles.indexOf(t) < 0) { stopTile(t); return; }
        if (t.engine) t.el.classList.add('playing');
      }, function () { failTile(t, g); });
    }
    function failTile(t, g) {
      if (closed || suspended) return;
      if (g != null && t.gen !== g) return;
      stopTile(t);
      t.failed = true;
      if (t.el) { t.el.classList.add('failed'); t.el.classList.remove('playing'); }
    }
    function stopTile(t) {
      t.gen = (t.gen || 0) + 1; // build 97: lopende start van deze tegel vervalt
      if (t.engine) { try { t.engine.stop(); } catch (e) { /* stil */ } t.engine = null; }
      if (t.el) { t.el.classList.remove('hole'); t.el.classList.remove('playing'); }
      if (t.main) document.documentElement.classList.remove('mv-hole');
    }
    /** Deze tegel krijgt het geluid (en AVPlay); de vorige hoofdtegel wordt een gedempte HTML5-tegel. */
    function makeMain(i) {
      if (i < 0 || i === mainIndex) return;
      var old = tiles[mainIndex], nu = tiles[i];
      if (old) { stopTile(old); old.main = false; }
      stopTile(nu); nu.main = true;
      mainIndex = i;
      layout();
      if (old) play(old);
      play(nu);
      A.streamPing(nu.ch.name + ' · Multiview');
    }
    function addTile(ch) {
      if (tiles.length >= MAX) return;
      var t = { ch: ch, main: false };
      tiles.push(t);
      layout();
      tiles.forEach(function (x) { if (x.main && x.engine && x.engine.setRect) x.engine.setRect(x.rect); });
      play(t);
    }
    function removeTile(i) {
      if (tiles.length <= 1) { close(); return; }
      var t = tiles[i];
      stopTile(t);
      if (t.video && t.video.parentNode) t.video.parentNode.removeChild(t.video);
      tiles.splice(i, 1);
      if (t.main) { mainIndex = 0; tiles[0].main = true; layout(); stopTile(tiles[0]); play(tiles[0]); return; }
      if (mainIndex > i) mainIndex--;
      layout();
    }
    function chooseChannel() {
      var used = {};
      tiles.forEach(function (t) { used[t.ch.id] = true; });
      var opts = channels.filter(function (c) { return !used[c.id]; }).slice(0, 300).map(function (c) { return { value: c.id, label: (c.number != null ? c.number + '  ' : '') + c.name }; });
      if (!opts.length) return;
      U.choose(L('Add channel'), opts, null, function (id) {
        for (var i = 0; i < channels.length; i++) if (channels[i].id === id) { addTile(channels[i]); return; }
      });
    }
    function fullscreen(t) {
      var ch = t.ch;
      close();
      B.player.playChannel(ch, channels);
    }
    function close() {
      if (closed) return;
      closed = true;
      clearInterval(pingTimer);
      tiles.forEach(function (t) { stopTile(t); });
      document.documentElement.classList.remove('mv-hole');
      document.documentElement.classList.remove('playing');
      A.streamStop();
      if (U.top() === screen) U.pop();
      if (MV.current === screen) MV.current = null;
    }
    screen.close = close;
    screen.onBack = function () { close(); return true; };
    screen.destroy = function () {
      if (closed) return;
      closed = true;
      tiles.forEach(stopTile); clearInterval(pingTimer); A.streamStop();
      if (MV.current === screen) MV.current = null;
      document.documentElement.classList.remove('playing');
      document.documentElement.classList.remove('mv-hole');
    };
    // naar de achtergrond: AVPlay en de video's vrijgeven (Tizen), bij terugkomen alles opnieuw starten
    screen.pauseForBackground = function () {
      if (closed || suspended) return;
      suspended = true;
      tiles.forEach(stopTile); clearInterval(pingTimer); A.streamStop();
    };
    screen.resumeFromBackground = function () {
      if (closed || !suspended) return;
      suspended = false;
      tiles.forEach(play); start();
    };

    function start() {
      A.streamStart(profile.id, profile.name, 'Multiview · ' + tiles.map(function (t) { return t.ch.name; }).join(', '), 'live', tiles[0].ch.id).then(function (r) {
        if (closed || suspended) return;
        if (r.limit) {
          U.toast(L('Too many people are watching')); close();
          // build 88: Gratis → upgradevenster (meer schermen tegelijk met Lifetime)
          if (r.plan ? String(r.plan).toLowerCase() === 'free' : A.isFree()) B.screens.upgrade('streams');
          return;
        }
        clearInterval(pingTimer);
        pingTimer = setInterval(function () {
          if (closed || suspended) { clearInterval(pingTimer); return; }
          // stopped: de stream telt niet meer (lange pauze), opnieuw aanmelden
          A.streamPing('Multiview').then(function (alive) { if (alive === false && !closed && !suspended) start(); });
        }, 30000);
      });
    }
    tiles.push({ ch: first, main: true });
    if (second) tiles.push({ ch: second, main: false });
    MV.current = screen;
    // Samsung-tv: één AVPlay-speler. Eén keer per sessie uitleggen waarom de andere tegels gedempt via HTML5 spelen.
    if (B.engines.hasAVPlay() && !MV.explained) {
      MV.explained = true;
      setTimeout(function () { if (!closed) U.toast(L('A Samsung TV has one full video player: the tile with sound uses it, the other tiles play muted where the TV can.'), 6000); }, 800);
    }
    document.documentElement.classList.add('playing');
    U.push(screen);
    layout();
    tiles.forEach(play);
    start();
    return screen;
  };
})();
