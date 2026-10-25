#!/usr/bin/env node
// Build 149: controles van js/build149.js (datums, verborgen categorieën overslaan, Ontdek categorieën, zoeken) en van
// js/jsonworker.js (overslaan op category_id), gelijk aan tools/test/Build149LogicTest.kt op Android, plus een
// belastingtest met 123.000 films, 30.000 series en 12.000 zenders waarvan 80% in verborgen categorieën:
// vóór (alles als licht object houden) tegen na (verborgen items alleen tellen). Geheugen = heap na inlezen.
// Draait zonder tv: node tizen/test/build149_test.js (de workflow Build doet dit; een fout laat de build mislukken).
'use strict';
var vm = require('vm');
var fs = require('fs');
var path = require('path');
var v8 = require('v8');
v8.setFlagsFromString('--expose-gc');
var gc = vm.runInNewContext('gc');

var JS = path.resolve(__dirname, '..', 'js');
var checks = 0, failures = 0;
function eq(name, got, want) {
  checks++;
  var a = JSON.stringify(got), b = JSON.stringify(want);
  if (a !== b) { failures++; console.log('FOUT ' + name + ': kreeg <' + a + '>, verwacht <' + b + '>'); console.log('::error title=build149_test::' + name); }
}
function ok(name, v) { eq(name, !!v, true); }

var mem = {};
var win = { console: console, Promise: Promise, Intl: Intl, Date: Date, JSON: JSON, Math: Math,
  localStorage: { getItem: function (k) { return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null; }, setItem: function (k, v) { mem[k] = String(v); } },
  performance: { memory: { usedJSHeapSize: 10, jsHeapSizeLimit: 100 } } };
win.window = win;
var ctx = vm.createContext(win);
vm.runInContext(fs.readFileSync(path.join(JS, 'build149.js'), 'utf8'), ctx, { filename: 'build149.js' });
var X = win.B.b149, D = X.dates;

// MARK: datums
eq('iso: Z met ms', D.parseIso('2026-10-12T18:00:00.000Z'), 1791828000000);
eq('iso: 7 decimalen', D.parseIso('2026-10-12T18:00:00.1234567Z'), 1791828000123);
eq('iso: +02:00', D.parseIso('2026-10-12T20:00:00+02:00'), 1791828000000);
eq('iso: +0200', D.parseIso('2026-10-12T20:00:00+0200'), 1791828000000);
eq('iso: zonder zone = UTC', D.parseIso('2026-10-12T18:00:00'), 1791828000000);
eq('iso: spatie', D.parseIso('2026-10-12 18:00:00'), 1791828000000);
eq('iso: alleen datum', D.parseIso('2026-10-12'), 1791763200000);
eq('iso: onzin', D.parseIso('gisteren'), null);
eq('iso: voor 1970', D.parseIso('1969-12-31T23:59:59.000Z'), -1000);
var same = true;
for (var i = 0; i < 20000; i++) { var ms = Math.floor((Math.random() - 0.3) * 4e12); if (D.parseIso(new Date(ms).toISOString()) !== ms) same = false; }
ok('iso: gelijk aan Date.parse (20.000 tijden)', same);
eq('xmltv: met zone', D.parseXmltv('20261012200000 +0200'), 1791828000000);
eq('xmltv: zonder zone', D.parseXmltv('20261012180000'), 1791828000000);
eq('xmltv: -0500', D.parseXmltv('20261012130000 -0500'), 1791828000000);
var made = D.created;
for (var j = 0; j < 1000; j++) D.format(j * 86400000, 'nl', { weekday: 'short', day: 'numeric', month: 'short' });
eq('Intl: één opmaker voor 1.000 datums', D.created - made, 1);
var t0 = Date.now();
for (j = 0; j < 20000; j++) new Date(j * 86400000).toLocaleDateString('nl', { weekday: 'short', day: 'numeric', month: 'short' });
var oldMs = Date.now() - t0; t0 = Date.now();
for (j = 0; j < 20000; j++) D.format(j * 86400000, 'nl', { weekday: 'short', day: 'numeric', month: 'short' });
var newMs = Date.now() - t0;
console.log('datums: 20.000 keer opmaken vóór ' + oldMs + ' ms (toLocaleDateString), na ' + newMs + ' ms');

