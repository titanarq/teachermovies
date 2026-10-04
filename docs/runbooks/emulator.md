# Emulator runbook -- Android TV smoke checks

What can be checked on this host without a TV, and how. Two scripts share their boot/build/install/
pair steps through `scripts/lib/emulator_common.sh`: `scripts/emulator_smoke.sh` (the MVP critical
path) and `scripts/emulator_assistant.sh` (the English-learning assistant and the remote keys, #376).
Evidence of the first full smoke run (2026-09-25) is in `.cache/emulator-verification/` (not in git).

## One-time setup (~9 GB: the TV image is 8.2 GB)

```sh
S=$HOME/Android/Sdk
yes | $S/cmdline-tools/latest/bin/sdkmanager --install emulator "system-images;android-36;android-tv;x86_64"
echo no | $S/cmdline-tools/latest/bin/avdmanager create avd -n tm_tv36 \
  -k "system-images;android-36;android-tv;x86_64" -d tv_1080p
# ~/.android/avd/tm_tv36.avd/config.ini: hw.ramSize=3072, disk.dataPartition.size=4G
```

- Needs `/dev/kvm`. Use an **x86_64** image: the API 34 "android-tv;x86" image is 32-bit only
  (`abilist=x86,armeabi-v7a`) and the APK packages no `x86` libs (jlibtorrent/libVLC). There is no
  API 35 TV image; API 36 exercises the Android 15+ foreground-service rules (#129).
- The emulator refuses to start when free disk < the data partition size.

## Run

```sh
scripts/emulator_smoke.sh            # boots tm_tv36 headless if not running, builds, checks, kills it
scripts/emulator_smoke.sh --no-build --keep   # reuse the APK, leave the emulator up
```

Checks: install, launch, `TorrentService` foreground, `GET /api/status`, 401 without token, PIN
pairing, Sintel magnet -> `completed`, file under `<volume>/Movies/<info-hash>/`, listed by
`/api/library`, D-pad focus reaches the card, player opens without a crash. Results in
`<OUT>/results.txt`, screenshots and logcat next to it. The PIN and token are never printed.

## Assistant and remote keys

`scripts/emulator_assistant.sh` (#376) reproduces the English-learning assistant's remote-control
flows here, without an APK on the TV: it runs the smoke script's boot/build/install/pair steps (they
are shared, in `scripts/lib/emulator_common.sh`), replaces the Sintel movie with a short cut, has a
fake laptop bridge upload the committed subtitle fixtures, then opens the movie from Biblioteca and
injects the D-pad keys -- a screenshot, a `uiautomator` text dump and the AppLog lines after each
step. Exit code 0 only if every check in `<OUT>/results.txt` passed.

```sh
scripts/emulator_assistant.sh                                     # embedded+downloads, builds first
scripts/emulator_assistant.sh --no-build --keep --scenario downloads-only
scripts/emulator_assistant.sh --dry-run --scenario no-english     # print every command, run none
```

Same env knobs as the smoke script (`ANDROID_SDK_ROOT`, `AVD`, `PORT`, `HOST_PORT`, `OUT`), plus
`FFMPEG` (default: `imageio-ffmpeg`'s static binary, then `ffmpeg` on `PATH`), `CUT_SECONDS` (90) and
`ASSISTANT_CACHE` (`.cache/emulator-assistant`, where the pulled movie and the muxed cuts are kept so
a second run does not pull ~600 MB again -- `rm -rf` it to force a re-pull).

`--dry-run` prints every `adb`, `curl` and `ffmpeg` command the run would execute, one per line, with
the PIN and both tokens as `<redacted>`, and runs none of them: nothing is booted, nothing is
requested, nothing is written to disk. It is how a worker verifies a change to the harness, since
`config/agents.yaml` forbids a worker to run `adb` at all; only the human or the control plane runs
it for real. A check whose predicate is a shell function (`emu_card_focused`) prints the function's
name; the `adb` commands it runs are the `uiautomator` pair listed just above it.

### The three scenarios

| `--scenario` | the cut the harness muxes | the fake bridge uploads | `hidden.start` must say |
|---|---|---|---|
| `embedded+downloads` (default) | 90 s with `sintel-cut.en.srt` muxed in as a `mov_text` track | `en` and `es` | `Started source=EMBEDDED` |
| `downloads-only` | 90 s with no subtitle track | `en` and `es` | `Started source=DOWNLOADED` |
| `no-english` | 90 s with no subtitle track | nothing | anything but `Started source=` |

In all three, Sintel's nine sidecar `.srt` files and whatever an earlier run left in `subs/` are
moved to `<torrent>/moved-aside/` first, so the only English on the device is the one the scenario
puts there. `moved-aside` is deliberately not called `Subs`: that is the one subdirectory the sidecar
search also reads, and libVLC's slave loading reads it too.

The fixtures `scripts/fixtures/subtitles/sintel-cut.{en,es}.srt` are original lines written for this
harness (no copyrighted subtitle text), timed inside the cut. Both files share their timings, so the
Spanish rewind has a timeline to follow. Keep every cue inside `CUT_SECONDS`.

### What the run does to the movie

The torrent is paused and then **every file is set to `skip`** (`PUT /api/torrents/<id>/files`),
because a completed torrent is re-checked on resume and would download the replaced pieces back over
the cut; `skip` also survives an app restart. Then the movie is pulled to `$ASSISTANT_CACHE`, muxed
with the cut, and pushed back over the same path, so `mainFilePath` in Room stays valid. The pull and
the push need adbd as root: `Android/data/<pkg>` is app-private on API 30+, and the harness asks for
`adb root` (the TV image allows it), says so in `results.txt` if it is refused, and re-adds the port
redirect afterwards because adbd restarted.

The mux sets both `language=eng` and `title='English [en]'` on the subtitle track. The app matches
the *label libVLC renders* for the track, not the container metadata, and accepts either form; the
label that really came out is in `$OUT/cut-streams.txt` and in the `candidates=[...]` part of the
`hidden.start` line. When `source=EMBEDDED` fails, that is the first thing to look at.

### What the checks mean

Biblioteca shows the movie, the D-pad reaches its card and the player opens -- the smoke script's
ground, re-checked here because everything below depends on it. Then, per scenario:

- **`hidden mode logged '<outcome>' (#373)`** -- the last `hidden.start lang=en ...` line of tag
  `TM/assistant` says where the English came from. This is the check #373 exists for: `EMBEDDED` when
  the cut carries the track, `DOWNLOADED` when only the bridge's file is there. For `no-english` the
  harness only requires that no `Started source=` line was logged at all, and reports the real
  outcome as an `INFO` line: on an emulator whose database already has a `Downloaded` row from an
  earlier run, the outcome is `Unreadable source=DOWNLOADED` (the file was moved aside) rather than
  `NoSubtitleFile`, and both mean "no English to show". A first run on a fresh AVD gives
  `NoSubtitleFile`.
- **`LEFT moved the playback position back`** -- the phrase rewind, not the 10 s seek. There is no
  live position anywhere (no `MediaSession`, so `dumpsys media_session` is empty, and nothing logs
  the position on a key), so the harness reads `lastPositionMs` from `GET /api/library`, which
  `PlaybackSession` rewrites every 5 s while playing **and at once on pause**. It therefore pauses
  before each of the two samples: pause, read `before`, resume, LEFT, wait 2 s (presses less than
  1.5 s apart form one group and the seek only fires when the group closes), pause, read `after`.
  The two numbers are in the check's own name in `results.txt`.
- **`no 10 s-seek fallback line after LEFT (#374)`** -- `embedded+downloads` only: no
  `LEFT: hidden mode ..., seek -10s` line of tag `TM/app-tv` after the key. The rewind happened, so
  the fallback must not have.
- **`the reason is on screen after LEFT` and `LEFT logged the 10 s seek and why` (#374)** --
  `no-english` only: the transport overlay says why there is no phrase rewind (the harness greps the
  ASCII part, `saltan 10 s`, of `Sin subtítulos en inglés: <- -> saltan 10 s`, because the message
  only stays up 3 s and is dumped right after the key), and the log carries the seek line with the
  state that caused it (`none` on a fresh database, `unreadable` on a reused one).

RIGHT, DOWN and BACK are injected and screenshotted as well, and the harness asserts nothing about
them beyond "no crash": the Spanish rewind needs the ES timeline the bridge would have to deliver,
DOWN opens the capture overlay whose explain and translate jobs need the real laptop bridge
(ADR-0005), and the Biblioteca badges are #375.

### What it cannot prove

What the last section of this runbook says the emulator cannot prove, plus: a real remote's key codes
and repeat behaviour (`input keyevent` is one clean down/up, so a long press is not exercised at all
-- and the app has no long-press handling to exercise), the TV's decoder and its subtitle track
limits, TTS by ear, the real bridge's explain/translate jobs, and anything about #375's badges. A
green run means the app resolved the right subtitle source and the right key reached the right
handler; it does not mean the TV behaves the same.

## Driving it by hand

```sh
A="adb -s emulator-5580"
$A shell input keyevent KEYCODE_DPAD_DOWN        # DPAD_UP/LEFT/RIGHT/CENTER, BACK, VOLUME_UP ...
$A exec-out screencap -p > shot.png
$A shell uiautomator dump /sdcard/ui.xml && $A shell cat /sdcard/ui.xml | grep -o 'focused="true"[^>]*bounds="[^"]*"'
$A emu redir add tcp:18787:8787                  # phone side: http://localhost:18787 (a browser works too)
$A shell dumpsys activity services com.teachermovies.tv | grep isForeground   # types=0x40000000 = specialUse
$A shell dumpsys servicediscovery | grep Advertiser                            # NSD announcement (#103)
$A reboot                                        # BOOT_COMPLETED autostart (#126), resume after reboot
```

- Use `adb emu redir`, not `adb forward`: forwarded requests are refused with 403 `not_lan` (#221).
- Test media: Blender open movies from webtorrent's public test torrents (Sintel, Big Buck Bunny,
  Cosmos Laundromat; CC-BY). Sintel ships 9 sidecar `.srt` files. For embedded-subtitle checks mux a
  cut with ffmpeg (a static binary comes with `pip install imageio-ffmpeg`), pause the torrent or set
  its files to `skip` first -- libtorrent otherwise repairs the replaced file.
  `scripts/emulator_assistant.sh` does all of that on its own (see "Assistant and remote keys");
  doing it by hand is only worth it when debugging one of its steps.
- Keys in the player are those of `RemoteKeyMapper`/`AssistantKeyMapper` (`docs/modules/app-tv.md`).
- `-no-audio`: TTS is verified by the Google TTS synthesis log lines, not by ear.

## What the emulator cannot prove

A real USB/SSD volume (`AndroidStorageVolumeProvider`), a real remote's key codes, audio output
and TV speakers, hardware video decoding, a phone on a real LAN (mDNS resolution from the phone,
routers with reverse DNS), and long-running behaviour (Android 15 `dataSync` 6 h cap).
