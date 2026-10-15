"""Start de Tizen-app in headless Chromium met een stub voor window.tizen/webapis en een neppe backend,
en maakt screenshots van de belangrijkste schermen. Gebruik: python3 tizen/test/screenshots.py <uitvoermap>"""
import json, os, sys, urllib.parse
from playwright.sync_api import sync_playwright

sys.path.insert(0, os.path.dirname(__file__))
import fake_backend as fb

TIZEN = os.environ.get('BINGR_TIZEN_DIR') or os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # build 100: ook het gebouwde pakket
OUT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/bingr-shots'
IMG = os.path.join(OUT, 'img')
os.makedirs(OUT, exist_ok=True)
fb.make_images(IMG)
CLIP = fb.make_media(IMG)

STUB = """
window.tizen = { application: { getCurrentApplication: function () { return { exit: function () { window.__exited = true; } }; } },
  tvinputdevice: { getSupportedKeys: function () { return []; }, registerKey: function () {} } };
window.webapis = { productinfo: { getRealModel: function () { return 'QE55Q80B'; }, getModel: function () { return 'QE55Q80B'; } } };
"""


RESULTS = []      # (naam, ok, detail) van 'walk' en 'expect'
PAGEERRORS = []   # fouten in de console (zonder ontbrekende bestanden en 404's van de testserver)
# focus in orde: element bestaat, is zichtbaar en hoort bij de bovenste laag (of er is in die laag niets te kiezen)
NAVCHECK = """(function () {
  var U = B.ui, f = U.focused, t = U.top();
  if (!t) return 'geen scherm';
  // speler zonder overlay en de tv-gids regelen de toetsen zelf
  if (!f) return t.isPlayer || U.focusables(t.el).length === 0 || t.el.classList.contains('guide') ? 'ok' : 'geen focus';
  if (!document.body.contains(f)) return 'focus los van de pagina';
  if (!t.el.contains(f)) return 'focus buiten bovenste laag (' + t.el.className + ')';
  if (!(f.offsetWidth > 0 || f.offsetParent)) return 'focus onzichtbaar';
  return 'ok';
})()"""


def run(page_setup, mode, steps):
    fb.SAVED.clear()
    fb.MODE['big'] = mode == 'big'
    # aanvragen per scenario (anders tellen eerdere runs mee)
    if os.path.exists(os.path.join(OUT, 'requests.jsonl')):
        os.remove(os.path.join(OUT, 'requests.jsonl'))
    with sync_playwright() as p:
        b = p.chromium.launch(args=['--disable-web-security', '--autoplay-policy=no-user-gesture-required'])
        ctx = b.new_context(viewport={'width': 1920, 'height': 1080})
        ctx.add_init_script(STUB)
        if page_setup:
            ctx.add_init_script('if (!sessionStorage.getItem("seeded")) { sessionStorage.setItem("seeded", "1");' + page_setup + '}')

        def handle(route):
            req = route.request
            u = urllib.parse.urlparse(req.url)
            hdr = {'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/json'}
            if u.netloc.endswith('supabase.co'):
                if u.path.endswith('/functions/v1/device-auth'):
                    data = json.loads(req.post_data or '{}')
                    if data.get('action') in ('put_state', 'stream_start', 'stream_stop', 'register_device', 'put_sources', 'rename_device', 'remove_device', 'heartbeat', 'announcements',
                                              'watch_stats', 'delete_watch_stats'):
                        with open(os.path.join(OUT, 'requests.jsonl'), 'a') as f:
                            f.write(json.dumps(data, ensure_ascii=False) + '\n')
                    code, body = fb.device_auth(data, mode)
                    return route.fulfill(status=code, headers=hdr, body=json.dumps(body))
                if u.path.startswith('/auth/v1/token') and 'grant_type=password' in u.query:
                    data = json.loads(req.post_data or '{}')
                    if data.get('password') != 'geheim123':
                        return route.fulfill(status=400, headers=hdr, body=json.dumps({'error_code': 'invalid_credentials'}))
                    return route.fulfill(status=200, headers=hdr, body=json.dumps(fb.session()))
                if u.path.startswith('/auth/v1/token') or u.path.startswith('/auth/v1/verify'):
                    return route.fulfill(status=200, headers=hdr, body=json.dumps(fb.session()))
                if u.path.startswith('/auth/v1/signup'):
                    # nieuw account dat eerst bevestigd moet worden: een user zonder access_token
                    with open(os.path.join(OUT, 'requests.jsonl'), 'a') as f:
                        f.write(json.dumps({'auth': 'signup', 'body': json.loads(req.post_data or '{}')}) + '\n')
                    return route.fulfill(status=200, headers=hdr, body=json.dumps({'id': 'user-2', 'email': 'nieuw@example.com'}))
                if u.path.startswith('/auth/v1/recover'):
                    with open(os.path.join(OUT, 'requests.jsonl'), 'a') as f:
                        f.write(json.dumps({'auth': 'recover', 'query': u.query}) + '\n')
                    return route.fulfill(status=200, headers=hdr, body='{}')
                if u.path.startswith('/auth/v1/logout'):
                    return route.fulfill(status=204, headers=hdr, body='')
                # profielfoto's (build 100): bucket avatars, alleen met een Bearer-token
                if u.path.startswith('/storage/v1/object/authenticated/avatars/'):
                    # eerste aanvraag mislukt (zoals een tv waarvan het netwerk nog niet klaar is): de app moet het opnieuw proberen
                    if fb.MODE.get('photo_fail_first') and not fb.MODE.get('photo_failed'):
                        fb.MODE['photo_failed'] = True
                        return route.fulfill(status=503, headers=hdr, body='{}')
                    if not (req.headers.get('authorization') or '').startswith('Bearer '):
                        return route.fulfill(status=400, headers=hdr, body='{"error":"missing auth"}')
                    with open(os.path.join(OUT, 'requests.jsonl'), 'a') as f:
                        f.write(json.dumps({'storage': u.path}) + '\n')
                    return route.fulfill(status=200, path=fb.photo_file(IMG), headers={'Access-Control-Allow-Origin': '*', 'Content-Type': 'image/jpeg'})
                if u.path.startswith('/rest/v1/account_data'):
                    if req.method == 'GET':
                        return route.fulfill(status=200, headers=hdr, body=json.dumps([{'profiles': fb.profiles(), 'updated_at': 'x'}]))
                    return route.fulfill(status=201, headers=hdr, body='')
                return route.fulfill(status=404, headers=hdr, body='{}')
            if u.netloc == 'xtream.test':
                q = urllib.parse.parse_qs(u.query)
                if u.path == '/xmltv.php':
                    # gezipte gids (.xml.gz): de app pakt hem zelf uit
                    return route.fulfill(status=200, headers={'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/gzip'}, body=fb.gz(fb.xmltv()))
                # zenders 1–4, terugkijken, films en afleveringen spelen een testfilmpje; de rest geeft een fout
                vid = u.path.startswith('/timeshift/') or u.path.startswith('/movie/') or u.path.startswith('/series/') or \
                    any(u.path.endswith('/%d.%s' % (n, e)) for n in (1, 2, 3, 4) for e in ('m3u8', 'ts'))
                if vid and CLIP:
                    return route.fulfill(status=200, path=CLIP, headers={'Access-Control-Allow-Origin': '*', 'Content-Type': 'video/mp4'})
                if u.path == '/player_api.php':
                    return route.fulfill(status=200, headers=hdr, body=json.dumps(fb.xtream((q.get('action') or [''])[0], q)))
                return route.fulfill(status=404, body='')
            if u.netloc == 'img.test':
                f = os.path.join(IMG, os.path.basename(u.path))
                if os.path.exists(f):
                    return route.fulfill(status=200, path=f, headers={'Access-Control-Allow-Origin': '*'})
                return route.fulfill(status=404, body='')
            if u.netloc in ('video.test',) and CLIP:
                return route.fulfill(status=200, path=CLIP, headers={'Access-Control-Allow-Origin': '*', 'Content-Type': 'video/mp4'})
            o = fb.other(req.url, req.method, req.post_data, IMG)
            if o is not None:
                return route.fulfill(status=o[0], headers=o[1], body=o[2])
            if req.url.startswith('file:'):
                return route.continue_()
            return route.fulfill(status=404, body='')

        ctx.route('**/*', handle)
        page = ctx.new_page()
        logs = []
        page.on('console', lambda m: logs.append(m.type + ': ' + m.text))
        page.on('pageerror', lambda e: logs.append('PAGEERROR: ' + str(e)))
        page.goto('file://' + os.path.join(TIZEN, 'index.html'))
        for st in steps:
            if st[0] == 'wait':
                page.wait_for_timeout(st[1])
            elif st[0] == 'key':
                for k in st[1].split():
                    page.keyboard.press(k)
                    page.wait_for_timeout(st[2] if len(st) > 2 else 250)
            elif st[0] == 'shot':
                page.screenshot(path=os.path.join(OUT, st[1] + '.png'))
            elif st[0] == 'tv':
                # toets met een tv-code (10009 = Terug, 10252 = Play/Pause, 403 = rood …)
                for c in str(st[1]).split():
                    page.evaluate('document.dispatchEvent(new KeyboardEvent("keydown", {keyCode: %d, bubbles: true}))' % int(c))
                    page.wait_for_timeout(st[2] if len(st) > 2 else 300)
            elif st[0] == 'walk':
                # na elke toets: staat de focus op iets zichtbaars in de bovenste laag? (geen doodlopende focus)
                bad = []
                for k in st[1].split():
                    if k.isdigit():
                        page.evaluate('document.dispatchEvent(new KeyboardEvent("keydown", {keyCode: %d, bubbles: true}))' % int(k))
                    else:
                        page.keyboard.press(k)
                    page.wait_for_timeout(st[3] if len(st) > 3 else 120)
                    ok = page.evaluate(NAVCHECK)
                    if ok != 'ok':
                        bad.append(k + ':' + ok)
                RESULTS.append((st[2], not bad, ', '.join(bad[:5])))
                print('WALK', st[2], 'OK' if not bad else 'FOUT ' + ', '.join(bad[:5]))
            elif st[0] == 'backto':
                # Terug (10009) tot de voorwaarde klopt, hooguit n keer (pagina's gaan eerst naar boven of naar hun categorieën)
                n = 0
                while n < st[2] and page.evaluate(st[1]) is not True:
                    page.evaluate('document.dispatchEvent(new KeyboardEvent("keydown", {keyCode: 10009, bubbles: true}))')
                    page.wait_for_timeout(250)
                    n += 1
                    chk = page.evaluate(NAVCHECK)
                    if chk != 'ok':
                        break
                ok = page.evaluate(st[1]) is True
                RESULTS.append((st[3], ok, '' if ok else 'na %d x terug: %s' % (n, page.evaluate(NAVCHECK))))
                print('BACKTO', st[3], 'OK (%dx)' % n if ok else 'FOUT')
            elif st[0] == 'expect':
                try:
                    r = page.evaluate(st[1])
                except Exception as e:
                    r = 'FOUT: ' + str(e).split('\n')[0]
                ok = r is True or r == 1
                RESULTS.append((st[2], ok, '' if ok else str(r)))
                print('EXPECT', st[2], 'OK' if ok else 'FOUT => ' + str(r))
            elif st[0] == 'hold':
                # build 102: OK ingedrukt houden (~0,9 s, met herhaalde keydowns zoals een afstandsbediening)
                page.keyboard.down('Enter')
                for _ in range(6):
                    page.wait_for_timeout(150)
                    page.keyboard.down('Enter')
                page.keyboard.up('Enter')
                page.wait_for_timeout(st[1] if len(st) > 1 else 300)
            elif st[0] == 'eval':
                try:
                    r = page.evaluate(st[1])
                except Exception as e:
                    r = 'FOUT: ' + str(e).split('\n')[0]
                print((st[2] if len(st) > 2 else st[1][:90]), '=>', r)
        for l in logs:
            if 'PAGEERROR' in l or (l.startswith('error:') and 'Failed to load resource' not in l):
                print('  console', l)
                PAGEERRORS.append(l)
        b.close()


# nep-AVPlay (alleen de API; beeld is er in headless niet): sporen uit window.__avTracks, aanroepen in window.__av
AVSTUB = """
window.__av = [];
window.__avTracks = window.__avTracks || [{index: 1, type: 'AUDIO', extra_info: '{"language":"eng"}'}, {index: 2, type: 'AUDIO', extra_info: '{"language":"dut"}'},
  {index: 3, type: 'TEXT', extra_info: '{"track_lang":"eng"}'}];
(function () {
  var st = 'NONE', cur = {AUDIO: 1, TEXT: null}, t = 0, timer = null, ls = null;
  function log(x) { window.__av.push(x); }
  webapis.avplay = {
    open: function (u) { log('open'); window.__avUrl = u; st = 'IDLE'; t = 0; },
    // build 102: beeldverhouding en snelheid (Opties in de speler) worden gelogd; de luisteraar voor nep-ondertitels
    setDisplayRect: function (x, y, w, h) { if (x || w !== 1920) log('rect:' + x + ':' + w); }, setDisplayMethod: function (m) { log('method:' + m); },
    setListener: function (l) { ls = l; window.__avListener = l; },
    setSpeed: function (r) { log('speed:' + r); },
    setTimeoutForBuffering: function () {}, setBufferingParam: function (a, b, c) { log('buf:' + a + ':' + c); },
    prepareAsync: function (ok) { log('prepare'); setTimeout(function () { st = 'READY'; ok(); }, 150); },
    play: function () { log('play'); st = 'PLAYING'; clearInterval(timer); timer = setInterval(function () { t += 250; }, 250); },
    pause: function () { log('pause'); st = 'PAUSED'; clearInterval(timer); }, stop: function () { log('stop'); st = 'IDLE'; clearInterval(timer); },
    close: function () { log('close'); st = 'NONE'; }, getState: function () { return st; },
    getDuration: function () { return 5400000; }, getCurrentTime: function () { return t; },
    seekTo: function (ms, ok) { log('seek:' + ms); t = ms; if (ok) ok(); },
    getTotalTrackInfo: function () { return window.__avTracks; },
    getCurrentStreamInfo: function () { var o = []; if (cur.AUDIO != null) o.push({type: 'AUDIO', index: cur.AUDIO}); if (cur.TEXT != null) o.push({type: 'TEXT', index: cur.TEXT}); return o; },
    setSelectTrack: function (k, i) { log('select:' + k + ':' + i + ':' + st); cur[k] = i; },
    setSilentSubtitle: function (b) { log('silent:' + b); }
  };
})();
"""

