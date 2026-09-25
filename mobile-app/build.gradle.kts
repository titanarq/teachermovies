plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
    // No project module yet (#195): the phone app talks to the TV only through its HTTP API.

    // Phone UI: Compose Material 3, versioned by the Compose BOM (ADR-0004). `tv-material` is the
    // TV app's and is not used here.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)

    // `ComponentActivity` + `setContent` for the launcher activity.
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
}