// MARK: overslaan
var sk = X.skip(function (g) { return g.indexOf('Verborgen') === 0; });
['Verborgen A', 'Zichtbaar', 'Verborgen A', 'Verborgen B'].forEach(function (g) { sk.skip(g); });
eq('skip: tellingen', sk.counts(), { 'Verborgen A': 2, 'Verborgen B': 1 });
var lib = { hiddenGroups: { 'movie|A': 3, 'movie|B': 2, 'series|C': 1 } };
eq('skip: per soort', X.hiddenCount(lib, 'movie'), 5);
eq('skip: namen', X.hiddenNames(lib, 'movie'), ['A', 'B']);
ok('skip: weer aan → opnieuw ophalen', X.needsReload(lib, function (g) { return g !== 'B'; }));
ok('skip: nog verborgen → niet', !X.needsReload(lib, function () { return true; }));
ok('geheugen: onder de grens', !X.memory.tooHigh());
ok('geheugen: boven de grens', X.memory.tooHigh({ used: 90, limit: 100 }));

// MARK: worker
var posted = [];
var wctx = vm.createContext({ self: { postMessage: function (m) { posted.push(m); } }, JSON: JSON, Math: Math, Object: Object, Array: Array, String: String });
vm.runInContext(fs.readFileSync(path.join(JS, 'jsonworker.js'), 'utf8'), wctx, { filename: 'jsonworker.js' });
wctx.self.onmessage({ data: { id: 1, text: JSON.stringify([{ stream_id: 1, name: 'A', category_id: '1', x: 9 }, { stream_id: 2, name: 'B', category_id: '2' }, { stream_id: 3, name: 'C', category_id: 1 }]),
  keys: ['stream_id', 'name', 'category_id'], skipField: 'category_id', skip: { '1': true } } });
eq('worker: alleen zichtbare items', posted[0].batch, [{ stream_id: 2, name: 'B', category_id: '2' }]);
eq('worker: telling verborgen', posted[1].skipped, { '1': 2 });
posted = [];
wctx.self.onmessage({ data: { id: 2, text: '[{"a":1}]', keys: ['a'] } });
eq('worker: zonder overslaan zoals build 148', [posted[0].batch, posted[1].done, posted[1].skipped], [[{ a: 1 }], true, null]);

// MARK: Ontdek categorieën
var DC = X.discover;
eq('discover: sleutel', DC.key('Disney+ Series'), 'disneyplusseries');
var prov = { netflix: 'n.png', disneyplus: 'd.png', hbomax: 'h.png', amazonprimevideo: 'p.png', videoland: 'v.png' };
eq('discover: Netflix Films 4K', DC.providerFor('Netflix Films 4K', prov), 'netflix');
eq('discover: Disney+ Series', DC.providerFor('Disney+ Series', prov), 'disneyplus');
eq('discover: HBO', DC.providerFor('HBO', prov), 'hbomax');
eq('discover: Prime Video', DC.providerFor('Prime Video', prov), 'amazonprimevideo');
eq('discover: Actie', DC.providerFor('Actie', prov), null);
eq('discover: hash gelijk aan Kotlin', DC.hash('netflix'), -381125525);
eq('discover: kleur gelijk aan Kotlin', DC.colorFor('Netflix'), DC.PALETTE[((-381125525 % 10) + 10) % 10]);
var cats = [{ name: 'Netflix', count: 40, posters: ['a', 'b', 'c'] }, { name: 'Klein', count: 2, posters: ['a', 'b', 'c'] }, { name: 'Kids', count: 9, posters: ['a', 'b', 'c', 'd'] }];
eq('discover: kies', DC.pick(cats, false, function () { return false; }).map(function (c) { return c.name; }), ['Netflix', 'Kids']);
eq('discover: kinderprofiel', DC.pick(cats, true, function (n) { return n === 'Kids'; }).length, 0);

// MARK: zoeken
var SE = X.search;
eq('zoeken: accenten', SE.key('Amélie'), 'amelie');
eq('zoeken: voorvoegsel', SE.key('NL | The Lord of the Rings'), 'the lord of the rings');
eq('zoeken: 4K -', SE.key('4K - Dune: Part Two'), 'dune part two');
eq('zoeken: [NL] en &', SE.key('[NL] Fast & Furious'), 'fast and furious');
eq('zoeken: apostrof', SE.key("Grey's Anatomy"), 'greys anatomy');
function items(list) { return list.map(function (t) { return { title: t }; }); }
function titles(list) { return list.map(function (x) { return x.title; }); }
var T = items(['NL | The Lord of the Rings: The Two Towers', 'Rings', 'Lord of War', 'Amélie', 'Fast & Furious 7', '[NL] Ringo', 'Breaking Bad', 'Stranger Things']);
eq('zoeken: woordvolgorde', titles(SE.find(T, 'rings lord', 5, 'title')), ['NL | The Lord of the Rings: The Two Towers']);
eq('zoeken: accenten vinden', titles(SE.find(T, 'amelie', 5, 'title')), ['Amélie']);
eq('zoeken: & en and', titles(SE.find(T, 'fast and furious', 5, 'title')), ['Fast & Furious 7']);
eq('zoeken: exact eerst', titles(SE.find(T, 'rings', 5, 'title'))[0], 'Rings');
eq('zoeken: deelwoord', titles(SE.find(T, 'ring', 5, 'title')).sort(), ['NL | The Lord of the Rings: The Two Towers', 'Rings', '[NL] Ringo'].sort());
eq('zoeken: woordbegin vóór bevat', titles(SE.find(items(['Unbreakable', 'Breaking Bad']), 'break', 5, 'title')), ['Breaking Bad', 'Unbreakable']);
eq('zoeken: typefout', titles(SE.find(T, 'strnger things', 5, 'title')), ['Stranger Things']);
eq('zoeken: geen typefout bij korte woorden', titles(SE.find(T, 'bda', 5, 'title')), []);

