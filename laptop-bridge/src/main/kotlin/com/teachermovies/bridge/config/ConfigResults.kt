package com.teachermovies.bridge.config

import java.nio.file.Path

/**
 * What [BridgeConfigStore.load] found (#271). No store method throws for an expected condition; the
 * subcommands turn a case into their own exit code and message.
 */
sealed interface ConfigLoad {
    /** There is no config file: a fresh install, or one `unpair` already cleared. */
    data object Missing : ConfigLoad

    /** The file exists and holds a readable [BridgeConfig]. */
    data class Loaded(
        val config: BridgeConfig,
    ) : ConfigLoad

    /**
     * The file exists but is not config JSON this bridge can read. [reason] is an exception class
     * name -- never a parser message and never file content, because a config file holds the token.
     */
    data class Corrupt(
        val reason: String,
    ) : ConfigLoad

    /** The file exists but could not be read at all (permissions, I/O). [reason] as in [Corrupt]. */
    data class Unreadable(
        val reason: String,
    ) : ConfigLoad
}

/** What [BridgeConfigStore.save] did. */
sealed interface ConfigSave {
    /** The configuration is on disk at [path], with mode 0600. */
    data class Saved(
        val path: Path,
    ) : ConfigSave

    /** Nothing was written. [reason] is an exception class name, as in [ConfigLoad.Corrupt]. */
    data class Failed(
        val reason: String,
    ) : ConfigSave
}

/** What [BridgeConfigStore.delete] did (`unpair`). */
sealed interface ConfigDelete {
    /** The file existed and is gone: [path] is where it was. */
    data class Deleted(
        val path: Path,
    ) : ConfigDelete

    /** There was nothing to delete. */
    data class Absent(
        val path: Path,
    ) : ConfigDelete

    /** The file is still there. [reason] is an exception class name, as in [ConfigLoad.Corrupt]. */
    data class Failed(
        val reason: String,
    ) : ConfigDelete
}
