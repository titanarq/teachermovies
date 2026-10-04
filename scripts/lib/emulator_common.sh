#!/usr/bin/env bash
# Shared plumbing for the emulator harnesses: scripts/emulator_smoke.sh (the MVP critical path) and
# scripts/emulator_assistant.sh (the English-learning assistant and the remote keys, #376).
#
# Sourced, never run on its own. The caller parses its own arguments, sources this file and calls
# `emu_init <out-label>` first; that sets every global below, exports PATH and installs the EXIT
# trap. `set -uo pipefail` and a cwd of the repository root are the caller's job (see the two
# scripts' headers).
#
# Env knobs, all optional: ANDROID_SDK_ROOT (~/Android/Sdk), AVD (tm_tv36), PORT (5580, so the
# serial is emulator-5580), HOST_PORT (18787), OUT (.cache/emulator-verification/<label>-<stamp>),
# FFMPEG (an ffmpeg binary; see emu_ffmpeg), DRY (0).
#
# With DRY=1 nothing is executed and nothing is written to disk: every adb, curl and ffmpeg command
# the run would execute is printed one per line, waits and check names as `#` comments, and each
# check reports `# check: <name>` instead of PASS/FAIL. The listing goes to file descriptor 3,
# which emu_init duplicates from the original stdout, so a command captured with $(...) still shows
# the command it stood for. The PIN and both tokens are the literal string `<redacted>` in dry mode
# -- the screen is never read and no pairing is attempted -- and emu_redact is a second net over
# every printed line. No secret can reach the listing.
#
# The host port redirect goes through the emulator console (`adb emu redir`), never `adb forward`:
# a forwarded request reaches the app from a peer that LanAddressGuard refuses with 403 `not_lan`
# (#221). Through `redir` it comes from 10.0.2.2, a LAN address.

# The CC-BY test movie both harnesses drive: Sintel, Blender Foundation, via webtorrent's public
# test torrents. It ships nine sidecar .srt files, which matters to the assistant harness.
SINTEL_ID=08ada5a7a6183aae1e09d831df6748d566095a10
SINTEL_MAGNET="magnet:?xt=urn:btih:$SINTEL_ID&dn=Sintel&tr=udp%3A%2F%2Fexplodie.org%3A6969&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337&tr=wss%3A%2F%2Ftracker.openwebtorrent.com&ws=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2F&xs=https%3A%2F%2Fwebtorrent.io%2Ftorrents%2Fsintel.torrent"

# --- init, teardown ----------------------------------------------------------------------------

emu_init() { # emu_init <out-label>
    local label=$1
    SDK=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}
    AVD=${AVD:-tm_tv36}
    PORT=${PORT:-5580}
    SERIAL=emulator-$PORT
    HOST_PORT=${HOST_PORT:-18787}
    OUT=${OUT:-.cache/emulator-verification/$label-$(date +%Y%m%d-%H%M%S)}
    PKG=com.teachermovies.tv
    BASE=http://localhost:$HOST_PORT
    # The internal volume's download layout: <volume>/Movies/<info-hash>/<torrent folder>/<file>.
    # shellcheck disable=SC2034 # read by the scripts that source this file, not by the file itself
    MOVIES=/storage/emulated/0/Android/data/$PKG/files/Movies
    FAILED=0
    STARTED_EMULATOR=0
    TOKEN=""
    PAIRED_TOKEN=""
    DRY=${DRY:-0}
    export PATH="$SDK/platform-tools:$SDK/emulator:$PATH"
    if [ "$DRY" = 1 ]; then
        exec 3>&1 # the dry-run listing survives a $(...) capture of the command it describes
    else
        mkdir -p "$OUT"
    fi
    trap emu_cleanup EXIT
}

