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
    laptop: '<rect x="4.5" y="5" width="15" height="10" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M2 18.5h20" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    grid: '<rect x="3" y="3" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/><rect x="13" y="3" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/><rect x="3" y="13" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/><rect x="13" y="13" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/>',
    bell: '<path d="M12 3a6 6 0 0 0-6 6v4.2L4 17h16l-2-3.8V9a6 6 0 0 0-6-6z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M9.5 19.5a2.5 2.5 0 0 0 5 0" fill="none" stroke="currentColor" stroke-width="2"/>',
    flag: '<path d="M5 21V4M5 4h11l-2 4 2 4H5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>'
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
  function isF(el) { return el && el.classList && el.classList.contains('f') && !el.classList.contains('disabled'); }
  /** Snelle weg: links/rechts binnen een rij (.row-track) en omhoog/omlaag tussen rijen op Home, zonder alles op te meten. */
  function fastMove(cur, dir) {
    var parent = cur.parentNode;
    if (!parent || !parent.classList) return null;
    if ((dir === 'left' || dir === 'right') && parent.classList.contains('row-track')) {
      var n = dir === 'left' ? cur.previousElementSibling : cur.nextElementSibling;
      while (n && !isF(n)) n = dir === 'left' ? n.previousElementSibling : n.nextElementSibling;
      return n || false;
    }
    if ((dir === 'up' || dir === 'down') && parent.classList.contains('row-track')) {
      var block = cur.closest('.row-block');
      if (!block || !block.parentNode || !block.parentNode.classList.contains('sy-track')) return null;
      var nb = dir === 'up' ? block.previousElementSibling : block.nextElementSibling;
      while (nb && !nb.querySelector('.f')) nb = dir === 'up' ? nb.previousElementSibling : nb.nextElementSibling;
      if (!nb) return dir === 'up' ? null : false;
      var fm = nb.querySelector('.fm');
      if (fm && fm._last && fm.contains(fm._last)) return fm._last;
      var cands = nb.querySelectorAll('.f');
      // zichtbare x zonder de lopende overgang: offsetLeft min de schuifpositie van de rij
      var vx = function (c) { var sc = c.parentNode && c.parentNode.parentNode; return c.offsetLeft - ((sc && sc._pos) || 0); };
      var ax = vx(cur), best = null, bd = Infinity;
      for (var i = 0; i < cands.length; i++) {
        var d = Math.abs(vx(cands[i]) - ax);
        if (d < bd) { bd = d; best = cands[i]; }
        else if (best && d > bd) break;
      }
      return best;
    }
    return null;
  }
  U.move = function (dir) {
    var cur = U.focused;
    var layer = U.layer();
    if (!cur || !layer.contains(cur)) { U.focusFirst(layer); return true; }
    var fast = fastMove(cur, dir);
    if (fast) { U.focus(fast); return true; }
    if (fast === false && (dir === 'right' || dir === 'down')) return false;
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

  /**
   * Scrollers (.sx horizontaal, .sy verticaal) zo zetten dat het element in beeld is.
   * Heeft de scroller een track (.row-track of .sy-track als eerste kind), dan schuift die met een CSS-transform
   * (GPU, geen herberekening van de pagina); anders scrollLeft/scrollTop.
   */
  U.reveal = function (el) {
    var p = el.parentNode;
    while (p && p !== document.body) {
      if (p.classList) {
        if (p.classList.contains('sx')) revealX(el, p);
        if (p.classList.contains('sy')) revealY(el, p);
      }
      p = p.parentNode;
    }
  };
  function trackOf(p, cls) {
    if (p._track !== undefined) return p._track;
    var f = p.firstElementChild;
    p._track = f && f.classList.contains(cls) ? f : null;
    return p._track;
  }
  U.scrollPos = function (p) { return trackOf(p, p.classList.contains('sx') ? 'row-track' : 'sy-track') ? (p._pos || 0) : (p.classList.contains('sx') ? p.scrollLeft : p.scrollTop); };
  U.scrollTo = function (p, v) {
    var horiz = p.classList.contains('sx');
    var t = trackOf(p, horiz ? 'row-track' : 'sy-track');
    v = Math.max(0, Math.round(v));
    if (!t) { if (horiz) p.scrollLeft = v; else p.scrollTop = v; return; }
    if (p._pos === v) return;
    p._pos = v;
    t.style.transform = horiz ? 'translate3d(' + (-v) + 'px,0,0)' : 'translate3d(0,' + (-v) + 'px,0)';
  };
  /**
   * Positie van el binnen de track via offsetLeft/offsetTop (de lay-out, niet de lopende CSS-overgang):
   * zo klopt de berekening ook als je drukt terwijl de vorige verschuiving nog loopt. null = niet te bepalen.
   */
  function offsetIn(el, track, horiz) {
    var v = 0, n = el;
    while (n && n !== track) {
      v += horiz ? n.offsetLeft : n.offsetTop;
      n = n.offsetParent;
      if (n && !track.contains(n) && n !== track) return null;
    }
    return n === track ? v : null;
  }
  function revealX(el, p) {
    var pad = parseInt(p.getAttribute('data-pad') || '60', 10);
    var cur = U.scrollPos(p);
    var t = trackOf(p, 'row-track');
    var x = t ? offsetIn(el, t, true) : null;
    if (x == null) x = el.getBoundingClientRect().left - p.getBoundingClientRect().left + cur;
    var w = el.offsetWidth;
    if (x - pad < cur) U.scrollTo(p, x - pad);
    else if (x + w + pad > cur + p.clientWidth) U.scrollTo(p, x + w + pad - p.clientWidth);
  }
  function revealY(el, p) {
    var padY = parseInt(p.getAttribute('data-pad') || '80', 10);
    var cur = U.scrollPos(p);
    var t = trackOf(p, 'sy-track');
    function top(n) {
      var v = t ? offsetIn(n, t, false) : null;
      return v != null ? v : n.getBoundingClientRect().top - p.getBoundingClientRect().top + cur;
    }
    var mode = p.getAttribute('data-align');
    if (mode === 'top') {
      var anchor = el.closest('.row-block') || el;
      U.scrollTo(p, top(anchor) - padY);
      return;
    }
    var y = top(el);
    if (y - padY < cur) U.scrollTo(p, y - padY);
    else if (y + el.offsetHeight + padY > cur + p.clientHeight) U.scrollTo(p, y + el.offsetHeight + padY - p.clientHeight);
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

  var lastHandled = 0;
  /** Ingedrukt gehouden pijltjes: hooguit één stap per ~75 ms, zodat de tv het tekenen bijhoudt. */
  U.REPEAT_MS = 75;
  U.onKeyUp = function () { B.keyRepeating = false; };
  U.onKeyDown = function (e) {
    var name = U.keyName(e);
    if (!name) return;
    var now = Date.now();
    var arrow = name === 'left' || name === 'right' || name === 'up' || name === 'down';
    B.keyRepeating = !!e.repeat || (arrow && now - B.lastKeyAt < 140);
    B.lastKeyAt = now;
    if (arrow && e.repeat && now - lastHandled < U.REPEAT_MS) { e.preventDefault(); e.stopPropagation(); return; }
    lastHandled = now;
    // herinnering in beeld: groen = naar die zender
    if (B.reminders && B.reminders.onKey(name)) { e.preventDefault(); e.stopPropagation(); return; }
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

  var lastToast = null;
  U.toast = function (text, ms) {
    // één melding tegelijk: een nieuwe vervangt de vorige (anders liggen ze over elkaar)
    if (lastToast && lastToast.parentNode) lastToast.parentNode.removeChild(lastToast);
    var t = lastToast = U.h('div.toast', text);
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
   * Schermtoetsenbord. opts: {layout, onChange(v), onDone(v), value, max}
   *   'search' | 'email' | 'pin'  : zoals voorheen (Netflix-stijl, alfabetisch)
   *   'url' | 'text' | 'mac'      : QWERTY voor de afstandsbediening, met snelkoppelingen (.com, http://, https://, :, /)
   * Cijfertoetsen van de afstandsbediening typen ook (via press).
   */
  U.keyboard = function (opts) {
    opts = opts || {};
    var value = opts.value || '';
    var rows, layout = opts.layout || 'search';
    var qwerty = layout === 'url' || layout === 'text' || layout === 'mac';
    if (layout === 'pin') rows = [['1', '2', '3'], ['4', '5', '6'], ['7', '8', '9'], ['⌫', '0', 'OK']];
    else if (layout === 'mac') {
      rows = [['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'], ['A', 'B', 'C', 'D', 'E', 'F', ':', '-'], ['⌫', '✕', 'OK']];
    } else if (qwerty) {
      rows = [
        ['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'],
        ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'],
        ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', '-'],
        ['⇧', 'z', 'x', 'c', 'v', 'b', 'n', 'm', '.', '/'],
        layout === 'url' ? ['http://', 'https://', ':', '_', '@', '.com', '.nl'] : ['@', '.', '_', '!', '?', '&', '+', '#'],
        ['␣', '⌫', '✕', 'OK']
      ];
    } else {
      var letters = 'abcdefghijklmnopqrstuvwxyz1234567890'.split('');
      if (layout === 'email') letters = letters.concat(['@', '.', '_', '-', '+', '!']);
      rows = [];
      for (var i = 0; i < letters.length; i += 6) rows.push(letters.slice(i, i + 6));
      rows.push(layout === 'email' ? ['⇧', '.com', '⌫'] : ['␣', '⌫', '✕']);
    }
    var upper = false;
    var el = U.h('div.kb.fm' + (layout === 'pin' ? '.kb-pin' : '') + (qwerty ? '.kb-qwerty' : ''));
    function render() {
      U.clear(el);
      rows.forEach(function (r) {
        var row = U.h('div.kb-row');
        r.forEach(function (k) {
          var wide = k.length > 1 || k === '␣' || k === '⌫' || k === '✕' || k === '⇧';
          var node;
          if (k === '⌫') node = U.h('div.key.f.wide', [U.icon('backspace')]);
          else if (k === '␣') node = U.h('div.key.f.wide.space', [U.icon('space')]);
          else if (k === 'OK') node = U.h('div.key.f.wide.ok', opts.okLabel || 'OK');
          else node = U.h('div.key.f' + (wide ? '.wide' : '') + (k === '⇧' && upper ? '.on' : ''), upper && k.length === 1 ? k.toUpperCase() : k);
          node._k = k;
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
      if (prev) for (var i = 0; i < keys.length; i++) if (keys[i]._k === prev._k) { U.focus(keys[i]); return; }
      if (keys.length) U.focus(keys[0]);
    }
    render();
    el.press = press;
    el.setValue = function (v) { value = v || ''; };
    el.getValue = function () { return value; };
    return el;
  };

  /**
   * Tekstveld bewerken met het schermtoetsenbord (servers, inloggen).
   * o: {title, value, layout ('url'|'text'|'email'|'mac'), secret, placeholder, hint}; fn(nieuwe waarde) bij OK.
   */
  U.editText = function (o, fn) {
    var value = o.value || '';
    var field = U.h('div.et-field');
    function draw() {
      var shown = o.secret ? new Array(value.length + 1).join('•') : value;
      field.textContent = shown || o.placeholder || '';
      field.classList.toggle('placeholder', !value);
    }
    var kb = U.keyboard({ layout: o.layout || 'text', value: value, okLabel: L('OK'), onChange: function (v) { value = v; draw(); },
      onDone: function (v) { U.pop(); fn(v); } });
    var s = { el: U.h('div.layer.edit-text', [U.h('div.et-box', [U.h('h2', o.title || ''), field,
      o.hint ? U.h('div.et-hint.dim', o.hint) : null, kb])]) };
    s.onKey = function (name) {
      if (name.charAt(0) === 'd' && name.length === 2) { kb.press(name.charAt(1)); return true; }
      if (name === 'done') { U.pop(); fn(value); return true; }
      return false;
    };
    s.onBack = function () { U.pop(); };
    draw();
    U.push(s);
    var first = kb.querySelector('.key');
    if (first) U.focus(first);
    return s;
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
  U.img = function (url, cls, fallbackText, eager) {
    var el = U.h('div.img' + (cls ? '.' + cls : ''));
    if (fallbackText) el.appendChild(U.h('div.img-fallback', fallbackText));
    if (url) {
      el._src = url;
      if (eager || !lazyObserver()) loadImg(el);
      else lazyObserver().observe(el);
    }
    return el;
  };
  function loadImg(el) {
    var url = el._src;
    if (!url || el._loading) return;
    el._loading = true;
    var i = new Image();
    i.onload = function () { el.style.backgroundImage = 'url("' + String(url).replace(/"/g, '%22') + '")'; el.classList.add('loaded'); };
    i.src = url;
  }
  /** Posters en logo's pas laden als ze (bijna) in beeld komen: minder geheugen en netwerk tijdens het scrollen. */
  var io;
  function lazyObserver() {
    if (io !== undefined) return io;
    io = null;
    try {
      if (window.IntersectionObserver) {
        io = new IntersectionObserver(function (entries) {
          entries.forEach(function (en) {
            if (en.isIntersecting || en.intersectionRatio > 0) { io.unobserve(en.target); loadImg(en.target); }
          });
        }, { root: null, rootMargin: '400px 700px 400px 700px' });
      }
    } catch (e) { io = null; }
    return io;
  }

  // MARK: virtuele lijst (lange lijsten: alleen de rijen in beeld bestaan in de DOM)

  /**
   * U.vlist({count, rowHeight, render(i) → element (.f), height, buffer, onFocusIndex(i)})
   * Geeft {el, focusIndex(i), refresh(count), index()} terug. Omhoog/omlaag regelt de lijst zelf; links/rechts
   * gaat naar de normale ruimtelijke navigatie. CH+/CH− springen een pagina.
   */
  U.vlist = function (o) {
    var rowH = o.rowHeight, height = o.height, buffer = o.buffer || 4;
    var el = U.h('div.vlist' + (o.cls ? '.' + o.cls : ''));
    var track = U.h('div.vlist-track');
    el.appendChild(track);
    var count = o.count, top = 0, cur = 0, nodes = {}, pos = 0;
    var visibleRows = Math.ceil(height / rowH);
    function place(i) {
      var n = nodes[i];
      if (n) return n;
      n = o.render(i);
      n.style.position = 'absolute';
      n.style.top = (i * rowH) + 'px';
      n.style.left = '0'; n.style.right = '0';
      n._vi = i;
      n.onKey = (function (orig) {
        return function (name, e) {
          if (orig && orig(name, e) === true) return true;
          if (name === 'up' || name === 'down') { var t = n._vi + (name === 'up' ? -1 : 1); if (t < 0 || t >= count) return name === 'down'; api.focusIndex(t); return true; }
          if (name === 'chup' || name === 'chdown') { api.focusIndex(B.clamp(n._vi + (name === 'chup' ? -visibleRows : visibleRows), 0, count - 1)); return true; }
          return false;
        };
      })(n.onKey);
      var userFocus = n.onFocus;
      n.onFocus = function () { cur = n._vi; if (o.onFocusIndex) o.onFocusIndex(n._vi); if (userFocus) userFocus(); };
      nodes[i] = n;
      track.appendChild(n);
      return n;
    }
    function windowAt(first) {
      var from = Math.max(0, first - buffer), to = Math.min(count - 1, first + visibleRows + buffer);
      Object.keys(nodes).forEach(function (k) {
        var i = +k;
        if ((i < from || i > to) && nodes[i] !== U.focused) { track.removeChild(nodes[i]); delete nodes[i]; }
      });
      for (var i = from; i <= to; i++) place(i);
    }
    function scrollTo(i) {
      // het gekozen item zo houden dat er boven nog ~2 rijen zichtbaar zijn
      var want = top;
      if (i < top + 1) want = Math.max(0, i - 1);
      else if (i > top + visibleRows - 3) want = Math.min(Math.max(0, count - visibleRows + 1), i - visibleRows + 3);
      top = Math.max(0, want);
      var p = top * rowH;
      if (p !== pos) { pos = p; track.style.transform = 'translate3d(0,' + (-p) + 'px,0)'; }
      windowAt(top);
    }
    var api = {
      el: el,
      index: function () { return cur; },
      focusIndex: function (i) {
        if (!count) return;
        i = B.clamp(i, 0, count - 1);
        scrollTo(i);
        U.focus(place(i));
      },
      node: function (i) { return nodes[i] || null; },
      refresh: function (n) {
        count = n == null ? count : n;
        Object.keys(nodes).forEach(function (k) { track.removeChild(nodes[k]); });
        nodes = {};
        cur = Math.min(cur, Math.max(0, count - 1));
        track.style.height = (count * rowH) + 'px';
        scrollTo(cur);
      },
      redrawVisible: function () {
        var f = U.focused, fi = f && f._vi;
        Object.keys(nodes).forEach(function (k) { track.removeChild(nodes[k]); });
        nodes = {};
        windowAt(top);
        if (fi != null && nodes[fi] && f && !document.body.contains(f)) U.focus(nodes[fi], true);
      },
      reset: function (n) { cur = 0; top = 0; pos = -1; api.refresh(n); }
    };
    api.reset(count);
    return api;
  };

  /**
   * Virtueel raster (posters): rijen van `cols` kaarten; alleen de rijen in beeld bestaan. Zelfde api als vlist.
   * o: {count, cols, cellW, cellH, height, render(i)}
   */
  U.vgrid = function (o) {
    var cols = o.cols, cellH = o.cellH, cellW = o.cellW, height = o.height, buffer = o.buffer || 2;
    var el = U.h('div.vgrid');
    var track = U.h('div.vlist-track');
    el.appendChild(track);
    var count = o.count, topRow = 0, cur = 0, nodes = {}, pos = -1;
    var visRows = Math.ceil(height / cellH);
    function rows() { return Math.ceil(count / cols); }
    function place(i) {
      var n = nodes[i];
      if (n) return n;
      n = o.render(i);
      n.style.position = 'absolute';
      n.style.top = (Math.floor(i / cols) * cellH) + 'px';
      n.style.left = ((i % cols) * cellW) + 'px';
      n._vi = i;
      n.onKey = (function (orig) {
        return function (name, e) {
          if (orig && orig(name, e) === true) return true;
          var c = n._vi % cols, t = null;
          if (name === 'up') t = n._vi - cols;
          else if (name === 'down') { t = n._vi + cols; if (t >= count && Math.floor(n._vi / cols) < rows() - 1) t = count - 1; }
          else if (name === 'left') { if (c === 0) return false; t = n._vi - 1; }
          else if (name === 'right') { if (c === cols - 1 || n._vi + 1 >= count) return false; t = n._vi + 1; }
          else if (name === 'chup' || name === 'chdown') t = B.clamp(n._vi + (name === 'chup' ? -1 : 1) * cols * visRows, 0, count - 1);
          if (t == null) return false;
          if (t < 0) return false;
          if (t >= count) return true;
          api.focusIndex(t);
          return true;
        };
      })(n.onKey);
      var userFocus = n.onFocus;
      n.onFocus = function () { cur = n._vi; if (o.onFocusIndex) o.onFocusIndex(n._vi); if (userFocus) userFocus(); };
      nodes[i] = n;
      track.appendChild(n);
      return n;
    }
    function windowAt(first) {
      var from = Math.max(0, (first - buffer) * cols), to = Math.min(count - 1, (first + visRows + buffer) * cols - 1);
      Object.keys(nodes).forEach(function (k) {
        var i = +k;
        if ((i < from || i > to) && nodes[i] !== U.focused) { track.removeChild(nodes[i]); delete nodes[i]; }
      });
      for (var i = from; i <= to; i++) place(i);
    }
    function scrollTo(i) {
      var r = Math.floor(i / cols);
      var want = topRow;
      if (r < topRow) want = r;
      else if (r > topRow + visRows - 2) want = r - visRows + 2;
      topRow = Math.max(0, want);
      var p = topRow * cellH;
      if (p !== pos) { pos = p; track.style.transform = 'translate3d(0,' + (-p) + 'px,0)'; }
      windowAt(topRow);
    }
    var api = {
      el: el,
      index: function () { return cur; },
      focusIndex: function (i) { if (!count) return; i = B.clamp(i, 0, count - 1); scrollTo(i); U.focus(place(i)); },
      reset: function (n) {
        count = n; cur = 0; topRow = 0; pos = -1;
        Object.keys(nodes).forEach(function (k) { track.removeChild(nodes[k]); });
        nodes = {};
        track.style.height = (rows() * cellH) + 'px';
        scrollTo(0);
      },
      redrawVisible: function () {
        Object.keys(nodes).forEach(function (k) { if (nodes[k] !== U.focused) { track.removeChild(nodes[k]); delete nodes[k]; } });
        windowAt(topRow);
      }
    };
    api.reset(count);
    return api;
  };
})();
