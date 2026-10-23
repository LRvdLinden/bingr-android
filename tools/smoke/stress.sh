#!/usr/bin/env bash
# Build 141: rooktest met grote nepgegevens in de emulator (workflow smoke.yml, binnen android-emulator-runner).
#
#   tools/smoke/stress.sh <set> <uitvoermap> <apk>
#
# 1. installeren; de app start met --ez bingr.stress.prepare true en zet 3 servers × (30.000 films, 20.000 series,
#    5.000 zenders met 4 dagen gids) en 3 profielen klaar (core/StressMode.kt), daarna stopt hij;
# 2. per ronde: de app koud starten, zelf een profiel laten kiezen (zoals een tik in "Wie kijkt er?"), 60 s lang
#    elke 1,5 s een toets sturen (zoals de afstandsbediening) en controleren: het proces leeft, geen ANR, geen crash
#    (ook geen OutOfMemoryError), en Home was binnen de tijd bruikbaar;
# 3. samenvatting (tijden, main thread, geheugen) in de uitvoermap en in de stapsamenvatting.
# Een fout laat de workflow mislukken. Geen netwerk en geen account in de app (BuildConfig.STRESS).
set -uo pipefail
SET=$1; OUT=$2; APK=$3
PKG=nl.lrvdlinden.bingr
ACT="$PKG/.MainActivity"
HOME_READY_MAX_MS=${HOME_READY_MAX_MS:-15000}
MAIN_BLOCK_MAX_MS=${MAIN_BLOCK_MAX_MS:-4000}
mkdir -p "$OUT"
exec > >(tee -a "$OUT/_log.txt") 2>&1
fail=0
err() { echo "::error::$*"; fail=1; }

echo "start $(date -u +%H:%M:%S)"
timeout 300 adb wait-for-device
for _ in $(seq 1 150); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
echo "apparaat: $(adb shell getprop ro.product.model | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), heap $(adb shell getprop dalvik.vm.heapsize | tr -d '\r') / largeHeap $(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r')"
adb shell settings put global anr_show_background 1 || true
adb shell settings put global window_animation_scale 0 || true
sleep 30   # systeem eerst laten bijkomen na het opstarten
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true

timeout 300 adb install -r -g "$APK" || { echo "::error::installeren mislukt"; exit 1; }

# 1. nepgegevens klaarzetten
adb logcat -c || true
adb shell am start -W -n "$ACT" --ez bingr.stress.prepare true >/dev/null
ready=0
for _ in $(seq 1 300); do
  if adb shell run-as "$PKG" ls files/stress-ready >/dev/null 2>&1; then ready=1; break; fi
  sleep 2
done
adb logcat -d -s BingrStress:I > "$OUT/prepare.txt" 2>/dev/null || true
cat "$OUT/prepare.txt" | grep BINGR_STRESS || true
[ $ready -eq 1 ] || { err "nepgegevens niet klaargezet binnen 10 min"; exit 1; }
CACHE=$(adb shell run-as "$PKG" du -sh files/servercache 2>/dev/null | tr -d '\r' | awk '{print $1}')
PREP=$(grep -o "prepare done ms=[0-9]*" "$OUT/prepare.txt" | head -1)
echo "::notice title=Rooktest $SET::$(adb shell getprop ro.product.model | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), heap $(adb shell getprop dalvik.vm.heapsize | tr -d '\r'); nepcache ${CACHE:-?} (3 servers); $PREP"
sleep 3
adb shell am force-stop "$PKG"

