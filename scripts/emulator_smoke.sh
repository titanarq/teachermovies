#!/usr/bin/env bash
# Android TV emulator smoke test: the MVP critical path on a headless emulator, no human needed.
# See docs/runbooks/emulator.md for the one-time SDK/AVD setup and what this does NOT prove.
#
#   scripts/emulator_smoke.sh [--no-build] [--keep]
#
# Steps: boot the AVD headless (unless one is already on $SERIAL) -> build + install the :app-tv
# debug APK -> launch -> TorrentService is foreground -> host port redirect to :8787 ->
# GET /api/status -> PIN pairing (PIN read from the TV screen with uiautomator, never printed) ->
# POST the Sintel magnet (Blender Foundation, CC-BY) -> wait for `completed` -> file under
# <volume>/Movies/<info-hash>/ -> listed by GET /api/library -> open it in the player (screenshot).
# Evidence (screenshots, logcat, api-*.txt) goes to $OUT. Exit code 0 only if every check passed.
#
# Env: ANDROID_SDK_ROOT (default ~/Android/Sdk), AVD (tm_tv36), PORT (5580: serial emulator-5580),
#      HOST_PORT (18787), OUT (.cache/emulator-verification/smoke-<timestamp>).
#
# The redirect goes through the emulator console (`adb emu redir`), not `adb forward`: requests
# then reach the app from 10.0.2.2, a LAN address; `adb forward` connections are refused with 403
# `not_lan` (#221: LanAddressGuard checks remoteHost).
set -uo pipefail
cd "$(dirname "$0")/.."

SDK=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
AVD=${AVD:-tm_tv36}
PORT=${PORT:-5580}
SERIAL=emulator-$PORT
HOST_PORT=${HOST_PORT:-18787}
OUT=${OUT:-.cache/emulator-verification/smoke-$(date +%Y%m%d-%H%M%S)}
PKG=com.teachermovies.tv
SINTEL_ID=08ada5a7a6183aae1e09d831df6748d566095a10
SINTEL_MAGNET="magnet:?xt=urn:btih:$SINTEL_ID&dn=Sintel&tr=udp%3A%2F%2Fexplodie.org%3A6969&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337&tr=wss%3A%2F%2Ftracker.openwebtorrent.com&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F&xs=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2Fsintel.torrent"
BUILD=1
KEEP=0
for arg in "$@"; do
    case $arg in
        --no-build) BUILD=0 ;;
        --keep) KEEP=1 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
mkdir -p "$OUT"
adb_() { adb -s "$SERIAL" "$@"; }
FAILED=0
STARTED_EMULATOR=0
TOKEN=""
check() { # check <name> <command...>: runs the command, records pass/fail
    local name=$1; shift
    if "$@" >>"$OUT/checks.log" 2>&1; then echo "PASS  $name" | tee -a "$OUT/results.txt"
    else echo "FAIL  $name" | tee -a "$OUT/results.txt"; FAILED=1; fi
}
key() { adb_ shell input keyevent "KEYCODE_$1"; sleep "${2:-0.8}"; }
shot() { adb_ exec-out screencap -p >"$OUT/$1.png"; }
ui_dump() { adb_ shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb_ shell cat /sdcard/ui.xml; }
api() { curl -s -m 10 -H "Authorization: Bearer $TOKEN" "$@"; }
BASE=http://localhost:$HOST_PORT

cleanup() {
    if [ "$STARTED_EMULATOR" = 1 ] && [ "$KEEP" = 0 ]; then adb_ emu kill >/dev/null 2>&1; fi
}
trap cleanup EXIT

# --- 1. emulator ------------------------------------------------------------------------------
if ! adb_ get-state >/dev/null 2>&1; then
    [ -e /dev/kvm ] || { echo "no /dev/kvm: the x86_64 emulator needs KVM" >&2; exit 2; }
    emulator -avd "$AVD" -port "$PORT" -no-window -no-audio -gpu swiftshader_indirect \
        -no-snapshot -no-boot-anim -cores 3 >"$OUT/emulator.log" 2>&1 &
    STARTED_EMULATOR=1
