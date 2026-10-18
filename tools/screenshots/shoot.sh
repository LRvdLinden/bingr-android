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

adb wait-for-device
for _ in $(seq 1 150); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
adb root >/dev/null 2>&1 && sleep 3 && adb wait-for-device
echo "apparaat: $(adb shell getprop ro.product.model | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r')"

# tijdzone zo dat het in de app ongeveer 20 uur is (avondprogramma's in de gids), zoals bij de Apple-screenshots
if [ -n "${TZ_APP:-}" ]; then
  adb shell settings put global auto_time_zone 0 >/dev/null 2>&1 || true
  adb shell service call alarm 3 s16 "$TZ_APP" >/dev/null 2>&1 || true
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

adb install -r -g "$APK" || { echo "::error::installeren mislukt"; exit 1; }

case "$SET" in
  phone) SCREENS="home detail live guide movies picker categories" ;;
  *)     SCREENS="home detail live guide movies picker categories multiview" ;;
esac

first=1
for s in $SCREENS; do
  adb shell am force-stop "$PKG"
  sleep 2
  adb shell am start -W -n "$PKG/.MainActivity" --ez bingr.screenshots true --es bingr.screen "$s" >/dev/null
  if [ $first = 1 ]; then sleep 25; first=0; else sleep 15; fi
  # TV: de focus staat bovenin; voor Home en Films één stap omlaag naar de knoppen van het billboard
  if [ "$SET" = "tv" ] && { [ "$s" = "home" ] || [ "$s" = "movies" ]; }; then
    adb shell input keyevent KEYCODE_DPAD_DOWN; sleep 3
  fi
  adb exec-out screencap -p > "$OUT/$s.png"
  echo "$s: $(python3 -c 'import sys,struct; d=open(sys.argv[1],"rb").read(24); print("%dx%d" % struct.unpack(">II", d[16:24]))' "$OUT/$s.png" 2>/dev/null)"
done
adb logcat -d -t 400 '*:E' > "$OUT/_logcat-errors.txt" 2>/dev/null || true
adb shell am force-stop "$PKG"
exit 0
