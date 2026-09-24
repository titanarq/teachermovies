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

## Descargas screen (#70)
- `DownloadsViewModel(engine, sync, serverUrl: Flow<String?> = flowOf(null), space)`; `DownloadsUiState`
  also carries `serverUrl` (empty-state hint), `selectedRowId` and `dialog: DownloadsDialog?`
  (`Actions(items: List<DownloadActionItem>, pauseResumeLabel)` with `focused` = first enabled item,
  `ConfirmDelete`, `ChooseFiles`). `DownloadRow.progress` (0..1) sizes the progress bar.
  `openActions(id)`, `onAction(DownloadAction)` (`PauseResume` / `ChooseFiles` / `Delete` / `Cancel`;
  disabled ones are ignored), `confirmDelete(deleteFiles)`, `dismissDialog()` (keeps `selectedRowId`).
  The dialog is rebuilt from the current row on every update and closes when the row disappears.
- Actions: `Pausar`/`Reanudar` is enabled when `canPause || canResume` (an `Error` row offers
  `Pausar`, since `DownloadState` allows `Error -> Paused` after #145); a `Completed` row offers
  `Pausar`, which stops seeding. `Elegir archivos` is disabled while fetching
  metadata; its dialog is the file selection of #71 (below). `Borrar` asks
  `¿Borrar también los archivos?` (Sí = delete files, No = keep them, Cancelar), focus on Cancelar.
- `DownloadsScreen(viewModel, fileSelectionFactory: (TorrentId) -> ViewModelProvider.Factory)`: `Espacio libre: X` header, `LazyColumn` of tv `ListItem`s (name,
  percent + state label, size · speed · N peers · ETA · ratio, progress bar), or the empty state
  `No hay descargas. Envía un magnet desde el móvil a <serverUrl>`. Entry focus is the first row
  (`focusRestorer`), OK opens the action dialog; a focused row that disappears hands focus to the
  row now in its place. BACK closes a dialog.
- The shell takes the section as the `downloadsContent` slot. `AppContainer.downloadVolumeSpace()`
  is the free space of the volume `VolumeSelector` picks (persisted id cached, never blocks);
  `MainActivity` derives `serverUrl` from the settings' port and `LanAddressResolver`.

## File selection (#71)
- `FileSelectionViewModel(engine, id)` loads `engine.files(id)` into `FileSelectionRow(index, path,
  sizeText, checked = priority != Skip)`; `toggle(index)` flips a row locally; `apply()` sends
  `setFilePriorities` for every file (checked -> `Normal`, unchecked -> `Skip`) and sets `done`.
  `FileSelectionUiState(rows, loading, notReady, applying, error, done)`, `canApply`. `NotReady`
  shows `Esperando metadata…` and the load retries once the torrent snapshot reports metadata.
- `FileSelectionRoute(factory, onClose)` owns the ViewModel in its own `ViewModelStore` (fresh per
  opening). `FileSelectionDialog`: one tv `ListItem` + checkbox per file (OK toggles), `Aplicar` /
  `Cancelar`; entry focus on the first row (else `Cancelar`), DOWN from the last row lands on
  `Aplicar`; BACK cancels. `MainActivity` builds `FileSelectionViewModel.Factory` from
  `AppContainer.torrentEngine`.

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
  `MainViewModel.openPlayer(id)` / `closePlayer()`. `MainActivity` shows the player route (#78)
  instead of the shell for `Player`.

## Reproductor (#78)
- `com.teachermovies.tv.player.RemoteKeyMapper.map(keyCode): PlayerAction?` (pure):
  DPAD_CENTER/ENTER/MEDIA_PLAY_PAUSE/SPACE -> `TogglePlayPause`; MEDIA_PLAY -> `Play`; MEDIA_PAUSE ->
  `Pause`; DPAD_LEFT/RIGHT -> `SeekBy(∓10 000)`; MEDIA_REWIND/FAST_FORWARD -> `SeekBy(∓30 000)`;
  DPAD_UP/MENU -> `ShowTracks` (opens the track panel, #79); BACK -> `Exit`; others null.
- `PlayerViewModel(session: PlaybackSession, player: Player, closeScope: CoroutineScope? = null)`
  exposes `StateFlow<PlayerUiState(title, positionText, durationText, progress, isPlaying,
  overlayVisible, error, exited, tracksPanel)>` (times via `Formatters.playbackTime`, `1:05` / `1:02:05`).
  `open(id)` (first call only), `onAction(PlayerAction?)` (any key, even unmapped, shows the overlay
  for 4 s; actions are forwarded to `Player`; ignored once exiting), `exit()` (closes the session --
  position saved, player released -- then `exited = true`). `error`: `FileMissing` ->
  `El archivo no está disponible (¿se ha desconectado el disco?)`, `NotFound` -> `Esta película ya
  no está en la biblioteca`, `PlayerState.Error(m)` -> `No se puede reproducir: m`. Cleared without
  `exit()`, it closes the session on `closeScope`. `PlayerViewModel.Factory(player, repo)` builds the
  session on its own main-thread scope.
- `PlayerRoute(id, factory, surfaceHost, onExit)`: the ViewModel lives in a route-local
  `ViewModelStore` cleared when the route leaves composition (keyed by `route.route`, so each play is
  a fresh session). `PlayerScreen`: black full-screen `AndroidView(FrameLayout)` attached through
  `VideoSurfaceHost` (detached on dispose); the root box takes focus and consumes mapped keys
  (key-down and key-up), unmapped keys pass through; bottom overlay with title, progress bar and
  position/duration; `onExit` -> `MainViewModel.closePlayer()` back to Biblioteca.
- `AppContainer.player: Player` and `AppContainer.videoSurfaceHost: VideoSurfaceHost` are the same
  `VlcPlayer` (the only `com.teachermovies.player.vlc` import in the app).

## Pistas de audio y subtítulos (#79)
- `PlayerUiState.tracksPanel: TracksPanelState?` (null = closed, and while an error is shown).
  `TracksPanelState(audio, subtitles: List<TrackOption(id, label, selected)>)` is rebuilt from
  `Player.audioTracks`/`subtitleTracks`/`selectedAudioId`/`selectedSubtitleId` while open
  (`TracksPanelState.of`, pure); `subtitles` always starts with `Desactivados` (`id = null`). Labels
  are the track name, `name (lang)` when the name does not mention the language, else a fallback.
- `PlayerViewModel`: `ShowTracks` opens it; `selectAudio(id)` / `selectSubtitle(id?)` call `Player`
  and close it (the `PlaybackSession` persists the change); `back()` (BACK, and `Exit`) closes an
  open panel without changes, otherwise exits; `closeTracks()`.
- `ui.player.TracksPanel`: right-side panel with `Audio` and `Subtítulos` columns of tv `ListItem`s
  (radio mark on the active one). Opening focuses the marked audio row (marked subtitle row when
  there is no audio); LEFT/RIGHT switch columns (restoring onto the marked row); focus cannot leave
  the panel; OK selects and closes. While it is open the player root maps no key, so BACK reaches
  the back handler; focus returns to the root when it closes.

## Asistente: capturar la frase (#86)
- `com.teachermovies.tv.player.AssistantKeyMapper.map(keyCode, overlayOpen): AssistantAction?`
  (pure), `sealed interface AssistantAction { CaptureLine, ReplayFragment, DismissOverlay, Consumed }`.
  Overlay closed: DPAD_DOWN/CAPTIONS -> `CaptureLine`, others null (fall through to
  `RemoteKeyMapper`). Overlay open: DPAD_CENTER/ENTER/MEDIA_PLAY_PAUSE -> `ReplayFragment`;
  BACK/DPAD_DOWN/CAPTIONS -> `DismissOverlay`; everything else `Consumed` (swallowed, no effect).
  Only this mapper and the hint line change if the key assignment of #30 changes.
- `PlayerUiState` gains `assistant: AssistantOverlayState(text, replaying)?` (from
  `LineCaptureController.captured` cue text and `.replaying`; null when nothing is captured or an
  error is shown), `assistantAvailable` and `message` (shown in the transport overlay for 3 s).
- `PlayerViewModel(session, player, hidden: HiddenSubtitleController, capture:
  LineCaptureController, closeScope)`; `Factory(player, repo, hidden, capture)`. `open` calls
  `hidden.start(mainFile)`: `Started` -> `assistantAvailable = true`, otherwise false (never blocks
  playback). `onAssistantAction`: `CaptureLine` -> `capture()` (not available -> `Esta película no
  tiene subtítulos en inglés`, movie keeps playing; `NoLine` -> `No hay ninguna frase que capturar`
  and playback resumes), `ReplayFragment` -> `replay()`, `DismissOverlay` -> `dismiss()` (resumes).
  Transport actions are ignored while a line is captured; `back()` dismisses it; exit/clear stops
  hidden mode and drops the capture without resuming.
- `AppContainer` builds one `SubtitleEngine` (over `player.positionMs`), `hiddenSubtitleController`
  and `lineCaptureController` on a main-thread scope.
- `ui.player.AssistantOverlay`: dimmed bottom band over the video with the English line in a large
  size, `Repitiendo…` while replaying and the hint `OK Repetir · ATRÁS Cerrar`. It takes focus when
  it appears and handles every key (key-down through the mapper, key-ups swallowed); the transport
  overlay is hidden while it is visible and the player root takes focus back when it closes. The
  player root consults `AssistantKeyMapper` before `RemoteKeyMapper`.

## Asistente: escuchar y traducir la frase (#92)
- `AssistantAction` gains `SpeakOriginal` and `TranslateLine`. Overlay open: DPAD_RIGHT ->
  `SpeakOriginal`, DPAD_LEFT -> `TranslateLine` (the #86 mappings are unchanged; the rest is still
  `Consumed`). Overlay closed: both keys stay null, so RIGHT/LEFT keep seeking via `RemoteKeyMapper`.
- `AssistantOverlayState(text, replaying, speaking, translation: TranslationUiState, message)`:
  `speaking` and `translation` come from `AssistantSpeechController.state`; `message` is a transient
  overlay notice (3 s).
- `PlayerViewModel(session, player, hidden, capture, speech: AssistantSpeechController, closeScope,
  prepareDispatcher = Dispatchers.Default)`; `Factory(player, repo, hidden, capture, speech)`.
  `open` prepares the speaker once on `prepareDispatcher`, never awaited by playback; an unavailable
  speaker only removes the spoken answers. `SpeakOriginal` -> `speakOriginal(line)`, `false` ->
  `Voz no disponible` for 3 s and nothing else. `TranslateLine` -> `translateAndSpeak(line)`.
  `DismissOverlay` (and BACK, exit, clear) also calls `reset()`. None of these keys resumes the movie.
- `AppContainer`: `speaker = AndroidTextToSpeechSpeaker(application)`, translation provider
  `CachingTranslationProvider(NullTranslationProvider)` (no provider is configurable yet, #31),
  `assistantSpeechController` on the assistant's main-thread scope.
- `AssistantOverlay` under the English line: `Traduciendo…` (`Loading`), the Spanish text in amber
  (`Ready`), `Sin conexión para traducir` (`Failed(OFFLINE)`), `Traducción no disponible`
  (`Failed(UNAVAILABLE)`), nothing (`Idle`) -- `translationLine(state)`; then the message, `Hablando…`
  while `speaking`, `Repitiendo…` while replaying, and the hint
  `OK Repetir · DERECHA Escuchar · IZQUIERDA Traducir · ATRÁS Cerrar`. Focus behaviour is that of #86.

## HTTP server hosting and pairing PIN (#66)
- `AppContainer.pairingManager: PairingManager` (`SecureRandom`, wall clock) and
  `AppContainer.httpServerController: HttpServerController` over `LocalHttpServer`, on the
  application scope. Each (re)start gets fresh `ServerDeps`: `torrentEngine`,
  `space = downloadVolumeSpace` (the `FileSpaceProvider` of the selected volume), the package
  `versionName` (or `"unknown"`), and a `LayoutSubtitleStore` whose layout comes from
  `SubtitleLayoutResolver.layoutFor(id, engine.torrents.value)` (volume root read back from the
  torrent's `savePath` = `<root>/Movies/<id>`; null when unknown or not yet known).
- `TeacherMoviesApp.onCreate` calls `httpServerController.start()`; the foreground `TorrentService`
  keeps the process alive. The manifest declares `INTERNET` (no cleartext config: server only).
- `FirstRunViewModel(..., lan, pin: () -> String, serverState: StateFlow<ServerState>, pinTicks)` and
  `SettingsViewModel(settings, volumes, space, pin, serverState, serverUrl: Flow<String?> = flowOf(null), pinTicks)`;
  both UI states gain `pin` and `serverState` (Settings also `serverUrl`). The PIN is re-read on
  creation, on `refresh()`/`refreshVolumes()`, on every `serverState` change and on every
  `pinTicks` emission (default `pinRefreshTicker()`, every 15 s).
- `ui.server.ServerPinAndStatus` shows `PIN 482916` and, when not running, `Servidor detenido`,
  `Error: puerto en uso` (`ServerStatusLabel.of`: a failure reason containing "in use"/`EADDRINUSE`)
  or `Error: <reason>`. Plain text only; focus order on both screens is unchanged.

## Boundaries
- Depends on feature modules' public interfaces only; contains no torrent, HTTP or VLC logic itself.
- All screens fully usable with the D-pad; focus order and initial focus are acceptance criteria.

## Tests
ViewModel unit tests with fakes (`FakeTorrentEngine`, in-memory repositories). Compose UI tests are instrumented and optional.
