#!/usr/bin/env bash
# Build (optional) and publish the TV debug APK on the LAN as a fixed URL: http://<laptop-ip>:8000/tm.apk
# Usage: scripts/publish_apk.sh [--build] [path/to/apk]
#   --build   run `./gradlew :app-tv:assembleDebug` first and stage the result as
#             .cache/apk/teachermovies-tv-<short-sha>-debug.apk
#   default APK: the newest .cache/apk/teachermovies-tv-*-debug.apk
# Idempotent: re-running repoints tm.apk; the HTTP server keeps running (detached, survives sessions).
# Works from any worktree: staging and serving always live in the main checkout's .cache/apk.
# Procedure and caveats: docs/runbooks/publish-apk.md
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
MAIN="$(cd "$(git rev-parse --path-format=absolute --git-common-dir)/.." && pwd)"
APK_DIR="$MAIN/.cache/apk"
SERVE_DIR="$APK_DIR/serve"
PORT=8000

build=0
if [ "${1:-}" = "--build" ]; then build=1; shift; fi

mkdir -p "$APK_DIR" "$SERVE_DIR"

if [ "$build" -eq 1 ]; then
  (cd "$ROOT" && ./gradlew -q :app-tv:assembleDebug)
  built="$ROOT/app-tv/build/outputs/apk/debug/app-tv-debug.apk"
  [ -f "$built" ] || { echo "build produced no APK at $built" >&2; exit 1; }
  sha="$(git -C "$ROOT" rev-parse --short HEAD)"
  cp -f "$built" "$APK_DIR/teachermovies-tv-$sha-debug.apk"
  set -- "$APK_DIR/teachermovies-tv-$sha-debug.apk"
fi

src="${1:-$(ls -t "$APK_DIR"/teachermovies-tv-*-debug.apk | head -1)}"
[ -f "$src" ] || { echo "APK not found: $src" >&2; exit 1; }

cp -f "$src" "$SERVE_DIR/tm.apk.tmp" && mv -f "$SERVE_DIR/tm.apk.tmp" "$SERVE_DIR/tm.apk"
basename "$src" > "$SERVE_DIR/VERSION.txt"
# TV keyboards auto-capitalise: serve the common spellings too, and a root page linking to it.
for alias in TM.apk Tm.apk; do ln -sf tm.apk "$SERVE_DIR/$alias"; done
printf '<html><body style="font-size:3em"><a href="tm.apk">Descargar tm.apk (%s)</a></body></html>\n' \
  "$(basename "$src")" > "$SERVE_DIR/index.html"

ip="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="src") print $(i+1)}')"
[ -n "$ip" ] || { echo "could not find the LAN IP" >&2; exit 1; }

if ! ss -ltn "sport = :$PORT" | grep -q ":$PORT"; then
  cd "$SERVE_DIR"
  setsid nohup python3 -m http.server "$PORT" --bind "$ip" >"$SERVE_DIR/http.log" 2>&1 < /dev/null &
  sleep 1
fi

url="http://$ip:$PORT/tm.apk"
if curl -sfI "$url" >/dev/null; then
  echo "Published $(basename "$src") -> $url"
else
  echo "Server not answering at $url (port $PORT busy by another process?)" >&2; exit 1
fi
