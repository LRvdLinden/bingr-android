#!/usr/bin/env python3
"""Maakt tizen/js/strings.js: alleen de teksten die de Tizen-app gebruikt, uit app/src/main/assets/Translations.json
(dezelfde vertalingen als de andere Bingr-apps) plus tools/strings-extra.json voor teksten die alleen op de tv bestaan.
Gebruik: python3 tizen/tools/make_strings.py [--check]  (--check: alleen melden welke Nederlandse teksten ontbreken)"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
TIZEN = os.path.dirname(HERE)
REPO = os.path.dirname(TIZEN)
SRC = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'Translations.json')
EXTRA = os.path.join(HERE, 'strings-extra.json')
OUT = os.path.join(TIZEN, 'js', 'strings.js')

# teksten die via een variabele worden opgevraagd (menu, kleuren)
DYNAMIC = ['Search', 'Home', 'Live TV', 'Movies', 'Series', 'My List', 'Settings',
           'Red', 'Blue', 'Green', 'Purple', 'Orange', 'Pink', 'Automatic', 'OK',
           # build 85: Categorieën (genre-namen, soorten en sorteringen gaan via een variabele)
           'Categories', 'All', 'Popular', 'Newest', 'A–Z', 'Rating',
           'Action', 'Adventure', 'Comedy', 'Drama', 'Thriller', 'Crime', 'Science Fiction', 'Fantasy', 'Horror',
           'Mystery', 'Romance', 'Animation', 'Family', 'Kids', 'Documentary', 'History', 'War', 'Western', 'Music',
           'Reality', 'Soap']

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
    body = json.dumps(out, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write('/* Gemaakt door tizen/tools/make_strings.py uit Translations.json; niet met de hand wijzigen. */\n')
        f.write('window.BINGR_STRINGS = ' + body + ';\n')
    print('strings.js: %d teksten, %d talen' % (len(ks), len(langs)))


if __name__ == '__main__':
    main()
