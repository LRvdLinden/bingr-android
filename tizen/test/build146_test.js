#!/usr/bin/env node
// Build 146: controles van js/build146.js (pincode als hash met de testvectoren uit docs/ACCOUNT.md, met WebCrypto en
// de pure-JS-PBKDF2; welke pincode geldt; profielen samenvoegen; support en de startdiagnose), gelijk aan
// tools/test/Build146LogicTest.kt op Android.
// Draait zonder tv: node tizen/test/build146_test.js (de workflow Build doet dit; een fout laat de build mislukken).
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
function ok(name, v) { eq(name, !!v, true); }

var noop = function () {};
function el() { return { style: {}, classList: { add: noop, remove: noop, toggle: noop, contains: function () { return false; } }, setAttribute: noop, appendChild: noop, addEventListener: noop }; }
var doc = { addEventListener: noop, createElement: el, body: el(), documentElement: el(), querySelector: function () { return null; }, hidden: false };
var mem = {};
var ls = { getItem: function (k) { return Object.prototype.hasOwnProperty.call(mem, k) ? mem[k] : null; }, setItem: function (k, v) { mem[k] = String(v); }, removeItem: function (k) { delete mem[k]; } };
var win = { document: doc, navigator: { userAgent: 'node', language: 'nl' }, addEventListener: noop, location: { href: 'file:///' },
  setTimeout: setTimeout, clearTimeout: clearTimeout, setInterval: function () { return 0; }, clearInterval: noop, console: console, Promise: Promise,
  localStorage: ls, atob: function (s) { return Buffer.from(s, 'base64').toString('latin1'); },
  btoa: function (s) { return Buffer.from(s, 'latin1').toString('base64'); }, Uint8Array: Uint8Array, Uint32Array: Uint32Array };
win.window = win;
var ctx = vm.createContext(win);
function load(f) { vm.runInContext(fs.readFileSync(path.join(JS, f), 'utf8'), ctx, { filename: f }); }
load('core.js');
var B = win.B;
load('build146.js');
var X = B.b146, P = X.pin;

