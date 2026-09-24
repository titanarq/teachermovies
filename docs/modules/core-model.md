# Module: core-model

**Gradle path:** `:core-model`.

## Responsibility
- Domain types: `Torrent`, `TorrentFile`, `DownloadState` (`FetchingMetadata, Queued, Downloading, Paused, Verifying, Completed, Error`), `LibraryItem`, `PlaybackState`, `SubtitleCue`.
- `DownloadState.canTransitionTo(next)` is the single place that decides whether a lifecycle move is legal; staying in the current state is always allowed. Callers (torrent engine mapping, HTTP pause/resume endpoints) check it instead of keeping their own table.
- Room database, entities and DAOs: info-hash, name, path, progress, main file, chosen audio track, chosen subtitle, last playback position.
  Package `com.teachermovies.core.db`: `TeacherMoviesDatabase` (file `teachermovies.db`,
  `TeacherMoviesDatabase.build(context)`), table `torrents` = `TorrentEntity` keyed by `infoHash`
  (`state` stores the `DownloadState` name), and `TorrentDao` (`upsert`, `get`, `observeAll`
  newest first by `addedAtEpochMs`, `observeByState`, `updateProgress`, `updatePlayback`,
  `delete`). Schemas are exported to `core-model/schemas/` and committed.
- DataStore-backed settings (HTTP port, download volume, auth token hash, assistant preferences):
  `SettingsRepository` is the only read/write path -- `settings: Flow<AppSettings>` plus one setter
  per field -- and `DataStoreSettingsRepository` implements it over DataStore Preferences, with
  `Context.settingsDataStore()` creating the production store (file name `settings`). A rejected
  write leaves the stored value untouched; `setHttpPort` is where the 1024..65535 rule lives.
- Repository interfaces that other modules implement or consume.

## Boundaries
- No dependency on any other project module. No Android UI types. Room only stores metadata, never media.
- Schema changes need a Room migration and an exported schema JSON.

## Tests
JVM tests for domain logic; Room DAO tests via Robolectric or in-memory DB. Settings tests need no
Android runtime: `PreferenceDataStoreFactory.create` over a file in a `TemporaryFolder` is a plain
JVM store.
