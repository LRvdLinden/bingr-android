#!/usr/bin/env python3
"""Maakt tizen/js/strings.js: alleen de teksten die de Tizen-app gebruikt, uit app/src/main/assets/Translations.json
(dezelfde vertalingen als de andere Bingr-apps) plus tools/strings-extra.json voor teksten die alleen op de tv bestaan.
Gebruik: python3 tizen/tools/make_strings.py [--check]  (--check: alleen melden welke Nederlandse teksten ontbreken)
build 97: python3 tizen/tools/make_strings.py --split <pakketmap>  schrijft per taal <pakketmap>/js/strings/<taal>.js en
<pakketmap>/js/boot.js (laadt bij het starten alleen de gekozen taal; Engels = de sleutels zelf). Voor tools/package.sh."""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
TIZEN = os.path.dirname(HERE)
REPO = os.path.dirname(TIZEN)
SRC = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'Translations.json')
EXTRA = os.path.join(HERE, 'strings-extra.json')
OUT = os.path.join(TIZEN, 'js', 'strings.js')

# teksten die via een variabele worden opgevraagd (menu, kleuren)
DYNAMIC = ['Search', 'Home', 'Live TV', 'Movies', 'Series', 'My List', 'Settings',
           'Red', 'Blue', 'Green', 'Purple', 'Orange', 'Pink', 'Gold', 'Automatic', 'OK',
           # build 85: Categorieën (genre-namen, soorten en sorteringen gaan via een variabele)
           'Categories', 'All', 'Popular', 'Newest', 'A–Z', 'Rating',
           'Action', 'Adventure', 'Comedy', 'Drama', 'Thriller', 'Crime', 'Science Fiction', 'Fantasy', 'Horror',
           'Mystery', 'Romance', 'Animation', 'Family', 'Kids', 'Documentary', 'History', 'War', 'Western', 'Music',
           'Reality', 'Soap',
           # build 102: sorteren (js/parity.js, via een variabele)
           'Default', 'Z–A', 'Highest rated',
           # build 146: tabs van Instellingen, support (status, groep, categorie, fouten) en Servers laden bij openen
           'General', 'Playback', 'Language and subtitles', 'Servers', 'Profiles and parental lock', 'Account', 'Connections',
           'Support', 'Diagnostics', 'About', 'Open', 'In progress', 'Closed', 'New', 'Awaiting your reply', 'Resolved',
           'Account and sign-in', 'Playback and streams', 'Apps and installing', 'Website and web player', 'Other',
           'Enter a subject (at least 3 characters).', 'The subject is too long (max. 120 characters).',
           'Describe your question in at least 10 characters.', 'Your message is too long (max. 5000 characters).',
           'Your message contains too many links (max. 6).', 'Too many requests. Wait a moment and try again.',
           'This ticket is closed. Create a new ticket.', "This ticket wasn't found.", 'The diagnostics are too large to send.',
           'Something went wrong. Please try again.', 'Ask', 'Always', 'Never, manually only',
           # build 147: samenvatting van de start en soort verbinding (js/build147.js, via een variabele)
           'Start: good', 'Start: slow', 'Start: stuck', 'Wi-Fi', 'Mobile', 'Ethernet', 'No connection', 'Unknown',
           # build 147: platformlaag (B.platform.texts in js/core.js, via een variabele)
           'This kind of server is not supported on Samsung TV yet.',
           'A Samsung TV has one full video player: the tile with sound uses it, the other tiles play muted where the TV can.', 'LG TV']

PAT = [re.compile(r'\bL\(\s*"((?:[^"\\]|\\.)*)"'), re.compile(r"\bL\(\s*'((?:[^'\\]|\\.)*)'")]


def keys():
    found = set(DYNAMIC)
    for name in sorted(os.listdir(os.path.join(TIZEN, 'js'))):
        if not name.endswith('.js') or name == 'strings.js':
            continue
        text = open(os.path.join(TIZEN, 'js', name), encoding='utf-8').read()
        for p in PAT:
            for m in p.finditer(text):
                found.add(json.loads('"' + m.group(1).replace("\\'", "'") + '"'))
    return sorted(found)


def main():
    tr = json.load(open(SRC, encoding='utf-8'))
    extra = json.load(open(EXTRA, encoding='utf-8')) if os.path.exists(EXTRA) else {}
    ks = keys()
    langs = sorted(tr.keys())
    out = {}
    missing = []
    for lang in langs:
        table = {}
        for k in ks:
            v = (extra.get(k) or {}).get(lang) or tr[lang].get(k)
            if v:
                table[k] = v
            elif lang == 'nl':
                missing.append(k)
        out[lang] = table
    if '--check' in sys.argv:
        for k in missing:
            print('ontbreekt (nl):', k)
        return
    if missing:
        print('Let op, zonder Nederlandse vertaling:', ', '.join(missing), file=sys.stderr)
    if '--split' in sys.argv:
        split(out, sys.argv[sys.argv.index('--split') + 1])
        return
    body = json.dumps(out, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write('/* Gemaakt door tizen/tools/make_strings.py uit Translations.json; niet met de hand wijzigen. */\n')
        f.write('window.BINGR_STRINGS = ' + body + ';\n')
    print('strings.js: %d teksten, %d talen' % (len(ks), len(langs)))


BOOT = """/* Gemaakt door tizen/tools/make_strings.py --split; niet met de hand wijzigen. */
(function () {
  'use strict';
  // build 97: alleen de teksten van de gekozen taal laden (bingr.lang, anders de taal van de tv); Engels heeft geen bestand
  var langs = %s;
  window.BINGR_LANGS = langs;
  var pick = null;
  try { var raw = window.localStorage.getItem('bingr.lang'); if (raw) pick = JSON.parse(raw); } catch (e) { pick = null; }
  if (!pick) {
    var sys = String(navigator.language || 'en').toLowerCase();
    pick = sys.indexOf('zh') === 0 ? 'zh-Hans' : sys.split(/[-_]/)[0];
  }
  if (langs.indexOf(pick) >= 0) document.write('<script src="js/strings/' + pick + '.js"><\\/script>');
})();
"""


def split(out, stage):
    d = os.path.join(stage, 'js', 'strings')
    os.makedirs(d, exist_ok=True)
    for lang, table in sorted(out.items()):
        body = json.dumps(table, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
        with open(os.path.join(d, lang + '.js'), 'w', encoding='utf-8') as f:
            f.write('(window.BINGR_STRINGS = window.BINGR_STRINGS || {})[%s] = %s;\n' % (json.dumps(lang), body))
    with open(os.path.join(stage, 'js', 'boot.js'), 'w', encoding='utf-8') as f:
        f.write(BOOT % json.dumps(sorted(out.keys())))
    print('strings: %d talen los in %s' % (len(out), d))


if __name__ == '__main__':
    main()
