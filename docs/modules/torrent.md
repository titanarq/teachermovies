# Module: torrent

**Gradle path:** `:torrent`.

## Responsibility
- `TorrentEngine` interface (see ADR-0001): add magnet / .torrent, metadata, file list, per-file priorities, pause/resume/remove, stats `Flow` (progress, speed, peers, ETA, ratio), resume data.
- jlibtorrent 2.x implementation (`JLibTorrentEngine`), running in a foreground service independent from the player.
- Automatic priority 0 for samples/images/extras; download only the movie file and `.srt`/`.ass`.
- Persisted resume data so downloads continue after reboot.
- Designed from day 1 for stream-while-downloading: piece-deadline / sequential window API around a playback position (`prioritizeWindow(fileIndex, byteOffset, windowBytes)`), even if phase 8 implements it later.

## Boundaries
- No jlibtorrent type escapes this module. Other modules use `FakeTorrentEngine` in tests.

## Tests
JVM tests against the interface contract with `FakeTorrentEngine`; file-selection heuristics unit-tested; jlibtorrent integration tests optional/instrumented.
