package com.teachermovies.bridge.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Reads and writes the [BridgeConfig] JSON at [path] (#271).
 *
 * That file holds a bearer token, so this class is the module's security boundary: the directory is
 * created `0700`, the file is *created* `0600` rather than chmod-ed after the fact, and a save
 * writes a temporary file in the same directory and moves it over the target atomically -- an
 * interrupted save can leave the previous config or no config, never a half-written one, and a save
 * also replaces a target whose permissions somebody had loosened. POSIX permissions only: the
 * bridge runs as a systemd user service on Linux (ADR-0005 §1).
 *
 * [path] is absolute and normalized whatever it was built from, and no failure reason this class
 * reports contains a message from the filesystem or the parser: a config path is user data and a
 * config file is a secret.
 */
class BridgeConfigStore(
    given: Path,
) {
    /** Where the config lives (and what `doctor` prints): absolute, normalized, `~` already expanded. */
    val path: Path = given.toAbsolutePath().normalize()

    fun load(): ConfigLoad {
        if (!Files.exists(path)) return ConfigLoad.Missing
        val text =
            try {
                Files.readString(path)
            } catch (e: IOException) {
                return ConfigLoad.Unreadable(reason(e))
            }
        return try {
            ConfigLoad.Loaded(JSON.decodeFromString(BridgeConfig.serializer(), text))
        } catch (e: SerializationException) {
            ConfigLoad.Corrupt(reason(e))
        }
    }

    fun save(config: BridgeConfig): ConfigSave {
        val directory = checkNotNull(path.parent) { "an absolute path always has a parent directory" }
        val temporary = directory.resolve("${path.fileName}$TEMPORARY_SUFFIX")
        return try {
            Files.createDirectories(directory, DIRECTORY_PERMISSIONS)
            Files.deleteIfExists(temporary)
            Files.createFile(temporary, FILE_PERMISSIONS)
            Files.writeString(temporary, JSON.encodeToString(BridgeConfig.serializer(), config))
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            ConfigSave.Saved(path)
        } catch (e: IOException) {
            ConfigSave.Failed(reason(e))
        } finally {
            // A failed save must not leave a second copy of the token behind.
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    fun delete(): ConfigDelete =
        try {
            if (Files.deleteIfExists(path)) ConfigDelete.Deleted(path) else ConfigDelete.Absent(path)
        } catch (e: IOException) {
            ConfigDelete.Failed(reason(e))
        }

    /**
     * The config file's permissions as three octal digits (`"0600"`), or null when there is no file
     * or its permissions cannot be read -- `doctor` prints the difference between the two.
     */
    fun permissions(): String? {
        if (!Files.exists(path)) return null
        return try {
            Files.getPosixFilePermissions(path).toOctal()
        } catch (e: IOException) {
            null
        }
    }

    private companion object {
        const val TEMPORARY_SUFFIX = ".tmp"

        /** Owner read/write only, from the moment the file exists; applied at creation, not after. */
        val OWNER_ONLY: Set<PosixFilePermission> =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            )

        val FILE_PERMISSIONS = PosixFilePermissions.asFileAttribute(OWNER_ONLY)

        val DIRECTORY_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(
                OWNER_ONLY + PosixFilePermission.OWNER_EXECUTE,
            )

        /**
         * `ignoreUnknownKeys` so a config written by a newer bridge stays readable; `explicitNulls =
         * false` and `prettyPrint` so the file a human may open holds only the keys that mean
         * something.
         */
        val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
                prettyPrint = true
            }

        /** The class name of [e]: never its message, which could quote the file or a path. */
        fun reason(e: Exception): String = e::class.simpleName ?: "error"
    }
}

/** The three read/write/execute groups, most significant first, as [PosixFilePermission]s. */
private val PERMISSION_TRIADS =
    listOf(
        listOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ),
        listOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
        ),
        listOf(
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        ),
    )

/**
 * The permissions as the four octal digits a `chmod` takes, e.g. `rw-------` -> `"0600"`. The
 * leading digit stands for the setuid, setgid and sticky bits, which [PosixFilePermission] cannot
 * express and a config file must never have, so it is always zero here.
 */
internal fun Set<PosixFilePermission>.toOctal(): String {
    val ownerGroupOthers = PERMISSION_TRIADS.joinToString("") { triad -> triadDigit(this, triad).toString() }
    return NO_SPECIAL_BITS + ownerGroupOthers
}

private const val NO_SPECIAL_BITS = "0"

/** The permissions of one triad as a single octal digit, e.g. `rw-` -> 6. */
private fun triadDigit(
    permissions: Set<PosixFilePermission>,
    triad: List<PosixFilePermission>,
): Int {
    var digit = 0
    if (permissions.contains(triad[0])) digit += READ_BIT
    if (permissions.contains(triad[1])) digit += WRITE_BIT
    if (permissions.contains(triad[2])) digit += EXECUTE_BIT
    return digit
}

private const val READ_BIT = 4
private const val WRITE_BIT = 2
private const val EXECUTE_BIT = 1
