# Module: assistant

**Gradle path:** `:assistant`.

## Responsibility
- Subtitle Engine: parse SRT/ASS, keep the current cue in sync with player time (`currentSubtitle`).
- `com.teachermovies.assistant.subtitles.SubtitleIndex(track: SubtitleTrack)`: `cueAt(positionMs): SubtitleCue?` binary-searches the cues sorted by `startMs` for the one with the greatest `startMs` that is `<= positionMs` (the same candidate wins when cues overlap, as ASS allows), then checks it still covers the half-open `[startMs, endMs)` range; `null` in a gap, before the first cue, after the last one, or for an empty track.
- `com.teachermovies.assistant.SubtitleEngine(positionMs: Flow<Long>, scope: CoroutineScope)`: collects `positionMs` on `scope` for as long as it stays alive and exposes `val currentSubtitle: StateFlow<SubtitleCue?>`, resolved through a `SubtitleIndex` built from whatever `SubtitleTrack` `load(track: SubtitleTrack?)` last set (`load(null)` clears it back to `null`). `load` also re-resolves `currentSubtitle` immediately against the last known position, without waiting for the next `positionMs` emission. `StateFlow`'s conflation is what keeps `currentSubtitle` from re-emitting while the position moves within the same cue (or the same gap): only an actual cue change gets a new value. The real caller (#83) is `Player.positionMs`; tests drive it from a plain `MutableStateFlow<Long>`.
- Remote-control actions: pause + capture current cue, replay original fragment, EN TTS, ES translation + TTS.
- Android `TextToSpeech` wrapper; `TranslationProvider` interface with swappable implementations (AI API optional, NOT on the MVP critical path).

## Boundaries
- Depends on the `Player` interface, never on libVLC directly. Network translation must degrade gracefully when offline.

## Tests
JVM tests for SRT/ASS parsing, cue lookup by time, fragment boundaries; fake TTS/translation.
`SubtitleIndexTest` covers boundaries, gaps, overlaps and the empty track; `SubtitleEngineTest` drives
`SubtitleEngine` from a `MutableStateFlow<Long>` under `kotlinx-coroutines-test`'s `runTest`.
