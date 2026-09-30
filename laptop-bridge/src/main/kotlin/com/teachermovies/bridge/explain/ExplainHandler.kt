package com.teachermovies.bridge.explain

import com.teachermovies.bridge.claude.ClaudeCli
import com.teachermovies.bridge.claude.ClaudeOutcome
import com.teachermovies.bridge.protocol.BridgeJobDto
import com.teachermovies.bridge.protocol.BridgeJobProtocol
import com.teachermovies.bridge.protocol.BridgeJobResultDto
import com.teachermovies.bridge.protocol.ExplainJobDto
import com.teachermovies.bridge.protocol.ExplanationDto
import com.teachermovies.bridge.run.JobHandler
import com.teachermovies.bridge.run.JobHandlerRegistry
import kotlinx.serialization.json.Json

/**
 * The `explain` job handler (#291, ADR-0005 §3 and §7): asks [cli] -- the explain conversation, one
 * long-lived Claude process of its own, apart from the translate one (#286) -- to explain the
 * captured line, validates the reply with [ExplainReply], re-asks once when it breaks the schema,
 * and answers the TV with the [ExplanationDto] JSON (tagged with [ExplainPrompt.VERSION]) as the
 * `text` of a [BridgeJobResultDto.Done].
 *
 * A non-answer (429, timeout, daily cap, API-key billing, a dead CLI) is posted as the transport's
 * own [ClaudeOutcome.failureResult], never re-asked; a second bad reply is [INVALID_REPLY].
 */
class ExplainHandler(
    private val cli: ClaudeCli,
) : JobHandler {
    override val kind: String = KIND

    init {
        require(cli.kind == KIND) { "the explain handler needs the explain conversation, not '${cli.kind}'" }
    }

    override suspend fun handle(job: BridgeJobDto): BridgeJobResultDto {
        val explain =
            job as? ExplainJobDto
                ?: return BridgeJobResultDto.Failed(JobHandlerRegistry.BAD_JOB, "no es un trabajo '$KIND'")
        val data = ExplainPrompt.userTurn(explain)
        val problem =
            when (val first = attempt(data)) {
                is Attempt.Done -> return first.result
                is Attempt.Invalid -> first.problem
            }
        return when (val second = attempt(ExplainPrompt.reAsk(problem, data))) {
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
        return when (val reply = ExplainReply.parse(text)) {
            is ExplainReply.Result.Invalid -> {
                Attempt.Invalid(reply.problem)
            }

            is ExplainReply.Result.Valid -> {
                val encoded = JSON.encodeToString(ExplanationDto.serializer(), reply.explanation)
                if (encoded.toByteArray(Charsets.UTF_8).size > BridgeJobProtocol.MAX_PAYLOAD_BYTES) {
                    Attempt.Invalid("la explicación no cabe en ${BridgeJobProtocol.MAX_PAYLOAD_BYTES} bytes")
                } else {
                    Attempt.Done(BridgeJobResultDto.Done(encoded))
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
        const val KIND = "explain"

        /** Result code when Claude's reply broke the schema twice. */
        const val INVALID_REPLY = "invalid_reply"

        private val JSON = Json
    }
}
