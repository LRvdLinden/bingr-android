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
    var c = D.accents[k] || D.accents.red;
    document.documentElement.style.setProperty('--accent', c);
    B.store.set('lastAccent', k);
  };
  app.startPage = function () {
    var s = String(D.shared('startScreen', 'home') || 'home').toLowerCase();
    var hidden = D.shared('hiddenTabs', []) || [];
    if (['home', 'live', 'movies', 'series'].indexOf(s) < 0 || hidden.indexOf(s) >= 0) s = 'home';
    return s;
  };

  // profielfoto's: één keer per pad+versie ophalen
  // build 97: per pad alleen de nieuwste versie; de object-URL van een vervangen of verwijderde foto vrijgeven
  var photos = {};
  function revoke(p) { p.then(function (u) { if (u) { try { URL.revokeObjectURL(u); } catch (e) { /* stil */ } } }, B.noop); }
  app.photo = function (path, updated) {
    var k = String(updated || '');
    var cur = photos[path];
    if (cur && cur.k === k) return cur.p;
    if (cur) revoke(cur.p);
    var p = A.photoURL(path);
    photos[path] = { k: k, p: p };
    return p;
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
    var accent = B.store.get('lastAccent', 'red');
    document.documentElement.style.setProperty('--accent', D.accents[accent] || D.accents.red);
    U.root = document.getElementById('app');
    // buiten de tv (test in een browser) bestaat AVPlay niet: het lege plugin-vak verbergen
    try { if (!(window.webapis && webapis.avplay)) document.getElementById('av-player').style.display = 'none'; } catch (e) { /* stil */ }
    U.registerKeys();
    document.addEventListener('keydown', U.onKeyDown, true);
    document.addEventListener('keyup', U.onKeyUp, true);
    document.addEventListener('visibilitychange', onVisibility);
    U.push(S.splash());
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
    app.profile = p;
    B.store.set('lastProfile', p.id);
    app.applyAccent();
    D.loadProfileState(p.id);
    PCrelock();
    var src = D.activeSource();
    var main = S.main();
    U.reset(main);
    Lib.select(src).then(function () {
      maybeResumeChannel();
      // achtergrondwerk pas als de app rustig is: reservebronnen en leeftijdsclassificaties
      B.whenQuiet(function () { Lib.warmOthers(); warmAges(); }, 2500);
    });
    D.syncState().then(function () { if (app.profile === p) { Lib.applyFilters(); B.trakt.refresh(false); } });
    B.reminders.start();
    setTimeout(checkAnnouncements, 1500);
  }

  // MARK: mededelingen van de beheerder (build 62)
  // Na de profielkeuze en bij terugkeer naar de voorgrond (hooguit één keer per uur); elke mededeling één keer.
  // Gelezen ids lokaal (max. 200); fouten stil negeren.

  var annLast = 0, annBusy = false, annQueue = [], annOpen = false;
  function annSeen() { var l = B.store.get('announcements.seen', []); return l && l.length != null ? l : []; }
  function annMarkSeen(id) {
    var l = annSeen().filter(function (x) { return x !== id; });
    l.push(id);
    if (l.length > 200) l = l.slice(l.length - 200);
    B.store.set('announcements.seen', l);
  }
  function checkAnnouncements() {
    if (annBusy || !A.isSignedIn() || onLogin || !app.profile) return;
    var now = Date.now();
    if (annLast && now - annLast < 3600000) { showAnnouncement(); return; }
    annLast = now;
    annBusy = true;
    A.announcements(B.lang || 'en').then(function (items) {
      var seen = annSeen();
      items.sort(function (a, b) { return a.createdAt - b.createdAt; }).forEach(function (a) {
        if (seen.indexOf(a.id) >= 0) return;
        if (annQueue.some(function (q) { return q.id === a.id; })) return;
        annQueue.push(a);
      });
    }).catch(B.noop).then(function () { annBusy = false; showAnnouncement(); });
  }
  /** Eén tegelijk; niet over de speler of multiview heen (dan bij de volgende controle). OK of Terug sluit. */
  function showAnnouncement() {
    if (annOpen || !annQueue.length || !app.profile || onLogin || B.player.current || B.multiview.current) return;
    var a = annQueue[0];
    annOpen = true;
    function done() { annOpen = false; annMarkSeen(a.id); annQueue.shift(); showAnnouncement(); }
    U.modal({ title: a.title || null, text: a.body || null, buttons: [{ label: L('OK'), fn: done }], onCancel: done });
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
  app.rebuild = function () {
    if (!app.profile) { showProfiles(); return; }
    var main = S.main();
    U.reset(main);
    main.show('settings', true);
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
    }).catch(B.noop).then(function () { checking = false; showAnnouncement(); });
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
      setTimeout(checkAnnouncements, 2000);
      // build 88: abonnement bijwerken (na een upgrade op bingr.online gaan de sloten open)
      A.fetchEntitlements(false);
      // build 75: terug op de tv → Trakt-vinkjes bijwerken (alleen als Trakt iets nieuws meldt), zoals iOS/Android
      if (app.profile) B.trakt.refresh(false);
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