emu_cleanup() { # EXIT trap: kill the emulator this run started, unless --keep
    [ "${STARTED_EMULATOR:-0}" = 1 ] || return 0
    [ "${KEEP:-0}" = 1 ] && return 0
    if [ "${DRY:-0}" = 1 ]; then
        emu_dry_line adb -s "${SERIAL:-emulator-5580}" emu kill
    else
        adb -s "$SERIAL" emu kill >/dev/null 2>&1
    fi
    return 0
}

# --- the dry-run listing -----------------------------------------------------------------------

emu_redact() { # stdin -> stdout: PINs and bearer tokens replaced by <redacted>
    sed -e 's/"pin":"[^"]*"/"pin":"<redacted>"/g' \
        -e "s/Bearer [^ \"']*/Bearer <redacted>/g" \
        -e 's/\([?&]token=\)[^&"'"'"' ]*/\1<redacted>/g'
}

emu_dry_text() { # emu_dry_text <command...>: the listing line on stdout, for composition
    printf '%s\n' "$*" | emu_redact
}

emu_dry_line() { # emu_dry_line <command...>: one command of the listing, on fd 3
    emu_dry || return 0
    emu_dry_text "$@" >&3
}

emu_dry_comment() { # emu_dry_comment <text>: a `#` line -- a wait, a check name, a note
    emu_dry || return 0
    printf '# %s\n' "$*" >&3
}

emu_dry() { [ "${DRY:-0}" = 1 ]; }

emu_attempts() { # emu_attempts <n>: n, or 1 in dry mode so a wait loop does not spin
    if emu_dry; then printf '1\n'; else printf '%s\n' "$1"; fi
}

emu_sleep() { # emu_sleep <seconds>: an explicit wait, named in the dry listing
    if emu_dry; then emu_dry_comment "wait $1 s"; return 0; fi
    sleep "$1"
}

# --- primitives every step is built from -------------------------------------------------------

adb_() { # adb_ <args>: adb against $SERIAL
    if emu_dry; then emu_dry_line adb -s "$SERIAL" "$@"; return 0; fi
    adb -s "$SERIAL" "$@"
}

emu_curl() { # emu_curl <args>: curl, or the printed command in dry mode (nothing is requested)
    if emu_dry; then emu_dry_line curl "$@"; return 0; fi
    curl "$@"
}

api() { # api <curl args>: curl with the phone-scope bearer token in $TOKEN
    emu_curl -s -m 10 -H "Authorization: Bearer ${TOKEN:-}" "$@"
}

check() { # check <name> <command...>: runs the command, records pass/fail
    local name=$1
    shift
    if emu_dry; then
        emu_dry_comment "check: $name"
        if [ "${1:-}" = adb_ ]; then
            shift
            emu_dry_line adb -s "$SERIAL" "$@"
        else
            emu_dry_line "$@"
        fi
        return 0
    fi
    if "$@" >>"$OUT/checks.log" 2>&1; then
        echo "PASS  $name" | tee -a "$OUT/results.txt"
    else
        echo "FAIL  $name" | tee -a "$OUT/results.txt"
        FAILED=1
    fi
}

emu_note() { # emu_note <text>: evidence in results.txt that is not a pass/fail check
    if emu_dry; then emu_dry_comment "note: $1"; return 0; fi
    echo "INFO  $1" | tee -a "$OUT/results.txt"
}

emu_to_file() { # emu_to_file <path> <command...>: the command's stdout into <path> (dry: nowhere)
    local out=$1
    shift
    if emu_dry; then
        emu_dry_comment "stdout of the next command would go to $out"
        "$@" >/dev/null
        return 0
    fi
    "$@" >"$out"
}

key() { # key <NAME> [seconds]: inject KEYCODE_<NAME>, then wait (0.8 s default)
    adb_ shell input keyevent "KEYCODE_$1"
    emu_sleep "${2:-0.8}"
}

shot() { # shot <name>: a screenshot of the device into $OUT/<name>.png
    if emu_dry; then
        printf '%s >%s/%s.png\n' "$(emu_dry_text adb -s "$SERIAL" exec-out screencap -p)" "$OUT" "$1" >&3
        return 0
    fi
    adb_ exec-out screencap -p >"$OUT/$1.png"
}

