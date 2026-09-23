# Module: assistant

**Gradle path:** `:assistant`.

## Responsibility
- Subtitle Engine: parse SRT/ASS, keep the current cue in sync with player time (`currentSubtitle`).
- Remote-control actions: pause + capture current cue, replay original fragment, EN TTS, ES translation + TTS.
- Android `TextToSpeech` wrapper; `TranslationProvider` interface with swappable implementations (AI API optional, NOT on the MVP critical path).

## Boundaries
- Depends on the `Player` interface, never on libVLC directly. Network translation must degrade gracefully when offline.

## Tests
JVM tests for SRT/ASS parsing, cue lookup by time, fragment boundaries; fake TTS/translation.
