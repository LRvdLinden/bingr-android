/*
 * Build 140: ouderpoort (docs/ACCOUNT.md → "Build 140", zoals NLziet), gelijk aan KidsGate.kt op Android. Vóór elke actie
 * waarmee je een actief kinderprofiel verlaat (profiel wisselen, "Wie kijkt er?", Instellingen, account en apparaten,
 * abonnement, uitloggen) eerst een rekensom. Goed = de actie gaat door; fout of Terug = je blijft in het kinderprofiel.
 * Na 3 foute pogingen een nieuwe som. Geen ontgrendeling voor later. Alleen lokaal; er gaat niets naar de server.
 * Geschreven voor Chromium M56 (ES2017).
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui;
  var L = function () { return B.L.apply(null, arguments); };
  var G = B.kidsGate = {};
  /** Na een geslaagde som in "Wie kijkt er?" daar niet nog eens vragen; weer false zodra een kinderprofiel opent. */
  var leftKids = false;

  G.active = function () { var p = B.app && B.app.profile; return !!(p && p.kids); };
  G.kidsOpened = function () { leftKids = false; };

  /** fn uitvoeren; bij een actief kinderprofiel pas na een goede som. */
  G.guard = function (fn) {
    if (!G.active()) { fn(); return; }
    ask(fn);
  };
  /**
   * "Wie kijkt er?" bij het starten: was het laatst actieve profiel op deze tv een kinderprofiel ([lastWasKids]) en kies je
   * een profiel dat geen kinderprofiel is, dan eerst de som. Niet bij het kiezen van een ander kinderprofiel.
   */
  G.guardPicker = function (p, lastWasKids, fn) {
    if (!lastWasKids || leftKids || (p && p.kids)) { fn(); return; }
    ask(fn);
  };

  function ask(fn) {
    var st = B.b140.gate.state();
    var sumEl = U.h('div.kg-sum', st.sum.text);
    var input = '';
    var inEl = U.h('div.kg-input', ' ');
    var err = U.h('div.pin-error');
    var s;
    function draw() { inEl.textContent = input || ' '; sumEl.textContent = st.sum.text; }
    function wrong() {
      input = ''; kb.setValue(''); err.textContent = L("That's not right. Try again.");
      inEl.classList.add('kg-shake');
      setTimeout(function () { inEl.classList.remove('kg-shake'); }, 400);
      draw();
    }
    function check(force) {
      var r = st.check(input, force);
      if (r === 'ok') { U.pop(); leftKids = true; fn(); return; }
      if (r === 'wrong' || r === 'new') wrong();
    }
    var kb = U.keyboard({ layout: 'pin', max: B.b140.gate.MAX_DIGITS, okLabel: L('OK'),
      onChange: function (v) { input = v; err.textContent = ''; draw(); check(false); },
      onDone: function () { if (input) check(true); } });
    var again = U.button(L('New sum'), function () { st.sum = B.b140.gate.newSum(); st.tries = 0; input = ''; kb.setValue(''); err.textContent = ''; draw(); }, { icon: 'replay' });
    var cancel = U.button(L('Cancel'), function () { U.pop(); });
    s = { modal: true, el: U.h('div.layer.modal', [U.h('div.modal-box.pin-box.kg-box', [
      U.h('h2', L('Ask a grown-up')), U.h('div.dim.kg-text', L('Solve this sum to leave the kids profile.')),
      sumEl, inEl, err, kb, U.h('div.kg-buttons.fm', [again, cancel])])]) };
    s.onKey = function (name) {
      if (name.charAt(0) === 'd' && name.length === 2) { kb.press(name.charAt(1)); return true; }
      return false;
    };
    s.onBack = function () { U.pop(); };
    draw();
    U.push(s);
    var first = kb.querySelector('.key');
    if (first) U.focus(first);
  }
})();