ui_dump() { # ui_dump: the current screen's view hierarchy on stdout (empty in dry mode)
    if emu_dry; then
        emu_dry_line adb -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml
        emu_dry_line adb -s "$SERIAL" shell cat /sdcard/ui.xml
        return 0
    fi
    adb_ shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb_ shell cat /sdcard/ui.xml
}

emu_ui_text() { # emu_ui_text <file>: the current screen's visible text, one node text per line
    if emu_dry; then ui_dump >/dev/null; return 0; fi
    ui_dump | grep -o 'text="[^"]*"' | sed -e 's/^text="//' -e 's/"$//' >"$1"
}

# --- step: boot --------------------------------------------------------------------------------

emu_device_attached() { # is something already answering on $SERIAL? (never true in dry mode)
    if emu_dry; then return 1; fi
    adb -s "$SERIAL" get-state >/dev/null 2>&1
}

emu_boot() { # boot $AVD headless unless one is already on $SERIAL, then wait for sys.boot_completed
    if ! emu_device_attached; then
        if emu_dry; then
            emu_dry_line test -e /dev/kvm
            emu_dry_line emulator -avd "$AVD" -port "$PORT" -no-window -no-audio \
                -gpu swiftshader_indirect -no-snapshot -no-boot-anim -cores 3 \
                ">$OUT/emulator.log 2>&1 &"
            STARTED_EMULATOR=1
        else
            [ -e /dev/kvm ] || {
                echo "no /dev/kvm: the x86_64 emulator needs KVM" >&2
                exit 2
            }
            emulator -avd "$AVD" -port "$PORT" -no-window -no-audio -gpu swiftshader_indirect \
                -no-snapshot -no-boot-anim -cores 3 >"$OUT/emulator.log" 2>&1 &
            STARTED_EMULATOR=1
        fi
    fi
    local _
    for _ in $(seq 1 "$(emu_attempts 90)"); do # up to 6 min
        [ "$(emu_boot_completed)" = 1 ] && break
        emu_sleep 4
    done
    check "emulator booted" test "$(emu_boot_completed)" = 1
    [ "$FAILED" = 0 ] || exit 1
}

emu_boot_completed() { adb_ shell getprop sys.boot_completed 2>/dev/null | tr -d '\r'; }

# --- step: build, install, launch --------------------------------------------------------------

emu_build_install() { # emu_build_install <screenshot-label>: the debug APK, installed and running
    if [ "${BUILD:-1}" = 1 ]; then
        check "assembleDebug" ./gradlew -q :app-tv:assembleDebug
    fi
    check "install" adb_ install -r -g app-tv/build/outputs/apk/debug/app-tv-debug.apk
    adb_ logcat -c # every log line the harness asserts on is this run's
    check "app launches" adb_ shell am start -W -n "$PKG/.MainActivity"
    emu_sleep 5
    shot "$1"
    check "TorrentService foreground" sh -c \
        "adb -s $SERIAL shell dumpsys activity services $PKG | grep -q 'isForeground=true'"
}

emu_restart_app() { # a cold start, so the library and the player see the files as they are now
    adb_ shell am force-stop "$PKG"
    adb_ shell am start -W -n "$PKG/.MainActivity" >/dev/null
    emu_sleep 4
}

emu_player_front_cmd() { # the `sh -c` payload proving the player is up: no crash, app in front
    printf '%s\n' "! adb -s $SERIAL logcat -d | grep -q 'FATAL EXCEPTION' && adb -s $SERIAL shell dumpsys activity activities | grep -q 'topResumedActivity.*$PKG'"
}

# --- step: host redirect, pairing --------------------------------------------------------------

emu_redirect() { # host:$HOST_PORT -> the app's :8787, through the emulator console (#221)
    adb_ emu redir del "tcp:$HOST_PORT" >/dev/null 2>&1
    adb_ emu redir add "tcp:$HOST_PORT:8787" >/dev/null
}

