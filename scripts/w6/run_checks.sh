#!/usr/bin/env bash
# Run all checks on a bench phone with no screen taps, wait for the end, pull the report folder.
#
#   scripts/w6/run_checks.sh -s <adb serial> [-w auto|none|<package>] [-t] [-r] [-o <out dir>] [-k] [-m <max seconds>]
#
#   -s  adb serial (required: never run this against a phone you did not name)
#   -w  the app the 15 s watch opens: auto (default: the profile's stock app, else the best-ranked candidate), none, or a package
#   -t  add the 30 s trial take-over (receive only; phones with a profile)
#   -r  hand the radio back first and open SignalDeck without taking it over (a verified phone's app takes the radio when
#       it opens; the watch, the probe and the trial then all skip with "SignalDeck owns the radio")
#   -o  where the folder goes (default docs/w6/reports/<host date>_checks)
#   -k  keep the copied stock APKs (left out by default: they are the manufacturer's software)
#   -m  give up after this many seconds (default 600)
#
# On a phone whose radio takes the USB port while it is on (Unihertz Atom XL), pass a Wi-Fi ADB serial (ip:5555):
# over the cable the phone is gone during the stock-app watch and comes back after the checks release the radio.
# Needs SignalDeck installed with root granted. The app is started if it is not running (the bench receiver lives in
# its service). The phone's clock may be wrong: the host's time is passed along, so the folder carries the host's date.
# Progress comes from the app's log tag BenchChecks; a run ends with "done <folder> <zip>" or "failed …".
set -euo pipefail
export MSYS_NO_PATHCONV=1                       # Git Bash on Windows: keep /sdcard/… paths as they are

serial="" watch="auto" trial=false out="" keep=false max=600 release=false
while getopts "s:w:tro:km:" o; do
  case "$o" in
    s) serial="$OPTARG" ;; w) watch="$OPTARG" ;; t) trial=true ;; r) release=true ;; o) out="$OPTARG" ;; k) keep=true ;; m) max="$OPTARG" ;;
    *) sed -n '2,14p' "$0"; exit 2 ;;
  esac
done
[ -n "$serial" ] || { echo "-s <adb serial> is required" >&2; exit 2; }

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb
  elif [ -n "${LOCALAPPDATA:-}" ] && [ -x "$(cygpath -u "$LOCALAPPDATA" 2>/dev/null)/Android/Sdk/platform-tools/adb.exe" ]; then ADB="$(cygpath -u "$LOCALAPPDATA")/Android/Sdk/platform-tools/adb.exe"
  else echo "adb not found: set ADB=/path/to/adb" >&2; exit 2; fi
fi
a() { "$ADB" -s "$serial" "$@"; }

a get-state >/dev/null || { echo "phone $serial not reachable" >&2; exit 1; }
if $release; then
  if [ -z "$(a shell pidof com.macdmr.radio | tr -d '\r')" ]; then   # not running (an install stops it): the stock app may still be paused
    a shell am start -n com.macdmr.radio/.MainActivity --ez noTakeOver true >/dev/null
    sleep 8
  fi
  echo "handing the radio back to the stock app"
  a shell am broadcast -a com.macdmr.radio.DIAG --es cmd exit --ez stock true >/dev/null
  sleep 30                                       # the Atom XL's hand-back checks the bridge first (~20 s)
  echo "starting SignalDeck without taking the radio over"
  a shell am start -n com.macdmr.radio/.MainActivity --ez noTakeOver true >/dev/null
  sleep 8
elif [ -z "$(a shell pidof com.macdmr.radio | tr -d '\r')" ]; then
  echo "starting SignalDeck"
  a shell am start -n com.macdmr.radio/.MainActivity >/dev/null
  sleep 6
fi

since=$(a shell date +%s | tr -d '\r')          # phone epoch: only log lines from this run count
host_ms=$(( $(date +%s) * 1000 ))
a shell am broadcast -a com.macdmr.radio.DIAG --es cmd checks --es watch "$watch" --ez trial "$trial" --el hosttime "$host_ms" >/dev/null
echo "checks started (watch=$watch trial=$trial)"

last="" result=""
for ((t = 0; t < max; t += 5)); do
  sleep 5
  lines=$(a shell logcat -d -v epoch -s BenchChecks | tr -d '\r' | awk -v s="$since" '$1 + 0 >= s' || true)
  step=$(printf '%s\n' "$lines" | grep -oE 'BenchChecks: .*' | tail -1 | sed 's/^BenchChecks: //' || true)
  [ -n "$step" ] && [ "$step" != "$last" ] && { echo "  [$t s] $step"; last="$step"; }
  result=$(printf '%s\n' "$lines" | grep -oE 'BenchChecks: (done|failed|busy).*' | tail -1 | sed 's/^BenchChecks: //' || true)
  [ -n "$result" ] && break
done
case "$result" in
  done*) ;;
  "") echo "no result after $max s" >&2; exit 1 ;;
  *) echo "$result" >&2; exit 1 ;;
esac

folder=$(echo "$result" | awk '{print $2}')
name=$(basename "$folder")
[ -n "$out" ] || out="docs/w6/reports/$(date +%Y%m%d)_checks"
mkdir -p "$out"
a pull "$folder" "$out/" >/dev/null
$keep || rm -f "$out/$name"/*.apk
echo "pulled $out/$name"
ls -la "$out/$name"
