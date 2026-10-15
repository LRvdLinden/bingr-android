#!/usr/bin/env bash
# Bouwt de Samsung Tizen-app (.wgt) uit tizen/.
#   tizen/tools/package.sh <uitvoermap>
# Omgeving:
#   TIZEN_CLI            pad naar de tizen-CLI (leeg = geen ondertekening, alleen een zip-wgt)
#   TIZEN_PROFILE        naam van het security-profiel om mee te ondertekenen
# Uitvoer: <uitvoermap>/Bingr-<V>-build<B>.wgt (of -unsigned.wgt als ondertekenen niet lukt)
# en <uitvoermap>/signing.txt met hoe hij ondertekend is.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TIZEN="$(dirname "$HERE")"
REPO="$(dirname "$TIZEN")"
OUT="${1:-$REPO/build/tizen}"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

V=$(grep '^BINGR_VERSION=' "$REPO/gradle.properties" | cut -d= -f2)
B=$(grep '^BINGR_BUILD=' "$REPO/gradle.properties" | cut -d= -f2)
# Tizen-versie: 1.0.<build> (laatste deel max. 65535)
TV="${V%.*}.$B"
echo "Bingr $V build $B -> Tizen-versie $TV"

# alleen wat in de app hoort (geen tests of tools)
STAGE="$OUT/app"
rm -rf "$STAGE"
mkdir -p "$STAGE"
python3 "$HERE/make_strings.py"
cp "$TIZEN/config.xml" "$TIZEN/index.html" "$TIZEN/icon.png" "$STAGE/"
cp -R "$TIZEN/css" "$TIZEN/js" "$TIZEN/img" "$STAGE/"
python3 - "$STAGE/config.xml" "$TV" <<'PY'
import re, sys
p, v = sys.argv[1], sys.argv[2]
s = open(p, encoding='utf-8').read()
s, n = re.subn(r'(<widget\b[^>]*?\sversion=")[^"]*"', lambda m: m.group(1) + v + '"', s, count=1, flags=re.S)
assert n == 1, 'versie niet gevonden in config.xml'
open(p, 'w', encoding='utf-8').write(s)
PY
sed -i.bak -e "s/__BINGR_BUILD__/$B/" -e "s/__BINGR_VERSION__/$V/" "$STAGE/js/core.js" && rm -f "$STAGE/js/core.js.bak"
grep -o 'version="[^"]*"' "$STAGE/config.xml" | head -2

# build 97: één verkleinde bundel (js/bingr.min.js) in plaats van ~20 losse scripts, teksten per taal (js/strings/<taal>.js,
# alleen de gekozen taal wordt geladen) en qrcode.js pas bij een QR-code. De bronbestanden in tizen/ blijven zoals ze zijn.
# Zonder node: het pakket blijft zoals vroeger (losse scripts, strings.js met alle talen).
if command -v node >/dev/null 2>&1; then
  python3 "$HERE/make_strings.py" --split "$STAGE"
  node "$HERE/bundle.js" "$STAGE"
else
  echo "let op: node niet gevonden; scripts worden niet gebundeld"
fi

NAME="Bingr-$V-build$B.wgt"
rm -f "$OUT"/*.wgt "$OUT/signing.txt"

signed=""
if [ -n "${TIZEN_CLI:-}" ] && [ -n "${TIZEN_PROFILE:-}" ]; then
  # tizen package zet de .wgt in de projectmap
  if "$TIZEN_CLI" package -t wgt -s "$TIZEN_PROFILE" -- "$STAGE"; then
    WGT=$(ls "$STAGE"/*.wgt 2>/dev/null | head -1 || true)
    if [ -n "$WGT" ]; then
      mv "$WGT" "$OUT/$NAME"
      signed="yes"
    fi
  fi
fi
if [ -z "$signed" ]; then
  # zonder tizen-CLI: gewone zip (niet ondertekend; installeert niet op een tv, wel bruikbaar om te testen)
  NAME="Bingr-$V-build$B-unsigned.wgt"
  (cd "$STAGE" && rm -f ./*.wgt && zip -qr -X "$OUT/$NAME" .)
  echo "unsigned" > "$OUT/signing.txt"
fi
echo "$OUT/$NAME"
ls -la "$OUT"
