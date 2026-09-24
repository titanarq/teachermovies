# Module: player

**Gradle path:** `:player`.

## Responsibility
- libVLC Android (stable 3.x) playback of a local file (and later a growing file for stream-while-downloading).
- Hardware decoding, internal/external subtitles, multiple audio tracks, track selection persisted per item.
- Resume from last position; expose position/time as `Flow` for the assistant.
- Hidden EN subtitle mode: the assistant reads cues while the on-screen subtitles stay off.
- `com.teachermovies.player.api`: the `Player` contract and its value types -- `PlayerState` (sealed: `Idle`/`Opening`/`Playing`/`Paused`/`Ended`/`Error`) and `Track(id, name, language)`. State, position, duration, both track lists and both selections are `StateFlow`s; the controls are `open(file, startPositionMs)` (resume is a caller decision, from the position `:core-model` persisted), `play`/`pause`/`togglePlayPause`, `seekTo`/`seekBy`, `selectAudio`/`selectSubtitle`, `addExternalSubtitle(file, select)` and `release`. `com.teachermovies.player.fake.FakePlayer` is the in-memory implementation, with test controls that stand in for what the real player learns from the media (`emitTracks`, `emitPosition`, `emitDuration`, `end`, `fail`).
- `com.teachermovies.player.policy`: pure functions a screen calls to decide what `open`/`selectAudio`/`selectSubtitle` to pass, so that decision is tested independently of any `Player` implementation. `TrackPolicy.audio(tracks, persistedId)` picks the persisted id when it names one of `tracks`, else the first track whose language or name looks English (`en`/`eng`/`english`, case-insensitive, word-bounded), else the first track, else `null` when `tracks` is empty. `TrackPolicy.subtitle(tracks, persistedId)` picks the persisted id when present, else `null` -- subtitles are off by default. `ResumePolicy.startPosition(lastPositionMs, durationMs)` returns `0` when `lastPositionMs` is under 10s or (given a known `durationMs`) within the last 60s of the media, else `lastPositionMs - 3s`.

## Boundaries
- Only this module touches `org.videolan.libvlc` types; exposes a `Player` interface to `app-tv`/`assistant`. Other modules use `FakePlayer` in tests.

## Tests
JVM tests for track-selection and resume logic behind the interface (`FakePlayerTest` covers the fake's own behaviour, which is what those tests rest on; `TrackPolicyTest`/`ResumePolicyTest` cover the policies above, table-driven); playback itself is instrumented/manual.
