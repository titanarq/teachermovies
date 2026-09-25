plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.teachermovies.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.teachermovies.mobile"
        minSdk = 26
        targetSdk = 35
        // No `ndk.abiFilters`: the phone app packages no native library.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // No project module (#195, #196): the phone app talks to the TV only through its HTTP API.

    // Phone UI: Compose Material 3, versioned by the Compose BOM (ADR-0004). `tv-material` is the
    // TV app's and is not used here.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)

    // `ComponentActivity` + `setContent` for the launcher activity.
    implementation(libs.androidx.activity.compose)

    // TV API client (#196, ADR-0004 "Ktor client in `:mobile-app`"): Ktor client on CIO, JSON via
    // kotlinx.serialization. No `:http-server` dependency -- that would pack the Ktor server into
    // the phone APK; the DTOs are mirrored in `com.teachermovies.mobile.api`.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)

    // Paired TV + token (#196).
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // `KtorTvApiTest` answers with fixed JSON from a real loopback `embeddedServer` (CIO).
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
}
