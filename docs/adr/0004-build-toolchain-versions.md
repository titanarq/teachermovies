# ADR-0004: Build toolchain versions

- **Status:** Accepted
- **Date:** 2026-09-24
- **Deciders:** MatillaM

## Context

The skeleton (#37) needs a coherent set of versions that (a) builds with the Gradle 8.7 wrapper
committed in #36 and cached on the worker host, (b) builds on the host (JDK 21 only, Android SDK
at `$ANDROID_HOME`, `sdkmanager` forbidden to workers) and on CI (ubuntu, JDK 17), and (c) lets the
native libraries of ADR-0001 resolve. All combinations below were verified on 2026-09-24 with a
throwaway probe (`assembleDebug test` of an app using Compose-TV, Room+KSP, DataStore, Ktor CIO,
jlibtorrent and libVLC).

## Decision

| Item | Version | Why |
|---|---|---|
| Gradle (wrapper) | 8.7 | Cached locally; meets AGP 8.6's minimum. No bump needed. |
| JDK toolchain | 17 via `foojay-resolver-convention` 0.8.0 | Host has only JDK 21; foojay provisions 17. CI uses Temurin 17. |
| compileSdk / targetSdk | 35 | `platforms;android-35` + `build-tools;35.0.0` installed on the host (2026-09-24) and in CI. |
| minSdk | 26 | Android TV 8.0+ covers all target devices; above jlibtorrent's 24 and libVLC's 17. |
| AGP | 8.6.1 | Newest AGP that runs on Gradle 8.7 (8.7.x needs Gradle 8.9) and officially supports API 35. |
| Kotlin (+ compose plugin) | 2.1.21 | Supports Gradle 8.7 and AGP 8.6; lets us consume Ktor 3.1 (built with Kotlin 2.1). |
| KSP | 2.1.21-2.0.2 | Matches Kotlin 2.1.21 (Room). |
| Compose BOM | 2025.08.00 | Compose 1.9.0 (`minCompileSdk=35`, AGP >= 8.6.0); verified. Later BOMs were not verified against SDK 35. |
| `androidx.tv:tv-material` | 1.1.0 | Latest stable Compose-for-TV (`minCompileSdk=35`, AGP >= 8.6.0); verified with the BOM above. |
| activity-compose | 1.10.1 | 1.11.0 requires compileSdk 36 and AGP 8.9.1. Rule for any other AndroidX artifact: its AAR `minCompileSdk` must be <= 35 and `minAndroidGradlePluginVersion` <= 8.6.1 (`checkDebugAarMetadata` fails otherwise). |
| core-ktx / lifecycle | 1.16.0 / 2.9.4 | core 1.17.0 requires compileSdk 36; lifecycle 2.9.x needs 34. |
| Room | 2.7.2 (KSP) | Verified with KSP 2.1.21-2.0.2. |
| DataStore Preferences | 1.1.7 | Verified with SDK 35. |
| Ktor server (CIO, test-host, SSE) | 3.1.3 | ADR-0002; 3.1 is built with Kotlin 2.1. |
| Ktor client (core, CIO; test-only) | 3.1.3 (`version.ref = ktor`) | `EventsRouteTest` drives a real loopback server, since the in-process test host never streams SSE; introduced by #62 (PR #143). |
| jlibtorrent | 2.0.12.9 | Latest release whose jars actually resolve on `https://dl.frostwire.com/maven` (`com.frostwire:jlibtorrent` + `jlibtorrent-android-{arm,arm64,x86,x86_64}`). Metadata lists 2.0.12.15/README mentions 2.0.13.6, but their jars 404. |
| libVLC (`org.videolan.android:libvlc-all`) | 3.7.2 | Latest 3.x usable with compileSdk 35: 3.7.3 declares `minCompileSdk=37`, 3.7.4-3.7.6 `minCompileSdk=36`. |
| kotlinx-coroutines | 1.10.2 | Required by Room, DataStore and Ktor; version compatible with Kotlin 2.1.21. |
| Robolectric (test-only) | 4.14.1 | JVM unit tests that need Android framework classes; introduced by #40 (PR #107). Supports SDK 35. |
| `androidx.test:core` (test-only) | 1.6.1 | `ApplicationProvider` for Robolectric tests; introduced by #40 (PR #107). |
| `kotlinx-coroutines-test` (test-only) | 1.10.2 | Same `coroutines` version ref as kotlinx-coroutines (must stay in lockstep); `runTest`/virtual time for the `:player` `StreamingPlaybackController` tests (#98). Already in the catalog; this row records it. |
| `com.anthropic:anthropic-java` | 2.65.0 | Official Anthropic Java SDK, runtime dependency for `AnthropicTranslationProvider` (#90, post-MVP). Pure JVM jar (not an AAR), so AGP's `minCompileSdk`/AAR-metadata checks don't apply; requires Java 8+ (JDK 17 toolchain satisfies it) and pulls in OkHttp 4.12.0 and Jackson 2.19.4 (Jackson >= 2.13.4 required) transitively, both fine on minSdk 26. |
| `org.jlleitschuh.gradle.ktlint` (Gradle plugin, build-only) | 14.2.0 | Formatting check/format tasks (`ktlintCheck`/`ktlintFormat`) for every project, root included (#38). Latest release; minimum Gradle 7.4 / AGP 4.1, so fine on Gradle 8.7 + AGP 8.6.1. |
| ktlint engine (`com.pinterest.ktlint`, build-only) | 1.8.0 | Run by the plugin above (`ktlint { version }`, #38); latest release, verified with `ktlintCheck`/`ktlintFormat` over the whole repo. |

Other notes for the skeleton:
- `gradle.properties` must set `org.gradle.jvmargs=-Xmx4g`: with the native libVLC/jlibtorrent
  payload `packageDebug` failed intermittently under Gradle's default heap.
- Repositories: `google()`, `mavenCentral()`, plus the FrostWire repo restricted with
  `content { includeGroup("com.frostwire") }`.

## Consequences

- We sit one SDK behind latest stable (AGENTS.md prefers latest). Upgrading to compileSdk 36 is a
  single infra task: Gradle >= 8.11.1, AGP >= 8.9, then libVLC 3.7.4+, activity 1.11+ and newer
  Compose BOMs; it needs the human to install `platforms;android-36`.
- Every version lives in `gradle/libs.versions.toml`; this ADR is updated (or superseded) when the
  table changes.
