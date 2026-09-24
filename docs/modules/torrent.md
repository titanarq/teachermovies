# Module: torrent

**Gradle path:** `:torrent`.

## Responsibility
- `TorrentEngine` interface (see ADR-0001): add magnet / .torrent, metadata, file list, per-file priorities, pause/resume/remove, stats `Flow` (progress, speed, peers, ETA, ratio), resume data.
- jlibtorrent 2.x implementation (`JLibTorrentEngine`), running in a foreground service independent from the player.
- Automatic priority 0 for samples/images/extras; download only the movie file and `.srt`/`.ass`.
- Persisted resume data so downloads continue after reboot.
- Designed from day 1 for stream-while-downloading: piece-deadline / sequential window API around a playback position (`prioritizeWindow(fileIndex, byteOffset, windowBytes)`), even if phase 8 implements it later.
- `com.teachermovies.torrent.policy`: pure Kotlin status-mapping policy, kept separate from any adapter so it stays engine-agnostic. `RawStatus`/`RawPhase` mirror libtorrent's `torrent_status::state_t` without referencing it; `DownloadStateMapper.map` derives the user-visible `DownloadState`; `etaSeconds` computes the remaining-time estimate. The jlibtorrent adapter (#52) is the only caller.

## Boundaries
- No jlibtorrent type escapes this module. Other modules use `FakeTorrentEngine` in tests.

## Tests
JVM tests against the interface contract with `FakeTorrentEngine`; file-selection heuristics unit-tested; jlibtorrent integration tests optional/instrumented.
