#!/usr/bin/env node
// Build 140: controles van de Samsung TV-logica (js/build140.js en de section `account` / Mijn lijst in js/sync.js), gelijk
// aan tools/test/Build140LogicTest.kt op Android. Draait zonder tv: node tizen/test/build140_test.js
// (de workflow Build doet dit naast allservers_test.js; een fout laat de build mislukken).
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
function ok(name, cond, detail) { checks++; if (!cond) { failures++; console.log('FOUT ' + name + (detail ? ': ' + JSON.stringify(detail) : '')); } }

var noop = function () {};
function el() { return { style: {}, classList: { add: noop, remove: noop, toggle: noop, contains: function () { return false; } }, setAttribute: noop, appendChild: noop, addEventListener: noop }; }
var doc = { addEventListener: noop, createElement: el, body: el(), documentElement: el(), querySelector: function () { return null; } };
var win = { document: doc, navigator: { userAgent: 'node', language: 'nl' }, addEventListener: noop, location: { href: 'file:///' },
  setTimeout: function () { return 0; }, clearTimeout: noop, setInterval: function () { return 0; }, clearInterval: noop, console: console, Promise: Promise };
win.window = win;
var ctx = vm.createContext(win);
function load(f) { vm.runInContext(fs.readFileSync(path.join(JS, f), 'utf8'), ctx, { filename: f }); }
load('core.js');
var B = win.B;
load('build140.js');
var X = B.b140, M = X.marks;

// MARK: 2. kindercategorieën (zelfde gevallen als Android)
[['NL | Kinderfilms', true], ['|EN| Kids', true], ['[NL] Disney+', true], ['Cartoon Network', true], ['NL - Familiefilms', true],
  ['NL: Nick Jr', true], ['NL | Z@pp', true], ['BE | Ketnet', true], ['Kid Movies', true], ['Baby TV', true], ['US | Animation', true],
  ['NL | VOD | Kinderseries', true], ['JP | Anime', false], ['Kidnap Thrillers', false], ['Babylon Berlin', false], ['NL | Sport', false],
  ['Kids XXX', false], ['Family 18+', false], ['Cartoons for adults', false], ['NL | Erotiek', false], ['', false], ['FR | Animación', false]
].forEach(function (c) { eq('kids: ' + c[0], X.isKidsCategory(c[0]), c[1]); });
eq('kids: normaliseren', X.normCategory('NL | Kinderfilms'), 'kinderfilms');
eq('kids: leeftijd onbekend mag', X.kidsAgeAllowed(null, 12), true);
eq('kids: leeftijd boven grens niet', X.kidsAgeAllowed(16, 12), false);

// MARK: 1. account-brede categorieën
eq('cat: norm', M.norm('ABC-1|NL | Sport'), 'abc-1|NL | Sport');
eq('cat: hoofdletters samen', M.normalizeAll({ 'ABC|Sport': { at: 5, on: true }, 'abc|Sport': { at: 3, on: false } }), { 'abc|Sport': { at: 5, on: true } });
var merged = M.merge({ 'abc|Sport': { at: 10, on: true }, 'abc|News': { at: 10, on: true } }, { 'abc|Sport': { at: 20, on: false }, 'abc|Kids': { at: 15, on: true } });
eq('cat: merge', merged, { 'abc|Sport': { at: 20, on: false }, 'abc|News': { at: 10, on: true }, 'abc|Kids': { at: 15, on: true } });
eq('cat: alive', M.alive(merged), ['abc|Kids', 'abc|News']);
eq('cat: index', M.index(['ABC|News', 'abc|Kids', 'x']), { abc: { News: true, Kids: true } });
var s1 = M.set({ 'abc|Sport': { at: 1000, on: false } }, ['ABC|Sport'], true, 500);
eq('cat: set na latere verwijdering', s1['abc|Sport'], { at: 1001, on: true });
eq('cat: set uit', M.set(s1, ['abc|Sport'], false, 2000)['abc|Sport'], { at: 2000, on: false });
eq('cat: uit zonder sleutel', M.set({}, ['abc|X'], false, 1), {});
eq('cat: server weg', M.removeServer({ 'abc|A': { at: 1, on: true }, 'def|A': { at: 1, on: true } }, 'ABC', 50), { 'abc|A': { at: 50, on: false }, 'def|A': { at: 1, on: true } });
var mig = M.migrate({ 'abc|Sport': { at: 7, on: false } }, ['ABC|Sport', 'ABC|News', 'def|Kids', 'zonderbar']);
eq('cat: migratie', mig, { 'abc|Sport': { at: 7, on: false }, 'abc|News': { at: 0, on: true }, 'def|Kids': { at: 0, on: true } });
eq('cat: migratie verliest van keuze elders', M.merge(mig, { 'abc|News': { at: 1, on: false } })['abc|News'], { at: 1, on: false });
eq('cat: prune', M.prune({ 'a|x': { at: 0, on: false }, 'a|y': { at: 0, on: true } }, 100 * X.DAY), { 'a|y': { at: 0, on: true } });

