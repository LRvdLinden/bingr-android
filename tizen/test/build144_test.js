#!/usr/bin/env node
// Build 144: controles van js/build144.js (Verder kijken: welke poster; automatisch verversen pas na het openen), gelijk
// aan tools/test/Build144LogicTest.kt op Android. Draait zonder tv: node tizen/test/build144_test.js
// (de workflow Build doet dit; een fout laat de build mislukken).
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

var noop = function () {};
function el() { return { style: {}, classList: { add: noop, remove: noop, toggle: noop, contains: function () { return false; } }, setAttribute: noop, appendChild: noop, addEventListener: noop }; }
var doc = { addEventListener: noop, createElement: el, body: el(), documentElement: el(), querySelector: function () { return null; }, hidden: false };
var win = { document: doc, navigator: { userAgent: 'node', language: 'nl' }, addEventListener: noop, location: { href: 'file:///' },
  setTimeout: function (fn) { fn(); return 0; }, clearTimeout: noop, setInterval: function () { return 0; }, clearInterval: noop, console: console, Promise: Promise };
win.window = win;
var ctx = vm.createContext(win);
function load(f) { vm.runInContext(fs.readFileSync(path.join(JS, f), 'utf8'), ctx, { filename: f }); }
load('core.js');
var B = win.B;
var disk = {};
B.idb = { get: function (k) { return Promise.resolve(disk[k] == null ? null : JSON.parse(JSON.stringify(disk[k]))); },
  set: function (k, v) { disk[k] = JSON.parse(JSON.stringify(v)); return Promise.resolve(); }, del: function (k) { delete disk[k]; return Promise.resolve(); } };
load('build144.js');
var C = B.b144.cover, A = B.b144.auto;

// MARK: sleutel (gelijk aan Android en Apple)
eq('sleutel: film met jaar uit de titel', C.key('NL - The Batman (2022) [4K]', null, false), 'm|the batman|2022');
eq('sleutel: jaar uit de bibliotheek gaat voor', C.key('The Batman (2022)', 2021, false), 'm|the batman|2021');
eq('sleutel: serie zonder jaar', C.key('Breaking Bad', null, true), 's|breaking bad|0');
eq('sleutel: film en serie verschillen', C.key('Dune', 2021, false) === C.key('Dune', 2021, true), false);

// MARK: volgorde: bibliotheek/catalogmeta → TMDB → bewaard beeld
var LIB = 'https://srv/poster.jpg', META = 'https://srv/meta.jpg', TMDB = 'https://image.tmdb.org/t/p/w342/x.jpg', STILL = 'https://srv/still.jpg';
eq('volgorde: bibliotheek eerst', C.pick(LIB, META, TMDB, STILL), LIB);
eq('volgorde: dan catalogmeta', C.pick(null, META, TMDB, STILL), META);
eq('volgorde: dan TMDB', C.pick(null, null, TMDB, STILL), TMDB);
eq('volgorde: pas dan het bewaarde beeld', C.pick(null, null, null, STILL), STILL);
eq('volgorde: niets', C.pick(null, null, null, null), null);
eq('volgorde: lege teksten tellen niet', C.pick('  ', '', '', STILL), STILL);
eq('volgorde: meta = bewaard beeld is geen poster (TMDB gaat voor)', C.pick(null, STILL, TMDB, STILL), TMDB);
eq('volgorde: meta = bewaard beeld, zonder TMDB het beeld', C.pick(null, STILL, null, STILL), STILL);
eq('opzoeken: niet met bibliotheekposter', C.needsLookup(LIB, null, STILL), false);
eq('opzoeken: niet met echte catalogmeta', C.needsLookup(null, META, STILL), false);
eq('opzoeken: wel zonder poster', C.needsLookup(null, null, STILL), true);
eq('opzoeken: wel als meta het bewaarde beeld is', C.needsLookup(null, STILL, STILL), true);

// MARK: wat wordt opgezocht
var wants = [{ title: 'Dune (2021)', year: null, isShow: false }, { title: 'Dune', year: 2021, isShow: false },
  { title: 'Breaking Bad', year: null, isShow: true }, { title: '  ', year: null, isShow: false }, { title: 'Known', year: 1999, isShow: false }];
var known = {}; known[C.key('Known', 1999, false)] = true;
var inFlight = {}; inFlight[C.key('Breaking Bad', null, true)] = true;
eq('pending: dubbel één keer, bekend en onderweg niet, lege titel niet',
  C.pending(wants, function (k) { return !!known[k]; }, inFlight).map(function (t) { return t.key; }), ['m|dune|2021']);
