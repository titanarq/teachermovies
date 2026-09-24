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
    implementation(project(":core-model"))
    // `api`, not `implementation`: `HiddenSubtitleController`'s constructor takes a `Player`, so
    // anything wiring the assistant compiles against it (the same way `:http-server` depends on
    // `:torrent`). Only the public `com.teachermovies.player.api` package is used from here; no
    // `org.videolan` type is referenced in `:assistant` (ADR-0001 §2, AGENTS.md).
    api(project(":player"))
    // `api`, not `implementation`: `SubtitleEngine.currentSubtitle` is a `StateFlow` and `load`'s
    // caller drives it off a `CoroutineScope` (same reasoning as `:torrent` and `:player`).
    api(libs.kotlinx.coroutines.core)
    // Official Anthropic Java SDK behind `AnthropicTranslationProvider` (#90). `implementation`: no
    // SDK type appears in this module's public API.
    implementation(libs.anthropic.java)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
