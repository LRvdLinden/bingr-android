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
      h('p.login-or', [L('Or go to'), ' ', h('b', 'bingr.online/koppelen'), ' ', L('and enter this code:')]),
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
    // build 97: zelfde tekenfunctie als Support (S.drawQR in extras.js), die qrcode.js pas nu laadt
    function drawQR(url) {
      U.clear(qrBox);
      if (!url) { qrBox._qr = null; qrBox.appendChild(U.spinner()); return; }
      S.drawQR(qrBox, url, 360);
    }
    drawQR(null);
    B.loadQR().catch(B.noop);
    var s = {
      el: el,
      onShow: function () {
        if (flow) return;
        flow = A.runTvFlow({
          onCode: function (c) { code.textContent = c ? A.displayCode(c.code) : '···-···'; drawQR(c ? c.url : null); },
          // build 82: geblokkeerd account blijft staan, ook als een nieuwe code de fout wist
          onError: function (m) { err.textContent = m || (A.blockedNotice() ? A.bannedMessage() : ''); }
        });
        if (A.blockedNotice()) err.textContent = A.bannedMessage();
        flow.promise.then(function () { finish(); });
      },
      // build 97: eerste Terug sluit niet meteen; nog een keer Terug (met melding), zoals op Home
      onBack: function () { B.app.confirmExit(); return true; },
      destroy: function () { if (flow) flow.cancel(); }
    };
    return s;
  };

  /** E-mail en wachtwoord met het schermtoetsenbord: inloggen, account maken en wachtwoord vergeten (ACCOUNT.md). */
  S.emailLogin = function (onDone) {
    var email = '', pw = '', field = 'email', signup = false, showPw = false;
    var emailBox = h('div.input.f.autofocus'), pwBox = h('div.input.f');
    // account maken: voor- en achternaam (verplicht; user_metadata first_name / last_name, voor de aanhef in de e-mails).
    // Typen met het gewone teksttoetsenbord (U.editText): dat heeft een spatie, het e-mailtoetsenbord niet.
    var firstName = '', lastName = '';
    var firstBox = h('div.input.f.nm'), lastBox = h('div.input.f.nm');
    var nameRow = h('div.email-names', [firstBox, lastBox]);
    function cleanName(s) {
      var v = String(s == null ? '' : s).replace(/[\u0000-\u001f\u007f<>]/g, '').replace(/\s+/g, ' ').trim();
      return Array.from(v).slice(0, 50).join('').trim();
    }
    function editName(which) {
      var first = which === 'first';
      U.editText({ title: first ? L('First name') : L('Last name'), value: first ? firstName : lastName, layout: 'text', placeholder: first ? L('First name') : L('Last name') }, function (v) {
        if (first) firstName = cleanName(v); else lastName = cleanName(v);
        err.textContent = ''; draw();
      });
    }
    firstBox.onEnter = function () { editName('first'); };
    lastBox.onEnter = function () { editName('last'); };
    var err = h('div.login-error');
    var title = h('h1');
    var kb = U.keyboard({ layout: 'email', onChange: function (v) { if (field === 'email') email = v; else pw = v; draw(); } });
    function draw() {
      title.textContent = signup ? L('Create account') : L('Sign In');
      nameRow.style.display = signup ? '' : 'none';
      firstBox.textContent = firstName || L('First name');
      firstBox.classList.toggle('placeholder', !firstName);
      lastBox.textContent = lastName || L('Last name');
      lastBox.classList.toggle('placeholder', !lastName);
      emailBox.textContent = email || L('Email');
      emailBox.classList.toggle('placeholder', !email);
      pwBox.textContent = pw ? (showPw ? pw : new Array(pw.length + 1).join('•')) : L('Password');
      pwBox.classList.toggle('placeholder', !pw);
      emailBox.classList.toggle('active', field === 'email');
      pwBox.classList.toggle('active', field === 'pw');
      mainBtn.querySelector('.lbl').textContent = signup ? L('Create account') : L('Sign In');
      showBtn.querySelector('.lbl').textContent = showPw ? L('Hide') : L('Show');
      switchBtn.querySelector('.lbl').textContent = signup ? L('Already have an account?') + ' ' + L('Sign in.') : L('New to Bingr?') + ' ' + L('Sign up now.');
    }
    emailBox.onFocus = function () { field = 'email'; kb.setValue(email); draw(); };
    pwBox.onFocus = function () { field = 'pw'; kb.setValue(pw); draw(); };
    emailBox.onEnter = function () { U.focus(kb.querySelector('.key')); };
    pwBox.onEnter = emailBox.onEnter;
    var busy = false;
    function validEmail() { return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim()); }
    function auth(path, body) {
      return B.http(B.config.supabaseUrl + path, {
        method: 'POST', headers: { 'apikey': B.config.supabaseKey, 'Content-Type': 'application/json' }, body: body, timeout: 20000
      });
    }
    function submit() {
      if (busy) return;
      if (signup && !firstName) { err.textContent = L('Enter your first name.'); return; }
      if (signup && !lastName) { err.textContent = L('Enter your last name.'); return; }
      if (!validEmail()) { err.textContent = L('Enter a valid email address.'); return; }
      // build 141: nieuw wachtwoord minimaal 8 tekens (server); inloggen met een ouder, korter wachtwoord blijft kunnen
      if (signup && pw.length < 8) { err.textContent = L('Password must be at least 8 characters.'); return; }
      if (!pw) { err.textContent = L('Incorrect email or password.'); return; }
      busy = true; err.textContent = '';
      var req = signup ? auth('/auth/v1/signup', { email: email.trim(), password: pw, data: { lang: B.lang, first_name: firstName, last_name: lastName } })
        // build 141: inloggen via de functie login (blokkade per account); bestaat die niet (404), dan de directe route
        : auth('/functions/v1/login', { email: email.trim(), password: pw, device_id: A.deviceId(), platform: B.config.platform }).then(function (r) {
          if (r.status !== 404) return r;
          return auth('/auth/v1/token?grant_type=password', { email: email.trim(), password: pw });
        });
      req.then(function (r) {
        busy = false;
        var j = r.json || {};
        if (r.status >= 200 && r.status < 300 && j.access_token) {
          B.account._store(j, email.trim());
          U.pop();
          onDone();
          return;
        }
        var codeErr = j.error_code || j.code || j.error;
        // build 82: door de beheerder geblokkeerd
        if (A.isBanned(r)) { err.textContent = A.bannedMessage(); return; }
        if (signup && r.status >= 200 && r.status < 300) {
          // account gemaakt, maar het e-mailadres moet eerst bevestigd worden
          err.textContent = L('Check your inbox to confirm your email address, then sign in.');
          signup = false; pw = ''; draw();
          return;
        }
        var retry = B.int(j.retry_after);
        if ((r.status === 429 || codeErr === 'too_many_attempts' || codeErr === 'over_request_rate_limit') && retry > 0) err.textContent = L('Too many attempts. Try again in %d minutes.', Math.max(1, Math.ceil(retry / 60)));
        else if (r.status === 429 || codeErr === 'too_many_attempts' || codeErr === 'over_request_rate_limit') err.textContent = L('Too many attempts. Wait a few minutes and try again.');
        else if (codeErr === 'weak_password') err.textContent = L('Password must be at least 8 characters.');
        else if (codeErr === 'user_already_exists' || codeErr === 'email_exists') err.textContent = L('An account with this email already exists.');
        else if (codeErr === 'email_not_confirmed') err.textContent = L('Confirm your email address first. Check your inbox.');
        else if (codeErr === 'invalid_credentials' || (!signup && r.status === 400)) err.textContent = L('Incorrect email or password.');
        else err.textContent = L('Something went wrong. Please try again.');
      }, function () { busy = false; err.textContent = L('No internet connection.'); });
    }
    var mainBtn = U.button(L('Sign In'), submit, { primary: true });
    var showBtn = U.button(L('Show'), function () { showPw = !showPw; draw(); });
    var forgotBtn = U.button(L('Forgot password?'), function () {
      if (!validEmail()) { err.textContent = L('Enter a valid email address.'); U.focus(emailBox); return; }
      err.textContent = '';
      auth('/auth/v1/recover?redirect_to=' + encodeURIComponent('https://bingr.online/reset'), { email: email.trim() }).then(function () {
        err.textContent = L("If an account exists, you'll receive an email to reset your password.");
      }, function () { err.textContent = L('No internet connection.'); });
    });
    var switchBtn = U.button(' ', function () { signup = !signup; err.textContent = ''; draw(); });
    var el = layer('email-login', [h('div.email-card', [
      title,
      h('div.email-cols', [kb, h('div.email-fields', [nameRow, emailBox, pwBox, mainBtn, err, h('div.email-links', [showBtn, forgotBtn]), switchBtn])])
    ])]);
    draw();
    U.push({ el: el, onBack: function () { U.pop(); return true; } });
  };

  // MARK: te veel apparaten

  S.deviceLimit = function (info, onRetry, onCancel) {
    var free = info.plan ? String(info.plan).toLowerCase() === 'free' : A.isFree();
    var list = h('div.dev-list.sy.fm', { 'data-pad': '40' });
    function draw(devs) {
      U.clear(list);
      devs.forEach(function (d) {
        var row = h('div.dev-row', [U.icon(platformIcon(d.platform)), h('div.dev-info', [h('div', d.name), h('div.dim', A.platformLabel(d.platform))])]);
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
      // build 88: Gratis → met Lifetime meer apparaten
      free ? h('p', L('Or upgrade to Lifetime to use Bingr on up to %d devices.', A.LIFETIME.devices)) : null,
      list,
      h('div.modal-buttons', [free ? U.button(L('Upgrade to Lifetime'), function () { S.upgrade('devices'); }, { icon: 'lifetime' }) : null, U.button(L('Cancel'), onCancel)])
    ])]);
    return { el: el, onBack: function () { onCancel(); return true; } };
  };
  function platformIcon(p) { return U.platformIcon(p); }
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
      // build 100: lukt het niet (netwerk of inlog nog niet klaar), dan na 25 s nog een keer, zolang de avatar in beeld is
      var tries = 0;
      var load = function () {
        B.app.photo(photo, p.photo.updated).then(function (url) {
          if (!url) {
            if (++tries < 4) setTimeout(function () { if (document.body.contains(el)) load(); }, 25000);
            return;
          }
          el.style.backgroundImage = 'url("' + url + '")';
          el.style.backgroundSize = 'cover';
          el.classList.add('has-photo');
        });
      };
      load();
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
        // build 102: ronde avatar met de naam eronder en het slotje naast de naam; hoofdprofiel met een rond
        // poppetje rechtsonder (zoals AvatarTile op de Apple TV)
        var tile = h('div.prof.f', [S.avatar(p, 'big'), h('div.prof-name', [h('span', p.name), D.pinFor(p) ? U.icon('lock', 'prof-lock') : null]),
          D.isMain(p) && D.profiles.length > 1 ? h('div.prof-main', [U.icon('person')]) : null,
          p.kids ? h('div.prof-kids', L('Kids')) : null]);
        if (B.store.get('lastProfile', null) === p.id) tile.classList.add('autofocus');
        tile.onEnter = function () {
          var go = function () {
            var pin = D.pinFor(p);
            // build 147: wachten op de gebruiker telt niet als app-tijd (startdiagnose)
            B.diag.mark('profile_picked');
            if (pin) { B.diag.mark('pin'); U.askPIN(L('Enter PIN'), function (x) { return B.b146.pin.check(x, pin); }, function () { B.diag.mark('pin_ok'); onPick(p); }); }
            else onPick(p);
          };
          // build 140: was het laatst actieve profiel op deze tv een kinderprofiel, dan eerst de rekensom (ouderpoort)
          var last = D.profileById(B.store.get('lastProfile', null));
          if (B.kidsGate) B.kidsGate.guardPicker(p, !!(last && last.kids), go); else go();
        };
        row.appendChild(tile);
      });
      if (!D.profiles.length) row.appendChild(h('div.dim', L('Loading…')));
    }
    draw();
    var el = layer('profiles', [h('div.prof-top', [U.logo()]), h('h1.prof-title', L("Who's watching?")), row]);
    var off = function () { var f = U.focused; draw(); if (!row.contains(f)) U.focusFirst(row); };
    var unsub = B.on('profiles', function () { if (el.parentNode && !el.classList.contains('hidden')) off(); });
    // build 97: Terug vraagt eerst om bevestiging (nog een keer Terug), zoals op Home
    return { el: el, onBack: function () { B.app.confirmExit(); return true; }, destroy: unsub };
  };

  // MARK: kaarten

  function posterCard(x, type, opts) {
    opts = opts || {};
    var title = type === 'series' || type === 'movie' ? x.title : x.name;
    // build 144: Verder kijken geeft zijn eigen cover mee (bibliotheek/catalogmeta, anders TMDB, anders het bewaarde beeld)
    var card = h('div.card.poster.f', [U.img(opts.poster || x.poster || x.logo, 'poster-img', title)]);
    var p = opts.progress;
    // build 141: Verder kijken: afspeelknop over de poster (de voortgang staat er al over)
    if (opts.play) card.appendChild(h('div.card-play', [U.icon('play')]));
    if (p != null) card.appendChild(h('div.card-progress', [h('div', { style: { width: Math.round(B.clamp(p, 0, 1) * 100) + '%' } })]));
    if (opts.label) card.appendChild(h('div.card-label', opts.label));
    // build 112: rood label linksboven (zelfde stijl als "N BEKEKEN"), bv. de datum bij Binnenkort op Bingr
    card._topBadge = opts.badge || null;
    langStrip(card, x, type);
    watchedMarks(card, x, type);
    card.onEnter = function () { openItem(type, x); };
    card.onFocus = opts.onFocus || null;
    // build 128: item van een andere server: de server hoort bij de sleutel (hetzelfde id kan op twee servers staan)
    card._key = type + ':' + (x._src ? x._src + '|' : '') + x.id;
    // build 102: lang OK / Tools = contextmenu (Mijn lijst, collectie, bekeken, Verder kijken), zoals op de Apple TV
    if ((type === 'movie' || type === 'series') && B.parity) B.parity.attachMenu(card, { type: type, item: x, progressKey: opts.progressKey || null, collection: opts.collection || null });
    return card;
  }
  S.posterCard = posterCard;
  /** Kleine doorschijnende strook onder op de poster met de taal van de titel ("NL", "IT", "EN · NL ondertitels", "Multi"). */
  function langStrip(card, x, type) {
    if (type !== 'movie' && type !== 'series') return;
    var txt = B.clang.badge(B.clang.forItem(x, type === 'series'));
    if (!txt) return;
    card.appendChild(h('div.card-lang', txt));
    card.classList.add('has-lang');
  }
  /**
   * Bekeken (build 75), zoals de andere apps: film → vinkje rechtsboven; serie → rood label "N BEKEKEN" linksboven
   * (aantal bekeken afleveringen: Trakt (alleen hoofdprofiel) samen met wat dit profiel in Bingr uitkeek).
   */
  var localEps = null;
  function localEpisodeIndex() {
    if (localEps) return localEps;
    localEps = {};
    D.setList('watched').forEach(function (k) {
      if (k.indexOf('ep:') !== 0) return;
      var bar = k.lastIndexOf('|');
      if (bar < 0) return;
      var sid = k.slice(3, bar);
      (localEps[sid] = localEps[sid] || {})[k.slice(bar + 1)] = true;
    });
    return localEps;
  }
  // bekeken gewijzigd (in Bingr, via sync of Trakt): de vinkjes/labels op alle getoonde posters bijwerken
  var marksPending = false;
  function marksChanged() {
    localEps = null;
    if (marksPending) return;
    marksPending = true;
    B.whenQuiet(function () {
      marksPending = false;
      var cards = document.querySelectorAll('.card.poster, .card.top10');
      for (var i = 0; i < cards.length; i++) if (cards[i]._wm) watchedMarks(cards[i], cards[i]._wm.x, cards[i]._wm.type);
    }, 500);
  }
  B.on('profileData', marksChanged);
  B.on('trakt', marksChanged);
  function watchedEpisodeCount(s) {
    var all = {}, n = 0;
    function add(k) { if (!all[k]) { all[k] = true; n++; } }
    var t = B.trakt.watchedEpisodes(s);
    if (t) Object.keys(t).forEach(add);
    var l = localEpisodeIndex()[s.id];
    if (l) Object.keys(l).forEach(add);
    (s.episodes || []).forEach(function (e) { if (D.isWatched(e.id)) add(e.season + '-' + e.number); });
    return n;
  }
  S.watchedEpisodeCount = watchedEpisodeCount;
  function watchedMarks(card, x, type) {
    if (type !== 'movie' && type !== 'series') return;
    card._wm = { x: x, type: type };
    var old = card.querySelectorAll('.card-check, .card-badges');
    for (var i = 0; i < old.length; i++) card.removeChild(old[i]);
    // linksboven: eerst het label van de kaart (build 112, bv. de datum), daaronder "N BEKEKEN"
    var badges = card._topBadge ? [h('div.card-badge', card._topBadge)] : [];
    if (type === 'movie') {
      if (D.isWatched(x.id) || B.trakt.isMovieWatched(x)) card.appendChild(h('div.card-check', [U.icon('check')]));
    } else if (type === 'series') {
      var n = watchedEpisodeCount(x);
      if (n > 0) badges.push(h('div.card-badge', L('%d WATCHED', n)));
    }
    if (badges.length) card.appendChild(h('div.card-badges', badges));
  }
  function pct(p) { return Math.round(B.clamp((Date.now() - p.start) / (p.end - p.start), 0, 1) * 100) + '%'; }
  function channelCard(ch, list, opts) {
    var nn = Lib.nowNext(ch);
    var card = h('div.card.chan.f', [
      h('div.chan-logo', [U.img(ch.logo, 'logo-img', ch.name)]),
      h('div.chan-meta', [h('div.chan-name', ch.name), h('div.chan-now.dim', nn.now ? nn.now.title : '')])
    ]);
    if (nn.now) card.appendChild(h('div.card-progress', [h('div', { style: { width: pct(nn.now) } })]));
    card.onEnter = function () {
      // build 128: zender van een andere (of verwijderde) server: daar spelen, of eerst zoeken
      if (ch._src && B.allservers && B.allservers.open({ type: 'channel', item: ch })) return;
      B.player.playChannel(ch, (list || [ch]).filter(function (c) { return !c._src === !ch._src; }));
    };
    card._key = 'channel:' + (ch._src ? ch._src + '|' : '') + ch.id;
    // build 102: contextmenu (favoriet, gids, collectie, terugkijk-correctie, multiview)
    if (B.parity) B.parity.attachMenu(card, { type: 'channel', item: ch, list: list || [ch], collection: (opts && opts.collection) || null });
    return card;
  }
  S.channelCard = channelCard;
  function top10Card(x, type, i) {
    var card = h('div.card.top10.f', [h('div.top10-num', String(i + 1)), U.img(x.poster, 'poster-img', x.title)]);
    langStrip(card, x, type);
    watchedMarks(card, x, type);
    card.onEnter = function () { openItem(type, x); };
    card._key = type + ':' + x.id;
    if (B.parity) B.parity.attachMenu(card, { type: type, item: x });
    return card;
  }
  function openItem(type, x) {
    // build 128: van een andere server (x._src): daar openen/spelen; verwijderd of niet meer daar: eerst zoeken
    if (x && x._src && B.allservers && B.allservers.open({ type: type, item: x })) return;
    if (type === 'channel') { B.player.playChannel(x, [x]); return; }
    U.push(S.detail(type, x));
  }
  S.openItem = openItem;

  /** build 102: seeAll = functie → kaart "Bekijk allemaal" aan het eind van de rij (rasterpagina met sorteren). */
  function rowBlock(title, cards, cls, seeAll) {
    if (!cards.length) return null;
    if (seeAll && B.parity) cards = cards.concat([B.parity.seeAllCard(seeAll)]);
    var scroller = h('div.row-scroll.sx.fm', { 'data-pad': '70' }, [h('div.row-track', cards)]);
    return h('div.row-block' + (cls ? '.' + cls : ''), [h('div.row-title', title), scroller]);
  }
  S.rowBlock = rowBlock;
  /** Verticale scroller met een track (scrollen met transform). */
  function vScroller(cls, attrs) {
    var track = h('div.sy-track');
    var el = h('div' + cls + '.sy', attrs || {}, [track]);
    el.track = track;
    return el;
  }
  S.vScroller = vScroller;

  // MARK: hoofdscherm met navigatie links

  var NAV = [
    ['search', 'search', 'Search'], ['home', 'home', 'Home'], ['live', 'live', 'Live TV'], ['movies', 'movies', 'Movies'],
    // build 86: Categorieën zit niet meer in het menu, maar in het profielmenu en als rij op Home
    // build 140: Categorieën naast Series (bladeren door de categorieën; kinderprofiel: alleen kindercategorieën)
    ['series', 'series', 'Series'], ['categories', 'grid', 'Categories'], ['mylist', 'list', 'My List'],
    ['settings', 'gear', 'Settings']
  ];
  S.main = function () {
    var content = h('div.main-content', { 'data-zone': 'content' });
    var nav = h('div.nav.fm', { 'data-zone': 'nav' });
    var current = null;
    var pages = {};
    // build 72: ronde avatar van het actieve profiel bovenaan het menu; OK opent het profielmenu (zoals op bingr.online)
    var profileTile = h('div.nav-profile.f', [S.avatar(B.app.profile, 'small'), h('span.nav-label', B.app.profile.name),
      h('span.nav-chevron', '\u25BE')]);
    profileTile.onEnter = function () { S.profileMenu(function (key) { show(key, true); }); };
    profileTile.onFocus = function () { nav.classList.add('open'); };
    profileTile.onBlur = function () { setTimeout(function () { if (!nav.contains(U.focused)) nav.classList.remove('open'); }, 0); };
    profileTile.onKey = function (name) {
      if (name === 'right') { focusContent(); return true; }
      return false;
    };
    nav.appendChild(profileTile);
    // build 146: badge met ongelezen supportantwoorden op de avatar en op Instellingen
    var navBadges = [];
    function badgeEl() { var b = h('span.badge.nav-badge'); navBadges.push(b); return b; }
    profileTile.appendChild(badgeEl());
    function updateBadges() {
      var t = B.b146.support.badge(B.support.unread());
      navBadges.forEach(function (b) { b.textContent = t || ''; b.style.display = t ? '' : 'none'; });
    }
    var items = {};
    NAV.forEach(function (n) {
      // build 102: tabbladen die verborgen zijn (settings.shared.hiddenTabs) niet in het menu
      if (B.app.tabHidden(n[0])) return;
      var it = h('div.nav-item.f', [U.icon(n[1]), h('span.nav-label', L(n[2])), n[0] === 'settings' ? badgeEl() : null]);
      // build 140: Instellingen vanuit een kinderprofiel: eerst de rekensom (ouderpoort)
      it.onEnter = function () {
        if (n[0] === 'settings' && B.kidsGate) B.kidsGate.guard(function () { show(n[0], true); });
        else show(n[0], true);
      };
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
    updateBadges();
    var wantContent = false;
    function focusContent() {
      var pg = pages[current];
      if (!pg) return false;
      if (pg.focusIn && pg.focusIn()) return true;
      var list = U.focusables(pg.el);
      if (list.length) { U.focus(list[0]); return true; }
      return false;
    }
    var previous = null;
    function show(key, focusIn) {
      if (current && current !== key) previous = current;
      if (current && pages[current] && pages[current].onHide) pages[current].onHide();
      Object.keys(items).forEach(function (k) { items[k].classList.toggle('active', k === key); });
      current = key;
      var pg = pages[key];
      if (!pg || pg.stale) {
        if (pg && pg.el.parentNode) { if (pg.destroy) pg.destroy(); pg.el.parentNode.removeChild(pg.el); }
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
        else if (back) {
          if (dataStale) { dataStale = false; rerender(); }
          var pg = pages[current];
          if (pg && pg.onShow) pg.onShow(true);
        }
      },
      onHide: function () { var pg = pages[current]; if (pg && pg.onHide) pg.onHide(); },
      onBack: function () {
        if (nav.contains(U.focused)) {
          if (current !== 'home') { show('home', true); return true; }
          B.app.confirmExit(); return true;
        }
        var pg = pages[current];
        if (pg && pg.onBack && pg.onBack()) return true;
        // pagina zonder menu-item (Categorieën, build 86): terug naar waar je vandaan kwam
        if (!items[current]) { show(previous && items[previous] ? previous : 'home', true); return true; }
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
      page: function () { return pages[current] || null; },
      refresh: function () {
        Object.keys(pages).forEach(function (k) { if (k !== current) pages[k].stale = true; });
        var pg = pages[current];
        if (pg && pg.refresh) pg.refresh();
        var lost = !U.focused || !document.body.contains(U.focused);
        if ((wantContent || lost) && U.top() === screen) { if (focusContent()) wantContent = false; else if (lost) U.focus(items[current] || items.home); }
      }
    };
    // build 86: pagina's (Categorieën) openen vanuit de inhoud, zoals de rij op Home
    S.goPage = function (key) { if (el.parentNode) show(key, true); };
    // build 146: de tv-gids is verlopen: kleine melding bovenaan de inhoud (blokkeert niets; omhoog bereikbaar), met Bijwerken
    var epgBar = null;
    S.epgNotice = function (on) {
      if (!el.parentNode) return;
      if (!on) { if (epgBar && epgBar.parentNode) epgBar.parentNode.removeChild(epgBar); epgBar = null; return; }
      if (epgBar) return;
      var upd = h('div.btn.f.epg-btn', L('Update'));
      var close = h('div.btn.f.epg-btn.epg-close', '\u2715');
      epgBar = h('div.epg-notice', [U.icon('guide'), h('span.epg-text', L('The TV guide has expired. Update your servers.')), upd, close]);
      upd.onEnter = function () { S.epgNotice(false); S.refreshServers(); focusContent(); };
      close.onEnter = function () { S.epgNotice(false); focusContent(); };
      el.appendChild(epgBar);
    };
    // nieuwe bibliotheek: pas tekenen als de afstandsbediening even stil is (scrollen gaat voor)
    var libPending = false;
    var offs = [];
    offs.push(B.on('support', function () { if (el.parentNode) updateBadges(); }));
    offs.push(B.on('library', function () {
      if (!el.parentNode || libPending) return;
      libPending = true;
      B.whenQuiet(function () { libPending = false; if (el.parentNode) { if (B.diag && B.diag.task) B.diag.task('rijen opbouwen', function () { screen.refresh(); }); else screen.refresh(); } }, 300);
    }));
    /** Huidige pagina opnieuw tekenen en de focus terugzetten op dezelfde titel (in dezelfde rij). */
    var dataStale = false;
    function rerender() {
      var pg = pages[current];
      if (!pg) return;
      var f = U.focused;
      // build 102: menu/keuzevenster erboven (contextmenu): de focus van dit scherm is de kaart van vóór het menu
      var fromLast = !(f && pg.el.contains(f)) && screen.lastFocus && pg.el.contains(screen.lastFocus);
      if (fromLast) f = screen.lastFocus;
      var key = f && f._key, rowT = null;
      var hadFocus = !!(f && pg.el.contains(f));
      if (f && f.closest) { var rb = f.closest('.row-block'); var t = rb && rb.querySelector('.row-title'); rowT = t ? t.textContent : null; }
      (pg.onData || pg.refresh)();
      if (fromLast) {
        if (document.body.contains(f)) return;
        var t0 = null, all0 = pg.el.querySelectorAll('.f');
        for (var j = 0; j < all0.length && key && !t0; j++) if (all0[j]._key === key) t0 = all0[j];
        // U.pop zet de focus straks op lastFocus
        screen.lastFocus = t0 || null;
        return;
      }
      if (!hadFocus || (U.focused && document.body.contains(U.focused) && pg.el.contains(U.focused))) return;
      var target = null, all = pg.el.querySelectorAll('.f');
      for (var i = 0; i < all.length && key; i++) {
        if (all[i]._key !== key) continue;
        if (!target) target = all[i];
        var rb2 = all[i].closest('.row-block'), t2 = rb2 && rb2.querySelector('.row-title');
        if (t2 && t2.textContent === rowT) { target = all[i]; break; }
      }
      if (target) U.focus(target, true); else focusContent();
    }
    // voortgang, lijsten en favorieten: alleen de rijen die erover gaan, en pas als het stil is
    var dataPending = false;
    function onProfileData() {
      Object.keys(pages).forEach(function (k) { if (pages[k].onData && k !== current) pages[k].stale = true; });
      if (!pages[current] || !pages[current].onData) return;
      if (U.top() !== screen) { dataStale = true; return; }
      if (dataPending) return;
      dataPending = true;
      B.whenQuiet(function () {
        dataPending = false;
        if (U.top() !== screen) { dataStale = true; return; }
        rerender();
      }, 600);
    }
    offs.push(B.on('profileData', onProfileData));
    offs.push(B.on('allservers', onProfileData));   // build 128: items van andere servers gevonden/verhuisd
    offs.push(B.on('covers', onProfileData));       // build 144: TMDB-posters voor Verder kijken binnen
    offs.push(B.on('epg', function () { var pg = pages[current]; if (pg && pg.onEpg) B.whenQuiet(function () { if (el.parentNode) pg.onEpg(); }, 400); }));
    // scherm weg (profiel wisselen, uitloggen, taal): luisteraars en pagina's opruimen, anders blijft alles in het geheugen
    screen.destroy = function () {
      offs.forEach(function (f) { f(); });
      offs = [];
      Object.keys(pages).forEach(function (k) { if (pages[k].destroy) { try { pages[k].destroy(); } catch (e) { B.log('destroy', e); } } });
      pages = {};
    };
    return screen;
  };

  S.pages = {};

  function stateBox() {
    if (Lib.loading && !Lib.raw.channels.length && !Lib.raw.movies.length && !Lib.raw.series.length) {
      return h('div.state', [U.spinner(), h('div', L('Loading your library…'))]);
    }
    if (Lib.error) return h('div.state', [h('h2', L("Couldn't load the server")), h('div.dim', Lib.error), U.button(L('Try again'), function () { Lib.select(Lib.source, true); }, { primary: true })]);
    if (!Lib.source) {
      var kids = [h('h2', L('No servers yet')), h('div.dim', L('Add a server in the Bingr app on your phone, tablet or computer. It will appear here automatically.'))];
      if (D.isMain(B.app.profile)) kids.push(U.button(L('Add server'), function () { U.push(S.serverEdit(null)); }, { primary: true, icon: 'list' }));
      return h('div.state', kids);
    }
    return null;
  }
  S.stateBox = stateBox;

  // MARK: Home

  S.pages.home = function () {
    var scroller = vScroller('.page.home', { 'data-align': 'top', 'data-pad': '40' });
    var track = scroller.track;
    var pg = { el: scroller };
    var blocks = {};      // rij-sleutel → element (voor gedeeltelijk vernieuwen)
    var buildJob = 0;
    /**
     * Build 140: uitgelicht = een vaste selectie (max. 8): om en om uit Voor jou en Nieuw op Bingr, dubbele weg. Wisselt om
     * de 8 s; waar hij begint schuift elke dag één plek op. Zolang die er nog niet is: de Top 10 van vandaag (per dag
     * dezelfde keuze). Geen willekeur per opening meer.
     */
    function heroPool() {
      var P = B.parity, fy = P && P.forYouRanked ? P.forYouRanked() : [], nw = B.newOnBingr ? B.newOnBingr.list() : [];
      return B.b140.picks.heroPool(fy, nw, function (e) { return e.type + ':' + String(e.item._src || '').toLowerCase() + '|' + e.item.id; });
    }
    function heroFor() {
      var lib = Lib.lib;
      return Lib.memo('hero', function () {
        var cands = lib.movies.filter(function (m) { return m.poster; }).slice(0, 400);
        if (!cands.length) cands = lib.series.filter(function (s) { return s.poster; }).slice(0, 400);
        if (!cands.length) return null;
        // build 102: billboard uit de Top 10 van vandaag (trending, = quickHero op tvOS); build 140: per dag vast
        var top = B.parity ? B.parity.top10(lib.movies.length ? 'movie' : 'series') : Lib.top10(lib.movies.length ? lib.movies : lib.series);
        var day = B.b140.picks.today();
        var list = top.length ? top.slice(0, 5) : cands;
        var pick = list[B.b140.picks.heroStart(day, list.length)];
        return { item: pick, type: lib.movies.indexOf(pick) >= 0 ? 'movie' : 'series' };
      });
    }
    var heroEl = null, heroTimer = null, heroList = [], heroAt = 0, heroSig = '';
    /** Build 140: de vaste selectie (opnieuw) zetten en om de 8 s de volgende tonen. */
    function startHero() {
      var pool = heroPool();
      var sig = pool.map(function (e) { return e.type + e.item.id; }).join(',');
      if (sig === heroSig && heroTimer) return;
      heroSig = sig;
      clearInterval(heroTimer); heroTimer = null;
      if (!pool.length) return;
      heroList = pool;
      heroAt = B.b140.picks.heroStart(B.b140.picks.today(), pool.length);
      showHero(pool[heroAt]);
      if (pool.length > 1) heroTimer = setInterval(function () {
        if (!document.body.contains(scroller)) { clearInterval(heroTimer); heroTimer = null; return; }
        // niet terwijl er een venster over Home ligt of de speler loopt
        var top = U.top();
        if (B.player.current || !top || !top.el || !top.el.contains(scroller)) return;
        heroAt = (heroAt + 1) % heroList.length;
        showHero(heroList[heroAt]);
      }, B.b140.picks.HERO_INTERVAL);
    }
    function showHero(hf) {
      if (!hf || !hf.item) return;
      var el = hero(hf);
      var hadFocus = heroEl && U.focused && heroEl.contains(U.focused);
      var focusKey = hadFocus ? U.focused._key : null;
      if (heroEl && heroEl.parentNode) heroEl.parentNode.replaceChild(el, heroEl);
      else track.insertBefore(el, track.firstChild);
      heroEl = el;
      if (hadFocus) {
        var c = el.querySelectorAll('.f'), hit = c[0];
        for (var i = 0; i < c.length; i++) if (c[i]._key === focusKey) { hit = c[i]; break; }
        if (hit) U.focus(hit, true);
      }
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
      var metaEl = h('div.hero-meta', meta.join('  ·  '));
      // build 102: IMDb-badge, genre en speelduur uit de metadata (= HeroBanner op tvOS), op de achtergrond
      if (B.parity) setTimeout(function () { B.parity.heroMeta(x, hf.type, metaEl); }, 0);
      return h('div.row-block.hero', [
        h('div.hero-bg', [U.img(x.poster, 'hero-img', null, true)]),
        h('div.hero-poster', [U.img(x.poster, 'poster-img', x.title, true)]),
        h('div.hero-shade'),
        h('div.hero-info', [
          h('div.hero-kind', hf.type === 'series' ? L('SERIES') : L('FILM')),
          h('h1.hero-title', x.title), metaEl,
          x.plot ? h('p.hero-plot', x.plot) : null,
          h('div.hero-buttons.fm', [playBtn, info])
        ])
      ]);
    }
    /** build 102: "Bekijk allemaal" (rasterpagina met sorteren) voor een rij; entries = [{type, item}]. */
    function seeAll(title, list) {
      if (!B.parity || !list || !list.length) return null;
      return function () { B.parity.openGrid(title, list); };
    }
    function entries(list, type) { return list.map(function (x) { return { type: type, item: x }; }); }
    function continueRow() {
      // build 128: Verder kijken over alle servers (js/allservers.js); van een andere server: daar verder
      var list = B.allservers.continueList(), wants = [];
      var cards = list.map(function (c) {
        // build 141: de poster (cover) van de film/serie met afspeelknop en voortgang erover, niet een still van de scène
        // build 144: zonder poster in de bibliotheek de TMDB-poster (bewaard op de tv), pas daarna het bewaarde beeld
        var card = posterCard(c.item, c.type, { progress: c.e.fraction, progressKey: c.key, play: true, poster: c.cover });
        if (c.src) card.onEnter = function () { B.allservers.resume(c); };
        if (c.lookup) wants.push(c.lookup);
        return card;
      });
      if (wants.length) setTimeout(function () { B.b144.covers.resolve(wants).catch(B.noop); }, 0);
      return rowBlock(L('Continue watching'), cards);
    }
    function myListRow() {
      var cards = [], all = [];
      // build 128: over alle servers, laatst toegevoegd eerst
      B.allservers.myList().forEach(function (hit) { all.push(hit); if (cards.length < 40) cards.push(posterCard(hit.item, hit.type)); });
      // build 111: TMDB-titels (Binnenkort op Bingr / Nieuw op Bingr) die niet in je bibliotheek staan
      if (B.discover) B.discover.myListHits().forEach(function (hit) { all.push(hit); if (cards.length < 40) cards.push(posterCard(hit.item, hit.type)); });
      return rowBlock(L('My List'), cards, null, seeAll(L('My List'), all));
    }
    function liveRow() {
      var lib = Lib.lib, chans = [];
      // build 128: recente en favoriete zenders van alle servers
      B.allservers.channels(D.setList('recentChannels').concat(D.setList('favorites'))).forEach(function (c) { if (chans.indexOf(c) < 0) chans.push(c); });
      if (!chans.length) chans = lib.channels.slice(0, 20);
      chans = chans.slice(0, 24);
      return rowBlock(L('Live now'), chans.map(function (c) { return channelCard(c, chans); }), 'live');
    }
    function builders() {
      var lib = Lib.lib;
      var b = {
        'continue': continueRow, 'mylist': myListRow, 'live': liveRow,
        'top10': function () {
          var out = [];
          // build 102: Top 10 uit TMDB/Trakt trending (kinderprofiel: populaire animatie/familie), gekoppeld aan je lijst
          var P = B.parity;
          var ms = P ? P.top10('movie') : Lib.top10(lib.movies), ss = P ? P.top10('series') : Lib.top10(lib.series);
          var kids = B.app.profile.kids;
          var st = kids ? L('Top 10 kids series') : L('Top 10 series today'), mt = kids ? L('Top 10 kids movies') : L('Top 10 movies today');
          var sb = rowBlock(st, ss.map(function (s, i) { return top10Card(s, 'series', i); }), 'top10row',
            P ? function () { P.openGrid(st, P.top10('series', true).map(function (x) { return { type: 'series', item: x }; })); } : null);
          var mb = rowBlock(mt, ms.map(function (m, i) { return top10Card(m, 'movie', i); }), 'top10row',
            P ? function () { P.openGrid(mt, P.top10('movie', true).map(function (x) { return { type: 'movie', item: x }; })); } : null);
          if (sb) out.push(sb);
          if (mb) out.push(mb);
          return out;
        },
        // build 102: "Voor jou" (= forYouRow op tvOS), alfabetisch, films en series door elkaar
        // build 140: per actief profiel; zonder kijkgeschiedenis heet de rij "Populair"
        'foryou': function () {
          // zonder wat intussen voortgang heeft of bekeken is
          var list = (B.parity ? B.parity.forYou() : []).filter(function (e) {
            return !D.progress(e.item.id) && !(e.type === 'movie' && B.parity.movieWatched(e.item));
          });
          var title = B.parity && B.parity.forYouPopular && B.parity.forYouPopular() ? L('Popular') : L('For you');
          // build 141: label FILM/SERIE op de tegels (zoals op Android en Apple TV)
          return rowBlock(title, list.map(function (e) { return posterCard(e.item, e.type, { badge: B.b141.typeBadge(e.type) }); }), 'foryou', seeAll(title, list));
        },
        // build 141: "Nieuw toegevoegd" is weg; Nieuw op Bingr staat op die plek (B.b141.homeRows.displayed)
        // build 111: Binnenkort op Bingr / Nieuw op Bingr (TMDB), met bronvermelding onder de rij
        'upcoming': function () { return B.discover ? B.discover.row('upcoming') : null; },
        // build 140: Nieuw op Bingr = laatst toegevoegd aan je bibliotheek (alle servers), nieuwste eerst (js/newonbingr.js)
        // build 141: op de plek van "Nieuw toegevoegd", met het label FILM/SERIE op de tegels (zoals Voor jou)
        'streaming': function () {
          var list = B.newOnBingr ? B.newOnBingr.list() : [];
          return rowBlock(L('New on Bingr'), list.map(function (e) { return posterCard(e.item, e.type, { badge: B.b141.typeBadge(e.type) }); }), null, seeAll(L('New on Bingr'), list));
        },
        'movies': function () { return rowBlock(L('Movies'), lib.movies.slice(0, 30).map(function (m) { return posterCard(m, 'movie'); }), null, seeAll(L('Movies'), entries(lib.movies, 'movie'))); },
        'series': function () { return rowBlock(L('Series'), lib.series.slice(0, 30).map(function (s) { return posterCard(s, 'series'); }), null, seeAll(L('Series'), entries(lib.series, 'series'))); },
        'categories': function () {
          var out = [];
          var mg = Lib.byGroup('movie'), sg = Lib.byGroup('series');
          lib.movieGroups.slice(0, 8).forEach(function (g) {
            var r = rowBlock(g, (mg[g] || []).slice(0, 30).map(function (m) { return posterCard(m, 'movie'); }), null, seeAll(g, entries(mg[g] || [], 'movie')));
            if (r) out.push(r);
          });
          lib.seriesGroups.slice(0, 6).forEach(function (g) {
            var r = rowBlock(g, (sg[g] || []).slice(0, 30).map(function (s) { return posterCard(s, 'series'); }), null, seeAll(g, entries(sg[g] || [], 'series')));
            if (r) out.push(r);
          });
          return out;
        }
      };
      // build 86: ankerpunt "Nu live" voor de rij Categorieën (ook als de rij Nu live uit staat of leeg is)
      b['@live'] = function () { return h('div.live-anchor'); };
      // plek van de rij Categorieën als het profiel hem elders in de indeling heeft gezet (niet direct na "live")
      b['@browse'] = function () { return h('div.browse-slot'); };
      D.collectionsFor(B.app.profile.id).forEach(function (c) {
        b['collection:' + c.id] = function () {
          var items = Lib.collectionItems(c, 40);
          var chans = items.filter(function (x) { return x.type === 'channel'; }).map(function (x) { return x.item; });
          // build 102: uit een handmatige collectie weghalen kan via het contextmenu; "Bekijk allemaal" = hele collectie
          return rowBlock(c.name, items.map(function (x) { return x.type === 'channel' ? channelCard(x.item, chans, { collection: c }) : posterCard(x.item, x.type, { collection: c }); }), null,
            seeAll(c.name, Lib.collectionItems(c, 500)));
        };
      });
      return b;
    }
    /**
     * Startscherm-indeling van dit profiel (gesynchroniseerd), of null. De rij Categorieën heet overal "browse"
     * (zoals iOS/tvOS/macOS en web); oudere sleutels "categorytiles" en "genres" tellen als "browse" (plek en
     * aan/uit blijven; dubbele vervallen).
     */
    var TILE_ALIASES = { categorytiles: 'browse', genres: 'browse' };
    function homeLayout() {
      var layout = D.homeLayout();
      if (!layout) return null;
      var seen = {}, out = [];
      layout.forEach(function (r) {
        if (!r || !r.key) return;
        var k = TILE_ALIASES[r.key] || r.key;
        if (seen[k]) return;
        seen[k] = true;
        out.push({ key: k, visible: r.visible !== false });
      });
      // build 141: zonder "Nieuw toegevoegd"; Nieuw op Bingr staat op die plek
      return B.b141.homeRows.displayed(out);
    }
    /** Categorieën op de standaardplek (in de indeling direct na "live", of niet in de indeling)? Dan 5 rijen onder Nu live. */
    function autoTiles() {
      var layout = homeLayout();
      if (!layout) return true;
      for (var i = 0; i < layout.length; i++) if (layout[i].key === 'browse') return i > 0 && layout[i - 1].key === 'live';
      return true;
    }
    function order(b) {
      var colls = Object.keys(b).filter(function (k) { return k.indexOf('collection:') === 0; });
      // build 111: Binnenkort op Bingr / Nieuw op Bingr (TMDB) na Nieuw toegevoegd
      // build 141: Nieuw op Bingr op de plek van "Nieuw toegevoegd" (die rij is weg)
      var o = ['continue', 'mylist', 'foryou', 'top10', 'live', 'streaming', 'upcoming'].concat(colls).concat(['categories']);
      var layout = homeLayout();
      var auto = autoTiles();
      if (layout) {
        var known = {};
        o = [];
        layout.forEach(function (r) {
          if (r.key === 'browse') { if (!auto && r.visible) o.push('@browse'); return; }
          if (!b[r.key] || r.key === '@live' || r.key === '@browse') return;
          known[r.key] = true;
          if (r.visible !== false) o.push(r.key);
          if (r.key === 'live') o.push('@live');
        });
        colls.forEach(function (k) { if (!known[k]) o.push(k); });
        if (!known.categories && o.indexOf('categories') < 0) o.push('categories');
        // build 102: "Voor jou" nog niet in de indeling (oudere indeling): standaard na Mijn lijst / Verder kijken
        if (!known.foryou) {
          var fa = 0;
          ['continue', 'mylist'].forEach(function (k) { var j = o.indexOf(k); if (j >= fa) fa = j + 1; });
          o.splice(fa, 0, 'foryou');
        }
        // build 111: Binnenkort op Bingr / Nieuw op Bingr nog niet in de indeling: standaard aan, na Nieuw toegevoegd
        // (of na Verder kijken / Mijn lijst / Voor jou als die rij uit staat)
        ['upcoming', 'streaming'].forEach(function (k, n) {
          if (known[k]) return;
          var da = Math.max(o.indexOf('streaming'), o.indexOf('upcoming')) + 1;
          if (da <= 0) ['continue', 'mylist', 'foryou'].forEach(function (x) { var j = o.indexOf(x); if (j >= da) da = j + 1; });
          o.splice(da, 0, k);
        });
        if (!known.live) {
          // geen "live" in de indeling: op de standaardplek (na Verder kijken, Mijn lijst en Top 10)
          var at = 0;
          ['continue', 'mylist', 'foryou', 'top10'].forEach(function (k) { var j = o.indexOf(k); if (j >= at) at = j + 1; });
          o.splice(at, 0, '@live');
        }
      } else o.splice(o.indexOf('live') + 1, 0, '@live');
      return o;
    }
    /** Rij "Categorieën" (build 86) aan? Per profiel in de startscherm-indeling (sleutel browse), standaard aan. */
    function tilesOn() {
      var layout = homeLayout(), on = true;
      if (layout) layout.forEach(function (r) { if (r.key === 'browse' && !r.visible) on = false; });
      return on;
    }
    /**
     * De rij "Categorieën" zetten: op de standaardplek met precies vijf getoonde rijen tussen "Nu live" en
     * Categorieën; zijn er daarna minder, dan onderaan. Telt de rijen zoals ze in de pagina staan (Top 10 = twee
     * rijen, lege rijen tellen niet). Heeft het profiel de rij elders gezet, dan op die plek (.browse-slot).
     */
    function positionCats() {
      var cat = blocks.browse && blocks.browse[0];
      if (!cat) return;
      var slot = track.querySelector('.browse-slot');
      if (slot) { if (cat.nextSibling !== slot) track.insertBefore(cat, slot); return; }
      var end = track.querySelector('.page-end');
      var anchor = track.querySelector('.live-anchor');
      var ref = end, n = 0;
      if (anchor) {
        for (var x = anchor.nextSibling; x && x !== end; x = x.nextSibling) {
          if (x === cat || !x.classList || !x.classList.contains('row-block') || x.classList.contains('hero')) continue;
          if (++n === 5) { ref = x.nextSibling; break; }
        }
      }
      if (ref !== cat && cat.nextSibling !== ref) track.insertBefore(cat, ref);
    }
    function buildCats() {
      var old = blocks.browse || [];
      var f = U.focused, fKey = f && old.some(function (o) { return o.contains(f); }) ? f._key : null;
      old.forEach(function (o) { if (o.parentNode) o.parentNode.removeChild(o); });
      var row = tilesOn() && S.categoriesRow ? S.categoriesRow() : null;
      blocks.browse = row ? [row] : [];
      if (!row) return;
      row._row = 'browse';
      track.insertBefore(row, track.querySelector('.page-end'));
      positionCats();
      if (fKey) {
        var c = row.querySelectorAll('.f');
        for (var i = 0; i < c.length; i++) if (c[i]._key === fKey) { U.focus(c[i], true); break; }
      }
    }
    function nodesOf(r) { return r == null ? [] : (Array.isArray(r) ? r : [r]); }
    /** Alles opnieuw: de bovenste rijen meteen, de rest in stukjes (scrollen blijft vloeiend). */
    function render() {
      var my = ++buildJob;
      built = false;
      U.clear(track);
      blocks = {};
      U.scrollTo(scroller, 0);
      var st = stateBox();
      if (st) { track.appendChild(st); return; }
      var hf = heroFor();
      heroEl = null; heroSig = ''; clearInterval(heroTimer); heroTimer = null;
      if (hf) { heroEl = hero(hf); track.appendChild(heroEl); }
      var b = builders(), keys = order(b);
      var end = h('div.page-end');
      track.appendChild(end);
      var i = 0;
      function step() {
        if (my !== buildJob) return;
        var t0 = Date.now();
        while (i < keys.length && (Date.now() - t0 < 10 || i < 3)) {
          var k = keys[i++];
          var nodes = nodesOf(b[k]());
          var holder = nodes.length ? nodes : [];
          blocks[k] = holder;
          holder.forEach(function (n) { n._row = k; track.insertBefore(n, end); });
        }
        if (i < keys.length) B.idle(step, 300);
        else { built = true; buildCats(); if (B.parity) loadExtras(); loadDiscover(); }
      }
      step();
    }
    /** Alleen deze rijen opnieuw (voortgang, Mijn lijst, favoriete zenders). */
    function refreshRows(keys) {
      var b = builders();
      keys.forEach(function (k) {
        var old = blocks[k];
        if (!old || !b[k]) return;
        var fresh = nodesOf(b[k]());
        var ref = null;
        if (old.length) ref = old[old.length - 1].nextSibling;
        else {
          // rij bestond nog niet (bijv. Mijn lijst was leeg): op de juiste plek invoegen
          var ks = order(b), at = ks.indexOf(k);
          for (var j = at + 1; j < ks.length && !ref; j++) { var nb = blocks[ks[j]]; if (nb && nb.length) ref = nb[0]; }
          ref = ref || track.querySelector('.page-end');
        }
        var f = U.focused, fKey = f && old.some(function (n) { return n.contains(f); }) ? f._key : null;
        old.forEach(function (n) { if (n.parentNode) n.parentNode.removeChild(n); });
        fresh.forEach(function (n) { n._row = k; track.insertBefore(n, ref); });
        blocks[k] = fresh;
        if (fKey) {
          var hit = null;
          fresh.forEach(function (n) { if (!hit) { var c = n.querySelectorAll('.f'); for (var i = 0; i < c.length; i++) if (c[i]._key === fKey) { hit = c[i]; break; } } });
          if (hit) U.focus(hit, true);
        }
      });
      positionCats();
    }
    // nieuwe genres binnen (TMDB): de rij Categorieën opnieuw als de afstandsbediening stil is
    var catPending = false;
    var offCats = B.on('ageRatings', function () {
      if (catPending || !blocks.browse) return;
      catPending = true;
      B.whenQuiet(function () {
        catPending = false;
        if (document.body.contains(scroller) && blocks.browse) buildCats();
      }, 2500);
    });
    /** build 111: TMDB-rijen en de titelindex op de achtergrond; daarna alleen die rijen opnieuw (als de afstandsbediening stil is). */
    var discJob = 0;
    function loadDiscover() {
      if (!B.discover) return;
      var my = ++discJob;
      B.discover.resolveMissing();
      B.discover.loadAll().then(function (changed) {
        if (!changed || my !== discJob) return;
        B.whenQuiet(function () { if (document.body.contains(scroller)) refreshRows(['upcoming', 'streaming']); }, 300);
      }, B.noop);
    }
    var offDiscover = B.on('discover', function () {
      B.whenQuiet(function () { if (document.body.contains(scroller)) refreshRows(['mylist', 'upcoming', 'streaming']); }, 300);
    });
    pg.destroy = function () { offCats(); offDiscover(); clearInterval(heroTimer); heroTimer = null; };
    pg.refresh = render;
    // build 102: ook de collectierijen (toevoegen via het contextmenu) en Voor jou (bekeken/voortgang)
    pg.onData = function () {
      pg.stale = false;
      var colls = Object.keys(builders()).filter(function (k) { return k.indexOf('collection:') === 0; });
      // nieuwe collectie (net gemaakt): de rij op zijn plek invoegen
      if (built) colls.forEach(function (k) { if (!blocks[k]) blocks[k] = []; });
      refreshRows(['continue', 'mylist', 'live', 'foryou'].concat(colls));
    };
    var built = false;
    /** build 102: Top 10 (trending) en Voor jou op de achtergrond; daarna alleen die rijen opnieuw. */
    var extrasJob = 0;
    function loadExtras() {
      var my = ++extrasJob, P = B.parity;
      P.loadTrending().then(function (changed) {
        if (my !== extrasJob || !document.body.contains(scroller)) return;
        if (changed) refreshRows(['top10']);
        return P.loadForYou().then(function (ch2) {
          if (my !== extrasJob || !document.body.contains(scroller)) return;
          if (ch2 || changed) B.whenQuiet(function () { if (document.body.contains(scroller)) refreshRows(['foryou']); }, 300);
          // build 140: Nieuw op Bingr (alle servers), daarna de vaste selectie voor uitgelicht
          return (B.newOnBingr ? B.newOnBingr.load() : Promise.resolve(false)).then(function (ch3) {
            if (my !== extrasJob || !document.body.contains(scroller)) return;
            B.whenQuiet(function () {
              if (!document.body.contains(scroller)) return;
              if (ch3) refreshRows(['streaming']);
              startHero();
            }, 300);
          });
        });
      }).catch(B.noop);
    }
    pg.onEpg = function () { refreshRows(['live']); };
    pg.focusIn = function () { var f = track.querySelector('.f.autofocus') || track.querySelector('.f'); if (f) { U.focus(f); return true; } return false; };
    pg.onBack = function () {
      if (U.scrollPos(scroller) > 0) { U.scrollTo(scroller, 0); pg.focusIn(); return true; }
      return false;
    };
    render();
    return pg;
  };

  // MARK: Live TV (categorieën, zenders met virtuele lijst, voorbeeld en de gids)

  S.pages.live = function () {
    var lib = Lib.lib;
    var groupsEl = vScroller('.lv-groups.fm', { 'data-pad': '120' });
    var chansBox = h('div.lv-chans');
    var pvPh = h('div.lv-pv-video-ph');
    var pvVideo = h('div.lv-pv-video', [pvPh]);
    var pvInfo = h('div.lv-pv-info');
    var pvHint = h('div.lv-pv-hint.dim', L('OK: watch  ·  Yellow: favorite') + '  ·  ' + L('Blue: TV guide'));
    var preview = h('div.lv-preview', [pvVideo, pvInfo, pvHint]);
    var guideBtn = U.button(L('TV guide'), function () { U.push(S.guide(currentList, list ? list.index() : 0)); }, { icon: 'guide', cls: 'lv-guide-btn' });
    var mvBtn = U.button(L('Multiview'), function () { B.multiview.open(currentList, list ? list.index() : 0); }, { icon: 'grid', cls: 'lv-mv-btn' });
    // build 102: zender of programma zoeken (= zoekveld in LiveTVView op tvOS)
    var searchBtn = S.channelSearch ? U.button(L('Search channel or program'), function () { S.channelSearch(); }, { icon: 'search', cls: 'lv-search-btn' }) : null;
    var el = h('div.page.live', [h('div.lv-head', [h('h1', L('Live TV')), h('div.lv-head-btns', [searchBtn, mvBtn, guideBtn])]), h('div.lv-cols', [groupsEl, chansBox, preview])]);
    var pg = { el: el };
    var currentList = [], currentGroup = null, lastChan = null, list = null;
    var src = Lib.source ? Lib.source.id : '';

    function groupList() {
      var out = [];
      // build 128: favorieten en recente zenders van alle servers
      if (D.setList('favorites').length) out.push({ key: '★', label: L('Favorites') });
      if (D.setList('recentChannels').length) out.push({ key: '⟲', label: L('Recent') });
      out.push({ key: '*', label: L('All channels') });
      // build 102: Evenementen (PPV/EVENT, op begintijd) en je collecties met zenders (= LiveTVView op tvOS)
      if (B.parity && B.parity.events().length) out.push({ key: '⚡', label: '⚡ ' + L('Events') });
      channelCollections().forEach(function (c) { out.push({ key: '▤' + c.id, label: '▤ ' + c.name }); });
      lib.channelGroups.forEach(function (g) { out.push({ key: g, label: g }); });
      return out;
    }
    function channelCollections() {
      return D.collectionsFor(B.app.profile.id).filter(function (c) {
        if (c.sourceID && String(c.sourceID).toLowerCase() !== String(src).toLowerCase()) return false;
        return c.kind === 'smart' ? (!Array.isArray(c.types) || !c.types.length || c.types.indexOf('channel') >= 0)
          : (c.items || []).some(function (it) { return it && it.type === 'channel'; });
      });
    }
    function collectionFor(key) {
      if (String(key).charAt(0) !== '▤') return null;
      var id = key.slice(1), hit = null;
      D.collectionsFor(B.app.profile.id).forEach(function (c) { if (c.id === id) hit = c; });
      return hit;
    }
    var events = {};
    function channelsFor(key) {
      function fromKeys(keys) { return B.allservers.channels(keys); }
      if (key === '★') return fromKeys(D.setList('favorites'));
      if (key === '⟲') return fromKeys(D.setList('recentChannels'));
      if (key === '*') return lib.channels;
      if (key === '⚡') {
        events = {};
        return (B.parity ? B.parity.events() : []).map(function (e) { events[e.ch.id] = e.ev; return e.ch; });
      }
      var coll = collectionFor(key);
      if (coll) return Lib.collectionItems(coll, 500).filter(function (x) { return x.type === 'channel'; }).map(function (x) { return x.item; });
      return Lib.byGroup('channel')[key] || [];
    }
    var selectSoon = B.debounce(function (key) { if (currentGroup !== key) selectGroup(key); }, 220);
    function renderGroups() {
      U.clear(groupsEl.track);
      var last = B.store.get('liveGroup', null);
      groupList().forEach(function (g) {
        var it = h('div.lv-group.f', g.label);
        if (g.key === last) it.classList.add('autofocus');
        it._key = g.key;
        // tijdens snel scrollen door de categorieën niet bij elke stap de zenderlijst opbouwen
        it.onFocus = function () { if (currentGroup !== g.key) { if (B.keyRepeating) selectSoon(g.key); else selectGroup(g.key); } };
        it.onEnter = function () { if (currentGroup !== g.key) selectGroup(g.key); if (list && currentList.length) list.focusIndex(0); };
        groupsEl.track.appendChild(it);
      });
    }
    function selectGroup(key) {
      selectSoon.cancel();
      currentGroup = key;
      B.store.set('liveGroup', key);
      Array.prototype.forEach.call(groupsEl.track.children, function (c) { c.classList.toggle('active', c._key === key); });
      currentList = channelsFor(key);
      if (!list) {
        list = U.vlist({ count: currentList.length, rowHeight: 112, height: 860, buffer: 5, render: function (i) { return chanRow(currentList[i], i); }, cls: 'lv-vlist' });
        chansBox.appendChild(list.el);
      } else list.reset(currentList.length);
      U.clear(pvInfo);
      if (currentList.length) showInfo(currentList[0]);
      else pvInfo.appendChild(h('div.dim.lv-empty', L('No channels')));
      B.preview.stop();
    }
    function chanRow(ch, i) {
      if (!ch) return h('div.lv-chan');
      var nn = Lib.nowNext(ch);
      var fav = D.inSet('favorites', D.key(ch._src || src, ch.id));
      // build 102: in Evenementen de wedstrijd, de begintijd en LIVE zodra hij bezig is
      var ev = currentGroup === '⚡' ? events[ch.id] : null;
      var live = ev && B.parity.eventLive(ev);
      var row = h('div.lv-chan.f' + (ev ? '.event' : ''), [
        h('div.lv-num', ch.number != null ? String(ch.number) : ''),
        h('div.lv-logo', [U.img(ch.logo, 'logo-img', '')]),
        h('div.lv-text', [h('div.lv-name', [live ? h('span.live-badge', L('LIVE')) : null, ev ? ev.title : ch.name, fav ? h('span.lv-fav', ' ★') : null, ch.catchupDays > 0 ? h('span.lv-cu', ' ⟲') : null]),
          h('div.lv-now.dim', ev ? (B.parity.eventWhen(ev) || ch.name) : (nn.now ? nn.now.title : ''))])
      ]);
      if (nn.now) row.appendChild(h('div.lv-bar', [h('div', { style: { width: pct(nn.now) } })]));
      row._key = 'channel:' + (ch._src ? ch._src + '|' : '') + ch.id;
      row.onFocus = function () { focusChannel(ch); };
      row.onEnter = function () { lastChan = ch; B.preview.stop(); B.player.playChannel(ch, currentList); };
      row.onKey = function (name) {
        if (name === 'yellow') { toggleFav(ch); return true; }
        if (name === 'info' || name === 'blue') { U.push(S.guide(currentList, i)); return true; }
        return false;
      };
      // build 102: lang OK / Tools = contextmenu van de zender
      if (B.parity) B.parity.attachMenu(row, { type: 'channel', item: ch, list: currentList, collection: collectionFor(currentGroup) });
      return row;
    }
    function toggleFav(ch) {
      D.toggleSet('favorites', D.key(ch._src || src, ch.id));
      U.toast(D.inSet('favorites', D.key(ch._src || src, ch.id)) ? L('Added to favorites') : L('Removed from favorites'));
    }
    // info meteen (goedkoop), het videovoorbeeld pas na ~1,2 s stilstand en nooit tijdens ingedrukt houden
    var infoSoon = B.debounce(function (ch) { showInfo(ch); }, 90);
    function focusChannel(ch) {
      lastChan = ch;
      if (B.keyRepeating) infoSoon(ch); else showInfo(ch);
      B.preview.focus(ch, pvVideo);
    }
    function showInfo(ch) {
      U.clear(pvInfo);
      if (pvPh._id !== ch.id) { pvPh._id = ch.id; U.clear(pvPh); pvPh.appendChild(U.img(ch.logo, 'logo-img', ch.name, true)); }
      var nn = Lib.nowNext(ch);
      pvInfo.appendChild(h('div.lv-pv-name', [h('span.lv-pv-num', ch.number != null ? String(ch.number) : ''), ch.name]));
      if (nn.now) {
        pvInfo.appendChild(h('div.lv-pv-time', B.fmtTime(nn.now.start) + ' – ' + B.fmtTime(nn.now.end)));
        pvInfo.appendChild(h('div.lv-pv-title', nn.now.title));
        pvInfo.appendChild(h('div.lv-bar.big', [h('div', { style: { width: pct(nn.now) } })]));
        if (nn.now.description) pvInfo.appendChild(h('p.lv-pv-desc', nn.now.description));
      } else pvInfo.appendChild(h('div.dim', L('No guide information')));
      if (nn.next) pvInfo.appendChild(h('div.lv-pv-next', [h('b', L('Next') + '  '), B.fmtTime(nn.next.start) + '  ' + nn.next.title]));
    }
    function render() {
      lib = Lib.lib; src = Lib.source ? Lib.source.id : '';
      var st = stateBox();
      if (st) { U.clear(groupsEl.track); U.clear(chansBox); list = null; U.clear(pvInfo); chansBox.appendChild(st); return; }
      if (!list) U.clear(chansBox);
      renderGroups();
      var first = groupsEl.track.querySelector('.autofocus') || groupsEl.track.firstChild;
      currentGroup = null;
      if (first) selectGroup(first._key);
    }
    pg.refresh = render;
    pg.onData = function () {
      // favorieten/recent gewijzigd: alleen de zichtbare rijen opnieuw tekenen
      if (list) list.redrawVisible();
    };
    pg.onEpg = function () { if (list) list.redrawVisible(); if (lastChan) showInfo(lastChan); };
    pg.onHide = function () { B.preview.stop(); };
    pg.onShow = function (back) { if (back && lastChan && list && chansBox.contains(U.focused)) B.preview.focus(lastChan, pvVideo); };
    pg.destroy = function () { B.preview.stop(); };
    pg.focusIn = function () {
      if (list && currentList.length) {
        var idx = lastChan ? currentList.indexOf(lastChan) : -1;
        list.focusIndex(idx >= 0 ? idx : list.index());
        return true;
      }
      var act = groupsEl.track.querySelector('.active');
      if (act) { U.focus(act); return true; }
      return false;
    };
    pg.onBack = function () {
      if (chansBox.contains(U.focused)) { B.preview.stop(); var a = groupsEl.track.querySelector('.active'); if (a) { U.focus(a); return true; } }
      return false;
    };
    render();
    return pg;
  };

  // MARK: tv-gids (rijen hergebruiken, programma's via binair zoeken)

  /** Tv-gids: tijdlijn van 2,5 uur, rijen per zender; pijltjes per programma, Enter opent het programma. */
  S.guide = function (channels, startIndex) {
    var ROWS = 8, PX_MIN = 10, SPAN = 150, LEFT = 260;
    var winStart = Math.floor(Date.now() / 1800000) * 1800000;
    var row = B.clamp(startIndex || 0, 0, Math.max(0, channels.length - 1)), top = Math.max(0, row - 2);
    var cursor = Date.now();
    var grid = h('div.gd-grid');
    var times = h('div.gd-times');
    var detail = h('div.gd-detail');
    var hint = h('div.gd-hint.dim', L('OK: options  ·  CH+/CH−: page'));
    var el = layer('guide', [h('div.gd-head', [h('div.gd-head-left', [h('h1', L('TV guide')), hint]), detail]), times, grid]);
    var rowEls = [], drawnTop = -1, drawnWin = -1, focusedCell = null;
    function progsFor(ch) {
      var end = winStart + SPAN * 60000;
      var out = Lib.programsBetween(ch, winStart, end);
      if (!out.length) out = [{ title: ch.name, start: winStart, end: end, empty: true }];
      return out;
    }
    function drawTimes() {
      U.clear(times);
      times.appendChild(h('div.gd-corner'));
      for (var m = 0; m < SPAN; m += 30) times.appendChild(h('div.gd-time', { style: { left: (LEFT + m * PX_MIN) + 'px' } }, B.fmtTime(winStart + m * 60000)));
    }
    function buildRow(r) {
      var ch = channels[r];
      var line = h('div.gd-row', [h('div.gd-chan', [h('span.gd-num', ch.number != null ? String(ch.number) : ''), h('span', ch.name)])]);
      var now = Date.now(), endW = winStart + SPAN * 60000;
      line._progs = progsFor(ch);
      line._cells = line._progs.map(function (p) {
        var s = Math.max(p.start, winStart), e = Math.min(p.end, endW);
        var cls = (p.start <= now && now < p.end ? '.now' : '') + (Lib.canCatchup(ch, p) && p.end <= now ? '.cu' : '') +
          (!p.empty && B.reminders.has(B.reminders.id(ch, p)) ? '.rem' : '');
        var cell = h('div.gd-prog' + cls, { style: { left: (LEFT + (s - winStart) / 60000 * PX_MIN) + 'px', width: Math.max(4, (e - s) / 60000 * PX_MIN - 4) + 'px' } }, p.title);
        line.appendChild(cell);
        return cell;
      });
      line._r = r;
      return line;
    }
    function draw() {
      // tijdvenster of bovenste rij veranderd: rijen (maximaal 8) opnieuw; anders alleen de focus verplaatsen
      if (drawnWin !== winStart || drawnTop !== top) {
        if (drawnWin !== winStart) drawTimes();
        U.clear(grid);
        rowEls = [];
        var nowX = (Date.now() - winStart) / 60000 * PX_MIN;
        if (nowX >= 0 && nowX <= SPAN * PX_MIN) grid.appendChild(h('div.gd-nowline', { style: { left: (LEFT + nowX) + 'px' } }));
        for (var r = top; r < Math.min(channels.length, top + ROWS); r++) { var ln = buildRow(r); rowEls.push(ln); grid.appendChild(ln); }
        drawnWin = winStart; drawnTop = top;
      }
      if (focusedCell) focusedCell.classList.remove('focused');
      rowEls.forEach(function (ln) { ln.classList.toggle('cur', ln._r === row); });
      var line = rowEls[row - top];
      if (!line) return;
      var c = current();
      focusedCell = line._cells[c.i];
      if (focusedCell) focusedCell.classList.add('focused');
      showDetail(channels[row], c.list[c.i]);
    }
    function showDetail(ch, p) {
      U.clear(detail);
      detail.appendChild(h('div.gd-d-title', p.empty ? ch.name : p.title));
      var extra = '';
      if (!p.empty && Lib.canCatchup(ch, p) && p.end <= Date.now()) extra = '  ·  ⟲ ' + L('Watch from the start');
      else if (!p.empty && p.start > Date.now() && B.reminders.has(B.reminders.id(ch, p))) extra = '  ·  🔔 ' + L('Reminder set');
      detail.appendChild(h('div.dim', ch.name + (p.empty ? '' : '  ·  ' + B.fmtTime(p.start) + ' – ' + B.fmtTime(p.end)) + extra));
      if (p.description) detail.appendChild(h('p.gd-d-desc', p.description));
    }
    function current() {
      var line = rowEls[row - top];
      var list = line ? line._progs : progsFor(channels[row]);
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
        var minStart = Date.now() - 7 * 86400000;
        if (dir === 'right') {
          var nx = c.list[c.i + 1];
          cursor = nx ? nx.start + 1 : c.list[c.i].end + 1;
        } else {
          var pv = c.list[c.i - 1];
          cursor = pv ? Math.max(pv.start, minStart) + 1 : Math.max(minStart, c.list[c.i].start - 60000);
        }
        while (cursor >= winStart + SPAN * 60000) winStart += 1800000;
        while (cursor < winStart) winStart -= 1800000;
      }
      draw();
    }
    function openProgram() {
      var ch = channels[row], c = current(), p = c.list[c.i];
      if (!ch) return;
      if (p.empty) { B.player.playChannel(ch, channels); return; }
      S.programSheet(ch, p, channels, function () { drawnTop = -1; draw(); });
    }
    if (!channels.length) { grid.appendChild(h('div.state.dim', L('No channels'))); }
    else draw();
    return {
      el: el,
      onKey: function (name) {
        if (!channels.length) return false;
        if (name === 'left' || name === 'right' || name === 'up' || name === 'down') { move(name); return true; }
        if (name === 'chup') { row = Math.max(0, row - ROWS); top = Math.max(0, row - 2); draw(); return true; }
        if (name === 'chdown') { row = Math.min(channels.length - 1, row + ROWS); top = Math.max(0, row - 2); draw(); return true; }
        if (name === 'enter') { openProgram(); return true; }
        if (name === 'play') { B.player.playChannel(channels[row], channels); return true; }
        return false;
      },
      onBack: function () { U.pop(); return true; }
    };
  };

  /**
   * Programmavenster (= ProgramSheet op Android): live kijken, terugkijken (vanaf het begin), herinnering,
   * en de terugkijk-correctie per zender.
   */
  S.programSheet = function (ch, p, channels, onChange) {
    var now = Date.now();
    var isNow = p.start <= now && now < p.end;
    var canCu = Lib.canCatchup(ch, p);
    var src = Lib.source;
    var buttons = [];
    if (isNow) buttons.push({ label: L('Watch live'), play: true, fn: function () { B.player.playChannel(ch, channels); } });
    if (canCu && !isNow) buttons.push({ label: L('Watch from the start'), play: !isNow, fn: function () { B.player.playCatchup(ch, p); } });
    if (canCu && isNow) buttons.push({ label: L('From the start'), fn: function () { B.player.playCatchup(ch, p); } });
    if (p.start > now) {
      var on = B.reminders.has(B.reminders.id(ch, p));
      buttons.push({ label: on ? L('Reminder set') : L('Remind me'), fn: function () {
        var added = B.reminders.toggle(ch, p, src ? src.id : '');
        U.toast(added ? L('Reminder set') : L('Reminder removed'));
        if (onChange) onChange();
      } });
    }
    if (ch.catchupDays > 0 && src && D.isMain(B.app.profile)) {
      var off = D.catchupOffset(D.sourceById(src.id) || src, ch.id);
      buttons.push({ label: L('Catch-up offset…') + (off ? ' (' + (off > 0 ? '+' : '') + off + ' min)' : ''), fn: function () { S.catchupOffset(ch); } });
    }
    buttons.push({ label: L('Close') });
    var text = ch.name + '  ·  ' + new Date(p.start).toLocaleDateString(B.lang, { weekday: 'short', day: 'numeric', month: 'short' }) + ' ' +
      B.fmtTime(p.start) + ' – ' + B.fmtTime(p.end) + (p.description ? '\n\n' + p.description : '');
    U.modal({ title: p.title, text: text, buttons: buttons });
  };
  /** Terugkijk-correctie kiezen (−120…+120 min in stappen van 5; 0 = geen). Gaat mee in put_sources. */
  S.catchupOffset = function (ch) {
    var src = Lib.source;
    if (!src) return;
    var cur = D.catchupOffset(D.sourceById(src.id) || src, ch.id);
    var opts = [];
    for (var m = -120; m <= 120; m += 5) opts.push({ value: m, label: (m > 0 ? '+' : '') + m + ' min' + (m === 0 ? '  (' + L('None') + ')' : '') });
    U.choose(L('Catch-up offset'), opts, cur, function (v) {
      D.setCatchupOffset(D.sourceById(src.id) || src, ch.id, v);
      U.toast(L('Catch-up offset') + ': ' + (v > 0 ? '+' : '') + v + ' min');
    });
  };

  // MARK: Films / Series (categorieën links, virtueel raster rechts)

  function catalogPage(type) {
    var groupsEl = vScroller('.ct-groups.fm', { 'data-pad': '120' });
    var gridBox = h('div.ct-gridbox');
    var title = h('h1', type === 'movie' ? L('Movies') : L('Series'));
    var count = h('span.ct-count.dim');
    // build 102: sorteren (Standaard, A–Z, Z–A, Nieuwste, Hoogst gewaardeerd), zoals CategoryView op tvOS
    var sortBox = h('div.ct-sorts');
    var sort = B.store.get('catalog.sort.' + type, 'default');
    function drawSorts() {
      U.clear(sortBox);
      if (!B.parity) return;
      sortBox.appendChild(B.parity.sortPills(sort, function (k) {
        sort = k;
        B.store.set('catalog.sort.' + type, k);
        drawSorts();
        var cur = groupKey;
        groupKey = null;
        select(cur);
        var a = sortBox.querySelector('.cat-pill.active');
        if (a) U.focus(a, true);
      }));
    }
    var el = h('div.page.catalog', [h('div.ct-head', [title, count, sortBox]), h('div.ct-cols', [groupsEl, gridBox])]);
    var pg = { el: el };
    var list = [], groupKey = null, grid = null;
    var COLS = 5, CW = 238, CH = 345;
    function items() { return type === 'movie' ? Lib.lib.movies : Lib.lib.series; }
    function groups() { return type === 'movie' ? Lib.lib.movieGroups : Lib.lib.seriesGroups; }
    var selectSoon = B.debounce(function (key) { if (groupKey !== key) select(key); }, 220);
    function render() {
      U.clear(groupsEl.track); U.clear(gridBox); grid = null;
      var st = stateBox();
      if (st) { gridBox.appendChild(st); return; }
      var all = [{ key: '*', label: L('All') }];
      if (Lib.recent(items(), 1).length) all.push({ key: '+', label: L('Recently added') });
      // build 102: je collecties met films/series als groep (= LiveTVView/CategoryView op tvOS)
      collections().forEach(function (c) { all.push({ key: '▤' + c.id, label: '▤ ' + c.name }); });
      groups().forEach(function (g) { all.push({ key: g, label: g }); });
      drawSorts();
      var last = B.store.get('catalog.' + type, null);
      all.forEach(function (g) {
        var it = h('div.ct-group.f', g.label);
        it._key = g.key;
        if (g.key === last) it.classList.add('autofocus');
        it.onFocus = function () { if (groupKey !== g.key) { if (B.keyRepeating) selectSoon(g.key); else select(g.key); } };
        it.onEnter = function () { if (groupKey !== g.key) select(g.key); if (grid && list.length) grid.focusIndex(0); };
        groupsEl.track.appendChild(it);
      });
      var first = groupsEl.track.querySelector('.autofocus') || groupsEl.track.firstChild;
      groupKey = null;
      if (first) select(first._key);
    }
    function select(key) {
      selectSoon.cancel();
      groupKey = key;
      B.store.set('catalog.' + type, key);
      Array.prototype.forEach.call(groupsEl.track.children, function (c) { c.classList.toggle('active', c._key === key); });
      var all = items();
      var coll = String(key).charAt(0) === '▤' ? collectionById(key.slice(1)) : null;
      list = key === '*' ? all : key === '+' ? Lib.recent(all, 60) : coll ? Lib.collectionItems(coll, 2000).filter(function (x) { return x.type === type; }).map(function (x) { return x.item; })
        : (Lib.byGroup(type)[key] || []);
      if (B.parity) list = B.parity.sort(list, sort);
      count.textContent = list.length ? '  ' + list.length : '';
      if (!grid) {
        U.clear(gridBox);
        grid = U.vgrid({ count: list.length, cols: COLS, cellW: CW, cellH: CH, height: 880, render: function (i) {
          var x = list[i];
          var p = type === 'movie' ? D.progress(x.id) : null;
          var c2 = String(groupKey).charAt(0) === '▤' ? collectionById(groupKey.slice(1)) : null;
          return posterCard(x, type, { progress: p ? p.fraction : null, collection: c2 });
        } });
        gridBox.appendChild(grid.el);
      } else grid.reset(list.length);
      var empty = gridBox.querySelector('.state');
      if (empty) gridBox.removeChild(empty);
      if (!list.length) gridBox.appendChild(h('div.state.dim', L('Nothing here yet')));
    }
    function collections() {
      var prof = B.app.profile;
      return prof ? D.collectionsFor(prof.id).filter(function (c) {
        if (c.kind === 'smart') return !Array.isArray(c.types) || !c.types.length || c.types.indexOf(type) >= 0;
        return (c.items || []).some(function (it) { return it && it.type === type; });
      }) : [];
    }
    function collectionById(id) { var hit = null; D.collectionsFor(B.app.profile.id).forEach(function (c) { if (c.id === id) hit = c; }); return hit; }
    pg.refresh = render;
    pg.focusIn = function () {
      if (grid && list.length) { grid.focusIndex(grid.index()); return true; }
      var a = groupsEl.track.querySelector('.active'); if (a) { U.focus(a); return true; } return false;
    };
    pg.onBack = function () {
      if (gridBox.contains(U.focused)) { var a = groupsEl.track.querySelector('.active'); if (a) { U.focus(a); return true; } }
      return false;
    };
    render();
    return pg;
  }
  S.pages.movies = function () { return catalogPage('movie'); };
  S.pages.series = function () { return catalogPage('series'); };

  // MARK: Mijn lijst

  S.pages.mylist = function () {
    var box = h('div.ct-gridbox.full');
    var el = h('div.page.catalog', [h('div.ct-head', [h('h1', L('My List'))]), box]);
    var pg = { el: el };
    function render() {
      U.clear(box);
      // build 128: Mijn lijst over alle servers
      var items = B.allservers.myList();
      if (B.discover) items = items.concat(B.discover.myListHits());   // build 111: TMDB-titels
      if (!items.length) { box.appendChild(h('div.state', [h('h2', L('Your list is empty')), h('div.dim', L('Add movies and series with “My List” on their page.'))])); return; }
      var grid = U.vgrid({ count: items.length, cols: 6, cellW: 238, cellH: 345, height: 880, render: function (i) { return posterCard(items[i].item, items[i].type); } });
      box.appendChild(grid.el);
    }
    pg.refresh = render;
    pg.onData = render;
    render();
    return pg;
  };

  // MARK: profielmenu (build 72)

  /**
   * Profielmenu, geopend vanaf de avatar bovenaan het menu: de andere profielen (meteen wisselen, met pincode als dat
   * profiel er een heeft), Wissel van profiel, Mijn lijst, Instellingen, Account en apparaten en Uitloggen.
   * Profielen beheren kan op de tv niet (dat gaat in de andere apps of op bingr.online), dus dat item ontbreekt hier.
   * go(pagina) toont een pagina van het hoofdscherm.
   */
  S.profileMenu = function (go) {
    var list = h('div.pm-list.sy.fm', { 'data-pad': '40' });
    var s = { modal: true, el: layer('modal.pm-layer', [h('div.modal-box.pm-box', [list])]) };
    function item(icon, label, fn, cls) {
      var el = h('div.choice.pm-item.f' + (cls || ''), [icon, h('span.pm-label', label)]);
      el.onEnter = function () { U.pop(); fn(); };
      list.appendChild(el);
      return el;
    }
    function sep() { list.appendChild(h('div.pm-sep')); }
    var cur = B.app.profile;
    // build 140: een kinderprofiel verlaten (wisselen, instellingen, account, uitloggen) = eerst de rekensom
    function gate(fn) { if (B.kidsGate) B.kidsGate.guard(fn); else fn(); }
    var others = D.profiles.filter(function (p) { return !cur || p.id !== cur.id; });
    others.forEach(function (p) {
      var el = item(S.avatar(p, 'mini'), p.name, function () {
        gate(function () {
          var pin = D.pinFor(p);
          if (pin) U.askPIN(L('PIN for %@', p.name), function (x) { return B.b146.pin.check(x, pin); }, function () { B.app.openProfile(p); });
          else B.app.openProfile(p);
        });
      });
      if (D.pinFor(p)) el.appendChild(U.icon('lock', 'pm-lock'));
    });
    if (others.length) sep();
    // build 146: alle servers vernieuwen (één voor één, met de bijwerk-indicator), direct boven "Wissel profiel"; niet bij
    // een kinderprofiel
    if (!(cur && cur.kids) && D.sources.length) item(U.icon('replay'), L('Refresh servers'), function () { S.refreshServers(); });
    item(U.icon('people'), L('Switch profile'), function () { gate(function () { B.app.switchProfile(); }); });
    item(U.icon('list'), L('My List'), function () { go('mylist'); });
    // build 86: Categorieën (eerst een menu-item links)
    var lib = Lib.lib;
    if (lib.movies.length || lib.series.length) item(U.icon('grid'), L('Categories'), function () { go('categories'); });
    // build 146: Instellingen in tabs; de badge = ongelezen antwoorden van support (de tv heeft geen push)
    var unread = B.b146.support.badge(B.support.unread());
    var st = item(U.icon('gear'), L('Settings'), function () { gate(function () { S.settingsTab = 'general'; go('settings'); B.emit('settingsTab'); }); });
    if (unread) st.appendChild(h('span.badge', unread));
    if (A.isSignedIn()) {
      item(U.icon('person'), L('Account & devices'), function () { gate(function () { S.settingsTab = 'account'; go('settings'); B.emit('settingsTab'); }); });
      // build 88: abonnement (Gratis / Lifetime)
      var ent = A.entitlements();
      if (ent) item(U.icon('lifetime'), L('Subscription') + ': ' + (ent.free ? L('Free') : L('Lifetime')), function () { gate(function () { S.upgrade(null); }); });
    }
    // build 146: Support als tab in Instellingen (tickets, reageren, nieuw ticket, diagnose)
    var sp = item(U.icon('help'), L('Support'), function () { gate(function () { S.settingsTab = 'support'; go('settings'); B.emit('settingsTab'); }); });
    if (unread) sp.appendChild(h('span.badge', unread));
    if (A.isSignedIn()) {
      sep();
      item(U.icon('signout'), L('Sign out'), function () {
        gate(function () {
          U.modal({ title: L('Sign out'), text: L('Sign out of Bingr on this device?'), buttons: [
            { label: L('Sign out'), fn: function () { B.app.signOut(); } }, { label: L('Cancel') }] });
        });
      }, '.danger');
    }
    s.onBack = function () { U.pop(); };
    U.push(s);
    return s;
  };

  // MARK: Zoeken

  S.pages.search = function () {
    var query = h('div.search-query', L('Search'));
    var results = vScroller('.search-results', { 'data-align': 'top', 'data-pad': '20' });
    var timer = null;
    var kb = U.keyboard({
      layout: 'search', onChange: function (v) {
        query.textContent = v || L('Search');
        query.classList.toggle('placeholder', !v);
        clearTimeout(timer);
        timer = setTimeout(function () { B.whenQuiet(function () { run(v); }, 250); }, 350);
      }
    });
    query.classList.add('placeholder');
    var el = h('div.page.search', [h('div.search-left', [query, kb]), results]);
    var pg = { el: el };
    var gen = 0, stopExtras = null;
    function run(v) {
      var my = ++gen;
      if (stopExtras) { stopExtras(); stopExtras = null; }
      U.clear(results.track);
      U.scrollTo(results, 0);
      if (!v.trim()) return;
      var r = Lib.search(v, 30);
      var a = rowBlock(L('Series'), r.series.map(function (s) { return posterCard(s, 'series'); }), 'srch-series');
      var b = rowBlock(L('Movies'), r.movies.map(function (m) { return posterCard(m, 'movie'); }), 'srch-movie');
      var c = rowBlock(L('Live TV'), r.channels.map(function (ch) { return channelCard(ch, r.channels); }), 'live');
      [b, a, c].forEach(function (x) { if (x) results.track.appendChild(x); });
      var empty = null;
      if (!a && !b && !c) results.track.appendChild(empty = h('div.state.dim', L('No results for “%@”', v)));
      // build 102: ook personen (TMDB), categorieën/genres, de tv-gids en je andere afspeellijsten (= SearchView op tvOS)
      // build 129: en de treffers op je andere servers in dezelfde rijen (ontdubbeld, openen zonder te wisselen)
      if (B.parity) stopExtras = B.parity.searchExtras(v, results.track, function () { return my === gen && document.body.contains(results); }, empty, r);
    }
    pg.destroy = function () { gen++; if (stopExtras) stopExtras(); };
    pg.focusIn = function () { var k = kb.querySelector('.key'); if (k) { U.focus(k); return true; } return false; };
    pg.refresh = function () { run(kb.getValue()); };
    // build 102: zoeken met een titel van buitenaf (aanbevelingen en acteurspagina, S.searchFor in details.js)
    pg.setQuery = function (v) {
      v = v || '';
      kb.setValue(v);
      query.textContent = v || L('Search');
      query.classList.toggle('placeholder', !v);
      clearTimeout(timer);
      run(v);
    };
    return pg;
  };

  // MARK: detailpagina (film of serie, met seizoenen en afleveringen)

  S.detail = function (type, x) {
    // build 111: titel uit TMDB (Binnenkort op Bingr / Nieuw op Bingr), niet in je bibliotheek
    if (B.discover && B.discover.isExternal(x.id)) return B.discover.detail(type, x);
    // build 128: titel van een andere server (x._src): Mijn lijst en afspelen op die server
    var src = x._src || (Lib.source ? Lib.source.id : '');
    var listKey = D.key(src, x.id);
    var plot = h('p.dt-plot', x.plot || '');
    var meta = h('div.dt-meta');
    var langEl = h('div.dt-lang');
    var buttons = h('div.dt-buttons.fm');
    var seasonsEl = h('div.dt-seasons.sx.fm', { 'data-pad': '40' });
    var epsEl = h('div.dt-eps.sx.fm', { 'data-pad': '80' });
    var epsTrack = h('div.row-track');
    epsEl.appendChild(epsTrack);
    var body = vScroller('.dt-body', { 'data-pad': '60' });
    // build 102: genres en regisseur onder de beschrijving, rijen Acteurs en Aanbevelingen onderaan (js/details.js)
    var creditsEl = h('div.dt-credits');
    var extraEl = h('div.dt-extra');
    // build 102: beoordelingen (IMDb/TMDB/Trakt/afspeellijst), voortgang van de serie en "Andere bronnen" (= DetailView op tvOS)
    var ratingsEl = h('div.dt-ratings-slot');
    var sprogEl = h('div.dt-sprog');
    var altEl = h('div.dt-alt-slot');
    var P = B.parity;
    // build 102: de poster schuift mee met de pagina (anders ligt hij over de rijen onderaan)
    body.track.appendChild(h('div.dt-poster', [U.img(x.poster, 'poster-img', x.title, true)]));
    body.track.appendChild(h('div.dt-info', [h('div.dt-kind', type === 'series' ? L('SERIES') : L('FILM')), h('h1.dt-title', x.title), meta, langEl, plot, creditsEl, buttons, ratingsEl]));
    if (type === 'series') body.track.appendChild(h('div.dt-episodes', [sprogEl, seasonsEl, epsEl]));
    body.track.appendChild(altEl);
    body.track.appendChild(extraEl);
    body.track.appendChild(h('div.dt-end'));
    var bg = h('div.dt-bg', [h('div.dt-kb', [U.img(x.poster, 'hero-img', null, true)])]);
    var el = layer('detail', [bg, h('div.dt-shade'), body]);
    var alive = true;
    var episodes = [], season = null, currentEp = null;
    var stremio = Lib.source && Lib.source.kind === 'Stremio';

    // MARK: trailer in de kop (build 82, = Android/iOS en bingr.online/movie/…)
    // Na 1,5 s zonder geluid in de achtergrond (YouTube-embed, youtube-nocookie), pas zichtbaar als hij echt speelt.
    // Series: trailer van het nieuwste seizoen, anders die van de serie. Stopt bij afspelen, een ander scherm,
    // de app op de achtergrond en aan het einde.
    // Build 85: de app draait vanaf file://, dus de embed krijgt geen Referer mee en YouTube weigert hem sindsdien
    // (fout 153). Daarom eerst de embed met widget_referrer, en lukt dat niet, dan via een kleine doorgeefpagina op
    // https://bingr.online/trailer/ (echte https-herkomst; geeft dezelfde widget-berichten door). Lukt ook dat niet
    // (fout of te traag), dan beweegt de kopafbeelding rustig (Ken Burns).
    var TRAILER_RELAY = 'https://bingr.online/trailer/';
    var tr = { frame: null, playing: false, muted: true, started: false, looking: false, wait: null, timeout: null, duration: 0,
      key: null, attempt: 0, failed: false };
    function trLog() {
      // diagnose alleen als debug aan staat (window.BINGR_DEBUG of D.setLocal('debug', true))
      if (!window.BINGR_DEBUG && D.local('debug', false) !== true) return;
      B.log.apply(null, ['trailer'].concat(Array.prototype.slice.call(arguments)));
    }
    function trailerAllowed() {
      if (D.local('autoplayTrailers', true) === false || document.hidden) return false;
      try { if (window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return false; } catch (e) { }
      var top = U.top();
      return !!top && top.el === el && !B.player.current;
    }
    function trailerSend(func, args) {
      try { tr.frame.contentWindow.postMessage(JSON.stringify({ event: 'command', func: func, args: args || [], id: 1, channel: 'widget' }), '*'); } catch (e) { }
    }
    function onTrailerMessage(ev) {
      if (!tr.frame || ev.source !== tr.frame.contentWindow) return;
      var m = null;
      try { m = typeof ev.data === 'string' ? JSON.parse(ev.data) : ev.data; } catch (e) { return; }
      if (!m) return;
      var state = m.event === 'onStateChange' ? m.info : (m.event === 'infoDelivery' && m.info ? m.info.playerState : null);
      if (m.event === 'onReady') trLog('ready', tr.attempt);
      if (m.event === 'onError') { trLog('fout', m.info, 'poging', tr.attempt); trailerFailed(); return; }
      if (state != null && state !== -1) trLog('state', state);
      if (state === 1 && !tr.playing) {
        tr.playing = true;
        clearTimeout(tr.timeout);
        tr.frame.className = 'dt-trailer on';
        bg.className = 'dt-bg';
        drawButtonsKeepFocus();
      } else if (state === 0) stopTrailer();
      // net vóór het einde stoppen (geen eindscherm met andere video's)
      if (m.event === 'infoDelivery' && m.info && m.info.currentTime != null && m.info.duration) tr.duration = m.info.duration;
      if (m.event === 'infoDelivery' && m.info && m.info.currentTime != null && tr.duration && tr.duration - m.info.currentTime < 1.2) stopTrailer();
    }
    function trailerSrc(id) {
      if (tr.attempt === 0) {
        return 'https://www.youtube-nocookie.com/embed/' + id + '?autoplay=1&mute=1&controls=0&playsinline=1&enablejsapi=1&rel=0' +
          '&iv_load_policy=3&disablekb=1&fs=0&modestbranding=1&cc_load_policy=0' +
          '&widget_referrer=' + encodeURIComponent('https://bingr.online/tizen');
      }
      return TRAILER_RELAY + '?v=' + encodeURIComponent(id) + '&app=tizen';
    }
    function mountTrailer() {
      var f = document.createElement('iframe');
      f.className = 'dt-trailer';
      f.setAttribute('tabindex', '-1');
      f.setAttribute('allow', 'autoplay; encrypted-media');
      f.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
      f.setAttribute('frameborder', '0');
      f.src = trailerSrc(tr.key);
      trLog('start poging', tr.attempt, tr.key);
      f.onload = function () {
        if (tr.frame !== f) return;
        // de IFrame-API stuurt pas berichten na "listening"
        try { f.contentWindow.postMessage(JSON.stringify({ event: 'listening', id: 1, channel: 'widget' }), '*'); } catch (e) { }
        trailerSend('mute');
        trailerSend('playVideo');
      };
      tr.frame = f;
      tr.playing = false;
      bg.appendChild(f);
      clearTimeout(tr.timeout);
      tr.timeout = setTimeout(function () { if (!tr.playing) { trLog('speelt niet binnen 12 s, poging', tr.attempt); trailerFailed(); } }, 12000);
    }
    function startTrailer(key) {
      if (tr.started || !key || !trailerAllowed()) return;
      tr.started = true;
      tr.key = String(key).replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32);
      tr.attempt = 0;
      tr.muted = true;
      window.addEventListener('message', onTrailerMessage);
      mountTrailer();
    }
    function removeFrame() {
      var f = tr.frame;
      if (!f) return;
      tr.frame = null;
      f.className = 'dt-trailer';
      setTimeout(function () { try { f.src = 'about:blank'; } catch (e) { } if (f.parentNode) f.parentNode.removeChild(f); }, 350);
    }
    /** Embed geweigerd of te traag: eerst de doorgeefpagina proberen, daarna Ken Burns op de kopafbeelding. */
    function trailerFailed() {
      clearTimeout(tr.timeout);
      if (tr.playing) { stopTrailer(); return; }
      trailerSend('stopVideo');
      removeFrame();
      // meteen rustig bewegen; speelt de tweede poging alsnog, dan komt de trailer eroverheen
      tr.failed = true;
      if (!tr.playing) bg.className = 'dt-bg kb';
      if (tr.attempt === 0 && trailerAllowed()) { tr.attempt = 1; mountTrailer(); return; }
      window.removeEventListener('message', onTrailerMessage);
    }
    function stopTrailer() {
      clearTimeout(tr.wait); clearTimeout(tr.timeout);
      tr.wait = null;
      if (!tr.frame) return;
      var was = tr.playing;
      trailerSend('stopVideo');
      window.removeEventListener('message', onTrailerMessage);
      removeFrame();
      tr.playing = false;
      if (was) drawButtonsKeepFocus();
    }
    /** Trailer opzoeken en na 1,5 s starten (één keer per pagina). */
    function scheduleTrailer() {
      if (tr.started || tr.looking || D.local('autoplayTrailers', true) === false) return;
      var newest = null;
      if (type === 'series') episodes.forEach(function (e) { if (newest == null || e.season > newest) newest = e.season; });
      var t0 = Date.now();
      tr.looking = true;
      B.ageRatings.trailer(x, type === 'series', newest).then(function (key) {
        if (!key || !document.body.contains(el)) return;
        tr.wait = setTimeout(function () { tr.wait = null; startTrailer(key); }, Math.max(0, 1500 - (Date.now() - t0)));
      });
    }
    function trailerToggleMute() {
      tr.muted = !tr.muted;
      if (tr.muted) trailerSend('mute'); else { trailerSend('unMute'); trailerSend('setVolume', [100]); }
      drawButtonsKeepFocus();
    }
    function onTrailerVisibility() { if (document.hidden) stopTrailer(); }
    document.addEventListener('visibilitychange', onTrailerVisibility);

    var metaExtra = null;
    function metaText(extra) {
      // build 102: speelduur/genre blijven staan als de pagina opnieuw getekend wordt (terug van een acteur)
      if (extra) metaExtra = extra; else extra = metaExtra;
      var parts = [];
      if (x.year) parts.push(String(x.year));
      if (x.rating) parts.push('★ ' + (Math.round(x.rating * 10) / 10));
      if (extra) parts = parts.concat(extra);
      if (x.group) parts.push(x.group);
      if (type === 'movie' && B.trakt.isMovieWatched(x)) parts.push('✓ Trakt');
      var ar = B.ageRatings.cached(x.rawTitle || x.title, x.year, type === 'series');
      if (ar && ar.age != null) parts.push(ar.age + '+');
      meta.textContent = parts.join('  ·  ');
    }
    metaText();

    // taal van de titel ("Taal: Italiaans") en andere taalversies (zelfde TMDB-id of titel + jaar)
    var isShow = type === 'series', versions = [];
    function drawLang() {
      var info = B.clang.forItem(x, isShow);
      langEl.textContent = B.clang.line(info);
      langEl.style.display = langEl.textContent ? '' : 'none';
      return info;
    }
    function refreshVersions() {
      var n = versions.length;
      versions = B.clang.versions(x, type);
      return versions.length !== n;
    }
    drawLang();
    // versie-index (hele bibliotheek) pas na het tekenen van de pagina
    setTimeout(function () { if (document.body.contains(el) && refreshVersions()) drawButtonsKeepFocus(); }, 60);
    // op de achtergrond: TMDB (originele taal als terugval, en het TMDB-id voor de versies); nooit wachten
    B.ageRatings.originalLanguage(x, isShow).then(function () {
      if (!document.body.contains(el)) return;
      drawLang();
      if (refreshVersions()) drawButtonsKeepFocus();
    });
    function chooseVersion() {
      if (!versions.length) { U.toast(L('No other language version found.')); return; }
      U.choose(L('Other language'), versions.map(function (v, i) {
        var y = v.item.year ? ' (' + v.item.year + ')' : '';
        return { value: i, label: v.label + '  ·  ' + v.item.title + y + (v.item.group ? '  ·  ' + v.item.group : '') };
      }), null, function (i) { U.push(S.detail(type, versions[i].item)); });
    }

    function listButton() {
      var on = D.inSet('myList', listKey);
      // plus / vinkje; bij wisselen blijft de knop staan (met focus) en animeert alleen het icoon
      var b = U.plusCheckButton('mylist', L('My List'), on, function () {
        // build 102: hoofdprofiel met Trakt: ook op de Trakt-watchlist
        if (P) P.toggleMyList(type, x); else D.toggleSet('myList', listKey);
        U.setPlusCheck(b, D.inSet('myList', listKey));
      });
      return b;
    }
    /** Stremio: bron kiezen uit alle directe streams van de add-ons. */
    function chooseStream(item, ctx) {
      U.toast(L('Loading…'));
      Lib.stremioStreams(item.stremio, item.src).then(function (list) {
        if (!list.length) { U.toast(L('No playable stream found.')); return; }
        U.choose(L('Choose a source'), list.slice(0, 40).map(function (s, i) { return { value: i, label: s.title + '  ·  ' + s.addon }; }), null, function (i) {
          item.streamURL = list[i].url;
          B.player.playVOD(item, ctx);
        });
      });
    }
    function drawButtons() {
      U.clear(buttons);
      if (type === 'movie') {
        var p = D.progress(x.id);
        var label = p && p.fraction > 0.02 ? L('Resume') : L('Play');
        buttons.appendChild(U.button(label, function () { stopTrailer(); B.player.playVOD(B.player.movieItem(x), {}); }, { primary: true, icon: 'play', autofocus: true }));
        if (p && p.fraction > 0.02) {
          buttons.appendChild(U.button(L('Watch from the beginning'), function () { stopTrailer(); B.player.open(B.player.movieItem(x), { resume: 0 }); }, { icon: 'replay' }));
          buttons.appendChild(h('div.dt-progress', [h('div.card-progress.inline', [h('div', { style: { width: Math.round(p.fraction * 100) + '%' } })]),
            h('span.dim', L('%d min left', Math.max(1, Math.round((p.position / p.fraction - p.position) / 60))))]));
        }
        if (stremio && x.stremio) buttons.appendChild(U.button(L('Choose a source'), function () { chooseStream(B.player.movieItem(x), {}); }, { icon: 'layers' }));
      } else {
        var ep = currentEp;
        var lbl = ep ? (D.progress(ep.id) ? L('Resume') : L('Play')) + '  S' + ep.season + ':E' + ep.number : L('Play');
        buttons.appendChild(U.button(lbl, function () { if (currentEp) playEpisode(currentEp); }, { primary: true, icon: 'play', autofocus: true }));
        if (stremio && ep) buttons.appendChild(U.button(L('Choose a source'), function () { if (currentEp) chooseStream(B.player.episodeItem(currentEp, x), { episodes: episodes, series: x }); }, { icon: 'layers' }));
      }
      if (tr.playing) buttons.appendChild(U.button(tr.muted ? L('Sound on') : L('Sound off'), trailerToggleMute, { icon: tr.muted ? 'volumeOff' : 'volume' }));
      if (versions.length) buttons.appendChild(U.button(L('Other language'), chooseVersion, { icon: 'globe' }));
      // build 102: trailer beeldvullend met geluid (serie: trailer van het gekozen seizoen, zoals tvOS)
      if (trailerKey) {
        var tl = type === 'series' && trailerSeason != null ? L('Trailer season %d', trailerSeason) : L('Trailer');
        var tb = U.button(tl, function () { stopTrailer(); P.playTrailer(trailerKey); }, { icon: 'play', cls: 'dt-trailer-btn' });
        buttons.appendChild(tb);
      }
      buttons.appendChild(listButton());
      if (P) {
        // build 102: bekeken (film) en aan een collectie toevoegen
        if (type === 'movie') {
          var w = P.movieWatched(x);
          buttons.appendChild(U.button(w ? L('Watched') : L('Mark watched'), function () {
            P.setMovieWatched(x, !w);
            U.toast(w ? L('Marked as unwatched') : L('Marked as watched'));
            drawButtonsKeepFocus();
            var again = buttons.querySelector('.dt-watched-btn');
            if (again) U.focus(again, true);
          }, { icon: w ? 'check' : 'eye', cls: 'dt-watched-btn' + (w ? ' on' : '') }));
        }
        buttons.appendChild(U.button(L('Collection'), function () { P.chooseCollection(type, x); }, { icon: 'grid', cls: 'dt-coll-btn' }));
      }
      // build 102: terug bij de knoppen = weer helemaal naar boven (titel en beschrijving in beeld)
      Array.prototype.forEach.call(buttons.querySelectorAll('.f'), function (b) { if (!b.onFocus) b.onFocus = toTop; });
    }
    function toTop() { U.scrollTo(body, 0); }
    function playEpisode(ep) {
      stopTrailer();
      B.player.playVOD(B.player.episodeItem(ep, x), { episodes: episodes, series: x });
    }
    function epWatched(ep) {
      return D.isWatched(ep.id) || D.isWatched(D.episodeKey(ep.seriesID, ep.season, ep.number)) || B.trakt.isEpisodeWatched(x, ep.season, ep.number);
    }
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
      seasonsEl._track = undefined;
      var track = h('div.row-track');
      seasons().forEach(function (sn) {
        var t = h('div.season-tab.f' + (sn === season ? '.active' : ''), L('Season %d', sn));
        t.onFocus = function () { if (season !== sn) { season = sn; drawSeasons(); drawEpisodes(null); drawProgress(); lookupTrailer(sn); } };
        t.onEnter = function () { var f = epsTrack.querySelector('.f'); if (f) U.focus(f); };
        track.appendChild(t);
      });
      // build 102: heel seizoen (niet) bekeken, ook naar Trakt (= vinkje naast de seizoenen op tvOS)
      var list = episodes.filter(function (e) { return e.season === season; });
      if (P && list.length) {
        var all = list.every(epWatched);
        var mk = h('div.season-tab.season-mark.f' + (all ? '.on' : ''), [U.icon('check'), h('span', all ? L('Mark season as unwatched') : L('Mark whole season watched'))]);
        mk.onEnter = function () {
          P.setEpisodesWatched(x, list, !all);
          U.toast(all ? L('Marked as unwatched') : L('Marked as watched'));
          drawSeasons(); drawEpisodes(null); drawProgress();
          var again = seasonsEl.querySelector('.season-mark');
          if (again) U.focus(again, true);
        };
        mk._key = 'season-mark';
        track.appendChild(mk);
      }
      seasonsEl.appendChild(track);
      var act = seasonsEl.querySelector('.active');
      seasonsEl._last = act;
      if (act && U.focused && !document.body.contains(U.focused)) U.focus(act, true);
    }
    function drawEpisodes(focusEp) {
      U.clear(epsTrack);
      U.scrollTo(epsEl, 0);
      var target = null;
      episodes.filter(function (e) { return e.season === season; }).forEach(function (e) {
        var p = D.progress(e.id);
        var watched = epWatched(e);
        var pc = p ? Math.round(p.fraction * 100) : (watched ? 100 : 0);
        var card = h('div.ep-card.f', [
          h('div.ep-still', [U.img(e.still || x.poster, 'still-img', ''), watched ? h('div.ep-watched', [U.icon('check')]) : null,
            pc ? h('div.card-progress', [h('div', { style: { width: pc + '%' } })]) : null]),
          h('div.ep-title', e.number + '. ' + e.title.replace(/^.*?S\d+\s*E\d+\s*[-:]?\s*/i, '')),
          h('div.ep-sub.dim', [e.durationSeconds ? Math.round(e.durationSeconds / 60) + ' min' : '', pc && !watched ? '  ·  ' + pc + '%' : '']),
          e.plot ? h('div.ep-plot.dim', e.plot) : null
        ]);
        card.onEnter = function () { playEpisode(e); };
        card.onFocus = function () { if (currentEp !== e) { currentEp = e; if (stremio) drawButtonsKeepFocus(); } };
        card._key = 'ep:' + e.id;
        // build 102: lang OK / Tools: afspelen of (niet) bekeken (= contextMenu van de afleveringskaart op tvOS)
        if (P) card._menu = function () {
          var wd = epWatched(e);
          P.menu('S' + e.season + ':E' + e.number + '  ' + e.title.replace(/^.*?S\d+\s*E\d+\s*[-:]?\s*/i, ''), [
            { key: 'play', icon: 'play', label: L('Play'), fn: function () { playEpisode(e); } },
            { key: 'watched', icon: 'check', label: wd ? L('Mark as unwatched') : L('Mark as watched'), fn: function () {
              P.setEpisodesWatched(x, [e], !wd);
              U.toast(wd ? L('Marked as unwatched') : L('Marked as watched'));
              drawSeasons(); drawEpisodes(e); drawProgress();
              var c2 = epsTrack.querySelector('.ep-card.f'), all2 = epsTrack.querySelectorAll('.ep-card.f');
              for (var i2 = 0; i2 < all2.length; i2++) if (all2[i2]._key === 'ep:' + e.id) c2 = all2[i2];
              if (c2) U.focus(c2, true);
            } }
          ]);
        };
        if (focusEp && e.id === focusEp.id) target = card;
        epsTrack.appendChild(card);
      });
      // omlaag naar de afleveringen komt uit bij de huidige aflevering
      epsEl._last = target || epsTrack.querySelector('.f');
      return target;
    }
    /** build 102: hoeveel van de serie en van dit seizoen je gezien hebt (= watchedFraction op tvOS). */
    function drawProgress() {
      U.clear(sprogEl);
      if (!P || !episodes.length) return;
      var f = P.fraction(x, episodes);
      if (f > 0) sprogEl.appendChild(P.progressLine(L('%d%% of the series watched', Math.round(f * 100)), f));
      var list = episodes.filter(function (e) { return e.season === season; });
      var fs = P.fraction(x, list);
      if (seasons().length > 1 && fs > 0) sprogEl.appendChild(P.progressLine(L('Season %d · %d%% watched', season, Math.round(fs * 100)), fs));
    }
    // build 102: trailer voor de knop (film; serie: van het gekozen seizoen, anders van de serie)
    var trailerKey = null, trailerSeason = null, trailerAsk = 0;
    function lookupTrailer(sn) {
      var my = ++trailerAsk;
      if (!P || !B.ageRatings.hasKey()) return;
      B.ageRatings.trailer(x, isShow, isShow ? sn : null).then(function (k) {
        if (!alive || my !== trailerAsk) return;
        var changed = k !== trailerKey;
        trailerKey = k || null;
        trailerSeason = isShow ? sn : null;
        if (changed || isShow) drawButtonsKeepFocus();
      }, B.noop);
    }
    var kb = null;
    function drawButtonsKeepFocus() { var f = U.focused; drawButtons(); if (f && !document.body.contains(f)) { var b = buttons.querySelector('.f'); if (b) U.focus(b, true); } }
    function loadEpisodes() {
      if (type !== 'series') return;
      epsTrack.appendChild(h('div.state', [U.spinner()]));
      Lib.episodes(x).then(function (r) {
        episodes = r.episodes || [];
        var info = r.info || {};
        if (info.plot && !x.plot) plot.textContent = info.plot;
        if (info.genre) metaText([info.genre]);
        loadDetails(r.info);
        scheduleTrailer();
        if (!episodes.length) { U.clear(epsTrack); epsTrack.appendChild(h('div.state.dim', L('No episodes found'))); return; }
        currentEp = pickCurrent();
        season = currentEp ? currentEp.season : seasons()[0];
        drawSeasons();
        var card = drawEpisodes(currentEp);
        drawProgress();
        lookupTrailer(season);
        drawButtons();
        // automatisch naar de huidige aflevering
        if (card) U.reveal(card);
        var play = buttons.querySelector('.f');
        if (play && U.top() && U.top().el === el) U.focus(play);
      });
    }
    function loadMovieInfo() {
      if (type !== 'movie') return;
      Lib.movieInfo(x).then(function (info) {
        loadDetails(info);
        if (!info) return;
        if (info.plot && !x.plot) plot.textContent = info.plot;
        var extra = [];
        if (info.duration) extra.push(info.duration);
        if (info.genre) extra.push(info.genre);
        metaText(extra);
      });
    }
    /**
     * build 102: beschrijving in de app-taal (TMDB), genres/regisseur/speelduur, Acteurs (ronde foto's; OK = pagina van
     * de acteur) en Aanbevelingen (in je bibliotheek = de titel, anders Zoeken). Eén keer per pagina; nooit blokkerend.
     */
    var detailsLoaded = false;
    function loadDetails(info) {
      if (detailsLoaded || !B.details) return;
      detailsLoaded = true;
      B.details.load(x, isShow, info).then(function (d) {
        if (!alive || !d) return;
        if (d.overview) plot.textContent = d.overview;
        metaText(d.runtime ? [d.runtime] : []);
        U.clear(creditsEl);
        U.append(creditsEl, B.details.credits(d));
        U.clear(extraEl);
        B.details.sections(d, extraEl);
        if (!P) return;
        // build 102: beoordelingen, achtergronden die in elkaar overlopen (als er geen trailer speelt) en Andere bronnen
        B.details.ratings(x, isShow, d).then(function (list) {
          if (!alive) return;
          U.clear(ratingsEl);
          var blk = B.details.ratingsBlock(list);
          if (blk) ratingsEl.appendChild(blk);
        });
        if (d.backdrops && d.backdrops.length && !kb) kb = P.kenBurns(bg, d.backdrops);
      }, B.noop);
      if (P) P.alternatives(type, x).then(function (list) {
        if (!alive || !list.length) return;
        U.clear(altEl);
        var r = rowBlock(L('Other sources'), list.map(P.otherCard), 'dt-alts');
        if (r) altEl.appendChild(r);
      }, B.noop);
    }
    drawButtons();
    var s = {
      el: el,
      onShow: function (back) {
        if (back) {
          drawButtons();
          metaText();
          if (type === 'series' && episodes.length) {
            var keep = currentEp; currentEp = pickCurrent() || keep;
            season = currentEp ? currentEp.season : season;
            drawSeasons(); var c = drawEpisodes(currentEp);
            drawProgress();
            if (c) U.focus(c);
          }
        }
      },
      onHide: function () { stopTrailer(); },
      onBack: function () { U.pop(); return true; },
      destroy: function () { alive = false; stopTrailer(); if (kb) kb.stop(); document.removeEventListener('visibilitychange', onTrailerVisibility); U.forgetImgs(el); }
    };
    loadEpisodes();
    loadMovieInfo();
    if (type === 'movie') { scheduleTrailer(); lookupTrailer(null); }
    return s;
  };

  // MARK: Instellingen

  /**
   * Build 146: Instellingen in tabs (op alle apps gelijk): Algemeen, Afspelen, Taal en ondertitels, Servers, Profielen en
   * kinderslot, Account, Koppelingen, Support, Diagnose, Over. Bovenaan een rij tabs (links/rechts, OK kiest; omlaag naar de
   * instellingen). Niets is verdwenen: elke instelling staat in de meest logische tab, met dezelfde regels als voorheen.
   */
  S.SETTINGS_TABS = [['general', 'General'], ['playback', 'Playback'], ['language', 'Language and subtitles'], ['servers', 'Servers'],
    ['profiles', 'Profiles and parental lock'], ['account', 'Account'], ['connections', 'Connections'], ['support', 'Support'],
    ['diagnose', 'Diagnostics'], ['about', 'About']];
  S.settingsTab = 'general';
  S.PRIVACY_URL = 'https://bingr.online/privacy.html';
  S.TERMS_URL = 'https://bingr.online/voorwaarden/';
  /** Pagina van bingr.online als QR-code (de tv opent geen browser). */
  S.qrLink = function (title, url) {
    var qr = h('div.qr-box.small');
    var box = h('div.support-cols', [qr, h('div', [
      h('p.modal-text', L("Scan the QR code with your phone's camera to open this page.")),
      h('div.support-url', url.replace(/^https?:\/\//, ''))
    ])]);
    var s = U.modal({ title: title, content: box, buttons: [{ label: L('Close') }] });
    S.drawQR(qr, url, 240);
    return s;
  };
  /** Instellingen openen op een tab (profielmenu, badge, melding). */
  S.openSettings = function (tab) {
    S.settingsTab = tab || 'general';
    var top = U.top();
    if (S.goPage && top && top.show) S.goPage('settings');
    else B.emit('openSettings', tab);
    B.emit('settingsTab');
  };

  S.pages.settings = function () {
    var list = vScroller('.st-list.fm', { 'data-pad': '120' });
    var el = h('div.page.settings', [h('div.ct-head', [h('h1', L('Settings'))]), list]);
    var pg = { el: el };
    function section(t) { list.track.appendChild(h('div.st-section', t)); }
    function row(label, value, fn, opts) {
      var r = h('div.st-row' + (fn ? '.f' : '.info'), [h('div.st-label', label), value != null ? h('div.st-value', value) : null]);
      r.onEnter = fn;
      if (opts && opts.danger) r.classList.add('danger');
      list.track.appendChild(r);
      return r;
    }
    function note(text) {
      var n = h('div.st-note.f', text);
      n.onEnter = B.noop;
      list.track.appendChild(n);
      return n;
    }
    function onOff(v) { return v ? L('On') : L('Off'); }
    function tabs() {
      var signedIn = A.isSignedIn();
      return S.SETTINGS_TABS.filter(function (t) { return t[0] !== 'account' || signedIn; });
    }
    var tabRow = null;
    function drawTabs(cur) {
      tabRow = h('div.st-tabs');
      tabs().forEach(function (t) {
        var badge = t[0] === 'support' ? X146.support.badge(B.support.unread()) : null;
        var tb = h('div.st-tab.f' + (t[0] === cur ? '.on' : ''), [h('span', L(t[1])), badge ? h('span.badge', badge) : null]);
        tb._tab = t[0];
        tb.onEnter = function () { if (S.settingsTab !== t[0]) { S.settingsTab = t[0]; render(true); } };
        tabRow.appendChild(tb);
      });
      list.track.appendChild(tabRow);
    }
    var X146 = B.b146;
    function render(tabChanged) {
      var f = U.focused, fLabel = f && list.contains(f) && f.firstChild ? f.firstChild.textContent : null;
      var onTab = !!(f && f._tab);
      U.clear(list.track);
      var prof = B.app.profile;
      var main = D.isMain(prof);
      var cur = S.settingsTab;
      if (!tabs().some(function (t) { return t[0] === cur; })) cur = S.settingsTab = 'general';
      drawTabs(cur);
      (TABS[cur] || TABS.general)(prof, main);
      if (tabChanged || onTab) {
        var tb = tabRow.querySelector('.st-tab.on');
        if (tb) U.focus(tb, true);
      } else if (fLabel) focusLabel(fLabel);
    }
    var TABS = {};
    TABS.general = function (prof) {
      section(L('Language'));
      var lang = D.shared('appLanguage', '') || '';
      var langLabel = '';
      B.languages.forEach(function (l) { if (l[0] === lang) langLabel = l[0] ? l[1] : L('Automatic'); });
      row(L('App language'), langLabel, function () {
        U.choose(L('Language'), B.languages.map(function (l) { return { value: l[0], label: l[0] ? l[1] : L('Automatic') }; }), lang, function (v) {
          D.setShared('appLanguage', v);
          B.setLanguage(v);
          B.app.rebuild();
        });
      });
      section(L('Interface'));
      B.app.interfaceSettingRows(row, onOff, render, { noLoad: true }); // build 102: startscherm en tabbladen
      section(L('Appearance'));
      var accent = D.accentKey(prof);
      // build 88: accentkleuren horen bij Lifetime
      if (!A.allowed('themes')) row(L('Accent color'), h('span', [L('Red'), S.lifetimeBadge()]), function () { S.upgrade('themes'); });
      else row(L('Accent color'), L(accent.charAt(0).toUpperCase() + accent.slice(1)), function () {
        U.choose(L('Accent color'), Object.keys(D.accents).map(function (k) { return { value: k, label: L(k.charAt(0).toUpperCase() + k.slice(1)) }; }), accent, function (v) {
          D.setAccent(prof.id, v).then(function () { B.app.applyAccent(); render(); });
          B.app.applyAccent(v); render();
        });
      });
      section(L('Reminders'));
      var rem = B.reminders.list();
      row(L('Reminders'), String(rem.length), function () { U.push(S.remindersList()); });
    };
    TABS.playback = function () {
      section(L('Player'));
      var resume = B.bool(D.shared('resumeLastChannel', false)) === true;
      row(L('Resume last channel'), onOff(resume), function () { D.setShared('resumeLastChannel', !resume); render(); });
      var lpv = D.local('livePreview', true) !== false;
      row(L('Live preview'), onOff(lpv), function () { D.setLocal('livePreview', !lpv); if (lpv) B.preview.stop(); render(); });
      var zd = D.local('zapDelay', 600);
      row(L('Zap delay'), (zd / 1000) + ' s', function () {
        U.choose(L('Zap delay'), [0, 300, 600, 1000, 1500].map(function (v) { return { value: v, label: (v / 1000) + ' s' }; }), zd, function (v) { D.setLocal('zapDelay', v); render(); });
      });
      // build 82: trailer zonder geluid in de kop van film- en seriepagina's (alleen op deze tv)
      section(L('Trailers'));
      var autoTr = D.local('autoplayTrailers', true) !== false;
      row(L('Play trailers automatically'), onOff(autoTr), function () { D.setLocal('autoplayTrailers', !autoTr); render(); });
    };
    TABS.language = function (prof) {
      // audio en ondertitels (settings.shared: audioLanguage, autoSubtitles, subtitleLanguage; gelijk met iOS en Android)
      section(L('Audio and subtitles'));
      var CLg = B.clang;
      var langOpts = ['nl', 'en', 'de', 'fr', 'es', 'it', 'pt', 'pl', 'tr', 'ar', 'hi'];
      // kijkvoorkeuren: de keuze geldt voor dit profiel (prefs); build 91: alleen het hoofdprofiel (of een account zonder
      // profielen) past ook die van het account aan (settings.shared), anders krijgen andere profielen deze keuze mee
      function pv(key, sharedKey) { var v = D.profilePref(key); return v != null ? v : String(D.shared(sharedKey, '') || ''); }
      var viewingMain = !prof || D.isMain(prof) || !D.profiles.length;
      function setViewing(sharedKey, prefKey, v) {
        if (viewingMain) D.setShared(sharedKey, v);
        D.setProfilePref(prefKey, v);
        render();
      }
      var aud = pv('audioLanguage', 'audioLanguage');
      function audLabel(v) { return v === 'stream' ? L('As in the stream') : (v ? CLg.name(v) : L('Same as app') + ' (' + CLg.name(B.lang) + ')'); }
      row(L('Default audio language'), audLabel(aud), function () {
        U.choose(L('Default audio language'), [{ value: '', label: audLabel('') }, { value: 'stream', label: L('As in the stream') }]
          .concat(langOpts.map(function (c) { return { value: c, label: CLg.name(c) }; })), aud, function (v) { setViewing('audioLanguage', 'audioLanguage', v); });
      });
      var subRaw = pv('streamSubtitleLanguage', 'subtitleLanguage');
      var subCur = subRaw === 'off' ? '' : CLg.norm(subRaw);
      var subAuto = L('Same as app') + ' (' + CLg.name(CLg.appLanguage()) + ')';
      // build 91: zelfde naam als op Android ("Standaard ondertiteltaal" = taal van ondertitels in de stream)
      row(L('Default subtitle language'), subCur ? CLg.name(subCur) : subAuto, function () {
        U.choose(L('Default subtitle language'), [{ value: '', label: subAuto }].concat(langOpts.map(function (c) { return { value: c, label: CLg.name(c) }; })), subCur, function (v) {
          setViewing('subtitleLanguage', 'streamSubtitleLanguage', v);
        });
      });
      B.app.subtitleSettingRows(row, render); // build 102: ondertitelgrootte
      var autoS = CLg.autoSubtitles();
      var autoLabels = { off: L('Off'), whenNeeded: L('When needed'), always: L('Always') };
      row(L('Automatic subtitles'), autoLabels[autoS], function () {
        U.choose(L('Automatic subtitles'), ['whenNeeded', 'always', 'off'].map(function (v) { return { value: v, label: autoLabels[v] }; }), autoS, function (v) {
          setViewing('autoSubtitles', 'autoSubtitles', v);
        });
      });
      section('OpenSubtitles');
      var OS = B.opensubtitles;
      // build 91: eigen naam (niet te verwarren met de ondertiteltaal in de stream); ISO 639-1, ook een taal die hier niet in de lijst staat
      var osl = OS.normLanguage(OS.defaultLanguage());
      var oslabel = '';
      OS.languages.forEach(function (l) { if (l[0] === osl) oslabel = l[1]; });
      if (!oslabel && osl) oslabel = CLg.name(osl) || osl;
      row(L('OpenSubtitles language'), oslabel || L('All'), function () {
        U.choose(L('OpenSubtitles language'), [{ value: '', label: L('All') }].concat(OS.languages.map(function (l) { return { value: l[0], label: l[1] }; })), osl, function (v) {
          setViewing('openSubtitlesLanguage', 'subtitleLanguage', v);
        });
      });
      row('OpenSubtitles', OS.isLoggedIn() ? OS.username() : L('Off'), function () { S.settingsTab = 'connections'; render(true); });
    };
    TABS.servers = function () {
      section(L('Your servers'));
      var src = Lib.source;
      row(L('Active server'), src ? src.name : L('None'), function () {
        var opts = D.sources.map(function (s) { return { value: s.id, label: s.name + '  ·  ' + s.kind }; });
        if (!opts.length) { U.toast(L('No servers yet')); return; }
        U.choose(L('Active server'), opts, src ? src.id : null, function (id) {
          var s2 = D.sourceById(id);
          if (!s2) return;
          D.setActiveSource(id); Lib.select(s2); render();
        });
      });
      row(L('Your servers'), String(D.sources.length), function () { U.push(S.servers()); });
      // build 130: alle servers (eerst de actieve, dan de andere één voor één op de achtergrond)
      row(L('Refresh now'), null, function () { S.refreshServers(); });
      section(L('Update playlists'));
      // build 145: "Servers laden bij openen" (per tv); build 146: de tv-gids volgt dezelfde keuze
      B.app.loadOnOpenRow(row, render);
      var at = src && Lib.stamps ? Lib.stamps[src.id] : 0;
      row(L('TV guide'), at ? L('Updated %@', new Date(at).toLocaleString(B.lang || undefined)) : L('Never updated'), null);
      note(L('Bingr opens with your saved list. Choose whether it asks to update your servers, updates them right away, or only when you refresh.') + ' ' +
        L('The TV guide is updated together with your servers.'));
      // build 128: categorieën aan/uit; build 140: voor het hele account; kinderprofielen zien dit niet
      if (!(B.app.profile && B.app.profile.kids)) {
        section(L('Categories and channels'));
        row(L('Categories on/off (NL, BE, DE…)'), null, function () { U.push(S.categoryChoices()); });
      }
    };
    TABS.profiles = function (prof, main) {
      section(L('Profile'));
      row(L('Switch profile'), prof.name, function () { B.app.switchProfile(); });
      // build 129: kijkgeschiedenis van dit profiel over alle servers (lokaal, = Android)
      row(L('Watch history'), String(D.history().length), function () { U.push(S.historyList()); });
      if (D.pinFor(prof)) {
        row(L('This profile is protected with a PIN'), null, null);
        // build 146: oude apps (≤ 145) kennen de hash niet
        note(L("Also update Bingr on your other devices, otherwise the PIN won't work there."));
      }
      section(L('Parental lock'));
      var pcOn = B.bool(D.shared('parentalControl', false)) === true;
      var mainPin = D.pinFor(D.mainProfile());
      if (main) {
        row(L('Parental lock'), onOff(pcOn), function () {
          var go = function () { D.setShared('parentalControl', !pcOn); setTimeout(function () { Lib.applyFilters(); render(); }, 50); };
          if (mainPin && pcOn) U.askPIN(L('Enter PIN'), function (x) { return B.b146.pin.check(x, mainPin); }, go); else go();
        });
      } else row(L('Parental lock'), onOff(pcOn), null);
      if (mainPin) {
        row(PC.isUnlocked() ? L('Lock now') : L('Unlock with PIN'), null, function () {
          if (PC.isUnlocked()) { PC.lock(); render(); return; }
          U.askPIN(L('Enter PIN'), function (x) { return B.b146.pin.check(x, mainPin); }, function () { PC.unlock(); render(); });
        });
      }
    };
    TABS.account = function (prof, main) {
      section(L('Account'));
      row(L('Signed in as %@', A.email() || ''), null, null);
      // build 88: abonnement (Gratis / Lifetime); statistieken, Nu aan het kijken en de geschiedenis horen bij Lifetime
      var ent = A.entitlements();
      if (ent) row(L('Subscription'), ent.free ? L('Free') : L('Lifetime'), function () { S.upgrade(null); });
      row(L('Devices'), null, function () { U.push(S.devices()); });
      function lock(f) { return A.allowed(f) ? null : S.lifetimeBadge(); }
      // kijkstatistieken (build 68): hoofdprofiel alle profielen, andere profielen alleen hun eigen
      row(L('Statistics'), lock('stats'), function () { S.gate('stats', function () { U.push(S.stats()); }); });
      if (main) {
        row(L('Watching now'), lock('now_watching'), function () { S.gate('now_watching', function () { U.push(S.streams()); }); });
        row(L('Watch history (7 days)'), lock('now_watching'), function () { S.gate('now_watching', function () { U.push(S.watchLog()); }); });
      }
      row(L('Sign out'), null, function () {
        U.modal({ title: L('Sign out'), text: L('Sign out of Bingr on this device?'), buttons: [
          { label: L('Sign out'), fn: function () { B.app.signOut(); } }, { label: L('Cancel') }] });
      }, { danger: true });
    };
    TABS.connections = function () {
      section(L('Connections'));
      var T = B.trakt, OS = B.opensubtitles;
      if (T.allowed()) {
        row('Trakt', T.isLoggedIn() ? (T.username() || L('On')) : (A.allowed('trakt') ? L('Off') : S.lifetimeBadge()), function () { S.traktMenu(render); });
      } else row('Trakt', L('The server, Trakt and OpenSubtitles belong to the main account and are used by all profiles.'), null);
      row('OpenSubtitles', OS.isLoggedIn() ? OS.username() : L('Off'), function () { S.openSubtitlesMenu(render); });
    };
    TABS.support = function () { S.supportRows(list.track, row, section, note, render); };
    TABS.diagnose = function () { S.diagnoseRows(list.track, row, section, note, render); };
    TABS.about = function () {
      section(L('About'));
      row('Bingr ' + B.config.version + ' (build ' + B.config.build + ')', 'Samsung TV · Tizen', null);
      row(L('Privacy'), 'bingr.online/privacy', function () { S.qrLink(L('Privacy'), S.PRIVACY_URL); });
      row(L('Terms of use'), 'bingr.online/voorwaarden', function () { S.qrLink(L('Terms of use'), S.TERMS_URL); });
      // build 75: hulp nodig? → support.bingr.online (QR-code)
      row(L('Support'), S.SUPPORT_URL.replace(/^https?:\/\//, ''), function () { S.support(); });
      // build 112: verplichte bronvermelding van TMDB en JustWatch; focusbaar, zodat de afstandsbediening tot onderaan kan
      if (B.discover) note(B.discover.attribution());
    };
    function focusLabel(t) {
      var rows = list.track.querySelectorAll('.st-row.f');
      for (var i = 0; i < rows.length; i++) if (rows[i].firstChild.textContent === t) { U.focus(rows[i], true); return; }
    }
    pg.refresh = render;
    pg.onShow = function () { render(false); if (S.settingsTab === 'support') B.support.refresh(true); };
    pg.focusIn = function () {
      var tb = tabRow && tabRow.querySelector('.st-tab.on');
      if (tb) { U.focus(tb); return true; }
      return false;
    };
    function visibleRender() { if (el.parentNode && el.style.display !== 'none' && U.top() && U.top().el.contains(el)) render(); }
    var offConn = B.on('connections', visibleRender);
    // build 88: abonnement gewijzigd → sloten bijwerken
    var offEnt = B.on('entitlements', visibleRender);
    // build 146: supporttickets en badge, diagnose, tab van buitenaf gekozen
    var offSup = B.on('support', visibleRender);
    var offDiag = B.on('diag', visibleRender);
    var offTab = B.on('settingsTab', function () { if (el.parentNode && el.style.display !== 'none') render(true); });
    pg.destroy = function () { offConn(); offEnt(); offSup(); offDiag(); offTab(); };
    render();
    return pg;
  };

  /** Build 146: alle servers vernieuwen (profielmenu en Instellingen → Servers), met de bijwerk-indicator. */
  S.refreshServers = function () {
    if (!D.sources.length) return;
    if (Lib.refreshingAll) { U.toast(L('Updating…')); return; }
    U.toast(L('Updating…'));
    Lib.refreshAll(function (text, done) { if (text) U.toast(text, done ? 5000 : 3000); });
  };

  /** Lijstscherm met laden (apparaten, nu kijken, geschiedenis, herinneringen, servers). */
  function listScreen(title, load, drawRow, empty, extra) {
    var list = vScroller('.st-list.fm', { 'data-pad': '120' });
    var head = h('div.ls-head', [h('h1', title)]);
    var el = layer('listscreen', [head, list]);
    /** quiet: op de achtergrond verversen (geen spinner, focus blijft op dezelfde regel). */
    function refresh(quiet) {
      var keepIdx = -1;
      if (quiet === true) {
        var rows = list.track.querySelectorAll('.f');
        for (var i = 0; i < rows.length; i++) if (rows[i] === U.focused) keepIdx = i;
      } else {
        U.clear(list.track);
        U.scrollTo(list, 0);
        list.track.appendChild(h('div.state', [U.spinner()]));
      }
      load().then(function (r) {
        U.clear(list.track);
        var sub = head.querySelector('.ls-sub');
        if (sub) head.removeChild(sub);
        if (r.header) head.appendChild(h('div.dim.ls-sub', r.header));
        if (extra) extra(list.track, refresh);
        if (!r.items.length && empty) list.track.appendChild(h('div.state.dim', empty));
        r.items.forEach(function (it) { list.track.appendChild(drawRow(it, refresh)); });
        // alleen de focus zetten als dit scherm nog bovenaan staat (anders belandt de focus in een verborgen laag)
        if (U.top() !== s) return;
        var all = list.track.querySelectorAll('.f');
        if (keepIdx >= 0 && all.length) U.focus(all[Math.min(keepIdx, all.length - 1)], true);
        else U.focusFirst(list);
        if (!U.focused || !el.contains(U.focused)) U.focusFirst(el);
      }, function (e) {
        if (quiet === true) return;
        U.clear(list.track); list.track.appendChild(h('div.state.dim', e.message || L('Something went wrong. Please try again.')));
        // build 88: hoort bij Lifetime (403 feature_locked) → knop naar het upgradevenster
        if (e && e.locked) {
          var up = U.button(L('Upgrade to Lifetime'), function () { S.upgrade(e.feature); }, { icon: 'lifetime', primary: true });
          list.track.appendChild(h('div.state', [up]));
          if (U.top() === s) U.focus(up, true);
        }
      });
    }
    var s = { el: el, refresh: refresh, onShow: function (back) { if (!back) refresh(); }, onBack: function () { U.pop(); return true; } };
    return s;
  }
  S.listScreen = listScreen;
  function relTime(ms) {
    if (!ms) return '';
    var d = (Date.now() - ms) / 1000;
    if (d < 120) return L('just now');
    if (d < 3600) return L('%d min ago', Math.round(d / 60));
    if (d < 86400) return B.fmtTime(ms);
    if (d < 2 * 86400) return L('yesterday');
    return new Date(ms).toLocaleDateString(B.lang || undefined); // build 97: in de taal van de app
  }
  S.devices = function () {
    return listScreen(L('Devices'), function () {
      return A.listDevices().then(function (r) { return { items: r.devices, header: L('%d of %d devices', r.devices.length, r.limit) }; });
    }, function (d, refresh) {
      var sub = [A.platformLabel(d.platform)];
      if (d.createdAt) sub.push(L('Linked %@', new Date(d.createdAt).toLocaleDateString(B.lang, { day: 'numeric', month: 'short', year: 'numeric' })));
      sub.push(L('Last used %@', d.current ? L('Now') : relTime(d.lastSeenAt)));
      var r = h('div.st-row.f.dev', [U.icon(platformIcon(d.platform)), h('div.st-label', [h('div', d.name + (d.current ? '  ·  ' + L('This device') : '')),
        h('div.dim', sub.join('  ·  '))])]);
      r.onEnter = function () {
        U.modal({ title: d.name, buttons: [
          { label: L('Rename'), icon: 'flag', fn: function () {
            U.editText({ title: L('Device name'), value: d.name, layout: 'text' }, function (v) {
              if (!String(v || '').trim()) return;
              A.renameDevice(d.id, v).then(refresh, function (e) { U.toast(e.message); });
            });
          } },
          { label: L('Remove'), fn: function () {
            U.modal({ title: d.name, text: L('Remove %@? It will be signed out.', d.name), buttons: [
              { label: L('Remove'), fn: function () {
                if (d.current) { B.app.signOut(); return; }
                A.removeDevice(d.id).then(refresh, function (e) { U.toast(e.message); });
              } }, { label: L('Cancel') }] });
          } },
          { label: L('Cancel') }] });
      };
      return r;
    }, '');
  };
  S.streams = function () {
    var scr = listScreen(L('Watching now'), function () {
      return A.listStreams().then(function (r) { return { items: r.streams, header: L('%d of %d watching now', r.streams.length, r.limit) }; });
    }, function (s) {
      var mins = s.startedAt ? Math.round((Date.now() - s.startedAt) / 60000) : 0;
      return h('div.st-row.f', [U.icon(platformIcon(s.platform)), S.rowAvatar(s.profileId, s.profileName), h('div.st-label', [h('div', s.title), h('div.dim', A.deviceNameWithLabel(s.deviceName, s.platform) + '  ·  ' + s.profileName + '  ·  ' + mins + ' min')])]);
    }, L('Nobody is watching right now.'));
    // ververst elke 30 s zolang het scherm bovenaan staat (ACCOUNT.md, build 51)
    var timer = setInterval(function () { if (U.top() === scr) scr.refresh(true); }, 30000);
    scr.destroy = function () { clearInterval(timer); };
    return scr;
  };
  S.watchLog = function () {
    return listScreen(L('Watch history (7 days)'), function () {
      return A.watchLog(7).then(function (e) { return { items: e }; });
    }, function (e) {
      var d = new Date(e.startedAt);
      return h('div.st-row.f', [h('div.wl-time', d.toLocaleDateString(B.lang, { weekday: 'short', day: 'numeric', month: 'short' }) + '  ' + B.fmtTime(e.startedAt)),
        S.rowAvatar(e.profileId, e.profileName), h('div.st-label', [h('div', e.title), h('div.dim', e.profileName + '  ·  ' + A.deviceNameWithLabel(e.deviceName, e.platform) + '  ·  ' + e.minutes + ' min')]),
        U.icon(platformIcon(e.platform), 'trail')]);
    }, L('No history yet'));
  };
  S.relTime = relTime;

  // MARK: build 128: categorieën aan/uit per profiel (alle servers; dezelfde naam op twee servers = één regel)

  var catPrefix = /^\s*[|\[({]?\s*([A-Z]{2,4}|[A-Z]{2}\+)\s*(?:[|\])}:\-–•]|\s+\|)/;
  var catSkip = { THE: 1, AND: 1, FOR: 1, ALL: 1, NEW: 1, TOP: 1, VOD: 1, TV: 1 };
  function catCode(g) { var m = catPrefix.exec(g); var c = m ? m[1].toUpperCase() : null; return c && !catSkip[c] ? c : null; }
  /**
   * Categorienamen van alle servers (actieve eerst, de rest uit de cache op deze tv): [{name, sids}].
   * Build 140: bewaard zolang de caches niet veranderen (niet bij elke tik alle bibliotheken opnieuw uit IndexedDB).
   */
  var groupMemo = {};
  function allGroups(kind) {
    var sig = kind + '|' + (Lib.source ? Lib.source.id : '') + '|' + Lib.version + '|' +
      D.sources.map(function (s) { return s.id + ':' + (Lib.stamps[s.id] || 0); }).join(',');
    if (groupMemo[kind] && groupMemo[kind].sig === sig) return Promise.resolve(groupMemo[kind].list);
    return loadGroups(kind).then(function (list) {
      var sig2 = kind + '|' + (Lib.source ? Lib.source.id : '') + '|' + Lib.version + '|' +
        D.sources.map(function (s) { return s.id + ':' + (Lib.stamps[s.id] || 0); }).join(',');
      groupMemo[kind] = { sig: sig2, list: list };
      return list;
    });
  }
  function loadGroups(kind) {
    var field = kind === 'live' ? 'channels' : kind === 'movies' ? 'movies' : 'series';
    var cur = Lib.source ? Lib.source.id : null, byName = {}, out = [];
    var list = D.sources.slice().sort(function (a, b) { return (a.id === cur ? 0 : 1) - (b.id === cur ? 0 : 1); });
    var chain = Promise.resolve();
    list.forEach(function (src) {
      chain = chain.then(function () {
        var p = src.id === cur ? Promise.resolve(Lib.raw) : B.idb.get('lib:' + src.id).then(function (c) {
          if (c && Lib.stamps) Lib.stamps[src.id] = c.at || 0;
          return c && c.lib;
        });
        return p.then(function (raw) {
          var items = (raw && raw[field]) || [], seen = {};
          for (var i = 0; i < items.length; i++) {
            var g = items[i].group || '';
            if (seen[g]) continue;
            seen[g] = true;
            if (!byName[g]) { byName[g] = { name: g, sids: [] }; out.push(byName[g]); }
            byName[g].sids.push(src.id);
          }
        });
      });
    });
    return chain.then(function () { return out; });
  }
  S.categoryChoices = function () {
    var kind = 'live', groups = [];
    function keys(gs) { var out = []; gs.forEach(function (g) { g.sids.forEach(function (sid) { out.push(D.key(sid, g.name)); }); }); return out; }
    // build 140: account-set via een index per server (geen kopie per regel); één regel aan/uit tekent alleen die regel
    // opnieuw, de tellers per land daarna als de afstandsbediening even stil is
    function isOn(g) { return g.sids.every(function (sid) { var x = D.accountHiddenIndex()[String(sid).toLowerCase()]; return !(x && x[g.name]); }); }
    var later = null;
    function set(gs, on, refresh, quiet) {
      D.setCategoriesHidden(keys(gs), !on);
      if (!quiet) { refresh(true); return; }
      clearTimeout(later);
      later = setTimeout(function () { B.whenQuiet(function () { if (U.top() === scr) refresh(true); }, 1500); }, 800);
    }
    function serverNames(g) {
      if (D.sources.length < 2) return '';
      return g.sids.map(function (id) { var s = D.sourceById(id); return s ? s.name : ''; }).filter(Boolean).join(', ');
    }
    var scr = listScreen(L('Categories'), function () {
      return allGroups(kind).then(function (gs) { groups = gs; return { items: gs, header: L('Categories (%d)', gs.length) }; });
    }, function (g, refresh) {
      var sub = serverNames(g);
      var val = h('div.st-value', isOn(g) ? L('On') : L('Off'));
      var r = h('div.st-row.f', [h('div.st-label', [h('div', g.name || '—'), sub ? h('div.dim', sub) : null]), val]);
      r.onEnter = function () { set([g], !isOn(g), refresh, true); val.textContent = isOn(g) ? L('On') : L('Off'); };
      return r;
    }, L('No categories'), function (track, refresh) {
      var kinds = h('div.cat-kinds.fm', [['live', L('Live TV')], ['movies', L('Movies')], ['series', L('Series')]].map(function (k) {
        var b = U.button(k[1], function () { if (kind !== k[0]) { kind = k[0]; refresh(); } }, { primary: kind === k[0] });
        return b;
      }));
      track.appendChild(kinds);
      var codes = {}, order = [];
      groups.forEach(function (g) { var c = catCode(g.name); if (!c) return; if (!codes[c]) { codes[c] = []; order.push(c); } codes[c].push(g); });
      order.sort(function (a, b) { return (codes[b].length - codes[a].length) || (a < b ? -1 : 1); });
      if (order.length) {
        track.appendChild(h('div.st-section', L('Countries & languages')));
        track.appendChild(h('div.cat-codes.fm', order.slice(0, 24).map(function (c) {
          var on = codes[c].filter(isOn).length, all = on === codes[c].length;
          return U.button(c + ' ' + on + '/' + codes[c].length, function () { set(codes[c], !all, refresh); }, { primary: all });
        })));
        track.appendChild(h('div.dim.st-note-small', L('Tap a code to turn all its categories on or off at once.')));
      }
      track.appendChild(h('div.cat-codes.fm', [
        U.button(L('All on'), function () { set(groups, true, refresh); }),
        U.button(L('All off'), function () { set(groups, false, refresh); })
      ]));
      track.appendChild(h('div.dim.st-note-small', L('Categories that are off don\'t appear anywhere in the app. Saved for your whole account (all profiles except kids profiles), for all your servers.')));
    });
    return scr;
  };
})();
