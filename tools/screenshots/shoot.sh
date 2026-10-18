#!/usr/bin/env bash
# Screenshots van de Android-app in de screenshotmodus (workflow playstore-screenshots.yml, binnen de emulator).
#
#   tools/screenshots/shoot.sh <set> <uitvoermap> <apk>
#
# set: phone (1080x1920, 9:16), tablet7 (1920x1080, sw600dp), tablet10 (2560x1440, sw720dp), tv (Android TV 1920x1080).
# Per scherm: app stoppen, starten met --ez bingr.screenshots true --es bingr.screen <scherm>, wachten, screencap.
# De modus werkt alleen in de screenshot-build (BuildConfig.SCREENSHOTS); geen netwerk, geen account in de app.
set -uo pipefail
SET=$1; OUT=$2; APK=$3
PKG=nl.lrvdlinden.bingr
mkdir -p "$OUT"
exec > >(tee -a "$OUT/_log.txt") 2>&1

echo "start $(date -u +%H:%M:%S)"
timeout 300 adb wait-for-device
for _ in $(seq 1 150); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
timeout 30 adb root >/dev/null 2>&1 && sleep 3 && timeout 60 adb wait-for-device
echo "apparaat: $(adb shell getprop ro.product.model | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"

# tijdzone zo dat het in de app ongeveer 20 uur is (avondprogramma's in de gids), zoals bij de Apple-screenshots
if [ -n "${TZ_APP:-}" ]; then
  adb shell settings put global auto_time_zone 0 >/dev/null 2>&1 || true
  timeout 20 adb shell service call alarm 3 s16 "$TZ_APP" >/dev/null 2>&1 || true
  adb shell setprop persist.sys.timezone "$TZ_APP" >/dev/null 2>&1 || true
  echo "tijdzone: $(adb shell getprop persist.sys.timezone | tr -d '\r'), tijd: $(adb shell date +%H:%M | tr -d '\r')"
fi

# schermmaat en dichtheid per set, draaien uit
adb shell settings put system accelerometer_rotation 0 || true
adb shell settings put system user_rotation 0 || true
case "$SET" in
  phone)    adb shell wm size 1080x1920; adb shell wm density 420 ;;
  tablet7)  adb shell wm size 1920x1080; adb shell wm density 288 ;;
  tablet10) adb shell wm size 2560x1440; adb shell wm density 320 ;;
  tv)       ;;
esac
adb shell wm size; adb shell wm density

# schone statusbalk (demomodus): 9:41, volle batterij, wifi, geen meldingen
if [ "$SET" != "tv" ]; then
  adb shell settings put global sysui_demo_allowed 1
  demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
  demo enter
  demo clock -e hhmm 0941
  demo battery -e level 100 -e plugged false -e powersave false
  demo network -e wifi show -e level 4 -e fully true
  demo network -e mobile show -e datatype none -e level 4 -e fully true
  demo notifications -e visible false
fi

# geen systeemmeldingen over de screenshots: uitleg bij verborgen balken al "gezien", geen ANR-vensters van andere apps
adb shell settings put secure immersive_mode_confirmations confirmed || true
adb shell settings put global anr_show_background 0 || true
adb shell settings put secure show_first_crash_dialog 0 || true
adb shell settings put global show_first_crash_dialog 0 || true
sleep 45   # launcher en systeem eerst laten bijkomen na het opstarten
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true

timeout 300 adb install -r -g "$APK" || { echo "::error::installeren mislukt"; exit 1; }

case "$SET" in
  phone)   SCREENS="home detail live guide movies picker categories" ;;
  tablet7) SCREENS="home detail live guide movies picker multiview" ;;   # Categorieën laat de 7"-emulator vastlopen
  *)       SCREENS="home detail live guide movies picker multiview categories" ;;
esac
W=1080; H=1920
case "$SET" in tablet7) W=1920; H=1080 ;; tablet10) W=2560; H=1440 ;; esac
# een stukje omlaag schuiven (vanaf y1 naar y2, als fractie van de hoogte)
scroll() { timeout 15 adb shell input swipe $((W / 2)) $((H * $1 / 100)) $((W / 2)) $((H * $2 / 100)) 900; sleep 4; }

first=1
for s in $SCREENS; do
  # emulator weggevallen (komt voor bij zware schermen): de rest overslaan, wat er is blijft bewaard
  [ "$(timeout 15 adb get-state 2>/dev/null)" = "device" ] || { echo "::warning::emulator weg vóór $s"; break; }
  timeout 30 adb shell am force-stop "$PKG"
  timeout 15 adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
  sleep 2
  timeout 60 adb shell am start -W -n "$PKG/.MainActivity" --ez bingr.screenshots true --es bingr.screen "$s" >/dev/null
  if [ $first = 1 ]; then sleep 25; first=0; else sleep 15; fi
  # Films: de rijen (Top 10, Mijn lijst) in beeld; tablets liggend: ook Home en Detail iets omlaag (anders één groot beeld)
  if [ "$SET" = "tv" ]; then
    if [ "$s" = "movies" ]; then
      timeout 15 adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 2
      timeout 15 adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 4
    fi
  elif [ "$SET" = "phone" ]; then
    [ "$s" = "movies" ] && scroll 85 20
  else
    # liggende tablet: de billboardkaart is ~86% van de breedte en daardoor veel hoger dan het scherm; tot de rijen
    case "$s" in
      home)   scroll 85 15; scroll 85 15; scroll 85 30 ;;
      movies) scroll 85 15; scroll 85 15; scroll 85 15; scroll 85 40 ;;
      detail) scroll 80 35 ;;
    esac
  fi
  if [ "$SET" != "tv" ]; then demo clock -e hhmm 0941; sleep 1; fi
  timeout 60 adb exec-out screencap -p > "$OUT/$s.png"
  echo "$s: $(python3 -c 'import sys,struct; d=open(sys.argv[1],"rb").read(24); print("%dx%d" % struct.unpack(">II", d[16:24]))' "$OUT/$s.png" 2>/dev/null)"
done
timeout 30 adb logcat -d -t 400 '*:E' > "$OUT/_logcat-errors.txt" 2>/dev/null || true
timeout 30 adb shell am force-stop "$PKG" || true
exit 0
