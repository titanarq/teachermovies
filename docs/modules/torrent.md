# Module: torrent

**Gradle path:** `:torrent`.

## Responsibility
- `TorrentEngine` interface (see ADR-0001): add magnet / .torrent, metadata, file list, per-file priorities, pause/resume/remove, stats `Flow` (progress, speed, peers, ETA, ratio), resume data.
- jlibtorrent 2.x implementation (`JLibTorrentEngine`), running in a foreground service independent from the player.
- Automatic priority 0 for samples/images/extras; download only the movie file and `.srt`/`.ass`.
- Persisted resume data so downloads continue after reboot.
- Designed from day 1 for stream-while-downloading: piece-deadline / sequential window API around a playback position (`prioritizeWindow(fileIndex, byteOffset, windowBytes)`), even if phase 8 implements it later.
- `com.teachermovies.torrent.policy`: pure Kotlin status-mapping policy, kept separate from any adapter so it stays engine-agnostic. `RawStatus`/`RawPhase` mirror libtorrent's `torrent_status::state_t` without referencing it; `DownloadStateMapper.map` derives the user-visible `DownloadState`; `etaSeconds` computes the remaining-time estimate. `FileSelectionPolicy.select(files: List<TorrentFileInfo>): Selection` picks the main video file (largest video whose path doesn't look like a sample/trailer/extra) and returns per-file `FilePriority`: the main file and any subtitle track at `Normal`, everything else at `Skip`. The jlibtorrent adapter (#52) is the only caller of both.
- `com.teachermovies.torrent.api.MagnetUri`: parses `xt=urn:btih:<40 hex or 32 base32>` (plus `dn`) into a lower-case hex info-hash, shared by `FakeTorrentEngine` and the real jlibtorrent engine (#51) so both validate magnets identically.
- `com.teachermovies.torrent.fake.FakeTorrentEngine` (ADR-0003): a deterministic, in-memory `TorrentEngine` in the **main** source set, so every module above `:torrent` can drive it in tests. State lives in `MutableStateFlow`s; test-only control methods (`emitMetadata`, `advance`, `complete`, `fail`, `setEngineStatus`) and `recordedCalls` (for `prioritizeWindow`/`clearWindow` assertions) drive it deterministically -- no real time, no sleeps.

## Boundaries
- No jlibtorrent type escapes this module. Other modules use `FakeTorrentEngine` in tests.

## Tests
JVM tests against the interface contract with `FakeTorrentEngine`; file-selection heuristics unit-tested; jlibtorrent integration tests optional/instrumented. `com.teachermovies.torrent.api.TorrentEngineContractTest` (in `src/test`) is the reusable, implementation-agnostic contract (`abstract fun createEngine(): TorrentEngine`); `FakeTorrentEngineTest` extends it for the fake, and the jlibtorrent-backed engine (#51) is expected to extend it too.
