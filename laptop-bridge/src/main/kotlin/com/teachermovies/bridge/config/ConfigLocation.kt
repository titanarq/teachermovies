package com.teachermovies.bridge.config

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where the bridge keeps its configuration (#271): `~/.config/teachermovies-bridge/config.json`, or
 * `$XDG_CONFIG_HOME/teachermovies-bridge/config.json` when that variable is set, or the file
 * `--config` names.
 *
 * Every path returned here is `~`-expanded, absolute and normalized, so nothing downstream (the
 * store, what `doctor` prints, the systemd unit of #278) has to guess which directory a relative
 * path was meant against, and no path the bridge keeps depends on the working directory the CLI
 * happened to be invoked from.
 */
object ConfigLocation {
    /** The bridge's own directory inside the config home. */
    const val DIR_NAME = "teachermovies-bridge"

    /** The configuration file's name inside [DIR_NAME]. */
    const val FILE_NAME = "config.json"

    /** The variable that replaces `~/.config` as the config home (XDG Base Directory spec). */
    const val CONFIG_HOME_VARIABLE = "XDG_CONFIG_HOME"

    /** The prefix [expand] substitutes with the user's home directory. */
    private const val TILDE = "~"

    /** The running user's home directory: what `~` stands for. */
    fun systemHome(): Path = Paths.get(System.getProperty("user.home"))

    /**
     * The configuration file for one run: [explicitSpec] when `--config` gave one, otherwise the
     * default location under [env]'s `XDG_CONFIG_HOME` or [home].
     */
    fun configFile(
        explicitSpec: String?,
        home: Path,
        env: (String) -> String?,
    ): Path {
        if (explicitSpec != null) return expand(explicitSpec, home)
        return configDir(home, env).resolve(FILE_NAME)
    }

    /** The directory holding [FILE_NAME]: this bridge's own [DIR_NAME] inside [configHome]. */
    fun configDir(
        home: Path,
        env: (String) -> String?,
    ): Path = configHome(home, env).resolve(DIR_NAME)

    /**
     * The config home every application directory hangs off: `$XDG_CONFIG_HOME` when set and
     * non-blank, else `~/.config`. The systemd user unit directory (#278) is another directory under
     * it, which is why this is not a detail of [configDir].
     */
    fun configHome(
        home: Path,
        env: (String) -> String?,
    ): Path {
        val xdgHome = env(CONFIG_HOME_VARIABLE)?.takeIf { it.isNotBlank() }
        val configHome = if (xdgHome == null) home.resolve(".config") else expand(xdgHome, home)
        return configHome.toAbsolutePath().normalize()
    }

    /**
     * [spec] as an absolute, normalized path, with a leading `~` or `~/` replaced by [home]. A spec
     * that is already absolute is only normalized; a relative one is resolved against the process's
     * working directory, which is what a user typing a relative `--config` means.
     */
    fun expand(
        spec: String,
        home: Path,
    ): Path {
        val withoutTilde =
            when {
                spec == TILDE -> {
                    home.toString()
                }

                spec.startsWith("$TILDE/") -> {
                    home.resolve(spec.removePrefix("$TILDE/")).toString()
                }

                else -> {
                    spec
                }
            }
        return Paths.get(withoutTilde).toAbsolutePath().normalize()
    }
}