function hex(s) { var u = new Uint8Array(s.length / 2); for (var i = 0; i < u.length; i++) u[i] = parseInt(s.substr(i * 2, 2), 16); return u; }
function ascii(s) { var u = new Uint8Array(s.length); for (var i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return u; }

// MARK: base64
eq('b64 salt 1', X.b64.encode(hex('000102030405060708090a0b0c0d0e0f')), 'AAECAwQFBgcICQoLDA0ODw==');
eq('b64 salt 2', X.b64.encode(ascii('Bingr-pin-test-1')), 'QmluZ3ItcGluLXRlc3QtMQ==');
eq('b64 salt 3', X.b64.encode(hex('fffefdfcfbfaf9f8f7f6f5f4f3f2f1f0')), '//79/Pv6+fj39vX08/Lx8A==');
eq('b64 ongeldig', X.b64.decode('Zm9v-mE='), null);
eq('b64 verkeerde lengte', X.b64.decode('Zm9'), null);

var VECTORS = [
  ['1234', 'AAECAwQFBgcICQoLDA0ODw==', 'hp5sg1DFvrCsw5n7qsO2DSIEM4lrJqZHc00NjxWG4fo='],
  ['0000', 'QmluZ3ItcGluLXRlc3QtMQ==', '+fGTeA7fJpt28Rrq3DjQxUFemZFqDW4eAb85/ZRYHg8='],
  ['98765432', '//79/Pv6+fj39vX08/Lx8A==', 'ZPbmm6a1FOjvmJWC0XNDHIDHyxK4739iaGZkx4Oid7c=']
];
eq('iteraties', P.ITER, 100000);
var s0 = X.b64.decode(VECTORS[0][1]);
eq('1 iteratie', X.b64.encode(P.pbkdf2Js('1234', s0, 1)), 'i/F57D1qcIBBiv5AzlKB8LqqXqCNwubIJuZH8P6K1uI=');
eq('2 iteraties', X.b64.encode(P.pbkdf2Js('1234', s0, 2)), 'oNCs4eO5peWpZXM8MGUdRebd/Uv8TI0RC7CQ7Ngh0N4=');
VECTORS.forEach(function (v) {
  eq('vector ' + v[0] + ' (pure JS)', X.b64.encode(P.pbkdf2Js(v[0], X.b64.decode(v[1]), P.ITER)), v[2]);
});

var H = { alg: 'pbkdf2-sha256', iter: 100000, salt: VECTORS[0][1], hash: VECTORS[0][2] };
eq('parse geldig', P.parse(H), H);
eq('parse extra velden', P.parse({ alg: H.alg, iter: H.iter, salt: H.salt, hash: H.hash, x: 1 }), H);
eq('parse ander alg', P.parse({ alg: 'sha256', iter: 100000, salt: H.salt, hash: H.hash }), null);
eq('parse te weinig iteraties', P.parse({ alg: H.alg, iter: 1000, salt: H.salt, hash: H.hash }), null);
eq('parse hash te kort', P.parse({ alg: H.alg, iter: 100000, salt: H.salt, hash: 'AAAA' }), null);
eq('parse null', P.parse(null), null);
ok('validPin', P.valid('0000') && P.valid('12345678') && !P.valid('123') && !P.valid('123456789') && !P.valid(1234) && !P.valid('12a4'));

// MARK: welke pincode geldt
var C = X.pinChoice;
eq('server: geldige hash', C.read({ pin: '1234', pinHash: H }).kind, 'hashed');
eq('server: hash null, pin tekst = oude app', C.read({ pin: '1234', pinHash: null }), { kind: 'legacy', pin: '1234' });
eq('server: alleen pin = oude app', C.read({ pin: '0123' }), { kind: 'legacy', pin: '0123' });
eq('server: pin null = geen pincode', C.read({ pin: null }).kind, 'cleared');
eq('server: hash null en pin null = verwijderd', C.read({ pin: null, pinHash: null }).kind, 'cleared');
eq('server: niets', C.read({ myList: [] }).kind, 'absent');
eq('server: ongeldige hash', C.read({ pin: '1234', pinHash: { alg: 'md5' } }).kind, 'invalid');
eq('server: pin geen cijfers', C.read({ pin: 'abcd' }).kind, 'cleared');
var other = { alg: H.alg, iter: H.iter, salt: X.b64.encode(new Uint8Array(16).fill(7)), hash: H.hash };
eq('kies: server nieuwer', C.pick(1, H, 2, other), 'server');
eq('kies: lokaal nieuwer', C.pick(3, H, 2, other), 'local');
eq('kies: gelijk en zelfde hash = lokaal', C.pick(2, H, 2, JSON.parse(JSON.stringify(H))), 'local');
eq('kies: gelijk, twee hashes = server (geen om-en-om)', C.pick(2, H, 2, other), 'server');
eq('kies: gelijk, lokaal nog oud en server hash = server', C.pick(2, '1234', 2, H), 'server');
eq('kies: gelijk, server leeg = lokaal', C.pick(2, H, 2, null), 'local');
eq('kies: gelijk, lokaal leeg = lokaal', C.pick(2, null, 2, H), 'local');

// MARK: profielen samenvoegen (zelfde gevallen als Android)
var M = X.profiles;
var t1 = '2026-10-01T10:00:00.000Z', t2 = '2026-10-02T10:00:00.000Z', t3 = '2026-10-03T10:00:00.000Z';
function row(k, at, del, main, order) { return { key: k, updatedAt: at, deleted: del, isMain: main, order: order }; }
var plan = M.plan([{ key: 'a', updatedAt: t2 }, { key: 'b', updatedAt: t1 }],
  [row('a', t1, false, true, 0), row('b', t2, false, false, 1), row('c', t2, false, false, 2), row('d', t1, true, false, null)], {}, 'a');
eq('samenvoegen: volgorde en soorten', plan.entries, [{ kind: 'keep', local: 0, row: 'a' }, { kind: 'take', local: 1, row: 'b' }, { kind: 'add', local: null, row: 'c' }]);
eq('samenvoegen: lokaal nieuwer → uploaden', plan.needUpload, true);
eq('samenvoegen: tijden', plan.stamps, { b: t2, c: t2 });
eq('samenvoegen: niets weg', plan.removed, []);
eq('samenvoegen: hoofdprofiel blijft', plan.adoptMain, null);
plan = M.plan([{ key: 'a', updatedAt: t1 }, { key: 'b', updatedAt: t1 }], [row('a', t2, true, false, null), row('b', t2, true, false, null)], {}, 'a');
eq('tombstone: B weg', plan.removed, [1]);
eq('tombstone: hoofdprofiel blijft', plan.entries, [{ kind: 'keep', local: 0, row: null }]);
eq('tombstone op hoofdprofiel → uploaden', plan.needUpload, true);
plan = M.plan([{ key: 'a', updatedAt: t1 }], [row('a', t1, false, true, 0), row('x', t1, false, false, 1)], { x: true }, 'a');
eq('lokale tombstone: x niet terug', plan.entries, [{ kind: 'keep', local: 0, row: 'a' }]);
eq('lokale tombstone: uploaden', plan.needUpload, true);
plan = M.plan([{ key: 'a', updatedAt: t1 }], [row('a', t1, false, true, 0)], {}, 'a');
eq('gelijk: geen upload', plan.needUpload, false);
eq('gelijk: geen tijden', plan.stamps, {});
plan = M.plan([{ key: 'a', updatedAt: t1 }, { key: 'b', updatedAt: t1 }], [row('a', t2, false, false, 1), row('b', t3, false, true, 0)], {}, 'a');
eq('hoofdprofiel overnemen', plan.adoptMain, 'b');
eq('volgorde volgt order van de server', plan.entries.map(function (e) { return e.row; }), ['b', 'a']);
plan = M.plan([{ key: 'a', updatedAt: null }], [], {}, 'a');
eq('alleen lokaal → uploaden', plan.needUpload, true);
plan = M.plan([{ key: 'a', updatedAt: t1 }], [row('a', t1, false, true, 0), row('z', t2, true, false, null)], {}, 'a');
eq('onbekende tombstone: niets', [plan.entries.length, plan.needUpload], [1, false]);

// MARK: support
var S = X.support;
eq('groep new', S.group('new'), 'open');
eq('groep open', S.group('open'), 'in_progress');
eq('groep pending', S.group('pending'), 'in_progress');
eq('groep resolved', S.group('resolved'), 'closed');
eq('groep closed', S.group('closed'), 'closed');
eq('groep van de server gaat voor', S.group('new', 'closed'), 'closed');
eq('label pending', S.statusKey('pending'), 'Awaiting your reply');
eq('badge 0', S.badge(0), null);
eq('badge 3', S.badge(3), '3');
eq('badge 120', S.badge(120), '99+');
eq('link lijst', S.parseLink('bingr://support'), { ticket: null });
eq('link ticket', S.parseLink('bingr://support/ticket?n=1042'), { ticket: 1042 });
eq('link ticket hoofdletters', S.parseLink('BINGR://Support/Ticket?x=1&n=7'), { ticket: 7 });
eq('link ticket zonder nummer = lijst', S.parseLink('bingr://support/ticket?n=abc'), { ticket: null });
eq('link ander', S.parseLink('bingr://live?channel=1'), null);
eq('formulier goed', S.checkNew('Hallo daar', 'Mijn stream start niet.'), null);
eq('formulier onderwerp kort', S.checkNew('Hi', 'Mijn stream start niet.'), 'subject_missing');
eq('formulier bericht kort', S.checkNew('Hallo', 'kort'), 'message_too_short');
eq('formulier onderwerp lang', S.checkNew(new Array(122).join('x'), 'Mijn stream start niet.'), 'subject_too_long');
eq('formulier te veel links', S.checkNew('Links', [1, 2, 3, 4, 5, 6, 7].map(function (i) { return 'https://a' + i + '.nl'; }).join(' ')), 'too_many_links');
eq('reactie leeg', S.checkReply('  '), 'message_missing');
eq('reactie 1 teken', S.checkReply('!'), null);
eq('categorieën', S.CATEGORIES, ['account', 'afspelen', 'apps', 'website', 'overig']);

// MARK: diagnose
var G = X.diag;
ok('mijlpaalnamen', G.validMilestone('servers_loaded') && G.validMilestone('first_frame') && !G.validMilestone('Gestart') && !G.validMilestone('9x'));
eq('opschonen url', G.scrub('fout bij http://user:pw@host.tv:8080/get.php?x=1 klaar'), 'fout bij <url> klaar');
eq('opschonen e-mail', G.scrub('mail a.b@example.com'), 'mail <e>');
eq('opschonen ip', G.scrub('verbinding met 192.168.1.20 mislukt'), 'verbinding met <ip> mislukt');
eq('opschonen mac', G.scrub('mac 00:1A:79:12:34:56'), 'mac <mac>');
eq('opschonen geheim', G.scrub('password=geheim&x'), 'password=…&x');
eq('stack max 50', G.stack(new Array(81).join('x').split('').map(function (_, i) { return 'at f' + i; })).length, 50);
var r = { build: 146, os: 'Tizen 6.0', started: 1000, marks: [], finished: false };
r = G.mark(r, 'start', 0, null); r = G.mark(r, 'splash', 400, 140); r = G.mark(r, 'splash', 900, 999); r = G.mark(r, 'Fout', 5, 1);
eq('mijlpalen eenmalig en geldig', r.marks.map(function (m) { return m.name; }), ['start', 'splash']);
eq('hoogste geheugen', r.peakMB, 140);
eq('niet klaar', G.analyze(r).kind, 'notFinished');
eq('niet klaar: laatste mijlpaal', G.analyze(r).lastMark, 'splash');
var done = G.mark(r, 'ready', 2000, 150);
eq('klaar = geen probleem', G.analyze(done), null);
eq('na klaar geen mijlpalen meer', G.mark(done, 'late', 3000, 1).marks.length, 3);
var crashed = B.assign({}, r, { crash: { at: '2026-10-24T10:00:00.000Z', type: 'TypeError', reason: 'x', stack: ['at a (app.js:1)'], build: 146 } });
eq('fout', G.analyze(crashed).kind, 'crash');
eq('hang', G.analyze(B.assign({}, done, { hangMs: 12000, hangAt: 'home' })).kind, 'hang');
eq('korte hang telt niet', G.analyze(B.assign({}, done, { hangMs: 4000 })), null);
ok('tekst', G.text(G.analyze(crashed)).indexOf('Stack:\nat a (app.js:1)') >= 0);
eq('snelheid ok', G.speed(300, true), 'ok');
eq('snelheid traag', G.speed(2500, true), 'slow');
eq('snelheid fout', G.speed(null, false), 'fail');

// MARK: tv-gids verlopen
var now = 1000000000;
eq('gids: geen gids = niet verlopen', X.epg.expired(now, []), false);
eq('gids: loopt nu', X.epg.expired(now, [[now - 1000, now + 1000]]), false);
eq('gids: begint straks', X.epg.expired(now, [[now + 3600000, now + 7200000]]), false);
eq('gids: alles voorbij = verlopen', X.epg.expired(now, [[now - 7200000, now - 3600000]]), true);
eq('gids: pas over 4 uur = verlopen', X.epg.expired(now, [[now + 4 * 3600000, now + 5 * 3600000]]), true);

// MARK: asynchroon: pure JS in stukjes, WebCrypto, make/verify/check
function async() {
  // zonder WebCrypto (zoals de Tizen-webview): pure JS in stukjes
  win.crypto = undefined;
  return P.pbkdf2Chunked('0000', X.b64.decode(VECTORS[1][1]), P.ITER, 7000).then(function (h) {
    eq('vector 0000 (in stukjes)', X.b64.encode(h), VECTORS[1][2]);
    return P.verify('1234', H);
  }).then(function (v) {
    ok('verify zonder WebCrypto', v);
    return P.check('1235', H);
  }).then(function (v) {
    ok('andere pin fout', !v);
    // met WebCrypto (veilige context)
    win.crypto = nodeCrypto.webcrypto;
    return Promise.all(VECTORS.map(function (v) { return P.derive(v[0], X.b64.decode(v[1]), P.ITER); }));
  }).then(function (hs) {
    hs.forEach(function (h, i) { eq('vector ' + VECTORS[i][0] + ' (WebCrypto)', X.b64.encode(h), VECTORS[i][2]); });
    return Promise.all([P.check('01234', H), P.check('12a4', H), P.check('1234', null), P.check('1234', '1234'), P.check('1235', '1234')]);
  }).then(function (rs) {
    eq('voorloopnul / geen cijfers / null / oude pincode goed / oude pincode fout', rs, [false, false, false, true, false]);
    return Promise.all([P.make('4321'), P.make('4321'), P.make('1234', X.b64.decode(VECTORS[0][1]))]);
  }).then(function (hs) {
    eq('make: formaat', Object.keys(hs[0]), ['alg', 'iter', 'salt', 'hash']);
    eq('make: salt 16 bytes', X.b64.decode(hs[0].salt).length, 16);
    ok('make: nieuwe salt', hs[0].salt !== hs[1].salt && hs[0].hash !== hs[1].hash);
    eq('make: vaste salt = testvector', hs[2].hash, VECTORS[0][2]);
    return Promise.all([P.verify('4321', hs[0]), P.verify('4321', hs[1])]);
  }).then(function (rs) { eq('make: beide kloppen', rs, [true, true]); });
}

async().then(function () {
  console.log(failures === 0 ? 'Build146 (Samsung TV): alle ' + checks + ' controles geslaagd' : 'Build146 (Samsung TV): ' + failures + ' van ' + checks + ' controles mislukt');
  process.exit(failures ? 1 : 0);
}, function (e) { console.log('FOUT ' + (e && e.stack || e)); process.exit(1); });
