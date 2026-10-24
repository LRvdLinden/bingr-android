#!/usr/bin/env node
// Build 147: controles van js/build147.js (eerlijke starttijden, goed/traag/vastgelopen, detector voor lange taken, vorige
// start, ophalen per soort, opslag, soort verbinding, werk in stukken), gelijk aan tools/test/Build147LogicTest.kt op
// Android, plus een catalogus van 10.000 films en 1.000 zenders: na "klaar" zit de hoofdthread nooit langer dan 250 ms vast
// (B.chunked en de PBKDF2-terugval in stukjes), en de tegenproef: 2,5 s werk in één keer ziet de detector als hang.
// Draait zonder tv: node tizen/test/build147_test.js (de workflow Build doet dit; een fout laat de build mislukken).
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
function ok(name, v) { eq(name, !!v, true); }

var noop = function () {};
function el() { return { style: {}, classList: { add: noop, remove: noop, toggle: noop, contains: function () { return false; } }, setAttribute: noop, appendChild: noop, addEventListener: noop }; }
var doc = { addEventListener: noop, createElement: el, body: el(), documentElement: el(), querySelector: function () { return null; }, hidden: false };
var mem = {};
var ls = { getItem: function (k) { return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null; }, setItem: function (k, v) { mem[k] = String(v); }, removeItem: function (k) { delete mem[k]; } };
// setTimeout gemeten: hoe lang duurt elk synchroon stuk werk dat via een timer loopt?
var longestSlice = 0;
function timed(fn) { return function () { var t0 = Date.now(); try { fn.apply(null, arguments); } finally { longestSlice = Math.max(longestSlice, Date.now() - t0); } }; }
var win = { document: doc, navigator: { userAgent: 'node', language: 'nl' }, addEventListener: noop, location: { href: 'file:///' },
  setTimeout: function (fn, ms) { return setTimeout(timed(fn), ms); }, clearTimeout: clearTimeout, setInterval: function () { return 0; }, clearInterval: noop,
  console: console, Promise: Promise, localStorage: ls, atob: function (s) { return Buffer.from(s, 'base64').toString('latin1'); },
  btoa: function (s) { return Buffer.from(s, 'latin1').toString('base64'); }, Uint8Array: Uint8Array, Uint32Array: Uint32Array };
win.window = win;
var ctx = vm.createContext(win);
function load(f) { vm.runInContext(fs.readFileSync(path.join(JS, f), 'utf8'), ctx, { filename: f }); }
load('core.js');
var B = win.B;
B.lastKeyAt = 0;
load('build146.js');
load('build147.js');
var X = B.b147, S = X.start;

// MARK: platformlaag (een andere tv-app overschrijft alleen B.platform)
eq('platform: Samsung', [B.platform.id, B.platform.name, B.platform.maker, B.platform.os], ['tizen', 'Samsung TV', 'Samsung', 'Tizen']);
eq('platform: config volgt', B.config.platform, 'tizen');
ok('platform: teksten', B.platform.texts.unsupportedServer.indexOf('Samsung TV') >= 0 && !!B.platform.texts.multiviewOnePlayer);
eq('platform: model buiten de tv', B.platform.model(), '');
ok('platform: exit is een functie', typeof B.platform.exit === 'function');

// MARK: eerlijke starttijden (de mijlpalen van ticket #1.030)
function marks(list) { return list.map(function (m) { return { name: m[0], ms: m[1] }; }); }
var t = S.timing(marks([['start', 0], ['account', 200], ['first_frame', 300], ['splash', 4100], ['profiles', 4100],
  ['profile_picked', 6800], ['pin', 6900], ['pin_ok', 12000], ['ready', 14400]]));
