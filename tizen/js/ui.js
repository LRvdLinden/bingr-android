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
    span.innerHTML = '<svg viewBox="' + (U.iconBoxes[name] || '0 0 24 24') + '" aria-hidden="true">' + paths + '</svg>';
    return span;
  };
  /** Iconen met een eigen viewBox (standaard 0 0 24 24). */
  U.iconBoxes = {
    appletv: '0 0 19.4976 19.4314' // build 74: Apple TV (SF Symbol "appletv")
  };
  /** Icoon per apparaatplatform (apparaten, Nu aan het kijken, te veel kijkers). */
  U.platformIcon = function (p) {
    p = String(p || '');
    if (/^browser$/i.test(p)) return 'globe'; // webspeler op bingr.online (build 62)
    if (/^(apple ?tv|tvos)$/i.test(p)) return 'appletv'; // build 74
    if (/TV|tizen/i.test(p)) return 'tv';
    if (/Mac/.test(p)) return 'laptop';
    return 'phone';
  };
  U.icons = {
    appletv: '<path d="M5.20687 19.4314L14.2907 19.4314C15.949 19.4314 17.3557 19.2815 18.3579 18.2793C19.3476 17.2792 19.4976 15.8974 19.4976 14.2246L19.4976 5.22355C19.4976 3.55067 19.3476 2.1668 18.3579 1.16882C17.3536 0.176964 15.949 0.0166814 14.2907 0.0166814L5.18577 0.0166814C3.55067 0.0166814 2.14188 0.179074 1.14179 1.17093C0.149932 2.17102 0 3.55489 0 5.1921L0 14.2246C0 15.8974 0.147822 17.2917 1.13968 18.2793C2.14188 19.2815 3.5361 19.4314 5.20687 19.4314ZM4.93695 17.8607C3.93656 17.8607 2.87758 17.7212 2.2877 17.1313C1.69781 16.5414 1.57069 15.497 1.57069 14.4945L1.57069 4.98508C1.57069 3.95535 1.69781 2.90461 2.28559 2.31473C2.87547 1.72274 3.94078 1.58737 4.9684 1.58737L14.5606 1.58737C15.561 1.58737 16.6179 1.72696 17.2078 2.31684C17.7997 2.90672 17.9269 3.95113 17.9269 4.95363L17.9269 14.4945C17.9269 15.497 17.7976 16.5414 17.2078 17.1313C16.6179 17.7233 15.561 17.8607 14.5606 17.8607Z" fill="currentColor"/><path d="M4.85163 12.6004C5.26284 12.6004 5.4373 12.3138 5.94819 12.3138C6.47155 12.3138 6.59616 12.6004 7.04476 12.6004C7.49335 12.6004 7.79241 12.1768 8.09148 11.7655C8.39054 11.3045 8.54007 10.8434 8.54007 10.8185C8.52761 10.8185 7.64288 10.4571 7.64288 9.46027C7.64288 8.61293 8.32823 8.22664 8.36562 8.18925C7.92948 7.56621 7.23167 7.54128 7.04476 7.54128C6.53386 7.54128 6.12265 7.82789 5.86097 7.82789C5.58683 7.82789 5.22546 7.56621 4.77687 7.56621C3.95444 7.56621 3.1071 8.2391 3.1071 9.53503C3.1071 10.345 3.40616 11.1923 3.79245 11.7406C4.14136 12.2141 4.42796 12.6004 4.85163 12.6004ZM5.89835 7.45406C6.5214 7.45406 7.16937 6.78117 7.15691 6.04597C7.16937 6.00859 7.15691 5.95875 7.14444 5.92136C6.48402 5.94628 5.81112 6.60671 5.81112 7.32945C5.81112 7.36683 5.81112 7.42914 5.82359 7.4416C5.83605 7.45406 5.86097 7.45406 5.89835 7.45406ZM11.0073 12.5755C11.2192 12.5755 11.4435 12.563 11.5556 12.5381L11.5805 12.5257L11.5805 11.7157L11.5432 11.7157C11.4809 11.7282 11.3313 11.7406 11.2316 11.7406C10.8578 11.7406 10.6958 11.5662 10.6958 11.1799L10.6958 8.60046L11.593 8.60046L11.593 7.77804L10.6958 7.77804L10.6958 6.65656L9.68648 6.65656L9.68648 7.77804L9.00112 7.77804L9.00112 8.60046L9.68648 8.60046L9.68648 11.292C9.68648 12.2017 10.0728 12.5755 11.0073 12.5755ZM13.6864 12.5381L14.7332 12.5381L16.4029 7.77804L15.3562 7.77804L14.2223 11.4914L13.0759 7.77804L11.9917 7.77804Z" fill="currentColor"/>',
    search: '<circle cx="10.5" cy="10.5" r="6.5" fill="none" stroke="currentColor" stroke-width="2.2"/><path d="M15.5 15.5L21 21" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/>',
    home: '<path d="M3 11.2L12 3.5l9 7.7V21h-6.2v-6.3H9.2V21H3z" fill="currentColor"/>',
    live: '<rect x="2.5" y="5" width="19" height="13" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M8 21h8M8.5 1.8L12 5l3.5-3.2" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round"/>',
    movies: '<rect x="3" y="3" width="18" height="18" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M7 3v18M17 3v18M3 8h4M3 12h4M3 16h4M17 8h4M17 12h4M17 16h4" stroke="currentColor" stroke-width="1.8"/>',
    series: '<rect x="2.5" y="6" width="19" height="14" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M6 3h12" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M10 10v6l5-3z" fill="currentColor"/>',
    list: '<path d="M12 4v16M4 12h16" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"/>',
    plus: '<path d="M12 4v16M4 12h16" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"/>',
    check: '<path d="M4.5 12.5l5 5L19.5 7" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>',
    // Mijn lijst en favoriete zenders: plus en vinkje in één icoon, de klasse "on" wisselt met een overgang
    // (css: .ic.pc, U.plusCheckButton / U.setPlusCheck)
    pluscheck: '<g class="pc-plus"><path d="M12 4v16M4 12h16" stroke="currentColor" stroke-width="2.6" stroke-linecap="round"/></g>' +
      '<path class="pc-check" d="M4.5 12.5l5 5L19.5 7" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>',
    gear: '<path d="M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7zm8.4 5l2-1.5-2-3.5-2.4.9a7.6 7.6 0 0 0-1.8-1L15.8 6h-4l-.4 2.4a7.6 7.6 0 0 0-1.8 1l-2.4-.9-2 3.5 2 1.5a7.6 7.6 0 0 0 0 2l-2 1.5 2 3.5 2.4-.9c.6.4 1.1.7 1.8 1l.4 2.4h4l.4-2.4c.7-.3 1.2-.6 1.8-1l2.4.9 2-3.5-2-1.5c.1-.7.1-1.3 0-2z" fill="currentColor" transform="translate(-1.8 -2) scale(1.08)"/>',
    play: '<path d="M7 4.5v15l12.5-7.5z" fill="currentColor"/>',
    // build 97: dubbele 'grid' (build 85) weggehaald; de latere definitie hieronder was al degene die gold
    // build 82: geluid aan/uit (trailer in de kop van de detailpagina)
    volume: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" fill="currentColor"/><path d="M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    volumeOff: '<path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" fill="currentColor"/><path d="M15.5 9.5l5 5M20.5 9.5l-5 5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    pause: '<path d="M6.5 4.5h4v15h-4zM13.5 4.5h4v15h-4z" fill="currentColor"/>',
    info: '<circle cx="12" cy="12" r="9.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 10.5v6.5M12 7v.5" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/>',
    lock: '<rect x="5" y="10.5" width="14" height="10" rx="2" fill="currentColor"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5" fill="none" stroke="currentColor" stroke-width="2.2"/>',
    back: '<path d="M15 4.5L7.5 12l7.5 7.5" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>',
    next: '<path d="M5 4.5v15l10-7.5zM16.5 4.5h3v15h-3z" fill="currentColor"/>',
    audio: '<path d="M4 9.5h4l5-4.5v14l-5-4.5H4z" fill="currentColor"/><path d="M16 8.5a5 5 0 0 1 0 7M18.5 6a8.5 8.5 0 0 1 0 12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    subs: '<rect x="2.5" y="5" width="19" height="14" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M6 13.5h5M13 13.5h5M6 16.5h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    guide: '<rect x="2.5" y="4" width="19" height="16" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M2.5 9h19M9 9v11M15 9v11" stroke="currentColor" stroke-width="1.8"/>',
    // build 104: Lifetime-badge (uitgespaard vinkje, even-odd); vulling in Bingr-rood via .ic-life in app.css
    lifetime: '<path class="ic-life" fill-rule="evenodd" clip-rule="evenodd" d="M9.5924 3.20027C9.34888 3.4078 9.22711 3.51158 9.09706 3.59874C8.79896 3.79854 8.46417 3.93721 8.1121 4.00672C7.95851 4.03705 7.79903 4.04977 7.48008 4.07522C6.6787 4.13918 6.278 4.17115 5.94371 4.28923C5.17051 4.56233 4.56233 5.17051 4.28923 5.94371C4.17115 6.278 4.13918 6.6787 4.07522 7.48008C4.04977 7.79903 4.03705 7.95851 4.00672 8.1121C3.93721 8.46417 3.79854 8.79896 3.59874 9.09706C3.51158 9.22711 3.40781 9.34887 3.20027 9.5924C2.67883 10.2043 2.4181 10.5102 2.26522 10.8301C1.91159 11.57 1.91159 12.43 2.26522 13.1699C2.41811 13.4898 2.67883 13.7957 3.20027 14.4076C3.40778 14.6511 3.51158 14.7729 3.59874 14.9029C3.79854 15.201 3.93721 15.5358 4.00672 15.8879C4.03705 16.0415 4.04977 16.201 4.07522 16.5199C4.13918 17.3213 4.17115 17.722 4.28923 18.0563C4.56233 18.8295 5.17051 19.4377 5.94371 19.7108C6.278 19.8288 6.6787 19.8608 7.48008 19.9248C7.79903 19.9502 7.95851 19.963 8.1121 19.9933C8.46417 20.0628 8.79896 20.2015 9.09706 20.4013C9.22711 20.4884 9.34887 20.5922 9.5924 20.7997C10.2043 21.3212 10.5102 21.5819 10.8301 21.7348C11.57 22.0884 12.43 22.0884 13.1699 21.7348C13.4898 21.5819 13.7957 21.3212 14.4076 20.7997C14.6511 20.5922 14.7729 20.4884 14.9029 20.4013C15.201 20.2015 15.5358 20.0628 15.8879 19.9933C16.0415 19.963 16.201 19.9502 16.5199 19.9248C17.3213 19.8608 17.722 19.8288 18.0563 19.7108C18.8295 19.4377 19.4377 18.8295 19.7108 18.0563C19.8288 17.722 19.8608 17.3213 19.9248 16.5199C19.9502 16.201 19.963 16.0415 19.9933 15.8879C20.0628 15.5358 20.2015 15.201 20.4013 14.9029C20.4884 14.7729 20.5922 14.6511 20.7997 14.4076C21.3212 13.7957 21.5819 13.4898 21.7348 13.1699C22.0884 12.43 22.0884 11.57 21.7348 10.8301C21.5819 10.5102 21.3212 10.2043 20.7997 9.5924C20.5922 9.34887 20.4884 9.22711 20.4013 9.09706C20.2015 8.79896 20.0628 8.46417 19.9933 8.1121C19.963 7.95851 19.9502 7.79903 19.9248 7.48008C19.8608 6.6787 19.8288 6.278 19.7108 5.94371C19.4377 5.17051 18.8295 4.56233 18.0563 4.28923C17.722 4.17115 17.3213 4.13918 16.5199 4.07522C16.201 4.04977 16.0415 4.03705 15.8879 4.00672C15.5358 3.93721 15.201 3.79854 14.9029 3.59874C14.7729 3.51158 14.6511 3.40781 14.4076 3.20027C13.7957 2.67883 13.4898 2.41811 13.1699 2.26522C12.43 1.91159 11.57 1.91159 10.8301 2.26522C10.5102 2.4181 10.2043 2.67883 9.5924 3.20027ZM16.3735 9.86314C16.6913 9.5453 16.6913 9.03 16.3735 8.71216C16.0557 8.39433 15.5403 8.39433 15.2225 8.71216L10.3723 13.5624L8.77746 11.9676C8.45963 11.6498 7.94432 11.6498 7.62649 11.9676C7.30866 12.2854 7.30866 12.8007 7.62649 13.1186L9.79678 15.2889C10.1146 15.6067 10.6299 15.6067 10.9478 15.2889L16.3735 9.86314Z"/>',
    star: '<path d="M12 3.5l2.6 5.6 6.1.6-4.6 4.1 1.3 6-5.4-3.1-5.4 3.1 1.3-6L3.3 9.7l6.1-.6z" fill="currentColor"/>',
    replay: '<path d="M5 12a7 7 0 1 0 2.1-5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/><path d="M4 3.5v5h5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
    // gestapelde lagen: wisselen van bron (Andere bron / Kies een bron)
    layers: '<path d="M12 3.5l9 4.5-9 4.5-9-4.5z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M3 12l9 4.5 9-4.5M3 16l9 4.5 9-4.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    swap: '<path d="M4 8h14l-3.5-3.5M20 16H6l3.5 3.5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
    backspace: '<path d="M8.5 5H21v14H8.5L2.5 12z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M11.5 9l6 6M17.5 9l-6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    space: '<path d="M4 10v4h16v-4" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
    tv: '<rect x="2.5" y="4.5" width="19" height="13" rx="2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M8 20.5h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    phone: '<rect x="6.5" y="2.5" width="11" height="19" rx="2.2" fill="none" stroke="currentColor" stroke-width="2"/><path d="M10.5 18.5h3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    laptop: '<rect x="4.5" y="5" width="15" height="10" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M2 18.5h20" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    grid: '<rect x="3" y="3" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/><rect x="13" y="3" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/><rect x="3" y="13" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/><rect x="13" y="13" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/>',
    bell: '<path d="M12 3a6 6 0 0 0-6 6v4.2L4 17h16l-2-3.8V9a6 6 0 0 0-6-6z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M9.5 19.5a2.5 2.5 0 0 0 5 0" fill="none" stroke="currentColor" stroke-width="2"/>',
    // wereldbol: andere taal(versie)
    globe: '<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2"/><path d="M3 12h18M12 3c2.6 2.6 3.8 5.6 3.8 9s-1.2 6.4-3.8 9c-2.6-2.6-3.8-5.6-3.8-9S9.4 5.6 12 3z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/>',
    flag: '<path d="M5 21V4M5 4h11l-2 4 2 4H5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/>',
    // build 102: bekeken markeren (oog)
    eye: '<path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><circle cx="12" cy="12" r="3" fill="currentColor"/>',
    // profielmenu (build 72)
    person: '<circle cx="12" cy="8" r="4" fill="none" stroke="currentColor" stroke-width="2"/><path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    people: '<circle cx="9" cy="8" r="3.5" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="17" cy="9" r="2.8" fill="none" stroke="currentColor" stroke-width="2"/><path d="M2.5 20c0-3.8 2.9-6 6.5-6s6.5 2.2 6.5 6M15.5 14.2c3.3-.4 6 1.5 6 5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>',
    signout: '<path d="M14 4H6v16h8" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="M10 12h11M17.5 8.5L21 12l-3.5 3.5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    help: '<circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" stroke-width="2"/><path d="M9.3 9.4a2.8 2.8 0 0 1 5.4 1c0 1.9-2.7 2.3-2.7 4" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><circle cx="12" cy="17.6" r="1.2" fill="currentColor"/>'
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
    // niets te kiezen in het nieuwe scherm (lege lijst, nog aan het laden): de oude focus loslaten,
    // anders zou OK iets in het scherm eronder openen
    if (U.focused && !s.el.contains(U.focused)) U.blur();
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
      // focus nog in het gesloten scherm (scherm eronder heeft niets te kiezen, zoals de tv-gids): loslaten
      if (U.focused && !t.el.contains(U.focused)) U.blur();
    }
  };
  U.popTo = function (s) { while (U.stack.length && U.top() !== s) U.pop(); };
  U.reset = function (s) {
    while (U.stack.length) U.pop();
    U.push(s);
  };

  // MARK: focus

  U.focused = null;
  U.blur = function () {
    var prev = U.focused;
    if (!prev) return;
    prev.classList.remove('focused');
    U.focused = null;
    if (prev.onBlur) prev.onBlur();
  };
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
    if (!(el.offsetParent !== null || el.offsetWidth > 0)) return false;
    // build 97: onzichtbaar maar wel in de lay-out (verborgen speler-overlay, visibility: hidden of opacity 0) telt niet
    if (el.closest && el.closest('.pl-overlay:not(.show)')) return false;
    try {
      var cs = window.getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.opacity === '0') return false;
    } catch (e) { /* stil */ }
    return true;
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
    65376: 'done', 65385: 'cancel', 32: 'playpause',
    // build 102: Tools-toets van de Samsung-afstandsbediening (contextmenu van een kaart); 93 = contextmenu-toets in de test-browser
    10135: 'tools', 93: 'tools'
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
        ColorF0Red: 'red', ColorF1Green: 'green', ColorF2Yellow: 'yellow', ColorF3Blue: 'blue', Tools: 'tools' };
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
  /**
   * build 102: lang indrukken (OK ~0,7 s, zoals een contextmenu op de Apple TV) op een element met _menu opent dat menu.
   * OK op zo'n element werkt dan bij het loslaten. Een gemiste keyup (komt op sommige tv's voor) mag niets blokkeren:
   * na 0,7 s zonder herhaalde toets telt het als een gewone druk. Ingedrukt houden geeft herhaalde keydowns.
   */
  U.LONG_MS = 700;
  var hold = null;
  function holdFire() {
    var hd = hold;
    if (!hd || hd.done) return;
    hd.done = true;
    if (hd.el !== U.focused || !document.body.contains(hd.el)) { hold = null; return; }
    if (hd.repeat) { if (hd.el._menu) hd.el._menu(); }
    else { hold = null; if (hd.el.onEnter) hd.el.onEnter(); }
  }
  function holdStart(el) {
    if (hold) clearTimeout(hold.timer);
    hold = { el: el, repeat: false, done: false, timer: setTimeout(holdFire, U.LONG_MS) };
  }
  U.onKeyUp = function (e) {
    B.keyRepeating = false;
    if (!hold || !e || U.keyName(e) !== 'enter') return;
    var hd = hold;
    hold = null;
    clearTimeout(hd.timer);
    if (e.preventDefault) { e.preventDefault(); e.stopPropagation(); }
    if (hd.done) return;
    if (hd.el === U.focused && document.body.contains(hd.el) && hd.el.onEnter) hd.el.onEnter();
  };
  /** Contextmenu van het element met de focus (Tools-toets of lang OK); true als er een is. */
  U.openMenu = function () {
    var f = U.focused;
    if (!f || !f._menu) return false;
    f._menu();
    return true;
  };
  // build 97: geen keyup (app naar de achtergrond, venster kwijt): niet blijven denken dat er een toets ingedrukt is
  try {
    window.addEventListener('blur', function () { B.keyRepeating = false; });
    document.addEventListener('visibilitychange', function () { B.keyRepeating = false; });
  } catch (e) { /* stil */ }
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
    // build 97: Exit-toets (10182): Bingr netjes afsluiten via dezelfde weg als twee keer Terug
    if (name === 'exit') { e.preventDefault(); e.stopPropagation(); if (B.app && B.app.exit) B.app.exit(); return; }
    // tekstvelden (alleen in de test-browser) houden hun letters
    if (e.target && e.target.tagName === 'INPUT' && name === 'back' && e.keyCode === 8) return;
    var top = U.top();
    var handled = false;
    // focus in een laag onder het bovenste scherm telt niet (geen toetsen naar een verborgen scherm)
    if (U.focused && top && !top.el.contains(U.focused)) U.blur();
    // build 102: OK op een kaart met een contextmenu: pas bij loslaten (kort) of na 0,7 s ingedrukt (menu)
    if (name === 'enter' && hold) {
      // nog ingedrukt (herhaling): niets anders doen, ook niet in het menu dat net openging
      if (e.repeat || (hold.el === U.focused && !hold.done)) {
        if (hold.el === U.focused) hold.repeat = true;
        e.preventDefault(); e.stopPropagation();
        return;
      }
      // nieuwe druk terwijl het loslaten van de vorige gemist is
      clearTimeout(hold.timer);
      hold = null;
    }
    if (name === 'enter' && U.focused && U.focused._menu && top && top.el.contains(U.focused)) {
      if (!e.repeat) holdStart(U.focused);
      e.preventDefault(); e.stopPropagation();
      return;
    }
    if (name === 'tools' && U.openMenu()) { e.preventDefault(); e.stopPropagation(); return; }
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
    if (opts.icon) kids.push(U.icon(opts.icon, opts.iconCls));
    if (label) kids.push(U.h('span.lbl', label));
    var cls = opts.cls ? '.' + String(opts.cls).split(/\s+/).join('.') : '';
    // afspeelknop (primair met play-icoon, of expliciet play): gevuld in de accentkleur van het profiel
    var play = opts.play || (opts.primary && opts.icon === 'play');
    var el = U.h('div.btn.f' + (opts.primary || play ? '.primary' : '') + (play ? '.play' : '') + cls + (opts.autofocus ? '.autofocus' : ''), kids);
    el.onEnter = onEnter;
    return el;
  };

  /** Icoonnaam voor menu's (plus of vinkje): Mijn lijst en favoriete zenders. */
  U.plusCheckIcon = function (on) { return on ? 'check' : 'plus'; };
  /** Toegankelijkheidslabels [uit, aan] per soort. */
  U.plusCheckLabels = {
    mylist: function () { return [L('Add to My List'), L('In My List')]; },
    favorite: function () { return [L('Add to favorites'), L('In favorites')]; }
  };
  /**
   * Aan/uit-knop plus ↔ vinkje (Mijn lijst, favoriete zenders): icoon 'pluscheck' (on = staat er al in), kind = 'mylist'
   * of 'favorite' (toegankelijkheidslabel). U.setPlusCheck(knop, on) wisselt met de animatie (plus draait weg, vinkje
   * tekent zich, kleine bounce bij toevoegen).
   */
  U.plusCheckButton = function (kind, label, on, onEnter, opts) {
    opts = opts || {};
    opts.icon = 'pluscheck';
    opts.iconCls = 'pc' + (on ? ' on' : '');
    var el = U.button(label, onEnter, opts);
    el._pcKind = kind;
    el.setAttribute('aria-label', U.plusCheckLabels[kind]()[on ? 1 : 0]);
    return el;
  };
  U.setPlusCheck = function (el, on) {
    var ic = el && el.querySelector('.ic.pc');
    if (!ic) return;
    if (el._pcKind) el.setAttribute('aria-label', U.plusCheckLabels[el._pcKind]()[on ? 1 : 0]);
    if (ic.classList.contains('on') === on) return;
    ic.classList.remove('pop');
    if (on) {
      void ic.offsetWidth; // animatie opnieuw starten
      ic.classList.add('pop');
    }
    if (on) ic.classList.add('on'); else ic.classList.remove('on');
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

  /** Modal: {title, text, buttons:[{label, fn, primary, play, icon}], content} — Terug sluit. */
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
      }, { primary: b.primary || i === 0, play: b.play, icon: b.icon || (b.play ? 'play' : null), autofocus: i === 0 }));
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

  /**
   * Pincode vragen (4 cijfers; cijfertoetsen van de afstandsbediening werken ook). fn(pin) → true = goed.
   * Build 141 (M6): na 5 foute pincodes op rij een oplopende wachttijd (B.b141), voor alle pincodes samen en ook na een
   * herstart; tijdens het wachten doen de toetsen niets.
   */
  U.askPIN = function (title, check, onOK) {
    var dots = U.h('div.pin-dots');
    var err = U.h('div.pin-error');
    var pin = '';
    var X = B.b141;
    var timer = null;
    function draw() {
      U.clear(dots);
      for (var i = 0; i < 4; i++) dots.appendChild(U.h('span.pin-dot' + (i < pin.length ? '.on' : '')));
    }
    function waitLeft() { return X ? X.pinRemaining() : 0; }
    function showWait() {
      if (timer) { clearTimeout(timer); timer = null; }
      var w = waitLeft();
      if (w <= 0) { if (err.getAttribute && err.getAttribute('data-wait')) { err.textContent = ''; err.removeAttribute('data-wait'); } return; }
      err.textContent = L('Too many incorrect PINs. Try again in %@.', X.lockout.shortText(w));
      if (err.setAttribute) err.setAttribute('data-wait', '1');
      timer = setTimeout(showWait, 1000);
    }
    var s;
    function typed(v) {
      if (waitLeft() > 0) { pin = ''; kb.setValue(''); draw(); showWait(); return; }
      pin = v; draw(); err.textContent = '';
      if (pin.length === 4) {
        var good = check(pin);
        if (X) { if (good) X.pinSucceeded(); else X.pinFailed(); }
        if (good) { if (timer) clearTimeout(timer); U.pop(); onOK(pin); }
        else {
          err.textContent = L('Incorrect PIN'); pin = ''; kb.setValue(''); setTimeout(draw, 300);
          if (waitLeft() > 0) showWait();
        }
      }
    }
    var kb = U.keyboard({ layout: 'pin', max: 4, onChange: typed, onDone: function () { typed(pin); } });
    s = { modal: true, el: U.h('div.layer.modal', [U.h('div.modal-box.pin-box', [U.h('h2', title), dots, err, kb])]) };
    s.onKey = function (name) {
      if (name.charAt(0) === 'd' && name.length === 2) { kb.press(name.charAt(1)); return true; }
      return false;
    };
    s.onBack = function () { if (timer) clearTimeout(timer); U.pop(); };
    draw();
    U.push(s);
    showWait();
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
  /** build 97: plaatjes in een weggehaald element niet meer volgen (anders houdt de observer ze in het geheugen). */
  U.forgetImgs = function (node) {
    if (!io || !node) return;
    try {
      if (node._src && !node._loading) io.unobserve(node);
      var list = node.querySelectorAll ? node.querySelectorAll('.img') : [];
      for (var i = 0; i < list.length; i++) if (list[i]._src && !list[i]._loading) io.unobserve(list[i]);
    } catch (e) { /* stil */ }
  };
  function dropNode(track, n) { if (n.parentNode === track) track.removeChild(n); U.forgetImgs(n); }

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
        if ((i < from || i > to) && nodes[i] !== U.focused) { dropNode(track, nodes[i]); delete nodes[i]; }
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
        Object.keys(nodes).forEach(function (k) { dropNode(track, nodes[k]); });
        nodes = {};
        cur = Math.min(cur, Math.max(0, count - 1));
        track.style.height = (count * rowH) + 'px';
        scrollTo(cur);
      },
      redrawVisible: function () {
        var f = U.focused, fi = f && f._vi;
        Object.keys(nodes).forEach(function (k) { dropNode(track, nodes[k]); });
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
        if ((i < from || i > to) && nodes[i] !== U.focused) { dropNode(track, nodes[i]); delete nodes[i]; }
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
        Object.keys(nodes).forEach(function (k) { dropNode(track, nodes[k]); });
        nodes = {};
        track.style.height = (rows() * cellH) + 'px';
        scrollTo(0);
      },
      redrawVisible: function () {
        Object.keys(nodes).forEach(function (k) { if (nodes[k] !== U.focused) { dropNode(track, nodes[k]); delete nodes[k]; } });
        windowAt(topRow);
      }
    };
    api.reset(count);
    return api;
  };
})();
