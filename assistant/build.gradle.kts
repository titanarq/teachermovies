plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.teachermovies.assistant"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `api`, not `implementation`: `PersistentCachingTranslationProvider`'s constructor takes a
    // `TranslationCacheRepository` (#274), so whoever wires the persistent translation cache compiles
    // against it (the same reasoning as `:player` below). Only `com.teachermovies.core.repo` is used
    // from here; no Room type appears in `:assistant`.
    api(project(":core-model"))
    // `api`, not `implementation`: `HiddenSubtitleController`'s constructor takes a `Player`, so
    // anything wiring the assistant compiles against it (the same way `:http-server` depends on
    // `:torrent`). Only the public `com.teachermovies.player.api` package is used from here; no
    // `org.videolan` type is referenced in `:assistant` (ADR-0001 §2, AGENTS.md).
    api(project(":player"))
    // `api`, not `implementation`: `SubtitleEngine.currentSubtitle` is a `StateFlow` and `load`'s
    // caller drives it off a `CoroutineScope` (same reasoning as `:torrent` and `:player`).
    api(libs.kotlinx.coroutines.core)
    // Official Anthropic Java SDK behind `AnthropicTranslationProvider` (#90). `implementation`: no
    // SDK type appears in this module's public API. Dormant since #290 (ADR-0005 §9): nothing wires
    // the provider, but it and this dependency are kept for a future extension -- do not delete.
    implementation(libs.anthropic.java)
    // Reads the explain handler's JSON document (#291) as a `JsonElement` tree in `Explanation.parse`;
    // no serialization plugin and no serialization type in this module's public API.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
