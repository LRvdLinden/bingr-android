/*
 * Build 146: de tabs Support en Diagnose in Instellingen op de Samsung TV, het ticket (gesprek, reageren met het
 * tv-toetsenbord, sluiten) en een nieuw ticket (onderwerp, categorie, bericht, "Diagnose meesturen" met "Bekijken").
 * Alles met de afstandsbediening: rijen zijn focusbaar, tekst invoeren gaat met U.editText. Gelijk aan Android
 * (SupportScreens.kt) en de Apple-apps. Geschreven voor Chromium M56 (ES2017, geen ?. ?? of spread).
 */
(function () {
  'use strict';
  var B = window.B;
  var U = B.ui, A = B.account, S = B.screens, D = B.data;
  var L = function () { return B.L.apply(null, arguments); };
  var h = U.h;
  var X = B.b146, SP = X.support, SC = B.support, DG = B.diag;

  function when(ms) { return ms ? new Date(ms).toLocaleString(B.lang || undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : ''; }
  function statusLabel(s) { return L(SP.statusKey(s)); }
  function frow(track, label, sub, fn, cls) {
    var r = h('div.st-row.f' + (cls || ''), [h('div.st-label', [h('div', label), sub ? h('div.dim', sub) : null])]);
    r.onEnter = fn || B.noop;
    track.appendChild(r);
    return r;
  }

  // MARK: tab Support

  S.supportRows = function (track, row, section, note, render) {
    if (!A.isSignedIn()) {
      section(L('Support'));
      note(L('Sign in with your Bingr account to see your support tickets here, reply to them and create a new ticket. Without an account you can still ask a question at support.bingr.online.'));
      row(L('Open support.bingr.online'), S.SUPPORT_URL.replace(/^https?:\/\//, ''), function () { S.support(); });
      return;
    }
    if (!SC.loaded && !SC.loading) SC.refresh(true);
    section(L('Support'));
    row(L('New ticket'), null, function () { S.supportNew(); });
    if (DG.lastProblem) frow(track, L('Send start diagnostics'), L("The previous start of Bingr didn't finish. Send the start diagnostics with a new ticket."), function () { S.supportNew(); });
    row(L('Refresh'), SC.loading ? L('Updating…') : null, function () { SC.refresh(true); });
    if (SC.error) row(SC.error, null, null);
    if (SC.loaded && !SC.items.length) note(L("You don't have any support tickets yet."));
    SP.GROUPS.forEach(function (g) {
      var items = SC.items.filter(function (t) { return t.group === g; });
      if (!items.length) return;
      section(L(SP.groupKey(g)) + ' (' + items.length + ')');
      items.forEach(function (t) {
        var sub = ['#' + t.number, statusLabel(t.status), L('Updated %@', when(t.lastReplyAt || t.createdAt))].join('  ·  ');
        var r = frow(track, t.subject || ('#' + t.number), sub, function () { S.supportTicket(t.number); });
        if (t.unread) r.appendChild(h('span.badge', L('New')));
      });
    });
    note(L('Answers from Bingr Support appear here and are also sent by email.'));
  };

  // MARK: diagnose: bekijken

  S.diagView = function (json) {
    var pre = h('div.diag-json', JSON.stringify(json, null, 2));
    var list = S.vScroller('.diag-scroll.fm', { 'data-pad': '40' });
    var n = h('div.st-note.f', [pre]);
    n.onEnter = B.noop;
    list.track.appendChild(n);
    return U.modal({ title: L('Diagnostics'), content: list, buttons: [{ label: L('Close') }] });
  };

  // MARK: nieuw ticket

  S.supportNew = function () {
    var subject = '', message = '', category = DG.lastProblem ? 'apps' : 'afspelen', withDiag = true, busy = false, error = null;
    var list = S.vScroller('.st-list.fm', { 'data-pad': '120' });
    var el = h('div.layer.listscreen', [h('div.ls-head', [h('h1', L('New ticket'))]), list]);
    var s = { el: el, onBack: function () { if (!busy) U.pop(); return true; } };
    function draw(focusIdx) {
      U.clear(list.track);
      var t = list.track;
      function field(label, value, fn) {
        var r = h('div.st-row.f', [h('div.st-label', label), h('div.st-value' + (value ? '' : '.placeholder'), value || L('Choose'))]);
        r.onEnter = fn; t.appendChild(r); return r;
      }
      field(L('Subject'), subject, function () {
        U.editText({ title: L('Subject'), value: subject, layout: 'text' }, function (v) { subject = String(v || '').slice(0, 120); draw(0); });
      });
      field(L('Category'), L(SP.categoryKey(category)), function () {
        U.choose(L('Category'), SP.CATEGORIES.map(function (c) { return { value: c, label: L(SP.categoryKey(c)) }; }), category, function (v) { category = v; draw(1); });
      });
      field(L('Your message'), message ? (message.length > 60 ? message.slice(0, 60) + '…' : message) : '', function () {
        U.editText({ title: L('Your message'), value: message, layout: 'text', hint: L('Describe your question in at least 10 characters.') },
          function (v) { message = String(v || '').slice(0, 5000); draw(2); });
      });
      field(L('Send diagnostics'), withDiag ? L('On') : L('Off'), function () { withDiag = !withDiag; draw(3); });
      if (withDiag) field(L('View'), null, function () { DG.forSupport().then(function (j) { S.diagView(j); }); });
      var send = h('div.st-row.f.primary-row', [U.icon('check'), h('div.st-label', busy ? L('Sending…') : L('Send'))]);
      send.onEnter = function () {
        if (busy) return;
        busy = true; error = null; draw(-1);
        (withDiag ? DG.forSupport().catch(function () { return null; }) : Promise.resolve(null)).then(function (d) {
          return SC.create(subject, category, message, d).then(function (r) {
            busy = false;
            if (r.number) {
              if (d && d.kind === 'start') DG.clearProblem();
              U.pop();
              U.toast(L('Sent as ticket #%d.', r.number), 4000);
              S.supportTicket(r.number);
            } else { error = r.error; draw(-1); }
          });
        });
      };
      t.appendChild(send);
      if (error) t.appendChild(h('div.st-row.info.danger', [h('div.st-label', error)]));
      note(t, withDiag ? (DG.lastProblem ? L("The previous start of Bingr didn't finish: the start diagnostics are sent.")
        : L('Device, app version, servers (without names or addresses), network and memory. No email, names, passwords or addresses.')) : null);
      var all = t.querySelectorAll('.f');
      if (focusIdx === -1) U.focus(send, true);
      else if (all.length) U.focus(all[Math.max(0, Math.min(focusIdx || 0, all.length - 1))], true);
    }
    function note(t, text) { if (text) { var n = h('div.st-note.f', text); n.onEnter = B.noop; t.appendChild(n); } }
    U.push(s);
    draw(0);
    return s;
  };

  // MARK: een ticket

  S.supportTicket = function (number) {
    var list = S.vScroller('.st-list.fm', { 'data-pad': '120' });
    var title = h('h1', '#' + number);
    var el = h('div.layer.listscreen', [h('div.ls-head', [title]), list]);
    var s = { el: el, onBack: function () { U.pop(); SC.refresh(true); return true; } };
    var data = null, reply = '', withDiag = false, busy = false, error = null;
    function load() {
      U.clear(list.track);
      list.track.appendChild(h('div.state', [U.spinner()]));
      SC.get(number).then(function (r) {
        // 404 (ander account op deze tv, of het ticket bestaat niet meer): terug naar de lijst
        if (r === 'not_found') { if (U.top() === s) { U.pop(); U.toast(L("This ticket wasn't found."), 4000); } return; }
        data = r;
        draw();
      });
    }
    function draw() {
      var t = list.track;
      U.clear(t);
      if (!data || data.error) {
        frow(t, (data && data.error) || L('Something went wrong. Please try again.'), null, null, '.danger');
        frow(t, L('Try again'), null, load);
        if (U.top() === s) U.focusFirst(list);
        return;
      }
      var tk = data.ticket;
      title.textContent = tk.subject || ('#' + tk.number);
      frow(t, '#' + tk.number + '  ·  ' + statusLabel(tk.status), [tk.category ? L(SP.categoryKey(tk.category)) : null, when(tk.createdAt)].filter(Boolean).join('  ·  '));
      t.appendChild(h('div.st-section', L('Conversation')));
      data.messages.forEach(function (m) {
        if (m.author === 'event') { frow(t, L('Status: %@', statusLabel(m.body)), when(m.createdAt), null, '.info-ev'); return; }
        var who = m.author === 'support' ? L('Bingr Support') : L('You');
        frow(t, m.body, [who, when(m.createdAt), m.diagnostics ? L('Diagnostics attached') : null].filter(Boolean).join('  ·  '), null,
          m.author === 'support' ? '.msg-support' : '.msg-you');
      });
      if (tk.canReply) {
        t.appendChild(h('div.st-section', L('Reply')));
        var r1 = h('div.st-row.f', [h('div.st-label', L('Your reply')), h('div.st-value' + (reply ? '' : '.placeholder'), reply ? (reply.length > 50 ? reply.slice(0, 50) + '…' : reply) : L('Choose'))]);
        r1.onEnter = function () { U.editText({ title: L('Your reply'), value: reply, layout: 'text' }, function (v) { reply = String(v || '').slice(0, 5000); draw(); focusLabel(L('Send')); }); };
        t.appendChild(r1);
        var r2 = h('div.st-row.f', [h('div.st-label', L('Send diagnostics')), h('div.st-value', withDiag ? L('On') : L('Off'))]);
        r2.onEnter = function () { withDiag = !withDiag; draw(); focusLabel(L('Send diagnostics')); };
        t.appendChild(r2);
        var r3 = h('div.st-row.f.primary-row', [U.icon('check'), h('div.st-label', busy ? L('Sending…') : L('Send'))]);
        r3.onEnter = function () {
          if (busy || !reply.trim()) return;
          busy = true; error = null; draw(); focusLabel(L('Sending…'));
          (withDiag ? DG.forSupport().catch(function () { return null; }) : Promise.resolve(null)).then(function (d) {
            return SC.reply(number, reply, d);
          }).then(function (err) {
            busy = false;
            if (err) { error = err; draw(); return; }
            reply = ''; withDiag = false; load();
          });
        };
        t.appendChild(r3);
        if (error) t.appendChild(h('div.st-row.info.danger', [h('div.st-label', error)]));
        var r4 = h('div.st-row.f.danger', [h('div.st-label', L('Close ticket'))]);
        r4.onEnter = function () {
          U.modal({ title: L('Close this ticket?'), buttons: [
            { label: L('Close ticket'), fn: function () { SC.close(number).then(function (err) { if (err) U.toast(err, 4000); else load(); }); } },
            { label: L('Cancel') }] });
        };
        t.appendChild(r4);
      } else {
        var n = h('div.st-note.f', L('This ticket is closed. Create a new ticket if you still have a question.'));
        n.onEnter = B.noop;
        t.appendChild(n);
        frow(t, L('New ticket'), null, function () { U.pop(); S.supportNew(); });
      }
      if (U.top() === s && (!U.focused || !el.contains(U.focused))) U.focusFirst(list);
    }
    function focusLabel(txt) {
      var rows = list.track.querySelectorAll('.st-row.f');
      for (var i = 0; i < rows.length; i++) if (rows[i].firstChild && rows[i].firstChild.textContent === txt) { U.focus(rows[i], true); return; }
    }
    U.push(s);
    load();
    return s;
  };

  // MARK: tab Diagnose

  var appDiag = null, appBusy = false, sendResult = null;
  S.diagnoseRows = function (track, row, section, note, render) {
    section(L('This device'));
    row(L('Device type'), L('TV'), null);
    row(L('Model'), DG.model() ? 'Samsung ' + DG.model() : 'Samsung TV', null);
    row(L('System'), 'Tizen ' + DG.osVersion(), null);
    row(L('App version'), B.config.version + ' (' + B.config.build + ')', null);
    row(L('Language'), DG.language(), null);

    section(L('Start diagnostics'));
    var p = DG.lastProblem;
    if (!p) {
      row(L('The last start went fine.'), null, null);
      var marks = DG.marks();
      if (marks.length) note(marks.map(function (m) { return m.name + ' ' + (m.ms / 1000).toFixed(1) + 's' + (m.mb != null ? ' ' + m.mb + 'MB' : ''); }).join('  ·  '));
      note(L("How the app started: milestones with time and memory. If a start doesn't finish or Bingr crashes, it shows here the next time."));
    } else {
      note(X.diag.text(p));
      if (A.isSignedIn()) {
        row(L('Send to support'), null, function () {
          SC.create(L('Start diagnostics') + ' · Samsung ' + (DG.model() || 'TV'), 'apps', L("Start diagnostics from the app (previous start didn't finish)."), DG.startJson(p)).then(function (r) {
            if (r.number) { DG.clearProblem(); U.toast(L('Sent as ticket #%d.', r.number), 4000); } else U.toast(r.error, 4000);
          });
        });
      } else row(L('Sign in to send this to support.'), null, null);
      row(L('Dismiss'), null, function () { DG.clearProblem(); }, { danger: true });
    }

    section(L('App diagnostics'));
    row(appBusy ? L('Checking…') : (appDiag ? L('Run again') : L('Run diagnostics')), null, function () {
      if (appBusy) return;
      appBusy = true; sendResult = null; render();
      DG.app().then(function (j) { appDiag = j; }, function () { appDiag = null; }).then(function () { appBusy = false; render(); });
    });
    var d = appDiag;
    if (d) {
      var st = d.state || {}, net = st.network || {};
      function speed(k) {
        return { ok: L('OK'), slow: L('Slow'), fail: L('Not reachable'), skipped: L('Not checked') }[net[k]] || L('Unknown');
      }
      DG.serverUpdates().forEach(function (u, i) {
        row(L('Server %d (%@)', i + 1, u.kind), u.at ? new Date(u.at).toLocaleString(B.lang || undefined) : L('Never updated'), null);
      });
      if (!D.sources.length) row(L('Servers'), '0', null);
      row(L('Last account sync'), st.lastSync ? new Date(st.lastSync).toLocaleString(B.lang || undefined) + (st.lastSyncOk === false ? '  ·  ' + L('failed') : '') : L('Not yet'), null);
      row(L('Bingr server'), speed('backend') + (net.backendMs != null ? '  ·  ' + net.backendMs + ' ms' : ''), null);
      row(L('Your IPTV server'), speed('provider') + (net.providerMs != null ? '  ·  ' + net.providerMs + ' ms' : ''), null);
      row(L('Network'), net.type || '', null);
      row(L('Storage'), (st.storage && st.storage.dbMB != null ? st.storage.dbMB : 0) + ' MB', null);
      var mem = d.memory || {};
      row(L('Memory'), L('App %d MB · in use %d of %d MB', mem.appMB || 0, mem.usedMB || 0, mem.totalMB || 0), null);
      row(L('Profiles'), String(st.profiles || 0), null);
      var P = B.b145.load;
      row(L('Load servers on open'), L(P.LABELS[P.mode()]), null);
      row(L('View'), null, function () { S.diagView(d); });
      if (A.isSignedIn()) {
        row(L('Send to support'), sendResult, function () {
          SC.create(L('App diagnostics') + ' · Samsung ' + (DG.model() || 'TV'), 'apps', L('App diagnostics sent from the app.'), d).then(function (r) {
            sendResult = r.number ? L('Sent as ticket #%d.', r.number) : r.error;
            render();
          });
        });
      } else row(L('Sign in to send this to support.'), null, null);
    }
    note(L('No personal data: no email, names, usernames, passwords, server names or addresses.'));
  };
})();
