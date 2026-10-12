/*
 * Schermen: splash, inloggen met QR (en e-mail), te veel apparaten, "Wie kijkt er?", de hoofdschermen
 * (Home, Live TV met gids, Films, Series, Mijn lijst, Zoeken, Instellingen) en de detailpagina.
 * 10-foot UI in Netflix-stijl, zelfde kleuren en logo als de andere Bingr-apps.
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui, D = B.data, A = B.account, Lib = B.lib, PC = B.parental;
  var h = U.h;
  var L = function () { return B.L.apply(null, arguments); };
  var S = B.screens = {};

  function layer(cls, kids) { return h('div.layer' + (cls ? '.' + cls : ''), kids); }

  // MARK: splash

  S.splash = function () {
    var el = layer('splash', [h('div.splash-inner', [U.logo(), h('div.splash-spin', [U.spinner()])])]);
    return { el: el, onBack: function () { return true; } };
  };

  // MARK: inloggen (tv: QR-code met de telefoon, of e-mail)

  S.login = function (onDone) {
    var qrBox = h('div.qr-box');
    var code = h('div.qr-code', '···-···');
    var err = h('div.login-error');
    var waiting = h('div.login-wait', [U.spinner(), h('span', L('Waiting for confirmation…'))]);
    var left = h('div.login-left', [
      h('h1', L('Sign in with your phone')),
      h('p.dim', L("Scan the QR code with your phone's camera")),
      qrBox,
      h('p.login-or', [L('Or go to'), ' ', h('b', 'bingr.online/tv'), ' ', L('and enter this code:')]),
      code, waiting, err
    ]);
    var emailBtn = U.button(L('Sign in with email'), function () { S.emailLogin(function () { finish(); }); }, { cls: 'wide' });
    var right = h('div.login-right', [h('div.login-ortext', L('OR')), emailBtn]);
    var el = layer('login', [h('div.login-bg'), h('div.login-top', [U.logo()]), h('div.login-card', [left, h('div.login-divider'), right])]);
    var flow = null;
    function finish() {
      if (flow) flow.cancel();
      U.toast(L('Signed in as %@', A.email() || ''));
      onDone();
    }
    function drawQR(url) {
      U.clear(qrBox);
      if (!url) { qrBox.appendChild(U.spinner()); return; }
      try {
        var q = window.qrcode(0, 'M');
        q.addData(url);
        q.make();
        var n = q.getModuleCount();
        var size = 360, cell = Math.floor(size / (n + 4));
        var canvas = document.createElement('canvas');
        canvas.width = canvas.height = cell * (n + 4);
        var g = canvas.getContext('2d');
        g.fillStyle = '#fff'; g.fillRect(0, 0, canvas.width, canvas.height);
        g.fillStyle = '#000';
        for (var r = 0; r < n; r++) for (var c = 0; c < n; c++) if (q.isDark(r, c)) g.fillRect((c + 2) * cell, (r + 2) * cell, cell, cell);
        qrBox.appendChild(canvas);
      } catch (e) { qrBox.appendChild(h('div', url)); }
    }
    drawQR(null);
    var s = {
      el: el,
      onShow: function () {
        if (flow) return;
        flow = A.runTvFlow({
          onCode: function (c) { code.textContent = c ? A.displayCode(c.code) : '···-···'; drawQR(c ? c.url : null); },
          onError: function (m) { err.textContent = m || ''; }
        });
        flow.promise.then(function () { finish(); });
      },
      onBack: function () { B.app.exit(); return true; },
      destroy: function () { if (flow) flow.cancel(); }
    };
    return s;
  };

  /** E-mail en wachtwoord met het schermtoetsenbord. */
  S.emailLogin = function (onDone) {
    var email = '', pw = '', field = 'email';
    var emailBox = h('div.input.f.autofocus'), pwBox = h('div.input.f');
    var err = h('div.login-error');
    var kb = U.keyboard({ layout: 'email', onChange: function (v) { if (field === 'email') email = v; else pw = v; draw(); } });
    function draw() {
      emailBox.textContent = email || L('Email');
      emailBox.classList.toggle('placeholder', !email);
      pwBox.textContent = pw ? new Array(pw.length + 1).join('•') : L('Password');
      pwBox.classList.toggle('placeholder', !pw);
      emailBox.classList.toggle('active', field === 'email');
      pwBox.classList.toggle('active', field === 'pw');
    }
    emailBox.onFocus = function () { field = 'email'; kb.setValue(email); draw(); };
    pwBox.onFocus = function () { field = 'pw'; kb.setValue(pw); draw(); };
    emailBox.onEnter = function () { U.focus(kb.querySelector('.key')); };
    pwBox.onEnter = emailBox.onEnter;
    var busy = false;
    var signIn = U.button(L('Sign In'), function () {
      if (busy) return;
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())) { err.textContent = L('Enter a valid email address.'); return; }
      if (pw.length < 6) { err.textContent = L('Password must be at least 6 characters.'); return; }
      busy = true; err.textContent = '';
      B.http(B.config.supabaseUrl + '/auth/v1/token?grant_type=password', {
        method: 'POST', headers: { 'apikey': B.config.supabaseKey, 'Content-Type': 'application/json' }, body: { email: email.trim(), password: pw }
      }).then(function (r) {
        busy = false;
        var j = r.json || {};
        if (r.status >= 200 && r.status < 300 && j.access_token) {
          B.account._store(j, email.trim());
          U.pop();
          onDone();
          return;
        }
        var codeErr = j.error_code || j.error;
        err.textContent = codeErr === 'email_not_confirmed' ? L('Confirm your email address first. Check your inbox.') : L('Incorrect email or password.');
      }, function () { busy = false; err.textContent = L('No internet connection.'); });
    }, { primary: true });
    var el = layer('email-login', [h('div.email-card', [
      h('h1', L('Sign In')),
      h('div.email-cols', [kb, h('div.email-fields', [emailBox, pwBox, signIn, err])])
    ])]);
    draw();
    U.push({ el: el, onBack: function () { U.pop(); return true; } });
  };

  // MARK: te veel apparaten

  S.deviceLimit = function (info, onRetry, onCancel) {
    var list = h('div.dev-list.sy.fm', { 'data-pad': '40' });
    function draw(devs) {
      U.clear(list);
      devs.forEach(function (d) {
        var row = h('div.dev-row', [U.icon(platformIcon(d.platform)), h('div.dev-info', [h('div', d.name), h('div.dim', d.platform)])]);
        var rm = U.button(L('Remove'), function () {
          A.removeDevice(d.id).then(onRetry, function (e) { U.toast(e.message); });
        });
        row.appendChild(rm);
        list.appendChild(row);
      });
    }
    draw(info.devices);
    var el = layer('limit', [h('div.limit-card', [
      h('h1', L('Too many devices')),
      h('p.dim', L('Your account can be used on up to %d devices. Remove a device to sign in on this one.', info.limit)),
      list,
      h('div.modal-buttons', [U.button(L('Cancel'), onCancel)])
    ])]);
    return { el: el, onBack: function () { onCancel(); return true; } };
  };
  function platformIcon(p) {
    p = String(p || '');
    if (/TV|tizen/i.test(p)) return 'tv';
    if (/Mac/.test(p)) return 'laptop';
    return 'phone';
  }
  S.platformIcon = platformIcon;

  // MARK: avatar

  S.avatar = function (p, size) {
    var av = D.avatar(p);
    var pal = D.palettes[av.colorIndex] || D.palettes[0];
    var el = h('div.avatar' + (size ? '.' + size : ''));
    el.style.backgroundImage = 'linear-gradient(135deg,' + pal[0] + ',' + pal[1] + ')';
    if (av.kind === 'kid') el.appendChild(h('img.avatar-kid', { src: 'img/' + av.value + '.png', alt: '' }));
    else el.appendChild(h('span.avatar-emoji', av.value));
    var photo = p && p.photo && p.photo.path;
    if (photo) {
      B.app.photo(photo, p.photo.updated).then(function (url) {
        if (!url) return;
        el.style.backgroundImage = 'url("' + url + '")';
        el.style.backgroundSize = 'cover';
        el.classList.add('has-photo');
      });
    }
    return el;
  };

  /**
   * Kleine ronde avatar voor een regel in "Wie kijkt er nu" en de kijkgeschiedenis (build 57): profiel op profile_id,
   * anders op naam (oudere regels zonder profile_id), anders de eerste letter in een gekleurde cirkel.
   */
  S.rowAvatar = function (profileId, profileName) {
    var p = (profileId && D.profileById(profileId)) || null;
    if (!p && profileName) {
      var n = String(profileName).trim().toLowerCase();
      for (var i = 0; i < D.profiles.length && !p; i++) if (String(D.profiles[i].name || '').trim().toLowerCase() === n) p = D.profiles[i];
    }
    if (p) return S.avatar(p, 'mini');
    var name = String(profileName || '').trim();
    var hash = 0;
    for (var j = 0; j < name.length; j++) hash = (hash * 31 + name.charCodeAt(j)) | 0;
    var pal = D.palettes[((hash % D.palettes.length) + D.palettes.length) % D.palettes.length];
    var el = h('div.avatar.mini.letter', name ? name.charAt(0).toUpperCase() : '?');
    el.style.backgroundImage = 'linear-gradient(135deg,' + pal[0] + ',' + pal[1] + ')';
    return el;
  };

  // MARK: Wie kijkt er?

  S.profiles = function (onPick) {
    var row = h('div.prof-row.fm');
    function draw() {
      U.clear(row);
      D.profiles.forEach(function (p) {
        var tile = h('div.prof.f', [S.avatar(p, 'big'), h('div.prof-name', p.name),
          D.pinFor(p) ? h('div.prof-lock', [U.icon('lock')]) : null,
          p.kids ? h('div.prof-kids', L('Kids')) : null]);
        if (B.store.get('lastProfile', null) === p.id) tile.classList.add('autofocus');
        tile.onEnter = function () {
          var pin = D.pinFor(p);
          if (pin) U.askPIN(L('Enter PIN'), function (x) { return x === pin; }, function () { onPick(p); });
          else onPick(p);
        };
        row.appendChild(tile);
      });
      if (!D.profiles.length) row.appendChild(h('div.dim', L('Loading…')));
    }
    draw();
    var el = layer('profiles', [h('div.prof-top', [U.logo()]), h('h1.prof-title', L("Who's watching?")), row]);
    var off = function () { var f = U.focused; draw(); if (!row.contains(f)) U.focusFirst(row); };
    B.on('profiles', function () { if (el.parentNode && !el.classList.contains('hidden')) off(); });
    return { el: el, onBack: function () { B.app.exit(); return true; } };
  };

  // MARK: kaarten

  function posterCard(x, type, opts) {
    opts = opts || {};
    var title = type === 'series' || type === 'movie' ? x.title : x.name;
    var card = h('div.card.poster.f', [U.img(x.poster || x.logo, 'poster-img', title)]);
    var p = opts.progress;
    if (p != null) card.appendChild(h('div.card-progress', [h('div', { style: { width: Math.round(B.clamp(p, 0, 1) * 100) + '%' } })]));
    if (opts.label) card.appendChild(h('div.card-label', opts.label));
    card.onEnter = function () { openItem(type, x); };
    card.onFocus = opts.onFocus || null;
    card._key = type + ':' + x.id;
    return card;
  }
  function channelCard(ch, list) {
    var nn = Lib.nowNext(ch);
    var card = h('div.card.chan.f', [
      h('div.chan-logo', [U.img(ch.logo, 'logo-img', ch.name)]),
      h('div.chan-meta', [h('div.chan-name', ch.name), h('div.chan-now.dim', nn.now ? nn.now.title : '')])
    ]);
    if (nn.now) card.appendChild(h('div.card-progress', [h('div', { style: { width: Math.round(B.clamp((Date.now() - nn.now.start) / (nn.now.end - nn.now.start), 0, 1) * 100) + '%' } })]));
    card.onEnter = function () { B.player.playChannel(ch, list || [ch]); };
    card._key = 'channel:' + ch.id;
    return card;
  }
  function top10Card(x, type, i) {
    var card = h('div.card.top10.f', [h('div.top10-num', String(i + 1)), U.img(x.poster, 'poster-img', x.title)]);
    card.onEnter = function () { openItem(type, x); };
    card._key = type + ':' + x.id;
    return card;
  }
  function openItem(type, x) {
    if (type === 'channel') { B.player.playChannel(x, [x]); return; }
    U.push(S.detail(type, x));
  }
  S.openItem = openItem;

  function rowBlock(title, cards, cls) {
    if (!cards.length) return null;
    var scroller = h('div.row-scroll.sx.fm', { 'data-pad': '70' }, [h('div.row-track', cards)]);
    return h('div.row-block' + (cls ? '.' + cls : ''), [h('div.row-title', title), scroller]);
  }

  // MARK: hoofdscherm met navigatie links

  var NAV = [
    ['search', 'search', 'Search'], ['home', 'home', 'Home'], ['live', 'live', 'Live TV'], ['movies', 'movies', 'Movies'],
    ['series', 'series', 'Series'], ['mylist', 'list', 'My List'], ['settings', 'gear', 'Settings']
  ];
  S.main = function () {
    var content = h('div.main-content', { 'data-zone': 'content' });
    var nav = h('div.nav.fm', { 'data-zone': 'nav' });
    var current = null;
    var pages = {};
    var profileTile = h('div.nav-profile', [S.avatar(B.app.profile, 'small'), h('span.nav-label', B.app.profile.name)]);
    nav.appendChild(profileTile);
    var items = {};
    NAV.forEach(function (n) {
      var it = h('div.nav-item.f', [U.icon(n[1]), h('span.nav-label', L(n[2]))]);
      it.onEnter = function () { show(n[0], true); };
      it.onFocus = function () { nav.classList.add('open'); };
      it.onBlur = function () { setTimeout(function () { if (!nav.contains(U.focused)) nav.classList.remove('open'); }, 0); };
      it.onKey = function (name) {
        if (name === 'right') { focusContent(); return true; }
        return false;
      };
      items[n[0]] = it;
      nav.appendChild(it);
    });
    var el = layer('main', [content, nav]);
    var wantContent = false;
    function focusContent() {
      var pg = pages[current];
      if (!pg) return false;
      if (pg.focusIn && pg.focusIn()) return true;
      var list = U.focusables(pg.el);
      if (list.length) { U.focus(list[0]); return true; }
      return false;
    }
    function show(key, focusIn) {
      if (current && pages[current] && pages[current].onHide) pages[current].onHide();
      Object.keys(items).forEach(function (k) { items[k].classList.toggle('active', k === key); });
      current = key;
      var pg = pages[key];
      if (!pg || pg.stale) {
        if (pg && pg.el.parentNode) pg.el.parentNode.removeChild(pg.el);
        pg = pages[key] = S.pages[key]();
        content.appendChild(pg.el);
      }
      Object.keys(pages).forEach(function (k) { pages[k].el.style.display = k === key ? '' : 'none'; });
      if (pg.onShow) pg.onShow();
      // inhoud nog niet geladen: zodra hij er is de focus erheen
      if (focusIn) wantContent = !focusContent();
    }
    var screen = {
      el: el,
      onShow: function (back) {
        if (!back && !current) show(B.app.startPage(), true);
        else if (back && dataStale) { dataStale = false; rerender(); }
      },
      onBack: function () {
        if (nav.contains(U.focused)) {
          if (current !== 'home') { show('home', true); return true; }
          B.app.confirmExit(); return true;
        }
        var pg = pages[current];
        if (pg && pg.onBack && pg.onBack()) return true;
        U.focus(items[current] || items.home);
        return true;
      },
      onKey: function (name) {
        wantContent = false;
        // links aan de rand van de inhoud: naar het menu
        if (name === 'left' && !nav.contains(U.focused)) {
          // naar het menu altijd via het menu-item van deze pagina
          if (!U.move('left') || nav.contains(U.focused)) U.focus(items[current] || items.home);
          return true;
        }
        return false;
      },
      show: show,
      refresh: function () {
        Object.keys(pages).forEach(function (k) { if (k !== current) pages[k].stale = true; });
        var pg = pages[current];
        if (pg && pg.refresh) pg.refresh();
        var lost = !U.focused || !document.body.contains(U.focused);
        if ((wantContent || lost) && U.top() === screen) { if (focusContent()) wantContent = false; else if (lost) U.focus(items[current]); }
      }
    };
    B.on('library', function () { if (el.parentNode) screen.refresh(); });
    /** Huidige pagina opnieuw tekenen en de focus terugzetten op dezelfde titel (in dezelfde rij). */
    var dataStale = false;
    function rerender() {
      var pg = pages[current];
      if (!pg) return;
      var f = U.focused, key = f && f._key, rowT = null;
      var hadFocus = !!(f && pg.el.contains(f));
      if (f && f.closest) { var rb = f.closest('.row-block'); var t = rb && rb.querySelector('.row-title'); rowT = t ? t.textContent : null; }
      (pg.onData || pg.refresh)();
      if (!hadFocus) return;
      var target = null, all = pg.el.querySelectorAll('.f');
      for (var i = 0; i < all.length && key; i++) {
        if (all[i]._key !== key) continue;
        if (!target) target = all[i];
        var rb2 = all[i].closest('.row-block'), t2 = rb2 && rb2.querySelector('.row-title');
        if (t2 && t2.textContent === rowT) { target = all[i]; break; }
      }
      if (target) U.focus(target); else focusContent();
    }
    B.on('profileData', function () {
      Object.keys(pages).forEach(function (k) { if (pages[k].onData && k !== current) pages[k].stale = true; });
      if (!pages[current] || !pages[current].onData) return;
      if (U.top() !== screen) { dataStale = true; return; }
      rerender();
    });
    B.on('epg', function () { var pg = pages[current]; if (pg && pg.onEpg) pg.onEpg(); });
    return screen;
  };

  S.pages = {};

  function stateBox(page) {
    if (Lib.loading && !Lib.raw.channels.length && !Lib.raw.movies.length && !Lib.raw.series.length) {
      return h('div.state', [U.spinner(), h('div', L('Loading your library…'))]);
    }
    if (Lib.error) return h('div.state', [h('h2', L("Couldn't load the server")), h('div.dim', Lib.error), U.button(L('Try again'), function () { Lib.select(Lib.source, true); }, { primary: true })]);
    if (!Lib.source) return h('div.state', [h('h2', L('No servers yet')), h('div.dim', L('Add a server in the Bingr app on your phone, tablet or computer. It will appear here automatically.'))]);
    void page;
    return null;
  }

  // MARK: Home

  S.pages.home = function () {
    var scroller = h('div.page.home.sy', { 'data-align': 'top', 'data-pad': '40' });
    var pg = { el: scroller };
    function heroFor() {
      var lib = Lib.lib;
      var cands = lib.movies.filter(function (m) { return m.poster; }).slice(0, 400);
      if (!cands.length) cands = lib.series.filter(function (s) { return s.poster; }).slice(0, 400);
      if (!cands.length) return null;
      var top = Lib.top10(lib.movies.length ? lib.movies : lib.series);
      var pick = top.length ? top[Math.floor(Math.random() * Math.min(5, top.length))] : cands[Math.floor(Math.random() * cands.length)];
      return { item: pick, type: lib.movies.indexOf(pick) >= 0 ? 'movie' : 'series' };
    }
    function hero(hf) {
      var x = hf.item;
      var playBtn = U.button(L('Play'), function () {
        if (hf.type === 'movie') B.player.playVOD(B.player.movieItem(x));
        else U.push(S.detail('series', x));
      }, { primary: true, icon: 'play', autofocus: true });
      var info = U.button(L('More info'), function () { U.push(S.detail(hf.type, x)); }, { icon: 'info' });
      var meta = [];
      if (x.year) meta.push(String(x.year));
      if (x.rating) meta.push('★ ' + (Math.round(x.rating * 10) / 10));
      if (x.group) meta.push(x.group);
      return h('div.row-block.hero', [
        h('div.hero-bg', [U.img(x.poster, 'hero-img')]),
        h('div.hero-poster', [U.img(x.poster, 'poster-img', x.title)]),
        h('div.hero-shade'),
        h('div.hero-info', [
          h('div.hero-kind', hf.type === 'series' ? L('SERIES') : L('FILM')),
          h('h1.hero-title', x.title), h('div.hero-meta', meta.join('  ·  ')),
          x.plot ? h('p.hero-plot', x.plot) : null,
          h('div.hero-buttons.fm', [playBtn, info])
        ])
      ]);
    }
    function continueRow() {
      var lib = Lib.lib, cards = [];
      D.progressList().forEach(function (pe) {
        if (cards.length >= 20) return;
        var hit = lib.byId[pe.key];
        if (hit && hit.type === 'movie') cards.push(posterCard(hit.item, 'movie', { progress: pe.e.fraction }));
        else if (!hit) {
          // aflevering: serie zoeken via de titel
          var s = null;
          for (var i = 0; i < lib.series.length; i++) if (lib.series[i].title === pe.e.title) { s = lib.series[i]; break; }
          if (s && !cards.some(function (c) { return c._sid === s.id; })) {
            var c = posterCard(s, 'series', { progress: pe.e.fraction });
            c._sid = s.id;
            cards.push(c);
          }
        }
      });
      return rowBlock(L('Continue watching'), cards);
    }
    function myListRow() {
      var lib = Lib.lib, src = Lib.source ? Lib.source.id : '', cards = [];
      D.setList('myList').forEach(function (k) {
        var parts = k.split('|');
        if (parts[0] !== src || cards.length >= 40) return;
        var hit = lib.byId[parts.slice(1).join('|')];
        if (hit && hit.type !== 'channel') cards.push(posterCard(hit.item, hit.type));
      });
      return rowBlock(L('My List'), cards);
    }
    function liveRow() {
      var lib = Lib.lib, src = Lib.source ? Lib.source.id : '', chans = [];
      D.setList('recentChannels').concat(D.setList('favorites')).forEach(function (k) {
        var parts = k.split('|');
        if (parts[0] !== src) return;
        var hit = lib.byId[parts.slice(1).join('|')];
        if (hit && hit.type === 'channel' && chans.indexOf(hit.item) < 0) chans.push(hit.item);
      });
      if (!chans.length) chans = lib.channels.slice(0, 20);
      chans = chans.slice(0, 24);
      return rowBlock(L('Live now'), chans.map(function (c) { return channelCard(c, chans); }), 'live');
    }
    function collectionRows() {
      return D.collectionsFor(B.app.profile.id).map(function (c) {
        var items = Lib.collectionItems(c, 40);
        var chans = items.filter(function (x) { return x.type === 'channel'; }).map(function (x) { return x.item; });
        var cards = items.map(function (x) { return x.type === 'channel' ? channelCard(x.item, chans) : posterCard(x.item, x.type); });
        return { key: 'collection:' + c.id, el: rowBlock(c.name, cards) };
      });
    }
    function render() {
      U.clear(scroller);
      var st = stateBox();
      if (st) { scroller.appendChild(st); return; }
      var lib = Lib.lib;
      var hf = heroFor();
      if (hf) scroller.appendChild(hero(hf));
      var builders = {
        'continue': continueRow, 'mylist': myListRow, 'live': liveRow,
        'top10': function () {
          var out = h('div');
          var ms = Lib.top10(lib.movies), ss = Lib.top10(lib.series);
          var a = rowBlock(B.app.profile.kids ? L('Top 10 kids movies') : L('Top 10 movies today'), ms.map(function (m, i) { return top10Card(m, 'movie', i); }), 'top10row');
          var b = rowBlock(B.app.profile.kids ? L('Top 10 kids series') : L('Top 10 series today'), ss.map(function (s, i) { return top10Card(s, 'series', i); }), 'top10row');
          if (b) out.appendChild(b);
          if (a) out.appendChild(a);
          return out.childNodes.length ? out : null;
        },
        'recent': function () { return rowBlock(L('Recently added'), Lib.recent(lib.movies, 30).map(function (m) { return posterCard(m, 'movie'); })); },
        'movies': function () { return rowBlock(L('Movies'), lib.movies.slice(0, 30).map(function (m) { return posterCard(m, 'movie'); })); },
        'series': function () { return rowBlock(L('Series'), lib.series.slice(0, 30).map(function (s) { return posterCard(s, 'series'); })); },
        'categories': function () {
          var out = h('div');
          lib.movieGroups.slice(0, 8).forEach(function (g) {
            var list = lib.movies.filter(function (m) { return m.group === g; }).slice(0, 30);
            var r = rowBlock(g, list.map(function (m) { return posterCard(m, 'movie'); }));
            if (r) out.appendChild(r);
          });
          lib.seriesGroups.slice(0, 6).forEach(function (g) {
            var list = lib.series.filter(function (s) { return s.group === g; }).slice(0, 30);
            var r = rowBlock(g, list.map(function (s) { return posterCard(s, 'series'); }));
            if (r) out.appendChild(r);
          });
          return out.childNodes.length ? out : null;
        }
      };
      var colls = collectionRows();
      colls.forEach(function (c) { builders[c.key] = function () { return c.el; }; });
      var order = ['continue', 'mylist', 'top10', 'live', 'recent'].concat(colls.map(function (c) { return c.key; })).concat(['categories']);
      var layout = D.homeLayout();
      if (layout) {
        var known = {};
        order = [];
        layout.forEach(function (r) { if (r && r.key && builders[r.key]) { known[r.key] = true; if (r.visible !== false) order.push(r.key); } });
        colls.forEach(function (c) { if (!known[c.key]) order.push(c.key); });
        if (!known.categories && order.indexOf('categories') < 0) order.push('categories');
      }
      order.forEach(function (k) {
        var b = builders[k];
        var node = b ? b() : null;
        if (!node) return;
        if (node.classList.contains('row-block')) scroller.appendChild(node);
        else while (node.firstChild) scroller.appendChild(node.firstChild);
      });
      scroller.appendChild(h('div.page-end'));
    }
    pg.refresh = render;
    pg.onData = function () { pg.stale = false; render(); };
    pg.focusIn = function () { var f = scroller.querySelector('.f.autofocus') || scroller.querySelector('.f'); if (f) { U.focus(f); return true; } return false; };
    pg.onBack = function () {
      if (scroller.scrollTop > 0) { scroller.scrollTop = 0; pg.focusIn(); return true; }
      return false;
    };
    render();
    return pg;
  };

  // MARK: Live TV (categorieën, zenders, nu/straks en de gids)

  S.pages.live = function () {
    var lib = Lib.lib;
    var groupsEl = h('div.lv-groups.sy.fm', { 'data-pad': '120' });
    var chansEl = h('div.lv-chans.sy.fm', { 'data-pad': '160' });
    var preview = h('div.lv-preview');
    var guideBtn = U.button(L('TV guide'), function () { U.push(S.guide(currentList, 0)); }, { icon: 'guide', cls: 'lv-guide-btn' });
    var el = h('div.page.live', [h('div.lv-head', [h('h1', L('Live TV')), guideBtn]), h('div.lv-cols', [groupsEl, chansEl, preview])]);
    var pg = { el: el };
    var currentList = [], currentGroup = null, rendered = 0, lastChan = null;
    var src = Lib.source ? Lib.source.id : '';

    function groupList() {
      var out = [];
      var favs = D.setList('favorites').filter(function (k) { return k.split('|')[0] === src; });
      if (favs.length) out.push({ key: '★', label: L('Favorites') });
      if (D.setList('recentChannels').some(function (k) { return k.split('|')[0] === src; })) out.push({ key: '⟲', label: L('Recent') });
      out.push({ key: '*', label: L('All channels') });
      lib.channelGroups.forEach(function (g) { out.push({ key: g, label: g }); });
      return out;
    }
    function channelsFor(key) {
      function fromKeys(keys) {
        var out = [];
        keys.forEach(function (k) {
          var p = k.split('|');
          if (p[0] !== src) return;
          var hit = lib.byId[p.slice(1).join('|')];
          if (hit && hit.type === 'channel') out.push(hit.item);
        });
        return out;
      }
      if (key === '★') return fromKeys(D.setList('favorites'));
      if (key === '⟲') return fromKeys(D.setList('recentChannels'));
      if (key === '*') return lib.channels;
      return lib.channels.filter(function (c) { return c.group === key; });
    }
    function renderGroups() {
      U.clear(groupsEl);
      var last = B.store.get('liveGroup', null);
      groupList().forEach(function (g) {
        var it = h('div.lv-group.f', g.label);
        if (g.key === last) it.classList.add('autofocus');
        it._key = g.key;
        it.onFocus = function () { if (currentGroup !== g.key) selectGroup(g.key); };
        it.onEnter = function () { var f = chansEl.querySelector('.f'); if (f) U.focus(f); };
        groupsEl.appendChild(it);
      });
    }
    function selectGroup(key) {
      currentGroup = key;
      B.store.set('liveGroup', key);
      Array.prototype.forEach.call(groupsEl.children, function (c) { c.classList.toggle('active', c._key === key); });
      currentList = channelsFor(key);
      rendered = 0;
      U.clear(chansEl);
      renderMore();
      if (currentList.length) showPreview(currentList[0]);
      else { U.clear(preview); preview.appendChild(h('div.dim.lv-empty', L('No channels'))); }
    }
    function renderMore() {
      var end = Math.min(currentList.length, rendered + 120);
      for (var i = rendered; i < end; i++) chansEl.appendChild(chanRow(currentList[i], i));
      rendered = end;
    }
    function chanRow(ch, i) {
      var nn = Lib.nowNext(ch);
      var fav = D.inSet('favorites', D.key(src, ch.id));
      var row = h('div.lv-chan.f', [
        h('div.lv-num', ch.number != null ? String(ch.number) : ''),
        h('div.lv-logo', [U.img(ch.logo, 'logo-img', '')]),
        h('div.lv-text', [h('div.lv-name', [ch.name, fav ? h('span.lv-fav', ' ★') : null]), h('div.lv-now.dim', nn.now ? nn.now.title : '')])
      ]);
      if (nn.now) row.appendChild(h('div.lv-bar', [h('div', { style: { width: Math.round(B.clamp((Date.now() - nn.now.start) / (nn.now.end - nn.now.start), 0, 1) * 100) + '%' } })]));
      row.onFocus = function () {
        showPreview(ch);
        if (i >= rendered - 10 && rendered < currentList.length) renderMore();
      };
      row.onEnter = function () { lastChan = ch; B.player.playChannel(ch, currentList); };
      row.onKey = function (name) {
        if (name === 'yellow' || name === 'info') { toggleFav(ch); return true; }
        return false;
      };
      return row;
    }
    function toggleFav(ch) {
      D.toggleSet('favorites', D.key(src, ch.id));
      U.toast(D.inSet('favorites', D.key(src, ch.id)) ? L('Added to favorites') : L('Removed from favorites'));
    }
    function showPreview(ch) {
      U.clear(preview);
      var nn = Lib.nowNext(ch);
      preview.appendChild(h('div.lv-pv-logo', [U.img(ch.logo, 'logo-img', ch.name)]));
      preview.appendChild(h('div.lv-pv-name', ch.name));
      if (nn.now) {
        preview.appendChild(h('div.lv-pv-time', B.fmtTime(nn.now.start) + ' – ' + B.fmtTime(nn.now.end)));
        preview.appendChild(h('div.lv-pv-title', nn.now.title));
        preview.appendChild(h('div.lv-bar.big', [h('div', { style: { width: Math.round(B.clamp((Date.now() - nn.now.start) / (nn.now.end - nn.now.start), 0, 1) * 100) + '%' } })]));
        if (nn.now.description) preview.appendChild(h('p.lv-pv-desc', nn.now.description));
      } else preview.appendChild(h('div.dim', L('No guide information')));
      if (nn.next) preview.appendChild(h('div.lv-pv-next', [h('b', L('Next') + '  '), B.fmtTime(nn.next.start) + '  ' + nn.next.title]));
      preview.appendChild(h('div.lv-pv-hint.dim', L('OK: watch  ·  Yellow: favorite')));
    }
    function render() {
      lib = Lib.lib; src = Lib.source ? Lib.source.id : '';
      var st = stateBox();
      if (st) { U.clear(groupsEl); U.clear(chansEl); U.clear(preview); chansEl.appendChild(st); return; }
      renderGroups();
      var first = groupsEl.querySelector('.autofocus') || groupsEl.firstChild;
      if (first) selectGroup(first._key);
    }
    pg.refresh = render;
    pg.onEpg = function () { if (U.focused && chansEl.contains(U.focused)) U.focused.onFocus(); };
    pg.focusIn = function () {
      var act = groupsEl.querySelector('.active');
      var f = chansEl.querySelector('.f');
      if (lastChan) {
        var rows = chansEl.querySelectorAll('.f');
        for (var i = 0; i < rows.length && i < currentList.length; i++) if (currentList[i] === lastChan) { U.focus(rows[i]); return true; }
      }
      if (f) { U.focus(f); return true; }
      if (act) { U.focus(act); return true; }
      return false;
    };
    pg.onBack = function () {
      if (chansEl.contains(U.focused)) { var a = groupsEl.querySelector('.active'); if (a) { U.focus(a); return true; } }
      return false;
    };
    render();
    return pg;
  };

  /** Tv-gids: tijdlijn van 2,5 uur, rijen per zender; pijltjes per programma, Enter kijkt. */
  S.guide = function (channels, startIndex) {
    var ROWS = 8, PX_MIN = 10, SPAN = 150;
    var winStart = Math.floor(Date.now() / 1800000) * 1800000;
    var row = Math.max(0, startIndex || 0), top = Math.max(0, row - 2);
    var cursor = Date.now();
    var grid = h('div.gd-grid');
    var times = h('div.gd-times');
    var detail = h('div.gd-detail');
    var el = layer('guide', [h('div.gd-head', [h('h1', L('TV guide')), detail]), times, grid]);
    function progsFor(ch) {
      var list = Lib.epgFor(ch);
      var end = winStart + SPAN * 60000;
      var out = list.filter(function (p) { return p.end > winStart && p.start < end; });
      if (!out.length) out = [{ title: ch.name, start: winStart, end: end, empty: true }];
      return out;
    }
    function draw() {
      U.clear(times); U.clear(grid);
      times.appendChild(h('div.gd-corner'));
      for (var m = 0; m < SPAN; m += 30) times.appendChild(h('div.gd-time', { style: { left: (260 + m * PX_MIN) + 'px' } }, B.fmtTime(winStart + m * 60000)));
      var nowX = (Date.now() - winStart) / 60000 * PX_MIN;
      if (nowX >= 0 && nowX <= SPAN * PX_MIN) grid.appendChild(h('div.gd-nowline', { style: { left: (260 + nowX) + 'px' } }));
      for (var r = top; r < Math.min(channels.length, top + ROWS); r++) {
        var ch = channels[r];
        var line = h('div.gd-row' + (r === row ? '.cur' : ''), [h('div.gd-chan', [h('span.gd-num', ch.number != null ? String(ch.number) : ''), h('span', ch.name)])]);
        progsFor(ch).forEach(function (p) {
          var s = Math.max(p.start, winStart), e = Math.min(p.end, winStart + SPAN * 60000);
          var cell = h('div.gd-prog' + (p.start <= Date.now() && Date.now() < p.end ? '.now' : ''), { style: { left: (260 + (s - winStart) / 60000 * PX_MIN) + 'px', width: Math.max(4, (e - s) / 60000 * PX_MIN - 4) + 'px' } }, p.title);
          if (r === row && p.start <= cursor && cursor < p.end) { cell.classList.add('focused'); showDetail(ch, p); }
          line.appendChild(cell);
        });
        grid.appendChild(line);
      }
    }
    function showDetail(ch, p) {
      U.clear(detail);
      detail.appendChild(h('div.gd-d-title', p.empty ? ch.name : p.title));
      detail.appendChild(h('div.dim', ch.name + (p.empty ? '' : '  ·  ' + B.fmtTime(p.start) + ' – ' + B.fmtTime(p.end))));
      if (p.description) detail.appendChild(h('p.gd-d-desc', p.description));
    }
    function current() {
      var list = progsFor(channels[row]);
      for (var i = 0; i < list.length; i++) if (list[i].start <= cursor && cursor < list[i].end) return { list: list, i: i };
      return { list: list, i: 0 };
    }
    function move(dir) {
      if (dir === 'up' || dir === 'down') {
        row = B.clamp(row + (dir === 'up' ? -1 : 1), 0, channels.length - 1);
        if (row < top) top = row;
        if (row >= top + ROWS) top = row - ROWS + 1;
      } else {
        var c = current();
        if (dir === 'right') {
          var nx = c.list[c.i + 1];
          cursor = nx ? nx.start + 1 : c.list[c.i].end + 1;
        } else {
          var pv = c.list[c.i - 1];
          cursor = pv ? Math.max(pv.start, winStart - SPAN * 60000) + 1 : c.list[c.i].start - 60000;
        }
        while (cursor >= winStart + SPAN * 60000) winStart += 1800000;
        while (cursor < winStart) winStart -= 1800000;
      }
      draw();
    }
    draw();
    return {
      el: el,
      onKey: function (name) {
        if (name === 'left' || name === 'right' || name === 'up' || name === 'down') { move(name); return true; }
        if (name === 'chup') { row = Math.max(0, row - ROWS); top = Math.max(0, row - 2); draw(); return true; }
        if (name === 'chdown') { row = Math.min(channels.length - 1, row + ROWS); top = Math.max(0, row - 2); draw(); return true; }
        if (name === 'enter') { B.player.playChannel(channels[row], channels); return true; }
        return false;
      },
      onBack: function () { U.pop(); return true; }
    };
  };

  // MARK: Films / Series (categorieën links, raster rechts)

  function catalogPage(type) {
    var groupsEl = h('div.ct-groups.sy.fm', { 'data-pad': '120' });
    var gridEl = h('div.ct-grid.sy', { 'data-pad': '60' });
    var title = h('h1', type === 'movie' ? L('Movies') : L('Series'));
    var el = h('div.page.catalog', [h('div.ct-head', [title]), h('div.ct-cols', [groupsEl, gridEl])]);
    var pg = { el: el };
    var list = [], shown = 0, groupKey = null;
    function items() { return type === 'movie' ? Lib.lib.movies : Lib.lib.series; }
    function groups() { return type === 'movie' ? Lib.lib.movieGroups : Lib.lib.seriesGroups; }
    function render() {
      U.clear(groupsEl); U.clear(gridEl);
      var st = stateBox();
      if (st) { gridEl.appendChild(st); return; }
      var all = [{ key: '*', label: L('All') }];
      if (Lib.recent(items(), 1).length) all.push({ key: '+', label: L('Recently added') });
      groups().forEach(function (g) { all.push({ key: g, label: g }); });
      var last = B.store.get('catalog.' + type, null);
      all.forEach(function (g) {
        var it = h('div.ct-group.f', g.label);
        it._key = g.key;
        if (g.key === last) it.classList.add('autofocus');
        it.onFocus = function () { if (groupKey !== g.key) select(g.key); };
        it.onEnter = function () { var f = gridEl.querySelector('.f'); if (f) U.focus(f); };
        groupsEl.appendChild(it);
      });
      var first = groupsEl.querySelector('.autofocus') || groupsEl.firstChild;
      if (first) select(first._key);
    }
    function select(key) {
      groupKey = key;
      B.store.set('catalog.' + type, key);
      Array.prototype.forEach.call(groupsEl.children, function (c) { c.classList.toggle('active', c._key === key); });
      var all = items();
      list = key === '*' ? all : key === '+' ? Lib.recent(all, 60) : all.filter(function (x) { return x.group === key; });
      shown = 0;
      U.clear(gridEl);
      gridEl.scrollTop = 0;
      more();
      if (!list.length) gridEl.appendChild(h('div.state.dim', L('Nothing here yet')));
    }
    function more() {
      var end = Math.min(list.length, shown + 60);
      for (var i = shown; i < end; i++) {
        (function (x, idx) {
          var p = type === 'movie' ? D.progress(x.id) : null;
          var card = posterCard(x, type, { progress: p ? p.fraction : null, onFocus: function () { if (idx >= shown - 12 && shown < list.length) more(); } });
          gridEl.appendChild(card);
        })(list[i], i);
      }
      shown = end;
    }
    pg.refresh = render;
    pg.focusIn = function () { var f = gridEl.querySelector('.f') || groupsEl.querySelector('.active'); if (f) { U.focus(f); return true; } return false; };
    pg.onBack = function () {
      if (gridEl.contains(U.focused)) { var a = groupsEl.querySelector('.active'); if (a) { U.focus(a); return true; } }
      return false;
    };
    render();
    return pg;
  }
  S.pages.movies = function () { return catalogPage('movie'); };
  S.pages.series = function () { return catalogPage('series'); };

  // MARK: Mijn lijst

  S.pages.mylist = function () {
    var gridEl = h('div.ct-grid.full.sy', { 'data-pad': '60' });
    var el = h('div.page.catalog', [h('div.ct-head', [h('h1', L('My List'))]), gridEl]);
    var pg = { el: el };
    function render() {
      U.clear(gridEl);
      var lib = Lib.lib, src = Lib.source ? Lib.source.id : '';
      var n = 0;
      D.setList('myList').forEach(function (k) {
        var p = k.split('|');
        if (p[0] !== src) return;
        var hit = lib.byId[p.slice(1).join('|')];
        if (hit && hit.type !== 'channel') { gridEl.appendChild(posterCard(hit.item, hit.type)); n++; }
      });
      if (!n) gridEl.appendChild(h('div.state', [h('h2', L('Your list is empty')), h('div.dim', L('Add movies and series with “My List” on their page.'))]));
    }
    pg.refresh = render;
    pg.onData = render;
    render();
    return pg;
  };

  // MARK: Zoeken

  S.pages.search = function () {
    var query = h('div.search-query', L('Search'));
    var results = h('div.search-results.sy', { 'data-align': 'top', 'data-pad': '20' });
    var timer = null;
    var kb = U.keyboard({
      layout: 'search', onChange: function (v) {
        query.textContent = v || L('Search');
        query.classList.toggle('placeholder', !v);
        clearTimeout(timer);
        timer = setTimeout(function () { run(v); }, 350);
      }
    });
    query.classList.add('placeholder');
    var el = h('div.page.search', [h('div.search-left', [query, kb]), results]);
    var pg = { el: el };
    function run(v) {
      U.clear(results);
      if (!v.trim()) return;
      var r = Lib.search(v, 30);
      var a = rowBlock(L('Series'), r.series.map(function (s) { return posterCard(s, 'series'); }));
      var b = rowBlock(L('Movies'), r.movies.map(function (m) { return posterCard(m, 'movie'); }));
      var c = rowBlock(L('Live TV'), r.channels.map(function (ch) { return channelCard(ch, r.channels); }), 'live');
      [b, a, c].forEach(function (x) { if (x) results.appendChild(x); });
      if (!a && !b && !c) results.appendChild(h('div.state.dim', L('No results for “%@”', v)));
    }
    pg.focusIn = function () { var k = kb.querySelector('.key'); if (k) { U.focus(k); return true; } return false; };
    pg.refresh = function () { run(kb.getValue()); };
    return pg;
  };

  // MARK: detailpagina (film of serie, met seizoenen en afleveringen)

  S.detail = function (type, x) {
    var src = Lib.source ? Lib.source.id : '';
    var listKey = D.key(src, x.id);
    var plot = h('p.dt-plot', x.plot || '');
    var meta = h('div.dt-meta');
    var buttons = h('div.dt-buttons.fm');
    var seasonsEl = h('div.dt-seasons.sx.fm', { 'data-pad': '40' });
    var epsEl = h('div.dt-eps.sx.fm', { 'data-pad': '80' });
    var epsTrack = h('div.row-track');
    epsEl.appendChild(epsTrack);
    var body = h('div.dt-body.sy', { 'data-pad': '60' }, [
      h('div.dt-info', [h('div.dt-kind', type === 'series' ? L('SERIES') : L('FILM')), h('h1.dt-title', x.title), meta, plot, buttons]),
      type === 'series' ? h('div.dt-episodes', [seasonsEl, epsEl]) : null
    ]);
    var el = layer('detail', [h('div.dt-bg', [U.img(x.poster, 'hero-img')]), h('div.dt-shade'),
      h('div.dt-poster', [U.img(x.poster, 'poster-img', x.title)]), body]);
    var episodes = [], season = null, currentEp = null;

    function metaText(extra) {
      var parts = [];
      if (x.year) parts.push(String(x.year));
      if (x.rating) parts.push('★ ' + (Math.round(x.rating * 10) / 10));
      if (extra) parts = parts.concat(extra);
      if (x.group) parts.push(x.group);
      meta.textContent = parts.join('  ·  ');
    }
    metaText();

    function listButton() {
      var on = D.inSet('myList', listKey);
      var b = U.button(L('My List'), function () {
        D.toggleSet('myList', listKey);
        var nb = listButton();
        buttons.replaceChild(nb, b);
        U.focus(nb);
      }, { icon: on ? 'check' : 'list' });
      return b;
    }
    function drawButtons() {
      U.clear(buttons);
      if (type === 'movie') {
        var p = D.progress(x.id);
        var label = p && p.fraction > 0.02 ? L('Resume') : L('Play');
        buttons.appendChild(U.button(label, function () { B.player.playVOD(B.player.movieItem(x), {}); }, { primary: true, icon: 'play', autofocus: true }));
        if (p && p.fraction > 0.02) {
          buttons.appendChild(U.button(L('Watch from the beginning'), function () { B.player.open(B.player.movieItem(x), { resume: 0 }); }, { icon: 'replay' }));
          buttons.appendChild(h('div.dt-progress', [h('div.card-progress.inline', [h('div', { style: { width: Math.round(p.fraction * 100) + '%' } })]),
            h('span.dim', L('%d min left', Math.max(1, Math.round((p.position / p.fraction - p.position) / 60))))]));
        }
      } else {
        var ep = currentEp;
        var lbl = ep ? (D.progress(ep.id) ? L('Resume') : L('Play')) + '  S' + ep.season + ':E' + ep.number : L('Play');
        buttons.appendChild(U.button(lbl, function () { if (currentEp) playEpisode(currentEp); }, { primary: true, icon: 'play', autofocus: true }));
      }
      buttons.appendChild(listButton());
    }
    function playEpisode(ep) {
      B.player.playVOD(B.player.episodeItem(ep, x), { episodes: episodes, series: x });
    }
    function epWatched(ep) { return D.isWatched(ep.id) || D.isWatched(D.episodeKey(ep.seriesID, ep.season, ep.number)); }
    /** Huidige aflevering: met voortgang (nieuwste), anders de eerste na de laatst bekeken, anders de eerste. */
    function pickCurrent() {
      var best = null, bestAt = 0;
      episodes.forEach(function (e) { var p = D.progress(e.id); if (p && p.updated > bestAt) { best = e; bestAt = p.updated; } });
      if (best) return best;
      var lastWatched = -1;
      episodes.forEach(function (e, i) { if (epWatched(e)) lastWatched = i; });
      if (lastWatched >= 0 && lastWatched < episodes.length - 1) return episodes[lastWatched + 1];
      return episodes[0] || null;
    }
    function seasons() {
      var s = [];
      episodes.forEach(function (e) { if (s.indexOf(e.season) < 0) s.push(e.season); });
      return s.sort(function (a, b) { return a - b; });
    }
    function drawSeasons() {
      U.clear(seasonsEl);
      var track = h('div.row-track');
      seasons().forEach(function (sn) {
        var t = h('div.season-tab.f' + (sn === season ? '.active' : ''), L('Season %d', sn));
        t.onFocus = function () { if (season !== sn) { season = sn; drawSeasons(); drawEpisodes(null); } };
        t.onEnter = function () { var f = epsTrack.querySelector('.f'); if (f) U.focus(f); };
        track.appendChild(t);
      });
      seasonsEl.appendChild(track);
      var act = seasonsEl.querySelector('.active');
      seasonsEl._last = act;
      if (act && U.focused && seasonsEl.contains(U.focused) && U.focused !== act) U.focus(act, true);
    }
    function drawEpisodes(focusEp) {
      U.clear(epsTrack);
      var target = null;
      episodes.filter(function (e) { return e.season === season; }).forEach(function (e) {
        var p = D.progress(e.id);
        var watched = epWatched(e);
        var pct = p ? Math.round(p.fraction * 100) : (watched ? 100 : 0);
        var card = h('div.ep-card.f', [
          h('div.ep-still', [U.img(e.still || x.poster, 'still-img', ''), watched ? h('div.ep-watched', [U.icon('check')]) : null,
            pct ? h('div.card-progress', [h('div', { style: { width: pct + '%' } })]) : null]),
          h('div.ep-title', e.number + '. ' + e.title.replace(/^.*?S\d+\s*E\d+\s*[-:]?\s*/i, '')),
          h('div.ep-sub.dim', [e.durationSeconds ? Math.round(e.durationSeconds / 60) + ' min' : '', pct && !watched ? '  ·  ' + pct + '%' : '']),
          e.plot ? h('div.ep-plot.dim', e.plot) : null
        ]);
        card.onEnter = function () { playEpisode(e); };
        card.onFocus = function () { currentEp = e; };
        if (focusEp && e.id === focusEp.id) target = card;
        epsTrack.appendChild(card);
      });
      // omlaag naar de afleveringen komt uit bij de huidige aflevering
      epsEl._last = target || epsTrack.querySelector('.f');
      return target;
    }
    function loadEpisodes() {
      if (type !== 'series') return;
      epsTrack.appendChild(h('div.state', [U.spinner()]));
      Lib.episodes(x).then(function (r) {
        episodes = r.episodes || [];
        var info = r.info || {};
        if (info.plot && !x.plot) plot.textContent = info.plot;
        if (info.genre) metaText([info.genre]);
        if (!episodes.length) { U.clear(epsTrack); epsTrack.appendChild(h('div.state.dim', L('No episodes found'))); return; }
        currentEp = pickCurrent();
        season = currentEp ? currentEp.season : seasons()[0];
        drawSeasons();
        var card = drawEpisodes(currentEp);
        drawButtons();
        // automatisch naar de huidige aflevering
        if (card) U.reveal(card);
        var play = buttons.querySelector('.f');
        if (play) U.focus(play);
      });
    }
    function loadMovieInfo() {
      if (type !== 'movie') return;
      Lib.movieInfo(x).then(function (info) {
        if (!info) return;
        if (info.plot && !x.plot) plot.textContent = info.plot;
        var extra = [];
        if (info.duration) extra.push(info.duration);
        if (info.genre) extra.push(info.genre);
        metaText(extra);
      });
    }
    drawButtons();
    var s = {
      el: el,
      onShow: function (back) {
        if (back) {
          drawButtons();
          if (type === 'series' && episodes.length) {
            var keep = currentEp; currentEp = pickCurrent() || keep;
            season = currentEp ? currentEp.season : season;
            drawSeasons(); var c = drawEpisodes(currentEp);
            if (c) U.focus(c);
          }
        }
      },
      onBack: function () { U.pop(); return true; }
    };
    loadEpisodes();
    loadMovieInfo();
    return s;
  };

  // MARK: Instellingen

  S.pages.settings = function () {
    var list = h('div.st-list.sy.fm', { 'data-pad': '120' });
    var el = h('div.page.settings', [h('div.ct-head', [h('h1', L('Settings'))]), list]);
    var pg = { el: el };
    function section(t) { list.appendChild(h('div.st-section', t)); }
    function row(label, value, fn, opts) {
      var r = h('div.st-row' + (fn ? '.f' : '.info'), [h('div.st-label', label), value != null ? h('div.st-value', value) : null]);
      r.onEnter = fn;
      if (opts && opts.danger) r.classList.add('danger');
      list.appendChild(r);
      return r;
    }
    function render() {
      U.clear(list);
      var prof = B.app.profile;
      var main = D.isMain(prof);
      section(L('Account'));
      row(L('Signed in as %@', A.email() || ''), null, null);
      row(L('Devices'), null, function () { U.push(S.devices()); });
      if (main) {
        row(L('Watching now'), null, function () { U.push(S.streams()); });
        row(L('Watch history (7 days)'), null, function () { U.push(S.watchLog()); });
      }
      row(L('Sign out'), null, function () {
        U.modal({ title: L('Sign out'), text: L('Sign out of Bingr on this device?'), buttons: [
          { label: L('Sign out'), fn: function () { B.app.signOut(); } }, { label: L('Cancel') }] });
      }, { danger: true });

      section(L('Profile'));
      row(L('Switch profile'), prof.name, function () { B.app.switchProfile(); });
      var accent = D.accentKey(prof);
      row(L('Accent color'), L(accent.charAt(0).toUpperCase() + accent.slice(1)), function () {
        U.choose(L('Accent color'), Object.keys(D.accents).map(function (k) { return { value: k, label: L(k.charAt(0).toUpperCase() + k.slice(1)) }; }), accent, function (v) {
          D.setAccent(prof.id, v).then(function () { B.app.applyAccent(); render(); });
          B.app.applyAccent(v); render();
        });
      });

      section(L('Server'));
      var src = Lib.source;
      row(L('Active server'), src ? src.name : L('None'), function () {
        var opts = D.sources.map(function (s) {
          var ok = D.supportedKinds.indexOf(s.kind) >= 0;
          return { value: s.id, label: s.name + (ok ? '' : '  (' + L('not supported on Samsung TV') + ')') };
        });
        if (!opts.length) { U.toast(L('No servers yet')); return; }
        U.choose(L('Active server'), opts, src ? src.id : null, function (id) {
          var s2 = D.sourceById(id);
          if (!s2 || D.supportedKinds.indexOf(s2.kind) < 0) { U.toast(L('This kind of server is not supported on Samsung TV yet.')); return; }
          D.setActiveSource(id); Lib.select(s2); render();
        });
      });
      row(L('Refresh now'), null, function () { if (Lib.source) { Lib.select(Lib.source, true); U.toast(L('Updating…')); } });

      section(L('App'));
      var lang = D.shared('appLanguage', '') || '';
      var langLabel = '';
      B.languages.forEach(function (l) { if (l[0] === lang) langLabel = l[0] ? l[1] : L('Automatic'); });
      row(L('Language'), langLabel, function () {
        U.choose(L('Language'), B.languages.map(function (l) { return { value: l[0], label: l[0] ? l[1] : L('Automatic') }; }), lang, function (v) {
          D.setShared('appLanguage', v);
          B.setLanguage(v);
          B.app.rebuild();
        });
      });
      var resume = B.bool(D.shared('resumeLastChannel', false)) === true;
      row(L('Resume last channel'), resume ? L('On') : L('Off'), function () { D.setShared('resumeLastChannel', !resume); render(); focusLabel(L('Resume last channel')); });
      var zd = D.local('zapDelay', 600);
      row(L('Zap delay'), (zd / 1000) + ' s', function () {
        U.choose(L('Zap delay'), [0, 300, 600, 1000, 1500].map(function (v) { return { value: v, label: (v / 1000) + ' s' }; }), zd, function (v) { D.setLocal('zapDelay', v); render(); });
      });

      section(L('Parental lock'));
      var pcOn = B.bool(D.shared('parentalControl', false)) === true;
      var mainPin = D.pinFor(D.mainProfile());
      if (main) {
        row(L('Parental lock'), pcOn ? L('On') : L('Off'), function () {
          var go = function () { D.setShared('parentalControl', !pcOn); setTimeout(function () { Lib.applyFilters(); render(); }, 50); };
          if (mainPin && pcOn) U.askPIN(L('Enter PIN'), function (x) { return x === mainPin; }, go); else go();
        });
      } else row(L('Parental lock'), pcOn ? L('On') : L('Off'), null);
      if (mainPin) {
        row(PC.isUnlocked() ? L('Lock now') : L('Unlock with PIN'), null, function () {
          if (PC.isUnlocked()) { PC.lock(); render(); return; }
          U.askPIN(L('Enter PIN'), function (x) { return x === mainPin; }, function () { PC.unlock(); render(); });
        });
      }
      section(L('About'));
      row('Bingr ' + B.config.version + ' (build ' + B.config.build + ')', 'Samsung TV · Tizen', null);
    }
    function focusLabel(t) {
      var rows = list.querySelectorAll('.st-row');
      for (var i = 0; i < rows.length; i++) if (rows[i].firstChild.textContent === t) { U.focus(rows[i]); return; }
    }
    pg.refresh = render;
    pg.onShow = render;
    render();
    return pg;
  };

  /** Lijstscherm met laden (apparaten, nu kijken, geschiedenis). */
  function listScreen(title, load, drawRow, empty) {
    var list = h('div.st-list.sy.fm', { 'data-pad': '120' });
    var head = h('div.ls-head', [h('h1', title)]);
    var el = layer('listscreen', [head, list]);
    function refresh() {
      U.clear(list);
      list.appendChild(h('div.state', [U.spinner()]));
      load().then(function (r) {
        U.clear(list);
        if (r.header) head.appendChild(h('div.dim.ls-sub', r.header));
        if (!r.items.length) list.appendChild(h('div.state.dim', empty));
        r.items.forEach(function (it) { list.appendChild(drawRow(it, refresh)); });
        U.focusFirst(list);
      }, function (e) { U.clear(list); list.appendChild(h('div.state.dim', e.message || L('Something went wrong. Please try again.'))); });
    }
    var s = { el: el, onShow: function (back) { if (!back) refresh(); }, onBack: function () { U.pop(); return true; } };
    return s;
  }
  function relTime(ms) {
    if (!ms) return '';
    var d = (Date.now() - ms) / 1000;
    if (d < 120) return L('just now');
    if (d < 3600) return L('%d min ago', Math.round(d / 60));
    if (d < 86400) return B.fmtTime(ms);
    if (d < 2 * 86400) return L('yesterday');
    return new Date(ms).toLocaleDateString();
  }
  S.devices = function () {
    return listScreen(L('Devices'), function () {
      return A.listDevices().then(function (r) { return { items: r.devices, header: L('%d of %d devices', r.devices.length, r.limit) }; });
    }, function (d, refresh) {
      var r = h('div.st-row.f.dev', [U.icon(platformIcon(d.platform)), h('div.st-label', [h('div', d.name + (d.current ? '  ·  ' + L('This device') : '')),
        h('div.dim', d.platform + '  ·  ' + L('Last used %@', d.current ? L('Now') : relTime(d.lastSeenAt)))])]);
      r.onEnter = function () {
        U.modal({ title: d.name, text: L('Remove %@? It will be signed out.', d.name), buttons: [
          { label: L('Remove'), fn: function () {
            if (d.current) { B.app.signOut(); return; }
            A.removeDevice(d.id).then(refresh, function (e) { U.toast(e.message); });
          } }, { label: L('Cancel') }] });
      };
      return r;
    }, '');
  };
  S.streams = function () {
    return listScreen(L('Watching now'), function () {
      return A.listStreams().then(function (r) { return { items: r.streams, header: L('%d of %d watching now', r.streams.length, r.limit) }; });
    }, function (s) {
      var mins = s.startedAt ? Math.round((Date.now() - s.startedAt) / 60000) : 0;
      return h('div.st-row.f', [U.icon(platformIcon(s.platform)), S.rowAvatar(s.profileId, s.profileName), h('div.st-label', [h('div', s.title), h('div.dim', s.deviceName + '  ·  ' + s.profileName + '  ·  ' + mins + ' min')])]);
    }, L('Nobody is watching right now.'));
  };
  S.watchLog = function () {
    return listScreen(L('Watch history (7 days)'), function () {
      return A.watchLog(7).then(function (e) { return { items: e }; });
    }, function (e) {
      var d = new Date(e.startedAt);
      return h('div.st-row.f', [h('div.wl-time', d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' }) + '  ' + B.fmtTime(e.startedAt)),
        S.rowAvatar(e.profileId, e.profileName), h('div.st-label', [h('div', e.title), h('div.dim', e.profileName + '  ·  ' + e.deviceName + '  ·  ' + e.minutes + ' min')])]);
    }, L('No history yet'));
  };
})();
