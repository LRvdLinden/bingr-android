#!/usr/bin/env bash
# Build 141: controle van een APK-bestand zonder apparaat (alleen openbare gegevens in het log):
#   tools/smoke/apk_info.sh <label> <bestand.apk> [verwachte SHA-256 van het certificaat, AA:BB:…]
# Is het een zip/APK (geen HTML of versleuteld bestand), klopt de handtekening (apksigner verify, v1/v2/v3), welk
# certificaat (SHA-256), package, versionCode, min-/targetSdk en native ABI's. Exit 1 als de APK niet geldig ondertekend
# is of het certificaat niet het verwachte is.
set -uo pipefail
L=$1; F=$2; WANT=${3:-}
BT=$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
[ -s "$F" ] || { echo "::error title=APK $L::bestand ontbreekt of is leeg"; exit 1; }
SIZE=$(stat -c %s "$F"); SHA=$(sha256sum "$F" | cut -c1-16)
MAGIC=$(head -c 4 "$F" | od -An -tx1 | tr -d ' \n')
if [ "$MAGIC" != "504b0304" ]; then
  echo "::error title=APK $L::geen APK (zip) maar '$(head -c 40 "$F" | tr -cd 'A-Za-z0-9 <>!/=._-' | cut -c1-40)…' ($SIZE bytes)"; exit 1
fi
V=$("$BT/apksigner" verify -v --print-certs "$F" 2>&1); rc=$?
CERT=$(echo "$V" | grep -m1 'certificate SHA-256 digest:' | awk '{print $NF}' | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//')
SCH=$(echo "$V" | grep -E 'Verified using v[0-9.]+ scheme' | sed -E 's/Verified using (v[0-9.]+) scheme.*: /\1=/' | tr '\n' ' ')
B=$("$BT/aapt2" dump badging "$F" 2>/dev/null)
PKG=$(echo "$B" | grep -m1 '^package:' | sed -E "s/.*name='([^']*)'.*versionCode='([^']*)'.*versionName='([^']*)'.*/\1 versionCode \2 (\3)/")
SDK="minSdk $(echo "$B" | grep -m1 -oE "^(sdkVersion|minSdkVersion):'[0-9]+'" | grep -oE '[0-9]+'), targetSdk $(echo "$B" | grep -m1 -oE "^targetSdkVersion:'[0-9]+'" | grep -oE '[0-9]+')"
ABI=$(echo "$B" | grep -m1 '^native-code:' | sed "s/native-code: //; s/'//g")
echo "::notice title=APK $L::$PKG; $SDK; ABI's ${ABI:-geen}; $SIZE bytes (sha256 $SHA…); handtekening: $([ $rc -eq 0 ] && echo geldig || echo ONGELDIG) $SCH; certificaat ${CERT:-?}"
[ $rc -eq 0 ] || { echo "$V" | grep -iE 'error|warning' | head -5 | while IFS= read -r l; do echo "::error title=APK $L::${l:0:300}"; done; exit 1; }
if [ -n "$WANT" ] && [ "$CERT" != "$WANT" ]; then echo "::error title=APK $L::ander certificaat dan de Bingr-releasesleutel"; exit 1; fi
exit 0
