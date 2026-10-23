#!/usr/bin/env node
// Build 129: controles van de Samsung TV-logica voor "één lijst over alle servers" (js/allservers.js, js/extras.js),
// gelijk aan tools/test/AllServersLogicTest.kt op Android. Draait zonder tv: node tizen/test/allservers_test.js
// (de workflow Build doet dit naast check_compat.js; een fout laat de build mislukken).
'use strict';
var vm = require('vm');
var fs = require('fs');
var path = require('path');

var JS = path.resolve(__dirname, '..', 'js');
var checks = 0, failures = 0;
function eq(name, got, want) {
  checks++;
  var a = JSON.stringify(got), b = JSON.stringify(want);
  if (a !== b) { failures++; console.log('FOUT ' + name + ': kreeg <' + a + '>, verwacht <' + b + '>'); }
}

// MARK: een minimale "tv" (geen DOM nodig voor deze modules)
var noop = function () {};
function el() { return { style: {}, classList: { add: noop, remove: noop, toggle: noop, contains: function () { return false; } }, setAttribute: noop, appendChild: noop, addEventListener: noop }; }
var doc = { addEventListener: noop, createElement: el, body: el(), documentElement: el(), querySelector: function () { return null; } };
var win = { document: doc, navigator: { userAgent: 'node', language: 'nl' }, addEventListener: noop, location: { href: 'file:///' },
  setTimeout: setTimeout, clearTimeout: clearTimeout, setInterval: function () { return 0; }, clearInterval: noop, console: console, Promise: Promise };
win.window = win;
var ctx = vm.createContext(win);
function load(f) { vm.runInContext(fs.readFileSync(path.join(JS, f), 'utf8'), ctx, { filename: f }); }
load('core.js');
var B = win.B;

// stubs van de modules waar allservers.js en extras.js op leunen
var idb = {}, idbGets = {};
B.idb = { get: function (k) { idbGets[k] = (idbGets[k] || 0) + 1; return Promise.resolve(idb[k]); }, set: function (k, v) { idb[k] = v; return Promise.resolve(); }, del: function (k) { delete idb[k]; return Promise.resolve(); } };
var store = {};
B.store = { get: function (k, d) { return k in store ? store[k] : (d === undefined ? null : d); }, set: function (k, v) { store[k] = v; }, del: function (k) { delete store[k]; } };
B.L = function (s) { var a = arguments, i = 1; return String(s).replace(/%@|%d/g, function () { return String(a[i++]); }); };
var collections = [];
var D = B.data = {
  sources: [{ id: 'SRV-A', name: 'Thuis', kind: 'Xtream Codes' }, { id: 'srv-b', name: 'Reserve', kind: 'Xtream Codes' }, { id: 'SRV-C', name: 'Derde', kind: 'M3U / M3U8' }],
  sourceById: function (id) { return D.sources.filter(function (s) { return String(s.id).toLowerCase() === String(id).toLowerCase(); })[0] || null; },
  setList: function () { return []; }, progressList: function () { return []; }, history: function () { return []; },
  collectionsFor: function () { return collections; }, collections: null, isMain: function () { return true; }
};
var fetched = [];
B.lib = { source: D.sources[0], lib: { byId: {}, channels: [], movies: [], series: [] }, raw: { channels: [], movies: [], series: [] },
  stamps: {}, version: 1, loading: false,
  filterSig: function () { return 'f'; },
  // build 130: ophalen van het netwerk (hier altijd mislukt), om te tellen hoe vaak het gebeurt
  fetchOther: function (src) { fetched.push(src.id); return Promise.reject(new Error('offline')); },
  // filter van een andere server: alles wat in een groep "XXX" staat is verborgen (kinderslot/verborgen categorie)
  filterOne: function () {
    function f(x) { return x.group === 'XXX' ? null : B.assign({}, x); }
    return { channel: f, movie: f, series: f };
  } };
B.app = { profile: { id: 'p1', name: 'Léon' } };
B.player = { current: null };
B.account = { isSignedIn: function () { return false; } };
B.ui = { h: function () { return el(); }, toast: noop, modal: noop };
B.screens = {};
// build 144: Verder kijken (cover) en de wachttijd voor het netwerk; deze "start" ligt al een paar minuten achter ons
load('build144.js');
B.b144.t0 = Date.now() - 300000;
load('allservers.js');
var AS = B.allservers;
AS.START_GRACE = 0;   // in de test meteen (op de tv: eerst 10 s de actieve server)

