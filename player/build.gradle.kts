plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.teachermovies.player"
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
    // `api`, not `implementation`: `StateFlow` appears in `Player`'s own public contract, so a
    // consumer compiles against coroutines too (same reason as in `:torrent`).
    api(libs.kotlinx.coroutines.core)

    implementation(project(":core-model"))

    // Supervised playback of a still-downloading file (`StreamingPlaybackController`) needs the
    // public types of :torrent (`TorrentEngine`, `RangeReadiness`); this follows the same
    // `app-tv -> feature modules -> core-model` direction :http-server already depends under
    // (AGENTS.md). Only its `api` package is used; :torrent keeps its native BitTorrent library
    // as its own `implementation` dependency.
    implementation(project(":torrent"))

    // `implementation`, never `api`: no `org.videolan` type crosses this module's boundary
    // (ADR-0001 §2, `docs/modules/player.md`), so consumers compile against `api` alone.
    implementation(libs.libvlc.all)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
