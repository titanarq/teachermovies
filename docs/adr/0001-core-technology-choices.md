# ADR-0001: Core technology choices

- **Status:** Accepted
- **Date:** 2026-09-23
- **Deciders:** MatillaM

## Context

teachermovies is an autonomous Android TV media center (see `docs/VISION.md`): it must download
torrents itself, store them on internal or USB/SSD storage, play arbitrary real-world files
(MKV with unpredictable audio/subtitle tracks, 4K), serve a small LAN API for a phone, and drive an
English-learning assistant from a hidden subtitle track.

## Decision

1. **Platform/UI:** Kotlin on Android TV, Jetpack Compose for TV, Room for metadata, DataStore for
   settings.
2. **Player: libVLC Android (stable 3.x line, 3.7.x at the time of writing) over AndroidX Media3.**
   libVLC plays the widest range of containers and codecs, handles multiple audio tracks and
   internal/external SRT/ASS subtitles in MKV reliably, and can play a local file that is still
   growing. Media3/ExoPlayer would require extension decoders and still misses containers/codecs
   common in this content.
3. **Torrent: jlibtorrent 2.x (libtorrent 2.x bindings, 2.0.12.x).** Native builds for ARM, ARM64,
   x86, x86-64; BitTorrent v1, v2 and hybrid; per-file and per-piece priorities, piece deadlines,
   resume data. It runs in a foreground service independent from the player.
4. **`TorrentEngine` interface separated from day 1.** All other modules depend on a
   `TorrentEngine` interface in `:torrent` (add magnet/.torrent, metadata, files, priorities,
   pause/resume/remove, stats `Flow`, resume data) and never on jlibtorrent types. The interface
   includes, from the first commit, the hooks needed for **stream-while-downloading** (dynamic
   piece prioritisation / deadlines around a playback position plus a read-ahead buffer), even
   though that feature is implemented after complete downloads work. A `FakeTorrentEngine` backs
   all tests above the torrent module.
5. **Local HTTP server** embedded in the app on `0.0.0.0:8787` (configurable port, LAN only),
   with SSE or WebSocket for progress and PIN-pairing + bearer-token auth; **NSD/mDNS** for
   discovery. The concrete server library is chosen by its first task.
6. **Assistant:** Android `TextToSpeech`; translation behind a swappable `TranslationProvider`,
   not on the MVP critical path.

## Consequences

- libVLC adds ~20-30 MB per ABI; ABI splits/App Bundles are configured in `infra`.
- jlibtorrent native libs must be packaged per ABI; its alert loop runs on its own thread and is
  bridged to coroutines/Flows inside `:torrent`.
- Replacing either native library later is contained to one module each (`:player`, `:torrent`).
- Stream-while-downloading does not require an interface change later, only an implementation.
