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

STUB = """
window.tizen = { application: { getCurrentApplication: function () { return { exit: function () { window.__exited = true; } }; } },
  tvinputdevice: { getSupportedKeys: function () { return []; }, registerKey: function () {} } };
window.webapis = { productinfo: { getRealModel: function () { return 'QE55Q80B'; }, getModel: function () { return 'QE55Q80B'; } } };
"""


def run(page_setup, mode, steps):
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
                    if data.get('action') in ('put_state', 'stream_start', 'register_device'):
                        with open(os.path.join(OUT, 'requests.jsonl'), 'a') as f:
                            f.write(json.dumps(data, ensure_ascii=False) + '\n')
                    code, body = fb.device_auth(data, mode)
                    return route.fulfill(status=code, headers=hdr, body=json.dumps(body))
                if u.path.startswith('/auth/v1/token') or u.path.startswith('/auth/v1/verify'):
                    return route.fulfill(status=200, headers=hdr, body=json.dumps(fb.session()))
                if u.path.startswith('/rest/v1/account_data'):
                    if req.method == 'GET':
                        return route.fulfill(status=200, headers=hdr, body=json.dumps([{'profiles': fb.profiles(), 'updated_at': 'x'}]))
                    return route.fulfill(status=201, headers=hdr, body='')
                return route.fulfill(status=404, headers=hdr, body='{}')
            if u.netloc == 'xtream.test':
                q = urllib.parse.parse_qs(u.query)
                if u.path == '/xmltv.php':
                    return route.fulfill(status=200, headers={'Access-Control-Allow-Origin': '*', 'Content-Type': 'text/xml'}, body=fb.xmltv())
                if u.path == '/player_api.php':
                    return route.fulfill(status=200, headers=hdr, body=json.dumps(fb.xtream((q.get('action') or [''])[0], q)))
                return route.fulfill(status=404, body='')
            if u.netloc == 'img.test':
                f = os.path.join(IMG, os.path.basename(u.path))
                if os.path.exists(f):
                    return route.fulfill(status=200, path=f, headers={'Access-Control-Allow-Origin': '*'})
                return route.fulfill(status=404, body='')
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
            elif st[0] == 'eval':
                print(st[1], '=>', page.evaluate(st[1]))
        for l in logs:
            if 'error' in l.lower() or 'PAGEERROR' in l:
                print('  console', l)
        b.close()


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
            ('key', 'Escape', 400),
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
            ('eval', 'B.player.playChannel(B.lib.lib.channels[0], B.lib.lib.channels) && 1'), ('wait', 1500), ('shot', '18-player-live'),
            ('wait', 3000), ('shot', '19-player-error'),
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