var many = []; for (var i = 0; i < 30; i++) many.push({ title: 'Film ' + i, year: 2000, isShow: false });
eq('pending: hooguit 12', C.pending(many, function () { return false; }, {}).length, 12);
eq('query: schone titel en jaar', C.query({ title: 'NL - Dune (2021) HD', year: null, isShow: false }), { q: 'Dune', year: 2021 });

// MARK: automatisch verversen pas na het openen
eq('auto: niet bij het openen', A.mayStart(0, true, false), false);
eq('auto: niet na 44 s', A.mayStart(44000, true, false), false);
eq('auto: na 45 s', A.mayStart(45000, true, false), true);
eq('auto: niet in profielkeuze/pincodescherm', A.mayStart(120000, false, false), false);
eq('auto: niet tijdens afspelen', A.mayStart(120000, true, true), false);
eq('auto: wachten tot 45 s', A.waitMs(10000), 35000);
eq('auto: daarna elke 5 s kijken', A.waitMs(90000), 5000);

// MARK: TMDB-posters opzoeken en bewaren
var calls = [], fail = false, events = 0;
B.on('covers', function () { events++; });
B.ageRatings = {
  hasKey: function () { return true; }, tmdbLang: function () { return 'nl-NL'; },
  tmdb: function (p, params) {
    calls.push(p + '?' + params.map(function (x) { return x[0] + '=' + x[1]; }).join('&'));
    if (fail) return Promise.resolve(null);
    var q = params[0][1];
    if (q === 'Nothing') return Promise.resolve({ results: [{ id: 1, poster_path: null }] });
    return Promise.resolve({ results: [{ id: 1, poster_path: null }, { id: 2, poster_path: '/' + q.replace(/ /g, '') + '.jpg' }] });
  }
};
var W = [{ title: 'Dune', year: 2021, isShow: false }, { title: 'Breaking Bad', year: null, isShow: true }, { title: 'Nothing', year: null, isShow: false }];
B.b144.covers.resolve(W).then(function (changed) {
  eq('tmdb: iets gevonden', changed, true);
  eq('tmdb: film met jaar, serie zonder', calls, ['/search/movie?query=Dune&language=nl-NL&include_adult=false&year=2021',
    '/search/tv?query=Breaking Bad&language=nl-NL&include_adult=false', '/search/movie?query=Nothing&language=nl-NL&include_adult=false']);
  eq('tmdb: eerste resultaat met poster', B.b144.covers.cover('Dune', 2021, false), 'https://image.tmdb.org/t/p/w342/Dune.jpg');
  eq('tmdb: serie', B.b144.covers.cover('Breaking Bad', null, true), 'https://image.tmdb.org/t/p/w342/BreakingBad.jpg');
  eq('tmdb: niets gevonden = geen poster', B.b144.covers.cover('Nothing', null, false), null);
  eq('tmdb: melding voor Home', events, 1);
  eq('tmdb: bewaard op de tv (ook "niets gevonden")', disk['continueCovers.v144'] && disk['continueCovers.v144']['m|nothing|0'], '');
  calls = [];
  return B.b144.covers.resolve(W);
}).then(function (changed) {
  eq('tmdb: niet opnieuw opgezocht', [changed, calls.length], [false, 0]);
  fail = true;
  return B.b144.covers.resolve([{ title: 'Offline', year: null, isShow: false }]);
}).then(function () {
  eq('tmdb: netwerkfout niet bewaard', disk['continueCovers.v144']['m|offline|0'], undefined);
  fail = false; calls = [];
  return B.b144.covers.resolve([{ title: 'Offline', year: null, isShow: false }]);
}).then(function () {
  eq('tmdb: na een netwerkfout later opnieuw', calls.length, 1);
  // "herstart": een nieuwe module leest wat bewaard is, zonder TMDB
  load('build144.js');
  calls = [];
  return B.b144.covers.resolve([{ title: 'Dune', year: 2021, isShow: false }]).then(function () {
    eq('herstart: bewaarde poster, geen nieuwe aanvraag', [B.b144.covers.cover('Dune', 2021, false), calls.length],
      ['https://image.tmdb.org/t/p/w342/Dune.jpg', 0]);
  });
}).then(function () {
  console.log(failures ? (checks + ' controles, ' + failures + ' fout') : ('Build 144 (Samsung TV): alle ' + checks + ' controles geslaagd'));
  process.exit(failures ? 1 : 0);
}, function (e) { console.log('FOUT ' + (e && e.stack || e)); process.exit(1); });
