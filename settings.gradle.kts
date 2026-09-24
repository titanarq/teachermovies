pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // Gradle evaluates pluginManagement before the version catalog exists, so this is the one
        // version that cannot be read through `libs`; it is recorded as `foojayResolver` in
        // gradle/libs.versions.toml (ADR-0004) and the two must stay equal.
        id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
    }
}

// Provisions the JDK 17 toolchain on hosts that only have a newer JDK (ADR-0004).
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // jlibtorrent is published only on FrostWire's own Maven (ADR-0004).
        maven("https://dl.frostwire.com/maven") {
            content {
                includeGroup("com.frostwire")
            }
        }
    }
}

rootProject.name = "teachermovies"

include(":app-tv")
include(":core-model")
include(":torrent")
include(":storage")
include(":http-server")
include(":player")
include(":assistant")
include(":discovery")
