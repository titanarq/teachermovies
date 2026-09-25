# Emulator runbook -- Android TV smoke checks

What can be checked on this host without a TV, and how. The script is `scripts/emulator_smoke.sh`;
evidence of the first full run (2026-09-25) is in `.cache/emulator-verification/` (not in git).

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
- Keys in the player are those of `RemoteKeyMapper`/`AssistantKeyMapper` (`docs/modules/app-tv.md`).
- `-no-audio`: TTS is verified by the Google TTS synthesis log lines, not by ear.

## What the emulator cannot prove

A real USB/SSD volume (`AndroidStorageVolumeProvider`), a real remote's key codes, audio output
and TV speakers, hardware video decoding, a phone on a real LAN (mDNS resolution from the phone,
routers with reverse DNS), and long-running behaviour (Android 15 `dataSync` 6 h cap).
