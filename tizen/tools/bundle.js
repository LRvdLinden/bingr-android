#!/usr/bin/env node
// build 97: bundelt de app-scripts van de Tizen-app in de pakketmap (niet in tizen/ zelf; de losse bronbestanden blijven).
//   node tizen/tools/bundle.js <pakketmap>
// - leest de volgorde van de <script>-tags uit <pakketmap>/index.html
// - js/*.js (behalve strings.js) → één js/bingr.min.js; met terser (als die te laden is, NODE_PATH) verkleind tot ES2017,
//   anders alleen aan elkaar geplakt
// - bibliotheken (js/lib/) blijven losse bestanden; qrcode.js laadt de app pas als er een QR-code nodig is
// - strings.js (alle talen) gaat eruit: js/boot.js laadt alleen js/strings/<taal>.js (Engels = de sleutels zelf)
// - de losse app-scripts en strings.js worden uit de pakketmap gehaald
'use strict';
var fs = require('fs');
var path = require('path');

var STAGE = path.resolve(process.argv[2] || '');
var INDEX = path.join(STAGE, 'index.html');
if (!process.argv[2] || !fs.existsSync(INDEX)) { console.error('gebruik: bundle.js <pakketmap met index.html>'); process.exit(2); }

var html = fs.readFileSync(INDEX, 'utf8');
var tagRe = /[ \t]*<script src="(js\/[^"]+)"><\/script>\r?\n?/g;
var scripts = [], m;
while ((m = tagRe.exec(html))) scripts.push(m[1]);
if (!scripts.length) { console.error('geen app-scripts in index.html'); process.exit(1); }

var bundled = scripts.filter(function (s) { return s.indexOf('js/lib/') !== 0 && s !== 'js/strings.js'; });
// bibliotheken die bij het starten nodig zijn (alles behalve qrcode.js) blijven een eigen script-tag
var libs = scripts.filter(function (s) { return s.indexOf('js/lib/') === 0 && !/qrcode\.js$/.test(s); });
if (!fs.existsSync(path.join(STAGE, 'js', 'boot.js'))) { console.error('js/boot.js ontbreekt (eerst make_strings.py --split)'); process.exit(1); }

var parts = bundled.map(function (s) {
  var src = fs.readFileSync(path.join(STAGE, s), 'utf8');
  return '/* ' + s + ' */\n' + src.replace(/\s+$/, '') + '\n;';
});
var code = parts.join('\n');

function finish(out, how) {
  fs.writeFileSync(path.join(STAGE, 'js', 'bingr.min.js'), out);
  // index.html: de eerste script-tag wordt boot + bibliotheken + bundel, de rest verdwijnt
  var first = true;
  html = html.replace(tagRe, function (tag) {
    if (!first) return '';
    first = false;
    var indent = (/^[ \t]*/.exec(tag) || [''])[0];
    return ['js/boot.js'].concat(libs, ['js/bingr.min.js']).map(function (s) { return indent + '<script src="' + s + '"></script>\n'; }).join('');
  });
  fs.writeFileSync(INDEX, html);
  bundled.forEach(function (s) { fs.unlinkSync(path.join(STAGE, s)); });
  try { fs.unlinkSync(path.join(STAGE, 'js', 'strings.js')); } catch (e) { /* al weg */ }
  console.log('bundel: ' + bundled.length + ' scripts → js/bingr.min.js (' + Math.round(out.length / 1024) + ' KB, ' + how + ')');
}

var terser = null;
try { terser = require('terser'); } catch (e) { terser = null; }
if (!terser) {
  console.log('let op: terser niet gevonden (NODE_PATH); bundel wordt niet verkleind');
  finish(code, 'niet verkleind');
} else {
  // ecma 2017: terser maakt er geen nieuwere syntax van (?. ?? enz.); B blijft B (check_compat herkent eigen modules)
  Promise.resolve(terser.minify(code, {
    ecma: 2017,
    compress: { ecma: 2017, passes: 1 },
    mangle: { reserved: ['B'] },
    format: { ecma: 2017, comments: false }
  })).then(function (r) {
    if (!r || typeof r.code !== 'string') throw new Error('terser gaf geen code');
    finish(r.code, 'terser');
  }).catch(function (e) {
    console.error('terser mislukt: ' + (e && e.message));
    process.exit(1);
  });
}
