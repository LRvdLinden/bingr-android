#!/usr/bin/env node
// Build 148: controles van js/build148.js (codes, bijwerken in fasen, geleverd en zichtbaar, haperingen, sorteren in
// stukken), gelijk aan tools/test/Build148LogicTest.kt op Android, plus een catalogus van 37.000 films, 12.000 zenders en
// 9.000 series (plus categorieën):
// - Xtream.library zet de lijsten in stukjes om (B.chunked): geen stuk langer dan 250 ms;
// - merge sort in stukken: geen stap langer dan 250 ms (B.sortChunked deed op CI 173 ms per stap bij 10k);
// - geheugen vóór/na: volledige JSON-objecten (zoals de server ze stuurt) tegen lichte objecten (alleen de nodige velden).
// Tijdsgrenzen zijn op een gedeelde CI-runner een waarschuwing; de uitkomst wordt altijd hard getest.
// Draait zonder tv: node tizen/test/build148_test.js (de workflow Build doet dit; een fout laat de build mislukken).
'use strict';
var vm = require('vm');
var fs = require('fs');
var path = require('path');
var v8 = require('v8');
v8.setFlagsFromString('--expose-gc');
var gc = vm.runInNewContext('gc');

var JS = path.resolve(__dirname, '..', 'js');
var checks = 0, failures = 0, warnings = 0;
function eq(name, got, want) {
  checks++;
  var a = JSON.stringify(got), b = JSON.stringify(want);
  if (a !== b) { failures++; console.log('FOUT ' + name + ': kreeg <' + a + '>, verwacht <' + b + '>'); }
}
function ok(name, v) { eq(name, !!v, true); }
function soft(name, v) { checks++; if (!v) { warnings++; console.log('WAARSCHUWING ' + name); console.log('::warning title=build148_test::' + name); } }

var noop = function () {};
function el() { return { style: {}, classList: { add: noop, remove: noop, toggle: noop, contains: function () { return false; } }, setAttribute: noop, appendChild: noop, addEventListener: noop }; }
var doc = { addEventListener: noop, createElement: el, body: el(), documentElement: el(), querySelector: function () { return null; }, hidden: false };
var mem = {};
var ls = { getItem: function (k) { return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null; }, setItem: function (k, v) { mem[k] = String(v); }, removeItem: function (k) { delete mem[k]; } };
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
load('build148.js');
var X = B.b148, C = X.codes;

// MARK: codes
eq('codes: vaste lijst (26)', C.FIXED.length, 26);
ok('codes: alle codes in de juiste stijl', C.ALL.every(C.valid));
eq('codes: start → started', C.of('start'), 'started');
eq('codes: splash → first_frame', C.of('splash'), 'first_frame');
eq('codes: profiles → profile_picker', C.of('profiles'), 'profile_picker');
eq('codes: pin → pin_screen', C.of('pin'), 'pin_screen');
eq('codes: servers_loaded → servers', C.of('servers_loaded'), 'servers');
eq('codes: ready blijft', C.of('ready'), 'ready');
eq('codes: oude taak sync toepassen', C.of('sync toepassen'), 'sync_apply');
eq('codes: oude taak rijen opbouwen', C.of('rijen opbouwen'), 'rows_build');
eq('codes: onbekende code in de juiste stijl komt door', C.of('foo_bar'), 'foo_bar');
eq('codes: tekst zonder code valt weg', C.of('Iets Anders!'), null);
eq('codes: label', C.label('catalog_sort'), 'Sorting catalog');
eq('codes: onbekend label = code', C.label('foo_bar'), 'foo_bar');
ok('codes: elke code een label', C.ALL.every(function (c) { return C.label(c) !== c; }));
eq('codes: dubbele code één keer', C.dedupe([{ name: 'start' }, { name: 'activity' }, { name: 'splash' }, { name: 'ready' }]).map(function (x) { return x.code; }),
  ['started', 'first_frame', 'ready']);
// alle labels hebben een Nederlandse vertaling (strings.js)
vm.runInContext(fs.readFileSync(path.join(JS, 'strings.js'), 'utf8'), ctx, { filename: 'strings.js' });
var nl = (win.BINGR_STRINGS || {}).nl || {};
ok('codes: labels vertaald (nl)', C.LABEL_KEYS.every(function (k) { return Object.prototype.hasOwnProperty.call(nl, k); }));

