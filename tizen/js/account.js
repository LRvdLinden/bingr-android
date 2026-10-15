/*
 * Bingr-account (Supabase via gewone REST), volgens docs/ACCOUNT.md van de iOS-repo.
 * Zelfde aanroepen als AccountService.kt op Android: tv-koppeling met QR, sessie vernieuwen,
 * apparaten (platform "tizen"), servers, state-onderdelen, tegelijk kijken en kijkgeschiedenis.
 * De sessie staat alleen op deze tv (localStorage "bingr.auth"); device_id blijft na uitloggen.
 */
(function () {
  'use strict';
  var B = window.B;
  var L = function () { return B.L.apply(null, arguments); };

  function AccountError(msg, code) { this.message = msg; this.code = code || null; }
  AccountError.prototype = Object.create(Error.prototype);
  B.AccountError = AccountError;

  var A = B.account = {};
  var session = B.store.get('auth', null); // {access_token, refresh_token, expires_at (s), email, user_id}
  var refreshing = null;

  A.isSignedIn = function () { return !!(session && session.refresh_token); };
  A.email = function () { return session ? session.email : null; };
  A.userId = function () {
    if (!session) return null;
    return session.user_id || jwtSubject(session.access_token);
  };

  function jwtSubject(token) {
    try {
      var part = String(token).split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      while (part.length % 4) part += '=';
      var claims = JSON.parse(decodeURIComponent(escape(atob(part))));
      return claims.sub || null;
    } catch (e) { return null; }
  }

  function storeSession(j, fallbackEmail) {
    if (!j || !j.access_token || !j.refresh_token) return false;
    var now = Math.floor(Date.now() / 1000);
    var user = j.user || {};
    session = {
      access_token: j.access_token,
      refresh_token: j.refresh_token,
      expires_at: j.expires_at || (now + (j.expires_in || 3600)),
      email: user.email || fallbackEmail || (session && session.email) || null,
      user_id: user.id || jwtSubject(j.access_token) || (session && session.user_id) || null
    };
    B.store.set('auth', session);
    blocked = false;
    return true;
  }

  A._store = storeSession;

  function clear() {
    session = null;
    ent = null; entOwner = null; entAt = 0;
    B.store.del('auth');
    B.store.set('deviceRegistered', false);
    B.emit('signedOut');
  }

  // MARK: aanvragen

  function post(path, body, bearer) {
    var h = { 'apikey': B.config.supabaseKey, 'Content-Type': 'application/json', 'Accept': 'application/json' };
    if (bearer) h.Authorization = 'Bearer ' + bearer;
    return B.http(B.config.supabaseUrl + path, { method: 'POST', headers: h, body: body || {}, timeout: 20000 })
      .then(function (r) {
        var j = r.json || {};
        r.ok = r.status >= 200 && r.status < 300;
        r.errorCode = j.error_code || (typeof j.code === 'string' ? j.code : null) || j.error || null;
        // build 88: 403 feature_locked (Gratis)
        r.featureLocked = r.status === 403 && (r.errorCode === 'feature_locked' || j.error === 'feature_locked');
        return r;
      }, function () { throw new AccountError(L('No internet connection.'), 'offline'); });
  }
  function generic() { return new AccountError(L('Something went wrong. Please try again.')); }

  // MARK: geblokkeerd account (build 82, = iOS en Android)
  /** Melding bij een geblokkeerd account. */
  A.bannedMessage = function () {
    return L('Your account has been blocked. You received an email with the reason and until when. Questions? Go to support.bingr.online.');
  };
  /**
   * Geblokkeerd? Supabase Auth: error_code "user_banned" (400/403; oudere versies "User is banned" in de tekst);
   * eigen functies mogen error: "user_banned" of banned: true geven. r = antwoord van B.http.
   */
  A.isBanned = function (r) {
    var j = (r && r.json) || {};
    if (j.banned === true) return true;
    if (!r || (r.status >= 200 && r.status < 300)) return false;
    var codes = [j.error_code, j.error, j.code, j.reason];
    for (var i = 0; i < codes.length; i++) {
      var c = typeof codes[i] === 'string' ? codes[i].toLowerCase() : '';
      if (c === 'user_banned' || c === 'banned' || c === 'account_blocked') return true;
    }
    var msg = String(j.msg || j.message || j.error_description || '').toLowerCase();
    return msg.indexOf('banned') >= 0;
  };
  /** Het inlogscherm toont de melding tot er weer iemand inlogt. */
  var blocked = false;
  A.blockedNotice = function () { return blocked; };
  function banned() { blocked = true; return new AccountError(A.bannedMessage(), 'banned'); }

  /** Sessie vernieuwen: 'ok' | 'invalid' (uitgelogd) | 'offline'. Nooit twee tegelijk (refresh tokens zijn eenmalig). */
  A.refresh = function () {
    if (refreshing) return refreshing;
    if (!session || !session.refresh_token) return Promise.resolve('invalid');
    var token = session.refresh_token;
    refreshing = post('/auth/v1/token?grant_type=refresh_token', { refresh_token: token }).then(function (r) {
      // intussen uitgelogd of met een ander account ingelogd: dit antwoord hoort bij een oude sessie
      if (!session || session.refresh_token !== token) return session ? 'ok' : 'invalid';
      if (r.ok && storeSession(r.json)) return 'ok';
      // build 82: geblokkeerd → lokaal uitloggen (geen logout-aanroep) en op het inlogscherm de melding tonen
      if (A.isBanned(r)) { blocked = true; clear(); return 'invalid'; }
      if (r.status === 400 || r.status === 401) { clear(); return 'invalid'; }
      return 'offline';
    }, function () { return 'offline'; }).then(function (res) { refreshing = null; return res; });
    return refreshing;
  };

  A.validAccessToken = function () {
    var now = Date.now() / 1000;
    if (session && session.access_token && session.expires_at - now > 60) return Promise.resolve(session.access_token);
    return A.refresh().then(function (res) {
      if (res === 'invalid') throw generic();
      if (!session || !session.access_token) throw new AccountError(L('No internet connection.'), 'offline');
      return session.access_token;
    });
  };

  /** device-auth met een geldige sessie; bij 401 één keer vernieuwen. null = netwerkfout of geen sessie. */
  function authed(body) {
    if (!A.isSignedIn()) return Promise.resolve(null);
    var attempt = 0;
    function go(token) {
      return post('/functions/v1/device-auth', body, token).then(function (r) {
        if (r.status !== 401 || attempt > 0) return r;
        attempt++;
        return A.refresh().then(function (res) {
          if (res === 'ok') return go(session.access_token);
          if (res === 'invalid') B.emit('sessionInvalid');
          return null;
        });
      }, function () { return null; });
    }
    return A.validAccessToken().then(go, function () { return null; });
  }
  A.authed = authed;

  // MARK: tv-login met QR-code

  A.normalizeCode = function (s) { return String(s || '').toUpperCase().replace(/[^A-Z0-9]/g, ''); };
  A.displayCode = function (c) { return c && c.length === 6 ? c.slice(0, 3) + '-' + c.slice(3) : c; };

  A.startTv = function () {
    return post('/functions/v1/device-auth', { action: 'start', device: A.deviceName() }).then(function (r) {
      var j = r.json || {};
      if (!r.ok || !j.code) throw generic();
      return {
        code: j.code, pollSecret: j.poll_secret || '', url: j.url || ('https://bingr.online/koppelen?code=' + j.code),
        expiresIn: j.expires_in || 600, interval: Math.max(1, j.interval || 3)
      };
    });
  };
  A.pollTv = function (c) {
    return post('/functions/v1/device-auth', { action: 'poll', code: c.code, poll_secret: c.pollSecret }).then(function (r) {
      if (A.isBanned(r)) throw banned();
      if (!r.ok) throw generic();
      var j = r.json || {};
      if (j.status === 'approved') return j.token_hash ? { status: 'approved', tokenHash: j.token_hash, email: j.email } : { status: 'expired' };
      return { status: j.status === 'expired' ? 'expired' : 'pending' };
    });
  };
  A.verifyTv = function (tokenHash, email) {
    return post('/auth/v1/verify', { type: 'magiclink', token_hash: tokenHash }).then(function (r) {
      if (A.isBanned(r)) throw banned();
      if (!r.ok || !storeSession(r.json, email)) throw generic();
    });
  };

  /**
   * Volledige tv-flow: code starten, pollen om de `interval` seconden, bij verlopen een nieuwe code.
   * handlers: onCode(code|null), onError(tekst|null). Geeft een object met cancel(); resolve bij inloggen.
   */
  A.runTvFlow = function (handlers) {
    var cancelled = false;
    var p = new Promise(function (resolve) {
      function startLoop() {
        if (cancelled) return;
        A.startTv().then(function (c) {
          if (cancelled) return;
          handlers.onError(null); handlers.onCode(c);
          var deadline = Date.now() + c.expiresIn * 1000;
          function tick() {
            if (cancelled) return;
            if (Date.now() > deadline) { handlers.onCode(null); startLoop(); return; }
            A.pollTv(c).then(function (res) {
              if (cancelled) return;
              handlers.onError(null);
              if (res.status === 'pending') setTimeout(tick, c.interval * 1000);
              else if (res.status === 'expired') { handlers.onCode(null); startLoop(); }
              else {
                A.verifyTv(res.tokenHash, res.email).then(function () { if (!cancelled) resolve(); },
                  function (e) { handlers.onError(e.message); handlers.onCode(null); startLoop(); });
              }
            }, function (e) { handlers.onError(e.message); setTimeout(tick, c.interval * 1000); });
          }
          setTimeout(tick, c.interval * 1000);
        }, function (e) {
          handlers.onError(e.message);
          setTimeout(startLoop, 5000);
        });
      }
      startLoop();
    });
    return { promise: p, cancel: function () { cancelled = true; } };
  };

  // MARK: apparaat

  /** Vaste UUID van deze tv; één keer gemaakt, alleen lokaal, blijft na uitloggen. */
  A.deviceId = function () {
    var id = B.store.get('deviceId', null);
    if (!id) { id = B.uuid(); B.store.set('deviceId', id); }
    return id;
  };
  A.model = function () {
    try { if (window.webapis && webapis.productinfo) return webapis.productinfo.getRealModel() || webapis.productinfo.getModel() || ''; } catch (e) { /* stil */ }
    return '';
  };
  /** Naam op het koppelscherm van de telefoon. */
  A.deviceName = function () { return 'Samsung TV'; };
  /** Naam in de apparatenlijst: "Samsung TV (QE55Q80B)". */
  A.deviceDisplayName = function () {
    var m = A.model();
    return m ? 'Samsung TV (' + m + ')' : 'Samsung TV';
  };
  A.isRegistered = function () { return !!B.store.get('deviceRegistered', false); };

  function parseDevices(list) {
    var own = A.deviceId();
    return (list || []).filter(function (d) { return d && d.device_id; }).map(function (d) {
      return {
        id: d.device_id, name: d.name || d.platform || '?', platform: d.platform || '',
        createdAt: B.parseISO(d.created_at), lastSeenAt: B.parseISO(d.last_seen_at),
        current: d.current === true || d.device_id === own
      };
    });
  }

  function deviceCall(action, id, extra) {
    var body = { action: action, device_id: id };
    if (extra) B.assign(body, extra);
    return authed(body);
  }

  // MARK: versies voor de beheerder (build 62); oudere backends negeren deze velden

  function placeholder(v) { return !v || String(v).indexOf('__') === 0; }
  /** "1.0.0 (62)": versie uit config.xml (via CI in core.js gezet) en het buildnummer. */
  A.appVersion = function () {
    var v = B.config.version, b = B.config.build;
    if (placeholder(v)) {
      try { v = tizen.application.getAppInfo().version; } catch (e) { v = null; }
    }
    return (placeholder(v) ? '1.0.0' : v) + ' (' + (placeholder(b) ? '0' : b) + ')';
  };
  /** "Tizen 6.5": platformversie van de tv, anders uit de user agent. */
  A.osVersion = function () {
    var v = null;
    try {
      if (window.tizen && tizen.systeminfo && tizen.systeminfo.getCapability) v = tizen.systeminfo.getCapability('http://tizen.org/feature/platform.version');
    } catch (e) { v = null; }
    if (!v) {
      var m = /Tizen[ \/](\d+(?:\.\d+)?)/i.exec(navigator.userAgent || '');
      if (m) v = m[1];
    }
    return v ? 'Tizen ' + String(v).split('.').slice(0, 2).join('.') : 'Tizen';
  };
  function versionFields() { return { app_version: A.appVersion(), os_version: A.osVersion() }; }

  /** announcements {lang} → [{id, title, body, createdAt}]; fouten → lege lijst (stil). */
  A.announcements = function (lang) {
    return deviceCall('announcements', A.deviceId(), { lang: lang }).then(function (r) {
      if (!r || !r.ok || !r.json) return [];
      return (r.json.items || []).filter(function (a) { return a && a.id && (a.title || a.body); }).map(function (a) {
        return { id: String(a.id), title: String(a.title || '').trim(), body: String(a.body || '').trim(), createdAt: B.parseISO(a.created_at) || 0 };
      });
    }, function () { return []; });
  };

  /** register_device → 'ok' | {limit, devices} (te veel apparaten) | 'failed'. */
  A.registerDevice = function () {
    return deviceCall('register_device', A.deviceId(), B.assign({ name: A.deviceDisplayName(), platform: B.config.platform }, versionFields())).then(function (r) {
      if (!r) return 'failed';
      var j = r.json || {};
      if (r.status === 403 && (r.errorCode === 'device_limit' || j.devices)) return { limit: j.limit || 15, devices: parseDevices(j.devices), plan: j.plan || null };
      if (!r.ok) return 'failed';
      B.store.set('deviceRegistered', true);
      return 'ok';
    });
  };
  A.heartbeat = function () {
    return deviceCall('heartbeat', A.deviceId(), versionFields()).then(function (r) {
      if (!r) return true; // netwerkfout: niet uitloggen
      if (r.json && r.json.revoked === true) return false;
      if (r.ok && r.json && r.json.entitlements) A.applyEntitlements(r.json.entitlements);
      return true;
    });
  };
  A.listDevices = function () {
    return deviceCall('list_devices', A.deviceId()).then(function (r) {
      if (!r || !r.ok) throw generic();
      return { limit: r.json.limit || 15, devices: parseDevices(r.json.devices) };
    });
  };
  /** Apparaat hernoemen (build 45): max. 60 tekens; register_device overschrijft de naam daarna niet meer. */
  A.renameDevice = function (id, name) {
    name = String(name || '').trim().slice(0, 60);
    if (!name) return Promise.reject(generic());
    return deviceCall('rename_device', id, { name: name }).then(function (r) { if (!r || !r.ok) throw generic(); });
  };
  /** Platformnaam voor mensen: "tizen" heet "Samsung TV" (ACCOUNT.md, build 45/55). */
  A.platformLabel = function (p) {
    var l = String(p || '').toLowerCase();
    if (l === 'tizen') return L('Samsung TV');
    if (l === 'browser') return L('Browser'); // webspeler op bingr.online (build 62)
    return String(p || '');
  };
  /** Apparaatnaam in Nu aan het kijken en de kijkgeschiedenis: bij de webspeler "Chrome op Mac · Browser". */
  A.deviceNameWithLabel = function (name, p) {
    if (String(p || '').toLowerCase() !== 'browser') return name;
    var label = L('Browser');
    var n = String(name || '');
    return (!n || n === '?' || n.toLowerCase() === 'browser' || n === label) ? label : n + '  ·  ' + label;
  };
  A.removeDevice = function (id) {
    return deviceCall('remove_device', id).then(function (r) { if (!r || !r.ok) throw generic(); });
  };

  /** Uitloggen (alleen deze tv): eerst remove_device voor deze tv, dan logout, daarna altijd lokaal wissen. */
  A.signOut = function () {
    var token = session && session.access_token;
    var p = A.isSignedIn() ? A.removeDevice(A.deviceId()).catch(B.noop) : Promise.resolve();
    return p.then(function () {
      if (!token) return;
      // build 74: scope=local, alleen de sessie van deze tv (zonder scope logt Supabase overal uit)
      return post('/auth/v1/logout?scope=local', {}, token).catch(B.noop);
    }).then(function () { clear(); });
  };
  /** Op een ander apparaat verwijderd (heartbeat: revoked): lokaal uitloggen zonder logout-aanroep. */
  A.revokedSignOut = function () { clear(); };

  // MARK: app-sleutels van de server (TMDB enz.), alleen lokaal bewaard

  A.fetchConfig = function () {
    return authed({ action: 'config' }).then(function (r) {
      if (!r || !r.ok || !r.json) return false;
      if (r.json.entitlements) A.applyEntitlements(r.json.entitlements);
      B.store.set('serverKeys', r.json);
      return true;
    });
  };
  A.serverKey = function (name) { var k = B.store.get('serverKeys', {}) || {}; return k[name] || ''; };

  // MARK: profielen (PostgREST account_data)

  function rest(method, path, body, extra) {
    return A.validAccessToken().then(function (token) {
      var h = { 'apikey': B.config.supabaseKey, 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'Accept': 'application/json' };
      if (extra) B.assign(h, extra);
      return B.http(B.config.supabaseUrl + path, { method: method, headers: h, body: body, timeout: 20000 });
    });
  }
  /** account_data.profiles (ook tombstones); null = mislukt. */
  A.readProfiles = function () {
    return rest('GET', '/rest/v1/account_data?select=profiles,updated_at').then(function (r) {
      if (r.status === 401) return A.refresh().then(function () { return null; });
      if (r.status < 200 || r.status >= 300 || !Array.isArray(r.json)) return null;
      var row = r.json[0];
      return row && Array.isArray(row.profiles) ? row.profiles : [];
    }, function () { return null; });
  };
  A.writeProfiles = function (profiles) {
    var uid = A.userId();
    if (!uid) return Promise.resolve(false);
    return rest('POST', '/rest/v1/account_data', { user_id: uid, profiles: profiles, updated_at: B.iso() },
      { 'Prefer': 'resolution=merge-duplicates,return=minimal' }).then(function (r) {
      return r.status >= 200 && r.status < 300;
    }, function () { return false; });
  };

  /**
   * Profielfoto (Storage-bucket avatars) als data:-URL; null bij een fout.
   * Build 100: data:-URL in plaats van een blob:-URL (werkt op elke tv-browser als CSS-achtergrond en hoeft niet
   * vrijgegeven te worden); fouten komen in het log, zodat ze in de diagnose te zien zijn.
   */
  A.photoURL = function (path) {
    return A.validAccessToken().then(function (token) {
      var url = B.config.supabaseUrl + '/storage/v1/object/authenticated/avatars/' +
        String(path).split('/').map(encodeURIComponent).join('/');
      return B.http(url, { headers: { 'apikey': B.config.supabaseKey, 'Authorization': 'Bearer ' + token }, binary: true, timeout: 20000 });
    }).then(function (r) {
      if (r.status < 200 || r.status >= 300 || !r.buffer || !r.buffer.byteLength) {
        B.log('profielfoto: HTTP ' + r.status);
        return null;
      }
      var u8 = new Uint8Array(r.buffer), bin = '';
      for (var i = 0; i < u8.length; i += 8192) bin += String.fromCharCode.apply(null, u8.subarray(i, i + 8192));
      var type = u8[0] === 0x89 && u8[1] === 0x50 ? 'image/png' : 'image/jpeg';
      return 'data:' + type + ';base64,' + btoa(bin);
    }, function (e) { B.log('profielfoto: ' + ((e && e.message) || e)); return null; });
  };

  // MARK: servers en state-onderdelen

  A.getSources = function () {
    return authed({ action: 'get_sources' }).then(function (r) {
      if (!r || !r.ok || !r.json) return null;
      var j = r.json;
      return {
        sources: (Array.isArray(j.sources) ? j.sources : []).filter(function (s) { return s && s.id; }),
        mainSourceID: j.mainSourceID || null, updatedAt: j.updatedAt || null
      };
    });
  };
  /**
   * put_sources: altijd de hele lijst, met tombstones (zoals de webconsole en de apps). Gooit bij een fout.
   * Geeft updatedAt van de server terug.
   */
  A.putSources = function (sources, mainSourceID) {
    // tombstones ouder dan 90 dagen mogen weg (ACCOUNT.md)
    var cut = Date.now() - 90 * 86400000;
    sources = sources.filter(function (s) { return !(s && s.deleted === true && (B.parseISO(s.deletedAt || s.updatedAt) || 0) < cut); });
    return authed({ action: 'put_sources', sources: sources, mainSourceID: mainSourceID || null }).then(function (r) {
      if (!r) throw new AccountError(L('No internet connection.'), 'offline');
      if (!r.ok || (r.json && r.json.ok === false)) throw generic();
      return (r.json && r.json.updatedAt) || B.iso();
    });
  };
  /** get_state: {naam: {data, updatedAt}}; null = mislukt. */
  A.getState = function (sections) {
    var body = { action: 'get_state' };
    if (sections && sections.length) body.sections = sections;
    return authed(body).then(function (r) {
      if (!r || !r.ok || !r.json) return null;
      return r.json.sections || {};
    });
  };
  A.putState = function (section, data) {
    return authed({ action: 'put_state', section: section, data: data }).then(function (r) {
      if (!r || !r.ok || (r.json && r.json.ok === false)) return null;
      return (r.json && r.json.updatedAt) || '';
    });
  };

  // MARK: abonnement (build 88): Gratis of Lifetime

  /*
   * device-auth "entitlements" {platform: 'tizen'} (ook als veld "entitlements" in config/heartbeat), bewaard per
   * account in localStorage. Onbekend (nooit opgehaald, netwerkfout zonder cache) = alles open zoals Lifetime.
   */
  var ent = null, entOwner = null, entAt = 0, entBusy = null;
  A.UPGRADE_URL = 'https://bingr.online/upgrade/';
  A.FREE = { streams: 2, devices: 8, servers: 1 };
  A.LIFETIME = { streams: 6, devices: 15 };

  function parseEnt(j) {
    if (!j || typeof j !== 'object' || !j.plan) return null;
    var plan = String(j.plan).toLowerCase(), free = plan === 'free';
    var lim = j.limits || {}, f = j.features || {}, up = j.upgrade || {};
    function feat(k) { return typeof f[k] === 'boolean' ? f[k] : !free; }
    function int(v) { var n = parseInt(v, 10); return isFinite(n) ? n : null; }
    return {
      plan: plan, free: free,
      streams: int(lim.streams), devices: int(lim.devices),
      servers: Object.prototype.hasOwnProperty.call(lim, 'servers') ? int(lim.servers) : (free ? A.FREE.servers : null),
      stats: feat('stats'), now_watching: feat('now_watching'), trakt: feat('trakt'), multi_server: feat('multi_server'), themes: feat('themes'),
      url: /^https:\/\//.test(String(up.url || '')) ? String(up.url) : A.UPGRADE_URL,
      link: typeof up.link === 'boolean' ? up.link : true,
      price: up.price || '€ 4,99', price_en: up.price_en || '€4.99'
    };
  }
  function entKey() { var u = A.userId(); return u ? 'entitlements.' + u : null; }
  /** Huidig abonnement of null (onbekend). */
  A.entitlements = function () {
    var u = A.isSignedIn() ? A.userId() : null;
    if (u !== entOwner) { entOwner = u; entAt = 0; ent = u ? parseEnt(B.store.get(entKey(), null)) : null; }
    return ent;
  };
  A.isFree = function () { var e = A.entitlements(); return !!(e && e.free); };
  A.isLifetime = function () { var e = A.entitlements(); return !!(e && !e.free); };
  /** feature: 'stats' | 'now_watching' | 'trakt' | 'multi_server' | 'themes' | 'streams' | 'devices'. */
  A.allowed = function (feature) {
    var e = A.entitlements();
    if (!e) return true;
    if (feature === 'streams' || feature === 'devices') return !e.free;
    return e[feature] !== false;
  };
  /** Mag er nog een server bij? Bestaande extra servers blijven gewoon werken. */
  A.canAddServer = function (existing) {
    var e = A.entitlements();
    if (!e || e.multi_server || e.servers == null) return true;
    return existing < e.servers;
  };
  A.upgradeUrl = function () { var e = A.entitlements(); return e ? e.url : A.UPGRADE_URL; };
  A.upgradeLink = function () { var e = A.entitlements(); return e ? e.link : true; };
  A.price = function () {
    var e = A.entitlements(), nl = String(B.lang || '').indexOf('nl') === 0;
    return nl ? (e ? e.price : '€ 4,99') : (e ? e.price_en : '€4.99');
  };
  A.applyEntitlements = function (j) {
    var e = parseEnt(j);
    if (!e || !A.isSignedIn()) return false;
    var before = A.entitlements();
    var was = before ? JSON.stringify(before) : '';
    ent = e; entOwner = A.userId(); entAt = Date.now();
    B.store.set(entKey(), j);
    if (JSON.stringify(e) !== was) B.emit('entitlements', { upgraded: !!(before && before.free && !e.free) });
    return true;
  };
  /** Ophalen (na inloggen, bij terugkeer op de tv); hooguit eens per minuut tenzij force. */
  A.fetchEntitlements = function (force) {
    if (!A.isSignedIn()) return Promise.resolve(false);
    if (entBusy) return entBusy;
    if (!force && Date.now() - entAt < 60000) return Promise.resolve(false);
    entBusy = authed({ action: 'entitlements', platform: B.config.platform || 'tizen' }).then(function (r) {
      if (!r || !r.ok || !r.json || unknownAction(r)) return false;
      return A.applyEntitlements(r.json);
    }, function () { return false; }).then(function (ok) { entBusy = null; return ok; });
    return entBusy;
  };
  /** Waarom iets bij Lifetime hoort (upgradevenster en foutmeldingen). */
  A.lockedReason = function (feature) {
    switch (feature) {
      case 'stats': return L('Statistics are part of Bingr Lifetime.');
      case 'now_watching': return L('Watching now and the watch history are part of Bingr Lifetime.');
      case 'trakt': return L('Trakt is part of Bingr Lifetime.');
      case 'multi_server': return L('More servers are part of Bingr Lifetime. Your current servers keep working.');
      case 'themes': return L('Accent colors are part of Bingr Lifetime.');
      case 'streams': return L('With Bingr Lifetime you can watch on up to %d screens at the same time.', A.LIFETIME.streams);
      case 'devices': return L('With Bingr Lifetime you can use up to %d devices.', A.LIFETIME.devices);
      default: return L('Get more out of Bingr with a one-time upgrade.');
    }
  };
  function normFeature(f) {
    f = String(f || '').toLowerCase();
    if (f === 'now-watching' || f === 'watching' || f === 'list_streams' || f === 'watch_log' || f === 'history') return 'now_watching';
    if (f === 'watch_stats' || f === 'statistics') return 'stats';
    return f;
  }
  /** Server zegt feature_locked: dat onderdeel dicht, opnieuw ophalen en een fout met de reden gooien. */
  function checkLocked(r) {
    if (!r || !r.featureLocked) return;
    var f = normFeature(r.json && r.json.feature);
    var e = A.entitlements() || parseEnt({ plan: 'free', features: { stats: true, now_watching: true, trakt: true, multi_server: true, themes: true } });
    e.plan = 'free'; e.free = true;
    if (f && Object.prototype.hasOwnProperty.call(e, f) && typeof e[f] === 'boolean') e[f] = false;
    ent = e; entOwner = A.userId();
    B.emit('entitlements', {});
    A.fetchEntitlements(true);
    var err = new AccountError(A.lockedReason(f), 'feature_locked');
    err.locked = true; err.feature = f;
    throw err;
  }

  // MARK: tegelijk kijken en kijkgeschiedenis

  function parseStreams(list) {
    return (list || []).filter(function (s) { return s && s.device_id; }).map(function (s) {
      return {
        deviceId: s.device_id, deviceName: s.device_name || s.platform || '?', platform: s.platform || '',
        profileId: s.profile_id || '', profileName: s.profile_name || '', title: s.title || '', kind: s.kind || '',
        startedAt: B.parseISO(s.started_at), current: s.device_id === A.deviceId()
      };
    });
  }
  /** IANA-tijdzone van de tv ("Europe/Amsterdam"), voor de statistieken per lokale dag (build 68). */
  A.timeZone = function () {
    try { var z = Intl.DateTimeFormat().resolvedOptions().timeZone; if (z) return z; } catch (e) { /* oude tv */ }
    try { if (window.tizen && tizen.time && tizen.time.getLocalTimezone) { var t = tizen.time.getLocalTimezone(); if (t) return t; } } catch (e2) { /* geen tizen.time */ }
    return 'Europe/Amsterdam';
  };
  /**
   * stream_start → {ok} | {limit, streams} | {failed} (netwerkfout: gewoon afspelen).
   * Build 68: extra = {seriesId, seriesTitle} bij een aflevering (statistieken); tz gaat altijd mee.
   */
  A.streamStart = function (profileId, profileName, title, kind, contentId, extra) {
    var body = {
      action: 'stream_start', device_id: A.deviceId(), profile_id: profileId, profile_name: profileName,
      title: title, kind: kind, content_id: contentId, tz: A.timeZone()
    };
    if (extra && extra.seriesId) body.series_id = String(extra.seriesId);
    if (extra && extra.seriesTitle) body.series_title = String(extra.seriesTitle).slice(0, 200);
    return authed(body).then(function (r) {
      if (!r) return { failed: true };
      var j = r.json || {};
      if (r.status === 403 && (r.errorCode === 'stream_limit' || j.streams)) return { limit: j.limit || 6, streams: parseStreams(j.streams), plan: j.plan || null };
      return r.ok ? { ok: true } : { failed: true };
    });
  };
  /** true = telt nog, false = gestopt (opnieuw stream_start), null = netwerkfout. */
  A.streamPing = function (title) {
    var body = { action: 'stream_ping', device_id: A.deviceId() };
    if (title) body.title = title;
    return authed(body).then(function (r) {
      if (!r) return null;
      if (r.json && r.json.stopped === true) return false;
      return r.ok ? true : null;
    });
  };
  // build 97: laatste stream_stop onthouden, zodat afsluiten erop kan wachten (app.exit)
  var lastStop = null;
  A.streamStop = function () { lastStop = authed({ action: 'stream_stop', device_id: A.deviceId() }).catch(B.noop); return lastStop; };
  A.lastStreamStop = function () { return lastStop; };
  A.listStreams = function () {
    return authed({ action: 'list_streams' }).then(function (r) {
      checkLocked(r);
      if (!r || !r.ok) throw generic();
      return { limit: r.json.limit || 6, streams: parseStreams(r.json.streams) };
    });
  };
  A.watchLog = function (days) {
    return authed({ action: 'watch_log', days: days || 7 }).then(function (r) {
      checkLocked(r);
      if (!r || !r.ok) throw generic();
      return (r.json.entries || []).map(function (e) {
        return {
          deviceName: e.device_name || e.platform || '?', platform: e.platform || '', profileId: e.profile_id || '', profileName: e.profile_name || '',
          title: e.title || '', kind: e.kind || '', startedAt: B.parseISO(e.started_at), minutes: e.minutes || 0
        };
      }).filter(function (e) { return e.startedAt; }).sort(function (a, b) { return b.startedAt - a.startedAt; });
    });
  };

  // MARK: kijkstatistieken (build 68), zie docs/ACCOUNT.md in de iOS-repo

  function num(v) { var n = typeof v === 'number' ? v : parseFloat(v); return isFinite(n) && n > 0 ? n : 0; }
  function str(v) { return v == null ? '' : String(v); }
  function unknownAction(r) { return r.status === 404 || (r.json && r.json.error === 'unknown_action') || r.errorCode === 'unknown_action'; }
  /** Fout als de server watch_stats nog niet kent (oude backend). */
  function unavailable() { var e = new AccountError(L('Statistics are not available yet. Please try again later.'), 'unavailable'); e.unavailable = true; return e; }

  /** Antwoord van watch_stats, tolerant: ontbrekende velden worden 0 of leeg. */
  A.parseStats = function (j) {
    j = j || {};
    var t = j.totals || {}, mv = t.movies || {}, se = t.series || {}, lv = t.live || {};
    function top(list) {
      return (list || []).filter(function (x) { return x && (x.title || x.item_key); }).map(function (x) {
        return { title: str(x.title), itemKey: str(x.item_key), minutes: num(x.minutes), episodes: Math.round(num(x.episodes)) };
      });
    }
    var tp = j.top || {};
    return {
      period: str(j.period), from: str(j.from), to: str(j.to), profileId: j.profile_id ? str(j.profile_id) : null,
      totalMinutes: t.minutes != null ? num(t.minutes) : num(mv.minutes) + num(se.minutes) + num(lv.minutes),
      movieCount: Math.round(num(mv.count)), movieMinutes: num(mv.minutes),
      seriesShows: Math.round(num(se.shows)), seriesEpisodes: Math.round(num(se.episodes)), seriesMinutes: num(se.minutes),
      liveChannels: Math.round(num(lv.channels)), liveMinutes: num(lv.minutes),
      buckets: (j.by_bucket || []).filter(function (b) { return b && b.key; }).map(function (b) {
        var m = num(b.movie_minutes), e = num(b.episode_minutes), l = num(b.live_minutes);
        return { key: str(b.key), minutes: b.minutes != null ? num(b.minutes) : m + e + l, movie: m, episode: e, live: l };
      }),
      topSeries: top(tp.series), topMovies: top(tp.movies), topChannels: top(tp.channels),
      profiles: (j.profiles || []).filter(function (p) { return p; }).map(function (p) {
        return { profileId: str(p.profile_id), profileName: str(p.profile_name), minutes: num(p.minutes) };
      }),
      since: str(j.since)
    };
  };
  /** watch_stats: period 'week' | 'month' | 'year', offset 0 = nu, -1 = vorige…; profileId leeg = alle profielen. */
  A.watchStats = function (period, offset, profileId) {
    var body = { action: 'watch_stats', period: period, offset: offset || 0, tz: A.timeZone() };
    if (profileId) body.profile_id = profileId;
    return authed(body).then(function (r) {
      if (!r) throw new AccountError(L('No internet connection.'), 'offline');
      checkLocked(r);
      if (unknownAction(r)) throw unavailable();
      if (!r.ok || !r.json) throw generic();
      return A.parseStats(r.json);
    });
  };
  /** delete_watch_stats; profileId leeg = alle profielen. */
  A.deleteWatchStats = function (profileId) {
    var body = { action: 'delete_watch_stats' };
    if (profileId) body.profile_id = profileId;
    return authed(body).then(function (r) {
      if (!r) throw new AccountError(L('No internet connection.'), 'offline');
      checkLocked(r);
      if (unknownAction(r)) throw unavailable();
      if (!r.ok || (r.json && r.json.ok === false)) throw generic();
      return true;
    });
  };
})();
