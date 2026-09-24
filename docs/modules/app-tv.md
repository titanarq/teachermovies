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

## Boundaries
- Depends on feature modules' public interfaces only; contains no torrent, HTTP or VLC logic itself.
- All screens fully usable with the D-pad; focus order and initial focus are acceptance criteria.

## Tests
ViewModel unit tests with fakes (`FakeTorrentEngine`, in-memory repositories). Compose UI tests are instrumented and optional.
