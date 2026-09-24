plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.teachermovies.http"
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
    // The API adds a torrent to a chosen volume and reports both, so it needs the public types of
    // :torrent and :storage as well as :core-model (AGENTS.md dependency direction).
    // `api`: `PairingManager` (in `ServerDeps`) takes a `SettingsRepository` in its constructor.
    api(project(":core-model"))
    // `api`: `ServerDeps` exposes `TorrentEngine` and `SpaceInfo` in its public constructor.
    api(project(":torrent"))
    api(project(":storage"))

    // ADR-0002: Ktor server with the CIO engine, JSON via kotlinx.serialization, JSON error pages.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    // `EventsRouteTest` (#62) needs a real client against a real loopback port; see libs.versions.toml.
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
}
