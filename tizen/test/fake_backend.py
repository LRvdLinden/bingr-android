"""Neppe backend voor lokale tests van de Tizen-app in Chromium (Playwright): Supabase (device-auth, account_data),
een Xtream-server met zenders, films en series, een XMLTV-gids en gegenereerde posters/logo's.
Alleen voor tests op de ontwikkelmachine; wordt niet meegepakt in de .wgt."""
import json, os, time, urllib.parse, random, colorsys
from datetime import datetime, timezone, timedelta

SRC = 'A1B2C3D4-0000-4000-8000-000000000001'
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
VOD_GROUPS = {'10': 'Actie', '11': 'Drama', '12': 'Familie', '13': 'Nieuw binnen'}
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


def profiles():
    now = datetime.now(timezone.utc)
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
         'updatedAt': iso(datetime.now(timezone.utc))}], 'mainSourceID': SRC, 'updatedAt': iso(datetime.now(timezone.utc))}


def xtream(action, params):
    if not action:
        return {'user_info': {'auth': 1, 'username': 'demo', 'status': 'Active'}, 'server_info': {}}
    if action == 'get_live_categories':
        return [{'category_id': k, 'category_name': v} for k, v in GROUPS_LIVE.items()]
    if action == 'get_live_streams':
        return [{'stream_id': i + 1, 'num': i + 1, 'name': n, 'stream_icon': 'http://img.test/l%02d.png' % i, 'category_id': g,
                 'epg_channel_id': 'ch%d.nl' % (i + 1), 'tv_archive': 0} for i, (n, g) in enumerate(CHANNELS)]
    if action == 'get_vod_categories':
        return [{'category_id': k, 'category_name': v} for k, v in VOD_GROUPS.items()]
    if action == 'get_vod_streams':
        out = []
        for i, t in enumerate(TITLES[:18]):
            g = ['10', '11', '12', '13'][i % 4]
            out.append({'stream_id': i + 1, 'name': '%s (%d)' % (t, 2015 + i % 10), 'stream_icon': 'http://img.test/p%02d.jpg' % i,
                        'category_id': g, 'container_extension': 'mp4', 'rating': str(6 + (i * 7 % 30) / 10), 'added': str(int(time.time()) - i * 86400)})
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
    now = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(hours=2)
    shows = ['Journaal', 'Het weer', 'Talkshow', 'Documentaire: De zee', 'Voetbal: Ajax – PSV', 'Film: Red Horizon', 'Quiz', 'Late Night', 'Tekenfilms', 'Nieuws']
    out = ['<?xml version="1.0" encoding="UTF-8"?><tv>']
    for i in range(len(CHANNELS)):
        t = now
        k = i
        while t < now + timedelta(hours=10):
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
        return 200, {'code': 'K7QM4X', 'poll_secret': 'x', 'url': 'https://bingr.online/tv?code=K7QM4X', 'expires_in': 600, 'interval': 3}
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
        return 200, {'tmdbAPIKey': '', 'omdbAPIKey': ''}
    if a == 'get_sources':
        return 200, sources()
    if a == 'get_state':
        return 200, {'sections': state()}
    if a == 'put_state':
        return 200, {'ok': True, 'updatedAt': iso(datetime.now(timezone.utc))}
    if a == 'stream_start':
        if mode == 'streamlimit':
            return 403, {'error': 'stream_limit', 'limit': 6, 'streams': [{'device_id': 'x%d' % i, 'device_name': 'TV %d' % i, 'platform': 'Android TV',
                                                                           'profile_name': 'Sam', 'title': 'NPO 1 · Journaal'} for i in range(6)]}
        return 200, {'ok': True}
    if a in ('stream_ping', 'stream_stop', 'remove_device'):
        return 200, {'ok': True}
    if a == 'list_devices':
        return 200, {'limit': 15, 'devices': [{'device_id': 'd1', 'name': 'iPhone van Léon', 'platform': 'iPhone'}]}
    return 400, {'error': 'unknown_action'}


def session():
    return {'access_token': 'h.' + 'eyJzdWIiOiJ1c2VyLTEifQ' + '.s', 'refresh_token': 'r2', 'expires_in': 3600,
            'user': {'id': 'user-1', 'email': 'leon@example.com'}}