# 2. rondes: profiel kiezen (en wisselen) met grote catalogi
round() {
  local name=$1 profile=$2 switch=$3
  local log="$OUT/round-$name.txt"
  echo "=== ronde $name: profiel $profile${switch:+, daarna $switch} ==="
  adb shell am force-stop "$PKG"
  adb logcat -c || true
  local extra=(--ei bingr.stress.profile "$profile")
  [ -n "$switch" ] && extra+=(--ei bingr.stress.switch "$switch")
  adb shell am start -W -n "$ACT" "${extra[@]}" >/dev/null
  local keys=(KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_DOWN KEYCODE_DPAD_LEFT KEYCODE_DPAD_DOWN KEYCODE_DPAD_UP)
  local t=0 i=0 died=0
  while [ $t -lt 60 ]; do
    adb shell input keyevent "${keys[$((i % ${#keys[@]}))]}" >/dev/null 2>&1 || true
    i=$((i + 1))
    sleep 1.5
    t=$(( t + 2 ))
    if [ -z "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ]; then died=1; break; fi
  done
  adb logcat -d > "$log" 2>/dev/null || true
  adb shell dumpsys meminfo "$PKG" > "$OUT/meminfo-$name.txt" 2>/dev/null || true
  grep "BINGR_STRESS" "$log" | sed 's/.*BINGR_STRESS /  /' | grep -v "^  mem " || true
  grep "BINGR_STRESS mem" "$log" | tail -3 | sed 's/.*BINGR_STRESS /  /' || true
  [ $died -eq 1 ] && err "ronde $name: app is gestopt (proces weg) binnen 60 s"
  if grep -E "ANR in $PKG|Application Not Responding: $PKG|am_anr.*$PKG" "$log" | grep -v BINGR_STRESS | head -3 | grep -q .; then
    err "ronde $name: ANR"; grep -E -A6 "ANR in $PKG" "$log" | head -20
  fi
  if grep -E "FATAL EXCEPTION|OutOfMemoryError|Process $PKG \(pid [0-9]+\) has died" "$log" | head -3 | grep -q .; then
    err "ronde $name: crash"; grep -E -A25 "FATAL EXCEPTION" "$log" | head -40
    grep -E "OutOfMemoryError" "$log" | head -5
  fi
  local n=0
  while read -r line; do
    n=$((n + 1))
    case "$line" in
      *TIMEOUT*) err "ronde $name: Home niet bruikbaar binnen 120 s ($line)" ;;
      *) ms=$(echo "$line" | sed -E 's/.* ms=([0-9]+).*/\1/'); [ "$ms" -le "$HOME_READY_MAX_MS" ] || err "ronde $name: Home pas na $ms ms bruikbaar (max $HOME_READY_MAX_MS)" ;;
    esac
  done < <(grep "BINGR_STRESS home_ready" "$log" | sed 's/.*BINGR_STRESS //')
  local want=1; [ -n "$switch" ] && want=2
  [ $n -ge $want ] || err "ronde $name: Home niet bruikbaar geworden ($n van $want keer)"
  local block
  block=$(grep "BINGR_STRESS mem" "$log" | sed -E 's/.*main_max_total_ms=([0-9]+).*/\1/' | sort -n | tail -1)
  [ -n "$block" ] && [ "$block" -gt "$MAIN_BLOCK_MAX_MS" ] && err "ronde $name: main thread zat $block ms vast (max $MAIN_BLOCK_MAX_MS)"
  # samenvatting als annotatie (te zien bij de run, ook zonder de logbestanden)
  local hr mem
  hr=$(grep "BINGR_STRESS home_ready" "$log" | sed -E 's/.*profile=([^ ]+) ms=([0-9]+|TIMEOUT).*/\1 \2 ms/' | tr '\n' ',' | sed 's/,$//')
  mem=$(grep "BINGR_STRESS mem" "$log" | tail -1 | sed -E 's/.*used_mb=([0-9]+) max_mb=([0-9]+) pss_mb=(-?[0-9]+) main_max_ms=[0-9]+ main_max_total_ms=([0-9]+).*/heap \1 van \2 MB, PSS \3 MB, main thread max \4 ms/')
  echo "::notice title=Rooktest $SET $name::Home bruikbaar: ${hr:-?}; $mem; proces leeft, geen ANR/crash: $([ $fail -eq 0 ] && echo ja || echo NEE)"
  {
    echo "### $SET · ronde $name"
    echo '```'
    grep "BINGR_STRESS" "$log" | sed 's/.*BINGR_STRESS //' | grep -E "pick|switch|home_ready|main_blocked" || true
    grep "BINGR_STRESS mem" "$log" | sed 's/.*BINGR_STRESS //' | tail -1
    grep -E "TOTAL PSS|TOTAL:" "$OUT/meminfo-$name.txt" | head -1 | sed 's/^ */meminfo /'
    echo '```'
  } >> "$OUT/summary.md"
}

round main 0 ""
round kids 1 ""
round switch 0 1

cat "$OUT/summary.md"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$OUT/summary.md" >> "$GITHUB_STEP_SUMMARY"
echo "einde $(date -u +%H:%M:%S), fouten: $fail"
exit $fail