eq('timing: totaal = ready', t.totalMs, 14400);
eq('timing: wachten = profielkeuze + pincode', t.userWaitMs, (6800 - 4100) + (12000 - 6900));
eq('timing: app-tijd = rest', t.appMs, 14400 - 7800);
eq('timing: klaar', t.finished, true);
var noPin = S.timing(marks([['start', 0], ['profiles', 1000], ['profile_picked', 3000], ['ready', 4000]]));
eq('timing zonder pincode: wachten', noPin.userWaitMs, 2000);
eq('timing zonder pincode: app', noPin.appMs, 2000);
var waiting = S.timing(marks([['start', 0], ['profiles', 1000], ['home', 1500]]));
eq('timing: wacht nog op de gebruiker (geen ready)', waiting.userWaitMs, 500);
eq('timing: niet klaar', waiting.finished, false);
eq('timing: leeg', S.timing([]).totalMs, 0);
eq('timing: dubbele naam telt één keer', S.timing(marks([['profiles', 100], ['profiles', 900], ['profile_picked', 1100], ['ready', 1200]])).userWaitMs, 1000);
eq('timing: eind vóór begin = 0', S.timing(marks([['pin', 900], ['pin_ok', 800], ['ready', 1000]])).userWaitMs, 0);

// MARK: samenvatting
eq('samenvatting goed', S.summary(4000, 0, false), 'good');
eq('samenvatting traag (app-tijd)', S.summary(5001, 0, false), 'slow');
eq('samenvatting goed op 5 s', S.summary(5000, 2000, false), 'good');
eq('samenvatting traag (hang > 2 s)', S.summary(1000, 2001, false), 'slow');
eq('samenvatting vastgelopen (hang > 10 s)', S.summary(1000, 10001, false), 'hung');
eq('samenvatting vastgelopen (ticket: 80,1 s)', S.summary(t.appMs, 80100, false), 'hung');
eq('samenvatting vastgelopen (crash)', S.summary(100, 0, true), 'hung');
eq('label', S.label('slow'), 'Start: slow');

// MARK: detector voor lange taken (gat in de tik)
(function () {
  var d = new X.hang.Detector(2000, 250);
  eq('detector: eerste tik', d.tick(1000, { foreground: true }), null);
  eq('detector: normale tik', d.tick(1250, { foreground: true }), null);
  eq('detector: gat van 2 s (net niet)', d.tick(1250 + 250 + 1999, { foreground: true }), null);
  var e = d.tick(3499 + 250 + 2000, { foreground: true, task: 'catalogus sorteren', afterReady: true, milestone: 'ready', trail: ['taak indexeren', 'taak catalogus sorteren'] });
  eq('detector: hang na 2 s', e && e.ms, 2000);
  eq('detector: taak', e && e.task, 'catalogus sorteren');
  eq('detector: laatste taken als stack', e && e.stack, ['taak indexeren', 'taak catalogus sorteren']);
  eq('detector: na klaar', e && e.afterReady, true);
  d.tick(5749 + 250 + 80100, { foreground: true, task: 'x' });
  eq('detector: langste op de voorgrond', d.maxForegroundMs, 80100);
  eq('detector: teller', d.count, 2);
  // achtergrond telt niet
  var bg = new X.hang.Detector(2000, 250);
  bg.tick(0, { foreground: true });
  bg.reset(100);
  bg.tick(30000, { foreground: true });
  eq('detector: na achtergrond telt het gat niet als voorgrond', bg.maxForegroundMs, 0);
  eq('detector: wel vastgelegd', bg.worst && bg.worst.foreground, false);
  var bg2 = new X.hang.Detector(2000, 250);
  bg2.tick(0, { foreground: false });
  bg2.tick(30000, { foreground: true });
  eq('detector: verborgen vóór het gat = achtergrond', bg2.maxForegroundMs, 0);
  eq('samenvatting met achtergrondhang', S.summary(1000, bg2.maxForegroundMs, false), 'good');
})();

// MARK: vorige start
var crash = X.previous({ marks: [{ name: 'home', ms: 5000 }], crash: { stack: ['at x'] }, finished: false });
eq('vorige: crash', crash.problem, 'crash');
var hung = X.previous({ marks: [{ name: 'ready', ms: 14400 }], finished: true, hangMs: 80100, hangFg: true, hangAt: 'ready', hangTask: 'catalogus sorteren', hangStack: ['at y'] });
eq('vorige: hang', hung.problem, 'hang');
eq('vorige: hang seconden', hung.seconds, 80.1);
eq('vorige: hang taak', hung.task, 'catalogus sorteren');
eq('vorige: hang stack', hung.stack, ['at y']);
eq('vorige: hang op de achtergrond = geen probleem', X.previous({ marks: [{ name: 'ready', ms: 1000 }], finished: true, hangMs: 30000, hangFg: false }).problem, 'none');
eq('vorige: korte hang (≤ 2 s) = geen probleem', X.previous({ marks: [{ name: 'ready', ms: 1000 }], finished: true, hangMs: 2000, hangFg: true }).problem, 'none');
eq('vorige: niet klaar', X.previous({ marks: [{ name: 'home', ms: 9000 }], finished: false }).problem, 'not_finished');
eq('vorige: goed', X.previous({ marks: [{ name: 'ready', ms: 3000 }], finished: true }).problem, 'none');
eq('vorige: geen', X.previous(null), null);

