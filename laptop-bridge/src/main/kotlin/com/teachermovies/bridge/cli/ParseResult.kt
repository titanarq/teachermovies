package com.teachermovies.bridge.cli

/** What [ArgsParser] made of the command line (#271). */
internal sealed interface ParseResult {
    /** `-h`/`--help`/`help`: the usage text goes to stdout and the exit code is [ExitCode.OK]. */
    data object Help : ParseResult

    /** A subcommand [BridgeCli] can run, with the `--config` file it must use (null = the default). */
    data class Parsed(
        val configSpec: String?,
        val command: Command,
    ) : ParseResult

    /**
     * A command line that cannot mean anything. [message] says what is wrong in Spanish, or is null
     * when the mistake is that nothing was given at all; either way the usage text follows and the
     * exit code is [ExitCode.USAGE]. A message never quotes a value that could be a PIN, only the
     * option's own name.
     */
    data class UsageError(
        val message: String?,
    ) : ParseResult
}
