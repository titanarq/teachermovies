plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.teachermovies.core"
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

// Room writes one JSON per database version here; the files are committed so every schema change
// is reviewable and a future migration can be tested against the previous version.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // `api`, not `implementation`: `Flow` in the public contract of `SettingsRepository` and
    // `DataStore<Preferences>` in the constructor of the production implementation are both things
    // a consumer compiles against when it wires DI (ADR-0003).
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.datastore.preferences)
    // `api`: `TeacherMoviesDatabase` is a `RoomDatabase` that consumers build and wire (ADR-0003).
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