// MARK: bijwerken in fasen
(function () {
  var r = new X.updates.Recorder('s1', 1000, 0, 100);
  r.phase('fetch', 3900, 210); r.phase('parse', 10100, 455); r.phase('fetch', 10600, 300);
  r.phase('save', 14400, 320); r.phase('index', 16500, 430); r.phase('apply', 22000, 440);
  r.phase('onzin', 23000, 999);
  var u = r.finish('ok', 22000);
  eq('bijwerken: fasen in vaste volgorde', u.phases.map(function (p) { return p.code; }), ['fetch', 'parse', 'index', 'apply', 'save']);
  eq('bijwerken: dezelfde fase telt op', u.phases[0].ms, 4400);
  eq('bijwerken: geheugen aan het eind van de fase', u.phases[1].memMB, 455);
  eq('bijwerken: totaal', u.totalMs, 22000);
  eq('bijwerken: piek', u.peakMB, 455);
  eq('bijwerken: uitkomst', u.result, 'ok');
  eq('bijwerken: onbekende uitkomst = failed', r.finish('raar', 22000).result, 'failed');
  var list = [];
  for (var i = 0; i < 7; i++) list = X.updates.push(list, B.assign({}, u, { server: 's' + (i + 1) }));
  eq('bijwerken: hooguit 5, nieuwste eerst', list.map(function (x) { return x.server; }), ['s7', 's6', 's5', 's4', 's3']);
})();

// MARK: geleverd en zichtbaar
eq('verborgen: voorbeeld uit de diagnose', X.hidden.of(36593, 10286, 26307, 0, 0), { delivered: 36593, visible: 10286, categories: 26307, kids: 0, duplicates: 0, other: 0 });
eq('verborgen: rest = overig', X.hidden.of(100, 50, 30, 5, 0), { delivered: 100, visible: 50, categories: 30, kids: 5, duplicates: 0, other: 15 });
eq('verborgen: nooit negatief', X.hidden.of(10, 20, 5, 5, 5), { delivered: 10, visible: 10, categories: 0, kids: 0, duplicates: 0, other: 0 });
(function () {
  var items = []; for (var i = 0; i < 1000; i++) items.push('g' + (i % 10));
  eq('verborgen: tellen per groep', X.hidden.count(items, 600, function (x) { return x; }, function (g) { return g === 'g1' || g === 'g2'; }, function (g) { return g === 'g3'; }),
    { delivered: 1000, visible: 600, categories: 200, kids: 100, duplicates: 0, other: 100 });
})();

// MARK: haperingen
(function () {
  var t = new X.stalls.Tracker();
  eq('hapering: bufferen vóór het eerste beeld telt niet', t.update(0, false, true, true), null);
  eq('hapering: eerste beeld', t.update(1000, true, false, true), null);
  eq('hapering: begint', t.update(5000, false, true, true), null);
  var s = t.update(6500, true, false, true);
  eq('hapering: duur en moment', s, { ms: 1500, atS: 4, live: true });
  t.update(7000, false, true, true);
  eq('hapering: korter dan 0,5 s telt niet', t.update(7300, true, false, true), null);
  var l = []; for (var i = 0; i < 12; i++) l = X.stalls.push(l, s);
  eq('hapering: hooguit 10', l.length, 10);
})();

// MARK: merge sort in stukken (nepklok: elke vergelijking 1 ms → stappen van hooguit ~budget)
(function () {
  var clock = 0, list = [], seed = 148;
  function rnd() { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; }
  for (var i = 0; i < 5000; i++) list.push({ t: Math.floor(rnd() * 100000), i: i });
  var job = X.mergeSortChunked(list, function (a, b) { clock += 0.01; return a.t - b.t; }), steps = 0, longest = 0;
  while (true) { var s0 = clock; var fin = job.step(12, function () { return clock; }); longest = Math.max(longest, clock - s0); steps++; if (fin) break; }
  var r = job.result();
  ok('sorteren: gesorteerd', r.every(function (x, k) { return k === 0 || r[k - 1].t <= x.t; }));
  ok('sorteren: stabiel', r.every(function (x, k) { return k === 0 || r[k - 1].t < x.t || r[k - 1].i < x.i; }));
  eq('sorteren: alles', r.length, 5000);
  ok('sorteren: in meerdere stappen (' + steps + ')', steps > 5);
  ok('sorteren: geen stap ver boven het budget (' + longest.toFixed(1) + ')', longest <= 25);
  eq('sorteren: leeg', X.mergeSortChunked([], function () { return 0; }).step(12, Date.now), true);
  var small = X.mergeSortChunked([3, 1, 2], function (a, b) { return a - b; }); small.step(12, Date.now);
  eq('sorteren: klein', small.result(), [1, 2, 3]);
})();

