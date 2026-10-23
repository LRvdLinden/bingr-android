#!/usr/bin/env bash
# Build 141: rooktest van de ECHTE release-APK (R8, release-sleutel) in de emulator, binnen android-emulator-runner
# (workflow Build, vóór het aanleveren). Zo komt een APK die niet installeert of meteen crasht nooit meer in het beheer
# of in de openbare testversie.
#
#   tools/smoke/release.sh <set> <uitvoermap> <nieuwe.apk> [mapping.txt] [oude.apk]
#
# 1. update: is er een oude APK (de openbare testversie of een vorige build, zelfde sleutel), dan eerst die installeren en
#    starten, daarna de nieuwe er met `adb install -r` overheen (zoals een gebruiker die bijwerkt) en starten;
# 2. vers: de app verwijderen, de nieuwe APK installeren en starten.
# Per start: het proces moet na START_WAIT_S (standaard 30) seconden nog leven, MainActivity moet getoond zijn en logcat
# mag geen FATAL EXCEPTION / crash van de app bevatten. Een installatiefout geeft de exacte code (INSTALL_FAILED_*,
# INSTALL_PARSE_FAILED_*). Een crash komt als ::error:: met de stacktrace, met mapping.txt teruggezet naar de echte namen
# (R8 retrace). Adressen (http…) worden uit de meldingen gehaald; de app heeft in de emulator geen account en geen servers.
set -uo pipefail
SET=$1; OUT=$2; APK=$3; MAP=${4:-}; OLD=${5:-}
PKG=nl.lrvdlinden.bingr
WAIT=${START_WAIT_S:-30}
mkdir -p "$OUT"
exec > >(tee -a "$OUT/_log.txt") 2>&1
fail=0
err() { echo "::error title=Rooktest release ($SET)::$*"; fail=1; }
clean() { sed -E 's#[a-zA-Z][a-zA-Z0-9+.-]*://[^ )"]*#<adres>#g'; }

