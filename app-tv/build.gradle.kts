plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.teachermovies.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.teachermovies.tv"
        minSdk = 26
        targetSdk = 35
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
    // app-tv -> feature modules -> core-model (AGENTS.md dependency direction); `implementation`
    // so nothing leaks further. The Compose-for-TV libraries belong to the UI shell (#44).
    implementation(project(":core-model"))
    implementation(project(":torrent"))
    implementation(project(":storage"))
    implementation(project(":http-server"))
    implementation(project(":player"))

    testImplementation(libs.junit)
}
