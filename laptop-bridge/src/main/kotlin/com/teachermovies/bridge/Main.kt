package com.teachermovies.bridge

import com.teachermovies.bridge.cli.BridgeCli
import kotlin.system.exitProcess

/**
 * Entry point of `teachermovies-bridge` (#271, ADR-0005 §1): the start script the Gradle
 * `application` plugin installs (see `laptop-bridge/build.gradle.kts`, and ADR-0004's row for that
 * plugin) runs this with the raw command line, and the process exits with [BridgeCli]'s code --
 * 0 correct, 1 the operation failed, 2 the command line did not mean anything.
 *
 * [BridgeCli] writes to [Appendable]s rather than to the console directly, so the streams are
 * flushed here before the process leaves; `exitProcess` is only needed for a failure, since a
 * successful run has already closed its Ktor client and has nothing left to run.
 */
fun main(args: Array<String>) {
    val exitCode = BridgeCli(System.out, System.err).run(args)
    System.out.flush()
    System.err.flush()
    if (exitCode != 0) exitProcess(exitCode)
}
