#!/usr/bin/env node
// Controleert of de Tizen-app draait op oude Samsung-tv's (Tizen 4.0 = Chromium M56, Tizen 5.0 = M63).
//   npm i --no-save acorn@8 acorn-walk@8 && node tizen/tools/check_compat.js
// - JS: moet als ES2017 te parsen zijn (geen ?. ?? ...spread in objecten, optionele catch, class fields)
// - JS: geen API's die M56 niet kent (flat, fromEntries, replaceAll, allSettled, finally, padStart, ...)
// - CSS: geen eigenschappen/selectors die M56 niet kent (gap in flexbox, grid, aspect-ratio, inset, :is, ...)
// Exitcode 1 bij bevindingen.
'use strict';
var fs = require('fs');
var path = require('path');
var acorn = require('acorn');
var walk = require('acorn-walk');

var TIZEN = path.resolve(__dirname, '..');
var problems = [];
function report(file, line, msg) { problems.push(path.relative(TIZEN, file) + ':' + line + ': ' + msg); }

function lineOf(src, idx) { return src.slice(0, idx).split('\n').length; }

// API's die op Chromium M56 ontbreken (methode-namen; alleen als eigenschap-aanroep gezocht)
var BAD_METHODS = Object.assign(Object.create(null), {
  flat: 'Array.prototype.flat (M69)', flatMap: 'Array.prototype.flatMap (M69)', fromEntries: 'Object.fromEntries (M73)',
  replaceAll: 'String.replaceAll (M85)', allSettled: 'Promise.allSettled (M76)', any: null, matchAll: 'String.matchAll (M73)',
  padStart: 'String.padStart (M57)', padEnd: 'String.padEnd (M57)', trimStart: 'String.trimStart (M66)', trimEnd: 'String.trimEnd (M66)',
  finally: 'Promise.finally (M63)', at: 'Array/String.at (M92)', findLast: 'findLast (M97)', findLastIndex: 'findLastIndex (M97)',
  structuredClone: 'structuredClone (M98)', toSorted: 'toSorted (M110)', hasOwn: 'Object.hasOwn (M93)',
  getOwnPropertyDescriptors: null, toggleAttribute: 'Element.toggleAttribute (M69)', replaceChildren: 'replaceChildren (M86)',
  requestIdleCallback: null
});
// globale constructors/objecten die M56 niet kent
var BAD_GLOBALS = Object.assign(Object.create(null), {
  AbortController: 'AbortController (M66)', ResizeObserver: 'ResizeObserver (M64)', globalThis: 'globalThis (M71)',
  queueMicrotask: 'queueMicrotask (M71)', BigInt: 'BigInt (M67)', WeakRef: 'WeakRef (M84)'
});

