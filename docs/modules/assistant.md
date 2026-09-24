# Module: assistant

**Gradle path:** `:assistant`.

## Responsibility
- Subtitle Engine: parse SRT/ASS, keep the current cue in sync with player time (`currentSubtitle`).
- `com.teachermovies.assistant.subtitles.SubtitleIndex(track: SubtitleTrack)`: `cueAt(positionMs): SubtitleCue?` binary-searches the cues sorted by `startMs` for the one with the greatest `startMs` that is `<= positionMs` (the same candidate wins when cues overlap, as ASS allows), then checks it still covers the half-open `[startMs, endMs)` range; `null` in a gap, before the first cue, after the last one, or for an empty track.
- `com.teachermovies.assistant.SubtitleEngine(positionMs: Flow<Long>, scope: CoroutineScope)`: collects `positionMs` on `scope` for as long as it stays alive and exposes `val currentSubtitle: StateFlow<SubtitleCue?>`, resolved through a `SubtitleIndex` built from whatever `SubtitleTrack` `load(track: SubtitleTrack?)` last set (`load(null)` clears it back to `null`). `load` also re-resolves `currentSubtitle` immediately against the last known position, without waiting for the next `positionMs` emission. `StateFlow`'s conflation is what keeps `currentSubtitle` from re-emitting while the position moves within the same cue (or the same gap): only an actual cue change gets a new value. The real caller (#83) is `Player.positionMs`; tests drive it from a plain `MutableStateFlow<Long>`.
- `com.teachermovies.assistant.subtitles.SubtitleParsers.parse(file: File): ParseResult`: dispatches on the file name to the `.srt`/`.ass` parser that supports it and never lets an exception escape; `ParseResult` is `Parsed(track)`, `Unsupported(extension)` or `Malformed(reason)`, and a supported file that yields zero cues is `Malformed` too.
- `com.teachermovies.assistant.subtitles.SidecarSubtitles.findFor(mediaFile: File, language: String = "en"): File?`: the standalone subtitle file that belongs to a movie. It searches, case-insensitively, the media file's own directory and a `Subs` subdirectory of it (the layout most torrents ship), preferring `<base>.<language>.<ext>` over `<base>.<ext>` over any `*.<language>.<ext>`, then `.srt` over `.ass`, then the media's own directory over `Subs`, then the name, so the answer does not depend on the order the filesystem happens to list files in. `null` when nothing matches; a directory that does not exist contributes no candidates instead of throwing.
- `com.teachermovies.assistant.HiddenSubtitleController(player: Player, engine: SubtitleEngine, scope: CoroutineScope)`: hidden EN subtitle mode (VISION §7, "Mostrar subtítulos: No") -- the assistant reads the movie's English cues in sync with playback while the player draws nothing on screen. `start(mediaFile: File, language: String = "en"): HiddenModeResult` resolves the sidecar file with `SidecarSubtitles`, parses it with `SubtitleParsers`, hands the track to `engine.load` and calls `player.selectSubtitle(null)`, returning `HiddenModeResult.Started`; it returns `NoSubtitleFile` when no sidecar exists for that language and `Unreadable(reason)` when the file cannot be turned into cues, and both failures leave the player's own subtitle selection untouched and `active` false. `start` stops any previous session first.
- While `HiddenSubtitleController.active: StateFlow<Boolean>` is true, the controller collects `player.selectedSubtitleId` on its `scope` and calls `player.selectSubtitle(null)` again on every non-null value, so a default-track policy in `:player` can never turn the on-screen subtitles back on behind the assistant's back. `stop()` clears the engine's track (`load(null)`), cancels that re-assertion and sets `active` false; it deliberately does not re-enable any subtitle track -- whether the viewer wants on-screen subtitles back is the caller's decision. The `SubtitleEngine` it is given is expected to have been built over the same player's `positionMs`: that is what makes `currentSubtitle` follow playback while hidden mode is on.
- Hidden mode needs a standalone `.srt`/`.ass` file: cues embedded in the video container are not reachable through the `Player` interface, so a movie without a sidecar gets `NoSubtitleFile`.
- Remote-control actions: pause + capture current cue, replay original fragment, EN TTS, ES translation + TTS.
- Android `TextToSpeech` wrapper; `TranslationProvider` interface with swappable implementations (AI API optional, NOT on the MVP critical path).

## Boundaries
- Depends on the `Player` interface, never on libVLC directly: `:assistant` has an `api` dependency on
  `:player` (a `Player` is a constructor parameter of `HiddenSubtitleController`, so whoever wires the
  assistant compiles against it) and uses only its public `com.teachermovies.player.api` package; no
  `org.videolan` type is referenced anywhere in the module, and tests drive it from
  `com.teachermovies.player.fake.FakePlayer`. Network translation must degrade gracefully when offline.

## Tests
JVM tests for SRT/ASS parsing, cue lookup by time, fragment boundaries; fake TTS/translation.
`SubtitleIndexTest` covers boundaries, gaps, overlaps and the empty track; `SubtitleEngineTest` drives
`SubtitleEngine` from a `MutableStateFlow<Long>` under `kotlinx-coroutines-test`'s `runTest`.
`SidecarSubtitlesTest` builds temporary directories to cover the discovery order, the `Subs` subdirectory,
case-insensitivity and the no-match and missing-directory cases; `HiddenSubtitleControllerTest` drives a
`FakePlayer` under `runTest` to cover subtitles forced off on start, re-assertion after an external
selection, cues following emitted positions, `NoSubtitleFile`, `Unreadable` and `stop`.