// MARK: 8. tijden
eq('stamp: geen vorige', X.stampAfter(100, null), 100);
eq('stamp: vorige nieuwer (klok)', X.stampAfter(100, 500), 501);

// MARK: ouderpoort
var seed = 7;
function rand() { seed = (seed * 16807) % 2147483647; return seed / 2147483647; }
for (var i = 0; i < 200; i++) {
  var sm = X.gate.newSum(rand);
  if (sm.multiply) ok('som: × in bereik', sm.a >= 6 && sm.a <= 12 && sm.b >= 3 && sm.b <= 9, sm);
  else ok('som: + in bereik', sm.a >= 11 && sm.a <= 49 && sm.b >= 11 && sm.b <= 49, sm);
  ok('som: max 3 cijfers', String(sm.answer).length <= 3, sm);
}
var st = X.gate.state({ a: 7, b: 8, multiply: true, answer: 56, text: '7 × 8 = ?' }, rand);
eq('poort: te kort', st.check('5', false), 'wait');
eq('poort: goed', st.check('56', false), 'ok');
var st2 = X.gate.state({ a: 7, b: 8, multiply: true, answer: 56, text: '7 × 8 = ?' }, rand);
eq('poort: fout 1', st2.check('55', false), 'wrong');
eq('poort: fout 2', st2.check('54', false), 'wrong');
eq('poort: fout 3 = nieuwe som', st2.check('53', false), 'new');
eq('poort: pogingen opnieuw', st2.tries, 0);

// MARK: 3/4. Voor jou, Nieuw op Bingr, uitgelicht
var P = X.picks;
eq('genres', P.genres('Action, Sci-Fi / Drama'), ['action', 'sci-fi', 'drama']);
var w = P.genreWeights([['animation', 'family'], ['animation']]);
ok('genre-gewicht: recent zwaarder', w.animation > w.family, w);
var cands = [{ key: 'a', genres: ['animation', 'family'], rating: 7, addedAt: 1 }, { key: 'b', genres: ['animation'], rating: 8, addedAt: 1 },
  { key: 'c', genres: ['horror'], rating: 9, addedAt: 1 }, { key: 'd', genres: ['family'], rating: 6, addedAt: 2 },
  { key: 'e', genres: ['animation', 'family'], rating: 9, addedAt: 1 }];
eq('voor jou: genres', P.byGenres(cands, w, { e: true }, 10), ['a', 'b', 'd']);
eq('voor jou: zonder geschiedenis leeg', P.byGenres(cands, {}, {}, 10), []);
eq('eerst gezien: nieuwe server', P.firstSeen(null, ['x', 'y'], 99), { x: 0, y: 0 });
eq('eerst gezien: nieuwe id', P.firstSeen({ x: 0 }, ['x', 'z'], 99), { x: 0, z: 99 });
eq('eerst gezien: weg', P.firstSeen({ x: 0, q: 5 }, ['x'], 99), { x: 0 });
eq('datum: provider', P.addedDate(500, 900), 500);
eq('datum: eerst gezien', P.addedDate(null, 900), 900);
eq('datum: onbekend', P.addedDate(null, null), 0);
var id = function (x) { return x; };
eq('hero: om en om', P.heroPool(['a', 'b', 'c', 'd', 'e'], ['b', 'x', 'y', 'z', 'w', 'v'], id), ['a', 'b', 'x', 'c', 'y', 'd', 'z', 'e']);
eq('hero: start', [P.heroStart(0, 8), P.heroStart(9, 8), P.heroStart(5, 0)], [0, 1, 0]);
eq('hero: dag met tijdzone', P.dayNumber(X.DAY - 1000, 3600000), 1);

// MARK: 7. vernieuwen
var R = X.refresh;
eq('vernieuwen: handmatig', R.due('manual', 10 * X.DAY, false), false);
eq('vernieuwen: dagelijks', [R.due('daily', 25 * 3600000, true), R.due('daily', 2 * 3600000, false)], [true, false]);
eq('vernieuwen: zonder cache', R.due('weekly', null, true), true);
eq('vernieuwen: bij openen één keer', [R.due('launch', 1000, false), R.due('launch', 1000, true)], [true, false]);
var ages = { A: 30 * 3600000, B: 3600000, C: 48 * 3600000 };
eq('vernieuwen: alle servers, actieve eerst', R.dueServers(['A', 'B', 'C'], 'C', 'daily', function (x) { return ages[x]; }, {}), ['C', 'A']);
eq('vernieuwen: bij openen alle servers één keer', R.dueServers(['A', 'B'], 'B', 'launch', function (x) { return ages[x]; }, { a: true }), ['B']);

