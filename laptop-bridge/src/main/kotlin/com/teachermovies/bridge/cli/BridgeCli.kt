package com.teachermovies.bridge.cli

import com.teachermovies.bridge.claude.AuthStatus
import com.teachermovies.bridge.claude.ClaudeAuth
import com.teachermovies.bridge.claude.ClaudeDirs
import com.teachermovies.bridge.claude.ClaudeSettingsFile
import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.config.ConfigLocation
import com.teachermovies.bridge.logs.TvLogFiles
import com.teachermovies.bridge.opensubtitles.CredentialsFile
import com.teachermovies.bridge.run.JmDnsDiscovery
import com.teachermovies.bridge.run.JobHandlerRegistry
import com.teachermovies.bridge.run.TvDiscovery
import com.teachermovies.bridge.service.ClaudeBinary
import com.teachermovies.bridge.tv.TvApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * The `teachermovies-bridge` command line (#271, ADR-0005 §1): parses [run]'s arguments, resolves
 * which config file this run uses and runs one subcommand against the TV.
 *
 * Output goes to [out] and problems to [err]; [home] and [env] locate the config file. All four are
 * injected so a test can capture what the human would read and point the bridge at a temporary home
 * instead of the real `~/.config`. The result is a process exit code: [ExitCode.OK],
 * [ExitCode.FAILED] or [ExitCode.USAGE].
 *
 * Neither the token nor the PIN ever reaches [out] or [err]: the subcommands report that a token is
 * stored and valid, never what it is, and a usage error names the offending option rather than the
 * value somebody typed after it.
 */
class BridgeCli(
    private val out: Appendable,
    private val err: Appendable,
    private val home: Path = ConfigLocation.systemHome(),
    private val env: (String) -> String? = { System.getenv(it) },
    private val registry: JobHandlerRegistry = JobHandlerRegistry.default(),
    private val discovery: TvDiscovery = JmDnsDiscovery(),
    private val claudeAuth: (Path) -> AuthStatus = { ClaudeAuth.status(it) },
) {
    /** Runs one invocation and returns the exit code; it never throws for a foreseeable mistake. */
    fun run(args: Array<String>): Int {
        val invocation =
            when (val parsed = ArgsParser.parse(args.toList())) {
                ParseResult.Help -> {
                    out.appendLine(ArgsParser.USAGE)
                    return ExitCode.OK
                }

                is ParseResult.UsageError -> {
                    usageError(parsed.message)
                    return ExitCode.USAGE
                }

                is ParseResult.Parsed -> {
                    parsed
                }
            }
        val store = BridgeConfigStore(ConfigLocation.configFile(invocation.configSpec, home, env))
        return runBlocking { dispatch(invocation.command, store) }
    }

    private fun usageError(message: String?) {
        if (message != null) err.appendLine("teachermovies-bridge: $message")
        err.appendLine()
        err.appendLine(ArgsParser.USAGE)
    }

    private suspend fun dispatch(
        command: Command,
        store: BridgeConfigStore,
    ): Int =
        when (command) {
            is Command.Pair -> {
                withTv { PairCommand(out, err, store, it).run(command) }
            }

            Command.Unpair -> {
                UnpairCommand(out, err, store).run()
            }

            Command.Doctor -> {
                val credentials = CredentialsFile(CredentialsFile.defaultPath(home, env))
                val claude =
                    ClaudeChecks(
                        location = ClaudeBinary.locate(home, env),
                        settings = ClaudeSettingsFile.besideConfig(store.path),
                        dirs = ClaudeDirs.default(home, env),
                        auth = claudeAuth,
                    )
                withTv { DoctorCommand(out, store, it, credentials, claude).run() }
            }

            is Command.Logs -> {
                withTv { LogsCommand(out, err, store, it).run(command) }
            }

            Command.Run -> {
                val logsDir = TvLogFiles.defaultDir(home, env)
                val credentials = CredentialsFile(CredentialsFile.defaultPath(home, env))
                withHttp { client ->
                    RunCommand(out, err, store, TvApi(client), registry, discovery, logsDir, credentials, client).run()
                }
            }

            // The one subcommand that needs no TV and no HTTP client: it writes a unit file (#278).
            is Command.InstallService -> {
                InstallServiceCommand(out, err, home, env, store).run(command)
            }
        }
}

/**
 * Runs [block] with a [TvApi] over a fresh CIO client and closes that client whatever happens: a
 * CLI process holds one client for one subcommand, and `unpair` never opens one at all.
 */
private suspend fun <T> withTv(block: suspend (TvApi) -> T): T = withHttp { block(TvApi(it)) }

/** The CIO client behind [withTv]; `run` also hands it to the OpenSubtitles client (#282). */
private suspend fun <T> withHttp(block: suspend (HttpClient) -> T): T {
    val httpClient = HttpClient(CIO)
    try {
        return block(httpClient)
    } finally {
        httpClient.close()
    }
}
