/*
 * Extra schermen: servers beheren (toevoegen, wijzigen, verwijderen, hoofdserver; put_sources zoals de webconsole),
 * inloggen bij Plex (plex.tv/link), Jellyfin/Emby en Stremio op de tv, Trakt (koppelcode), OpenSubtitles en de
 * lijst met herinneringen.
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui, D = B.data, Lib = B.lib, S = B.screens, M = B.media;
  var h = U.h;
  var L = function () { return B.L.apply(null, arguments); };
  function lower(s) { return String(s || '').toLowerCase(); }
  function host(u) { var m = /^[a-z]+:\/\/([^\/?#]+)/i.exec(String(u || '')); return m ? m[1] : ''; }
  function layer(cls, kids) { return h('div.layer' + (cls ? '.' + cls : ''), kids); }

  // MARK: servers

  S.servers = function () {
    var main = D.isMain(B.app.profile);
    var s = S.listScreen(L('Your servers'), function () {
      return D.syncSources().catch(B.noop).then(function () {
        return { items: D.sources.slice(), header: main ? null : L('The server and all settings are managed in the main account.') };
      });
    }, function (src, refresh) {
      var isMainSrc = lower(src.id) === lower(D.mainSourceID);
      var isActive = Lib.source && lower(Lib.source.id) === lower(src.id);
      var r = h('div.st-row.f', [U.icon(src.kind === 'Plex' || src.kind === 'Jellyfin' || src.kind === 'Emby' ? 'movies' : src.kind === 'Stremio' ? 'play' : 'tv'),
        h('div.st-label', [h('div', [src.name || '?', isMainSrc ? h('span.badge', L('Main server')) : null, isActive ? h('span.badge.dim-badge', L('Active')) : null]),
          h('div.dim', src.kind + (host(src.serverURL || src.m3uURL) ? '  ·  ' + host(src.serverURL || src.m3uURL) : ''))])]);
      r.onEnter = function () {
        var buttons = [];
        if (!isActive) buttons.push({ label: L('Watch from this server'), fn: function () { D.setActiveSource(src.id); Lib.select(D.sourceById(src.id)); refresh(); } });
        if (main) buttons.push({ label: L('Edit server'), fn: function () { U.push(S.serverEdit(src, refresh)); } });
        if (main && !isMainSrc) buttons.push({ label: L('Make main server'), fn: function () { D.setMainSource(src.id).then(refresh, function (e) { U.toast(e.message); }); } });
        buttons.push({ label: L('Cancel') });
        U.modal({ title: src.name, text: src.kind, buttons: buttons });
      };
      return r;
    }, L('No servers yet'), function (track, refresh) {
      if (!main) return;
      var add = h('div.st-row.f.add-row', [U.icon('list'), h('div.st-label', L('Add server'))]);
      add.onEnter = function () {
        U.choose(L('Choose the playlist type'), D.kinds.map(function (k) { return { value: k, label: k }; }), null, function (k) { U.push(S.serverEdit({ kind: k }, refresh)); });
      };
      track.appendChild(add);
    });
    return s;
  };

  /**
   * Server toevoegen of wijzigen. src = bestaande bron of {kind} voor een nieuwe. Wachtwoorden worden nooit
   * getoond; leeg laten = ongewijzigd (zoals de webconsole).
   */
  S.serverEdit = function (src, onSaved) {
    var isNew = !src || !src.id;
    var kind = (src && src.kind) || 'Xtream Codes';
    var o = isNew ? { id: B.uuid().toUpperCase(), kind: kind, name: '', avatar: { symbol: 'tv', colorIndex: Math.floor(Math.random() * 8) },
      m3uURL: '', epgURL: '', serverURL: '', username: '', macAddress: '', preferHLS: false, secrets: {} } : B.copy(src);
    var password = '';           // nieuw wachtwoord (leeg = ongewijzigd)
    var newSecrets = {};         // gewijzigde geheimen (sessie, tokens)
    var makeMain = isNew ? !D.sources.length : lower(o.id) === lower(D.mainSourceID);
    var list = S.vScroller('.st-list.fm', { 'data-pad': '120' });
    var err = h('div.se-error');
    var head = h('div.ls-head', [h('h1', isNew ? L('Add server') : L('Edit server')), h('div.dim.ls-sub', kind)]);
    var el = layer('listscreen.server-edit', [head, list, err]);
    var busy = false;

    function field(label, value, placeholder, fn) {
      var r = h('div.st-row.f', [h('div.st-label', label), h('div.st-value', value || placeholder || '')]);
      if (!value) r.lastChild.classList.add('placeholder');
      r.onEnter = fn;
      r._label = label;
      list.track.appendChild(r);
      return r;
    }
    function edit(label, key, layoutName, opts) {
      opts = opts || {};
      var shown = opts.secret ? (password ? new Array(password.length + 1).join('•') : (isNew ? '' : L('unchanged'))) : o[key];
      field(label, shown, opts.placeholder, function () {
        U.editText({ title: label, value: opts.secret ? '' : (o[key] || ''), layout: layoutName, secret: !!opts.secret,
          placeholder: opts.placeholder, hint: opts.hint }, function (v) {
          if (opts.secret) password = v; else o[key] = String(v).trim();
          draw(label);
        });
      });
    }
    function toggle(label, on, fn) {
      var r = field(label, on ? L('On') : L('Off'), null, fn);
      r.lastChild.classList.remove('placeholder');
    }
    function draw(focusLabel) {
      U.clear(list.track);
      edit(L('Name'), 'name', 'text', { placeholder: L('e.g. My playlist') });
      if (kind === 'M3U / M3U8') {
        edit('M3U URL', 'm3uURL', 'url', { placeholder: L('URL to your M3U8 file') });
        edit(L('EPG (optional)'), 'epgURL', 'url', { placeholder: L('http://your-domain/epg.xml (optional)') });
      } else if (kind === 'Xtream Codes') {
        edit(L('Server'), 'serverURL', 'url', { placeholder: 'http://server:8080' });
        edit(L('Username'), 'username', 'text');
        edit(L('Password'), 'password', 'text', { secret: true });
        toggle(L('Live TV as HLS (.m3u8) instead of .ts'), !!o.preferHLS, function () { o.preferHLS = !o.preferHLS; draw(L('Live TV as HLS (.m3u8) instead of .ts')); });
        edit(L('EPG (optional)'), 'epgURL', 'url', { placeholder: L('empty = EPG from the server') });
      } else if (kind === 'Stalker / MAC-portal') {
        edit(L('Portal URL'), 'serverURL', 'url', { placeholder: 'http://portal:8080/c/' });
        edit(L('MAC address'), 'macAddress', 'mac', { placeholder: '00:1A:79:…' });
        edit(L('EPG (optional)'), 'epgURL', 'url', { placeholder: L('http://your-domain/epg.xml (optional)') });
      } else if (kind === 'Jellyfin' || kind === 'Emby') {
        edit(L('Server'), 'serverURL', 'url', { placeholder: 'http://192.168.1.10:8096' });
        edit(L('Username'), 'username', 'text');
        edit(L('Password'), 'password', 'text', { secret: true, hint: L('Only used to sign in; Bingr keeps the session, not the password.') });
        var sess = newSecrets.session ? JSON.parse(newSecrets.session) : M.session(o);
        field(L('Status'), sess ? L('Signed in') : L('Not signed in'), null, null).classList.remove('f');
      } else if (kind === 'Plex') {
        var ps = newSecrets.session ? JSON.parse(newSecrets.session) : M.session(o);
        field(L('Sign in with Plex'), ps ? L('Signed in with Plex') + (ps.serverURI ? '  ·  ' + host(ps.serverURI) : '') : L('Not signed in'), null, plexLink);
      } else if (kind === 'Stremio') {
        edit(L('Add-ons (optional)'), 'm3uURL', 'url', { placeholder: 'https://…/manifest.json', hint: L('Separate several add-on URLs with a comma. Cinemeta is always included.') });
        edit(L('Stremio account (optional)') + ': ' + L('Email'), 'username', 'text');
        edit(L('Password'), 'password', 'text', { secret: true });
      }
      toggle(L('Main server'), makeMain, function () { makeMain = !makeMain; draw(L('Main server')); });
      var save = h('div.st-row.f.primary-row', [U.icon('check'), h('div.st-label', L('Save'))]);
      save.onEnter = doSave;
      save._label = L('Save');
      list.track.appendChild(save);
      if (!isNew) {
        var del = h('div.st-row.f.danger', [h('div.st-label', L('Delete server'))]);
        del.onEnter = doDelete;
        list.track.appendChild(del);
      }
      var rows = list.track.querySelectorAll('.st-row.f'), target = null;
      for (var i = 0; i < rows.length; i++) if (focusLabel && rows[i]._label === focusLabel) target = rows[i];
      if (target) U.focus(target, true);
      else if (rows.length) U.focus(rows[0], true);
    }
    /** Plex: koppelcode tonen, wachten op plex.tv/link, dan de server zoeken. */
    function plexLink() {
      var code = h('div.qr-code', '····');
      var status = h('div.login-wait', [U.spinner(), h('span', L('Waiting for confirmation…'))]);
      var box = h('div.modal-box.link-box', [h('h2', L('Sign in with Plex')), h('p.modal-text', L('Go to plex.tv/link and enter this code:')), code, status]);
      var cancelled = false;
      var s = { modal: true, el: h('div.layer.modal', [box]), onBack: function () { cancelled = true; U.pop(); } };
      U.push(s);
      M.Plex.createPin().then(function (pin) {
        code.textContent = pin.code;
        var tries = 0;
        function poll() {
          if (cancelled) return;
          if (++tries > 150) { status.textContent = L('The code has expired. Please try again.'); return; }
          M.Plex.checkPin(pin).then(function (token) {
            if (cancelled) return;
            if (!token) { setTimeout(poll, 2000); return; }
            status.textContent = L('Signing in to server…');
            return M.Plex.discoverServer(token).then(function (sess) {
              newSecrets.password = token;
              newSecrets.session = M.sessionString(sess);
              o.serverURL = sess.serverURI;
              if (!o.name) o.name = 'Plex';
              if (!cancelled) { U.pop(); draw(L('Sign in with Plex')); U.toast(L('Signed in with Plex')); }
            });
          }).catch(function (e) { if (!cancelled) status.textContent = e.message || L('Something went wrong. Please try again.'); });
        }
        setTimeout(poll, 2000);
      }, function (e) { status.textContent = e.message || L('Something went wrong. Please try again.'); });
    }
    function fail(msg) { busy = false; err.textContent = msg; U.toast(msg, 4000); }
    /** Controleren zoals de webconsole, inloggen waar nodig (Jellyfin/Emby/Stremio), dan put_sources. */
    function doSave() {
      if (busy) return;
      err.textContent = '';
      o.name = String(o.name || '').trim();
      if (!o.name) { fail(L('Name this server')); return; }
      var sec = B.assign({}, newSecrets);
      var pre = Promise.resolve();
      if (kind === 'M3U / M3U8') {
        if (!/^https?:\/\//i.test(o.m3uURL)) { fail(L('Invalid URL')); return; }
      } else if (kind === 'Xtream Codes') {
        if (!o.serverURL || !o.username || (isNew && !password)) { fail(L('Fill in all fields.')); return; }
        if (password) sec.password = password;
        var test = B.assign({}, o);
        pre = new B.providers.Xtream(test, password || D.secret(o, 'password')).authenticate().then(B.noop, function (e) { throw e; });
      } else if (kind === 'Stalker / MAC-portal') {
        if (!o.serverURL || !o.macAddress) { fail(L('Fill in all fields.')); return; }
        o.macAddress = String(o.macAddress).toUpperCase();
      } else if (kind === 'Jellyfin' || kind === 'Emby') {
        if (!o.serverURL || !o.username) { fail(L('Fill in all fields.')); return; }
        if (password) {
          pre = new M.Jellyfin(o, null).login(o.username, password).then(function (sess) { sec.session = M.sessionString(sess); sec.password = null; });
        } else if (!M.session(o) && !sec.session) { fail(L('Not signed in. Edit the playlist and sign in again.')); return; }
      } else if (kind === 'Plex') {
        if (!sec.session && !M.session(o)) { fail(L('Not signed in. Edit the playlist and sign in again.')); return; }
      } else if (kind === 'Stremio') {
        if (o.username && password) pre = M.Stremio.login(o.username, password).then(function (k) { sec.password = k; });
      }
      busy = true;
      U.toast(L('Signing in to server…'));
      pre.then(function () {
        o.secrets = sec;
        return D.saveSource(o, { makeMain: makeMain });
      }).then(function (saved) {
        busy = false;
        U.toast(L('Saved'));
        B.idb.del('lib:' + saved.id);
        var act = D.activeSource();
        if (makeMain || (Lib.source && lower(Lib.source.id) === lower(saved.id)) || !Lib.source) {
          if (makeMain) B.store.del('activeSource');
          Lib.select(D.sourceById(saved.id) || act, true);
        }
        U.pop();
        if (onSaved) onSaved();
      }, function (e) {
        busy = false;
        var msg = (e && e.message) || L('Something went wrong. Please try again.');
        if (kind === 'Xtream Codes' && !sec.session) {
          U.modal({ title: L("Couldn't sign in to the server. Check the server, username and password."), text: msg, buttons: [
            { label: L('Save anyway'), fn: function () { o.secrets = sec; busy = true; D.saveSource(o, { makeMain: makeMain }).then(function () { busy = false; U.pop(); if (onSaved) onSaved(); }, function (e2) { fail(e2.message); }); } },
            { label: L('Cancel') }] });
          return;
        }
        fail(msg);
      });
    }
    function doDelete() {
      U.modal({ title: L('Delete server'), text: L('Profiles keep their own lists. Channels, movies and series of this server disappear from the app.'), buttons: [
        { label: L('Delete server'), fn: function () {
          D.deleteSource(o.id).then(function () {
            U.pop();
            if (Lib.source && lower(Lib.source.id) === lower(o.id)) Lib.select(D.activeSource());
            if (onSaved) onSaved();
          }, function (e) { U.toast(e.message); });
        } }, { label: L('Cancel') }] });
    }
    var s = { el: el, onBack: function () { U.pop(); return true; }, onShow: function (back) { if (!back) draw(null); } };
    return s;
  };

  // MARK: Trakt

  S.traktMenu = function (onChange) {
    var T = B.trakt;
    if (!T.ready()) { U.toast(L('Trakt: could not connect')); return; }
    if (T.isLoggedIn()) {
      var scrob = T.scrobbleEnabled();
      U.modal({ title: 'Trakt', text: L('Signed in as %@', T.username() || 'Trakt') + '\n\n' + L("What you watch in Bingr is scrobbled automatically. What you've watched on Trakt (or in another app) is checked off here."),
        buttons: [
          { label: L('Sync now'), fn: function () { U.toast(L('Syncing with Trakt…')); T.refresh(true).then(function () { U.toast(L('Trakt synced %@', B.fmtTime(Date.now()))); }); } },
          { label: L('Scrobble') + ': ' + (scrob ? L('On') : L('Off')), fn: function () { D.setShared('traktScrobble', !scrob); if (onChange) onChange(); } },
          { label: L('Sign out of Trakt'), fn: function () { T.logout().then(function () { if (onChange) onChange(); }); } },
          { label: L('Close') }] });
      return;
    }
    var code = h('div.qr-code', '········');
    var qr = h('div.qr-box.small');
    var urlEl = h('b', 'trakt.tv/activate');
    var status = h('div.login-wait', [U.spinner(), h('span', L('Waiting for confirmation…'))]);
    var box = h('div.modal-box.link-box', [h('h2', L('Connect Trakt')), h('div.link-cols', [qr, h('div', [h('p.modal-text', [L('Or go to'), ' ', urlEl, ' ', L('and enter this code:')]), code, status])])]);
    var job = null;
    var s = { modal: true, el: h('div.layer.modal', [box]), onBack: function () { if (job) job.cancel(); U.pop(); } };
    U.push(s);
    T.startLogin().then(function (c) {
      code.textContent = c.userCode;
      urlEl.textContent = String(c.url).replace(/^https?:\/\//, '');
      S.drawQR(qr, c.url, 240);
      job = T.finishLogin(c);
      return job;
    }).then(function () {
      U.pop();
      U.toast(L('Signed in as %@', T.username() || 'Trakt'));
      if (onChange) onChange();
    }, function (e) { status.textContent = (e && e.message) || L('Something went wrong. Please try again.'); });
  };

  // MARK: OpenSubtitles

  S.openSubtitlesMenu = function (onChange) {
    var OS = B.opensubtitles;
    if (!OS.ready()) { U.toast(L('Something went wrong. Please try again.')); return; }
    if (OS.isLoggedIn()) {
      U.modal({ title: 'OpenSubtitles', text: L('Signed in as %@', OS.username()), buttons: [
        { label: L('Sign out'), fn: function () { OS.logout().then(function () { if (onChange) onChange(); }); } }, { label: L('Close') }] });
      return;
    }
    U.editText({ title: L('Sign in to OpenSubtitles') + ': ' + L('Username'), layout: 'text', hint: L('No account yet? Create one for free at opensubtitles.com.') }, function (user) {
      if (!user) return;
      U.editText({ title: L('Password'), layout: 'text', secret: true }, function (pw) {
        if (!pw) return;
        U.toast(L('Signing in…'));
        OS.login(user.trim(), pw).then(function () {
          U.toast(L('Signed in as %@', user.trim()));
          if (onChange) onChange();
        }, function (e) { U.toast((e && e.message) || L('Incorrect email or password.'), 4000); });
      });
    });
  };

  // MARK: herinneringen

  S.remindersList = function () {
    return S.listScreen(L('Reminders'), function () {
      return Promise.resolve({ items: B.reminders.list() });
    }, function (r, refresh) {
      var d = new Date(r.start);
      var row = h('div.st-row.f', [U.icon('bell'), h('div.wl-time', d.toLocaleDateString(B.lang, { weekday: 'short', day: 'numeric', month: 'short' }) + '  ' + B.fmtTime(r.start)),
        h('div.st-label', [h('div', r.title), h('div.dim', r.channelName)])]);
      row.onEnter = function () {
        U.modal({ title: r.title, text: r.channelName + '  ·  ' + B.fmtTime(r.start), buttons: [
          { label: L('Remove reminder'), fn: function () { B.reminders.remove(r.id); refresh(); } }, { label: L('Cancel') }] });
      };
      return row;
    }, L('No reminders yet. Open a program in the TV guide and choose “Remind me”.'));
  };

  /**
   * Support (build 75): de tv kan niet betrouwbaar een browser openen, dus een QR-code met het adres van
   * https://support.bingr.online (scannen met de telefoon).
   */
  S.SUPPORT_URL = 'https://support.bingr.online';
  S.support = function () {
    var qr = h('div.qr-box.small');
    var box = h('div.support-cols', [qr, h('div', [
      h('p.modal-text', L("Questions or problems? Scan the QR code with your phone's camera to open the Bingr support page.")),
      h('div.support-url', S.SUPPORT_URL.replace(/^https?:\/\//, ''))
    ])]);
    var s = U.modal({ title: L('Support'), content: box, buttons: [{ label: L('Close') }] });
    S.drawQR(qr, S.SUPPORT_URL, 240);
    return s;
  };

  /** QR-code tekenen in een vak (gedeeld met het inlogscherm). */
  S.drawQR = function (box, url, size) {
    U.clear(box);
    try {
      var q = window.qrcode(0, 'M');
      q.addData(url);
      q.make();
      var n = q.getModuleCount();
      size = size || 360;
      var cell = Math.floor(size / (n + 4));
      var canvas = document.createElement('canvas');
      canvas.width = canvas.height = cell * (n + 4);
      var g = canvas.getContext('2d');
      g.fillStyle = '#fff'; g.fillRect(0, 0, canvas.width, canvas.height);
      g.fillStyle = '#000';
      for (var r = 0; r < n; r++) for (var c = 0; c < n; c++) if (q.isDark(r, c)) g.fillRect((c + 2) * cell, (r + 2) * cell, cell, cell);
      box.appendChild(canvas);
    } catch (e) { box.appendChild(h('div', url)); }
  };
})();
