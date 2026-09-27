# Module: http-server

**Gradle path:** `:http-server`.

## Responsibility
- Embedded HTTP server on `0.0.0.0:8787` (port configurable, LAN interfaces only), Ktor 3.x with the CIO engine (ADR-0002).
- REST API from `docs/VISION.md` (`/api/status`, `/api/torrents[...]`, `/api/library`, `/api/subtitles`) plus SSE or WebSocket for live progress.
- Minimal bundled web UI for phones: paste magnet, upload .torrent, upload subtitle, download list.
- Pairing and auth (ADR-0002): the TV shows a PIN; `POST /api/pair` exchanges it for a token.
  **Every `/api/*` endpoint requires `Authorization: Bearer <token>` except `GET /api/status` and
  `POST /api/pair`** -- reads (`/api/torrents*`, `/api/library`) included. Missing/invalid token ->
  401 `{"error":"unauthorized"}` + `WWW-Authenticate: Bearer`.
- Token scopes (#270, ADR-0005 §4): a token is issued for `phone` (the default, and what the phone
  and the bundled web UI use) or for `bridge` (a laptop bridge). Its hash is stored in that scope's
  own set (`AppSettings.authTokenHashes` / `AppSettings.bridgeTokenHashes`), and every protected
  route names the scopes it accepts: a bridge token reaches only `/api/bridge/*` (#275) and
  `/api/logs*` (#269, ADR-0006 §4), and is 401 on every phone route, just as a phone token is 401
  on `/api/bridge/*`. Clearing the phone hashes revokes the phones only ("Olvidar portátil" is #289).
- `GET /api/events` (SSE) also requires the token; because `EventSource` cannot set headers, this
  route alone also accepts `?token=<token>`. Every other route ignores/rejects a query-param token.
  Logs redact both the header and the `token` query parameter.
- The static web UI (`/`, `/static/*`) is public; it runs the pairing flow and sends the token itself.
- LAN-only (#59): an application-level plugin refuses every request whose remote address is not on
  the LAN, even the public routes, before any route runs -- 403 `{"error":"not_lan"}`. This is
  independent of and runs before the bearer-token check.

## Public contract (package `com.teachermovies.http`)
- `LocalHttpServer(deps: ServerDeps, port: Int, host: String = "0.0.0.0")`: `start()` is
  non-blocking (`embeddedServer(CIO, port, host) { module(deps) }.start(wait = false)`), `stop()` is
  graceful and idempotent.
- `fun Application.module(deps: ServerDeps)` holds all plugins and routing; tests run it in-process
  with `testApplication { application { module(fakeDeps) } }`.
- `ServerDeps(engine: TorrentEngine, remove: suspend (TorrentId, Boolean) -> EngineResult<Unit>,
  space: () -> SpaceInfo?, appVersion: String, clock: () -> Long,
  pairing: PairingManager, subtitles: SubtitleStore, library: TorrentRepository,
  logs: RingBufferLogSink = RingBufferLogSink(), bridge: BridgeJobHub = BridgeJobHub(),
  allowTestRemoteHeader: Boolean = false)`, extended by later issues. `bridge` is the job hub of
  `/api/bridge/*` (#275); `AppContainer` must pass the same instance the assistant submits to. `logs` is the ring buffer of ADR-0006 that `/api/logs*` serves (#269);
  the default is a fresh empty buffer until #268's `AppContainer` wiring passes the process-wide
  instance it installs in `AppLog`. `allowTestRemoteHeader` is test-only (#59) and must stay
  `false` in production.
- `com.teachermovies.http.auth.LanAddressPolicy.isAllowed(address: String): Boolean` (#59): whether
  a literal IPv4/IPv6 address is on the LAN -- loopback, `10/8`, `172.16/12`, `192.168/16`,
  `169.254/16`, `fe80::/10`, `fc00::/7`, and the IPv4-mapped IPv6 form of any allowed IPv4 range.
  Parses addresses by hand and never resolves a hostname, so an unparsable string (including a real
  hostname) is refused without a DNS lookup. The application-level guard applies it to
  `call.request.origin.remoteAddress` -- the socket peer's IP literal, never `remoteHost`, which
  Ktor fills with a reverse-resolved name when one exists (`127.0.0.1` arrives as `localhost`, a
  DHCP client as e.g. `android-phone.lan`) and so would be refused (#221); only when `ServerDeps.allowTestRemoteHeader` is `true` does an
  `X-Test-Remote` request header override that address, for route tests.
- `com.teachermovies.http.auth.TokenScope`: `PHONE` | `BRIDGE`, with the `wireValue` (`phone`,
  `bridge`) that `POST /api/pair` accepts in its `scope` field and echoes back, and
  `TokenScope.fromWireValue(scope)` returning null for anything else (#270).
- `com.teachermovies.http.auth.PairingManager(settings: SettingsRepository, random: SecureRandom,
  clock: () -> Long)`: `currentPin()` (6 digits, zero-padded; new every 10 min and after each
  successful pairing), `suspend pair(pin, scope = PHONE): PairResult` (`Paired(token)` | `WrongPin` |
  `TooManyAttempts` once 5 wrong PINs fall within 60 s), `suspend scopeOf(token): TokenScope?` --
  the scope a pairing issued the token with, or null for a token none issued. Tokens are 32
  random bytes, base64url without padding; only their SHA-256 hex is persisted, in
  `SettingsRepository.addAuthTokenHash` for a phone token and `addBridgeTokenHash` for a bridge
  one, so they survive restarts and each scope's set can be cleared on its own.
- `POST /api/pair` (public) body `{"pin":"482916","deviceName":"...","scope":"phone"}` -> 200
  `{"token":"...","scope":"phone"}` | 400 `bad_request` (also for a `scope` that is not `phone` or
  `bridge`, which pairs nothing and leaves the PIN usable) | 401 `wrong_pin` | 429
  `too_many_attempts`. `scope` may be omitted, which means `phone`.
- `fun Route.requireBearer(pairing, scopes, allowQueryToken = false) { ... }` wraps every protected
  route: a missing/invalid token, or one whose scope is not in `scopes`, -> 401 `unauthorized` +
  `WWW-Authenticate: Bearer`, handler not run. Phone routes pass `setOf(TokenScope.PHONE)`;
  `/api/logs*` passes both scopes (#269) and `/api/bridge/*` passes `setOf(TokenScope.BRIDGE)`
  (#275). Only `/api/events` passes `allowQueryToken = true`. `redactTokenQuery(uri)` is what any
  request logging must apply (and it must never log the `Authorization` header).
- `GET /api/status` (public) -> 200
  `{"version":"...","engine":"running","freeBytes":123,"totalBytes":456,"torrents":2}`; `engine` is
  the `EngineStatus` in lower case, `freeBytes`/`totalBytes` are `null` when unknown.
- Read routes (#57), all wrapped in `requireBearer`, DTOs in `com.teachermovies.http.dto`:
  - `GET /api/torrents` -> 200 `[TorrentDto]`.
  - `GET /api/torrents/{id}` -> 200 `TorrentDto` | 404 `unknown_torrent` | 400 `invalid_id`.
  - `GET /api/torrents/{id}/files` -> 200 `[FileDto]` | 409 `not_ready` before metadata | 404
    `unknown_torrent` | 400 `invalid_id`.
  - `TorrentDto(id, name, state, progress, downloadedBytes, totalBytes, downloadSpeed, uploadSpeed,
    peers, etaSeconds, ratio)`: `state` is `DownloadState` in snake_case
    (`fetching_metadata`|`queued`|`downloading`|`paused`|`verifying`|`completed`|`error`), `progress`
    a percentage with one decimal (`17.4`). `FileDto(index, path, size, priority, downloadedBytes)`:
    `priority` is `FilePriority` lower-case (`skip`|`normal`|`high`). Mapped from `TorrentSnapshot`/
    `TorrentFileInfo` by `toDto()`.
- Mutating routes (#60, `TorrentWriteRoutes.kt`), all wrapped in `requireBearer` (401 without a
  valid token, engine never called):
  - `POST /api/torrents/magnet` `{"magnet":"magnet:?xt=urn:btih:..."}` -> 201
    `{"id":"<hash>","state":"fetching_metadata"}` | 400 `invalid_magnet` | 400 `bad_request`
    (malformed body) | 409 `already_exists` with `"id":"<hash>"`.
  - `POST /api/torrents/file` multipart field `torrent` -> 201 as above | 400 `invalid_torrent` |
    400 `bad_request` (not multipart / no `torrent` field) | 413 `too_large` (over
    `MAX_TORRENT_FILE_BYTES` = 10 MiB; never buffers more than that) | 409 `already_exists`.
  - `POST /api/torrents/{id}/pause`, `POST /api/torrents/{id}/resume` -> 204.
  - `DELETE /api/torrents/{id}?deleteFiles=true|false` (default `false`; anything else 400
    `bad_request`) -> 204. Goes through `ServerDeps.remove` (wired to `EngineRepositorySync.remove`,
    the same path as the TV's Descargas delete), so once the engine confirms, the torrent's Room row
    is deleted too and it leaves `GET /api/library` and Biblioteca (#219); an engine failure leaves
    the row in place.
  - `PUT /api/torrents/{id}/files` `[{"index":0,"priority":"skip|normal|high"}]` -> 204 | 409
    `not_ready` before metadata | 400 `invalid_priority` | 400 `invalid_index` | 400 `bad_request`;
    a rejected body applies no change at all.
  - Every `{id}` route: 400 `invalid_id`, 404 `unknown_torrent`.
  - `EngineError.toHttp(): HttpError(status, body)` is the one `EngineError` -> HTTP mapping:
    `InvalidMagnet` 400 `invalid_magnet`, `InvalidTorrentFile` 400 `invalid_torrent`,
    `UnknownTorrent` 404 `unknown_torrent`, `AlreadyExists` 409 `already_exists` (+`id`), `NotReady`
    409 `not_ready`, `Unsupported` 501 `unsupported`, `Io` 500 `io_error` (engine text never
    copied into the body).
- `POST /api/subtitles` (#61, `SubtitleRoutes.kt`), wrapped in `requireBearer`: a phone uploads an
  external subtitle for a torrent. Multipart fields `torrentId` and `file`; accepted extensions
  `srt`/`ass`/`ssa`/`vtt` (case-insensitive); at most `MAX_SUBTITLE_FILE_BYTES` = 2 MiB (never
  buffers more) -> 201 `{"path":"subs/<sanitized name>"}` | 400 `invalid_id` | 404
  `unknown_torrent` | 400 `unsupported_subtitle` | 413 `too_large` | 400 `bad_request` (not
  multipart / missing a field) | 500 `io_error` if the store itself fails. Writes through
  `ServerDeps.subtitles: SubtitleStore` (`fun save(torrentId, fileName, bytes): Result<String>`,
  package `com.teachermovies.http`); `LayoutSubtitleStore(layoutFor: (String) -> DownloadLayout?)`
  sanitises `fileName` to `[A-Za-z0-9._-]` (rejecting a name that sanitises to empty, `"."` or
  `".."`) and writes to `DownloadLayout.resolveInTorrent(id, "subs/<sanitized name>")`.
- `GET /api/library` (#73, `LibraryRoutes.kt`), wrapped in `requireBearer` (401 without a valid
  token) -> 200 `[LibraryItemDto(id, title, sizeBytes, completedAt, lastPositionMs)]`, newest
  completed first (the order of `ServerDeps.library.observeLibrary()`); `completedAt` is ISO-8601
  UTC (`2026-09-24T10:00:00Z`). No file system path is ever exposed. Mapped by `LibraryItem.toDto()`.
- Errors are JSON `{"error":"<code>","message":"..."}` (StatusPages); `ApiError.id` is present
  only on `already_exists`: unknown `/api/*` route -> 404
  `not_found`; any uncaught exception -> 500 `internal`, never with a stack trace or exception
  message. A route that sends its own `ApiError` with `ApplicationCall.respondApiError` (e.g.
  `unknown_torrent`) is exempt from being overwritten by the shared `not_found` 404 page --
  `StatusPages`'s `status(NotFound)` handler otherwise fires on any 404, not only an unmatched route.
- `HttpServerController(settings: SettingsRepository, depsFactory: () -> ServerDeps, scope:
  CoroutineScope, serverFactory: (ServerDeps, Int) -> RunningServer)`: follows
  `settings.settings.map { it.httpPort }.distinctUntilChanged()`, starting the server on the first
  port and, on every later change, stopping the running one before starting the new one (never both
  at once). `fun interface RunningServer { fun stop() }` lets tests avoid opening sockets. `val
  state: StateFlow<ServerState>` reports `Stopped` | `Running(port)` | `Failed(port, reason)`; a
  bind failure is `Failed`, never a crash. `start()`/`stop()` are idempotent. Hosting this in the
  app/service and showing the pairing PIN is #66.
- `GET /api/events` (#62): live torrent progress over Server-Sent Events, plain
  `call.respondTextWriter(ContentType.Text.EventStream)` (no `sse` plugin), wrapped in
  `requireBearer(pairing, allowQueryToken = true)`. `Cache-Control: no-cache`; sends
  `event: torrents` with `[TorrentDto]` immediately and again whenever `engine.torrents` changes,
  conflated and throttled to at most one event per second, plus a `: ping` comment every 15 s. Ends
  quietly (no exception reaches the client) when the connection drops. `com.teachermovies.http.sse.
  SseFormat.event(name, data)` renders one frame (a multi-line `data` becomes one `data:` line per
  input line); the web client consuming this is #63.
- Log routes (#269, `LogRoutes.kt`, ADR-0006 §4): `GET /api/logs` and `GET /api/logs/stream`,
  both wrapped in `requireBearer` with BOTH token scopes (`PHONE` and `BRIDGE`) and neither with
  `allowQueryToken` -- the stream takes the token only in the `Authorization` header (the
  `?token=` exception stays exclusive to `/api/events`; the phone web reads the stream with
  `fetch`, #273). The wire DTOs live in `:bridge-protocol` (`com.teachermovies.bridge.protocol`,
  ADR-0005 §1), shared with the laptop bridge, and are served from `ServerDeps.logs`:
  - `GET /api/logs?since=&level=&limit=` -> 200
    `LogsPageDto{"bootId":"...","entries":[LogEntryDto(seq, timeMs, level, module, message)]}`,
    oldest first. `since` is a `LogEntry.seq` cursor (only `seq > since`; default 0 = the whole
    buffer), `level` a minimum (`debug`|`info`|`warn`|`error`, lower case on the wire) and
    `limit` the page size (default `RingBufferLogSink.DEFAULT_PAGE` = 500, at most
    `RingBufferLogSink.MAX_LINES` = 5000). The first invalid parameter -> 400 `bad_request`.
    `timeMs` is epoch millis and `message` arrived redacted and truncated from the sink. When
    `bootId` changes, `seq` started over and readers page again from 0.
  - `GET /api/logs/stream?since=&level=` -> SSE in the same `respondTextWriter` shape as
    `/api/events`: `Cache-Control: no-cache`, a `: ping` comment every 15 s, quiet end when the
    connection drops. First frame `event: boot` with `{"bootId":"..."}` (`LogStreamBootDto`),
    then the backlog as one `event: log` frame per entry (`data` = `LogEntryDto`), then every
    new line live. `limit` is ignored (the backlog is the whole buffer). The live queue is armed
    before the backlog snapshot is read and a seq cursor drops duplicates, so a line recorded
    while the stream starts up is delivered exactly once. Invalid `since`/`level` -> 400 before
    the stream starts.
- Bridge job hub (#275, ADR-0005 §2; `BridgeRoutes.kt`, package `com.teachermovies.http.bridge`):
  the TV never runs an AI call; it hands typed jobs to the paired laptop bridge and waits for its
  answer. Both routes are wrapped in `requireBearer(setOf(TokenScope.BRIDGE))` without
  `allowQueryToken`: a phone token, a `?token=` or no token is 401. Wire DTOs live in
  `:bridge-protocol` (`BridgeJobDto` = `TranslateJobDto(id, line)` | `ExplainJobDto(id, title, line,
  before, after, spanishLine)` discriminated by `kind` = `translate`|`explain`; `BridgeCancelDto(id)`;
  `BridgeJobResultDto` = `Done(text)` | `Failed(code, message?)` discriminated by `status` =
  `ok`|`error`; names and limits in `BridgeJobProtocol`).
  - `interface AssistantBridge { val connected: StateFlow<Boolean>; suspend fun submit(job:
    BridgeJob, timeout: Duration): BridgeOutcome }` is what other modules use (#287/#292);
    `BridgeJob` = `Translate(line)` | `Explain(title, line, before, after, spanishLine)`, and
    `BridgeOutcome` = `Done(text)` | `NoBridge` (no bridge stream open: answered at once, nothing
    sent) | `TimedOut` | `BridgeError(code, message)` | `Disconnected` (the stream the job went out
    on closed first) | `Replaced` | `Rejected(reason)` (over the per-job limits: a line -- `line`,
    `title`, `spanishLine` or any `before`/`after` entry -- over 500 chars, or the encoded job over
    4 KB; never sent). `submit` never throws for a bridge-side failure.
  - `BridgeJobHub(random: SecureRandom = SecureRandom())` implements it. Job ids are 16 random
    bytes base64url, single-use. One job per slot (= kind): a newer `translate` resolves the
    older one `Replaced`. The bridge gets `event: cancel` whenever a job ends without an answer
    while its stream is open (replaced, timed out, the caller cancelled). The newest stream wins:
    a second `GET /api/bridge/jobs` closes the older stream, whose in-flight jobs resolve
    `Disconnected`, and `connected` stays true. `connected` turns false when the current stream
    ends; a bridge that vanishes without closing its socket is noticed at the next write (a job,
    or the 15 s ping) at the latest.
  - `FakeAssistantBridge(connected = true, respond = { Done("") })` (main source set, ADR-0003):
    records `submitted`, answers `respond(job)` while connected and `NoBridge` after
    `setConnected(false)`.
  - `GET /api/bridge/jobs` -> SSE in the `respondTextWriter` shape of `/api/events`:
    `Cache-Control: no-cache`, a `: ping` comment at once and every 15 s, then one `event: job`
    frame (`data` = `BridgeJobDto`) per submitted job and `event: cancel` (`data` =
    `BridgeCancelDto`) per cancelled one. Quiet end when the connection drops.
  - `POST /api/bridge/jobs/{id}/result` with a `BridgeJobResultDto` body -> 204 (the waiting
    `submit` resolves `Done`/`BridgeError`) | 404 `unknown_job` (never issued, or ended too long
    ago -- the hub remembers the last 256 ended ids) | 409 `job_closed` (already answered,
    replaced, timed out, cancelled or disconnected) | 400 `bad_request` (not a valid result) |
    400 `too_large` (body over 4 KB; never buffers more). A 400 leaves the job waiting.
- Phone web UI (#63, `WebUiRoutes.kt`), public: `GET /` -> 200 `text/html` (`web/index.html`),
  `GET /static/<file>` -> any file under `src/main/resources/web/` (`app.js`, `app.css`) via
  `staticResources`; vanilla JS, no build step, no external resources. The page pairs with
  `POST /api/pair` when `localStorage` holds no token (it sends no `scope`, so its token is
  `phone`-scoped), sends `Authorization: Bearer` on every protected call (a 401 clears the token
  and shows the PIN form), follows `/api/events?token=` and falls back to polling
  `GET /api/torrents` every 3 s when the stream fails. Each download row
  (#64) also has `SUBIR SUBTÍTULO` (file input `.srt,.ass,.ssa,.vtt` -> multipart
  `POST /api/subtitles` with `torrentId` + `file`) and `BORRAR` (a `<dialog>` with the checkbox
  `Borrar también los archivos` -> `DELETE /api/torrents/{id}?deleteFiles=true|false`); results
  are shown inline in Spanish, a 4xx shows the server's error `message`.

## Boundaries
- Talks to `TorrentEngine` and repositories through interfaces only. No UPnP, nothing exposed to the Internet. Never log tokens/PINs.

## Tests
Route tests with an in-process test client and `FakeTorrentEngine`; auth tests (401 without token)
for every protected endpoint, and a test that `?token=` is accepted only on `/api/events`.
`LanAddressPolicyTest` is table-driven over both address families; route tests other than the
LAN-guard's own use `ServerDeps.allowTestRemoteHeader = true` with a test client that sends
`X-Test-Remote` (`lanClient()`), since the in-process test client's real remote address is never an
IP literal. `EventsRouteTest`'s happy path drives a real `embeddedServer` on a loopback port with a
real client engine (`ktor-client-cio`, test-only) instead of `testApplication`: the in-process test
host runs a request's whole pipeline -- including the response body -- to completion before handing
anything back to its client, which an SSE stream that outlives the request never does on its own;
a real loopback connection has no such limitation and needs no `X-Test-Remote` override, since
`127.0.0.1` already satisfies `LanAddressPolicy`.
`BridgeJobHubTest` covers the hub's rules in virtual time (`runTest`); `BridgeRoutesTest` runs
the result route and the bridge-only auth in-process, opening the stream side straight on the hub;
`BridgeJobsStreamTest` drives `GET /api/bridge/jobs` over a real loopback connection (job, result,
replace -> cancel, newest stream wins).
`LogsRouteTest` (the page) and the 401/400 paths of `LogsStreamRouteTest` run in-process; the
stream's happy paths drive a real loopback `embeddedServer` like `EventsRouteTest` (boot frame,
backlog from `since`, live lines, level filter), and assert that `?token=` is 401 on
`/api/logs/stream`.
