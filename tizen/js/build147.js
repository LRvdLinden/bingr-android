/*
 * Build 147 (versie 1.0.1), gelijk aan Build147Logic.kt op Android (zelfde gevallen in tizen/test/build147_test.js en
 * tools/test/Build147LogicTest.kt). Diagnose-schema 2 (docs/ACCOUNT.md in iptv-ios-appletv, "Build 147").
 * Alleen pure logica, zonder tizen.*-API's of toetscodes (ook bruikbaar voor een andere tv-app op dezelfde basis):
 * - X.start: eerlijke starttijden (app-tijd los van wachten op de gebruiker) en goed / traag / vastgelopen.
 * - X.hang: detector voor lange taken (een gat in een setInterval-tik; geen antwoord binnen 2 s = hang).
 * - X.previous: de vorige start als `start.previous` (none / hang / crash / not_finished).
 * - X.fetch: per server en soort: geleverd, provider leverde niets, ophalen mislukt (korte foutcode) of alles verborgen.
 * - X.storage: één totaal dat precies optelt uit cache, catalogus en downloads.
 * - X.netType: wifi / cellular / ethernet / unknown.
 * - X.slices: werk in kleine stukken met een tijdsbudget.
 * Geschreven voor Chromium M56 (ES2017, geen ?. ?? of spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var X = B.b147 = {};

  // MARK: eerlijke starttijden

  var S = X.start = { SLOW_APP_MS: 5000, SLOW_HANG_MS: 2000, HUNG_MS: 10000, FINAL: 'ready',
    WAITS: [['profiles', 'profile_picked'], ['pin', 'pin_ok']] };
  /** marks = [{name, ms}] → {totalMs, appMs, userWaitMs, finished}. */
  S.timing = function (marks) {
    var at = {}, max = 0;
    (marks || []).forEach(function (m) {
      if (!Object.prototype.hasOwnProperty.call(at, m.name)) at[m.name] = m.ms;
      if (m.ms > max) max = m.ms;
    });
    var ready = Object.prototype.hasOwnProperty.call(at, S.FINAL) ? at[S.FINAL] : null;
    var total = ready != null ? ready : max;
    var wait = 0;
    S.WAITS.forEach(function (w) {
      if (!Object.prototype.hasOwnProperty.call(at, w[0])) return;
      var s = at[w[0]];
      if (s > total) return;
      var e = Object.prototype.hasOwnProperty.call(at, w[1]) ? Math.min(at[w[1]], total) : total;
      if (e > s) wait += e - s;
    });
    wait = Math.max(0, Math.min(total, wait));
    return { totalMs: total, appMs: total - wait, userWaitMs: wait, finished: ready != null };
  };
  /** 'good' | 'slow' | 'hung'; maxHangMs = langste hang op de voorgrond. */
  S.summary = function (appMs, maxHangMs, crashed) {
    if (crashed || maxHangMs > S.HUNG_MS) return 'hung';
    if (appMs > S.SLOW_APP_MS || maxHangMs > S.SLOW_HANG_MS) return 'slow';
    return 'good';
  };
  S.label = function (summary) { return summary === 'hung' ? 'Start: stuck' : summary === 'slow' ? 'Start: slow' : 'Start: good'; };

  // MARK: lange taken (gat in de tik)

  var H = X.hang = { TICK_MS: 250, LIMIT_MS: 2000, MAX_STACK: 30 };
  /**
   * Detector: tick(now, info) bij elke setInterval-tik. Is het gat sinds de vorige tik groter dan LIMIT_MS (plus de tik
   * zelf), dan zat de hoofdthread zo lang vast. info = {foreground, task, afterReady, milestone, trail}; foreground =
   * stond de app vóór én na het gat op de voorgrond (een verborgen app krijgt minder tikken: telt niet).
   * Geeft het hang-object of null.
   */
  H.Detector = function (limit, tickMs) {
    this.limit = limit || H.LIMIT_MS;
    this.tickMs = tickMs || H.TICK_MS;
    this.last = null;
    this.lastFg = true;
    this.maxForegroundMs = 0;
    this.worst = null;
    this.count = 0;
  };
  H.Detector.prototype.tick = function (now, info) {
    info = info || {};
    var fg = info.foreground !== false;
    var prev = this.last, prevFg = this.lastFg;
    this.last = now; this.lastFg = fg;
    if (prev == null) return null;
    var stuck = now - prev - this.tickMs;
    if (stuck < this.limit) return null;
    var e = { startedAt: prev, ms: stuck, task: info.task || null, foreground: fg && prevFg, afterReady: !!info.afterReady,
      milestone: info.milestone || null, stack: (info.trail || []).slice(-H.MAX_STACK) };
    this.count++;
    if (e.foreground && e.ms > this.maxForegroundMs) this.maxForegroundMs = e.ms;
    var w = this.worst;
    if (!w || (e.foreground !== w.foreground ? e.foreground : e.ms > w.ms)) this.worst = e;
    return e;
  };
  /** De app ging naar de achtergrond of kwam terug: het gat daarna telt niet. */
  H.Detector.prototype.reset = function (now) { this.last = now; this.lastFg = false; };

  // MARK: vorige start

  X.previous = function (r) {
    if (!r || !r.marks) return null;
    var last = r.marks.length ? r.marks[r.marks.length - 1] : null;
    var lastMark = last ? last.name : null, lastMs = last ? last.ms : 0;
    function sec(ms) { return Math.round(ms / 100) / 10; }
    if (r.crash) return { problem: 'crash', milestone: lastMark, seconds: sec(lastMs), foreground: true, task: null, stack: (r.crash.stack || []).slice(0, 50) };
    var fg = r.hangFg !== false;
    if (r.hangMs != null && fg && r.hangMs > S.SLOW_HANG_MS) {
      return { problem: 'hang', milestone: r.hangAt || lastMark, seconds: sec(r.hangMs), foreground: true, task: r.hangTask || null,
        stack: (r.hangStack || []).slice(0, 50) };
    }
    if (!r.finished) return { problem: 'not_finished', milestone: lastMark, seconds: sec(lastMs), foreground: true, task: null, stack: [] };
    return { problem: 'none', milestone: lastMark, seconds: sec(lastMs), foreground: fg, task: r.hangMs != null ? (r.hangTask || null) : null,
      stack: r.hangMs != null ? (r.hangStack || []).slice(0, 50) : [] };
  };

  // MARK: ophalen per server en soort

  var F = X.fetch = { KINDS: ['live', 'movie', 'series'] };
  /** raw = wat de provider leverde (null = niet opgehaald), visible = na filters, error = korte foutcode. */
  F.status = function (raw, visible, error) {
    if (error && (raw == null || raw === 0)) return 'failed';
    if (raw == null || raw === 0) return 'empty';
    if (visible != null && visible === 0) return 'hidden';
    return 'ok';
  };
  /** Korte foutcode zonder URL: timeout, http_<code>, parse, network, auth, cancelled of error. */
  F.errorCode = function (type, message, httpCode) {
    if (httpCode >= 100 && httpCode <= 599) return 'http_' + httpCode;
    var t = String(type || '').toLowerCase(), m = String(message || '').toLowerCase();
    var h = /\b(?:http|status)[\s:_]*([1-5][0-9]{2})\b/.exec(m);
    if (h) return 'http_' + h[1];
    if (t.indexOf('timeout') >= 0 || m.indexOf('timeout') >= 0 || m.indexOf('timed out') >= 0) return 'timeout';
    if (t.indexOf('cancel') >= 0 || t.indexOf('abort') >= 0) return 'cancelled';
    if (t.indexOf('json') >= 0 || t.indexOf('syntax') >= 0 || m.indexOf('pars') >= 0 || m.indexOf('json') >= 0) return 'parse';
    if (t.indexOf('network') >= 0 || m.indexOf('network') >= 0 || m.indexOf('failed to fetch') >= 0) return 'network';
    if (m.indexOf('auth') >= 0 || m.indexOf('sign in') >= 0) return 'auth';
    return 'error';
  };
  F.serverName = function (i) { return 's' + (i + 1); };

  // MARK: opslag

  X.storage = function (cacheBytes, catalogBytes, downloadsBytes, freeBytes) {
    function mb(b) { return Math.floor((Math.max(0, b || 0) + 524288) / 1048576); }
    var c = mb(cacheBytes), k = mb(catalogBytes), d = mb(downloadsBytes);
    return { totalMB: c + k + d, cacheMB: c, catalogMB: k, downloadsMB: d, freeMB: freeBytes == null ? null : mb(freeBytes) };
  };

  // MARK: soort verbinding

  /** Ruwe naam van het systeem ('ETHERNET', 'WIFI', 'CELLULAR', '4G', 'NONE', …) → wifi | cellular | ethernet | none | unknown. */
  X.netType = function (raw) {
    var t = String(raw || '').toUpperCase();
    if (t === 'ETHERNET') return 'ethernet';
    if (t === 'WIFI' || t === 'WLAN') return 'wifi';
    if (t === '2G' || t === '2.5G' || t === '3G' || t === '4G' || t === '5G' || t === 'CELLULAR') return 'cellular';
    if (t === 'NONE') return 'none';
    return 'unknown';
  };
  X.netLabel = function (type) {
    return type === 'wifi' ? 'Wi-Fi' : type === 'cellular' ? 'Mobile' : type === 'ethernet' ? 'Ethernet' : type === 'none' ? 'No connection' : 'Unknown';
  };

  // MARK: werk in stukken

  X.slices = { BUDGET_MS: 12 };
  /**
   * Synchroon deel van B.chunked: verwerkt list vanaf i tot het budget op is; geeft de nieuwe index. Zo kan een test met
   * een nepklok controleren dat geen stuk langer duurt dan het budget.
   */
  X.slices.step = function (list, i, each, budget, clock) {
    var t0 = clock();
    while (i < list.length) {
      each(list[i], i);
      i++;
      if ((i & 63) === 0 && clock() - t0 >= budget) break;
    }
    return i;
  };
})();