// MARK: normaliseren en samenvoegen (zelfde gevallen als AllServersLogicTest.kt)
eq('titel: voorvoegsel, jaar, 4K', AS.normTitle('NL | Dune: Part Two (2024) 4K'), 'dune part two');
eq('zender: NL: + HD', AS.normChannel('NL: NPO 1 HD'), 'npo1');
eq('crossKey: jaar uit de titel', AS.crossKey('movie', 'Dune (2021)'), AS.crossKey('movie', 'NL: Dune', 2021));
eq('crossKey: film ≠ serie', AS.crossKey('movie', 'Fargo').base === AS.crossKey('series', 'Fargo').base, false);
eq('crossKey: TMDB gaat voor', AS.crossKey('movie', 'Anders', 2000, 603), { base: 'm:tmdb:603', year: null });
eq('crossKey: zender op naam', AS.crossKey('channel', 'NL: NPO 1 HD'), AS.crossKey('channel', '|NL| NPO 1 FHD'));
eq('crossKey: lege zender', AS.crossKey('channel', 'HD'), null);

function T(sid, title, year) { return { sid: sid, title: title, year: year == null ? null : year }; }
function tk(x) { return AS.crossKey('movie', x.title, x.year); }
var active = [T('A', 'Dune (2021)'), T('A', 'NL | Dune 2021 4K'), T('A', 'Arrival')];
var other1 = [T('B', 'Dune', 2021), T('B', 'Heat (1995)'), T('B', 'Heat (1995) HD')];
var other2 = [T('C', 'Arrival (2016)'), T('C', 'Heat', 1995), T('C', 'Tenet')];
var merged = AS.mergeAcross([active, other1, other2], tk);
eq('samenvoegen: actieve server helemaal, ook twee versies', merged.slice(0, 3), active);
eq('samenvoegen: dubbele van andere servers weg (onbekend jaar = titel)', merged.slice(3), [T('B', 'Heat (1995)'), T('C', 'Tenet')]);
eq('samenvoegen: jaar ±1 is hetzelfde, ±2 niet', AS.mergeAcross([[T('A', 'Heat', 1995)], [T('B', 'Heat', 1996), T('B', 'Heat', 1997)]], tk),
  [T('A', 'Heat', 1995), T('B', 'Heat', 1997)]);
eq('samenvoegen: limiet', AS.mergeAcross([active, other1], tk, 4).length, 4);
eq('samenvoegen: lege naam niet samenvoegen', AS.mergeAcross([[T('A', '4K')], [T('B', 'HD')]], tk).length, 2);
var chans = AS.mergeAcross([['NPO 1 HD', 'RTL 4'], ['NL: NPO 1 FHD', 'SBS 6'], ['RTL 4 50fps', 'Veronica']], function (n) { return AS.crossKey('channel', n); });
eq('zenders over servers: één per zender', chans, ['NPO 1 HD', 'RTL 4', 'SBS 6', 'Veronica']);
eq('entryKey: zender', AS.entryKey({ type: 'channel', item: { name: 'NPO 1 HD' } }), { base: 'c:npo1', year: null });
eq('entryKey: rawTitle gaat voor', AS.entryKey({ type: 'movie', item: { title: 'Dune', rawTitle: 'NL | Dune (2021)' } }), { base: 'm:dune', year: 2021 });
eq('slim: zoekwoord', AS.smartHit('nl: marvel avengers', 'Films', ['marvel'], null), true);
eq('slim: één van de woorden', AS.smartHit('star wars', 'Films', ['marvel', 'star'], null), true);
eq('slim: andere categorie', AS.smartHit('marvel', 'Kids', ['marvel'], 'Films'), false);
eq('slim: leeg woord telt niet', AS.smartHit('dune', 'Films', [''], null), false);

