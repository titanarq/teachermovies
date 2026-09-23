# AGENTS.md -- teachermovies

Rules for every agent (human-driven or headless) working in this repository. Read this first,
then the module doc of the module your issue names (`docs/modules/<module>.md`), then any ADR it
links. The product vision is `docs/VISION.md` (Spanish); decisions are in `docs/adr/`.

## What this is

An autonomous **Android TV media center**: a built-in BitTorrent client (jlibtorrent), a libVLC
player, a small embedded HTTP server so a phone on the LAN can send magnets/.torrent files, and an
**English-learning assistant** (hidden EN subtitle track -> "what did they say?" -> replay, EN TTS,
ES translation + TTS). Only content whose download and playback are authorized is in scope.

MVP 1.0 critical path: phone -> paste magnet -> TV receives -> metadata -> pick the movie file ->
download -> stored -> appears in Library -> libVLC plays it.

## How the work is organized

- The tracker is GitHub issues on `titanarq/teachermovies`, driven by the agent OS in `agent_os/`
  (a `git subtree` of `titanarq/agent-os`; **never edit anything under `agent_os/`**). Its
  configuration is `config/agents.yaml`.
- One issue = one unit of work. The issue body is the brief: acceptance criteria, module label,
  budget class (`<!-- budget: <class> -->`). Stay inside what the issue asks for.
- Workers work on a branch in their own worktree and open a PR. **Nobody merges their own work**;
  the human merges.
- A task that uncovers something out of scope files a new issue instead of widening its diff.

## Modules

Gradle modules map 1:1 to `module:<name>` labels and `docs/modules/<name>.md`:

| module | Gradle path | owns |
|---|---|---|
| app-tv | `:app-tv` | Android TV application, Compose for TV UI, D-pad navigation, DI wiring |
| core-model | `:core-model` | domain model, Room database + DAOs, DataStore settings |
| torrent | `:torrent` | `TorrentEngine` interface + jlibtorrent implementation, torrent service |
| storage | `:storage` | storage volumes (internal/USB/SSD), download layout, free space |
| http-server | `:http-server` | embedded HTTP API on :8787, web UI, SSE/WebSocket, pairing/auth |
| player | `:player` | libVLC playback, tracks, subtitles, resume position |
| assistant | `:assistant` | subtitle engine, capture, TTS, translation provider interface |
| discovery | `:discovery` | NSD/mDNS announcement (`movieassistant.local`) |
| mobile-app | `:mobile-app` | phase 2 phone app (share -> send to TV) |
| infra | (root) | Gradle build, version catalog, CI, agent OS config |

Dependency direction: `app-tv` -> feature modules -> `core-model`. Feature modules never depend on
`app-tv` or on each other's implementations; they talk through interfaces in `core-model` or their
own `api` package. `torrent` exposes only `TorrentEngine` and its value types to other modules --
never a jlibtorrent type.

## Kotlin / Android conventions

- Kotlin only, JDK 17 toolchain, Gradle Kotlin DSL (`*.gradle.kts`) with a version catalog
  (`gradle/libs.versions.toml`). Add dependencies ONLY through the catalog.
- `minSdk` 24 or higher as the skeleton decides; `targetSdk`/`compileSdk` = latest stable.
- UI: Jetpack Compose for TV (`androidx.tv:tv-material`), every screen navigable by D-pad;
  focus handling is part of "done".
- Concurrency: coroutines + `Flow`/`StateFlow`; no RxJava, no raw threads except where a native
  library forces its own (jlibtorrent alert loop, libVLC events) -- bridge those into Flows.
- Architecture: unidirectional data flow (ViewModel exposes `StateFlow<UiState>`); repositories
  own data; no Android framework types in domain classes.
- DI: constructor injection; the chosen DI framework is decided by the skeleton task (ADR if it is
  not plain manual DI or Hilt).
- Persistence: Room stores metadata only (info-hash, name, path, progress, main file, chosen
  audio/subtitle track, last position); media files live on disk under
  `<volume>/Movies/<torrent-id>/`.
- Errors: no swallowed exceptions; model failures as sealed results at module boundaries.
- Formatting: ktlint/ktfmt style as configured by the build; no wildcard imports.
- Security: the HTTP server binds LAN interfaces only, mutating endpoints require
  `Authorization: Bearer <token>` obtained by PIN pairing; no UPnP for the HTTP server; never log
  tokens or PINs.
- Tests: JVM unit tests (`src/test`) for all logic; fakes over mocks where possible; a
  `FakeTorrentEngine` is the default for anything above the torrent module. Instrumented tests
  (`src/androidTest`) are allowed but are not run by the test command.

## Test command

`scripts/test.sh` (runs `./gradlew test`; extra args are passed to Gradle, e.g.
`scripts/test.sh :torrent:test`). Full log in `.cache/gradle-test-last.log`. Until the Gradle
skeleton exists it prints a message and exits 0. Every PR must leave `scripts/test.sh` green.

## Never touch

- `agent_os/` (the mechanism; changes go upstream to `titanarq/agent-os`).
- `docs/adr/*`, `docs/VISION.md`, `AGENTS.md` -- proposals to change them go in the PR
  description or a new issue; the human edits them.
- `.secrets/`, `.env`, signing keys; never commit credentials or print their contents.
