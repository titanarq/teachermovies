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
  versioned by the Compose BOM, ADR-0004); `androidx.tv:tv-material` is not used. It depends on no
  other project module yet.
- Manifest: `android.permission.INTERNET` and one launcher `MainActivity` (`ComponentActivity` +
  `setContent`) showing a placeholder Material 3 screen titled `Movie Assistant` with
  `Buscando la TV...`; later sub-issues of #35 replace it.
- `share.SharedLinkParser.extractMagnet(text: String?): String?` -- pure Kotlin, no Android type:
  - finds the first `magnet:?` in `text` (scheme matched case-insensitively), cut at the first
    whitespace character, so a magnet inside surrounding shared text or followed by a newline works;
  - returns it only if its query has an `xt` parameter whose value starts with `urn:btih:` (v1) or
    `urn:btmh:` (v2), otherwise `null`; only the first magnet is considered;
  - returns `null` for null, blank or magnet-less text (including `http(s)` links).

## Boundaries
- Uses only the public HTTP API; no shared code with `app-tv` beyond `discovery` and API DTOs.

## Tests
JVM tests for link parsing and API client with a fake server.
- `share/SharedLinkParserTest` (`scripts/test.sh :mobile-app:test`): bare magnet, magnet inside
  other text, uppercase `MAGNET:`, `urn:btmh:` magnet, trailing newline, two magnets (first wins),
  magnet without `xt`, `http(s)` links, blank text and null.
