#!/usr/bin/env node
// Build 145: controles van js/build145.js ("Servers laden bij openen": Vragen / Altijd / Nooit, alleen handmatig; de
// vraag "Servers bijwerken?"; volgorde van het bijwerken), gelijk aan tools/test/Build145LogicTest.kt op Android.
// Draait zonder tv: node tizen/test/build145_test.js (de workflow Build doet dit; een fout laat de build mislukken).
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
var mem = {};
var ls = { getItem: function (k) { return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null; }, setItem: function (k, v) { mem[k] = String(v); }, removeItem: function (k) { delete mem[k]; } };
var win = { document: doc, navigator: { userAgent: 'node', language: 'nl' }, addEventListener: noop, location: { href: 'file:///' },
  setTimeout: function (fn) { fn(); return 0; }, clearTimeout: noop, setInterval: function () { return 0; }, clearInterval: noop, console: console, Promise: Promise,
  localStorage: ls };
win.window = win;
var ctx = vm.createContext(win);
function load(f) { vm.runInContext(fs.readFileSync(path.join(JS, f), 'utf8'), ctx, { filename: f }); }
load('core.js');
var B = win.B;
load('build145.js');
var P = B.b145.load;

// MARK: stand op het apparaat
eq('stand: niets = Vragen', P.current(null), 'ask');
eq('stand: leeg = Vragen', P.current(''), 'ask');
eq('stand: ask', P.current('ask'), 'ask');
eq('stand: always', P.current('always'), 'always');
eq('stand: never', P.current('never'), 'never');
eq('stand: met spaties', P.current(' never '), 'never');
eq('stand: oude termijn telt niet (daily)', P.current('daily'), 'ask');
eq('stand: onbekend = Vragen', P.current('sometimes'), 'ask');
eq('ruwe waarden gelijk op alle apps', P.MODES, ['ask', 'always', 'never']);

// MARK: eenmalige omzetting van de oude vernieuwtermijn
eq('omzetten: niets ingesteld → Vragen', P.migrate(null), 'ask');
['manual', 'launch', 'sixHours', 'daily', 'weekly'].forEach(function (old) { eq('omzetten: oud ' + old + ' → Vragen', P.migrate(old), 'ask'); });
eq('omzetten: al Vragen → niets', P.migrate('ask'), null);
eq('omzetten: al Altijd → niets', P.migrate('always'), null);
eq('omzetten: al Nooit → niets', P.migrate('never'), null);

// MARK: wat na het kiezen van een profiel
eq('Vragen, bewaarde lijst, volwassene: vraag', P.decide('ask', true, false, false), 'ask');
eq('Vragen: één keer per keer openen', P.decide('ask', true, false, true), 'nothing');
eq('Vragen, kinderprofiel: Later', P.decide('ask', true, true, false), 'nothing');
eq('Vragen, geen bewaarde lijst: geen vraag (laadt zelf)', P.decide('ask', false, false, false), 'nothing');
eq('Altijd: meteen bijwerken', P.decide('always', true, false, false), 'updateAll');
eq('Altijd, kinderprofiel: ook bijwerken', P.decide('always', true, true, false), 'updateAll');
eq('Altijd, na een profielwissel: ook', P.decide('always', true, false, true), 'updateAll');
eq('Altijd, geen bewaarde lijst: de andere ook', P.decide('always', false, false, false), 'updateAll');
eq('Nooit: niets', P.decide('never', true, false, false), 'nothing');
eq('Nooit, geen bewaarde lijst: niets extra', P.decide('never', false, false, false), 'nothing');
eq('Nooit, kinderprofiel: niets', P.decide('never', true, true, false), 'nothing');

// MARK: volgorde van het bijwerken
var ids = ['A1', 'B2', 'C3'];
eq('volgorde: open server eerst', P.order(ids, 'B2'), ['B2', 'A1', 'C3']);
eq('volgorde: open server zonder op hoofdletters te letten', P.order(ids, 'b2'), ['B2', 'A1', 'C3']);
eq('volgorde: geen open server', P.order(ids, null), ids);
eq('volgorde: onbekende open server', P.order(ids, 'X9'), ids);
eq('volgorde: al bijgewerkt overslaan', P.order(ids, 'B2', { b2: true }), ['A1', 'C3']);
eq('volgorde: alles al bijgewerkt', P.order(ids, 'A1', { a1: true, b2: true, c3: true }), []);
eq('volgorde: dubbel één keer', P.order(['A1', 'a1', 'B2'], null), ['A1', 'B2']);
eq('volgorde: leeg', P.order([], 'A1'), []);

// MARK: wanneer de volgende server
eq('verder: hoofdscherm, niets speelt', P.mayRun(true, false), true);
eq('wachten: tijdens het afspelen', P.mayRun(true, true), false);
eq('wachten: Wie kijkt er? / pincodescherm', P.mayRun(false, false), false);
eq('stoppen: ander profiel of andere server', P.stillCurrent(3, 4), false);
eq('verder: zelfde keuze', P.stillCurrent(3, 3), true);

// MARK: opslag op de tv (lokaal, niet in de account)
eq('tv: zonder stand = Vragen', P.mode(), 'ask');
P.migrateNow();
eq('tv: omgezet naar Vragen', B.store.get(P.KEY, null), 'ask');
P.setMode('never');
eq('tv: Nooit bewaard', P.mode(), 'never');
P.migrateNow();
eq('tv: omzetten laat een geldige stand staan', P.mode(), 'never');
P.setMode('daily');
eq('tv: ongeldige stand wordt niet bewaard', P.mode(), 'never');
P.setMode('always');
eq('tv: Altijd bewaard', P.mode(), 'always');
eq('tv: niet in settings (account)', B.store.get('settings', null), null);

console.log(failures ? (checks + ' controles, ' + failures + ' fout') : ('Build 145 (Samsung TV): alle ' + checks + ' controles geslaagd'));
process.exit(failures ? 1 : 0);
