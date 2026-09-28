// The laptop-side bridge (#271, ADR-0005 §1): a Kotlin/JVM command-line program,
// `teachermovies-bridge`, that pairs with the TV, keeps the bridge token in
// ~/.config/teachermovies-bridge/config.json (mode 0600) and reports its own health. Pure JVM, so
// it may depend only on :bridge-protocol -- never on an Android module (AGENTS.md).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Gradle core plugin, bundled with the wrapper (ADR-0004 row "Gradle `application` plugin"):
    // `run` and `installDist` give the `teachermovies-bridge` start script the systemd user unit
    // (#278) will launch. No version to pin, so no catalog alias.
    id("application")
}

kotlin {
    jvmToolchain(17)
}

application {
    // Names the start scripts `teachermovies-bridge` rather than the module name.
    applicationName = "teachermovies-bridge"
    mainClass.set("com.teachermovies.bridge.MainKt")
}

dependencies {
    // The wire DTOs shared with :http-server (#269, ADR-0005 §1).
    implementation(project(":bridge-protocol"))
    // This module encodes its own config JSON and the pair request body.
    implementation(libs.kotlinx.serialization.json)
    // The TV API client (#271): the same Ktor-client-on-CIO set :mobile-app uses for its own client
    // (ADR-0004, "Ktor client"). No `:http-server` dependency: that is an Android library and would
    // pull the Ktor server into the laptop program.
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    // `run` (#277): an mDNS browse of the TV's `_http._tcp` announcement when the URL saved at
    // pairing stops answering (ADR-0004 row "JmDNS"). Pure JVM; brings `slf4j-api` along, as Ktor does.
    implementation(libs.jmdns)

    testImplementation(libs.junit)
    // "Tests run against an in-test Ktor server standing in for the TV" (#271): a real CIO server on
    // a loopback port, so the server artifacts are test-only here, exactly as in :mobile-app.
    testImplementation(libs.ktor.server.core)
    testImplementation(libs.ktor.server.cio)
}
