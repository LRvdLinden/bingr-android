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

  app.exit = function () {
    // speler netjes sluiten (AVPlay vrijgeven, stream_stop, voortgang) en openstaande wijzigingen versturen
    try { if (B.player.current) B.player.current.close(); if (B.multiview.current) B.multiview.current.close(); B.preview.stop(); } catch (e) { /* stil */ }
    D.flush();
    try { tizen.application.getCurrentApplication().exit(); } catch (e) { B.log('exit'); }
  };
  var exitArmed = 0;
  app.confirmExit = function () {
    if (Date.now() - exitArmed < 2500) { app.exit(); return; }
    exitArmed = Date.now();
    U.toast(L('Press Back again to close Bingr'), 2500);
  };

  app.applyAccent = function (key) {
    var k = key || D.accentKey(app.profile);
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
  var photos = {};
  app.photo = function (path, updated) {
    var k = path + '|' + (updated || '');
    if (!photos[k]) photos[k] = A.photoURL(path);
    return photos[k];
  };

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
    setTimeout(function () {
      if (A.isSignedIn()) {
        afterLogin(false);
        A.refresh().then(function (res) { if (res === 'invalid') toLogin(); });
      } else toLogin();
    }, 900);
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
  app.switchProfile = function () {
    if (B.player.current) B.player.current.close();
    if (B.multiview.current) B.multiview.current.close();
    B.preview.stop();
    B.ageRatings.stop();
    D.flush();
    showProfiles();
  };

  /** Meteen naar een ander profiel vanuit het profielmenu (build 72); de pincode is dan al gecontroleerd. */
  app.openProfile = function (p) {
    if (B.player.current) B.player.current.close();
    if (B.multiview.current) B.multiview.current.close();
    B.preview.stop();
    B.ageRatings.stop();
    D.flush();
    pickProfile(p);
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
  var agePending = false;
  B.on('ageRatings', function () {
    if (!app.profile || D.ageLimit(app.profile) == null || agePending) return;
    agePending = true;
    B.whenQuiet(function () { agePending = false; Lib.applyFilters(); }, 1500);
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

  B.on('profiles', function () {
    if (app.profile && !D.profileById(app.profile.id)) showProfiles();
  });

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
