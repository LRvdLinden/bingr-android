#!/usr/bin/env node
// Build 150: controles van js/build150.js (categorienamen opschonen, samenvoegen, ontdubbelen, logo's) met de gedeelde
// gevallen uit tools/test/build150_cases.json (dezelfde als tools/test/Build150LogicTest.kt op Android), plus zoeken via
// js/build149.js (B.b149.search.key gebruikt dezelfde opschoning).
// Draait zonder tv: node tizen/test/build150_test.js (de workflow Build doet dit; een fout laat de build mislukken).
'use strict';
var vm = require('vm');
var fs = require('fs');
var path = require('path');

var JS = path.resolve(__dirname, '..', 'js');
var CASES = JSON.parse(fs.readFileSync(path.resolve(__dirname, '..', '..', 'tools', 'test', 'build150_cases.json'), 'utf8'));
var checks = 0, failures = 0;
function eq(name, got, want) {
  checks++;
  var a = JSON.stringify(got), b = JSON.stringify(want);
  if (a !== b) { failures++; console.log('FOUT ' + name + ': kreeg <' + a + '>, verwacht <' + b + '>'); console.log('::error title=build150_test::' + name); }
}
function ok(name, v) { eq(name, !!v, true); }

var mem = {};
var win = { console: console, Promise: Promise, Intl: Intl, Date: Date, JSON: JSON, Math: Math,
  localStorage: { getItem: function (k) { return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null; }, setItem: function (k, v) { mem[k] = String(v); } } };
win.window = win;
var ctx = vm.createContext(win);
['build149.js', 'build150.js'].forEach(function (f) { vm.runInContext(fs.readFileSync(path.join(JS, f), 'utf8'), ctx, { filename: f }); });
var X = win.B.b150, C = X.cat;

CASES.clean.forEach(function (c) {
  var r = C.clean(c[0]);
  eq("opschonen '" + c[0] + "'", r.name, c[1]);
  eq("labels '" + c[0] + "'", r.tags, c[2]);
  eq("idempotent '" + c[0] + "'", C.clean(r.name).name, r.name);
});
CASES.merge.forEach(function (c) {
  var g = X.merge.groups(c[0]);
  eq('samenvoegen ' + JSON.stringify(c[0]), g.map(function (x) { return [x.name, x.sources.length, X.merge.chips(x.tags)]; }), c[1]);
});
CASES.search.forEach(function (c) { eq("zoeksleutel '" + c[0] + "'", win.B.b149.search.key(c[0]), c[1]); });
var providers = {};
['Netflix', 'Videoland', 'Disney Plus', 'HBO Max', 'Amazon Prime Video', 'SkyShowtime', 'Viaplay', 'Apple TV Plus', 'NPO Start', 'Pathé Thuis']
  .forEach(function (n) { providers[X.merge.key(n)] = 'https://image.tmdb.org/t/p/w300/' + n + '.png'; });
providers._at = 1;
CASES.logos.forEach(function (c) { eq("logo '" + c[0] + "'", X.logos.providerFor(c[0], providers), c[1]); });

var items = [{ id: 'tt1', t: 'NL | Dune', y: 2021 }, { id: 'tt1', t: 'Dune (MULTI)', y: 2021 }, { id: null, t: 'The Witcher', y: 2019 },
  { id: null, t: 'MULTI | The Witcher', y: 2019 }, { id: null, t: 'The Witcher', y: 2020 }, { id: 'tt9', t: 'Other', y: null }];
var d = X.merge.dedupe(items, function (x) { return x.id; }, function (x) { return x.t; }, function (x) { return x.y; });
eq('ontdubbelen: aantal', d.length, 4);
eq('ontdubbelen: eerste wint', d[0].t, 'NL | Dune');
eq('voorkeursbron NL', X.merge.preferSources(['| MULTI | NETFLIX', '| EN | NETFLIX', '| NL | NETFLIX'], ['NL'])[0], '| NL | NETFLIX');
eq('voorkeursbron onbekend → meertalig', X.merge.preferSources(['| EN | NETFLIX', '| MULTI | NETFLIX'], ['DE'])[0], '| MULTI | NETFLIX');

// zoeken in verborgen categorieën (compacte index uit js/providers.js)
var hidx = { movie: { n: ['NL | The Witcher: Blood Origin', 'Dune 4K', 'Dune'], g: [0, 1, 1], gs: ['| NL | NETFLIX', 'XXX Adult'] },
  series: { n: ['MULTI | The Witcher'], g: [0], gs: ['| MULTI | NETFLIX'] } };
var hh = X.hiddenSearch(hidx, 'witcher', 10);
eq('verborgen: treffers', hh.map(function (x) { return x.type + ':' + x.group; }), ['series:| MULTI | NETFLIX', 'movie:| NL | NETFLIX']);
eq('verborgen: ontdubbeld (Dune 4K = Dune)', X.hiddenSearch(hidx, 'dune', 10).length, 1);
eq('verborgen: leeg zonder index', X.hiddenSearch(null, 'dune', 10), []);
ok('verborgen: sleutels bewaard', hidx.movie._k && hidx.movie._k.length === 3);

// Worker: overgeslagen items met naam en categorie (skipName)
var posted = [], wself = { postMessage: function (m) { posted.push(m); } };
wself.self = wself;
vm.runInContext(fs.readFileSync(path.join(JS, 'jsonworker.js'), 'utf8'), vm.createContext(wself), { filename: 'jsonworker.js' });
wself.onmessage({ data: { id: 1, text: JSON.stringify([{ name: 'A', category_id: '1' }, { name: 'B', category_id: '2' }, { name: 'C', category_id: '2' }]),
  keys: ['name'], skipField: 'category_id', skip: { '2': true }, skipName: 'name' } });
var done = posted[posted.length - 1];
eq('worker: namen overgeslagen', [done.skippedNames, done.skippedCats, done.skipped], [['B', 'C'], ['2', '2'], { '2': 2 }]);
posted = [];
wself.onmessage({ data: { id: 2, text: '[{"name":"A","category_id":"2"}]', keys: ['name'], skipField: 'category_id', skip: { '2': true } } });
eq('worker: zonder skipName geen namen (zoals build 149)', posted[posted.length - 1].skippedNames, null);

var t0 = Date.now();
for (var i = 0; i < 50000; i++) C.strip('| NL | NETFLIX SERIES ' + i + ' (4K)');
var ms = Date.now() - t0;
console.log('opschonen: 50.000 namen in ' + ms + ' ms');
ok('opschonen: 50.000 namen < 3 s', ms < 3000);

console.log(failures === 0 ? 'build150: alle ' + checks + ' controles geslaagd' : 'build150: ' + failures + ' van ' + checks + ' controles mislukt');
process.exit(failures ? 1 : 0);