// MARK: slimme collecties over alle servers (achtergrondronde)
idb['lib:srv-b'] = { at: 1, lib: {
  channels: [{ id: 'c1', name: 'Marvel TV', group: 'Live' }, { id: 'c2', name: 'Marvel XXX', group: 'XXX' }],
  movies: [{ id: 'm1', title: 'Marvel: Avengers', group: 'Films' }, { id: 'm2', title: 'Dune', group: 'Films' }],
  series: [{ id: 's1', title: 'Marvel Daredevil', group: 'Series' }] } };
idb['lib:SRV-C'] = { at: 1, lib: { channels: [], movies: [{ id: 'x9', title: 'Marvel Avengers', group: 'Kids' }], series: [] } };
var smartAll = { id: 'COLL-1', kind: 'smart', query: 'marvel', types: ['movie', 'channel'], updatedAt: '2026-10-01T00:00:00Z' };
var smartFilms = { id: 'coll-2', kind: 'smart', query: 'Marvel', types: [], group: 'Films', sourceID: 'SRV-C', updatedAt: '2026-10-01T00:00:00Z' };
var manual = { id: 'coll-3', kind: 'manual', items: [] };
collections = [smartAll, smartFilms, manual];

// MARK: build 130: niet lang "Bijwerken" na openen (achtergrond rustig, één keer, niet in een lus)
function wait(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
function build130() {
  var h = 3600000, t0 = 1700000000000;
  eq('ophalen: nooit geprobeerd', AS.mayFetch(null, t0), true);
  eq('ophalen: 5 uur geleden', AS.mayFetch(t0 - 5 * h, t0), false);
  eq('ophalen: 6 uur geleden', AS.mayFetch(t0 - 6 * h, t0), true);
  eq('ophalen: klok teruggezet', AS.mayFetch(t0 + h, t0), true);
  eq('rust: tijdens afspelen niet', AS.idleOK(true, false, 60000), false);
  eq('rust: actieve laadt nog', AS.idleOK(false, true, 60000), false);
  eq('rust: alles klaar', AS.idleOK(false, false, 60000), true);
  eq('vernieuwen: actieve eerst', AS.refreshOrder(['a', 'B', 'c'], 'b'), ['B', 'a', 'c']);
  eq('vernieuwen: dubbel één keer', AS.refreshOrder(['a', 'A', 'b'], 'x'), ['a', 'b']);
  eq('scan: volgorde maakt niet uit', AS.scanSig(['s|1', 's|2'], 5, 'f') === AS.scanSig(['s|2', 's|1'], 5, 'f'), true);
  eq('scan: nieuwe cache = opnieuw', AS.scanSig(['s|1'], 5, 'f') === AS.scanSig(['s|1'], 6, 'f'), false);

  // ronde: Mijn lijst met een titel van srv-b (cache) en van srv-d (geen cache, server onbereikbaar)
  collections = [];
  D.sources.push({ id: 'srv-d', name: 'Kapot', kind: 'Xtream Codes' });
  D.setList = function (f) { return f === 'myList' ? ['srv-b|m1', 'srv-d|q1'] : []; };
  idbGets = {};
  var timedOut = false;
  return AS.withTimeout(new Promise(function () {}), 20).then(function (v) {
    timedOut = v === null;
    eq('time-out: trage server geeft na de time-out op', timedOut, true);
    return AS.refresh();
  }).then(function () {
    eq('ronde: titel van de andere server gevonden', !!AS.item('srv-b|m1'), true);
    eq('ronde: cache van srv-b één keer gelezen', idbGets['lib:srv-b'], 1);
    eq('ronde: server zonder cache één keer geprobeerd', fetched, ['srv-d']);
    return AS.refresh();
  }).then(function () {
    eq('ronde 2: niets veranderd = cache niet opnieuw inlezen', idbGets['lib:srv-b'], 1);
    eq('ronde 2: kapotte server niet opnieuw (hooguit eens per 6 uur)', fetched, ['srv-d']);
    // nieuwe cache van srv-b (bijv. na vernieuwen) → wel opnieuw
    idb['lib:srv-b'].at = 2; B.lib.stamps['srv-b'] = 2;
    return AS.refresh();
  }).then(function () {
    eq('ronde 3: nieuwe cache = opnieuw inlezen', idbGets['lib:srv-b'], 2);
    // tijdens het afspelen: wachten
    B.player.current = { id: 'x' };
    idb['lib:srv-b'].at = 3; B.lib.stamps['srv-b'] = 3;
    var done = false;
    var p = AS.refresh().then(function () { done = true; });
    return wait(200).then(function () {
      eq('afspelen: achtergrond wacht', done, false);
      eq('afspelen: niets ingelezen', idbGets['lib:srv-b'], 2);
      B.player.current = null;
      return p;
    }).then(function () {
      eq('na afspelen: ronde loopt', done && idbGets['lib:srv-b'] === 3, true);
      // de actieve server laadt nog: ook wachten
      B.lib.loading = true;
      idb['lib:srv-b'].at = 4; B.lib.stamps['srv-b'] = 4;
      var done2 = false;
      var p2 = AS.refresh().then(function () { done2 = true; });
      return wait(200).then(function () {
        eq('actieve laadt: achtergrond wacht', done2, false);
        B.lib.loading = false;
        return p2;
      });
    });
  });
}

AS._smartRound().then(function () {
  var a = AS.smartFor(smartAll).map(function (e) { return e.type + ':' + e.item._src + '|' + e.item.id; });
  eq('slim: treffers van alle andere servers (filters van het profiel, actieve niet)', a, ['channel:srv-b|c1', 'movie:srv-b|m1', 'movie:SRV-C|x9']);
  eq('slim: sourceID beperkt tot die server, en de categorie telt', AS.smartFor(smartFilms).length, 0);
  eq('slim: handmatige collectie doet niet mee', AS.smartFor(manual), []);
  eq('slim: gewijzigde collectie = oude treffers niet meer gebruiken', AS.smartFor(B.assign({}, smartAll, { updatedAt: '2026-10-02T00:00:00Z' })), []);
  // samenvoegen met de eigen treffers zoals Lib.collectionItems: dezelfde film van een andere server één keer
  var local = [{ type: 'movie', item: { id: 'am1', title: 'Marvel Avengers (2012)' } }];
  var all = AS.mergeAcross([local, AS.smartFor(smartAll)], AS.entryKey).map(function (e) { return (e.item._src || 'actief') + '|' + e.item.id; });
  eq('slim: dubbele titel van een andere server weg', all, ['actief|am1', 'srv-b|c1']);
  eq('slim: versie omhoog na nieuwe treffers', AS.version > 0, true);

  // MARK: scherm Geschiedenis (js/extras.js)
  B.lang = 'nl';
  load('extras.js');
  var S = B.screens;
  eq('geschiedenis: scherm bestaat', typeof S.historyList, 'function');
  eq('geschiedenis: openen bestaat', typeof S.openHistory, 'function');
  var now = new Date(2026, 9, 18, 21, 0).getTime();
  var fmt = B.fmtTime;
  eq('geschiedenis: vandaag', S.historyWhen(new Date(2026, 9, 18, 20, 15).getTime(), now), 'Today  ' + fmt(new Date(2026, 9, 18, 20, 15).getTime()));
  eq('geschiedenis: gisteren', S.historyWhen(new Date(2026, 9, 17, 23, 59).getTime(), now).indexOf('Yesterday'), 0);
  eq('geschiedenis: eerder = datum', /Today|Yesterday/.test(S.historyWhen(new Date(2026, 9, 10, 9, 0).getTime(), now)), false);

  // teksten van het nieuwe scherm hebben een Nederlandse vertaling (strings.js)
  var strings = fs.readFileSync(path.join(JS, 'strings.js'), 'utf8');
  var nl = JSON.parse(strings.slice(strings.indexOf('{'), strings.lastIndexOf('}') + 1)).nl;
  ['Watch history', 'Nothing watched yet on this profile.', 'Remove from history', 'Clear watch history', 'Today', 'Yesterday',
    'Open', 'Play', 'This title is no longer available.'].forEach(function (k) { eq('vertaling: ' + k, !!nl[k], true); });
  eq('vertaling: Geschiedenis', nl['Watch history'], 'Kijkgeschiedenis');

  return build130();
}).then(function () {
  console.log(checks + ' controles, ' + failures + ' fout');
  process.exit(failures ? 1 : 0);
}).catch(function (e) { console.log('FOUT ' + (e && e.stack || e)); process.exit(1); });
