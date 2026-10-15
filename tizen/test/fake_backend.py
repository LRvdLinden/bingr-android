"""Neppe backend voor lokale tests van de Tizen-app in Chromium (Playwright): Supabase (device-auth, account_data),
een Xtream-server met zenders, films en series, een XMLTV-gids en gegenereerde posters/logo's.
Alleen voor tests op de ontwikkelmachine; wordt niet meegepakt in de .wgt."""
import json, os, time, urllib.parse, random, colorsys
from datetime import datetime, timezone, timedelta

SRC = 'A1B2C3D4-0000-4000-8000-000000000001'
JF = 'B2C3D4E5-0000-4000-8000-000000000002'
PX = 'C3D4E5F6-0000-4000-8000-000000000003'
SM = 'D4E5F6A7-0000-4000-8000-000000000004'
# wat de app tijdens een test bewaart (put_sources / put_state)
SAVED = {}
MAIN = '11111111-AAAA-4000-8000-000000000001'
KIDS = '22222222-BBBB-4000-8000-000000000002'
SAM = '33333333-CCCC-4000-8000-000000000003'

GROUPS_LIVE = {'1': 'Nederland', '2': 'Sport', '3': 'Kids', '4': 'Nieuws & docu'}
CHANNELS = [('NPO 1', '1'), ('NPO 2', '1'), ('NPO 3', '1'), ('RTL 4', '1'), ('RTL 5', '1'), ('SBS 6', '1'), ('RTL 7', '1'),
            ('Veronica', '1'), ('Net5', '1'), ('RTL 8', '1'), ('Discovery', '4'), ('National Geo', '4'), ('Eurosport 1', '2'),
            ('Ziggo Sport', '2'), ('ESPN', '2'), ('Nickelodeon', '3'), ('Disney Junior', '3'), ('Cartoon Net', '3'),
            ('BBC One', '4'), ('CNN', '4')]
TITLES = ['Midnight Harbor', 'The Last Signal', 'Golden Fields', 'Neon District', 'Ocean Deep', 'Iron Valley', 'Paper Moons',
          'Silent Peak', 'Red Horizon', 'Frozen Lake', 'City of Glass', 'Wild Coast', 'Night Shift', 'Echo Park', 'Summer Run',
          'Blue Planet', 'Desert Wind', 'The Archive', 'Northern Lights', 'Hidden Garden', 'Fast Lane', 'The Heist',
          'Dragon Tales', 'Space Kids']
VOD_GROUPS = {'10': 'Actie', '11': 'Drama', '12': 'Familie', '13': 'Nieuw binnen', '14': 'VOD | DE'}
# build 59: titels met een taal-tag zoals IPTV-aanbieders ze leveren (ContentLanguage)
LANG_MOVIES = [(101, 'Animal (IT)', '2023', '13', 2), (102, 'NL | Animal', '2023', '13', 3), (103, 'Animal Kingdom (NL)', '2021', '11', 4),
               (104, 'Night Train MULTI', '2022', '10', 5), (105, 'EN: Paper Trail (NL SUBS)', '2020', '11', 6), (106, 'Der Tunnel', '2019', '14', 7)]
SER_GROUPS = {'20': 'Spanning', '21': 'Kinderseries'}


def iso(dt):
    return dt.strftime('%Y-%m-%dT%H:%M:%S.000Z')


