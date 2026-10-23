#!/usr/bin/env node
// Build 141: controles van de beveiliging op Samsung TV (js/build141.js), gelijk aan tools/test/Build141LogicTest.kt op
// Android. Draait zonder tv: node tizen/test/build141_test.js (de workflow Build doet dit; een fout laat de build mislukken).
// - pincode-limiet en vaste hosts (zelfde gevallen als Android)
// - kluis: zonder IndexedDB terugval op localStorage; met IndexedDB (nagebootst) + Web Crypto van Node: eenmalig
//   overzetten uit localStorage, versleuteld opslaan (geen platte tekst), en na een herstart weer lezen
'use strict';
var vm = require('vm');
var fs = require('fs');
var path = require('path');
var nodeCrypto = require('crypto');

var JS = path.resolve(__dirname, '..', 'js');
var checks = 0, failures = 0;
function eq(name, got, want) {
  checks++;
  var a = JSON.stringify(got), b = JSON.stringify(want);
  if (a !== b) { failures++; console.log('FOUT ' + name + ': kreeg <' + a + '>, verwacht <' + b + '>'); }
}
function ok(name, cond, detail) { checks++; if (!cond) { failures++; console.log('FOUT ' + name + (detail ? ': ' + JSON.stringify(detail) : '')); } }

var noop = function () {};
function el() { return { style: {}, classList: { add: noop, remove: noop, toggle: noop, contains: function () { return false; } }, setAttribute: noop, appendChild: noop, addEventListener: noop }; }

function fakeLocalStorage(init) {
  var m = Object.assign({}, init || {});
  return {
    _m: m,
    getItem: function (k) { return Object.prototype.hasOwnProperty.call(m, k) ? m[k] : null; },
    setItem: function (k, v) { m[k] = String(v); },
    removeItem: function (k) { delete m[k]; },
    key: function (i) { return Object.keys(m)[i] || null; },
    get length() { return Object.keys(m).length; }
  };
}

/** Minimale IndexedDB (open, objectStore get/put/delete) met een gedeelde opslag, zodat een "herstart" hem terugziet. */
function fakeIndexedDB(disk) {
  function later(fn) { setImmediate(fn); }
  function request(fn) {
    var r = { onsuccess: null, onerror: null, result: undefined };
    later(function () {
      try { r.result = fn(); if (r.onsuccess) r.onsuccess(); } catch (e) { r.error = e; if (r.onerror) r.onerror(); }
    });
    return r;
  }
  function db(name) {
    var stores = disk[name];
    return {
      createObjectStore: function (s) { stores[s] = stores[s] || {}; },
      transaction: function (s) {
        return {
          objectStore: function () {
            var st = stores[s];
            return {
              get: function (k) { return request(function () { return st[k]; }); },
              put: function (v, k) { return request(function () { st[k] = v; return k; }); },
              delete: function (k) { return request(function () { delete st[k]; }); }
            };
          }
        };
      }
    };
  }
  return {
    open: function (name) {
      var r = { onsuccess: null, onerror: null, onupgradeneeded: null, result: null };
      later(function () {
        var fresh = !disk[name];
        if (fresh) disk[name] = {};
        r.result = db(name);
        if (fresh && r.onupgradeneeded) r.onupgradeneeded();
        if (r.onsuccess) r.onsuccess();
      });
      return r;
    }
  };
}

function boot(opts) {
  var doc = { addEventListener: noop, createElement: el, body: el(), documentElement: el(), querySelector: function () { return null; } };
  var win = { document: doc, navigator: { userAgent: 'node', language: 'nl' }, addEventListener: noop, location: { href: 'file:///' },
    setTimeout: setTimeout, clearTimeout: clearTimeout, setInterval: function () { return 0; }, clearInterval: noop, console: console,
    Promise: Promise, TextEncoder: TextEncoder, TextDecoder: TextDecoder, Uint8Array: Uint8Array, JSON: JSON,
    performance: { now: function () { return Date.now(); } } };
  win.window = win;
  if (opts.localStorage) win.localStorage = opts.localStorage;
  if (opts.indexedDB) win.indexedDB = opts.indexedDB;
  if (opts.crypto) win.crypto = opts.crypto;
  var ctx = vm.createContext(win);
  vm.runInContext(fs.readFileSync(path.join(JS, 'core.js'), 'utf8'), ctx, { filename: 'core.js' });
  vm.runInContext(fs.readFileSync(path.join(JS, 'build141.js'), 'utf8'), ctx, { filename: 'build141.js' });
  return win.B;
}