emu_pin_from_dump() { # the `PIN 123456` text of the current screen, digits only (never printed)
    ui_dump | grep -o 'PIN [0-9]\{6\}' | head -1 | awk '{print $2}'
}

emu_read_pin() { # the PIN off the screen; two tabs to Configuración when it is not showing
    if emu_dry; then
        ui_dump >/dev/null
        printf '<redacted>\n'
        return 0
    fi
    local pin
    pin=$(emu_pin_from_dump)
    if [ -z "$pin" ]; then # the PIN is on the first-run screen or on the Configuración tab
        key DPAD_RIGHT
        key DPAD_RIGHT 1.5
        pin=$(emu_pin_from_dump)
    fi
    printf '%s\n' "$pin"
}

emu_pair() { # emu_pair <deviceName> <scope: phone|bridge>: the new token lands in PAIRED_TOKEN
    local name=$1 scope=$2 response _
    PAIRED_TOKEN=""
    for _ in $(seq 1 "$(emu_attempts 3)"); do
        PIN=$(emu_read_pin)
        response=$(emu_curl -s -m 5 -X POST "$BASE/api/pair" -H 'Content-Type: application/json' \
            -d "{\"pin\":\"${PIN:-}\",\"deviceName\":\"$name\",\"scope\":\"$scope\"}")
        PAIRED_TOKEN=$(printf '%s' "$response" |
            python3 -c 'import sys,json; print(json.load(sys.stdin).get("token",""))' 2>/dev/null)
        unset PIN # a successful pair consumes it, and it never reaches the listing or a log
        if emu_dry; then PAIRED_TOKEN='<redacted>'; fi # the listing reads like a pair that worked
        [ -n "$PAIRED_TOKEN" ] && break
        # The screen re-reads the PIN every 15 s and a pair consumes it: the dump may be stale.
        emu_sleep 3
    done
    # Leave the first-run screen (its only button is "Continuar") so Biblioteca is the start screen.
    if ui_dump | grep -q 'text="Continuar"'; then key DPAD_CENTER 2; fi
}

# --- step: the Sintel torrent ------------------------------------------------------------------

emu_add_sintel() { # POST the magnet; 409 is fine, it means an earlier run already added it
    local body code
    body=$(python3 -c 'import json,sys; print(json.dumps({"magnet": sys.argv[1]}))' "$SINTEL_MAGNET")
    code=$(api -o "$OUT/api-magnet.txt" -w '%{http_code}' -X POST "$BASE/api/torrents/magnet" \
        -H 'Content-Type: application/json' -d "$body")
    check "POST magnet (201, or 409 already added)" sh -c "[ ${code:-0} = 201 ] || [ ${code:-0} = 409 ]"
}

emu_wait_completed() { # polls GET /api/torrents/$SINTEL_ID; leaves the last state in $state
    state=""
    local _
    for _ in $(seq 1 "$(emu_attempts 120)"); do # up to 10 min
        state=$(api "$BASE/api/torrents/$SINTEL_ID" |
            python3 -c 'import sys,json; print(json.load(sys.stdin).get("state",""))' 2>/dev/null)
        [ "$state" = completed ] && break
        emu_sleep 5
    done
    if emu_dry; then state=completed; fi # the listing reads like a download that finished
}

emu_torrent_files() { # the torrent's files (index, path, size, priority) as JSON on stdout
    api "$BASE/api/torrents/$SINTEL_ID/files"
}

# --- step: Biblioteca -> the card -> the player ------------------------------------------------

emu_card_focused() { # emu_card_focused <title>: is the card holding <title> the focused node?
    ui_dump | python3 -c '
import re, sys
x = sys.stdin.read()
box = lambda n: tuple(map(int, re.findall(r"\d+", re.search(r"bounds=\"([^\"]*)\"", n).group(1))))
f = [box(n) for n in re.findall(r"<node[^>]*focused=\"true\"[^>]*>", x)]
t = [box(n) for n in re.findall(r"<node[^>]*text=\"" + re.escape(sys.argv[1]) + r"\"[^>]*>", x)]
ok = any(a[0] <= b[0] and a[1] <= b[1] and a[2] >= b[2] and a[3] >= b[3] for a in f for b in t)
sys.exit(0 if ok else 1)' "$1"
}

