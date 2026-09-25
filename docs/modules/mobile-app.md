# Module: mobile-app

**Gradle path:** `:mobile-app` (phase 2 -- not part of MVP 1.0).

## Responsibility
- Small Android phone app: discover the TV via NSD, pair with the PIN, keep the token.
- `Share -> Movie Assistant` intent filter that sends a magnet/link to the TV API; show download progress.

## Public contract (package `com.teachermovies.mobile`)
- Build shape (#195): `:mobile-app` is an Android application (`android.application`,
  `kotlin.android`, `kotlin.compose`), `namespace`/`applicationId` `com.teachermovies.mobile`,
  `compileSdk`/`targetSdk` 35, `minSdk` 26, Java 17 and `jvmToolchain(17)`, no `ndk.abiFilters`
  (no native library). UI is Jetpack Compose Material 3 (`androidx.compose.material3:material3`,
  versioned by the Compose BOM, ADR-0004); `androidx.tv:tv-material` is not used. Its only
  project dependency is `:discovery` (#197), whose client contract it reuses unchanged.
  `kotlin.serialization` plugin; runtime deps (#196): `ktor-client-core`, `ktor-client-cio`,
  `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`, `kotlinx-coroutines-core`,
  `androidx-datastore-preferences`; (#197) `androidx-lifecycle-viewmodel-ktx`,
  `androidx-lifecycle-runtime-compose`.
- Manifest: `android.permission.INTERNET`, `application android:name=".MobileApp"` and one
  launcher `MainActivity` (`ComponentActivity` + `setContent`) hosting the connection screens
  (#197) under a Material 3 top bar titled `Movie Assistant`.
- DI (#197, ADR-0003): `di.MobileContainer(application)` is built once in `MobileApp.onCreate`
  (`MobileApp.container`). It exposes, typed as interfaces: `serviceDiscoverer: ServiceDiscoverer`
  = `NsdServiceDiscoverer(AndroidNsdBrowser(application))`; `tvApi: TvApi` = `KtorTvApi` over one
  `HttpClient(CIO)` (private); `pairedTvStore: PairedTvStore` = `DataStorePairedTvStore` over the
  `preferencesDataStore` named `paired_tv`; and `deviceName: String` = `android.os.Build.MODEL`
  (the only place it is read; `Android` if the platform reports none). No fake is wired.
- Connection flow (#197), package `connection`:
  - `ConnectionViewModel(discoverer: ServiceDiscoverer, api: TvApi, store: PairedTvStore,
    deviceName: String)` exposes `uiState: StateFlow<ConnectionUiState>`; `ConnectionViewModel.Factory`
    (a `ViewModelProvider.Factory` over the same four arguments) is what `MainActivity` builds from
    the container (`by viewModels`), collecting the state with `collectAsStateWithLifecycle`.
  - `ConnectionUiState` = `Loading` | `Searching(tvs: List<DiscoveredTv>, addressError: String? = null)`
    | `Pairing(instanceName, baseUrl, error: String? = null, busy = false)` | `Connected(pairedTv)`.
  - `Loading` until `store.pairedTv` gives its first value: a stored TV -> `Connected(it)`,
    otherwise `Searching(<last discovered list>)`. Then `discoverer.discover()` (`_http._tcp`) is
    collected for the ViewModel's whole life: while `Searching` each emission replaces `tvs`; while
    `Connected`, a TV with the paired `instanceName` whose `baseUrl` differs from the stored one
    triggers `store.updateBaseUrl(newBaseUrl)` and `Connected` with the new URL (DHCP moved the
    TV); an unchanged address or another TV does nothing.
  - `selectTv(tv)` (from `Searching`) -> `Pairing(tv.instanceName, tv.baseUrl)`.
  - `enterAddress(text)` (from `Searching`): trimmed `host` or `host:port`, host made of letters,
    digits, `.` and `-` (no scheme, path or IPv6), port digits in `1..65535`, default 8787 ->
    `Pairing(instanceName = "host:port", baseUrl = "http://host:port")`. Anything else leaves the
    state and sets `addressError = "Dirección no válida"`.
  - `submitPin(pin)` (from a non-busy `Pairing`): anything but exactly 6 ASCII digits sets
    `error = "El PIN tiene 6 cifras"` and never calls the API. Otherwise `busy = true, error = null`
    and `api.pair(baseUrl, pin, deviceName)`: `Paired(token)` -> `store.save(PairedTv(instanceName,
    baseUrl, token))` -> `Connected`; `WrongPin` -> `PIN incorrecto`; `TooManyAttempts` ->
    `Demasiados intentos; espera un minuto`; `Failed` -> `No se puede conectar con la TV` (each
    with `busy = false`). An answer arriving after `cancelPairing` is dropped.
  - `cancelPairing()` (from `Pairing`, also the system back) cancels an in-flight pair request and
    returns to `Searching` with the last discovered list.
  - `forgetTv()` (from `Connected`) -> `store.clear()` -> `Searching`.
  - Screens (Compose Material 3, Spanish, strings in `res/values/strings.xml`): `TvListScreen`
    (one `ListItem` per TV: `instanceName` and `host:port`; `Buscando la TV...` while the list is
    empty; an `Introducir dirección` field with a `CONECTAR` button and the address error),
    `PairingScreen` (the TV name, `Escribe el PIN que muestra la TV`, a numeric PIN field that keeps
    at most 6 digits, `EMPAREJAR`, `CANCELAR`, the error text and a progress indicator while busy)
    and `ConnectedScreen` (`<instanceName> ● conectada`, `OLVIDAR ESTA TV`; a placeholder until the
    downloads list lands). `Loading` shows a progress indicator. The PIN lives only in the pairing
    screen's field and the one `pair` call; nothing logs it or the token.
- `share.SharedLinkParser.extractMagnet(text: String?): String?` -- pure Kotlin, no Android type:
  - finds the first `magnet:?` in `text` (scheme matched case-insensitively), cut at the first
    whitespace character, so a magnet inside surrounding shared text or followed by a newline works;
  - returns it only if its query has an `xt` parameter whose value starts with `urn:btih:` (v1) or
    `urn:btmh:` (v2), otherwise `null`; only the first magnet is considered;
  - returns `null` for null, blank or magnet-less text (including `http(s)` links).
- TV API client (#196), package `api` -- the phone's client of `docs/modules/http-server.md`, over
  Ktor client (CIO engine, ADR-0004 "Ktor client in `:mobile-app`"). `:mobile-app` does NOT depend
  on `:http-server` (that would pack the Ktor server into the APK); the DTOs are mirrored here.
  - `interface TvApi`; `baseUrl` (e.g. `http://192.168.1.20:8787`, a trailing `/` is tolerated) and,
    for protected calls, `token` are passed on every call, so one instance serves any TV:
    - `suspend status(baseUrl): ApiResult<TvStatus>` -> `GET /api/status`, no token;
    - `suspend pair(baseUrl, pin, deviceName): PairOutcome` -> `POST /api/pair`
      `{"pin","deviceName"}`, no token;
    - `suspend torrents(baseUrl, token): ApiResult<List<TorrentSummary>>` -> `GET /api/torrents`;
    - `suspend addMagnet(baseUrl, token, magnet): AddMagnetOutcome` -> `POST /api/torrents/magnet`
      `{"magnet"}`.
  - `KtorTvApi(httpClient: HttpClient)`: derives its own client from `httpClient` (`config {}`, same
    engine) with `HttpTimeout` 10 s (`TIMEOUT_MILLIS`: request, connect and socket),
    `ContentNegotiation` JSON `Json { ignoreUnknownKeys = true }` and `expectSuccess = false`.
    Protected calls send `Authorization: Bearer <token>` in the header, never in the URL.
  - `ApiResult<T>` = `Success(value)` | `Failure(ApiFailure)`. `ApiFailure` = `Unauthorized` (any 401
    on a protected call) | `Http(status, code, message)` (any other non-2xx; `code`/`message` from the
    server's `{"error","message"}` body, `null` when the body is not one) | `Network(reason)`
    (connection refused, timeout, unknown host, malformed or unexpected body; `reason` is only an
    exception class name, `timeout` or `unexpected response body`, never a body).
  - `PairOutcome` = `Paired(token)` (2xx) | `WrongPin` (401 `wrong_pin`) | `TooManyAttempts` (429) |
    `Failed(ApiFailure)` (any other 401 or non-2xx is `Http`, not `Unauthorized`: pairing is public).
  - `AddMagnetOutcome` = `Added(id, state)` (2xx, 201 documented) | `AlreadyExists(id)` (409
    `already_exists` with `id`) | `InvalidMagnet` (400 `invalid_magnet`) | `Failed(ApiFailure)`.
  - No method throws: every exception becomes `Network`; `CancellationException` is rethrown.
    Nothing in the package logs a token, a PIN or an `Authorization` header; `Paired.toString()`
    redacts the token.
  - `@Serializable` mirrors: `TvStatus(version, engine, freeBytes: Long?, totalBytes: Long?,
    torrents: Int)` and `TorrentSummary(id, name, state, progress: Double, downloadedBytes,
    totalBytes, downloadSpeed, peers, etaSeconds: Long?)` (`uploadSpeed`/`ratio` ignored).
  - Fake (ADR-0003, main source set): `api.fake.FakeTvApi(statusResult, pairResult, torrentsResult,
    addMagnetResult)` -- mutable scripted results (defaults: a running TV, pairs with any PIN, empty
    list, accepts any magnet); every call is appended to `calls: List<FakeTvApi.Call>`
    (`Status`/`Pair`/`Torrents`/`AddMagnet`, with its arguments), in order.
- Paired-TV store (#196), package `data`:
  - `PairedTv(instanceName, baseUrl, token)` (`toString()` redacts the token).
  - `interface PairedTvStore { val pairedTv: Flow<PairedTv?>; suspend save(tv); suspend
    updateBaseUrl(baseUrl); suspend clear() }` -- one paired TV at a time, `save` replaces it,
    `updateBaseUrl` is a no-op when nothing is stored.
  - `DataStorePairedTvStore(dataStore: DataStore<Preferences>)`: keys `paired_tv_instance_name`,
    `paired_tv_base_url`, `paired_tv_token`; reads as `null` unless all three are present.
  - Fake: `data.fake.InMemoryPairedTvStore(initial: PairedTv? = null)`, `MutableStateFlow`-backed.

## Boundaries
- Uses only the public HTTP API; no shared code with `app-tv` beyond `discovery` and API DTOs.

## Tests
JVM tests for link parsing and API client with a fake server.
- `share/SharedLinkParserTest` (`scripts/test.sh :mobile-app:test`): bare magnet, magnet inside
  other text, uppercase `MAGNET:`, `urn:btmh:` magnet, trailing newline, two magnets (first wins),
  magnet without `xt`, `http(s)` links, blank text and null.
- `api/KtorTvApiTest` (#196): a real Ktor CIO `embeddedServer` on a loopback port (`port = 0`, read
  back from the resolved connectors; `api/LoopbackTvServer`, test-only `ktor-server-core`/`-cio`)
  answers each test with one fixed JSON and records method, path, query, `Authorization` and body.
  Covers: bearer header on `torrents`/`addMagnet` (no query token) and absent on `status`/`pair`,
  401 -> `Unauthorized`, 409 `already_exists` with `id`, `invalid_magnet`, `wrong_pin`, 429, a 500
  with an error body, a non-JSON error body, an unknown extra field ignored, malformed and
  wrong-shape bodies -> `Network`, and a closed port -> `Network` on every call.
- `data/DataStorePairedTvStoreTest` (#196): JVM, `PreferenceDataStoreFactory.create` over a file in
  a `TemporaryFolder`: empty -> `null`, save then read, save replaces, `updateBaseUrl` with and
  without a stored TV, `clear`, token redacted from `toString`.
- `connection/ConnectionViewModelTest` (#197): JVM, `runTest` with `Dispatchers.setMain`
  (`UnconfinedTestDispatcher`), `FakeServiceDiscoverer`, `FakeTvApi`, `InMemoryPairedTvStore`:
  `Loading` until the store answers, the list following discovery, `selectTv`, typed host (default
  port), `host:port`, invalid addresses (empty, bad/out-of-range port, scheme, spaces), malformed
  PINs never reaching the API, `Paired` stored and connected with `Build.MODEL`'s stand-in as the
  device name, `busy` in flight, `WrongPin`/`TooManyAttempts`/`Failed` errors, retry after an
  error, `cancelPairing`, the stored-TV start, `updateBaseUrl` on a re-resolved address, no update
  for an unchanged address or another TV, and `forgetTv`.