// MARK: 37.000 films, 12.000 zenders, 9.000 series
var NM = 37000, NC = 12000, NS = 9000;
/** Zoals een Xtream-server ze stuurt (veel velden die de app niet gebruikt). */
function fullMovie(i) {
  return { num: i + 1, name: 'Film titel ' + (i * 7919 % 1000003) + ' (' + (1980 + i % 45) + ')', title: 'Film titel ' + i, year: String(1980 + i % 45),
    stream_type: 'movie', stream_id: i, stream_icon: 'http://img.example/p/' + i + '.jpg', rating: String((i % 100) / 10), rating_5based: (i % 50) / 10,
    tmdb: String(100000 + i), trailer: '', added: String(1700000000 + i), is_adult: 0, category_id: String(i % 400), category_ids: [i % 400],
    container_extension: 'mkv', custom_sid: null, direct_source: '' };
}
function fullChannel(i) {
  return { num: i + 1, name: 'Zender ' + i + ' HD', stream_type: 'live', stream_id: i, stream_icon: 'http://img.example/l/' + i + '.png',
    epg_channel_id: 'z' + i + '.nl', added: String(1600000000 + i), is_adult: 0, category_id: String(i % 300), category_ids: [i % 300],
    custom_sid: '', tv_archive: i % 2, direct_source: '', tv_archive_duration: '3', thumbnail: '' };
}
function fullSeries(i) {
  return { num: i + 1, name: 'Serie ' + (i * 31 % 100003), title: 'Serie ' + i, year: String(1990 + i % 30), stream_type: 'series', series_id: i,
    cover: 'http://img.example/s/' + i + '.jpg', plot: 'Een korte beschrijving van serie ' + i + ' met wat tekst.', cast: 'Acteur A, Acteur B',
    director: 'Regisseur', genre: 'Drama', release_date: '2020-01-01', releaseDate: '2020-01-01', last_modified: String(1700000000 + i),
    rating: '7', rating_5based: 3.5, backdrop_path: ['http://img.example/b/' + i + '.jpg'], youtube_trailer: '', episode_run_time: '45',
    category_id: String(i % 200), category_ids: [i % 200] };
}
function cats(n, prefix) { var l = []; for (var i = 0; i < n; i++) l.push({ category_id: String(i), category_name: prefix + ' ' + i, parent_id: 0 }); return l; }
function arr(n, f) { var l = new Array(n); for (var i = 0; i < n; i++) l[i] = f(i); return l; }

