/*
 * UI-basis voor de afstandsbediening: schermstapel, ruimtelijke focus (pijltjes), scrollen naar de focus,
 * toetsen (Enter, Terug = 10009, mediatoetsen) en kleine bouwstenen (knoppen, modals, toetsenbord, toasts).
 */
(function () {
  'use strict';
  var B = window.B;
  var L = function () { return B.L.apply(null, arguments); };
  var U = B.ui = {};

  // MARK: DOM-hulp

  /** h('div.class#id', {attrs}, [children|tekst]) */
  U.h = function (sel, attrs, kids) {
    sel = String(sel).replace(/\s+/g, '.');
    var m = String(sel).match(/^([a-z0-9]+)?((?:[.#][\w-]+)*)$/i) || [];
    var el = document.createElement(m[1] || 'div');
    (m[2] || '').replace(/([.#])([\w-]+)/g, function (x, t, v) { if (t === '.') el.classList.add(v); else el.id = v; return ''; });
    if (attrs && (typeof attrs !== 'object' || Array.isArray(attrs) || attrs.nodeType)) { kids = attrs; attrs = null; }
    if (attrs) {
      for (var k in attrs) {
        if (!B.has(attrs, k) || attrs[k] == null || attrs[k] === false) continue;
        var v = attrs[k];
        if (k === 'text') el.textContent = v;
        else if (k === 'html') el.innerHTML = v;
        else if (k === 'style' && typeof v === 'object') B.assign(el.style, v);
        else if (k === 'onEnter' || k === 'onFocus' || k === 'onBlur' || k === 'onKey') el[k] = v;
        else if (k === 'f') { if (v) el.classList.add('f'); }
        else el.setAttribute(k, v === true ? '' : v);
      }
    }
    U.append(el, kids);
    return el;
  };
  U.append = function (el, kids) {
    if (kids == null) return el;
    if (!Array.isArray(kids)) kids = [kids];
    kids.forEach(function (c) {
      if (c == null || c === false) return;
      el.appendChild(typeof c === 'string' || typeof c === 'number' ? document.createTextNode(String(c)) : c);
    });
    return el;
  };
  U.clear = function (el) { while (el && el.firstChild) el.removeChild(el.firstChild); return el; };
  U.icon = function (name, cls) {
    var paths = U.icons[name] || '';
    var span = document.createElement('span');
    span.className = 'ic' + (cls ? ' ' + cls : '');
    span.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' + paths + '</svg>';
    return span;
  };
  U.icons = {
    search: '<circle cx="10.5" cy="10.5" r="6.5" fill="none" stroke="currentColor" stroke-width="2.2"/><path d="M15.5 15.5L21 21" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/>',
    home: '<path d="M3 11.2L12 3.5l9 7.7V21h-6.2v-6.3H9.2V21H3z" fill="currentColor"/>',
    live: '<rect x="2.5" y="5" width="19" height="13" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M8 21h8M8.5 1.8L12 5l3.5-3.2" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round"/>',
    movies: '<rect x="3" y="3" width="18" height="18" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M7 3v18M17 3v18M3 8h4M3 12h4M3 16h4M17 8h4M17 12h4M17 16h4" stroke="currentColor" stroke-width="1.8"/>',
    series: '<rect x="2.5" y="6" width="19" height="14" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M6 3h12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M10 10v6l5-3z" fill="currentColor"/>',
    list: '<path d="M12 4v16M4 12h16" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"/>',
    check: '<path d="M4.5 12.5l5 5L19.5 7" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>',
    gear: '<path d="M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7zm8.4 5l2-1.5-2-3.5-2.4.9a7.6 7.6 0 0 0-1.8-1L15.8 6h-4l-.4 2.4a7.6 7.6 0 0 0-1.8 1l-2.4-.9-2 3.5 2 1.5a7.6 7.6 0 0 0 0 2l-2 1.5 2 3.5 2.4-.9c.6.4 1.1.7 1.8 1l.4 2.4h4l.4-2.4c.7-.3 1.2-.6 1.8-1l2.4.9 2-3.5-2-1.5c.1-.7.1-1.3 0-2z" fill="currentColor" transform="translate(-1.8 -2) scale(1.08)"/>',
    play: '<path d="M7 4.5v15l12.5-7.5z" fill="currentColor"/>',
    pause: '<path d="M6.5 4.5h4v15h-4zM13.5 4.5h4v15h-4z" fill="currentColor"/>',
    info: '<circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 10.5v6.5M12 7v.5" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/>',
    lock: '<rect x="5" y="10.5" width="14" height="10" rx="2" fill="currentColor"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" fill="none" stroke="currentColor" stroke-width="2.2"/>',
    back: '<path d="M15 4.5L7.5 12l7.5 7.5" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>',
    next: '<path d="M5 4.5v15l10-7.5zM16.5 4.5h3v15h-3z" fill="currentColor"/>',
    audio: '<path d="M4 9.5h4l5-4.5v14l-5-4.5H4z" fill="currentColor"/><path d="M16 8.5a5 5 0 0 1 0 7M18.5 6a8.5 8.5 0 0 1 0 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    subs: '<rect x="2.5" y="5" width="19" height="14" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M6 13.5h5M13 13.5h5M6 16.5h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    guide: '<rect x="2.5" y="4" width="19" height="16" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M2.5 9h19M9 9v11M15 9v11" stroke="currentColor" stroke-width="1.8"/>',
    star: '<path d="M12 3.5l2.6 5.6 6.1.6-4.6 4.1 1.3 6-5.4-3.1-5.4 3.1 1.3-6L3.3 9.7l6.1-.6z" fill="currentColor"/>',
    replay: '<path d="M5 12a7 7 0 1 0 2.1-5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/><path d="M4 3.5v5h5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
    swap: '<path d="M4 8h14l-3.5-3.5M20 16H6l3.5 3.5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
    backspace: '<path d="M8.5 5H21v14H8.5L2.5 12z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M11.5 9l6 6M17.5 9l-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    space: '<path d="M4 10v4h16v-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
    tv: '<rect x="2.5" y="4.5" width="19" height="13" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M8 20.5h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    phone: '<rect x="6.5" y="2.5" width="11" height="19" rx="2.2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M10.5 18.5h3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    laptop: '<rect x="4.5" y="5" width="15" height="10" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M2 18.5h20" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>'
  };
  U.logo = function (small) {
    return U.h('div.logo' + (small ? '.small' : ''), [U.h('div.logo-word', 'BINGR'), U.h('div.logo-bar')]);
  };
  U.spinner = function () { return U.h('div.spinner'); };

  // MARK: schermstapel

  U.root = null;
  U.stack = [];
  U.top = function () { return U.stack[U.stack.length - 1] || null; };
  /** scherm = {el, onKey(naam, e), onBack(), onShow(), onHide(), destroy(), modal} */
  U.push = function (s) {
    var prev = U.top();
    if (prev && !s.modal) { prev.lastFocus = U.focused; prev.el.classList.add('hidden'); if (prev.onHide) prev.onHide(); }
    else if (prev) prev.lastFocus = U.focused;
    U.stack.push(s);
    U.root.appendChild(s.el);
    if (s.onShow) s.onShow();
    if (!s.el.contains(U.focused)) U.focusFirst(s.el);
    return s;
  };
  U.pop = function () {
    var s = U.stack.pop();
    if (!s) return;
    if (s.onHide) s.onHide();
    if (s.destroy) s.destroy();
    if (s.el.parentNode) s.el.parentNode.removeChild(s.el);
    var t = U.top();
    if (t) {
      t.el.classList.remove('hidden');
      if (t.onShow) t.onShow(true);
      if (t.lastFocus && t.el.contains(t.lastFocus) && visible(t.lastFocus)) U.focus(t.lastFocus);
      else U.focusFirst(t.el);
    }
  };
  U.popTo = function (s) { while (U.stack.length && U.top() !== s) U.pop(); };
  U.reset = function (s) {
    while (U.stack.length) U.pop();
    U.push(s);
  };

  // MARK: focus

  U.focused = null;
  U.focus = function (el, silent) {
    if (!el) return;
    var prev = U.focused;
    if (prev === el) return;
    if (prev) { prev.classList.remove('focused'); if (prev.onBlur) prev.onBlur(); }
    U.focused = el;
    el.classList.add('focused');
    // container met focusgeheugen onthoudt het laatst gekozen kind
    var p = el.parentNode;
    while (p && p !== document.body) {
      if (p.classList && p.classList.contains('fm')) p._last = el;
      p = p.parentNode;
    }
    U.reveal(el);
    if (!silent && el.onFocus) el.onFocus();
  };
  function visible(el) {
    if (!el || !el.isConnected && !document.body.contains(el)) return false;
    if (el.classList.contains('disabled')) return false;
    return el.offsetParent !== null || el.offsetWidth > 0;
  }
  U.focusables = function (root) {
    var all = (root || U.layer()).querySelectorAll('.f');
    var out = [];
    for (var i = 0; i < all.length; i++) if (visible(all[i])) out.push(all[i]);
    return out;
  };
  U.layer = function () { var t = U.top(); return t ? t.el : document.body; };
  U.focusFirst = function (root) {
    var pref = (root || U.layer()).querySelector('.f.autofocus');
    if (pref && visible(pref)) { U.focus(pref); return; }
    var list = U.focusables(root);
    if (list.length) U.focus(list[0]);
  };

  /** Ruimtelijke navigatie: dichtstbijzijnde focusbare in die richting, met voorkeur voor dezelfde rij/kolom. */
  U.move = function (dir) {
    var cur = U.focused;
    var layer = U.layer();
    if (!cur || !layer.contains(cur)) { U.focusFirst(layer); return true; }
    var a = cur.getBoundingClientRect();
    var ax = a.left + a.width / 2, ay = a.top + a.height / 2;
    var best = null, bestScore = Infinity;
    var list = U.focusables(layer);
    // omhoog/omlaag blijft binnen dezelfde zone (bijv. niet van de inhoud naar het menu links)
    var zoneOf = function (e) { return e.closest ? e.closest('[data-zone]') : null; };
    var zone = (dir === 'up' || dir === 'down') ? zoneOf(cur) : null;
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el === cur) continue;
      if (zone && zoneOf(el) !== zone) continue;
      var b = el.getBoundingClientRect();
      var bx = b.left + b.width / 2, by = b.top + b.height / 2;
      var primary, secondary;
      // het midden van de kandidaat moet echt in die richting liggen (voorbij de rand van het huidige element)
      if (dir === 'left') { if (bx >= a.left + 2) continue; primary = a.left - b.right; secondary = Math.abs(by - ay); }
      else if (dir === 'right') { if (bx <= a.right - 2) continue; primary = b.left - a.right; secondary = Math.abs(by - ay); }
      else if (dir === 'up') { if (by >= a.top + 2) continue; primary = a.top - b.bottom; secondary = Math.abs(bx - ax); }
      else { if (by <= a.bottom - 2) continue; primary = b.top - a.bottom; secondary = Math.abs(bx - ax); }
      if (primary < -4) primary = 0;
      var score = primary + secondary * (dir === 'left' || dir === 'right' ? 3 : 0.6);
      // binnen dezelfde rij blijven bij links/rechts
      if ((dir === 'left' || dir === 'right') && Math.abs(by - ay) > Math.max(a.height, b.height) * 0.6) score += 5000;
      if (score < bestScore) { bestScore = score; best = el; }
    }
    if (!best) return false;
    // naar een andere container met focusgeheugen: het laatst gekozen kind daarvan
    var fm = best.closest ? best.closest('.fm') : null;
    if (fm && !fm.contains(cur) && fm._last && fm.contains(fm._last) && visible(fm._last)) best = fm._last;
    U.focus(best);
    return true;
  };

  /** Scrollers (.sx horizontaal, .sy verticaal) zo zetten dat het element in beeld is. */
  U.reveal = function (el) {
    var p = el.parentNode;
    while (p && p !== document.body) {
      if (p.classList) {
        if (p.classList.contains('sx')) {
          var pad = parseInt(p.getAttribute('data-pad') || '60', 10);
          var x = elLeftIn(el, p);
          if (x - pad < p.scrollLeft) p.scrollLeft = Math.max(0, x - pad);
          else if (x + el.offsetWidth + pad > p.scrollLeft + p.clientWidth) p.scrollLeft = x + el.offsetWidth + pad - p.clientWidth;
        }
        if (p.classList.contains('sy')) {
          var padY = parseInt(p.getAttribute('data-pad') || '80', 10);
          var y = elTopIn(el, p);
          var mode = p.getAttribute('data-align');
          if (mode === 'top') {
            var anchor = el.closest('.row-block') || el;
            var ty = elTopIn(anchor, p) - padY;
            p.scrollTop = Math.max(0, ty);
          } else if (y - padY < p.scrollTop) p.scrollTop = Math.max(0, y - padY);
          else if (y + el.offsetHeight + padY > p.scrollTop + p.clientHeight) p.scrollTop = y + el.offsetHeight + padY - p.clientHeight;
        }
      }
      p = p.parentNode;
    }
  };
  function elLeftIn(el, container) {
    return el.getBoundingClientRect().left - container.getBoundingClientRect().left + container.scrollLeft;
  }
  function elTopIn(el, container) {
    return el.getBoundingClientRect().top - container.getBoundingClientRect().top + container.scrollTop;
  }

  // MARK: toetsen

  var KEYS = {
    37: 'left', 38: 'up', 39: 'right', 40: 'down', 13: 'enter', 10009: 'back', 8: 'back', 27: 'back', 461: 'back',
    415: 'play', 19: 'pause', 10252: 'playpause', 413: 'stop', 417: 'ff', 412: 'rw', 427: 'chup', 428: 'chdown',
    33: 'chup', 34: 'chdown', 457: 'info', 403: 'red', 404: 'green', 405: 'yellow', 406: 'blue', 10182: 'exit',
    65376: 'done', 65385: 'cancel', 32: 'playpause'
  };
  U.keyName = function (e) {
    var c = e.keyCode;
    if (c >= 48 && c <= 57) return 'd' + (c - 48);
    if (c >= 96 && c <= 105) return 'd' + (c - 96);
    return KEYS[c] || null;
  };
  /** Mediatoetsen en cijfers aanmelden bij de tv (anders komen ze niet bij de app). */
  U.registerKeys = function () {
    try {
      if (!window.tizen || !tizen.tvinputdevice) return;
      var names = { MediaPlayPause: 'playpause', MediaPlay: 'play', MediaPause: 'pause', MediaStop: 'stop',
        MediaFastForward: 'ff', MediaRewind: 'rw', ChannelUp: 'chup', ChannelDown: 'chdown', Info: 'info',
        ColorF0Red: 'red', ColorF1Green: 'green', ColorF2Yellow: 'yellow', ColorF3Blue: 'blue' };
      var digits = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];
      tizen.tvinputdevice.getSupportedKeys().forEach(function (k) {
        var isDigit = digits.indexOf(k.name) >= 0;
        if (!B.has(names, k.name) && !isDigit) return;
        try { tizen.tvinputdevice.registerKey(k.name); } catch (e) { /* stil */ }
        // de code van deze tv gebruiken (meestal gelijk aan de standaard)
        if (!isDigit) KEYS[k.code] = names[k.name];
      });
    } catch (e) { B.log('keys', e); }
  };

  U.onKeyDown = function (e) {
    var name = U.keyName(e);
    if (!name) return;
    // tekstvelden (alleen in de test-browser) houden hun letters
    if (e.target && e.target.tagName === 'INPUT' && name === 'back' && e.keyCode === 8) return;
    var top = U.top();
    var handled = false;
    if (U.focused && U.focused.onKey) handled = U.focused.onKey(name, e) === true;
    if (!handled && top && top.onKey) handled = top.onKey(name, e) === true;
    if (!handled) {
      if (name === 'left' || name === 'right' || name === 'up' || name === 'down') { U.move(name); handled = true; }
      else if (name === 'enter') { if (U.focused && U.focused.onEnter) { U.focused.onEnter(); handled = true; } }
      else if (name === 'back') {
        if (top && top.onBack) { if (top.onBack() !== false) handled = true; }
        else if (U.stack.length > 1) { U.pop(); handled = true; }
      }
    }
    if (handled) { e.preventDefault(); e.stopPropagation(); }
  };

  // MARK: bouwstenen

  U.button = function (label, onEnter, opts) {
    opts = opts || {};
    var kids = [];
    if (opts.icon) kids.push(U.icon(opts.icon));
    if (label) kids.push(U.h('span.lbl', label));
    var cls = opts.cls ? '.' + String(opts.cls).split(/\s+/).join('.') : '';
    var el = U.h('div.btn.f' + (opts.primary ? '.primary' : '') + cls + (opts.autofocus ? '.autofocus' : ''), kids);
    el.onEnter = onEnter;
    return el;
  };

  U.toast = function (text, ms) {
    var t = U.h('div.toast', text);
    document.body.appendChild(t);
    setTimeout(function () { t.classList.add('show'); }, 10);
    setTimeout(function () { t.classList.remove('show'); setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 400); }, ms || 3000);
  };

  /** Modal: {title, text, buttons:[{label, fn, primary}], content} — Terug sluit. */
  U.modal = function (o) {
    var box = U.h('div.modal-box', [
      o.title ? U.h('h2', o.title) : null,
      o.text ? U.h('p.modal-text', o.text) : null,
      o.content || null
    ]);
    var btns = U.h('div.modal-buttons');
    var s = { modal: true, el: U.h('div.layer.modal', [box]) };
    (o.buttons || [{ label: L('OK') }]).forEach(function (b, i) {
      btns.appendChild(U.button(b.label, function () {
        if (b.keep) { if (b.fn) b.fn(); return; }
        U.pop(); if (b.fn) b.fn();
      }, { primary: b.primary || i === 0, autofocus: i === 0 }));
    });
    box.appendChild(btns);
    s.onBack = function () { U.pop(); if (o.onCancel) o.onCancel(); };
    U.push(s);
    return s;
  };

  /** Keuzelijst in een modal; fn(waarde). */
  U.choose = function (title, options, current, fn) {
    var list = U.h('div.choose-list.sy.fm', { 'data-pad': '40' });
    var s = { modal: true, el: U.h('div.layer.modal', [U.h('div.modal-box.choose', [U.h('h2', title), list])]) };
    options.forEach(function (o) {
      var on = o.value === current;
      var el = U.h('div.choice.f' + (on ? '.on.autofocus' : ''), [U.h('span.mark', on ? '●' : ''), U.h('span', o.label)]);
      el.onEnter = function () { U.pop(); fn(o.value); };
      list.appendChild(el);
    });
    s.onBack = function () { U.pop(); };
    U.push(s);
    return s;
  };

  /**
   * Schermtoetsenbord (Netflix-stijl). opts: {layout: 'search'|'email'|'pin', onChange(v), onDone(v), value}
   */
  U.keyboard = function (opts) {
    opts = opts || {};
    var value = opts.value || '';
    var rows;
    if (opts.layout === 'pin') rows = [['1', '2', '3'], ['4', '5', '6'], ['7', '8', '9'], ['⌫', '0', 'OK']];
    else {
      var letters = 'abcdefghijklmnopqrstuvwxyz1234567890'.split('');
      if (opts.layout === 'email') letters = letters.concat(['@', '.', '_', '-', '+', '!']);
      rows = [];
      for (var i = 0; i < letters.length; i += 6) rows.push(letters.slice(i, i + 6));
      rows.push(opts.layout === 'email' ? ['⇧', '.com', '⌫'] : ['␣', '⌫', '✕']);
    }
    var upper = false;
    var el = U.h('div.kb' + (opts.layout === 'pin' ? '.kb-pin' : ''));
    function render() {
      U.clear(el);
      rows.forEach(function (r) {
        var row = U.h('div.kb-row');
        r.forEach(function (k) {
          var label = k;
          var wide = k.length > 1 || k === '␣' || k === '⌫' || k === '✕' || k === '⇧';
          var node;
          if (k === '⌫') node = U.h('div.key.f.wide', [U.icon('backspace')]);
          else if (k === '␣') node = U.h('div.key.f.wide', [U.icon('space')]);
          else node = U.h('div.key.f' + (wide ? '.wide' : ''), upper && k.length === 1 ? k.toUpperCase() : label);
          node.onEnter = function () { press(k); };
          row.appendChild(node);
        });
        el.appendChild(row);
      });
    }
    function press(k) {
      if (k === '⌫') value = value.slice(0, -1);
      else if (k === '✕') value = '';
      else if (k === '␣') value += ' ';
      else if (k === '⇧') { upper = !upper; var f = U.focused; render(); refocus(f); return; }
      else if (k === 'OK') { if (opts.onDone) opts.onDone(value); return; }
      else value += upper && k.length === 1 ? k.toUpperCase() : k;
      if (opts.max && value.length > opts.max) value = value.slice(0, opts.max);
      if (opts.onChange) opts.onChange(value);
    }
    function refocus(prev) {
      var keys = el.querySelectorAll('.key');
      if (prev) for (var i = 0; i < keys.length; i++) if (keys[i].textContent.toLowerCase() === prev.textContent.toLowerCase()) { U.focus(keys[i]); return; }
      if (keys.length) U.focus(keys[0]);
    }
    render();
    el.press = press;
    el.setValue = function (v) { value = v; };
    el.getValue = function () { return value; };
    return el;
  };

  /** Pincode vragen (4 cijfers; cijfertoetsen van de afstandsbediening werken ook). fn(pin) → true = goed. */
  U.askPIN = function (title, check, onOK) {
    var dots = U.h('div.pin-dots');
    var err = U.h('div.pin-error');
    var pin = '';
    function draw() {
      U.clear(dots);
      for (var i = 0; i < 4; i++) dots.appendChild(U.h('span.pin-dot' + (i < pin.length ? '.on' : '')));
    }
    var s;
    function typed(v) {
      pin = v; draw(); err.textContent = '';
      if (pin.length === 4) {
        if (check(pin)) { U.pop(); onOK(pin); }
        else { err.textContent = L('Incorrect PIN'); pin = ''; kb.setValue(''); setTimeout(draw, 300); }
      }
    }
    var kb = U.keyboard({ layout: 'pin', max: 4, onChange: typed, onDone: function () { typed(pin); } });
    s = { modal: true, el: U.h('div.layer.modal', [U.h('div.modal-box.pin-box', [U.h('h2', title), dots, err, kb])]) };
    s.onKey = function (name) {
      if (name.charAt(0) === 'd' && name.length === 2) { kb.press(name.charAt(1)); return true; }
      return false;
    };
    s.onBack = function () { U.pop(); };
    draw();
    U.push(s);
  };

  // MARK: plaatjes

  /** Achtergrondafbeelding met nette terugval (titel op een kleurverloop). */
  U.img = function (url, cls, fallbackText) {
    var el = U.h('div.img' + (cls ? '.' + cls : ''));
    if (fallbackText) el.appendChild(U.h('div.img-fallback', fallbackText));
    if (url) {
      var i = new Image();
      i.onload = function () { el.style.backgroundImage = 'url("' + String(url).replace(/"/g, '%22') + '")'; el.classList.add('loaded'); };
      i.src = url;
    }
    return el;
  };
})();
