plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
    implementation(project(":core-model"))
    implementation(project(":torrent"))
    implementation(project(":storage"))

    testImplementation(libs.junit)
}