// MARK: sync.js: section `account` en Mijn lijst (met een nep-server)
var store = {};
B.store = { get: function (k, d) { return k in store ? JSON.parse(JSON.stringify(store[k])) : (d === undefined ? null : d); }, set: function (k, v) { store[k] = JSON.parse(JSON.stringify(v)); }, del: function (k) { delete store[k]; }, keys: function () { return []; } };
B.idb = { get: function () { return Promise.resolve(null); }, set: function () { return Promise.resolve(); }, del: function () { return Promise.resolve(); }, clear: noop };
var server = {};
B.account = {
  isSignedIn: function () { return true; }, userId: function () { return 'u1'; },
  getState: function (names) {
    var out = {};
    Object.keys(server).forEach(function (k) { if (!names || names.indexOf(k) >= 0) out[k] = { data: JSON.parse(JSON.stringify(server[k])), updatedAt: new Date().toISOString() }; });
    return Promise.resolve(out);
  },
  putState: function (n, d) { server[n] = JSON.parse(JSON.stringify(d)); return Promise.resolve(new Date().toISOString()); }
};
B.L = function (s) { return s; };
B.app = { profile: { id: 'p1', name: 'Léon' } };
load('sync.js');
var D = B.data;
D.sources = [{ id: 'SRV-A', name: 'Thuis' }];
D.sourceById = function (sid) { return D.sources.filter(function (s) { return s.id.toLowerCase() === String(sid).toLowerCase(); })[0] || null; };
D.customization = function () { return { hidden: [] }; };
D.profiles = [{ id: 'p1', name: 'Léon' }, { id: 'p2', name: 'Kind', kids: true }];

var chain = Promise.resolve();
function step(fn) { chain = chain.then(fn); }
step(function () {
  // migratie: per-profiel-set van het niet-kinderprofiel (iOS: hoofdletters) naar `account`, kinderprofiel telt niet
  var all = {
    'profile.p1': { data: { hiddenCategories: [{ id: 'SRV-A|NL | Sport', at: '2026-10-01T00:00:00Z' }] } },
    'profile.p2': { data: { hiddenCategories: [{ id: 'SRV-A|NL | Kids', at: '2026-10-01T00:00:00Z' }] } }
  };
  D._migrateAccount(all);
  eq('migratie: unie van niet-kinderprofielen, at 0', D.accountMarks(), { 'srv-a|NL | Sport': { at: 0, on: true } });
  return D._mergeAccount(undefined);
});
step(function () {
  eq('account: leeg op de server → geüpload', server.account && server.account.hiddenCategories.map(function (x) { return x.id; }), ['srv-a|NL | Sport']);
  eq('account: verborgen voor gewoon profiel', D.hiddenCategoryNames('SRV-A'), { 'NL | Sport': true });
  B.app.profile = D.profiles[1];
  eq('account: kinderprofiel: geen account-set', D.hiddenCategoryNames('SRV-A'), {});
  B.app.profile = D.profiles[0];
  // een ander apparaat zet "NL | Sport" weer aan (nieuwer) en verbergt "NL | News"; onbekende velden blijven
  server.account.removed.hiddenCategories = { 'srv-a|NL | Sport': '2026-10-20T10:00:00.000Z' };
  server.account.hiddenCategories = [{ id: 'SRV-A|NL | News', at: '2026-10-20T10:00:00.000Z' }];
  server.account.other = { keep: 1 };
  return D._mergeAccount({ data: server.account });
});
step(function () {
  eq('account: keuzes van elders gewonnen', D.hiddenCategoryNames('SRV-A'), { 'NL | News': true });
  eq('account: onbekende velden blijven', server.account.other, { keep: 1 });
  // server verwijderd: zijn categorieën als verwijdering
  D.sources = [];
  D.sources = [{ id: 'SRV-B', name: 'Ander' }];
  return D._mergeAccount({ data: server.account });
});
step(function () {
  eq('account: verwijderde server → verwijdering', D.hiddenCategoryNames('SRV-A'), {});
  ok('account: verwijdering gesynct', server.account.removed.hiddenCategories['srv-a|NL | News'] != null, server.account.removed);
  // Mijn lijst: toevoegen na een verwijdering met een latere tijd (klok van een ander apparaat loopt voor)
  D.sources = [{ id: 'SRV-A', name: 'Thuis' }];
  D.loadProfileState('p1');
  var future = Date.now() + 3600000;
  server['profile.p1'] = { myList: [], removed: { myList: { 'SRV-A|m1': new Date(future).toISOString() } } };
  return D.syncState();
});
step(function () {
  D.toggleSet('myList', 'SRV-A|m1', true);
  eq('mijn lijst: toegevoegd', D.inSet('myList', 'SRV-A|m1'), true);
  return D.flush();
});
step(function () {
  eq('mijn lijst: na sync nog aanwezig', D.inSet('myList', 'SRV-A|m1'), true);
  var onServer = (server['profile.p1'].myList || []).map(function (x) { return x.id; });
  eq('mijn lijst: op de server aanwezig (tweede apparaat ziet het)', onServer, ['SRV-A|m1']);
  // tweede apparaat: leest de server opnieuw in een leeg profiel
  store = {};
  D.loadProfileState('p1');
  return D.syncState();
});
step(function () {
  eq('mijn lijst: tweede apparaat', D.inSet('myList', 'SRV-A|m1'), true);
});
chain.then(function () {
  console.log(failures ? 'MISLUKT: ' + failures + ' van ' + checks + ' controles' : 'OK: ' + checks + ' controles geslaagd');
  process.exit(failures ? 1 : 0);
}, function (e) { console.log('FOUT ' + (e && e.stack)); process.exit(1); });