fi
for _ in $(seq 1 90); do
    [ "$(adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
    sleep 4
done
check "emulator booted" test "$(adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1
[ "$FAILED" = 0 ] || exit 1

# --- 2. build + install -----------------------------------------------------------------------
if [ "$BUILD" = 1 ]; then
    check "assembleDebug" ./gradlew -q :app-tv:assembleDebug
fi
check "install" adb_ install -r -g app-tv/build/outputs/apk/debug/app-tv-debug.apk
adb_ logcat -c
check "app launches" adb_ shell am start -W -n "$PKG/.MainActivity"
sleep 5
shot 01-launch
check "TorrentService foreground" sh -c \
    "adb -s $SERIAL shell dumpsys activity services $PKG | grep -q 'isForeground=true'"

# --- 3. HTTP server, pairing ------------------------------------------------------------------
adb_ emu redir del "tcp:$HOST_PORT" >/dev/null 2>&1
adb_ emu redir add "tcp:$HOST_PORT:8787" >/dev/null
check "GET /api/status" sh -c "curl -sf -m 5 $BASE/api/status | tee $OUT/api-status.txt"
check "protected route without token is 401" \
    test "$(curl -s -o /dev/null -w '%{http_code}' -m 5 "$BASE/api/torrents")" = 401
PIN=$(ui_dump | grep -o 'PIN [0-9]\{6\}' | head -1 | awk '{print $2}')
if [ -z "$PIN" ]; then # PIN is on the first-run screen or Configuración
    key DPAD_RIGHT; key DPAD_RIGHT 1.5
    PIN=$(ui_dump | grep -o 'PIN [0-9]\{6\}' | head -1 | awk '{print $2}')
fi
TOKEN=$(curl -s -m 5 -X POST "$BASE/api/pair" -H 'Content-Type: application/json' \
    -d "{\"pin\":\"$PIN\",\"deviceName\":\"emulator-smoke\"}" |
    python3 -c 'import sys,json; print(json.load(sys.stdin).get("token",""))' 2>/dev/null)
unset PIN
# Leave the first-run screen (its only button is "Continuar") so Biblioteca is the start screen.
if ui_dump | grep -q 'text="Continuar"'; then key DPAD_CENTER 2; fi
check "PIN pairing" test -n "$TOKEN"

# --- 4. magnet -> metadata -> download -> disk -> library -------------------------------------
BODY=$(python3 -c 'import json,sys; print(json.dumps({"magnet": sys.argv[1]}))' "$SINTEL_MAGNET")
code=$(api -o "$OUT/api-magnet.txt" -w '%{http_code}' -X POST "$BASE/api/torrents/magnet" \
    -H 'Content-Type: application/json' -d "$BODY")
check "POST magnet (201, or 409 already added)" sh -c "[ $code = 201 ] || [ $code = 409 ]"
state=""
for _ in $(seq 1 120); do # up to 10 min
    state=$(api "$BASE/api/torrents/$SINTEL_ID" |
        python3 -c 'import sys,json; print(json.load(sys.stdin).get("state",""))' 2>/dev/null)
    [ "$state" = completed ] && break
    sleep 5
done
api "$BASE/api/torrents/$SINTEL_ID/files" >"$OUT/api-files.txt"
check "download completed" test "$state" = completed
MOVIES=/storage/emulated/0/Android/data/$PKG/files/Movies # internal volume (first run default)
check "file under <volume>/Movies/<info-hash>/" \
    adb_ shell test -s "$MOVIES/$SINTEL_ID/Sintel/Sintel.mp4"
check "listed by GET /api/library" sh -c \
    "curl -s -m 5 -H 'Authorization: Bearer $TOKEN' $BASE/api/library | tee $OUT/api-library.txt | grep -q $SINTEL_ID"

# --- 5. Biblioteca -> player ------------------------------------------------------------------
adb_ shell am force-stop "$PKG"
adb_ shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 4
shot 02-biblioteca
# Biblioteca is the first tab; DOWN focuses the first card. Walk right until the focused card
# contains the "Sintel" title.
sintel_focused() {
    ui_dump | python3 -c '
import re, sys
x = sys.stdin.read()
box = lambda n: tuple(map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", n).group(1))))
f = [box(n) for n in re.findall(r"<node[^>]*focused=\"true\"[^>]*>", x)]
t = [box(n) for n in re.findall(r"<node[^>]*text=\"Sintel\"[^>]*>", x)]
ok = any(a[0] <= b[0] and a[1] <= b[1] and a[2] >= b[2] and a[3] >= b[3] for a in f for b in t)
sys.exit(0 if ok else 1)'
}
key DPAD_DOWN 1
for _ in 1 2 3 4 5 6; do sintel_focused && break; key DPAD_RIGHT 1; done
check "D-pad focus reaches the Sintel card" sintel_focused
key DPAD_CENTER 8
shot 03-player
check "player is showing (app in front, no crash)" sh -c \
    "! adb -s $SERIAL logcat -d | grep -q 'FATAL EXCEPTION' && adb -s $SERIAL shell dumpsys activity activities | grep -q 'topResumedActivity.*$PKG'"
adb_ logcat -d >"$OUT/logcat.txt"
key BACK 2

echo "evidence: $OUT"
exit "$FAILED"
