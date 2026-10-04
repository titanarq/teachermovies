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
# The boot/build/install/pair/download/navigate steps are shared with scripts/emulator_assistant.sh
# and live in scripts/lib/emulator_common.sh, which also documents the redirect: it goes through the
# emulator console (`adb emu redir`), not `adb forward`, because forwarded requests reach the app
# from a peer LanAddressGuard refuses with 403 `not_lan` (#221).
set -uo pipefail
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/lib/emulator_common.sh
. "$SCRIPT_DIR/lib/emulator_common.sh"
cd "$SCRIPT_DIR/.." || exit 2

BUILD=1
KEEP=0
for arg in "$@"; do
    case $arg in
        --no-build) BUILD=0 ;;
        --keep) KEEP=1 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done
emu_init smoke

# --- 1. emulator ------------------------------------------------------------------------------
emu_boot

# --- 2. build + install -----------------------------------------------------------------------
emu_build_install 01-launch

# --- 3. HTTP server, pairing ------------------------------------------------------------------
emu_redirect
check "GET /api/status" sh -c "curl -sf -m 5 $BASE/api/status | tee $OUT/api-status.txt"
check "protected route without token is 401" \
    test "$(emu_curl -s -o /dev/null -w '%{http_code}' -m 5 "$BASE/api/torrents")" = 401
emu_pair emulator-smoke phone
TOKEN=$PAIRED_TOKEN
check "PIN pairing" test -n "$TOKEN"

# --- 4. magnet -> metadata -> download -> disk -> library -------------------------------------
emu_add_sintel
emu_wait_completed
emu_torrent_files >"$OUT/api-files.txt"
check "download completed" test "$state" = completed
check "file under <volume>/Movies/<info-hash>/" \
    adb_ shell test -s "$MOVIES/$SINTEL_ID/Sintel/Sintel.mp4"
check "listed by GET /api/library" sh -c \
    "curl -s -m 5 -H 'Authorization: Bearer $TOKEN' $BASE/api/library | tee $OUT/api-library.txt | grep -q $SINTEL_ID"

# --- 5. Biblioteca -> player ------------------------------------------------------------------
emu_restart_app
shot 02-biblioteca
# Biblioteca is the first tab; DOWN focuses the first card. Walk right until the focused card
# contains the "Sintel" title.
emu_focus_card Sintel
check "D-pad focus reaches the Sintel card" emu_card_focused Sintel
key DPAD_CENTER 8
shot 03-player
check "player is showing (app in front, no crash)" sh -c "$(emu_player_front_cmd)"
adb_ logcat -d >"$OUT/logcat.txt"
key BACK 2

echo "evidence: $OUT"
exit "$FAILED"