// MARK: ophalen per server en soort
var F = X.fetch;
eq('fetch: geleverd', F.status(10259, 10259, null), 'ok');
eq('fetch: provider leverde niets (ticket: 0 series)', F.status(0, 0, null), 'empty');
eq('fetch: mislukt', F.status(0, null, 'timeout'), 'failed');
eq('fetch: nooit opgehaald = leeg', F.status(null, null, null), 'empty');
eq('fetch: alles verborgen', F.status(500, 0, null), 'hidden');
eq('fetch: geleverd ondanks oude fout', F.status(5, 5, 'timeout'), 'ok');
eq('foutcode: timeout', F.errorCode('Error', 'timeout'), 'timeout');
eq('foutcode: http (NetError)', F.errorCode('Error', 'http_502'), 'http_502');
eq('foutcode: http', F.errorCode('Error', 'x', 502), 'http_502');
eq('foutcode: http uit de tekst', F.errorCode('IOException', 'HTTP 404 Not Found'), 'http_404');
eq('foutcode: json (NetError)', F.errorCode('Error', 'bad_json'), 'parse');
eq('foutcode: netwerk', F.errorCode('Error', 'network'), 'network');
eq('foutcode: nooit een URL', F.errorCode('Exception', 'http://user:pass@host.example/x?password=1').indexOf('http') >= 0, false);
eq('foutcode: onbekend', F.errorCode('Error', 'x'), 'error');
eq('server anoniem', F.serverName(0), 's1');
eq('soorten', F.KINDS, ['live', 'movie', 'series']);

// MARK: opslag en verbinding
var st = X.storage(156 * 1048576, 132 * 1048576 + 400000, 0, 18000 * 1048576);
eq('opslag: totaal = som', st.totalMB, st.cacheMB + st.catalogMB + st.downloadsMB);
eq('opslag: delen', [st.cacheMB, st.catalogMB, st.downloadsMB, st.freeMB], [156, 132, 0, 18000]);
var st2 = X.storage(700000, 700000, 700000, null);
eq('opslag: afronden per deel', st2.totalMB, 3);
eq('opslag: vrij onbekend', st2.freeMB, null);
eq('net: ethernet', X.netType('ETHERNET'), 'ethernet');
eq('net: wifi', X.netType('WIFI'), 'wifi');
eq('net: mobiel', X.netType('4G'), 'cellular');
eq('net: geen', X.netType('NONE'), 'none');
eq('net: onbekend', X.netType(''), 'unknown');
eq('net: label', X.netLabel('wifi'), 'Wi-Fi');

// MARK: werk in stukken (nepklok)
(function () {
  var clock = 0, list = [], sum = 0, i = 0, slices = 0;
  for (var k = 1; k <= 10000; k++) list.push(k);
  while (i < list.length) { i = X.slices.step(list, i, function (v) { sum += v; clock += 1; }, 12, function () { return clock; }); slices++; }
  eq('stukken: alles verwerkt', sum, 50005000);
  ok('stukken: in meerdere stukken', slices > 100);
})();

// MARK: 10.000 films en 1.000 zenders, en de PBKDF2-terugval: elk stuk ≤ 250 ms
var seed = 147;
function rnd() { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; }
var movies = [], channels = [];
for (var i = 0; i < 10000; i++) movies.push({ id: 'xv' + i, title: 'Titel ' + Math.floor(rnd() * 1e6) + ' ' + (i % 97), group: 'Groep ' + Math.floor(rnd() * 60), addedAt: 1.7e12 + Math.floor(rnd() * 1e9), rating: rnd() * 10 });
for (var j = 0; j < 1000; j++) channels.push({ id: 'x' + j, name: 'Zender ' + j, group: 'Groep ' + (j % 20), number: Math.floor(rnd() * 2000) });
var hidden = { 'Groep 3': true, 'Groep 7': true };

