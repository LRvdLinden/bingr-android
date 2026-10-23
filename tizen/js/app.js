/*
 * Opstarten en levenscyclus: splash → (geen sessie) inloggen → apparaat registreren → "Wie kijkt er?" → app.
 * Heartbeat bij starten, bij terugkeer naar de voorgrond en elke 5 minuten (zoals Account.deviceCheck).
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui, D = B.data, A = B.account, Lib = B.lib, S = B.screens;
  var L = function () { return B.L.apply(null, arguments); };
  var app = B.app = { profile: null };

  // Accentkleur goud (nieuw; past bij het gouden app-icoon). D.accents is dezelfde lijst als ACCENTS in sync.js
  // (accentKey, setAccent en de migratie kennen hem daardoor ook). Oudere apps kennen "gold" niet en tekenen rood.
  if (!D.accents.gold) D.accents.gold = '#F5B30B';
  // tekst/iconen óp een vlak in de accentkleur: wit, maar donker op een licht accent (wit op goud is onleesbaar)
  var ON_ACCENT = { gold: '#1A1400' };
  function setAccentVars(k) {
    var root = document.documentElement.style;
    root.setProperty('--accent', D.accents[k] || D.accents.red);
    root.setProperty('--on-accent', D.accents[k] && ON_ACCENT[k] ? ON_ACCENT[k] : '#fff');
  }

  function withTimeout(p, ms) {
    return Promise.race([p, new Promise(function (r) { setTimeout(function () { r(null); }, ms); })]);
  }

  var exiting = false;
  app.exit = function () {
    if (exiting) return;
    exiting = true;
    // speler netjes sluiten (AVPlay vrijgeven, stream_stop, voortgang) en openstaande wijzigingen versturen
    try { if (B.player.current) B.player.current.close(); if (B.multiview.current) B.multiview.current.close(); B.preview.stop(); } catch (e) { /* stil */ }
    // build 97: wachten op het versturen (voortgang, stream_stop), maar hooguit ~1,5 s
    var work = Promise.all([
      Promise.resolve().then(function () { return D.flush(); }).catch(B.noop),
      Promise.resolve().then(function () { return A.lastStreamStop ? A.lastStreamStop() : null; }).catch(B.noop)
    ]);
    withTimeout(work, 1500).then(quit, quit);
    function quit() { try { tizen.application.getCurrentApplication().exit(); } catch (e) { B.log('exit'); exiting = false; } }
  };
  var exitArmed = 0;
  app.confirmExit = function () {
    if (Date.now() - exitArmed < 2500) { app.exit(); return; }
    exitArmed = Date.now();
    U.toast(L('Press Back again to close Bingr'), 2500);
  };

  app.applyAccent = function (key) {
    var k = key || D.accentKey(app.profile);
    // build 88: accentkleuren horen bij Lifetime; met Gratis rood (de gekozen kleur blijft bewaard)
    if (!A.allowed('themes')) k = 'red';
    setAccentVars(k);
    B.store.set('lastAccent', k);
  };
  app.startPage = function () {
    var s = String(D.shared('startScreen', 'home') || 'home').toLowerCase();
    var hidden = D.shared('hiddenTabs', []) || [];
    if (['home', 'live', 'movies', 'series'].indexOf(s) < 0 || hidden.indexOf(s) >= 0) s = 'home';
    return s;
  };

  /** build 102: tabblad verborgen via settings.shared.hiddenTabs ("live", "movies", "series"; = iOS en Android). */
  app.hiddenTabs = function () {
    var l = D.shared('hiddenTabs', []);
    return l && l.length != null ? Array.prototype.map.call(l, function (x) { return String(x).toLowerCase(); }) : [];
  };
  app.tabHidden = function (key) { return app.hiddenTabs().indexOf(key) >= 0; };

  // MARK: intromuziek (build 102, = SplashTune op tvOS: audio/bingr_tune.m4a bij het starten)
  // Altijd aan: de instelling is vervallen; settings.shared.introMusic wordt niet meer gelezen of geschreven.

  function playIntro() {
    try {
      var a = new Audio('audio/bingr_tune.m4a');
      a.volume = 1;
      var p = a.play();
      if (p && p.catch) p.catch(function (e) { B.log('intro', e && e.name); });
      // na afloop vrijgeven
      a.onended = function () { a.removeAttribute('src'); try { a.load(); } catch (e) { /* stil */ } };
    } catch (e) { B.log('intro', e && e.message); }
  }

  // MARK: build 145: servers laden bij openen (geen vernieuwtermijn meer; B.b145.load, Lib.updateAll)

  /**
   * De vraag "Servers bijwerken?" (Lib.askUpdate, na het kiezen van een profiel met een bewaarde lijst en de stand
   * Vragen). Niet tijdens het afspelen: dan wacht hij tot de speler dicht is. De focus staat eerst op Later; Terug = Later.
   */
  var askTimer = null;
  function showAskUpdate() {
    clearTimeout(askTimer);
    if (!Lib.askUpdate || !app.profile) return;
    if (document.hidden || B.player.current || (B.multiview && B.multiview.current)) { askTimer = setTimeout(showAskUpdate, 2000); return; }
    var asked = app.profile;
    U.modal({
      title: L('Update servers?'),
      text: L('Your saved list is ready. Do you want to update your servers now?'),
      buttons: [
        { label: L('Update now'), fn: function () { if (app.profile === asked) Lib.answerUpdate(true); } },
        { label: L('Later'), fn: function () { Lib.answerUpdate(false); } }
      ],
      focus: 1,
      onCancel: function () { Lib.answerUpdate(false); }
    });
  }
  B.on('askUpdate', function () { setTimeout(showAskUpdate, 300); });

  // MARK: instellingen (build 102): rijen voor het instellingenscherm, gedeeld via settings.shared

  /** Ondertitelgrootte (settings.shared.subtitleSize), in de sectie Audio en ondertitels. */
  app.subtitleSettingRows = function (row, render) {
    var cur = B.player.subtitleSize();
    row(L('Subtitle size'), B.player.sizeLabel(cur), function () {
      U.choose(L('Subtitle size'), B.player.SIZES.map(function (z) { return { value: z, label: B.player.sizeLabel(z) }; }), cur, function (z) {
        D.setShared('subtitleSize', z); render();
      });
    });
  };
  /** Startscherm, tabbladen tonen/verbergen en playlists verversen (sectie Weergave). */
  app.interfaceSettingRows = function (row, onOff, render) {
    var tabs = [['home', 'Home'], ['live', 'Live TV'], ['movies', 'Movies'], ['series', 'Series']];
    var start = app.startPage();
    var startLabel = '';
    tabs.forEach(function (t) { if (t[0] === start) startLabel = L(t[1]); });
    row(L('Start screen'), startLabel, function () {
      U.choose(L('Start screen'), tabs.filter(function (t) { return t[0] === 'home' || !app.tabHidden(t[0]); }).map(function (t) { return { value: t[0], label: L(t[1]) }; }), start, function (v) {
        D.setShared('startScreen', v); render();
      });
    });
    [['live', 'Show Live TV'], ['movies', 'Show Movies'], ['series', 'Show Series']].forEach(function (t) {
      var shown = !app.tabHidden(t[0]);
      row(L(t[1]), onOff(shown), function () {
        var list = app.hiddenTabs().filter(function (x) { return x !== t[0]; });
        if (shown) list.push(t[0]);
        D.setShared('hiddenTabs', list);
        // menu opnieuw opbouwen (zelfde rij blijft gekozen)
        app.rebuild(L(t[1]));
      });
    });
    // build 145: "Servers laden bij openen" (per tv, niet gesynct) in plaats van de vernieuwtermijn (refreshInterval)
    if (!(app.profile && app.profile.kids)) {
      var P = B.b145.load, mode = P.mode();
      var modeLabels = { ask: L('Ask'), always: L('Always'), never: L('Never, manually only') };
      row(L('Load servers on open'), modeLabels[mode], function () {
        U.choose(L('Load servers on open'), P.MODES.map(function (k) { return { value: k, label: modeLabels[k] }; }), mode, function (v) {
          P.setMode(v);
          render();
        });
      });
    }
  };

  // profielfoto's: één keer per pad+versie ophalen (per pad alleen de nieuwste versie)
  // build 100: data:-URL's (niets vrij te geven); een mislukte poging wordt niet onthouden: de volgende keer dat de foto
  // nodig is (na minstens 20 s) opnieuw proberen. Vroeger bleef een fout bij het opstarten (netwerk van de tv nog niet
  // klaar, inlog die net vernieuwd werd) tot een herstart hangen: geen foto.
  var photos = {};
  function revoke() { /* data:-URL's: niets vrij te geven */ }
  app.photo = function (path, updated) {
    var k = String(updated || '');
    var cur = photos[path];
    if (cur && cur.k === k && (cur.ok !== false || Date.now() - cur.at < 20000)) return cur.p;
    var entry = { k: k, at: Date.now(), ok: null };
    entry.p = A.photoURL(path).then(function (u) { entry.ok = !!u; entry.at = Date.now(); return u; });
    photos[path] = entry;
    return entry.p;
  };
  /** build 97: foto's van profielen die er niet meer zijn (of geen foto meer hebben) vrijgeven. */
  function prunePhotos() {
    var used = {};
    D.profiles.forEach(function (p) { if (p && p.photo && p.photo.path) used[p.photo.path] = String(p.photo.updated || ''); });
    Object.keys(photos).forEach(function (path) {
      if (!B.has(used, path)) { revoke(photos[path].p); delete photos[path]; }
    });
  }

  // MARK: flow

  function start() {
    B.migrate();
    B.setLanguage(D.shared('appLanguage', '') || '');
    setAccentVars(B.store.get('lastAccent', 'red'));
    U.root = document.getElementById('app');
    // buiten de tv (test in een browser) bestaat AVPlay niet: het lege plugin-vak verbergen
    try { if (!(window.webapis && webapis.avplay)) document.getElementById('av-player').style.display = 'none'; } catch (e) { /* stil */ }
    U.registerKeys();
    document.addEventListener('keydown', U.onKeyDown, true);
    document.addEventListener('keyup', U.onKeyUp, true);
    document.addEventListener('visibilitychange', onVisibility);
    U.push(S.splash());
    // build 145: eenmalig de oude vernieuwtermijn omzetten naar "Servers laden bij openen: Vragen"
    B.b145.load.migrateNow();
    // build 141: eerst de kluis laden (sessie, servers, pincodes); daarna pas verder
    var vault = B.vault ? B.vault.ready : Promise.resolve();
    vault.then(afterVault, afterVault);
  }

  function afterVault() {
    if (A.isSignedIn()) playIntro();
    B.on('signedOut', function () { toLogin(); });
    B.on('sessionInvalid', function () { A.revokedSignOut(); });
    // build 97: geen vaste wachttijd meer voor het splashscherm: door zodra de app klaar is (na één frame)
    setTimeout(function () {
      if (A.isSignedIn()) {
        afterLogin(false);
        A.refresh().then(function (res) { if (res === 'invalid') toLogin(); });
      } else toLogin();
    }, 0);
  }

  var onLogin = false;
  function toLogin() {
    if (onLogin) return;
    onLogin = true;
    if (B.player.current) B.player.current.close();
    if (B.multiview.current) B.multiview.current.close();
    B.preview.stop();
    app.profile = null;
    U.reset(S.login(function () { onLogin = false; afterLogin(true); }));
  }

  /** Na inloggen of starten met een sessie: registreren, synchroniseren, profielen. */
  function afterLogin(fresh) {
    D.checkAccountSwitch();
    if (fresh) U.reset(S.splash());
    var reg = (fresh || !A.isRegistered()) ? A.registerDevice() : Promise.resolve('ok');
    reg.then(function (r) {
      if (r && r.limit) { showLimit(r); return; }
      A.fetchConfig().then(function (ok) { if (ok) B.emit('config'); });
      // abonnement (build 88): Gratis of Lifetime
      A.fetchEntitlements(true);
      var haveCache = D.profiles.length > 0;
      var sync = Promise.all([D.syncProfiles(), D.syncSources(), D.syncState()]).then(function (r) { D.migrateAccent(); return r; });
      // met cache meteen door; zonder cache maximaal 8 seconden wachten
      return withTimeout(sync, haveCache ? 50 : 8000).then(showProfiles);
    });
    startHeartbeat();
  }
  function showLimit(info) {
    var s = S.deviceLimit(info, function () {
      A.registerDevice().then(function (r) {
        if (r && r.limit) { U.pop(); showLimit(r); return; }
        afterLogin(false);
      });
    }, function () { app.signOut(); });
    U.reset(s);
  }

  function showProfiles() {
    app.profile = null;
    // één profiel zonder pincode: meteen door (zoals de andere apps na het eerste gebruik)
    U.reset(S.profiles(pickProfile));
    app.applyAccent(B.store.get('lastAccent', 'red'));
  }
  /**
   * build 97: speler sluiten en de voortgang van het huidige profiel versturen vóórdat een ander profiel geladen wordt
   * (hooguit ~4 s wachten; de upload loopt daarna door voor het oude profiel, zie D.loadProfileState).
   */
  function leaveProfile() {
    if (B.player.current) B.player.current.close();
    if (B.multiview.current) B.multiview.current.close();
    B.preview.stop();
    B.ageRatings.stop();
    return withTimeout(Promise.resolve().then(function () { return D.flush(); }).catch(B.noop), 4000);
  }
  var switching = false;
  app.switchProfile = function () {
    if (switching) return;
    switching = true;
    leaveProfile().then(function () { switching = false; showProfiles(); });
  };

  /** Meteen naar een ander profiel vanuit het profielmenu (build 72); de pincode is dan al gecontroleerd. */
  app.openProfile = function (p) {
    if (switching) return;
    switching = true;
    leaveProfile().then(function () { switching = false; pickProfile(p); });
  };

  function pickProfile(p) {
    // build 140: weer in een kinderprofiel → bij het verlaten opnieuw de rekensom
    if (p && p.kids && B.kidsGate) B.kidsGate.kidsOpened();
    app.profile = p;
    B.store.set('lastProfile', p.id);
    app.applyAccent();
    D.loadProfileState(p.id);
    PCrelock();
    var src = D.activeSource();
    var main = S.main();
    U.reset(main);
    // build 144: de zenders van de andere servers (reservebron) pas ±45 s na het kiezen van het profiel
    B.b144.auto.profileOpened();
    // build 145: na het laden van de open server de keuze van "Servers laden bij openen" (vraag, Altijd of niets)
    Lib.profileOpened();
    Lib.select(src).then(function () {
      maybeResumeChannel();
      // achtergrondwerk pas als de app rustig is: leeftijdsclassificaties
      B.whenQuiet(function () { warmAges(); }, 2500);
    });
    D.syncState().then(function () { if (app.profile === p) { Lib.applyFilters(); B.trakt.refresh(false); } });
    B.reminders.start();
  }

  /** TMDB-leeftijden bijwerken als dit profiel een leeftijdsgrens heeft (kindergroepen eerst). */
  function warmAges() {
    if (!app.profile || D.ageLimit(app.profile) == null) return;
    B.ageRatings.warm(Lib.raw);
  }
  // nieuwe classificaties: opnieuw filteren (alleen bij een leeftijdsgrens), als de afstandsbediening stil is
  // build 97: hooguit één keer per 10 s opnieuw filteren en tekenen
  var agePending = false, ageLast = 0;
  B.on('ageRatings', function () {
    if (!app.profile || D.ageLimit(app.profile) == null || agePending) return;
    agePending = true;
    setTimeout(function () {
      B.whenQuiet(function () {
        agePending = false;
        if (!app.profile || D.ageLimit(app.profile) == null) return;
        ageLast = Date.now();
        Lib.applyFilters();
      }, 1500);
    }, Math.max(0, 10000 - (Date.now() - ageLast)));
  });
  B.on('config', function () { if (app.profile) { B.trakt.refresh(false); warmAges(); } });
  function PCrelock() { if (B.parental.isUnlocked()) B.parental.lock(); }
  B.ageRatings.preload();

  /** Instelling "Laatste zender hervatten": na het kiezen van een profiel meteen die zender. */
  function maybeResumeChannel() {
    if (B.bool(D.shared('resumeLastChannel', false)) !== true || !app.profile) return;
    var last = B.store.get('lastChannel.' + String(app.profile.id).toLowerCase(), null);
    if (!last || !Lib.source || String(last.sourceId).toLowerCase() !== String(Lib.source.id).toLowerCase()) return;
    var hit = Lib.lib.byId && Lib.lib.byId[last.channelId];
    if (!hit || hit.type !== 'channel') return;
    var list = Lib.lib.channels.filter(function (c) { return c.group === hit.item.group; });
    B.player.playChannel(hit.item, list);
  }

  app.signOut = function () {
    if (B.player.current) B.player.current.close();
    if (B.multiview.current) B.multiview.current.close();
    D.flush().then(function () { return A.signOut(); });
  };

  /** Na een taalwissel de schermen opnieuw opbouwen. */
  /** focusLabel (build 102): na het opbouwen de focus op de instelling met deze naam. */
  app.rebuild = function (focusLabel) {
    if (!app.profile) { showProfiles(); return; }
    var main = S.main();
    U.reset(main);
    main.show('settings', true);
    if (focusLabel) {
      var rows = main.el.querySelectorAll('.st-row.f');
      for (var i = 0; i < rows.length; i++) if (rows[i].firstChild && rows[i].firstChild.textContent === focusLabel) { U.focus(rows[i], true); break; }
    }
  };

  // MARK: heartbeat en achtergrond

  var hbTimer = null, checking = false;
  function deviceCheck() {
    if (!A.isSignedIn() || checking || onLogin) return;
    checking = true;
    var p = A.isRegistered() ? A.heartbeat() : A.registerDevice().then(function () { return true; });
    p.then(function (alive) {
      if (alive === false) { A.revokedSignOut(); return; }
      return Promise.all([D.syncProfiles(), D.syncSources(), D.syncState()]).then(function () {
        // actief profiel verwijderd op een ander apparaat → terug naar "Wie kijkt er?"
        if (app.profile && !D.profileById(app.profile.id)) { showProfiles(); return; }
        if (app.profile) {
          var fresh = D.profileById(app.profile.id);
          var accentChanged = D.accentKey(fresh) !== D.accentKey(app.profile);
          app.profile = fresh;
          if (accentChanged) app.applyAccent();
          // server gewijzigd (andere hoofdserver of verwijderd)
          var src = D.activeSource();
          if (src && (!Lib.source || String(src.id).toLowerCase() !== String(Lib.source.id).toLowerCase())) Lib.select(src);
          else if (src) { Lib.source = src; Lib.applyFilters(); }
        }
      });
    }).catch(B.noop).then(function () { checking = false; });
  }
  function startHeartbeat() {
    clearInterval(hbTimer);
    hbTimer = setInterval(deviceCheck, 5 * 60000);
    setTimeout(deviceCheck, 4000);
  }
  function onVisibility() {
    if (document.hidden) {
      clearInterval(hbTimer);
      B.preview.stop();
      if (B.player.current) B.player.current.pauseForBackground();
      if (B.multiview.current) B.multiview.current.pauseForBackground();
      D.flush();
    } else {
      startHeartbeat();
      // build 88: abonnement bijwerken (na een upgrade op bingr.online gaan de sloten open)
      A.fetchEntitlements(false);
      // build 75: terug op de tv → Trakt-vinkjes bijwerken (alleen als Trakt iets nieuws meldt), zoals iOS/Android
      if (app.profile) B.trakt.refresh(false);
      // build 145: geen automatisch verversen meer bij terugkeer (de vernieuwtermijn is weg)
      if (B.player.current) B.player.current.resumeFromBackground();
      if (B.multiview.current) B.multiview.current.resumeFromBackground();
    }
  }

  // taal uit het account (settings.shared.appLanguage): bij een wijziging de schermen opnieuw opbouwen
  B.on('settings', function () {
    var before = B.lang;
    B.setLanguage(D.shared('appLanguage', '') || '');
    if (B.lang === before || B.player.current || B.multiview.current || onLogin) return;
    var top = U.top();
    if (!top || top.el.classList.contains('splash')) return;
    if (app.profile) { var main = S.main(); U.reset(main); }
    else showProfiles();
  });

  // build 97: teksten van een andere taal zijn later binnengekomen (pakket laadt één taal tegelijk): schermen opnieuw opbouwen
  B.on('strings', function () {
    if (B.player.current || B.multiview.current || onLogin) return;
    var top = U.top();
    if (!top || top.el.classList.contains('splash')) return;
    if (app.profile) app.rebuild();
    else showProfiles();
  });

  // build 88: abonnement gewijzigd → accentkleur opnieuw toepassen
  B.on('entitlements', function () { if (app.profile) app.applyAccent(); });

  B.on('profiles', function () {
    prunePhotos();
    if (app.profile && !D.profileById(app.profile.id)) showProfiles();
  });

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
