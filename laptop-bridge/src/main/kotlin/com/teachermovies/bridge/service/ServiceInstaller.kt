package com.teachermovies.bridge.service

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes the rendered unit where systemd's user manager reads it (#278), replacing a unit an earlier
 * `install-service` left there: an install is idempotent, so upgrading the bridge is `installDist`,
 * `install-service`, `systemctl --user daemon-reload`, `restart`.
 *
 * The unit file is `0644` and its directory `0700` when this creates it. World-readable is right
 * here and would not be for the config file: a unit holds no secret, [ServiceUnit] refuses to render
 * one into it, and systemd itself reads units as the user rather than as root. Only an [IOException]
 * is a failure this reports; a filesystem without POSIX permissions raises
 * `UnsupportedOperationException` and reaches the JVM, as everywhere else in this module -- the
 * bridge runs on Linux (ADR-0005 §1).
 */
internal class ServiceInstaller(
    private val unitFile: Path,
) {
    fun install(text: String): ServiceInstall {
        val replaced = Files.exists(unitFile)
        val directory = checkNotNull(unitFile.parent) { "an absolute unit path always has a directory" }
        return try {
            Files.createDirectories(directory, DIRECTORY_PERMISSIONS)
            Files.write(unitFile, text.toByteArray(StandardCharsets.UTF_8), CREATE, TRUNCATE_EXISTING, WRITE)
            // Written rather than created with the mode: an install over an earlier unit keeps that
            // file's inode, and with it whatever permissions it had been given.
            Files.setPosixFilePermissions(unitFile, UNIT_FILE_PERMISSIONS)
            ServiceInstall.Installed(unitFile, replaced)
        } catch (e: IOException) {
            ServiceInstall.Failed(reason(e))
        }
    }

    private companion object {
        val CREATE = StandardOpenOption.CREATE
        val TRUNCATE_EXISTING = StandardOpenOption.TRUNCATE_EXISTING
        val WRITE = StandardOpenOption.WRITE

        /** Owner read/write, everybody read: the mode a unit file is meant to have. */
        val UNIT_FILE_PERMISSIONS: Set<PosixFilePermission> =
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            )

        /** The unit directory when this creates it, `0700` like the bridge's own config directory. */
        val DIRECTORY_PERMISSIONS =
            PosixFilePermissions.asFileAttribute(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )

        /** The class name of [e]: never its message, which could quote a path. */
        fun reason(e: Exception): String = e::class.simpleName ?: "error"
    }
}

/** What [ServiceInstaller.install] did. */
internal sealed interface ServiceInstall {
    /** The unit is on disk at [path]; [replaced] says whether it overwrote an earlier one. */
    data class Installed(
        val path: Path,
        val replaced: Boolean,
    ) : ServiceInstall

    /** Nothing was written. [reason] is an exception class name, as in `ConfigLoad.Corrupt`. */
    data class Failed(
        val reason: String,
    ) : ServiceInstall
}
