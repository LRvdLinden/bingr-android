/*
 * Kijkstatistieken zoals Trakt (build 68): week, maand of jaar, per profiel, via device-auth watch_stats.
 * Hoofdprofiel: filter op alle profielen of één profiel en de verdeling per profiel; andere profielen (ook kinderen)
 * zien alleen hun eigen statistieken. Films in de accentkleur, series blauw, live tv oranje.
 * Pijltjes overal; links/rechts op de grafiek kiest een dag (of maand); Terug sluit.
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui, D = B.data, A = B.account, Lib = B.lib, S = B.screens;
  var h = U.h;
  var L = function () { return B.L.apply(null, arguments); };
  /** Sinds wanneer de server de statistieken bijhoudt (als het antwoord geen since heeft). */
  var SINCE = '2026-10-13';

  // MARK: tekst

  /** "45 min", "2,5 u", "38 u". */
  function fmtTime(min) {
    var m = Math.round(min || 0);
    if (m < 60) return L('%d min', m);
    var hrs = min / 60;
    var txt;
    try { txt = hrs.toLocaleString(B.lang, { maximumFractionDigits: hrs < 10 ? 1 : 0 }); } catch (e) { txt = String(hrs < 10 ? Math.round(hrs * 10) / 10 : Math.round(hrs)); }
    return L('%@ h', txt);
  }
  function nMovies(n) { return n === 1 ? L('1 movie') : L('%d movies', n); }
  function nSeries(n) { return n === 1 ? L('1 series') : L('%d series', n); }
  function nEpisodes(n) { return n === 1 ? L('1 episode') : L('%d episodes', n); }
  function nChannels(n) { return n === 1 ? L('1 channel') : L('%d channels', n); }
  function cap(s) { s = String(s || ''); return s.charAt(0).toUpperCase() + s.slice(1); }
  function fmtDate(d, o) { try { return d.toLocaleDateString(B.lang, o); } catch (e) { return d.toDateString(); } }

  // MARK: datums (weken beginnen op maandag)

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function dayKey(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function monthKey(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1); }
  function parseDay(s) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(s || '');
    return m ? new Date(+m[1], +m[2] - 1, +m[3]) : null;
  }
  function localRange(period, offset) {
    var now = new Date();
    var a = new Date(now.getFullYear(), now.getMonth(), now.getDate()), b;
    if (period === 'week') {
      a.setDate(a.getDate() - (a.getDay() + 6) % 7 + offset * 7);
      b = new Date(a.getFullYear(), a.getMonth(), a.getDate() + 6);
    } else if (period === 'month') {
      a = new Date(a.getFullYear(), a.getMonth() + offset, 1);
      b = new Date(a.getFullYear(), a.getMonth() + 1, 0);
    } else {
      a = new Date(a.getFullYear() + offset, 0, 1);
      b = new Date(a.getFullYear(), 11, 31);
    }
    return { from: a, to: b };
  }
  /** Periode uit het antwoord van de server (die gaat voor), anders zelf berekend. */
  function rangeOf(stats, period, offset) {
    var f = stats && parseDay(stats.from), t = stats && parseDay(stats.to);
    return f && t && t >= f ? { from: f, to: t } : localRange(period, offset);
  }
  function periodLabel(period, offset, r) {
    var txt;
    if (period === 'week') {
      var same = r.from.getFullYear() === r.to.getFullYear();
      txt = fmtDate(r.from, same ? { day: 'numeric', month: 'short' } : { day: 'numeric', month: 'short', year: 'numeric' }) + ' – ' +
        fmtDate(r.to, { day: 'numeric', month: 'short', year: 'numeric' });
    } else if (period === 'month') txt = cap(fmtDate(r.from, { month: 'long', year: 'numeric' }));
    else txt = String(r.from.getFullYear());
    if (offset !== 0) return txt;
    return (period === 'week' ? L('This week') : period === 'month' ? L('This month') : L('This year')) + ' · ' + txt;
  }
  /** Eén staaf per dag (week/maand) of per maand (jaar), ook zonder kijktijd. */
  function slotsOf(period, r, buckets) {
    var year = period === 'year';
    var by = {};
    (buckets || []).forEach(function (b) { by[String(b.key).slice(0, year ? 7 : 10)] = b; });
    var out = [];
    var c = new Date(r.from.getFullYear(), r.from.getMonth(), year ? 1 : r.from.getDate());
    while (c <= r.to && out.length < 400) {
      var k = year ? monthKey(c) : dayKey(c);
      var b = by[k];
      var mv = b ? b.movie : 0, ep = b ? b.episode : 0, lv = b ? b.live : 0;
      // alleen een totaal zonder verdeling (onbekende soort): als film tellen
      if (b && mv + ep + lv <= 0 && b.minutes > 0) mv = b.minutes;
      out.push({ key: k, date: new Date(c.getTime()), movie: mv, episode: ep, live: lv, total: mv + ep + lv });
      if (year) c = new Date(c.getFullYear(), c.getMonth() + 1, 1);
      else c = new Date(c.getFullYear(), c.getMonth(), c.getDate() + 1);
    }
    return out;
  }
  /** Bovenkant van de y-as: een rond aantal uren (minstens 1 uur). */
  function niceMax(m) {
    if (m <= 60) return 60;
    var hrs = m / 60;
    var steps = [1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 24, 30, 40, 50, 60, 80, 100, 120, 150, 200, 250, 300, 400, 500, 600, 800, 1000];
    for (var i = 0; i < steps.length; i++) if (steps[i] >= hrs) return steps[i] * 60;
    return Math.ceil(hrs / 100) * 6000;
  }
  function slotTitle(s, period) {
    return cap(period === 'year' ? fmtDate(s.date, { month: 'long', year: 'numeric' }) : fmtDate(s.date, { weekday: 'long', day: 'numeric', month: 'long' }));
  }

  // MARK: scherm

  S.stats = function () {
    var prof = B.app.profile;
    var main = D.isMain(prof);
    var st = { period: 'month', offset: 0, filter: null, data: null, loading: false, error: null, unavailable: false, expanded: {}, sel: -1, req: 0 };
    var list = S.vScroller('.st-list.fm', { 'data-pad': '120' });
    var el = h('div.layer.listscreen.stats', [h('div.ls-head', [h('h1', L('Statistics'))]), list]);
    var scr = { el: el, onBack: function () { U.pop(); return true; } };
    var chart = null;

    function profileId() { return main ? st.filter : (prof && prof.id) || null; }
    function add(x) { if (x) list.track.appendChild(x); return x; }
    /** Focusbaar element met een vaste sleutel, zodat de focus na opnieuw tekenen op dezelfde plek blijft. */
    function fk(x, key, onEnter) { x.classList.add('f'); x._fk = key; if (onEnter) x.onEnter = onEnter; return x; }

    function load() {
      var req = ++st.req, period = st.period, offset = st.offset, pid = profileId();
      st.loading = true;
      render();
      A.watchStats(period, offset, pid).then(function (d) {
        if (req !== st.req) return;
        st.data = { period: period, offset: offset, profileId: pid, stats: d };
        st.error = null; st.unavailable = false; st.loading = false; st.sel = -1;
        render();
      }, function (e) {
        if (req !== st.req) return;
        st.loading = false;
        if (e && e.unavailable) st.unavailable = true;
        else st.error = (e && e.message) || L('Something went wrong. Please try again.');
        render();
      });
    }

    function controls() {
      var bar = h('div.stx-bar');
      [['week', L('Week')], ['month', L('Month')], ['year', L('Year')]].forEach(function (p) {
        bar.appendChild(fk(h('div.stx-seg' + (st.period === p[0] ? '.active' : ''), p[1]), 'seg-' + p[0], function () {
          if (st.period === p[0]) return;
          st.period = p[0]; st.offset = 0; load();
        }));
      });
      bar.appendChild(h('div.stx-gap'));
      bar.appendChild(fk(h('div.stx-arrow', '‹'), 'prev', function () { st.offset -= 1; load(); }));
      bar.appendChild(h('div.stx-period', periodLabel(st.period, st.offset, rangeOf(st.data && st.data.period === st.period && st.data.offset === st.offset ? st.data.stats : null, st.period, st.offset))));
      // volgende periode: alleen tot nu (blijft focusbaar, zodat de focus niet wegvalt)
      bar.appendChild(fk(h('div.stx-arrow' + (st.offset < 0 ? '' : '.off'), '›'), 'next', function () { if (st.offset < 0) { st.offset += 1; load(); } }));
      if (st.loading && st.data) bar.appendChild(U.spinner());
      return bar;
    }

    function chips() {
      var row = h('div.stx-chips');
      var all = h('div.stx-all', [U.icon('grid')]);
      row.appendChild(fk(h('div.stx-chip' + (st.filter == null ? '.active' : ''), [all, h('span', L('All profiles'))]), 'chip-all', function () {
        if (st.filter == null) return;
        st.filter = null; load();
      }));
      D.profiles.forEach(function (p) {
        row.appendChild(fk(h('div.stx-chip' + (st.filter === p.id ? '.active' : ''), [S.avatar(p, 'mini'), h('span', p.name)]), 'chip-' + p.id, function () {
          if (st.filter === p.id) return;
          st.filter = p.id; load();
        }));
      });
      return row;
    }

    function tile(title, value, detail, color) {
      return h('div.stx-kpi', [
        h('div.stx-kpi-t', [color ? h('i.stx-dot', { style: { background: color } }) : null, title]),
        h('div.stx-kpi-v', value),
        detail ? h('div.stx-kpi-d.dim', detail) : null
      ]);
    }
    function kpis(s) {
      return fk(h('div.stx-kpis', [
        tile(L('Hours watched'), fmtTime(s.totalMinutes), null, null),
        tile(L('Movies'), fmtTime(s.movieMinutes), nMovies(s.movieCount), 'var(--accent)'),
        tile(L('Series'), fmtTime(s.seriesMinutes), nSeries(s.seriesShows) + ' · ' + nEpisodes(s.seriesEpisodes), '#3B82F6'),
        tile(L('Live TV'), fmtTime(s.liveMinutes), nChannels(s.liveChannels), '#F59E0B')
      ]), 'kpi');
    }

    function drawChart(slots, period) {
      var top = niceMax(slots.reduce(function (m, s) { return Math.max(m, s.total); }, 0));
      var title = h('div.stx-chart-title', L('Hours watched'));
      var sub = h('div.stx-chart-sub.dim', '');
      var bars = h('div.stx-bars', [h('div.stx-mid')]);
      var cols = slots.map(function (s) {
        var stack = h('div.stx-stack');
        // van boven naar beneden: live, series, films
        [['l', s.live], ['e', s.episode], ['m', s.movie]].forEach(function (p) {
          if (p[1] > 0) stack.appendChild(h('div.stx-p-' + p[0], { style: { height: Math.max(1, Math.round(Math.min(p[1], top) / top * 300)) + 'px' } }));
        });
        var col = h('div.stx-col', [stack]);
        bars.appendChild(col);
        return col;
      });
      var xl = h('div.stx-xl' + (period === 'month' ? '.month' : ''));
      if (period === 'month') {
        for (var i = 0; i < slots.length; i += 7) {
          xl.appendChild(h('div', { style: { flex: String(Math.min(7, slots.length - i)) } }, String(slots[i].date.getDate())));
        }
      } else {
        slots.forEach(function (s) {
          var t = period === 'year' ? fmtDate(s.date, { month: 'short' }) : fmtDate(s.date, { weekday: 'short' });
          xl.appendChild(h('div', { style: { flex: '1' } }, cap(String(t).replace(/\.$/, '')).slice(0, 3)));
        });
      }
      function legend(color, label) { return h('div', [h('i', { style: { background: color } }), label]); }
      var box = fk(h('div.stx-chart', [
        title, sub,
        h('div.stx-plot', [h('div.stx-axis', [h('div', fmtTime(top)), h('div', fmtTime(top / 2)), h('div', '0')]), bars]),
        xl,
        h('div.stx-legend', [legend('var(--accent)', L('Movies')), legend('#3B82F6', L('Series')), legend('#F59E0B', L('Live TV'))])
      ]), 'chart');
      function show() {
        var cur = slots[st.sel];
        cols.forEach(function (c, i) {
          c.classList.toggle('dim', st.sel >= 0 && i !== st.sel);
          c.classList.toggle('sel', i === st.sel);
        });
        title.textContent = cur ? slotTitle(cur, period) + ' · ' + fmtTime(cur.total) : L('Hours watched');
        sub.textContent = cur ? [[L('Movies'), cur.movie], [L('Series'), cur.episode], [L('Live TV'), cur.live]]
          .filter(function (p) { return p[1] > 0; }).map(function (p) { return p[0] + ' ' + fmtTime(p[1]); }).join(' · ') : '';
      }
      box.onFocus = function () {
        if (st.sel < 0 && slots.length) {
          st.sel = slots.length - 1;
          for (var j = slots.length - 1; j >= 0; j--) if (slots[j].total > 0) { st.sel = j; break; }
        }
        show();
      };
      box.onKey = function (name) {
        if (name === 'left' && st.sel > 0) { st.sel--; show(); return true; }
        if (name === 'right' && st.sel < slots.length - 1) { st.sel++; show(); return true; }
        return false;
      };
      show();
      chart = box;
      return box;
    }

    function topList(id, title, items, live, detail, open) {
      if (!items.length) return;
      add(h('div.st-section', title));
      var more = st.expanded[id] === true;
      items.slice(0, more ? 10 : 5).forEach(function (t, i) {
        var hit = open(t);
        var art = hit ? (live ? hit.logo : hit.poster) : null;
        var d = detail(t);
        add(fk(h('div.st-row.stx-top', [
          h('div.stx-rank', String(i + 1)),
          U.img(art || null, 'stx-poster' + (live ? ' live' : ''), String(t.title || '').slice(0, 2), true),
          h('div.st-label', [h('div', t.title || '—'), d ? h('div.dim', d) : null]),
          h('div.st-value', fmtTime(t.minutes))
        ]), id + '-' + i, function () {
          if (!hit) return;
          if (live) B.player.playChannel(hit, Lib.lib.channels);
          else U.push(S.detail(id === 'series' ? 'series' : 'movie', hit));
        }));
      });
      if (items.length > 5) {
        add(fk(h('div.st-row', [h('div.st-label', more ? L('Show less') : L('Show more'))]), 'more-' + id, function () {
          st.expanded[id] = !more; render();
        }));
      }
    }
    function findIn(arr, key, title, nameField) {
      var t = String(title || '').toLowerCase();
      var byId = null, byName = null;
      for (var i = 0; i < arr.length; i++) {
        if (arr[i].id === key) { byId = arr[i]; break; }
        if (!byName && t && String(arr[i][nameField] || '').toLowerCase() === t) byName = arr[i];
      }
      return byId || byName;
    }

    function confirmClear() {
      var pid = profileId();
      var who = pid ? ((D.profileById(pid) || prof || {}).name || '') : null;
      U.modal({
        title: L('Clear statistics'),
        text: who == null ? L('Clear the viewing statistics of all profiles?') : L('Clear the viewing statistics of %@?', who),
        buttons: [{ label: L('Clear statistics'), fn: function () {
          A.deleteWatchStats(pid).then(function () { U.toast(L('Statistics cleared')); load(); }, function (e) {
            if (e && e.unavailable) { st.unavailable = true; render(); }
            U.toast((e && e.message) || L('Something went wrong. Please try again.'));
          });
        } }, { label: L('Cancel') }]
      });
    }

    function render() {
      var f = U.focused, key = f && el.contains(f) ? f._fk : null;
      U.clear(list.track);
      chart = null;
      add(controls());
      if (main && D.profiles.length > 1) add(chips());
      if (st.unavailable) {
        add(h('div.state.dim', L('Statistics are not available yet. Please try again later.')));
      } else if (!st.data) {
        if (st.loading) add(h('div.state', [U.spinner()]));
        else if (st.error) add(h('div.state', [h('div.dim', st.error), fk(U.button(L('Try again'), null), 'retry', load)]));
      } else {
        var d = st.data, s = d.stats;
        if (st.error) add(h('div.dim.stx-err', st.error));
        var empty = s.totalMinutes <= 0 && s.movieMinutes <= 0 && s.seriesMinutes <= 0 && s.liveMinutes <= 0 &&
          !s.buckets.some(function (b) { return b.minutes > 0; }) && !s.topSeries.length && !s.topMovies.length && !s.topChannels.length;
        if (empty) {
          var since = parseDay(s.since) || parseDay(SINCE);
          add(h('div.state.dim', L('No viewing statistics yet. Bingr keeps track from %@.', fmtDate(since, { day: 'numeric', month: 'long', year: 'numeric' }))));
        } else {
          add(kpis(s));
          add(drawChart(slotsOf(d.period, rangeOf(s, d.period, d.offset), s.buckets), d.period));
          var lib = Lib.lib || { series: [], movies: [], channels: [] };
          topList('series', L('Top series'), s.topSeries, false, function (t) { return t.episodes > 0 ? nEpisodes(t.episodes) : null; },
            function (t) { return findIn(lib.series || [], t.itemKey, t.title, 'title'); });
          topList('movies', L('Top movies'), s.topMovies, false, function () { return null; },
            function (t) { return findIn(lib.movies || [], t.itemKey, t.title, 'title'); });
          topList('channels', L('Top channels'), s.topChannels, true, function () { return null; },
            function (t) { return findIn(lib.channels || [], t.itemKey, t.title, 'name'); });
          var shares = s.profiles.filter(function (p) { return p.minutes > 0; }).sort(function (a, b) { return b.minutes - a.minutes; });
          if (main && d.profileId == null && shares.length) {
            var total = shares.reduce(function (m, p) { return m + p.minutes; }, 0) || 1;
            add(h('div.st-section', L('Per profile')));
            shares.forEach(function (p) {
              var known = p.profileId && D.profileById(p.profileId);
              var name = p.profileName || (known && known.name) || '—';
              var pct = Math.round(p.minutes / total * 100);
              add(fk(h('div.st-row', [S.rowAvatar(p.profileId, name),
                h('div.st-label', [h('div', name), h('div.dim', pct + '%'),
                  h('div.stx-share', [h('div', { style: { width: Math.max(2, pct) + '%' } })])]),
                h('div.st-value', fmtTime(p.minutes))]), 'share-' + (p.profileId || name), function () {
                if (known) { st.filter = known.id; load(); }
              }));
            });
          }
        }
      }
      if (!st.unavailable && (st.data || !st.loading)) {
        add(h('div.st-section', ''));
        var clear = fk(h('div.st-row.danger', [h('div.st-label', L('Clear statistics'))]), 'clear', confirmClear);
        add(clear);
      }
      if (U.top() !== scr) return;
      var all = list.track.querySelectorAll('.f');
      for (var i = 0; key && i < all.length; i++) if (all[i]._fk === key) { U.focus(all[i], true); if (all[i] === chart && chart.onFocus) chart.onFocus(); return; }
      if (!U.focused || !el.contains(U.focused) || !document.body.contains(U.focused)) {
        var seg = list.track.querySelector('.stx-seg.active');
        if (seg) U.focus(seg); else U.focusFirst(list);
      }
    }

    scr.onShow = function (back) { if (!back) load(); };
    return scr;
  };
})();
