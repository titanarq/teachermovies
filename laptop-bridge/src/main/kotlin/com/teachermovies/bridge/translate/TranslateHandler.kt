package com.teachermovies.bridge.translate

import com.teachermovies.bridge.claude.ClaudeCli
import com.teachermovies.bridge.claude.ClaudeOutcome
import com.teachermovies.bridge.protocol.BridgeJobDto
import com.teachermovies.bridge.protocol.BridgeJobProtocol
import com.teachermovies.bridge.protocol.BridgeJobResultDto
import com.teachermovies.bridge.protocol.TranslateJobDto
import com.teachermovies.bridge.run.JobHandler
import com.teachermovies.bridge.run.JobHandlerRegistry
import kotlinx.serialization.json.Json

/**
 * The `translate` job handler (#286, ADR-0005 §6): asks [cli] -- the translate conversation, one
 * long-lived Claude process of its own, apart from the explain one (#291) -- for the Castilian
 * Spanish of the captured line, validates the reply with [TranslateReply], re-asks once when it
 * breaks the schema, and answers the TV with that Spanish line, bare, as the `text` of a
 * [BridgeJobResultDto.Done]: the TV shows it verbatim as LEFT's "IA" line and caches it by the
 * English line (#287), so it carries no document of its own the way an explanation does.
 *
 * A non-answer (429, timeout, daily cap, API-key billing, a dead CLI) is posted as the transport's
 * own [ClaudeOutcome.failureResult], never re-asked; a second bad reply is [INVALID_REPLY].
 */
class TranslateHandler(
    private val cli: ClaudeCli,
) : JobHandler {
    override val kind: String = KIND

    init {
        require(cli.kind == KIND) { "the translate handler needs the translate conversation, not '${cli.kind}'" }
    }

    override suspend fun handle(job: BridgeJobDto): BridgeJobResultDto {
        val translate =
            job as? TranslateJobDto
                ?: return BridgeJobResultDto.Failed(JobHandlerRegistry.BAD_JOB, "no es un trabajo '$KIND'")
        val data = TranslatePrompt.userTurn(translate)
        val problem =
            when (val first = attempt(data)) {
                is Attempt.Done -> return first.result
                is Attempt.Invalid -> first.problem
            }
        return when (val second = attempt(TranslatePrompt.reAsk(problem, data))) {
            is Attempt.Done -> {
                second.result
            }

            is Attempt.Invalid -> {
                BridgeJobResultDto.Failed(
                    INVALID_REPLY,
                    "respuesta de Claude no válida dos veces: ${second.problem}",
                )
            }
        }
    }

    private suspend fun attempt(prompt: String): Attempt {
        val outcome = cli.ask(prompt)
        outcome.failureResult()?.let { return Attempt.Done(it) }
        val text = (outcome as ClaudeOutcome.Answered).text
        return when (val reply = TranslateReply.parse(text)) {
            is TranslateReply.Result.Invalid -> {
                Attempt.Invalid(reply.problem)
            }

            is TranslateReply.Result.Valid -> {
                // The whole result body is what the TV's POST route measures, envelope and escapes
                // included, and it refuses one over the limit with 413 rather than storing nothing.
                val done = BridgeJobResultDto.Done(reply.translation)
                val encoded = JSON.encodeToString(BridgeJobResultDto.serializer(), done)
                if (encoded.toByteArray(Charsets.UTF_8).size > BridgeJobProtocol.MAX_PAYLOAD_BYTES) {
                    Attempt.Invalid("la traducción no cabe en ${BridgeJobProtocol.MAX_PAYLOAD_BYTES} bytes")
                } else {
                    Attempt.Done(done)
                }
            }
        }
    }

    private sealed interface Attempt {
        data class Done(
            val result: BridgeJobResultDto,
        ) : Attempt

        data class Invalid(
            val problem: String,
        ) : Attempt
    }

    companion object {
        /** The job kind, and the kind of the Claude conversation it needs. */
        const val KIND = "translate"

        /** Result code when Claude's reply broke the schema twice; the explain handler (#291) uses the same one. */
        const val INVALID_REPLY = "invalid_reply"

        private val JSON = Json
    }
}
