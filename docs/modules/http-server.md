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

## Public contract (package `com.teachermovies.http`)
- `LocalHttpServer(deps: ServerDeps, port: Int, host: String = "0.0.0.0")`: `start()` is
  non-blocking (`embeddedServer(CIO, port, host) { module(deps) }.start(wait = false)`), `stop()` is
  graceful and idempotent.
- `fun Application.module(deps: ServerDeps)` holds all plugins and routing; tests run it in-process
  with `testApplication { application { module(fakeDeps) } }`.
- `ServerDeps(engine: TorrentEngine, space: () -> SpaceInfo?, appVersion: String, clock: () -> Long)`,
  extended by later issues.
- `GET /api/status` (public) -> 200
  `{"version":"...","engine":"running","freeBytes":123,"totalBytes":456,"torrents":2}`; `engine` is
  the `EngineStatus` in lower case, `freeBytes`/`totalBytes` are `null` when unknown.
- Errors are JSON `{"error":"<code>","message":"..."}` (StatusPages): unknown `/api/*` route -> 404
  `not_found`; any uncaught exception -> 500 `internal`, never with a stack trace or exception message.

## Boundaries
- Talks to `TorrentEngine` and repositories through interfaces only. No UPnP, nothing exposed to the Internet. Never log tokens/PINs.

## Tests
Route tests with an in-process test client and `FakeTorrentEngine`; auth tests (401 without token) for every protected endpoint, and a test that `?token=` is accepted only on `/api/events`.
