# Publishing the TV app to the TV (APK on the LAN)

The TV installs the app by downloading a debug APK from this laptop. One command builds the APK
from the current checkout and serves it at a fixed URL:

```sh
scripts/publish_apk.sh --build          # from the checkout (main, normally) that has the version to ship
# -> Published teachermovies-tv-<sha>-debug.apk -> http://<laptop-ip>:8000/tm.apk
```

On the TV, open that URL in its browser/downloader (`tm.apk`; `TM.apk` and `Tm.apk` work too because
TV keyboards auto-capitalise; the root page `http://<laptop-ip>:8000/` links to it), download, install.

## What the script does

- `--build` runs `./gradlew :app-tv:assembleDebug` (a few minutes) and stages the result as
  `.cache/apk/teachermovies-tv-<short-sha>-debug.apk`. Without `--build` it republishes the newest
  staged APK, or the path given as an argument.
- Copies it atomically to `.cache/apk/serve/tm.apk`, writes `VERSION.txt` and the root page.
- Starts `python3 -m http.server 8000` on the LAN address, detached (`setsid nohup`), only if port
  8000 is free. It survives sessions; re-running just repoints `tm.apk`.
- Staging and serving always live in the **main checkout's** `.cache/apk/` (git-ignored), even when
  the script runs from another worktree. The script is idempotent.

## Before publishing

1. Be on the commit to ship, with a clean tree for `app-tv`: the short sha in the file name is
   `HEAD`, so a build from a dirty or stale checkout is mislabelled. `git fetch && git merge
   --ff-only origin/main` first when shipping main.
2. Do not run it from a second Gradle build at the same time as another in the same checkout.

## Check it worked

```sh
curl -sI http://<laptop-ip>:8000/tm.apk | head -1      # HTTP/1.0 200 OK
cat .cache/apk/serve/VERSION.txt                        # the sha you meant to ship
```

## Do not confuse with

`~/apk-publico` on port 8766 serves another project's APK (Student Assistant). It is not this one.

## After installing

- The PIN pairing is off by default in debug builds (`PIN_PAIRING_REQUIRED=false`, see
  `docs/modules/http-server.md`); the laptop bridge connects without pairing. Restart the bridge
  after the TV app was reinstalled: `systemctl --user restart teachermovies-bridge.service`
  (`docs/runbooks/laptop-bridge.md`).
- The TV address can change; `scripts/add-hosts-alias.sh` and the bridge's mDNS discovery cover it.
