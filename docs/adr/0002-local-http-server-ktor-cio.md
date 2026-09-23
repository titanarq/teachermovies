# ADR-0002: Local HTTP server: Ktor (CIO engine), bearer auth on all /api/* but status and pairing

- **Status:** Accepted
- **Date:** 2026-09-24
- **Deciders:** MatillaM
- **Refines:** ADR-0001 §5 (which left the server library to its first task)

## Context

`:http-server` embeds a LAN-only HTTP API on `:8787` (configurable) in the Android TV app: a JSON
REST API (`docs/VISION.md` "API local"), a live progress stream for the phone web UI, multipart
uploads (.torrent, subtitles) and a small static web page. It must run inside the app process,
fit the coroutines/`Flow` architecture (`AGENTS.md`), be testable on the JVM without a network, and
stay small on the APK.

## Decision

1. **Library: Ktor server 3.x with the CIO engine** (version pinned in ADR-0004).
   - Embeddable: `embeddedServer(CIO, port, host)` started/stopped by `LocalHttpServer`, no servlet
     container, no Netty/Jetty dependency tree.
   - Coroutine-native: handlers are `suspend`, so collecting `TorrentEngine` `StateFlow`s and
     writing SSE is natural; no thread-per-request.
   - First-class SSE (`respondTextWriter(ContentType.Text.EventStream)` or the `sse` plugin) and a
     WebSocket plugin if the phone app later needs a bidirectional channel.
   - `ktor-server-test-host` (`testApplication { }`) gives in-process route tests with no socket.
   - CIO is pure Kotlin (no native code, no extra ABI concern) and small.
   Rejected: NanoHTTPD (blocking, thread-per-connection, no coroutine story, unmaintained),
   Netty/Jetty engines (heavier, more transitive dependencies on Android).
2. **Auth scope.** Every `/api/*` endpoint requires `Authorization: Bearer <token>` **except**
   `GET /api/status` (connection indicator, discloses no content) and `POST /api/pair` (the only way
   to obtain a token). This tightens `docs/VISION.md` §9 ("add/delete require Bearer"): read
   endpoints (`/api/torrents*`, `/api/library`, `/api/events`) also expose the household's
   downloads and are protected too. A missing/invalid token -> 401 `{"error":"unauthorized"}` with
   `WWW-Authenticate: Bearer`.
3. **SSE token.** `GET /api/events` also requires the token. Because the browser `EventSource` API
   cannot set request headers, that one route (and only it) additionally accepts the token as the
   query parameter `?token=<token>`. Every other route rejects a query-param token (401). Request
   logging must redact the `token` query parameter as well as the `Authorization` header.
4. Static web UI (`/`, `/static/*`) is served without a token; it holds no data and performs the
   pairing flow itself.

## Consequences

- The web UI stores the token in `localStorage`, sends it as a header on every `fetch`, and opens
  `EventSource('/api/events?token=' + encodeURIComponent(token))`.
- Tokens in URLs can leak into logs/history; accepted only for SSE, on the LAN, and mitigated by
  redaction. If a WebSocket channel replaces SSE later, the token moves to the first message.
- The phase-2 phone app (`:mobile-app`) must pair before any read call.
