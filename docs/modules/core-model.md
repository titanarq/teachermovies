# Module: core-model

**Gradle path:** `:core-model`.

## Responsibility
- Domain types: `Torrent`, `TorrentFile`, `DownloadState` (`FetchingMetadata, Queued, Downloading, Paused, Verifying, Completed, Error`), `LibraryItem`, `PlaybackState`, `SubtitleCue`.
- `DownloadState.canTransitionTo(next)` is the single place that decides whether a lifecycle move is legal; staying in the current state is always allowed. Callers (torrent engine mapping, HTTP pause/resume endpoints) check it instead of keeping their own table.
  The rule: a move is legal unless it would undo something the engine cannot undo. Metadata is
  never lost once known, so the only rejected moves are `Downloading -> FetchingMetadata`,
  `Verifying -> FetchingMetadata` and `Completed -> FetchingMetadata`; every other move is allowed,
  because `DownloadStateMapper` really produces them (e.g. `Queued -> Verifying`,
  `FetchingMetadata -> Completed` for a re-added finished torrent, `Completed -> Downloading` when a
  skipped file is un-skipped, and `Error`/`Paused`/`Queued` from any state). The jlibtorrent ticker
  publishes the engine's own state as the mapper derives it rather than gating it through this
  table.
- Room database, entities and DAOs: info-hash, name, path, progress, main file, chosen audio track, chosen subtitle, last playback position.
  Package `com.teachermovies.core.db`: `TeacherMoviesDatabase` (file `teachermovies.db`,
  `TeacherMoviesDatabase.build(context)`), table `torrents` = `TorrentEntity` keyed by `infoHash`
  (`state` stores the `DownloadState` name), and `TorrentDao` (`upsert`, `get`, `observeAll`
  newest first by `addedAtEpochMs`, `observeByState`, `updateProgress`, `updatePlayback`,
  `delete`). Schemas are exported to `core-model/schemas/` and committed.
- `TorrentRepository` (`com.teachermovies.core.repo`) is the domain-level read/write path over
  `TorrentDao`: `observeDownloads()`/`observeLibrary()` split torrents by `DownloadState.Completed`
  (library = completed with a known main file, newest completed first), `upsert(torrent,
  mainFilePath, now)` keeps an existing row's `addedAtEpochMs` and stamps `completedAtEpochMs` only
  the first time a torrent's state becomes `Completed`, and `updatePlayback`/`delete` round out the
  contract. `RoomTorrentRepository` implements it over Room (an unknown persisted `state` string
  maps to `DownloadState.Error`); `InMemoryTorrentRepository` in
  `com.teachermovies.core.repo.fake` is the deterministic fake other modules' tests use (ADR-0003).
  It does not keep itself in sync with the torrent engine -- that mapping is #68.
- DataStore-backed settings (HTTP port, download volume, auth token hash, first-run done,
  `autostartOnBoot` -- open the app when the TV powers on, off by default -- assistant preferences):
  `SettingsRepository` is the only read/write path -- `settings: Flow<AppSettings>` plus one setter
  per field -- and `DataStoreSettingsRepository` implements it over DataStore Preferences, with
  `Context.settingsDataStore()` creating the production store (file name `settings`). A rejected
  write leaves the stored value untouched; `setHttpPort` is where the 1024..65535 rule lives.
  `InMemorySettingsRepository` in `com.teachermovies.core.settings.fake` (main source set,
  ADR-0003) is the deterministic fake over a `MutableStateFlow<AppSettings>` that other modules'
  JVM tests use; it applies the same port rule.
- Repository interfaces that other modules implement or consume.

## Boundaries
- No dependency on any other project module. No Android UI types. Room only stores metadata, never media.
- Schema changes need a Room migration and an exported schema JSON.

## Tests
JVM tests for domain logic; Room DAO tests via Robolectric or in-memory DB. Settings tests need no
Android runtime: `PreferenceDataStoreFactory.create` over a file in a `TemporaryFolder` is a plain
JVM store.