function heavyEach(m, out) {
  // per titel wat de app doet: filter, titel normaliseren, groep en index
  if (hidden[m.group]) return;
  var key = String(m.title).toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
  out.push({ k: key, m: m });
}

var tests = [];
tests.push(function () {
  longestSlice = 0;
  var out = [], outC = [];
  // ook het eerste stuk (synchroon bij het starten) meten
  var first;
  timed(function () { first = B.chunked(movies, function (m) { heavyEach(m, out); }); })();
  ok('10k: in meerdere stukken (eerste stuk ' + longestSlice + ' ms)', longestSlice <= 250);
  return first.then(function () {
    return B.chunked(channels, function (c) { if (!hidden[c.group]) outC.push(c); });
  }).then(function () {
    // sorteren en indexeren in stukken van de gefilterde lijst (geen sort over alles in één keer nodig voor de rijen)
    var byId = {};
    return B.chunked(out, function (e) { byId[e.m.id] = e.m; }).then(function () {
      var want = movies.filter(function (m) { return !hidden[m.group]; }).length;
      eq('10k: films gefilterd', out.length, want);
      eq('10k: index', Object.keys(byId).length, want);
      eq('10k: zenders gefilterd', outC.length, channels.filter(function (c) { return !hidden[c.group]; }).length);
      console.log('10k films + 1k zenders in stukken: langste stuk ' + longestSlice + ' ms');
      ok('10k: langste stuk ' + longestSlice + ' ms ≤ 250 ms', longestSlice <= 250);
    });
  });
});
tests.push(function () {
  // pincode hashen zonder WebCrypto (oude tv): PBKDF2 100.000 iteraties in stukjes van ~12 ms
  longestSlice = 0;
  var P = B.b146.pin;
  var t0 = Date.now();
  return P.pbkdf2Chunked('1234', B.b146.b64.decode('AAECAwQFBgcICQoLDA0ODw=='), P.ITER).then(function (h) {
    eq('pbkdf2 in stukjes: zelfde uitkomst (testvector)', B.b146.b64.encode(h), 'hp5sg1DFvrCsw5n7qsO2DSIEM4lrJqZHc00NjxWG4fo=');
    console.log('PBKDF2 in stukjes: ' + (Date.now() - t0) + ' ms, langste stuk ' + longestSlice + ' ms');
    ok('pbkdf2 in stukjes: langste stuk ' + longestSlice + ' ms ≤ 250 ms', longestSlice <= 250);
  });
});
tests.push(function () {
  // tegenproef met echte tijd: 2,5 s werk in één keer → de detector (tik 250 ms) ziet een hang met de taak
  return new Promise(function (resolve) {
    var d = new X.hang.Detector(), task = null, seen = null;
    var iv = setInterval(function () { var e = d.tick(Date.now(), { foreground: true, task: task, trail: task ? ['taak ' + task] : [] }); if (e) seen = e; }, 250);
    setTimeout(function () {
      task = 'catalogus sorteren';
      var until = Date.now() + 2500, n = 0;
      while (Date.now() < until) { movies.slice().sort(function (a, b) { return a.title < b.title ? -1 : a.title > b.title ? 1 : 0; }); n++; }
      setTimeout(function () {
        task = null;
        setTimeout(function () {
          clearInterval(iv);
          ok('tegenproef: hang gezien (' + (seen && seen.ms) + ' ms)', seen && seen.ms >= 2000);
          eq('tegenproef: taak', seen && seen.task, 'catalogus sorteren');
          eq('tegenproef: samenvatting traag', S.summary(1000, d.maxForegroundMs, false), 'slow');
          resolve();
        }, 400);
      }, 0);
    }, 600);
  });
});

tests.reduce(function (p, f) { return p.then(f); }, Promise.resolve()).then(function () {
  console.log(failures === 0 ? 'Build 147 (Samsung TV): alle ' + checks + ' controles geslaagd' : 'Build 147 (Samsung TV): ' + failures + ' van ' + checks + ' controles mislukt');
  process.exit(failures === 0 ? 0 : 1);
}, function (e) { console.log('FOUT', e && e.stack); process.exit(1); });
