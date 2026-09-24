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

    testImplementation(libs.junit)
}