emu_focus_card() { # emu_focus_card <title>: DOWN onto the first card, then RIGHT until it is focused
    key DPAD_DOWN 1
    local _
    for _ in $(seq 1 "$(emu_attempts 6)"); do
        emu_card_focused "$1" && break
        key DPAD_RIGHT 1
    done
}

# --- evidence and observation ------------------------------------------------------------------

emu_save_logs() { # emu_save_logs <basename>: the whole logcat, plus only the app's AppLog lines
    if emu_dry; then
        emu_dry_line adb -s "$SERIAL" logcat -d ">$OUT/$1.txt"
        emu_dry_line grep -E "' TM/[a-z0-9-]+: '" "$OUT/$1.txt" ">$OUT/$1-app.txt"
        return 0
    fi
    adb_ logcat -d >"$OUT/$1.txt"
    grep -E ' TM/[a-z0-9-]+: ' "$OUT/$1.txt" >"$OUT/$1-app.txt" || true
}

emu_position_ms() { # the movie's saved playback position in ms, from GET /api/library
    if emu_dry; then
        api "$BASE/api/library" >/dev/null
        printf '<ms>\n'
        return 0
    fi
    api "$BASE/api/library" | python3 -c '
import json, sys
for item in json.load(sys.stdin):
    if item.get("id") == sys.argv[1]:
        print(item.get("lastPositionMs", ""))
        break
' "$SINTEL_ID" 2>/dev/null
}

# --- device file surgery (the assistant harness replaces the movie with a cut) ------------------

emu_adb_root() { # adbd as root: on API 30+ Android/data/<pkg> is app-private, even to `adb shell`
    if emu_dry; then
        emu_dry_line adb -s "$SERIAL" root
        emu_dry_line adb -s "$SERIAL" wait-for-device
        emu_dry_line adb -s "$SERIAL" shell id -u
        return 0
    fi
    adb -s "$SERIAL" root >/dev/null 2>&1
    adb -s "$SERIAL" wait-for-device >/dev/null 2>&1
    [ "$(adb -s "$SERIAL" shell id -u 2>/dev/null | tr -d '\r')" = 0 ]
}

emu_adb_unroot() { # back to the shell uid; adbd restarts, so the caller re-adds the redirect
    if emu_dry; then
        emu_dry_line adb -s "$SERIAL" unroot
        emu_dry_line adb -s "$SERIAL" wait-for-device
        return 0
    fi
    adb -s "$SERIAL" unroot >/dev/null 2>&1
    adb -s "$SERIAL" wait-for-device >/dev/null 2>&1
}

emu_ffmpeg() { # the ffmpeg to mux with: $FFMPEG, else imageio-ffmpeg's static binary, else PATH
    if [ -n "${FFMPEG:-}" ]; then printf '%s\n' "$FFMPEG"; return 0; fi
    local exe
    exe=$(python3 -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())' 2>/dev/null)
    [ -n "$exe" ] || exe=$(command -v ffmpeg 2>/dev/null)
    if [ -z "$exe" ]; then
        if emu_dry; then printf 'ffmpeg\n'; return 0; fi
        echo "no ffmpeg: pip install imageio-ffmpeg (docs/runbooks/emulator.md), or set FFMPEG=" >&2
        exit 2
    fi
    printf '%s\n' "$exe"
}

emu_ffmpeg_streams() { # emu_ffmpeg_streams <ffmpeg> <file>: the streams the mux produced
    if emu_dry; then emu_dry_line "$1" -hide_banner -i "$2"; return 0; fi
    "$1" -hide_banner -i "$2" 2>&1 | grep -E 'Stream #|Duration' || true
}
