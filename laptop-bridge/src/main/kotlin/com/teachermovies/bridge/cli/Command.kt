package com.teachermovies.bridge.cli

/**
 * The subcommand [ArgsParser] read off the command line (#271), already validated: a [Pair] holds a
 * URL that parses and a non-blank PIN, a [Logs] holds only filters the TV accepts.
 */
internal sealed interface Command {
    /** `pair --url <url> --pin <pin> [--name <name>]`; [deviceName] null means "this host's name". */
    data class Pair(
        val url: String,
        val pin: String,
        val deviceName: String?,
    ) : Command {
        /** The PIN is a secret on the way to the TV: no parse error or log line may carry it. */
        override fun toString(): String = "Pair(url=$url, pin=<redacted>, deviceName=$deviceName)"
    }

    /** `unpair`: forget the stored pairing. */
    data object Unpair : Command

    /** `doctor`: report this bridge's own health. */
    data object Doctor : Command

    /** `run`: hold the TV's job stream open and answer its jobs until the TV refuses the token (#277). */
    data object Run : Command

    /** `logs [--since <seq>] [--level <nivel>] [--limit <n>]`; a null filter is the TV's default. */
    data class Logs(
        val since: Long?,
        val level: String?,
        val limit: Int?,
    ) : Command
}
