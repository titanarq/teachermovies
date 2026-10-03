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
- `SettingsScreen`: port field on the left (UP/DOWN move focus like on any row -- UP to the tab
  row -- and never change the port; OK opens a numeric text field whose OK/Done saves and BACK
  cancels without saving, #224), radio-style volume list on the right (`X,Y GB libres de Z GB`). RIGHT/LEFT move
  between them; entry focus is the port field; BACK returns to the tab row.
- Text-field editors (port; #222, which also covered the translation-key field removed by #289): OK opens the editor *inside* the focused row's
  `Surface` instead of swapping the row out, so the row keeps focus until the editor is attached;
  the editor then takes focus from a `LaunchedEffect` (IME opens). On OK/Done or BACK the row takes
  focus back *before* the editor leaves composition, so focus is never left on a removed node
  (which dropped it to the first focusable, the Biblioteca tab, and switched tabs). Moving focus
  out of an open editor abandons the edit and leaves focus where it went. Manual check on the TV
  emulator: Configuración -> OK on the port row -> focused node is the text field and the
  Configuración tab stays selected; BACK -> focus back on the port row
  (`adb shell input keyevent DPAD_CENTER` / `BACK`, then `uiautomator dump` for the focused bounds).
- Theme (#225): `com.teachermovies.tv.ui.TeacherMoviesTheme` wraps everything `MainActivity` shows:
  tv-material `MaterialTheme(colorScheme = TeacherMoviesColorScheme)` (`darkColorScheme()`), its
  `background` painted edge to edge and `onBackground` as the default `LocalContentColor`, so text
  outside a `Surface` is light on dark. Screens use `MaterialTheme.colorScheme`; only the player
  overlays (over video) use fixed white-on-black. The shell's `TabRow` keeps the TV overscan-safe
  margin: 48 dp horizontal, 27 dp vertical.
- Shell focus (#224): UP from a section's content enters the tab row on the *selected* tab
  (`focusRestorer` on the `TabRow`), never on the nearest neighbour, which would switch sections.
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
- Focus after the player (#249): `closePlayer()` from the Biblioteca section sets
  `MainUiState.restoreFocusTo` to the played id; `LibraryScreen(..., restoreFocusTo, onFocusRestored)`
  scrolls the grid to that card (`restoreFocusIndex`), waits a frame (so it wins over the shell's
  launch request on the tab) and focuses it, then `MainViewModel.focusRestored()` clears it. A
  movie no longer in the library leaves focus on the Biblioteca tab; changing section drops a
  pending restore. Manual check: OK on a card -> player -> BACK -> `uiautomator dump` shows that
  card's bounds focused.

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
  open panel without changes, otherwise exits; `closeTracks()`. `selectSubtitle` goes through
  `HiddenSubtitleController.selectByViewer` (#227): while hidden EN mode is active the viewer's
  pick is shown (hidden mode stops forcing subtitles off for the rest of this movie and keeps
  capturing lines), instead of being silently reverted.
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
  Volume keys (VOLUME_UP/DOWN/MUTE) are null in both modes and pass through the open overlay
  (key-down and key-up not consumed), so the system still changes the TV volume; the player root
  maps no key while the overlay is open, so nothing reaches `RemoteKeyMapper` behind it (#178).
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
  `CachingTranslationProvider(AnthropicTranslationProvider(config))`, where `config` is an
  `AnthropicApiConfigSource` reading `settingsRepository.settings.first().translationApiKey` (blank
  -> null) on every request (#212); with no key the provider answers `Unavailable` and the overlay
  shows `Traducción no disponible`. No key is ever compiled into the app. Superseded: the
  Anthropic provider is unwired since #290 (ADR-0005 §9) -- see the bridge wiring below; its code
  stays dormant in `:assistant` and `:core-model` clears the stored key on upgrade. `assistantSpeechController` on the assistant's main-thread scope.
- `AssistantOverlay` under the English line: `Traduciendo…` (`Loading`), the Spanish text in amber
  (`Ready`), `Sin conexión para traducir` (`Failed(OFFLINE)`), `Traducción no disponible`
  (`Failed(UNAVAILABLE)`), nothing (`Idle`) -- `translationLine(state)`; then the message, `Hablando…`
  while `speaking`, `Repitiendo…` while replaying, and the hint
  `OK Repetir · DERECHA Escuchar · IZQUIERDA Traducir · ATRÁS Cerrar` (superseded by #293 below).
  Focus behaviour is that of #86.

## Asistente: IZQUIERDA = español, subtítulo alineado primero (#288, ADR-0005 §6)
- `TranslateLine` (DPAD_LEFT, overlay open) no longer calls `translateAndSpeak`: nothing is spoken.
  `PlayerViewModel(..., spanishLines: AlignedSpanishSource = NONE)` first asks
  `spanishLines.lineFor(torrentId, mainFile, cue)`; while it runs the translation is `Loading`. A
  line -> `Ready(text)` labelled `subtítulo` (`subtítulo · latino` for the Latin-American download,
  ADR-0005 §5), and the bridge is not asked. Null -> `speech.translate(line)` and, once `Ready`,
  the label `IA`; its failures keep their texts (`Sin conexión para traducir`,
  `Traducción no disponible`). A second LEFT while looking or aligned does nothing; on the bridge
  path it retries a failed translation. Dismiss/exit cancels the lookup and drops a late answer.
- `AssistantOverlayState.spanishLabel` carries that label; `AssistantOverlay` draws it under the
  Spanish line.
- `LookupAlignedSpanishSource(lookup: SpanishLineLookup, english = hidden.track, find)`: null outside
  hidden mode, with no Spanish track, or without a trustworthy alignment (`NoGoodAlignment` /
  `NoMatchingLine`); a throwing lookup is logged and answers null (-> bridge).
  `SpanishSubtitleFinder(fetches, embedded)` lists, in order, sidecars `*.es|spa.srt|ass` next to
  the movie then in `Subs/`, the embedded `es`/`spa` text track
  (`HiddenSubtitleController.embeddedSubtitle`), and the bridge download (`es` fetch row
  `Downloaded`, `latino` from its variant); a sidecar that is the download counts once, as the
  download; missing/unparseable files are skipped. Re-run on every press, so a mid-movie download
  counts from the next LEFT.
- `AppContainer`: the translation provider is now
  `PersistentCachingTranslationProvider(BridgeTranslationProvider(HubBridgeTranslateGateway(bridgeJobHub)), RoomTranslationCacheRepository)`;
  `bridgeJobHub` is the same `BridgeJobHub` passed to `ServerDeps.bridge`. `HubBridgeTranslateGateway`
  maps `BridgeOutcome` to `BridgeTranslateOutcome` (`Replaced` -> `Disconnected`, `Rejected` ->
  `BridgeError`). `alignedSpanishSource` wires `SpanishLineLookup` over
  `RoomSubtitleAlignmentRepository` and is passed to `PlayerViewModel.Factory`. The Anthropic API
  key is no longer read by this wiring.

## Asistente: DERECHA = Explicar (#293, ADR-0005 §7)
- `AssistantAction.SpeakOriginal` ("Escuchar", #92) is gone: overlay open, DPAD_RIGHT ->
  `ExplainLine`. DPAD_UP stays `Consumed` inside the open overlay; overlay closed, UP is still null
  in `AssistantKeyMapper`, so `RemoteKeyMapper` opens the tracks panel. The transient overlay
  `message` (`Voz no disponible`) went with it.
- `PlayerViewModel(..., explanations: ExplanationController? = null)` (and `Factory`): `ExplainLine`
  takes the aligned Spanish line LEFT already found, or asks `spanishLines.lineFor` itself, then
  calls `explanations.explain(ExplanationContext.of(hidden.track, cue, title, spanish))`. The panel
  reads `Thinking` from the key press on (while that lookup runs too). Another RIGHT while thinking or
  once `Shown` does nothing; after `Unavailable` it asks again. Dismiss/exit/clear cancel the lookup
  and call `explanations.dismiss()`. Nothing is spoken (no TTS call) and the movie stays paused.
  Without a controller RIGHT does nothing.
- `AssistantOverlayState.explanation: ExplanationUiState`; `AssistantOverlay` draws
  `explanationLines(state)` under the Spanish line: `Pensando…` (`Thinking`), the summary then up to
  3 points as `expresión: explicación` then the optional subtitle note (`Shown`),
  `Explicación no disponible (enciende el portátil)` (`Unavailable`), nothing (`Idle`). Hint:
  `OK Repetir · IZQUIERDA Español · DERECHA Explicar · ATRÁS Cerrar`.
- `AppContainer.explanationController = ExplanationController(LineExplainer(HubBridgeExplainGateway(bridgeJobHub), RoomExplanationCacheRepository), assistantScope)`.
  `HubBridgeExplainGateway` submits `BridgeJob.Explain` with the context field for field and maps
  `BridgeOutcome` to `BridgeExplainOutcome` (`Replaced` -> `Disconnected`, `Rejected` -> `BridgeError`).

## Asistente: OK N veces retrocede N frases (#339)
- `AssistantKeyMapper` is untouched: with the overlay open, DPAD_CENTER/ENTER/MEDIA_PLAY_PAUSE is
  still `ReplayFragment`, and every other key keeps its mapping. What changes is what a run of those
  presses does: `LineCaptureController` counts them and replays one more subtitle line back each
  time (see `docs/modules/assistant.md`).
- `AssistantOverlayState.linesBack: Int = 0` comes from `LineCaptureController.linesBack`, combined
  with `.replaying` into one flow first because the panel's `combine` was already at its five flows.
  It is how many lines before the captured one the fragment being replayed is: 0 while OK repeats
  the captured line itself.
- `AssistantOverlay` draws `hintLine(state.linesBack)` where it drew `ASSISTANT_HINT`: that same hint
  while `linesBack` is 0, `‹ N frases atrás` while it is not, for as long as that replay is the
  active one. Nothing else in the overlay changes, and `Repitiendo…` still shows while it plays.

## Asistente: modelo de teclas 2026-09-29 (ADR-0005 amendment; supersedes #86/#92/#288/#293/#339 keys)
Delivered in two stages (#347), both **actual**: LEFT/RIGHT rewind, subtitle line and menu hint (stage 1,
"Actual (stage 1)" below) and UP explaining with the auto-fit panel (stage 2, "Actual (stage 2)"). The older
sections above (#293 "DERECHA = Explicar", #288 LEFT = Spanish) are superseded by this one.
- Menu closed: DPAD_DOWN opens the informational menu (pauses, shows the captured English line and
  the hint); DPAD_LEFT / DPAD_RIGHT no longer seek by 10 s while the assistant is available: they go
  back N phrases (N = presses in a group, gap < 1.5 s, `REWIND_GROUP_WINDOW_MS`), LEFT showing the
  English subtitle and RIGHT the Spanish one, then play with no menu. Without an assistant
  (no English subtitles) LEFT/RIGHT keep seeking. MENU/BACK keep their meaning; DPAD_UP opens the
  tracks panel when the menu is closed.
- Menu open: DPAD_UP = explain in English (N presses = N phrases, one request per group), the movie
  stays paused and the menu visible; LEFT/RIGHT rewind as above and close the menu; BACK/DOWN close
  it and resume. OK is unchanged.
- The rewind subtitle is drawn by `:app-tv` from the assistant's cue text (English track, aligned
  Spanish track), independent of the libVLC subtitle selection, and removed when playback returns
  to the point of the first press.
- The explanation is spoken (English TTS, `SpokenOutputSettings.explanations` on by default, it
  degrades to text when there is no voice) and shown in a panel using most of the screen. The
  explanation is English only (no Spanish translation in the text or the TTS). The TV cannot scroll, so the panel
  auto-fits: font size and line spacing are computed so the full text fits, shrinking to a legible minimum and
  truncating with an ellipsis only below it.

### Actual (stage 1, #347): phrase rewind
- `AssistantKeyMapper.map(keyCode, overlayOpen, assistantAvailable = true)`: DPAD_LEFT/RIGHT are
  `AssistantAction.RewindEnglish` / `RewindSpanish` with the menu open or closed; with
  `assistantAvailable = false` (no English subtitles) they are null and `RemoteKeyMapper` seeks +-10 s.
  Open menu: OK replays, BACK/DOWN/CAPTIONS dismiss, volume passes through, UP is
  `ExplainLine` (stage 2). The old LEFT = Spanish / RIGHT = Explicar menu keys are gone; the
  `TranslateLine` action stays defined but no key maps to it.
- `PlayerViewModel(..., rewind: PhraseRewindController?, spanishText: MovieSpanishText?)`: a rewind
  action is one `rewind.press(ENGLISH|SPANISH)`; grouping (1 500 ms, N = presses) lives in the
  controller. A press paints nothing and never pauses (#365): `rewindText`, the display and the
  player's subtitle stay as they were until the window closes, then one seek goes N phrases back and
  the previous state comes back at the end of the phrase that was playing at the first press (the
  press position when it fell in a gap). An open menu closes on the first press
  (`capture.dismiss(resume = false)`, speech and explanation reset): the movie, already paused by
  the menu, stays paused through the group window and the controller then seeks and plays, so the
  rewind starts from the captured position and ends with no menu. Transport
  actions are not blocked. `PlayerUiState.rewindText` = `PhraseRewindController.displayText`
  (null while `display` is `OFF`); `PlayerScreen` draws it bottom-centre over the video, independent
  of the libVLC subtitle and of the tracks panel. The root box keeps key focus (no focus moves).
- `MovieSpanishText` (a `SpanishTextSource`) is what `AppContainer.phraseRewindController` is built
  over once; `open` loads its `SpanishCueTimeline` (#344) after hidden mode starts and `exit`
  clears it. Without a timeline RIGHT draws nothing and shows `Sin subtítulos en español` for 3 s
  (`NO_SPANISH`); LEFT is unaffected.
- Rewind subtitle on the player (#358): `AppContainer.phraseRewindController` is built with a `RewindSubtitleSession`, so LEFT/RIGHT select the English/Spanish subtitle as a temporary libVLC track and hot-restore the previous selection (a viewer track, or none) when playback reaches the end of the phrase in progress at the first press; `rewindText` is therefore null while that track shows (it only draws when there is no session). `MovieSpanishText` also implements `SpanishCueSource` (the loaded timeline's cues on the playback timeline). `PlayerViewModel` attaches the playback session as save guard, calls `rewind.setMovie(file)` after hidden mode starts, `rewind.onUserSeek()` on `PlayerAction.SeekBy` (restores at once), `rewind.cancel()`/`setMovie(null)` in `stopAssistant` -- before `session.close()` -- and shows `NO_SPANISH`/`NO_SUBTITLES` when the controller's `unavailable` fires. Manual TV check: LEFT x2 then RIGHT x1 while playing, with subtitles off and with a viewer-chosen track; the temporary subtitle shows during the replay and nothing shows and the movie does not pause while pressing, and the previous state returns at the end of the phrase heard when pressing.
- The menu (DOWN) is unchanged (pauses, shows the captured English line); its hint is
  `ARRIBA Explicar · ATRÁS Cerrar`.

### Actual (stage 2, #347): UP explains, auto-fit panel
- `AssistantKeyMapper`: with the menu open DPAD_UP = `ExplainLine`; closed it is null, so
  `RemoteKeyMapper` still opens the tracks panel. DPAD_RIGHT no longer explains (it is the Spanish
  rewind).
- `PlayerViewModel.explainLine`: each UP press restarts a 1 500 ms window
  (`EXPLAIN_GROUP_WINDOW_MS`); when it closes one request goes to `ExplanationController` with
  `ExplanationContext.of(track, cues, title, spanish)`, where `cues` are the N consecutive cues of
  `hidden.track` ending at the captured one (clamped at the start of the track; the phrase count is
  not capped). The aligned Spanish line is only sent for N = 1. The panel reads `Pensando…` from the
  first press; the movie stays paused and the menu open throughout. A press while a group request is
  being gathered, on its way or already `Shown` is ignored; after `Unavailable` a new group asks again.
- Speech: a `Shown` explanation is passed to `AssistantSpeechController.speakExplanation(spokenText)`
  (English voice, `SpokenOutputSettings.explanations`; off or no voice = text only, no error message).
  Dismissing the menu or exiting resets the speech.
- `ui/player/ExplanationFit` is a pure function. `ScreenMetrics(widthPx, heightPx, density)` comes from
  `rememberScreenMetrics()` (configuration size and density). The panel is 80 % of the width and 55 %
  of the height (floor 60 % / 50 %) minus 24 dp padding. Font range: 22 px at 720p scaled by the screen
  height (min) to 36 px (max), converted to sp by the density; `fit(text, screen)` takes the largest
  font in 0.5 sp steps whose estimated line count (per paragraph, average glyph 0.55 em) times the line
  height fits, line height going 1.4 -> 1.2 x font as it shrinks. Below the minimum it returns the minimum
  with `ellipsis = true` and the `maxLines` that fit (`Text(maxLines, overflow = Ellipsis)`). No scrolling.
  `Thinking…` / `Explicación no disponible (enciende el portátil)` stay one-line states.
- Tests: `AssistantKeyMapperTest`, `PlayerViewModelTest` (grouping N presses -> one request with N cues,
  speech called / not called), `ExplanationFitTest` (1280x720, 1920x1080, 1080p at tvdpi, ellipsis case).

## Reproducir mientras descarga (#226)
- Entry point: Descargas, OK on a row -> the action dialog starts with `Reproducir` (focused) when
  the row is `Downloading`/`Paused` and its main file is known (`DownloadRow.canPlay`); a completed
  row plays from Biblioteca and offers no `Reproducir`. `DownloadsViewModel.onAction(Play)` closes
  the dialog and emits the id on `playRequests`; `DownloadsScreen(..., onPlay)` collects it and
  `MainActivity` passes `MainViewModel::openPlayer`, the same `player/{id}` route as Biblioteca.
  BACK from the player returns to Descargas on that row.
- `AppContainer.streamingPlaybackController: StreamingPlaybackController` over `torrentEngine` and
  `player`, on a main-thread scope; `PlayerViewModel.Factory(..., streamingController)` passes it
  with the repository. `PlayerViewModel(..., streamingController = null, repo = null)`: `open(id)`
  of an item whose repository state is not `Completed` calls `PlaybackSession.openStreaming(id,
  controller)`; a completed (or unknown) item, or no controller, uses `open(id)`.
  `SessionResult.StreamingFailed` shows `No se puede reproducir mientras se descarga`.
- `PlayerUiState.streamStatus`: the controller's `Preparing` (`Preparando la reproducción… N %` of
  the ranges it waits for) or `Buffering` (`Cargando… N %` of the bytes needed to resume) as text,
  drawn centred over the picture; null otherwise. An Exit while still preparing cancels the wait,
  stops the controller and releases the player.

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

## LAN announcement (#103)
- `app-tv` depends on `:discovery`. `AppContainer.serviceAnnouncer: ServiceAnnouncer` is
  `NsdServiceAnnouncer(AndroidNsdRegistrar(application))`; `AppContainer.serverAnnouncementCoordinator`
  is `discovery.ServerAnnouncementCoordinator(announcer, serverState = httpServerController.state,
  deviceName = { Build.MODEL }, scope = applicationScope)` -- the container is the only place that
  reads `Build.MODEL`.
- `ServerAnnouncementCoordinator` (no Android types): `start()` collects the server state; on
  `Running(port)` it announces `TvServiceInfo(ServiceNames.instanceName(deviceName()), port)`, again
  only when the port differs from the one announced; on `Stopped`/`Failed` it calls
  `announcer.stop()`. `stop()` cancels the collection and withdraws the announcement.
- `TeacherMoviesApp.onCreate` starts it right after `httpServerController.start()`. The announcement
  is never on the server's path (`ServiceAnnouncer` does not throw) and no screen reads
  `AnnouncementState`: the TV keeps showing `http://IP:port` and the PIN as #66 defines them.

## Autostart on TV boot (#126)
- `com.teachermovies.tv.autostart.Autostart` (`start(): AutostartResult` = `Started` |
  `TorrentServiceRefused(reason)`) is the seam; `BootAutostartCoordinator(settings, autostart)`
  (no Android types) `onBroadcast(action)`: not `BOOT_COMPLETED` -> `IgnoredAction` (nothing read);
  otherwise reads the settings once, `autostartOnBoot == false` (the default) -> `Disabled`, true ->
  `Started(autostart.start())`, invoked exactly once.
- `ServiceAutostart(context, httpServerController)` is the production seam: `httpServerController
  .start()` (#66, idempotent -- `TeacherMoviesApp.onCreate` normally already ran it, and with it
  the LAN announcement of #103) and then `TorrentService.start(context)` (#55,
  `startForegroundService`). A refused service start (`ForegroundServiceStartNotAllowedException`)
  is returned as `TorrentServiceRefused`, never thrown. `AppContainer.bootAutostartCoordinator`
  wires both.
- `BootCompletedReceiver` (manifest: `exported="true"`, `enabled="true"`, `BOOT_COMPLETED` filter,
  `RECEIVE_BOOT_COMPLETED` permission) ignores any other action, holds the broadcast with
  `goAsync()`, runs the coordinator in a coroutine and calls `finish()` in every path; the outcome
  and any failure are logged.
- Configuración: `SettingsUiState.autostartOnBoot` and `SettingsViewModel.setAutostartOnBoot`; the
  switch row `Arrancar al encender la TV` sits under the volume list (title `Arranque`). DOWN from
  the last volume reaches it (RIGHT from the port field when there is no volume), OK toggles it,
  UP returns to the list, LEFT to the port field. Entry focus is still the port field.
- Configuración, laptop bridge (#289, ADR-0005 §4/§9; replaces the translation-key field of #212,
  which is gone from the screen -- the stored key itself and the Anthropic provider wiring are
  #290): `SettingsViewModel(..., bridgeConnected: Flow<Boolean> = flowOf(false),
  disconnectBridge: () -> Unit = {})`, wired by `MainActivity` to `AppContainer.bridgeJobHub`
  (`connected`, `disconnectBridge`) -- one `BridgeJobHub` for the process, passed to every
  `ServerDeps` so the state survives a server restart. `SettingsUiState.bridgeConnected`,
  `bridgePaired` (any bridge token stored) and `bridgeName` (`AppSettings.bridgeDeviceName`).
  `forgetBridge()` calls `settings.clearBridgeTokenHashes()` (phones stay paired) and then
  `disconnectBridge()`, so the laptop's reconnect is refused. Under the autostart switch a plain
  text line says `Portátil (Claude): Conectado · <nombre>` (`Conectado` alone when the bridge gave
  no name) or `Portátil (Claude): No conectado`; while a bridge is paired or connected the row
  `Olvidar portátil` follows it. DOWN from the switch reaches that row, UP returns to the switch,
  LEFT to the port field; OK forgets the bridge and moves focus to the switch before the row
  leaves the screen (#222). `SettingsUiState.toString` redacts the PIN.
  `SettingsScreenFocusTest` (Robolectric + Compose `ui-test-junit4`, on a 960x540 dp screen,
  plain `Application`) drives this order with real D-pad key events.
- Platform limits: after a boot only the process, the HTTP server (+ NSD announcement) and, where
  allowed, the torrent service run; the app is **not** brought to the foreground (Android 10+
  forbids starting an activity from a background receiver). `TorrentService` is a `dataSync`
  foreground service, which Android 15 does not allow to start from `BOOT_COMPLETED`: there the
  receiver logs `TorrentServiceRefused`, the server still runs until the system reclaims the
  process, and downloads resume when the app is opened. The receiver firing on a real boot is a
  manual check on a device (`adb` / instrumented tests are not run by agents).

## Logging (#268, ADR-0006)
- Nothing in the app calls `android.util.Log` except `tv.log.AndroidLogSink`; every module logs
  through `AppLog` (`:core-model`) with its module name (`app-tv`, `player`, `torrent`...).
- `AppContainer` declares `logBuffer: RingBufferLogSink` first and runs `LoggingSetup.install` right
  after it, before anything else is built: `AppLog.minLevel` is DEBUG in a debuggable build and
  INFO otherwise, and it installs `AndroidLogSink` (logcat, tag `TM/<module>`, priority by level)
  and `logBuffer` (redacted, what #269 serves over HTTP).
- It also installs `CrashLogHandler` as the default uncaught-exception handler: it logs the crash at
  ERROR (module `app-tv`, thread name and stack trace) and then always chains to the handler that
  was there before, so the platform still reports the crash and kills the process.

## Subtitle needs (#280, ADR-0005 §5)
- `AppContainer.subtitleFetchRepository: SubtitleFetchRepository` is
  `RoomSubtitleFetchRepository(torrentDatabase.subtitleFetchStateDao())`, the automatic-subtitle
  fetch state of #274, and is what `ServerDeps.subtitleFetches` hands to the bridge's subtitle
  routes. `AppContainer` now also owns the one `BridgeJobHub`, so the server's `/api/bridge/jobs`
  routes and the nudge below act on the same instance instead of one hub each.
- `tv.subtitles.SubtitleNeedsCoordinator(library: TorrentRepository, fetches:
  SubtitleFetchRepository, notify: (TorrentId) -> Unit, scope)` (no Android types; `embeddedLanguages`,
  `nowMs` and `dispatcher` are injectable and default to `:player`'s `EmbeddedTextTracks.languagesOf`,
  the wall clock and `Dispatchers.IO`): `start()` collects `observeLibrary()` and, for every movie of
  every emission -- the ones already stored when it starts as well as each one that completes later --
  writes that movie's fetch-state rows through `ensurePending`. `"es"` always; `"en"` only when the
  movie has no English subtitle of its own, which is `SidecarSubtitles.findFor(file, "en")`
  (`:assistant`) finding a sidecar next to it or the container carrying an English text track (a
  language of `en`, `eng` or any `en-` variant). Both rows carry the moviehash of
  `OpenSubtitlesHash.of` (`:assistant`, #279) when the file can be hashed, and null when it is too
  short or unreadable. `ensurePending` leaves an existing row's state, attempts and retry clock
  alone, so passing over the whole library on each emission is what fills in a hash that could not be
  computed the first time and otherwise changes nothing. The rows are written on the IO dispatcher,
  and a movie whose rows cannot be stored is logged and skipped so the coordinator keeps following
  the library -- that is the only thing that publishes later downloads.
- `notify` then fires once per id that is new since the previous emission, wired to
  `bridgeJobHub.notifySubtitlesNeeded(id)`, so a download that completes puts a `subtitles-needed`
  frame on the bridge's stream, always after that movie's own rows are stored and therefore already
  visible to `GET /api/bridge/subtitle-needs`. The library already there when it starts is only
  recorded, never replayed -- a bridge that was off reads the whole needs list when it connects
  (#282) -- and a movie that leaves the library and comes back counts as new again.

## Boundaries
- Depends on feature modules' public interfaces only; contains no torrent, HTTP or VLC logic itself.
- All screens fully usable with the D-pad; focus order and initial focus are acceptance criteria.

## Tests
ViewModel unit tests with fakes (`FakeTorrentEngine`, in-memory repositories). Compose UI tests are instrumented and optional.