def make_images(out):
    from PIL import Image, ImageDraw, ImageFont
    os.makedirs(out, exist_ok=True)
    if os.path.exists(os.path.join(out, 'l19.png')):
        return
    random.seed(4)
    try:
        font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 34)
    except Exception:
        font = ImageFont.load_default()
    for i, t in enumerate(TITLES):
        h = random.random()
        c1 = tuple(int(x * 255) for x in colorsys.hsv_to_rgb(h, .7, .75))
        c2 = tuple(int(x * 255) for x in colorsys.hsv_to_rgb((h + .12) % 1, .8, .25))
        im = Image.new('RGB', (400, 600)); d = ImageDraw.Draw(im)
        for y in range(600):
            f = y / 600
            d.line([(0, y), (400, y)], fill=tuple(int(c1[k] * (1 - f) + c2[k] * f) for k in range(3)))
        for _ in range(6):
            x = random.randint(-50, 350); y = random.randint(0, 500); r = random.randint(40, 160)
            d.ellipse([x, y, x + r, y + r], outline=(255, 255, 255), width=3)
        y = 420
        for w in t.split():
            d.text((28, y), w.upper(), font=font, fill=(255, 255, 255)); y += 44
        im.save(os.path.join(out, 'p%02d.jpg' % i), quality=85)
    for i, (t, _) in enumerate(CHANNELS):
        im = Image.new('RGBA', (300, 170), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
        c = tuple(int(x * 255) for x in colorsys.hsv_to_rgb((i * 0.13) % 1, .6, .95))
        d.rounded_rectangle([30, 30, 270, 140], radius=22, fill=c + (255,))
        tw = d.textlength(t, font=font)
        d.text(((300 - tw) / 2, 66), t, font=font, fill=(255, 255, 255, 255))
        im.save(os.path.join(out, 'l%02d.png' % i))


def photo_file(img_dir):
    """Een echte kleine JPEG als profielfoto (build 100)."""
    f = os.path.join(img_dir, 'profile-photo.jpg')
    if not os.path.exists(f):
        from PIL import Image
        Image.new('RGB', (256, 256), (200, 40, 60)).save(f, 'JPEG', quality=80)
    return f


def profiles():
    now = datetime.now(timezone.utc)
    if MODE.get('photo'):
        lst = _profiles(now)
        lst[0] = dict(lst[0], photo={'path': 'user-1/' + MAIN.lower() + '.jpg', 'updated': iso(now)})
        return lst
    return _profiles(now)


def _profiles(now):
    return [
        {'id': MAIN, 'name': 'Léon', 'avatar': {'symbol': 'popcorn', 'colorIndex': 0, 'emoji': '🍿'}, 'kids': False, 'ageLimit': None,
         'isMain': True, 'order': 0, 'photo': None, 'updatedAt': iso(now), 'accentColor': 'red'},
        {'id': SAM, 'name': 'Sam', 'avatar': {'symbol': 'gamecontroller', 'colorIndex': 1}, 'kids': False, 'ageLimit': 16,
         'isMain': False, 'order': 1, 'photo': None, 'updatedAt': iso(now), 'accentColor': 'blue'},
        {'id': KIDS, 'name': 'Kids', 'avatar': {'symbol': 'kid03', 'colorIndex': 4}, 'kids': True, 'ageLimit': None,
         'isMain': False, 'order': 2, 'photo': None, 'updatedAt': iso(now), 'accentColor': 'green'},
        {'id': 'DEAD0000-0000-4000-8000-000000000009', 'deleted': True, 'deletedAt': iso(now), 'updatedAt': iso(now)},
    ]


def state():
    now = datetime.now(timezone.utc)
    t = lambda m: iso(now - timedelta(minutes=m))
    return {
        'settings': {'data': {'shared': {'appLanguage': 'nl', 'parentalControl': False, 'parentalAutoLockAdult': True,
                                         'resumeLastChannel': False,
                                         'introMarkers': {SRC.lower() + ':xs1': {'introStart': 5, 'introEnd': 70, 'creditsStart': 2500}}},
                              'android': {'prefs': {}}, 'updatedAt': t(100)}, 'updatedAt': t(100)},
        'profile.' + MAIN.lower(): {'data': {
            'myList': [{'id': SRC + '|xs2', 'at': t(30)}, {'id': SRC + '|xv5', 'at': t(40)}],
            'favorites': [{'id': SRC + '|x1', 'at': t(50)}, {'id': SRC + '|x13', 'at': t(51)}],
            'watched': [{'id': 'xe1001', 'at': t(300)}, {'id': 'ep:xs1|1-1', 'at': t(300)}],
            'recentChannels': [{'id': SRC + '|x4', 'at': t(5)}, {'id': SRC + '|x14', 'at': t(8)}],
            'progress': {'xv3': {'position': 2400, 'duration': 6000, 'fraction': 0.4, 'updatedAt': t(20), 'title': 'Neon District', 'playlistID': SRC},
                         'xe1002': {'position': 1500, 'duration': 2700, 'fraction': 0.55, 'updatedAt': t(10), 'title': 'Midnight Harbor', 'playlistID': SRC}},
            'history': [], 'removed': {}, 'pin': None, 'pinUpdatedAt': t(500),
            'homeLayout': {'rows': [{'key': 'continue', 'visible': True}, {'key': 'mylist', 'visible': True}, {'key': 'top10', 'visible': True},
                                    {'key': 'live', 'visible': True}, {'key': 'collection:c1', 'visible': True}, {'key': 'recent', 'visible': True},
                                    {'key': 'categories', 'visible': True}], 'updatedAt': t(400)},
            'updatedAt': t(10)}, 'updatedAt': t(10)},
        'profile.' + SAM.lower(): {'data': {'pin': '1234', 'pinUpdatedAt': t(100)}, 'updatedAt': t(100)},
        'collections': {'data': {'version': 2, 'migratedAt': t(1000), 'updatedAt': t(60), 'items': [], 'profiles': {
            MAIN: {'items': [
                {'id': 'c1', 'name': 'Sport live', 'image': {'symbol': 'sportscourt', 'colorIndex': 2}, 'kind': 'smart', 'query': 'sport, espn',
                 'types': ['channel'], 'order': 0, 'updatedAt': t(60)},
                {'id': 'c2', 'name': 'Filmavond', 'image': {'symbol': 'popcorn', 'colorIndex': 0}, 'kind': 'manual',
                 'items': [{'type': 'movie', 'id': 'xv7'}, {'type': 'movie', 'id': 'xv1'}, {'type': 'series', 'id': 'xs3'}], 'order': 1, 'updatedAt': t(60)}],
                'updatedAt': t(60)}}}, 'updatedAt': t(60)},
    }


def sources():
    return {'sources': [
        {'id': SRC, 'name': 'Thuis IPTV', 'kind': 'Xtream Codes', 'avatar': {'symbol': 'tv', 'colorIndex': 0}, 'm3uURL': '', 'epgURL': '',
         'serverURL': 'http://xtream.test', 'username': 'demo', 'macAddress': '', 'preferHLS': True, 'secrets': {'password': 'demo'},
         'android': {'customization': {'renamed': {}, 'hidden': [], 'groupOrder': [], 'channelOrder': {}, 'merged': {}}, 'lockedGroups': []},
         'updatedAt': iso(datetime.now(timezone.utc)), 'catchupOffsets': {'x2': -5}},
        {'id': JF, 'name': 'Jellyfin thuis', 'kind': 'Jellyfin', 'avatar': {'symbol': 'film', 'colorIndex': 2}, 'm3uURL': '', 'epgURL': '',
         'serverURL': 'http://jf.test', 'username': 'leon', 'macAddress': '', 'preferHLS': False,
         'secrets': {'session': json.dumps({'token': 'jf-token', 'userID': 'u1', 'serverURI': 'http://jf.test'})},
         'updatedAt': iso(datetime.now(timezone.utc))},
        {'id': PX, 'name': 'Plex', 'kind': 'Plex', 'avatar': {'symbol': 'film', 'colorIndex': 3}, 'm3uURL': '', 'epgURL': '',
         'serverURL': 'http://px.test', 'username': '', 'macAddress': '', 'preferHLS': False,
         'secrets': {'password': 'plex-user', 'session': json.dumps({'token': 'px-token', 'userID': 'Server', 'serverURI': 'http://px.test'})},
         'updatedAt': iso(datetime.now(timezone.utc))},
        {'id': SM, 'name': 'Stremio', 'kind': 'Stremio', 'avatar': {'symbol': 'play', 'colorIndex': 4}, 'm3uURL': 'http://stremio.test/manifest.json',
         'epgURL': '', 'serverURL': '', 'username': '', 'macAddress': '', 'preferHLS': False, 'secrets': {},
         'updatedAt': iso(datetime.now(timezone.utc))},
        {'id': 'DEAD0000-0000-4000-8000-00000000000A', 'deleted': True, 'deletedAt': iso(datetime.now(timezone.utc)), 'updatedAt': iso(datetime.now(timezone.utc))}],
        'mainSourceID': SRC, 'updatedAt': iso(datetime.now(timezone.utc))}


MODE = {'big': False}


def xtream(action, params):
    if MODE['big'] and action in ('get_live_streams', 'get_vod_streams'):
        # grote bibliotheek voor de prestatietest: 6000 zenders, 25000 films
        if action == 'get_live_streams':
            return [{'stream_id': i + 1, 'num': i + 1, 'name': 'Zender %d' % (i + 1), 'stream_icon': 'http://img.test/l%02d.png' % (i % 20),
                     'category_id': str(1 + i % 4), 'epg_channel_id': 'ch%d.nl' % (i % 20 + 1), 'tv_archive': 0} for i in range(6000)]
        return [{'stream_id': i + 1, 'name': '%s %d (%d)' % (TITLES[i % 24], i, 1990 + i % 35), 'stream_icon': 'http://img.test/p%02d.jpg' % (i % 24),
                 'category_id': ['10', '11', '12', '13'][i % 4], 'container_extension': 'mp4', 'rating': str(5 + (i % 50) / 10),
                 'added': str(int(time.time()) - i * 600)} for i in range(25000)]
    if not action:
        return {'user_info': {'auth': 1, 'username': 'demo', 'status': 'Active'}, 'server_info': {}}
    if action == 'get_live_categories':
        return [{'category_id': k, 'category_name': v} for k, v in GROUPS_LIVE.items()]
    if action == 'get_live_streams':
        return [{'stream_id': i + 1, 'num': i + 1, 'name': n, 'stream_icon': 'http://img.test/l%02d.png' % i, 'category_id': g,
                 'epg_channel_id': 'ch%d.nl' % (i + 1), 'tv_archive': 1 if i < 6 else 0, 'tv_archive_duration': 3 if i < 6 else 0}
                for i, (n, g) in enumerate(CHANNELS)]
    if action == 'get_vod_categories':
        return [{'category_id': k, 'category_name': v} for k, v in VOD_GROUPS.items()]
    if action == 'get_vod_streams':
        out = []
        for i, t in enumerate(TITLES[:18]):
            g = ['10', '11', '12', '13'][i % 4]
            out.append({'stream_id': i + 1, 'name': '%s (%d)' % (t, 2015 + i % 10), 'stream_icon': 'http://img.test/p%02d.jpg' % i,
                        'category_id': g, 'container_extension': 'mp4', 'rating': str(6 + (i * 7 % 30) / 10), 'added': str(int(time.time()) - i * 86400)})
        for sid, name, year, g, img in LANG_MOVIES:
            out.append({'stream_id': sid, 'name': name, 'year': year, 'stream_icon': 'http://img.test/p%02d.jpg' % img, 'category_id': g,
                        'container_extension': 'mp4', 'rating': '7.1', 'added': str(int(time.time()) - sid * 3600)})
        return out
    if action == 'get_series_categories':
        return [{'category_id': k, 'category_name': v} for k, v in SER_GROUPS.items()]
    if action == 'get_series':
        return [{'series_id': i + 1, 'name': TITLES[i if i < 4 else 18 + i], 'cover': 'http://img.test/p%02d.jpg' % (i if i < 4 else 18 + i),
                 'category_id': '20' if i < 4 else '21', 'plot': 'Een spannende serie over geheimen, vriendschap en een haven die nooit slaapt.',
                 'rating': '8.%d' % i, 'releaseDate': '202%d-01-01' % i} for i in range(6)]
    if action == 'get_series_info':
        sid = int(params.get('series_id', ['1'])[0])
        eps = {}
        for s in (1, 2):
            eps[str(s)] = [{'id': str(sid * 1000 + (s - 1) * 100 + e), 'episode_num': e, 'season': s, 'title': 'Aflevering %d' % e,
                            'container_extension': 'mp4', 'info': {'movie_image': 'http://img.test/p%02d.jpg' % ((sid + e) % 24),
                                                                   'plot': 'De bemanning ontdekt iets wat alles verandert.', 'duration_secs': 2700}}
                           for e in range(1, 9)]
        return {'info': {'name': TITLES[sid - 1], 'plot': 'Een spannende serie.', 'genre': 'Drama, Thriller'}, 'episodes': eps}
    if action == 'get_vod_info':
        return {'info': {'plot': 'Een film vol actie en emotie, over een stad die nooit slaapt.', 'genre': 'Actie, Drama', 'duration': '1:52:00'}}
    return []


def xmltv():
    now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(hours=26)
    shows = ['Journaal', 'Het weer', 'Talkshow', 'Documentaire: De zee', 'Voetbal: Ajax – PSV', 'Film: Red Horizon', 'Quiz', 'Late Night', 'Tekenfilms', 'Nieuws']
    out = ['<?xml version="1.0" encoding="UTF-8"?><tv>']
    for i in range(len(CHANNELS)):
        t = now
        k = i
        while t < now + timedelta(hours=34):
            d = timedelta(minutes=[30, 45, 60, 90][k % 4])
            fmt = lambda x: x.strftime('%Y%m%d%H%M%S +0000')
            out.append('<programme start="%s" stop="%s" channel="ch%d.nl"><title lang="nl">%s</title><desc>Beschrijving van %s.</desc></programme>'
                       % (fmt(t), fmt(t + d), i + 1, shows[k % len(shows)], shows[k % len(shows)]))
            t += d; k += 1
    out.append('</tv>')
    return '\n'.join(out)


def device_auth(body, mode):
    a = body.get('action')
    if a == 'start':
        return 200, {'code': 'K7QM4X', 'poll_secret': 'x', 'url': 'https://bingr.online/koppelen?code=K7QM4X', 'expires_in': 600, 'interval': 3}
    if a == 'poll':
        if mode == 'approve':
            return 200, {'status': 'approved', 'token_hash': 'hash123', 'email': 'leon@example.com'}
        return 200, {'status': 'pending'}
    if a == 'register_device':
        if mode == 'limit':
            return 403, {'error': 'device_limit', 'limit': 15, 'devices': [{'device_id': 'd%d' % i, 'name': n, 'platform': p} for i, (n, p) in
                                                                          enumerate([('iPhone van Léon', 'iPhone'), ('Woonkamer', 'Apple TV'), ('MacBook', 'Mac')])]}
        return 200, {'ok': True, 'limit': 15}
    if a == 'heartbeat':
        return 200, {'ok': True}
    if a == 'config':
        return 200, {'tmdbAPIKey': 'tmdb-test', 'omdbAPIKey': '', 'traktClientID': 'trakt-id', 'traktClientSecret': 'trakt-secret',
                     'openSubtitlesAPIKey': 'os-key'}
    if a == 'get_sources':
        return 200, SAVED['sources'] if SAVED.get('sources') else sources()
    if a == 'put_sources':
        SAVED['sources'] = {'sources': body.get('sources') or [], 'mainSourceID': body.get('mainSourceID'), 'updatedAt': iso(datetime.now(timezone.utc))}
        return 200, {'ok': True, 'updatedAt': iso(datetime.now(timezone.utc))}
    if a == 'get_state':
        st = state()
        st.update(SAVED.get('state', {}))
        want = body.get('sections')
        if want:
            st = {k: v for k, v in st.items() if k in want}
        return 200, {'sections': st}
    if a == 'put_state':
        SAVED.setdefault('state', {})[body.get('section')] = {'data': body.get('data'), 'updatedAt': iso(datetime.now(timezone.utc))}
        return 200, {'ok': True, 'updatedAt': iso(datetime.now(timezone.utc))}
    if a == 'stream_start':
        if mode == 'streamlimit':
            return 403, {'error': 'stream_limit', 'limit': 6, 'streams': [{'device_id': 'x%d' % i, 'device_name': 'TV %d' % i, 'platform': 'Android TV',
                                                                           'profile_name': 'Sam', 'title': 'NPO 1 · Journaal'} for i in range(6)]}
        return 200, {'ok': True}
    if a in ('stream_ping', 'stream_stop', 'remove_device'):
        return 200, {'ok': True}
    if a == 'list_devices':
        now = datetime.now(timezone.utc)
        devs = [{'device_id': 'd1', 'name': SAVED.get('rename', {}).get('d1', 'iPhone van Léon'), 'platform': 'iPhone',
                 'created_at': iso(now - timedelta(days=30)), 'last_seen_at': iso(now - timedelta(minutes=5))},
                {'device_id': body.get('device_id'), 'name': SAVED.get('rename', {}).get(body.get('device_id'), 'Samsung TV (QE55Q80B)'),
                 'platform': 'tizen', 'created_at': iso(now - timedelta(days=2)), 'last_seen_at': iso(now), 'current': True},
                # webspeler (build 62)
                {'device_id': 'd3', 'name': 'Chrome op Mac', 'platform': 'browser',
                 'created_at': iso(now - timedelta(days=1)), 'last_seen_at': iso(now - timedelta(hours=2))}]
        return 200, {'limit': 15, 'devices': devs}
    if a == 'rename_device':
        SAVED.setdefault('rename', {})[body.get('device_id')] = body.get('name')
        return 200, {'ok': True}
    if a == 'list_streams':
        now = datetime.now(timezone.utc)
        return 200, {'limit': 6, 'streams': [{'device_id': 'd1', 'device_name': 'iPhone van Léon', 'platform': 'iPhone', 'profile_name': 'Léon',
                                              'title': 'NPO 1 · Journaal', 'kind': 'live', 'started_at': iso(now - timedelta(minutes=42))},
                                             {'device_id': 'd3', 'device_name': 'Chrome op Mac', 'platform': 'browser', 'profile_name': 'Sam',
                                              'title': 'Red Horizon', 'kind': 'movie', 'started_at': iso(now - timedelta(minutes=12))}]}
    if a == 'watch_log':
        now = datetime.now(timezone.utc)
        return 200, {'entries': [{'device_name': 'Samsung TV', 'platform': 'tizen', 'profile_name': 'Léon', 'title': 'Red Horizon',
                                  'kind': 'movie', 'content_id': 'xv9', 'started_at': iso(now - timedelta(hours=3)), 'minutes': 45},
                                 {'device_name': 'Chrome op Mac', 'platform': 'browser', 'profile_name': 'Sam', 'title': 'NPO 1 · Journaal',
                                  'kind': 'live', 'started_at': iso(now - timedelta(hours=5)), 'minutes': 20}]}
    if a == 'announcements':
        # mededelingen van de beheerder (build 62); andere scenario's: geen (oudere backend → onbekende actie)
        if mode != 'announce':
            return 400, {'error': 'unknown_action'}
        now = datetime.now(timezone.utc)
        nl = body.get('lang') == 'nl'
        return 200, {'items': [
            {'id': 'a2', 'title': 'Onderhoud vannacht' if nl else 'Maintenance tonight',
             'body': 'Tussen 02:00 en 03:00 kan inloggen even niet lukken.' if nl else 'Signing in may not work between 02:00 and 03:00.',
             'created_at': iso(now - timedelta(hours=1))},
            {'id': 'a1', 'title': 'Welkom bij Bingr' if nl else 'Welcome to Bingr', 'body': 'Bedankt voor het testen!' if nl else 'Thanks for testing!',
             'created_at': iso(now - timedelta(days=1))},
            {'id': 'a0', 'title': 'Al gezien', 'body': 'Deze is al gelezen.', 'created_at': iso(now - timedelta(days=2))}]}
    if a == 'watch_stats':
        # kijkstatistieken (build 68); 'oldstats' = oude backend zonder watch_stats, 'nostats' = nog niets gekeken
        if mode == 'oldstats':
            return 400, {'error': 'unknown_action'}
        return 200, watch_stats(body, mode)
    if a == 'delete_watch_stats':
        SAVED['stats_deleted'] = body.get('profile_id') or 'all'
        return 200, {'ok': True}
    return 400, {'error': 'unknown_action'}


def watch_stats(body, mode):
    """Antwoord van watch_stats zoals docs/ACCOUNT.md: totalen, staven per dag/maand, toplijsten en per profiel."""
    period = body.get('period') or 'month'
    offset = int(body.get('offset') or 0)
    pid = body.get('profile_id')
    today = datetime.now().date()
    if period == 'week':
        start = today - timedelta(days=today.weekday()) + timedelta(days=7 * offset)
        end = start + timedelta(days=6)
    elif period == 'month':
        y, m = today.year, today.month + offset
        while m < 1:
            m += 12; y -= 1
        start = today.replace(year=y, month=m, day=1)
        end = (start.replace(day=28) + timedelta(days=4)).replace(day=1) - timedelta(days=1)
    else:
        start = today.replace(year=today.year + offset, month=1, day=1)
        end = start.replace(month=12, day=31)
    out = {'period': period, 'from': start.isoformat(), 'to': end.isoformat(), 'profile_id': pid, 'since': '2026-10-13'}
    if mode == 'nostats' or SAVED.get('stats_deleted'):
        out.update({'totals': {'minutes': 0, 'movies': {'count': 0, 'minutes': 0}, 'series': {'shows': 0, 'episodes': 0, 'minutes': 0},
                               'live': {'channels': 0, 'minutes': 0}}, 'by_bucket': [], 'top': {'series': [], 'movies': [], 'channels': []}, 'profiles': []})
        return out
    buckets = []
    if period == 'year':
        for mth in range(1, 13):
            k = '%04d-%02d' % (start.year, mth)
            buckets.append({'key': k, 'minutes': 0, 'movie_minutes': 300 + mth * 40, 'episode_minutes': 600 + (mth % 4) * 200, 'live_minutes': 200 + (mth % 3) * 90})
    else:
        d = start
        i = 0
        while d <= end:
            if i % 3 != 2:
                buckets.append({'key': d.isoformat(), 'movie_minutes': (i % 4) * 35, 'episode_minutes': 45 + (i % 5) * 30, 'live_minutes': (i % 2) * 50})
            d += timedelta(days=1); i += 1
        for b in buckets:
            b['minutes'] = b['movie_minutes'] + b['episode_minutes'] + b['live_minutes']
    if period == 'year':
        for b in buckets:
            b['minutes'] = b['movie_minutes'] + b['episode_minutes'] + b['live_minutes']
    mv = sum(b['movie_minutes'] for b in buckets); ep = sum(b['episode_minutes'] for b in buckets); lv = sum(b['live_minutes'] for b in buckets)
    out['totals'] = {'minutes': mv + ep + lv, 'movies': {'count': 7, 'minutes': mv}, 'series': {'shows': 4, 'episodes': 23, 'minutes': ep},
                     'live': {'channels': 6, 'minutes': lv}}
    out['by_bucket'] = buckets
    out['top'] = {
        'series': [{'title': TITLES[i], 'item_key': 'xs%d' % (i + 1), 'minutes': 600 - i * 90, 'episodes': 9 - i} for i in range(4)] +
                  [{'title': 'Onbekende serie %d' % i, 'item_key': 't:onbekend %d' % i, 'minutes': 120 - i * 10, 'episodes': 2} for i in range(4)],
        'movies': [{'title': TITLES[i], 'item_key': 'xv%d' % (i + 1), 'minutes': 140 - i * 9} for i in range(7)],
        'channels': [{'title': n, 'item_key': 'x', 'minutes': 300 - i * 40} for i, (n, g) in enumerate(CHANNELS[:6])],
    }
    out['profiles'] = [] if pid else [{'profile_id': MAIN, 'profile_name': 'Léon', 'minutes': (mv + ep + lv) * 0.6},
                                      {'profile_id': SAM, 'profile_name': 'Sam', 'minutes': (mv + ep + lv) * 0.3},
                                      {'profile_id': KIDS, 'profile_name': 'Kids', 'minutes': (mv + ep + lv) * 0.1}]
    return out


def session():
    return {'access_token': 'h.' + 'eyJzdWIiOiJ1c2VyLTEifQ' + '.s', 'refresh_token': 'r2', 'expires_in': 3600,
            'user': {'id': 'user-1', 'email': 'leon@example.com'}}


# MARK: andere diensten (Jellyfin, Plex, Stremio, TMDB, Trakt, OpenSubtitles, video)

def gz(data):
    import gzip
    return gzip.compress(data.encode('utf-8') if isinstance(data, str) else data)


def make_media(out):
    """Kort testfilmpje (VP9 in MP4, speelt in Chromium) voor het voorbeeld in Live TV en multiview."""
    import shutil, subprocess
    f = os.path.join(out, 'clip.mp4')
    if os.path.exists(f) or not shutil.which('ffmpeg'):
        return f if os.path.exists(f) else None
    subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc=size=640x360:rate=25', '-t', '120',
                    '-c:v', 'libvpx-vp9', '-b:v', '300k', '-deadline', 'realtime', '-cpu-used', '8', f], check=False)
    return f if os.path.exists(f) else None


