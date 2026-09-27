package com.teachermovies.bridge.cli

/**
 * The process exit codes of `teachermovies-bridge` (#271), the ones `--help` documents and the ones
 * the systemd unit of #278 will be judged by: [OK] when the subcommand did its job, [FAILED] when
 * the TV, the configuration or the filesystem refused it, [USAGE] when the command line itself was
 * wrong.
 */
internal object ExitCode {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2
}
