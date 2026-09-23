# Module: player

**Gradle path:** `:player`.

## Responsibility
- libVLC Android (stable 3.x) playback of a local file (and later a growing file for stream-while-downloading).
- Hardware decoding, internal/external subtitles, multiple audio tracks, track selection persisted per item.
- Resume from last position; expose position/time as `Flow` for the assistant.
- Hidden EN subtitle mode: the assistant reads cues while the on-screen subtitles stay off.

## Boundaries
- Only this module touches `org.videolan.libvlc` types; exposes a `Player` interface to `app-tv`/`assistant`.

## Tests
JVM tests for track-selection and resume logic behind the interface; playback itself is instrumented/manual.
