// Root build (#37): declares the plugin versions once, from the catalog, and applies none of them.
// Each module applies what it needs with `alias(libs.plugins...)` and sets `jvmToolchain(17)`.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint) apply false
}

// Formatting is enforced mechanically (#38): every project, the root included (for its
// `*.gradle.kts`), gets `ktlintCheck`/`ktlintFormat`. The rules live in the root `.editorconfig`.
val ktlintPluginId =
    libs.plugins.ktlint
        .get()
        .pluginId
val ktlintVersion = libs.versions.ktlint.get()
allprojects {
    apply(plugin = ktlintPluginId)
    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
        filter {
            // Generated sources (KSP/Room, Compose) are not ours to format.
            exclude { it.file.path.contains("${File.separator}build${File.separator}") }
        }
    }
}
