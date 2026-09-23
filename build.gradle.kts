// Root build (#37): declares the plugin versions once, from the catalog, and applies none of them.
// Each module applies what it needs with `alias(libs.plugins...)` and sets `jvmToolchain(17)`.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// The two no-ops below keep `./gradlew test` / `assembleDebug` (and so scripts/test.sh and CI)
// green while the included modules still have no build files. Gradle runs an unqualified task name
// in every project that has it, so no root aggregation is needed: drop each no-op as soon as the
// modules provide the real task (`test` in stage 2/3 of #37, `assembleDebug` in stage 3/3).
tasks.register("test") {
    group = "verification"
    description = "No-op until the module build files exist."
}
tasks.register("assembleDebug") {
    group = "build"
    description = "No-op until :app-tv exists."
}