// MARK: pincode-limiet
var B0 = boot({});
var X = B0.b141, P = X.lockout;
eq('pin: 4 fouten geen wachttijd', P.waitAfter(4), 0);
eq('pin: 5 fouten 30 s', P.waitAfter(5), 30000);
eq('pin: 6 fouten 1 min', P.waitAfter(6), 60000);
eq('pin: 7 fouten 5 min', P.waitAfter(7), 300000);
eq('pin: 8 fouten 15 min', P.waitAfter(8), 900000);
eq('pin: 9 fouten 1 uur', P.waitAfter(9), 3600000);
eq('pin: 50 fouten max 1 uur', P.waitAfter(50), 3600000);
var s = null;
for (var i = 0; i < 5; i++) s = P.failed(s, 1000);
eq('pin: telt fouten', s.f, 5);
eq('pin: resterend direct', P.remaining(s, 1000), 30000);
eq('pin: resterend na 10 s', P.remaining(s, 11000), 20000);
eq('pin: voorbij', P.remaining(s, 40000), 0);
eq('pin: klok teruggezet', P.remaining(s, 500), 30000);
eq('pin: onder de grens niets', P.remaining({ f: 3, t: 1000 }, 1000), 0);
eq('pin: tekst s', P.shortText(29500), '30 s');
eq('pin: tekst min', P.shortText(61000), '2 min');
eq('pin: tekst uur', P.shortText(3600000), '1 h');
// teller van deze tv (zonder opslag: in het geheugen)
for (i = 0; i < 5; i++) X.pinFailed();
ok('pin: na 5 fouten wachten', X.pinRemaining() > 25000, X.pinRemaining());
X.pinSucceeded();
eq('pin: goed = teller weg', X.pinRemaining(), 0);

// MARK: vaste hosts
eq('trakt: api', X.traktOAuthBase('https://api.trakt.tv'), 'https://api.trakt.tv');
eq('trakt: auth', X.traktOAuthBase('https://auth.trakt.tv/'), 'https://auth.trakt.tv');
eq('trakt: vreemde host', X.traktOAuthBase('https://evil.example'), 'https://api.trakt.tv');
eq('trakt: http niet', X.traktOAuthBase('http://api.trakt.tv'), 'https://api.trakt.tv');
eq('trakt: leeg', X.traktOAuthBase(null), 'https://api.trakt.tv');
eq('os: standaard', X.osHost(null), 'api.opensubtitles.com');
eq('os: vip', X.osHost('vip-api.opensubtitles.com'), 'vip-api.opensubtitles.com');
eq('os: met https', X.osHost('https://api.opensubtitles.com/'), 'api.opensubtitles.com');
eq('os: vreemde host', X.osHost('evil.example'), 'api.opensubtitles.com');
eq('os: lijkt erop', X.osHost('opensubtitles.com.evil.example'), 'api.opensubtitles.com');
eq('os: achtervoegsel-truc', X.osHost('evilopensubtitles.com'), 'api.opensubtitles.com');
eq('os: met poort', X.osHost('api.opensubtitles.com:8443'), 'api.opensubtitles.com');
eq('os: met gebruiker', X.osHost('x@evil.example'), 'api.opensubtitles.com');
eq('os: http niet', X.osHost('http://api.opensubtitles.com'), 'api.opensubtitles.com');

// MARK: feedback (zelfde gevallen als tools/test/Build141FeedbackTest.kt)
var HR = X.homeRows, ML = X.myList;
function rows(keys) { return keys.map(function (k) { return { key: k, visible: true }; }); }
function keys(rs) { return rs.map(function (r) { return r.key; }); }
eq('home: recent weg, streaming op die plek', keys(HR.displayed(rows(['continue', 'mylist', 'top10', 'foryou', 'recent', 'upcoming', 'streaming', 'live']))),
  ['continue', 'mylist', 'top10', 'foryou', 'streaming', 'upcoming', 'live']);
eq('home: zichtbaarheid van Nieuw op Bingr blijft', HR.displayed([{ key: 'streaming', visible: false }, { key: 'continue', visible: true }, { key: 'recent', visible: true }, { key: 'live', visible: true }]),
  [{ key: 'continue', visible: true }, { key: 'streaming', visible: false }, { key: 'live', visible: true }]);
eq('home: zonder recent ongewijzigd', keys(HR.displayed(rows(['streaming', 'live']))), ['streaming', 'live']);
eq('home: zonder streaming alleen recent weg', keys(HR.displayed(rows(['recent', 'live']))), ['live']);
eq('lijst: TMDB-titel op een andere server', ML.externalID('SERVER-B|tmdb-movie-603'), 'tmdb-movie-603');
eq('lijst: gewone titel is geen TMDB', ML.externalID('srv|xv123'), null);
eq('lijst: bestaande TMDB-sleutel hergebruiken', ML.keyFor(['OTHER|tmdb-movie-1', 'a|xv1'], 'active', 'tmdb-movie-1'), 'OTHER|tmdb-movie-1');
eq('lijst: nieuwe sleutel op de actieve server', ML.keyFor(['a|xv1'], 'active', 'tmdb-movie-2'), 'active|tmdb-movie-2');
eq('lijst: gewone titel altijd met zijn server', ML.keyFor(['other|xv1'], 'active', 'xv1'), 'active|xv1');
eq('verder: poster gaat voor still', X.continueArt('poster.jpg', null, 'still.jpg'), 'poster.jpg');
eq('verder: meta-poster (serie)', X.continueArt(null, 'series.jpg', 'still.jpg'), 'series.jpg');
eq('verder: anders de bewaarde afbeelding', X.continueArt(null, null, 'still.jpg'), 'still.jpg');
eq('verder: lege poster telt niet', X.continueArt(' ', '', 'still.jpg'), 'still.jpg');

