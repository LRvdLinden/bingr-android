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


def run(page_setup, mode, steps):
    fb.SAVED.clear()
    fb.MODE['big'] = mode == 'big'
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
                    if data.get('action') in ('put_state', 'stream_start', 'register_device', 'put_sources'):
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
            elif st[0] == 'eval':
                try:
                    r = page.evaluate(st[1])
                except Exception as e:
                    r = 'FOUT: ' + str(e).split('\n')[0]
                print((st[2] if len(st) > 2 else st[1][:90]), '=>', r)
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

