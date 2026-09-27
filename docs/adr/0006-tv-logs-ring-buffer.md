# ADR-0006: App logs in a redacted in-memory ring buffer, served over the HTTP API

- **Status:** Accepted
- **Date:** 2026-09-27
- **Deciders:** MatillaM

## Context

Diagnosing the TV today needs `adb logcat`. The app has about a dozen raw `android.util.Log` calls
(`VlcPlayer`, `TorrentService`, `JLibTorrentEngine`, `BootCompletedReceiver`) and no logging
facade. The laptop bridge (ADR-0005) and the phone web UI can already reach the TV's
authenticated HTTP API, so logs can be read there instead, provided nothing secret ends up in them.

## Decision

1. **Facade.** An `AppLog` facade in `:core-model` (pure Kotlin) with pluggable sinks;
   `AppContainer` installs a logcat sink and a `RingBufferLogSink`. `AppLog` is an accepted global
   (an exception to ADR-0003). All `android.util.Log` calls move to it.
2. **Buffer.** `LogEntry(seq, timeMs, level, module, message)`, capped at 5000 lines / 1 MiB, each
   message at most 2 KB, with a `bootId` per process. INFO and above in release builds, DEBUG in
   debug builds. In memory only (no persisted crash log).
3. **Redaction.** A `LogRedactor` runs before storage: Bearer/`Authorization`/`token=`, 43-char
   base64url tokens, `sk-ant-…`, PINs, `password=`, `Api-Key`, `OPENSUBTITLES_*`, JWTs, and
   registered secrets (the current PIN). Table-tested. Defence in depth: callers still never log
   secrets (AGENTS.md).
4. **Endpoints.** `GET /api/logs?since=&level=&limit=` and `GET /api/logs/stream` (SSE,
   `Authorization` header only, no `?token=`), reachable with a phone- or bridge-scoped token
   (ADR-0005 §4). The phone web UI adds a "Registros" view that reads the stream with `fetch` plus
   the header.
5. **Laptop mirror.** The bridge mirrors the stream to `<logsDir>/<yyyy-mm-dd>.log` (default
   `/home/titan/projects/teachermovies/.cache/tv-logs`), backfills with `since`, handles a `bootId`
   change, and keeps 14 days.

## Consequences

- TV diagnostics without adb, from the phone or from the laptop's files.
- A bounded memory cost (≤1 MiB) and one global object in the app.
- Logs are lost on process death unless the bridge was mirroring them.
- The redactor is a second line of defence, not a licence to log secrets.
