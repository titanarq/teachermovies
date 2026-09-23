# ADR-0003: Manual DI via AppContainer; fakes live in the main source set

- **Status:** Accepted
- **Date:** 2026-09-24
- **Deciders:** MatillaM

## Context

`AGENTS.md` asks the skeleton to settle the DI approach (ADR needed if not manual DI or Hilt) and
wants fakes over mocks, with a `FakeTorrentEngine` as the default for anything above `:torrent`.
Several modules' tests need the same fakes (`:http-server`, `:app-tv`, `:player` sessions...).

## Decision

1. **Manual DI.** `:app-tv` owns one plain class `com.teachermovies.tv.di.AppContainer`, created
   in `TeacherMoviesApp.onCreate` and held as `lateinit var container`. It constructs every
   implementation (`JLibTorrentEngine`, Room repositories, `DataStoreSettingsRepository`,
   `VlcPlayer`, `LocalHttpServer`, ...) and exposes them typed as their interfaces. Classes take
   their collaborators through the constructor; no DI framework (no Hilt, Koin, Dagger), no service
   locator calls outside `AppContainer`. ViewModels get their dependencies through a
   `ViewModelProvider.Factory` built from the container.
   Why: ~15 bindings, one process, no generated code, no KSP/kapt time for DI, trivially readable
   by small-context workers, and tests build graphs by hand anyway.
2. **Fakes in the main source set** of the module that owns the interface, in a `fake` package:
   `com.teachermovies.torrent.fake.FakeTorrentEngine`, `com.teachermovies.player.fake.FakePlayer`,
   `com.teachermovies.core.repo.fake.InMemoryTorrentRepository` (and similar later). They are
   deterministic (no real time, no sleeps, `MutableStateFlow` state) and expose test controls.
   Why: other modules' JVM tests depend on them via the normal module dependency; `testFixtures`
   for Android library modules is still awkward with AGP/Kotlin and a shared `:testing` module
   would invert the dependency direction. Cost: a few KB of fake classes in the APK, and R8 strips
   them from release builds as they are unreferenced.
3. `AppContainer` never wires a fake in production code; `:app-tv` may use fakes only in
   `src/test` or a debug-only preview.

## Consequences

- Revisit (new ADR) if bindings grow past ~40 or a second process/entry point appears.
- Fakes are part of each module's public surface and follow its API changes in the same PR.