# R8 retrace: uit de cmdline-tools van de runner, anders de R8-jar uit de Gradle-cache
retrace() {
  local in=$1 rt
  if [ -z "$MAP" ] || [ ! -s "$MAP" ]; then cat "$in"; return; fi
  rt=$(ls -d "${ANDROID_HOME:-/nonexistent}"/cmdline-tools/*/bin/retrace 2>/dev/null | sort -V | tail -1)
  if [ -n "$rt" ] && "$rt" "$MAP" "$in" 2>/dev/null; then return; fi
  local r8; r8=$(find ~/.gradle/caches -name 'r8-*.jar' 2>/dev/null | sort -V | tail -1)
  if [ -n "$r8" ] && java -cp "$r8" com.android.tools.r8.retrace.Retrace "$MAP" "$in" 2>/dev/null; then return; fi
  cat "$in"
}

install_apk() {   # <label> <apk> [-r]; zet INSTALL_CODE
  local label=$1 apk=$2 flag=${3:-} res
  res=$(timeout 300 adb install $flag "$apk" 2>&1 | tr -d '\r')
  echo "$res" | tail -3 > "$OUT/install-$label.txt"
  if echo "$res" | grep -q '^Success'; then INSTALL_CODE=Success; return 0; fi
  INSTALL_CODE=$(echo "$res" | grep -oE 'INSTALL_[A-Z_]+|Failure \[[^]]*\]' | head -1)
  INSTALL_CODE=${INSTALL_CODE:-$(echo "$res" | tail -1 | clean | cut -c1-200)}
  return 1
}

start_app() {     # <label>: starten, WAIT s volgen, crash/ANR melden
  local label=$1 log="$OUT/logcat-$label.txt" crash="$OUT/crash-$label.txt" died=0 t=0
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  adb logcat -c >/dev/null 2>&1 || true
  adb logcat -b crash -c >/dev/null 2>&1 || true
  adb shell am start -W -n "$PKG/$PKG.MainActivity" > "$OUT/start-$label.txt" 2>&1 || true
  while [ $t -lt "$WAIT" ]; do
    sleep 2; t=$((t + 2))
    if [ -z "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ]; then died=1; break; fi
  done
  sleep 1
  adb logcat -d > "$log" 2>/dev/null || true
  adb logcat -d -b crash > "$crash" 2>/dev/null || true
  adb exec-out screencap -p > "$OUT/screen-$label.png" 2>/dev/null || true
  # stacktrace van de app (FATAL EXCEPTION in ons proces) uit de crash-buffer, anders uit het gewone log
  local trace="$OUT/trace-$label.txt"
  { grep -h -E "AndroidRuntime" "$crash" "$log" 2>/dev/null || true; } | awk '!seen[$0]++' \
    | sed -E 's/^.*AndroidRuntime: ?//' | grep -v '^$' > "$trace.raw" || true
  local fatal=0
  grep -q "FATAL EXCEPTION" "$trace.raw" && grep -q "Process: $PKG" "$trace.raw" && fatal=1
  grep -qE "Process $PKG \(pid [0-9]+\) has died|am_crash.*$PKG" "$log" && fatal=1
  local displayed; displayed=$(grep -m1 -oE "Displayed $PKG/[^ ]+: \+[0-9a-z]+" "$log" || true)
  if [ $fatal -eq 1 ] || [ $died -eq 1 ]; then
    retrace "$trace.raw" | clean > "$trace"
    err "$label: app gestopt binnen $WAIT s (proces weg: $([ $died -eq 1 ] && echo ja || echo nee))"
    # de eerste regels van de stacktrace (gedeobfusceerd) als ::error::, de rest staat in $trace
    grep -E -A28 "FATAL EXCEPTION" "$trace" | head -40 | while IFS= read -r l; do echo "::error title=Crash $SET $label::${l:0:300}"; done
    [ -s "$trace" ] || echo "::error title=Crash $SET $label::geen stacktrace in logcat (zie logcat-$label.txt)"
    return 1
  fi
  if grep -qE "ANR in $PKG" "$log"; then err "$label: ANR binnen $WAIT s"; return 1; fi
  [ -n "$displayed" ] || echo "::warning title=Rooktest release ($SET)::$label: geen 'Displayed $PKG/…' in logcat (proces leeft wel)"
  echo "::notice title=Rooktest release ($SET)::$label: geïnstalleerd en gestart, na $WAIT s actief, geen crash (${displayed:-Displayed ?})"
  return 0
}

echo "start $(date -u +%H:%M:%S)"
timeout 300 adb wait-for-device
for _ in $(seq 1 150); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
ABIS=$(adb shell getprop ro.product.cpu.abilist | tr -d '\r')
echo "apparaat: $(adb shell getprop ro.product.model | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), ABI's $ABIS"
sleep 20
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true

# 1. update over de oude APK
if [ -n "$OLD" ] && [ -s "$OLD" ]; then
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  if install_apk old "$OLD"; then
    start_app oud >/dev/null 2>&1 || echo "::warning title=Rooktest release ($SET)::de oude APK start zelf niet goed (zie logcat-oud.txt)"
    if install_apk update "$APK" -r; then
      echo "::notice title=Rooktest release ($SET)::update over de oude APK geïnstalleerd (adb install -r: Success)"
      start_app update || true
    else
      err "update over de oude APK NIET geïnstalleerd: $INSTALL_CODE"
    fi
  else
    echo "::warning title=Rooktest release ($SET)::oude APK niet te installeren ($INSTALL_CODE); alleen de verse installatie getest"
  fi
fi

# 2. vers installeren
adb uninstall "$PKG" >/dev/null 2>&1 || true
if install_apk vers "$APK"; then
  echo "::notice title=Rooktest release ($SET)::verse installatie: Success"
  start_app vers || true
else
  err "verse installatie mislukt: $INSTALL_CODE"
fi

echo "einde $(date -u +%H:%M:%S), fouten: $fail"
exit $fail