// MARK: kluis
var SESSION = { access_token: 'AT-geheim', refresh_token: 'RT-geheim', expires_at: 1, email: 'x@example.invalid' };
var SOURCES = [{ id: 'S1', kind: 'xtream', username: 'u', password: 'wachtwoord123' }];

function vaultTests() {
  // 1. zonder IndexedDB: alles blijft in localStorage (zoals vroeger)
  var ls1 = fakeLocalStorage({ 'bingr.auth': JSON.stringify(SESSION) });
  var B1 = boot({ localStorage: ls1 });
  return B1.vault.ready.then(function (m) {
    eq('kluis zonder IDB: terugval', m, 'ls');
    eq('kluis zonder IDB: sessie leesbaar', B1.store.get('auth', null), SESSION);
    B1.store.set('pins', { p1: '1234' });
    eq('kluis zonder IDB: in localStorage', ls1.getItem('bingr.pins'), JSON.stringify({ p1: '1234' }));

    // 2. met IndexedDB en Web Crypto: overzetten, versleuteld, localStorage leeg
    var disk = {};
    var ls2 = fakeLocalStorage({ 'bingr.auth': JSON.stringify(SESSION), 'bingr.sources': JSON.stringify(SOURCES),
      'bingr.pins': JSON.stringify({ p1: '4321' }), 'bingr.lastProfile': JSON.stringify('p1') });
    var B2 = boot({ localStorage: ls2, indexedDB: fakeIndexedDB(disk), crypto: nodeCrypto.webcrypto });
    var reloaded = [];
    B2.vault.onLoad(function () { reloaded.push(B2.store.get('auth', null)); });
    eq('kluis: vóór het laden nog niets', B2.store.get('auth', null), null);
    return B2.vault.ready.then(function (m2) {
      eq('kluis: IndexedDB', m2, 'idb');
      eq('kluis: versleuteld', B2.vault.encrypted(), true);
      eq('kluis: onLoad-haak', reloaded, [SESSION]);
      eq('kluis: sessie', B2.store.get('auth', null), SESSION);
      eq('kluis: servers', B2.store.get('sources', null), SOURCES);
      eq('kluis: uit localStorage (auth)', ls2.getItem('bingr.auth'), null);
      eq('kluis: uit localStorage (sources)', ls2.getItem('bingr.sources'), null);
      eq('kluis: uit localStorage (pins)', ls2.getItem('bingr.pins'), null);
      eq('kluis: gewone instelling blijft', ls2.getItem('bingr.lastProfile'), JSON.stringify('p1'));
      var raw = JSON.stringify(disk['bingr-vault'].kv, function (k, v) {
        if (v instanceof ArrayBuffer) return Buffer.from(v).toString('latin1');
        if (ArrayBuffer.isView(v)) return Buffer.from(v.buffer, v.byteOffset, v.byteLength).toString('latin1');
        return v;
      });
      ok('kluis: geen platte tekst (token)', raw.indexOf('RT-geheim') < 0);
      ok('kluis: geen platte tekst (wachtwoord)', raw.indexOf('wachtwoord123') < 0);
      ok('kluis: geen platte tekst (pincode)', raw.indexOf('4321') < 0);
      B2.store.set('auth', { access_token: 'AT2', refresh_token: 'RT2' });
      B2.store.del('pins');
      eq('kluis: niets terug in localStorage', ls2.getItem('bingr.auth'), null);
      return new Promise(function (r) { setTimeout(r, 50); }).then(function () {
        // 3. "herstart": dezelfde IndexedDB, lege localStorage
        var B3 = boot({ localStorage: ls2, indexedDB: fakeIndexedDB(disk), crypto: nodeCrypto.webcrypto });
        return B3.vault.ready.then(function (m3) {
          eq('herstart: IndexedDB', m3, 'idb');
          eq('herstart: nieuwe sessie terug', B3.store.get('auth', null), { access_token: 'AT2', refresh_token: 'RT2' });
          eq('herstart: verwijderd blijft weg', B3.store.get('pins', null), null);
          eq('herstart: servers', B3.store.get('sources', null), SOURCES);
        });
      });
    });
  });
}

vaultTests().then(function () {
  console.log(failures === 0 ? 'Build 141 (Samsung TV): alle ' + checks + ' controles geslaagd' : 'Build 141 (Samsung TV): ' + failures + ' van ' + checks + ' controles mislukt');
  process.exit(failures === 0 ? 0 : 1);
}, function (e) {
  console.log('FOUT kluis: ' + (e && e.stack || e));
  process.exit(1);
});
