package com.teachermovies.bridge.cli

import com.teachermovies.bridge.claude.ClaudeCli
import com.teachermovies.bridge.claude.ClaudeCliTransport
import com.teachermovies.bridge.claude.ClaudeDirs
import com.teachermovies.bridge.claude.ClaudeSettings
import com.teachermovies.bridge.claude.ClaudeSettingsFile
import com.teachermovies.bridge.claude.ClaudeSettingsLoad
import com.teachermovies.bridge.claude.DailyCap
import com.teachermovies.bridge.config.BridgeConfigStore
import com.teachermovies.bridge.explain.ExplainHandler
import com.teachermovies.bridge.explain.ExplainPrompt
import com.teachermovies.bridge.run.JobHandler
import com.teachermovies.bridge.run.RunLog
import com.teachermovies.bridge.service.ClaudeBinary
import com.teachermovies.bridge.service.ClaudeLocation
import com.teachermovies.bridge.translate.TranslateHandler
import com.teachermovies.bridge.translate.TranslatePrompt
import java.nio.file.Path

/**
 * The job handlers `run` answers with, and the Claude conversations behind them, which `run` warms
 * up as it starts and closes as it ends: each kind has its own long-lived process (ADR-0005 §3).
 */
class JobHandlers(
    val handlers: List<JobHandler>,
    val conversations: List<ClaudeCli>,
) {
    companion object {
        val NONE = JobHandlers(emptyList(), emptyList())

        /**
         * The production set: the translate (#286) and explain (#291) handlers, each over a
         * [ClaudeCliTransport] of its own -- one process and one system prompt per kind -- from the
         * CLI [ClaudeBinary.locate] finds and the settings in `claude.json` beside the config, with
         * one [DailyCap] counted across every conversation. No CLI or unusable settings: [NONE],
         * said once in [log], and `run` goes on answering every job `unsupported_kind`.
         */
        internal fun claude(
            home: Path,
            env: (String) -> String?,
            store: BridgeConfigStore,
            log: RunLog,
        ): JobHandlers {
            val executable =
                when (val location = ClaudeBinary.locate(home, env)) {
                    is ClaudeLocation.Found -> {
                        location.path.toAbsolutePath()
                    }

                    is ClaudeLocation.Unusable -> {
                        log.line(
                            "${ClaudeBinary.VARIABLE}=${location.path} no se puede ejecutar: sin trabajos de Claude.",
                        )
                        return NONE
                    }

                    ClaudeLocation.Absent -> {
                        log.line("No se encuentra '${ClaudeBinary.EXECUTABLE_NAME}': sin trabajos de Claude.")
                        return NONE
                    }
                }
            val file = ClaudeSettingsFile.besideConfig(store.path)
            val settings =
                when (val load = file.load()) {
                    ClaudeSettingsLoad.Defaults -> {
                        ClaudeSettings()
                    }

                    is ClaudeSettingsLoad.Loaded -> {
                        load.settings
                    }

                    is ClaudeSettingsLoad.Corrupt -> {
                        log.line("Ajustes de Claude ${file.path} no válidos (${load.reason}): sin trabajos de Claude.")
                        return NONE
                    }

                    is ClaudeSettingsLoad.Unreadable -> {
                        log.line("No se puede leer ${file.path} (${load.reason}): sin trabajos de Claude.")
                        return NONE
                    }
                }
            val cap = DailyCap(settings.dailyJobCap)
            val dirs = ClaudeDirs.default(home, env)
            val translate =
                ClaudeCliTransport(
                    TranslateHandler.KIND,
                    TranslatePrompt.SYSTEM_PROMPT,
                    executable,
                    settings,
                    dirs,
                    cap,
                    log,
                )
            val explain =
                ClaudeCliTransport(
                    ExplainHandler.KIND,
                    ExplainPrompt.SYSTEM_PROMPT,
                    executable,
                    settings,
                    dirs,
                    cap,
                    log,
                )
            return JobHandlers(
                listOf(TranslateHandler(translate), ExplainHandler(explain)),
                listOf(translate, explain),
            )
        }
    }
}
