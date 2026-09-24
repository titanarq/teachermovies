plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // app-tv -> feature modules -> core-model (AGENTS.md dependency direction); `implementation`
    // so nothing leaks further.
    implementation(project(":core-model"))
    implementation(project(":torrent"))
    implementation(project(":storage"))
    implementation(project(":http-server"))
    implementation(project(":player"))

    // The Compose-for-TV shell (#44). The BOM aligns every compose artifact with the combination
    // ADR-0004 verified against compileSdk 35; `tv-material` (package `androidx.tv.material3`) is
    // what supplies TabRow, Tab, Text and MaterialTheme, and re-exports compose-ui and foundation
    // as `api`, which is where the shell's layout and focus modifiers come from.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.tv.material)

    // `ComponentActivity` + `setContent` + `viewModels()` + `BackHandler` for the launcher activity.
    implementation(libs.androidx.activity.compose)

    // `MainViewModel : ViewModel`, lifecycle-aware collection of its `StateFlow`, and the
    // `MutableStateFlow` behind it (AGENTS.md: coroutines + `StateFlow` is the concurrency model).
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
