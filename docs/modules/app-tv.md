# Module: app-tv

**Gradle path:** `:app-tv` (the Android TV application module).

## Responsibility
- Application entry point (`Application`, launcher `Activity` with `LEANBACK_LAUNCHER`).
- Compose for TV UI and navigation: `Library`, `Downloads`, `Settings`, first-run setup, player screen host.
- First-run screen shows `Server: <ip>:8787`, pairing PIN, free space, download folder, torrent engine state.
- Wiring (DI) of every feature module; starts the foreground torrent/HTTP service.

## Boundaries
- Depends on feature modules' public interfaces only; contains no torrent, HTTP or VLC logic itself.
- All screens fully usable with the D-pad; focus order and initial focus are acceptance criteria.

## Tests
ViewModel unit tests with fakes (`FakeTorrentEngine`, in-memory repositories). Compose UI tests are instrumented and optional.
