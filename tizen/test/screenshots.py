"""Start de Tizen-app in headless Chromium met een stub voor window.tizen/webapis en een neppe backend,
en maakt screenshots van de belangrijkste schermen. Gebruik: python3 tizen/test/screenshots.py <uitvoermap>"""
import json, os, sys, urllib.parse
from playwright.sync_api import sync_playwright

sys.path.insert(0, os.path.dirname(__file__))
import fake_backend as fb

TIZEN = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
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
                    if data.get('action') in ('put_state', 'stream_start', 'stream_stop', 'register_device', 'put_sources', 'rename_device', 'remove_device', 'heartbeat', 'announcements'):
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
    open: function (u) { log('open'); st = 'IDLE'; t = 0; },
    setDisplayRect: function () {}, setDisplayMethod: function () {}, setListener: function (l) { ls = l; },
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

    if RESULTS or PAGEERRORS:
        print('\n== Samenvatting ==')
        for name, ok, detail in RESULTS:
            print(('OK   ' if ok else 'FOUT ') + name + ('' if ok else '  (' + detail + ')'))
        print('%d controles, %d fout, %d consolefouten' % (len(RESULTS), len([r for r in RESULTS if not r[1]]), len(PAGEERRORS)))
        if any(not r[1] for r in RESULTS) or PAGEERRORS:
            sys.exit(1)
