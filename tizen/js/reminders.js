/*
 * Herinneringen voor programma's uit de tv-gids (= Reminders op Android). Gedeeld via state-section `reminders`:
 *   {items: [{id: "<zender-id>-<start in s>", playlistID, channelID, channelName, title, start (ISO)}], updatedAt}
 * Het geheel met de nieuwste updatedAt wint. Elke 20 s kijkt de app of er één (bijna) begint: dan een melding in
 * beeld met "Live kijken"; de herinnering gaat daarna uit de lijst (en dat gaat ook naar de andere apparaten).
 *
 * Per profiel (zie docs/ACCOUNT.md → "Herinneringen per profiel" in iptv-ios-appletv): elk profiel zet, ziet en
 * verwijdert alleen zijn eigen herinneringen. ID hoofdprofiel "<zender>-<start>", ander profiel
 * "p:<profiel in kleine letters>:<zender>-<start>", plus het veld profileID. De melding in beeld komt voor de
 * herinneringen van alle profielen, met de profielnaam erbij ("Voor Emma: …").
 */
(function () {
  'use strict';
  var B = window.B;
  var A = B.account;
  var L = function () { return B.L.apply(null, arguments); };
  var R = B.reminders = {};
  var lower = function (s) { return String(s || '').toLowerCase(); };

  var local = B.store.get('reminders', null);   // {items, at}
  function items() { return (local && local.items) || []; }
  function save() { B.store.set('reminders', local); }
  function json() {
    // onbekende velden van de server (nieuwere app-versies) blijven staan, zoals op Android
    var out = B.assign({}, (local && local.base) || {});
    out.items = items().map(function (r) {
      var o = { id: r.id, playlistID: r.playlistID, channelID: r.channelID, channelName: r.channelName, title: r.title, start: B.iso(r.start) };
      if (r.profileID) o.profileID = lower(r.profileID);
      return o;
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
      // profiel: het veld, anders uit de ID ("p:<profiel>:…"), anders het hoofdprofiel (null)
      var prof = r.profileID ? lower(r.profileID) : profileFromID(String(r.id));
      return { id: String(r.id), playlistID: r.playlistID || '', channelID: r.channelID || '', channelName: r.channelName || '', title: r.title || '', start: s, profileID: prof };
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

  // MARK: per profiel

  function profileFromID(id) {
    if (id.indexOf('p:') !== 0) return null;
    var end = id.indexOf(':', 2);
    return end > 2 ? lower(id.slice(2, end)) : null;
  }
  /** Profiel van een herinnering (null = hoofdprofiel). */
  function owner(r) { return r.profileID ? lower(r.profileID) : profileFromID(String(r.id)); }
  function mainID() { var m = B.data.mainProfile(); return m ? lower(m.id) : null; }
  /** Het actieve profiel, of het hoofdprofiel als er (nog) niemand gekozen is. */
  function current() { var p = B.app && B.app.profile; return p ? lower(p.id) : mainID(); }
  /** Profiel-ID voor een nieuwe herinnering: null bij het hoofdprofiel. */
  function ownerFor(prof) { return !prof || prof === mainID() ? null : prof; }
  function isMine(r) {
    var p = B.app && B.app.profile;
    if (!p) return true;
    var o = owner(r);
    return o ? o === lower(p.id) : B.data.isMain(p);
  }
  /** Titel met de profielnaam als het account meer profielen heeft ("Voor Emma: Journaal"). */
  R.displayTitle = function (r) {
    if (B.data.profiles.length <= 1) return r.title;
    var prof = B.data.profileById(owner(r) || mainID());
    var name = prof && String(prof.name || '').trim();
    return name ? L('For %@: %@', name, r.title) : r.title;
  };
  /** Live kijken vanuit de melding: eigen herinnering, of het actieve profiel heeft geen leeftijdsgrens. */
  function canWatch(r) { return isMine(r) || !(B.app && B.app.profile && B.data.ageLimit(B.app.profile) != null); }

  /** ID van de herinnering van het actieve profiel voor dit programma. */
  R.id = function (ch, p) {
    var base = ch.id + '-' + Math.floor(p.start / 1000);
    var o = ownerFor(current());
    return o ? 'p:' + o + ':' + base : base;
  };
  /** Herinneringen van het actieve profiel. */
  R.list = function () { return items().filter(isMine).sort(function (a, b) { return a.start - b.start; }); };
  R.has = function (id) { return items().some(function (r) { return r.id === id; }); };
  R.add = function (ch, p, playlistID) {
    var id = R.id(ch, p);
    if (R.has(id)) return;
    changed(items().concat([{ id: id, playlistID: playlistID || '', channelID: ch.id, channelName: ch.name, title: p.title || ch.name, start: p.start,
      profileID: ownerFor(current()) }]));
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
      h('div.rb-text', [h('div.rb-title', R.displayTitle(r)), h('div.dim', sub)]),
      canWatch(r) ? h('div.rb-hint', L('Green: watch live')) : null
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
    // herinnering van een ander profiel op een profiel met leeftijdsgrens: niet langs het leeftijdsfilter
    if (!canWatch(r)) return true;
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
