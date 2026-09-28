package com.teachermovies.bridge.service

import com.teachermovies.bridge.config.ConfigLocation
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The start script the unit's `ExecStart` must name (#278). systemd refuses a relative one, and a
 * service has no shell to resolve `teachermovies-bridge` against, so what lands in the unit is one
 * absolute path: `--exec` when the human gave it, otherwise the script of the distribution this very
 * process was started from.
 *
 * Deriving it works for an installed distribution -- `installDist` puts the jars in `<root>/lib` and
 * the start script in `<root>/bin`, so the code source's sibling `bin` directory holds it -- and
 * deliberately fails for `./gradlew :laptop-bridge:run`, whose code source is a directory of classes
 * with no distribution next to it: a unit pointing into `build/classes` would break on the next
 * build, so [LauncherLocation.Underived] asks for `--exec` instead.
 */
internal object BridgeLauncher {
    /** The start script's name, which `applicationName` in the build script fixes. */
    const val SCRIPT_NAME = "teachermovies-bridge"

    private const val BIN_DIR = "bin"

    /** The script to launch, or why there is none to launch. */
    fun locate(
        explicitSpec: String?,
        home: Path,
    ): LauncherLocation {
        val spec = explicitSpec?.takeIf { it.isNotBlank() }
        if (spec != null) return verdict(ConfigLocation.expand(spec, home))
        val derived = derivedScript()
        if (derived != null && Files.isExecutable(derived)) return LauncherLocation.Found(derived)
        return LauncherLocation.Underived(derived)
    }

    /**
     * `<code source root>/../bin/` + [SCRIPT_NAME], or null when this process has no such sibling. A
     * code source this JVM cannot turn into a file path is no derivation either: [locate] then
     * reports [LauncherLocation.Underived] and the human names the script with `--exec`.
     */
    private fun derivedScript(): Path? =
        try {
            val location = codeSourceLocation()
            if (location == null) null else scriptInSiblingBin(Paths.get(location))
        } catch (e: URISyntaxException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: SecurityException) {
            null
        }

    /** This process's own code source as a URI, or null when the JVM will not say. */
    private fun codeSourceLocation(): URI? =
        BridgeLauncher::class.java.protectionDomain
            ?.codeSource
            ?.location
            ?.toURI()

    /** [codeSource]'s sibling `bin/` + [SCRIPT_NAME], or null when it has no directory to be a sibling of. */
    private fun scriptInSiblingBin(codeSource: Path): Path? =
        codeSource.parent
            ?.resolveSibling(BIN_DIR)
            ?.resolve(SCRIPT_NAME)

    private fun verdict(path: Path): LauncherLocation =
        when {
            !Files.exists(path) -> LauncherLocation.Unusable(path, exists = false)
            !Files.isExecutable(path) -> LauncherLocation.Unusable(path, exists = true)
            else -> LauncherLocation.Found(path)
        }
}

/** What [BridgeLauncher.locate] settled on. */
internal sealed interface LauncherLocation {
    /** The absolute, runnable start script the unit will launch. */
    data class Found(
        val path: Path,
    ) : LauncherLocation

    /**
     * `--exec` named a file that cannot be launched: [exists] is false when there is nothing at
     * [path] at all.
     */
    data class Unusable(
        val path: Path,
        val exists: Boolean,
    ) : LauncherLocation

    /**
     * No `--exec`, and nothing derived from the running program. [tried] is the path this process
     * guessed at, when it got as far as guessing one.
     */
    data class Underived(
        val tried: Path?,
    ) : LauncherLocation
}