// MARK: belastingtest
function gen(n, cats, kind) {
  var a = new Array(n);
  for (var i = 0; i < n; i++) {
    a[i] = { num: i + 1, name: 'NL | Titel nummer ' + i, stream_type: kind, stream_id: 100000 + i, stream_icon: 'https://img.example.invalid/' + kind + '/' + i + '.jpg',
      rating: '7.' + (i % 10), rating_5based: 3.5, added: String(1600000000 + i), category_id: String(i % cats), category_ids: [i % cats],
      container_extension: 'mkv', custom_sid: null, direct_source: '', plot: kind === 'series' ? 'Een verhaal over iets dat gebeurt, aflevering na aflevering.' : undefined };
  }
  return JSON.stringify(a);
}
var texts = [gen(123340, 400, 'movie'), gen(30322, 120, 'series'), gen(11929, 60, 'live')];
var KEYS = ['stream_id', 'name', 'stream_icon', 'category_id', 'container_extension', 'rating', 'added'];
function pick(text, skipIds) {
  var list = JSON.parse(text), out = [], skipped = 0;
  for (var i = 0; i < list.length; i++) {
    var x = list[i];
    if (skipIds && skipIds[String(x.category_id)] === true) { skipped++; continue; }
    var o = {}; for (var k = 0; k < KEYS.length; k++) { var v = x[KEYS[k]]; if (v != null && v !== '') o[KEYS[k]] = v; }
    o.title = x.name; o.group = 'Cat ' + x.category_id; o.streamURL = 'http://x/movie/u/p/' + x.stream_id + '.mkv';
    out.push(o);
  }
  out.skipped = skipped;
  return out;
}
function heapMB() { gc(); return Math.round(process.memoryUsage().heapUsed / 1048576); }
var base = heapMB();
t0 = Date.now();
var before = texts.map(function (t) { return pick(t, null); });
var beforeMs = Date.now() - t0, beforeMB = heapMB() - base, beforeN = before.reduce(function (n, l) { return n + l.length; }, 0);
before = null;
base = heapMB();
var ids = {}; for (var c = 0; c < 400; c++) if (c % 5 !== 0) ids[String(c)] = true;
t0 = Date.now();
var after = texts.map(function (t) { return pick(t, ids); });
var afterMs = Date.now() - t0, afterMB = heapMB() - base, afterN = after.reduce(function (n, l) { return n + l.length; }, 0), hidden = after.reduce(function (n, l) { return n + l.skipped; }, 0);
console.log('geheugen vóór: ' + beforeMB + ' MB vastgehouden (' + beforeN + ' items, ' + beforeMs + ' ms) · na: ' + afterMB + ' MB (' + afterN + ' ingelezen, ' + hidden + ' verborgen geteld, ' + afterMs + ' ms)');
eq('belasting: ingelezen + verborgen = geleverd', afterN + hidden, 123340 + 30322 + 11929);
ok('belasting: minder geheugen', afterMB < beforeMB);
t0 = Date.now(); SE.find(after[0], 'titel 1234', 50, 'title'); var first = Date.now() - t0;
t0 = Date.now(); SE.find(after[0], 'nummer 999', 50, 'title'); var again = Date.now() - t0;
console.log('zoeken: eerste keer ' + first + ' ms (sleutels maken), daarna ' + again + ' ms (' + after[0].length + ' titels)');

console.log(failures === 0 ? 'build149: alle ' + checks + ' controles geslaagd' : 'build149: ' + failures + ' van ' + checks + ' controles mislukt');
process.exit(failures ? 1 : 0);
