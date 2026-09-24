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

## Boundaries
- Depends on feature modules' public interfaces only; contains no torrent, HTTP or VLC logic itself.
- All screens fully usable with the D-pad; focus order and initial focus are acceptance criteria.

## Tests
ViewModel unit tests with fakes (`FakeTorrentEngine`, in-memory repositories). Compose UI tests are instrumented and optional.
