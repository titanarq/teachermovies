plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.teachermovies.torrent"
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
    // `api`, not `implementation`: `TorrentId`/`DownloadState` (from `:core-model`) and `StateFlow`
    // appear in `TorrentEngine`'s own public contract, so a consumer compiles against them too.
    api(project(":core-model"))
    api(libs.kotlinx.coroutines.core)

    // jlibtorrent 2.0.12.9 (ADR-0001 §3, ADR-0004): the Java bindings plus one native-lib jar per
    // ABI that :app-tv packages (arm64-v8a, armeabi-v7a, x86_64 -- docs/modules/infra.md).
    // `implementation`: no jlibtorrent type may escape this module.
    implementation(libs.jlibtorrent)
    implementation(libs.jlibtorrent.android.arm)
    implementation(libs.jlibtorrent.android.arm64)
    implementation(libs.jlibtorrent.android.x64)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