// geheugen vóór/na: volledige objecten (vóór build 148) tegen lichte objecten (alleen de nodige velden, js/jsonworker.js)
function heapMB() { gc(); gc(); return process.memoryUsage().heapUsed / 1048576; }
var K = null;
var tests = [];
tests.push(function () {
  load('language.js');
  load('providers.js');
  K = B.providers.Xtream.KEYS;
  ok('lichte velden: lijsten', !!(K && K.live && K.vod && K.series));
  // vóór: de JSON-tekst ingelezen op de hoofdthread (volledige objecten)
  var base = heapMB();
  var full = { m: JSON.parse(JSON.stringify(arr(NM, fullMovie))), c: JSON.parse(JSON.stringify(arr(NC, fullChannel))), s: JSON.parse(JSON.stringify(arr(NS, fullSeries))) };
  var before = heapMB() - base;
  // na: zelfde tekst, met alleen de nodige velden (wat de Worker terugstuurt)
  var texts = { m: JSON.stringify(full.m), c: JSON.stringify(full.c), s: JSON.stringify(full.s) };
  full = null;
  var p = Promise.all([B.parseJSONList(texts.m, K.vod), B.parseJSONList(texts.c, K.live), B.parseJSONList(texts.s, K.series)]);
  return p.then(function (r) {
    texts = null;
    var after = heapMB() - base;
    console.log('geheugen ruwe lijsten 37k/12k/9k: vóór (volledige objecten) ' + before.toFixed(1) + ' MB, na (lichte objecten) ' + after.toFixed(1) + ' MB');
    eq('lichte objecten: aantallen', [r[0].length, r[1].length, r[2].length], [NM, NC, NS]);
    eq('lichte objecten: alleen de nodige velden', Object.keys(r[0][5]).sort(), K.vod.slice().sort());
    soft('lichte objecten: minder geheugen (' + after.toFixed(1) + ' < ' + before.toFixed(1) + ' MB)', after < before);
    r = null;
  });
});
tests.push(function () {
  // Xtream.library met een nepserver: lijsten in stukjes omgezet, elk stuk ≤ 250 ms
  var x = new B.providers.Xtream({ id: 's', serverURL: 'http://srv', username: 'u', kind: 'Xtream Codes' }, 'p');
  var data = {
    get_live_categories: cats(300, 'Zenders'), get_vod_categories: cats(400, 'Films'), get_series_categories: cats(200, 'Series'),
    get_live_streams: arr(NC, fullChannel), get_vod_streams: arr(NM, fullMovie), get_series: arr(NS, fullSeries)
  };
  x.list = function (a, keys) {
    var l = data[a];
    if (keys) l = l.map(function (o) { var p = {}; keys.forEach(function (k) { if (o[k] != null && o[k] !== '') p[k] = o[k]; }); return p; });
    return new Promise(function (res) { setTimeout(function () { res(l); }, 5); });
  };
  var fetched = 0;
  longestSlice = 0;
  var t0 = Date.now();
  var job;
  timed(function () { job = x.library({ fetched: function () { fetched++; } }); })();
  return job.then(function (lib) {
    console.log('Xtream 37k/12k/9k in stukjes: ' + (Date.now() - t0) + ' ms, langste stuk ' + longestSlice + ' ms');
    eq('37k: films', lib.movies.length, NM);
    eq('37k: zenders', lib.channels.length, NC);
    eq('37k: series', lib.series.length, NS);
    eq('37k: groep uit de categorie', lib.movies[401].group, 'Films 1');
    eq('37k: stream-adres', lib.channels[7].streamURL, 'http://srv/live/u/p/7.ts');
    eq('37k: terugkijken', [lib.channels[1].catchupDays, lib.channels[2].catchupDays], [3, 0]);
    eq('37k: serie-id', lib.series[3].xtreamSeriesID, 3);
    eq('37k: "alles binnen" één keer', fetched, 1);
    soft('37k: langste stuk ' + longestSlice + ' ms ≤ 250 ms', longestSlice <= 250);
  });
});
tests.push(function () {
  // sorteren van 37k titels in stukken met echte tijd (B.b148.sortChunked): geen stap boven 250 ms
  var list = arr(NM, function (i) { return { title: 'Titel ' + (i * 7919 % 1000003) }; });
  longestSlice = 0;
  var p;
  timed(function () { p = X.sortChunked(list, function (a, b) { return a.title < b.title ? -1 : a.title > b.title ? 1 : 0; }, 12); })();
  return p.then(function (r) {
    ok('sorteren 37k: gesorteerd', r.every(function (x, k) { return k === 0 || r[k - 1].title <= x.title; }));
    console.log('sorteren 37k in stukken: langste stap ' + longestSlice + ' ms');
    soft('sorteren 37k: langste stap ' + longestSlice + ' ms ≤ 250 ms', longestSlice <= 250);
  });
});

tests.reduce(function (p, t) { return p.then(t); }, Promise.resolve()).then(function () {
  var w = warnings ? ' (' + warnings + ' waarschuwingen)' : '';
  console.log(failures === 0 ? 'build148: alle ' + checks + ' controles geslaagd' + w : 'build148: ' + failures + ' van ' + checks + ' controles mislukt' + w);
  process.exit(failures ? 1 : 0);
}, function (e) { console.log('FOUT ' + (e && e.stack || e)); process.exit(1); });
