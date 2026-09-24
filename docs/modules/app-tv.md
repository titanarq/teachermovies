# Module: app-tv

**Gradle path:** `:app-tv` (the Android TV application module).

## Responsibility
- Application entry point (`Application`, launcher `Activity` with `LEANBACK_LAUNCHER`).
- Compose for TV UI and navigation: `Library`, `Downloads`, `Settings`, first-run setup, player screen host.
- First-run screen shows `Server: <ip>:8787`, pairing PIN, free space, download folder, torrent engine state.
- Wiring (DI) of every feature module; starts the foreground torrent/HTTP service.

## Configuración (#45)
- `SettingsViewModel(settings: SettingsRepository, volumes: StorageVolumeProvider, space: SpaceProvider)`
  exposes `StateFlow<SettingsUiState(httpPort, volumes: List<VolumeRow>, selectedVolumeId, volumeMissing, portError)>`
  and `changePort(Int)` (1024..65535, otherwise `portError` and nothing saved), `selectVolume(id)`,
  `refreshVolumes()`. The selected volume comes from `VolumeSelector`; while the persisted one is
  missing it is the fallback and `volumeMissing` is true (the persisted id is not overwritten).
- `SettingsScreen`: port field on the left (UP/DOWN +/-1, OK opens a numeric text field, BACK
  cancels it), radio-style volume list on the right (`X,Y GB libres de Z GB`). RIGHT/LEFT move
  between them; entry focus is the port field; BACK returns to the tab row.
- The shell takes the section as a slot; `MainActivity` builds the ViewModel from `AppContainer`.

## First run (#46)
- `com.teachermovies.tv.net.LanAddressResolver`: pure `pick(List<NetIf>)` returns the first
  site-local IPv4 of an up, non-loopback interface, `eth*` before `wlan*` before the rest;
  `current()` applies it to `NetworkInterface.getNetworkInterfaces()` (null = no network).
- `FirstRunViewModel(settings, volumes, space, engine: TorrentEngine, lan)` exposes
  `StateFlow<FirstRunUiState(serverUrl /* http://ip:port */, freeBytes, downloadFolder, engineStatus)>`,
  `firstRunCompleted: StateFlow<Boolean?>` (null until settings are read), `refresh()` (LAN address
  and volumes, called each time the screen is shown) and `complete()` (`setFirstRunCompleted(true)`).
  The volume is the one `VolumeSelector` picks; the folder is its `DownloadLayout.moviesDir()`.
- `MainActivity` shows `FirstRunScreen` instead of the shell while `firstRunCompleted == false`.
  `Continuar` is its only focusable element and has initial focus; BACK leaves the app.

## Torrent engine wiring (#55)
- `AppContainer.torrentEngine: TorrentEngine` is a `JLibTorrentEngine` (the only
  `com.teachermovies.torrent.jlib` import in the app) with `stateDir = filesDir/torrent-state`,
  `Dispatchers.IO`, and registered in `TorrentEngineHolder` when the container is built.
- `SavePathProviderFactory.create(volumes, space, persistedVolumeId, fallbackRoot)` returns
  `DownloadLayout(selectedVolume.root).torrentDir(id.value)`; the volume is re-selected by
  `VolumeSelector` on every call (missing persisted volume -> its fallback; no volume at all ->
  `fallbackRoot` = `filesDir`).
- `MainActivity.onCreate` calls `TorrentService.start(this)` and, on API 33+, requests
  `POST_NOTIFICATIONS` once (remembered in the activity's preferences; denial is not an error).

## Descargas: formatters and view model (#69)
- `com.teachermovies.tv.format.Formatters`: `bytes`/`speed` (decimal 1000-based B/KB/MB/GB/TB, one
  decimal, Spanish comma), `sizeText(downloaded, total)` (both numbers scaled to one shared unit,
  e.g. `"18,4 / 25,6 GB"`), `eta(seconds?)` (`"1 h 2 min"` / `"2 min 5 s"` / `"59 s"` / `"—"` for
  `null`), `percent(Double)` (rounded, `"72 %"`), `ratio(Double)` (two decimals, `"0,46"`) and
  `stateLabel(DownloadState)` (the Spanish label shown for each state). No raw number or state name
  reaches Compose; every one of these is a pure function covered by `FormattersTest`.
- `DownloadsViewModel(engine: TorrentEngine, sync: EngineRepositorySync, space: () -> SpaceInfo?)`
  exposes `StateFlow<DownloadsUiState(rows: List<DownloadRow>, freeSpace: String?)>`, mapped
  straight from `TorrentEngine.torrents`. `DownloadRow` carries the raw `DownloadState` (so the
  screen can call `Formatters.stateLabel` and style per state) plus every other field already
  formatted, and `canPause`/`canResume`, derived from `DownloadState.canTransitionTo(Paused)` (a
  completed, still-seeding torrent can be paused too). `pause`/`resume` call `TorrentEngine`
  directly; `remove(id, deleteFiles)` goes through `EngineRepositorySync.remove` so the persisted
  row is deleted only once the engine confirms it. `space` is a snapshot, not a stream, re-read on
  every `torrents` update. Does not build the screen itself (#70).
- `AppContainer.torrentRepository: TorrentRepository` (`RoomTorrentRepository` over
  `TeacherMoviesDatabase.build(application)`) and `AppContainer.engineRepositorySync:
  EngineRepositorySync` (#68), started when the container is built, on its own
  application-lifetime `CoroutineScope`.

## Biblioteca (#72)
- `LibraryViewModel(repo: TorrentRepository)` exposes `StateFlow<LibraryUiState(items: List<LibraryCard>)>`
  mapped from `observeLibrary()` (newest completed first). `LibraryCard(id, title, sizeText,
  resumeText?)`: `sizeText` is `Formatters.bytes(sizeBytes)`, `resumeText` is
  `"Continuar en " + Formatters.eta(lastPositionMs / 1000)` when `lastPositionMs > 0`, else null.
- `LibraryScreen`: `LazyVerticalGrid` of tv `Card`s (title, ▶ `100 %`, size, optional resume text);
  DOWN from the tab row enters on the first card (`focusRestorer`, then the last focused card); OK
  calls `onPlay(id)`. No movies -> `Tu biblioteca está vacía`, nothing focusable (focus stays on
  the tab row). The shell takes it as the `libraryContent` slot.
- Navigation: `MainUiState.route: AppRoute` is `Shell` or `Player(id)` (`player/{id}`);
  `MainViewModel.openPlayer(id)` / `closePlayer()`. `MainActivity` shows `PlayerPlaceholderScreen`
  (`Reproductor pendiente`, BACK -> `closePlayer`) instead of the shell for `Player`; the real
  player replaces it in #78.

## Boundaries
- Depends on feature modules' public interfaces only; contains no torrent, HTTP or VLC logic itself.
- All screens fully usable with the D-pad; focus order and initial focus are acceptance criteria.

## Tests
ViewModel unit tests with fakes (`FakeTorrentEngine`, in-memory repositories). Compose UI tests are instrumented and optional.
