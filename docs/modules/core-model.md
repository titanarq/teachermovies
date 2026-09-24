# Module: core-model

**Gradle path:** `:core-model`.

## Responsibility
- Domain types: `Torrent`, `TorrentFile`, `DownloadState` (`FetchingMetadata, Queued, Downloading, Paused, Verifying, Completed, Error`), `LibraryItem`, `PlaybackState`, `SubtitleCue`.
- `DownloadState.canTransitionTo(next)` is the single place that decides whether a lifecycle move is legal; staying in the current state is always allowed. Callers (torrent engine mapping, HTTP pause/resume endpoints) check it instead of keeping their own table.
- Room database, entities and DAOs: info-hash, name, path, progress, main file, chosen audio track, chosen subtitle, last playback position.
- DataStore-backed settings (HTTP port, download volume, auth token hash, assistant preferences).
- Repository interfaces that other modules implement or consume.

## Boundaries
- No dependency on any other project module. No Android UI types. Room only stores metadata, never media.
- Schema changes need a Room migration and an exported schema JSON.

## Tests
JVM tests for domain logic; Room DAO tests via Robolectric or in-memory DB.
