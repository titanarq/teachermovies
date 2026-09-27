// Pure-JVM DTO module (ADR-0005 §1, #269): the wire contract :http-server serves and
// :laptop-bridge (#271) will consume. Depends on no other project module.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `api`: consumers encode/decode the DTOs themselves, so the JSON runtime is part of this
    // module's contract.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