function checkJS(file) {
  var src = fs.readFileSync(file, 'utf8');
  var ast;
  try {
    ast = acorn.parse(src, { ecmaVersion: 2017, sourceType: 'script', locations: true, allowHashBang: true });
  } catch (e) {
    report(file, e.loc ? e.loc.line : '?', 'geen ES2017: ' + e.message);
    return;
  }
  walk.full(ast, function (n) {
    // alleen aanroepen (x.flat()), want velden als reminder.at zijn gewone eigenschappen
    var c = n.type === 'CallExpression' && n.callee.type === 'MemberExpression' && !n.callee.computed ? n.callee.property : null;
    // eigen modules (B.subs.at) zijn geen browser-API
    var own = c && n.callee.object.type === 'MemberExpression' && n.callee.object.object.type === 'Identifier' && n.callee.object.object.name === 'B';
    if (c && !own && c.type === 'Identifier' && BAD_METHODS[c.name]) {
      report(file, n.loc.start.line, BAD_METHODS[c.name]);
    }
    if (n.type === 'Identifier' && BAD_GLOBALS[n.name]) {
      report(file, n.loc.start.line, BAD_GLOBALS[n.name]);
    }
    if (n.type === 'Literal' && n.regex) {
      var r = n.regex;
      if (/\(\?<[=!]/.test(r.pattern)) report(file, n.loc.start.line, 'regex lookbehind (M62)');
      if (/\(\?<[A-Za-z]/.test(r.pattern)) report(file, n.loc.start.line, 'regex named groups (M64)');
      if (/\\[pP]\{/.test(r.pattern) && r.flags.indexOf('u') >= 0) report(file, n.loc.start.line, 'regex unicode property (M64)');
      if (r.flags.indexOf('s') >= 0) report(file, n.loc.start.line, 'regex flag s (M62)');
    }
    if (n.type === 'CallExpression' && n.callee.type === 'MemberExpression' && !n.callee.computed &&
        n.callee.property.name === 'scrollIntoView' && n.arguments.length && n.arguments[0].type === 'ObjectExpression') {
      report(file, n.loc.start.line, 'scrollIntoView met opties (M61)');
    }
  });
}

// CSS: eenvoudige tekstcontrole per declaratie/selector
var CSS_RULES = [
  [/(^|[;{\s])(row-|column-)?gap\s*:/, 'gap (in flexbox pas M84; gebruik marges)'],
  [/(^|[;{\s])grid-gap\s*:/, 'grid-gap (CSS grid pas M57)'],
  [/display\s*:\s*(inline-)?grid/, 'CSS grid (M57)'],
  [/grid-template/, 'CSS grid (M57)'],
  [/aspect-ratio\s*:/, 'aspect-ratio (M88)'],
  [/(^|[;{\s])inset\s*:/, 'inset (M87)'],
  [/:is\(/, ':is() (M88)'], [/:where\(/, ':where() (M88)'], [/:focus-visible/, ':focus-visible (M86)'],
  [/:focus-within/, ':focus-within (M60)'],
  [/backdrop-filter\s*:/, 'backdrop-filter (M76)'],
  [/display\s*:\s*contents/, 'display: contents (M65)'],
  [/(^|[^-\w])(clamp|min|max)\(/, 'clamp()/min()/max() (M79)'],
  [/overscroll-behavior/, 'overscroll-behavior (M63)'],
  [/scroll-behavior\s*:/, 'scroll-behavior (M61)'],
  [/place-(items|content|self)\s*:/, 'place-* (M59)'],
  [/\benv\(/, 'env() (M69)'],
  [/#[0-9a-fA-F]{8}\b|#[0-9a-fA-F]{4}\b(?![-\w])/, 'kleur #rrggbbaa (M62)'],
  [/rgba?\([^)]*\/[^)]*\)/, 'rgb() met / (M65)'],
  [/text-decoration-thickness|text-underline-offset/, 'text-decoration-* (M87)'],
  [/content-visibility/, 'content-visibility (M85)'],
  [/:not\([^)]*,/, ':not() met een lijst (M88)'],
  [/::placeholder/, '::placeholder (M57; gebruik ::-webkit-input-placeholder)'],
  [/(^|[;{\s])translate\s*:|(^|[;{\s])scale\s*:|(^|[;{\s])rotate\s*:/, 'losse translate/scale/rotate (M104)']
];

function stripComments(s) { return s.replace(/\/\*[\s\S]*?\*\//g, function (m) { return m.replace(/[^\n]/g, ' '); }); }

function checkCSS(file, src, offsetLine) {
  src = stripComments(src);
  var lines = src.split('\n');
  lines.forEach(function (l, i) {
    CSS_RULES.forEach(function (r) {
      if (r[0].test(l)) report(file, (offsetLine || 0) + i + 1, 'CSS ' + r[1]);
    });
  });
}

// CSS die in JS-strings staat (style.gap = ..., cssText met gap:)
function checkInlineStyles(file) {
  var src = fs.readFileSync(file, 'utf8');
  var re = /\.style\.(gap|rowGap|columnGap|aspectRatio|inset|backdropFilter)\b|['"][^'"\n]*\b(gap|aspect-ratio|inset|backdrop-filter)\s*:[^'"\n]*['"]/g;
  var m;
  while ((m = re.exec(src))) report(file, lineOf(src, m.index), 'inline-stijl ' + (m[1] || m[2]) + ' (niet in M56)');
}

function walkDir(dir, out) {
  fs.readdirSync(dir).forEach(function (f) {
    var p = path.join(dir, f);
    if (fs.statSync(p).isDirectory()) walkDir(p, out); else out.push(p);
  });
  return out;
}

walkDir(path.join(TIZEN, 'js'), []).forEach(function (f) {
  if (!/\.js$/.test(f)) return;
  var lib = f.indexOf(path.sep + 'lib' + path.sep) >= 0;
  if (lib) {
    // bibliotheken: alleen de syntax controleren (ze testen zelf op API's)
    try { acorn.parse(fs.readFileSync(f, 'utf8'), { ecmaVersion: 2017, sourceType: 'script' }); } catch (e) { report(f, e.loc ? e.loc.line : '?', 'geen ES2017: ' + e.message); }
    return;
  }
  checkJS(f);
  checkInlineStyles(f);
});
walkDir(path.join(TIZEN, 'css'), []).forEach(function (f) { if (/\.css$/.test(f)) checkCSS(f, fs.readFileSync(f, 'utf8')); });
var html = fs.readFileSync(path.join(TIZEN, 'index.html'), 'utf8');
var sm = /<style[^>]*>([\s\S]*?)<\/style>/g, mm;
while ((mm = sm.exec(html))) checkCSS(path.join(TIZEN, 'index.html'), mm[1], lineOf(html, mm.index) - 1);

if (problems.length) {
  console.log(problems.join('\n'));
  console.log('\n' + problems.length + ' bevinding(en): niet geschikt voor Chromium M56 (Tizen 4.0)');
  process.exit(1);
}
console.log('Tizen-compatibiliteit: geen bevindingen (ES2017, Chromium M56)');
