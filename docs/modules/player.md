# Module: player

**Gradle path:** `:player`.

## Responsibility
- libVLC Android (stable 3.x) playback of a local file (and later a growing file for stream-while-downloading).
- Hardware decoding, internal/external subtitles, multiple audio tracks, track selection persisted per item.
- Resume from last position; expose position/time as `Flow` for the assistant.
- Hidden EN subtitle mode: the assistant reads cues while the on-screen subtitles stay off.
- `com.teachermovies.player.api`: the `Player` contract and its value types -- `PlayerState` (sealed: `Idle`/`Opening`/`Playing`/`Paused`/`Ended`/`Error`) and `Track(id, name, language)`. State, position, duration, both track lists and both selections are `StateFlow`s; the controls are `open(file, startPositionMs)` (resume is a caller decision, from the position `:core-model` persisted), `play`/`pause`/`togglePlayPause`, `seekTo`/`seekBy`, `selectAudio`/`selectSubtitle`, `addExternalSubtitle(file, select)` and `release`. `com.teachermovies.player.fake.FakePlayer` is the in-memory implementation, with test controls that stand in for what the real player learns from the media (`emitTracks`, `emitPosition`, `emitDuration`, `end`, `fail`).

## Boundaries
- Only this module touches `org.videolan.libvlc` types; exposes a `Player` interface to `app-tv`/`assistant`. Other modules use `FakePlayer` in tests.

## Tests
JVM tests for track-selection and resume logic behind the interface (`FakePlayerTest` covers the fake's own behaviour, which is what those tests rest on); playback itself is instrumented/manual.
