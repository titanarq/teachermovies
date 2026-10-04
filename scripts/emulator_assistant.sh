#!/usr/bin/env bash
# Local emulator harness for the English-learning assistant (#376): the remote-control flows on
# this laptop, without an APK on the TV and without a TV. Read
# docs/runbooks/emulator.md ("Assistant and remote keys") for what each check means and for what
# this harness cannot prove.
#
#   scripts/emulator_assistant.sh [--no-build] [--keep]
#       [--scenario embedded+downloads|downloads-only|no-english] [--dry-run]
#
# Steps: the smoke script's boot/build/install/pair steps (scripts/lib/emulator_common.sh) -> the
# Sintel torrent (Blender Foundation, CC-BY) completes -> the torrent is paused and every file is
# set to `skip`, so libtorrent never repairs what comes next -> its sidecar .srt files and any
# download an earlier run left are moved aside -> the main file is replaced by a short ffmpeg cut of
# the same movie that carries an embedded English text track built from
# scripts/fixtures/subtitles/sintel-cut.en.srt (when the scenario wants one) -> a second pairing
# with scope `bridge` uploads the committed en/es fixtures through POST /api/bridge/subtitles
# exactly as the laptop bridge does -> the movie is opened from Biblioteca with the D-pad and
# CENTER/LEFT/RIGHT/DOWN/BACK are injected, each step waited for, screenshotted and dumped ->
# $OUT/results.txt ends with PASS/FAIL per check, exit code 0 only if all of them passed.
#
# Env: the smoke script's knobs (ANDROID_SDK_ROOT, AVD, PORT, HOST_PORT, OUT) plus FFMPEG (an ffmpeg
#      binary; default: imageio-ffmpeg's static one, then ffmpeg on PATH), CUT_SECONDS (90) and
#      ASSISTANT_CACHE (.cache/emulator-assistant: the pulled movie and the muxed cuts live there,
#      so a second run does not pull ~600 MB again).
#
# --dry-run prints every adb/curl/ffmpeg command the run would execute, one per line, with the PIN
# and both tokens replaced by <redacted>, and executes none of them. It writes nothing to disk.
#
# The worker must never run this for real: config/agents.yaml forbids `adb`. Verify it with
# `bash -n` and `--dry-run`; the human or the control plane runs it against the emulator.
set -uo pipefail
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=scripts/lib/emulator_common.sh
. "$SCRIPT_DIR/lib/emulator_common.sh"
cd "$SCRIPT_DIR/.." || exit 2

usage() {
    cat <<'USAGE'
scripts/emulator_assistant.sh [--no-build] [--keep] [--scenario S] [--dry-run]

  --no-build     reuse app-tv/build/outputs/apk/debug/app-tv-debug.apk instead of assembling it
  --keep         leave the emulator running when the harness ends
  --scenario S   embedded+downloads (default) | downloads-only | no-english
  --dry-run      print every adb/curl/ffmpeg command, redacted, and run none of them

Env: ANDROID_SDK_ROOT, AVD, PORT, HOST_PORT, OUT, FFMPEG, CUT_SECONDS, ASSISTANT_CACHE.
Evidence goes to $OUT; $OUT/results.txt holds one PASS/FAIL line per check.
USAGE
}

BUILD=1
KEEP=0
DRY=0
SCENARIO=embedded+downloads
while [ $# -gt 0 ]; do
    case $1 in
        --no-build) BUILD=0 ;;
        --keep) KEEP=1 ;;
        --dry-run) DRY=1 ;;
        --scenario)
            shift
            [ $# -gt 0 ] || { echo "--scenario needs a value" >&2; exit 2; }
            SCENARIO=$1
            ;;
        --scenario=*) SCENARIO=${1#--scenario=} ;;
        --help | -h)
            usage
            exit 0
            ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
    shift
done

case $SCENARIO in
    embedded+downloads) EMBED_EN=1; UPLOAD_SUBS=1 ;;
    downloads-only) EMBED_EN=0; UPLOAD_SUBS=1 ;;
    no-english) EMBED_EN=0; UPLOAD_SUBS=0 ;;
    *)
        echo "unknown --scenario: $SCENARIO (embedded+downloads|downloads-only|no-english)" >&2
        exit 2
        ;;
esac

emu_init assistant
CUT_SECONDS=${CUT_SECONDS:-90}
CACHE=${ASSISTANT_CACHE:-.cache/emulator-assistant}
FIXTURES=scripts/fixtures/subtitles
TORRENT_DIR=$MOVIES/$SINTEL_ID
MOVIE_DIR=$TORRENT_DIR/Sintel
MAIN_FILE=$MOVIE_DIR/Sintel.mp4
MOVED_ASIDE=$TORRENT_DIR/moved-aside
SRC_MOVIE=$CACHE/Sintel.mp4
CUT_FILE=$CACHE/sintel-cut-$SCENARIO-$CUT_SECONDS.mp4
ROOTED=0
[ "$DRY" = 1 ] || mkdir -p "$CACHE"

