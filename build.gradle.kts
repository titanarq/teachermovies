// Root build (#37): declares the plugin versions once, from the catalog, and applies none of them.
// Each module applies what it needs with `alias(libs.plugins...)` and sets `jvmToolchain(17)`.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// The no-op below keeps `./gradlew assembleDebug` (and so CI) green while `:app-tv` still has no
// build file. Gradle runs an unqualified task name in every project that has it, so no root
// aggregation is needed: drop it as soon as `:app-tv` provides the real task (stage 3/3 of #37).
tasks.register("assembleDebug") {
    group = "build"
    description = "No-op until :app-tv exists."
}
