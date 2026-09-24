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
- `ServerDeps(engine: TorrentEngine, space: () -> SpaceInfo?, appVersion: String, clock: () -> Long,
  pairing: PairingManager, allowTestRemoteHeader: Boolean = false)`, extended by later issues.
  `allowTestRemoteHeader` is test-only (#59) and must stay `false` in production.
- `com.teachermovies.http.auth.LanAddressPolicy.isAllowed(address: String): Boolean` (#59): whether
  a literal IPv4/IPv6 address is on the LAN -- loopback, `10/8`, `172.16/12`, `192.168/16`,
  `169.254/16`, `fe80::/10`, `fc00::/7`, and the IPv4-mapped IPv6 form of any allowed IPv4 range.
  Parses addresses by hand and never resolves a hostname, so an unparsable string (including a real
  hostname) is refused without a DNS lookup. The application-level guard applies it to
  `call.request.origin.remoteHost`; only when `ServerDeps.allowTestRemoteHeader` is `true` does an
  `X-Test-Remote` request header override that address, for route tests.
- `com.teachermovies.http.auth.PairingManager(settings: SettingsRepository, random: SecureRandom,
  clock: () -> Long)`: `currentPin()` (6 digits, zero-padded; new every 10 min and after each
  successful pairing), `suspend pair(pin): PairResult` (`Paired(token)` | `WrongPin` |
  `TooManyAttempts` once 5 wrong PINs fall within 60 s), `suspend isValid(token)`. Tokens are 32
  random bytes, base64url without padding; only their SHA-256 hex is persisted
  (`SettingsRepository.addAuthTokenHash`), so they survive restarts.
- `POST /api/pair` (public) body `{"pin":"482916","deviceName":"..."}` -> 200 `{"token":"..."}` |
  400 `bad_request` | 401 `wrong_pin` | 429 `too_many_attempts`.
- `fun Route.requireBearer(pairing, allowQueryToken = false) { ... }` wraps every protected route:
  missing/invalid token -> 401 `unauthorized` + `WWW-Authenticate: Bearer`, handler not run. Only
  `/api/events` passes `allowQueryToken = true`. `redactTokenQuery(uri)` is what any request
  logging must apply (and it must never log the `Authorization` header).
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
    `bad_request`) -> 204.
  - `PUT /api/torrents/{id}/files` `[{"index":0,"priority":"skip|normal|high"}]` -> 204 | 409
    `not_ready` before metadata | 400 `invalid_priority` | 400 `invalid_index` | 400 `bad_request`;
    a rejected body applies no change at all.
  - Every `{id}` route: 400 `invalid_id`, 404 `unknown_torrent`.
  - `EngineError.toHttp(): HttpError(status, body)` is the one `EngineError` -> HTTP mapping:
    `InvalidMagnet` 400 `invalid_magnet`, `InvalidTorrentFile` 400 `invalid_torrent`,
    `UnknownTorrent` 404 `unknown_torrent`, `AlreadyExists` 409 `already_exists` (+`id`), `NotReady`
    409 `not_ready`, `Unsupported` 501 `unsupported`, `Io` 500 `io_error` (engine text never
    copied into the body).
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

## Boundaries
- Talks to `TorrentEngine` and repositories through interfaces only. No UPnP, nothing exposed to the Internet. Never log tokens/PINs.

## Tests
Route tests with an in-process test client and `FakeTorrentEngine`; auth tests (401 without token) for every protected endpoint, and a test that `?token=` is accepted only on `/api/events`. `LanAddressPolicyTest` is table-driven over both address families; route tests other than the LAN-guard's own use `ServerDeps.allowTestRemoteHeader = true` with a test client that sends `X-Test-Remote`, since the in-process test client's real remote address is never an IP literal.
