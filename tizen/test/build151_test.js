#!/usr/bin/env node
// Build 151: controles van js/build151.js (rij "Zenders" op Home: welke zenders, naam, ontdubbelen) met de gedeelde
// gevallen uit tools/test/build151_cases.json (dezelfde als tools/test/Build151LogicTest.kt op Android).
// Draait zonder tv: node tizen/test/build151_test.js (de workflow Build doet dit; een fout laat de build mislukken).
'use strict';
var vm = require('vm');
var fs = require('fs');
var path = require('path');

var JS = path.resolve(__dirname, '..', 'js');
var CASES = JSON.parse(fs.readFileSync(path.resolve(__dirname, '..', '..', 'tools', 'test', 'build151_cases.json'), 'utf8'));
var checks = 0, failures = 0;
function eq(name, got, want) {
  checks++;
  var a = JSON.stringify(got), b = JSON.stringify(want);
  if (a !== b) { failures++; console.log('FOUT ' + name + ': kreeg <' + a + '>, verwacht <' + b + '>'); console.log('::error title=build151_test::' + name); }
}
function ok(name, v) { eq(name, !!v, true); }

var mem = {};
var win = { console: console, Promise: Promise, Intl: Intl, Date: Date, JSON: JSON, Math: Math,
  localStorage: { getItem: function (k) { return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null; }, setItem: function (k, v) { mem[k] = String(v); } } };
win.window = win;
var ctx = vm.createContext(win);
['build149.js', 'build150.js', 'build151.js'].forEach(function (f) { vm.runInContext(fs.readFileSync(path.join(JS, f), 'utf8'), ctx, { filename: f }); });
var CH = win.B.b151.channels;

CASES.names.forEach(function (c) {
  eq("zendernaam '" + c[0] + "'", CH.name(c[0]), c[1]);
  eq("zendersleutel '" + c[0] + "'", CH.sameKey(c[0]), c[2]);
});
CASES.pick.forEach(function (c) {
  var chans = {};
  Object.keys(c.channels).forEach(function (id) { var v = c.channels[id]; chans[id] = { id: id, name: v[0], logo: v[1], kids: v[2] }; });
  var pref = c.preferred.map(function (id) { return chans[id]; });
  var groups = c.groups.map(function (g) { return [g[0], g[1].map(function (id) { return chans[id]; })]; });
  var got = CH.pick(pref, groups, { key: function (x) { return x.id; }, name: function (x) { return x.name; },
    hasLogo: function (x) { return x.logo; }, allowed: function (x) { return !c.kids || x.kids; } }, c.max);
  eq('kiezen: ' + c.name, got.map(function (x) { return x.id; }), c.want);
});

// ontdubbelen per rij: twee servers (A = actief, B) met overlappende titels
var RW = win.B.b151.rows;
CASES.dedupe.forEach(function (c) {
  var fn = c.mode === 'channels'
    ? { id: function (x) { return 'c:' + CH.sameKey(x.title); }, title: function () { return ''; }, year: function () { return null; },
        rank: function (x) { return RW.rank(RW.tags(x.group, x.title), x.src === 'A', c.preferred); } }
    : { id: function (x) { return x.id; }, title: function (x) { return x.title; }, year: function (x) { return x.year; },
        rank: c.mode === 'recent' ? function (x) { return -x.updated; } : function (x) { return RW.rank(RW.tags(x.group, x.title), x.src === 'A', c.preferred); } };
  eq('ontdubbelen: ' + c.name, RW.dedupe(c.items, fn).map(function (x) { return x.k; }), c.want);
});

// belasting: 12.000 zenders in 400 categorieën, 50 recente/favoriete
var big = [], by = {};
for (var i = 0; i < 12000; i++) { var ch = { id: 'c' + i, name: 'NL | Zender ' + i + ' HD', logo: i % 3 !== 0 }; big.push(ch); (by['G' + (i % 400)] = by['G' + (i % 400)] || []).push(ch); }
var bigGroups = Object.keys(by).map(function (k) { return [k, by[k]]; });
var fn = { key: function (x) { return x.id; }, name: function (x) { return x.name; }, hasLogo: function (x) { return x.logo; }, allowed: function () { return true; } };
var t0 = Date.now(), r = [];
for (var n = 0; n < 20; n++) r = CH.pick(big.slice(0, 50), bigGroups, fn);
var ms = Math.round((Date.now() - t0) / 20);
console.log('kiezen: 12.000 zenders in ' + ms + ' ms');
eq('kiezen: maximum', r.length, CH.MAX);
ok('kiezen: 12.000 zenders < 50 ms', ms < 50);

console.log(failures === 0 ? 'build151: alle ' + checks + ' controles geslaagd' : 'build151: ' + failures + ' van ' + checks + ' controles mislukt');
process.exit(failures ? 1 : 0);