# What the hidden-mode outcome line of #373 must say for this scenario.
case $SCENARIO in
    embedded+downloads) EXPECT_OUTCOME='Started source=EMBEDDED' ;;
    downloads-only) EXPECT_OUTCOME='Started source=DOWNLOADED' ;;
    no-english) EXPECT_OUTCOME='' ;; # anything but `Started source=`; the real line is reported
esac

emu_dry_comment "scenario $SCENARIO: embedded English track=$EMBED_EN, bridge uploads=$UPLOAD_SUBS, expected hidden.start outcome=${EXPECT_OUTCOME:-not Started source=}"

# --- what this harness checks, in one place ----------------------------------------------------
# The log lines it asserts on are those of #373 (`hidden.start ... source=EMBEDDED|DOWNLOADED`, tag
# TM/assistant) and #374 (`LEFT: hidden mode <state>, seek -10s`, tag TM/app-tv). emu_save_logs
# writes the AppLog-only extract of each logcat dump next to the dump itself, and the checks below
# grep that extract, so every command they run is a literal one in the --dry-run listing.

emu_hidden_outcome() { # the last `hidden.start` line, without its logcat prefix (evidence only)
    grep -F 'hidden.start' "$OUT/logcat-player-app.txt" 2>/dev/null | tail -1 | grep -o 'hidden.start.*'
}

# --- 1. emulator ------------------------------------------------------------------------------
emu_boot

# --- 2. build + install -----------------------------------------------------------------------
emu_build_install 01-launch

# --- 3. HTTP server, phone pairing -------------------------------------------------------------
emu_redirect
check "GET /api/status" sh -c "curl -sf -m 5 $BASE/api/status | tee $OUT/api-status.txt"
emu_pair emulator-assistant phone
TOKEN=$PAIRED_TOKEN
check "PIN pairing (phone scope)" test -n "$TOKEN"
if [ "$FAILED" != 0 ]; then
    echo "no phone token: every step below needs one" >&2
    echo "evidence: $OUT"
    exit "$FAILED"
fi

# --- 4. the Sintel torrent, complete -----------------------------------------------------------
emu_add_sintel
emu_wait_completed
emu_to_file "$OUT/api-files.txt" emu_torrent_files
check "download completed" test "$state" = completed

