# Module: core-model

**Gradle path:** `:core-model`.

## Responsibility
- Domain types: `Torrent`, `TorrentFile`, `DownloadState` (`FetchingMetadata, Queued, Downloading, Paused, Verifying, Completed, Error`), `LibraryItem`, `PlaybackState`, `SubtitleCue`,
  `SubtitleFetch` + `SubtitleFetchState` (one movie's automatic-subtitle search in one language) and
  `SubtitleAlignment` (how a Spanish file lines up with a movie's timeline).
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
  newest first by `addedAtEpochMs`, `observeByState`, `observeLibrary`, `updateProgress`, `updatePlayback`,
  `delete`). Schemas are exported to `core-model/schemas/` and committed.
  Version 2 (#274, ADR-0005 §5/§6/§8) adds four tables with a DAO each, and `build(context)` installs
  `MIGRATION_1_2` (`Migrations.kt`), so an upgrade keeps every torrent row and the new tables simply
  start empty: `translation_cache` = `TranslationCacheEntity` keyed by the normalized English source
  text, `explanation_cache` = `ExplanationCacheEntity` keyed by (movie title, line, prompt version) and
  holding the bridge's explanation JSON verbatim, `subtitle_fetch_state` = `SubtitleFetchStateEntity`
  keyed by (info-hash, language) with `state` storing a `SubtitleFetchState` name, and
  `subtitle_alignment` = `SubtitleAlignmentEntity` keyed by (info-hash, subtitle path).
  `Migration1To2Test` rebuilds a real
  version 1 file from the committed `1.json` and migrates it, which is what keeps the migration's DDL
  and the exported `2.json` in step (Room refuses to open a database a migration left misshapen).
- The four version-2 tables each get a repository, on the same interface / `Room*` / `InMemory*` split
  as `TorrentRepository` (ADR-0003), with a shared `*ContractTest` holding the behaviour both
  implementations must satisfy. `TranslationCacheRepository` (`translationOf(sourceText, now)` /
  `store(...)`) and `ExplanationCacheRepository` (`explanationFor(movieTitle, line, promptVersion,
  now)` / `store(...)`, keyed by the prompt version too so a new bridge prompt starts fresh answers
  instead of serving an older prompt's) cache only successes (ADR-0005 §8 -- a blank answer stores
  nothing) and are bounded by
  `CachePruningPolicy` (5000 rows / 90 days by default), applied on every write: rows last used before
  the age limit go first, then the least recently used ones above the row cap. A read only refreshes
  `lastUsedAtEpochMs` and `hitCount`, which is what keeps a re-watched line alive, and every cache key
  goes through `normalizeCacheText` so one line is one row whatever padding its `.srt` had.
  `SubtitleFetchRepository` (`observeAll`, `get`, `dueForFetch(now)`, `save`, `ensurePending`,
  `delete`, `deleteForTorrent`) stores the search state; whether a row is due is `SubtitleFetch.isDue`
  -- one rule for both implementations -- with ADR-0005 §5's seven-day not-found retry as
  `SubtitleFetch.NOT_FOUND_RETRY_MS`. `SubtitleAlignmentRepository` (`get`, `bestFor`, `save`,
  `delete`, `deleteForTorrent`) stores alignments, and `SubtitleAlignment.spanishMsFor(enMs)` applies
  the offset and frame-rate scale of ADR-0005 §6; how good a score has to be before it wins over a
  machine translation is #283's call. Consumers are #280/#283 (fetch state, alignment), #287
  (translation cache) and #292 (explanation cache): this module owns the schema and empty repositories,
  not their read/write logic.
- `TorrentRepository` (`com.teachermovies.core.repo`) is the domain-level read/write path over
  `TorrentDao`: `observeDownloads()`/`observeLibrary()` split torrents by `DownloadState.Completed`
  (library = completed once -- `completedAtEpochMs` set -- with a known main file, whatever the
  current state: paused, re-checking or seeding rows stay listed and only `delete` removes them,
  #247; `getLibraryItem` follows the same rule; newest completed first), `upsert(torrent,
  mainFilePath, now)` keeps an existing row's `addedAtEpochMs` and stamps `completedAtEpochMs` only
  the first time a torrent's state becomes `Completed`, and `updatePlayback`/`delete` round out the
  contract. `RoomTorrentRepository` implements it over Room (an unknown persisted `state` string
  maps to `DownloadState.Error`); `InMemoryTorrentRepository` in
  `com.teachermovies.core.repo.fake` is the deterministic fake other modules' tests use (ADR-0003).
  It does not keep itself in sync with the torrent engine -- that mapping is #68.
- DataStore-backed settings (HTTP port, download volume, auth token hashes -- one set for
  phone-scoped pairing tokens and a separate `bridgeTokenHashes` for bridge-scoped ones, each with
  its own add method (#270, ADR-0005 §4); `clearBridgeTokenHashes()` forgets the bridge set and
  `bridgeDeviceName` -- the name the paired bridge gave, set by `setBridgeDeviceName` (blank
  clears) -- while leaving the phone set alone ("Olvidar portátil", #289) -- first-run done,
  `autostartOnBoot` -- open the app when the TV powers on, off by default -- `translationApiKey` --
  the user's own Anthropic API key for EN->ES translation, null by default, cleared by
  `setTranslationApiKey(null)` or a blank string, never logged and redacted from
  `AppSettings.toString()`; dormant since #290 (ADR-0005 §9): nothing sets or reads it any more
  and `Context.settingsDataStore()` runs `ClearTranslationApiKeyMigration` on open, so a key stored
  before the upgrade is removed from the file before the first read -- assistant preferences):
  `SettingsRepository` is the only read/write path -- `settings: Flow<AppSettings>` plus one setter
  per field -- and `DataStoreSettingsRepository` implements it over DataStore Preferences, with
  `Context.settingsDataStore()` creating the production store (file name `settings`). A rejected
  write leaves the stored value untouched; `setHttpPort` is where the 1024..65535 rule lives.
  `InMemorySettingsRepository` in `com.teachermovies.core.settings.fake` (main source set,
  ADR-0003) is the deterministic fake over a `MutableStateFlow<AppSettings>` that other modules'
  JVM tests use; it applies the same port rule and the same null/blank-clears key rule.
- App logging (ADR-0006), package `com.teachermovies.core.log`: `AppLog` is the one facade every
  module logs through -- `d`/`i`/`w`/`e(module, message[, error])` -- and an accepted global, the
  exception to ADR-0003's constructor injection, because a logger handed through constructors would
  touch every class in the app. It filters on `minLevel` (INFO by default; a debug build lowers it),
  reads `timeMs` from a replaceable `clock`, numbers every accepted line into a
  `LogEntry(seq, timeMs, level, module, message)` and hands it to each installed `LogSink`
  (`AppContainer` installs them, #268). It does not redact: the facade keeps the text it is given.
  `RingBufferLogSink` is the in-memory buffer the HTTP API serves (#269). On the way in it redacts
  through `LogRedactor` and cuts a message to 2 KiB, marked `[truncated]`; it keeps the newest
  5000 lines / 1 MiB of message text and evicts the oldest beyond either cap. It publishes each
  stored line on `entries: SharedFlow<LogEntry>` (no replay, slow collectors lose the oldest line)
  and pages the buffer back with `entries(since, minLevel, limit)`: oldest first, `since` being the
  last `seq` the caller got, 500 lines to a page by default. `bootId` identifies the process, and a
  reader that sees it change discards its cursor because `seq` started over.
  `LogRedactor` replaces the values of `Authorization`/`Bearer`/`token=`/`password=`/`Api-Key`/
  `OPENSUBTITLES_*`, `sk-ant-` Anthropic keys, JWTs, PINs and the 43-character base64url token
  `PairingManager` issues, plus any secret registered with `registerSecret` (the current PIN), with
  `[REDACTED]`, keeping the key that named the value. Redaction is idempotent and is a second line
  of defence, not a licence to log a secret (AGENTS.md).
  `RecordingLogSink` in `com.teachermovies.core.log.fake` (main source set, ADR-0003) is the
  deterministic sink other modules' JVM tests use: it keeps every entry it is handed and invents
  nothing.
- Repository interfaces that other modules implement or consume.

## Boundaries
- No dependency on any other project module. No Android UI types. Room only stores metadata, never media.
- Schema changes need a Room migration and an exported schema JSON.
- `com.teachermovies.core.log` is pure Kotlin/JVM (no Android type), so any module -- including the
  future pure-JVM ones -- can log through `AppLog`, and the log tests run with no Android runtime.

## Tests
JVM tests for domain logic; Room DAO tests via Robolectric or in-memory DB. Settings tests need no
Android runtime: `PreferenceDataStoreFactory.create` over a file in a `TemporaryFolder` is a plain
JVM store. Log tests need none either: the redactor is table-tested (including the lines that must
survive untouched), the ring buffer's caps, truncation and paging are asserted directly, and
`AppLog` is tested through `RecordingLogSink` with a fixed clock. A test that collects the sink's
`SharedFlow` launches the collector on `UnconfinedTestDispatcher(testScheduler)`: on the standard
test dispatcher it is not subscribed yet when the lines are recorded, and a `SharedFlow` with no
replay keeps nothing for a latecomer. `Migration1To2Test` is the pattern for a schema bump: it
rebuilds the previous version's file from the committed schema JSON (no second copy of the
entities, no `room-testing` dependency), migrates it, and asserts both that the old rows survived
and that the exported JSON of the new version describes the tables the migration created.