JSON = {'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/json'}


def other(url, method, body, img_dir):
    """Antwoord voor de andere testdomeinen: (status, headers, bytes) of None."""
    u = urllib.parse.urlparse(url)
    q = urllib.parse.parse_qs(u.query)
    host, path = u.netloc, u.path
    qs = q.get('query', [''])[0].lower()
    j = lambda o, code=200: (code, JSON, json.dumps(o).encode())
    if host == 'jf.test':
        if path == '/Users/AuthenticateByName':
            return j({'AccessToken': 'jf-new', 'User': {'Id': 'u1'}})
        if path.startswith('/Items/') and path.endswith('/Images/Primary'):
            n = sum(ord(c) for c in path) % 24
            return 200, {'Access-Control-Allow-Origin': '*', 'Content-Type': 'image/jpeg'}, open(os.path.join(img_dir, 'p%02d.jpg' % n), 'rb').read()
        if path == '/Users/u1/Items':
            t = q.get('IncludeItemTypes', [''])[0]
            if t == 'Movie':
                return j({'Items': [{'Id': 'm%d' % i, 'Name': TITLES[i], 'ProductionYear': 2010 + i, 'Genres': ['Drama' if i % 2 else 'Animatie'],
                                     'CommunityRating': 7.1, 'Overview': 'Jellyfin-film.', 'DateCreated': '2026-10-0%dT10:00:00.0000000Z' % (i % 9 + 1),
                                     'ProviderIds': {'Imdb': 'tt00%05d' % i}} for i in range(8)]})
            if t == 'Series':
                return j({'Items': [{'Id': 's1', 'Name': 'Northern Lights', 'ProductionYear': 2021, 'Genres': ['Drama'], 'Overview': 'Jellyfin-serie.',
                                     'ProviderIds': {'Imdb': 'tt0099999'}}]})
        if path == '/Shows/s1/Episodes':
            return j({'Items': [{'Id': 'e%d' % e, 'Name': 'Deel %d' % e, 'ParentIndexNumber': 1, 'IndexNumber': e, 'RunTimeTicks': 27000000000} for e in range(1, 6)]})
        if path == '/LiveTv/Channels':
            return 404, JSON, b'{}'
        return 404, JSON, b'{}'
    if host == 'px.test':
        if path == '/identity':
            return j({'MediaContainer': {'machineIdentifier': 'abc'}})
        if path == '/library/sections':
            return j({'MediaContainer': {'Directory': [{'key': '1', 'type': 'movie', 'title': 'Films'}, {'key': '2', 'type': 'show', 'title': 'Series'}]}})
        if path == '/library/sections/1/all':
            return j({'MediaContainer': {'Metadata': [{'ratingKey': str(100 + i), 'title': TITLES[i + 8], 'year': 2018 + i % 5, 'thumb': '/thumb/%d' % i,
                                                       'Genre': [{'tag': 'Plex-films'}], 'addedAt': 1760000000 + i,
                                                       'Media': [{'Part': [{'key': '/parts/%d/file.mp4' % i}]}],
                                                       'Guid': [{'id': 'imdb://tt01%05d' % i}]} for i in range(6)]}})
        if path == '/library/sections/2/all':
            return j({'MediaContainer': {'Metadata': [{'ratingKey': '200', 'title': 'Echo Park', 'year': 2019, 'thumb': '/thumb/9'}]}})
        if path == '/library/metadata/200/allLeaves':
            return j({'MediaContainer': {'Metadata': [{'ratingKey': str(300 + e), 'title': 'Episode %d' % e, 'parentIndex': 1, 'index': e,
                                                       'duration': 1500000, 'Media': [{'Part': [{'key': '/parts/e%d.mp4' % e}]}]} for e in range(1, 4)]}})
        if path.startswith('/thumb/'):
            n = int(path.split('/')[-1]) % 24
            return 200, {'Access-Control-Allow-Origin': '*', 'Content-Type': 'image/jpeg'}, open(os.path.join(img_dir, 'p%02d.jpg' % n), 'rb').read()
        return 404, JSON, b'{}'
    if host == 'plex.tv':
        if path == '/api/v2/pins' and method == 'POST':
            return j({'id': 4242, 'code': 'B7KQ'})
        if path == '/api/v2/pins/4242':
            return j({'id': 4242, 'code': 'B7KQ', 'authToken': 'plex-user-token'})
        if path == '/api/v2/resources':
            return j([{'name': 'Server', 'provides': 'server', 'accessToken': 'px-token',
                       'connections': [{'uri': 'http://px.test', 'local': True}]}])
    if host == 'stremio.test':
        if path == '/manifest.json':
            return j({'name': 'Test-add-on', 'resources': ['catalog', 'meta', 'stream'], 'types': ['movie', 'series'],
                      'catalogs': [{'type': 'movie', 'id': 'top', 'name': 'Populair'}, {'type': 'series', 'id': 'top', 'name': 'Populair'}]})
        if path == '/catalog/movie/top.json':
            return j({'metas': [{'id': 'tt02%05d' % i, 'name': TITLES[i + 4], 'poster': 'http://img.test/p%02d.jpg' % (i + 4),
                                 'releaseInfo': '2020', 'imdbRating': '7.%d' % i} for i in range(6)]})
        if path == '/catalog/series/top.json':
            return j({'metas': [{'id': 'tt0300001', 'name': 'Wild Coast', 'poster': 'http://img.test/p11.jpg', 'releaseInfo': '2022'}]})
        if path == '/meta/series/tt0300001.json':
            return j({'meta': {'videos': [{'id': 'tt0300001:1:%d' % e, 'season': 1, 'episode': e, 'name': 'Afl. %d' % e} for e in range(1, 4)]}})
        if path.startswith('/stream/'):
            return j({'streams': [{'name': '1080p', 'title': 'Test-bron', 'url': 'http://video.test/clip.mp4'},
                                  {'name': 'Torrent', 'infoHash': 'abc'}]})
        return 404, JSON, b'{}'
    if host == 'api.themoviedb.org':
        if path in ('/3/search/movie', '/3/search/tv'):
            kids = any(w in qs for w in ['dragon', 'space kids', 'golden'])
            if not qs:
                return j({'results': []})
            ol = 'it' if qs == 'animal' else ('de' if 'tunnel' in qs else 'en')
            return j({'results': [{'id': 1000 + len(qs), 'genre_ids': [16, 10751] if kids else [53, 18], 'adult': False, 'original_language': ol}]})
        if path.startswith('/3/find/'):
            return j({'movie_results': [], 'tv_results': []})
        if path.endswith('/release_dates'):
            mid = int(path.split('/')[3])
            cert = 'AL' if mid % 2 else '16'
            return j({'results': [{'iso_3166_1': 'NL', 'release_dates': [{'certification': cert}]}, {'iso_3166_1': 'US', 'release_dates': [{'certification': 'PG-13'}]}]})
        if path.endswith('/content_ratings'):
            return j({'results': [{'iso_3166_1': 'NL', 'rating': '12'}]})
        if path.startswith('/3/movie/') or path.startswith('/3/tv/'):
            return j({'id': int(path.split('/')[3]), 'original_language': 'en'})
        return j({})
    if host in ('api.trakt.tv', 'auth.trakt.tv'):
        if path == '/oauth/device/code':
            return j({'device_code': 'dev123', 'user_code': '7F3K2Q9M', 'verification_url': 'https://trakt.tv/activate', 'expires_in': 600, 'interval': 1})
        if path == '/oauth/device/token':
            return j({'access_token': 'trakt-at', 'refresh_token': 'trakt-rt', 'created_at': int(time.time()), 'expires_in': 7776000})
        if path == '/users/settings':
            return j({'user': {'username': 'leon'}})
        if path == '/sync/last_activities':
            return j({'all': '2026-10-12T10:00:00.000Z'})
        if path == '/sync/watched/movies':
            return j([{'movie': {'title': 'Neon District', 'year': 2017, 'ids': {'imdb': 'tt0000003'}}}])
        if path == '/sync/watched/shows':
            return j([{'show': {'title': 'Midnight Harbor', 'year': 2020, 'ids': {'imdb': 'tt1000001'}},
                       'seasons': [{'number': 1, 'episodes': [{'number': 1}, {'number': 2}, {'number': 3}]}]}])
        if path.startswith('/search/'):
            return j([{'movie': {'title': qs, 'year': 2020, 'ids': {'imdb': 'tt0000777'}}, 'show': {'title': qs, 'ids': {'imdb': 'tt1000001'}}}])
        if path.startswith('/scrobble/') or path.startswith('/sync/history'):
            with open(os.path.join(os.path.dirname(img_dir), 'requests.jsonl'), 'a') as f:
                f.write(json.dumps({'trakt': path, 'body': json.loads(body or '{}')}) + '\n')
            return j({'id': 1}, 201)
        return j({})
    if host == 'api.opensubtitles.com':
        if path == '/api/v1/login':
            return j({'token': 'os-token', 'base_url': 'api.opensubtitles.com', 'user': {'remaining_downloads': 20}})
        if path == '/api/v1/subtitles':
            return j({'data': [{'attributes': {'language': 'nl', 'release': 'Iron.Valley.2020.1080p.NL', 'download_count': 1200, 'from_trusted': True,
                                               'files': [{'file_id': 77, 'file_name': 'iron.srt'}]}},
                               {'attributes': {'language': 'en', 'release': 'Iron.Valley.2020.EN', 'download_count': 900,
                                               'files': [{'file_id': 78}]}}]})
        if path == '/api/v1/download':
            return j({'link': 'http://os.test/sub.srt', 'remaining': 19})
    if host == 'os.test':
        srt = '1\n00:00:00,500 --> 00:00:30,000\nDit is een <i>ondertitel</i> van OpenSubtitles.\nTweede regel.\n\n2\n00:00:30,500 --> 00:01:00,000\nNog een.\n'
        return 200, {'Access-Control-Allow-Origin': '*', 'Content-Type': 'application/x-subrip'}, srt.encode('cp1252')
    return None