# --- 5. freeze the torrent, so libtorrent does not repair the cut ------------------------------
# A completed torrent is re-checked on resume and mismatched pieces are downloaded again, which
# would overwrite the cut; `skip` also survives an app restart (docs/runbooks/emulator.md).
code=$(api -o /dev/null -w '%{http_code}' -X POST "$BASE/api/torrents/$SINTEL_ID/pause")
if emu_dry; then code=204; fi # the listing reads like a run where every call succeeded
check "torrent paused (POST /api/torrents/<id>/pause is 204)" test "$code" = 204
SKIP_ALL=$(emu_torrent_files | python3 -c '
import json, sys
files = json.load(sys.stdin)
print(json.dumps([{"index": f["index"], "priority": "skip"} for f in files]))' 2>/dev/null)
if emu_dry && [ -z "$SKIP_ALL" ]; then
    SKIP_ALL='[{"index":0,"priority":"skip"}]' # the dry run has no files response to read
fi
code=$(api -o /dev/null -w '%{http_code}' -X PUT "$BASE/api/torrents/$SINTEL_ID/files" \
    -H 'Content-Type: application/json' -d "$SKIP_ALL")
if emu_dry; then code=204; fi
check "every file set to skip (PUT /api/torrents/<id>/files is 204)" test "$code" = 204
check "still listed by GET /api/library" sh -c \
    "curl -s -m 5 -H 'Authorization: Bearer $TOKEN' $BASE/api/library | tee $OUT/api-library.txt | grep -q $SINTEL_ID"

# --- 6. the movie becomes a short cut; every English subtitle already on disk moves aside ------
# Android/data/<pkg> is app-private on API 30+, so the file surgery runs with adbd as root. If the
# image refuses, the commands below run as `shell` and the checks say whether that was enough.
emu_note "adbd goes to root for the file surgery under Android/data/$PKG"
if emu_adb_root; then ROOTED=1; else emu_note "adb root refused; continuing as the shell uid"; fi
adb_ shell mkdir -p "$MOVED_ASIDE"
# Sintel ships nine sidecar .srt files; `moved-aside` is neither the movie's folder nor a `Subs`
# folder, so neither the sidecar search nor libVLC's slave loading can see them any more.
adb_ shell "mv $MOVIE_DIR/*.srt $MOVED_ASIDE/ 2>/dev/null; mv $MOVIE_DIR/Subs/*.srt $MOVED_ASIDE/ 2>/dev/null; mv $TORRENT_DIR/subs/*.srt $MOVED_ASIDE/ 2>/dev/null; true"
check "no sidecar .srt left next to the movie" sh -c \
    "! adb -s $SERIAL shell ls '$MOVIE_DIR' | grep -q '\.srt\$'"
emu_dry_comment "the pull is skipped when $SRC_MOVIE is already there from an earlier run"
if [ ! -s "$SRC_MOVIE" ] || emu_dry; then adb_ pull "$MAIN_FILE" "$SRC_MOVIE"; fi

FF=$(emu_ffmpeg)
if [ "$EMBED_EN" = 1 ]; then
    emu_note "muxing a ${CUT_SECONDS}s cut carrying the English fixture as a mov_text track"
    # `language=eng` and a title containing `[en]`: the app reads the track LABEL libVLC renders,
    # not the container metadata, and accepts either (EmbeddedSubtitleTracks.rankOf). The label
    # this mux really produced is in $OUT/cut-streams.txt and in the hidden.start candidates.
    check "ffmpeg muxed the cut with an embedded English track" \
        "$FF" -y -t "$CUT_SECONDS" -i "$SRC_MOVIE" -i "$FIXTURES/sintel-cut.en.srt" \
        -map 0:v -map 0:a? -map 1:0 -c copy -c:s mov_text \
        -metadata:s:s:0 language=eng -metadata:s:s:0 title='English [en]' "$CUT_FILE"
else
    emu_note "muxing a ${CUT_SECONDS}s cut with no subtitle track at all"
    check "ffmpeg muxed the cut with no subtitle track" \
        "$FF" -y -t "$CUT_SECONDS" -i "$SRC_MOVIE" -map 0:v -map 0:a? -c copy "$CUT_FILE"
fi
emu_to_file "$OUT/cut-streams.txt" emu_ffmpeg_streams "$FF" "$CUT_FILE"
if ! emu_dry; then
    emu_note "cut streams: $(grep -o 'Stream #.*' "$OUT/cut-streams.txt" 2>/dev/null | tr '\n' ' ')"
    emu_note "cut subtitle stream: $(grep -m1 -o 'Stream #.*Subtitle.*' "$OUT/cut-streams.txt" 2>/dev/null || echo none)"
fi
adb_ push "$CUT_FILE" "$MAIN_FILE"
# The device's own `stat` against the host's: same size means the push replaced the movie. Both
# sides must answer, so an empty device size (a refused push) fails instead of matching an empty.
check "the movie on the device is the cut" sh -c \
    "d=\$(adb -s $SERIAL shell stat -c %s '$MAIN_FILE' | tr -d '\r'); h=\$(stat -c %s '$CUT_FILE'); [ -n \"\$d\" ] && [ \"\$d\" = \"\$h\" ]"
if [ "$ROOTED" = 1 ]; then emu_adb_unroot; fi
emu_redirect # adbd restarted under root/unroot; the console redirect is added again

# --- 7. the fake laptop bridge: a second pairing, then the fixtures ----------------------------
if [ "$UPLOAD_SUBS" = 1 ]; then
    # The first pairing consumed the PIN: Configuración shows a fresh one (two tabs to the right).
    emu_pair emulator-assistant-bridge bridge
    BRIDGE_TOKEN=$PAIRED_TOKEN
    check "PIN pairing (bridge scope)" test -n "$BRIDGE_TOKEN"
    for lang in en es; do
        code=$(emu_curl -s -o "$OUT/api-subtitle-$lang.txt" -w '%{http_code}' -m 20 \
            -X POST "$BASE/api/bridge/subtitles" -H "Authorization: Bearer $BRIDGE_TOKEN" \
            -F "torrentId=$SINTEL_ID" -F "language=$lang" \
            -F "file=@$FIXTURES/sintel-cut.$lang.srt;type=application/x-subrip")
        if emu_dry; then code=201; fi
        check "POST /api/bridge/subtitles ($lang) is 201" test "$code" = 201
        if [ -s "$OUT/api-subtitle-$lang.txt" ]; then
            emu_note "upload $lang -> $(cat "$OUT/api-subtitle-$lang.txt")"
        fi
    done
else
    emu_note "scenario $SCENARIO uploads nothing: no English may exist anywhere on the device"
fi

# --- 8. Biblioteca -> the card -> the player ---------------------------------------------------
emu_restart_app
shot 02-biblioteca
emu_ui_text "$OUT/ui-biblioteca.txt"
check "Biblioteca shows the movie" grep -F -q Sintel "$OUT/ui-biblioteca.txt"
emu_focus_card Sintel
check "D-pad focus reaches the Sintel card" emu_card_focused Sintel
key DPAD_CENTER 8 # open the card; the player starts and hidden mode goes looking for English
shot 03-player
check "player is showing (app in front, no crash)" sh -c "$(emu_player_front_cmd)"

# --- 9. what hidden mode resolved (#373) -------------------------------------------------------
emu_sleep 6 # HiddenSubtitleController waits up to 3 s for the container to publish its tracks
emu_save_logs logcat-player
emu_note "last hidden.start: $(emu_hidden_outcome)"
if [ -n "$EXPECT_OUTCOME" ]; then
    check "hidden mode logged '$EXPECT_OUTCOME' (#373)" sh -c \
        "grep -F 'hidden.start' '$OUT/logcat-player-app.txt' | tail -1 | grep -F -q '$EXPECT_OUTCOME'"
else
    check "hidden mode logged its outcome (#373)" \
        grep -F -q 'hidden.start' "$OUT/logcat-player-app.txt"
    check "hidden mode started no English source (#373)" sh -c \
        "! grep -F 'hidden.start' '$OUT/logcat-player-app.txt' | tail -1 | grep -F -q 'Started source='"
fi

# --- 10. LEFT: the phrase rewind, or the 10 s seek that says why there is none (#374) ----------
# The position is read from `lastPositionMs` in GET /api/library, which PlaybackSession rewrites
# every 5 s while playing and at once on pause -- so each sample is taken after a pause, and the
# pause is what makes the two samples comparable (documented in docs/runbooks/emulator.md).
key DPAD_CENTER 2 # pause: the position is saved immediately
emu_sleep 1
POS_BEFORE=$(emu_position_ms)
key DPAD_CENTER 2 # resume, so LEFT arrives while playing
emu_sleep 1
key DPAD_LEFT 0.6
shot 04-after-left # the #374 message stays up 3 s only
emu_ui_text "$OUT/ui-after-left.txt"
emu_sleep 2 # presses less than 1.5 s apart form one group; the seek fires when it closes
key DPAD_CENTER 2 # pause: the rewound position is saved immediately
emu_sleep 1
POS_AFTER=$(emu_position_ms)
emu_save_logs logcat-after-left
if [ "$SCENARIO" = no-english ]; then
    check "the reason is on screen after LEFT (#374)" grep -F -q 'saltan 10 s' "$OUT/ui-after-left.txt"
    check "LEFT logged the 10 s seek and why (#374)" sh -c \
        "grep -q 'LEFT: hidden mode .*seek -10s' '$OUT/logcat-after-left-app.txt'"
else
    check "LEFT moved the playback position back ($POS_BEFORE -> $POS_AFTER ms)" \
        test "$POS_AFTER" -lt "$POS_BEFORE"
    if [ "$SCENARIO" = embedded+downloads ]; then
        check "no 10 s-seek fallback line after LEFT (#374)" sh -c \
            "! grep -F -q 'seek -10s' '$OUT/logcat-after-left-app.txt'"
    fi
fi
if ! emu_dry; then
    emu_note "fallback line, if any: $(grep -m1 -o 'LEFT: hidden mode.*' "$OUT/logcat-after-left-app.txt" 2>/dev/null || echo none)"
    emu_note "phrase rewind, first line: $(grep -m1 -o 'phrase rewind:.*' "$OUT/logcat-after-left-app.txt" 2>/dev/null || echo none)"
fi

# --- 11. RIGHT, DOWN, BACK: the rest of the remote, observed and not asserted (#375) -----------
key DPAD_RIGHT 3 # the Spanish rewind; without a Spanish timeline it says so on screen
shot 05-after-right
emu_ui_text "$OUT/ui-after-right.txt"
key DPAD_DOWN 3 # capture the line: the assistant overlay opens
shot 06-after-down
emu_ui_text "$OUT/ui-after-down.txt"
key BACK 2 # dismiss the overlay
shot 07-overlay-dismissed
key BACK 2 # leave the player
shot 08-after-back
emu_save_logs logcat
check "no crash during the key sequence" sh -c "! grep -q 'FATAL EXCEPTION' '$OUT/logcat.txt'"

if emu_dry; then
    echo "# dry run: nothing was executed and nothing was written; evidence would go to $OUT"
    exit 0
fi
echo "evidence: $OUT"
exit "$FAILED"
