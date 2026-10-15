/*
 * Herinneringen voor programma's uit de tv-gids (= Reminders op Android). Gedeeld via state-section `reminders`:
 *   {items: [{id: "<zender-id>-<start in s>", playlistID, channelID, channelName, title, start (ISO)}], updatedAt}
 * Het geheel met de nieuwste updatedAt wint. Elke 20 s kijkt de app of er één (bijna) begint: dan een melding in
 * beeld met "Live kijken"; de herinnering gaat daarna uit de lijst (en dat gaat ook naar de andere apparaten).
 */
(function () {
  'use strict';
  var B = window.B;
  var A = B.account;
  var L = function () { return B.L.apply(null, arguments); };
  var R = B.reminders = {};

  var local = B.store.get('reminders', null);   // {items, at}
  function items() { return (local && local.items) || []; }
  function save() { B.store.set('reminders', local); }
  function json() {
    // onbekende velden van de server (nieuwere app-versies) blijven staan, zoals op Android
    var out = B.assign({}, (local && local.base) || {});
    out.items = items().map(function (r) {
      return { id: r.id, playlistID: r.playlistID, channelID: r.channelID, channelName: r.channelName, title: r.title, start: B.iso(r.start) };
    });
    out.updatedAt = B.iso(local ? local.at : Date.now());
    return out;
  }
  function baseOf(data) {
    var b = {};
    Object.keys(data || {}).forEach(function (k) { if (k !== 'items' && k !== 'reminders' && k !== 'list' && k !== 'updatedAt') b[k] = data[k]; });
    return b;
  }
  function parse(o) {
    var list = (o && (o.items || o.reminders || o.list)) || [];
    var seen = {};
    return list.map(function (r) {
      if (!r || !r.id || seen[r.id]) return null;
      seen[r.id] = true;
      var s = B.parseISO(r.start);
      if (s == null) return null;
      return { id: String(r.id), playlistID: r.playlistID || '', channelID: r.channelID || '', channelName: r.channelName || '', title: r.title || '', start: s };
    }).filter(Boolean);
  }
  function upload() { return A.putState('reminders', json()).catch(B.noop); }
  function changed(list) {
    local = { items: list, at: Date.now(), base: (local && local.base) || {} };
    save();
    B.emit('reminders');
    upload();
  }
  function merge(sec) {
    var data = sec && sec.data;
    if (!data || typeof data !== 'object') { if (local && (items().length || local.at)) upload(); return; }
    var at = B.parseISO(data.updatedAt) || B.parseISO(sec.updatedAt) || 0;
    var locAt = local ? local.at || 0 : 0;
    if (!local && items().length && !parse(data).length) { upload(); return; }
    if (!local || at > locAt) {
      var now = Date.now();
      local = { items: parse(data).filter(function (r) { return r.start > now - 3600000; }), at: at, base: baseOf(data) };
      save();
      B.emit('reminders');
    } else if (locAt > at) upload();
  }
  B.on('state', function (all) { if (all) merge(all.reminders || null); });
  B.on('wipe', function () { local = null; B.store.del('reminders'); });

  R.id = function (ch, p) { return ch.id + '-' + Math.floor(p.start / 1000); };
  R.list = function () { return items().slice().sort(function (a, b) { return a.start - b.start; }); };
  R.has = function (id) { return items().some(function (r) { return r.id === id; }); };
  R.add = function (ch, p, playlistID) {
    var id = R.id(ch, p);
    if (R.has(id)) return;
    changed(items().concat([{ id: id, playlistID: playlistID || '', channelID: ch.id, channelName: ch.name, title: p.title || ch.name, start: p.start }]));
  };
  R.remove = function (id) { changed(items().filter(function (r) { return r.id !== id; })); };
  R.toggle = function (ch, p, playlistID) {
    var id = R.id(ch, p);
    if (R.has(id)) { R.remove(id); return false; }
    R.add(ch, p, playlistID);
    return true;
  };

  // MARK: melding in beeld

  var timer = null, banner = null;
  R.start = function () {
    clearInterval(timer);
    timer = setInterval(check, 20000);
    setTimeout(check, 3000);
  };
  /**
   * build 97: niet melden (en niets weghalen) als de app op de achtergrond staat; een herinnering gaat pas uit de
   * lijst als de melding echt een paar seconden in beeld was (of de gebruiker op groen drukte).
   */
  var SEEN_MS = 3000, seenTimer = null;
  function check() {
    if (!B.app || !B.app.profile || document.hidden || R.pending) return;
    var now = Date.now();
    var due = null;
    items().forEach(function (r) { if (!due && r.start <= now + 60000 && r.start > now - 300000) due = r; });
    if (!due) return;
    show(due);
  }
  function markSeen(r) {
    if (r && r.seen !== true) { r.seen = true; if (R.has(r.id)) R.remove(r.id); }
  }
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) {
      // melding nog niet gezien: weghalen zonder de herinnering te wissen (komt terug als de app weer voor staat)
      clearTimeout(seenTimer);
      if (R.pending && R.pending.seen !== true && banner) hide(banner);
    } else if (timer) setTimeout(check, 1500);
  });
  R._check = check;
  function show(r) {
    var U = B.ui, h = U.h;
    if (banner && banner.parentNode) banner.parentNode.removeChild(banner);
    var now = Date.now();
    var sub = r.start > now + 30000 ? L('Starts in %d min on %@', Math.max(1, Math.round((r.start - now) / 60000)), r.channelName) : L('Starts now on %@', r.channelName);
    banner = h('div.reminder-banner', [
      U.icon('bell'),
      h('div.rb-text', [h('div.rb-title', r.title), h('div.dim', sub)]),
      h('div.rb-hint', L('Green: watch live'))
    ]);
    document.body.appendChild(banner);
    var el = banner;
    setTimeout(function () { el.classList.add('show'); }, 20);
    R.pending = r;
    clearTimeout(seenTimer);
    seenTimer = setTimeout(function () { if (!document.hidden && banner === el) markSeen(r); }, SEEN_MS);
    setTimeout(function () { hide(el); }, 20000);
  }
  function hide(el) {
    if (!el) return;
    el.classList.remove('show');
    setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 400);
    if (banner === el) { banner = null; R.pending = null; }
  }
  /** Groene toets (of OK op de melding): naar de zender van de melding. true = afgehandeld. */
  R.onKey = function (name) {
    if (!R.pending || name !== 'green') return false;
    var r = R.pending;
    markSeen(r);
    hide(banner);
    var Lib = B.lib;
    var hit = Lib.lib.byId && Lib.lib.byId[r.channelID];
    if (!hit || hit.type !== 'channel' || (Lib.source && String(r.playlistID).toLowerCase() !== String(Lib.source.id).toLowerCase())) {
      B.ui.toast(L('This channel is not available right now.'));
      return true;
    }
    if (B.player.current) B.player.current.close();
    B.player.playChannel(hit.item, Lib.lib.channels);
    return true;
  };
})();
