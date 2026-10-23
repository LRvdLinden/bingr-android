/*
 * Build 145 (versie 1.0.1), gelijk aan Build145Logic.kt op Android (zelfde gevallen in tizen/test/build145_test.js en
 * tools/test/Build145LogicTest.kt) en aan de webspeler (web/js/xs.js):
 * "Servers laden bij openen". De gebruiker kiest zelf wanneer de servers worden bijgewerkt; de vernieuwtermijn
 * (settings.shared.refreshInterval: elke dag, week, elke start …) en het automatisch verversen na ±45 s (build 144) zijn weg.
 * Na de splash en de profielkeuze (dus na de juiste pincode), alleen met een bewaarde lijst:
 * - 'ask' (Vragen, standaard): de vraag "Servers bijwerken?" (Nu bijwerken / Later), één keer per keer openen en niet bij
 *   een kinderprofiel (daar geldt Later);
 * - 'always' (Altijd): meteen bijwerken, zonder vraag (ook bij een kinderprofiel);
 * - 'never' (Nooit, alleen handmatig): niets; handmatig met Nu vernieuwen.
 * Bijwerken = alle servers van het account, één voor één, de open server eerst, op de achtergrond. De instelling staat
 * alleen op deze tv (localStorage `loadOnOpen`) en gaat niet mee met de account.
 * Geschreven voor Chromium M56 (ES2017, geen ?. ?? of spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var X = B.b145 = {};

  var L = X.load = { MODES: ['ask', 'always', 'never'], KEY: 'loadOnOpen' };
  /** Geldige stand of null. */
  L.parse = function (raw) {
    var v = raw == null ? '' : String(raw).trim();
    return L.MODES.indexOf(v) >= 0 ? v : null;
  };
  /** De stand op dit apparaat; niets of iets onbekends = 'ask'. */
  L.current = function (stored) { return L.parse(stored) || 'ask'; };
  /** Eenmalige omzetting: zonder geldige nieuwe stand 'ask' (wat de oude termijn ook was); null = niets te doen. */
  L.migrate = function (stored) { return L.parse(stored) ? null : 'ask'; };
  /**
   * Na het kiezen van een profiel: 'ask' (de vraag), 'updateAll' of 'nothing'. hasSavedList = de open server kwam uit de
   * bewaarde lijst (zonder laadt hij zelf, meteen); kids = kinderprofiel; asked = de vraag kwam al bij deze keer openen.
   */
  L.decide = function (mode, hasSavedList, kids, asked) {
    if (mode === 'always') return 'updateAll';
    if (!hasSavedList) return 'nothing';
    if (mode === 'never') return 'nothing';
    if (kids || asked) return 'nothing';
    return 'ask';
  };
  /** Volgorde: de open server eerst, daarna de rest; skip = {id in kleine letters: true} (al bijgewerkt). */
  L.order = function (ids, active, skip) {
    skip = skip || {};
    var out = [], seen = {};
    function add(id) {
      var k = String(id).toLowerCase();
      if (B.has(skip, k) || B.has(seen, k)) return;
      seen[k] = true;
      out.push(id);
    }
    if (active != null) {
      var a = String(active).toLowerCase();
      for (var i = 0; i < ids.length; i++) if (String(ids[i]).toLowerCase() === a) { add(ids[i]); break; }
    }
    ids.forEach(add);
    return out;
  };
  /** Mag de volgende server nu (niet tijdens het afspelen, alleen met een gekozen profiel)? Anders wachten. */
  L.mayRun = function (onMain, playing) { return !!onMain && !playing; };
  L.stillCurrent = function (started, now) { return started === now; };

  // MARK: de stand op deze tv

  L.mode = function () { return L.current(B.store.get(L.KEY, null)); };
  L.setMode = function (v) { var m = L.parse(v); if (m) B.store.set(L.KEY, m); };
  /** Bij het starten, één keer: de oude vernieuwtermijn vervalt, de stand wordt 'ask'. */
  L.migrateNow = function () {
    var m = L.migrate(B.store.get(L.KEY, null));
    if (m) B.store.set(L.KEY, m);
  };
  /** Teksten (gelijk aan Android, Apple en de webspeler). */
  L.LABELS = { ask: 'Ask', always: 'Always', never: 'Never, manually only' };
})();