SIGNED_IN = "localStorage.setItem('bingr.auth', JSON.stringify({access_token:'h.eyJzdWIiOiJ1c2VyLTEifQ.s', refresh_token:'r1', expires_at: Math.floor(Date.now()/1000)+3600, email:'leon@example.com', user_id:'user-1'})); localStorage.setItem('bingr.deviceRegistered','true');"

if __name__ == '__main__':
    which = sys.argv[2] if len(sys.argv) > 2 else 'all'
    if which in ('all', 'login'):
        run('', 'normal', [('wait', 300), ('shot', '01-splash'), ('wait', 1800), ('shot', '02-login-qr'),
                           ('key', 'Enter', 600), ('shot', '02b-email')])
    if which in ('all', 'qr'):
        run('', 'approve', [('wait', 6000), ('shot', '02c-after-qr'),
                            ('eval', 'JSON.stringify({auth: !!localStorage.getItem("bingr.auth"), reg: localStorage.getItem("bingr.deviceRegistered"), dev: localStorage.getItem("bingr.deviceId"), profiles: B.data.profiles.length})')])
    if which in ('all', 'photo'):
        # build 100: profielfoto uit het account op "Wie kijkt er?" en in het menu
        fb.MODE['photo'] = True
        run(SIGNED_IN, 'normal', [('wait', 3000), ('shot', '04p-profiles-photo'),
            ('expect', 'document.querySelectorAll(".avatar.has-photo").length >= 1', 'profielfoto zichtbaar op Wie kijkt er?'),
            ('key', 'Enter', 400), ('wait', 2500),
            ('expect', 'document.querySelectorAll(".avatar.has-photo").length >= 1 || !!document.querySelector(".has-photo")', 'profielfoto ook na kiezen')])
        # eerste poging mislukt → na ~25 s alsnog de foto
        fb.MODE['photo_fail_first'] = True; fb.MODE['photo_failed'] = False
        run(SIGNED_IN, 'normal', [('wait', 3000),
            ('expect', 'document.querySelectorAll(".avatar.has-photo").length === 0', 'eerste poging mislukt: nog geen foto'),
            ('wait', 27000),
            ('expect', 'document.querySelectorAll(".avatar.has-photo").length >= 1', 'profielfoto na nieuwe poging')])
        fb.MODE['photo'] = False; fb.MODE['photo_fail_first'] = False
    if which in ('all', 'limit'):
        run(SIGNED_IN.replace("'true'", "'false'"), 'limit', [('wait', 2500), ('shot', '03-device-limit')])
    if which in ('all', 'app'):
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('shot', '04-profiles'),
            ('key', 'Enter', 400), ('wait', 2500), ('shot', '05-home'),
            ('key', 'ArrowDown', 400), ('shot', '06-home-continue'),
            ('key', 'ArrowDown ArrowDown ArrowDown', 400), ('shot', '07-home-rows'),
            ('key', 'ArrowLeft', 400), ('shot', '08-nav-open'),
            ('key', 'ArrowDown Enter', 600), ('wait', 800), ('shot', '09-live'),
            ('key', 'ArrowRight ArrowRight ArrowDown', 300), ('shot', '10-live-channel'),
            ('eval', 'document.querySelector(".lv-guide-btn") ? 1 : 0'),
        ])
    if which in ('all', 'app2'):
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
            ('key', 'ArrowLeft', 300), ('eval', 'B.ui.focused.textContent'),
            ('key', 'ArrowDown ArrowDown Enter', 350), ('wait', 600), ('shot', '11-movies'),
            ('key', 'ArrowRight ArrowRight', 300), ('shot', '11b-movies-grid'),
            ('key', 'Enter', 400), ('wait', 1200), ('shot', '11c-movie-detail'),
            ('key', 'ArrowRight', 300), ('shot', '11d-detail-play-unfocused'),
            ('key', 'ArrowLeft Enter', 500), ('shot', '11e-resume-modal'),
            ('key', 'Escape', 400), ('key', 'Escape', 400),
            ('eval', 'B.ui.top().show("series", true) || 1'), ('wait', 600), ('key', 'Enter', 400), ('wait', 1800), ('shot', '13-series-detail'),
            ('key', 'ArrowDown ArrowDown', 400), ('shot', '14-series-episodes'),
            ('key', 'Escape', 400),
            ('eval', 'B.ui.top().show("settings", true) || 1'), ('wait', 600), ('shot', '15-settings'),
            ('eval', 'B.ui.top().show("search", true) || 1'), ('wait', 300),
            ('key', 'ArrowDown ArrowDown Enter ArrowRight ArrowRight ArrowRight ArrowRight Enter', 200), ('wait', 1000), ('shot', '16-search'),
            ('eval', 'B.ui.top().show("mylist", true) || 1'), ('wait', 300), ('shot', '16b-mylist'),
        ])
    if which in ('all', 'guide'):
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
            ('key', 'ArrowLeft ArrowDown ArrowDown Enter', 350), ('wait', 800),
            ('eval', 'B.ui.push(B.screens.guide(B.lib.lib.channels, 3)) && 1'), ('wait', 500),
            ('key', 'ArrowRight ArrowDown', 300), ('shot', '17-guide'),
            ('key', 'Escape', 300),
            ('eval', 'B.player.playChannel(B.lib.lib.channels[0], B.lib.lib.channels) && 1'), ('wait', 2500), ('shot', '18-player-live'),
            ('eval', 'B.player.current.close() || 1'), ('wait', 500),
            ('eval', 'B.player.playChannel(B.lib.lib.channels[10], B.lib.lib.channels) && 1'), ('wait', 4500), ('shot', '19-player-error'),
        ])
    if which in ('all', 'kids'):
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'ArrowRight ArrowRight Enter', 400), ('wait', 2500), ('shot', '20-kids-home'),
            ('eval', 'JSON.stringify({ch: B.lib.lib.channels.map(function(c){return c.name}), mv: B.lib.lib.movies.length, se: B.lib.lib.series.map(function(s){return s.title})})'),
        ])
    if which in ('all', 'pin'):
        run(SIGNED_IN, 'normal', [('wait', 2500), ('key', 'ArrowRight Enter', 400), ('shot', '21-pin'),
                                  ('key', '1 2 3 4', 200), ('wait', 2500), ('shot', '22-sam-home')])
    if which in ('all', 'sync'):
        run(SIGNED_IN, 'normal', [('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
                                  ('eval', 'B.data.toggleSet("myList", B.lib.source.id + "|xv9"); B.data.setProgress("xv9", {fraction: 0.3, position: 600, title: "Iron Valley"}); B.data.flush(); 1'),
                                  ('wait', 1500)])
    if which in ('all', 'streamlimit'):
        run(SIGNED_IN, 'streamlimit', [('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
                                         ('eval', 'B.player.playChannel(B.lib.lib.channels[0], B.lib.lib.channels) && 1'), ('wait', 1500), ('shot', '23-stream-limit')])
    if which in ('all', 'live2'):
        # Live TV: voorbeeld na stilstand, snel scrollen met een virtuele lijst, gids met terugkijken en herinneringen
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
            ('key', 'ArrowLeft ArrowDown Enter', 350), ('wait', 600),
            ('key', 'ArrowLeft ArrowDown ArrowDown', 300), ('key', 'ArrowRight', 300), ('wait', 3500), ('shot', '24-live-preview'),
            ('eval', 'document.querySelector(".lv-pv-video").className', 'preview-klasse'),
            ('eval', 'document.querySelectorAll(".lv-vlist .lv-chan").length + " rijen in de DOM voor " + B.lib.lib.channels.length + " zenders"', 'virtuele lijst'),
            ('eval', '(function(){var t=performance.now();for(var i=0;i<15;i++){document.dispatchEvent(new KeyboardEvent("keydown",{keyCode:40,bubbles:true}));} return Math.round(performance.now()-t)+" ms voor 15x omlaag"})()', 'toetsen'),
            ('wait', 600), ('shot', '24b-live-scrolled'),
            ('eval', 'Object.keys(B.lib.raw.epg).length + " zenders met gids (uit .gz)"', 'gzip-gids'),
            ('eval', 'B.ui.push(B.screens.guide(B.lib.lib.channels, 1)) && 1'), ('wait', 500),
            ('key', 'ArrowLeft ArrowLeft ArrowLeft', 250), ('shot', '25-guide-catchup'),
            ('key', 'Enter', 400), ('shot', '25b-program-sheet'),
            ('key', 'Enter', 400), ('wait', 2500), ('shot', '26-player-catchup'),
            ('eval', 'B.player.current && B.player.current.el.querySelector(".pl-title").textContent', 'titel'),
            ('key', 'Escape Escape', 500), ('wait', 400),
            ('eval', 'B.ui.top().el.className', 'scherm'),
            ('key', 'ArrowRight ArrowRight ArrowRight ArrowRight ArrowRight ArrowRight', 200), ('key', 'Enter', 400), ('shot', '27-program-future'),
            ('key', 'Enter', 400), ('wait', 300), ('shot', '27b-guide-reminder'),
            ('eval', 'JSON.stringify(B.reminders.list())', 'herinneringen'),
            ('eval', 'B.reminders.add(B.lib.lib.channels[2], {title: "Journaal", start: Date.now() + 30000, end: Date.now() + 900000}, B.lib.source.id); B.reminders._check(); 1'),
            ('wait', 600), ('shot', '28-reminder-banner'),
        ])
    if which in ('all', 'multiview'):
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
            ('eval', 'B.multiview.open(B.lib.lib.channels, 0) && 1'), ('wait', 3000), ('shot', '29-multiview'),
            ('key', 'ArrowRight ArrowRight', 300), ('key', 'Enter', 500), ('shot', '29b-multiview-add'),
            ('key', 'ArrowDown ArrowDown Enter', 400), ('wait', 2500), ('shot', '29c-multiview-3'),
            ('key', 'ArrowLeft', 300), ('key', 'Enter', 300), ('wait', 2000), ('shot', '29d-multiview-swap'),
            ('eval', 'Array.prototype.map.call(document.querySelectorAll(".mv-tile:not(.add)"), function(t){return t.className}).join(" | ")', 'tegels'),
        ])
    if which in ('all', 'servers'):
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
            ('eval', 'B.ui.top().show("settings", true) || 1'), ('wait', 600), ('shot', '30-settings-new'),
            ('key', 'ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown', 120), ('wait', 400), ('shot', '30b-settings-scrolled'),
            ('eval', 'B.ui.push(B.screens.servers()) && 1'), ('wait', 1200), ('shot', '31-servers'),
            ('key', 'ArrowDown Enter', 400), ('shot', '31b-server-menu'),
            ('key', 'ArrowDown Enter', 600), ('shot', '32-server-edit-xtream'),
            ('key', 'Enter', 400), ('shot', '33-keyboard-qwerty'),
            ('key', 'ArrowDown ArrowDown ArrowDown ArrowDown Enter', 200), ('key', 'ArrowRight ArrowRight Enter', 200), ('shot', '33b-keyboard-typed'),
            ('key', 'Escape', 300), ('key', 'Escape', 300), ('wait', 300),
            ('key', 'ArrowUp ArrowUp ArrowUp ArrowUp ArrowUp', 100), ('key', 'Enter', 400), ('shot', '34-add-server-kind'),
            ('key', 'ArrowDown ArrowDown ArrowDown ArrowDown Enter', 500), ('shot', '35-add-jellyfin'),
            ('key', 'Escape', 300),
            ('eval', 'B.ui.push(B.screens.serverEdit({kind: "Plex"})) && 1'), ('wait', 500),
            ('key', 'ArrowDown Enter', 400), ('wait', 300), ('shot', '36-plex-link'), ('wait', 3500), ('shot', '36b-plex-linked'),
            ('key', 'ArrowDown ArrowDown Enter', 300), ('wait', 1500), ('shot', '36c-plex-saved'),
            ('eval', 'JSON.stringify(B.data.sources.map(function(s){return s.name + ":" + s.kind}))', 'bronnen'),
        ])
        print(open(os.path.join(OUT, 'requests.jsonl')).read()[-1500:] if os.path.exists(os.path.join(OUT, 'requests.jsonl')) else '')
    if which in ('all', 'connect'):
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 3000),
            ('eval', 'B.screens.traktMenu(function(){}) || 1'), ('wait', 400), ('shot', '37-trakt-code'), ('wait', 6500),
            ('eval', 'JSON.stringify({trakt: B.trakt.isLoggedIn(), user: B.trakt.username()})', 'trakt'),
            ('eval', 'B.ui.top().show("settings", true) || 1'), ('wait', 800), ('shot', '38-settings-trakt'),
            ('eval', 'B.opensubtitles.login("leon", "geheim").then(function(){return 1})'), ('wait', 500),
            ('eval', 'B.player.playVOD(B.player.movieItem(B.lib.lib.byId["xv9"].item), {resume: 0}) || 1'), ('wait', 2500),
            ('key', 'ArrowRight ArrowRight ArrowRight Enter', 300), ('wait', 300), ('shot', '39-subtitle-menu'),
            ('key', 'ArrowDown ArrowDown Enter', 300), ('wait', 1500), ('shot', '39b-opensubtitles-list'),
            ('key', 'Enter', 300), ('wait', 2500), ('shot', '40-opensubtitles-overlay'),
            ('eval', 'document.querySelector(".pl-sub").textContent', 'ondertitel'),
            ('eval', 'B.player.current.close() || 1'), ('wait', 1500),
            ('eval', 'JSON.stringify((B.data.sources.length))', 'bronnen'),
        ])
        print(open(os.path.join(OUT, 'requests.jsonl')).read()[-2500:] if os.path.exists(os.path.join(OUT, 'requests.jsonl')) else '')
    if which in ('all', 'intro'):
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
            ('eval', 'B.ui.push(B.screens.detail("series", B.lib.lib.byId["xs1"].item)) && 1'), ('wait', 1500), ('shot', '41-detail-trakt'),
            ('key', 'Enter', 400), ('wait', 500), ('key', 'ArrowDown Enter', 300), ('wait', 3000),
            ('key', 'Enter', 300), ('eval', 'B.ui.focused && B.ui.focused.textContent', 'focus'),
            ('eval', 'document.querySelector(".pl-buttons") && Array.prototype.map.call(document.querySelectorAll(".pl-buttons .btn"), function(b){return b.textContent}).join("|")', 'knoppen'),
            ('key', 'r', 100),
            ('eval', 'document.dispatchEvent(new KeyboardEvent("keydown",{keyCode:403,bubbles:true})) && 1'), ('wait', 600), ('shot', '42-intro-panel'),
            ('key', 'Enter', 300), ('key', 'ArrowDown Enter', 300), ('wait', 300), ('shot', '42b-intro-marked'),
            ('eval', 'JSON.stringify(B.data.shared("introMarkers"))', 'markers'),
        ])
    if which in ('all', 'media'):
        for sid, name in (('B2C3D4E5-0000-4000-8000-000000000002', 'jellyfin'), ('C3D4E5F6-0000-4000-8000-000000000003', 'plex'), ('D4E5F6A7-0000-4000-8000-000000000004', 'stremio')):
            run(SIGNED_IN + "localStorage.setItem('bingr.activeSource', JSON.stringify('%s'));" % sid, 'normal', [
                ('wait', 2500), ('key', 'Enter', 400), ('wait', 3500), ('shot', '43-home-' + name),
                ('eval', 'JSON.stringify({src: B.lib.source && B.lib.source.kind, m: B.lib.lib.movies.length, s: B.lib.lib.series.length, err: B.lib.error})', name),
                ('eval', 'B.ui.push(B.screens.detail("series", B.lib.lib.series[0])) && 1'), ('wait', 2500), ('shot', '44-detail-' + name),
                ('eval', 'Array.prototype.map.call(document.querySelectorAll(".ep-title"), function(e){return e.textContent}).join("|")', 'afleveringen'),
                ('eval', 'B.lib.resolveURL(B.player.movieItem(B.lib.lib.movies[0]), false)', 'film-url'),
            ])
    if which in ('all', 'ages'):
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'ArrowRight ArrowRight Enter', 400), ('wait', 9000), ('shot', '45-kids-ages'),
            ('eval', 'JSON.stringify({mv: B.lib.lib.movies.map(function(m){return m.title}), cache: Object.keys(B.ageRatings._cache()).length})', 'kinderen'),
        ])
    if which in ('all', 'extra'):
        # schermen die hierboven niet langskomen: M3U-, Stalker-, Emby- en Stremio-formulier, herinneringen,
        # OpenSubtitles-inloggen, multiview met 4 tegels, server verwijderen (tombstone)
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
            ('eval', 'B.ui.push(B.screens.serverEdit({kind: "M3U / M3U8"})) && 1'), ('wait', 400), ('shot', '48-add-m3u'), ('key', 'Escape', 300),
            ('eval', 'B.ui.push(B.screens.serverEdit({kind: "Stalker / MAC-portal"})) && 1'), ('wait', 400), ('shot', '49-add-stalker'), ('key', 'Escape', 300),
            ('eval', 'B.ui.push(B.screens.serverEdit({kind: "Emby"})) && 1'), ('wait', 400), ('shot', '50-add-emby'), ('key', 'Escape', 300),
            ('eval', 'B.ui.push(B.screens.serverEdit({kind: "Stremio"})) && 1'), ('wait', 400), ('shot', '51-add-stremio'), ('key', 'Escape', 300),
            ('eval', 'B.reminders.add(B.lib.lib.channels[3], {title: "Voetbal: Ajax – PSV", start: Date.now() + 7200000, end: Date.now() + 9000000}, B.lib.source.id); B.ui.push(B.screens.remindersList()) && 1'),
            ('wait', 600), ('shot', '52-reminders'), ('key', 'Escape', 300),
            ('eval', 'B.screens.openSubtitlesMenu(function(){}) || 1'), ('wait', 400), ('shot', '53-opensubtitles-login'), ('key', 'Escape', 300),
            ('eval', 'B.multiview.open(B.lib.lib.channels, 0) && 1'), ('wait', 1500),
            ('key', 'ArrowRight ArrowRight', 200), ('key', 'Enter', 400), ('key', 'Enter', 400), ('wait', 800),
            ('eval', 'var a = document.querySelector(".mv-tile.add"); B.ui.focus(a); 1'), ('key', 'Enter', 400), ('key', 'ArrowDown Enter', 400), ('wait', 2000), ('shot', '54-multiview-4'),
            ('eval', 'document.querySelectorAll(".mv-tile:not(.add)").length', 'tegels'),
            ('eval', 'B.multiview.current.close() || 1'), ('wait', 400),
            ('eval', 'B.data.deleteSource("D4E5F6A7-0000-4000-8000-000000000004").then(function(){return JSON.stringify(B.data.sources.map(function(s){return s.name}))})', 'na verwijderen'),
        ])
        print([l for l in open(os.path.join(OUT, 'requests.jsonl')).read().splitlines() if 'put_sources' in l][-1][-400:] if os.path.exists(os.path.join(OUT, 'requests.jsonl')) else '')
    if which in ('all', 'lang'):
        # build 59: taal van de titel (strook op de poster, "Taal:" op de detailpagina, Andere taal), gewenste audiotaal
        # via AVPlay, audiopaneel met één audiotaal, automatische ondertitels en een start die nergens op wacht
        run(SIGNED_IN + AVSTUB, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 3000),
            ('eval', 'JSON.stringify(B.lib.lib.movies.filter(function(m){return +m.id.slice(2) > 100}).map(function(m){return m.title + " = " + B.clang.badge(B.clang.forItem(m, false))}))', 'titels'),
            ('eval', 'B.ui.top().show("movies", true) || 1'), ('wait', 800), ('key', 'ArrowDown ArrowDown ArrowDown ArrowDown', 250), ('key', 'ArrowRight', 300), ('wait', 800), ('shot', '55-lang-posters'),
            ('eval', 'B.ui.push(B.screens.detail("movie", B.lib.lib.byId["xv101"].item)) && 1'), ('wait', 1500), ('shot', '56-lang-detail'),
            ('eval', 'document.querySelector(".dt-lang").textContent + " | " + Array.prototype.map.call(document.querySelectorAll(".dt-buttons .btn"), function(b){return b.textContent}).join("|")', 'detail'),
            ('eval', 'var b = Array.prototype.filter.call(document.querySelectorAll(".dt-buttons .btn"), function(b){return b.querySelector(".ic") && b.textContent.indexOf("taal") >= 0})[0]; B.ui.focus(b); 1'),
            ('key', 'Enter', 400), ('shot', '57-lang-versions'), ('key', 'Escape', 300), ('key', 'Escape', 300),
            ('eval', 'B.opensubtitles.login("leon", "geheim").then(function(){return 1})'), ('wait', 300),
            # gewenste audiotaal: app-taal nl → spoor 2 (dut) vóór play()
            ('eval', 'B.player.playVOD(B.player.movieItem(B.lib.lib.byId["xv5"].item), {resume: 0}) || 1'), ('wait', 2500),
            ('eval', 'JSON.stringify({av: window.__av, t: B.player.timings})', 'avplay + tijden'),
            ('eval', 'B.player.current.close() || 1'), ('wait', 600),
            # één audiotaal (Italiaans): automatisch Nederlandse ondertitels (OpenSubtitles) en het audiopaneel
            ('eval', 'window.__avTracks = [{index: 1, type: "AUDIO", extra_info: "{\\"language\\":\\"ita\\"}"}]; window.__av = []; B.player.playVOD(B.player.movieItem(B.lib.lib.byId["xv101"].item), {resume: 0}) || 1'),
            ('wait', 3500), ('shot', '58-lang-autosubs'),
            ('eval', 'document.querySelector(".pl-sub").textContent + " | " + (document.querySelector(".toast") ? document.querySelector(".toast").textContent : "")', 'ondertitel'),
            ('eval', 'var b = Array.prototype.filter.call(document.querySelectorAll(".pl-buttons .btn"), function(b){return b.textContent === "Audio"})[0]; B.ui.focus(b); 1'),
            ('key', 'Enter', 400), ('shot', '59-lang-audio-panel'),
            ('eval', 'document.querySelector(".modal-box").textContent', 'paneel'),
            ('key', 'Enter', 400), ('wait', 2500),
            ('eval', 'JSON.stringify({title: document.querySelector(".pl-title").textContent, av: window.__av.slice(-6)})', 'na wisselen'),
            ('eval', 'B.player.current.close() || 1'), ('wait', 400),
            ('eval', 'B.ui.top().show("settings", true) || 1'), ('wait', 600),
            ('eval', 'var r = Array.prototype.filter.call(document.querySelectorAll(".st-row"), function(r){return r.textContent.indexOf("Automatische") === 0})[0]; B.ui.focus(r); 1'), ('wait', 300), ('shot', '60-lang-settings'),
        ])
    if which in ('all', 'announce'):
        # mededelingen (build 62): na de profielkeuze één voor één (oudste eerst), OK en Terug sluiten; gelezen ids lokaal
        seen = "localStorage.setItem('bingr.announcements.seen', JSON.stringify(['a0']));"
        run(SIGNED_IN + seen, 'announce', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 6000), ('shot', '61-announcement'),
            ('expect', 'B.ui.top().modal === true && document.querySelector(".modal-box h2").textContent === "Welkom bij Bingr"', 'oudste mededeling eerst'),
            ('walk', 'Enter', 'mededeling: OK'), ('wait', 300),
            ('expect', 'B.ui.top().modal === true && document.querySelector(".modal-box h2").textContent === "Onderhoud vannacht"', 'daarna de volgende'),
            ('shot', '62-announcement-2'),
            ('walk', '10009', 'mededeling: terug'), ('wait', 300),
            ('expect', 'B.ui.top().el.classList.contains("main")', 'mededelingen dicht'),
            ('expect', 'JSON.stringify(B.store.get("announcements.seen")) === JSON.stringify(["a0","a1","a2"])', 'gelezen ids bewaard'),
            ('eval', 'JSON.stringify(B.account.appVersion()) + " " + JSON.stringify(B.account.osVersion())', 'versies'),
        ])
        with open(os.path.join(OUT, 'requests.jsonl')) as f:
            reqs = [json.loads(l) for l in f if l.strip()]
        hb = [r for r in reqs if r.get('action') == 'heartbeat']
        ok = bool(hb) and all(r.get('app_version') and r.get('os_version', '').startswith('Tizen') for r in hb)
        RESULTS.append(('heartbeat met app_version en os_version', ok, json.dumps(hb[:1], ensure_ascii=False)))
        an = [r for r in reqs if r.get('action') == 'announcements']
        RESULTS.append(('announcements één keer met lang', len(an) == 1 and an[0].get('lang') == 'nl', json.dumps(an, ensure_ascii=False)))
    if which in ('all', 'perf'):
        # prestaties met een grote bibliotheek: lange taken (> 50 ms) tijdens snel scrollen tellen
        LT = 'window.__lt = []; try { new PerformanceObserver(function (l) { l.getEntries().forEach(function (e) { window.__lt.push(Math.round(e.duration)); }); }).observe({entryTypes: ["longtask"]}); } catch (e) {} 1'
        run(SIGNED_IN, 'big', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 6000),
            ('eval', 'JSON.stringify({ch: B.lib.lib.channels.length, mv: B.lib.lib.movies.length})', 'bibliotheek'),
            ('eval', LT),
            ('key', 'ArrowDown ArrowDown ArrowDown ArrowRight ArrowRight ArrowRight ArrowRight ArrowRight ArrowRight ArrowDown ArrowDown ArrowUp', 60),
            ('wait', 800), ('eval', 'JSON.stringify(window.__lt)', 'lange taken Home'),
            ('eval', 'B.store.set("liveGroup", "*"); B.ui.top().show("live", true) || 1'), ('wait', 1500), ('eval', LT),
            ('eval', '(function(){var t=performance.now();for(var i=0;i<60;i++){document.dispatchEvent(new KeyboardEvent("keydown",{keyCode:40,bubbles:true,repeat:i>0}));} return Math.round(performance.now()-t)+" ms"})()', '60x omlaag in Live'),
            ('key', 'ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown', 50),
            ('wait', 2500), ('shot', '46-perf-live'),
            ('eval', 'JSON.stringify({lt: window.__lt, rows: document.querySelectorAll(".lv-vlist .lv-chan").length})', 'lange taken Live'),
            ('eval', 'B.ui.top().show("movies", true) || 1'), ('wait', 1500), ('eval', LT),
            ('key', 'ArrowRight ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowRight ArrowRight', 60),
            ('wait', 1200), ('shot', '47-perf-movies'),
            ('eval', 'JSON.stringify({lt: window.__lt, cards: document.querySelectorAll(".vgrid .card").length, all: B.lib.lib.movies.length})', 'lange taken Films'),
        ])

    if which in ('all', 'nav'):
        # alleen met toetsen: elk menu-item openen, in de inhoud rondlopen en met Terug (10009) terug naar het menu.
        # Na elke toets moet de focus op iets zichtbaars in de bovenste laag staan.
        CONTENT = 'ArrowDown ArrowDown ArrowRight ArrowRight ArrowDown ArrowLeft ArrowUp ArrowUp ArrowUp ArrowRight ArrowDown'
        steps = [('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
                 ('walk', 'ArrowDown ArrowDown ArrowRight ArrowRight ArrowRight ArrowDown ArrowDown ArrowDown ArrowDown ArrowUp ArrowUp ArrowLeft', 'home: rondlopen'),
                 ('walk', 'ArrowLeft ArrowLeft ArrowLeft ArrowLeft ArrowLeft ArrowLeft', 'home: naar het menu'),
                 ('expect', 'B.ui.focused.classList.contains("nav-item")', 'menu bereikt met links'),
                 ('walk', 'ArrowUp Enter', 'menu: zoeken'), ('walk', CONTENT, 'zoeken: rondlopen'), ('walk', '10009', 'zoeken: terug'),
                 ('expect', 'B.ui.focused.classList.contains("nav-item")', 'terug uit zoeken naar het menu')]
        for name in ('home', 'live', 'movies', 'series', 'mylist', 'settings'):
            steps += [('walk', 'ArrowDown Enter', 'menu: ' + name), ('wait', 700), ('walk', CONTENT, name + ': rondlopen'),
                      ('backto', 'B.ui.focused.classList.contains("nav-item")', 3, 'terug uit ' + name + ' naar het menu (max. 3x)')]
        steps += [('walk', '10009', 'menu: terug naar home'), ('expect', 'B.ui.top().page() && B.ui.top().page().el.classList.contains("home")', 'terug = home'),
                  ('walk', 'ArrowLeft 10009', 'home-menu: terug (melding)'), ('tv', '10009'), ('expect', 'window.__exited === true', 'twee keer terug sluit de app')]
        run(SIGNED_IN, 'normal', steps)
    if which in ('all', 'dialogs'):
        # elk soort scherm en dialoog: in rondlopen en met Terug (10009) weer weg, zonder doodlopende focus
        MAINTOP = 'B.ui.top().el.classList.contains("main")'
        run(SIGNED_IN + AVSTUB, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 3000),
            # detailpagina van een serie
            ('eval', 'B.ui.push(B.screens.detail("series", B.lib.lib.byId["xs1"].item)) && 1'), ('wait', 1500),
            ('walk', 'ArrowDown ArrowDown ArrowRight ArrowRight ArrowDown ArrowUp ArrowUp ArrowUp ArrowLeft ArrowLeft', 'detail: rondlopen'),
            ('walk', '10009', 'detail: terug'), ('expect', MAINTOP, 'detail dicht'),
            # film met voortgang: hervatten-vraag
            ('eval', 'B.ui.push(B.screens.detail("movie", B.lib.lib.byId["xv3"].item)) && 1'), ('wait', 1200),
            ('walk', 'Enter', 'detail film: afspelen'), ('expect', 'B.ui.top().modal === true', 'hervatten-vraag open'),
            ('walk', 'ArrowRight ArrowLeft ArrowDown', 'hervatten-vraag: rondlopen'), ('walk', '10009', 'hervatten-vraag: terug'),
            ('expect', '!B.player.current && B.ui.top().el.classList.contains("detail")', 'hervatten-vraag dicht, terug op detail'),
            ('walk', '10009', 'detail film: terug'),
            # keuzelijst (taal) in Instellingen
            ('eval', 'B.ui.top().show("settings", true) || 1'), ('wait', 600),
            ('eval', 'var r = Array.prototype.filter.call(document.querySelectorAll(".st-row"), function(r){return r.textContent.indexOf("Taal") === 0})[0]; B.ui.focus(r); 1'),
            ('walk', 'Enter', 'instellingen: taal kiezen'), ('expect', 'B.ui.top().modal === true', 'keuzelijst open'),
            ('walk', 'ArrowDown ArrowDown ArrowUp', 'keuzelijst: rondlopen'), ('walk', '10009', 'keuzelijst: terug'), ('expect', MAINTOP, 'keuzelijst dicht'),
            # servers → bewerken → toetsenbord
            ('eval', 'B.ui.push(B.screens.servers()) && 1'), ('wait', 1000),
            ('walk', 'ArrowDown ArrowDown ArrowUp', 'servers: rondlopen'),
            ('eval', 'B.ui.push(B.screens.serverEdit(B.data.sources[0])) && 1'), ('wait', 500),
            ('walk', 'ArrowDown ArrowDown ArrowUp Enter', 'server bewerken: veld openen'), ('expect', 'B.ui.top().el.classList.contains("edit-text")', 'toetsenbord open'),
            ('walk', 'ArrowDown ArrowRight ArrowRight ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowLeft ArrowUp', 'toetsenbord: rondlopen'),
            ('walk', '10009', 'toetsenbord: terug'), ('expect', '!B.ui.top().el.classList.contains("edit-text")', 'toetsenbord dicht'),
            ('walk', '10009 10009', 'bewerken + servers: terug'), ('expect', MAINTOP, 'terug op het hoofdscherm'),
            # apparaten: hernoemen en terug
            ('eval', 'B.ui.push(B.screens.devices()) && 1'), ('wait', 1200),
            ('expect', 'document.querySelector(".listscreen").textContent.indexOf("Samsung TV") >= 0 && document.querySelector(".listscreen").textContent.indexOf("tizen") < 0', 'platform als Samsung TV'),
            ('expect', 'document.querySelector(".listscreen").textContent.indexOf("Gekoppeld") >= 0', 'Gekoppeld-datum'),
            ('expect', 'document.querySelector(".listscreen").textContent.indexOf("Chrome op Mac") >= 0 && document.querySelector(".listscreen").textContent.indexOf("browser") < 0 && !!document.querySelector(".listscreen .ic path[d^=\\"M3 12h18\\"]")', 'webspeler: Browser met wereldbol'),
            ('walk', 'ArrowDown ArrowUp Enter', 'apparaten: menu'), ('walk', 'Enter', 'apparaten: hernoemen'),
            ('expect', 'B.ui.top().el.classList.contains("edit-text")', 'naam-toetsenbord open'),
            ('tv', '65376'), ('wait', 1500),
            ('walk', 'ArrowDown', 'apparaten na hernoemen'), ('walk', '10009', 'apparaten: terug'), ('expect', MAINTOP, 'apparaten dicht'),
            ('eval', 'B.ui.push(B.screens.streams()) && 1'), ('wait', 1000), ('walk', 'ArrowDown 10009', 'nu kijken: terug'), ('expect', MAINTOP, 'nu kijken dicht'),
            ('eval', 'B.ui.push(B.screens.watchLog()) && 1'), ('wait', 1000), ('walk', 'ArrowDown 10009', 'kijkgeschiedenis: terug'), ('expect', MAINTOP, 'kijkgeschiedenis dicht'),
            ('eval', 'B.ui.push(B.screens.remindersList()) && 1'), ('wait', 500), ('walk', 'ArrowDown 10009', 'herinneringen: terug'), ('expect', MAINTOP, 'herinneringen dicht'),
            ('eval', 'B.screens.openSubtitlesMenu(function(){}) || 1'), ('wait', 400), ('walk', 'ArrowDown ArrowDown 10009', 'OpenSubtitles: terug'), ('expect', MAINTOP, 'OpenSubtitles dicht'),
            ('eval', 'B.screens.traktMenu(function(){}) || 1'), ('wait', 600), ('walk', 'ArrowDown 10009', 'Trakt: terug'), ('expect', MAINTOP, 'Trakt dicht'),
            # gids → programma → terug
            ('eval', 'B.ui.push(B.screens.guide(B.lib.lib.channels, 1)) && 1'), ('wait', 600),
            ('walk', 'ArrowRight ArrowDown ArrowLeft ArrowLeft ArrowUp ArrowDown', 'gids: rondlopen'), ('walk', 'Enter', 'gids: programma'),
            ('walk', 'ArrowDown ArrowUp 10009', 'programma: terug'), ('walk', '10009', 'gids: terug'), ('expect', MAINTOP, 'gids dicht'),
            # speler: film (overlay, ondertitelkeuze, terug)
            ('eval', 'B.player.playVOD(B.player.movieItem(B.lib.lib.byId["xv5"].item), {resume: 0}) || 1'), ('wait', 2500),
            ('walk', 'Enter ArrowRight ArrowRight ArrowRight', 'speler: knoppen'), ('walk', 'Enter', 'speler: ondertitels kiezen'),
            ('expect', 'B.ui.top().modal === true && !!B.player.current', 'ondertitelkeuze open boven de speler'),
            ('walk', 'ArrowDown 10009', 'ondertitelkeuze: terug'), ('expect', 'B.ui.top().isPlayer === true', 'terug in de speler'),
            ('walk', '10009', 'speler: overlay weg'), ('walk', '10009', 'speler: dicht'),
            ('expect', '!B.player.current && ' + MAINTOP, 'speler dicht met terug'),
            # speler: live zappen en terug
            ('eval', 'B.player.playChannel(B.lib.lib.channels[0], B.lib.lib.channels) && 1'), ('wait', 2000),
            ('walk', '10009 ArrowDown', 'live: zappen'), ('wait', 1500),
            ('backto', '!B.player.current && ' + MAINTOP, 2, 'live: dicht met terug (overlay, dan speler)'),
            # multiview
            ('eval', 'B.multiview.open(B.lib.lib.channels, 0) && 1'), ('wait', 1500),
            ('walk', 'ArrowRight ArrowDown ArrowLeft ArrowUp ArrowRight', 'multiview: rondlopen'), ('walk', '10009', 'multiview: terug'),
            ('expect', '!B.multiview.current && ' + MAINTOP, 'multiview dicht'),
            ('expect', '!document.documentElement.classList.contains("playing") && !document.documentElement.classList.contains("mv-hole")', 'geen speler-klassen meer'),
            ('eval', 'window.__av.filter(function (x) { return x === "open"; }).length + " open / " + window.__av.filter(function (x) { return x === "close"; }).length + " close"', 'AVPlay open/close'),
        ])
        print(open(os.path.join(OUT, 'requests.jsonl')).read().count('rename_device'), 'x rename_device')
    if which in ('all', 'background'):
        # app naar de achtergrond (Tizen: AVPlay vrijgeven), en terug: opnieuw openen op dezelfde plek
        HIDE = 'Object.defineProperty(document, "hidden", {configurable: true, get: function () { return true; }}); document.dispatchEvent(new Event("visibilitychange")); 1'
        SHOW = 'Object.defineProperty(document, "hidden", {configurable: true, get: function () { return false; }}); document.dispatchEvent(new Event("visibilitychange")); 1'
        run(SIGNED_IN + AVSTUB, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 3000),
            ('eval', 'B.player.playVOD(B.player.movieItem(B.lib.lib.byId["xv5"].item), {resume: 0}) || 1'), ('wait', 2500),
            ('eval', 'webapis.avplay.seekTo(600000); window.__av = []; 1'), ('wait', 300),
            ('eval', HIDE), ('wait', 400),
            ('expect', 'window.__av.indexOf("close") >= 0 && webapis.avplay.getState() === "NONE"', 'achtergrond: AVPlay gesloten'),
            ('expect', '!!B.player.current', 'speler blijft open'),
            ('wait', 1500), ('expect', 'webapis.avplay.getState() === "NONE"', 'achtergrond: blijft dicht'),
            ('eval', 'window.__av = []; 1'), ('eval', SHOW), ('wait', 2000),
            ('eval', 'JSON.stringify(window.__av)', 'av na terugkomen'),
            ('expect', 'window.__av.indexOf("open") >= 0 && window.__av.some(function (x) { return /^seek:60\\d{4}$/.test(x); }) && webapis.avplay.getState() === "PLAYING"', 'voorgrond: opnieuw open op dezelfde plek'),
            # gepauzeerd naar de achtergrond: na terugkomen weer gepauzeerd
            ('tv', '10252'), ('wait', 300), ('eval', HIDE), ('wait', 300), ('eval', SHOW), ('wait', 2000),
            ('expect', 'webapis.avplay.getState() === "PAUSED"', 'gepauzeerd blijft gepauzeerd'),
            ('eval', 'B.player.current.close() || 1'), ('wait', 400),
            # live
            ('eval', 'B.player.playChannel(B.lib.lib.channels[0], B.lib.lib.channels) && 1'), ('wait', 2000),
            ('eval', HIDE), ('wait', 300), ('expect', 'webapis.avplay.getState() === "NONE"', 'live: achtergrond dicht'),
            ('eval', SHOW), ('wait', 2000), ('expect', 'webapis.avplay.getState() === "PLAYING"', 'live: weer aan'),
            ('eval', 'B.player.current.close() || 1'), ('wait', 400),
            # multiview
            ('eval', 'B.multiview.open(B.lib.lib.channels, 0) && 1'), ('wait', 2000),
            ('eval', HIDE), ('wait', 300), ('expect', 'webapis.avplay.getState() === "NONE" && !document.querySelector(".mv-tile.playing")', 'multiview: achtergrond alles dicht'),
            ('eval', SHOW), ('wait', 2500), ('expect', 'webapis.avplay.getState() === "PLAYING"', 'multiview: weer aan'),
            ('eval', 'B.multiview.current.close() || 1'), ('wait', 400),
        ])
        reqs = open(os.path.join(OUT, 'requests.jsonl')).read() if os.path.exists(os.path.join(OUT, 'requests.jsonl')) else ''
        RESULTS.append(('stream_stop bij achtergrond', reqs.count('"stream_stop"') >= 4, str(reqs.count('"stream_stop"'))))
    if which in ('all', 'leak'):
        # profiel wisselen mag geen luisteraars (en dus oude schermen) laten liggen
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
            ('eval', 'window.__l0 = ["epg", "library", "profileData", "profiles", "connections"].map(function (e) { return B.listenerCount(e); }).join(","); window.__l0', 'luisteraars'),
            ('eval', 'B.app.switchProfile(); 1'), ('wait', 600), ('key', 'Enter', 400), ('wait', 1500),
            ('eval', 'B.app.switchProfile(); 1'), ('wait', 600), ('key', 'Enter', 400), ('wait', 1500),
            ('eval', 'B.app.switchProfile(); 1'), ('wait', 600), ('key', 'Enter', 400), ('wait', 1500),
            ('expect', '["epg", "library", "profileData", "profiles", "connections"].map(function (e) { return B.listenerCount(e); }).join(",") === window.__l0', 'geen extra luisteraars na 3x wisselen'),
        ])
    if which in ('all', 'details'):
        # build 102: ronde avatars (Apple TV) en rijke detailpagina's: TMDB-beschrijving, genres/regisseur, Acteurs met
        # ronde foto's (OK = acteurspagina, Terug = zelfde acteur), Aanbevelingen (bibliotheek = titel, anders Zoeken)
        ROUND = ('Array.prototype.every.call(document.querySelectorAll(".avatar"), function (a) { var cs = getComputedStyle(a); '
                 'return cs.borderTopLeftRadius === "50%" && cs.borderBottomRightRadius === "50%" && a.offsetWidth === a.offsetHeight; }) '
                 '&& document.querySelectorAll(".avatar").length > 0')
        CAST = 'document.querySelectorAll(".dt-cast .cast-card.f")'
        RECS = 'document.querySelectorAll(".dt-recs .row-track > .f")'
        fb.TMDB_LANGS.clear()
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('shot', 'd01-profiles-round'),
            ('expect', ROUND, 'Wie kijkt er?: alle avatars rond (borderRadius 50%)'),
            ('expect', 'getComputedStyle(document.querySelector(".prof .avatar")).borderRadius === "50%"', 'avatar: borderRadius 50%'),
            ('expect', '(function(){var f = B.ui.focused.querySelector(".avatar"); var cs = getComputedStyle(f); return cs.boxShadow.indexOf("6px") >= 0 && cs.transform !== "none";})()', 'focusring rond om de cirkel en groter'),
            ('key', 'Enter', 400), ('wait', 2500),
            ('expect', ROUND, 'menu: avatar rond'),
            ('eval', 'B.lang', 'taal'),
            # film: Iron Valley
            ('eval', 'window.__mv = B.lib.lib.movies.filter(function (m) { return m.title.indexOf("Iron Valley") === 0; })[0]; B.ui.push(B.screens.detail("movie", window.__mv)) && 1'),
            ('wait', 2500), ('shot', 'd02-movie-detail'),
            ('expect', 'document.querySelector(".dt-credits").textContent.indexOf("Sofia Lang") >= 0 && document.querySelector(".dt-credits").textContent.indexOf(B.L("Genres")) >= 0', 'film: genres en regisseur'),
            ('expect', 'document.querySelector(".dt-meta").textContent.indexOf(B.L("%d min", 112)) >= 0', 'film: speelduur uit de afspeellijst'),
            ('expect', 'B.lang === "en" || document.querySelector(".dt-plot").textContent === "TMDB-beschrijving in het Nederlands."', 'film: beschrijving in de app-taal (TMDB)'),
            ('expect', 'document.querySelector(".dt-cast .row-title").textContent === B.L("Actors") && %s.length === 10' % CAST, 'film: rij Acteurs met 10 focusbare kaarten'),
            ('expect', 'document.querySelector(".dt-recs .row-title").textContent === B.L("Recommendations") && %s.length === 6' % RECS, 'film: rij Aanbevelingen (6)'),
            ('expect', 'document.querySelectorAll(".dt-recs .card.rec-out").length === 3 && document.querySelectorAll(".dt-recs .card:not(.rec-out)").length === 3', 'film: 3 in de bibliotheek, 3 zoeken'),
            ('wait', 1500),
            ('expect', '%s[1].querySelector(".img.loaded") === null && %s[1].querySelector(".img-fallback").textContent === "TH"' % (CAST, CAST), 'foto mislukt: initialen'),
            ('expect', '!!%s[0].querySelector(".img.loaded") && getComputedStyle(%s[0].querySelector(".cast-pic")).borderTopLeftRadius === "50%%"' % (CAST, CAST), 'castfoto geladen en rond'),
            # build 102: tweede regel knoppen (Afvinken/Collectie) en de rij Andere bronnen (Stremio) staan ertussen
            ('walk', 'ArrowDown ArrowDown ArrowDown', 'film: omlaag naar Acteurs'),
            ('expect', 'B.ui.focused.classList.contains("cast-card") && B.ui.focused === %s[0]' % CAST, 'film: focus op de eerste acteur'),
            ('walk', 'ArrowRight ArrowRight', 'film: door de acteurs'), ('shot', 'd03-movie-actors'),
            ('expect', 'B.ui.focused === %s[2]' % CAST, 'film: derde acteur'),
            ('walk', 'Enter', 'acteur openen'), ('wait', 1800), ('shot', 'd04-person'),
            ('expect', 'B.ui.top().el.classList.contains("person") && document.querySelector(".ps-name").textContent === "Robin Vale"', 'acteurspagina open'),
            ('expect', 'getComputedStyle(document.querySelector(".ps-pic")).borderTopLeftRadius === "50%"', 'acteur: ronde foto'),
            ('expect', 'document.querySelector(".ps-facts").textContent.indexOf("1984") >= 0 && document.querySelector(".ps-facts").textContent.indexOf("Utrecht") >= 0', 'acteur: geboren'),
            ('expect', 'document.querySelectorAll(".ps-have .row-track > .f").length === 2 && document.querySelector(".ps-have .row-title").textContent === B.L("Available in Bingr")', 'acteur: Beschikbaar in Bingr (2)'),
            ('expect', 'document.querySelectorAll(".ps-rest .row-track > .f").length === 2', 'acteur: Niet in je afspeellijsten (2, zonder talkshow)'),
            ('expect', 'B.ui.focused === document.querySelector(".ps-have .f")', 'acteur: focus op de eerste titel'),
            ('walk', 'ArrowDown ArrowRight ArrowUp ArrowUp', 'acteur: rondlopen'),
            ('expect', 'B.ui.focused.classList.contains("ps-biobox")', 'acteur: biografie focusbaar'),
            ('walk', 'Enter', 'biografie uitklappen'), ('wait', 300),
            ('expect', 'B.ui.focused.classList.contains("open")', 'biografie open'),
            ('walk', 'ArrowDown', 'biografie scrollen'), ('wait', 300), ('shot', 'd05-person-bio'),
            ('expect', 'document.querySelector(".ps-bio-text").style.transform.indexOf("-160px") >= 0 && B.ui.focused.classList.contains("ps-biobox")', 'omlaag scrollt de tekst'),
            ('walk', 'ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown', 'uit de biografie naar de rijen'),
            ('expect', '!B.ui.focused.classList.contains("ps-biobox")', 'weer in de rijen'),
            ('walk', '10009', 'acteur: terug'),
            ('expect', 'B.ui.top().el.classList.contains("detail") && B.ui.focused === %s[2]' % CAST, 'terug: focus op dezelfde acteur'),
            ('walk', 'ArrowDown', 'film: omlaag naar Aanbevelingen'),
            ('expect', 'B.ui.focused.parentNode.parentNode.parentNode.classList.contains("dt-recs")', 'film: focus in Aanbevelingen'),
            ('walk', 'ArrowRight ArrowRight', 'film: door de aanbevelingen'), ('shot', 'd06-movie-recs'),
            # aanbeveling die niet in de bibliotheek staat: Zoeken met de titel; Terug = weer hier
            ('eval', 'B.ui.focus(document.querySelector(".dt-recs .rec-out")); 1'),
            ('walk', 'Enter', 'aanbeveling zoeken'), ('wait', 600), ('shot', 'd07-rec-search'),
            ('expect', 'B.ui.top().el.classList.contains("search-layer") && document.querySelector(".search-layer .search-query").textContent === "Starfall Express"', 'zoeken met de titel'),
            ('walk', '10009', 'zoeken: terug'),
            ('expect', 'B.ui.top().el.classList.contains("detail") && B.ui.focused === document.querySelector(".dt-recs .rec-out")', 'terug uit zoeken: zelfde kaart'),
            # aanbeveling in de bibliotheek: opent die titel
            ('eval', 'B.ui.focus(document.querySelector(".dt-recs .card:not(.rec-out)")); 1'),
            ('walk', 'Enter', 'aanbeveling openen'), ('wait', 1500),
            ('expect', 'B.ui.top().el.classList.contains("detail") && B.ui.top().el.querySelector(".dt-title").textContent.indexOf("Neon District") === 0', 'aanbeveling opent de titel'),
            ('walk', '10009', 'aanbeveling: terug'),
            ('walk', 'ArrowUp ArrowUp ArrowUp', 'film: terug naar de knoppen'),
            ('expect', 'B.ui.focused.classList.contains("btn") && document.querySelector(".dt-body")._pos === 0', 'knoppen: pagina weer bovenaan'),
            ('walk', '10009', 'film: terug'),
            # serie: Midnight Harbor
            ('eval', 'B.ui.push(B.screens.detail("series", B.lib.lib.series.filter(function (s) { return s.title === "Midnight Harbor"; })[0])) && 1'),
            ('wait', 2500), ('shot', 'd08-series-detail'),
            ('expect', 'document.querySelector(".dt-credits").textContent.indexOf("Drama, Thriller") >= 0 && document.querySelector(".dt-credits").textContent.indexOf(B.L("Director")) < 0', 'serie: genres, geen regisseur'),
            ('expect', '%s.length === 3 && %s.length === 3' % (CAST, RECS), 'serie: Acteurs (3) en Aanbevelingen (3)'),
            ('expect', 'document.querySelector(".dt-cast").textContent.indexOf("Kapitein Mara") >= 0', 'serie: rol uit aggregate_credits'),
            ('walk', 'ArrowDown ArrowDown ArrowDown', 'serie: knoppen → seizoenen → afleveringen → acteurs'),
            ('expect', 'B.ui.focused.classList.contains("cast-card") && B.ui.focused.parentNode.parentNode.parentNode.classList.contains("dt-cast")', 'serie: focus in de rij Acteurs'), ('shot', 'd09-series-actors'),
            ('walk', 'ArrowDown ArrowRight', 'serie: aanbevelingen'), ('shot', 'd10-series-recs'),
            ('walk', 'ArrowUp Enter', 'serie: acteur openen'), ('wait', 1500),
            ('expect', 'B.ui.top().el.classList.contains("person")', 'serie: acteurspagina'),
            ('walk', '10009', 'serie: terug van acteur'),
            ('expect', 'B.ui.focused.classList.contains("cast-card")', 'serie: focus terug op de acteur'),
            ('walk', 'ArrowUp ArrowUp ArrowUp 10009', 'serie: omhoog en terug'),
            ('expect', 'B.ui.top().el.classList.contains("main")', 'terug op het hoofdscherm'),
        ])
        valid = {'en-US', 'nl-NL', 'de-DE', 'fr-FR', 'es-ES', 'it-IT', 'pt-PT', 'pl-PL', 'tr-TR', 'ar-SA', 'zh-CN'}
        RESULTS.append(('TMDB-talen zijn geldige locales', bool(fb.TMDB_LANGS) and all(l in valid for l in fb.TMDB_LANGS), str(sorted(set(fb.TMDB_LANGS)))))
        # profiel met leeftijdsgrens (Kids): alleen aanbevelingen uit de eigen bibliotheek, geen "Niet in je afspeellijsten"
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'ArrowRight ArrowRight Enter', 400), ('wait', 3000),
            ('expect', 'B.app.profile && B.data.ageLimit(B.app.profile) != null', 'profiel met leeftijdsgrens'),
            ('eval', 'B.ui.push(B.screens.detail("movie", B.lib.lib.movies[0])) && 1'), ('wait', 2500), ('shot', 'd11-kids-detail'),
            ('expect', '%s.length > 0 && document.querySelectorAll(".rec-out").length === 0' % CAST, 'kids: acteurs, geen aanbevelingen buiten de bibliotheek'),
            ('eval', 'B.ui.focus(%s[0]); 1' % CAST), ('walk', 'Enter', 'kids: acteur'), ('wait', 1500), ('shot', 'd12-kids-person'),
            ('expect', 'B.ui.top().el.classList.contains("person") && !document.querySelector(".ps-rest") && document.querySelectorAll(".rec-out").length === 0', 'kids: geen "Niet in je afspeellijsten"'),
            ('walk', '10009', 'kids: terug'),
            ('expect', 'B.ui.focused === %s[0]' % CAST, 'kids: focus terug op de acteur'),
        ])
    if which in ('all', 'prefs'):
        # kijkvoorkeuren: oude waarden (bool, subtitleLanguage "off"), voorkeur per profiel, openstaande instellingen
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 3000),
            ('eval', 'B.data.settings.data.shared.autoSubtitles = true; 1'), ('expect', 'B.clang.autoSubtitles() === "always"', 'oude true = always'),
            ('eval', 'B.data.settings.data.shared.autoSubtitles = false; 1'), ('expect', 'B.clang.autoSubtitles() === "whenNeeded"', 'oude false = whenNeeded'),
            ('eval', 'delete B.data.settings.data.shared.autoSubtitles; B.data.settings.data.shared.subtitleLanguage = "off"; 1'),
            ('expect', 'B.clang.autoSubtitles() === "off"', 'oude subtitleLanguage off = uit'),
            ('eval', 'B.data.setProfilePref("autoSubtitles", "always"); B.data.setProfilePref("audioLanguage", "de"); 1'), ('wait', 4500),
            ('expect', 'B.clang.autoSubtitles() === "always" && B.clang.preferredAudio() === "de"', 'voorkeur van het profiel gaat voor'),
            ('eval', 'B.data.setShared("subtitleSize", "large").then(function () { return 1; })'),
            ('expect', '!localStorage.getItem("bingr.settingsPending") && B.data.shared("subtitleSize") === "large"', 'instelling verstuurd'),
            ('eval', 'B.data.syncState().then(function () { return 1; })'),
            ('expect', 'B.data.profilePref("audioLanguage") === "de" && B.data.shared("subtitleSize") === "large"', 'na sync nog steeds goed'),
        ])
        reqs = open(os.path.join(OUT, 'requests.jsonl')).read() if os.path.exists(os.path.join(OUT, 'requests.jsonl')) else ''
        prof = [json.loads(l) for l in reqs.splitlines() if '"put_state"' in l and '"section": "profile.' in l]
        RESULTS.append(('prefs in profile.<id>', bool(prof) and (prof[-1]['data'].get('prefs') or {}).get('audioLanguage') == 'de', str((prof[-1]['data'].get('prefs') if prof else None))))
    if which in ('all', 'email'):
        # e-mail: account maken (bevestigen), wachtwoord vergeten en terug naar het QR-scherm
        run('', 'normal', [
            ('wait', 2500), ('key', 'ArrowRight Enter', 500), ('expect', 'B.ui.top().el.classList.contains("email-login")', 'e-mailscherm open'),
            ('walk', 'ArrowRight ArrowRight ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowUp ArrowUp', 'e-mail: rondlopen'),
            ('eval', 'var b = Array.prototype.filter.call(document.querySelectorAll(".email-fields .btn"), function (x) { return x.textContent.indexOf(B.L("Sign up now.")) >= 0; })[0]; B.ui.focus(b); 1'),
            ('key', 'Enter', 300), ('expect', 'document.querySelector(".email-card h1").textContent === B.L("Create account")', 'wissel naar account maken'),
            # e-mail typen: a @ b . c d (eerst het e-mailveld kiezen; het toetsenbord typt in het laatst gekozen veld)
            ('eval', 'B.ui.focus(document.querySelectorAll(".email-fields .input")[0]); B.ui.focus(document.querySelector(".kb .key")); 1'),
            ('key', 'Enter ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown Enter ArrowUp ArrowUp ArrowUp ArrowUp ArrowUp ArrowUp ArrowRight Enter ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown Enter ArrowUp ArrowUp ArrowUp ArrowUp ArrowUp ArrowUp ArrowRight Enter ArrowRight Enter', 80),
            ('expect', 'document.querySelector(".email-fields .input").textContent === "a@b.cd"', 'e-mail getypt'),
            ('eval', 'B.ui.focus(document.querySelectorAll(".email-fields .input")[1]); 1'), ('wait', 100),
            ('eval', 'B.ui.focus(document.querySelector(".kb .key")); 1'), ('key', 'Enter Enter Enter Enter Enter Enter', 80),
            ('eval', 'B.ui.focus(document.querySelector(".email-fields > .btn.primary")); 1'), ('key', 'Enter', 1200), ('shot', '61-signup'),
            ('expect', 'document.querySelector(".email-login .login-error").textContent === B.L("Check your inbox to confirm your email address, then sign in.")', 'account gemaakt: bevestig je e-mail'),
            ('eval', 'var b = Array.prototype.filter.call(document.querySelectorAll(".email-links .btn"), function (x) { return x.textContent === B.L("Forgot password?"); })[0]; B.ui.focus(b); 1'),
            ('key', 'Enter', 1000), ('expect', 'document.querySelector(".email-login .login-error").textContent === B.L("If an account exists, you\'ll receive an email to reset your password.")', 'wachtwoord vergeten verstuurd'),
            ('walk', '10009', 'e-mail: terug'), ('expect', 'B.ui.top().el.classList.contains("login")', 'terug op het QR-scherm'),
        ])
        reqs = open(os.path.join(OUT, 'requests.jsonl')).read() if os.path.exists(os.path.join(OUT, 'requests.jsonl')) else ''
        RESULTS.append(('signup met taal + recover met redirect', '"lang": "en"' in reqs and 'redirect_to=https%3A%2F%2Fbingr.online%2Freset' in reqs, reqs[-300:]))
    if which in ('all', 'limitback'):
        # te veel apparaten: Terug = annuleren = uitloggen
        run(SIGNED_IN.replace("'true'", "'false'"), 'limit', [('wait', 2500), ('walk', 'ArrowDown ArrowUp', 'limiet: rondlopen'), ('tv', '10009', 1500),
                                                              ('expect', 'B.ui.top().el.classList.contains("login") && !B.account.isSignedIn()', 'terug = uitgelogd')])

    if which in ('all', 'stats'):
        # kijkstatistieken (build 68): hoofdprofiel met filter, grafiek met links/rechts, toplijsten, jaar, wissen en terug
        STATS = 'B.ui.top().el.classList.contains("stats")'
        FOCUS = 'var r = Array.prototype.filter.call(document.querySelectorAll(".stats .f"), function (x) { return %s; })[0]; B.ui.focus(r); !!r'
        run(SIGNED_IN + AVSTUB, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 3000),
            ('eval', 'B.ui.top().show("settings", true) || 1'), ('wait', 600),
            ('expect', 'Array.prototype.some.call(document.querySelectorAll(".st-row"), function (r) { return r.textContent === B.L("Statistics"); })', 'instellingen: rij Statistieken'),
            ('eval', 'B.ui.push(B.screens.stats()) && 1'), ('wait', 1500), ('shot', '70-stats-month'),
            ('expect', 'B.ui.focused.classList.contains("stx-seg") && B.ui.focused.classList.contains("active")', 'focus op de gekozen periode (maand)'),
            ('expect', 'document.querySelectorAll(".stats .stx-chip").length === 4', 'filter: alle profielen + 3 profielen'),
            ('expect', 'document.querySelectorAll(".stats .stx-kpi").length === 4 && document.querySelectorAll(".stats .stx-col").length >= 28', 'tegels en staven per dag'),
            ('expect', 'document.querySelector(".stats").textContent.indexOf(B.L("Per profile")) >= 0', 'per profiel bij alle profielen'),
            ('walk', 'ArrowDown ArrowDown ArrowDown ArrowUp ArrowRight ArrowLeft ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowUp', 'statistieken: rondlopen'),
            ('eval', FOCUS % 'x.classList.contains("stx-chart")'), ('wait', 200),
            ('eval', 'window.__t0 = document.querySelector(".stx-chart-title").textContent; window.__t0', 'gekozen dag'),
            ('walk', 'ArrowLeft ArrowLeft', 'grafiek: links'), ('shot', '71-stats-chart'),
            ('expect', 'B.ui.focused.classList.contains("stx-chart") && document.querySelector(".stx-chart-title").textContent !== window.__t0 && document.querySelectorAll(".stx-col.sel").length === 1', 'links/rechts kiest een staaf'),
            ('eval', FOCUS % 'x.textContent === B.L("Show more")'), ('walk', 'Enter', 'toplijst: meer'), ('wait', 300),
            ('expect', 'document.querySelectorAll(".stats .stx-top").length === 8 + 5 + 5', 'series uitgeklapt tot 8'),
            ('expect', 'B.ui.focused && B.ui.focused.textContent === B.L("Show less")', 'focus blijft op meer/minder'),
            ('expect', 'document.querySelectorAll(".stats .stx-poster.loaded").length > 0 || document.querySelectorAll(".stats .stx-poster").length > 0', 'posters'),
            ('shot', '72-stats-top'),
            ('eval', FOCUS % 'x._fk === "seg-year"'), ('walk', 'Enter', 'jaar'), ('wait', 900), ('shot', '73-stats-year'),
            ('expect', 'document.querySelectorAll(".stats .stx-col").length === 12 && B.ui.focused._fk === "seg-year"', 'jaar: 12 maanden, focus blijft'),
            ('eval', FOCUS % 'x._fk === "prev"'), ('walk', 'Enter', 'vorig jaar'), ('wait', 900),
            ('expect', 'document.querySelector(".stx-period").textContent === String(new Date().getFullYear() - 1)', 'vorig jaar'),
            ('eval', FOCUS % ('x._fk === "chip-' + fb.SAM + '"')), ('walk', 'Enter', 'filter: Sam'), ('wait', 900),
            ('expect', 'document.querySelector(".stats").textContent.indexOf(B.L("Per profile")) < 0 && document.querySelector(".stx-chip.active > span").textContent === "Sam"', 'Sam: geen verdeling per profiel'),
            ('eval', FOCUS % 'x._fk === "clear"'), ('walk', 'Enter', 'wissen: vraag'),
            ('expect', 'B.ui.top().modal === true && document.querySelector(".modal-text").textContent.indexOf("Sam") >= 0', 'bevestigen voor Sam'),
            ('walk', '10009', 'wissen: annuleren'), ('expect', STATS, 'terug in statistieken'),
            ('walk', '10009', 'statistieken: terug'), ('expect', 'B.ui.top().el.classList.contains("main")', 'statistieken dicht'),
            # afspelen: aflevering (series_id, tz) en terugkijken (catchup, id van de zender)
            ('eval', 'B.lib.episodes(B.lib.lib.byId["xs1"].item).then(function (r) { window.__ep = r.episodes[0]; return r.episodes.length; })'), ('wait', 600),
            ('eval', 'B.player.playVOD(B.player.episodeItem(window.__ep, B.lib.lib.byId["xs1"].item), {resume: 0}) || 1'), ('wait', 2000),
            ('eval', 'B.player.current.close() || 1'), ('wait', 400),
            ('eval', 'var ch = B.lib.lib.channels[0]; B.player.playCatchup(ch, {title: "Journaal", start: Date.now() - 7200000, end: Date.now() - 5400000}, B.lib.lib.channels) && 1'), ('wait', 1500),
            ('eval', 'B.player.current && B.player.current.close() || 1'), ('wait', 400),
        ])
        reqs = [json.loads(l) for l in open(os.path.join(OUT, 'requests.jsonl')).read().splitlines() if l.strip()] if os.path.exists(os.path.join(OUT, 'requests.jsonl')) else []
        ws = [r for r in reqs if r.get('action') == 'watch_stats']
        RESULTS.append(('watch_stats met tz, periode en offset', bool(ws) and all(r.get('tz') for r in ws) and any(r.get('period') == 'year' and r.get('offset') == -1 for r in ws), str(ws[-1] if ws else None)))
        RESULTS.append(('watch_stats: alle profielen zonder profile_id, daarna Sam', any('profile_id' not in r for r in ws) and ws[-1].get('profile_id') == fb.SAM, str([r.get('profile_id') for r in ws])))
        starts = [r for r in reqs if r.get('action') == 'stream_start']
        ep = [r for r in starts if r.get('kind') == 'episode']
        RESULTS.append(('stream_start aflevering: series_id, series_title, tz', bool(ep) and ep[0].get('series_id') == 'xs1' and bool(ep[0].get('series_title')) and bool(ep[0].get('tz')), str(ep[:1])))
        cu = [r for r in starts if r.get('kind') == 'catchup']
        RESULTS.append(('stream_start terugkijken: kind catchup, id van de zender', bool(cu) and '-cu-' not in cu[0].get('content_id', '-cu-'), str(cu[:1])))
        # ander profiel (Kids, zonder pincode): alleen eigen statistieken, geen filter
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'ArrowRight ArrowRight Enter', 400), ('wait', 3000),
            ('expect', 'B.app.profile && B.app.profile.name === "Kids"', 'profiel Kids'),
            ('eval', 'B.ui.push(B.screens.stats()) && 1'), ('wait', 1500), ('shot', '74-stats-kids'),
            ('expect', 'document.querySelectorAll(".stats .stx-chip").length === 0 && document.querySelector(".stats").textContent.indexOf(B.L("Per profile")) < 0', 'Kids: geen filter en geen verdeling'),
            ('eval', FOCUS % 'x._fk === "clear"'), ('walk', 'Enter', 'Kids: wissen'), ('walk', 'Enter', 'Kids: bevestigen'), ('wait', 1200),
            ('expect', 'document.querySelector(".stats .state") && document.querySelector(".stats .state").textContent.indexOf("2026") >= 0', 'na wissen: lege staat'),
            ('walk', '10009', 'Kids: terug'),
        ])
        reqs = [json.loads(l) for l in open(os.path.join(OUT, 'requests.jsonl')).read().splitlines() if l.strip()] if os.path.exists(os.path.join(OUT, 'requests.jsonl')) else []
        ws = [r for r in reqs if r.get('action') == 'watch_stats']
        dl = [r for r in reqs if r.get('action') == 'delete_watch_stats']
        RESULTS.append(('Kids: watch_stats en wissen alleen met eigen profile_id', bool(ws) and all(r.get('profile_id') == fb.KIDS for r in ws) and bool(dl) and dl[0].get('profile_id') == fb.KIDS, str(ws[:1]) + str(dl)))
        # oude backend en nog niets gekeken
        run(SIGNED_IN, 'oldstats', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
            ('eval', 'B.ui.push(B.screens.stats()) && 1'), ('wait', 1200), ('shot', '75-stats-old-backend'),
            ('expect', 'document.querySelector(".stats").textContent.indexOf(B.L("Statistics are not available yet. Please try again later.")) >= 0', 'oude backend: nog niet beschikbaar'),
            ('walk', 'ArrowDown ArrowRight 10009', 'oude backend: terug'),
        ])
        run(SIGNED_IN, 'nostats', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 2500),
            ('eval', 'B.ui.push(B.screens.stats()) && 1'), ('wait', 1200), ('shot', '76-stats-empty'),
            ('expect', 'document.querySelector(".stats .state").textContent.indexOf("2026") >= 0', 'lege staat met datum'),
            ('walk', 'ArrowDown ArrowDown ArrowDown 10009', 'leeg: terug'),
        ])

    if which in ('all', 'playeropts'):
        # build 102: Opties in de speler (snelheid, ondertitelgrootte, beeld, slaaptimer), ondertiteltiming van sporen uit de
        # stream, live pauzeren/terugspoelen/terug naar live (zender met terugkijken) en de nieuwe instellingen (gesynchroniseerd)
        PICK = 'var c = Array.prototype.filter.call(B.ui.top().el.querySelectorAll(".choice"), function (x) { return x.textContent.indexOf(%s) >= 0; })[0]; if (c) B.ui.focus(c, true); !!c'
        BTN = 'var b = Array.prototype.filter.call(document.querySelectorAll(".pl-buttons .btn"), function (x) { return %s; })[0]; if (b) B.ui.focus(b, true); !!b'
        ROW = 'var r = Array.prototype.filter.call(document.querySelectorAll(".st-row.f"), function (x) { return x.firstChild.textContent === %s; })[0]; if (r) B.ui.focus(r, true); !!r'
        def T(k, *a):
            return 'B.L(%s)' % ', '.join([json.dumps(k)] + [json.dumps(x) for x in a])
        def pick(expr, name):
            return [('expect', PICK % expr, 'kies: ' + name), ('key', 'Enter', 400)]
        OPTS = [('expect', BTN % ('x.textContent === ' + T('Options')), 'knop Opties'), ('key', 'Enter', 400),
                ('expect', 'B.ui.top().modal === true && B.ui.top().el.querySelector("h2").textContent === ' + T('Options'), 'Opties-paneel open')]
        steps = [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 3000),
            ('eval', 'B.player.playVOD(B.player.movieItem(B.lib.lib.byId["xv5"].item), {resume: 0}) || 1'), ('wait', 2500),
            ('eval', 'window.__av = []; 1'),
        ] + OPTS + [('shot', '80-player-options')] + pick(T('Speed'), 'snelheid') + pick('"1.5"', '1.5×') + [
            ('expect', 'window.__av.indexOf("speed:1.5") >= 0 && B.player.current.state().rate === 1.5', 'setSpeed(1.5)'),
            ('expect', 'B.ui.top().modal === true && B.ui.focused.textContent.indexOf(' + T('Speed') + ') >= 0', 'terug in Opties op Snelheid'),
        ] + pick(T('Subtitle size'), 'ondertitelgrootte') + pick(T('Large'), 'groot') + [
            ('expect', 'B.data.shared("subtitleSize") === "large" && document.querySelector(".pl-sub").style.fontSize === "58px"', 'ondertitels groot (gedeeld)'),
        ] + pick(T('Picture'), 'beeld') + pick('"4:3"', '4:3') + [
            ('expect', 'window.__av.indexOf("method:PLAYER_DISPLAY_MODE_FULL_SCREEN") >= 0 && window.__av.indexOf("rect:240:1440") >= 0', '4:3: setDisplayRect + setDisplayMethod'),
        ] + pick(T('Picture'), 'beeld') + pick(T('Fill'), 'vullen') + [
            ('expect', 'window.__av.indexOf("method:PLAYER_DISPLAY_MODE_CROPPED_FULL") >= 0 && B.player.current.state().aspect === "fill"', 'vullen: CROPPED_FULL'),
        ] + pick(T('Sleep timer'), 'slaaptimer') + pick(T('In %d minutes', 15), '15 min') + [
            ('expect', 'B.player.current.state().sleepAt > Date.now() + 14 * 60000', 'slaaptimer 15 min'),
            ('shot', '81-player-options-set'),
            ('key', 'Escape', 300),
            ('eval', 'B.player.current.sleepIn(31) || 1'), ('wait', 2200),
            ('expect', 'document.querySelector(".toast") && document.querySelector(".toast").textContent === ' + T('Sleep timer: playback stops in 30 seconds.'), 'slaaptimer: melding 30 s vooraf'),
            ('eval', 'window.__av = []; B.player.current.sleepIn(1) || 1'), ('wait', 2500),
            ('expect', '!B.player.current && window.__av.indexOf("pause") >= 0 && window.__av.indexOf("close") >= 0', 'slaaptimer: gepauzeerd en gesloten'),
            # ondertiteltiming van een spoor uit de stream: alleen later; tekst komt met vertraging
            ('eval', 'B.player.playVOD(B.player.movieItem(B.lib.lib.byId["xv5"].item), {resume: 0}) || 1'), ('wait', 2500),
            ('expect', 'B.player.current.state().rate === 1 || B.player.current.state().rate === 1.5', 'opties blijven in de sessie of zijn nieuw'),
            ('expect', BTN % ('x.textContent === ' + T('Subtitles')), 'knop Ondertitels'), ('key', 'Enter', 400),
        ] + pick('B.clang.name("eng")', 'Engels spoor') + [
            ('expect', BTN % ('x.textContent === ' + T('Subtitles')), 'knop Ondertitels'), ('key', 'Enter', 400),
        ] + pick(T('Subtitle timing'), 'ondertiteltiming') + [
            ('expect', '!Array.prototype.some.call(B.ui.top().el.querySelectorAll(".choice"), function (x) { return x.textContent.indexOf("−") >= 0; })', 'AVPlay: alleen later (geen −)'),
            ('shot', '82-subtitle-timing'),
        ] + pick('"+2 s"', '+2 s') + [
            ('expect', 'B.player.current.state().subDelay === 2', 'vertraging 2 s'),
            ('eval', 'window.__avListener.onsubtitlechange(1500, "Hallo daar"); 1'), ('wait', 400),
            ('expect', 'document.querySelector(".pl-sub").textContent === ""', 'tekst nog niet getoond'),
            ('wait', 2400),
            ('expect', 'document.querySelector(".pl-sub").textContent === "Hallo daar"', 'tekst na 2 s getoond'),
            ('eval', 'B.player.current.close() || 1'), ('wait', 500),
            # live met terugkijken: pauzeren, −10, terug naar live
            ('eval', 'B.player.playChannel(B.lib.lib.channels[0], B.lib.lib.channels) && 1'), ('wait', 2500),
            ('expect', 'B.player.current.state().canTimeshift === true', 'zender met terugkijken: timeshift'),
            ('expect', 'document.querySelector(".pl-golive") && document.querySelector(".pl-golive").textContent === ' + T('LIVE'), 'LIVE-knop'),
            ('expect', BTN % ('x.textContent === ' + T('Options')), 'live: knop Opties'), ('key', 'Enter', 400),
            ('expect', '!Array.prototype.some.call(B.ui.top().el.querySelectorAll(".choice"), function (x) { return x.textContent.indexOf(' + T('Speed') + ') >= 0; })', 'live: geen snelheid'),
            ('key', 'Escape', 300),
            ('tv', '10252'), ('wait', 300),
            ('expect', 'webapis.avplay.getState() === "PAUSED"', 'live gepauzeerd'),
            ('eval', 'window.__av = []; 1'),
            ('tv', '412'), ('wait', 2000),
            ('expect', 'window.__avUrl.indexOf("/timeshift/") >= 0 && B.player.current.state().shiftStart != null && B.player.current.state().behind >= 9', 'terugspoelen: terugkijkstream'),
            ('expect', 'document.querySelector(".pl-golive").textContent.indexOf("−") === 0 && document.querySelector(".pl-golive").textContent.indexOf(' + T('Back to live') + ') > 0', 'label −mm:ss · Terug naar live'),
            ('shot', '83-live-timeshift'),
            ('tv', '412'), ('wait', 1800),
            ('expect', 'B.player.current.state().behind >= 19', 'nog eens −10'),
            ('eval', 'B.ui.focus(document.querySelector(".pl-golive"), true); 1'), ('key', 'Enter', 400), ('wait', 1500),
            ('expect', 'window.__avUrl.indexOf("/timeshift/") < 0 && B.player.current.state().shiftStart == null && B.player.current.state().behind === 0', 'terug naar live'),
            ('expect', 'document.querySelector(".pl-golive").textContent === ' + T('LIVE'), 'weer LIVE'),
            ('eval', 'B.ui.focus(document.querySelector(".pl-buttons .btn")); 1'),
            ('key', 'ArrowRight ArrowRight ArrowRight ArrowRight ArrowRight ArrowRight ArrowRight ArrowRight ArrowRight ArrowRight', 120),
            ('expect', 'var bs = document.querySelectorAll(".pl-buttons .btn"), l = bs[bs.length - 1]; B.ui.focused === l && l.getBoundingClientRect().right <= 1920 && l.getBoundingClientRect().left >= 0', 'laatste knop in beeld (rij schuift mee)'),
            ('shot', '83b-live-buttons-scrolled'),
            ('eval', 'B.player.current.close() || 1'), ('wait', 500),
            # instellingen: ondertitelgrootte, startscherm, tabbladen, intromuziek, playlists verversen
            ('eval', 'B.ui.top().show("settings", true) || 1'), ('wait', 600),
            ('expect', ROW % T('Subtitle size'), 'instelling ondertitelgrootte'), ('key', 'Enter', 400),
        ] + pick(T('Small'), 'klein') + [
            ('expect', ROW % T('Show Movies'), 'instelling Films tonen'), ('key', 'Enter', 600),
            ('expect', 'JSON.stringify(B.data.shared("hiddenTabs")) === \'["movies"]\' && !Array.prototype.some.call(document.querySelectorAll(".nav-item"), function (x) { return x.textContent === ' + T('Movies') + '; })', 'Films verborgen in het menu'),
            ('expect', 'B.ui.focused && B.ui.focused.firstChild.textContent === ' + T('Show Movies'), 'focus blijft op Films tonen'),
            ('expect', ROW % T('Start screen'), 'instelling startscherm'), ('key', 'Enter', 400),
            ('expect', '!Array.prototype.some.call(B.ui.top().el.querySelectorAll(".choice"), function (x) { return x.textContent === ' + T('Movies') + '; })', 'startscherm: geen verborgen tab'),
        ] + pick(T('Live TV'), 'Live TV') + [
            ('expect', 'B.app.startPage() === "live"', 'startscherm live'),
            ('expect', ROW % T('Intro sound'), 'instelling intromuziek'), ('key', 'Enter', 400),
            ('expect', 'B.data.shared("introMusic") === false', 'intromuziek uit'),
            ('expect', ROW % T('Update playlists'), 'instelling verversen'), ('key', 'Enter', 400),
        ] + pick(T('Every 6 hours'), 'elke 6 uur') + [
            ('expect', 'B.data.shared("refreshInterval") === "sixHours" && B.lib.refreshDue(7 * 3600000) && !B.lib.refreshDue(3600000)', 'verversen elke 6 uur'),
            ('shot', '84-settings-interface'),
            ('wait', 1500),
        ]
        run(SIGNED_IN + AVSTUB, 'normal', steps)
        reqs = [json.loads(l) for l in open(os.path.join(OUT, 'requests.jsonl')).read().splitlines() if l.strip()] if os.path.exists(os.path.join(OUT, 'requests.jsonl')) else []
        sets = [r for r in reqs if r.get('action') == 'put_state' and r.get('section') == 'settings']
        last = (sets[-1].get('data') or {}).get('shared', {}) if sets else {}
        want = {'subtitleSize': 'small', 'hiddenTabs': ['movies'], 'startScreen': 'live', 'introMusic': False, 'refreshInterval': 'sixHours'}
        RESULTS.append(('put_state settings: gedeelde sleutels', all(last.get(k) == v for k, v in want.items()), str({k: last.get(k) for k in want})))

    if which in ('all', 'parity'):
        # build 102: gelijk met de Apple TV — contextmenu (lang OK / Tools), bekeken, collecties, detailpagina (trailer,
        # beoordelingen, voortgang, Andere bronnen), Voor jou, Top 10 uit trending, Bekijk allemaal met sorteren,
        # zoeken (personen, tv-gids, categorieën, andere afspeellijsten), Live TV (Evenementen, zoeken)
        fb.MODE['parity'] = True
        MID = '(function (t) { return B.lib.lib.movies.filter(function (m) { return m.title === t; })[0].id; })'
        FOC = 'B.ui.focus(Array.prototype.filter.call(document.querySelectorAll(".f"), function (x) { return %s; })[0]) || 1'
        PICK = 'B.ui.focus(Array.prototype.filter.call(document.querySelectorAll(".ctx-layer .pm-item"), function (x) { return x._mk === "%s"; })[0]) || 1'
        CTX = '!!document.querySelector(".ctx-layer") && B.ui.top().el.classList.contains("ctx-layer")'
        TOPDETAIL = 'B.ui.top().el.classList.contains("detail") && B.ui.top().el.querySelector(".dt-title").textContent.indexOf("%s") === 0'
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'Enter', 400), ('wait', 7000), ('shot', 'p01-home'),
            ('expect', '!!document.querySelector(".hero-meta .imdb-badge") && document.querySelector(".hero-meta").textContent.indexOf("7.8") >= 0', 'billboard: IMDb-badge'),
            ('expect', 'document.querySelector(".hero-meta").textContent.indexOf("min") >= 0 && document.querySelector(".hero-meta .hero-tags").textContent.indexOf(" • ") >= 0', 'billboard: genre en speelduur'),
            ('expect', '!!document.querySelector(".row-block.foryou") && document.querySelector(".row-block.foryou .row-title").textContent === B.L("For you") && document.querySelectorAll(".row-block.foryou .card.poster:not(.see-all)").length >= 3', 'Home: rij Voor jou'),
            ('expect', 'Array.prototype.some.call(document.querySelectorAll(".row-block.foryou .card.poster"), function (c) { return c._key === "movie:" + %s("Desert Wind"); })' % MID, 'Voor jou: Trakt-aanbeveling uit je bibliotheek'),
            ('expect', 'B.lib.lib.byId[document.querySelectorAll(".top10row")[1].querySelector(".card.top10")._key.split(":")[1]].item.title === "Paper Moons"', 'Top 10 films: trending (TMDB) als eerste'),
            ('expect', 'B.lib.lib.byId[document.querySelectorAll(".top10row")[0].querySelector(".card.top10")._key.split(":")[1]].item.title === "Golden Fields"', 'Top 10 series: trending'),
            # Bekijk allemaal (Top 10 films) → raster met sorteren
            ('eval', FOC % 'x.classList.contains("see-all") && x.closest(".top10row") === document.querySelectorAll(".top10row")[1]'), ('shot', 'p02-see-all-card'),
            ('walk', 'Enter', 'Bekijk allemaal openen'), ('wait', 600), ('shot', 'p03-grid'),
            ('expect', 'B.ui.top().el.classList.contains("gridpage") && document.querySelectorAll(".gridpage .vgrid .card.poster").length >= 6', 'rasterpagina met alle titels'),
            ('eval', 'window.__o1 = B.ui.top().order().join(); 1'),
            ('walk', 'ArrowUp', 'raster: omhoog naar sorteren'),
            ('expect', 'B.ui.focused.classList.contains("cat-pill")', 'focus op de sorteerknoppen'),
            ('eval', FOC % 'x._sort === "za"'), ('walk', 'Enter', 'sorteren Z–A'), ('wait', 400), ('shot', 'p04-grid-za'),
            ('expect', '(function () { var t = B.ui.top().order().map(function (id) { return B.lib.lib.byId[id].item.title; }); for (var i = 1; i < t.length; i++) if (t[i - 1].localeCompare(t[i]) < 0) return t.join("|"); return B.ui.top().order().join() !== window.__o1; })()', 'Z–A: andere volgorde, aflopend'),
            ('eval', FOC % 'x._sort === "az"'), ('walk', 'Enter', 'sorteren A–Z'), ('wait', 300),
            ('expect', '(function () { var t = B.ui.top().order().map(function (id) { return B.lib.lib.byId[id].item.title; }); for (var i = 1; i < t.length; i++) if (t[i - 1].localeCompare(t[i]) > 0) return t.join("|"); return true; })()', 'A–Z: oplopend'),
            ('walk', '10009', 'raster: terug'),
            ('expect', 'B.ui.top().el.classList.contains("main")', 'terug op Home'),
            # contextmenu op een filmkaart: lang OK
            ('eval', FOC % ('x._key === "movie:" + %s("Silent Peak") && !x.closest(".top10row")' % MID)),
            ('hold',), ('shot', 'p05-context-menu'),
            ('expect', CTX, 'lang OK: contextmenu open'),
            ('expect', '["open", "mylist", "coll", "watched"].every(function (k) { return Array.prototype.some.call(document.querySelectorAll(".ctx-layer .pm-item"), function (x) { return x._mk === k; }); })', 'menu: details, Mijn lijst, collectie, bekeken'),
            ('walk', 'ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown ArrowDown', 'menu: focus blijft in het menu'),
            ('expect', 'B.ui.top().el.contains(B.ui.focused)', 'focus gevangen in het menu'),
            ('eval', PICK % 'mylist'), ('key', 'Enter', 500),
            ('expect', 'B.data.inSet("myList", B.data.key(B.lib.source.id, %s("Silent Peak")))' % MID, 'menu: toegevoegd aan Mijn lijst'),
            ('expect', '!document.querySelector(".ctx-layer")', 'menu dicht na kiezen'),
            ('hold',), ('eval', PICK % 'watched'), ('key', 'Enter', 500),
            ('expect', 'B.data.isWatched(%s("Silent Peak"))' % MID, 'menu: gemarkeerd als bekeken'),
            ('hold',), ('eval', PICK % 'coll'), ('key', 'Enter', 500), ('shot', 'p06-choose-collection'),
            ('eval', 'B.ui.focus(Array.prototype.filter.call(document.querySelectorAll(".choose-list .choice"), function (x) { return x.textContent.indexOf("Filmavond") >= 0; })[0]) || 1'),
            ('key', 'Enter', 600),
            ('expect', 'B.data.collectionsFor(B.app.profile.id).filter(function (c) { return c.id === "c2"; })[0].items.some(function (i) { return i.id === %s("Silent Peak") && i.type === "movie"; })' % MID, 'collectie Filmavond: film toegevoegd'),
            # Tools-toets opent hetzelfde menu, Terug sluit
            ('tv', '10135', 400), ('expect', CTX, 'Tools-toets: contextmenu'),
            ('walk', '10009', 'menu: Terug'), ('expect', '!document.querySelector(".ctx-layer") && B.ui.focused._key === "movie:" + %s("Silent Peak")' % MID, 'Terug: menu dicht, focus op de kaart'),
            # kort OK opent gewoon de titel
            ('walk', 'Enter', 'kort OK: titel openen'), ('wait', 800),
            ('expect', TOPDETAIL % 'Silent Peak', 'kort OK opent de detailpagina'),
            ('walk', '10009', 'detail: terug'),
            # Verder kijken: weghalen
            ('eval', FOC % 'x._ctx && x._ctx.progressKey === "xv3"'), ('hold',),
            ('expect', 'Array.prototype.some.call(document.querySelectorAll(".ctx-layer .pm-item"), function (x) { return x._mk === "continue"; })', 'Verder kijken: Verwijderen uit Verder kijken'),
            ('eval', PICK % 'continue'), ('key', 'Enter', 800),
            ('expect', '!B.data.progress("xv3")', 'voortgang gewist'),
            ('wait', 4000),
            # detailpagina film: Iron Valley
            ('eval', 'B.ui.push(B.screens.detail("movie", B.lib.lib.byId[%s("Iron Valley")].item)) && 1' % MID), ('wait', 6000), ('shot', 'p07-movie-detail'),
            ('expect', '!!document.querySelector(".dt-trailer-btn")', 'detail: knop Trailer'),
            ('expect', '(function () { var t = document.querySelector(".dt-ratings").textContent; return ["IMDb", "7.8", "TMDB", "7.4", "Trakt", "81%"].every(function (w) { return t.indexOf(w) >= 0; }) || t; })()', 'detail: beoordelingen IMDb/TMDB/Trakt'),
            ('expect', '!!document.querySelector(".dt-coll-btn") && !!document.querySelector(".dt-watched-btn")', 'detail: knoppen Collectie en Afvinken'),
            ('expect', 'document.querySelectorAll(".kb-stack .kb-layer").length === 3', 'detail: 3 achtergronden (Ken Burns)'),
            ('expect', 'Array.prototype.some.call(document.querySelectorAll(".dt-alts .card.other-src .src-badge"), function (b) { return b.textContent === "Jellyfin thuis"; })', 'detail: Andere bronnen (Jellyfin)'),
            ('eval', FOC % 'x.classList.contains("dt-watched-btn")'), ('walk', 'Enter', 'film afvinken'), ('wait', 300),
            ('expect', 'B.data.isWatched(%s("Iron Valley")) && document.querySelector(".dt-watched-btn").classList.contains("on")' % MID, 'film bekeken'),
            ('walk', 'Enter', 'film weer niet bekeken'), ('wait', 300),
            ('expect', '!B.data.isWatched(%s("Iron Valley"))' % MID, 'film niet bekeken'),
            ('eval', FOC % 'x.classList.contains("dt-trailer-btn")'), ('walk', 'Enter', 'trailer openen'), ('wait', 500), ('shot', 'p08-trailer'),
            ('expect', 'B.ui.top().isTrailer === true && !!document.querySelector(".trailer-full iframe")', 'trailer beeldvullend'),
            ('walk', '10009', 'trailer: terug'),
            ('expect', TOPDETAIL % 'Iron Valley', 'terug op de detailpagina'),
            ('walk', '10009', 'film: terug'),
            # serie: voortgang, seizoen afvinken, aflevering-menu
            ('eval', 'B.ui.push(B.screens.detail("series", B.lib.lib.series.filter(function (s) { return s.title === "Midnight Harbor"; })[0])) && 1'), ('wait', 3500), ('shot', 'p09-series-detail'),
            ('expect', 'document.querySelectorAll(".dt-sprogress").length === 2 && document.querySelector(".dt-sprogress").textContent.indexOf("%") >= 0', 'serie: voortgang serie en seizoen'),
            ('expect', 'document.querySelector(".dt-trailer-btn").textContent === B.L("Trailer season %d", 1)', 'serie: trailer van het seizoen'),
            ('eval', FOC % 'x.classList.contains("season-mark")'), ('walk', 'Enter', 'seizoen afvinken'), ('wait', 400), ('shot', 'p10-season-marked'),
            ('expect', '(function () { var s = B.lib.lib.series.filter(function (x) { return x.title === "Midnight Harbor"; })[0]; return B.lib.episodeCache[s.id].episodes.filter(function (e) { return e.season === 1; }).every(function (e) { return B.data.isWatched(e.id); }); })()', 'heel seizoen bekeken'),
            ('expect', 'document.querySelector(".dt-sprogress").textContent.indexOf("50%") >= 0 || document.querySelector(".dt-sprogress").textContent', 'voortgang serie bijgewerkt'),
            ('eval', FOC % 'x.classList.contains("ep-card")'), ('hold',),
            ('expect', CTX + ' && Array.prototype.some.call(document.querySelectorAll(".ctx-layer .pm-item"), function (x) { return x._mk === "watched"; })', 'aflevering: contextmenu met bekeken'),
            ('eval', PICK % 'watched'), ('key', 'Enter', 400),
            ('expect', '!B.data.isWatched(B.ui.focused._key.slice(3))', 'aflevering weer niet bekeken'),
            ('walk', '10009', 'serie: terug'),
            # Films: sorteren
            ('eval', 'B.ui.top().show("movies", true) || 1'), ('wait', 800),
            ('eval', FOC % 'x._sort === "za" && x.closest(".ct-sorts")'), ('walk', 'Enter', 'Films: Z–A'), ('wait', 400), ('shot', 'p11-movies-za'),
            ('expect', '(function () { var c = document.querySelector(".ct-gridbox .card.poster"); var t = B.lib.lib.byId[c._key.split(":")[1]].item.title; return B.lib.lib.movies.every(function (m) { return m.title.localeCompare(t) <= 0; }) || t; })()', 'Films: Z–A eerst de laatste titel'),
            # Live TV: Evenementen en zoeken
            ('eval', 'B.ui.top().show("live", true) || 1'), ('wait', 1000),
            ('expect', '!!Array.prototype.filter.call(document.querySelectorAll(".lv-group"), function (x) { return x._key === "⚡"; })[0]', 'Live TV: groep Evenementen'),
            ('eval', FOC % 'x._key === "⚡"'), ('wait', 600), ('shot', 'p12-live-events'),
            ('expect', '(function () { var r = document.querySelectorAll(".lv-vlist .lv-chan"); return r[0].textContent.indexOf("Ajax – PSV") >= 0 && !!r[0].querySelector(".live-badge") && r[1].textContent.indexOf("Feyenoord – AZ") >= 0 && r[3].textContent.indexOf("Old Fight Night") >= 0 || Array.prototype.map.call(r, function (x) { return x.textContent; }).join("|"); })()', 'Evenementen: op begintijd, LIVE, verlopen onderaan'),
            ('eval', 'document.querySelector(".lv-vlist").__x = 1; B.ui.focus(document.querySelector(".lv-vlist .lv-chan.f")) || 1'), ('hold',),
            ('expect', '["fav", "mv", "coll"].every(function (k) { return Array.prototype.some.call(document.querySelectorAll(".ctx-layer .pm-item"), function (x) { return x._mk === k; }); })', 'zender: contextmenu (favoriet, multiview, collectie)'),
            ('walk', '10009', 'zendermenu: terug'),
            ('eval', FOC % 'x.classList.contains("lv-search-btn")'), ('walk', 'Enter', 'Live TV: zoeken'), ('wait', 300),
            ('expect', 'B.ui.top().el.classList.contains("chan-search")', 'zoekscherm zender/programma'),
            ('eval', 'B.ui.top().setQuery("voetbal") || 1'), ('wait', 1500), ('shot', 'p13-live-search'),
            ('expect', 'document.querySelectorAll(".chan-search .search-progs .card.prog").length > 0', 'Live TV zoeken: programma uit de gids'),
            ('eval', 'B.ui.top().setQuery("npo") || 1'), ('wait', 800),
            ('expect', 'document.querySelectorAll(".chan-search .card.chan").length >= 3', 'Live TV zoeken: zenders'),
            ('walk', '10009', 'zoeken: terug'),
            # Zoeken: personen, tv-gids, categorieën en andere afspeellijsten
            ('eval', 'B.ui.top().show("search", true) || 1'), ('wait', 400),
            ('eval', 'B.ui.top().page().setQuery("robin") || 1'), ('wait', 2000), ('shot', 'p14-search-person'),
            ('expect', 'document.querySelectorAll(".search-people .cast-card").length === 1 && document.querySelector(".search-people .cast-name").textContent === "Robin Vale"', 'zoeken: persoon'),
            ('eval', FOC % 'x.classList.contains("person-hit")'), ('walk', 'Enter', 'persoon openen'), ('wait', 1800),
            ('expect', 'B.ui.top().el.classList.contains("person")', 'persoonspagina'),
            ('walk', '10009', 'persoon: terug'),
            ('eval', 'B.ui.top().page().setQuery("voetbal") || 1'), ('wait', 1500),
            ('expect', 'document.querySelectorAll(".search-results .search-progs .card.prog").length > 0', 'zoeken: programma in de tv-gids'),
            ('eval', FOC % 'x.classList.contains("prog")'), ('walk', 'Enter', 'programma openen'), ('wait', 300),
            ('expect', 'B.ui.top().modal === true', 'programmavenster'),
            ('walk', '10009', 'programma: terug'),
            ('eval', 'B.ui.top().page().setQuery("actie") || 1'), ('wait', 1200),
            ('expect', 'document.querySelectorAll(".search-cats .search-cat").length >= 1', 'zoeken: categorieën/genres'),
            ('eval', 'B.ui.top().page().setQuery("northern") || 1'), ('wait', 6000), ('shot', 'p15-search-other-source'),
            ('expect', 'Array.prototype.some.call(document.querySelectorAll(".search-others .card.other-src .src-badge"), function (b) { return b.textContent === "Jellyfin thuis"; })', 'zoeken: titel uit een andere afspeellijst'),
            ('eval', FOC % 'x.classList.contains("other-src")'), ('walk', 'Enter', 'andere bron openen'), ('wait', 6000), ('shot', 'p16-switched-source'),
            ('expect', 'B.lib.source.name === "Jellyfin thuis" && B.ui.top().el.classList.contains("detail") && B.ui.top().el.querySelector(".dt-title").textContent === "Northern Lights"', 'gewisseld van bron en titel geopend'),
            ('walk', '10009', 'andere bron: terug'),
        ])
        reqs = [json.loads(l) for l in open(os.path.join(OUT, 'requests.jsonl')).read().splitlines() if l.strip()] if os.path.exists(os.path.join(OUT, 'requests.jsonl')) else []
        prof = [r for r in reqs if r.get('action') == 'put_state' and str(r.get('section', '')).lower() == 'profile.' + fb.MAIN.lower()]
        def ids(r, f):
            return [x.get('id') for x in ((r.get('data') or {}).get(f) or []) if isinstance(x, dict) and not x.get('removed')]
        RESULTS.append(('put_state: Mijn lijst bevat de film', any(fb.SRC + '|xv8' in ids(r, 'myList') for r in prof), str([ids(r, 'myList') for r in prof][-1:])))
        RESULTS.append(('put_state: bekeken bevat de film', any('xv8' in ids(r, 'watched') for r in prof), str([ids(r, 'watched') for r in prof][-1:])))
        RESULTS.append(('put_state: seizoen bekeken (ep-sleutels)', any(('ep:xs1|1-8') in ids(r, 'watched') for r in prof), ''))
        RESULTS.append(('put_state: voortgang gewist (removed.progress)', any('xv3' in (((r.get('data') or {}).get('removed') or {}).get('progress') or {}) for r in prof), ''))
        cols = [r for r in reqs if r.get('action') == 'put_state' and r.get('section') == 'collections']
        def c2(r):
            b = ((r.get('data') or {}).get('profiles') or {}).get(fb.MAIN) or {}
            return [c for c in b.get('items', []) if c.get('id') == 'c2']
        RESULTS.append(('put_state: collectie (version 2, eigen emmer)', any(r['data'].get('version') == 2 and c2(r) and {'type': 'movie', 'id': 'xv8'} in c2(r)[0].get('items', []) for r in cols), str(cols[-1:])[:300]))
        tk = [r for r in reqs if 'trakt' in r]
        RESULTS.append(('Trakt: watchlist en geschiedenis', any(r['trakt'] == '/sync/watchlist' for r in tk) and any(r['trakt'] == '/sync/history' for r in tk) and any(r['trakt'] == '/sync/history/remove' for r in tk), str([r['trakt'] for r in tk])))
        # kinderprofiel: Top 10 uit populaire kids-titels
        run(SIGNED_IN, 'normal', [
            ('wait', 2500), ('key', 'ArrowRight ArrowRight Enter', 400), ('wait', 6000), ('shot', 'p17-kids-home'),
            ('expect', 'B.app.profile.kids === true && document.querySelectorAll(".top10row .row-title")[0].textContent === B.L("Top 10 kids series")', 'kids: Top 10 kids'),
            ('expect', 'B.lib.lib.byId[document.querySelectorAll(".top10row")[0].querySelector(".card.top10")._key.split(":")[1]].item.title === "Dragon Tales"', 'kids: populaire kids-serie eerst'),
        ])
        fb.MODE['parity'] = False

    if RESULTS or PAGEERRORS:
        print('\n== Samenvatting ==')
        for name, ok, detail in RESULTS:
            print(('OK   ' if ok else 'FOUT ') + name + ('' if ok else '  (' + detail + ')'))
        print('%d controles, %d fout, %d consolefouten' % (len(RESULTS), len([r for r in RESULTS if not r[1]]), len(PAGEERRORS)))
        if any(not r[1] for r in RESULTS) or PAGEERRORS:
            sys.exit(1)
